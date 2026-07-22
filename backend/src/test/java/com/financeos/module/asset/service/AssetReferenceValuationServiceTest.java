package com.financeos.module.asset.service;

import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AssetReferenceValuationServiceTest {

    @Test
    void listAttachesBatchCalculatedReferenceValuationsWithoutRefreshingProviders() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        MarketQuoteQueryService quoteQueryService = mock(MarketQuoteQueryService.class);
        ReferenceValuationService valuationService = mock(ReferenceValuationService.class);
        Asset asset = asset();
        MarketQuoteSnapshotResponse quote = quote();
        ReferenceValuationResponse valuation = mock(ReferenceValuationResponse.class);
        Map<String, MarketQuoteSnapshotResponse> quotes = Map.of("AAPL", quote);
        when(assetMapper.selectList(any())).thenReturn(List.of(asset));
        when(quoteQueryService.findCachedUsQuotes(List.of("AAPL"))).thenReturn(quotes);
        when(quoteQueryService.normalizeSymbol("AAPL")).thenReturn("AAPL");
        when(valuationService.calculateAll(List.of(asset), quotes)).thenReturn(Map.of(7L, valuation));

        List<AssetResponse> result = new AssetService(assetMapper, quoteQueryService, valuationService).listByUser(9L);

        assertThat(result).singleElement().satisfies(response -> {
            assertThat(response.marketQuote()).isEqualTo(quote);
            assertThat(response.referenceValuation()).isEqualTo(valuation);
        });
        verify(quoteQueryService).findCachedUsQuotes(List.of("AAPL"));
        verify(valuationService).calculateAll(List.of(asset), quotes);
        verifyNoMoreInteractions(valuationService);
    }

    private Asset asset() {
        Asset asset = new Asset();
        asset.setId(7L);
        asset.setUserId(9L);
        asset.setName("Apple");
        asset.setSymbol("AAPL");
        asset.setType("STOCK");
        asset.setMarket("US");
        asset.setCurrency("CNY");
        asset.setQuantity(new BigDecimal("2.00000000"));
        asset.setAvgCost(new BigDecimal("10.0000"));
        asset.setCurrentPrice(new BigDecimal("12.0000"));
        return asset;
    }

    private MarketQuoteSnapshotResponse quote() {
        return new MarketQuoteSnapshotResponse("AAPL", "US", "USD", new BigDecimal("20.00000000"),
                Instant.parse("2026-07-22T00:00:00Z"), Instant.parse("2026-07-22T00:01:00Z"),
                "TEST", MarketQuoteFreshness.FRESH);
    }
}
