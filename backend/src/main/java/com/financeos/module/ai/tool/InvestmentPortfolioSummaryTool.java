package com.financeos.module.ai.tool;

import com.financeos.module.ai.tool.dto.InvestmentPortfolioSummaryFact;
import com.financeos.module.investment.read.dto.InvestmentPortfolioResponse;
import com.financeos.module.investment.read.service.InvestmentPortfolioQueryService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class InvestmentPortfolioSummaryTool {
    private final InvestmentPortfolioQueryService portfolioQueryService;

    public InvestmentPortfolioSummaryTool(InvestmentPortfolioQueryService portfolioQueryService) {
        this.portfolioQueryService = portfolioQueryService;
    }

    /**
     * Returns the authenticated user's transaction-driven investment portfolio summary.
     * Reference valuation is read-only cached data and is not an accounting balance.
     */
    public InvestmentPortfolioSummaryFact getInvestmentPortfolioSummary(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new IllegalArgumentException("authenticatedUserId must be positive");
        }

        InvestmentPortfolioResponse response = portfolioQueryService.get(authenticatedUserId);
        InvestmentPortfolioResponse.PortfolioReferenceValuation reference = response.referenceValuation();
        return new InvestmentPortfolioSummaryFact(
                response.currency(),
                response.positionCount(),
                response.openPositionCount(),
                response.closedPositionCount(),
                new BigDecimal(response.openTotalCost()),
                new BigDecimal(response.cumulativeRealizedProfitLoss()),
                new InvestmentPortfolioSummaryFact.ReferenceValuation(
                        reference.baseCurrency(),
                        reference.value() == null ? null : new BigDecimal(reference.value()),
                        reference.valuedPositionCount(),
                        reference.totalOpenPositionCount(),
                        reference.freshness(),
                        reference.warnings().stream()
                                .map(warning -> new InvestmentPortfolioSummaryFact.Warning(
                                        warning.code(), warning.component()))
                                .toList()));
    }
}
