package com.financeos.module.asset.marketdata.mapper;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.asset.marketdata.entity.MarketQuote;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketQuoteMapperPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MarketQuoteMapper marketQuoteMapper;

    @Test
    void upsertsOnePublicQuoteForMultipleAssetsAndMapsTimestamptzToInstant() {
        MarketQuote original = quote("  aapl ", "123.45678901", "2026-01-02T03:04:05Z");
        MarketQuote latest = quote("AAPL", "234.56789012", "2026-01-02T04:05:06Z");
        MarketQuote stale = quote("AAPL", "111.11111111", "2026-01-02T02:03:04Z");

        marketQuoteMapper.upsertLatest(original);
        marketQuoteMapper.upsertLatest(latest);
        marketQuoteMapper.upsertLatest(stale);
        Long userId = insertUser("quote-owner");
        Long otherUserId = insertUser("other-quote-owner");
        insertAsset(userId, "First holding");
        insertAsset(otherUserId, "Second holding");

        MarketQuote stored = marketQuoteMapper.findByMarketAndSymbol("US", " aapl ");

        assertThat(stored.getSymbol()).isEqualTo("AAPL");
        assertThat(stored.getPrice()).isEqualByComparingTo("234.56789012");
        assertThat(stored.getQuoteTime()).isEqualTo(Instant.parse("2026-01-02T04:05:06Z"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM market_quotes WHERE market = 'US' AND symbol = 'AAPL'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void preservesNumericPrecisionAndDatabaseRejectsNonPositivePrice() {
        marketQuoteMapper.upsertLatest(quote("MSFT", "123.45678901", "2026-01-02T03:04:05Z"));
        MarketQuote invalidReplacement = quote("MSFT", "0", "2026-01-02T04:05:06Z");

        BigDecimal storedPrice = jdbcTemplate.queryForObject("SELECT price FROM market_quotes WHERE symbol = 'MSFT'", BigDecimal.class);
        Integer precision = jdbcTemplate.queryForObject("""
                SELECT numeric_precision FROM information_schema.columns
                WHERE table_name = 'market_quotes' AND column_name = 'price'
                """, Integer.class);
        Integer scale = jdbcTemplate.queryForObject("""
                SELECT numeric_scale FROM information_schema.columns
                WHERE table_name = 'market_quotes' AND column_name = 'price'
                """, Integer.class);

        assertThat(storedPrice).isEqualByComparingTo("123.45678901");
        assertThat(precision).isEqualTo(20);
        assertThat(scale).isEqualTo(8);
        assertThatThrownBy(() -> marketQuoteMapper.upsertLatest(invalidReplacement))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT price FROM market_quotes WHERE symbol = 'MSFT'", BigDecimal.class))
                .isEqualByComparingTo("123.45678901");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO market_quotes (market, symbol, currency, price, quote_time, fetched_at, provider, created_at, updated_at)
                VALUES ('US', 'ZERO', 'USD', 0, now(), now(), 'TWELVE_DATA', now(), now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private MarketQuote quote(String symbol, String price, String quoteTime) {
        MarketQuote quote = new MarketQuote();
        quote.setSymbol(symbol);
        quote.setCurrency("USD");
        quote.setPrice(new BigDecimal(price));
        quote.setQuoteTime(Instant.parse(quoteTime));
        quote.setFetchedAt(Instant.parse("2026-01-02T05:06:07Z"));
        return quote;
    }

    private Long insertUser(String username) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES (?, ?, 'hash') RETURNING id
                """, Long.class, username, username + "@example.com");
    }

    private void insertAsset(Long userId, String name) {
        jdbcTemplate.update("""
                INSERT INTO assets (user_id, name, symbol, type, currency, quantity, avg_cost)
                VALUES (?, ?, 'AAPL', 'STOCK', 'CNY', 1, 1)
                """, userId, name);
    }
}
