package com.financeos.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FlywayMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRESQL::getDriverClassName);
    }

    @Test
    void migratesAnEmptyPostgresDatabaseThroughVersionThree() {
        Integer applicationTableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('users', 'accounts', 'categories', 'transactions', 'assets', 'asset_prices', 'market_quotes', 'exchange_rates')
                """, Integer.class);
        Integer versionOneMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '1' AND description = 'baseline' AND success = true
                """, Integer.class);
        Integer versionTwoMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '2' AND description = 'market quotes' AND success = true
                """, Integer.class);
        Integer versionThreeMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '3' AND description = 'exchange rates' AND success = true
                """, Integer.class);
        Integer ratePrecision = jdbcTemplate.queryForObject("""
                SELECT numeric_precision FROM information_schema.columns
                WHERE table_name = 'exchange_rates' AND column_name = 'rate'
                """, Integer.class);
        Integer rateScale = jdbcTemplate.queryForObject("""
                SELECT numeric_scale FROM information_schema.columns
                WHERE table_name = 'exchange_rates' AND column_name = 'rate'
                """, Integer.class);
        Map<String, String> columns = jdbcTemplate.query("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_name = 'exchange_rates'
                """, resultSet -> {
            Map<String, String> result = new java.util.HashMap<>();
            while (resultSet.next()) {
                result.put(resultSet.getString("column_name"), resultSet.getString("data_type"));
            }
            return result;
        });
        List<String> constraints = jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'exchange_rates'::regclass
                """, String.class);

        assertThat(applicationTableCount).isEqualTo(8);
        assertThat(versionOneMigrationCount).isEqualTo(1);
        assertThat(versionTwoMigrationCount).isEqualTo(1);
        assertThat(versionThreeMigrationCount).isEqualTo(1);
        assertThat(ratePrecision).isEqualTo(24);
        assertThat(rateScale).isEqualTo(12);
        assertThat(columns).containsEntry("id", "bigint")
                .containsEntry("base_currency", "character varying")
                .containsEntry("quote_currency", "character varying")
                .containsEntry("rate", "numeric")
                .containsEntry("rate_time", "timestamp with time zone")
                .containsEntry("fetched_at", "timestamp with time zone")
                .containsEntry("provider", "character varying")
                .containsEntry("created_at", "timestamp with time zone")
                .containsEntry("updated_at", "timestamp with time zone");
        assertThat(constraints).contains(
                "exchange_rates_pkey",
                "uk_exchange_rates_currency_pair",
                "ck_exchange_rates_base_currency_format",
                "ck_exchange_rates_quote_currency_format",
                "ck_exchange_rates_rate_positive",
                "ck_exchange_rates_distinct_currencies");
    }
}
