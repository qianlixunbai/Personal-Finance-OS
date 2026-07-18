package com.financeos.module.asset.marketdata.service;

public interface MarketDataProvider {
    MarketDataQuote fetchQuote(String symbol);
}
