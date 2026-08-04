package com.financeos.module.investment.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.investment.read.dto.CursorPage;
import com.financeos.module.investment.read.dto.InvestmentLogicalTransactionListItem;
import com.financeos.module.investment.read.dto.InvestmentPositionListItem;
import com.financeos.module.investment.read.model.LogicalTransactionListQuery;
import com.financeos.module.investment.read.model.PositionListQuery;
import com.financeos.module.investment.read.service.InvestmentLogicalTransactionReadQueryService;
import com.financeos.module.investment.read.service.InvestmentPositionReadQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class InvestmentReadQueryPostgresIntegrationTest extends PostgresIntegrationTest {
    @Autowired private InvestmentPositionReadQueryService positionService;
    @Autowired private InvestmentLogicalTransactionReadQueryService transactionService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void listsOnlyTransactionDrivenPositionsWithStableSeekPaginationAndFormattedValues() {
        Fixture fixture = fixture();
        long secondInstrument = insertInstrument(fixture.userId(), "ZZZ", "Second Fund");
        long secondPosition = insertPosition(fixture.userId(), fixture.accountId(), secondInstrument, "2.00000000", "11.00000000", "22.00", "OPEN");
        jdbcTemplate.update("INSERT INTO assets (user_id, name, type, currency, quantity, avg_cost, position_mode) VALUES (?, 'Legacy', 'FUND', 'CNY', 1, 1, 'LEGACY')", fixture.userId());

        CursorPage<InvestmentPositionListItem> first = positionService.list(fixture.userId(), new PositionListQuery(null, null, null, null, 1));
        CursorPage<InvestmentPositionListItem> second = positionService.list(fixture.userId(), new PositionListQuery(null, null, null, first.nextCursor(), 1));

        assertThat(first.records()).singleElement().satisfies(item -> {
            assertThat(item.positionId()).isEqualTo(fixture.positionId());
            assertThat(item.quantity()).isEqualTo("1.00000000");
            assertThat(item.totalCost()).isEqualTo("10.00");
            assertThat(item.positionMode()).isEqualTo("TRANSACTION_DRIVEN");
        });
        assertThat(first.hasMore()).isTrue();
        assertThat(second.records()).extracting(InvestmentPositionListItem::positionId).containsExactly(secondPosition);
        assertThat(second.nextCursor()).isNull();
        assertThat(positionService.list(fixture.userId(), new PositionListQuery(null, null, null, null, null)).records())
                .extracting(InvestmentPositionListItem::positionId).containsExactly(fixture.positionId(), secondPosition);
    }

    @Test
    void readsLogicalTransactionsInEffectiveTimeOrderAndProtectsForeignFilters() {
        Fixture fixture = fixture();
        long first = insertBuy(fixture, "2026-08-01T10:00:00Z", "first");
        long second = insertBuy(fixture, "2026-08-01T10:00:00Z", "second");
        insertBuy(fixture, "2026-07-01T10:00:00Z", "older");
        Fixture foreign = fixture();

        CursorPage<InvestmentLogicalTransactionListItem> page = transactionService.list(fixture.userId(),
                new LogicalTransactionListQuery(null, null, null, "BUY", null,
                        Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"), null, 20));

        assertThat(page.records()).extracting(InvestmentLogicalTransactionListItem::logicalTransactionId)
                .containsExactly(second, first);
        assertThat(page.records()).allSatisfy(item -> {
            assertThat(item.correctionStatus()).isEqualTo("UNCHANGED");
            assertThat(item.effective()).isTrue();
            assertThat(item.quantity()).isEqualTo("1.00000000");
            assertThat(item.netAmount()).isEqualTo("10.00");
        });
        assertThatThrownBy(() -> transactionService.list(fixture.userId(),
                new LogicalTransactionListQuery(foreign.positionId(), null, null, null, null, null, null, null, 20)))
                .isInstanceOf(BusinessException.class).extracting(error -> ((BusinessException) error).getCode()).isEqualTo(404);
    }

    @Test
    void foldsStandaloneReversalIntoOneFilteredRedactedLogicalEvent() {
        Fixture fixture = fixture();
        Instant originalTime = Instant.parse("2026-08-01T10:00:00Z");
        Instant reversalTradeTime = Instant.parse("2026-08-04T10:00:00Z");
        Instant reversalCreatedAt = Instant.parse("2026-08-04T10:00:01Z");
        long originalId = insertBuy(fixture, originalTime.toString(), "standalone-original");
        long reversalId = insertStandaloneReversal(fixture, originalId, reversalTradeTime, reversalCreatedAt);

        CursorPage<InvestmentLogicalTransactionListItem> page = transactionService.list(fixture.userId(), null);

        assertThat(page.records()).singleElement().satisfies(item -> {
            assertThat(item.logicalTransactionId()).isEqualTo(originalId).isNotEqualTo(reversalId);
            assertThat(item.transactionType()).isEqualTo("BUY");
            assertThat(item.quantity()).isEqualTo("1.00000000");
            assertThat(item.unitPrice()).isEqualTo("10.00000000");
            assertThat(item.netAmount()).isEqualTo("10.00");
            assertThat(item.correctionStatus()).isEqualTo("REVERSED");
            assertThat(item.effective()).isFalse();
            assertThat(item.effectiveTradeTime()).isEqualTo(originalTime);
            assertThat(item.correctionCreatedAt()).isEqualTo(reversalCreatedAt);
            assertPublicLogicalTransactionShape(item);
        });
        assertThat(logicalRecords(fixture.userId(), fixture.positionId(), "BUY", "REVERSED",
                originalTime.minusSeconds(1), originalTime.plusSeconds(1))).extracting(InvestmentLogicalTransactionListItem::logicalTransactionId)
                .containsExactly(originalId);
        assertThat(logicalRecords(fixture.userId(), fixture.positionId(), "SELL", "REVERSED",
                originalTime.minusSeconds(1), originalTime.plusSeconds(1))).isEmpty();
        assertThat(logicalRecords(fixture.userId(), fixture.positionId(), "BUY", "UNCHANGED",
                originalTime.minusSeconds(1), originalTime.plusSeconds(1))).isEmpty();
        assertThat(logicalRecords(fixture.userId(), fixture.positionId(), "BUY", "REVERSED",
                reversalTradeTime.minusSeconds(1), reversalTradeTime.plusSeconds(1))).isEmpty();

        Fixture foreign = fixture();
        assertThatThrownBy(() -> insertCrossUserReversal(foreign, originalId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(transactionService.list(foreign.userId(), null).records()).isEmpty();
    }

    @Test
    void foldsReplacementIntoOriginalIdentityAndEffectiveTimeWithReplacementValues() {
        Fixture fixture = fixture();
        Instant originalTime = Instant.parse("2026-08-01T10:00:00Z");
        Instant normalTime = Instant.parse("2026-08-02T10:00:00Z");
        Instant correctionCreatedAt = Instant.parse("2026-08-04T10:00:01Z");
        long originalId = insertBuy(fixture, originalTime.toString(), "replacement-original");
        long newerNormalId = insertBuy(fixture, normalTime.toString(), "newer-normal");
        ReplacementFacts facts = insertReplacement(fixture, originalId, correctionCreatedAt);

        CursorPage<InvestmentLogicalTransactionListItem> page = transactionService.list(fixture.userId(), null);

        assertThat(page.records()).extracting(InvestmentLogicalTransactionListItem::logicalTransactionId)
                .containsExactly(newerNormalId, originalId);
        assertThat(page.records()).filteredOn(item -> item.logicalTransactionId().equals(originalId)).singleElement().satisfies(item -> {
            assertThat(item.logicalTransactionId()).isNotEqualTo(facts.reversalId()).isNotEqualTo(facts.replacementId());
            assertThat(item.transactionType()).isEqualTo("BUY");
            assertThat(item.quantity()).isEqualTo("3.00000000");
            assertThat(item.unitPrice()).isEqualTo("11.00000000");
            assertThat(item.grossAmount()).isEqualTo("33.00");
            assertThat(item.feeAmount()).isEqualTo("1.00");
            assertThat(item.netAmount()).isEqualTo("34.00");
            assertThat(item.correctionStatus()).isEqualTo("REPLACED");
            assertThat(item.effective()).isTrue();
            assertThat(item.effectiveTradeTime()).isEqualTo(originalTime);
            assertThat(item.correctionCreatedAt()).isEqualTo(correctionCreatedAt);
            assertPublicLogicalTransactionShape(item);
        });
        assertThat(logicalRecords(fixture.userId(), fixture.positionId(), "BUY", "REPLACED",
                originalTime.minusSeconds(1), originalTime.plusSeconds(1))).extracting(InvestmentLogicalTransactionListItem::logicalTransactionId)
                .containsExactly(originalId);
        assertThat(logicalRecords(fixture.userId(), fixture.positionId(), "SELL", "REPLACED",
                originalTime.minusSeconds(1), originalTime.plusSeconds(1))).isEmpty();
        assertThat(logicalRecords(fixture.userId(), fixture.positionId(), "BUY", "REPLACED",
                correctionCreatedAt.minusSeconds(1), correctionCreatedAt.plusSeconds(1))).isEmpty();
    }

    @Test
    void paginatesMixedLogicalEventsByEffectiveTimeAndLogicalIdWithoutPhysicalFactSlots() {
        Fixture fixture = fixture();
        Instant tiedTime = Instant.parse("2026-08-01T10:00:00Z");
        long ordinaryId = insertBuy(fixture, tiedTime.toString(), "page-ordinary");
        long reversedId = insertBuy(fixture, tiedTime.toString(), "page-reversed");
        long replacedId = insertBuy(fixture, tiedTime.toString(), "page-replaced");
        insertStandaloneReversal(fixture, reversedId, Instant.parse("2026-08-03T10:00:00Z"), Instant.parse("2026-08-03T10:00:01Z"));
        insertReplacement(fixture, replacedId, Instant.parse("2026-08-04T10:00:01Z"));

        CursorPage<InvestmentLogicalTransactionListItem> first = transactionService.list(fixture.userId(),
                new LogicalTransactionListQuery(null, null, null, null, null, null, null, null, 2));
        long insertedAfterFirstPage = insertBuy(fixture, "2026-08-05T10:00:00Z", "inserted-after-page-one");
        CursorPage<InvestmentLogicalTransactionListItem> second = transactionService.list(fixture.userId(),
                new LogicalTransactionListQuery(null, null, null, null, null, null, null, first.nextCursor(), 2));

        assertThat(first.records()).extracting(InvestmentLogicalTransactionListItem::logicalTransactionId)
                .containsExactly(replacedId, reversedId);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.records()).extracting(InvestmentLogicalTransactionListItem::logicalTransactionId)
                .containsExactly(ordinaryId);
        assertThat(second.hasMore()).isFalse();
        assertThat(List.of(first.records(), second.records()).stream().flatMap(List::stream)
                .map(InvestmentLogicalTransactionListItem::logicalTransactionId).toList())
                .containsExactly(replacedId, reversedId, ordinaryId)
                .doesNotHaveDuplicates()
                .doesNotContain(insertedAfterFirstPage);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE user_id = ? AND trade_time = ?",
                Integer.class, fixture.userId(), Timestamp.from(tiedTime))).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE user_id = ?",
                Integer.class, fixture.userId())).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE user_id = ? AND correction_group_id IS NOT NULL",
                Integer.class, fixture.userId())).isEqualTo(2);
    }

    @Test
    void databaseRejectsIncompleteReplacementGroupsBeforeTheyCanReachTheReadModel() {
        Fixture fixture = fixture();
        long originalId = insertBuy(fixture, "2026-08-01T10:00:00Z", "incomplete-original");
        UUID groupId = UUID.randomUUID();

        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                insertReplacementFact(fixture, originalId, groupId, "incomplete-replacement")));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("fk_investment_transactions_correction_group_command");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE correction_group_id = ?",
                Integer.class, groupId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_trigger
                WHERE tgname IN ('trg_validate_investment_replacement_group_complete',
                                 'trg_validate_investment_replacement_command_complete')
                  AND tgdeferrable AND tginitdeferred
                """, Integer.class)).isEqualTo(2);
        assertThat(transactionService.list(fixture.userId(), null).records()).singleElement()
                .extracting(InvestmentLogicalTransactionListItem::logicalTransactionId).isEqualTo(originalId);
    }

    private Fixture fixture() {
        long userId = jdbcTemplate.queryForObject("INSERT INTO users (username, email, password_hash) VALUES (?, ?, 'hash') RETURNING id", Long.class,
                "read-user-" + System.nanoTime(), "read-" + System.nanoTime() + "@example.com");
        long accountId = jdbcTemplate.queryForObject("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0) RETURNING id", Long.class, userId);
        long instrumentId = insertInstrument(userId, "READ" + userId, "Read Fund");
        long positionId = insertPosition(userId, accountId, instrumentId, "1.00000000", "10.00000000", "10.00", "OPEN");
        return new Fixture(userId, accountId, instrumentId, positionId);
    }

    private long insertInstrument(long userId, String symbol, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, ?, ?, 'FUND', 'FUND', 'CNY', 'ACTIVE') RETURNING id
                """, Long.class, userId, symbol, name);
    }

    private long insertPosition(long userId, long accountId, long instrumentId, String quantity, String averageCost, String totalCost, String status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO assets (user_id, account_id, instrument_id, name, symbol, type, market, currency, quantity, avg_cost,
                                    total_cost, realized_profit_loss, position_status, position_mode, projection_version)
                VALUES (?, ?, ?, 'Read position', 'READ', 'FUND', 'FUND', 'CNY', ?, ?, ?, 0.00, ?, 'TRANSACTION_DRIVEN', 1)
                RETURNING id
                """, Long.class, userId, accountId, instrumentId, new BigDecimal(quantity), new BigDecimal(averageCost),
                new BigDecimal(totalCost), status);
    }

    private long insertBuy(Fixture fixture, String time, String key) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO investment_transactions (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price,
                    gross_amount, fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash, account_balance_after,
                    position_quantity_after, position_avg_cost_after, position_total_cost_after, position_realized_profit_loss_after,
                    position_status_after, projection_version_after)
                VALUES (?, ?, ?, 'BUY', 'POSTED', 1.00000000, 10.00000000, 10.00, 0.00, 0.00, 10.00, 0.00, 0.00, 'CNY',
                    ?, ?, 'MANUAL', ?, ?, 0.00, 1.00000000, 10.00000000, 10.00, 0.00, 'OPEN', 1)
                RETURNING id
                """, Long.class, fixture.userId(), fixture.positionId(), fixture.accountId(), Timestamp.from(Instant.parse(time)), Timestamp.from(Instant.parse(time)),
                key + '-' + System.nanoTime(), "a".repeat(64));
    }

    private long insertStandaloneReversal(Fixture fixture, long originalId, Instant tradeTime, Instant createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO investment_transactions (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price,
                    gross_amount, fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash, account_balance_after,
                    position_quantity_after, position_avg_cost_after, position_total_cost_after, position_realized_profit_loss_after,
                    position_status_after, projection_version_after, original_transaction_id, correction_reason, cash_delta, created_at)
                SELECT user_id, asset_id, account_id, 'REVERSAL', 'POSTED', quantity, unit_price,
                    gross_amount, fee_amount, tax_amount, net_amount, 0.00, 0.00, currency,
                    ?, ?, 'CORRECTION', ?, ?, 0.00,
                    0.00000000, 0.00000000, 0.00, 0.00, 'CLOSED', 2, id, 'read reversal reason', net_amount, ?
                FROM investment_transactions WHERE id = ? AND user_id = ?
                RETURNING id
                """, Long.class, Timestamp.from(tradeTime), Timestamp.from(tradeTime),
                "read-reversal-" + UUID.randomUUID(), "b".repeat(64), Timestamp.from(createdAt), originalId, fixture.userId());
    }

    private void insertCrossUserReversal(Fixture foreign, long originalId) {
        jdbcTemplate.update("""
                INSERT INTO investment_transactions (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price,
                    gross_amount, fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash, account_balance_after,
                    position_quantity_after, position_avg_cost_after, position_total_cost_after, position_realized_profit_loss_after,
                    position_status_after, projection_version_after, original_transaction_id, correction_reason, cash_delta, created_at)
                VALUES (?, ?, ?, 'REVERSAL', 'POSTED', 1.00000000, 10.00000000,
                    10.00, 0.00, 0.00, 10.00, 0.00, 0.00, 'CNY',
                    ?, ?, 'CORRECTION', ?, ?, 0.00,
                    0.00000000, 0.00000000, 0.00, 0.00, 'CLOSED', 2, ?, 'cross-user attempt', 10.00, ?)
                """, foreign.userId(), foreign.positionId(), foreign.accountId(),
                Timestamp.from(Instant.parse("2026-08-04T10:00:00Z")), Timestamp.from(Instant.parse("2026-08-04T10:00:00Z")),
                "cross-user-" + UUID.randomUUID(), "c".repeat(64), originalId, Timestamp.from(Instant.parse("2026-08-04T10:00:01Z")));
    }

    private ReplacementFacts insertReplacement(Fixture fixture, long originalId, Instant correctionCreatedAt) {
        UUID groupId = UUID.randomUUID();
        long[] ids = new long[2];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ids[0] = insertGroupedReversal(fixture, originalId, groupId);
            ids[1] = insertReplacementFact(fixture, originalId, groupId, "replacement-" + groupId);
            jdbcTemplate.update("""
                    INSERT INTO investment_transaction_corrections (
                        correction_group_id, user_id, account_id, asset_id, instrument_id, original_transaction_id,
                        transaction_type, correction_kind, idempotency_key, request_hash, correction_reason,
                        reversal_transaction_id, replacement_transaction_id, reversal_cash_delta, replacement_cash_delta,
                        command_cash_delta, balance_after, position_quantity_after, position_avg_cost_after,
                        position_total_cost_after, position_realized_profit_loss_after, position_status_after,
                        projection_version, last_transaction_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'BUY', 'REPLACEMENT', ?, ?, 'read replacement reason',
                        ?, ?, 10.00, -34.00, -24.00, -34.00, 3.00000000, 11.33333333,
                        34.00, 0.00, 'OPEN', 2, ?, ?)
                    """, groupId, fixture.userId(), fixture.accountId(), fixture.positionId(), fixture.instrumentId(), originalId,
                    "replacement-command-" + groupId, "d".repeat(64), ids[0], ids[1], ids[1], Timestamp.from(correctionCreatedAt));
        });
        return new ReplacementFacts(ids[0], ids[1], groupId);
    }

    private long insertGroupedReversal(Fixture fixture, long originalId, UUID groupId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO investment_transactions (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price,
                    gross_amount, fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash, original_transaction_id,
                    correction_reason, cash_delta, correction_group_id, created_at)
                SELECT user_id, asset_id, account_id, 'REVERSAL', 'POSTED', quantity, unit_price,
                    gross_amount, fee_amount, tax_amount, net_amount, 0.00, 0.00, currency,
                    ?, ?, 'CORRECTION', ?, ?, id, 'read replacement reason', net_amount, ?, ?
                FROM investment_transactions WHERE id = ? AND user_id = ?
                RETURNING id
                """, Long.class, Timestamp.from(Instant.parse("2026-08-04T10:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-04T10:00:00Z")), "grouped-reversal-" + groupId,
                "e".repeat(64), groupId, Timestamp.from(Instant.parse("2026-08-04T10:00:00Z")), originalId, fixture.userId());
    }

    private long insertReplacementFact(Fixture fixture, long originalId, UUID groupId, String key) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO investment_transactions (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price,
                    gross_amount, fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash, account_balance_after,
                    position_quantity_after, position_avg_cost_after, position_total_cost_after, position_realized_profit_loss_after,
                    position_status_after, projection_version_after, cash_delta, correction_group_id,
                    replay_anchor_transaction_id, replay_sequence, created_at)
                SELECT user_id, asset_id, account_id, transaction_type, 'POSTED', 3.00000000, 11.00000000,
                    33.00, 1.00, 0.00, 34.00, 0.00, 0.00, currency,
                    trade_time, settlement_time, 'CORRECTION', ?, ?, -34.00,
                    3.00000000, 11.33333333, 34.00, 0.00, 'OPEN', 2, -34.00, ?, id, 1, ?
                FROM investment_transactions WHERE id = ? AND user_id = ?
                RETURNING id
                """, Long.class, key, "f".repeat(64), groupId,
                Timestamp.from(Instant.parse("2026-08-04T10:00:00Z")), originalId, fixture.userId());
    }

    private List<InvestmentLogicalTransactionListItem> logicalRecords(long userId, long positionId, String type,
                                                                       String correctionStatus, Instant from, Instant to) {
        return transactionService.list(userId,
                new LogicalTransactionListQuery(positionId, null, null, type, correctionStatus, from, to, null, 20)).records();
    }

    private void assertPublicLogicalTransactionShape(InvestmentLogicalTransactionListItem item) {
        JsonNode json = objectMapper.valueToTree(item);
        assertThat(json.properties().stream().map(java.util.Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "logicalTransactionId", "positionId", "account", "instrument", "transactionType", "effectiveTradeTime",
                "quantity", "unitPrice", "grossAmount", "feeAmount", "taxAmount", "netAmount", "releasedCostAmount",
                "realizedProfitLoss", "correctionStatus", "effective", "correctionCreatedAt");
        assertThat(json.toString()).doesNotContain("originalTransactionId", "reversalTransactionId", "replacementTransactionId",
                "correctionGroupId", "correctionReason", "requestHash", "idempotencyKey", "accountBalanceAfter",
                "positionQuantityAfter", "receipt", "digest", "trace");
    }

    private record Fixture(long userId, long accountId, long instrumentId, long positionId) { }
    private record ReplacementFacts(long reversalId, long replacementId, UUID groupId) { }
}
