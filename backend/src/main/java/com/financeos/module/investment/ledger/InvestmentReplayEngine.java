package com.financeos.module.investment.ledger;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return replay(entries, Set.of());
    }

    public InvestmentReplayResult replay(List<InvestmentReplayEntry> entries, Set<Long> candidateReversedOriginalIds) {
        if (entries == null) {
            throw new InvestmentLedgerValidationException("Replay entries are required");
        }
        if (candidateReversedOriginalIds == null || candidateReversedOriginalIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new InvestmentLedgerValidationException("Candidate reversed original ids must be positive");
        }
        List<InvestmentReplayEntry> orderedEntries = entries.stream()
                .sorted(Comparator.comparing(InvestmentReplayEntry::tradeTime).thenComparingLong(InvestmentReplayEntry::id))
                .toList();
        Map<Long, InvestmentReplayEntry> entriesById = new HashMap<>();
        Set<Long> reversedOriginalIds = new HashSet<>(candidateReversedOriginalIds);
        for (InvestmentReplayEntry entry : orderedEntries) {
            if (entriesById.put(entry.id(), entry) != null) {
                throw new InvestmentLedgerValidationException("Replay entry ids must be unique");
            }
            if (entry.command().transactionType() == InvestmentTransactionType.REVERSAL) {
                if (!reversedOriginalIds.add(entry.originalTransactionId())) {
                    throw new InvestmentLedgerValidationException("Original transaction has more than one reversal");
                }
            }
        }
        for (Long originalId : reversedOriginalIds) {
            InvestmentReplayEntry original = entriesById.get(originalId);
            if (original == null
                    || original.command().transactionType() == InvestmentTransactionType.REVERSAL
                    || original.command().transactionType() == InvestmentTransactionType.OPENING_POSITION) {
                throw new InvestmentLedgerValidationException("Reversal original must be a buy, sell or dividend transaction");
            }
        }
        InvestmentPositionState position = InvestmentPositionState.empty();
        List<InvestmentCalculationResult> calculations = new ArrayList<>();
        boolean hasEffectiveEntry = false;
        boolean hasEffectiveBuyOrOpeningPosition = false;
        for (InvestmentReplayEntry entry : orderedEntries) {
            if (entry.status() == InvestmentTransactionStatus.REVERSED
                    || entry.command().transactionType() == InvestmentTransactionType.REVERSAL
                    || reversedOriginalIds.contains(entry.id())) {
                continue;
            }
            if (entry.command().transactionType() == InvestmentTransactionType.OPENING_POSITION && hasEffectiveEntry) {
                throw new InvestmentLedgerValidationException("Opening position must be the first effective investment transaction");
            }
            if (entry.command().transactionType() == InvestmentTransactionType.DIVIDEND && !hasEffectiveBuyOrOpeningPosition) {
                throw new InvestmentLedgerValidationException("Dividend requires an earlier effective buy or opening position");
            }
            InvestmentCalculationResult result = calculator.calculate(position, entry.command());
            position = new InvestmentPositionState(
                    result.newQuantity(),
                    result.newTotalCost(),
                    result.newCumulativeRealizedProfitLoss());
            calculations.add(result);
            hasEffectiveEntry = true;
            if (entry.command().transactionType() == InvestmentTransactionType.BUY
                    || entry.command().transactionType() == InvestmentTransactionType.OPENING_POSITION) {
                hasEffectiveBuyOrOpeningPosition = true;
            }
        }
        return new InvestmentReplayResult(position, calculations);
    }
}
