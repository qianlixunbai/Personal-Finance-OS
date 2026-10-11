package com.financeos.module.asset.marketdata.service;

import com.financeos.module.asset.marketdata.entity.MarketQuote;
import com.financeos.module.asset.marketdata.mapper.MarketQuoteMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketQuotePersistenceServiceTest {
    private final MarketQuoteMapper mapper = mock(MarketQuoteMapper.class);
    private final MarketQuotePersistenceService persistence = new MarketQuotePersistenceService(mapper);

    @Test
    void zeroRowsOnlyAcceptsVerifiedStrictlyNewerAuthority() {
        MarketQuote input = quote();
        MarketQuote stored = quote();
        stored.setQuoteTime(input.getQuoteTime().plusSeconds(1));
        stored.setPrice(new BigDecimal("200"));
        when(mapper.upsertLatest(input)).thenReturn(0);
        when(mapper.findByMarketAndSymbol("US", "C8")).thenReturn(stored);
        var result = persistence.upsert(input);
        assertThat(result.written()).isFalse();
        assertThat(result.quote()).isSameAs(stored);
    }

    @Test
    void zeroRowsFailsClosedForMissingMismatchedEqualOlderOrUnusableRows() {
        MarketQuote input = quote();
        when(mapper.upsertLatest(input)).thenReturn(0);
        when(mapper.findByMarketAndSymbol("US", "C8")).thenReturn(null);
        assertFails(input);
        MarketQuote stored = quote();
        when(mapper.findByMarketAndSymbol("US", "C8")).thenReturn(stored);
        assertFails(input);
        stored.setQuoteTime(input.getQuoteTime().minusSeconds(1));
        assertFails(input);
        stored.setQuoteTime(input.getQuoteTime().plusSeconds(1));
        stored.setMarket("CN");
        assertFails(input);
        stored.setMarket("US");
        stored.setSymbol("OTHER");
        assertFails(input);
        stored.setSymbol("C8");
        stored.setQuoteTime(null);
        assertFails(input);
        stored.setQuoteTime(input.getQuoteTime().plusSeconds(1));
        stored.setFetchedAt(null);
        assertFails(input);
        stored.setFetchedAt(input.getFetchedAt());
        stored.setPrice(BigDecimal.ZERO);
        assertFails(input);
        stored.setPrice(BigDecimal.TEN);
        stored.setProvider(" ");
        assertFails(input);
    }

    @Test
    void successfulWriteReturnsReadBackDatabaseValuesAndRejectsMissingAuthority() {
        MarketQuote input = quote();
        MarketQuote stored = quote();
        stored.setPrice(new BigDecimal("10.12345679"));
        when(mapper.upsertLatest(input)).thenReturn(1);
        when(mapper.findByMarketAndSymbol("US", "C8")).thenReturn(stored);
        var result = persistence.upsert(input);
        assertThat(result.written()).isTrue();
        assertThat(result.quote()).isSameAs(stored);
        when(mapper.findByMarketAndSymbol("US", "C8")).thenReturn(null);
        assertFails(input);
    }

    @Test
    void unexpectedAffectedCountAndDatabaseExceptionsRemainFailures() {
        MarketQuote input = quote();
        when(mapper.upsertLatest(input)).thenReturn(2);
        assertFails(input);
        verify(mapper, never()).findByMarketAndSymbol(anyString(), anyString());
        RuntimeException databaseFailure = new RuntimeException("database failure");
        when(mapper.upsertLatest(input)).thenThrow(databaseFailure);
        assertThatThrownBy(() -> persistence.upsert(input)).isSameAs(databaseFailure);
    }

    private void assertFails(MarketQuote input) {
        assertThatThrownBy(() -> persistence.upsert(input)).isInstanceOf(IllegalStateException.class);
    }

    private static MarketQuote quote() {
        MarketQuote quote = new MarketQuote();
        quote.setMarket("US");
        quote.setSymbol("C8");
        quote.setCurrency("USD");
        quote.setPrice(BigDecimal.TEN);
        quote.setQuoteTime(Instant.parse("2026-10-11T00:00:00Z"));
        quote.setFetchedAt(Instant.parse("2026-10-11T02:00:00Z"));
        quote.setProvider("TEST");
        return quote;
    }
}
