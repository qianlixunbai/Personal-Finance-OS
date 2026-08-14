package com.financeos.module.importing.dto;

public record TransactionImportPreviewSummary(int totalRows, int validRows, int warningRows,
                                              int errorRows, int importableRows, int duplicateCandidates) {
}
