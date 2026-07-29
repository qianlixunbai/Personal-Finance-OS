package com.financeos.module.investment.ledger;

import java.time.Instant;

public record InvestmentReplayEntry(
        long id,
        Instant tradeTime,
        InvestmentTransactionStatus status,
        InvestmentLedgerCommand command,
        Long originalTransactionId) {

    public InvestmentReplayEntry(long id, Instant tradeTime, InvestmentTransactionStatus status,
                                 InvestmentLedgerCommand command) {
        this(id, tradeTime, status, command, null);
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
    }
}
