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
class FlywayTransactionImportVersionSixteenUpgradeIntegrationTest {
    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000101";
    private static final String BATCH_ID = "00000000-0000-0000-0000-000000000201";

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @AfterEach
    void resetDatabase() {
        flyway(null).clean();
    }

    @Test
    void upgradesRepresentativeVersionFifteenImportDataWithoutLosingReceiptLinks() throws Exception {
        flyway(MigrationVersion.fromVersion("15")).migrate();
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    INSERT INTO users (username, email, password_hash) VALUES ('v15-import', 'v15-import@example.com', 'hash');
                    INSERT INTO accounts (user_id, name, type, currency, balance, status) VALUES (1, 'Cash', 'CASH', 'CNY', -10.00, 'ACTIVE');
                    INSERT INTO categories (user_id, name, type, is_system) VALUES (1, 'Food', 'EXPENSE', false);
                    INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, description, transacted_at)
                    VALUES (1, 1, 1, 'EXPENSE', 10.00, 'CNY', 'legacy import', '2026-08-01T00:00:00Z');
                    INSERT INTO transaction_import_sessions
                    (id, user_id, preallocated_batch_id, status, revision, original_file_name, content_type, file_size, file_digest,
                     temporary_storage_reference, mapping_digest, options_digest, normalized_rows_digest, plan_storage_reference,
                     expires_at, consumed_at, cancelled_at)
                    VALUES
                    ('00000000-0000-0000-0000-000000000101', 1, '00000000-0000-0000-0000-000000000201', 'CONSUMED', 1,
                     'legacy.csv', 'text/csv', 10, '%1$s', NULL, '%2$s', '%3$s', '%4$s', NULL,
                     '2026-08-01T01:00:00Z', '2026-08-01T00:01:00Z', NULL),
                    ('00000000-0000-0000-0000-000000000102', 1, '00000000-0000-0000-0000-000000000202', 'PREVIEW_READY', 2,
                     'active.csv', 'text/csv', 10, '%1$s', NULL, '%2$s', '%3$s', '%4$s', 'plans/active',
                     '2026-08-02T01:00:00Z', NULL, NULL),
                    ('00000000-0000-0000-0000-000000000103', 1, '00000000-0000-0000-0000-000000000203', 'CANCELLED', 0,
                     'cancelled.csv', 'text/csv', 10, '%1$s', NULL, NULL, '%3$s', NULL, NULL,
                     '2026-08-02T01:00:00Z', NULL, '2026-08-01T00:02:00Z');
                    INSERT INTO transaction_import_batches
                    (id, user_id, original_file_name, file_digest, mapping_digest, options_digest, normalized_rows_digest,
                     contract_version, idempotency_key, request_hash, status, total_rows, warning_count, confirmed_at)
                    VALUES ('00000000-0000-0000-0000-000000000201', 1, 'legacy.csv', '%1$s', '%2$s', '%3$s', '%4$s',
                            'TRANSACTION_IMPORT_CONFIRM_V1', 'legacy-import-key', '%5$s', 'CONFIRMED', 1, 0, '2026-08-01T00:01:00Z');
                    INSERT INTO transaction_import_items (user_id, batch_id, source_row_number, canonical_row_fingerprint, created_transaction_id)
                    VALUES (1, '00000000-0000-0000-0000-000000000201', 1, '%5$s', 1);
                    """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), "e".repeat(64)));
        }

        flyway(null).migrate();

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("17");
            assertThat(queryString(connection, "SELECT session_id::text FROM transaction_import_batches WHERE id = '" + BATCH_ID + "'"))
                    .isEqualTo(SESSION_ID);
            assertThat(queryString(connection, "SELECT result_digest FROM transaction_import_batches WHERE id = '" + BATCH_ID + "'"))
                    .matches("[0-9a-f]{64}");
            assertThat(queryString(connection, "SELECT created_transaction_id::text FROM transaction_import_items WHERE batch_id = '" + BATCH_ID + "'"))
                    .isEqualTo("1");
            assertThat(queryString(connection, "SELECT count(*)::text FROM transaction_import_batch_account_impacts WHERE batch_id = '" + BATCH_ID + "'"))
                    .isEqualTo("1");
            assertThat(queryString(connection, "SELECT status FROM transaction_import_sessions WHERE id = '00000000-0000-0000-0000-000000000102'"))
                    .isEqualTo("PREVIEW_READY");
            assertThatThrownBy(() -> connection.createStatement().execute("UPDATE transaction_import_batches SET warning_count = 1 WHERE id = '" + BATCH_ID + "'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private String queryString(Connection connection, String sql) throws Exception {
        try (var resultSet = connection.createStatement().executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private String appliedVersion(Connection connection) throws Exception {
        return queryString(connection, "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
