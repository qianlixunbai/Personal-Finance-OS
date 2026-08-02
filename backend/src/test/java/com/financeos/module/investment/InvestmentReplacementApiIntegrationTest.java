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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class InvestmentReplacementApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void replacesBuyWithOneCommandTwoFactsAndOneCombinedBalanceMutation() throws Exception {
        Long userId = seedBuy();

        int status = replace(userId, 1, "replace-buy", "{\"quantity\":\"3.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\",\"reason\":\"Broker correction\"}");

        assertThat(status).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transaction_corrections", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE correction_group_id IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-30.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("3.00000000"))
                .containsEntry("total_cost", new BigDecimal("30.00"))
                .containsEntry("projection_version", 2)
                .containsEntry("last_transaction_id", 3L);
    }

    @Test
    void replaysTheImmutableCommandReceiptForTheSameKeyAndHash() throws Exception {
        Long userId = seedBuy();
        String request = "{\"quantity\":\"3.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\",\"reason\":\"Broker correction\"}";

        assertThat(replace(userId, 1, "replace-buy", request)).isEqualTo(200);
        assertThat(replace(userId, 1, "replace-buy", request)).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transaction_corrections", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE correction_group_id IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsTheSameKeyWhenReplacementAuditMetadataChanges() throws Exception {
        Long userId = seedBuy();
        String first = "{\"quantity\":\"3.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\",\"externalReference\":\"broker-1\",\"note\":\"first\",\"reason\":\"Broker correction\"}";
        String changed = "{\"quantity\":\"3.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\",\"externalReference\":\"broker-2\",\"note\":\"changed\",\"reason\":\"Broker correction\"}";

        assertThat(replace(userId, 1, "replace-buy", first)).isEqualTo(200);
        assertThat(replace(userId, 1, "replace-buy", changed)).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transaction_corrections", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsReplacementThatWouldMakeAFollowingSellOversellWithoutWrites() throws Exception {
        Long userId = seedBuy();
        int sell = mockMvc.perform(post("/api/v1/investment/positions/1/sell").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "sell").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(sell).isEqualTo(200);

        assertThat(replace(userId, 1, "replace-buy", "{\"quantity\":\"1.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\",\"reason\":\"Broker correction\"}")).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transaction_corrections", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE correction_group_id IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("0.00");
    }

    @Test
    void replacesSellAndPreservesTheOriginalSellAuditFact() throws Exception {
        Long userId = seedBuy();
        assertThat(sell(userId, "sell", "1.00000000", "12.00000000")).isEqualTo(200);

        assertThat(replace(userId, 2, "replace-sell", "{\"quantity\":\"1.00000000\",\"unitPrice\":\"11.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\",\"reason\":\"Broker correction\"}")).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap("SELECT net_amount, released_cost_amount, realized_profit_loss FROM investment_transactions WHERE id = 2"))
                .containsEntry("net_amount", new BigDecimal("12.00"))
                .containsEntry("released_cost_amount", new BigDecimal("10.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("2.00"));
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-9.00");
    }

    @Test
    void replacesDividendWithoutChangingPositionQuantityOrCost() throws Exception {
        Long userId = seedBuy();
        assertThat(dividend(userId, "dividend", "5.00")).isEqualTo(200);

        assertThat(replace(userId, 2, "replace-dividend", "{\"grossAmount\":\"3.00\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\",\"reason\":\"Broker correction\"}")).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, realized_profit_loss FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"));
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-17.00");
    }

    private Long seedBuy() throws Exception {
        Long userId = jdbcTemplate.queryForObject("INSERT INTO users (username, email, password_hash) VALUES ('replacement-user', 'replacement@example.com', 'hash') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)", userId);
        jdbcTemplate.update("INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status) VALUES (?, 'REP', 'Replacement Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')", userId);
        int status = mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "first-buy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"instrumentId\":1,\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        return userId;
    }

    private int replace(Long userId, long transactionId, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/transactions/" + transactionId + "/replacement")
                        .with(authentication(auth(userId))).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private int sell(Long userId, String key, String quantity, String unitPrice) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/sell").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"" + quantity + "\",\"unitPrice\":\"" + unitPrice + "\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int dividend(Long userId, String key, String grossAmount) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"" + grossAmount + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
