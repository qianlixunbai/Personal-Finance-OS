package com.financeos.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayVersionNineUpgradeIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @AfterEach
    void resetDatabase() {
        flyway(null).clean();
    }

    @Test
    void upgradesAVersionEightDatabaseWithOpeningMigrationSafeguards() throws Exception {
        flyway(MigrationVersion.fromVersion("8")).migrate();

        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v9-user', 'v9-user@example.com', 'hash');
                    INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Brokerage', 'BROKERAGE', 'CNY', 0);
                    INSERT INTO assets (user_id, account_id, name, type, currency, quantity, avg_cost, position_mode)
                    VALUES (1, 1, 'Legacy fund', 'FUND', 'CNY', 1, 10, 'LEGACY');
                    """);
        }

        flyway(null).migrate();

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("10");
            assertThat(constraintExists(connection, "uk_assets_user_id_id_account_id")).isTrue();
            assertThat(constraintExists(connection, "fk_investment_transactions_user_asset_account")).isTrue();
            assertThat(indexExists(connection, "uk_investment_transactions_posted_opening_asset")).isTrue();
            connection.createStatement().execute("""
                    INSERT INTO investment_transactions
                    (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                     fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                     trade_time, settlement_time, source, idempotency_key, request_hash)
                    VALUES (1, 1, 1, 'OPENING_POSITION', 'POSTED', 1, 10, 10, 0, 0, 0, 0, 0, 'CNY',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MIGRATION', 'opening-one', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
                    """);
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    INSERT INTO investment_transactions
                    (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                     fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                     trade_time, settlement_time, source, idempotency_key, request_hash)
                    VALUES (1, 1, 1, 'OPENING_POSITION', 'POSTED', 1, 10, 10, 0, 0, 0, 0, 0, 'CNY',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MIGRATION', 'opening-two', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb')
                    """)).isInstanceOf(SQLException.class);
            connection.createStatement().execute("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Second', 'BROKERAGE', 'CNY', 0)");
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    INSERT INTO investment_transactions
                    (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                     fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                     trade_time, settlement_time, source, idempotency_key, request_hash)
                    VALUES (1, 1, 2, 'BUY', 'POSTED', 1, 10, 10, 0, 0, 10, 0, 0, 'CNY',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MIGRATION', 'different-account', 'hash-three')
                    """)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void failsBeforeDdlWhenVersionEightContainsDuplicatePostedOpeningFacts() throws Exception {
        flyway(MigrationVersion.fromVersion("8")).migrate();
        insertUserAccountAndAsset();
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO investment_transactions
                    (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                     fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                     trade_time, settlement_time, source, idempotency_key, request_hash)
                    VALUES
                    (1, 1, 1, 'OPENING_POSITION', 'POSTED', 1, 10, 10, 0, 0, 0, 0, 0, 'CNY',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MIGRATION', 'opening-one', 'hash-one'),
                    (1, 1, 1, 'OPENING_POSITION', 'POSTED', 1, 10, 10, 0, 0, 0, 0, 0, 'CNY',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MIGRATION', 'opening-two', 'hash-two');
                    """);
        }

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("duplicate posted opening facts");

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("8");
            assertThat(constraintExists(connection, "uk_assets_user_id_id_account_id")).isFalse();
        }
    }

    @Test
    void failsBeforeDdlWhenVersionEightContainsTransactionAndAssetAccountMismatch() throws Exception {
        flyway(MigrationVersion.fromVersion("8")).migrate();
        insertUserAccountAndAsset();
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Other brokerage', 'BROKERAGE', 'CNY', 0);
                    INSERT INTO investment_transactions
                    (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                     fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                     trade_time, settlement_time, source, idempotency_key, request_hash)
                    VALUES
                    (1, 1, 2, 'BUY', 'POSTED', 1, 10, 10, 0, 0, 10, 0, 0, 'CNY',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MIGRATION', 'mismatched-account', 'hash-mismatch');
                    """);
        }

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("mismatched account bindings");

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("8");
            assertThat(constraintExists(connection, "uk_assets_user_id_id_account_id")).isFalse();
        }
    }

    private void insertUserAccountAndAsset() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v9-user', 'v9-user@example.com', 'hash');
                    INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Brokerage', 'BROKERAGE', 'CNY', 0);
                    INSERT INTO assets (user_id, account_id, name, type, currency, quantity, avg_cost, position_mode)
                    VALUES (1, 1, 'Legacy fund', 'FUND', 'CNY', 1, 10, 'LEGACY');
                    """);
        }
    }

    private boolean constraintExists(Connection connection, String constraintName) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery("""
                SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '%s')
                """.formatted(constraintName))) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }

    private boolean indexExists(Connection connection, String indexName) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery("""
                SELECT EXISTS (SELECT 1 FROM pg_class WHERE relkind = 'i' AND relname = '%s')
                """.formatted(indexName))) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
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
