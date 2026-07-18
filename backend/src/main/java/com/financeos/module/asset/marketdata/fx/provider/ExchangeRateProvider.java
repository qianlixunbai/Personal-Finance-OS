package com.financeos.module.asset.marketdata.fx.provider;

public interface ExchangeRateProvider {
    ExchangeRateQuote fetchRate(String baseCurrency, String quoteCurrency);
}
