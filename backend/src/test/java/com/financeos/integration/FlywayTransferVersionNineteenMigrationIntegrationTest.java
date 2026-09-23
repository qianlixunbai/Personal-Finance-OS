package com.financeos.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayTransferVersionNineteenMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @AfterEach
    void cleanDatabase() {
        flyway(null).clean();
    }

    @Test
    void upgradesVersionEighteenAndEnforcesOwnedTransferFacts() throws Exception {
        flyway(MigrationVersion.fromVersion("18")).migrate();
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES
                    ('transfer-owner', 'transfer-owner@example.com', 'hash'),
                    ('transfer-other', 'transfer-other@example.com', 'hash');
                    INSERT INTO accounts (user_id, name, type, currency, balance) VALUES
                    (1, 'From', 'CASH', 'CNY', 100.00),
                    (1, 'To', 'BANK', 'CNY', 0.00),
                    (2, 'Other', 'CASH', 'CNY', 0.00);
                    """);
        }

        flyway(null).migrate();

        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, description, transacted_at)
                    VALUES (1, 1, 2, 10.00, 'CNY', 'valid transfer', CURRENT_TIMESTAMP)
                    """);

            assertThat(appliedVersion(connection)).isEqualTo("19");
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
                    VALUES (1, 1, 1, 10.00, 'CNY', CURRENT_TIMESTAMP)
                    """)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
                    VALUES (1, 1, 3, 10.00, 'CNY', CURRENT_TIMESTAMP)
                    """)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
                    VALUES (1, 1, 2, 0.00, 'CNY', CURRENT_TIMESTAMP)
                    """)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
                    VALUES (1, 1, 2, 10.00, 'USD', CURRENT_TIMESTAMP)
                    """)).isInstanceOf(SQLException.class);
        }
    }

    private String appliedVersion(Connection connection) throws Exception {
        try (var resultSet = connection.createStatement().executeQuery("""
                SELECT version FROM flyway_schema_history
                WHERE success = true AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
