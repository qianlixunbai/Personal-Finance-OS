package com.financeos.module.importing.dto;

public record TransactionImportValidationMessage(String id, String code, String scope, String field,
                                                 Integer rowNumber, String message, boolean retryable) {
}
