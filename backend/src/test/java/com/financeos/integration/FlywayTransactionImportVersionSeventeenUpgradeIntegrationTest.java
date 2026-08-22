package com.financeos.integration;

import com.financeos.FinanceOsApplication;
import com.financeos.common.BusinessException;
import com.financeos.module.importing.dto.TransactionImportConfirmRequest;
import com.financeos.module.importing.service.TransactionImportConfirmService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = FinanceOsApplication.class)
@ActiveProfiles("test")
@Testcontainers
@Import(FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.LegacyV15FlywayConfiguration.class)
class FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest {
    private static final List<TimestampFixture> TIMESTAMPS = List.of(
            new TimestampFixture("2026-08-01T00:01:00Z"),
            new TimestampFixture("2026-08-01T00:01:00.123Z"),
            new TimestampFixture("2026-08-01T00:01:00.123456Z"),
            new TimestampFixture("2026-08-01T00:01:00.123400Z"),
            new TimestampFixture("2026-08-01T00:01:00.123450Z"),
            new TimestampFixture("2026-08-01T00:01:00.000001Z")
    );

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionImportConfirmService confirmService;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRESQL::getDriverClassName);
    }

    @AfterEach
    void cleanDatabase() {
        flyway(null).clean();
    }

    @Test
    void forwardFixesV16DigestsForEveryJavaInstantCanonicalPrecisionAndRestoresGetAndReplay() {
        insertRepresentativeV15Data();

        flyway(MigrationVersion.fromVersion("16")).migrate();
        injectLegacyTrimmedDigestForTrailingZeroFixture();

        assertThatThrownBy(() -> confirmService.getReceipt(1L, batchId(3)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("IMPORT_CONFIRM_INCONSISTENT");

        flyway(null).migrate();

        assertThat(appliedVersion()).isEqualTo("17");
        for (int index = 0; index < TIMESTAMPS.size(); index++) {
            TimestampFixture fixture = TIMESTAMPS.get(index);
            UUID batchId = batchId(index);
            assertThat(Instant.parse(fixture.value()).toString()).isEqualTo(fixture.value());

            var receipt = confirmService.getReceipt(1L, batchId);
            var replay = confirmService.confirm(1L, sessionId(index), "legacy-import-key-" + index,
                    new TransactionImportConfirmRequest("legacy-replay-token", List.of()));

            assertThat(receipt.confirmedAt()).isEqualTo(Instant.parse(fixture.value()));
            assertThat(replay.idempotentReplay()).isTrue();
            assertThat(replay.receipt()).isEqualTo(receipt);
            assertThat(jdbcTemplate.queryForObject("SELECT result_digest FROM transaction_import_batches WHERE id = ?", String.class, batchId))
                    .isEqualTo(receipt.resultDigest());
        }
    }

    private void insertRepresentativeV15Data() {
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash) VALUES ('v15-v17-import', 'v15-v17-import@example.com', 'hash')");
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance, status) VALUES (1, 'Cash', 'CASH', 'CNY', -60.00, 'ACTIVE')");
        jdbcTemplate.update("INSERT INTO categories (user_id, name, type, is_system) VALUES (1, 'Food', 'EXPENSE', false)");
        for (int index = 0; index < TIMESTAMPS.size(); index++) {
            String digest = String.valueOf((char) ('a' + index)).repeat(64);
            String confirmedAt = TIMESTAMPS.get(index).value();
            UUID sessionId = sessionId(index);
            UUID batchId = batchId(index);
            jdbcTemplate.update("""
                    INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, description, transacted_at)
                    VALUES (1, 1, 1, 'EXPENSE', 10.00, 'CNY', ?, CAST(? AS timestamp))
                    """, "legacy import " + index, confirmedAt);
            jdbcTemplate.update("""
                    INSERT INTO transaction_import_sessions
                    (id, user_id, preallocated_batch_id, status, revision, original_file_name, content_type, file_size, file_digest,
                     temporary_storage_reference, mapping_digest, options_digest, normalized_rows_digest, plan_storage_reference,
                     expires_at, consumed_at, cancelled_at)
                    VALUES (CAST(? AS uuid), 1, CAST(? AS uuid), 'CONSUMED', 1, 'legacy-%d.csv', 'text/csv', 10, ?, NULL, ?, ?, ?, NULL,
                            CAST(? AS timestamptz), CAST(? AS timestamptz), NULL)
                    """.formatted(index), sessionId.toString(), batchId.toString(), digest, digest, digest, digest,
                    Instant.parse(confirmedAt).plusSeconds(3600).toString(), confirmedAt);
            jdbcTemplate.update("""
                    INSERT INTO transaction_import_batches
                    (id, user_id, original_file_name, file_digest, mapping_digest, options_digest, normalized_rows_digest,
                     contract_version, idempotency_key, request_hash, status, total_rows, warning_count, confirmed_at)
                    VALUES (CAST(? AS uuid), 1, 'legacy-%d.csv', ?, ?, ?, ?, 'TRANSACTION_IMPORT_CONFIRM_V1', ?, ?, 'CONFIRMED', 1, 0, CAST(? AS timestamptz))
                    """.formatted(index), batchId.toString(), digest, digest, digest, digest, "legacy-import-key-" + index,
                    requestHash(sessionId, batchId, digest), confirmedAt);
            jdbcTemplate.update("""
                    INSERT INTO transaction_import_items (user_id, batch_id, source_row_number, canonical_row_fingerprint, created_transaction_id)
                    VALUES (1, CAST(? AS uuid), 1, ?, ?)
                    """, batchId.toString(), digest, index + 1L);
        }
    }

    private void injectLegacyTrimmedDigestForTrailingZeroFixture() {
        jdbcTemplate.execute("ALTER TABLE transaction_import_batches DISABLE TRIGGER trg_transaction_import_batches_immutable");
        try {
            jdbcTemplate.update("""
                    WITH receipt_input AS (
                        SELECT batch.id,
                               batch.session_id::text || '|' || batch.id::text || '|'
                               || regexp_replace(
                                   to_char(batch.confirmed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                                    E'(\\.\\d*?[1-9])0*Z$', E'\\1Z')
                               || '|[TransactionReference[rowNumber=1, transactionId=4]]|'
                               || '[AccountImpact[accountId=1, rowCount=1, balanceBefore=-50.00, delta=-10.00, balanceAfter=-60.00]]' AS value
                        FROM transaction_import_batches batch
                        WHERE batch.id = CAST(? AS uuid)
                    )
                    UPDATE transaction_import_batches batch
                    SET result_digest = encode(digest(receipt_input.value, 'sha256'), 'hex')
                    FROM receipt_input
                    WHERE batch.id = receipt_input.id
                    """, batchId(3).toString());
        } finally {
            jdbcTemplate.execute("ALTER TABLE transaction_import_batches ENABLE TRIGGER trg_transaction_import_batches_immutable");
        }
    }

    private String requestHash(UUID sessionId, UUID batchId, String digest) {
        return sha256(String.join("|", "TRANSACTION_IMPORT_CONFIRM", "TRANSACTION_IMPORT_CONFIRM_V1", "1", sessionId.toString(),
                batchId.toString(), "1", digest, digest, digest, digest, ""));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String appliedVersion() {
        return jdbcTemplate.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success = true AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private UUID sessionId(int index) {
        return UUID.fromString("00000000-0000-0000-0000-000000000" + (101 + index));
    }

    private UUID batchId(int index) {
        return UUID.fromString("00000000-0000-0000-0000-000000000" + (201 + index));
    }

    private record TimestampFixture(String value) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class LegacyV15FlywayConfiguration {
        @Bean
        FlywayMigrationStrategy migrateOnlyThroughV15() {
            return flyway -> Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("15")).load().migrate();
        }
    }
}
