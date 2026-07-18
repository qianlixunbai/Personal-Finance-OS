package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.mapper.ExchangeRateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;

@Service
public class ExchangeRatePersistenceService {
    private final ExchangeRateMapper mapper;

    public ExchangeRatePersistenceService(ExchangeRateMapper mapper) { this.mapper = mapper; }

    @Transactional
    public ExchangeRate upsert(ExchangeRate rate) {
        if (rate.getRate().precision() - rate.getRate().scale() > 12) {
            throw new IllegalArgumentException("Exchange rate exceeds NUMERIC(24,12) capacity");
        }
        rate.setRate(rate.getRate().setScale(12, RoundingMode.HALF_UP));
        if (mapper.upsertLatest(rate) != 1) throw new IllegalStateException("Could not persist exchange rate");
        ExchangeRate stored = mapper.findByBaseCurrencyAndQuoteCurrency(rate.getBaseCurrency(), rate.getQuoteCurrency());
        if (stored == null) throw new IllegalStateException("Could not read persisted exchange rate");
        return stored;
    }
}
