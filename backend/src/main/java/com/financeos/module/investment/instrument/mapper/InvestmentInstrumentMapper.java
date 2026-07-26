package com.financeos.module.investment.instrument.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvestmentInstrumentMapper extends BaseMapper<InvestmentInstrument> {

    @Select("""
            SELECT * FROM investment_instruments
            WHERE user_id = #{userId} AND id = #{instrumentId}
            """)
    InvestmentInstrument findByUserIdAndId(@Param("userId") Long userId, @Param("instrumentId") Long instrumentId);

    @Select("""
            SELECT * FROM investment_instruments
            WHERE user_id = #{userId} AND market = #{market} AND symbol = #{symbol}
            """)
    InvestmentInstrument findByUserIdAndMarketAndSymbol(@Param("userId") Long userId,
                                                          @Param("market") String market,
                                                          @Param("symbol") String symbol);

    @Select("""
            SELECT * FROM investment_instruments
            WHERE user_id = #{userId}
            ORDER BY id
            """)
    List<InvestmentInstrument> listByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM investment_instruments WHERE user_id = #{userId} AND id = #{instrumentId}
            )
            """)
    boolean existsByUserIdAndId(@Param("userId") Long userId, @Param("instrumentId") Long instrumentId);
}
