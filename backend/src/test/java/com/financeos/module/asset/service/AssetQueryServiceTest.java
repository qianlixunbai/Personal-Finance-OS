package com.financeos.module.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
class AssetQueryServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Asset.class);
    }

    @Test
    void listByUserOnlyQueriesCnyAssets() {
        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetQueryService service = new AssetQueryService(assetMapper);
        when(assetMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.listByUser(1L);

        ArgumentCaptor<LambdaQueryWrapper<Asset>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(assetMapper).selectList(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getSqlSegment().contains("currency"));
    }
}
