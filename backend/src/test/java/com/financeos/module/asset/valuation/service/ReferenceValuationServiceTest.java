package com.financeos.module.asset.valuation.service;

import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateFreshness;
import com.financeos.module.asset.valuation.dto.ReferenceValuationFreshness;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceValuationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private final ReferenceValuationService service = new ReferenceValuationService(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void calculatesCnyReferenceValueWithExactIntermediatePrecisionAndHalfUpRounding() {
        ReferenceValuationResponse result = service.calculate(asset("12345678.12345678"),
                quote("USD", "98765432.12345678", MarketQuoteFreshness.FRESH),
                rate("7.123456789012", ExchangeRateFreshness.FRESH));

        BigDecimal nativeValue = new BigDecimal("12345678.12345678").multiply(new BigDecimal("98765432.12345678"));
        BigDecimal cnyValue = nativeValue.multiply(new BigDecimal("7.123456789012"));
        assertThat(result.nativeMarketValue()).isEqualByComparingTo(nativeValue);
        assertThat(result.baseCurrencyMarketValue()).isEqualByComparingTo(cnyValue.setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(result.baseCurrencyMarketValue().scale()).isEqualTo(2);
        assertThat(result.valuationFreshness()).isEqualTo(ReferenceValuationFreshness.FRESH);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void usesSystemIdentityForCnyQuoteWithoutAnExchangeRateSnapshot() {
        ReferenceValuationResponse result = service.calculate(asset("1.00000000"),
                quote("CNY", "10.005", MarketQuoteFreshness.FRESH), null);

        assertThat(result.fxRequired()).isFalse();
        assertThat(result.fxRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(result.fxProvider()).isEqualTo("SYSTEM_IDENTITY");
        assertThat(result.baseCurrencyMarketValue()).isEqualByComparingTo("10.01");
        assertThat(result.valuationFreshness()).isEqualTo(ReferenceValuationFreshness.FRESH);
    }

    @Test
    void retainsNativeValueAndReturnsPartialWhenFxIsMissing() {
        ReferenceValuationResponse result = service.calculate(asset("2.00000000"),
                quote("USD", "10.00000000", MarketQuoteFreshness.FRESH), null);

        assertThat(result.nativeMarketValue()).isEqualByComparingTo("20.0000000000000000");
        assertThat(result.baseCurrencyMarketValue()).isNull();
        assertThat(result.fxBaseCurrency()).isEqualTo("USD");
        assertThat(result.fxQuoteCurrency()).isEqualTo("CNY");
        assertThat(result.fxRate()).isNull();
        assertThat(result.valuationFreshness()).isEqualTo(ReferenceValuationFreshness.PARTIAL);
        assertThat(result.warnings()).extracting(warning -> warning.code()).contains("FX_MISSING");
    }

    @Test
    void returnsUnavailableWhenQuoteIsMissing() {
        ReferenceValuationResponse result = service.calculate(asset("2.00000000"), null, rate("7.1", ExchangeRateFreshness.FRESH));

        assertThat(result.quotePrice()).isNull();
        assertThat(result.nativeMarketValue()).isNull();
        assertThat(result.baseCurrencyMarketValue()).isNull();
        assertThat(result.valuationFreshness()).isEqualTo(ReferenceValuationFreshness.PARTIAL);
        assertThat(result.warnings()).extracting(warning -> warning.code()).contains("QUOTE_MISSING");
    }

    @Test
    void batchesOneHundredPositionsAndDeduplicatesFxCurrencies() {
        com.financeos.module.asset.marketdata.fx.service.ExchangeRateQueryService rateQueries =
                mock(com.financeos.module.asset.marketdata.fx.service.ExchangeRateQueryService.class);
        ReferenceValuationService batchService = new ReferenceValuationService(rateQueries, Clock.fixed(NOW, ZoneOffset.UTC));
        List<Asset> assets = java.util.stream.LongStream.rangeClosed(1, 100).mapToObj(id -> {
            Asset asset = asset("1.00000000");
            asset.setId(id);
            asset.setSymbol("S" + id);
            return asset;
        }).toList();
        Map<String, MarketQuoteSnapshotResponse> quotes = assets.stream().collect(java.util.stream.Collectors.toMap(
                Asset::getSymbol, asset -> new MarketQuoteSnapshotResponse(asset.getSymbol(), "US", "USD",
                        BigDecimal.TEN, NOW, NOW, "TEST", MarketQuoteFreshness.FRESH)));
        when(rateQueries.findAll(List.of("USD"), "CNY")).thenReturn(List.of(rate("7.1", ExchangeRateFreshness.FRESH)));
        when(rateQueries.freshnessOf(org.mockito.ArgumentMatchers.any())).thenReturn(ExchangeRateFreshness.FRESH);

        Map<Long, ReferenceValuationResponse> values = batchService.calculateAll(assets, quotes);

        assertThat(values).hasSize(100);
        verify(rateQueries, times(1)).findAll(List.of("USD"), "CNY");
    }

    private Asset asset(String quantity) {
        Asset asset = new Asset();
        asset.setId(7L);
        asset.setSymbol("AAPL");
        asset.setQuantity(new BigDecimal(quantity));
        return asset;
    }

    private MarketQuoteSnapshotResponse quote(String currency, String price, MarketQuoteFreshness freshness) {
        return new MarketQuoteSnapshotResponse("AAPL", "US", currency, new BigDecimal(price), NOW.minusSeconds(60), NOW,
                "TEST_QUOTE", freshness);
    }

    private ExchangeRate rate(String value, ExchangeRateFreshness freshness) {
        ExchangeRate rate = new ExchangeRate();
        rate.setBaseCurrency("USD");
        rate.setQuoteCurrency("CNY");
        rate.setRate(new BigDecimal(value));
        rate.setRateTime(NOW.minusSeconds(60));
        rate.setFetchedAt(NOW);
        rate.setProvider("TEST_FX");
        return rate;
    }
}
