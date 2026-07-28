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
    void migratesAnEmptyPostgresDatabaseThroughVersionEight() {
        Integer applicationTableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('users', 'accounts', 'categories', 'transactions', 'assets', 'asset_prices', 'market_quotes', 'exchange_rates', 'investment_transactions', 'investment_instruments')
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
        Integer versionFourMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '4' AND description = 'investment ledger foundation' AND success = true
                """, Integer.class);
        Integer versionFiveMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '5' AND description = 'harden investment ledger constraints' AND success = true
                """, Integer.class);
        Integer versionSixMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '6' AND description = 'align asset projection precision' AND success = true
                """, Integer.class);
        Integer versionSevenMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '7' AND description = 'create investment instruments' AND success = true
                """, Integer.class);
        Integer versionEightMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '8' AND description = 'bind asset positions to instruments' AND success = true
                """, Integer.class);
        Integer versionTenMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE version = '10' AND description = 'harden investment write receipts' AND success = true
                """, Integer.class);
        Integer versionElevenMigrationCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE version = '11' AND description = 'harden dividend write receipts' AND success = true
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
        Integer quantityPrecision = jdbcTemplate.queryForObject("""
                SELECT numeric_precision FROM information_schema.columns
                WHERE table_name = 'investment_transactions' AND column_name = 'quantity'
                """, Integer.class);
        Integer quantityScale = jdbcTemplate.queryForObject("""
                SELECT numeric_scale FROM information_schema.columns
                WHERE table_name = 'investment_transactions' AND column_name = 'quantity'
                """, Integer.class);
        Integer amountPrecision = jdbcTemplate.queryForObject("""
                SELECT numeric_precision FROM information_schema.columns
                WHERE table_name = 'investment_transactions' AND column_name = 'gross_amount'
                """, Integer.class);
        Integer amountScale = jdbcTemplate.queryForObject("""
                SELECT numeric_scale FROM information_schema.columns
                WHERE table_name = 'investment_transactions' AND column_name = 'gross_amount'
                """, Integer.class);
        Integer assetQuantityPrecision = jdbcTemplate.queryForObject("""
                SELECT numeric_precision FROM information_schema.columns
                WHERE table_name = 'assets' AND column_name = 'quantity'
                """, Integer.class);
        Integer assetQuantityScale = jdbcTemplate.queryForObject("""
                SELECT numeric_scale FROM information_schema.columns
                WHERE table_name = 'assets' AND column_name = 'quantity'
                """, Integer.class);
        Integer assetAverageCostPrecision = jdbcTemplate.queryForObject("""
                SELECT numeric_precision FROM information_schema.columns
                WHERE table_name = 'assets' AND column_name = 'avg_cost'
                """, Integer.class);
        Integer assetAverageCostScale = jdbcTemplate.queryForObject("""
                SELECT numeric_scale FROM information_schema.columns
                WHERE table_name = 'assets' AND column_name = 'avg_cost'
                """, Integer.class);
        Map<String, String> investmentColumns = jdbcTemplate.query("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_name = 'investment_transactions'
                """, resultSet -> {
            Map<String, String> result = new java.util.HashMap<>();
            while (resultSet.next()) {
                result.put(resultSet.getString("column_name"), resultSet.getString("data_type"));
            }
            return result;
        });
        List<String> investmentConstraints = jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = 'investment_transactions'::regclass
                """, String.class);
        List<String> investmentIndexes = jdbcTemplate.queryForList("""
                SELECT index_class.relname
                FROM pg_index index_definition
                JOIN pg_class index_class ON index_class.oid = index_definition.indexrelid
                WHERE index_definition.indrelid = 'investment_transactions'::regclass
                """, String.class);

        assertThat(applicationTableCount).isEqualTo(10);
        assertThat(versionOneMigrationCount).isEqualTo(1);
        assertThat(versionTwoMigrationCount).isEqualTo(1);
        assertThat(versionThreeMigrationCount).isEqualTo(1);
        assertThat(versionFourMigrationCount).isEqualTo(1);
        assertThat(versionFiveMigrationCount).isEqualTo(1);
        assertThat(versionSixMigrationCount).isEqualTo(1);
        assertThat(versionSevenMigrationCount).isEqualTo(1);
        assertThat(versionEightMigrationCount).isEqualTo(1);
        assertThat(versionTenMigrationCount).isEqualTo(1);
        assertThat(versionElevenMigrationCount).isEqualTo(1);
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
        assertThat(quantityPrecision).isEqualTo(28);
        assertThat(quantityScale).isEqualTo(8);
        assertThat(amountPrecision).isEqualTo(28);
        assertThat(amountScale).isEqualTo(2);
        assertThat(assetQuantityPrecision).isEqualTo(28);
        assertThat(assetQuantityScale).isEqualTo(8);
        assertThat(assetAverageCostPrecision).isEqualTo(28);
        assertThat(assetAverageCostScale).isEqualTo(8);
        assertThat(investmentColumns).containsEntry("id", "bigint")
                .containsEntry("user_id", "bigint")
                .containsEntry("asset_id", "bigint")
                .containsEntry("account_id", "bigint")
                .containsEntry("quantity", "numeric")
                .containsEntry("unit_price", "numeric")
                .containsEntry("gross_amount", "numeric")
                .containsEntry("trade_time", "timestamp with time zone")
                .containsEntry("settlement_time", "timestamp with time zone")
                .containsEntry("idempotency_key", "character varying");
        assertThat(investmentConstraints).contains(
                "investment_transactions_pkey",
                "uk_investment_transactions_user_idempotency",
                "fk_investment_transactions_user_asset",
                "fk_investment_transactions_user_account",
                "ck_investment_transactions_type",
                "ck_investment_transactions_status",
                "ck_investment_transactions_currency_format",
                "ck_investment_transactions_settlement_time",
                "ck_investment_transactions_reversal_state",
                "ck_investment_transactions_type_fields",
                "ck_investment_transactions_opening_source",
                "ck_investment_transactions_buy_amounts",
                "ck_investment_transactions_sell_amounts",
                "ck_investment_transactions_dividend_amounts",
                "ck_investment_transactions_opening_position_amounts_v5",
                "ck_investment_transactions_replacement_not_self",
                "fk_investment_transactions_replacement_user_asset",
                "ck_investment_transactions_receipt");
        assertThat(investmentIndexes).contains(
                "idx_investment_transactions_user_trade_time_desc",
                "idx_investment_transactions_user_asset_trade_time",
                "idx_investment_transactions_user_account_trade_time_desc",
                "idx_investment_transactions_user_type_trade_time_desc");
    }
}
