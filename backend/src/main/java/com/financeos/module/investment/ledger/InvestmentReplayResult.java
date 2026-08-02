package com.financeos.module.investment.ledger;

import java.util.List;

public record InvestmentReplayResult(
        InvestmentPositionState position,
        List<InvestmentCalculationResult> appliedCalculations,
        InvestmentReplayTrace trace) {

    public InvestmentReplayResult(InvestmentPositionState position, List<InvestmentCalculationResult> appliedCalculations) {
        this(position, appliedCalculations, InvestmentReplayTrace.empty());
    }

    public InvestmentReplayResult {
        if (position == null || appliedCalculations == null || trace == null) {
            throw new InvestmentLedgerValidationException("Replay position, calculations and trace are required");
        }
        appliedCalculations = List.copyOf(appliedCalculations);
    }
}
