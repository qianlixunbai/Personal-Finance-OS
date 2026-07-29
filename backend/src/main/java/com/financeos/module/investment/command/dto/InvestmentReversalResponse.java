package com.financeos.module.investment.command.dto;

import java.time.Instant;

public record InvestmentReversalResponse(
        String correctionStatus,
        Long originalTransactionId,
        Long reversalTransactionId,
        Long replacementTransactionId,
        Long assetId,
        Long accountId,
        Long instrumentId,
        String cashDelta,
        String balanceAfter,
        String quantity,
        String avgCost,
        String totalCost,
        String realizedProfitLoss,
        String positionStatus,
        Integer projectionVersion,
        Long lastTransactionId,
        String currentPrice,
        String marketValue,
        boolean idempotentReplay,
        Instant createdAt) {
}
