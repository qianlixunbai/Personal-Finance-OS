package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExchangeRatePersistenceServiceTest {

    @Test
    void rejectsZeroBeforeCallingTheMapper() {
        ExchangeRateMapper mapper = mock(ExchangeRateMapper.class);
        ExchangeRate rate = validRate(BigDecimal.ZERO);

        assertThatIllegalArgumentException().isThrownBy(() -> new ExchangeRatePersistenceService(mapper).upsert(rate));

        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsARateThatRoundsBeyondNumericTwentyFourTwelveCapacity() {
        ExchangeRateMapper mapper = mock(ExchangeRateMapper.class);
        ExchangeRate rate = validRate(new BigDecimal("999999999999.9999999999995"));

        assertThatIllegalArgumentException().isThrownBy(() -> new ExchangeRatePersistenceService(mapper).upsert(rate));

        verifyNoInteractions(mapper);
    }

    @Test
    void roundsRatesExplicitlyWithHalfUpBeforePersisting() {
        ExchangeRateMapper mapper = mock(ExchangeRateMapper.class);
        ExchangeRate rate = validRate(new BigDecimal("7.1234567890125"));
        when(mapper.upsertLatest(rate)).thenReturn(1);
        when(mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(rate);

        ExchangeRate stored = new ExchangeRatePersistenceService(mapper).upsert(rate);

        assertThat(stored.getRate()).isEqualByComparingTo("7.123456789013");
        assertThat(rate.getRate().scale()).isEqualTo(12);
    }

    private ExchangeRate validRate(BigDecimal value) {
        ExchangeRate rate = new ExchangeRate();
        rate.setBaseCurrency("USD");
        rate.setQuoteCurrency("CNY");
        rate.setRate(value);
        rate.setRateTime(Instant.parse("2026-07-19T00:00:00Z"));
        rate.setFetchedAt(Instant.parse("2026-07-19T00:01:00Z"));
        rate.setProvider("TEST");
        return rate;
    }
}
