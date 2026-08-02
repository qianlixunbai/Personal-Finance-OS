package com.financeos.module.investment;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.ledger.InvestmentLedgerCommand;
import com.financeos.module.investment.ledger.InvestmentPositionState;
import com.financeos.module.investment.ledger.InvestmentReplayEngine;
import com.financeos.module.investment.ledger.InvestmentReplayEntry;
import com.financeos.module.investment.ledger.InvestmentReplayResult;
import com.financeos.module.investment.ledger.InvestmentTransactionStatus;
import com.financeos.module.investment.ledger.InvestmentTransactionType;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@AutoConfigureMockMvc
class InvestmentReplacementConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final String BUY_REPLACEMENT = """
            {"quantity":"3.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00","reason":"Broker correction"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private InvestmentTransactionMapper transactionMapper;

    @Autowired
    private AssetMapper assetMapper;

    @SpyBean
    private InvestmentReplayEngine replayEngine;

    @Test
    void concurrentSameKeyAndHashWritesOneCorrectionAndReplaysTheImmutableReceipt() throws Exception {
        Long userId = seedBuy();

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "same-key", BUY_REPLACEMENT),
                () -> replace(userId, 1, "same-key", BUY_REPLACEMENT),
                "investment_transactions");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(200);
        assertThat(List.of(race.first().body(), race.second().body()))
                .anySatisfy(body -> assertThat(body).contains("\"idempotentReplay\":false"))
                .anySatisfy(body -> assertThat(body).contains("\"idempotentReplay\":true"));
        assertOneReplacementCommand(1, 3, "-30.00", 2, 3L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void concurrentSameKeyWithDifferentHashWritesOnceAndConflictsOnce() throws Exception {
        Long userId = seedBuy();
        String changed = """
                {"quantity":"4.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00","reason":"Different broker correction"}
                """;

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "same-key", BUY_REPLACEMENT),
                () -> replace(userId, 1, "same-key", changed),
                "investment_transactions");

        assertThat(List.of(race.first().status(), race.second().status())).containsExactlyInAnyOrder(200, 409);
        assertOneReplacementCommand(1, 3, "-30.00", 2, 3L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void concurrentDifferentKeysForOneOriginalWriteOneCorrectionAndLeaveNoPartialFacts() throws Exception {
        Long userId = seedBuy();

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "first-key", BUY_REPLACEMENT),
                () -> replace(userId, 1, "second-key", BUY_REPLACEMENT),
                "investment_transactions");

        assertThat(List.of(race.first().status(), race.second().status())).containsExactlyInAnyOrder(200, 409);
        assertOneReplacementCommand(1, 3, "-30.00", 2, 3L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void replacementAndStandaloneReversalSerializeWithExactlyOneEffectiveCorrection() throws Exception {
        Long userId = seedBuy();

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "replace", BUY_REPLACEMENT),
                () -> reverse(userId, 1, "standalone-reversal"),
                "investment_transactions");

        assertThat(List.of(race.first().status(), race.second().status())).containsExactlyInAnyOrder(200, 409);
        assertOneReplacementCommand(1, 3, "-30.00", 2, 3L);
        Snapshot snapshot = snapshotFromFreshConnection();
        assertThat(countTransactions(snapshot, "REVERSAL")).isEqualTo(1);
        assertThat(snapshot.transactions().stream()
                .filter(row -> "REVERSAL".equals(row.get("transaction_type")))
                .map(row -> row.get("correction_group_id")))
                .allMatch(group -> group != null);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void replacementAndBuySerializeAndReplayTheLatestPersistedHistory() throws Exception {
        Long userId = seedBuy();

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "replace", BUY_REPLACEMENT),
                () -> buy(userId, "later-buy", "1.00000000", "20.00000000"),
                "accounts");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-50.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("4.00000000"))
                .containsEntry("total_cost", new BigDecimal("50.00"))
                .containsEntry("projection_version", 3)
                .containsEntry("last_transaction_id", 4L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void replacementAndSellSerializeAndReplayTheLatestPersistedHistory() throws Exception {
        Long userId = seedBuy();

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "replace", BUY_REPLACEMENT),
                () -> sell(userId, "later-sell", "1.00000000", "12.00000000"),
                "accounts");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-18.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, realized_profit_loss, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("2.00"))
                .containsEntry("projection_version", 3)
                .containsEntry("last_transaction_id", 4L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void replacementAndDividendSerializeAndReplayTheLatestPersistedHistory() throws Exception {
        Long userId = seedBuy();

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "replace", BUY_REPLACEMENT),
                () -> dividend(userId, "later-dividend", "5.00"),
                "accounts");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-25.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("3.00000000"))
                .containsEntry("total_cost", new BigDecimal("30.00"))
                .containsEntry("projection_version", 3)
                .containsEntry("last_transaction_id", 4L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void replacementConflictsWhenAnEarlierSerializedSellMakesItsCandidateOversell() throws Exception {
        Long userId = seedBuy();
        String smallerReplacement = """
                {"quantity":"1.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00","reason":"Smaller broker correction"}
                """;

        RaceResult race = raceWithBlockedFirstReplay(
                () -> sell(userId, "first-sell", "2.00000000", "10.00000000"),
                () -> replace(userId, 1, "replacement-after-sell", smallerReplacement),
                "accounts");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(409);
        Snapshot snapshot = snapshotFromFreshConnection();
        assertThat(snapshot.commands()).isEmpty();
        assertThat(countTransactions(snapshot, "REVERSAL")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("0.00000000"))
                .containsEntry("total_cost", new BigDecimal("0.00"))
                .containsEntry("projection_version", 2)
                .containsEntry("last_transaction_id", 2L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void replacementAndPriceUpdateSerializeWithoutLosingTheNewReferenceValuation() throws Exception {
        Long userId = seedBuy();

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "replace", BUY_REPLACEMENT),
                () -> updatePrice(userId, "99.00"),
                "assets");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(200);
        Map<String, Object> asset = jdbcTemplate.queryForMap(
                "SELECT quantity, total_cost, current_price, market_value, projection_version FROM assets WHERE id = 1");
        assertThat((BigDecimal) asset.get("quantity")).isEqualByComparingTo("3.00000000");
        assertThat((BigDecimal) asset.get("total_cost")).isEqualByComparingTo("30.00");
        assertThat((BigDecimal) asset.get("current_price")).isEqualByComparingTo("99.00");
        assertThat((BigDecimal) asset.get("market_value")).isEqualByComparingTo("297.00");
        assertThat(asset).containsEntry("projection_version", 2);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void replacementAndAccountDeactivateSerializeAndPreserveTheInactiveState() throws Exception {
        Long userId = seedBuy();

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "replace", BUY_REPLACEMENT),
                () -> deactivateAccount(userId),
                "accounts");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM accounts WHERE id = 1", String.class)).isEqualTo("INACTIVE");
        assertOneReplacementCommand(1, 3, "-30.00", 2, 3L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void replacementAndInstrumentDeactivateSerializeAndPreserveTheInactiveState() throws Exception {
        Long userId = seedBuy();

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "replace", BUY_REPLACEMENT),
                () -> deactivateInstrumentInIndependentTransaction(userId),
                "investment_instruments");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM investment_instruments WHERE id = 1", String.class))
                .isEqualTo("INACTIVE");
        assertOneReplacementCommand(1, 3, "-30.00", 2, 3L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void replacementsForDifferentOriginalsOnOneAssetSerializeAndBothCommitOnce() throws Exception {
        Long userId = seedTwoBuys();
        String firstReplacement = BUY_REPLACEMENT;
        String secondReplacement = """
                {"quantity":"1.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00","reason":"Second broker correction"}
                """;

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "replace-first", firstReplacement),
                () -> replace(userId, 2, "replace-second", secondReplacement),
                "accounts");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(200);
        Snapshot snapshot = snapshotFromFreshConnection();
        assertThat(snapshot.commands()).hasSize(2);
        assertThat(countTransactions(snapshot, "REVERSAL")).isEqualTo(2);
        assertThat(snapshot.transactions().stream().filter(row -> row.get("correction_group_id") != null)).hasSize(4);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-40.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("4.00000000"))
                .containsEntry("total_cost", new BigDecimal("40.00"))
                .containsEntry("projection_version", 4)
                .containsEntry("last_transaction_id", 6L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    @Test
    void secondReplacementForAnotherOriginalConflictsWhenTheFirstMakesItInvalid() throws Exception {
        Long userId = seedTwoBuysAndSell();
        String firstReplacement = """
                {"quantity":"1.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00","reason":"First smaller correction"}
                """;
        String secondReplacement = """
                {"quantity":"1.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00","reason":"Second smaller correction"}
                """;

        RaceResult race = raceWithBlockedFirstReplay(
                () -> replace(userId, 1, "replace-first", firstReplacement),
                () -> replace(userId, 2, "replace-second", secondReplacement),
                "accounts");

        assertThat(race.first().status()).isEqualTo(200);
        assertThat(race.second().status()).isEqualTo(409);
        Snapshot snapshot = snapshotFromFreshConnection();
        assertThat(snapshot.commands()).hasSize(1);
        assertThat(countTransactions(snapshot, "REVERSAL")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("0.00000000"))
                .containsEntry("total_cost", new BigDecimal("0.00"))
                .containsEntry("projection_version", 4)
                .containsEntry("last_transaction_id", 5L);
        assertAssetMatchesFullReplay(userId, 1L);
        assertNoWaitingLocks();
    }

    private RaceResult raceWithBlockedFirstReplay(ThrowingSupplier first, ThrowingSupplier second,
                                                   String expectedWaitQueryFragment) throws Exception {
        ReplayGate gate = blockFirstReplay();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResult> firstResult = executor.submit(first::get);
            gate.awaitFirstReplay();
            Future<HttpResult> secondResult = executor.submit(second::get);
            awaitDatabaseLockWaitOn(expectedWaitQueryFragment);
            gate.releaseGate();
            return new RaceResult(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
        } finally {
            gate.releaseGate();
            shutdown(executor);
        }
    }

    private ReplayGate blockFirstReplay() {
        CountDownLatch firstReplayReached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blockFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (blockFirst.compareAndSet(true, false)) {
                firstReplayReached.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release the first replacement replay");
                }
            }
            return invocation.callRealMethod();
        }).when(replayEngine).replay(any());
        return new ReplayGate(firstReplayReached, release);
    }

    private void awaitDatabaseLockWaitOn(String queryFragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock'
                      AND query ILIKE ?
                    """, Integer.class, "%" + queryFragment + "%");
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Concurrent request did not wait for PostgreSQL resource " + queryFragment);
    }

    private void assertNoWaitingLocks() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
            if (waiting != null && waiting == 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("PostgreSQL lock wait remained after concurrent replacement requests completed");
    }

    private void assertOneReplacementCommand(int expectedCommands, int expectedFacts, String expectedBalance,
                                             int expectedProjectionVersion, long expectedLastTransactionId) throws SQLException {
        Snapshot snapshot = snapshotFromFreshConnection();
        assertThat(snapshot.commands()).hasSize(expectedCommands);
        assertThat(snapshot.transactions()).hasSize(expectedFacts);
        assertThat(countTransactions(snapshot, "REVERSAL")).isEqualTo(1);
        assertThat(snapshot.transactions().stream().filter(row -> row.get("correction_group_id") != null)).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo(expectedBalance);
        assertThat(jdbcTemplate.queryForMap("SELECT projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("projection_version", expectedProjectionVersion)
                .containsEntry("last_transaction_id", expectedLastTransactionId);
    }

    private void assertAssetMatchesFullReplay(Long userId, Long assetId) {
        List<InvestmentTransaction> transactions = transactionMapper.selectAllByUserIdAndAssetId(userId, assetId);
        InvestmentReplayResult replay = replayEngine.replay(transactions.stream().map(this::entry).toList());
        InvestmentPositionState position = replay.position();
        Asset asset = assetMapper.findByUserIdAndId(userId, assetId);
        BigDecimal avgCost = position.quantity().signum() == 0
                ? BigDecimal.ZERO.setScale(8)
                : position.totalCost().divide(position.quantity(), 8, RoundingMode.HALF_UP);

        assertThat(asset.getQuantity()).isEqualByComparingTo(position.quantity());
        assertThat(asset.getTotalCost()).isEqualByComparingTo(position.totalCost());
        assertThat(asset.getRealizedProfitLoss()).isEqualByComparingTo(position.cumulativeRealizedProfitLoss());
        assertThat(asset.getAvgCost()).isEqualByComparingTo(avgCost);
        assertThat(asset.getPositionStatus()).isEqualTo(position.quantity().signum() == 0 ? "CLOSED" : "OPEN");
    }

    private InvestmentReplayEntry entry(InvestmentTransaction transaction) {
        return new InvestmentReplayEntry(transaction.getId(), transaction.getTradeTime(),
                InvestmentTransactionStatus.valueOf(transaction.getStatus()), command(transaction),
                transaction.getOriginalTransactionId(), transaction.getReplayAnchorTransactionId(),
                transaction.getReplaySequence() == null ? (short) 0 : transaction.getReplaySequence());
    }

    private InvestmentLedgerCommand command(InvestmentTransaction transaction) {
        return switch (InvestmentTransactionType.valueOf(transaction.getTransactionType())) {
            case BUY -> InvestmentLedgerCommand.buy(transaction.getQuantity(), transaction.getUnitPrice(),
                    transaction.getFeeAmount(), transaction.getTaxAmount());
            case SELL -> InvestmentLedgerCommand.sell(transaction.getQuantity(), transaction.getUnitPrice(),
                    transaction.getFeeAmount(), transaction.getTaxAmount());
            case DIVIDEND -> InvestmentLedgerCommand.dividend(transaction.getGrossAmount(), transaction.getFeeAmount(),
                    transaction.getTaxAmount());
            case OPENING_POSITION -> InvestmentLedgerCommand.openingPosition(transaction.getQuantity(), transaction.getUnitPrice());
            case REVERSAL -> InvestmentLedgerCommand.reversal();
        };
    }

    private long countTransactions(Snapshot snapshot, String transactionType) {
        return snapshot.transactions().stream()
                .filter(row -> transactionType.equals(row.get("transaction_type")))
                .count();
    }

    private Snapshot snapshotFromFreshConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return new Snapshot(
                    rows(connection, "SELECT * FROM investment_transactions ORDER BY id"),
                    rows(connection, "SELECT * FROM investment_transaction_corrections ORDER BY id"),
                    rows(connection, "SELECT * FROM accounts ORDER BY id"),
                    rows(connection, "SELECT * FROM assets ORDER BY id"),
                    rows(connection, "SELECT * FROM investment_instruments ORDER BY id"));
        }
    }

    private List<Map<String, Object>> rows(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private Long seedBuy() throws Exception {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES ('replacement-concurrency', 'replacement-concurrency@example.com', 'hash')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'REPCON', 'Replacement Concurrency Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """, userId);
        assertThat(firstBuy(userId, "first-buy", "2.00000000", "10.00000000").status()).isEqualTo(200);
        return userId;
    }

    private Long seedTwoBuys() throws Exception {
        Long userId = seedBuy();
        assertThat(buy(userId, "second-buy", "2.00000000", "10.00000000").status()).isEqualTo(200);
        return userId;
    }

    private Long seedTwoBuysAndSell() throws Exception {
        Long userId = seedTwoBuys();
        assertThat(sell(userId, "third-sell", "3.00000000", "10.00000000").status()).isEqualTo(200);
        return userId;
    }

    private HttpResult firstBuy(Long userId, String key, String quantity, String unitPrice) throws Exception {
        return response(mockMvc.perform(post("/api/v1/investment/positions")
                .with(authentication(auth(userId)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + "\"accountId\":1,\"instrumentId\":1,\"quantity\":\"" + quantity
                        + "\",\"unitPrice\":\"" + unitPrice + "\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}")));
    }

    private HttpResult replace(Long userId, long transactionId, String key, String body) throws Exception {
        return response(mockMvc.perform(post("/api/v1/investment/transactions/" + transactionId + "/replacement")
                .with(authentication(auth(userId)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)));
    }

    private HttpResult reverse(Long userId, long transactionId, String key) throws Exception {
        return response(mockMvc.perform(post("/api/v1/investment/transactions/" + transactionId + "/reversal")
                .with(authentication(auth(userId)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Standalone correction\"}")));
    }

    private HttpResult buy(Long userId, String key, String quantity, String unitPrice) throws Exception {
        return response(mockMvc.perform(post("/api/v1/investment/positions/1/buy")
                .with(authentication(auth(userId)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":\"" + quantity + "\",\"unitPrice\":\"" + unitPrice
                        + "\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}")));
    }

    private HttpResult sell(Long userId, String key, String quantity, String unitPrice) throws Exception {
        return response(mockMvc.perform(post("/api/v1/investment/positions/1/sell")
                .with(authentication(auth(userId)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":\"" + quantity + "\",\"unitPrice\":\"" + unitPrice
                        + "\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}")));
    }

    private HttpResult dividend(Long userId, String key, String grossAmount) throws Exception {
        return response(mockMvc.perform(post("/api/v1/investment/positions/1/dividends")
                .with(authentication(auth(userId)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"grossAmount\":\"" + grossAmount + "\"}")));
    }

    private HttpResult updatePrice(Long userId, String price) throws Exception {
        return response(mockMvc.perform(put("/api/v1/assets/1/price")
                .with(authentication(auth(userId)))
                .param("price", price)));
    }

    private HttpResult deactivateAccount(Long userId) throws Exception {
        return response(mockMvc.perform(post("/api/v1/accounts/1/deactivate")
                .with(authentication(auth(userId)))));
    }

    private HttpResult deactivateInstrumentInIndependentTransaction(Long userId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("""
                    SELECT id FROM investment_instruments
                    WHERE user_id = ? AND id = 1
                    FOR UPDATE
                    """);
                 PreparedStatement update = connection.prepareStatement("""
                    UPDATE investment_instruments SET status = 'INACTIVE'
                    WHERE user_id = ? AND id = 1
                    """)) {
                lock.setLong(1, userId);
                lock.executeQuery();
                update.setLong(1, userId);
                int updated = update.executeUpdate();
                connection.commit();
                return new HttpResult(updated == 1 ? 200 : 404, "");
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private HttpResult response(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        var response = actions.andReturn().getResponse();
        return new HttpResult(response.getStatus(), response.getContentAsString());
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        HttpResult get() throws Exception;
    }

    private record HttpResult(int status, String body) {
    }

    private record RaceResult(HttpResult first, HttpResult second) {
    }

    private record ReplayGate(CountDownLatch firstReplayReached, CountDownLatch release) {
        void awaitFirstReplay() throws InterruptedException {
            assertThat(firstReplayReached.await(5, TimeUnit.SECONDS)).isTrue();
        }

        void releaseGate() {
            release.countDown();
        }
    }

    private record Snapshot(List<Map<String, Object>> transactions,
                            List<Map<String, Object>> commands,
                            List<Map<String, Object>> accounts,
                            List<Map<String, Object>> assets,
                            List<Map<String, Object>> instruments) {
    }
}
