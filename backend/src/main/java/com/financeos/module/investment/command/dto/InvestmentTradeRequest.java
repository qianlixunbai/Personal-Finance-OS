package com.financeos.module.investment.command.dto;

import jakarta.validation.constraints.NotBlank;

public record InvestmentTradeRequest(
        @NotBlank String quantity,
        @NotBlank String unitPrice,
        @NotBlank String feeAmount,
        @NotBlank String taxAmount) {
}
