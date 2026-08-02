package com.financeos.module.investment.command.dto;

import java.time.Instant;

public record InvestmentReplacementResponse(
        String correctionStatus, String correctionGroupId, Long originalTransactionId,
        Long reversalTransactionId, Long replacementTransactionId, String transactionType,
        Long assetId, Long accountId, Long instrumentId, String reversalCashDelta,
        String replacementCashDelta, String commandCashDelta, String balanceAfter,
        InvestmentCommandResponse.FinalPosition finalPosition, Long lastTransactionId,
        boolean idempotentReplay, Instant createdAt) { }
