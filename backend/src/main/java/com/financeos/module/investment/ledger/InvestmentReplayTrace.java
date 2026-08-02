package com.financeos.module.investment.ledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Canonical effective replay history, including a stable content digest for consistency checks. */
public record InvestmentReplayTrace(List<InvestmentReplayTraceStep> steps, String canonicalDigest) {

    public InvestmentReplayTrace {
        if (steps == null) {
            throw new InvestmentLedgerValidationException("Replay trace steps are required");
        }
        steps = List.copyOf(steps);
        if (canonicalDigest == null || canonicalDigest.isBlank()) {
            throw new InvestmentLedgerValidationException("Replay trace digest is required");
        }
    }

    public static InvestmentReplayTrace of(List<InvestmentReplayTraceStep> steps) {
        return new InvestmentReplayTrace(steps, digest(steps));
    }

    public static InvestmentReplayTrace empty() {
        return of(List.of());
    }

    private static String digest(List<InvestmentReplayTraceStep> steps) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (InvestmentReplayTraceStep step : steps) {
                InvestmentCalculationResult calculation = step.calculation();
                String canonical = step.logicalFactIdentity() + "|" + step.effectiveAnchorId() + "|"
                        + step.replaySequence() + "|" + step.tradeTime() + "|" + step.transactionType() + "|"
                        + decimal(calculation.grossAmount()) + "|" + decimal(calculation.netAmount()) + "|"
                        + decimal(calculation.cashDelta()) + "|" + decimal(calculation.newQuantity()) + "|"
                        + decimal(calculation.newTotalCost()) + "|" + decimal(calculation.newAvgCost()) + "|"
                        + decimal(calculation.releasedCostAmount()) + "|" + decimal(calculation.realizedProfitLoss()) + "|"
                        + decimal(calculation.newCumulativeRealizedProfitLoss()) + "\n";
                digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }
}
