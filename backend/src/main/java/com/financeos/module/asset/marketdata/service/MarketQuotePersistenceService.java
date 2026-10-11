package com.financeos.module.asset.marketdata.service;

import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.temporal.ChronoUnit;

@Service
public class MarketQuotePersistenceService {
    private final MarketQuoteMapper marketQuoteMapper;

    public MarketQuotePersistenceService(MarketQuoteMapper marketQuoteMapper) {
        this.marketQuoteMapper = marketQuoteMapper;
    }

    @Transactional
    public UpsertResult upsert(MarketQuote quote) {
        int affectedRows = marketQuoteMapper.upsertLatest(quote);
        if (affectedRows != 0 && affectedRows != 1) {
            throw new IllegalStateException("Could not persist market quote");
        }
        // The UPSERT holds the conflicting row lock until this transaction commits,
        // including when its quote_time predicate rejects the candidate.
        MarketQuote stored = marketQuoteMapper.findByMarketAndSymbol(quote.getMarket(), quote.getSymbol());
        if (stored == null || !quote.getMarket().equals(stored.getMarket())
                || !quote.getSymbol().equals(stored.getSymbol()) || stored.getQuoteTime() == null
                || stored.getFetchedAt() == null || stored.getPrice() == null || stored.getPrice().signum() <= 0
                || !StringUtils.hasText(stored.getCurrency()) || !StringUtils.hasText(stored.getProvider())) {
            throw new IllegalStateException("Could not verify persisted market quote");
        }
        if (affectedRows == 0 && !stored.getQuoteTime().isAfter(quote.getQuoteTime())) {
            throw new IllegalStateException("Skipped market quote without a newer persisted quote");
        }
        // PostgreSQL timestamps have microsecond precision; return the stored precision.
        if (affectedRows == 1 && stored.getQuoteTime().isBefore(quote.getQuoteTime().truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalStateException("Persisted market quote is older than the written quote");
        }
        return new UpsertResult(stored, affectedRows == 1);
    }

    public record UpsertResult(MarketQuote quote, boolean written) {
    }
}
