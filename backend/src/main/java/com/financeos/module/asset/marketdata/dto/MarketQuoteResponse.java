package com.financeos.module.asset.marketdata.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketQuoteResponse(
        String symbol,
        String market,
        String currency,
        BigDecimal price,
        Instant quoteTime,
        Instant fetchedAt,
        String provider,
        MarketQuoteFreshness freshness,
        MarketQuoteRefreshResult refreshResult,
        String warning
) {
}
