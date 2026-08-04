package com.financeos.module.investment.read.mapper;

import java.time.Instant;

public record LogicalTransactionReadCriteria(Long userId, Long positionId, Long accountId, Long instrumentId,
                                             String type, String correctionStatus, Instant from, Instant to,
                                             Instant lastEffectiveTradeTime, Long lastLogicalTransactionId, int limit) {
}
