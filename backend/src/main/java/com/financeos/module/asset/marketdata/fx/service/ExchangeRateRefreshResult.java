package com.financeos.module.asset.marketdata.fx.service;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeRateRefreshResult(String baseCurrency, String quoteCurrency, BigDecimal rate,
                                        Instant rateTime, Instant fetchedAt, String provider,
                                        ExchangeRateFreshness freshness, ExchangeRateRefreshStatus status,
                                        String warningCode) {
}
