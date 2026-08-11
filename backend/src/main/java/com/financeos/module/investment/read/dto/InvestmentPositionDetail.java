package com.financeos.module.investment.read.dto;

import java.util.List;

public record InvestmentPositionDetail(
        Long positionId,
        String positionMode,
        Account account,
        Instrument instrument,
        String quantity,
        String averageCost,
        String totalCost,
        String cumulativeRealizedProfitLoss,
        String status,
        ManualReference manualReference,
        CachedReferenceValuation referenceValuation
) {
    public record Account(Long id, String displayName) {
    }

    public record Instrument(Long id, String symbol, String name, String market, String assetClass, String quoteCurrency) {
    }

    public record ManualReference(String currentPrice, String marketValue) {
    }

    public record CachedReferenceValuation(boolean accountingTruth, String quotePrice, String quoteCurrency,
                                           String quoteTime, String quoteFetchedAt, String quoteProvider,
                                           String fxRate, String fxBaseCurrency, String fxQuoteCurrency,
                                           String fxRateTime, String fxFetchedAt, String fxProvider,
                                           String baseCurrency, String baseCurrencyValue, String freshness,
                                           List<Warning> warnings) {
        public CachedReferenceValuation {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record Warning(String code, String component) {
    }
}
