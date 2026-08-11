package com.financeos.module.investment.read.service;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.valuation.dto.ReferenceValuationFreshness;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import com.financeos.module.asset.valuation.dto.ReferenceValuationWarning;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
import com.financeos.module.investment.read.dto.InvestmentPortfolioResponse;
import com.financeos.module.investment.read.mapper.InvestmentPortfolioStatisticsRow;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class InvestmentPortfolioQueryService {
    private static final String CNY = ReferenceValuationService.BASE_CURRENCY;

    private final InvestmentReadMapper mapper;
    private final MarketQuoteQueryService quoteQueries;
    private final ReferenceValuationService valuationService;

    public InvestmentPortfolioQueryService(InvestmentReadMapper mapper, MarketQuoteQueryService quoteQueries,
                                           ReferenceValuationService valuationService) {
        this.mapper = mapper;
        this.quoteQueries = quoteQueries;
        this.valuationService = valuationService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvestmentPortfolioResponse get(Long userId) {
        InvestmentPortfolioStatisticsRow statistics = mapper.selectPortfolioStatistics(userId);
        if (statistics == null) {
            statistics = new InvestmentPortfolioStatisticsRow();
        }
        List<Asset> openPositions = mapper.selectOpenTransactionDrivenPositionAssets(userId);
        Map<Long, ReferenceValuationResponse> valuations = openPositions.isEmpty() ? Map.of()
                : valuationService.calculateAll(openPositions, quoteQueries.findCachedUsQuotes(
                        openPositions.stream().filter(this::isUsPosition).map(Asset::getSymbol).toList()));
        return new InvestmentPortfolioResponse(CNY, statistics.getPositionCount(), statistics.getOpenPositionCount(),
                statistics.getClosedPositionCount(), amount(statistics.getOpenTotalCost()),
                amount(statistics.getCumulativeRealizedProfitLoss()), summarize(openPositions, valuations));
    }

    private InvestmentPortfolioResponse.PortfolioReferenceValuation summarize(List<Asset> openPositions,
                                                                                Map<Long, ReferenceValuationResponse> valuations) {
        BigDecimal value = BigDecimal.ZERO;
        int valued = 0;
        boolean stale = false;
        List<InvestmentPortfolioResponse.Warning> warnings = new ArrayList<>();
        for (Asset position : openPositions) {
            ReferenceValuationResponse valuation = valuations.get(position.getId());
            if (valuation == null) {
                continue;
            }
            if (valuation.baseCurrencyMarketValue() != null) {
                value = value.add(valuation.baseCurrencyMarketValue());
                valued++;
            }
            stale |= valuation.valuationFreshness() == ReferenceValuationFreshness.STALE;
            for (ReferenceValuationWarning warning : valuation.warnings()) {
                warnings.add(new InvestmentPortfolioResponse.Warning(warning.code(), warning.component()));
            }
        }
        String freshness = valued == 0 ? ReferenceValuationFreshness.UNAVAILABLE.name()
                : valued < openPositions.size() ? ReferenceValuationFreshness.PARTIAL.name()
                : stale ? ReferenceValuationFreshness.STALE.name() : ReferenceValuationFreshness.FRESH.name();
        return new InvestmentPortfolioResponse.PortfolioReferenceValuation(CNY,
                valued == 0 ? null : InvestmentReadSupport.decimal(value, 2), valued, openPositions.size(), freshness, warnings);
    }

    private String amount(BigDecimal value) {
        return InvestmentReadSupport.decimal(value == null ? BigDecimal.ZERO : value, 2);
    }

    private boolean isUsPosition(Asset asset) {
        return asset.getSymbol() != null && "US".equalsIgnoreCase(asset.getMarket());
    }
}
