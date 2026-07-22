package com.financeos.module.investment.mapper;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.investment.entity.InvestmentTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
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
    void persistsDividendAndReversedRecordsButExcludesReversedRowsFromReplayQuery() {
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
        investmentTransactionMapper.insert(reversedBuy);

        assertThat(investmentTransactionMapper.selectPostedByUserIdAndAssetId(userId, assetId))
                .extracting(InvestmentTransaction::getTransactionType)
                .containsExactly("DIVIDEND");
        assertThat(investmentTransactionMapper.findByUserIdAndId(userId, reversedBuy.getId()).getStatus())
                .isEqualTo("REVERSED");
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
        transaction.setRequestHash("hash-" + idempotencyKey);
        return transaction;
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
