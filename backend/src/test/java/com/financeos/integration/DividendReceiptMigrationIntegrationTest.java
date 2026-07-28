package com.financeos.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class DividendReceiptMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeEach
    void migrateThroughV10() {
        flyway().clean();
        flyway().migrate();
    }

    @Test
    void migratesV10WithoutDividendAndMakesDividendReceiptsMandatory() throws SQLException {
        insertPositionWithoutDividend();
        migrateThroughV11();

        assertThat(queryForInt("SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success")).isEqualTo(1);
        assertThatThrownBy(() -> execute("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, gross_amount, fee_amount, tax_amount,
                    net_amount, released_cost_amount, realized_profit_loss, currency, trade_time, settlement_time,
                    source, idempotency_key, request_hash)
                VALUES (1, 1, 1, 'DIVIDEND', 'POSTED', 1.00, 0.00, 0.00, 1.00, 0.00, 0.00,
                        'CNY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MANUAL', 'missing-receipt', repeat('a', 64))
                """))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void failsFastWithoutDroppingV10ReceiptConstraintWhenHistoricalDividendExists() throws SQLException {
        insertHistoricalDividend();

        assertThatThrownBy(this::migrateThroughV11)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("existing dividend facts");

        assertThat(queryForInt("SELECT count(*) FROM flyway_schema_history WHERE version = '11'")).isZero();
        assertThat(queryForString("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'investment_transactions'::regclass
                  AND conname = 'ck_investment_transactions_receipt'
                """)).contains("OPENING_POSITION", "DIVIDEND");
    }

    private void migrateThroughV11() {
        Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations(migrationLocation()).load().migrate();
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations(migrationLocation()).cleanDisabled(false).target("10").load();
    }

    private String migrationLocation() {
        return "filesystem:" + Path.of("src", "main", "resources", "db", "migration").toAbsolutePath();
    }

    private void insertHistoricalDividend() throws SQLException {
        insertPositionWithoutDividend();
        execute("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, gross_amount, fee_amount, tax_amount,
                    net_amount, released_cost_amount, realized_profit_loss, currency, trade_time, settlement_time,
                    source, idempotency_key, request_hash)
                VALUES (1, 1, 1, 'DIVIDEND', 'POSTED', 1.00, 0.00, 0.00, 1.00, 0.00, 0.00,
                        'CNY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MANUAL', 'old-dividend', repeat('a', 64))
                """);
    }

    private void insertPositionWithoutDividend() throws SQLException {
        execute("INSERT INTO users (username, email, password_hash) VALUES ('u', 'u@example.com', 'hash')");
        execute("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Brokerage', 'BROKERAGE', 'CNY', 0)");
        execute("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (1, 'FUND1', 'Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """);
        execute("""
                INSERT INTO assets (user_id, account_id, instrument_id, name, symbol, type, market, currency,
                                    quantity, avg_cost, total_cost, realized_profit_loss, position_status, position_mode)
                VALUES (1, 1, 1, 'Fund', 'FUND1', 'FUND', 'FUND', 'CNY',
                        1.00000000, 1.00000000, 1.00, 0.00, 'OPEN', 'TRANSACTION_DRIVEN')
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int queryForInt(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String queryForString(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
