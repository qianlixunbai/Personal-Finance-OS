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
    void consistencyCheckerRejectsTamperedGroupedReversalFactAndRollsBack() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_reversal_note_corruption", "investment_transactions", "BEFORE INSERT", """
                IF NEW.transaction_type = 'REVERSAL' THEN
                    NEW.note := 'tampered grouped reversal';
                END IF;
                RETURN NEW;
                """);

        assertSafeInternalFailure(replace(userId, "reversal-note-corruption"));
        assertUnchanged(before);
    }

    @Test
    void consistencyCheckerRejectsTamperedReplacementFactMetadataAndRollsBack() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_fact_note_corruption", "investment_transactions", "BEFORE INSERT", """
                IF NEW.source = 'CORRECTION' AND NEW.transaction_type <> 'REVERSAL' THEN
                    NEW.note := 'tampered replacement note';
                END IF;
                RETURN NEW;
                """);

        assertSafeInternalFailure(replace(userId, "replacement-note-corruption"));
        assertUnchanged(before);
    }

    @Test
    void consistencyCheckerRejectsTamperedAccountBalanceAndRollsBack() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_balance_corruption", "accounts", "BEFORE UPDATE OF balance", """
                IF NEW.balance IS DISTINCT FROM OLD.balance THEN
                    NEW.balance := NEW.balance + 1.00;
                END IF;
                RETURN NEW;
                """);

        assertSafeInternalFailure(replace(userId, "account-balance-corruption"));
        assertUnchanged(before);
    }

    @Test
    void consistencyCheckerRejectsTamperedAssetPositionAndRollsBack() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_position_corruption", "assets", "BEFORE UPDATE", """
                NEW.quantity := NEW.quantity + 1.00000000;
                RETURN NEW;
                """);

        assertSafeInternalFailure(replace(userId, "asset-position-corruption"));
        assertUnchanged(before);
    }

    @Test
    void consistencyCheckerRejectsTamperedProjectionVersionAndLastTransactionIdAndRollsBack() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_projection_corruption", "assets", "BEFORE UPDATE", """
                NEW.projection_version := OLD.projection_version + 2;
                NEW.last_transaction_id := OLD.last_transaction_id;
                RETURN NEW;
                """);

        assertSafeInternalFailure(replace(userId, "projection-version-corruption"));
        assertUnchanged(before);
    }

    @Test
    void consistencyCheckerRejectsTamperedCombinedReceiptAndRollsBack() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_command_receipt_corruption", "investment_transaction_corrections", "BEFORE INSERT", """
                NEW.idempotency_key := NEW.idempotency_key || '-tampered';
                RETURN NEW;
                """);

        assertSafeInternalFailure(replace(userId, "command-receipt-corruption"));
        assertUnchanged(before);
    }

    @Test
    void firstReplayFailureReturnsSafe500BeforeAnyWrite() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doThrow(new IllegalStateException("injected first replacement replay failure"))
                .when(replayEngine).replay(any());

        assertSafeInternalFailure(replace(userId, "first-replay-failure"));
        assertUnchanged(before);
    }

    @Test
    void candidateReplayInternalFailureReturnsSafe500BeforeAnyWrite() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doAnswer(invocation -> {
            Set<?> excludedOriginalIds = invocation.getArgument(1);
            if (!excludedOriginalIds.isEmpty()) {
                throw new IllegalStateException("injected candidate replacement replay failure");
            }
            return invocation.callRealMethod();
        }).when(replayEngine).replay(any(), anySet());

        assertSafeInternalFailure(replace(userId, "candidate-replay-failure"));
        assertUnchanged(before);
    }

    @Test
    void groupedReversalInsertFailureRollsBackTheWholeReplacement() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_reversal_insert_failure", "investment_transactions", "BEFORE INSERT", """
                IF NEW.transaction_type = 'REVERSAL' THEN
                    RAISE EXCEPTION 'injected grouped reversal insert failure';
                END IF;
                RETURN NEW;
                """);

        assertSafeDatabaseFailure(replace(userId, "reversal-insert-failure"));
        assertUnchanged(before);
    }

    @Test
    void replacementFactInsertFailureRollsBackTheAlreadyInsertedGroupedReversal() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_fact_insert_failure", "investment_transactions", "BEFORE INSERT", """
                IF NEW.source = 'CORRECTION' AND NEW.transaction_type <> 'REVERSAL' THEN
                    RAISE EXCEPTION 'injected replacement fact insert failure';
                END IF;
                RETURN NEW;
                """);

        assertSafeDatabaseFailure(replace(userId, "replacement-insert-failure"));
        assertUnchanged(before);
    }

    @Test
    void accountMutationFailureRollsBackBothFactsAndTheCommandEnvelope() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_account_failure", "accounts", "BEFORE UPDATE OF balance", """
                IF NEW.balance IS DISTINCT FROM OLD.balance THEN
                    RAISE EXCEPTION 'injected replacement account mutation failure';
                END IF;
                RETURN NEW;
                """);

        assertSafeDatabaseFailure(replace(userId, "account-mutation-failure"));
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
    void secondReplayTraceMismatchRollsBackTheWholeReplacement() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            InvestmentReplayResult result = (InvestmentReplayResult) invocation.callRealMethod();
            if (calls.incrementAndGet() == 2) {
                return new InvestmentReplayResult(result.position(), result.appliedCalculations(), InvestmentReplayTrace.empty());
            }
            return result;
        }).when(replayEngine).replay(any());

        assertSafeInternalFailure(replace(userId, "second-trace-mismatch"));
        assertUnchanged(before);
    }

    @Test
    void secondReplayCanonicalDigestMismatchRollsBackTheWholeReplacement() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            InvestmentReplayResult result = (InvestmentReplayResult) invocation.callRealMethod();
            if (calls.incrementAndGet() == 2) {
                return new InvestmentReplayResult(result.position(), result.appliedCalculations(),
                        new InvestmentReplayTrace(result.trace().steps(), "0".repeat(64)));
            }
            return result;
        }).when(replayEngine).replay(any());

        assertSafeInternalFailure(replace(userId, "second-digest-mismatch"));
        assertUnchanged(before);
    }

    @Test
    void projectionUpdateAffectingZeroRowsRollsBackPrecedingWrites() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doReturn(0).when(assetMapper).updateTransactionDrivenProjection(
                anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), anyString(), anyLong());

        assertSafeInternalFailure(replace(userId, "projection-zero-rows"));
        assertUnchanged(before);
    }

    @Test
    void projectionUpdateAffectingMultipleRowsRollsBackPrecedingWrites() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doReturn(2).when(assetMapper).updateTransactionDrivenProjection(
                anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), anyString(), anyLong());

        assertSafeInternalFailure(replace(userId, "projection-multiple-rows"));
        assertUnchanged(before);
    }

    @Test
    void projectionMapperFailureRollsBackPrecedingWrites() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doThrow(new IllegalStateException("injected projection failure"))
                .when(assetMapper).updateTransactionDrivenProjection(
                        anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), anyString(), anyLong());

        assertSafeInternalFailure(replace(userId, "projection-exception"));
        assertUnchanged(before);
    }

    @Test
    void commandInsertExceptionRollsBackFactsBalanceAndProjection() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doThrow(new DataIntegrityViolationException("injected replacement command insert failure"))
                .when(correctionMapper).insert(any(InvestmentTransactionCorrection.class));

        assertSafeInternalFailure(replace(userId, "command-insert-exception"));
        assertUnchanged(before);
    }

    @Test
    void commandCheckViolationRollsBackFactsBalanceAndProjection() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_command_check_failure", "investment_transaction_corrections", "BEFORE INSERT", """
                RAISE EXCEPTION 'injected replacement command check violation' USING ERRCODE = '23514';
                RETURN NEW;
                """);

        assertSafeInternalFailure(replace(userId, "command-check-violation"));
        assertUnchanged(before);
    }

    @Test
    void commandForeignKeyViolationRollsBackFactsBalanceAndProjection() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        createTrigger("injected_replacement_command_fk_failure", "investment_transaction_corrections", "BEFORE INSERT", """
                RAISE EXCEPTION 'injected replacement command foreign key violation' USING ERRCODE = '23503';
                RETURN NEW;
                """);

        assertSafeInternalFailure(replace(userId, "command-fk-violation"));
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
