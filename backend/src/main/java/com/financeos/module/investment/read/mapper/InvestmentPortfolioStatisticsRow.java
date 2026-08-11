package com.financeos.module.investment.read.mapper;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class InvestmentPortfolioStatisticsRow {
    private int positionCount;
    private int openPositionCount;
    private int closedPositionCount;
    private BigDecimal openTotalCost;
    private BigDecimal cumulativeRealizedProfitLoss;

    public InvestmentPortfolioStatisticsRow() {
    }

    public InvestmentPortfolioStatisticsRow(int positionCount, int openPositionCount, int closedPositionCount,
                                            BigDecimal openTotalCost, BigDecimal cumulativeRealizedProfitLoss) {
        this.positionCount = positionCount;
        this.openPositionCount = openPositionCount;
        this.closedPositionCount = closedPositionCount;
        this.openTotalCost = openTotalCost;
        this.cumulativeRealizedProfitLoss = cumulativeRealizedProfitLoss;
    }
}
