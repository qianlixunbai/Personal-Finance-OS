package com.financeos.module.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.ledger.dto.MonthlyCashFlowAggregate;
import com.financeos.module.ledger.dto.TransactionDuplicateProbe;
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

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM transactions
                WHERE user_id = #{userId} AND account_id = #{accountId} AND category_id = #{categoryId}
                  AND type = #{type} AND amount = #{amount} AND transacted_at = #{transactedAt}
                  AND COALESCE(description, '') = COALESCE(#{description}, '')
            )
            """)
    boolean existsProbableDuplicate(@Param("userId") Long userId, @Param("accountId") Long accountId,
                                    @Param("categoryId") Long categoryId, @Param("type") String type,
                                    @Param("amount") BigDecimal amount,
                                    @Param("transactedAt") java.time.LocalDateTime transactedAt,
                                    @Param("description") String description);

    @Select("""
            SELECT id FROM transactions
            WHERE user_id = #{userId} AND account_id = #{accountId} AND category_id = #{categoryId}
              AND type = #{type} AND amount = #{amount} AND transacted_at = #{transactedAt}
              AND COALESCE(description, '') = COALESCE(#{description}, '')
            ORDER BY id
            """)
    List<Long> findProbableDuplicateIds(@Param("userId") Long userId, @Param("accountId") Long accountId,
                                        @Param("categoryId") Long categoryId, @Param("type") String type,
                                        @Param("amount") BigDecimal amount,
                                        @Param("transactedAt") java.time.LocalDateTime transactedAt,
                                        @Param("description") String description);

    @Select("""
            <script>
            SELECT t.id, t.user_id, t.account_id, t.category_id, t.type, t.amount, t.currency, t.description,
                   t.transacted_at, t.created_at, t.updated_at
            FROM transactions t
            JOIN (VALUES
              <foreach collection="probes" item="probe" separator=",">
                (#{probe.accountId}, #{probe.categoryId}, #{probe.type}, #{probe.amount}, #{probe.transactedAt}, #{probe.description})
              </foreach>
            ) AS probe(account_id, category_id, type, amount, transacted_at, description)
              ON t.account_id = probe.account_id AND t.category_id = probe.category_id AND t.type = probe.type
             AND t.amount = probe.amount AND t.transacted_at = probe.transacted_at
             AND COALESCE(t.description, '') = COALESCE(probe.description, '')
            WHERE t.user_id = #{userId}
            ORDER BY t.id
            </script>
            """)
    List<Transaction> findProbableDuplicateCandidates(@Param("userId") Long userId,
                                                       @Param("probes") List<TransactionDuplicateProbe> probes);

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
