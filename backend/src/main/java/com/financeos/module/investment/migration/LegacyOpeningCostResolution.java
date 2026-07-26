package com.financeos.module.investment.migration;

import java.math.BigDecimal;
import java.util.List;

public record LegacyOpeningCostResolution(
        BigDecimal totalCost,
        String totalCostSource,
        BigDecimal unitPrice,
        BigDecimal expectedAvgCost,
        List<String> blockingErrors,
        List<String> warnings) {

    public boolean isReady() {
        return blockingErrors.isEmpty();
    }
}
