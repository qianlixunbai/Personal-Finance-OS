package com.financeos.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayVersionThreeUpgradeIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void upgradesExistingVersionThreeDataToInvestmentLedgerVersionFourWithoutChangingLegacyAssets() throws Exception {
        flyway(MigrationVersion.fromVersion("3")).migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v3-user', 'v3@example.com', 'hash')
                    """);
            connection.createStatement().execute("""
                    INSERT INTO accounts (user_id, name, type, currency, balance)
                    VALUES (1, 'Cash', 'CASH', 'CNY', 100.00)
                    """);
            connection.createStatement().execute("""
                    INSERT INTO assets (user_id, name, symbol, type, currency, quantity, avg_cost, current_price, market_value)
                    VALUES (1, 'Apple', 'AAPL', 'STOCK', 'CNY', 2.00000000, 100.0000, 120.0000, 240.00)
                    """);
            connection.createStatement().execute("""
                    INSERT INTO market_quotes (market, symbol, currency, price, quote_time, fetched_at, provider, created_at, updated_at)
                    VALUES ('US', 'AAPL', 'USD', 200, now(), now(), 'TEST', now(), now())
                    """);
            connection.createStatement().execute("""
                    INSERT INTO exchange_rates (base_currency, quote_currency, rate, rate_time, fetched_at, provider)
                    VALUES ('USD', 'CNY', 7.2, now(), now(), 'TEST')
                    """);
        }

        flyway(null).migrate();

        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
             ResultSet result = connection.createStatement().executeQuery("""
                     SELECT quantity, avg_cost, current_price, market_value, account_id, position_mode, total_cost
                     FROM assets WHERE id = 1
                     """)) {
            result.next();
            assertThat(result.getBigDecimal("quantity")).isEqualByComparingTo("2.00000000");
            assertThat(result.getBigDecimal("avg_cost")).isEqualByComparingTo("100.0000");
            assertThat(result.getBigDecimal("current_price")).isEqualByComparingTo("120.0000");
            assertThat(result.getBigDecimal("market_value")).isEqualByComparingTo("240.00");
            assertThat(result.getObject("account_id")).isNull();
            assertThat(result.getString("position_mode")).isEqualTo("LEGACY");
            assertThat(result.getObject("total_cost")).isNull();
        }
        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
             ResultSet result = connection.createStatement().executeQuery("""
                     SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success = true
                     """)) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
