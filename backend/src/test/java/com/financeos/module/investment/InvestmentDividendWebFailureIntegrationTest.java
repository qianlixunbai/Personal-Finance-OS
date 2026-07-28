package com.financeos.module.investment;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.ledger.InvestmentReplayEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class InvestmentDividendWebFailureIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @SpyBean
    private InvestmentReplayEngine replayEngine;

    @SpyBean
    private AssetMapper assetMapper;

    @SpyBean
    private AccountMapper accountMapper;

    @AfterEach
    void removeInjectedDatabaseObjects() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS injected_cash_mismatch ON accounts");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS injected_cash_mismatch()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS injected_receipt_mismatch ON investment_transactions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS injected_receipt_mismatch()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS injected_metadata_mismatch ON assets");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS injected_metadata_mismatch()");
        jdbcTemplate.execute("DROP INDEX IF EXISTS ux_injected_dividend_external_reference");
    }

    @Test
    void dividendRollsBackWhenConsistencyCheckerDetectsCashMismatch() throws Exception {
        Long userId = seedOpenPosition();
        jdbcTemplate.execute("""
                CREATE FUNCTION injected_cash_mismatch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.balance := NEW.balance + 1; RETURN NEW; END; $$
                """);
        jdbcTemplate.execute("CREATE TRIGGER injected_cash_mismatch BEFORE UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION injected_cash_mismatch()");

        assertSafeInternalFailure(userId, "cash-mismatch");
        assertUnchanged();
    }

    @Test
    void dividendRollsBackWhenFirstReplayFails() throws Exception {
        Long userId = seedOpenPosition();
        doThrow(new IllegalStateException("injected first replay failure"))
                .when(replayEngine).replay(any());

        assertSafeInternalFailure(userId, "first-replay-failure");
        assertUnchanged();
    }

    @Test
    void dividendRollsBackWhenSecondReplayFailsAfterFactAndBalanceUpdate() throws Exception {
        Long userId = seedOpenPosition();
        clearInvocations(accountMapper, replayEngine);
        doCallRealMethod().doThrow(new IllegalStateException("injected second replay failure"))
                .when(replayEngine).replay(any());

        assertSafeInternalFailure(userId, "second-replay-failure");
        InOrder order = inOrder(replayEngine, accountMapper);
        order.verify(accountMapper).updateBalance(anyLong(), anyLong(), any());
        order.verify(replayEngine).replay(any());
        assertUnchanged();
    }

    @Test
    void dividendRollsBackWhenProjectionUpdateAffectsZeroRows() throws Exception {
        Long userId = seedOpenPosition();
        doReturn(0).when(assetMapper).updateTransactionDrivenProjection(
                anyLong(), anyLong(), anyInt(), any(), any(), any(), any(), anyString(), anyLong());

        assertSafeInternalFailure(userId, "projection-row-count");
        assertUnchanged();
    }

    @Test
    void dividendRollsBackWhenConsistencyCheckerDetectsReceiptMismatch() throws Exception {
        Long userId = seedOpenPosition();
        jdbcTemplate.execute("""
                CREATE FUNCTION injected_receipt_mismatch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.source := 'IMPORT'; RETURN NEW; END; $$
                """);
        jdbcTemplate.execute("CREATE TRIGGER injected_receipt_mismatch BEFORE INSERT ON investment_transactions FOR EACH ROW EXECUTE FUNCTION injected_receipt_mismatch()");

        assertSafeInternalFailure(userId, "receipt-mismatch");
        assertUnchanged();
    }

    @Test
    void dividendRollsBackWhenConsistencyCheckerDetectsPositionMetadataMismatch() throws Exception {
        Long userId = seedOpenPosition();
        jdbcTemplate.execute("""
                CREATE FUNCTION injected_metadata_mismatch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN NEW.last_transaction_id := OLD.last_transaction_id; RETURN NEW; END; $$
                """);
        jdbcTemplate.execute("CREATE TRIGGER injected_metadata_mismatch BEFORE UPDATE ON assets FOR EACH ROW EXECUTE FUNCTION injected_metadata_mismatch()");

        assertSafeInternalFailure(userId, "metadata-mismatch");
        assertUnchanged();
    }

    @Test
    void dividendDoesNotTreatUnknownUniqueConstraintAsIdempotentReplay() throws Exception {
        Long userId = seedOpenPosition();
        jdbcTemplate.execute("CREATE UNIQUE INDEX ux_injected_dividend_external_reference ON investment_transactions (external_reference) WHERE external_reference IS NOT NULL");

        assertThat(dividend(userId, "known-dividend", "non-idempotent-unique").getStatus()).isEqualTo(200);
        var response = dividend(userId, "unknown-unique", "non-idempotent-unique");

        assertThat(response.getStatus()).isNotEqualTo(200);
        assertThat(response.getContentAsString())
                .doesNotContain("\"idempotentReplay\":true", "transactionId", "uk_injected_dividend_external_reference", "SQL", "stack trace");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-15.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, position_status, last_transaction_id, projection_version, current_price, market_value, account_id, instrument_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000"))
                .containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("position_status", "OPEN")
                .containsEntry("last_transaction_id", 2L)
                .containsEntry("projection_version", 2)
                .containsEntry("account_id", 1L)
                .containsEntry("instrument_id", 1L);
    }

    private void assertUnchanged() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-20.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, position_status, last_transaction_id, projection_version, current_price, market_value, account_id, instrument_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000"))
                .containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("position_status", "OPEN")
                .containsEntry("last_transaction_id", 1L)
                .containsEntry("projection_version", 1)
                .containsEntry("account_id", 1L)
                .containsEntry("instrument_id", 1L);
        assertThat((BigDecimal) jdbcTemplate.queryForMap("SELECT current_price, market_value FROM assets WHERE id = 1").get("current_price"))
                .isEqualByComparingTo("99.00");
        assertThat((BigDecimal) jdbcTemplate.queryForMap("SELECT current_price, market_value FROM assets WHERE id = 1").get("market_value"))
                .isEqualByComparingTo("198.00");
    }

    private void assertSafeInternalFailure(Long userId, String idempotencyKey) throws Exception {
        var response = dividend(userId, idempotencyKey, null);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString())
                .doesNotContain("-15.00", "5.00", "requestHash", "Idempotency-Key", "SQL", "AssetMapper", "stack trace");
    }

    private org.springframework.mock.web.MockHttpServletResponse dividend(Long userId, String idempotencyKey, String externalReference) throws Exception {
        String body = externalReference == null ? "{\"grossAmount\":\"5.00\"}"
                : "{\"grossAmount\":\"5.00\",\"externalReference\":\"" + externalReference + "\"}";
        return mockMvc.perform(post("/api/v1/investment/positions/1/dividends")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    private Long seedOpenPosition() throws Exception {
        Long userId = jdbcTemplate.queryForObject("INSERT INTO users (username, email, password_hash) VALUES ('web-failure-user', 'web-failure@example.com', 'hash') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)", userId);
        jdbcTemplate.update("INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status) VALUES (?, 'WEBFAIL', 'Web Failure Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')", userId);
        int status = mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "first-buy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"instrumentId\":1,\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        jdbcTemplate.update("UPDATE assets SET current_price = 99.00, market_value = 198.00 WHERE id = 1");
        return userId;
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
