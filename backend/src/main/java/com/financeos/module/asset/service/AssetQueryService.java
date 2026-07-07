package com.financeos.module.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AssetQueryService {

    private final AssetMapper assetMapper;

    public AssetQueryService(AssetMapper assetMapper) {
        this.assetMapper = assetMapper;
    }

    public List<Asset> listByUser(Long userId) {
        return assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getUserId, userId)
        );
    }
}
