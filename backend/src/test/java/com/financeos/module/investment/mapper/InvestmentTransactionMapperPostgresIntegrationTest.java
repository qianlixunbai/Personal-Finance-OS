package com.financeos.module.investment.mapper;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.investment.entity.InvestmentTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.NestedExceptionUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentTransactionMapperPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private InvestmentTransactionMapper investmentTransactionMapper;

    @Test
    void insertsAndReadsPostedTransactionsInReplayOrderWithExactPrecision() {
        Long userId = insertUser("investor");
        Long accountId = insertAccount(userId, "Brokerage");
        Long assetId = insertAsset(userId, accountId, "Fund");

        InvestmentTransaction laterBuy = transaction(userId, assetId, accountId, "BUY", "2026-02-01T10:00:00Z", "later-buy");
        InvestmentTransaction earlierOpening = transaction(userId, assetId, accountId, "OPENING_POSITION", "2026-01-01T10:00:00Z", "opening");
        InvestmentTransaction sell = transaction(userId, assetId, accountId, "SELL", "2026-03-01T10:00:00Z", "sell");
        sell.setGrossAmount(decimal("30.00"));
        sell.setNetAmount(decimal("29.00"));
        sell.setFeeAmount(decimal("1.00"));
        sell.setReleasedCostAmount(decimal("20.00"));
        sell.setRealizedProfitLoss(decimal("9.00"));
        earlierOpening.setNetAmount(decimal("0.00"));
        investmentTransactionMapper.insert(laterBuy);
        investmentTransactionMapper.insert(earlierOpening);
        investmentTransactionMapper.insert(sell);

        InvestmentTransaction stored = investmentTransactionMapper.findByUserIdAndId(userId, laterBuy.getId());
        List<InvestmentTransaction> replayEntries = investmentTransactionMapper.selectPostedByUserIdAndAssetId(userId, assetId);

        assertThat(laterBuy.getId()).isNotNull();
        assertThat(stored.getQuantity()).isEqualByComparingTo("2.00000000");
        assertThat(stored.getUnitPrice()).isEqualByComparingTo("10.00000000");
        assertThat(stored.getGrossAmount()).isEqualByComparingTo("20.00");
        assertThat(stored.getTradeTime()).isEqualTo(Instant.parse("2026-02-01T10:00:00Z"));
        assertThat(replayEntries).extracting(InvestmentTransaction::getId)
                .containsExactly(earlierOpening.getId(), laterBuy.getId(), sell.getId());
        assertThat(replayEntries).extracting(InvestmentTransaction::getTransactionType)
                .containsExactly("OPENING_POSITION", "BUY", "SELL");
        assertThat(investmentTransactionMapper.findByUserIdAndIdempotencyKey(userId, "later-buy").getId())
                .isEqualTo(laterBuy.getId());
    }

    @Test
    void rejectsTheRetiredMutableReversedStatusModel() {
        Long userId = insertUser("income-investor");
        Long accountId = insertAccount(userId, "Brokerage");
        Long assetId = insertAsset(userId, accountId, "Fund");

        InvestmentTransaction dividend = transaction(userId, assetId, accountId, "DIVIDEND", "2026-01-01T10:00:00Z", "dividend");
        dividend.setQuantity(null);
        dividend.setUnitPrice(null);
        dividend.setGrossAmount(decimal("10.00"));
        dividend.setFeeAmount(decimal("0.00"));
        dividend.setTaxAmount(decimal("1.00"));
        dividend.setNetAmount(decimal("9.00"));
        InvestmentTransaction reversedBuy = transaction(userId, assetId, accountId, "BUY", "2026-01-02T10:00:00Z", "reversed");
        reversedBuy.setStatus("REVERSED");
        reversedBuy.setReversedAt(Instant.parse("2026-01-03T10:00:00Z"));
        reversedBuy.setReversalReason("Corrected by future replacement workflow");
        investmentTransactionMapper.insert(dividend);

        assertThatThrownBy(() -> investmentTransactionMapper.insert(reversedBuy))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(investmentTransactionMapper.selectPostedByUserIdAndAssetId(userId, assetId))
                .extracting(InvestmentTransaction::getTransactionType)
                .containsExactly("DIVIDEND");
    }

    @Test
    void enforcesUserIsolationIdempotencyAndDatabaseConstraints() {
        Long firstUserId = insertUser("first-investor");
        Long secondUserId = insertUser("second-investor");
        Long firstAccountId = insertAccount(firstUserId, "First brokerage");
        Long secondAccountId = insertAccount(secondUserId, "Second brokerage");
        Long firstAssetId = insertAsset(firstUserId, firstAccountId, "First fund");
        InvestmentTransaction first = transaction(firstUserId, firstAssetId, firstAccountId, "BUY", "2026-01-01T10:00:00Z", "duplicate-key");
        investmentTransactionMapper.insert(first);

        assertThat(investmentTransactionMapper.findByUserIdAndId(secondUserId, first.getId())).isNull();
        assertThatThrownBy(() -> investmentTransactionMapper.insert(
                transaction(firstUserId, firstAssetId, firstAccountId, "SELL", "2026-01-02T10:00:00Z", "duplicate-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> investmentTransactionMapper.insert(
                transaction(firstUserId, firstAssetId, secondAccountId, "BUY", "2026-01-03T10:00:00Z", "cross-user-account")))
                .isInstanceOf(DataIntegrityViolationException.class);
        InvestmentTransaction invalidDividend = transaction(firstUserId, firstAssetId, firstAccountId, "DIVIDEND", "2026-01-04T10:00:00Z", "invalid-dividend");
        assertThatThrownBy(() -> investmentTransactionMapper.insert(invalidDividend))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void scopesReplayQueriesToTheUserAndOrdersSameTimeTransactionsById() {
        Long firstUserId = insertUser("replay-first");
        Long secondUserId = insertUser("replay-second");
        Long firstAccountId = insertAccount(firstUserId, "First brokerage");
        Long secondAccountId = insertAccount(secondUserId, "Second brokerage");
        Long firstAssetId = insertAsset(firstUserId, firstAccountId, "First fund");
        Long secondAssetId = insertAsset(secondUserId, secondAccountId, "Second fund");
        String tradeTime = "2026-01-01T10:00:00Z";

        InvestmentTransaction first = transaction(firstUserId, firstAssetId, firstAccountId, "BUY", tradeTime, "replay-first");
        InvestmentTransaction second = transaction(firstUserId, firstAssetId, firstAccountId, "BUY", tradeTime, "replay-second");
        InvestmentTransaction foreign = transaction(secondUserId, secondAssetId, secondAccountId, "BUY", tradeTime, "replay-foreign");
        investmentTransactionMapper.insert(first);
        investmentTransactionMapper.insert(second);
        investmentTransactionMapper.insert(foreign);

        assertThat(investmentTransactionMapper.selectPostedByUserIdAndAssetId(firstUserId, firstAssetId))
                .extracting(InvestmentTransaction::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(investmentTransactionMapper.findByUserIdAndId(secondUserId, first.getId())).isNull();
        assertThat(investmentTransactionMapper.findByUserIdAndIdempotencyKey(secondUserId, "replay-first")).isNull();
    }

    @Test
    void enforcesTransactionAmountEquationsWithPostgresChecks() {
        Long userId = insertUser("amount-investor");
        Long accountId = insertAccount(userId, "Brokerage");
        Long assetId = insertAsset(userId, accountId, "Fund");

        InvestmentTransaction buyWithWrongNet = transaction(userId, assetId, accountId, "BUY", "2026-01-01T10:00:00Z", "buy-wrong-net");
        buyWithWrongNet.setNetAmount(decimal("19.99"));
        assertCheckRejected(buyWithWrongNet, "ck_investment_transactions_buy_amounts");

        InvestmentTransaction sellWithWrongProfitLoss = sell(userId, assetId, accountId, "2026-01-02T10:00:00Z", "sell-wrong-pnl");
        sellWithWrongProfitLoss.setRealizedProfitLoss(decimal("8.01"));
        assertCheckRejected(sellWithWrongProfitLoss, "ck_investment_transactions_sell_amounts");

        InvestmentTransaction dividendWithCost = dividend(userId, assetId, accountId, "2026-01-03T10:00:00Z", "dividend-cost");
        dividendWithCost.setReleasedCostAmount(decimal("0.01"));
        assertCheckRejected(dividendWithCost, "ck_investment_transactions_dividend_amounts");

        InvestmentTransaction openingWithFee = opening(userId, assetId, accountId, "2026-01-04T10:00:00Z", "opening-fee");
        openingWithFee.setFeeAmount(decimal("0.01"));
        assertCheckRejected(openingWithFee, "ck_investment_transactions_opening_position_amounts");
    }

    @Test
    void rejectsTheRetiredReplacesTransactionIdField() {
        Long firstUserId = insertUser("replacement-first");
        Long firstAccountId = insertAccount(firstUserId, "First brokerage");
        Long firstAssetId = insertAsset(firstUserId, firstAccountId, "First fund");

        InvestmentTransaction original = transaction(firstUserId, firstAssetId, firstAccountId, "BUY", "2026-01-01T10:00:00Z", "original");
        investmentTransactionMapper.insert(original);

        InvestmentTransaction sameUserSameAsset = transaction(firstUserId, firstAssetId, firstAccountId, "BUY", "2026-01-02T10:00:00Z", "same-user-asset");
        sameUserSameAsset.setReplacesTransactionId(original.getId());
        assertThatThrownBy(() -> investmentTransactionMapper.insert(sameUserSameAsset))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exposesTheAppendOnlyReversalColumnsFromV12() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'investment_transactions'
                      AND column_name = 'original_transaction_id'
                )
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'investment_transactions'
                      AND column_name = 'cash_delta'
                )
                """, Boolean.class)).isTrue();
    }

    @Test
    void enforcesAppendOnlyReversalBindingAuditAndImmutability() {
        Long userId = insertUser("reversal-constraints");
        Long accountId = insertAccount(userId, "Brokerage");
        Long assetId = insertAsset(userId, accountId, "Fund");
        InvestmentTransaction original = transaction(userId, assetId, accountId, "BUY", "2026-01-01T10:00:00Z", "original-buy");
        original.setSource("MANUAL");
        investmentTransactionMapper.insert(original);

        InvestmentTransaction reversal = reversalOf(original, "reversal-one");
        investmentTransactionMapper.insert(reversal);
        assertThat(reversal.getId()).isNotNull();
        assertThat(reversal.getCashDelta()).isEqualByComparingTo("20.00");

        InvestmentTransaction duplicate = reversalOf(original, "reversal-two");
        assertThatThrownBy(() -> investmentTransactionMapper.insert(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertSqlStateAndConstraint(error, "23505", "uk_investment_transactions_reversal_original"));
        InvestmentTransaction wrongCashDelta = reversalOf(original, "wrong-cash-delta");
        wrongCashDelta.setCashDelta(decimal("19.99"));
        assertCheckRejected(wrongCashDelta, "investment reversal audit values are invalid");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE investment_transactions SET note = 'changed' WHERE id = ?", original.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM investment_transactions WHERE id = ?", reversal.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void triggerRejectsReversalsOfOpeningFactsAndReversalFacts() {
        Long userId = insertUser("reversal-trigger-targets");
        Long accountId = insertAccount(userId, "Brokerage");
        Long assetId = insertAsset(userId, accountId, "Fund");
        InvestmentTransaction opening = opening(userId, assetId, accountId, "2026-01-01T10:00:00Z", "opening");
        investmentTransactionMapper.insert(opening);

        assertCheckRejected(reversalOf(opening, "opening-reversal"), "investment reversal original is invalid");

        InvestmentTransaction buy = transaction(userId, assetId, accountId, "BUY", "2026-01-02T10:00:00Z", "buy");
        investmentTransactionMapper.insert(buy);
        InvestmentTransaction firstReversal = reversalOf(buy, "first-reversal");
        investmentTransactionMapper.insert(firstReversal);
        InvestmentTransaction reversalOfReversal = reversalOf(firstReversal, "reversal-of-reversal");
        assertCheckRejected(reversalOfReversal, "investment reversal original is invalid");
    }

    private InvestmentTransaction transaction(Long userId, Long assetId, Long accountId, String type, String tradeTime, String idempotencyKey) {
        InvestmentTransaction transaction = new InvestmentTransaction();
        transaction.setUserId(userId);
        transaction.setAssetId(assetId);
        transaction.setAccountId(accountId);
        transaction.setTransactionType(type);
        transaction.setStatus("POSTED");
        transaction.setQuantity(decimal("2.00000000"));
        transaction.setUnitPrice(decimal("10.00000000"));
        transaction.setGrossAmount(decimal("20.00"));
        transaction.setFeeAmount(decimal("0.00"));
        transaction.setTaxAmount(decimal("0.00"));
        transaction.setNetAmount("OPENING_POSITION".equals(type) ? decimal("0.00") : decimal("20.00"));
        transaction.setReleasedCostAmount(decimal("0.00"));
        transaction.setRealizedProfitLoss(decimal("0.00"));
        transaction.setCurrency("CNY");
        transaction.setTradeTime(Instant.parse(tradeTime));
        transaction.setSettlementTime(Instant.parse(tradeTime));
        transaction.setSource("MIGRATION");
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setRequestHash("a".repeat(64));
        if ("BUY".equals(type) || "SELL".equals(type) || "DIVIDEND".equals(type)) {
            transaction.setAccountBalanceAfter(decimal("0.00"));
            transaction.setPositionQuantityAfter(decimal("2.00000000"));
            transaction.setPositionAvgCostAfter(decimal("10.00000000"));
            transaction.setPositionTotalCostAfter(decimal("20.00"));
            transaction.setPositionRealizedProfitLossAfter(decimal("0.00"));
            transaction.setPositionStatusAfter("OPEN");
            transaction.setProjectionVersionAfter(1);
        }
        return transaction;
    }

    private InvestmentTransaction sell(Long userId, Long assetId, Long accountId, String tradeTime, String idempotencyKey) {
        InvestmentTransaction transaction = transaction(userId, assetId, accountId, "SELL", tradeTime, idempotencyKey);
        transaction.setFeeAmount(decimal("1.00"));
        transaction.setTaxAmount(decimal("1.00"));
        transaction.setNetAmount(decimal("18.00"));
        transaction.setReleasedCostAmount(decimal("10.00"));
        transaction.setRealizedProfitLoss(decimal("8.00"));
        return transaction;
    }

    private InvestmentTransaction dividend(Long userId, Long assetId, Long accountId, String tradeTime, String idempotencyKey) {
        InvestmentTransaction transaction = transaction(userId, assetId, accountId, "DIVIDEND", tradeTime, idempotencyKey);
        transaction.setQuantity(null);
        transaction.setUnitPrice(null);
        transaction.setGrossAmount(decimal("10.00"));
        transaction.setNetAmount(decimal("10.00"));
        return transaction;
    }

    private InvestmentTransaction opening(Long userId, Long assetId, Long accountId, String tradeTime, String idempotencyKey) {
        InvestmentTransaction transaction = transaction(userId, assetId, accountId, "OPENING_POSITION", tradeTime, idempotencyKey);
        transaction.setNetAmount(decimal("0.00"));
        return transaction;
    }

    private InvestmentTransaction reversalOf(InvestmentTransaction original, String idempotencyKey) {
        InvestmentTransaction reversal = new InvestmentTransaction();
        reversal.setUserId(original.getUserId());
        reversal.setAssetId(original.getAssetId());
        reversal.setAccountId(original.getAccountId());
        reversal.setTransactionType("REVERSAL");
        reversal.setStatus("POSTED");
        reversal.setQuantity(original.getQuantity());
        reversal.setUnitPrice(original.getUnitPrice());
        reversal.setGrossAmount(original.getGrossAmount());
        reversal.setFeeAmount(original.getFeeAmount());
        reversal.setTaxAmount(original.getTaxAmount());
        reversal.setNetAmount(original.getNetAmount());
        reversal.setReleasedCostAmount(decimal("0.00"));
        reversal.setRealizedProfitLoss(decimal("0.00"));
        reversal.setCurrency(original.getCurrency());
        reversal.setTradeTime(Instant.parse("2026-01-02T10:00:00Z"));
        reversal.setSettlementTime(Instant.parse("2026-01-02T10:00:00Z"));
        reversal.setSource("CORRECTION");
        reversal.setIdempotencyKey(idempotencyKey);
        reversal.setRequestHash("b".repeat(64));
        reversal.setOriginalTransactionId(original.getId());
        reversal.setCorrectionReason("Broker correction");
        reversal.setCashDelta(decimal("20.00"));
        reversal.setAccountBalanceAfter(decimal("0.00"));
        reversal.setPositionQuantityAfter(decimal("2.00000000"));
        reversal.setPositionAvgCostAfter(decimal("10.00000000"));
        reversal.setPositionTotalCostAfter(decimal("20.00"));
        reversal.setPositionRealizedProfitLossAfter(decimal("0.00"));
        reversal.setPositionStatusAfter("OPEN");
        reversal.setProjectionVersionAfter(1);
        return reversal;
    }

    private void assertCheckRejected(InvestmentTransaction transaction, String constraintName) {
        assertThatThrownBy(() -> investmentTransactionMapper.insert(transaction))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertSqlStateAndConstraint(error, "23514", constraintName));
    }

    private void assertForeignKeyRejected(InvestmentTransaction transaction, String constraintName) {
        assertThatThrownBy(() -> investmentTransactionMapper.insert(transaction))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertSqlStateAndConstraint(error, "23503", constraintName));
    }

    private void assertSqlStateAndConstraint(Throwable error, String sqlState, String constraintName) {
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(error);
        assertThat(rootCause).isInstanceOf(SQLException.class);
        SQLException sqlException = (SQLException) rootCause;
        assertThat(sqlException.getSQLState()).isEqualTo(sqlState);
        assertThat(sqlException.getMessage()).contains(constraintName);
    }

    private Long insertUser(String username) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES (?, ?, 'hash')
                RETURNING id
                """, Long.class, username, username + "@example.com");
    }

    private Long insertAccount(Long userId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, ?, 'BROKERAGE', 'CNY', 0)
                RETURNING id
                """, Long.class, userId, name);
    }

    private Long insertAsset(Long userId, Long accountId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO assets (user_id, account_id, name, type, currency, quantity, avg_cost)
                VALUES (?, ?, ?, 'FUND', 'CNY', 0, 0)
                RETURNING id
                """, Long.class, userId, accountId, name);
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
