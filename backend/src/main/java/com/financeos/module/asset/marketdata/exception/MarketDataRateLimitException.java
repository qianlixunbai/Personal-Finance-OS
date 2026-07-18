package com.financeos.module.asset.marketdata.exception;

import com.financeos.common.BusinessException;

public class MarketDataRateLimitException extends BusinessException {
    public MarketDataRateLimitException() {
        super(429, "Market data refresh limit has been reached");
    }
}
