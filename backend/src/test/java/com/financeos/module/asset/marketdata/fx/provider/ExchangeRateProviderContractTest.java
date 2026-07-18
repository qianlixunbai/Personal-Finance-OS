package com.financeos.module.asset.marketdata.fx.provider;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeRateProviderContractTest {

    @Test
    void quoteCarriesOnlyTheTraceableCurrencyPairRateAndProviderMetadata() {
        ExchangeRateQuote quote = new ExchangeRateQuote(
                "USD", "CNY", new BigDecimal("7.250000000000"),
                Instant.parse("2026-01-02T03:04:05Z"), "FX_PROVIDER");

        assertThat(quote.baseCurrency()).isEqualTo("USD");
        assertThat(quote.quoteCurrency()).isEqualTo("CNY");
        assertThat(quote.rate()).isEqualByComparingTo("7.250000000000");
        assertThat(quote.rateTime()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(quote.provider()).isEqualTo("FX_PROVIDER");
    }

    @Test
    void providerExceptionExposesOnlyItsSafeClassificationMessage() {
        ExchangeRateProviderException exception = new ExchangeRateProviderException(
                ExchangeRateProviderException.ErrorType.RESPONSE_FORMAT);

        assertThat(exception.getMessage()).isEqualTo("Exchange rate provider response is invalid");
        assertThat(exception.getMessage()).doesNotContain("api", "key", "response body");
    }
}
