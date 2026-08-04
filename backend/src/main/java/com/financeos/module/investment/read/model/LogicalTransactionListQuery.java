package com.financeos.module.investment.read.model;

import java.time.Instant;

public record LogicalTransactionListQuery(Long positionId, Long accountId, Long instrumentId, String type,
                                          String correctionStatus, Instant from, Instant to, String cursor, Integer size) {
}
