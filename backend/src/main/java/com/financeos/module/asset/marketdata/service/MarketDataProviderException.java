package com.financeos.module.asset.marketdata.service;

import lombok.Getter;

@Getter
public class MarketDataProviderException extends RuntimeException {
    private final MarketDataErrorType errorType;

    public MarketDataProviderException(MarketDataErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public MarketDataProviderException(MarketDataErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }
}
