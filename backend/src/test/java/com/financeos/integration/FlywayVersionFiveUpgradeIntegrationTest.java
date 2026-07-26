package com.financeos.integration;

import org.flywaydb.core.Flyway;
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

@Testcontainers
class FlywayVersionFiveUpgradeIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeEach
    void resetDatabase() {
        flyway(null).clean();
    }

    @Test
    void upgradesVersionFiveAssetsWithoutChangingLegacyDataAndSupportsEightDecimalPlaces() throws Exception {
        flyway(MigrationVersion.fromVersion("5")).migrate();
        insertLegacyAsset();

        flyway(null).migrate();

        try (Connection connection = connection();
             ResultSet precision = connection.createStatement().executeQuery("""
                     SELECT column_name, numeric_precision, numeric_scale
                     FROM information_schema.columns
                     WHERE table_name = 'assets' AND column_name IN ('quantity', 'avg_cost')
                     ORDER BY column_name
                     """);
             ResultSet legacyAsset = connection.createStatement().executeQuery("""
                     SELECT quantity, avg_cost, position_mode
                     FROM assets WHERE id = 1
                     """)) {
            assertThat(precision.next()).isTrue();
            assertThat(precision.getString("column_name")).isEqualTo("avg_cost");
            assertThat(precision.getInt("numeric_precision")).isEqualTo(28);
            assertThat(precision.getInt("numeric_scale")).isEqualTo(8);
            assertThat(precision.next()).isTrue();
            assertThat(precision.getString("column_name")).isEqualTo("quantity");
            assertThat(precision.getInt("numeric_precision")).isEqualTo(28);
            assertThat(precision.getInt("numeric_scale")).isEqualTo(8);

            assertThat(legacyAsset.next()).isTrue();
            assertThat(legacyAsset.getBigDecimal("quantity")).isEqualByComparingTo("12.12345678");
            assertThat(legacyAsset.getBigDecimal("avg_cost")).isEqualByComparingTo("10.1234");
            assertThat(legacyAsset.getString("position_mode")).isEqualTo("LEGACY");
        }

        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    UPDATE assets
                    SET quantity = 99999999999999999999.12345678,
                        avg_cost = 99999999999999999999.12345678
                    WHERE id = 1
                    """);
            try (ResultSet highPrecisionAsset = connection.createStatement().executeQuery("""
                    SELECT quantity, avg_cost FROM assets WHERE id = 1
                    """)) {
                assertThat(highPrecisionAsset.next()).isTrue();
                assertThat(highPrecisionAsset.getBigDecimal("quantity"))
                        .isEqualByComparingTo("99999999999999999999.12345678");
                assertThat(highPrecisionAsset.getBigDecimal("avg_cost"))
                        .isEqualByComparingTo("99999999999999999999.12345678");
            }
        }
    }

    private void insertLegacyAsset() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v5-user', 'v5@example.com', 'hash');
                    INSERT INTO assets (user_id, name, type, currency, quantity, avg_cost, position_mode)
                    VALUES (1, 'Legacy fund', 'FUND', 'CNY', 12.12345678, 10.1234, 'LEGACY');
                    """);
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
