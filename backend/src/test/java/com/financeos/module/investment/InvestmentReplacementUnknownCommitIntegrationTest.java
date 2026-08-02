package com.financeos.module.investment;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.investment.command.InvestmentCommandService;
import org.junit.jupiter.api.AfterEach;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class InvestmentReplacementUnknownCommitIntegrationTest extends PostgresIntegrationTest {

    private static final String TRIGGER_NAME = "injected_replacement_unknown_commit_rollback";
    private static final String FUNCTION_NAME = TRIGGER_NAME + "_fn";
    private static final String REPLACEMENT_BODY = """
            {"quantity":"3.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00","reason":"Broker correction"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @SpyBean
    private InvestmentCommandService commandService;

    @AfterEach
    void dropInjectedRollbackTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + TRIGGER_NAME + " ON investment_transaction_corrections");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + FUNCTION_NAME + "()");
    }

    @Test
    void retryAfterUnknownResultFollowingCommitReturnsTheStoredImmutableReceipt() throws Exception {
        Long userId = seedBuy();
        AtomicBoolean hideCommittedResultOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object response = invocation.callRealMethod();
            if (hideCommittedResultOnce.compareAndSet(true, false)) {
                throw new SimulatedUnknownCommitResult();
            }
            return response;
        }).when(commandService).replace(anyLong(), anyLong(), anyString(), any());

        HttpResult unknown = replace(userId, "unknown-committed", REPLACEMENT_BODY);

        assertThat(unknown.status()).isEqualTo(500);
        assertThat(unknown.body()).doesNotContain("idempotentReplay", "Broker correction", "SQL", "stack trace");
        Snapshot committed = snapshotFromFreshConnection();
        assertOneCompletedReplacement(committed, "3.00000000", "30.00", "-30.00");

        assertThat(dividend(userId, "later-dividend", "5.00").status()).isEqualTo(200);
        HttpResult recovered = replace(userId, "unknown-committed", REPLACEMENT_BODY);

        assertThat(recovered.status()).isEqualTo(200);
        assertThat(recovered.body()).contains(
                "\"idempotentReplay\":true",
                "\"balanceAfter\":\"-30.00\"",
                "\"projectionVersion\":2",
                "\"lastTransactionId\":3");
        Snapshot afterLaterCommand = snapshotFromFreshConnection();
        assertThat(afterLaterCommand.commands()).isEqualTo(committed.commands());
        assertThat(afterLaterCommand.transactions()).hasSize(4);
        assertThat(afterLaterCommand.transactions().stream().filter(row -> "DIVIDEND".equals(row.get("transaction_type"))))
                .hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-25.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("3.00000000"))
                .containsEntry("total_cost", new BigDecimal("30.00"))
                .containsEntry("projection_version", 3)
                .containsEntry("last_transaction_id", 4L);
        assertNoWaitingLocks();
    }

    @Test
    void retryAfterUnknownResultBeforeCommitExecutesTheReplacementExactlyOnce() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshotFromFreshConnection();
        hideTheFirstRolledBackResultAsUnknown();
        createRollbackTrigger("unknown-uncommitted");

        HttpResult unknown = replace(userId, "unknown-uncommitted", REPLACEMENT_BODY);

        assertThat(unknown.status()).isEqualTo(500);
        assertThat(snapshotFromFreshConnection()).isEqualTo(before);
        dropInjectedRollbackTrigger();

        HttpResult retry = replace(userId, "unknown-uncommitted", REPLACEMENT_BODY);

        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body()).contains("\"idempotentReplay\":false");
        assertOneCompletedReplacement(snapshotFromFreshConnection(), "3.00000000", "30.00", "-30.00");
        assertNoWaitingLocks();
    }

    @Test
    void differentHashAfterAnUncommittedUnknownResultUsesTheRealEmptyStateInsteadOfRecovering() throws Exception {
        Long userId = seedBuy();
        hideTheFirstRolledBackResultAsUnknown();
        createRollbackTrigger("unknown-different-hash");

        assertThat(replace(userId, "unknown-different-hash", REPLACEMENT_BODY).status()).isEqualTo(500);
        Snapshot afterRollback = snapshotFromFreshConnection();
        assertThat(afterRollback.commands()).isEmpty();
        assertThat(afterRollback.transactions()).hasSize(1);
        dropInjectedRollbackTrigger();

        String changed = """
                {"quantity":"4.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00","reason":"Changed after rollback"}
                """;
        HttpResult truthfulRetry = replace(userId, "unknown-different-hash", changed);

        assertThat(truthfulRetry.status()).isEqualTo(200);
        assertThat(truthfulRetry.body()).contains("\"idempotentReplay\":false");
        Snapshot committed = snapshotFromFreshConnection();
        assertOneCompletedReplacement(committed, "4.00000000", "40.00", "-40.00");
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM assets WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("4.00000000");
        assertThat(replace(userId, "unknown-different-hash", REPLACEMENT_BODY).status()).isEqualTo(409);
        assertNoWaitingLocks();
    }

    private void hideTheFirstRolledBackResultAsUnknown() {
        AtomicBoolean hideFailureOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (RuntimeException failure) {
                if (hideFailureOnce.compareAndSet(true, false)) {
                    throw new SimulatedUnknownCommitResult();
                }
                throw failure;
            }
        }).when(commandService).replace(anyLong(), anyLong(), anyString(), any());
    }

    private void createRollbackTrigger(String idempotencyKey) {
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.idempotency_key = '%s' THEN
                        RAISE EXCEPTION 'injected unknown commit rollback';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(FUNCTION_NAME, idempotencyKey));
        jdbcTemplate.execute("CREATE TRIGGER " + TRIGGER_NAME
                + " BEFORE INSERT ON investment_transaction_corrections"
                + " FOR EACH ROW EXECUTE FUNCTION " + FUNCTION_NAME + "()");
    }

    private void assertOneCompletedReplacement(Snapshot snapshot, String expectedQuantity, String expectedTotalCost,
                                               String expectedBalance) {
        assertThat(snapshot.commands()).hasSize(1);
        assertThat(snapshot.transactions()).hasSize(3);
        assertThat(snapshot.transactions().stream().filter(row -> "REVERSAL".equals(row.get("transaction_type"))))
                .hasSize(1);
        assertThat(snapshot.transactions().stream().filter(row -> row.get("correction_group_id") != null)).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo(expectedBalance);
        Map<String, Object> command = snapshot.commands().getFirst();
        Object replacementId = command.get("last_transaction_id");
        Map<String, Object> asset = jdbcTemplate.queryForMap(
                "SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1");
        assertThat((BigDecimal) asset.get("quantity")).isEqualByComparingTo(expectedQuantity);
        assertThat((BigDecimal) asset.get("total_cost")).isEqualByComparingTo(expectedTotalCost);
        assertThat(asset).containsEntry("projection_version", 2).containsEntry("last_transaction_id", replacementId);
        assertThat(snapshot.transactions().stream()
                .filter(row -> replacementId.equals(row.get("id")))
                .findFirst().orElseThrow())
                .containsEntry("transaction_type", "BUY");
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
        throw new AssertionError("PostgreSQL lock wait remained after unknown commit recovery");
    }

    private Snapshot snapshotFromFreshConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            return new Snapshot(
                    rows(connection, "SELECT * FROM investment_transactions ORDER BY id"),
                    rows(connection, "SELECT * FROM investment_transaction_corrections ORDER BY id"),
                    rows(connection, "SELECT * FROM accounts ORDER BY id"),
                    rows(connection, "SELECT * FROM assets ORDER BY id"));
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

    private Long seedBuy() throws Exception {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES ('replacement-unknown', 'replacement-unknown@example.com', 'hash')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'REPUNKNOWN', 'Replacement Unknown Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """, userId);
        assertThat(firstBuy(userId).status()).isEqualTo(200);
        return userId;
    }

    private HttpResult firstBuy(Long userId) throws Exception {
        return response(mockMvc.perform(post("/api/v1/investment/positions")
                .with(authentication(auth(userId)))
                .header("Idempotency-Key", "first-buy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId":1,"instrumentId":1,"quantity":"2.00000000",
                         "unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00"}
                        """)));
    }

    private HttpResult replace(Long userId, String key, String body) throws Exception {
        return response(mockMvc.perform(post("/api/v1/investment/transactions/1/replacement")
                .with(authentication(auth(userId)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)));
    }

    private HttpResult dividend(Long userId, String key, String grossAmount) throws Exception {
        return response(mockMvc.perform(post("/api/v1/investment/positions/1/dividends")
                .with(authentication(auth(userId)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"grossAmount\":\"" + grossAmount + "\"}")));
    }

    private HttpResult response(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        var response = actions.andReturn().getResponse();
        return new HttpResult(response.getStatus(), response.getContentAsString());
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private record HttpResult(int status, String body) {
    }

    private record Snapshot(List<Map<String, Object>> transactions,
                            List<Map<String, Object>> commands,
                            List<Map<String, Object>> accounts,
                            List<Map<String, Object>> assets) {
    }

    private static final class SimulatedUnknownCommitResult extends RuntimeException {
    }
}
