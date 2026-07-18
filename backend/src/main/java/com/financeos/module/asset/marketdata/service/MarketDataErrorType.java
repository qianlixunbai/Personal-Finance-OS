package com.financeos.module.asset.marketdata.service;

public enum MarketDataErrorType {
    DISABLED,
    INVALID_REQUEST,
    NOT_FOUND,
    RATE_LIMITED,
    AUTHENTICATION,
    UPSTREAM_ERROR,
    PROVIDER_UNAVAILABLE,
    TRANSPORT,
    RESPONSE_FORMAT,
    INVALID_QUOTE
}
