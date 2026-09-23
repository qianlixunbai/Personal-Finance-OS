package com.financeos.module.ledger.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferRequest(
        @NotNull
        @Positive
        Long fromAccountId,
        @NotNull
        @Positive
        Long toAccountId,
        @NotNull
        @Positive
        @Digits(integer = 16, fraction = 2)
        BigDecimal amount,
        @NotNull
        LocalDateTime transactedAt,
        @Size(max = 500)
        String description
) {
}
