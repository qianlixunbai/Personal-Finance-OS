package com.financeos.module.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionRequest(
        @NotNull Long accountId,
        @NotNull Long categoryId,
        @NotBlank String type,
        @NotNull BigDecimal amount,
        String currency,
        String description,
        @NotNull LocalDateTime transactedAt
) {
}
