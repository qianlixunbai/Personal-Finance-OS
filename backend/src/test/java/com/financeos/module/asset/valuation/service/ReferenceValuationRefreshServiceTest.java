package com.financeos.module.asset.valuation.service;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.marketdata.service.MarketQuoteService;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateQueryService;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateFreshness;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateRefreshResult;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateRefreshStatus;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateService;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceValuationRefreshServiceTest {

    @Test
    void refreshUsesSystemIdentityForCnyQuotesWithoutCallingFxRefresh() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        MarketQuoteQueryService quoteQueryService = mock(MarketQuoteQueryService.class);
        MarketQuoteService quoteService = mock(MarketQuoteService.class);
        ExchangeRateQueryService fxQueryService = mock(ExchangeRateQueryService.class);
        ExchangeRateService fxService = mock(ExchangeRateService.class);
        ReferenceValuationService valuationService = mock(ReferenceValuationService.class);
        Asset asset = asset();
        MarketQuote quote = quote();
        ReferenceValuationResponse expected = mock(ReferenceValuationResponse.class);
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(quoteQueryService.normalizeSymbol("AAPL")).thenReturn("AAPL");
        when(quoteQueryService.find("US", "AAPL"))
                .thenReturn(new MarketQuoteQueryService.QuoteSnapshot(quote, MarketQuoteFreshness.FRESH));
        when(valuationService.calculate(org.mockito.ArgumentMatchers.eq(asset), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull())).thenReturn(expected);

        ReferenceValuationResponse actual = new ReferenceValuationRefreshService(assetMapper, quoteQueryService,
                quoteService, fxQueryService, fxService, valuationService).refresh(9L, 7L);

        assertThat(actual).isSameAs(expected);
        verify(valuationService).calculate(org.mockito.ArgumentMatchers.eq(asset), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull());
        verify(fxService, never()).refreshRate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(quoteService, never()).refresh(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsStaleValuationWithFeatureDisabledWarningWhenOldFxSnapshotIsUsable() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        MarketQuoteQueryService quoteQueryService = mock(MarketQuoteQueryService.class);
        MarketQuoteService quoteService = mock(MarketQuoteService.class);
        ExchangeRateQueryService fxQueryService = mock(ExchangeRateQueryService.class);
        ExchangeRateService fxService = mock(ExchangeRateService.class);
        Asset asset = asset();
        MarketQuote quote = quote();
        quote.setCurrency("USD");
        com.financeos.module.asset.marketdata.fx.entity.ExchangeRate oldRate = oldUsdCnyRate();
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        ReferenceValuationService valuationService = new ReferenceValuationService(fxQueryService,
                Clock.fixed(now, ZoneOffset.UTC));
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(quoteQueryService.normalizeSymbol("AAPL")).thenReturn("AAPL");
        when(quoteQueryService.find("US", "AAPL"))
                .thenReturn(new MarketQuoteQueryService.QuoteSnapshot(quote, MarketQuoteFreshness.FRESH));
        when(fxQueryService.find("USD", "CNY")).thenReturn(oldRate);
        when(fxQueryService.freshnessOf(oldRate)).thenReturn(ExchangeRateFreshness.STALE);
        when(fxService.refreshRate(9L, "USD", "CNY")).thenReturn(new ExchangeRateRefreshResult(
                "USD", "CNY", oldRate.getRate(), oldRate.getRateTime(), oldRate.getFetchedAt(), oldRate.getProvider(),
                ExchangeRateFreshness.STALE, ExchangeRateRefreshStatus.STALE_FALLBACK, "FEATURE_DISABLED"));

        ReferenceValuationResponse result = new ReferenceValuationRefreshService(assetMapper, quoteQueryService,
                quoteService, fxQueryService, fxService, valuationService).refresh(9L, 7L);

        assertThat(result.valuationFreshness()).isEqualTo(com.financeos.module.asset.valuation.dto.ReferenceValuationFreshness.STALE);
        assertThat(result.fxFreshness()).isEqualTo(ExchangeRateFreshness.STALE);
        assertThat(result.baseCurrencyMarketValue()).isEqualByComparingTo("144.00");
        assertThat(result.warnings()).extracting(warning -> warning.code()).contains("FEATURE_DISABLED");
        verify(fxService).refreshRate(9L, "USD", "CNY");
        verify(quoteService, never()).refresh(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void translatesInternalFxRefreshFailureToThePublicWarningCode() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        MarketQuoteQueryService quoteQueryService = mock(MarketQuoteQueryService.class);
        MarketQuoteService quoteService = mock(MarketQuoteService.class);
        ExchangeRateQueryService fxQueryService = mock(ExchangeRateQueryService.class);
        ExchangeRateService fxService = mock(ExchangeRateService.class);
        Asset asset = asset();
        MarketQuote quote = quote();
        quote.setCurrency("USD");
        com.financeos.module.asset.marketdata.fx.entity.ExchangeRate oldRate = oldUsdCnyRate();
        ReferenceValuationService valuationService = new ReferenceValuationService(fxQueryService,
                Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC));
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(quoteQueryService.normalizeSymbol("AAPL")).thenReturn("AAPL");
        when(quoteQueryService.find("US", "AAPL"))
                .thenReturn(new MarketQuoteQueryService.QuoteSnapshot(quote, MarketQuoteFreshness.FRESH));
        when(fxQueryService.find("USD", "CNY")).thenReturn(oldRate);
        when(fxQueryService.freshnessOf(oldRate)).thenReturn(ExchangeRateFreshness.STALE);
        when(fxService.refreshRate(9L, "USD", "CNY")).thenReturn(new ExchangeRateRefreshResult(
                "USD", "CNY", oldRate.getRate(), oldRate.getRateTime(), oldRate.getFetchedAt(), oldRate.getProvider(),
                ExchangeRateFreshness.STALE, ExchangeRateRefreshStatus.STALE_FALLBACK, "FX_REFRESH_FAILED"));

        ReferenceValuationResponse result = new ReferenceValuationRefreshService(assetMapper, quoteQueryService,
                quoteService, fxQueryService, fxService, valuationService).refresh(9L, 7L);

        assertThat(result.warnings()).extracting(warning -> warning.code()).contains("REFRESH_FAILED");
        assertThat(result.warnings()).extracting(warning -> warning.code()).doesNotContain("FX_REFRESH_FAILED");
    }

    private Asset asset() {
        Asset asset = new Asset();
        asset.setId(7L);
        asset.setUserId(9L);
        asset.setType("STOCK");
        asset.setMarket("US");
        asset.setSymbol("AAPL");
        asset.setQuantity(new BigDecimal("2.00000000"));
        return asset;
    }

    private MarketQuote quote() {
        MarketQuote quote = new MarketQuote();
        quote.setMarket("US");
        quote.setSymbol("AAPL");
        quote.setCurrency("CNY");
        quote.setPrice(new BigDecimal("10.00000000"));
        quote.setQuoteTime(Instant.parse("2026-07-22T00:00:00Z"));
        quote.setFetchedAt(Instant.parse("2026-07-22T00:01:00Z"));
        quote.setProvider("TEST");
        return quote;
    }

    private com.financeos.module.asset.marketdata.fx.entity.ExchangeRate oldUsdCnyRate() {
        com.financeos.module.asset.marketdata.fx.entity.ExchangeRate rate = new com.financeos.module.asset.marketdata.fx.entity.ExchangeRate();
        rate.setBaseCurrency("USD");
        rate.setQuoteCurrency("CNY");
        rate.setRate(new BigDecimal("7.2"));
        rate.setRateTime(Instant.parse("2026-07-21T00:00:00Z"));
        rate.setFetchedAt(Instant.parse("2026-07-21T00:00:00Z"));
        rate.setProvider("TEST_FX");
        return rate;
    }
}
