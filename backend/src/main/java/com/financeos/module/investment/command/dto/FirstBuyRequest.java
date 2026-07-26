package com.financeos.module.investment.command.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FirstBuyRequest(
        @NotNull @Positive Long accountId,
        @NotNull @Positive Long instrumentId,
        String quantity,
        String unitPrice,
        String feeAmount,
        String taxAmount) {

    public InvestmentTradeRequest trade() {
        return new InvestmentTradeRequest(quantity, unitPrice, feeAmount, taxAmount);
    }
}
