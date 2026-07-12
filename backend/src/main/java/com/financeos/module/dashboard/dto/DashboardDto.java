package com.financeos.module.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

public record DashboardDto(
        BigDecimal totalAssets,
        BigDecimal netWorth,
        BigDecimal monthIncome,
        BigDecimal monthExpense,
        BigDecimal monthNet,
        List<AssetAllocation> assetAllocation,
        List<MonthlyCashFlow> monthlyCashFlowTrend,
        List<RecentTransaction> recentTransactions
) {
    public record AssetAllocation(String name, BigDecimal value, BigDecimal percentage) {}
    public record MonthlyCashFlow(String month, BigDecimal income, BigDecimal expense, BigDecimal net) {}
    public record RecentTransaction(Long id, String type, BigDecimal amount, String category, String account, String date) {}
}
