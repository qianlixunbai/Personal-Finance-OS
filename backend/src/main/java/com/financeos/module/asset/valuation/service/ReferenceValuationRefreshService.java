package com.financeos.module.asset.valuation.service;

import com.financeos.common.BusinessException;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.marketdata.service.MarketQuoteService;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateFreshness;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateQueryService;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateRefreshResult;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateService;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class ReferenceValuationRefreshService {
    private static final String MARKET = "US";

    private final AssetMapper assetMapper;
    private final MarketQuoteQueryService quoteQueryService;
    private final MarketQuoteService quoteService;
    private final ExchangeRateQueryService exchangeRateQueryService;
    private final ExchangeRateService exchangeRateService;
    private final ReferenceValuationService valuationService;

    public ReferenceValuationRefreshService(AssetMapper assetMapper, MarketQuoteQueryService quoteQueryService,
                                            MarketQuoteService quoteService,
                                            ExchangeRateQueryService exchangeRateQueryService,
                                            ExchangeRateService exchangeRateService,
                                            ReferenceValuationService valuationService) {
        this.assetMapper = assetMapper;
        this.quoteQueryService = quoteQueryService;
        this.quoteService = quoteService;
        this.exchangeRateQueryService = exchangeRateQueryService;
        this.exchangeRateService = exchangeRateService;
        this.valuationService = valuationService;
    }

    public ReferenceValuationResponse refresh(Long userId, Long assetId) {
        Asset asset = ownedRefreshableAsset(userId, assetId);
        String symbol = quoteQueryService.normalizeSymbol(asset.getSymbol());
        if (symbol == null) {
            throw new BusinessException(400, "Asset symbol is invalid");
        }
        MarketQuoteQueryService.QuoteSnapshot snapshot = quoteQueryService.find(MARKET, symbol);
        MarketQuoteSnapshotResponse quote = snapshot.quote() == null ? null : snapshot(snapshot.quote(), snapshot.freshness());
        if (snapshot.freshness() != MarketQuoteFreshness.FRESH) {
            quoteService.refresh(userId, assetId);
            snapshot = quoteQueryService.find(MARKET, symbol);
            quote = snapshot.quote() == null ? null : snapshot(snapshot.quote(), snapshot.freshness());
        }
        if (quote == null || ReferenceValuationService.BASE_CURRENCY.equalsIgnoreCase(quote.currency())) {
            return valuationService.calculate(asset, quote, null);
        }
        ExchangeRate rate = exchangeRateQueryService.find(quote.currency(), ReferenceValuationService.BASE_CURRENCY);
        ExchangeRateRefreshResult refreshResult = null;
        if (exchangeRateQueryService.freshnessOf(rate) != ExchangeRateFreshness.FRESH) {
            refreshResult = exchangeRateService.refreshRate(userId, quote.currency(), ReferenceValuationService.BASE_CURRENCY);
            rate = exchangeRateQueryService.find(quote.currency(), ReferenceValuationService.BASE_CURRENCY);
        }
        return valuationService.withRefreshWarning(valuationService.calculate(asset, quote, rate),
                refreshResult == null ? null : publicWarningCode(refreshResult.warningCode()));
    }

    private Asset ownedRefreshableAsset(Long userId, Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || !userId.equals(asset.getUserId())) {
            throw new BusinessException(404, "Asset not found");
        }
        boolean typeSupported = "STOCK".equalsIgnoreCase(asset.getType()) || "ETF".equalsIgnoreCase(asset.getType());
        boolean marketSupported = asset.getMarket() != null && MARKET.equals(asset.getMarket().trim().toUpperCase(Locale.ROOT));
        if (!typeSupported || !marketSupported) {
            throw new BusinessException(400, "Asset does not support reference valuation refresh");
        }
        return asset;
    }

    private MarketQuoteSnapshotResponse snapshot(MarketQuote quote, MarketQuoteFreshness freshness) {
        return new MarketQuoteSnapshotResponse(quote.getSymbol(), quote.getMarket(), quote.getCurrency(), quote.getPrice(),
                quote.getQuoteTime(), quote.getFetchedAt(), quote.getProvider(), freshness);
    }

    private String publicWarningCode(String warningCode) {
        return "FX_REFRESH_FAILED".equals(warningCode) ? "REFRESH_FAILED" : warningCode;
    }
}
