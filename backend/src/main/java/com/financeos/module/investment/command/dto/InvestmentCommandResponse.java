package com.financeos.module.investment.command.dto;

import java.time.Instant;

public record InvestmentCommandResponse(
        Long transactionId,
        Long assetId,
        Long accountId,
        Long instrumentId,
        String transactionType,
        Instant tradeTime,
        Instant settlementTime,
        String grossAmount,
        String feeAmount,
        String taxAmount,
        String netAmount,
        String cashDelta,
        String balanceAfter,
        boolean idempotentReplay,
        Instant createdAt,
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
