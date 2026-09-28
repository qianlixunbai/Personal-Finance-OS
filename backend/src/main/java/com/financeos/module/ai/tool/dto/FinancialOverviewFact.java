package com.financeos.module.ai.tool.dto;

import java.math.BigDecimal;

public record FinancialOverviewFact(
        String currency,
        BigDecimal accountingTotalAssets,
        BigDecimal accountingNetWorth,
        String currentMonth,
        BigDecimal currentMonthIncome,
        BigDecimal currentMonthExpense,
        BigDecimal currentMonthNetCashFlow
) {
}
