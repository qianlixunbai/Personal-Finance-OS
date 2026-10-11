package com.financeos.module.asset.marketdata.service;

import com.financeos.common.BusinessException;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.config.MarketDataProperties;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteRefreshResult;
import com.financeos.module.asset.marketdata.dto.MarketQuoteResponse;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Timeout(20)
class MarketQuoteServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T02:00:00Z");
    private final AssetMapper assets = mock(AssetMapper.class);
    private final MarketQuoteMapper quotes = mock(MarketQuoteMapper.class);
    private final MarketDataProvider provider = mock(MarketDataProvider.class);
    private final MarketQuotePersistenceService persistence = mock(MarketQuotePersistenceService.class);
    private final MarketDataProperties properties = new MarketDataProperties();
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final MarketQuoteQueryService query = new MarketQuoteQueryService(quotes, properties, clock);
    private final MarketQuoteRateLimiter limiter = spy(new MarketQuoteRateLimiter(properties, clock));
    private final MarketQuoteService service = new MarketQuoteService(assets, provider, query, persistence, limiter, clock);

    @BeforeEach
    void setup() {
        Asset asset = new Asset();
        asset.setUserId(1L);
        asset.setType("STOCK");
        asset.setMarket("US");
        asset.setSymbol("C8");
        when(assets.selectById(1L)).thenReturn(asset);
        when(provider.fetchQuote("C8")).thenReturn(new MarketDataQuote("US", "C8", "USD", BigDecimal.TEN, NOW, "TEST"));
        when(persistence.upsert(any())).thenReturn(new MarketQuotePersistenceService.UpsertResult(quote(NOW), true));
    }

    @Test
    void ttlBoundaryHitsCacheWithoutProviderOrQuotaAndExpiresImmediatelyAfter() {
        MarketQuote cached = quote(NOW.minus(properties.getCacheTtl()));
        when(quotes.findByMarketAndSymbol("US", "C8")).thenReturn(cached);
        assertThat(service.refresh(1L, 1L).refreshResult()).isEqualTo(MarketQuoteRefreshResult.CACHE_HIT);
        verifyNoInteractions(provider, persistence, limiter);
        now.set(NOW.plusNanos(1));
        assertThat(query.freshnessOf(cached)).isEqualTo(MarketQuoteFreshness.STALE);
        assertThat(service.refresh(1L, 1L).refreshResult()).isEqualTo(MarketQuoteRefreshResult.UPDATED);
        verify(provider).fetchQuote("C8");
        verify(limiter).tryAcquire(1L);
    }

    @Test
    void perUserAndGlobalQuotaResetAtMinuteBoundary() {
        properties.setUserRequestLimitPerMinute(1);
        properties.setProviderRequestLimitPerMinute(2);
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).isTrue();
        assertThat(limiter.tryAcquire(3L)).isFalse();
        now.set(NOW.plusSeconds(60));
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(2L)).isTrue();
    }

    @Test
    void rejectedCandidateStillConsumesProviderQuotaAndCannotRenewTtl() {
        properties.setUserRequestLimitPerMinute(1);
        MarketQuote stored = quote(NOW.minusSeconds(1800));
        when(quotes.findByMarketAndSymbol("US", "C8")).thenReturn(stored);
        when(persistence.upsert(any())).thenReturn(new MarketQuotePersistenceService.UpsertResult(stored, false));
        MarketQuoteResponse first = service.refresh(1L, 1L);
        assertThat(first.refreshResult()).isEqualTo(MarketQuoteRefreshResult.STALE_FALLBACK);
        assertThat(first.fetchedAt()).isEqualTo(stored.getFetchedAt());
        assertThat(first.warning()).contains("older");
        MarketQuoteResponse limited = service.refresh(1L, 1L);
        assertThat(limited.refreshResult()).isEqualTo(MarketQuoteRefreshResult.STALE_FALLBACK);
        assertThat(limited.warning()).contains("Refresh failed");
        verify(provider, times(1)).fetchQuote("C8");
        verify(persistence, times(1)).upsert(any());
    }

    @Test
    void databaseFailureIsNotMisreportedAsProviderFallbackAndFlightIsCleanedForRetry() {
        when(quotes.findByMarketAndSymbol("US", "C8")).thenReturn(quote(NOW.minusSeconds(1800)));
        when(persistence.upsert(any())).thenThrow(new IllegalStateException("database failure"))
                .thenReturn(new MarketQuotePersistenceService.UpsertResult(quote(NOW), true));
        assertThatThrownBy(() -> service.refresh(1L, 1L)).isInstanceOf(IllegalStateException.class);
        assertThat(service.refresh(1L, 1L).refreshResult()).isEqualTo(MarketQuoteRefreshResult.UPDATED);
        verify(provider, times(2)).fetchQuote("C8");
    }

    @Test
    void invalidProviderCandidatePreservesStoredQuoteWithoutWriting() {
        MarketQuote cached = quote(NOW.minusSeconds(1800));
        when(quotes.findByMarketAndSymbol("US", "C8")).thenReturn(cached);
        when(provider.fetchQuote("C8")).thenReturn(new MarketDataQuote("CN", "OTHER", "USD", BigDecimal.TEN, NOW, "TEST"));
        assertThat(service.refresh(1L, 1L).refreshResult()).isEqualTo(MarketQuoteRefreshResult.STALE_FALLBACK);
        verifyNoInteractions(persistence);
    }

    @Test
    void sameInstanceSingleFlightSharesResultAndOnlyLeaderConsumesQuota() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> followerThread = new AtomicReference<>();
        doAnswer(invocation -> {
            providerEntered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Provider release timed out");
            return new MarketDataQuote("US", "C8", "USD", BigDecimal.TEN, NOW, "TEST");
        }).when(provider).fetchQuote("C8");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MarketQuoteResponse> leader = pool.submit(() -> service.refresh(1L, 1L));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MarketQuoteResponse> follower = pool.submit(() -> {
                followerThread.set(Thread.currentThread());
                return service.refresh(1L, 1L);
            });
            // Observe the actual follower join before allowing the leader to finish.
            // This avoids mistaking a later CACHE_HIT for single-flight coverage.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean joined = false;
            while (System.nanoTime() < deadline) {
                Thread thread = followerThread.get();
                if (thread != null && Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(CompletableFuture.class.getName()) && frame.getMethodName().equals("join"))) {
                    joined = true;
                    break;
                }
                Thread.yield();
            }
            assertThat(joined).isTrue();
            release.countDown();
            assertThat(follower.get(5, TimeUnit.SECONDS)).isSameAs(leader.get(5, TimeUnit.SECONDS));
            verify(provider).fetchQuote("C8");
            verify(persistence).upsert(any());
            verify(limiter).tryAcquire(1L);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static MarketQuote quote(Instant fetchedAt) {
        MarketQuote quote = new MarketQuote();
        quote.setMarket("US");
        quote.setSymbol("C8");
        quote.setCurrency("USD");
        quote.setPrice(BigDecimal.TEN);
        quote.setQuoteTime(NOW);
        quote.setFetchedAt(fetchedAt);
        quote.setProvider("TEST");
        return quote;
    }
}
