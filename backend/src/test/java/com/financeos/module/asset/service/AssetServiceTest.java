package com.financeos.module.asset.service;

import com.financeos.common.BusinessException;
import com.financeos.module.asset.mapper.AssetMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AssetServiceTest {

    @Test
    void updatePriceRejectsZeroPrice() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetService service = new AssetService(assetMapper);

        assertThrows(BusinessException.class, () -> service.updatePrice(1L, 10L, BigDecimal.ZERO));

        verify(assetMapper, never()).selectById(10L);
    }
}
