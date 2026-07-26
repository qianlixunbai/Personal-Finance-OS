package com.financeos.module.investment.instrument.service;

public record CreateInvestmentInstrumentCommand(
        String symbol,
        String name,
        String market,
        String assetClass,
        String quoteCurrency
) {
}
