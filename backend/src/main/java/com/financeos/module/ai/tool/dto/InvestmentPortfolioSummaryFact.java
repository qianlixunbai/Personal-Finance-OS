package com.financeos.module.ai.tool.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Finance-owned summary for transaction-driven positions only.
 * Reference valuation is cached, read-only information and does not represent accounting truth.
 */
public record InvestmentPortfolioSummaryFact(
        String currency,
        int positionCount,
        int openPositionCount,
        int closedPositionCount,
        BigDecimal openTotalCost,
        BigDecimal cumulativeRealizedProfitLoss,
        ReferenceValuation referenceValuation
) {
    public record ReferenceValuation(
            String baseCurrency,
            BigDecimal referenceMarketValue,
            int valuedPositionCount,
            int totalOpenPositionCount,
            String freshness,
            List<Warning> warnings
    ) {
        public ReferenceValuation {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record Warning(String code, String component) {
    }
}
