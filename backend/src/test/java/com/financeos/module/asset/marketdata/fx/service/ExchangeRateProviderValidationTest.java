package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.config.FxDataProperties;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProvider;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProviderException;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExchangeRateProviderValidationTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void rejectsANonPositiveProviderRateWithTheInvalidRateCategory() {
        Fixture fixture = fixture();
        when(fixture.provider.fetchRate("USD", "CNY")).thenReturn(quote("USD", "CNY", BigDecimal.ZERO));

        assertThatThrownBy(() -> fixture.service.refreshRate(1L, "USD", "CNY"))
                .isInstanceOf(ExchangeRateProviderException.class)
                .extracting(error -> ((ExchangeRateProviderException) error).getErrorType())
                .isEqualTo(ExchangeRateProviderException.ErrorType.INVALID_RATE);
    }

    @Test
    void rejectsAMismatchedCurrencyPairWithTheCurrencyMismatchCategory() {
        Fixture fixture = fixture();
        when(fixture.provider.fetchRate("USD", "CNY")).thenReturn(quote("EUR", "CNY", BigDecimal.ONE));

        assertThatThrownBy(() -> fixture.service.refreshRate(1L, "USD", "CNY"))
                .isInstanceOf(ExchangeRateProviderException.class)
                .extracting(error -> ((ExchangeRateProviderException) error).getErrorType())
                .isEqualTo(ExchangeRateProviderException.ErrorType.CURRENCY_MISMATCH);
    }

    @Test
    void neverIncludesProviderSecretsInTheConfigurationFailureMessage() {
        Fixture fixture = fixture();
        fixture.properties.setApiKey("secret-key");
        fixture.properties.setBaseUrl("https://provider.example/private-path");
        fixture.properties.setEnabled(false);

        assertThatThrownBy(() -> fixture.service.refreshRate(1L, "USD", "CNY"))
                .hasMessageNotContaining("secret-key")
                .hasMessageNotContaining("private-path");
    }

    private Fixture fixture() {
        FxDataProperties properties = new FxDataProperties();
        properties.setEnabled(true);
        ExchangeRateMapper mapper = mock(ExchangeRateMapper.class);
        ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
        ExchangeRatePersistenceService persistence = mock(ExchangeRatePersistenceService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExchangeRateQueryService query = new ExchangeRateQueryService(mapper, properties, clock);
        ExchangeRateRateLimiter limiter = new ExchangeRateRateLimiter(properties, clock);
        return new Fixture(properties, provider, new ExchangeRateService(query, persistence, limiter, properties, provider, clock));
    }

    private ExchangeRateQuote quote(String base, String quote, BigDecimal rate) {
        return new ExchangeRateQuote(base, quote, rate, NOW, "TEST");
    }

    private record Fixture(FxDataProperties properties, ExchangeRateProvider provider, ExchangeRateService service) { }
}
