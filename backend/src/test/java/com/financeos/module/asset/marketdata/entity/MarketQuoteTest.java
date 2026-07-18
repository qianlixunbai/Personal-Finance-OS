package com.financeos.module.asset.marketdata.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MarketQuoteTest {

    @Test
    void normalizesSymbolBeforePersistence() {
        MarketQuote quote = validQuote();
        quote.setSymbol("  aapl ");

        quote.prepareForPersistence();

        assertThat(quote.getSymbol()).isEqualTo("AAPL");
        assertThat(quote.getMarket()).isEqualTo("US");
        assertThat(quote.getProvider()).isEqualTo("TWELVE_DATA");
    }

    @Test
    void rejectsNonPositivePriceBeforePersistence() {
        MarketQuote quote = validQuote();
        quote.setPrice(BigDecimal.ZERO);

        assertThatIllegalArgumentException().isThrownBy(quote::prepareForPersistence)
                .withMessage("Market quote price must be positive");
    }

    private MarketQuote validQuote() {
        MarketQuote quote = new MarketQuote();
        quote.setSymbol("AAPL");
        quote.setCurrency("USD");
        quote.setPrice(new BigDecimal("123.45678901"));
        quote.setQuoteTime(Instant.parse("2026-01-02T03:04:05Z"));
        quote.setFetchedAt(Instant.parse("2026-01-02T03:04:06Z"));
        return quote;
    }
}
