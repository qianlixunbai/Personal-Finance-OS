package com.financeos.module.investment.ledger;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public final class InvestmentReplayEngine {

    private final InvestmentLedgerCalculator calculator;

    public InvestmentReplayEngine() {
        this(new InvestmentLedgerCalculator());
    }

    InvestmentReplayEngine(InvestmentLedgerCalculator calculator) {
        if (calculator == null) {
            throw new InvestmentLedgerValidationException("Calculator is required");
        }
        this.calculator = calculator;
    }

    public InvestmentReplayResult replay(List<InvestmentReplayEntry> entries) {
        if (entries == null) {
            throw new InvestmentLedgerValidationException("Replay entries are required");
        }
        List<InvestmentReplayEntry> orderedEntries = entries.stream()
                .sorted(Comparator.comparing(InvestmentReplayEntry::tradeTime).thenComparingLong(InvestmentReplayEntry::id))
                .toList();
        InvestmentPositionState position = InvestmentPositionState.empty();
        List<InvestmentCalculationResult> calculations = new ArrayList<>();
        boolean hasEffectiveEntry = false;
        for (InvestmentReplayEntry entry : orderedEntries) {
            if (entry.status() == InvestmentTransactionStatus.REVERSED) {
                continue;
            }
            if (entry.command().transactionType() == InvestmentTransactionType.OPENING_POSITION && hasEffectiveEntry) {
                throw new InvestmentLedgerValidationException("Opening position must be the first effective investment transaction");
            }
            InvestmentCalculationResult result = calculator.calculate(position, entry.command());
            position = new InvestmentPositionState(
                    result.newQuantity(),
                    result.newTotalCost(),
                    result.newCumulativeRealizedProfitLoss());
            calculations.add(result);
            hasEffectiveEntry = true;
        }
        return new InvestmentReplayResult(position, calculations);
    }
}
