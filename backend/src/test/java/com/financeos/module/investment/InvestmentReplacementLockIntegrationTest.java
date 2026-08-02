package com.financeos.module.investment;

import com.financeos.common.GlobalExceptionHandler;
import com.financeos.integration.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class InvestmentReplacementLockIntegrationTest extends PostgresIntegrationTest {

    private static final String REPLACEMENT_BODY = """
            {"quantity":"3.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00","reason":"Broker correction"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @SpyBean
    private GlobalExceptionHandler globalExceptionHandler;

    @Test
    void originalFactLockTimeoutReturnsConflictRollsBackAndAllowsSameKeyRetry() throws Exception {
        assertTimeoutRollsBackAndRetries(LockTarget.ORIGINAL);
    }

    @Test
    void accountLockTimeoutReturnsConflictRollsBackAndAllowsSameKeyRetry() throws Exception {
        assertTimeoutRollsBackAndRetries(LockTarget.ACCOUNT);
    }

    @Test
    void instrumentLockTimeoutReturnsConflictRollsBackAndAllowsSameKeyRetry() throws Exception {
        assertTimeoutRollsBackAndRetries(LockTarget.INSTRUMENT);
    }

    @Test
    void assetLockTimeoutReturnsConflictRollsBackAndAllowsSameKeyRetry() throws Exception {
        assertTimeoutRollsBackAndRetries(LockTarget.ASSET);
    }

    @Test
    void existingCorrectionCommandLockTimeoutReturnsConflictWithoutChangingCompletedHistory() throws Exception {
        Long userId = seedBuy();
        String key = "existing-correction-command-lock";
        assertThat(replace(userId, key)).isEqualTo(200);
        Snapshot before = snapshotFromFreshConnection();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("""
                    SELECT id FROM investment_transaction_corrections
                    WHERE user_id = ? AND idempotency_key = ?
                    FOR UPDATE
                    """)) {
                lock.setLong(1, userId);
                lock.setString(2, key);
                lock.executeQuery();
                int blockerPid = backendPid(connection);

                Future<Integer> blockedReplay = executor.submit(() -> replace(userId, key));
                awaitDatabaseLockWaitOn(LockTarget.CORRECTION_COMMAND.queryFragment(), blockerPid);

                assertThat(blockedReplay.get(10, TimeUnit.SECONDS)).isEqualTo(409);
                verify(globalExceptionHandler).handleDataAccessException(argThat(this::hasLockTimeoutSqlState));
                assertThat(snapshotFromFreshConnection()).isEqualTo(before);
            } finally {
                connection.rollback();
            }
        } finally {
            shutdown(executor);
        }

        assertNoWaitingLocks();
        assertThat(replace(userId, key)).isEqualTo(200);
        assertThat(snapshotFromFreshConnection()).isEqualTo(before);
    }

    @Test
    void replacementIsPostgresDeadlockVictimRollsBackAndAllowsSameKeyRetry() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshotFromFreshConnection();
        String key = "replacement-deadlock";

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection auxiliary = dataSource.getConnection()) {
            auxiliary.setAutoCommit(false);
            try (PreparedStatement deadlockTimeout = auxiliary.prepareStatement("SET LOCAL deadlock_timeout = '10s'");
                 PreparedStatement lockAsset = auxiliary.prepareStatement("SELECT id FROM assets WHERE id = 1 FOR UPDATE");
                 PreparedStatement waitForAccount = auxiliary.prepareStatement("SELECT id FROM accounts WHERE id = 1 FOR UPDATE")) {
                deadlockTimeout.execute();
                lockAsset.executeQuery();
                int blockerPid = backendPid(auxiliary);

                Future<Integer> victim = executor.submit(() -> replace(userId, key));
                awaitDatabaseLockWaitOn(LockTarget.ASSET.queryFragment(), blockerPid);
                waitForAccount.executeQuery();
                auxiliary.commit();

                assertThat(victim.get(10, TimeUnit.SECONDS)).isEqualTo(409);
                verify(globalExceptionHandler).handleDataAccessException(argThat(this::hasDeadlockSqlState));
            } finally {
                if (!auxiliary.getAutoCommit()) {
                    auxiliary.rollback();
                }
            }
        } finally {
            shutdown(executor);
        }

        assertThat(snapshotFromFreshConnection()).isEqualTo(before);
        assertNoWaitingLocks();
        assertThat(replace(userId, key)).isEqualTo(200);
        assertSuccessfulReplacement();
        assertNoWaitingLocks();
    }

    private void assertTimeoutRollsBackAndRetries(LockTarget target) throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshotFromFreshConnection();
        String key = "replacement-" + target.name().toLowerCase() + "-timeout";

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(target.lockSql())) {
                lock.executeQuery();
                int blockerPid = backendPid(connection);

                Future<Integer> result = executor.submit(() -> replace(userId, key));
                awaitDatabaseLockWaitOn(target.queryFragment(), blockerPid);

                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(409);
                verify(globalExceptionHandler).handleDataAccessException(argThat(this::hasLockTimeoutSqlState));
                assertThat(snapshotFromFreshConnection()).isEqualTo(before);
            } finally {
                connection.rollback();
            }
        } finally {
            shutdown(executor);
        }

        assertNoWaitingLocks();
        assertThat(replace(userId, key)).isEqualTo(200);
        assertSuccessfulReplacement();
        assertNoWaitingLocks();
    }

    private void awaitDatabaseLockWaitOn(String queryFragment, int blockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock'
                      AND query ILIKE ?
                      AND ?::int = ANY(pg_blocking_pids(pid))
                    """, Integer.class, "%" + queryFragment + "%", blockerPid);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Replacement request did not wait for PostgreSQL resource " + queryFragment);
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
        throw new AssertionError("PostgreSQL lock wait remained after the replacement request completed");
    }

    private void assertSuccessfulReplacement() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transaction_corrections", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'REVERSAL'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE correction_group_id IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-30.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("3.00000000"))
                .containsEntry("total_cost", new BigDecimal("30.00"))
                .containsEntry("projection_version", 2)
                .containsEntry("last_transaction_id", 3L);
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

    private int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private boolean hasLockTimeoutSqlState(DataAccessException exception) {
        return hasSqlState(exception, "55P03");
    }

    private boolean hasDeadlockSqlState(DataAccessException exception) {
        return hasSqlState(exception, "40P01");
    }

    private boolean hasSqlState(Throwable throwable, String expectedSqlState) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sqlException
                    && expectedSqlState.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private Long seedBuy() throws Exception {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES ('replacement-lock', 'replacement-lock@example.com', 'hash')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'REPLOCK', 'Replacement Lock Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """, userId);
        assertThat(mockMvc.perform(post("/api/v1/investment/positions")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", "first-buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":1,"instrumentId":1,"quantity":"2.00000000",
                                 "unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00"}
                                """))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        jdbcTemplate.update("UPDATE assets SET current_price = 99.00, market_value = 198.00 WHERE id = 1");
        return userId;
    }

    private int replace(Long userId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/transactions/1/replacement")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REPLACEMENT_BODY))
                .andReturn().getResponse().getStatus();
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private enum LockTarget {
        ORIGINAL("SELECT id FROM investment_transactions WHERE id = 1 FOR UPDATE", "investment_transactions"),
        ACCOUNT("SELECT id FROM accounts WHERE id = 1 FOR UPDATE", "accounts"),
        INSTRUMENT("SELECT id FROM investment_instruments WHERE id = 1 FOR UPDATE", "investment_instruments"),
        ASSET("SELECT id FROM assets WHERE id = 1 FOR UPDATE", "assets"),
        CORRECTION_COMMAND("", "investment_transaction_corrections");

        private final String lockSql;
        private final String queryFragment;

        LockTarget(String lockSql, String queryFragment) {
            this.lockSql = lockSql;
            this.queryFragment = queryFragment;
        }

        String lockSql() {
            return lockSql;
        }

        String queryFragment() {
            return queryFragment;
        }
    }

    private record Snapshot(List<Map<String, Object>> transactions,
                            List<Map<String, Object>> commands,
                            List<Map<String, Object>> accounts,
                            List<Map<String, Object>> assets,
                            List<Map<String, Object>> instruments) {
    }
}
