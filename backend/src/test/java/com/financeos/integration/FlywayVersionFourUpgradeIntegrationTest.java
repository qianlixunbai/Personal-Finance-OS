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
class FlywayVersionFourUpgradeIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeEach
    void resetDatabase() {
        flyway(null).clean();
    }

    @Test
    void rejectsHistoricalNonOpeningFactsWithoutChangingThem() throws Exception {
        flyway(MigrationVersion.fromVersion("4")).migrate();
        insertVersionFourBuy("20.00");

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("existing non-opening investment facts");

        try (Connection connection = connection();
             ResultSet row = connection.createStatement().executeQuery("""
                     SELECT gross_amount, net_amount, released_cost_amount, realized_profit_loss
                     FROM investment_transactions WHERE id = 1
                     """)) {
            row.next();
            assertThat(row.getBigDecimal("gross_amount")).isEqualByComparingTo("20.00");
            assertThat(row.getBigDecimal("net_amount")).isEqualByComparingTo("20.00");
            assertThat(row.getBigDecimal("released_cost_amount")).isEqualByComparingTo("0.00");
            assertThat(row.getBigDecimal("realized_profit_loss")).isEqualByComparingTo("0.00");
        }
    }

    @Test
    void failsFastForContradictoryVersionFourInvestmentFactsWithoutRewritingThem() throws Exception {
        flyway(MigrationVersion.fromVersion("4")).migrate();
        insertVersionFourBuy("999.00");

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("existing invalid transaction facts found");

        try (Connection connection = connection();
             ResultSet row = connection.createStatement().executeQuery("SELECT net_amount FROM investment_transactions WHERE id = 1")) {
            row.next();
            assertThat(row.getBigDecimal("net_amount")).isEqualByComparingTo("999.00");
        }
    }

    private void insertVersionFourBuy(String netAmount) throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v4-user', 'v4@example.com', 'hash');
                    INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Brokerage', 'BROKERAGE', 'CNY', 0);
                    INSERT INTO assets (user_id, account_id, name, type, currency, quantity, avg_cost) VALUES (1, 1, 'Fund', 'FUND', 'CNY', 0, 0);
                    """);
            connection.createStatement().execute("""
                    INSERT INTO investment_transactions (
                        user_id, asset_id, account_id, transaction_type, status, quantity, unit_price,
                        gross_amount, fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss,
                        currency, trade_time, settlement_time, source, idempotency_key, request_hash
                    ) VALUES (1, 1, 1, 'BUY', 'POSTED', 2, 10, 20, 0, 0, %s, 0, 0,
                        'CNY', now(), now(), 'MIGRATION', 'v4-buy', 'v4-buy-hash')
                    """.formatted(netAmount));
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
