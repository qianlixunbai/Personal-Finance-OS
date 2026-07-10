package com.financeos.module.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.ledger.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface TransactionMapper extends BaseMapper<Transaction> {

    @Select("SELECT COALESCE(SUM(amount), 0) FROM transactions " +
            "WHERE user_id = #{userId} AND type = #{type} AND currency = 'CNY'")
    BigDecimal sumByType(@Param("userId") Long userId, @Param("type") String type);

    @Select("SELECT COALESCE(SUM(amount), 0) FROM transactions " +
            "WHERE user_id = #{userId} AND type = #{type} AND currency = 'CNY' " +
            "AND transacted_at BETWEEN #{start} AND #{end}")
    BigDecimal sumByTypeAndDate(@Param("userId") Long userId, @Param("type") String type,
                                @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);
}
