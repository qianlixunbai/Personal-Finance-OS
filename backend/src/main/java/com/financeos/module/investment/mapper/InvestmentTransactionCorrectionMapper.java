package com.financeos.module.investment.mapper;

import com.financeos.module.investment.entity.InvestmentTransactionCorrection;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InvestmentTransactionCorrectionMapper {

    @Insert("""
            INSERT INTO investment_transaction_corrections (
                correction_group_id, user_id, account_id, asset_id, instrument_id, original_transaction_id,
                transaction_type, correction_kind, idempotency_key, request_hash, correction_reason,
                reversal_transaction_id, replacement_transaction_id, reversal_cash_delta, replacement_cash_delta,
                command_cash_delta, balance_after, position_quantity_after, position_avg_cost_after,
                position_total_cost_after, position_realized_profit_loss_after, position_status_after,
                projection_version, last_transaction_id, created_at)
            VALUES (
                #{correctionGroupId,typeHandler=com.financeos.module.investment.mapper.PostgresUuidTypeHandler,jdbcType=OTHER}, #{userId}, #{accountId}, #{assetId}, #{instrumentId}, #{originalTransactionId},
                #{transactionType}, #{correctionKind}, #{idempotencyKey}, #{requestHash}, #{correctionReason},
                #{reversalTransactionId}, #{replacementTransactionId}, #{reversalCashDelta}, #{replacementCashDelta},
                #{commandCashDelta}, #{balanceAfter}, #{positionQuantityAfter}, #{positionAvgCostAfter},
                #{positionTotalCostAfter}, #{positionRealizedProfitLossAfter}, #{positionStatusAfter},
                #{projectionVersion}, #{lastTransactionId}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InvestmentTransactionCorrection correction);

    @Select("SELECT * FROM investment_transaction_corrections WHERE user_id = #{userId} AND id = #{id}")
    @Results({
            @Result(column = "correction_group_id", property = "correctionGroupId", typeHandler = PostgresUuidTypeHandler.class)
    })
    InvestmentTransactionCorrection findByUserIdAndId(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT * FROM investment_transaction_corrections WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}")
    @Results({@Result(column = "correction_group_id", property = "correctionGroupId", typeHandler = PostgresUuidTypeHandler.class)})
    InvestmentTransactionCorrection findByUserIdAndIdempotencyKey(@Param("userId") Long userId, @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM investment_transaction_corrections WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} FOR UPDATE")
    @Results({@Result(column = "correction_group_id", property = "correctionGroupId", typeHandler = PostgresUuidTypeHandler.class)})
    InvestmentTransactionCorrection findByUserIdAndIdempotencyKeyForUpdate(@Param("userId") Long userId, @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM investment_transaction_corrections WHERE user_id = #{userId} AND original_transaction_id = #{originalTransactionId}")
    @Results({@Result(column = "correction_group_id", property = "correctionGroupId", typeHandler = PostgresUuidTypeHandler.class)})
    InvestmentTransactionCorrection findByUserIdAndOriginalTransactionId(@Param("userId") Long userId, @Param("originalTransactionId") Long originalTransactionId);
}
