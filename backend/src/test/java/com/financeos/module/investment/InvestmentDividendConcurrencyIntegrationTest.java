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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@AutoConfigureMockMvc
class InvestmentDividendConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void concurrentDifferentKeysCreateTwoDividendFactsAndAdvanceProjectionTwice() throws Exception {
        Long userId = seedOpenPosition();

        List<Integer> statuses = concurrently(
                () -> dividend(userId, "dividend-a", "5.00"),
                () -> dividend(userId, "dividend-b", "5.00"));

        assertThat(statuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-10.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000"))
                .containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("projection_version", 3)
                .containsEntry("last_transaction_id", 3L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND' AND account_balance_after IS NOT NULL AND projection_version_after IN (2, 3)", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentSameKeyDifferentHashWritesOneDividendAndReturnsOneConflict() throws Exception {
        Long userId = seedOpenPosition();

        List<Integer> statuses = concurrently(
                () -> dividend(userId, "same-key", "5.00"),
                () -> dividend(userId, "same-key", "6.00"));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class)).isEqualTo(1);
        BigDecimal balance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class);
        assertThat(balance.compareTo(new BigDecimal("-15.00"))).isIn(0, 1);
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentSameKeySameHashWritesOnceAndReplaysStoredReceipt() throws Exception {
        Long userId = seedOpenPosition();

        List<Integer> statuses = concurrently(
                () -> dividend(userId, "same-key", "5.00"),
                () -> dividend(userId, "same-key", "5.00"));

        assertThat(statuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-15.00");
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentDividendAndSubsequentBuyOnOpenPositionPreservePriceAndReplayProjection() throws Exception {
        Long userId = seedOpenPosition();
        jdbcTemplate.update("UPDATE assets SET current_price = 99.00, market_value = 198.00 WHERE id = 1");

        List<Integer> statuses = concurrently(
                () -> dividend(userId, "dividend", "5.00"),
                () -> buy(userId, "subsequent-buy", "1.00000000", "20.00000000"));

        assertThat(statuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, projection_version, current_price, market_value FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("3.00000000"))
                .containsEntry("avg_cost", new BigDecimal("13.33333333"))
                .containsEntry("total_cost", new BigDecimal("40.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("projection_version", 3);
        assertThat(jdbcTemplate.queryForObject("SELECT current_price FROM assets WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("99.00");
        assertThat(jdbcTemplate.queryForObject("SELECT market_value FROM assets WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("198.00");
    }

    @Test
    void concurrentDividendAndReopenBuyOnClosedPositionBothSucceed() throws Exception {
        Long userId = seedOpenPosition();
        assertThat(sell(userId, "close", "2.00000000", "10.00000000")).isEqualTo(200);

        List<Integer> statuses = concurrently(
                () -> dividend(userId, "closed-dividend", "5.00"),
                () -> buy(userId, "reopen-buy", "1.00000000", "20.00000000"));

        assertThat(statuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, position_status, projection_version FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("1.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("position_status", "OPEN")
                .containsEntry("projection_version", 4);
    }

    @Test
    void concurrentDeactivateAndDividendLeaveAccountInactiveAndCreditCashOnce() throws Exception {
        Long userId = seedOpenPosition();

        List<Integer> statuses = concurrently(
                () -> deactivate(userId),
                () -> dividend(userId, "dividend", "5.00"));

        assertThat(statuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM accounts WHERE id = 1", String.class)).isEqualTo("INACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-15.00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDividendAndPriceUpdatePreserveBothMarketAndLedgerFields() throws Exception {
        Long userId = seedOpenPosition();

        List<Integer> statuses = concurrently(
                () -> dividend(userId, "dividend", "5.00"),
                () -> updatePrice(userId, "99.00"));

        assertThat(statuses).containsOnly(200);
        var asset = jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, projection_version, current_price, market_value FROM assets WHERE id = 1");
        assertThat(asset).containsEntry("quantity", new BigDecimal("2.00000000"))
                .containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("projection_version", 2);
        assertThat((BigDecimal) asset.get("current_price")).isEqualByComparingTo("99.00");
        assertThat((BigDecimal) asset.get("market_value")).isEqualByComparingTo("198.00");
    }

    @Test
    void testLevelInstrumentDeactivateBeforeDividendStillCreditsThePosition() throws Exception {
        Long userId = seedOpenPosition();
        jdbcTemplate.update("UPDATE investment_instruments SET status = 'INACTIVE' WHERE id = 1");

        assertThat(dividend(userId, "inactive-instrument", "5.00")).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM investment_instruments WHERE id = 1", String.class)).isEqualTo("INACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-15.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, realized_profit_loss, position_status, projection_version FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000")).containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("20.00")).containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("position_status", "OPEN").containsEntry("projection_version", 2);
    }

    @Test
    void testLevelInstrumentDeactivateAfterDividendPreservesTheDividendReceipt() throws Exception {
        Long userId = seedOpenPosition();
        assertThat(dividend(userId, "active-instrument", "5.00")).isEqualTo(200);
        jdbcTemplate.update("UPDATE investment_instruments SET status = 'INACTIVE' WHERE id = 1");

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM investment_instruments WHERE id = 1", String.class)).isEqualTo("INACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'DIVIDEND' AND account_balance_after = -15.00 AND projection_version_after = 2", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-15.00");
    }

    @Test
    void concurrentDividendAndPartialSellSerializeWithoutChangingDividendCostFields() throws Exception {
        Long userId = seedOpenPosition();

        List<Integer> statuses = concurrently(
                () -> dividend(userId, "dividend", "5.00"),
                () -> sell(userId, "partial-sell", "1.00000000", "12.00000000"));

        assertThat(statuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-3.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, realized_profit_loss, position_status, projection_version FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("1.00000000"))
                .containsEntry("total_cost", new BigDecimal("10.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("2.00"))
                .containsEntry("position_status", "OPEN")
                .containsEntry("projection_version", 3);
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, unit_price, released_cost_amount, realized_profit_loss FROM investment_transactions WHERE transaction_type = 'DIVIDEND'"))
                .containsEntry("quantity", null).containsEntry("unit_price", null)
                .containsEntry("released_cost_amount", new BigDecimal("0.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"));
    }

    @Test
    void concurrentDividendAndFullSellLeaveClosedPositionWithoutCostTail() throws Exception {
        Long userId = seedOpenPosition();

        List<Integer> statuses = concurrently(
                () -> dividend(userId, "dividend", "5.00"),
                () -> sell(userId, "full-sell", "2.00000000", "10.00000000"));

        assertThat(statuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("5.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, avg_cost, total_cost, position_status, projection_version FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("0.00000000"))
                .containsEntry("avg_cost", new BigDecimal("0.00000000"))
                .containsEntry("total_cost", new BigDecimal("0.00"))
                .containsEntry("position_status", "CLOSED")
                .containsEntry("projection_version", 3);
    }

    private List<Integer> concurrently(ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> one = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return first.get(); });
            Future<Integer> two = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return second.get(); });
            return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
    }

    private int dividend(Long userId, String key, String gross) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"" + gross + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int sell(Long userId, String key, String quantity, String unitPrice) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/sell").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"" + quantity + "\",\"unitPrice\":\"" + unitPrice + "\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int buy(Long userId, String key, String quantity, String unitPrice) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/buy").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"" + quantity + "\",\"unitPrice\":\"" + unitPrice + "\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int deactivate(Long userId) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts/1/deactivate").with(authentication(auth(userId))))
                .andReturn().getResponse().getStatus();
    }

    private int updatePrice(Long userId, String price) throws Exception {
        return mockMvc.perform(put("/api/v1/assets/1/price").with(authentication(auth(userId)))
                        .param("price", price))
                .andReturn().getResponse().getStatus();
    }

    private Long seedOpenPosition() throws Exception {
        Long userId = jdbcTemplate.queryForObject("INSERT INTO users (username, email, password_hash) VALUES ('concurrency-user', 'concurrency@example.com', 'hash') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)", userId);
        jdbcTemplate.update("INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status) VALUES (?, 'CON', 'Concurrency Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')", userId);
        int status = mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                        .header("Idempotency-Key", "first-buy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"instrumentId\":1,\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(200);
        return userId;
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @FunctionalInterface
    private interface ThrowingSupplier { int get() throws Exception; }
}
