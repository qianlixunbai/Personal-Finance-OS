package com.financeos.module.importing.dto;

import java.util.List;
import java.util.Map;

public record TransactionImportPreviewRow(int rowNumber, Map<String, String> sourceValues,
                                          Map<String, String> normalizedValues, String mappingStatus,
                                          List<TransactionImportValidationMessage> errors,
                                          List<TransactionImportValidationMessage> warnings,
                                          String duplicateStatus, boolean importable) {
    public TransactionImportPreviewRow {
        sourceValues = Map.copyOf(sourceValues);
        normalizedValues = Map.copyOf(normalizedValues);
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }
}
