package com.financeos.module.importing.dto;

public record TransactionImportPreviewRequest(TransactionImportFormat format, TransactionImportMapping mapping) {
    public TransactionImportPreviewRequest {
        format = format == null ? TransactionImportFormat.AUTO : format;
    }
}
