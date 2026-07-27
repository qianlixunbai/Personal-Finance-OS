package com.financeos.module.investment;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InvestmentCommandApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void firstBuyCreatesFactBalanceAndTransactionDrivenPosition() throws Exception {
        Long userId = insertUserAccountAndInstrument();

        mockMvc.perform(post("/api/v1/investment/positions")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", "first-buy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountId", 1,
                                "instrumentId", 1,
                                "quantity", "2.00000000",
                                "unitPrice", "10.00000000",
                                "feeAmount", "1.00",
                                "taxAmount", "0.50"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.transactionType").value("BUY"))
                .andExpect(jsonPath("$.data.cashDelta").value("-21.50"))
                .andExpect(jsonPath("$.data.balanceAfter").value("-21.50"))
                .andExpect(jsonPath("$.data.finalPosition.quantity").value("2.00000000"))
                .andExpect(jsonPath("$.data.finalPosition.totalCost").value("21.50"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-21.50");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT quantity, avg_cost, total_cost, realized_profit_loss, position_status, position_mode,
                       account_id, instrument_id, projection_version
                FROM assets WHERE id = 1
                """))
                .containsEntry("quantity", new BigDecimal("2.00000000"))
                .containsEntry("avg_cost", new BigDecimal("10.75000000"))
                .containsEntry("total_cost", new BigDecimal("21.50"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("position_status", "OPEN")
                .containsEntry("position_mode", "TRANSACTION_DRIVEN")
                .containsEntry("account_id", 1L)
                .containsEntry("instrument_id", 1L)
                .containsEntry("projection_version", 1);
    }

    @Test
    void subsequentBuyAndPartialSellUseLedgerAmountsAndReceipts() throws Exception {
        Long userId = insertUserAccountAndInstrument();
        firstBuy(userId, "first-buy", "2.00000000", "10.00000000", "1.00", "0.50");

        mockMvc.perform(post("/api/v1/investment/positions/1/buy")
                        .with(authentication(auth(userId))).header("Idempotency-Key", "second-buy")
                        .contentType(MediaType.APPLICATION_JSON).content(trade("1.00000000", "20.00000000", "0.00", "0.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashDelta").value("-20.00"))
                .andExpect(jsonPath("$.data.finalPosition.quantity").value("3.00000000"))
                .andExpect(jsonPath("$.data.finalPosition.totalCost").value("41.50"));

        mockMvc.perform(post("/api/v1/investment/positions/1/sell")
                        .with(authentication(auth(userId))).header("Idempotency-Key", "partial-sell")
                        .contentType(MediaType.APPLICATION_JSON).content(trade("1.00000000", "30.00000000", "0.50", "0.50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashDelta").value("29.00"))
                .andExpect(jsonPath("$.data.balanceAfter").value("-12.50"))
                .andExpect(jsonPath("$.data.finalPosition.quantity").value("2.00000000"))
                .andExpect(jsonPath("$.data.finalPosition.totalCost").value("27.67"))
                .andExpect(jsonPath("$.data.finalPosition.realizedProfitLoss").value("15.17"));

        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-12.50");
        assertThat(jdbcTemplate.queryForObject("SELECT released_cost_amount FROM investment_transactions WHERE id = 3", BigDecimal.class))
                .isEqualByComparingTo("13.83");
    }

    @Test
    void idempotencyReplaysStoredReceiptAndRejectsDifferentRequest() throws Exception {
        Long userId = insertUserAccountAndInstrument();
        String body = firstBuyBody("2.00000000", "10.00000000", "1.00", "0.50");

        mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "replay-key").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.transactionId").value(1))
                .andExpect(jsonPath("$.data.idempotentReplay").value(false));
        mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "replay-key").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.transactionId").value(1))
                .andExpect(jsonPath("$.data.balanceAfter").value("-21.50"))
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));
        mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "replay-key").contentType(MediaType.APPLICATION_JSON)
                        .content(firstBuyBody("3.00000000", "10.00000000", "1.00", "0.50")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-21.50");
    }

    @Test
    void insufficientSellReturnsConflictAndRollsBackWithoutChangingFactBalanceOrProjection() throws Exception {
        Long userId = insertUserAccountAndInstrument();
        firstBuy(userId, "first-buy", "2.00000000", "10.00000000", "1.00", "0.50");

        mockMvc.perform(post("/api/v1/investment/positions/1/sell")
                        .with(authentication(auth(userId))).header("Idempotency-Key", "invalid-sell")
                        .contentType(MediaType.APPLICATION_JSON).content(trade("3.00000000", "30.00000000", "0.00", "0.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-21.50");
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM assets WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("2.00000000");
    }

    @Test
    void concurrentSameIdempotencyKeyPostsExactlyOneFactAndReplaysIt() throws Exception {
        Long userId = insertUserAccountAndInstrument();
        String body = firstBuyBody("2.00000000", "10.00000000", "1.00", "0.50");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> statusForFirstBuy(userId, "race-key", body), executor);
            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> statusForFirstBuy(userId, "race-key", body), executor);
            assertThat(first.join()).isEqualTo(200);
            assertThat(second.join()).isEqualTo(200);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-21.50");
    }

    private int statusForFirstBuy(Long userId, String key, String body) {
        try {
            return mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void firstBuy(Long userId, String key, String quantity, String unitPrice, String fee, String tax) throws Exception {
        mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(firstBuyBody(quantity, unitPrice, fee, tax)))
                .andExpect(status().isOk());
    }

    private String firstBuyBody(String quantity, String unitPrice, String fee, String tax) throws Exception {
        return objectMapper.writeValueAsString(Map.of("accountId", 1, "instrumentId", 1,
                "quantity", quantity, "unitPrice", unitPrice, "feeAmount", fee, "taxAmount", tax));
    }

    private String trade(String quantity, String unitPrice, String fee, String tax) throws Exception {
        return objectMapper.writeValueAsString(Map.of("quantity", quantity, "unitPrice", unitPrice,
                "feeAmount", fee, "taxAmount", tax));
    }

    private Long insertUserAccountAndInstrument() {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES ('investment-user', 'investment-user@example.com', 'hash') RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'BUY', 'Buy Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """, userId);
        return userId;
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
