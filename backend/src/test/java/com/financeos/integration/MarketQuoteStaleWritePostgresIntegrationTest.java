package com.financeos.integration;

import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.config.MarketDataProperties;
import com.financeos.module.asset.marketdata.dto.MarketQuoteRefreshResult;
import com.financeos.module.asset.marketdata.dto.MarketQuoteResponse;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import com.financeos.module.asset.marketdata.service.*;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@Timeout(30)
class MarketQuoteStaleWritePostgresIntegrationTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T02:00:00Z");
    private static final Instant T1 = NOW.minusSeconds(7200);
    private static final Instant T2 = NOW.minusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @Autowired private AssetMapper assets;
    @SpyBean private MarketQuoteMapper quotes;
    @Autowired private MarketQuotePersistenceService persistence;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @SpyBean private MarketQuoteRateLimiter apiLimiter;
    @MockBean private MarketDataProvider provider;
    @MockBean private ExchangeRateProvider fxProvider;

    @BeforeEach
    void fixture() {
        ReflectionTestUtils.setField(apiLimiter, "windowStart", null);
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash) VALUES ('c8-user', 'c8@example.com', 'fixture-hash')");
        jdbcTemplate.update("""
                INSERT INTO assets (user_id, name, symbol, type, market, currency, quantity, avg_cost, current_price, market_value)
                VALUES (1, 'C8 stock', 'C8', 'STOCK', 'US', 'CNY', 2, 10, 12, 24)
                """);
    }

    @Test
    void olderProviderQuoteReturnsStoredStaleAuthorityWithoutAdvancingFetchedAt() {
        MarketQuote stored = entity("US", "C8", "200", T2, NOW.minusSeconds(1800), "NEWER");
        assertThat(quotes.upsertLatest(stored)).isEqualTo(1);
        clearInvocations(quotes);
        MarketQuoteService service = service(symbol -> candidate("100", T1, "OLDER"));
        Throwable failure = catchThrowable(() -> {
            MarketQuoteResponse response = service.refresh(1L, 1L);
            assertAuthority(response, stored);
            assertThat(response.refreshResult()).isEqualTo(MarketQuoteRefreshResult.STALE_FALLBACK);
            assertThat(response.freshness().name()).isEqualTo("STALE");
            assertThat(response.warning()).contains("older");
        });
        assertStored(stored);
        verify(quotes).upsertLatest(any());
        System.out.println("C8 stale candidate: database retained T2/200/NEWER; service failure=" + failure);
        assertThat(failure).isNull();
    }

    @RepeatedTest(3)
    void separateServiceInstancesReturnAuthorityAfterNewerCommitsFirst() throws Exception {
        CountDownLatch olderFetched = new CountDownLatch(1);
        CountDownLatch releaseOlder = new CountDownLatch(1);
        MarketQuoteService older = service(symbol -> {
            olderFetched.countDown();
            await(releaseOlder);
            return candidate("100", T1, "OLDER");
        });
        MarketQuoteService newer = service(symbol -> candidate("200", T2, "NEWER"));
        CopyOnWriteArrayList<Integer> affected = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            int rows = (int) invocation.callRealMethod();
            affected.add(rows);
            return rows;
        }).when(quotes).upsertLatest(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MarketQuoteResponse> oldResult = pool.submit(() -> older.refresh(1L, 1L));
            assertThat(olderFetched.await(10, TimeUnit.SECONDS)).isTrue();
            MarketQuoteResponse newResult = pool.submit(() -> newer.refresh(1L, 1L)).get(10, TimeUnit.SECONDS);
            assertThat(newResult.refreshResult()).isEqualTo(MarketQuoteRefreshResult.UPDATED);
            releaseOlder.countDown();
            Throwable failure = catchThrowable(() -> {
                MarketQuoteResponse oldResponse = oldResult.get(10, TimeUnit.SECONDS);
                assertAuthority(oldResponse, quotes.findByMarketAndSymbol("US", "C8"));
                assertThat(oldResponse.refreshResult()).isEqualTo(MarketQuoteRefreshResult.CACHE_HIT);
                assertThat(oldResponse.freshness().name()).isEqualTo("FRESH");
            });
            assertStored(entity("US", "C8", "200", T2, NOW, "NEWER"));
            assertThat(affected).containsExactly(1, 0);
            System.out.println("C8 separate instances: affected=" + affected + "; final=T2/200/NOW/NEWER; older failure=" + failure);
            assertThat(failure).isNull();
        } finally {
            releaseOlder.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void mapperInsertsUpdatesEqualAndNewerTimesButRejectsOlderWithoutCrossKeyWrites() {
        MarketQuote initial = entity("US", "C8", "100", T1, NOW.minusSeconds(1800), "INITIAL");
        assertThat(quotes.upsertLatest(initial)).isEqualTo(1);
        MarketQuote equal = entity("US", "C8", "150", T1, NOW.minusSeconds(900), "EQUAL");
        assertThat(quotes.upsertLatest(equal)).isEqualTo(1);
        assertStored(equal);
        MarketQuote newer = entity("US", "C8", "200", T2, NOW, "NEWER");
        assertThat(quotes.upsertLatest(newer)).isEqualTo(1);
        assertThat(quotes.upsertLatest(initial)).isZero();
        assertStored(newer);
        assertThat(quotes.upsertLatest(entity("CN", "C8", "300", T1, NOW, "CN"))).isEqualTo(1);
        assertThat(quotes.upsertLatest(entity("US", "OTHER", "400", T1, NOW, "OTHER"))).isEqualTo(1);
        assertStored(newer);
        assertThat(quotes.findByMarketAndSymbol("CN", "C8").getPrice()).isEqualByComparingTo("300");
        assertThat(quotes.findByMarketAndSymbol("US", "OTHER").getPrice()).isEqualByComparingTo("400");
    }

    @Test
    void controllerReturnsStoredStaleQuoteInsteadOf500() throws Exception {
        Instant fetched = Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        MarketQuote stored = entity("US", "C8", "200", T2, fetched, "NEWER");
        quotes.upsertLatest(stored);
        when(provider.fetchQuote("C8")).thenReturn(candidate("100", T1, "OLDER"));
        mvc.perform(post("/api/v1/assets/1/quote/refresh")
                        .with(authentication(new UsernamePasswordAuthenticationToken(1L, null, List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(200))
                .andExpect(jsonPath("$.data.provider").value("NEWER"))
                .andExpect(jsonPath("$.data.quoteTime").value(T2.toString()))
                .andExpect(jsonPath("$.data.fetchedAt").value(fetched.toString()))
                .andExpect(jsonPath("$.data.refreshResult").value("STALE_FALLBACK"))
                .andExpect(jsonPath("$.data.freshness").value("STALE"));
        assertStored(stored);
    }

    @Test
    void controllerInsertEqualAndNewerUpdatesReturnStoredNumericAndTimestampPrecision() throws Exception {
        for (Instant time : List.of(T1, T1, T2)) {
            when(provider.fetchQuote("C8")).thenReturn(candidate("10.123456789", time, "WRITTEN"));
            JsonNode body = refreshApi(1L, "/quote/refresh", 200);
            MarketQuote stored = quotes.findByMarketAndSymbol("US", "C8");
            assertThat(body.path("price").decimalValue()).isEqualByComparingTo(stored.getPrice());
            assertThat(stored.getPrice()).isEqualByComparingTo("10.12345679");
            assertThat(body.path("quoteTime").asText()).isEqualTo(stored.getQuoteTime().toString());
            assertThat(body.path("fetchedAt").asText()).isEqualTo(stored.getFetchedAt().toString());
            assertThat(body.path("provider").asText()).isEqualTo(stored.getProvider());
            assertThat(body.path("refreshResult").asText()).isEqualTo("UPDATED");
            // Force TTL expiry for the next explicit refresh; quoteTime remains unchanged.
            jdbcTemplate.update("UPDATE market_quotes SET fetched_at = fetched_at - INTERVAL '1 hour'");
        }
    }

    @ParameterizedTest
    @CsvSource({"NOT_FOUND,404", "RATE_LIMITED,429", "RESPONSE_FORMAT,502", "INVALID_QUOTE,502",
            "INVALID_REQUEST,502", "UPSTREAM_ERROR,502", "DISABLED,503", "AUTHENTICATION,503",
            "PROVIDER_UNAVAILABLE,503", "TRANSPORT,503"})
    void noCacheProviderErrorsKeepHttpMapping(MarketDataErrorType error, int expected) throws Exception {
        when(provider.fetchQuote("C8")).thenThrow(new MarketDataProviderException(error, "fixture provider error"));
        refreshApi(1L, "/quote/refresh", expected);
        assertThat(quotes.findByMarketAndSymbol("US", "C8")).isNull();
        verify(provider).fetchQuote("C8");
    }

    @Test
    void providerFailureAndLocalLimitKeepStaleFallbackAndNoCacheLimitRemains429() throws Exception {
        MarketQuote stored = entity("US", "C8", "200", T2, NOW.minusSeconds(7200), "NEWER");
        quotes.upsertLatest(stored);
        when(provider.fetchQuote("C8")).thenThrow(new MarketDataProviderException(MarketDataErrorType.TRANSPORT, "fixture"));
        assertThat(refreshApi(1L, "/quote/refresh", 200).path("refreshResult").asText()).isEqualTo("STALE_FALLBACK");
        assertStored(stored);
        clearInvocations(provider);
        doReturn(false).when(apiLimiter).tryAcquire(1L);
        assertThat(refreshApi(1L, "/quote/refresh", 200).path("refreshResult").asText()).isEqualTo("STALE_FALLBACK");
        verifyNoInteractions(provider);
        jdbcTemplate.update("DELETE FROM market_quotes");
        refreshApi(1L, "/quote/refresh", 429);
        verifyNoInteractions(provider);
    }

    @Test
    void ownershipIsCheckedBeforeCacheProviderAndPersistence() throws Exception {
        quotes.upsertLatest(entity("US", "C8", "200", T2, Instant.now(), "NEWER"));
        clearInvocations(quotes, apiLimiter);
        refreshApi(2L, "/quote/refresh", 404);
        verifyNoInteractions(provider, quotes, apiLimiter);
    }

    @Test
    void referenceValuationAndReadOnlyGetUseAuthorityAndLeaveFinancialFactsUnchanged() throws Exception {
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Broker', 'BROKERAGE', 'CNY', 100)");
        jdbcTemplate.update("INSERT INTO categories (user_id, name, type) VALUES (1, 'Fixture income', 'INCOME')");
        jdbcTemplate.update("""
                INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, transacted_at)
                VALUES (1, 1, 1, 'INCOME', 100, 'CNY', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (1, 'FACT', 'Fact instrument', 'CN', 'STOCK', 'CNY', 'ACTIVE')
                """);
        mvc.perform(post("/api/v1/investment/positions").with(authentication(auth(1L)))
                        .header("Idempotency-Key", "c8-financial-fact")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":1,"instrumentId":1,"quantity":"2.00000000","unitPrice":"10.00000000","feeAmount":"0.00","taxAmount":"0.00"}
                                """))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isPositive();
        Map<String, String> before = financialState();
        MarketQuote stored = entity("US", "C8", "200", T2, NOW.minusSeconds(7200), "NEWER");
        quotes.upsertLatest(stored);
        jdbcTemplate.update("""
                INSERT INTO exchange_rates (base_currency, quote_currency, rate, rate_time, fetched_at, provider)
                VALUES ('USD', 'CNY', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'TEST_FX')
                """);
        when(provider.fetchQuote("C8")).thenReturn(candidate("100", T1, "OLDER"));
        JsonNode result = refreshApi(1L, "/reference-valuation/refresh", 200);
        assertThat(result.path("quotePrice").decimalValue()).isEqualByComparingTo("200");
        assertThat(result.path("quoteProvider").asText()).isEqualTo("NEWER");
        assertThat(result.path("quoteTime").asText()).isEqualTo(T2.toString());
        assertThat(result.path("quoteFreshness").asText()).isEqualTo("STALE");
        assertThat(result.path("baseCurrencyMarketValue").decimalValue()).isEqualByComparingTo("2800");
        assertStored(stored);
        verify(provider).fetchQuote("C8");
        clearInvocations(provider);
        for (String path : List.of("/api/v1/assets/1", "/api/v1/assets", "/api/v1/assets/page")) {
            mvc.perform(get(path).with(authentication(auth(1L)))).andExpect(status().isOk());
        }
        verifyNoInteractions(provider, fxProvider);
        assertThat(financialState()).isEqualTo(before);
    }

    private Map<String, String> financialState() {
        return List.of("accounts", "transactions", "assets", "investment_transactions", "investment_instruments")
                .stream().collect(java.util.stream.Collectors.toMap(table -> table,
                        table -> jdbcTemplate.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]') FROM "
                                + table + " t", String.class)));
    }

    private JsonNode refreshApi(Long userId, String suffix, int status) throws Exception {
        var result = mvc.perform(post("/api/v1/assets/1" + suffix).with(authentication(auth(userId))))
                .andExpect(status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of());
    }

    private MarketQuoteService service(MarketDataProvider source) {
        MarketDataProperties properties = new MarketDataProperties();
        return new MarketQuoteService(assets, source, new MarketQuoteQueryService(quotes, properties, CLOCK),
                persistence, new MarketQuoteRateLimiter(properties, CLOCK), CLOCK);
    }

    private static MarketDataQuote candidate(String price, Instant time, String source) {
        return new MarketDataQuote("US", "C8", "USD", new BigDecimal(price), time, source);
    }

    private static MarketQuote entity(String market, String symbol, String price, Instant time, Instant fetched, String source) {
        MarketQuote quote = new MarketQuote();
        quote.setMarket(market);
        quote.setSymbol(symbol);
        quote.setCurrency("USD");
        quote.setPrice(new BigDecimal(price));
        quote.setQuoteTime(time);
        quote.setFetchedAt(fetched);
        quote.setProvider(source);
        return quote;
    }

    private void assertStored(MarketQuote expected) {
        MarketQuote actual = quotes.findByMarketAndSymbol(expected.getMarket(), expected.getSymbol());
        assertThat(actual.getQuoteTime()).isEqualTo(expected.getQuoteTime());
        assertThat(actual.getPrice()).isEqualByComparingTo(expected.getPrice());
        assertThat(actual.getFetchedAt()).isEqualTo(expected.getFetchedAt());
        assertThat(actual.getProvider()).isEqualTo(expected.getProvider());
    }

    private static void assertAuthority(MarketQuoteResponse response, MarketQuote stored) {
        assertThat(response.market()).isEqualTo(stored.getMarket());
        assertThat(response.symbol()).isEqualTo(stored.getSymbol());
        assertThat(response.currency()).isEqualTo(stored.getCurrency());
        assertThat(response.price()).isEqualByComparingTo(stored.getPrice());
        assertThat(response.quoteTime()).isEqualTo(stored.getQuoteTime());
        assertThat(response.fetchedAt()).isEqualTo(stored.getFetchedAt());
        assertThat(response.provider()).isEqualTo(stored.getProvider());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
