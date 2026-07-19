package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.config.FxDataProperties;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProvider;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProviderException;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExchangeRateServiceConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void singleFlightCallsProviderOnceForTenConcurrentRequestsAndChargesOnlyTheLeader() throws Exception {
        Fixture fixture = fixture(1, 2);
        blockInitialCacheLookups(fixture, 10, null);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            providerStarted.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return quote("USD", "CNY");
        }).when(fixture.provider).fetchRate("USD", "CNY");
        when(fixture.persistence.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ExchangeRateRefreshResult> results = concurrently(10,
                () -> fixture.service.refreshRate(42L, "USD", "CNY"), providerStarted, releaseProvider);

        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(ExchangeRateRefreshStatus.UPDATED));
        assertThat(calls).hasValue(1);
        verify(fixture.persistence).upsert(any());
        assertThat(fixture.limiter.tryAcquire(42L)).isFalse();
        assertThat(fixture.limiter.tryAcquire(99L)).isTrue();
    }

    @Test
    void failedSingleFlightReturnsOneStaleFallbackAndAllowsTheNextRequestToRetry() throws Exception {
        Fixture fixture = fixture(5, 30);
        ExchangeRate old = rate("USD", "CNY", NOW.minusSeconds(3601), "7.20");
        blockInitialCacheLookups(fixture, 10, old);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                providerStarted.countDown();
                assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
                throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.TIMEOUT);
            }
            return quote("USD", "CNY");
        }).when(fixture.provider).fetchRate("USD", "CNY");
        when(fixture.persistence.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ExchangeRateRefreshResult> results = concurrently(10,
                () -> fixture.service.refreshRate(42L, "USD", "CNY"), providerStarted, releaseProvider);

        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(ExchangeRateRefreshStatus.STALE_FALLBACK);
            assertThat(result.rate()).isEqualByComparingTo(old.getRate());
            assertThat(result.warningCode()).isEqualTo("FX_REFRESH_FAILED");
        });
        assertThat(fixture.service.refreshRate(42L, "USD", "CNY").status()).isEqualTo(ExchangeRateRefreshStatus.UPDATED);
        assertThat(calls).hasValue(2);
    }

    @Test
    void differentCurrencyPairsReachTheProviderConcurrently() throws Exception {
        Fixture fixture = fixture(5, 30);
        CountDownLatch bothProviderCalls = new CountDownLatch(2);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothProviderCalls.countDown();
            assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
            return quote(invocation.getArgument(0), invocation.getArgument(1));
        }).when(fixture.provider).fetchRate(any(), any());
        when(fixture.persistence.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ExchangeRateRefreshResult> usd = executor.submit(() -> fixture.service.refreshRate(1L, "USD", "CNY"));
            Future<ExchangeRateRefreshResult> eur = executor.submit(() -> fixture.service.refreshRate(2L, "EUR", "CNY"));
            assertThat(bothProviderCalls.await(5, TimeUnit.SECONDS)).isTrue();
            releaseProvider.countDown();
            assertThat(usd.get(5, TimeUnit.SECONDS).status()).isEqualTo(ExchangeRateRefreshStatus.UPDATED);
            assertThat(eur.get(5, TimeUnit.SECONDS).status()).isEqualTo(ExchangeRateRefreshStatus.UPDATED);
        } finally {
            executor.shutdownNow();
        }
        verify(fixture.provider).fetchRate("USD", "CNY");
        verify(fixture.provider).fetchRate("EUR", "CNY");
    }

    @Test
    void skipsProviderAndQuotaWhenTheSecondCacheCheckFindsAFreshSnapshot() {
        Fixture fixture = fixture(1, 1);
        ExchangeRate stale = rate("USD", "CNY", NOW.minusSeconds(3600), "7.20");
        ExchangeRate fresh = rate("USD", "CNY", NOW.minusSeconds(1), "7.21");
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(stale, fresh);

        ExchangeRateRefreshResult result = fixture.service.refreshRate(42L, "USD", "CNY");

        assertThat(result.status()).isEqualTo(ExchangeRateRefreshStatus.CACHE_HIT);
        verifyNoInteractions(fixture.provider, fixture.persistence);
        assertThat(fixture.limiter.tryAcquire(42L)).isTrue();
    }

    @Test
    void persistenceFailureFallsBackToTheUnchangedSnapshotAndNoCacheFailureCanRetry() {
        Fixture staleFixture = fixture(5, 30);
        ExchangeRate old = rate("USD", "CNY", NOW.minusSeconds(3601), "7.20");
        when(staleFixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(old);
        when(staleFixture.provider.fetchRate("USD", "CNY")).thenReturn(quote("USD", "CNY"));
        when(staleFixture.persistence.upsert(any())).thenThrow(new IllegalStateException("persistence failed"));

        ExchangeRateRefreshResult fallback = staleFixture.service.refreshRate(1L, "USD", "CNY");

        assertThat(fallback.status()).isEqualTo(ExchangeRateRefreshStatus.STALE_FALLBACK);
        assertThat(fallback.rate()).isEqualByComparingTo("7.20");
        assertThat(old.getRate()).isEqualByComparingTo("7.20");
        verify(staleFixture.persistence).upsert(any());

        Fixture emptyFixture = fixture(5, 30);
        when(emptyFixture.provider.fetchRate("USD", "CNY")).thenReturn(quote("USD", "CNY"));
        when(emptyFixture.persistence.upsert(any())).thenThrow(new IllegalStateException("persistence failed"));

        assertThatThrownBy(() -> emptyFixture.service.refreshRate(1L, "USD", "CNY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("persistence failed");
        doAnswer(invocation -> invocation.getArgument(0)).when(emptyFixture.persistence).upsert(any());
        assertThat(emptyFixture.service.refreshRate(1L, "USD", "CNY").status()).isEqualTo(ExchangeRateRefreshStatus.UPDATED);
        verify(emptyFixture.provider, org.mockito.Mockito.times(2)).fetchRate("USD", "CNY");
    }

    @Test
    void fallsBackToTheSecondStaleSnapshotWhenTheFirstLookupIsEmptyAndProviderFails() {
        Fixture fixture = fixture(1, 2);
        ExchangeRate second = staleRate("SECOND", NOW.minusSeconds(3600), NOW.minusSeconds(30), "7.21");
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(null, second);
        when(fixture.provider.fetchRate("USD", "CNY"))
                .thenThrow(new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.TIMEOUT));

        ExchangeRateRefreshResult result = fixture.service.refreshRate(42L, "USD", "CNY");

        assertStaleFallback(result, second);
        verify(fixture.provider).fetchRate("USD", "CNY");
        verifyNoInteractions(fixture.persistence);
        assertThat(fixture.limiter.tryAcquire(42L)).isFalse();
    }

    @Test
    void fallsBackToTheNewerSecondStaleSnapshotWhenProviderFails() {
        Fixture fixture = fixture(5, 30);
        ExchangeRate first = staleRate("FIRST", NOW.minusSeconds(7200), NOW.minusSeconds(120), "7.20");
        ExchangeRate second = staleRate("SECOND", NOW.minusSeconds(3600), NOW.minusSeconds(30), "7.21");
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(first, second);
        when(fixture.provider.fetchRate("USD", "CNY"))
                .thenThrow(new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.TIMEOUT));

        ExchangeRateRefreshResult result = fixture.service.refreshRate(42L, "USD", "CNY");

        assertStaleFallback(result, second);
        verify(fixture.provider).fetchRate("USD", "CNY");
        verifyNoInteractions(fixture.persistence);
    }

    @Test
    void persistenceFailureFallsBackToTheSecondSnapshotWhenTheFirstLookupIsEmpty() {
        Fixture fixture = fixture(5, 30);
        ExchangeRate second = staleRate("SECOND", NOW.minusSeconds(3600), NOW.minusSeconds(30), "7.21");
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(null, second);
        when(fixture.provider.fetchRate("USD", "CNY")).thenReturn(quote("USD", "CNY"));
        when(fixture.persistence.upsert(any())).thenThrow(new IllegalStateException("persistence failed"));

        ExchangeRateRefreshResult result = fixture.service.refreshRate(42L, "USD", "CNY");

        assertStaleFallback(result, second);
        verify(fixture.provider).fetchRate("USD", "CNY");
        verify(fixture.persistence).upsert(any());
    }

    @Test
    void persistenceFailureFallsBackToTheNewerSecondSnapshot() {
        Fixture fixture = fixture(5, 30);
        ExchangeRate first = staleRate("FIRST", NOW.minusSeconds(7200), NOW.minusSeconds(120), "7.20");
        ExchangeRate second = staleRate("SECOND", NOW.minusSeconds(3600), NOW.minusSeconds(30), "7.21");
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(first, second);
        when(fixture.provider.fetchRate("USD", "CNY")).thenReturn(quote("USD", "CNY"));
        when(fixture.persistence.upsert(any())).thenThrow(new IllegalStateException("persistence failed"));

        ExchangeRateRefreshResult result = fixture.service.refreshRate(42L, "USD", "CNY");

        assertStaleFallback(result, second);
        verify(fixture.provider).fetchRate("USD", "CNY");
        verify(fixture.persistence).upsert(any());
    }

    @Test
    void followersShareTheLeadersNewerSecondSnapshotAfterAProviderFailure() throws Exception {
        Fixture fixture = fixture(1, 30);
        ExchangeRate first = staleRate("FIRST", NOW.minusSeconds(7200), NOW.minusSeconds(120), "7.20");
        ExchangeRate second = staleRate("SECOND", NOW.minusSeconds(3600), NOW.minusSeconds(30), "7.21");
        blockInitialCacheLookupsThenReturnSecondSnapshot(fixture, 10, first, second);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                providerStarted.countDown();
                assertThat(releaseProvider.await(5, TimeUnit.SECONDS)).isTrue();
                throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.TIMEOUT);
            }
            return quote("USD", "CNY");
        }).when(fixture.provider).fetchRate("USD", "CNY");
        when(fixture.persistence.upsert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ExchangeRateRefreshResult> results = concurrently(10,
                () -> fixture.service.refreshRate(42L, "USD", "CNY"), providerStarted, releaseProvider);

        assertThat(results).allSatisfy(result -> assertStaleFallback(result, second));
        assertThat(calls).hasValue(1);
        verifyNoInteractions(fixture.persistence);
        assertThat(fixture.limiter.tryAcquire(42L)).isFalse();
        assertThat(fixture.service.refreshRate(99L, "USD", "CNY").status()).isEqualTo(ExchangeRateRefreshStatus.UPDATED);
        assertThat(calls).hasValue(2);
    }

    private List<ExchangeRateRefreshResult> concurrently(int count, ThrowingSupplier<ExchangeRateRefreshResult> action,
                                                           CountDownLatch providerStarted, CountDownLatch releaseProvider) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CyclicBarrier barrier = new CyclicBarrier(count);
        try {
            List<Future<ExchangeRateRefreshResult>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return action.get();
                }));
            }
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseProvider.countDown();
            List<ExchangeRateRefreshResult> results = new ArrayList<>();
            for (Future<ExchangeRateRefreshResult> future : futures) results.add(future.get(5, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture fixture(int perUser, int global) {
        FxDataProperties properties = new FxDataProperties();
        properties.setEnabled(true);
        properties.setCacheTtl(java.time.Duration.ofMinutes(60));
        properties.getRateLimit().setPerUserPerMinute(perUser);
        properties.getRateLimit().setGlobalPerMinute(global);
        ExchangeRateMapper mapper = mock(ExchangeRateMapper.class);
        ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
        ExchangeRatePersistenceService persistence = mock(ExchangeRatePersistenceService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExchangeRateRateLimiter limiter = new ExchangeRateRateLimiter(properties, clock);
        ExchangeRateQueryService query = new ExchangeRateQueryService(mapper, properties, clock);
        return new Fixture(mapper, provider, persistence, limiter,
                new ExchangeRateService(query, persistence, limiter, properties, provider, clock));
    }

    private void blockInitialCacheLookups(Fixture fixture, int callerCount, ExchangeRate result) {
        CountDownLatch allInitialLookups = new CountDownLatch(callerCount);
        AtomicInteger lookups = new AtomicInteger();
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenAnswer(invocation -> {
            if (lookups.incrementAndGet() <= callerCount) {
                allInitialLookups.countDown();
                assertThat(allInitialLookups.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        });
    }

    private void blockInitialCacheLookupsThenReturnSecondSnapshot(Fixture fixture, int callerCount, ExchangeRate first, ExchangeRate second) {
        CountDownLatch allInitialLookups = new CountDownLatch(callerCount);
        AtomicInteger lookups = new AtomicInteger();
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenAnswer(invocation -> {
            if (lookups.incrementAndGet() <= callerCount) {
                allInitialLookups.countDown();
                assertThat(allInitialLookups.await(5, TimeUnit.SECONDS)).isTrue();
                return first;
            }
            return second;
        });
    }

    private ExchangeRateQuote quote(String base, String quote) {
        return new ExchangeRateQuote(base, quote, new BigDecimal("7.2345678901234"), NOW.minusSeconds(1), "TEST");
    }

    private ExchangeRate rate(String base, String quote, Instant fetchedAt, String value) {
        ExchangeRate rate = new ExchangeRate();
        rate.setBaseCurrency(base);
        rate.setQuoteCurrency(quote);
        rate.setRate(new BigDecimal(value));
        rate.setRateTime(NOW.minusSeconds(60));
        rate.setFetchedAt(fetchedAt);
        rate.setProvider("TEST");
        return rate;
    }

    private ExchangeRate staleRate(String provider, Instant fetchedAt, Instant rateTime, String value) {
        ExchangeRate rate = rate("USD", "CNY", fetchedAt, value);
        rate.setRateTime(rateTime);
        rate.setProvider(provider);
        return rate;
    }

    private void assertStaleFallback(ExchangeRateRefreshResult result, ExchangeRate expected) {
        assertThat(result.status()).isEqualTo(ExchangeRateRefreshStatus.STALE_FALLBACK);
        assertThat(result.freshness()).isEqualTo(ExchangeRateFreshness.STALE);
        assertThat(result.rate()).isEqualByComparingTo(expected.getRate());
        assertThat(result.rateTime()).isEqualTo(expected.getRateTime());
        assertThat(result.fetchedAt()).isEqualTo(expected.getFetchedAt());
        assertThat(result.provider()).isEqualTo(expected.getProvider());
        assertThat(result.warningCode()).isEqualTo("FX_REFRESH_FAILED");
    }

    private record Fixture(ExchangeRateMapper mapper, ExchangeRateProvider provider, ExchangeRatePersistenceService persistence,
                           ExchangeRateRateLimiter limiter, ExchangeRateService service) { }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
