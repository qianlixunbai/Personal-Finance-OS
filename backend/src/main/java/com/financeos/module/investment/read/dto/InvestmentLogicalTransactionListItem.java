package com.financeos.module.investment.read.dto;

import java.time.Instant;

public record InvestmentLogicalTransactionListItem(Long logicalTransactionId, Long positionId,
                                                    InvestmentPositionListItem.Account account,
                                                    InvestmentPositionListItem.Instrument instrument,
                                                    String transactionType, Instant effectiveTradeTime,
                                                    String quantity, String unitPrice, String grossAmount,
                                                    String feeAmount, String taxAmount, String netAmount,
                                                    String releasedCostAmount, String realizedProfitLoss,
                                                    String correctionStatus, boolean effective,
                                                    Instant correctionCreatedAt) {
}
