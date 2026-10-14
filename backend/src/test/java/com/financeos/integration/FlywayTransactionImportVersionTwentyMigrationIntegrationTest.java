package com.financeos.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FlywayTransactionImportVersionTwentyMigrationIntegrationTest {
    private static final long MIGRATION_PAUSE_LOCK = 91_021_020_261L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @AfterEach
    void cleanDatabase() {
        flyway(null).clean();
    }

    @Test
    void upgradesV19WithoutChangingHistoricalReceiptsAndEnforcesTheNewLifecycleBoundary() throws Exception {
        flyway(MigrationVersion.fromVersion("19")).migrate();
        ReceiptFixture fixture;
        ReceiptSnapshot before;
        try (Connection connection = connection()) {
            fixture = seedReceipt(connection);
            before = receiptSnapshot(connection);
        }

        flyway(MigrationVersion.fromVersion("20")).migrate();

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("20");
            assertThat(receiptSnapshot(connection)).isEqualTo(before);
            assertV20Schema(connection);
            assertInsertValidation(connection, fixture);
            assertImmutableReceiptTables(connection, fixture);

            int deleted = executeUpdate(connection, "DELETE FROM transactions WHERE user_id = ? AND id = ?",
                    fixture.userId(), fixture.originalTransactionId());
            assertThat(deleted).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM transactions WHERE id = ?", fixture.originalTransactionId())).isZero();
            assertThat(count(connection, "SELECT count(*) FROM transaction_import_items WHERE batch_id = ? AND created_transaction_id = ?",
                    fixture.batchId(), fixture.originalTransactionId())).isEqualTo(1);
            assertThat(receiptSnapshot(connection)).isEqualTo(before);
        }
    }

    @Test
    void freshV1MigrationAppliesThroughV20WithTheExpectedReceiptConstraintsAndTriggers() throws Exception {
        flyway(MigrationVersion.fromVersion("20")).migrate();

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("20");
            assertV20Schema(connection);
            assertThat(count(connection, "SELECT count(*) FROM transaction_import_items")).isZero();
        }
    }

    @Test
    void rejectsAnInvalidHistoricalReferenceBeforeChangingTheV19Schema() throws Exception {
        flyway(MigrationVersion.fromVersion("19")).migrate();
        ReceiptFixture fixture;
        try (Connection connection = connection()) {
            fixture = seedReceipt(connection);
            execute(connection, "ALTER TABLE transaction_import_items DROP CONSTRAINT fk_transaction_import_items_owned_transaction");
            insertItem(connection, fixture.userId(), fixture.batchId(), 2, fixture.originalTransactionId() + 9_000_000L);
            execute(connection, """
                    ALTER TABLE transaction_import_items
                    ADD CONSTRAINT fk_transaction_import_items_owned_transaction
                    FOREIGN KEY (user_id, created_transaction_id)
                    REFERENCES transactions (user_id, id) NOT VALID
                    """);
            assertThat(count(connection, "SELECT count(*) FROM transaction_import_items WHERE batch_id = ? AND created_transaction_id = ?",
                    fixture.batchId(), fixture.originalTransactionId() + 9_000_000L)).isEqualTo(1);
        }

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(Exception.class)
                .satisfies(exception -> assertThat(rootCause((Throwable) exception).getMessage())
                        .contains("cannot decouple transaction import receipt with an invalid historical transaction reference"));

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("19");
            assertThat(count(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = '20'")).isZero();
            assertThat(count(connection, "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_transaction_import_items_validate_transaction_reference' AND NOT tgisinternal"))
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM pg_proc WHERE proname = 'validate_transaction_import_item_transaction_reference'"))
                    .isZero();
            assertThat(count(connection, """
                    SELECT count(*) FROM pg_constraint
                    WHERE conrelid = 'transaction_import_items'::regclass
                      AND conname = 'fk_transaction_import_items_owned_transaction'
                      AND NOT convalidated
                    """)).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM transaction_import_items WHERE batch_id = ?", fixture.batchId())).isEqualTo(2);
        }
    }

    @Test
    void rollsBackFunctionTriggerAndConstraintChangesWhenTheFinalDropFails() throws Exception {
        flyway(MigrationVersion.fromVersion("19")).migrate();
        ReceiptFixture fixture;
        ReceiptSnapshot before;
        try (Connection connection = connection()) {
            fixture = seedReceipt(connection);
            before = receiptSnapshot(connection);
            execute(connection, """
                    ALTER TABLE transaction_import_items
                    RENAME CONSTRAINT fk_transaction_import_items_owned_transaction
                    TO fk_transaction_import_items_owned_transaction_test_renamed
                    """);
        }

        assertThatThrownBy(() -> flyway(null).migrate())
                .isInstanceOf(Exception.class)
                .satisfies(exception -> assertThat(rootCause((Throwable) exception).getMessage())
                        .contains("fk_transaction_import_items_owned_transaction")
                        .contains("does not exist"));

        try (Connection connection = connection()) {
            assertThat(appliedVersion(connection)).isEqualTo("19");
            assertThat(count(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = '20'")).isZero();
            assertThat(receiptSnapshot(connection)).isEqualTo(before);
            assertThat(count(connection, "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_transaction_import_items_validate_transaction_reference' AND NOT tgisinternal"))
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM pg_proc WHERE proname = 'validate_transaction_import_item_transaction_reference'"))
                    .isZero();
            assertThat(count(connection, """
                    SELECT count(*) FROM pg_constraint
                    WHERE conrelid = 'transaction_import_items'::regclass
                      AND conname = 'fk_transaction_import_items_owned_transaction_test_renamed'
                    """)).isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM transaction_import_items WHERE batch_id = ?", fixture.batchId())).isEqualTo(1);
        }
    }

    @Test
    void migrationHoldsBothTableLocksAcrossTheTriggerToFkCutoverAndQueuesConcurrentWrites() throws Exception {
        flyway(MigrationVersion.fromVersion("19")).migrate();
        ReceiptFixture fixture;
        try (Connection connection = connection()) {
            fixture = seedReceipt(connection);
        }

        Connection pauseGate = connection();
        pauseGate.setAutoCommit(true);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        Future<?> migration = null;
        PendingSql pendingInsert = null;
        PendingSql pendingDelete = null;
        boolean eventTriggerInstalled = false;
        try {
            execute(pauseGate, "SELECT pg_advisory_lock(" + MIGRATION_PAUSE_LOCK + "::bigint)");
            installMigrationPauseEventTrigger(pauseGate);
            eventTriggerInstalled = true;

            migration = executor.submit(() -> flyway(MigrationVersion.fromVersion("20")).migrate());
            int migrationPid = awaitMigrationLock();
            int gatePid = scalarInt(pauseGate, "SELECT pg_backend_pid()");
            awaitCondition(() -> blockedBy(migrationPid, gatePid), "V20 migration paused after creating its INSERT trigger");
            assertThat(databaseCount("""
                    SELECT count(*) FROM pg_constraint
                    WHERE conrelid = 'transaction_import_items'::regclass
                      AND conname = 'fk_transaction_import_items_owned_transaction'
                    """)).isEqualTo(1);

            pendingInsert = submitSql(executor, "c1-v20-migration-insert", connection -> {
                insertItem(connection, fixture.userId(), fixture.batchId(), 2, fixture.otherOwnerTransactionId());
                return 1;
            });
            pendingDelete = submitSql(executor, "c1-v20-migration-delete", connection -> executeUpdate(connection,
                    "DELETE FROM transactions WHERE user_id = ? AND id = ?", fixture.userId(), fixture.originalTransactionId()));

            int insertPid = pendingInsert.backendPid().get(5, TimeUnit.SECONDS);
            int deletePid = pendingDelete.backendPid().get(5, TimeUnit.SECONDS);
            awaitCondition(() -> blockedBy(insertPid, migrationPid), "concurrent INSERT blocked by V20 table lock");
            awaitCondition(() -> blockedBy(deletePid, migrationPid), "concurrent DELETE blocked by V20 table lock");
            assertThat(migration.isDone()).isFalse();
            assertThat(pendingInsert.outcome().isDone()).isFalse();
            assertThat(pendingDelete.outcome().isDone()).isFalse();

            execute(pauseGate, "SELECT pg_advisory_unlock(" + MIGRATION_PAUSE_LOCK + "::bigint)");
            migration.get(10, TimeUnit.SECONDS);

            SqlOutcome insertOutcome = pendingInsert.outcome().get(10, TimeUnit.SECONDS);
            SqlOutcome deleteOutcome = pendingDelete.outcome().get(10, TimeUnit.SECONDS);
            assertThat(insertOutcome.succeeded()).isFalse();
            assertThat(insertOutcome.sqlState()).isEqualTo("23503");
            assertThat(deleteOutcome.succeeded()).isTrue();
            assertThat(deleteOutcome.updateCount()).isEqualTo(1);
            try (Connection connection = connection()) {
                assertThat(appliedVersion(connection)).isEqualTo("20");
                assertThat(count(connection, "SELECT count(*) FROM transactions WHERE id = ?", fixture.originalTransactionId())).isZero();
                assertThat(count(connection, "SELECT count(*) FROM transaction_import_items WHERE batch_id = ? AND created_transaction_id = ?",
                        fixture.batchId(), fixture.originalTransactionId())).isEqualTo(1);
            }
        } finally {
            try {
                execute(pauseGate, "SELECT pg_advisory_unlock(" + MIGRATION_PAUSE_LOCK + "::bigint)");
            } catch (SQLException ignored) {
                // The migration may already have released the gate through connection teardown.
            }
            if (migration != null && !migration.isDone()) {
                migration.cancel(true);
            }
            if (eventTriggerInstalled) {
                dropMigrationPauseEventTrigger(pauseGate);
            }
            pauseGate.close();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void serializesInsertAndDeleteInEitherRowLockOrder() throws Exception {
        flyway(null).migrate();
        ReceiptFixture fixture;
        try (Connection connection = connection()) {
            fixture = seedReceipt(connection);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            long insertFirstTransaction;
            try (Connection connection = connection()) {
                insertFirstTransaction = insertTransaction(connection, fixture.userId(), fixture.accountId(), fixture.categoryId(),
                        "EXPENSE", "20.00", "insert-first");
            }
            try (Connection insertConnection = connection()) {
                insertConnection.setAutoCommit(false);
                int insertPid = scalarInt(insertConnection, "SELECT pg_backend_pid()");
                insertItem(insertConnection, fixture.userId(), fixture.batchId(), 2, insertFirstTransaction);

                PendingSql delete = submitSql(executor, "c1-v20-delete-after-insert", connection -> executeUpdate(connection,
                        "DELETE FROM transactions WHERE user_id = ? AND id = ?", fixture.userId(), insertFirstTransaction));
                int deletePid = delete.backendPid().get(5, TimeUnit.SECONDS);
                awaitCondition(() -> blockedBy(deletePid, insertPid), "DELETE waits for the successful INSERT key-share lock");
                assertThat(delete.outcome().isDone()).isFalse();

                insertConnection.commit();
                SqlOutcome deleteOutcome = delete.outcome().get(5, TimeUnit.SECONDS);
                assertThat(deleteOutcome.succeeded()).isTrue();
                assertThat(deleteOutcome.updateCount()).isEqualTo(1);
            }
            assertThat(databaseCount("SELECT count(*) FROM transaction_import_items WHERE batch_id = ? AND source_row_number = 2 AND created_transaction_id = ?",
                    fixture.batchId(), insertFirstTransaction)).isEqualTo(1);
            assertThat(databaseCount("SELECT count(*) FROM transactions WHERE id = ?", insertFirstTransaction)).isZero();

            long deleteFirstTransaction;
            try (Connection connection = connection()) {
                deleteFirstTransaction = insertTransaction(connection, fixture.userId(), fixture.accountId(), fixture.categoryId(),
                        "EXPENSE", "30.00", "delete-first");
            }
            try (Connection deleteConnection = connection()) {
                deleteConnection.setAutoCommit(false);
                int deletePid = scalarInt(deleteConnection, "SELECT pg_backend_pid()");
                assertThat(executeUpdate(deleteConnection, "DELETE FROM transactions WHERE user_id = ? AND id = ?",
                        fixture.userId(), deleteFirstTransaction)).isEqualTo(1);

                PendingSql insert = submitSql(executor, "c1-v20-insert-after-delete", connection -> {
                    insertItem(connection, fixture.userId(), fixture.batchId(), 3, deleteFirstTransaction);
                    return 1;
                });
                int insertPid = insert.backendPid().get(5, TimeUnit.SECONDS);
                awaitCondition(() -> blockedBy(insertPid, deletePid), "INSERT key-share lookup waits for the in-flight DELETE");
                assertThat(insert.outcome().isDone()).isFalse();

                deleteConnection.commit();
                SqlOutcome insertOutcome = insert.outcome().get(5, TimeUnit.SECONDS);
                assertThat(insertOutcome.succeeded()).isFalse();
                assertThat(insertOutcome.sqlState()).isEqualTo("23503");
            }
            assertThat(databaseCount("SELECT count(*) FROM transaction_import_items WHERE batch_id = ? AND source_row_number = 3",
                    fixture.batchId())).isZero();
            assertThat(databaseCount("SELECT count(*) FROM transactions WHERE id = ?", deleteFirstTransaction)).isZero();

            long rollbackFirstTransaction;
            try (Connection connection = connection()) {
                rollbackFirstTransaction = insertTransaction(connection, fixture.userId(), fixture.accountId(), fixture.categoryId(),
                        "EXPENSE", "40.00", "delete-rollback-first");
            }
            try (Connection deleteConnection = connection()) {
                deleteConnection.setAutoCommit(false);
                int deletePid = scalarInt(deleteConnection, "SELECT pg_backend_pid()");
                assertThat(executeUpdate(deleteConnection, "DELETE FROM transactions WHERE user_id = ? AND id = ?",
                        fixture.userId(), rollbackFirstTransaction)).isEqualTo(1);

                PendingSql insert = submitSql(executor, "c1-v20-insert-after-delete-rollback", connection -> {
                    insertItem(connection, fixture.userId(), fixture.batchId(), 4, rollbackFirstTransaction);
                    return 1;
                });
                int insertPid = insert.backendPid().get(5, TimeUnit.SECONDS);
                awaitCondition(() -> blockedBy(insertPid, deletePid), "INSERT key-share lookup waits for the in-flight DELETE");
                assertThat(insert.outcome().isDone()).isFalse();

                deleteConnection.rollback();
                SqlOutcome insertOutcome = insert.outcome().get(5, TimeUnit.SECONDS);
                assertThat(insertOutcome.succeeded()).isTrue();
            }
            assertThat(databaseCount("SELECT count(*) FROM transaction_import_items WHERE batch_id = ? AND source_row_number = 4",
                    fixture.batchId())).isEqualTo(1);
            assertThat(databaseCount("SELECT count(*) FROM transactions WHERE id = ?", rollbackFirstTransaction)).isEqualTo(1);

            long repeatableReadTransaction;
            try (Connection connection = connection()) {
                repeatableReadTransaction = insertTransaction(connection, fixture.userId(), fixture.accountId(), fixture.categoryId(),
                        "EXPENSE", "50.00", "repeatable-read-delete-first");
            }
            try (Connection repeatableReadConnection = connection()) {
                repeatableReadConnection.setAutoCommit(false);
                repeatableReadConnection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                assertThat(count(repeatableReadConnection, "SELECT count(*) FROM transactions WHERE id = ?", repeatableReadTransaction))
                        .isEqualTo(1);

                try (Connection deleteConnection = connection()) {
                    assertThat(executeUpdate(deleteConnection, "DELETE FROM transactions WHERE user_id = ? AND id = ?",
                            fixture.userId(), repeatableReadTransaction)).isEqualTo(1);
                }

                assertThatThrownBy(() -> insertItem(repeatableReadConnection, fixture.userId(), fixture.batchId(), 5,
                        repeatableReadTransaction))
                        .isInstanceOf(SQLException.class)
                        .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isIn("40001", "23503"));
                repeatableReadConnection.rollback();
            }
            assertThat(databaseCount("SELECT count(*) FROM transaction_import_items WHERE batch_id = ? AND source_row_number = 5",
                    fixture.batchId())).isZero();
            assertThat(databaseCount("SELECT count(*) FROM transactions WHERE id = ?", repeatableReadTransaction)).isZero();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void assertV20Schema(Connection connection) throws SQLException {
        assertThat(appliedVersion(connection)).isEqualTo("20");
        assertThat(stringList(connection, """
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'transaction_import_items'::regclass AND contype = 'f'
                """))
                .contains("fk_transaction_import_items_owned_batch")
                .doesNotContain("fk_transaction_import_items_owned_transaction");
        assertThat(scalarString(connection, """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'transaction_import_items'
                  AND column_name = 'created_transaction_id'
                """))
                .isEqualTo("NO");
        assertThat(stringList(connection, """
                SELECT tgname || ':' || tgenabled::text FROM pg_trigger
                WHERE tgrelid = 'transaction_import_items'::regclass AND NOT tgisinternal
                """))
                .contains("trg_transaction_import_items_immutable:O",
                        "trg_transaction_import_items_validate_transaction_reference:O");
        assertThat(scalarString(connection, """
                SELECT pg_get_triggerdef(oid) FROM pg_trigger
                WHERE tgrelid = 'transaction_import_items'::regclass
                  AND tgname = 'trg_transaction_import_items_validate_transaction_reference'
                """))
                .contains("BEFORE INSERT")
                .contains("EXECUTE FUNCTION validate_transaction_import_item_transaction_reference()");
        assertThat(scalarString(connection, """
                SELECT pg_get_functiondef('validate_transaction_import_item_transaction_reference()'::regprocedure)
                """))
                .contains("FOR KEY SHARE")
                .contains("transaction.user_id = NEW.user_id")
                .contains("transaction.id = NEW.created_transaction_id");
    }

    private void assertInsertValidation(Connection connection, ReceiptFixture fixture) throws Exception {
        try (Connection insertConnection = connection()) {
            insertConnection.setAutoCommit(false);
            insertItem(insertConnection, fixture.userId(), fixture.batchId(), 2, fixture.originalTransactionId());
            insertConnection.rollback();
        }

        try (Connection sameTransaction = connection()) {
            sameTransaction.setAutoCommit(false);
            long transactionId = insertTransaction(sameTransaction, fixture.userId(), fixture.accountId(), fixture.categoryId(),
                    "EXPENSE", "12.00", "same-transaction-insert");
            insertItem(sameTransaction, fixture.userId(), fixture.batchId(), 2, transactionId);
            sameTransaction.rollback();
        }

        assertSqlState("23503", () -> insertItem(connection, fixture.userId(), fixture.batchId(), 2, Long.MAX_VALUE));
        assertSqlState("23503", () -> insertItem(connection, fixture.userId(), fixture.batchId(), 2, fixture.otherOwnerTransactionId()));
        assertSqlState("23503", () -> insertItem(connection, fixture.otherUserId(), fixture.batchId(), 2,
                fixture.otherOwnerTransactionId()));

        execute(connection, "CREATE TEMP TABLE transactions (id BIGINT, user_id BIGINT)");
        try {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO pg_temp.transactions (id, user_id) VALUES (?, ?)")) {
                statement.setLong(1, Long.MAX_VALUE);
                statement.setLong(2, fixture.userId());
                statement.executeUpdate();
            }
            assertSqlState("23503", () -> insertItem(connection, fixture.userId(), fixture.batchId(), 2, Long.MAX_VALUE));
        } finally {
            execute(connection, "DROP TABLE pg_temp.transactions");
        }
        assertThat(count(connection, "SELECT count(*) FROM transaction_import_items WHERE batch_id = ?", fixture.batchId())).isEqualTo(1);
    }

    private void assertImmutableReceiptTables(Connection connection, ReceiptFixture fixture) throws Exception {
        assertSqlState("23514", () -> executeUpdate(connection,
                "UPDATE transaction_import_batches SET warning_count = warning_count + 1 WHERE id = ?", fixture.batchId()));
        assertSqlState("23514", () -> executeUpdate(connection,
                "DELETE FROM transaction_import_batches WHERE id = ?", fixture.batchId()));
        assertSqlState("23514", () -> executeUpdate(connection,
                "UPDATE transaction_import_items SET warning_codes = 'mutated' WHERE batch_id = ?", fixture.batchId()));
        assertSqlState("23514", () -> executeUpdate(connection,
                "DELETE FROM transaction_import_items WHERE batch_id = ?", fixture.batchId()));
        assertSqlState("23514", () -> executeUpdate(connection,
                "UPDATE transaction_import_batch_account_impacts SET delta = delta + 1 WHERE batch_id = ?", fixture.batchId()));
        assertSqlState("23514", () -> executeUpdate(connection,
                "DELETE FROM transaction_import_batch_account_impacts WHERE batch_id = ?", fixture.batchId()));
        assertThat(count(connection, "SELECT count(*) FROM transaction_import_batches WHERE id = ?", fixture.batchId())).isEqualTo(1);
        assertThat(count(connection, "SELECT count(*) FROM transaction_import_items WHERE batch_id = ?", fixture.batchId())).isEqualTo(1);
        assertThat(count(connection, "SELECT count(*) FROM transaction_import_batch_account_impacts WHERE batch_id = ?", fixture.batchId()))
                .isEqualTo(1);
    }

    private ReceiptFixture seedReceipt(Connection connection) throws SQLException {
        long userId = insertUser(connection, "c1-owner-" + UUID.randomUUID());
        long otherUserId = insertUser(connection, "c1-other-" + UUID.randomUUID());
        long accountId = insertAccount(connection, userId, "owner-account");
        executeUpdate(connection, "UPDATE accounts SET balance = 90.00 WHERE id = ?", accountId);
        long categoryId = insertCategory(connection, userId, "owner-category");
        long otherAccountId = insertAccount(connection, otherUserId, "other-account");
        long otherCategoryId = insertCategory(connection, otherUserId, "other-category");
        long originalTransactionId = insertTransaction(connection, userId, accountId, categoryId, "EXPENSE", "10.00", "receipt-source");
        long otherOwnerTransactionId = insertTransaction(connection, otherUserId, otherAccountId, otherCategoryId,
                "EXPENSE", "11.00", "other-owner-source");
        UUID batchId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String fileDigest = "1".repeat(64);
        String mappingDigest = "2".repeat(64);
        String optionsDigest = "3".repeat(64);
        String rowsDigest = "4".repeat(64);
        String requestHash = "5".repeat(64);
        String resultDigest = receiptDigest(sessionId, batchId, originalTransactionId, accountId);

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transaction_import_sessions
                (id, user_id, preallocated_batch_id, status, revision, original_file_name, content_type, file_size,
                 file_digest, temporary_storage_reference, mapping_digest, options_digest, normalized_rows_digest,
                 plan_storage_reference, expires_at, consumed_at, cancelled_at)
                VALUES (?, ?, ?, 'CONSUMED', 1, 'c1.csv', 'text/csv', 128, ?, NULL, ?, ?, ?, NULL,
                        TIMESTAMPTZ '2026-10-01 00:00:00+00', TIMESTAMPTZ '2026-09-30 12:00:00+00', NULL)
                """)) {
            statement.setObject(1, sessionId);
            statement.setLong(2, userId);
            statement.setObject(3, batchId);
            statement.setString(4, fileDigest);
            statement.setString(5, mappingDigest);
            statement.setString(6, optionsDigest);
            statement.setString(7, rowsDigest);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transaction_import_batches
                (id, user_id, original_file_name, file_digest, mapping_digest, options_digest, normalized_rows_digest,
                 contract_version, idempotency_key, request_hash, status, total_rows, warning_count, confirmed_at,
                 session_id, result_digest)
                VALUES (?, ?, 'c1.csv', ?, ?, ?, ?, 'TRANSACTION_IMPORT_CONFIRM_V1', ?, ?, 'CONFIRMED', 1, 0,
                        TIMESTAMPTZ '2026-09-30 12:00:00+00', ?, ?)
                """)) {
            statement.setObject(1, batchId);
            statement.setLong(2, userId);
            statement.setString(3, fileDigest);
            statement.setString(4, mappingDigest);
            statement.setString(5, optionsDigest);
            statement.setString(6, rowsDigest);
            statement.setString(7, "c1-key-" + batchId);
            statement.setString(8, requestHash);
            statement.setObject(9, sessionId);
            statement.setString(10, resultDigest);
            statement.executeUpdate();
        }
        insertItem(connection, userId, batchId, 1, originalTransactionId);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transaction_import_batch_account_impacts
                (user_id, batch_id, account_id, row_count, balance_before, delta, balance_after)
                VALUES (?, ?, ?, 1, 100.00, -10.00, 90.00)
                """)) {
            statement.setLong(1, userId);
            statement.setObject(2, batchId);
            statement.setLong(3, accountId);
            statement.executeUpdate();
        }
        return new ReceiptFixture(userId, otherUserId, accountId, categoryId, batchId, originalTransactionId,
                otherOwnerTransactionId);
    }

    private long insertUser(Connection connection, String suffix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (username, email, password_hash)
                VALUES (?, ?, 'test-hash') RETURNING id
                """)) {
            statement.setString(1, "c1-" + suffix);
            statement.setString(2, "c1-" + suffix + "@example.test");
            return returningLong(statement);
        }
    }

    private long insertAccount(Connection connection, long userId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO accounts (user_id, name, type, currency, balance, status)
                VALUES (?, ?, 'CASH', 'CNY', 0.00, 'ACTIVE') RETURNING id
                """)) {
            statement.setLong(1, userId);
            statement.setString(2, name + "-" + UUID.randomUUID());
            return returningLong(statement);
        }
    }

    private String receiptDigest(UUID sessionId, UUID batchId, long transactionId, long accountId) {
        String receiptInput = sessionId + "|" + batchId + "|" + Instant.parse("2026-09-30T12:00:00Z")
                + "|[TransactionReference[rowNumber=1, transactionId=" + transactionId + "]]"
                + "|[AccountImpact[accountId=" + accountId
                + ", rowCount=1, balanceBefore=100.00, delta=-10.00, balanceAfter=90.00]]";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(receiptInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long insertCategory(Connection connection, long userId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO categories (user_id, name, type, is_system)
                VALUES (?, ?, 'EXPENSE', false) RETURNING id
                """)) {
            statement.setLong(1, userId);
            statement.setString(2, name + "-" + UUID.randomUUID().toString().substring(0, 8));
            return returningLong(statement);
        }
    }

    private long insertTransaction(Connection connection, long userId, long accountId, long categoryId,
                                   String type, String amount, String description) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, description, transacted_at)
                VALUES (?, ?, ?, ?, ?, 'CNY', ?, TIMESTAMP '2026-09-30 12:00:00') RETURNING id
                """)) {
            statement.setLong(1, userId);
            statement.setLong(2, accountId);
            statement.setLong(3, categoryId);
            statement.setString(4, type);
            statement.setBigDecimal(5, new java.math.BigDecimal(amount));
            statement.setString(6, description);
            return returningLong(statement);
        }
    }

    private void insertItem(Connection connection, long userId, UUID batchId, int rowNumber, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transaction_import_items
                (user_id, batch_id, source_row_number, canonical_row_fingerprint, created_transaction_id)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, userId);
            statement.setObject(2, batchId);
            statement.setInt(3, rowNumber);
            statement.setString(4, "a".repeat(64));
            statement.setLong(5, transactionId);
            statement.executeUpdate();
        }
    }

    private ReceiptSnapshot receiptSnapshot(Connection connection) throws SQLException {
        return new ReceiptSnapshot(
                jsonRows(connection, "transaction_import_sessions", "id"),
                jsonRows(connection, "transaction_import_batches", "id"),
                jsonRows(connection, "transaction_import_items", "id"),
                jsonRows(connection, "transaction_import_batch_account_impacts", "id"));
    }

    private String jsonRows(Connection connection, String table, String orderBy) throws SQLException {
        String sql = "SELECT COALESCE(jsonb_agg(to_jsonb(row_value) ORDER BY row_value." + orderBy
                + "), '[]'::jsonb)::text FROM " + table + " row_value";
        return scalarString(connection, sql);
    }

    private void installMigrationPauseEventTrigger(Connection connection) throws SQLException {
        execute(connection, """
                CREATE FUNCTION test_pause_v20_after_create_trigger()
                RETURNS event_trigger AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(91021020261::bigint);
                END;
                $$ LANGUAGE plpgsql
                """);
        execute(connection, """
                CREATE EVENT TRIGGER test_pause_v20_after_create_trigger
                ON ddl_command_end
                WHEN TAG IN ('CREATE TRIGGER')
                EXECUTE FUNCTION test_pause_v20_after_create_trigger()
                """);
    }

    private void dropMigrationPauseEventTrigger(Connection connection) throws SQLException {
        execute(connection, "DROP EVENT TRIGGER IF EXISTS test_pause_v20_after_create_trigger");
        execute(connection, "DROP FUNCTION IF EXISTS test_pause_v20_after_create_trigger()");
    }

    private int awaitMigrationLock() throws Exception {
        final int[] migrationPid = {-1};
        awaitCondition(() -> {
            try (Connection connection = connection()) {
                List<Integer> pids = integerList(connection, """
                        SELECT pid
                        FROM pg_locks
                        WHERE locktype = 'relation' AND relation = 'transactions'::regclass
                          AND mode = 'AccessExclusiveLock' AND granted
                        INTERSECT
                        SELECT pid
                        FROM pg_locks
                        WHERE locktype = 'relation' AND relation = 'transaction_import_items'::regclass
                          AND mode = 'AccessExclusiveLock' AND granted
                        """);
                if (!pids.isEmpty()) {
                    migrationPid[0] = pids.getFirst();
                    return true;
                }
                return false;
            }
        }, "V20 migration holds ACCESS EXCLUSIVE locks on transactions and transaction_import_items");
        return migrationPid[0];
    }

    private PendingSql submitSql(ExecutorService executor, String applicationName, SqlAction action) {
        CompletableFuture<Integer> backendPid = new CompletableFuture<>();
        Future<SqlOutcome> outcome = executor.submit(() -> {
            try (Connection connection = connection()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET application_name = '" + applicationName + "'");
                }
                backendPid.complete(scalarInt(connection, "SELECT pg_backend_pid()"));
                try {
                    return new SqlOutcome(true, action.execute(connection), null, null);
                } catch (SQLException exception) {
                    return new SqlOutcome(false, 0, exception.getSQLState(), exception.getMessage());
                }
            }
        });
        return new PendingSql(backendPid, outcome);
    }

    private void awaitCondition(SqlBooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    private boolean blockedBy(int waitingPid, int blockingPid) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT ? = ANY(pg_blocking_pids(?))")) {
            statement.setInt(1, blockingPid);
            statement.setInt(2, waitingPid);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private void assertSqlState(String expectedState, ThrowingRunnable action) {
        assertThatThrownBy(action::run).isInstanceOf(SQLException.class)
                .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo(expectedState));
    }

    private String appliedVersion(Connection connection) throws SQLException {
        return scalarString(connection, """
                SELECT version FROM flyway_schema_history
                WHERE success = true AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """);
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

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
    }

    private long returningLong(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("INSERT did not return an id");
            }
            return resultSet.getLong(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int executeUpdate(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private int count(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private int databaseCount(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection()) {
            return count(connection, sql, parameters);
        }
    }

    private int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private List<String> stringList(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            var values = new java.util.ArrayList<String>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    private List<Integer> integerList(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            var values = new java.util.ArrayList<Integer>();
            while (resultSet.next()) {
                values.add(resultSet.getInt(1));
            }
            return values;
        }
    }

    private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private record ReceiptFixture(long userId, long otherUserId, long accountId, long categoryId, UUID batchId,
                                  long originalTransactionId, long otherOwnerTransactionId) { }

    private record ReceiptSnapshot(String sessions, String batches, String items, String impacts) { }

    private record PendingSql(CompletableFuture<Integer> backendPid, Future<SqlOutcome> outcome) { }

    private record SqlOutcome(boolean succeeded, int updateCount, String sqlState, String message) { }

    @FunctionalInterface
    private interface SqlAction {
        int execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface SqlBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
