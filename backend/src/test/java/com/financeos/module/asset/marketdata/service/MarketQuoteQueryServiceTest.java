package com.financeos.module.asset.marketdata.service;

import com.financeos.module.asset.marketdata.config.MarketDataProperties;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketQuoteQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Test
    void batchesNormalizedDistinctSymbolsAndKeepsFreshnessDynamic() {
        MarketQuoteMapper mapper = mock(MarketQuoteMapper.class);
        MarketQuoteQueryService service = service(mapper);
        when(mapper.findByMarketAndSymbols(eq("US"), eq(List.of("AAPL", "MSFT"))))
                .thenReturn(List.of(quote("AAPL", NOW.minusSeconds(900)), quote("MSFT", NOW.minusSeconds(901))));

        Map<String, MarketQuoteSnapshotResponse> quotes = service.findCachedUsQuotes(List.of(" aapl ", "MSFT", "AAPL", "bad!"));

        assertThat(quotes).hasSize(2);
        assertThat(quotes.get("AAPL").freshness()).isEqualTo(MarketQuoteFreshness.FRESH);
        assertThat(quotes.get("MSFT").freshness()).isEqualTo(MarketQuoteFreshness.STALE);
        verify(mapper).findByMarketAndSymbols("US", List.of("AAPL", "MSFT"));
    }

    @Test
    void skipsDatabaseAccessWhenNoSupportedSymbolRemains() {
        MarketQuoteMapper mapper = mock(MarketQuoteMapper.class);

        assertThat(service(mapper).findCachedUsQuotes(List.of("", "bad!", "  "))).isEmpty();

        verifyNoInteractions(mapper);
    }

    @Test
    void returnsNullForAnInvalidDetailSymbolWithoutQuerying() {
        MarketQuoteMapper mapper = mock(MarketQuoteMapper.class);

        assertThat(service(mapper).findCachedUsQuote("!bad")).isNull();

        verify(mapper, never()).findByMarketAndSymbol(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private MarketQuoteQueryService service(MarketQuoteMapper mapper) {
        MarketDataProperties properties = new MarketDataProperties();
        properties.setCacheTtl(java.time.Duration.ofMinutes(15));
        return new MarketQuoteQueryService(mapper, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MarketQuote quote(String symbol, Instant fetchedAt) {
        MarketQuote quote = new MarketQuote();
        quote.setMarket("US");
        quote.setSymbol(symbol);
        quote.setCurrency("USD");
        quote.setPrice(new BigDecimal("212.34"));
        quote.setQuoteTime(NOW.minusSeconds(30));
        quote.setFetchedAt(fetchedAt);
        quote.setProvider("TEST");
        return quote;
    }
}
