package com.financeos.module.asset.marketdata.provider.twelvedata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
record TwelveDataQuotePayload(
        String symbol,
        String currency,
        BigDecimal close,
        String datetime,
        Long timestamp,
        Integer code,
        String status
) {
}
