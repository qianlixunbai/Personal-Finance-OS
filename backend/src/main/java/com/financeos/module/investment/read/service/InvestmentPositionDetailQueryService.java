package com.financeos.module.investment.read.service;

import com.financeos.common.BusinessException;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.valuation.dto.ReferenceValuationFreshness;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import com.financeos.module.asset.valuation.dto.ReferenceValuationWarning;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
import com.financeos.module.investment.read.dto.InvestmentPositionDetail;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import com.financeos.module.investment.read.mapper.InvestmentReadRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class InvestmentPositionDetailQueryService {
    private final InvestmentReadMapper mapper;
    private final MarketQuoteQueryService quoteQueries;
    private final ReferenceValuationService valuationService;

    public InvestmentPositionDetailQueryService(InvestmentReadMapper mapper, MarketQuoteQueryService quoteQueries,
                                                ReferenceValuationService valuationService) {
        this.mapper = mapper;
        this.quoteQueries = quoteQueries;
        this.valuationService = valuationService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvestmentPositionDetail get(Long userId, Long positionId) {
        InvestmentReadRow row = mapper.selectPositionDetail(userId, positionId);
        Asset asset = mapper.selectOwnedTransactionDrivenPositionAsset(userId, positionId);
        if (row == null || asset == null) {
            throw new BusinessException(404, "Investment position not found");
        }
        Map<String, MarketQuoteSnapshotResponse> quotes = asset.getSymbol() == null || !"US".equalsIgnoreCase(asset.getMarket()) ? Map.of()
                : quoteQueries.findCachedUsQuotes(List.of(asset.getSymbol()));
        if (quotes == null) {
            quotes = Map.of();
        }
        Map<Long, ReferenceValuationResponse> valuations = valuationService.calculateAll(List.of(asset), quotes);
        ReferenceValuationResponse valuation = valuations == null ? null : valuations.get(asset.getId());
        return new InvestmentPositionDetail(row.getPositionId(), row.getPositionMode(),
                new InvestmentPositionDetail.Account(row.getAccountId(), row.getAccountName(), row.getAccountType(), row.getAccountStatus()),
                new InvestmentPositionDetail.Instrument(row.getInstrumentId(), row.getInstrumentSymbol(), row.getInstrumentName(),
                        row.getInstrumentMarket(), row.getInstrumentAssetClass(), row.getInstrumentQuoteCurrency(), row.getInstrumentStatus()),
                InvestmentReadSupport.decimal(row.getQuantity(), 8), InvestmentReadSupport.decimal(row.getAverageCost(), 8),
                InvestmentReadSupport.decimal(row.getTotalCost(), 2),
                InvestmentReadSupport.decimal(row.getCumulativeRealizedProfitLoss(), 2), row.getPositionStatus(),
                new InvestmentPositionDetail.ManualReference(InvestmentReadSupport.decimal(asset.getCurrentPrice(), 8),
                        InvestmentReadSupport.decimal(asset.getMarketValue(), 2)), cachedReference(valuation));
    }

    private InvestmentPositionDetail.CachedReferenceValuation cachedReference(ReferenceValuationResponse value) {
        if (value == null) {
            return new InvestmentPositionDetail.CachedReferenceValuation(false, null, null, null, null, null,
                    null, null, null, null, null, null, ReferenceValuationService.BASE_CURRENCY, null,
                    ReferenceValuationFreshness.UNAVAILABLE.name(), List.of());
        }
        return new InvestmentPositionDetail.CachedReferenceValuation(false,
                InvestmentReadSupport.decimal(value.quotePrice(), 8), value.quoteCurrency(), instant(value.quoteTime()),
                instant(value.quoteFetchedAt()), value.quoteProvider(), InvestmentReadSupport.decimal(value.fxRate(), 12),
                value.fxBaseCurrency(), value.fxQuoteCurrency(), instant(value.fxRateTime()), instant(value.fxFetchedAt()),
                value.fxProvider(), value.baseCurrency(), InvestmentReadSupport.decimal(value.baseCurrencyMarketValue(), 2),
                value.valuationFreshness().name(), value.warnings().stream()
                        .map(warning -> new InvestmentPositionDetail.Warning(warning.code(), warning.component())).toList());
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
