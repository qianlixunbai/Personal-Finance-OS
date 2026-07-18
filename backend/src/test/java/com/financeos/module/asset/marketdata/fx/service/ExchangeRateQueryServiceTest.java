package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.config.FxDataProperties;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExchangeRateQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void calculatesFreshnessFromFetchedAtWithAnExclusiveTtlBoundary() {
        FxDataProperties properties = new FxDataProperties();
        properties.setCacheTtl(java.time.Duration.ofMinutes(60));
        ExchangeRateQueryService service = new ExchangeRateQueryService(mock(ExchangeRateMapper.class), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.freshnessOf(rate(NOW.minusSeconds(3599)))).isEqualTo(ExchangeRateFreshness.FRESH);
        assertThat(service.freshnessOf(rate(NOW.minusSeconds(3600)))).isEqualTo(ExchangeRateFreshness.STALE);
        assertThat(service.freshnessOf(null)).isEqualTo(ExchangeRateFreshness.NEVER_FETCHED);
    }

    private ExchangeRate rate(Instant fetchedAt) {
        ExchangeRate rate = new ExchangeRate();
        rate.setBaseCurrency("USD");
        rate.setQuoteCurrency("CNY");
        rate.setRate(BigDecimal.ONE);
        rate.setRateTime(NOW.minusSeconds(7200));
        rate.setFetchedAt(fetchedAt);
        rate.setProvider("TEST");
        return rate;
    }
}
