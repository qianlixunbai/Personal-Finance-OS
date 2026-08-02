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
        Map<Long, InvestmentReplayEntry> entriesById = new HashMap<>();
        Set<Long> reversedOriginalIds = new HashSet<>(candidateReversedOriginalIds);
        for (InvestmentReplayEntry entry : entries) {
            if (entriesById.put(entry.id(), entry) != null) {
                throw new InvestmentLedgerValidationException("Replay entry ids must be unique");
            }
            if (entry.command().transactionType() == InvestmentTransactionType.REVERSAL) {
                if (!reversedOriginalIds.add(entry.originalTransactionId())) {
                    throw new InvestmentLedgerValidationException("Original transaction has more than one reversal");
                }
            }
        }
        for (InvestmentReplayEntry entry : entries) {
            if (entry.replayAnchorTransactionId() == null) {
                continue;
            }
            InvestmentReplayEntry anchor = entriesById.get(entry.replayAnchorTransactionId());
            if (anchor == null
                    || anchor.replayAnchorTransactionId() != null
                    || !isOriginalTradeFact(anchor)) {
                throw new InvestmentLedgerValidationException(
                        "Replacement replay anchor must reference a BUY, SELL or DIVIDEND fact");
            }
        }
        List<InvestmentReplayEntry> orderedEntries = entries.stream()
                .sorted(Comparator.comparing((InvestmentReplayEntry entry) -> effectiveTradeTime(entry, entriesById))
                        .thenComparingLong(InvestmentReplayEntry::effectiveAnchorId)
                        .thenComparingInt(InvestmentReplayEntry::replaySequence)
                        .thenComparingLong(InvestmentReplayEntry::id))
                .toList();
        for (Long originalId : reversedOriginalIds) {
            InvestmentReplayEntry original = entriesById.get(originalId);
            if (original == null || !isOriginalTradeFact(original)) {
                throw new InvestmentLedgerValidationException("Reversal original must be a buy, sell or dividend transaction");
            }
        }
        InvestmentPositionState position = InvestmentPositionState.empty();
        List<InvestmentCalculationResult> calculations = new ArrayList<>();
        List<InvestmentReplayTraceStep> traceSteps = new ArrayList<>();
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
            traceSteps.add(new InvestmentReplayTraceStep(
                    entry.effectiveAnchorId(), entry.effectiveAnchorId(), entry.replaySequence(),
                    effectiveTradeTime(entry, entriesById), entry.command().transactionType(), result));
            hasEffectiveEntry = true;
            if (entry.command().transactionType() == InvestmentTransactionType.BUY
                    || entry.command().transactionType() == InvestmentTransactionType.OPENING_POSITION) {
                hasEffectiveBuyOrOpeningPosition = true;
            }
        }
        return new InvestmentReplayResult(position, calculations, InvestmentReplayTrace.of(traceSteps));
    }

    private boolean isOriginalTradeFact(InvestmentReplayEntry entry) {
        return entry.command().transactionType() == InvestmentTransactionType.BUY
                || entry.command().transactionType() == InvestmentTransactionType.SELL
                || entry.command().transactionType() == InvestmentTransactionType.DIVIDEND;
    }

    private java.time.Instant effectiveTradeTime(InvestmentReplayEntry entry,
                                                 Map<Long, InvestmentReplayEntry> entriesById) {
        return entry.replayAnchorTransactionId() == null
                ? entry.tradeTime()
                : entriesById.get(entry.replayAnchorTransactionId()).tradeTime();
    }
}
