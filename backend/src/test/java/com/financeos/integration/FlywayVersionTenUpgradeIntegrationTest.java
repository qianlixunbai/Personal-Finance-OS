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
class FlywayVersionTenUpgradeIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @AfterEach
    void resetDatabase() {
        flyway(null).clean();
    }

    @Test
    void upgradesVersionNineWithImmutableBuySellReceipts() throws Exception {
        flyway(MigrationVersion.fromVersion("9")).migrate();

        flyway(null).migrate();

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("16");
            assertThat(columnExists(connection, "account_balance_after")).isTrue();
            assertThat(columnExists(connection, "position_quantity_after")).isTrue();
            assertThat(columnExists(connection, "position_avg_cost_after")).isTrue();
            assertThat(columnExists(connection, "position_total_cost_after")).isTrue();
            assertThat(columnExists(connection, "position_realized_profit_loss_after")).isTrue();
            assertThat(columnExists(connection, "position_status_after")).isTrue();
            assertThat(columnExists(connection, "projection_version_after")).isTrue();
            assertThat(constraintExists(connection, "ck_investment_transactions_receipt")).isTrue();
            assertThat(constraintExists(connection, "ck_investment_transactions_request_hash_sha256")).isTrue();
            assertThat(constraintExists(connection, "ck_investment_transactions_idempotency_key_canonical")).isTrue();
            assertThat(columnExists(connection, "original_transaction_id")).isTrue();
            assertThat(columnExists(connection, "correction_reason")).isTrue();
            assertThat(columnExists(connection, "cash_delta")).isTrue();
            insertUserAccountInstrumentAndPosition(connection);
            assertThatThrownBy(() -> connection.createStatement().execute("""
                    INSERT INTO investment_transactions
                    (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                     fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                     trade_time, settlement_time, source, idempotency_key, request_hash)
                    VALUES (1, 1, 1, 'BUY', 'POSTED', 1, 10, 10, 0, 0, 10, 0, 0, 'CNY',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MANUAL', 'missing-receipt',
                            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
                    """)).isInstanceOf(SQLException.class);
            connection.createStatement().execute("""
                    INSERT INTO investment_transactions
                    (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                     fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                     trade_time, settlement_time, source, idempotency_key, request_hash, account_balance_after,
                     position_quantity_after, position_avg_cost_after, position_total_cost_after,
                     position_realized_profit_loss_after, position_status_after, projection_version_after)
                    VALUES (1, 1, 1, 'BUY', 'POSTED', 1, 10, 10, 0, 0, 10, 0, 0, 'CNY',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MANUAL', 'valid-receipt',
                            'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', -10,
                            1, 10, 10, 0, 'OPEN', 1)
                    """);
        }
    }

    @Test
    void failsBeforeDdlWhenVersionNineContainsNonOpeningFactsWithoutReceipts() throws Exception {
        flyway(MigrationVersion.fromVersion("9")).migrate();
        try (Connection connection = connection()) {
            insertUserAccountInstrumentAndPosition(connection);
            connection.createStatement().execute("""
                    INSERT INTO investment_transactions
                    (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                     fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                     trade_time, settlement_time, source, idempotency_key, request_hash)
                    VALUES (1, 1, 1, 'BUY', 'POSTED', 1, 10, 10, 0, 0, 10, 0, 0, 'CNY',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MANUAL', 'old-buy', 'old-hash')
                    """);
        }

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("existing non-opening investment facts");

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("9");
            assertThat(columnExists(connection, "account_balance_after")).isFalse();
        }
    }

    @Test
    void failsBeforeDdlWhenVersionNineContainsNonCanonicalOpeningRequestHash() throws Exception {
        flyway(MigrationVersion.fromVersion("9")).migrate();
        try (Connection connection = connection()) {
            insertUserAccountInstrumentAndPosition(connection);
            connection.createStatement().execute("""
                    INSERT INTO investment_transactions
                    (user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                     fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                     trade_time, settlement_time, source, idempotency_key, request_hash)
                    VALUES (1, 1, 1, 'OPENING_POSITION', 'POSTED', 1, 10, 10, 0, 0, 0, 0, 0, 'CNY',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MIGRATION', 'opening-key', 'not-a-sha256-hash')
                    """);
        }

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("existing invalid request hash");

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("9");
            assertThat(columnExists(connection, "account_balance_after")).isFalse();
        }
    }

    private void insertUserAccountInstrumentAndPosition(Connection connection) throws Exception {
        connection.createStatement().execute("""
                INSERT INTO users (username, email, password_hash) VALUES ('v10-user', 'v10-user@example.com', 'hash');
                INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Brokerage', 'BROKERAGE', 'CNY', 0);
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (1, 'V10', 'V10 Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE');
                INSERT INTO assets (user_id, account_id, instrument_id, name, type, currency, quantity, avg_cost,
                                    total_cost, realized_profit_loss, position_status, position_mode)
                VALUES (1, 1, 1, 'V10 Fund', 'FUND', 'CNY', 1, 10, 10, 0, 'OPEN', 'TRANSACTION_DRIVEN');
                """);
    }

    private boolean columnExists(Connection connection, String columnName) throws Exception {
        try (ResultSet result = connection.createStatement().executeQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'investment_transactions' AND column_name = '%s'
                )
                """.formatted(columnName))) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
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
