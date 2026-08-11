package com.financeos.module.investment.read.dto;

import java.util.List;

public record InvestmentPortfolioResponse(
        String currency,
        int positionCount,
        int openPositionCount,
        int closedPositionCount,
        String openTotalCost,
        String cumulativeRealizedProfitLoss,
        PortfolioReferenceValuation referenceValuation
) {
    public record PortfolioReferenceValuation(String baseCurrency, String value, int valuedPositionCount,
                                              int totalOpenPositionCount, String freshness, List<Warning> warnings) {
        public PortfolioReferenceValuation {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record Warning(String code, String component) {
    }
}
