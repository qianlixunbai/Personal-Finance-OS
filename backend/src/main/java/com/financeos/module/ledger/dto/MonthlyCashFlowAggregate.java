package com.financeos.module.ledger.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MonthlyCashFlowAggregate(LocalDate monthStart, BigDecimal income, BigDecimal expense) {
}
