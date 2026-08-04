package com.financeos.module.investment.read.mapper;

public record PositionReadCriteria(Long userId, String status, Long accountId, Long instrumentId,
                                   Long lastInstrumentId, Long lastAccountId, Long lastPositionId, int limit) {
}
