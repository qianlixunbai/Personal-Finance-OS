package com.financeos.module.investment.read.cursor;

import java.time.Instant;

public record LogicalTransactionCursor(Instant effectiveTradeTime, Long logicalTransactionId) {
}
