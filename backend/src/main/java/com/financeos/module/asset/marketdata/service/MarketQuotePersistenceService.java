package com.financeos.module.asset.marketdata.service;

import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketQuotePersistenceService {
    private final MarketQuoteMapper marketQuoteMapper;

    public MarketQuotePersistenceService(MarketQuoteMapper marketQuoteMapper) {
        this.marketQuoteMapper = marketQuoteMapper;
    }

    @Transactional
    public void upsert(MarketQuote quote) {
        if (marketQuoteMapper.upsertLatest(quote) != 1) {
            throw new IllegalStateException("Could not persist market quote");
        }
    }
}
