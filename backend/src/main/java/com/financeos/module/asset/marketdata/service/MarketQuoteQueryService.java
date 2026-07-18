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

@Service
public class MarketQuoteQueryService {
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

    public record QuoteSnapshot(MarketQuote quote, MarketQuoteFreshness freshness) {
    }
}
