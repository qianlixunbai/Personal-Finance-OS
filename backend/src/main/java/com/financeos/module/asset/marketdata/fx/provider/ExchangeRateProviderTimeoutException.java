package com.financeos.module.asset.marketdata.fx.provider;

public class ExchangeRateProviderTimeoutException extends ExchangeRateProviderException {
    public ExchangeRateProviderTimeoutException() {
        super(ErrorType.TIMEOUT);
    }
}
