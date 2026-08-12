package com.financeos.module.investment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.investment.entity.InvestmentTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvestmentTransactionMapper extends BaseMapper<InvestmentTransaction> {

    @Select("SELECT * FROM investment_transactions WHERE user_id = #{userId} AND id = #{id}")
    @Results(id = "investmentTransactionResult", value = {
            @Result(column = "correction_group_id", property = "correctionGroupId", typeHandler = PostgresUuidTypeHandler.class)
    })
    InvestmentTransaction findByUserIdAndId(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT * FROM investment_transactions WHERE user_id = #{userId} AND id = #{id} FOR UPDATE")
    @ResultMap("investmentTransactionResult")
    InvestmentTransaction findByUserIdAndIdForUpdate(@Param("userId") Long userId, @Param("id") Long id);

    @Select("""
            SELECT * FROM investment_transactions
            WHERE user_id = #{userId} AND asset_id = #{assetId} AND status = 'POSTED'
            ORDER BY trade_time ASC, id ASC
            """)
    @ResultMap("investmentTransactionResult")
    List<InvestmentTransaction> selectPostedByUserIdAndAssetId(@Param("userId") Long userId, @Param("assetId") Long assetId);

    @Select("""
            SELECT * FROM investment_transactions
            WHERE user_id = #{userId} AND asset_id = #{assetId}
            ORDER BY trade_time ASC, id ASC
            """)
    @ResultMap("investmentTransactionResult")
    List<InvestmentTransaction> selectAllByUserIdAndAssetId(@Param("userId") Long userId, @Param("assetId") Long assetId);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM investment_transactions
                WHERE user_id = #{userId} AND asset_id = #{assetId}
            )
            """)
    boolean existsAnyByUserIdAndAssetId(@Param("userId") Long userId, @Param("assetId") Long assetId);

    @Select("SELECT * FROM investment_transactions WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ResultMap("investmentTransactionResult")
    InvestmentTransaction findByUserIdAndIdempotencyKey(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM investment_transactions
            WHERE user_id = #{userId}
              AND original_transaction_id = #{originalTransactionId}
              AND transaction_type = 'REVERSAL'
            """)
    @ResultMap("investmentTransactionResult")
    InvestmentTransaction findReversalByUserIdAndOriginalTransactionId(
            @Param("userId") Long userId,
            @Param("originalTransactionId") Long originalTransactionId);
}
