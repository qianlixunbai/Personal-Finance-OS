package com.financeos.module.ai.tool;

import com.financeos.module.ai.tool.dto.FinancialOverviewFact;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.dashboard.service.DashboardService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FinancialOverviewToolTest {

    @Test
    void mapsAccountingOverviewForTheAuthenticatedUser() {
        long authenticatedUserId = 41L;
        DashboardService dashboardService = mock(DashboardService.class);
        DashboardDto dashboard = new DashboardDto(
                new BigDecimal("8200.50"),
                new BigDecimal("8200.50"),
                new BigDecimal("1200.00"),
                new BigDecimal("650.00"),
                new BigDecimal("550.00"),
                List.of(),
                List.of(
                        new DashboardDto.MonthlyCashFlow("2025-01", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                        new DashboardDto.MonthlyCashFlow("2025-02", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                ),
                List.of());
        when(dashboardService.getDashboard(authenticatedUserId)).thenReturn(dashboard);

        FinancialOverviewFact result = new FinancialOverviewTool(dashboardService)
                .getFinancialOverview(authenticatedUserId);

        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.accountingTotalAssets()).isEqualByComparingTo("8200.50");
        assertThat(result.accountingNetWorth()).isEqualByComparingTo("8200.50");
        assertThat(result.currentMonth()).isEqualTo("2025-02");
        assertThat(result.currentMonthIncome()).isEqualByComparingTo("1200.00");
        assertThat(result.currentMonthExpense()).isEqualByComparingTo("650.00");
        assertThat(result.currentMonthNetCashFlow()).isEqualByComparingTo("550.00");
        verify(dashboardService).getDashboard(authenticatedUserId);
    }

    @Test
    void rejectsMissingAuthenticatedUserBeforeQuerying() {
        DashboardService dashboardService = mock(DashboardService.class);

        assertThatThrownBy(() -> new FinancialOverviewTool(dashboardService).getFinancialOverview(null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(dashboardService);
    }
}
