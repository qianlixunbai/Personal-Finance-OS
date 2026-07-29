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
class InvestmentReversalLockIntegrationTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DataSource dataSource;
    @SpyBean private GlobalExceptionHandler globalExceptionHandler;

    @Test void originalLockTimeoutReturnsConflictWithoutAnyWrite() throws Exception { assertTimeout("investment_transactions"); }
    @Test void accountLockTimeoutReturnsConflictWithoutAnyWrite() throws Exception { assertTimeout("accounts"); }
    @Test void instrumentLockTimeoutReturnsConflictWithoutAnyWrite() throws Exception { assertTimeout("investment_instruments"); }
    @Test void assetLockTimeoutReturnsConflictWithoutAnyWrite() throws Exception { assertTimeout("assets"); }
    @Test void originalLockReleaseBeforeTimeoutAllowsOneReversal() throws Exception { assertRelease("investment_transactions"); }
    @Test void accountLockReleaseBeforeTimeoutAllowsOneReversal() throws Exception { assertRelease("accounts"); }
    @Test void instrumentLockReleaseBeforeTimeoutAllowsOneReversal() throws Exception { assertRelease("investment_instruments"); }
    @Test void assetLockReleaseBeforeTimeoutAllowsOneReversal() throws Exception { assertRelease("assets"); }

    @Test
    void reversalIsPostgresDeadlockVictimAndRollsBackWhileAuxiliaryTransactionSurvives() throws Exception {
        Long userId = seed();
        try (Connection auxiliary = dataSource.getConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            auxiliary.setAutoCommit(false);
            try (PreparedStatement deadlockTimeout = auxiliary.prepareStatement("SET LOCAL deadlock_timeout = '10s'");
                 PreparedStatement lockAsset = auxiliary.prepareStatement("SELECT id FROM assets WHERE id = 1 FOR UPDATE");
                 PreparedStatement waitForAccount = auxiliary.prepareStatement("SELECT id FROM accounts WHERE id = 1 FOR UPDATE")) {
                deadlockTimeout.execute();
                lockAsset.executeQuery();
                Future<Integer> victim = executor.submit(() -> reverse(userId));
                awaitDatabaseLockWait();
                waitForAccount.executeQuery();
                auxiliary.commit();

                assertThat(victim.get(10, TimeUnit.SECONDS)).isEqualTo(409);
                verify(globalExceptionHandler).handleDataAccessException(argThat(this::hasDeadlockSqlState));
            } finally {
                if (!auxiliary.getAutoCommit()) auxiliary.rollback();
            }
        }
        assertUnchanged();
    }

    private void assertTimeout(String table) throws Exception {
        Long userId = seed();
        try (Connection connection = dataSource.getConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + table + " WHERE id = 1 FOR UPDATE")) {
                statement.executeQuery();
                Future<Integer> result = executor.submit(() -> reverse(userId));
                awaitDatabaseLockWait();
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            } finally {
                connection.rollback();
            }
        }
        assertUnchanged();
    }

    private void assertRelease(String table) throws Exception {
        Long userId = seed();
        try (Connection connection = dataSource.getConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + table + " WHERE id = 1 FOR UPDATE")) {
                statement.executeQuery();
                Future<Integer> result = executor.submit(() -> reverse(userId));
                awaitDatabaseLockWait();
                connection.rollback();
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
        assertReversed();
    }

    private void awaitDatabaseLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.yield();
        }
        throw new AssertionError("REVERSAL request did not enter a PostgreSQL lock wait");
    }

    private int reverse(Long userId) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/transactions/1/reversal").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "lock-reversal").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Broker correction\"}"))
                .andReturn().getResponse().getStatus();
    }

    private void assertUnchanged() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'REVERSAL'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-20.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, position_status, projection_version, last_transaction_id, current_price, market_value FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000")).containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("position_status", "OPEN").containsEntry("projection_version", 1).containsEntry("last_transaction_id", 1L);
    }

    private void assertReversed() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'REVERSAL'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, position_status, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("0.00000000")).containsEntry("total_cost", new BigDecimal("0.00"))
                .containsEntry("position_status", "CLOSED").containsEntry("projection_version", 2).containsEntry("last_transaction_id", 2L);
    }

    private boolean hasDeadlockSqlState(DataAccessException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sqlException && "40P01".equals(sqlException.getSQLState())) return true;
        }
        return false;
    }

    private Long seed() throws Exception {
        Long userId = jdbcTemplate.queryForObject("INSERT INTO users (username,email,password_hash) VALUES ('reversal-lock','reversal-lock@example.com','hash') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO accounts (user_id,name,type,currency,balance) VALUES (?,'Brokerage','BROKERAGE','CNY',0)", userId);
        jdbcTemplate.update("INSERT INTO investment_instruments (user_id,symbol,name,market,asset_class,quote_currency,status) VALUES (?,'REVLOCK','Reversal Lock Fund','FUND','FUND','CNY','ACTIVE')", userId);
        assertThat(mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId))).header("Idempotency-Key","buy").contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":1,\"instrumentId\":1,\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        jdbcTemplate.update("UPDATE assets SET current_price = 99.00, market_value = 198.00 WHERE id = 1");
        return userId;
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
