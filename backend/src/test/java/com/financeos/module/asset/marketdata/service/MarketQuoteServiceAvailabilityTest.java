package com.financeos.module.asset.marketdata.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketQuoteServiceAvailabilityTest {

    @Test
    void exposesTheMarketQuoteRefreshService() {
        assertThat(isPresent("com.financeos.module.asset.marketdata.service.MarketQuoteService")).isTrue();
    }

    private boolean isPresent(String typeName) {
        try {
            Class.forName(typeName);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
