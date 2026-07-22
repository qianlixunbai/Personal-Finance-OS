package com.financeos.module.investment.ledger;

import java.util.List;

public record InvestmentReplayResult(
        InvestmentPositionState position,
        List<InvestmentCalculationResult> appliedCalculations) {

    public InvestmentReplayResult {
        if (position == null || appliedCalculations == null) {
            throw new InvestmentLedgerValidationException("Replay position and calculations are required");
        }
        appliedCalculations = List.copyOf(appliedCalculations);
    }
}
