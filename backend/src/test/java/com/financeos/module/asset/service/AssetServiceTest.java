package com.financeos.module.asset.service;

import com.financeos.common.BusinessException;
import com.financeos.module.asset.dto.AssetRequest;
import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
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
    void createRejectsNonCnyCurrency() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = service(assetMapper);
        AssetRequest request = new AssetRequest(
                "Apple", "AAPL", "STOCK", "NASDAQ", "USD", BigDecimal.ONE, BigDecimal.TEN);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, request));

        assertEquals(400, ex.getCode());
        assertEquals("当前版本仅支持 CNY 币种", ex.getMessage());
        verify(assetMapper, never()).insert(org.mockito.ArgumentMatchers.any(Asset.class));
    }

    @Test
    void updatePriceRejectsZeroPrice() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = service(assetMapper);

        assertThrows(BusinessException.class, () -> service.updatePrice(1L, 10L, BigDecimal.ZERO));

        verify(assetMapper, never()).selectById(10L);
    }

    @Test
    void closeSetsPositiveQuantityToZero() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = service(assetMapper);
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
        AssetService service = service(assetMapper);
        Asset asset = asset(10L, 1L, "招商银行", "600036", BigDecimal.ZERO);
        when(assetMapper.selectById(10L)).thenReturn(asset);

        AssetResponse response = service.close(1L, 10L);

        assertEquals(BigDecimal.ZERO, response.quantity());
        verify(assetMapper, never()).updateById(asset);
    }

    @Test
    void closeRejectsTransactionDrivenPositionWithoutMutatingItsProjection() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = service(assetMapper);
        Asset asset = asset(10L, 1L, "基金", "FUND", new BigDecimal("12.12345678"));
        asset.setPositionMode("TRANSACTION_DRIVEN");
        asset.setAvgCost(new BigDecimal("10.12345678"));
        asset.setTotalCost(new BigDecimal("122.34"));
        asset.setRealizedProfitLoss(new BigDecimal("5.67"));
        asset.setPositionStatus("OPEN");
        asset.setLastTransactionId(99L);
        asset.setProjectionVersion(4);
        when(assetMapper.selectById(10L)).thenReturn(asset);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.close(1L, 10L));

        assertEquals(409, ex.getCode());
        assertEquals(new BigDecimal("12.12345678"), asset.getQuantity());
        assertEquals(new BigDecimal("10.12345678"), asset.getAvgCost());
        assertEquals(new BigDecimal("122.34"), asset.getTotalCost());
        assertEquals(new BigDecimal("5.67"), asset.getRealizedProfitLoss());
        assertEquals("OPEN", asset.getPositionStatus());
        assertEquals(99L, asset.getLastTransactionId());
        assertEquals(4, asset.getProjectionVersion());
        verify(assetMapper, never()).updateById(asset);
    }

    @Test
    void deleteRejectsTransactionDrivenPositionWithoutDeletingIt() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = service(assetMapper);
        Asset asset = asset(10L, 1L, "基金", "FUND", BigDecimal.ZERO);
        asset.setPositionMode("TRANSACTION_DRIVEN");
        asset.setTotalCost(BigDecimal.ZERO);
        asset.setPositionStatus("CLOSED");
        asset.setProjectionVersion(4);
        when(assetMapper.selectById(10L)).thenReturn(asset);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(1L, 10L));

        assertEquals(409, ex.getCode());
        assertEquals(BigDecimal.ZERO, asset.getQuantity());
        assertEquals(BigDecimal.ZERO, asset.getTotalCost());
        assertEquals("CLOSED", asset.getPositionStatus());
        assertEquals(4, asset.getProjectionVersion());
        verify(assetMapper, never()).deleteById(10L);
    }

    @Test
    void legacyZeroQuantityAssetCanStillBeDeleted() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = service(assetMapper);
        Asset asset = asset(10L, 1L, "基金", "FUND", BigDecimal.ZERO);
        asset.setPositionMode("LEGACY");
        when(assetMapper.selectById(10L)).thenReturn(asset);

        service.delete(1L, 10L);

        verify(assetMapper).deleteById(10L);
    }

    @Test
    void transactionDrivenAssetStillAllowsReferencePriceUpdatesWithoutChangingProjectionFields() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = service(assetMapper);
        Asset asset = asset(10L, 1L, "基金", "FUND", new BigDecimal("12.12345678"));
        asset.setPositionMode("TRANSACTION_DRIVEN");
        asset.setAvgCost(new BigDecimal("10.12345678"));
        asset.setTotalCost(new BigDecimal("122.34"));
        asset.setRealizedProfitLoss(new BigDecimal("5.67"));
        asset.setPositionStatus("OPEN");
        asset.setLastTransactionId(99L);
        asset.setProjectionVersion(4);
        when(assetMapper.selectById(10L)).thenReturn(asset);

        service.updatePrice(1L, 10L, new BigDecimal("12.34"));

        assertEquals(new BigDecimal("12.12345678"), asset.getQuantity());
        assertEquals(new BigDecimal("10.12345678"), asset.getAvgCost());
        assertEquals(new BigDecimal("122.34"), asset.getTotalCost());
        assertEquals(new BigDecimal("5.67"), asset.getRealizedProfitLoss());
        assertEquals("OPEN", asset.getPositionStatus());
        assertEquals(99L, asset.getLastTransactionId());
        assertEquals(4, asset.getProjectionVersion());
        verify(assetMapper).updateById(asset);
    }

    @Test
    void closeDoesNotModifyAnAssetOwnedByAnotherUser() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = service(assetMapper);
        Asset asset = asset(10L, 2L, "其他用户基金", "FUND", BigDecimal.ONE);
        when(assetMapper.selectById(10L)).thenReturn(asset);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.close(1L, 10L));

        assertEquals(404, ex.getCode());
        assertEquals(BigDecimal.ONE, asset.getQuantity());
        verify(assetMapper, never()).updateById(asset);
    }

    @Test
    void deleteStillRejectsAssetWithPositiveQuantity() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = service(assetMapper);
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

    private AssetService service(AssetMapper assetMapper) {
        return new AssetService(assetMapper, mock(MarketQuoteQueryService.class));
    }
}
