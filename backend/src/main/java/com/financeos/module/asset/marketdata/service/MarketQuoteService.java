package com.financeos.module.asset.marketdata.service;

import com.financeos.common.BusinessException;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteRefreshResult;
import com.financeos.module.asset.marketdata.dto.MarketQuoteResponse;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.exception.MarketDataRateLimitException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketQuoteService {
    private static final String MARKET = "US";
    private static final String STALE_WARNING = "Refresh failed; the most recently successful reference quote is shown.";
    private static final java.util.regex.Pattern SYMBOL = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9.-]{0,14}$");

    private final AssetMapper assetMapper;
    private final MarketDataProvider marketDataProvider;
    private final MarketQuoteQueryService queryService;
    private final MarketQuotePersistenceService persistenceService;
    private final MarketQuoteRateLimiter rateLimiter;
    private final Clock clock;
    private final ConcurrentHashMap<QuoteKey, CompletableFuture<MarketQuoteResponse>> inFlight = new ConcurrentHashMap<>();

    public MarketQuoteService(AssetMapper assetMapper, MarketDataProvider marketDataProvider,
                              MarketQuoteQueryService queryService, MarketQuotePersistenceService persistenceService,
                              MarketQuoteRateLimiter rateLimiter, Clock clock) {
        this.assetMapper = assetMapper;
        this.marketDataProvider = marketDataProvider;
        this.queryService = queryService;
        this.persistenceService = persistenceService;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public MarketQuoteResponse refresh(Long userId, Long assetId) {
        Asset asset = ownedRefreshableAsset(userId, assetId);
        String symbol = normalizeSymbol(asset.getSymbol());
        MarketQuoteQueryService.QuoteSnapshot cached = queryService.find(MARKET, symbol);
        if (cached.freshness() == MarketQuoteFreshness.FRESH) {
            return queryService.response(cached.quote(), MarketQuoteFreshness.FRESH, MarketQuoteRefreshResult.CACHE_HIT, null);
        }
        QuoteKey key = new QuoteKey(MARKET, symbol);
        CompletableFuture<MarketQuoteResponse> created = new CompletableFuture<>();
        CompletableFuture<MarketQuoteResponse> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw exception;
            }
        }
        try {
            MarketQuoteResponse response = refreshStaleOrMissing(userId, symbol);
            created.complete(response);
            return response;
        } catch (RuntimeException exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, created);
        }
    }

    private MarketQuoteResponse refreshStaleOrMissing(Long userId, String symbol) {
        MarketQuoteQueryService.QuoteSnapshot snapshot = queryService.find(MARKET, symbol);
        if (snapshot.freshness() == MarketQuoteFreshness.FRESH) {
            return queryService.response(snapshot.quote(), MarketQuoteFreshness.FRESH, MarketQuoteRefreshResult.CACHE_HIT, null);
        }
        try {
            if (!rateLimiter.tryAcquire(userId)) {
                throw new MarketDataRateLimitException();
            }
            MarketDataQuote providerQuote = marketDataProvider.fetchQuote(symbol);
            validateProviderQuote(providerQuote, symbol);
            MarketQuote quote = toEntity(providerQuote);
            persistenceService.upsert(quote);
            return queryService.response(quote, MarketQuoteFreshness.FRESH, MarketQuoteRefreshResult.UPDATED, null);
        } catch (MarketDataRateLimitException exception) {
            return fallbackOrThrow(snapshot, 429);
        } catch (MarketDataProviderException exception) {
            return fallbackOrThrow(snapshot, statusFor(exception.getErrorType()));
        }
    }

    private MarketQuoteResponse fallbackOrThrow(MarketQuoteQueryService.QuoteSnapshot snapshot, int status) {
        if (snapshot.quote() != null) {
            return queryService.response(snapshot.quote(), MarketQuoteFreshness.STALE,
                    MarketQuoteRefreshResult.STALE_FALLBACK, STALE_WARNING);
        }
        throw new BusinessException(status, messageFor(status));
    }

    private Asset ownedRefreshableAsset(Long userId, Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || !userId.equals(asset.getUserId())) {
            throw new BusinessException(404, "Asset not found");
        }
        if (!"STOCK".equalsIgnoreCase(asset.getType()) && !"ETF".equalsIgnoreCase(asset.getType())) {
            throw new BusinessException(400, "Asset type does not support market quote refresh");
        }
        return asset;
    }

    private String normalizeSymbol(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(400, "Asset symbol is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL.matcher(normalized).matches()) {
            throw new BusinessException(400, "Asset symbol is invalid");
        }
        return normalized;
    }

    private void validateProviderQuote(MarketDataQuote quote, String symbol) {
        if (quote == null || !MARKET.equals(quote.market()) || !symbol.equals(quote.symbol())
                || !StringUtils.hasText(quote.currency()) || quote.price() == null || quote.price().signum() <= 0
                || quote.quoteTime() == null || !StringUtils.hasText(quote.provider())) {
            throw new MarketDataProviderException(MarketDataErrorType.INVALID_QUOTE, "Provider returned an invalid quote");
        }
    }

    private MarketQuote toEntity(MarketDataQuote providerQuote) {
        MarketQuote quote = new MarketQuote();
        quote.setMarket(MARKET);
        quote.setSymbol(providerQuote.symbol());
        quote.setCurrency(providerQuote.currency().trim().toUpperCase(Locale.ROOT));
        quote.setPrice(providerQuote.price());
        quote.setQuoteTime(providerQuote.quoteTime());
        quote.setFetchedAt(clock.instant());
        quote.setProvider(providerQuote.provider());
        return quote;
    }

    private int statusFor(MarketDataErrorType errorType) {
        return switch (errorType) {
            case NOT_FOUND -> 404;
            case RATE_LIMITED -> 429;
            case RESPONSE_FORMAT, INVALID_QUOTE, INVALID_REQUEST, UPSTREAM_ERROR -> 502;
            case DISABLED, AUTHENTICATION, PROVIDER_UNAVAILABLE, TRANSPORT -> 503;
        };
    }

    private String messageFor(int status) {
        return switch (status) {
            case 404 -> "Market quote was not found";
            case 429 -> "Market data refresh limit has been reached";
            case 502 -> "Market data provider returned an invalid response";
            default -> "Market data is temporarily unavailable";
        };
    }

    private record QuoteKey(String market, String symbol) {
    }
}
