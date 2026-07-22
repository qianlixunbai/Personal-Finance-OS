package com.financeos.module.investment.ledger;

import java.time.LocalDateTime;

public record InvestmentReplayEntry(
        long id,
        LocalDateTime tradeTime,
        InvestmentTransactionStatus status,
        InvestmentLedgerCommand command) {

    public InvestmentReplayEntry {
        if (id <= 0) {
            throw new InvestmentLedgerValidationException("Replay entry id must be positive");
        }
        if (tradeTime == null || status == null || command == null) {
            throw new InvestmentLedgerValidationException("Replay entry trade time, status and command are required");
        }
    }
}
