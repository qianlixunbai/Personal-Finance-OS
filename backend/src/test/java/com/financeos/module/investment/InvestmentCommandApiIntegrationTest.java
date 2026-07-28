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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void dividendPostsAnImmutableCashReceiptWithoutChangingPositionCost() throws Exception {
        Long userId = insertUserAccountAndInstrument();
        firstBuy(userId, "first-buy", "2.00000000", "10.00000000", "1.00", "0.50");

        mockMvc.perform(post("/api/v1/investment/positions/1/dividends")
                        .with(authentication(auth(userId))).header("Idempotency-Key", "dividend-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "grossAmount", "10.00", "feeAmount", "1.00", "taxAmount", "2.00",
                                "externalReference", " DIV-2026-01 ", "note", " cash dividend "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionType").value("DIVIDEND"))
                .andExpect(jsonPath("$.data.grossAmount").value("10.00"))
                .andExpect(jsonPath("$.data.feeAmount").value("1.00"))
                .andExpect(jsonPath("$.data.taxAmount").value("2.00"))
                .andExpect(jsonPath("$.data.netAmount").value("7.00"))
                .andExpect(jsonPath("$.data.cashDelta").value("7.00"))
                .andExpect(jsonPath("$.data.balanceAfter").value("-14.50"))
                .andExpect(jsonPath("$.data.finalPosition.quantity").value("2.00000000"))
                .andExpect(jsonPath("$.data.finalPosition.totalCost").value("21.50"))
                .andExpect(jsonPath("$.data.finalPosition.projectionVersion").value(2));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT quantity, unit_price, net_amount, released_cost_amount, realized_profit_loss,
                       account_balance_after, position_quantity_after, position_total_cost_after,
                       position_status_after, projection_version_after, external_reference, note
                FROM investment_transactions WHERE id = 2
                """))
                .containsEntry("quantity", null)
                .containsEntry("unit_price", null)
                .containsEntry("net_amount", new BigDecimal("7.00"))
                .containsEntry("released_cost_amount", new BigDecimal("0.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("account_balance_after", new BigDecimal("-14.50"))
                .containsEntry("position_quantity_after", new BigDecimal("2.00000000"))
                .containsEntry("position_total_cost_after", new BigDecimal("21.50"))
                .containsEntry("position_status_after", "OPEN")
                .containsEntry("projection_version_after", 2)
                .containsEntry("external_reference", "DIV-2026-01")
                .containsEntry("note", "cash dividend");
    }

    @Test
    void dividendDefaultsChargesAllowsZeroNetAndReplaysItsOriginalReceipt() throws Exception {
        Long userId = insertUserAccountAndInstrument();
        firstBuy(userId, "first-buy", "2.00000000", "10.00000000", "0.00", "0.00");
        String body = objectMapper.writeValueAsString(Map.of("grossAmount", "5.00", "feeAmount", "2.00", "taxAmount", "3.00"));

        mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "zero-net").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.netAmount").value("0.00"))
                .andExpect(jsonPath("$.data.cashDelta").value("0.00"))
                .andExpect(jsonPath("$.data.balanceAfter").value("-20.00"))
                .andExpect(jsonPath("$.data.finalPosition.lastTransactionId").value(2))
                .andExpect(jsonPath("$.data.finalPosition.projectionVersion").value(2))
                .andExpect(jsonPath("$.data.idempotentReplay").value(false));
        mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "zero-net").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(2))
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));
        mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "zero-net").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"5.01\",\"feeAmount\":\"2.00\",\"taxAmount\":\"3.00\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-20.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, position_status, projection_version FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("position_status", "OPEN")
                .containsEntry("projection_version", 2);
    }

    @Test
    void dividendAllowsClosedPositionAndInactiveAccountAndInstrument() throws Exception {
        Long userId = insertUserAccountAndInstrument();
        firstBuy(userId, "first-buy", "2.00000000", "10.00000000", "0.00", "0.00");
        mockMvc.perform(post("/api/v1/investment/positions/1/sell").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "close").contentType(MediaType.APPLICATION_JSON)
                        .content(trade("2.00000000", "12.00000000", "0.00", "0.00")))
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE accounts SET status = 'INACTIVE' WHERE id = 1");
        jdbcTemplate.update("UPDATE investment_instruments SET status = 'INACTIVE' WHERE id = 1");

        mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "closed-dividend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"3.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balanceAfter").value("7.00"))
                .andExpect(jsonPath("$.data.finalPosition.quantity").value("0.00000000"))
                .andExpect(jsonPath("$.data.finalPosition.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.finalPosition.projectionVersion").value(3));
    }

    @Test
    void dividendRejectsInvalidAmountsAndUnknownJsonFieldsWithoutWritingAnything() throws Exception {
        Long userId = insertUserAccountAndInstrument();
        firstBuy(userId, "first-buy", "2.00000000", "10.00000000", "0.00", "0.00");

        mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "number").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":10.00}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "scientific").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"1e1\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "too-much").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"10.00\",\"feeAmount\":\"8.00\",\"taxAmount\":\"2.01\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "unknown").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"10.00\",\"netAmount\":\"10.00\"}"))
                .andExpect(status().isBadRequest());
        jdbcTemplate.update("UPDATE accounts SET status = 'BROKEN' WHERE id = 1");
        mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "broken-account").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"10.00\"}"))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-20.00");
    }

    @Test
    void concurrentSameDividendIdempotencyKeyPostsOneFactAndOneCashMutation() throws Exception {
        Long userId = insertUserAccountAndInstrument();
        firstBuy(userId, "first-buy", "2.00000000", "10.00000000", "0.00", "0.00");
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> statusForDividend(userId, "dividend-race", barrier));
            Future<Integer> second = executor.submit(() -> statusForDividend(userId, "dividend-race", barrier));
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-15.00");
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(2);
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

    private int statusForDividend(Long userId, String key, CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"grossAmount\":\"5.00\"}"))
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
