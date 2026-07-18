package com.financeos.module.asset.marketdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MarketQuoteMapper extends BaseMapper<MarketQuote> {

    @Select("""
            SELECT id, market, symbol, currency, price, quote_time, fetched_at, provider, created_at, updated_at
            FROM market_quotes
            WHERE market = #{market} AND symbol = UPPER(TRIM(#{symbol}))
            """)
    MarketQuote findByMarketAndSymbol(@Param("market") String market, @Param("symbol") String symbol);

    default int upsertLatest(MarketQuote quote) {
        quote.prepareForPersistence();
        return upsertLatestInternal(quote);
    }

    @Insert("""
            INSERT INTO market_quotes (
                market, symbol, currency, price, quote_time, fetched_at, provider, created_at, updated_at
            ) VALUES (
                #{quote.market}, #{quote.symbol}, #{quote.currency}, #{quote.price}, #{quote.quoteTime},
                #{quote.fetchedAt}, #{quote.provider}, #{quote.createdAt}, #{quote.updatedAt}
            )
            ON CONFLICT (market, symbol) DO UPDATE SET
                currency = EXCLUDED.currency,
                price = EXCLUDED.price,
                quote_time = EXCLUDED.quote_time,
                fetched_at = EXCLUDED.fetched_at,
                provider = EXCLUDED.provider,
                updated_at = EXCLUDED.updated_at
            WHERE EXCLUDED.quote_time >= market_quotes.quote_time
            """)
    int upsertLatestInternal(@Param("quote") MarketQuote quote);
}
