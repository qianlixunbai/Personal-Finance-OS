package com.financeos.integration;

import com.financeos.FinanceOsApplication;
import com.financeos.common.BusinessException;
import com.financeos.module.importing.dto.TransactionImportConfirmRequest;
import com.financeos.module.importing.dto.TransactionImportReceipt;
import com.financeos.module.importing.service.TransactionImportConfirmService;
import com.financeos.module.importing.service.TransactionImportPreviewCleanupScheduler;
import com.financeos.module.importing.service.TransactionImportPreviewCleanupService;
import com.financeos.module.importing.storage.LocalTemporaryImportFileStorage;
import com.financeos.module.importing.storage.TrustedImportStorageRoot;
import com.zaxxer.hikari.HikariDataSource;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
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

    // Each operation opens/closes its own connection; this is also the offline migration observer.
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        assertNoOtherDatabaseConnections();
        flyway(null).clean();
    }

    @Test
    void forwardFixesV16DigestsForEveryJavaInstantCanonicalPrecisionAndRestoresGetAndReplay() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword()));
        List<String> beforeRepair;
        List<String> oldBindings;
        try (RuntimeContext legacy = startCurrentContext()) {
            oldBindings = storageBindingSnapshot();
            // Startup cleanup has returned. No scheduler or external caller can enter this window.
            flyway(null).clean();
            flyway(MigrationVersion.fromVersion("15")).migrate();
            assertThat(appliedVersion()).isEqualTo("15");
            insertRepresentativeV15Data();
            assertV15Fixtures();
            List<String> v15 = historicalSnapshot(false);

            flyway(MigrationVersion.fromVersion("16")).migrate();
            assertThat(appliedVersion()).isEqualTo("16");
            assertThat(historicalSnapshot(false)).isEqualTo(v15);
            assertV16Backfill();
            // V16's ordinary SQL string preserves doubled backslashes: milliseconds stay at six digits.
            assertThat(jdbcTemplate.queryForObject("SHOW standard_conforming_strings", String.class)).isEqualTo("on");
            assertThat(storedDigest(1)).isEqualTo(sha256(sessionId(1) + "|" + batchId(1)
                    + "|2026-08-01T00:01:00.123000Z|" + references(1) + "|" + impacts()));
            assertThat(storedDigest(1)).isNotEqualTo(canonicalDigest(1));
            assertThat(storedDigest(3)).isEqualTo(canonicalDigest(3));
            // Preserve the original separately injected trimmed-timestamp corruption negative case.
            injectLegacyTrimmedDigestForTrailingZeroFixture();
            assertThat(storedDigest(3)).isNotEqualTo(canonicalDigest(3));
            beforeRepair = historicalSnapshot(true);
            List<String> negativeSnapshot = receiptSnapshot();

            // The only runtime operation permitted on V16: Batch / Item / Impact reads.
            assertThatThrownBy(() -> legacy.confirmService().getReceipt(1L, batchId(3)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(500))
                    .hasMessage("IMPORT_CONFIRM_INCONSISTENT");
            assertThat(receiptSnapshot()).isEqualTo(negativeSnapshot);
            legacy.assertQuiescent();
        }
        assertNoOtherDatabaseConnections();

        var repair = flyway(MigrationVersion.fromVersion("17")).migrate();
        assertThat(repair.migrationsExecuted).isEqualTo(1);
        assertThat(appliedVersion()).isEqualTo("17");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '17' AND success", Integer.class)).isEqualTo(1);
        assertThat(historicalSnapshot(true)).isEqualTo(beforeRepair);
        for (int index = 0; index < TIMESTAMPS.size(); index++) {
            assertThat(storedDigest(index)).isEqualTo(canonicalDigest(index));
        }
        List<String> repairedReceipts = receiptSnapshot();
        List<String> financialBeforeLatest = legacyFinancialSnapshot();
        flyway(null).migrate();
        assertThat(appliedVersion()).isEqualTo("21");
        assertThat(receiptSnapshot()).isEqualTo(repairedReceipts);
        // V18 adds two nullable columns to transactions; compare the pre-existing fields.
        assertThat(legacyFinancialSnapshot()).isEqualTo(financialBeforeLatest);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transactions WHERE idempotency_key IS NOT NULL OR request_hash IS NOT NULL", Integer.class)).isZero();
        assertThat(storageBindingSnapshot()).isEmpty();

        try (RuntimeContext current = startCurrentContext()) {
            assertThat(storageBindingSnapshot()).doesNotContainAnyElementsOf(oldBindings);
            var confirmService = current.confirmService();
            List<String> financialBeforeReplay = financialSnapshot();
            List<String> receiptsBeforeReplay = receiptSnapshot();
            for (int index = 0; index < TIMESTAMPS.size(); index++) {
                TimestampFixture fixture = TIMESTAMPS.get(index);
                UUID batchId = batchId(index);
                assertThat(Instant.parse(fixture.value()).toString()).isEqualTo(fixture.value());

                var receipt = confirmService.getReceipt(1L, batchId);
                var replay = confirmService.confirm(1L, sessionId(index), "legacy-import-key-" + index,
                        new TransactionImportConfirmRequest("legacy-replay-token", List.of()));

                assertThat(receipt.confirmedAt()).isEqualTo(Instant.parse(fixture.value()));
                assertThat(receipt.importSessionId()).isEqualTo(sessionId(index));
                assertThat(receipt.importBatchId()).isEqualTo(batchId);
                assertThat(receipt.transactions()).isEqualTo(references(index));
                assertThat(receipt.accountImpacts()).isEqualTo(impacts());
                assertThat(receipt.totalRows()).isEqualTo(1);
                assertThat(receipt.createdCount()).isEqualTo(1);
                assertThat(receipt.resultDigest()).isEqualTo(canonicalDigest(index));
                assertThat(replay.idempotentReplay()).isTrue();
                assertThat(replay.receipt()).isEqualTo(receipt);
                assertThat(storedDigest(index)).isEqualTo(receipt.resultDigest());
                assertThat(financialSnapshot()).isEqualTo(financialBeforeReplay);
                assertThat(receiptSnapshot()).isEqualTo(receiptsBeforeReplay);
            }
            current.assertQuiescent();
        }
        assertNoOtherDatabaseConnections();
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

    private List<TransactionImportReceipt.TransactionReference> references(int index) {
        return List.of(new TransactionImportReceipt.TransactionReference(1, index + 1L));
    }

    private List<TransactionImportReceipt.AccountImpact> impacts() {
        // Historical V16 backfill uses the final account balance for every batch, not a running balance.
        return List.of(new TransactionImportReceipt.AccountImpact(1L, 1,
                new BigDecimal("-50.00"), new BigDecimal("-10.00"), new BigDecimal("-60.00")));
    }

    private String canonicalDigest(int index) {
        return sha256(sessionId(index) + "|" + batchId(index) + "|" + Instant.parse(TIMESTAMPS.get(index).value())
                + "|" + references(index) + "|" + impacts());
    }

    private String storedDigest(int index) {
        return jdbcTemplate.queryForObject("SELECT result_digest FROM transaction_import_batches WHERE id = ?",
                String.class, batchId(index));
    }

    private void assertV15Fixtures() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo("-60.00");
        for (int index = 0; index < TIMESTAMPS.size(); index++) {
            Instant confirmedAt = jdbcTemplate.queryForObject("SELECT confirmed_at FROM transaction_import_batches WHERE id = ?",
                    (row, number) -> row.getTimestamp(1).toInstant(), batchId(index));
            assertThat(confirmedAt).isEqualTo(Instant.parse(TIMESTAMPS.get(index).value()));
            assertThat(jdbcTemplate.queryForObject("SELECT preallocated_batch_id FROM transaction_import_sessions WHERE id = ?",
                    UUID.class, sessionId(index))).isEqualTo(batchId(index));
        }
    }

    private void assertV16Backfill() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batch_account_impacts", Integer.class)).isEqualTo(6);
        for (int index = 0; index < TIMESTAMPS.size(); index++) {
            assertThat(jdbcTemplate.queryForObject("SELECT session_id FROM transaction_import_batches WHERE id = ?",
                    UUID.class, batchId(index))).isEqualTo(sessionId(index));
            assertThat(storedDigest(index)).matches("[0-9a-f]{64}");
            assertThat(jdbcTemplate.query("""
                    SELECT account_id, row_count, balance_before, delta, balance_after
                    FROM transaction_import_batch_account_impacts WHERE batch_id = ? ORDER BY account_id
                    """, (row, number) -> new TransactionImportReceipt.AccountImpact(row.getLong(1), row.getInt(2),
                    row.getBigDecimal(3), row.getBigDecimal(4), row.getBigDecimal(5)), batchId(index)))
                    .isEqualTo(impacts());
        }
    }

    private String snapshotTable(String table, String omittedFields) {
        return jdbcTemplate.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(row_value)" + omittedFields
                + " ORDER BY row_value.id), '[]'::jsonb)::text FROM " + table + " row_value", String.class);
    }

    private List<String> financialSnapshot() {
        return List.of(snapshotTable("accounts", ""), snapshotTable("transactions", ""));
    }

    private List<String> legacyFinancialSnapshot() {
        return List.of(snapshotTable("accounts", ""), snapshotTable("transactions", " - 'idempotency_key' - 'request_hash'"));
    }

    private List<String> receiptSnapshot() {
        return List.of(snapshotTable("transaction_import_sessions", ""), snapshotTable("transaction_import_batches", ""),
                snapshotTable("transaction_import_items", ""), snapshotTable("transaction_import_batch_account_impacts", ""));
    }

    private List<String> historicalSnapshot(boolean includeImpacts) {
        var snapshot = new java.util.ArrayList<>(financialSnapshot());
        snapshot.add(snapshotTable("users", ""));
        snapshot.add(snapshotTable("categories", ""));
        snapshot.add(snapshotTable("transaction_import_sessions", ""));
        snapshot.add(snapshotTable("transaction_import_batches", includeImpacts ? " - 'result_digest'" : " - 'session_id' - 'result_digest'"));
        snapshot.add(snapshotTable("transaction_import_items", ""));
        if (includeImpacts) snapshot.add(snapshotTable("transaction_import_batch_account_impacts", ""));
        return List.copyOf(snapshot);
    }

    private List<String> storageBindingSnapshot() {
        return jdbcTemplate.queryForList("""
                SELECT storage_id::text FROM transaction_import_storage_bindings
                WHERE NOT quarantined ORDER BY kind
                """, String.class);
    }

    private void assertNoOtherDatabaseConnections() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid()
                """, Integer.class)).isZero();
    }

    private RuntimeContext startCurrentContext() {
        AtomicInteger readyEvents = new AtomicInteger();
        SpringApplication application = new SpringApplication(FinanceOsApplication.class);
        application.setAdditionalProfiles("test");
        application.addListeners(event -> {
            if (event instanceof ApplicationReadyEvent) readyEvents.incrementAndGet();
        });
        var context = application.run(
                "--server.port=0", "--spring.datasource.url=" + POSTGRESQL.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRESQL.getUsername(),
                "--spring.datasource.password=" + POSTGRESQL.getPassword(),
                "--spring.datasource.hikari.minimum-idle=0", "--spring.datasource.hikari.maximum-pool-size=2");
        RuntimeContext runtime = null;
        try {
            runtime = new RuntimeContext(context, readyEvents);
            assertThat(context.isActive()).isTrue();
            assertThat(appliedVersion()).isEqualTo("21");
            assertThat(storageBindingSnapshot()).hasSize(2);
            runtime.assertQuiescent();
            return runtime;
        } catch (RuntimeException | AssertionError exception) {
            if (runtime == null) context.close();
            else runtime.close();
            throw exception;
        }
    }

    private record StorageResources(TrustedImportStorageRoot root, ThreadPoolExecutor workers, FileChannel control) {
        static StorageResources capture(LocalTemporaryImportFileStorage storage) {
            var root = (TrustedImportStorageRoot) ReflectionTestUtils.getField(storage, "root");
            Object state = ReflectionTestUtils.getField(root, "context");
            return new StorageResources(root, (ThreadPoolExecutor) ReflectionTestUtils.getField(state, "workers"),
                    (FileChannel) ReflectionTestUtils.getField(state, "control"));
        }
    }

    private class RuntimeContext implements AutoCloseable {
        private final ConfigurableApplicationContext context;
        private final AtomicInteger readyEvents;
        private final HikariDataSource pool;
        private final TransactionImportPreviewCleanupService cleanup;
        private final List<StorageResources> storage;

        RuntimeContext(ConfigurableApplicationContext context, AtomicInteger readyEvents) {
            this.context = context;
            this.readyEvents = readyEvents;
            this.pool = context.getBean(HikariDataSource.class);
            this.cleanup = context.getBean(TransactionImportPreviewCleanupService.class);
            this.storage = context.getBeansOfType(LocalTemporaryImportFileStorage.class).values().stream()
                    .map(StorageResources::capture).toList();
            assertThat(storage).hasSize(2);
        }

        TransactionImportConfirmService confirmService() {
            return context.getBean(TransactionImportConfirmService.class);
        }

        void assertQuiescent() {
            // Default event multicaster is synchronous: run() returns after startup cleanup.
            assertThat(readyEvents.get()).isEqualTo(1);
            assertThat(context.getBeansOfType(TransactionImportPreviewCleanupScheduler.class)).isEmpty();
            assertThat(context.containsBean("transactionImportPreviewCleanupTaskScheduler")).isFalse();
            var lock = (ReentrantReadWriteLock) ReflectionTestUtils.getField(cleanup, "cleanupLifecycleLock");
            assertThat(lock.getReadLockCount()).isZero();
            assertThat(lock.isWriteLocked()).isFalse();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            for (StorageResources resource : storage) {
                assertThat(resource.workers().getActiveCount()).isZero();
                assertThat(resource.workers().getQueue()).isEmpty();
            }
        }

        @Override
        public void close() {
            Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            ListAppender<ILoggingEvent> shutdownLogs = new ListAppender<>();
            shutdownLogs.start();
            logger.addAppender(shutdownLogs);
            try {
                context.close();
            } finally {
                logger.detachAppender(shutdownLogs);
                shutdownLogs.stop();
            }
            assertThat(shutdownLogs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList()).isEmpty();
            assertThat(context.isActive()).isFalse();
            assertThat(pool.isClosed()).isTrue();
            for (StorageResources resource : storage) {
                assertThat(ReflectionTestUtils.getField(resource.root(), "closed")).isEqualTo(true);
                assertThat(resource.workers().isTerminated()).isTrue();
                assertThat(resource.control().isOpen()).isFalse();
            }
            assertThat(readyEvents.get()).isEqualTo(1);
            assertNoOtherDatabaseConnections();
        }
    }
}
