package com.financeos.module.asset.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financeos.common.PageResult;
import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetMarketQuoteServiceTest {

    @Test
    void listAttachesOneCachedQuotePerSupportedSymbolWithoutChangingValuation() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        MarketQuoteQueryService quoteQueryService = mock(MarketQuoteQueryService.class);
        Asset stock = asset(1L, "STOCK", " aapl ");
        Asset duplicate = asset(2L, "ETF", "AAPL");
        Asset cash = asset(3L, "CASH", "AAPL");
        Asset invalid = asset(4L, "STOCK", "!bad");
        when(assetMapper.selectList(any())).thenReturn(List.of(stock, duplicate, cash, invalid));
        MarketQuoteSnapshotResponse quote = quote();
        when(quoteQueryService.findCachedUsQuotes(List.of(" aapl ", "AAPL", "!bad"))).thenReturn(Map.of("AAPL", quote));
        when(quoteQueryService.normalizeSymbol(" aapl ")).thenReturn("AAPL");
        when(quoteQueryService.normalizeSymbol("AAPL")).thenReturn("AAPL");
        when(quoteQueryService.normalizeSymbol("!bad")).thenReturn(null);

        List<AssetResponse> responses = new AssetService(assetMapper, quoteQueryService).listByUser(9L);

        assertThat(responses).extracting(AssetResponse::marketQuote).containsExactly(quote, quote, null, null);
        assertThat(responses).extracting(AssetResponse::currentPrice).containsExactly(stock.getCurrentPrice(), duplicate.getCurrentPrice(), cash.getCurrentPrice(), invalid.getCurrentPrice());
        assertThat(responses).extracting(AssetResponse::marketValue).containsExactly(stock.getMarketValue(), duplicate.getMarketValue(), cash.getMarketValue(), invalid.getMarketValue());
        verify(quoteQueryService).findCachedUsQuotes(List.of(" aapl ", "AAPL", "!bad"));
        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    @Test
    void pageOnlyQueriesQuotesForRecordsOnTheCurrentPage() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        MarketQuoteQueryService quoteQueryService = mock(MarketQuoteQueryService.class);
        Page<Asset> page = new Page<>(2, 20, 21);
        page.setRecords(List.of(asset(7L, "STOCK", "MSFT")));
        when(assetMapper.selectPage(any(), any())).thenReturn(page);
        when(quoteQueryService.findCachedUsQuotes(List.of("MSFT"))).thenReturn(Map.of("MSFT", quote()));
        when(quoteQueryService.normalizeSymbol("MSFT")).thenReturn("MSFT");

        PageResult<AssetResponse> response = new AssetService(assetMapper, quoteQueryService).pageByUser(9L, 2, 20);

        assertThat(response.records()).hasSize(1);
        assertThat(response.records().getFirst().marketQuote()).isEqualTo(quote());
        verify(quoteQueryService).findCachedUsQuotes(List.of("MSFT"));
    }

    @Test
    void detailReadsOnlyItsCachedQuoteForSupportedAssets() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        MarketQuoteQueryService quoteQueryService = mock(MarketQuoteQueryService.class);
        Asset asset = asset(7L, "ETF", "MSFT");
        when(assetMapper.selectById(7L)).thenReturn(asset);
        when(quoteQueryService.findCachedUsQuote("MSFT")).thenReturn(quote());

        AssetResponse response = new AssetService(assetMapper, quoteQueryService).getById(9L, 7L);

        assertThat(response.marketQuote()).isEqualTo(quote());
        verify(quoteQueryService).findCachedUsQuote("MSFT");
        verify(assetMapper, never()).updateById(any(Asset.class));
    }

    private Asset asset(Long id, String type, String symbol) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setUserId(9L);
        asset.setName(symbol);
        asset.setSymbol(symbol);
        asset.setType(type);
        asset.setMarket("US");
        asset.setCurrency("CNY");
        asset.setQuantity(BigDecimal.TEN);
        asset.setAvgCost(BigDecimal.TEN);
        asset.setCurrentPrice(new BigDecimal("11"));
        asset.setMarketValue(new BigDecimal("110"));
        return asset;
    }

    private MarketQuoteSnapshotResponse quote() {
        return new MarketQuoteSnapshotResponse("AAPL", "US", "USD", new BigDecimal("212.34"),
                Instant.parse("2026-07-18T00:00:00Z"), Instant.parse("2026-07-18T00:01:00Z"),
                "TEST", MarketQuoteFreshness.FRESH);
    }
}
