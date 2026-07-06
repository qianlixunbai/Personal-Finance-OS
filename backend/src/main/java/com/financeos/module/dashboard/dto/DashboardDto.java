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
        List<RecentTransaction> recentTransactions
) {
    public record AssetAllocation(String name, BigDecimal value, double percentage) {}
    public record RecentTransaction(Long id, String type, BigDecimal amount, String category, String account, String date) {}
}
