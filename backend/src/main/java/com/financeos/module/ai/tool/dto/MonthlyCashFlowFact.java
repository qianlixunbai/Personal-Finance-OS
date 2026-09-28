package com.financeos.module.ai.tool.dto;

import java.math.BigDecimal;
import java.util.List;

public record MonthlyCashFlowFact(
        String currency,
        String fromMonth,
        String throughMonth,
        List<MonthCashFlow> months
) {
    public record MonthCashFlow(
            String month,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal net
    ) {
    }
}
