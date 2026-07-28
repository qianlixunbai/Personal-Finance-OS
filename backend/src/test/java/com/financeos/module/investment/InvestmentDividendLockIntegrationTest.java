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
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
class InvestmentDividendLockIntegrationTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DataSource dataSource;
    @SpyBean private GlobalExceptionHandler globalExceptionHandler;

    @Test void accountLockTimeoutReturnsConflictWithoutAnyWrite() throws Exception { assertTimeout("accounts", "id"); }
    @Test void instrumentLockTimeoutReturnsConflictWithoutAnyWrite() throws Exception { assertTimeout("investment_instruments", "id"); }
    @Test void assetLockTimeoutReturnsConflictWithoutAnyWrite() throws Exception { assertTimeout("assets", "id"); }
    @Test void accountLockReleaseBeforeTimeoutAllowsOneCompleteDividend() throws Exception { assertRelease("accounts", "id"); }
    @Test void instrumentLockReleaseBeforeTimeoutAllowsOneCompleteDividend() throws Exception { assertRelease("investment_instruments", "id"); }
    @Test void assetLockReleaseBeforeTimeoutAllowsOneCompleteDividend() throws Exception { assertRelease("assets", "id"); }

    @Test
    void dividendIsPostgresDeadlockVictimAndRollsBackWhileReverseLockTransactionSurvives() throws Exception {
        Long userId = seed();
        try (Connection auxiliary = dataSource.getConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            auxiliary.setAutoCommit(false);
            try (PreparedStatement deadlockTimeout = auxiliary.prepareStatement("SET LOCAL deadlock_timeout = '10s'");
                 PreparedStatement lockAsset = auxiliary.prepareStatement("SELECT id FROM assets WHERE id = 1 FOR UPDATE");
                 PreparedStatement waitForAccount = auxiliary.prepareStatement("SELECT id FROM accounts WHERE id = 1 FOR UPDATE")) {
                deadlockTimeout.execute();
                lockAsset.executeQuery();
                Future<Integer> victim = executor.submit(() -> dividend(userId));
                awaitDatabaseLockWait();

                waitForAccount.executeQuery();
                auxiliary.commit();

                assertThat(victim.get(10, TimeUnit.SECONDS)).isEqualTo(409);
                verify(globalExceptionHandler).handleDataAccessException(argThat(this::hasDeadlockSqlState));
            } finally {
                if (!auxiliary.getAutoCommit()) {
                    auxiliary.rollback();
                }
            }
        }
        assertUnchanged();
    }

    private void assertTimeout(String table, String idColumn) throws Exception {
        Long userId = seed();
        CountDownLatch locked = new CountDownLatch(1);
        try (Connection connection = dataSource.getConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + idColumn + " FROM " + table + " WHERE id = 1 FOR UPDATE")) {
                statement.executeQuery();
                locked.countDown();
                Future<Integer> result = executor.submit(() -> dividend(userId));
                assertThat(locked.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            } finally { connection.rollback(); }
        }
        assertUnchanged();
    }

    private void assertRelease(String table, String idColumn) throws Exception {
        Long userId = seed();
        try (Connection connection = dataSource.getConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + idColumn + " FROM " + table + " WHERE id = 1 FOR UPDATE")) {
                statement.executeQuery();
                Future<Integer> result = executor.submit(() -> dividend(userId));
                awaitDatabaseLockWait();
                connection.rollback();
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-15.00");
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(2);
    }

    private void awaitDatabaseLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.yield();
        }
        throw new AssertionError("DIVIDEND request did not enter a PostgreSQL lock wait");
    }

    private int dividend(Long userId) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "timeout").contentType(MediaType.APPLICATION_JSON).content("{\"grossAmount\":\"5.00\"}"))
                .andReturn().getResponse().getStatus();
    }

    private void assertUnchanged() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-20.00");
        var asset = jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, position_status, projection_version, last_transaction_id, current_price, market_value, account_id, instrument_id FROM assets WHERE id = 1");
        assertThat(asset).containsEntry("quantity", new BigDecimal("2.00000000")).containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00")).containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("position_status", "OPEN").containsEntry("projection_version", 1).containsEntry("last_transaction_id", 1L)
                .containsEntry("account_id", 1L).containsEntry("instrument_id", 1L);
        assertThat((BigDecimal) asset.get("current_price")).isEqualByComparingTo("99.00");
        assertThat((BigDecimal) asset.get("market_value")).isEqualByComparingTo("198.00");
    }

    private boolean hasDeadlockSqlState(DataAccessException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sqlException && "40P01".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private Long seed() throws Exception {
        Long userId=jdbcTemplate.queryForObject("INSERT INTO users (username,email,password_hash) VALUES ('lock-user','lock@example.com','hash') RETURNING id",Long.class);
        jdbcTemplate.update("INSERT INTO accounts (user_id,name,type,currency,balance) VALUES (?,'Brokerage','BROKERAGE','CNY',0)",userId);
        jdbcTemplate.update("INSERT INTO investment_instruments (user_id,symbol,name,market,asset_class,quote_currency,status) VALUES (?,'LOCK','Lock Fund','FUND','FUND','CNY','ACTIVE')",userId);
        assertThat(mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId))).header("Idempotency-Key","buy").contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":1,\"instrumentId\":1,\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        jdbcTemplate.update("UPDATE assets SET current_price = 99.00, market_value = 198.00 WHERE id = 1");
        return userId;
    }
    private UsernamePasswordAuthenticationToken auth(Long userId){return new UsernamePasswordAuthenticationToken(userId,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));}
}
