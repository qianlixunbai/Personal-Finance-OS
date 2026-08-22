package com.financeos.module.importing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionImportReceipt(UUID importSessionId, UUID importBatchId, String status,
                                       String sourceFileName, String fileDigest, int totalRows,
                                       int acceptedRows, int createdCount, int skippedCount,
                                       int warningCount, List<TransactionReference> transactions,
                                       List<AccountImpact> accountImpacts, Instant confirmedAt,
                                       String contractVersion, String resultDigest) {
    public TransactionImportReceipt {
        transactions = List.copyOf(transactions);
        accountImpacts = List.copyOf(accountImpacts);
    }
    public record TransactionReference(int rowNumber, Long transactionId) { }
    public record AccountImpact(Long accountId, int rowCount, BigDecimal balanceBefore,
                                BigDecimal delta, BigDecimal balanceAfter) { }
}
