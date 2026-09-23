package com.financeos.module.investment;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.entity.InvestmentTransactionCorrection;
import com.financeos.module.investment.ledger.InvestmentReplayEngine;
import com.financeos.module.investment.ledger.InvestmentReplayResult;
import com.financeos.module.investment.ledger.InvestmentReplayTrace;
import com.financeos.module.investment.mapper.InvestmentTransactionCorrectionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class InvestmentReplacementFailureIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @SpyBean
    private InvestmentReplayEngine replayEngine;

    @SpyBean
    private AssetMapper assetMapper;

    @SpyBean
    private InvestmentTransactionCorrectionMapper correctionMapper;

    private final List<InjectedTrigger> injectedTriggers = new ArrayList<>();

    @AfterEach
    void removeInjectedDatabaseObjects() {
        for (int index = injectedTriggers.size() - 1; index >= 0; index--) {
            InjectedTrigger injected = injectedTriggers.get(index);
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + injected.triggerName() + " ON " + injected.tableName());
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + injected.functionName() + "()");
        }
        jdbcTemplate.execute("DROP INDEX IF EXISTS ux_injected_replacement_command_unknown_unique");
    }

    @Test
    void consistencyCheckerRejectsProjectionThatOverwritesReferencePricesAndRollsBack() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_price_corruption", "assets", "BEFORE UPDATE", """
                NEW.current_price := 0.00;
                NEW.market_value := 0.00;
                RETURN NEW;
                """);

        MockHttpServletResponse response = replace(userId, "price-corruption");

        assertSafeInternalFailure(response);
        assertUnchanged(before);
    }



    @Test
    void secondReplayFailureRollsBackFactsBalanceProjectionAndCommand() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doCallRealMethod().doThrow(new IllegalStateException("injected second replacement replay failure"))
                .when(replayEngine).replay(any());

        assertSafeInternalFailure(replace(userId, "second-replay-failure"));
        assertUnchanged(before);
    }









    @Test
    void unknownCommandUniqueConstraintIsNotMistakenForIdempotentRecovery() throws Exception {
        Long userId = seedTwoIndependentBuys();
        assertThat(replaceStatus(userId, 1, "first-command", replacementBody("First command"))).isEqualTo(200);
        Snapshot before = snapshot();
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX ux_injected_replacement_command_unknown_unique
                ON investment_transaction_corrections (correction_reason)
                """);

        MockHttpServletResponse response = replace(userId, 2, "unknown-command-unique", replacementBody("First command"));

        assertSafeInternalFailure(response);
        assertThat(response.getContentAsString()).doesNotContain("idempotentReplay", "unknown-command-unique", "23505");
        assertUnchanged(before);
    }

    private void createTrigger(String triggerName, String tableName, String event, String body) {
        String functionName = triggerName + "_fn";
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                %s
                END;
                $$
                """.formatted(functionName, body));
        jdbcTemplate.execute("CREATE TRIGGER " + triggerName + " " + event + " ON " + tableName
                + " FOR EACH ROW EXECUTE FUNCTION " + functionName + "()");
        injectedTriggers.add(new InjectedTrigger(tableName, triggerName, functionName));
    }

    private void assertSafeInternalFailure(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(500);
        assertSafeFailureBody(response);
    }

    private void assertSafeDatabaseFailure(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(503);
        assertSafeFailureBody(response);
    }

    private void assertSafeFailureBody(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentAsString()).doesNotContain(
                "99.00", "198.00", "Broker correction", "SQL", "SQLSTATE", "trigger", "injected",
                "AssetMapper", "AccountMapper", "InvestmentTransactionCorrectionMapper", "stack trace");
    }

    private void assertUnchanged(Snapshot before) throws Exception {
        assertThat(snapshot()).isEqualTo(before);
        assertNoWaitingLocks();
    }

    private Snapshot snapshot() throws SQLException {
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

    private void assertNoWaitingLocks() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
            if (waiting != null && waiting == 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("PostgreSQL lock wait remained after replacement failure rollback");
    }

    private Long seedBuy() throws Exception {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES ('replacement-failure', 'replacement-failure@example.com', 'hash')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'REPFAIL', 'Replacement Failure Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """, userId);
        int status = mockMvc.perform(post("/api/v1/investment/positions")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", "first-buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":1,"instrumentId":1,"quantity":"2.00000000",
                                 "unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00"}
                                """))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        jdbcTemplate.update("UPDATE assets SET current_price = 99.00, market_value = 198.00 WHERE id = 1");
        return userId;
    }

    private Long seedTwoIndependentBuys() throws Exception {
        Long userId = seedBuy();
        jdbcTemplate.update("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Second Brokerage', 'BROKERAGE', 'CNY', 0)
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'REPFAIL2', 'Second Replacement Failure Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """, userId);
        int status = mockMvc.perform(post("/api/v1/investment/positions")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", "second-first-buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":2,"instrumentId":2,"quantity":"2.00000000",
                                 "unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00"}
                                """))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        return userId;
    }

    private MockHttpServletResponse replace(Long userId, String key) throws Exception {
        return replace(userId, 1, key, replacementBody("Broker correction"));
    }

    private int replaceStatus(Long userId, long transactionId, String key, String body) throws Exception {
        return replace(userId, transactionId, key, body).getStatus();
    }

    private MockHttpServletResponse replace(Long userId, long transactionId, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/transactions/" + transactionId + "/replacement")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    private String replacementBody(String reason) {
        return "{\"quantity\":\"3.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\",\"reason\":\"" + reason + "\"}";
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private record Snapshot(List<Map<String, Object>> transactions,
                            List<Map<String, Object>> commands,
                            List<Map<String, Object>> accounts,
                            List<Map<String, Object>> assets) {
    }

    private record InjectedTrigger(String tableName, String triggerName, String functionName) {
    }
}
