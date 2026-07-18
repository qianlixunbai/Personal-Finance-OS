package com.financeos.module.asset.marketdata.fx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExchangeRateMapper extends BaseMapper<ExchangeRate> {

    @Select("""
            SELECT id, base_currency, quote_currency, rate, rate_time, fetched_at, provider, created_at, updated_at
            FROM exchange_rates
            WHERE base_currency = UPPER(TRIM(#{baseCurrency}))
              AND quote_currency = UPPER(TRIM(#{quoteCurrency}))
            """)
    ExchangeRate findByBaseCurrencyAndQuoteCurrency(@Param("baseCurrency") String baseCurrency,
                                                    @Param("quoteCurrency") String quoteCurrency);

    default List<ExchangeRate> findByBaseCurrenciesAndQuoteCurrency(List<String> baseCurrencies, String quoteCurrency) {
        if (baseCurrencies == null || baseCurrencies.isEmpty()) {
            return List.of();
        }
        return findByBaseCurrenciesAndQuoteCurrencyInternal(baseCurrencies, quoteCurrency);
    }

    @Select("""
            <script>
            SELECT id, base_currency, quote_currency, rate, rate_time, fetched_at, provider, created_at, updated_at
            FROM exchange_rates
            WHERE quote_currency = UPPER(TRIM(#{quoteCurrency}))
              AND base_currency IN
              <foreach item="baseCurrency" collection="baseCurrencies" open="(" separator="," close=")">
                UPPER(TRIM(#{baseCurrency}))
              </foreach>
            </script>
            """)
    List<ExchangeRate> findByBaseCurrenciesAndQuoteCurrencyInternal(@Param("baseCurrencies") List<String> baseCurrencies,
                                                                    @Param("quoteCurrency") String quoteCurrency);

    default int upsertLatest(ExchangeRate exchangeRate) {
        exchangeRate.prepareForPersistence();
        return upsertLatestInternal(exchangeRate);
    }

    @Insert("""
            INSERT INTO exchange_rates (
                base_currency, quote_currency, rate, rate_time, fetched_at, provider, created_at, updated_at
            ) VALUES (
                #{exchangeRate.baseCurrency}, #{exchangeRate.quoteCurrency}, #{exchangeRate.rate},
                #{exchangeRate.rateTime}, #{exchangeRate.fetchedAt}, #{exchangeRate.provider},
                #{exchangeRate.createdAt}, #{exchangeRate.updatedAt}
            )
            ON CONFLICT (base_currency, quote_currency) DO UPDATE SET
                rate = EXCLUDED.rate,
                rate_time = EXCLUDED.rate_time,
                fetched_at = EXCLUDED.fetched_at,
                provider = EXCLUDED.provider,
                updated_at = EXCLUDED.updated_at
            WHERE EXCLUDED.rate_time > exchange_rates.rate_time
               OR (EXCLUDED.rate_time = exchange_rates.rate_time
                   AND EXCLUDED.fetched_at >= exchange_rates.fetched_at)
            """)
    int upsertLatestInternal(@Param("exchangeRate") ExchangeRate exchangeRate);
}
