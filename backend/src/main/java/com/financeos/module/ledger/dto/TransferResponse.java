package com.financeos.module.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferResponse (
        Long id,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        String currency,
        String description,
        LocalDateTime transactedAt,
        LocalDateTime createdAt
) {
}
