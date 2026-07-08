package com.financeos.module.asset.service;

import com.financeos.common.BusinessException;
import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTest {

    @Test
    void updatePriceRejectsZeroPrice() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = new AssetService(assetMapper);

        assertThrows(BusinessException.class, () -> service.updatePrice(1L, 10L, BigDecimal.ZERO));

        verify(assetMapper, never()).selectById(10L);
    }

    @Test
    void closeSetsPositiveQuantityToZero() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = new AssetService(assetMapper);
        Asset asset = asset(10L, 1L, "招商银行", "600036", BigDecimal.TEN);
        when(assetMapper.selectById(10L)).thenReturn(asset);

        AssetResponse response = service.close(1L, 10L);

        assertEquals(BigDecimal.ZERO, asset.getQuantity());
        assertEquals(BigDecimal.ZERO, asset.getMarketValue());
        assertEquals(BigDecimal.ZERO, response.quantity());
        assertEquals(BigDecimal.ZERO, response.marketValue());
        verify(assetMapper).updateById(asset);
    }

    @Test
    void closeIsIdempotentWhenQuantityIsAlreadyZero() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = new AssetService(assetMapper);
        Asset asset = asset(10L, 1L, "招商银行", "600036", BigDecimal.ZERO);
        when(assetMapper.selectById(10L)).thenReturn(asset);

        AssetResponse response = service.close(1L, 10L);

        assertEquals(BigDecimal.ZERO, response.quantity());
        verify(assetMapper, never()).updateById(asset);
    }

    @Test
    void deleteStillRejectsAssetWithPositiveQuantity() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = new AssetService(assetMapper);
        Asset asset = asset(10L, 1L, "招商银行", "600036", BigDecimal.ONE);
        when(assetMapper.selectById(10L)).thenReturn(asset);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(1L, 10L));

        assertEquals("该资产仍有持仓，无法删除", ex.getMessage());
        verify(assetMapper, never()).deleteById(10L);
    }

    private Asset asset(Long id, Long userId, String name, String symbol, BigDecimal quantity) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setUserId(userId);
        asset.setName(name);
        asset.setSymbol(symbol);
        asset.setType("STOCK");
        asset.setMarket("CN");
        asset.setCurrency("CNY");
        asset.setQuantity(quantity);
        asset.setAvgCost(BigDecimal.valueOf(10));
        asset.setCurrentPrice(BigDecimal.valueOf(12));
        asset.setMarketValue(asset.getCurrentPrice().multiply(quantity));
        return asset;
    }
}
