package com.financeos.module.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.common.BusinessException;
import com.financeos.module.asset.dto.AssetRequest;
import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class AssetService {

    private final AssetMapper assetMapper;

    public AssetService(AssetMapper assetMapper) {
        this.assetMapper = assetMapper;
    }

    public List<AssetResponse> listByUser(Long userId) {
        return assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getUserId, userId)
        ).stream().map(this::toResponse).toList();
    }

    public AssetResponse getById(Long userId, Long id) {
        Asset asset = assetMapper.selectById(id);
        if (asset == null || !asset.getUserId().equals(userId)) {
            throw new BusinessException(404, "资产不存在");
        }
        return toResponse(asset);
    }

    @Transactional
    public AssetResponse create(Long userId, AssetRequest req) {
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setName(req.name());
        asset.setSymbol(req.symbol());
        asset.setType(req.type());
        asset.setMarket(req.market());
        asset.setCurrency(req.currency() != null ? req.currency() : "CNY");
        asset.setQuantity(req.quantity());
        asset.setAvgCost(req.avgCost());
        assetMapper.insert(asset);
        return toResponse(asset);
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
        return toResponse(asset);
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

    private AssetResponse toResponse(Asset a) {
        BigDecimal currentPrice = a.getCurrentPrice() != null ? a.getCurrentPrice() : BigDecimal.ZERO;
        BigDecimal marketValue = currentPrice.multiply(a.getQuantity());
        BigDecimal cost = a.getAvgCost().multiply(a.getQuantity());
        BigDecimal profitLoss = marketValue.subtract(cost);
        BigDecimal profitLossRate = cost.compareTo(BigDecimal.ZERO) > 0
                ? profitLoss.divide(cost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        return new AssetResponse(a.getId(), a.getName(), a.getSymbol(), a.getType(),
                a.getMarket(), a.getCurrency(), a.getQuantity(), a.getAvgCost(),
                currentPrice, marketValue, profitLoss, profitLossRate, a.getCreatedAt());
    }
}
