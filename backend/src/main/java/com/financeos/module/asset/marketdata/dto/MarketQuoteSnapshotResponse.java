package com.financeos.module.asset.marketdata.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only cached market quote included with asset query responses.
 */
public record MarketQuoteSnapshotResponse(
        String symbol,
        String market,
        String currency,
        BigDecimal price,
        Instant quoteTime,
        Instant fetchedAt,
        String provider,
        MarketQuoteFreshness freshness
) {
}
