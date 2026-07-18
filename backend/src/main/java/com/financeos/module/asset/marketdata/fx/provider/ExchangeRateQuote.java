package com.financeos.module.asset.marketdata.fx.provider;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeRateQuote(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        Instant rateTime,
        String provider
) {
}
