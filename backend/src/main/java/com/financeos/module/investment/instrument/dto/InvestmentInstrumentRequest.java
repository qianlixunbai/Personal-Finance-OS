package com.financeos.module.investment.instrument.dto;

import jakarta.validation.constraints.NotBlank;

public record InvestmentInstrumentRequest(@NotBlank String symbol, @NotBlank String name, @NotBlank String market,
                                          @NotBlank String assetClass, @NotBlank String quoteCurrency) {
}
