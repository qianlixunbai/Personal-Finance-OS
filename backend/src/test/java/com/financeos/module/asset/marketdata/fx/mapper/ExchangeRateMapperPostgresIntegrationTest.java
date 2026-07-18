package com.financeos.module.asset.marketdata.fx.mapper;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeRateMapperPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ExchangeRateMapper exchangeRateMapper;

    @Test
    void upsertsTheLatestPublicRateAndPreservesItsIdentityAndCreationTime() {
        exchangeRateMapper.upsertLatest(rate(" usd ", " cny ", "7.200000000001",
                "2026-01-02T03:04:05Z", "2026-01-02T03:05:05Z", "FIRST"));
        ExchangeRate original = exchangeRateMapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY");

        exchangeRateMapper.upsertLatest(rate("USD", "CNY", "7.250000000000",
                "2026-01-02T04:04:05Z", "2026-01-02T04:05:05Z", "SECOND"));
        ExchangeRate stored = exchangeRateMapper.findByBaseCurrencyAndQuoteCurrency(" usd ", " cny ");

        assertThat(stored.getId()).isEqualTo(original.getId());
        assertThat(stored.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(stored.getBaseCurrency()).isEqualTo("USD");
        assertThat(stored.getQuoteCurrency()).isEqualTo("CNY");
        assertThat(stored.getRate()).isEqualByComparingTo("7.250000000000");
        assertThat(stored.getRateTime()).isEqualTo(Instant.parse("2026-01-02T04:04:05Z"));
        assertThat(stored.getFetchedAt()).isEqualTo(Instant.parse("2026-01-02T04:05:05Z"));
        assertThat(stored.getProvider()).isEqualTo("SECOND");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM exchange_rates WHERE base_currency = 'USD' AND quote_currency = 'CNY'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void retainsANewerSnapshotWhenAnOlderRateTimeIsUpserted() {
        exchangeRateMapper.upsertLatest(rate("USD", "CNY", "7.250000000000",
                "2026-01-02T04:04:05Z", "2026-01-02T04:05:05Z", "CURRENT"));

        int affectedRows = exchangeRateMapper.upsertLatest(rate("USD", "CNY", "7.100000000000",
                "2026-01-02T03:04:05Z", "2026-01-02T06:05:05Z", "STALE"));

        ExchangeRate stored = exchangeRateMapper.findByBaseCurrencyAndQuoteCurrency("USD", "CNY");
        assertThat(affectedRows).isZero();
        assertThat(stored.getRate()).isEqualByComparingTo("7.250000000000");
        assertThat(stored.getProvider()).isEqualTo("CURRENT");
    }

    @Test
    void returnsSeveralNormalizedBaseCurrenciesForOneQuoteCurrency() {
        exchangeRateMapper.upsertLatest(rate("USD", "CNY", "7.250000000000",
                "2026-01-02T04:04:05Z", "2026-01-02T04:05:05Z", "FX"));
        exchangeRateMapper.upsertLatest(rate("EUR", "CNY", "7.850000000000",
                "2026-01-02T04:04:05Z", "2026-01-02T04:05:05Z", "FX"));

        List<ExchangeRate> rates = exchangeRateMapper.findByBaseCurrenciesAndQuoteCurrency(
                List.of(" usd ", "eur", "JPY"), " cny ");

        assertThat(rates).extracting(ExchangeRate::getBaseCurrency).containsExactlyInAnyOrder("USD", "EUR");
    }

    @Test
    void returnsNoRatesWithoutQueryingInvalidSqlForNullOrEmptyBaseCurrencies() {
        assertThatCode(() -> exchangeRateMapper.findByBaseCurrenciesAndQuoteCurrency(null, "CNY"))
                .doesNotThrowAnyException();
        assertThat(exchangeRateMapper.findByBaseCurrenciesAndQuoteCurrency(null, "CNY")).isEmpty();
        assertThat(exchangeRateMapper.findByBaseCurrenciesAndQuoteCurrency(List.of(), "CNY")).isEmpty();
    }

    @Test
    void databaseRejectsNonPositiveRatesAndSameCurrencyPairs() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO exchange_rates (base_currency, quote_currency, rate, rate_time, fetched_at, provider, created_at, updated_at)
                VALUES ('USD', 'CNY', 0, now(), now(), 'FX', now(), now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO exchange_rates (base_currency, quote_currency, rate, rate_time, fetched_at, provider, created_at, updated_at)
                VALUES ('USD', 'CNY', -1, now(), now(), 'FX', now(), now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO exchange_rates (base_currency, quote_currency, rate, rate_time, fetched_at, provider, created_at, updated_at)
                VALUES ('CNY', 'CNY', 1, now(), now(), 'FX', now(), now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO exchange_rates (base_currency, quote_currency, rate, rate_time, fetched_at, provider, created_at, updated_at)
                VALUES ('US', 'CNY', 1, now(), now(), 'FX', now(), now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ExchangeRate rate(String baseCurrency, String quoteCurrency, String rate, String rateTime,
                              String fetchedAt, String provider) {
        ExchangeRate exchangeRate = new ExchangeRate();
        exchangeRate.setBaseCurrency(baseCurrency);
        exchangeRate.setQuoteCurrency(quoteCurrency);
        exchangeRate.setRate(new BigDecimal(rate));
        exchangeRate.setRateTime(Instant.parse(rateTime));
        exchangeRate.setFetchedAt(Instant.parse(fetchedAt));
        exchangeRate.setProvider(provider);
        return exchangeRate;
    }
}
