package com.financeos.module.investment.ledger;

import java.time.Instant;

public record InvestmentReplayEntry(
        long id,
        Instant tradeTime,
        InvestmentTransactionStatus status,
        InvestmentLedgerCommand command,
        Long originalTransactionId,
        Long replayAnchorTransactionId,
        short replaySequence) {

    public InvestmentReplayEntry(long id, Instant tradeTime, InvestmentTransactionStatus status,
                                 InvestmentLedgerCommand command) {
        this(id, tradeTime, status, command, null, null, (short) 0);
    }

    public InvestmentReplayEntry(long id, Instant tradeTime, InvestmentTransactionStatus status,
                                 InvestmentLedgerCommand command, Long originalTransactionId) {
        this(id, tradeTime, status, command, originalTransactionId, null, (short) 0);
    }

    public InvestmentReplayEntry {
        if (id <= 0) {
            throw new InvestmentLedgerValidationException("Replay entry id must be positive");
        }
        if (tradeTime == null || status == null || command == null) {
            throw new InvestmentLedgerValidationException("Replay entry trade time, status and command are required");
        }
        if (command.transactionType() == InvestmentTransactionType.REVERSAL && originalTransactionId == null) {
            throw new InvestmentLedgerValidationException("Reversal replay entry requires an original transaction id");
        }
        if (command.transactionType() != InvestmentTransactionType.REVERSAL && originalTransactionId != null) {
            throw new InvestmentLedgerValidationException("Only reversal replay entries may reference an original transaction");
        }
        if (replaySequence < 0) {
            throw new InvestmentLedgerValidationException("Replay sequence must not be negative");
        }
        if (replayAnchorTransactionId == null && replaySequence != 0) {
            throw new InvestmentLedgerValidationException("Replay sequence requires an anchor transaction id");
        }
        if (replayAnchorTransactionId != null && replayAnchorTransactionId <= 0) {
            throw new InvestmentLedgerValidationException("Replay anchor transaction id must be positive");
        }
        if (replayAnchorTransactionId != null && replaySequence != 1) {
            throw new InvestmentLedgerValidationException("Replay anchor requires replay sequence 1");
        }
    }

    public long effectiveAnchorId() {
        return replayAnchorTransactionId == null ? id : replayAnchorTransactionId;
    }
}
