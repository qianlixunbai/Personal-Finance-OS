package com.financeos.module.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financeos.common.BusinessException;
import com.financeos.common.PageResult;
import com.financeos.module.asset.dto.AssetRequest;
import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AssetService {

    private static final String BASE_CURRENCY = "CNY";
    private static final String MARKET = "US";

    private final AssetMapper assetMapper;
    private final MarketQuoteQueryService marketQuoteQueryService;

    public AssetService(AssetMapper assetMapper, MarketQuoteQueryService marketQuoteQueryService) {
        this.assetMapper = assetMapper;
        this.marketQuoteQueryService = marketQuoteQueryService;
    }

    public List<AssetResponse> listByUser(Long userId) {
        List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getUserId, userId)
        );
        return toResponses(assets);
    }

    public PageResult<AssetResponse> pageByUser(Long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<Asset> result = assetMapper.selectPage(
                Page.of(safePage, safeSize),
                new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getUserId, userId)
                        .orderByDesc(Asset::getCreatedAt)
        );
        List<AssetResponse> records = toResponses(result.getRecords());
        return new PageResult<>(records, result.getTotal(), safePage, safeSize);
    }

    public AssetResponse getById(Long userId, Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null || !asset.getUserId().equals(userId)) {
            throw new BusinessException(404, "资产不存在");
        }
        return toResponse(asset, quoteFor(asset));
    }

    @Transactional
    public AssetResponse create(Long userId, AssetRequest req) {
        String currency = req.currency() != null ? req.currency() : BASE_CURRENCY;
        validateCurrency(currency);
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setName(req.name());
        asset.setSymbol(req.symbol());
        asset.setType(req.type());
        asset.setMarket(req.market());
        asset.setCurrency(currency);
        asset.setQuantity(req.quantity());
        asset.setAvgCost(req.avgCost());
        assetMapper.insert(asset);
        return toResponse(asset, null);
    }

    @Transactional
    public AssetResponse updatePrice(Long userId, Long id, BigDecimal currentPrice) {
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "资产价格必须大于 0");
        }
        Asset asset = assetMapper.selectById(id);
        if (asset == null || !asset.getUserId().equals(userId)) {
            throw new BusinessException(404, "资产不存在");
        }
        asset.setCurrentPrice(currentPrice);
        asset.setMarketValue(currentPrice.multiply(asset.getQuantity()));
        assetMapper.updateById(asset);
        return toResponse(asset, null);
    }

    @Transactional
    public AssetResponse close(Long userId, Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null || !asset.getUserId().equals(userId)) {
            throw new BusinessException(404, "资产不存在");
        }
        if (asset.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            asset.setQuantity(BigDecimal.ZERO);
            asset.setMarketValue(BigDecimal.ZERO);
            assetMapper.updateById(asset);
        }
        return toResponse(asset, null);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null || !asset.getUserId().equals(userId)) {
            throw new BusinessException(404, "资产不存在");
        }
        if (asset.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(400, "该资产仍有持仓，无法删除");
        }
        assetMapper.deleteById(id);
    }

    private void validateCurrency(String currency) {
        if (!BASE_CURRENCY.equals(currency)) {
            throw new BusinessException(400, "当前版本仅支持 CNY 币种");
        }
    }

    private List<AssetResponse> toResponses(List<Asset> assets) {
        Map<String, MarketQuoteSnapshotResponse> quotes = marketQuoteQueryService.findCachedUsQuotes(
                assets.stream().filter(this::supportsMarketQuote).map(Asset::getSymbol).toList());
        return assets.stream().map(asset -> toResponse(asset, quoteFromMap(asset, quotes))).toList();
    }

    private MarketQuoteSnapshotResponse quoteFromMap(Asset asset, Map<String, MarketQuoteSnapshotResponse> quotes) {
        if (!supportsMarketQuote(asset)) {
            return null;
        }
        String symbol = marketQuoteQueryService.normalizeSymbol(asset.getSymbol());
        return symbol == null ? null : quotes.get(symbol);
    }

    private MarketQuoteSnapshotResponse quoteFor(Asset asset) {
        return supportsMarketQuote(asset) ? marketQuoteQueryService.findCachedUsQuote(asset.getSymbol()) : null;
    }

    private boolean supportsMarketQuote(Asset asset) {
        return ("STOCK".equalsIgnoreCase(asset.getType()) || "ETF".equalsIgnoreCase(asset.getType()))
                && asset.getMarket() != null
                && MARKET.equals(asset.getMarket().trim().toUpperCase(Locale.ROOT));
    }

    private AssetResponse toResponse(Asset a, MarketQuoteSnapshotResponse marketQuote) {
        BigDecimal currentPrice = a.getCurrentPrice() != null ? a.getCurrentPrice() : BigDecimal.ZERO;
        BigDecimal marketValue = currentPrice.multiply(a.getQuantity());
        BigDecimal cost = a.getAvgCost().multiply(a.getQuantity());
        BigDecimal profitLoss = marketValue.subtract(cost);
        BigDecimal profitLossRate = cost.compareTo(BigDecimal.ZERO) > 0
                ? profitLoss.divide(cost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        return new AssetResponse(a.getId(), a.getName(), a.getSymbol(), a.getType(),
                a.getMarket(), a.getCurrency(), a.getQuantity(), a.getAvgCost(),
                currentPrice, marketValue, profitLoss, profitLossRate, a.getCreatedAt(), marketQuote);
    }
}
