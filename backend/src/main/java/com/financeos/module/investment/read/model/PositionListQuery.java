package com.financeos.module.investment.read.model;

public record PositionListQuery(String status, Long accountId, Long instrumentId, String cursor, Integer size) {
}
