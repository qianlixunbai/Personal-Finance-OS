package com.financeos.module.investment.read;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
import com.financeos.module.asset.valuation.dto.ReferenceValuationFreshness;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateFreshness;
import com.financeos.module.investment.read.dto.InvestmentPortfolioResponse;
import com.financeos.module.investment.read.mapper.InvestmentPortfolioStatisticsRow;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import com.financeos.module.investment.read.service.InvestmentPortfolioQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InvestmentPortfolioQueryServiceTest {

    @Test
    void summarizesOnlyOpenTransactionDrivenPositionsAndUsesCachedReferenceValues() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        MarketQuoteQueryService quoteQueries = mock(MarketQuoteQueryService.class);
        ReferenceValuationService valuationService = mock(ReferenceValuationService.class);
        InvestmentPortfolioQueryService service = new InvestmentPortfolioQueryService(mapper, quoteQueries, valuationService);
        Asset open = asset(11L, "AAPL", "2.00000000");
        open.setMarket("US");
        when(mapper.selectPortfolioStatistics(7L)).thenReturn(new InvestmentPortfolioStatisticsRow(2, 1, 1,
                new BigDecimal("20.00"), new BigDecimal("3.00")));
        when(mapper.selectOpenTransactionDrivenPositionAssets(7L)).thenReturn(List.of(open));
        when(quoteQueries.findCachedUsQuotes(List.of("AAPL"))).thenReturn(Map.of("AAPL",
                new MarketQuoteSnapshotResponse("AAPL", "US", "CNY", new BigDecimal("12.00000000"),
                        Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:01:00Z"),
                        "TEST", MarketQuoteFreshness.FRESH)));
        when(valuationService.calculateAll(org.mockito.ArgumentMatchers.eq(List.of(open)), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(Map.of(11L, new ReferenceValuationResponse(11L, "AAPL", new BigDecimal("2.00000000"),
                        "CNY", new BigDecimal("12.00000000"), null, null, "TEST", MarketQuoteFreshness.FRESH,
                        false, "CNY", "CNY", BigDecimal.ONE, null, null, "SYSTEM", ExchangeRateFreshness.FRESH,
                        new BigDecimal("24.00000000"), "CNY", new BigDecimal("24.00"),
                        ReferenceValuationFreshness.FRESH, Instant.parse("2026-08-01T00:02:00Z"), "REFERENCE_VALUATION_V1", List.of())));

        InvestmentPortfolioResponse response = service.get(7L);

        assertThat(response.currency()).isEqualTo("CNY");
        assertThat(response.positionCount()).isEqualTo(2);
        assertThat(response.openTotalCost()).isEqualTo("20.00");
        assertThat(response.cumulativeRealizedProfitLoss()).isEqualTo("3.00");
        assertThat(response.referenceValuation().value()).isEqualTo("24.00");
        assertThat(response.referenceValuation().valuedPositionCount()).isEqualTo(1);
        assertThat(response.referenceValuation().freshness()).isEqualTo("FRESH");
    }

    @Test
    void requestsUsQuotesOnlyForUsPositions() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        MarketQuoteQueryService quoteQueries = mock(MarketQuoteQueryService.class);
        ReferenceValuationService valuationService = mock(ReferenceValuationService.class);
        InvestmentPortfolioQueryService service = new InvestmentPortfolioQueryService(mapper, quoteQueries, valuationService);
        Asset us = asset(11L, "AAPL", "1.00000000");
        us.setMarket("US");
        Asset nonUs = asset(12L, "600000", "1.00000000");
        nonUs.setMarket("CN");
        when(mapper.selectPortfolioStatistics(7L)).thenReturn(new InvestmentPortfolioStatisticsRow());
        when(mapper.selectOpenTransactionDrivenPositionAssets(7L)).thenReturn(List.of(us, nonUs));
        when(quoteQueries.findCachedUsQuotes(List.of("AAPL"))).thenReturn(Map.of());
        when(valuationService.calculateAll(org.mockito.ArgumentMatchers.eq(List.of(us, nonUs)), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(Map.of());

        service.get(7L);

        verify(quoteQueries).findCachedUsQuotes(List.of("AAPL"));
    }

    @Test
    void returnsStableZeroAndUnavailableSemanticsForAnEmptyPortfolio() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        MarketQuoteQueryService quoteQueries = mock(MarketQuoteQueryService.class);
        ReferenceValuationService valuationService = mock(ReferenceValuationService.class);
        InvestmentPortfolioQueryService service = new InvestmentPortfolioQueryService(mapper, quoteQueries, valuationService);
        when(mapper.selectPortfolioStatistics(7L)).thenReturn(new InvestmentPortfolioStatisticsRow());
        when(mapper.selectOpenTransactionDrivenPositionAssets(7L)).thenReturn(List.of());

        InvestmentPortfolioResponse response = service.get(7L);

        assertThat(response.positionCount()).isZero();
        assertThat(response.openPositionCount()).isZero();
        assertThat(response.closedPositionCount()).isZero();
        assertThat(response.openTotalCost()).isEqualTo("0.00");
        assertThat(response.cumulativeRealizedProfitLoss()).isEqualTo("0.00");
        assertThat(response.referenceValuation().value()).isNull();
        assertThat(response.referenceValuation().valuedPositionCount()).isZero();
        assertThat(response.referenceValuation().totalOpenPositionCount()).isZero();
        assertThat(response.referenceValuation().freshness()).isEqualTo("UNAVAILABLE");
        assertThat(response.referenceValuation().warnings()).isEmpty();
        verifyNoInteractions(quoteQueries, valuationService);
    }

    @Test
    void coverageTakesPrecedenceOverFreshness() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        MarketQuoteQueryService quoteQueries = mock(MarketQuoteQueryService.class);
        ReferenceValuationService valuationService = mock(ReferenceValuationService.class);
        InvestmentPortfolioQueryService service = new InvestmentPortfolioQueryService(mapper, quoteQueries, valuationService);
        Asset valued = asset(11L, "AAPL", "1.00000000");
        valued.setMarket("US");
        Asset missing = asset(12L, "MSFT", "1.00000000");
        missing.setMarket("US");
        when(mapper.selectPortfolioStatistics(7L)).thenReturn(new InvestmentPortfolioStatisticsRow(2, 2, 0,
                new BigDecimal("20.00"), BigDecimal.ZERO));
        when(mapper.selectOpenTransactionDrivenPositionAssets(7L)).thenReturn(List.of(valued, missing));
        when(quoteQueries.findCachedUsQuotes(List.of("AAPL", "MSFT"))).thenReturn(Map.of());
        when(valuationService.calculateAll(org.mockito.ArgumentMatchers.eq(List.of(valued, missing)),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(11L,
                valuation(11L, "AAPL", "10.00", ReferenceValuationFreshness.FRESH)));

        InvestmentPortfolioResponse response = service.get(7L);

        assertThat(response.referenceValuation().value()).isEqualTo("10.00");
        assertThat(response.referenceValuation().valuedPositionCount()).isEqualTo(1);
        assertThat(response.referenceValuation().totalOpenPositionCount()).isEqualTo(2);
        assertThat(response.referenceValuation().freshness()).isEqualTo("PARTIAL");
    }

    @Test
    void completeCoverageIsStaleWhenAnyUsableValuationIsStale() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        MarketQuoteQueryService quoteQueries = mock(MarketQuoteQueryService.class);
        ReferenceValuationService valuationService = mock(ReferenceValuationService.class);
        InvestmentPortfolioQueryService service = new InvestmentPortfolioQueryService(mapper, quoteQueries, valuationService);
        Asset first = asset(11L, "AAPL", "1.00000000");
        first.setMarket("US");
        Asset second = asset(12L, "MSFT", "1.00000000");
        second.setMarket("US");
        when(mapper.selectPortfolioStatistics(7L)).thenReturn(new InvestmentPortfolioStatisticsRow(2, 2, 0,
                new BigDecimal("20.00"), BigDecimal.ZERO));
        when(mapper.selectOpenTransactionDrivenPositionAssets(7L)).thenReturn(List.of(first, second));
        when(quoteQueries.findCachedUsQuotes(List.of("AAPL", "MSFT"))).thenReturn(Map.of());
        when(valuationService.calculateAll(org.mockito.ArgumentMatchers.eq(List.of(first, second)),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
                11L, valuation(11L, "AAPL", "10.00", ReferenceValuationFreshness.FRESH),
                12L, valuation(12L, "MSFT", "20.00", ReferenceValuationFreshness.STALE)));

        InvestmentPortfolioResponse response = service.get(7L);

        assertThat(response.referenceValuation().value()).isEqualTo("30.00");
        assertThat(response.referenceValuation().valuedPositionCount()).isEqualTo(2);
        assertThat(response.referenceValuation().freshness()).isEqualTo("STALE");
    }

    private ReferenceValuationResponse valuation(long id, String symbol, String value,
                                                   ReferenceValuationFreshness freshness) {
        return new ReferenceValuationResponse(id, symbol, BigDecimal.ONE, "CNY", BigDecimal.ONE,
                null, null, "TEST", MarketQuoteFreshness.FRESH, false, "CNY", "CNY", BigDecimal.ONE,
                null, null, "SYSTEM", ExchangeRateFreshness.FRESH, new BigDecimal(value), "CNY",
                new BigDecimal(value), freshness, Instant.parse("2026-08-01T00:02:00Z"),
                "REFERENCE_VALUATION_V1", List.of());
    }

    private Asset asset(long id, String symbol, String quantity) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setSymbol(symbol);
        asset.setQuantity(new BigDecimal(quantity));
        return asset;
    }
}
