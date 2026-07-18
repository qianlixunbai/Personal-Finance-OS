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
    void upgradesExistingVersionOneDataToMarketQuotesVersionTwo() throws Exception {
        flyway(MigrationVersion.fromVersion("1")).migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v1-user', 'v1@example.com', 'hash')
                    """);
        }

        flyway(null).migrate();

        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
             ResultSet result = connection.createStatement().executeQuery("""
                     SELECT count(*) FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_name = 'market_quotes'
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
