package com.financeos.module.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.asset.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface AssetMapper extends BaseMapper<Asset> {

    @Select("""
            SELECT * FROM assets
            WHERE user_id = #{userId} AND id = #{assetId}
            FOR UPDATE
            """)
    Asset selectOwnedForUpdate(@Param("userId") Long userId, @Param("assetId") Long assetId);

    @Select("SELECT * FROM assets WHERE user_id = #{userId} AND id = #{assetId}")
    Asset findByUserIdAndId(@Param("userId") Long userId, @Param("assetId") Long assetId);

    @Select("""
            SELECT * FROM assets
            WHERE user_id = #{userId}
              AND account_id = #{accountId}
              AND instrument_id = #{instrumentId}
              AND position_mode = 'TRANSACTION_DRIVEN'
            FOR UPDATE
            """)
    Asset selectTransactionDrivenByBindingForUpdate(@Param("userId") Long userId,
                                                     @Param("accountId") Long accountId,
                                                     @Param("instrumentId") Long instrumentId);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM assets
                WHERE user_id = #{userId}
                  AND account_id = #{accountId}
                  AND instrument_id = #{instrumentId}
                  AND position_mode = 'TRANSACTION_DRIVEN'
            )
            """)
    boolean existsTransactionDrivenPosition(@Param("userId") Long userId,
                                             @Param("accountId") Long accountId,
                                             @Param("instrumentId") Long instrumentId);

    @Update("""
            UPDATE assets
            SET current_price = #{currentPrice}, market_value = #{marketValue}, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND id = #{assetId}
            """)
    int updateReferencePrice(@Param("userId") Long userId, @Param("assetId") Long assetId,
                             @Param("currentPrice") BigDecimal currentPrice,
                             @Param("marketValue") BigDecimal marketValue);

    @Update("""
            UPDATE assets
            SET account_id = #{accountId}, instrument_id = #{instrumentId}
            WHERE user_id = #{userId} AND id = #{assetId} AND position_mode = 'LEGACY'
            """)
    int bindLegacyAsset(@Param("userId") Long userId, @Param("assetId") Long assetId,
                        @Param("accountId") Long accountId, @Param("instrumentId") Long instrumentId);

    @Update("""
            UPDATE assets
            SET quantity = #{quantity},
                avg_cost = #{avgCost},
                total_cost = #{totalCost},
                realized_profit_loss = #{realizedProfitLoss},
                position_status = #{positionStatus},
                last_transaction_id = #{lastTransactionId},
                projection_version = projection_version + 1,
                position_mode = 'TRANSACTION_DRIVEN',
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND id = #{assetId}
              AND position_mode = 'LEGACY'
              AND account_id = #{accountId}
              AND instrument_id = #{instrumentId}
            """)
    int finalizeOpeningMigrationProjection(@Param("userId") Long userId,
                                           @Param("assetId") Long assetId,
                                           @Param("accountId") Long accountId,
                                           @Param("instrumentId") Long instrumentId,
                                           @Param("quantity") BigDecimal quantity,
                                           @Param("avgCost") BigDecimal avgCost,
                                           @Param("totalCost") BigDecimal totalCost,
                                           @Param("realizedProfitLoss") BigDecimal realizedProfitLoss,
                                           @Param("positionStatus") String positionStatus,
                                           @Param("lastTransactionId") Long lastTransactionId);

    @Update("""
            UPDATE assets
            SET quantity = #{quantity},
                avg_cost = #{avgCost},
                total_cost = #{totalCost},
                realized_profit_loss = #{realizedProfitLoss},
                position_status = #{positionStatus},
                last_transaction_id = #{lastTransactionId},
                projection_version = projection_version + 1,
                position_mode = 'TRANSACTION_DRIVEN',
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND id = #{assetId}
              AND projection_version = #{expectedProjectionVersion}
              AND position_mode = 'TRANSACTION_DRIVEN'
            """)
    int updateTransactionDrivenProjection(@Param("userId") Long userId,
                                           @Param("assetId") Long assetId,
                                           @Param("expectedProjectionVersion") Integer expectedProjectionVersion,
                                           @Param("quantity") BigDecimal quantity,
                                           @Param("avgCost") BigDecimal avgCost,
                                           @Param("totalCost") BigDecimal totalCost,
                                           @Param("realizedProfitLoss") BigDecimal realizedProfitLoss,
                                           @Param("positionStatus") String positionStatus,
                                           @Param("lastTransactionId") Long lastTransactionId);
}
