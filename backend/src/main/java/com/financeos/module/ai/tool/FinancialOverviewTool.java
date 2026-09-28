package com.financeos.module.ai.tool;

import com.financeos.module.ai.tool.dto.FinancialOverviewFact;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.dashboard.service.DashboardService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FinancialOverviewTool {

    private static final String BASE_CURRENCY = "CNY";

    private final DashboardService dashboardService;

    public FinancialOverviewTool(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    public FinancialOverviewFact getFinancialOverview(Long authenticatedUserId) {
        validateAuthenticatedUserId(authenticatedUserId);

        DashboardDto dashboard = dashboardService.getDashboard(authenticatedUserId);
        List<DashboardDto.MonthlyCashFlow> trend = dashboard.monthlyCashFlowTrend();
        if (trend == null || trend.isEmpty()) {
            throw new IllegalStateException("Dashboard did not provide a monthly cash flow trend");
        }

        String currentMonth = trend.get(trend.size() - 1).month();
        return new FinancialOverviewFact(
                BASE_CURRENCY,
                dashboard.totalAssets(),
                dashboard.netWorth(),
                currentMonth,
                dashboard.monthIncome(),
                dashboard.monthExpense(),
                dashboard.monthNet()
        );
    }

    private void validateAuthenticatedUserId(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new IllegalArgumentException("authenticatedUserId must be a positive number");
        }
    }
}
