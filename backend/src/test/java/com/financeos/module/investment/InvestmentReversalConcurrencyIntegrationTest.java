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
class InvestmentReversalConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void concurrentSameKeyAndHashWritesOneReversalAndReplaysTheSameReceipt() throws Exception {
        Long userId = seedBuy();

        List<Integer> statuses = concurrently(
                () -> reverse(userId, "same-key", "Broker correction"),
                () -> reverse(userId, "same-key", "Broker correction"));

        assertThat(statuses).containsOnly(200);
        assertReversedBuyFinalState(1, "0.00", 2);
    }

    @Test
    void concurrentSameKeyWithDifferentHashWritesOnceAndConflictsOnce() throws Exception {
        Long userId = seedBuy();

        List<Integer> statuses = concurrently(
                () -> reverse(userId, "same-key", "Broker correction"),
                () -> reverse(userId, "same-key", "Different reason"));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertReversedBuyFinalState(1, "0.00", 2);
    }

    @Test
    void concurrentDifferentKeysForOneOriginalWriteOnceAndConflictOnce() throws Exception {
        Long userId = seedBuy();

        List<Integer> statuses = concurrently(
                () -> reverse(userId, "first-key", "Broker correction"),
                () -> reverse(userId, "second-key", "Broker correction"));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertReversedBuyFinalState(1, "0.00", 2);
    }

    @Test
    void reversalAndSubsequentBuySerializeAndKeepOneEffectiveBuy() throws Exception {
        Long userId = seedBuy();

        List<Integer> statuses = concurrently(
                () -> reverse(userId, "reverse", "Broker correction"),
                () -> buy(userId, "subsequent-buy"));

        assertThat(statuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'REVERSAL'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-20.00");
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("2.00000000")).containsEntry("total_cost", new BigDecimal("20.00"))
                .containsEntry("projection_version", 3).containsEntry("last_transaction_id", 3L);
    }

    @Test
    void reversalRacesWithSellOrDividendWithoutCreatingAnInvalidHistory() throws Exception {
        Long sellUser = seedBuy();
        List<Integer> sellStatuses = concurrently(
                () -> reverse(sellUser, "reverse", "Broker correction"),
                () -> sell(sellUser, "sell"));
        assertThat(sellStatuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM assets WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("0.00000000");
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(2);

        jdbcTemplate.execute("TRUNCATE TABLE exchange_rates, market_quotes, transactions, accounts, categories, users RESTART IDENTITY CASCADE");
        Long dividendUser = seedBuy();
        List<Integer> dividendStatuses = concurrently(
                () -> reverse(dividendUser, "reverse", "Broker correction"),
                () -> dividend(dividendUser, "dividend"));
        assertThat(dividendStatuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM assets WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("0.00000000");
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(2);
    }

    @Test
    void reversalAndMarketOrLifecycleUpdatesPreserveTheirIndependentEffects() throws Exception {
        Long priceUser = seedBuy();
        List<Integer> priceStatuses = concurrently(
                () -> reverse(priceUser, "reverse", "Broker correction"),
                () -> updatePrice(priceUser));
        assertThat(priceStatuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForObject("SELECT current_price FROM assets WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("99.00");
        assertThat(jdbcTemplate.queryForObject("SELECT market_value FROM assets WHERE id = 1", BigDecimal.class))
                .isIn(new BigDecimal("0.00"), new BigDecimal("198.00"));

        jdbcTemplate.execute("TRUNCATE TABLE exchange_rates, market_quotes, transactions, accounts, categories, users RESTART IDENTITY CASCADE");
        Long lifecycleUser = seedBuy();
        List<Integer> lifecycleStatuses = concurrently(
                () -> reverse(lifecycleUser, "reverse", "Broker correction"),
                () -> deactivateAccount(lifecycleUser));
        assertThat(lifecycleStatuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM accounts WHERE id = 1", String.class)).isEqualTo("INACTIVE");
        assertReversedBuyFinalState(1, "0.00", 2);
    }

    private void assertReversedBuyFinalState(int expectedReversals, String balance, int projectionVersion) {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE transaction_type = 'REVERSAL'", Integer.class)).isEqualTo(expectedReversals);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo(balance);
        assertThat(jdbcTemplate.queryForMap("SELECT quantity, total_cost, projection_version, last_transaction_id FROM assets WHERE id = 1"))
                .containsEntry("quantity", new BigDecimal("0.00000000")).containsEntry("total_cost", new BigDecimal("0.00"))
                .containsEntry("projection_version", projectionVersion).containsEntry("last_transaction_id", 2L);
    }

    private List<Integer> concurrently(ThrowingSupplier first, ThrowingSupplier second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> one = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return first.get(); });
            Future<Integer> two = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return second.get(); });
            return List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
    }

    private Long seedBuy() throws Exception {
        Long userId = jdbcTemplate.queryForObject("INSERT INTO users (username, email, password_hash) VALUES ('reversal-concurrency', 'reversal-concurrency@example.com', 'hash') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)", userId);
        jdbcTemplate.update("INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status) VALUES (?, 'REVCON', 'Reversal Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')", userId);
        assertThat(firstBuy(userId, "first-buy")).isEqualTo(200);
        return userId;
    }

    private int reverse(Long userId, String key, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/transactions/1/reversal").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int firstBuy(Long userId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"instrumentId\":1,\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int buy(Long userId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/buy").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int sell(Long userId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/sell").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":\"2.00000000\",\"unitPrice\":\"10.00000000\",\"feeAmount\":\"0.00\",\"taxAmount\":\"0.00\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int dividend(Long userId, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/positions/1/dividends").with(authentication(auth(userId)))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grossAmount\":\"5.00\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int updatePrice(Long userId) throws Exception {
        return mockMvc.perform(put("/api/v1/assets/1/price").with(authentication(auth(userId))).param("price", "99.00"))
                .andReturn().getResponse().getStatus();
    }

    private int deactivateAccount(Long userId) throws Exception {
        return mockMvc.perform(post("/api/v1/accounts/1/deactivate").with(authentication(auth(userId))))
                .andReturn().getResponse().getStatus();
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @FunctionalInterface
    private interface ThrowingSupplier { int get() throws Exception; }
}
