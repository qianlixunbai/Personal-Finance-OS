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
class AppendOnlyInvestmentReversalMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeEach
    void migrateThroughV11() {
        flyway("11").clean();
        flyway("11").migrate();
    }

    @Test
    void upgradesV11ToV12WhenNoLegacyCorrectionDataExists() throws SQLException {
        insertBuy();

        migrateThroughV12();

        assertThat(queryForInt("SELECT count(*) FROM flyway_schema_history WHERE version = '12' AND success")).isEqualTo(1);
        assertThat(queryForInt("SELECT count(*) FROM information_schema.columns WHERE table_name = 'investment_transactions' AND column_name IN ('original_transaction_id', 'correction_reason', 'cash_delta')"))
                .isEqualTo(3);
        assertThat(queryForInt("SELECT count(*) FROM pg_trigger WHERE tgrelid = 'investment_transactions'::regclass AND tgname = 'trg_prevent_investment_transaction_mutation'"))
                .isEqualTo(1);
    }

    @Test
    void failsFastForRetiredMutableCorrectionData() throws SQLException {
        insertBuy();
        execute("UPDATE investment_transactions SET status = 'REVERSED', reversed_at = CURRENT_TIMESTAMP, reversal_reason = 'legacy' WHERE id = 1");
        assertMigrationFailsWithoutRecordingV12();

        flyway("11").clean();
        flyway("11").migrate();
        insertBuy();
        insertBuy("buy-2");
        execute("UPDATE investment_transactions SET replaces_transaction_id = 2 WHERE id = 1");
        assertMigrationFailsWithoutRecordingV12();
    }

    private void assertMigrationFailsWithoutRecordingV12() throws SQLException {
        assertThatThrownBy(this::migrateThroughV12)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("legacy reversal or replacement data exists");
        assertThat(queryForInt("SELECT count(*) FROM flyway_schema_history WHERE version = '12'")).isZero();
    }

    private void migrateThroughV12() {
        Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations(migrationLocation()).load().migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations(migrationLocation()).cleanDisabled(false).target(target).load();
    }

    private String migrationLocation() {
        return "filesystem:" + Path.of("src", "main", "resources", "db", "migration").toAbsolutePath();
    }

    private void insertBuy() throws SQLException {
        execute("INSERT INTO users (username, email, password_hash) VALUES ('u', 'u@example.com', 'hash')");
        execute("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Brokerage', 'BROKERAGE', 'CNY', 0)");
        execute("INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status) VALUES (1, 'FUND1', 'Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')");
        execute("INSERT INTO assets (user_id, account_id, instrument_id, name, symbol, type, market, currency, quantity, avg_cost, total_cost, realized_profit_loss, position_status, position_mode) VALUES (1, 1, 1, 'Fund', 'FUND1', 'FUND', 'FUND', 'CNY', 1.00000000, 1.00000000, 1.00, 0.00, 'OPEN', 'TRANSACTION_DRIVEN')");
        insertBuy("buy");
    }

    private void insertBuy(String idempotencyKey) throws SQLException {
        execute("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                    fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash,
                    account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                    position_realized_profit_loss_after, position_status_after, projection_version_after)
                VALUES (1, 1, 1, 'BUY', 'POSTED', 1.00000000, 1.00000000, 1.00, 0.00, 0.00, 1.00, 0.00, 0.00,
                        'CNY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MANUAL', '%s', repeat('a', 64),
                        -1.00, 1.00000000, 1.00000000, 1.00, 0.00, 'OPEN', 1)
                """.formatted(idempotencyKey));
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
}
