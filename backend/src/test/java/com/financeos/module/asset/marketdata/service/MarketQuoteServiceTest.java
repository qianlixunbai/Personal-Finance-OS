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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketQuoteServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void returnsCacheHitAtTheExactTtlBoundaryWithoutCallingProvider() {
        Fixture fixture = fixture();
        MarketQuote cached = quote("aapl", NOW.minusSeconds(900));
        when(fixture.assetMapper.selectById(7L)).thenReturn(stock(" aapl "));
        when(fixture.quoteMapper.findByMarketAndSymbol("US", "AAPL")).thenReturn(cached);

        MarketQuoteResponse response = fixture.service.refresh(1L, 7L);

        assertThat(response.refreshResult()).isEqualTo(MarketQuoteRefreshResult.CACHE_HIT);
        assertThat(response.freshness()).isEqualTo(MarketQuoteFreshness.FRESH);
        verify(fixture.provider, never()).fetchQuote(any());
        verify(fixture.persistence, never()).upsert(any());
    }

    @Test
    void refreshesStaleQuoteAndPersistsWithInjectedClock() {
        Fixture fixture = fixture();
        when(fixture.assetMapper.selectById(7L)).thenReturn(stock("aapl"));
        when(fixture.quoteMapper.findByMarketAndSymbol("US", "AAPL")).thenReturn(quote("AAPL", NOW.minusSeconds(901)));
        when(fixture.provider.fetchQuote("AAPL")).thenReturn(providerQuote("AAPL"));

        MarketQuoteResponse response = fixture.service.refresh(1L, 7L);

        assertThat(response.refreshResult()).isEqualTo(MarketQuoteRefreshResult.UPDATED);
        assertThat(response.fetchedAt()).isEqualTo(NOW);
        assertThat(response.price()).isEqualByComparingTo("212.34");
        org.mockito.ArgumentCaptor<MarketQuote> persisted = org.mockito.ArgumentCaptor.forClass(MarketQuote.class);
        verify(fixture.persistence).upsert(persisted.capture());
        assertThat(persisted.getValue().getFetchedAt()).isEqualTo(NOW);
        assertThat(persisted.getValue().getSymbol()).isEqualTo("AAPL");
    }

    @Test
    void returnsStaleFallbackWithoutChangingOldQuoteWhenProviderFails() {
        Fixture fixture = fixture();
        MarketQuote oldQuote = quote("AAPL", NOW.minusSeconds(901));
        when(fixture.assetMapper.selectById(7L)).thenReturn(stock("AAPL"));
        when(fixture.quoteMapper.findByMarketAndSymbol("US", "AAPL")).thenReturn(oldQuote);
        when(fixture.provider.fetchQuote("AAPL"))
                .thenThrow(new MarketDataProviderException(MarketDataErrorType.TRANSPORT, "network failure"));

        MarketQuoteResponse response = fixture.service.refresh(1L, 7L);

        assertThat(response.refreshResult()).isEqualTo(MarketQuoteRefreshResult.STALE_FALLBACK);
        assertThat(response.freshness()).isEqualTo(MarketQuoteFreshness.STALE);
        assertThat(response.warning()).isNotBlank();
        assertThat(response.fetchedAt()).isEqualTo(oldQuote.getFetchedAt());
        verify(fixture.persistence, never()).upsert(any());
    }

    @Test
    void mapsMissingQuoteAndProviderNotFoundTo404() {
        Fixture fixture = fixture();
        when(fixture.assetMapper.selectById(7L)).thenReturn(stock("AAPL"));
        when(fixture.provider.fetchQuote("AAPL"))
                .thenThrow(new MarketDataProviderException(MarketDataErrorType.NOT_FOUND, "not found"));

        assertThatThrownBy(() -> fixture.service.refresh(1L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(404);
    }

    @Test
    void rejectsForeignUnsupportedAndInvalidAssetsBeforeLookingUpQuotes() {
        Fixture fixture = fixture();
        when(fixture.assetMapper.selectById(7L)).thenReturn(asset(2L, "STOCK", "AAPL"));
        assertCode(() -> fixture.service.refresh(1L, 7L), 404);

        when(fixture.assetMapper.selectById(8L)).thenReturn(asset(1L, "CASH", "AAPL"));
        assertCode(() -> fixture.service.refresh(1L, 8L), 400);

        when(fixture.assetMapper.selectById(9L)).thenReturn(asset(1L, "ETF", "!bad"));
        assertCode(() -> fixture.service.refresh(1L, 9L), 400);
        verify(fixture.quoteMapper, never()).findByMarketAndSymbol(any(), any());
    }

    @Test
    void sharesOneProviderCallAcrossTenConcurrentRefreshes() throws Exception {
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch assetsSelected = new CountDownLatch(10);
        AtomicInteger calls = new AtomicInteger();
        MarketDataProvider blockingProvider = symbol -> {
            calls.incrementAndGet();
            providerStarted.countDown();
            try {
                if (!releaseProvider.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Provider was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return providerQuote(symbol);
        };
        Fixture fixture = fixture(blockingProvider);
        AtomicReference<MarketQuote> persistedQuote = new AtomicReference<>();
        when(fixture.assetMapper.selectById(7L)).thenAnswer(invocation -> {
            assetsSelected.countDown();
            return stock("AAPL");
        });
        when(fixture.quoteMapper.findByMarketAndSymbol("US", "AAPL")).thenAnswer(invocation -> persistedQuote.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            persistedQuote.set(invocation.getArgument(0));
            return null;
        }).when(fixture.persistence).upsert(any(MarketQuote.class));
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Future<MarketQuoteResponse>> futures = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                futures.add(executor.submit(() -> fixture.service.refresh(1L, 7L)));
            }
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(assetsSelected.await(5, TimeUnit.SECONDS)).isTrue();
            releaseProvider.countDown();
            List<MarketQuoteResponse> responses = new ArrayList<>();
            for (Future<MarketQuoteResponse> future : futures) {
                responses.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(calls.get()).isEqualTo(1);
            assertThat(responses).allSatisfy(response -> assertThat(response.refreshResult())
                    .isEqualTo(MarketQuoteRefreshResult.UPDATED));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void limitsOnlyActualProviderCallsAndResetsOnTheNextMinute() {
        MutableClock clock = new MutableClock(NOW);
        MarketDataProperties properties = properties();
        properties.setUserRequestLimitPerMinute(1);
        properties.setProviderRequestLimitPerMinute(2);
        MarketQuoteRateLimiter limiter = new MarketQuoteRateLimiter(properties, clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).isTrue();
        clock.set(NOW.plusSeconds(60));
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void clearsSingleFlightKeyAfterFailureSoTheNextRequestCanRetry() {
        Fixture fixture = fixture();
        when(fixture.assetMapper.selectById(7L)).thenReturn(stock("AAPL"));
        when(fixture.provider.fetchQuote("AAPL"))
                .thenThrow(new MarketDataProviderException(MarketDataErrorType.TRANSPORT, "offline"))
                .thenReturn(providerQuote("AAPL"));

        assertCode(() -> fixture.service.refresh(1L, 7L), 503);

        MarketQuoteResponse retry = fixture.service.refresh(1L, 7L);
        assertThat(retry.refreshResult()).isEqualTo(MarketQuoteRefreshResult.UPDATED);
        verify(fixture.provider, times(2)).fetchQuote("AAPL");
    }

    private Fixture fixture() {
        return fixture(mock(MarketDataProvider.class));
    }

    private Fixture fixture(MarketDataProvider provider) {
        AssetMapper assetMapper = mock(AssetMapper.class);
        MarketQuoteMapper quoteMapper = mock(MarketQuoteMapper.class);
        MarketQuotePersistenceService persistence = mock(MarketQuotePersistenceService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MarketDataProperties properties = properties();
        MarketQuoteQueryService queryService = new MarketQuoteQueryService(quoteMapper, properties, clock);
        MarketQuoteRateLimiter limiter = new MarketQuoteRateLimiter(properties, clock);
        return new Fixture(assetMapper, quoteMapper, persistence, provider,
                new MarketQuoteService(assetMapper, provider, queryService, persistence, limiter, clock));
    }

    private MarketDataProperties properties() {
        MarketDataProperties properties = new MarketDataProperties();
        properties.setCacheTtl(java.time.Duration.ofMinutes(15));
        return properties;
    }

    private Asset stock(String symbol) {
        return asset(1L, "STOCK", symbol);
    }

    private Asset asset(Long userId, String type, String symbol) {
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setType(type);
        asset.setSymbol(symbol);
        return asset;
    }

    private MarketQuote quote(String symbol, Instant fetchedAt) {
        MarketQuote quote = new MarketQuote();
        quote.setMarket("US");
        quote.setSymbol(symbol);
        quote.setCurrency("USD");
        quote.setPrice(new BigDecimal("200"));
        quote.setQuoteTime(NOW.minusSeconds(3600));
        quote.setFetchedAt(fetchedAt);
        quote.setProvider("TWELVE_DATA");
        return quote;
    }

    private MarketDataQuote providerQuote(String symbol) {
        return new MarketDataQuote("US", symbol, "USD", new BigDecimal("212.34"),
                NOW.minusSeconds(30), "TWELVE_DATA");
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int expectedCode) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(expectedCode);
    }

    private record Fixture(AssetMapper assetMapper, MarketQuoteMapper quoteMapper,
                           MarketQuotePersistenceService persistence, MarketDataProvider provider,
                           MarketQuoteService service) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
