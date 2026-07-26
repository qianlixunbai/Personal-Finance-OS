package com.financeos.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayVersionSevenAndEightUpgradeIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeEach
    void resetDatabase() {
        flyway(null).clean();
    }

    @Test
    void upgradesAVersionSixDatabaseWithoutChangingLegacyAssets() throws Exception {
        flyway(MigrationVersion.fromVersion("6")).migrate();
        insertLegacyAsset();

        flyway(null).migrate();

        try (Connection connection = connection();
             ResultSet result = connection.createStatement().executeQuery("""
                     SELECT a.position_mode, a.account_id, a.instrument_id,
                            i.symbol, i.market, i.asset_class, i.quote_currency, i.status
                     FROM assets a
                     LEFT JOIN investment_instruments i ON i.user_id = a.user_id
                     WHERE a.id = 1
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("position_mode")).isEqualTo("LEGACY");
            assertThat(result.getObject("account_id")).isNull();
            assertThat(result.getObject("instrument_id")).isNull();
            assertThat(result.getString("symbol")).isNull();
            assertThat(result.getString("market")).isNull();
            assertThat(result.getString("asset_class")).isNull();
            assertThat(result.getString("quote_currency")).isNull();
            assertThat(result.getString("status")).isNull();
        }
    }

    @Test
    void failsBeforeDdlWhenVersionSixContainsTransactionDrivenAssets() throws Exception {
        flyway(MigrationVersion.fromVersion("6")).migrate();
        insertTransactionDrivenAsset();

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("TRANSACTION_DRIVEN");

        try (Connection connection = connection()) {
            assertThat(tableExists(connection, "investment_instruments")).isFalse();
            assertThat(appliedVersion(connection)).isEqualTo("6");
        }
    }

    private void insertLegacyAsset() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v7-legacy', 'v7-legacy@example.com', 'hash');
                    INSERT INTO assets (user_id, name, symbol, type, market, currency, quantity, avg_cost, position_mode)
                    VALUES (1, 'Legacy fund', '000001', 'FUND', 'CN', 'CNY', 12.12345678, 10.12345678, 'LEGACY');
                    """);
        }
    }

    private void insertTransactionDrivenAsset() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v7-projection', 'v7-projection@example.com', 'hash');
                    INSERT INTO assets (user_id, name, type, currency, quantity, avg_cost, position_mode)
                    VALUES (1, 'Existing projection', 'STOCK', 'CNY', 1, 1, 'TRANSACTION_DRIVEN');
                    """);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet result = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return result.next();
        }
    }

    private String appliedVersion(Connection connection) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery("""
                SELECT version FROM flyway_schema_history
                WHERE success = true AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
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
