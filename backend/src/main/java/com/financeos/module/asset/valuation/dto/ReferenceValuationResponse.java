package com.financeos.module.asset.valuation.dto;

import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateFreshness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ReferenceValuationResponse(
        Long assetId,
        String symbol,
        BigDecimal quantity,
        String quoteCurrency,
        BigDecimal quotePrice,
        Instant quoteTime,
        Instant quoteFetchedAt,
        String quoteProvider,
        MarketQuoteFreshness quoteFreshness,
        boolean fxRequired,
        String fxBaseCurrency,
        String fxQuoteCurrency,
        BigDecimal fxRate,
        Instant fxRateTime,
        Instant fxFetchedAt,
        String fxProvider,
        ExchangeRateFreshness fxFreshness,
        BigDecimal nativeMarketValue,
        String baseCurrency,
        BigDecimal baseCurrencyMarketValue,
        ReferenceValuationFreshness valuationFreshness,
        Instant calculatedAt,
        String formulaVersion,
        List<ReferenceValuationWarning> warnings
) {
}
