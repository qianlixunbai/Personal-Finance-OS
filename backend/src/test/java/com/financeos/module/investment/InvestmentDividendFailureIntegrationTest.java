package com.financeos.module.investment;

import com.financeos.integration.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class InvestmentDividendFailureIntegrationTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;


    @Test
    void assetProjectionUpdateTriggerFailureRollsBackFactAndBalanceMutation() throws Exception {
        Long userId = seed();
        failingTrigger("assets", "BEFORE UPDATE", "RETURN NEW");
        try {
            assertFailure(userId);
            assertUnchanged();
        } finally { dropFailureTrigger("assets"); }
    }



    private void assertFailure(Long userId) throws Exception {
        int status = mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "injected-failure").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"5.00\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isGreaterThanOrEqualTo(500);
    }

    private void assertUnchanged() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-20.00");
        var asset = jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, projection_version, current_price, market_value FROM assets WHERE id = 1");
        assertThat(asset)
                .containsEntry("quantity", new BigDecimal("2.00000000"))
                .containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("projection_version", 1);
        assertThat((BigDecimal) asset.get("current_price")).isEqualByComparingTo("99.00");
        assertThat((BigDecimal) asset.get("market_value")).isEqualByComparingTo("198.00");
    }

    private void failingTrigger(String table, String event, String ignored) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION injected_dividend_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected dividend failure'; END; $$
                """);
        jdbcTemplate.execute("CREATE TRIGGER injected_dividend_failure " + event + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION injected_dividend_failure()");
    }

    private void dropFailureTrigger(String table) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS injected_dividend_failure ON " + table);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS injected_dividend_failure()");
    }

    private Long seed() throws Exception {
        Long userId = jdbcTemplate.queryForObject("INSERT INTO users (username, email, password_hash) VALUES ('failure-user', 'failure@example.com', 'hash') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)", userId);
        jdbcTemplate.update("INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status) VALUES (?, 'FAIL', 'Failure Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')", userId);
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
