package com.financeos.module.asset.marketdata.fx.provider;

public class ExchangeRateProviderException extends RuntimeException {
    private final ErrorType errorType;

    public ExchangeRateProviderException(ErrorType errorType) {
        super(errorType.safeMessage);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public enum ErrorType {
        DISABLED("Exchange rate provider is disabled"),
        CONFIGURATION("Exchange rate provider configuration is invalid"),
        AUTHENTICATION("Exchange rate provider authentication failed"),
        RATE_LIMITED("Exchange rate provider rate limit was reached"),
        UPSTREAM_ERROR("Exchange rate provider is unavailable"),
        TRANSPORT("Exchange rate provider could not be reached"),
        RESPONSE_FORMAT("Exchange rate provider response is invalid"),
        UNSUPPORTED_PAIR("Exchange rate currency pair is unsupported"),
        INVALID_RATE("Exchange rate provider returned an invalid rate"),
        INVALID_TIME("Exchange rate provider returned an invalid time"),
        CURRENCY_MISMATCH("Exchange rate provider returned a mismatched currency pair");

        private final String safeMessage;

        ErrorType(String safeMessage) {
            this.safeMessage = safeMessage;
        }
    }
}
