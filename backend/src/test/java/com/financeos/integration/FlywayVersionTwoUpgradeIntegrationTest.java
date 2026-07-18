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
class FlywayVersionTwoUpgradeIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void upgradesExistingVersionTwoDataToExchangeRatesVersionThreeWithoutDamagingExistingData() throws Exception {
        flyway(MigrationVersion.fromVersion("2")).migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v1-user', 'v1@example.com', 'hash')
                    """);
            connection.createStatement().execute("""
                    INSERT INTO accounts (user_id, name, type, currency, balance)
                    VALUES (1, 'Cash', 'CASH', 'CNY', 100.00)
                    """);
            connection.createStatement().execute("""
                    INSERT INTO assets (user_id, name, symbol, type, currency, quantity, avg_cost)
                    VALUES (1, 'Apple', 'AAPL', 'STOCK', 'CNY', 1, 100)
                    """);
            connection.createStatement().execute("""
                    INSERT INTO market_quotes (market, symbol, currency, price, quote_time, fetched_at, provider, created_at, updated_at)
                    VALUES ('US', 'AAPL', 'USD', 200, now(), now(), 'TEST', now(), now())
                    """);
        }

        flyway(null).migrate();

        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
             ResultSet result = connection.createStatement().executeQuery("""
                     SELECT (SELECT count(*) FROM users) + (SELECT count(*) FROM accounts)
                            + (SELECT count(*) FROM assets) + (SELECT count(*) FROM market_quotes)
                            + (SELECT count(*) FROM exchange_rates)
                     """)) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(4);
        }

        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
             ResultSet result = connection.createStatement().executeQuery("""
                     SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success = true
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
