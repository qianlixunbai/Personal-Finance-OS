package com.financeos.module.investment.command.dto;

public sealed interface InvestmentReplacementRequest permits BuyReplacementRequest, SellReplacementRequest, DividendReplacementRequest {
    String reason();
    String externalReference();
    String note();
}
