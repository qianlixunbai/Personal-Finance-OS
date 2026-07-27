package com.financeos.module.investment.command.dto;

public record InvestmentCommandResponse(
        Long transactionId,
        Long assetId,
        Long accountId,
        Long instrumentId,
        String transactionType,
        String grossAmount,
        String netAmount,
        String cashDelta,
        String balanceAfter,
        boolean idempotentReplay,
        FinalPosition finalPosition) {

    public record FinalPosition(
            String quantity,
            String avgCost,
            String totalCost,
            String realizedProfitLoss,
            String status,
            Integer projectionVersion,
            Long lastTransactionId) {
    }
}
