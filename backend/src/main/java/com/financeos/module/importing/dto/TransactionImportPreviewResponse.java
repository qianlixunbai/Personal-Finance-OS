package com.financeos.module.importing.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionImportPreviewResponse(UUID importSessionId, UUID importBatchId, String sessionStatus,
                                               int revision, List<String> detectedColumns,
                                               TransactionImportMapping mapping,
                                               List<TransactionImportPreviewRow> rows,
                                               TransactionImportPreviewSummary summary,
                                               String fileDigest, String mappingDigest,
                                               String optionsDigest, String normalizedRowsDigest,
                                               Instant expiresAt, boolean confirmable, String previewToken) {
    public TransactionImportPreviewResponse {
        detectedColumns = List.copyOf(detectedColumns);
        rows = List.copyOf(rows);
    }
}
