package com.financeos.module.asset.marketdata.service;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketDataQuote(
        String market,
        String symbol,
        String currency,
        BigDecimal price,
        Instant quoteTime,
        String provider
) {
}
