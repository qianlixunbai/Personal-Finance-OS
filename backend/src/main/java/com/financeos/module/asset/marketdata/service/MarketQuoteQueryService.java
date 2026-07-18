package com.financeos.module.asset.marketdata.service;

import com.financeos.module.asset.marketdata.config.MarketDataProperties;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteRefreshResult;
import com.financeos.module.asset.marketdata.dto.MarketQuoteResponse;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;

@Service
public class MarketQuoteQueryService {
    private static final String US_MARKET = "US";
    private static final java.util.regex.Pattern SYMBOL = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9.-]{0,14}$");
    private final MarketQuoteMapper marketQuoteMapper;
    private final MarketDataProperties properties;
    private final Clock clock;

    public MarketQuoteQueryService(MarketQuoteMapper marketQuoteMapper, MarketDataProperties properties, Clock clock) {
        this.marketQuoteMapper = marketQuoteMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public QuoteSnapshot find(String market, String symbol) {
        MarketQuote quote = marketQuoteMapper.findByMarketAndSymbol(market, symbol);
        return new QuoteSnapshot(quote, freshnessOf(quote));
    }

    /**
     * Reads cached quotes in one database query. This method never invokes a provider.
     */
    public Map<String, MarketQuoteSnapshotResponse> findCachedUsQuotes(Collection<String> symbols) {
        Set<String> normalizedSymbols = symbols.stream()
                .map(this::normalizeSymbol)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedSymbols.isEmpty()) {
            return Map.of();
        }
        List<MarketQuote> quotes = marketQuoteMapper.findByMarketAndSymbols(US_MARKET, List.copyOf(normalizedSymbols));
        return quotes.stream().collect(Collectors.toMap(
                MarketQuote::getSymbol,
                this::snapshot,
                (first, ignored) -> first
        ));
    }

    public MarketQuoteSnapshotResponse findCachedUsQuote(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized == null) {
            return null;
        }
        MarketQuote quote = marketQuoteMapper.findByMarketAndSymbol(US_MARKET, normalized);
        return quote == null ? null : snapshot(quote);
    }

    public String normalizeSymbol(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return SYMBOL.matcher(normalized).matches() ? normalized : null;
    }

    public MarketQuoteFreshness freshnessOf(MarketQuote quote) {
        if (quote == null || quote.getFetchedAt() == null) {
            return MarketQuoteFreshness.NEVER_FETCHED;
        }
        Instant expiresAt = quote.getFetchedAt().plus(properties.getCacheTtl());
        return clock.instant().isAfter(expiresAt) ? MarketQuoteFreshness.STALE : MarketQuoteFreshness.FRESH;
    }

    public MarketQuoteResponse response(MarketQuote quote, MarketQuoteFreshness freshness,
                                        MarketQuoteRefreshResult refreshResult, String warning) {
        return new MarketQuoteResponse(quote.getSymbol(), quote.getMarket(), quote.getCurrency(), quote.getPrice(),
                quote.getQuoteTime(), quote.getFetchedAt(), quote.getProvider(), freshness, refreshResult, warning);
    }

    private MarketQuoteSnapshotResponse snapshot(MarketQuote quote) {
        return new MarketQuoteSnapshotResponse(quote.getSymbol(), quote.getMarket(), quote.getCurrency(), quote.getPrice(),
                quote.getQuoteTime(), quote.getFetchedAt(), quote.getProvider(), freshnessOf(quote));
    }

    public record QuoteSnapshot(MarketQuote quote, MarketQuoteFreshness freshness) {
    }
}
