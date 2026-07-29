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
class InvestmentReversalApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reversesBuyWithoutMutatingTheOriginalFactOrReceipt() throws Exception {
        Long userId = seedBuy();

        int status = mockMvc.perform(post("/api/v1/investment/transactions/1/reversal")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", "reverse-buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Duplicate broker entry\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap("SELECT transaction_type, status, net_amount, request_hash, account_balance_after FROM investment_transactions WHERE id = 1"))
                .containsEntry("transaction_type", "BUY").containsEntry("status", "POSTED")
                .containsEntry("net_amount", new BigDecimal("20.00")).containsEntry("account_balance_after", new BigDecimal("-20.00"));
        assertThat(jdbcTemplate.queryForMap("SELECT transaction_type, source, original_transaction_id, correction_reason, cash_delta FROM investment_transactions WHERE id = 2"))
                .containsEntry("transaction_type", "REVERSAL").containsEntry("source", "CORRECTION")
                .containsEntry("original_transaction_id", 1L).containsEntry("correction_reason", "Duplicate broker entry")
                .containsEntry("cash_delta", new BigDecimal("20.00"));
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, position_status, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("0.00000000")).containsEntry("avg_cost", new BigDecimal("0.00000000"))
                .containsEntry("total_cost", new BigDecimal("0.00")).containsEntry("position_status", "CLOSED")
                .containsEntry("projection_version", 2).containsEntry("last_transaction_id", 2L);
    }

    @Test
    void reversesFullSellAndReopensThePosition() throws Exception {
        Long userId = seedFullSell();

        assertThat(reverse(userId, 2, "reverse-sell", "Broker correction")).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-20.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, realized_profit_loss, position_status, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000")).containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00")).containsEntry("position_status", "OPEN")
                .containsEntry("projection_version", 3).containsEntry("last_transaction_id", 3L);
        assertThat(jdbcTemplate.queryForObject("SELECT cash_delta FROM investment_transactions WHERE id = 3", BigDecimal.class)).isEqualByComparingTo("-20.00");
    }

    @Test
    void reversesPartialSellByReplayingTheRemainingPosition() throws Exception {
        Long userId = seedBuy();
        assertThat(sell(userId, "partial-sell", "1.00000000", "12.00000000")).isEqualTo(200);

        assertThat(reverse(userId, 2, "reverse-partial-sell", "Broker correction")).isEqualTo(200);

        assertThat(jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, position_status, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000")).containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00")).containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("position_status", "OPEN").containsEntry("projection_version", 3).containsEntry("last_transaction_id", 3L);
    }

    @Test
    void reversesDividendWithoutChangingPositionCostOrQuantity() throws Exception {
        Long userId = seedBuy();
        assertThat(dividend(userId, "dividend", "5.00")).isEqualTo(200);

        assertThat(reverse(userId, 2, "reverse-dividend", "Broker correction")).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-20.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, position_status, projection_version FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000")).containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00")).containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("position_status", "OPEN").containsEntry("projection_version", 3);
    }

    @Test
    void replaysTheStoredReceiptForTheSameIdempotencyKeyAndHash() throws Exception {
        Long userId = seedBuy();

        assertThat(reverse(userId, 1, "reverse-buy", "Duplicate broker entry")).isEqualTo(200);
        assertThat(reverse(userId, 1, "reverse-buy", "Duplicate broker entry")).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'REVERSAL'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsSameIdempotencyKeyWithADifferentReasonAndDifferentKeyForTheSameOriginal() throws Exception {
        Long userId = seedBuy();

        assertThat(reverse(userId, 1, "reverse-buy", "Duplicate broker entry")).isEqualTo(200);
        assertThat(reverse(userId, 1, "reverse-buy", "Changed reason")).isEqualTo(409);
        assertThat(reverse(userId, 1, "another-key", "Duplicate broker entry")).isEqualTo(409);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'REVERSAL'", Integer.class)).isEqualTo(1);
    }

    @Test
    void allowsReversalAfterAccountAndInstrumentBecomeInactive() throws Exception {
        Long userId = seedBuy();
        jdbcTemplate.update("UPDATE accounts SET status = 'INACTIVE' WHERE id = 1");
        jdbcTemplate.update("UPDATE investment_instruments SET status = 'INACTIVE' WHERE id = 1");

        assertThat(reverse(userId, 1, "reverse-buy", "Broker correction")).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM accounts WHERE id = 1", String.class)).isEqualTo("INACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM investment_instruments WHERE id = 1", String.class)).isEqualTo("INACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("0.00");
    }

    @Test
    void preservesMarketPriceFieldsWhileRebuildingThePositionProjection() throws Exception {
        Long userId = seedBuy();
        jdbcTemplate.update("UPDATE assets SET current_price = 12.34, market_value = 24.68 WHERE id = 1");

        assertThat(reverse(userId, 1, "reverse-buy", "Broker correction")).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT current_price FROM assets WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("12.34");
        assertThat(jdbcTemplate.queryForObject("SELECT market_value FROM assets WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("24.68");
    }

    @Test
    void rejectsReversingAReversalAndHidesCrossUserOriginals() throws Exception {
        Long userId = seedBuy();
        assertThat(reverse(userId, 1, "reverse-buy", "Broker correction")).isEqualTo(200);

        assertThat(reverse(userId, 2, "reverse-reversal", "Broker correction")).isEqualTo(409);
        assertThat(reverse(userId + 1000, 1, "foreign-reversal", "Broker correction")).isEqualTo(404);
    }

    @Test
    void rejectsReversalThatWouldMakeAFollowingSellOversell() throws Exception {
        Long userId = seedFullSell();

        assertThat(reverse(userId, 1, "reverse-buy", "Broker correction")).isEqualTo(409);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'REVERSAL'", Integer.class)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsInvalidReasonIdempotencyKeyAndUnknownJsonFieldsWithoutWriting() throws Exception {
        Long userId = seedBuy();

        assertThat(reverseRaw(userId, 1, "key", "{\"reason\":\"  \"}")).isEqualTo(400);
        assertThat(reverseRaw(userId, 1, " key ", "{\"reason\":\"Broker correction\"}")).isEqualTo(400);
        assertThat(reverseRaw(userId, 1, "key", "{\"reason\":\"Broker correction\",\"cashDelta\":\"20.00\"}")).isEqualTo(400);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'REVERSAL'", Integer.class)).isZero();
    }

    private Long seedBuy() throws Exception {
        Long userId = jdbcTemplate.queryForObject("INSERT INTO users (username, email, password_hash) VALUES ('reversal-user', 'reversal@example.com', 'hash') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)", userId);
        jdbcTemplate.update("INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status) VALUES (?, 'REV', 'Reversal Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')", userId);
        int status = mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "first-buy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"instrumentId\":1,\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        return userId;
    }

    private Long seedFullSell() throws Exception {
        Long userId = seedBuy();
        int status = mockMvc.perform(post("/api/v1/investment/positions/1/sell").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "full-sell").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        return userId;
    }

    private int reverse(Long userId, long transactionId, String key, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/transactions/" + transactionId + "/reversal")
                        .with(authentication(auth(userId))).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int dividend(Long userId, String key, String grossAmount) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"" + grossAmount + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int sell(Long userId, String key, String quantity, String unitPrice) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/sell").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"" + quantity + "\",\"unitPrice\":\"" + unitPrice + "\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int reverseRaw(Long userId, long transactionId, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/transactions/" + transactionId + "/reversal")
                        .with(authentication(auth(userId))).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
