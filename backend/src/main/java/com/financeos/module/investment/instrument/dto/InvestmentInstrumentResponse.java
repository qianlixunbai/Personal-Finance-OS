package com.financeos.module.investment.instrument.dto;

import com.financeos.module.investment.instrument.entity.InvestmentInstrument;

public record InvestmentInstrumentResponse(Long id, String symbol, String name, String market, String assetClass,
                                           String quoteCurrency, String status) {
    public static InvestmentInstrumentResponse from(InvestmentInstrument instrument) {
        return new InvestmentInstrumentResponse(instrument.getId(), instrument.getSymbol(), instrument.getName(),
                instrument.getMarket(), instrument.getAssetClass().name(), instrument.getQuoteCurrency(),
                instrument.getStatus().name());
    }
}
