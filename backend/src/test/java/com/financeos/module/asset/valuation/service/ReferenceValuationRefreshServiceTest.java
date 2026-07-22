package com.financeos.module.asset.valuation.service;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.marketdata.service.MarketQuoteService;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateQueryService;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateService;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

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
}
