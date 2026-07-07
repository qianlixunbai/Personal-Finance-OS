package com.financeos.module.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
        Long id,
        Long accountId,
        Long categoryId,
        String type,
        BigDecimal amount,
        String currency,
        String description,
        LocalDateTime transactedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
