package com.financeos.module.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionDuplicateProbe(Long accountId, Long categoryId, String type, BigDecimal amount,
                                        LocalDateTime transactedAt, String description) {
}
