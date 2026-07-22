package com.financeos.module.investment.ledger;

import java.math.BigDecimal;

public record InvestmentCalculationResult(
        BigDecimal grossAmount,
        BigDecimal netAmount,
        BigDecimal cashDelta,
        BigDecimal newQuantity,
        BigDecimal newTotalCost,
        BigDecimal newAvgCost,
        BigDecimal releasedCostAmount,
        BigDecimal realizedProfitLoss,
        BigDecimal newCumulativeRealizedProfitLoss) {
}
