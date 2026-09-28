package com.financeos.module.ai.tool;

import com.financeos.module.ai.tool.dto.InvestmentPortfolioSummaryFact;
import com.financeos.module.investment.read.dto.InvestmentPortfolioResponse;
import com.financeos.module.investment.read.service.InvestmentPortfolioQueryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InvestmentPortfolioSummaryToolTest {

    @Test
    void mapsAccountingPortfolioSeparatelyFromReferenceValuation() {
        long authenticatedUserId = 89L;
        InvestmentPortfolioQueryService portfolioQueryService = mock(InvestmentPortfolioQueryService.class);
        when(portfolioQueryService.get(authenticatedUserId)).thenReturn(new InvestmentPortfolioResponse(
                "CNY", 4, 3, 1, "1000.00", "-125.50",
                new InvestmentPortfolioResponse.PortfolioReferenceValuation(
                        "CNY", "1310.08", 2, 3, "PARTIAL",
                        List.of(new InvestmentPortfolioResponse.Warning("QUOTE_MISSING", "QUOTE")))));

        InvestmentPortfolioSummaryFact result = new InvestmentPortfolioSummaryTool(portfolioQueryService)
                .getInvestmentPortfolioSummary(authenticatedUserId);

        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.positionCount()).isEqualTo(4);
        assertThat(result.openPositionCount()).isEqualTo(3);
        assertThat(result.closedPositionCount()).isEqualTo(1);
        assertThat(result.openTotalCost()).isEqualByComparingTo("1000.00");
        assertThat(result.cumulativeRealizedProfitLoss()).isEqualByComparingTo("-125.50");
        assertThat(result.referenceValuation().baseCurrency()).isEqualTo("CNY");
        assertThat(result.referenceValuation().referenceMarketValue()).isEqualByComparingTo("1310.08");
        assertThat(result.referenceValuation().valuedPositionCount()).isEqualTo(2);
        assertThat(result.referenceValuation().totalOpenPositionCount()).isEqualTo(3);
        assertThat(result.referenceValuation().freshness()).isEqualTo("PARTIAL");
        assertThat(result.referenceValuation().warnings())
                .containsExactly(new InvestmentPortfolioSummaryFact.Warning("QUOTE_MISSING", "QUOTE"));
        verify(portfolioQueryService).get(authenticatedUserId);
    }

    @Test
    void preservesMissingReferenceValueAsNull() {
        long authenticatedUserId = 89L;
        InvestmentPortfolioQueryService portfolioQueryService = mock(InvestmentPortfolioQueryService.class);
        when(portfolioQueryService.get(authenticatedUserId)).thenReturn(new InvestmentPortfolioResponse(
                "CNY", 2, 2, 0, "450.00", "0.00",
                new InvestmentPortfolioResponse.PortfolioReferenceValuation(
                        "CNY", null, 0, 2, "UNAVAILABLE", List.of())));

        InvestmentPortfolioSummaryFact result = new InvestmentPortfolioSummaryTool(portfolioQueryService)
                .getInvestmentPortfolioSummary(authenticatedUserId);

        assertThat(result.openTotalCost()).isEqualByComparingTo("450.00");
        assertThat(result.referenceValuation().referenceMarketValue()).isNull();
        assertThat(result.referenceValuation().valuedPositionCount()).isZero();
        assertThat(result.referenceValuation().totalOpenPositionCount()).isEqualTo(2);
        assertThat(result.referenceValuation().freshness()).isEqualTo("UNAVAILABLE");
        assertThat(result.referenceValuation().warnings()).isEmpty();
        verify(portfolioQueryService).get(authenticatedUserId);
    }

    @Test
    void rejectsMissingAuthenticatedUserBeforeQuerying() {
        InvestmentPortfolioQueryService portfolioQueryService = mock(InvestmentPortfolioQueryService.class);

        assertThatThrownBy(() -> new InvestmentPortfolioSummaryTool(portfolioQueryService)
                .getInvestmentPortfolioSummary(null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(portfolioQueryService);
    }
}
