package com.financeos.module.asset.marketdata.fx.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeRateTest {

    @Test
    void normalizesCurrenciesBeforePersistence() {
        ExchangeRate exchangeRate = validRate();
        exchangeRate.setBaseCurrency(" usd ");
        exchangeRate.setQuoteCurrency(" cny ");

        exchangeRate.prepareForPersistence();

        assertThat(exchangeRate.getBaseCurrency()).isEqualTo("USD");
        assertThat(exchangeRate.getQuoteCurrency()).isEqualTo("CNY");
    }

    @Test
    void rejectsInvalidSnapshotInputs() {
        assertThatThrownBy(() -> validRateWith(null, "CNY", BigDecimal.ONE, Instant.now(), "FX").prepareForPersistence())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validRateWith("US", "CNY", BigDecimal.ONE, Instant.now(), "FX").prepareForPersistence())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validRateWith("CNY", "CNY", BigDecimal.ONE, Instant.now(), "FX").prepareForPersistence())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validRateWith("USD", "CNY", BigDecimal.ZERO, Instant.now(), "FX").prepareForPersistence())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validRateWith("USD", "CNY", BigDecimal.ONE.negate(), Instant.now(), "FX").prepareForPersistence())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validRateWith("USD", "CNY", BigDecimal.ONE, null, "FX").prepareForPersistence())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validRateWith("USD", "CNY", BigDecimal.ONE, Instant.now(), " ").prepareForPersistence())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ExchangeRate validRate() {
        return validRateWith("USD", "CNY", BigDecimal.ONE, Instant.parse("2026-01-02T03:04:05Z"), "FX");
    }

    private ExchangeRate validRateWith(String baseCurrency, String quoteCurrency, BigDecimal rate, Instant rateTime, String provider) {
        ExchangeRate exchangeRate = new ExchangeRate();
        exchangeRate.setBaseCurrency(baseCurrency);
        exchangeRate.setQuoteCurrency(quoteCurrency);
        exchangeRate.setRate(rate);
        exchangeRate.setRateTime(rateTime);
        exchangeRate.setProvider(provider);
        return exchangeRate;
    }
}
