package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.config.FxDataProperties;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
public class ExchangeRateQueryService {
    private final ExchangeRateMapper mapper;
    private final FxDataProperties properties;
    private final Clock clock;

    public ExchangeRateQueryService(ExchangeRateMapper mapper, FxDataProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    public ExchangeRateFreshness freshnessOf(ExchangeRate rate) {
        if (rate == null || rate.getFetchedAt() == null) {
            return ExchangeRateFreshness.NEVER_FETCHED;
        }
        return clock.instant().isBefore(rate.getFetchedAt().plus(properties.getCacheTtl()))
                ? ExchangeRateFreshness.FRESH : ExchangeRateFreshness.STALE;
    }

    public ExchangeRate find(String baseCurrency, String quoteCurrency) {
        return mapper.findByBaseCurrencyAndQuoteCurrency(normalize(baseCurrency), normalize(quoteCurrency));
    }

    public List<ExchangeRate> findAll(List<String> baseCurrencies, String quoteCurrency) {
        return mapper.findByBaseCurrenciesAndQuoteCurrency(baseCurrencies, normalize(quoteCurrency));
    }

    public ExchangeRate syntheticCnyRate() {
        ExchangeRate rate = new ExchangeRate();
        rate.setBaseCurrency("CNY");
        rate.setQuoteCurrency("CNY");
        rate.setRate(BigDecimal.ONE);
        rate.setRateTime(clock.instant());
        rate.setFetchedAt(clock.instant());
        rate.setProvider("SYSTEM");
        return rate;
    }

    public String normalize(String currency) {
        if (currency == null || currency.trim().isEmpty()) throw new IllegalArgumentException("Currency is required");
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency must be a three-letter code");
        return normalized;
    }
}
