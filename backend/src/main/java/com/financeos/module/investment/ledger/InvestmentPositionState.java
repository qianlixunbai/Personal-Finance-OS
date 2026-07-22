package com.financeos.module.investment.ledger;

import java.math.BigDecimal;

public record InvestmentPositionState(
        BigDecimal quantity,
        BigDecimal totalCost,
        BigDecimal cumulativeRealizedProfitLoss) {

    public InvestmentPositionState {
        quantity = requireNonNegative(quantity, 8, "Quantity").setScale(8);
        totalCost = requireNonNegative(totalCost, 2, "Total cost").setScale(2);
        cumulativeRealizedProfitLoss = requireScale(cumulativeRealizedProfitLoss, 2, "Cumulative realized profit/loss").setScale(2);
        if (quantity.signum() == 0 && totalCost.signum() != 0) {
            throw new InvestmentLedgerValidationException("Empty position cannot retain total cost");
        }
        if (quantity.signum() > 0 && totalCost.signum() == 0) {
            throw new InvestmentLedgerValidationException("Open position must retain positive total cost");
        }
    }

    public static InvestmentPositionState empty() {
        return new InvestmentPositionState(BigDecimal.ZERO.setScale(8), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
    }

    private static BigDecimal requireNonNegative(BigDecimal value, int scale, String name) {
        BigDecimal checked = requireScale(value, scale, name);
        if (checked.signum() < 0) {
            throw new InvestmentLedgerValidationException(name + " must not be negative");
        }
        return checked;
    }

    private static BigDecimal requireScale(BigDecimal value, int scale, String name) {
        if (value == null) {
            throw new InvestmentLedgerValidationException(name + " is required");
        }
        if (value.scale() > scale) {
            throw new InvestmentLedgerValidationException(name + " scale must not exceed " + scale);
        }
        return value;
    }
}
