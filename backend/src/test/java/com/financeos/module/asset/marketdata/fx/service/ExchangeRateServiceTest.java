package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.config.FxDataProperties;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProvider;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProviderException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExchangeRateServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void returnsFreshCacheWithoutCallingProviderOrConsumingQuota() {
        Fixture fixture = fixture();
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(rate(NOW.minusSeconds(1)));

        ExchangeRateRefreshResult result = fixture.service.refreshRate(1L, " usd ", "cny");

        assertThat(result.status()).isEqualTo(ExchangeRateRefreshStatus.CACHE_HIT);
        verifyNoInteractions(fixture.provider, fixture.persistence);
    }

    @Test
    void returnsSyntheticCnyWithoutDatabaseProviderOrQuota() {
        Fixture fixture = fixture();

        ExchangeRateRefreshResult result = fixture.service.refreshRate(1L, "CNY", "CNY");

        assertThat(result.rate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.provider()).isEqualTo("SYSTEM");
        verifyNoInteractions(fixture.mapper, fixture.provider, fixture.persistence);
    }

    @Test
    void returnsStaleFallbackWhenProviderFailsWithAnOldSnapshot() {
        Fixture fixture = fixture();
        ExchangeRate old = rate(NOW.minusSeconds(3600));
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(old);
        when(fixture.provider.fetchRate("USD", "CNY")).thenThrow(new ExchangeRateProviderException(ExchangeRateProviderException.ErrorType.TIMEOUT));

        ExchangeRateRefreshResult result = fixture.service.refreshRate(1L, "USD", "CNY");

        assertThat(result.status()).isEqualTo(ExchangeRateRefreshStatus.STALE_FALLBACK);
        assertThat(result.rate()).isEqualByComparingTo(old.getRate());
        verify(fixture.persistence, never()).upsert(any());
    }

    @Test
    void rejectsDisabledRefreshWithoutCallingProvider() {
        Fixture fixture = fixture(); fixture.properties.setEnabled(false);

        assertThatThrownBy(() -> fixture.service.refreshRate(1L, "USD", "CNY"))
                .isInstanceOf(ExchangeRateProviderException.class);
        verifyNoInteractions(fixture.provider, fixture.persistence);
    }

    @Test
    void returnsStaleFallbackWhenRefreshIsDisabledAndAnOldSnapshotExists() {
        Fixture fixture = fixture();
        fixture.properties.setEnabled(false);
        ExchangeRate old = rate(NOW.minusSeconds(3600));
        when(fixture.mapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY")).thenReturn(old);

        ExchangeRateRefreshResult result = fixture.service.refreshRate(1L, "USD", "CNY");

        assertThat(result.status()).isEqualTo(ExchangeRateRefreshStatus.STALE_FALLBACK);
        assertThat(result.freshness()).isEqualTo(ExchangeRateFreshness.STALE);
        assertThat(result.warningCode()).isEqualTo("FEATURE_DISABLED");
        assertThat(result.rate()).isEqualByComparingTo(old.getRate());
        verifyNoInteractions(fixture.provider, fixture.persistence);
    }

    private Fixture fixture() {
        FxDataProperties properties = new FxDataProperties(); properties.setEnabled(true); properties.setCacheTtl(java.time.Duration.ofMinutes(60));
        ExchangeRateMapper mapper = mock(ExchangeRateMapper.class); ExchangeRateProvider provider = mock(ExchangeRateProvider.class);
        ExchangeRatePersistenceService persistence = mock(ExchangeRatePersistenceService.class); Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExchangeRateQueryService query = new ExchangeRateQueryService(mapper, properties, clock);
        ExchangeRateRateLimiter limiter = new ExchangeRateRateLimiter(properties, clock);
        return new Fixture(mapper, provider, persistence, properties, new ExchangeRateService(query, persistence, limiter, properties, provider, clock));
    }

    private ExchangeRate rate(Instant fetchedAt) { ExchangeRate rate = new ExchangeRate(); rate.setBaseCurrency("USD"); rate.setQuoteCurrency("CNY"); rate.setRate(new BigDecimal("7.2")); rate.setRateTime(NOW.minusSeconds(7200)); rate.setFetchedAt(fetchedAt); rate.setProvider("TEST"); return rate; }
    private record Fixture(ExchangeRateMapper mapper, ExchangeRateProvider provider, ExchangeRatePersistenceService persistence, FxDataProperties properties, ExchangeRateService service) { }
}
