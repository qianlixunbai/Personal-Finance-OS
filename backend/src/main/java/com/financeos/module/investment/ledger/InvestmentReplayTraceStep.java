package com.financeos.module.investment.ledger;

import java.time.Instant;

/** A canonical, effective replay fact. Physical storage ids are intentionally excluded. */
public record InvestmentReplayTraceStep(
        long logicalFactIdentity,
        long effectiveAnchorId,
        short replaySequence,
        Instant tradeTime,
        InvestmentTransactionType transactionType,
        InvestmentCalculationResult calculation) {

    public InvestmentReplayTraceStep {
        if (logicalFactIdentity <= 0 || effectiveAnchorId <= 0 || replaySequence < 0
                || tradeTime == null || transactionType == null || calculation == null) {
            throw new InvestmentLedgerValidationException("Replay trace step is incomplete");
        }
    }
}
