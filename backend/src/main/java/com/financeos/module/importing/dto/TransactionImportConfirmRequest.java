package com.financeos.module.importing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record TransactionImportConfirmRequest(@NotBlank String previewToken,
                                              @NotNull List<String> acknowledgedWarningIds) {
    public TransactionImportConfirmRequest {
        acknowledgedWarningIds = List.copyOf(acknowledgedWarningIds);
    }
}
