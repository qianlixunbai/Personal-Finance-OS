package com.financeos.module.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.ledger.dto.MonthlyCashFlowAggregate;
import com.financeos.module.ledger.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface TransactionMapper extends BaseMapper<Transaction> {

    @Select("""
            SELECT id, user_id, account_id, category_id, type, amount, currency, description,
                   transacted_at, created_at, updated_at
            FROM transactions
            WHERE id = #{transactionId} AND user_id = #{userId}
            FOR UPDATE
            """)
    Transaction selectOwnedForUpdate(@Param("userId") Long userId, @Param("transactionId") Long transactionId);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM transactions " +
            "WHERE user_id = #{userId} AND type = #{type} AND currency = 'CNY'")
    BigDecimal sumByType(@Param("userId") Long userId, @Param("type") String type);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM transactions " +
            "WHERE user_id = #{userId} AND type = #{type} AND currency = 'CNY' " +
            "AND transacted_at >= #{startInclusive} AND transacted_at < #{endExclusive}")
    BigDecimal sumByTypeAndDate(@Param("userId") Long userId, @Param("type") String type,
                                @Param("startInclusive") java.time.LocalDateTime startInclusive,
                                @Param("endExclusive") java.time.LocalDateTime endExclusive);

    @Select("""
            SELECT date_trunc('month', transacted_at)::date AS month_start,
                   COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount ELSE 0 END), 0) AS income,
                   COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END), 0) AS expense
            FROM transactions
            WHERE user_id = #{userId}
              AND currency = 'CNY'
              AND type IN ('INCOME', 'EXPENSE')
              AND transacted_at >= #{startInclusive}
              AND transacted_at < #{endExclusive}
            GROUP BY date_trunc('month', transacted_at)
            ORDER BY month_start
            """)
    List<MonthlyCashFlowAggregate> monthlyCashFlowByMonth(
            @Param("userId") Long userId,
            @Param("startInclusive") java.time.LocalDateTime startInclusive,
            @Param("endExclusive") java.time.LocalDateTime endExclusive);
}
