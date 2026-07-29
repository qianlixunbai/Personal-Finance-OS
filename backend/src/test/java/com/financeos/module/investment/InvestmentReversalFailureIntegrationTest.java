package com.financeos.module.investment;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.ledger.InvestmentReplayEngine;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class InvestmentReversalFailureIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private InvestmentReplayEngine replayEngine;

    @SpyBean
    private InvestmentTransactionMapper transactionMapper;

    @SpyBean
    private AccountMapper accountMapper;

    @SpyBean
    private AssetMapper assetMapper;

    @AfterEach
    void removeInjectedDatabaseObjects() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS injected_reversal_cash_mismatch ON accounts");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS injected_reversal_cash_mismatch()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS zz_injected_reversal_receipt_mismatch ON investment_transactions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS injected_reversal_receipt_mismatch()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS injected_reversal_position_mismatch ON assets");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS injected_reversal_position_mismatch()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS injected_reversal_metadata_mismatch ON assets");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS injected_reversal_metadata_mismatch()");
        jdbcTemplate.execute("DROP INDEX IF EXISTS ux_injected_reversal_currency");
    }

    @Test
    void firstReplayInternalFailureReturnsSanitized500BeforeAnyWrite() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doThrow(new IllegalStateException("injected first replay failure"))
                .when(replayEngine).replay(any());

        assertSafeInternalFailure(reverse(userId, 1, "first-replay-failure", "Broker correction"));

        assertUnchanged(before);
    }

    @Test
    void candidateReplayRejectsBuyReversalThatWouldMakeFollowingSellOversell() throws Exception {
        Long userId = seedBuyAndFullSell();
        Snapshot before = snapshot();

        MockHttpServletResponse response = reverse(userId, 1, "candidate-oversell", "Broker correction");

        assertThat(response.getStatus()).isEqualTo(409);
        assertUnchanged(before);
    }

    @Test
    void candidateReplayRejectsBuyReversalThatWouldOrphanDividendHistory() throws Exception {
        Long userId = seedBuyAndDividend();
        Snapshot before = snapshot();

        MockHttpServletResponse response = reverse(userId, 1, "candidate-dividend", "Broker correction");

        assertThat(response.getStatus()).isEqualTo(409);
        assertUnchanged(before);
    }

    @Test
    void candidateReplayInternalFailureReturnsSanitized500WithoutWriting() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doAnswer(invocation -> {
            Set<?> candidateReversedOriginalIds = invocation.getArgument(1);
            if (!candidateReversedOriginalIds.isEmpty()) {
                throw new IllegalStateException("injected candidate replay failure");
            }
            return invocation.callRealMethod();
        }).when(replayEngine).replay(any(), anySet());

        assertSafeInternalFailure(reverse(userId, 1, "candidate-internal-failure", "Broker correction"));

        assertUnchanged(before);
    }

    @Test
    void secondReplayInternalFailureReturnsFullySanitized500AndRollsBackInsertedFactAndBalance() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        clearInvocations(replayEngine, transactionMapper, accountMapper);
        doCallRealMethod().doThrow(new IllegalStateException("injected second replay failure"))
                .when(replayEngine).replay(any());

        MockHttpServletResponse response =
                reverse(userId, 1, "second-replay-failure", "Broker correction");
        assertSafeInternalFailure(response);
        assertThat(response.getContentAsString())
                .doesNotContain("second-replay-failure", "Broker correction");

        InOrder order = inOrder(replayEngine, transactionMapper, accountMapper);
        order.verify(replayEngine).replay(any());
        order.verify(transactionMapper).insert(any(InvestmentTransaction.class));
        order.verify(accountMapper).updateBalance(anyLong(), anyLong(), any());
        order.verify(replayEngine).replay(any());
        assertUnchanged(before);
    }

    @Test
    void projectionUpdateAffectingZeroRowsReturnsSanitized500AndRollsBack() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doReturn(0).when(assetMapper).updateTransactionDrivenProjection(
                anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), anyString(), anyLong());

        assertSafeInternalFailure(reverse(userId, 1, "projection-zero-rows", "Broker correction"));

        assertUnchanged(before);
    }

    @Test
    void projectionUpdateAffectingMultipleRowsReturnsSanitized500AndRollsBack() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        doReturn(2).when(assetMapper).updateTransactionDrivenProjection(
                anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), anyString(), anyLong());

        assertSafeInternalFailure(reverse(userId, 1, "projection-multiple-rows", "Broker correction"));

        assertUnchanged(before);
    }

    @Test
    void persistedCashMismatchIsDetectedAndRollsBackTheWholeReversal() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        jdbcTemplate.execute("""
                CREATE FUNCTION injected_reversal_cash_mismatch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.balance := NEW.balance + 1; RETURN NEW; END; $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER injected_reversal_cash_mismatch
                BEFORE UPDATE ON accounts
                FOR EACH ROW EXECUTE FUNCTION injected_reversal_cash_mismatch()
                """);

        assertSafeInternalFailure(reverse(userId, 1, "cash-checker-mismatch", "Broker correction"));

        assertUnchanged(before);
    }

    @Test
    void persistedReversalReceiptMismatchIsDetectedAndRollsBackTheWholeReversal() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        jdbcTemplate.execute("""
                CREATE FUNCTION injected_reversal_receipt_mismatch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.account_balance_after := NEW.account_balance_after + 1; RETURN NEW; END; $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER zz_injected_reversal_receipt_mismatch
                BEFORE INSERT ON investment_transactions
                FOR EACH ROW EXECUTE FUNCTION injected_reversal_receipt_mismatch()
                """);

        assertSafeInternalFailure(reverse(userId, 1, "receipt-checker-mismatch", "Broker correction"));

        assertUnchanged(before);
    }

    @Test
    void persistedPositionMismatchIsDetectedAndRollsBackTheWholeReversal() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        jdbcTemplate.execute("""
                CREATE FUNCTION injected_reversal_position_mismatch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.realized_profit_loss := NEW.realized_profit_loss + 1; RETURN NEW; END; $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER injected_reversal_position_mismatch
                BEFORE UPDATE ON assets
                FOR EACH ROW EXECUTE FUNCTION injected_reversal_position_mismatch()
                """);

        assertSafeInternalFailure(reverse(userId, 1, "position-checker-mismatch", "Broker correction"));

        assertUnchanged(before);
    }

    @Test
    void persistedProjectionMetadataMismatchIsDetectedAndRollsBackTheWholeReversal() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        jdbcTemplate.execute("""
                CREATE FUNCTION injected_reversal_metadata_mismatch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.projection_version := OLD.projection_version; RETURN NEW; END; $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER injected_reversal_metadata_mismatch
                BEFORE UPDATE ON assets
                FOR EACH ROW EXECUTE FUNCTION injected_reversal_metadata_mismatch()
                """);

        assertSafeInternalFailure(reverse(userId, 1, "metadata-checker-mismatch", "Broker correction"));

        assertUnchanged(before);
    }

    @Test
    void unknownUniqueConstraintIsNotTreatedAsIdempotentReversalRecovery() throws Exception {
        Long userId = seedBuy();
        Snapshot before = snapshot();
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX ux_injected_reversal_currency
                ON investment_transactions (currency)
                """);

        MockHttpServletResponse response = reverse(userId, 1, "unknown-unique", "Sensitive broker reason");

        assertSafeInternalFailure(response);
        assertThat(response.getContentAsString())
                .doesNotContain("\"idempotentReplay\":true", "reversalTransactionId",
                        "unknown-unique", "Sensitive broker reason",
                        "ux_injected_reversal_currency", "23505");
        assertUnchanged(before);
    }

    private void assertSafeInternalFailure(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString())
                .doesNotContain("20.00", "-20.00", "99.00", "198.00",
                        "Broker correction", "Sensitive broker reason",
                        "cashDelta", "requestHash", "Idempotency-Key",
                        "SQL", "SQLSTATE", "constraint", "trigger",
                        "AssetMapper", "AccountMapper", "InvestmentTransactionMapper",
                        "stack trace", "injected");
    }

    private void assertUnchanged(Snapshot before) {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM investment_transactions
                WHERE transaction_type = 'REVERSAL'
                """, Integer.class)).isZero();
        assertThat(snapshot()).isEqualTo(before);
    }

    private Snapshot snapshot() {
        List<Map<String, Object>> transactions = jdbcTemplate.queryForList("""
                SELECT id, user_id, account_id, asset_id, transaction_type, status,
                       quantity, unit_price, gross_amount, fee_amount, tax_amount, net_amount,
                       released_cost_amount, realized_profit_loss, currency, trade_time,
                       settlement_time, source, idempotency_key, request_hash,
                       account_balance_after, position_quantity_after, position_avg_cost_after,
                       position_total_cost_after, position_realized_profit_loss_after,
                       position_status_after, projection_version_after, external_reference,
                       original_transaction_id, correction_reason, cash_delta
                FROM investment_transactions
                ORDER BY id
                """);
        Map<String, Object> account = jdbcTemplate.queryForMap("""
                SELECT id, user_id, name, type, currency, balance, status
                FROM accounts WHERE id = 1
                """);
        Map<String, Object> asset = jdbcTemplate.queryForMap("""
                SELECT id, user_id, account_id, instrument_id, quantity, avg_cost, total_cost,
                       realized_profit_loss, position_status, position_mode, last_transaction_id,
                       projection_version, current_price, market_value
                FROM assets WHERE id = 1
                """);
        return new Snapshot(transactions, account, asset);
    }

    private MockHttpServletResponse reverse(Long userId, long transactionId, String key, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/transactions/{transactionId}/reversal", transactionId)
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn().getResponse();
    }

    private Long seedBuy() throws Exception {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES ('reversal-failure', 'reversal-failure@example.com', 'hash')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments
                    (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'REVFAIL', 'Reversal Failure Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """, userId);
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/investment/positions")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", "first-buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":1,"instrumentId":1,"quantity":"2.00000000",
                                 "unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00"}
                                """))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        jdbcTemplate.update("UPDATE assets SET current_price = 99.00, market_value = 198.00 WHERE id = 1");
        return userId;
    }

    private Long seedBuyAndFullSell() throws Exception {
        Long userId = seedBuy();
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/investment/positions/1/sell")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", "full-sell")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":"2.00000000","unitPrice":"10.00000000",
                                 "feeAmount":"0.00","taxAmount":"0.00"}
                                """))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return userId;
    }

    private Long seedBuyAndDividend() throws Exception {
        Long userId = seedBuy();
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/investment/positions/1/dividends")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", "dividend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"5.00\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return userId;
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private record Snapshot(List<Map<String, Object>> transactions,
                            Map<String, Object> account,
                            Map<String, Object> asset) {
    }
}
