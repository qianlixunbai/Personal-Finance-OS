package com.financeos.module.importing.dto;

import java.time.Instant;
import java.util.UUID;

public record TransactionImportBatchStatusResponse(UUID importBatchId, String status, String sourceFileName,
                                                   String fileDigest, int totalRows, int acceptedRows,
                                                   int createdCount, int skippedCount, int warningCount,
                                                   Instant confirmedAt, String contractVersion) {
}
