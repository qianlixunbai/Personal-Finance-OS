package com.financeos.module.investment.read.dto;

import java.time.Instant;
import java.util.List;

public record InvestmentTransactionAuditTimeline(Long logicalTransactionId, String correctionStatus,
                                                  List<AuditEvent> events, AuditCurrentPosition currentPosition) {
    public InvestmentTransactionAuditTimeline {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public record AuditEvent(String eventKind, Instant createdAt, Long physicalFactId, Long originalFactId,
                             String factType, String reason, String receiptApplicability,
                             AuditBusinessValues businessValues, AuditPostingReceipt postingReceipt,
                             AuditCorrectionFinalReceipt correctionFinalReceipt,
                             List<ReplacementPhysicalFact> facts) {
        public AuditEvent {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    public record ReplacementPhysicalFact(String role, Long physicalFactId, String factType,
                                          String receiptApplicability, AuditBusinessValues businessValues) {
    }

    public record AuditBusinessValues(String quantity, String unitPrice, String grossAmount, String feeAmount,
                                      String taxAmount, String netAmount, String releasedCostAmount,
                                      String realizedProfitLoss, String note, String externalReference) {
    }

    public record AuditPostingReceipt(String accountBalanceAfter, String positionQuantityAfter,
                                      String positionAverageCostAfter, String positionTotalCostAfter,
                                      String positionRealizedProfitLossAfter, String positionStatusAfter) {
    }

    public record AuditCorrectionFinalReceipt(String accountBalanceAfter, String positionQuantityAfter,
                                              String positionAverageCostAfter, String positionTotalCostAfter,
                                              String positionRealizedProfitLossAfter, String positionStatusAfter) {
    }

    public record AuditCurrentPosition(String quantity, String averageCost, String totalCost,
                                       String cumulativeRealizedProfitLoss, String status) {
    }
}
