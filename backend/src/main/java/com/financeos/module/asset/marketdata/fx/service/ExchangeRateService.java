package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.config.FxDataProperties;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProvider;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProviderException;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateQuote;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExchangeRateService {
    private final ExchangeRateQueryService queryService;
    private final ExchangeRatePersistenceService persistenceService;
    private final ExchangeRateRateLimiter limiter;
    private final FxDataProperties properties;
    private final ExchangeRateProvider provider;
    private final Clock clock;
    private final ConcurrentHashMap<String, CompletableFuture<ExchangeRateRefreshResult>> inFlight = new ConcurrentHashMap<>();

    public ExchangeRateService(ExchangeRateQueryService queryService, ExchangeRatePersistenceService persistenceService,
                               ExchangeRateRateLimiter limiter, FxDataProperties properties,
                               @Nullable ExchangeRateProvider provider, Clock clock) {
        this.queryService = queryService; this.persistenceService = persistenceService; this.limiter = limiter;
        this.properties = properties; this.provider = provider; this.clock = clock;
    }

    public ExchangeRateRefreshResult refreshRate(Long userId, String baseCurrency, String quoteCurrency) {
        String base = queryService.normalize(baseCurrency); String quote = queryService.normalize(quoteCurrency);
        if (base.equals("CNY") && quote.equals("CNY")) return result(queryService.syntheticCnyRate(), ExchangeRateFreshness.FRESH, ExchangeRateRefreshStatus.CACHE_HIT, null);
        if (!properties.isEnabled() || provider == null) throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.DISABLED);
        ExchangeRate cached = queryService.find(base, quote);
        if (queryService.freshnessOf(cached) == ExchangeRateFreshness.FRESH) return result(cached, ExchangeRateFreshness.FRESH, ExchangeRateRefreshStatus.CACHE_HIT, null);
        String key = base + ":" + quote; CompletableFuture<ExchangeRateRefreshResult> created = new CompletableFuture<>();
        CompletableFuture<ExchangeRateRefreshResult> current = inFlight.putIfAbsent(key, created);
        if (current != null) return join(current);
        try { ExchangeRateRefreshResult refreshed = refreshLeader(userId, base, quote, cached); created.complete(refreshed); return refreshed; }
        catch (RuntimeException exception) { created.completeExceptionally(exception); throw exception; }
        finally { inFlight.remove(key, created); }
    }

    private ExchangeRateRefreshResult refreshLeader(Long userId, String base, String quote, ExchangeRate first) {
        ExchangeRate second = queryService.find(base, quote);
        if (queryService.freshnessOf(second) == ExchangeRateFreshness.FRESH) return result(second, ExchangeRateFreshness.FRESH, ExchangeRateRefreshStatus.CACHE_HIT, null);
        try {
            if (!limiter.tryAcquire(userId)) throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.RATE_LIMITED);
            ExchangeRateQuote quoteResult = provider.fetchRate(base, quote);
            ExchangeRate entity = entity(base, quote, quoteResult);
            return result(persistenceService.upsert(entity), ExchangeRateFreshness.FRESH, ExchangeRateRefreshStatus.UPDATED, null);
        } catch (RuntimeException exception) {
            if (first != null) return result(first, ExchangeRateFreshness.STALE, ExchangeRateRefreshStatus.STALE_FALLBACK, "FX_REFRESH_FAILED");
            throw exception;
        }
    }

    private ExchangeRate entity(String base, String quote, ExchangeRateQuote providerQuote) {
        if (providerQuote == null) throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.RESPONSE_FORMAT);
        String responseBase;
        String responseQuote;
        try {
            responseBase = queryService.normalize(providerQuote.baseCurrency());
            responseQuote = queryService.normalize(providerQuote.quoteCurrency());
        } catch (IllegalArgumentException exception) {
            throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.CURRENCY_MISMATCH);
        }
        if (!base.equals(responseBase) || !quote.equals(responseQuote)) {
            throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.CURRENCY_MISMATCH);
        }
        if (providerQuote.rate() == null || providerQuote.rate().signum() <= 0) {
            throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.INVALID_RATE);
        }
        if (providerQuote.rateTime() == null) {
            throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.INVALID_TIME);
        }
        if (providerQuote.provider() == null || providerQuote.provider().trim().isEmpty()) {
            throw new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.RESPONSE_FORMAT);
        }
        ExchangeRate entity = new ExchangeRate(); entity.setBaseCurrency(base); entity.setQuoteCurrency(quote); entity.setRate(providerQuote.rate()); entity.setRateTime(providerQuote.rateTime()); entity.setFetchedAt(clock.instant()); entity.setProvider(providerQuote.provider()); return entity;
    }

    private ExchangeRateRefreshResult result(ExchangeRate rate, ExchangeRateFreshness freshness, ExchangeRateRefreshStatus status, String warning) { return new ExchangeRateRefreshResult(rate.getBaseCurrency(), rate.getQuoteCurrency(), rate.getRate(), rate.getRateTime(), rate.getFetchedAt(), rate.getProvider(), freshness, status, warning); }
    private ExchangeRateRefreshResult join(CompletableFuture<ExchangeRateRefreshResult> future) { try { return future.join(); } catch (CompletionException exception) { if (exception.getCause() instanceof RuntimeException runtime) throw runtime; throw exception; } }
}
