package com.financeos.module.investment.read.dto;

import java.time.Instant;

public record InvestmentTransactionDetail(
        Long logicalTransactionId,
        String transactionType,
        String correctionStatus,
        boolean effective,
        Instant effectiveTradeTime,
        Instant settlementTime,
        TransactionAccount account,
        TransactionInstrument instrument,
        TransactionBusinessValues originalBusinessValues,
        TransactionBusinessValues effectiveBusinessValues,
        PostingReceipt postingReceipt,
        CorrectionFinalReceipt correctionFinalReceipt,
        CorrectionSummary correction,
        CurrentPosition currentPosition
) {
    public record TransactionAccount(Long id, String displayName) {
    }

    public record TransactionInstrument(Long id, String symbol, String name, String market, String assetClass,
                                        String quoteCurrency) {
    }

    public record TransactionBusinessValues(String quantity, String unitPrice, String grossAmount, String feeAmount,
                                            String taxAmount, String netAmount, String releasedCostAmount,
                                            String realizedProfitLoss, String note, String externalReference) {
    }

    public record PostingReceipt(String accountBalanceAfter, String positionQuantityAfter, String positionAverageCostAfter,
                                 String positionTotalCostAfter, String positionRealizedProfitLossAfter,
                                 String positionStatusAfter) {
    }

    public record CorrectionFinalReceipt(String accountBalanceAfter, String positionQuantityAfter,
                                         String positionAverageCostAfter, String positionTotalCostAfter,
                                         String positionRealizedProfitLossAfter, String positionStatusAfter) {
    }

    public record CorrectionSummary(Instant createdAt, String reason, Long reversalTransactionId,
                                    Long replacementTransactionId) {
    }

    public record CurrentPosition(String quantity, String averageCost, String totalCost,
                                  String cumulativeRealizedProfitLoss, String status) {
    }
}
