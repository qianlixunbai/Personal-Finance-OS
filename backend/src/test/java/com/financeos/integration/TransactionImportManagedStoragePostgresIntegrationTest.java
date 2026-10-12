package com.financeos.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.importing.dto.*;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.*;
import com.financeos.module.importing.preview.TransactionImportPreviewService;
import com.financeos.module.importing.service.*;
import com.financeos.module.importing.storage.*;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.*;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

@Timeout(50)
class TransactionImportManagedStoragePostgresIntegrationTest extends PostgresIntegrationTest {
    @TempDir Path directory;
    @Autowired TransactionImportFileLifecycleService lifecycle;
    @Autowired TransactionImportFileObjectMapper objects;
    @Autowired TransactionImportSessionMapper sessions;
    @Autowired PlatformTransactionManager manager;
    @Autowired AccountMapper accounts;
    @Autowired CategoryMapper categories;
    @Autowired TransactionMapper transactions;
    @Autowired ObjectMapper json;
    @Autowired TransactionImportPreviewTokenService tokens;
    LocalTemporaryImportFileStorage raw;
    LocalTemporaryImportFileStorage plans;
    TransactionImportPreviewService preview;
    TransactionImportPreviewCleanupService cleanup;
    final UUID deployment = UUID.randomUUID();
    final UUID rawId = UUID.randomUUID();
    final UUID planId = UUID.randomUUID();
    Path rawRoot;
    Path planRoot;
    Clock clock;

    @BeforeEach void fixture() throws Exception {
        rawRoot = directory.resolve("raw"); planRoot = directory.resolve("plan");
        TrustedImportStorageRoot.provision(rawRoot, deployment, rawId, "RAW");
        TrustedImportStorageRoot.provision(planRoot, deployment, planId, "PLAN");
        raw = new LocalTemporaryImportFileStorage(rawRoot, 5 * 1024 * 1024, deployment, rawId, "RAW", lifecycle);
        plans = new LocalTemporaryImportFileStorage(planRoot, 64 * 1024 * 1024, deployment, planId, "PLAN", lifecycle);
        clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        preview = new TransactionImportPreviewService(new TransactionImportSessionService(sessions, clock, lifecycle), sessions,
                raw, accounts, categories, transactions, plans, json, clock, tokens);
        cleanup = new TransactionImportPreviewCleanupService(sessions, raw, plans, clock, lifecycle);
        jdbcTemplate.update("INSERT INTO users(username,email,password_hash) VALUES('c9-managed','c9-managed@example.com','synthetic-fixture')");
        jdbcTemplate.update("INSERT INTO accounts(user_id,name,type,currency,balance) VALUES(1,'Cash','CASH','CNY',0)");
        jdbcTemplate.update("INSERT INTO categories(user_id,name,type) VALUES(1,'Food','EXPENSE')");
    }
    @AfterEach void closeStorage() { if (raw != null) raw.close(); if (plans != null) plans.close(); }

    @Test void registrationExistsBeforeTheFirstActualWriteAndNamesBothArtifacts() throws Exception {
        var stream = new ByteArrayInputStream("synthetic".getBytes()) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                String ref = jdbcTemplate.queryForObject("SELECT reference FROM transaction_import_file_objects WHERE storage_id=CAST(? AS UUID)", String.class, rawId.toString());
                assertState(ref, "RESERVED");
                assertThat(Files.isRegularFile(rawRoot.resolve(ref + ".part"))).isTrue();
                return super.read(bytes, offset, length);
            }
        };
        var saved = raw.save(1L, UUID.randomUUID(), stream, "synthetic.csv");
        assertThat(objects.find(saved.reference()).isWriteComplete()).isTrue();
        assertThat(Files.isRegularFile(rawRoot.resolve(saved.reference()))).isTrue();
        assertThat(Files.exists(rawRoot.resolve(saved.reference() + ".part"))).isFalse();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void productionWindowsPinPreventsRootAndAncestorRenameWhileOpen() {
        assertThat(rawRoot.getFileSystem().provider().getClass().getSimpleName()).isEqualTo("WindowsFileSystemProvider");
        assertThatThrownBy(() -> Files.move(rawRoot, directory.resolve("moved-raw"))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> Files.move(directory, directory.resolveSibling(directory.getFileName() + "-moved"))).isInstanceOf(IOException.class);
        assertThat(unbound().reference()).isNotNull();
    }

    @Test void reclamationRequiresBothTwentyFourHoursAndExpiredLease() {
        var saved = unbound();
        jdbcTemplate.update("UPDATE transaction_import_file_objects SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE reference=?", saved.reference());
        raw.cleanupRetired(); assertState(saved.reference(), "RESERVED");
        jdbcTemplate.update("UPDATE transaction_import_file_objects SET created_at=CURRENT_TIMESTAMP-INTERVAL '25 hours',lease_until=CURRENT_TIMESTAMP+INTERVAL '1 hour' WHERE reference=?", saved.reference());
        raw.cleanupRetired(); assertState(saved.reference(), "RESERVED");
        expireReservation(saved.reference());
        raw.cleanupRetired(); assertState(saved.reference(), "DELETED");
        assertThat(Files.exists(rawRoot.resolve(saved.reference()))).isFalse();
    }

    @RepeatedTest(3) void expiredWriterRetainsRealGuardUntilItStopsAndCannotCompleteOrRecreate() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            InputStream content = new ByteArrayInputStream("synthetic".getBytes()) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    entered.countDown(); await(release);
                    return super.read(bytes, offset, length);
                }
            };
            Future<?> writing = executor.submit(() -> raw.save(1L, UUID.randomUUID(), content, "synthetic.csv"));
            await(entered);
            String ref = jdbcTemplate.queryForObject("SELECT reference FROM transaction_import_file_objects WHERE storage_id=CAST(? AS UUID)", String.class, rawId.toString());
            var stale = objects.find(ref);
            expireReservation(ref);
            raw.cleanupRetired();
            assertState(ref, "RETIRED");
            assertThat(Files.exists(rawRoot.resolve(ref + ".part"))).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM transaction_import_file_objects WHERE reference=?", Integer.class, ref)).isPositive();
            release.countDown();
            assertThatThrownBy(() -> writing.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(InvalidImportFileException.class);
            due(ref); raw.cleanupRetired(); assertState(ref, "DELETED");
            try (var guard = new TrustedImportStorageRoot(rawRoot, deployment, rawId, "RAW")) {
                assertThatThrownBy(() -> guard.execute(access -> {
                    lifecycle.authorizeWrite(stale);
                    access.create(ref + ".part").close(); return null;
                })).isInstanceOf(InvalidImportFileException.class);
            }
            assertThat(Files.exists(rawRoot.resolve(ref))).isFalse();
            assertThat(Files.exists(rawRoot.resolve(ref + ".part"))).isFalse();
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @RepeatedTest(3) void independentReaderAndCleanerCannotUnlinkUntilActualReadCompletes() throws Exception {
        var response = ready();
        TransactionImportSession session = sessions.findByIdAndUserId(response.importSessionId(), 1L);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var guard = new TrustedImportStorageRoot(rawRoot, deployment, rawId, "RAW");
             var second = new LocalTemporaryImportFileStorage(rawRoot, 5 * 1024 * 1024, deployment, rawId, "RAW", lifecycle)) {
            Future<byte[]> reading = executor.submit(() -> guard.execute(access -> {
                lifecycle.authorizeRead(session.getTemporaryStorageReference(), rawId.toString(), 1L, session.getId());
                try (var channel = access.read(session.getTemporaryStorageReference())) {
                    entered.countDown(); await(release);
                    return Channels.newInputStream(channel).readAllBytes();
                }
            }));
            await(entered);
            lifecycle.cancel(1L, session.getId(), clock.instant());
            assertThatThrownBy(() -> second.delete(session.getTemporaryStorageReference())).isInstanceOf(InvalidImportFileException.class);
            assertState(session.getTemporaryStorageReference(), "RETIRED");
            assertThat(Files.isRegularFile(rawRoot.resolve(session.getTemporaryStorageReference()))).isTrue();
            release.countDown();
            assertThat(reading.get(10, TimeUnit.SECONDS)).isEqualTo(csvBytes());
            cleanup.cleanupExpired();
            assertState(session.getTemporaryStorageReference(), "DELETED");
            assertThat(sessions.findByIdAndUserId(session.getId(), 1L).getTemporaryStorageReference()).isNull();
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void knownMissingBindingQuarantinesPersistentlyAndDoesNotRegisterFalseDeletion() throws Exception {
        var saved = unbound(); lifecycle.retireUnpublished(saved.reference());
        Files.delete(rawRoot.resolve(TrustedImportStorageRoot.MARKER));
        assertThatThrownBy(() -> raw.delete(saved.reference())).isInstanceOf(ImportStorageBindingException.class);
        assertState(saved.reference(), "RETIRED");
        assertThat(Files.isRegularFile(rawRoot.resolve(saved.reference()))).isTrue();
        assertThatThrownBy(this::unbound).isInstanceOf(InvalidImportFileException.class);
        Files.writeString(rawRoot.resolve(TrustedImportStorageRoot.MARKER), TrustedImportStorageRoot.bindingDigest(rawRoot, deployment, rawId, "RAW"));
        raw.close();
        assertThatThrownBy(() -> new LocalTemporaryImportFileStorage(rawRoot, 5 * 1024 * 1024, deployment, rawId, "RAW", lifecycle))
                .isInstanceOf(InvalidImportFileException.class);
        assertState(saved.reference(), "RETIRED");
        // Explicit isolated operator re-verification, not an automatic production recovery.
        jdbcTemplate.update("UPDATE transaction_import_storage_bindings SET quarantined=FALSE WHERE storage_id=CAST(? AS UUID)", rawId.toString());
        raw = new LocalTemporaryImportFileStorage(rawRoot, 5 * 1024 * 1024, deployment, rawId, "RAW", lifecycle);
        due(saved.reference()); raw.cleanupRetired(); assertState(saved.reference(), "DELETED");
    }

    @Test void unauthorizedReconfigurationRejectsTheSameStorageIdAtAnotherRoot() throws Exception {
        var saved = unbound();
        Path other = directory.resolve("wrong-root");
        TrustedImportStorageRoot.provision(other, deployment, rawId, "RAW");
        assertThatThrownBy(() -> new LocalTemporaryImportFileStorage(other, 5 * 1024 * 1024, deployment, rawId, "RAW", lifecycle))
                .isInstanceOf(InvalidImportFileException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT quarantined FROM transaction_import_storage_bindings WHERE storage_id=CAST(? AS UUID)", Boolean.class, rawId.toString())).isTrue();
        assertThat(Files.isRegularFile(rawRoot.resolve(saved.reference()))).isTrue();
        assertThatThrownBy(() -> raw.delete(saved.reference())).isInstanceOf(InvalidImportFileException.class);
        assertState(saved.reference(), "RESERVED");
    }

    @Test void activeReferencesCannotBeDeletedAndForeignReadsAreRejected() {
        var response = ready(); var session = sessions.findByIdAndUserId(response.importSessionId(), 1L);
        assertThatThrownBy(() -> raw.delete(session.getTemporaryStorageReference())).isInstanceOf(InvalidImportFileException.class);
        assertThatThrownBy(() -> raw.open(2L, session.getId(), session.getTemporaryStorageReference())).isInstanceOf(InvalidImportFileException.class);
        cleanup.cleanupOnStartup();
        assertState(session.getTemporaryStorageReference(), "LIVE");
        assertState(session.getPlanStorageReference(), "LIVE");
        assertThat(raw.exists(session.getTemporaryStorageReference())).isTrue();
    }

    @Test void sessionDeletionPreservesDurableObjectsAndRecoveryIntent() {
        var response = ready(); var session = sessions.findByIdAndUserId(response.importSessionId(), 1L);
        jdbcTemplate.update("DELETE FROM transaction_import_sessions WHERE id=?", session.getId());
        assertThat(objects.find(session.getTemporaryStorageReference()).getSessionId()).isNull();
        assertThat(objects.find(session.getPlanStorageReference()).getIntendedSessionId()).isEqualTo(session.getId().toString());
        raw.cleanupRetired(); plans.cleanupRetired();
        assertState(session.getTemporaryStorageReference(), "DELETED");
        assertState(session.getPlanStorageReference(), "DELETED");
    }

    @Test void duplicateDeletionIsIdempotentAndNormalRestartDoesNotRequireReverification() {
        var saved = unbound(); lifecycle.retireUnpublished(saved.reference());
        assertThat(raw.delete(saved.reference())).isTrue();
        assertThat(raw.delete(saved.reference())).isFalse();
        raw.close();
        raw = new LocalTemporaryImportFileStorage(rawRoot, 5 * 1024 * 1024, deployment, rawId, "RAW", lifecycle);
        assertThat(unbound().reference()).isNotEqualTo(saved.reference());
    }

    @Test void terminalMetadataBeforeUnlinkIsRecoveredByTheActualStartupCallback() {
        var response = ready(); var before = sessions.findByIdAndUserId(response.importSessionId(), 1L);
        lifecycle.cancel(1L, before.getId(), clock.instant());
        assertState(before.getTemporaryStorageReference(), "RETIRED");
        assertThat(Files.exists(rawRoot.resolve(before.getTemporaryStorageReference()))).isTrue();
        cleanup.cleanupOnStartup();
        assertThat(sessions.findByIdAndUserId(before.getId(), 1L).getTemporaryStorageReference()).isNull();
        assertState(before.getPlanStorageReference(), "DELETED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
    }

    @Test void lostCommitAcknowledgementCannotTriggerDeletionOfThePublishedPlan() throws Exception {
        var response = ready(); var before = sessions.findByIdAndUserId(response.importSessionId(), 1L);
        var frozen = preview.loadFrozenPlan(before);
        var candidate = plans.save(1L, before.getId(), new ByteArrayInputStream(json.writeValueAsBytes(frozen)), "synthetic-plan.json");
        AtomicBoolean loseAcknowledgement = new AtomicBoolean(true);
        PlatformTransactionManager lostAck = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
            public void commit(TransactionStatus status) {
                manager.commit(status);
                if (loseAcknowledgement.getAndSet(false)) throw new DataAccessResourceFailureException("Synthetic committed acknowledgement lost");
            }
        };
        var uncertain = new TransactionImportFileLifecycleService(objects, sessions, lostAck);
        assertThatThrownBy(() -> uncertain.publishPlan(before, candidate.reference(), before.getMappingDigest(), before.getNormalizedRowsDigest(), clock.instant()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(uncertain.retireUnpublished(candidate.reference())).isFalse();
        assertThatThrownBy(() -> plans.delete(candidate.reference())).isInstanceOf(InvalidImportFileException.class);
        assertState(candidate.reference(), "LIVE");
        assertState(before.getPlanStorageReference(), "RETIRED");
        assertThat(plans.exists(candidate.reference())).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
    }

    @Test void actualSchedulerDeletesRetiredObjectsAndStopsAcceptingNewWork() throws Exception {
        var first = unbound(); lifecycle.retireUnpublished(first.reference());
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1); taskScheduler.initialize();
        var scheduler = new TransactionImportPreviewCleanupScheduler(cleanup, taskScheduler, Duration.ofMillis(40));
        try {
            scheduler.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!"DELETED".equals(objects.find(first.reference()).getState()) && System.nanoTime() < deadline) Thread.sleep(25);
            assertState(first.reference(), "DELETED");
            scheduler.stop(); assertThat(scheduler.isRunning()).isFalse();
            var second = unbound(); lifecycle.retireUnpublished(second.reference());
            Thread.sleep(150);
            assertState(second.reference(), "RETIRED");
            assertThat(Files.isRegularFile(rawRoot.resolve(second.reference()))).isTrue();
        } finally { scheduler.stop(); taskScheduler.shutdown(); }
    }

    @Test void callerDeadlineDoesNotReleaseTheActualIoWorkersGuard() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            InputStream input = new InputStream() {
                @Override public int read() { throw new UnsupportedOperationException(); }
                @Override public int read(byte[] bytes, int offset, int length) {
                    entered.countDown();
                    try { if (!release.await(45, TimeUnit.SECONDS)) throw new AssertionError("Deadline fixture not released"); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                    return -1;
                }
                @Override public void close() { exited.countDown(); }
            };
            Future<?> caller = executor.submit(() -> raw.save(1L, UUID.randomUUID(), input, "synthetic.csv"));
            await(entered);
            String ref = jdbcTemplate.queryForObject("SELECT reference FROM transaction_import_file_objects WHERE storage_id=CAST(? AS UUID)", String.class, rawId.toString());
            assertThatThrownBy(() -> caller.get(33, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InvalidImportFileException.class);
            assertThat(exited.getCount()).isEqualTo(1);
            expireReservation(ref); raw.cleanupRetired();
            assertState(ref, "RETIRED");
            assertThat(Files.exists(rawRoot.resolve(ref + ".part"))).isTrue();
            release.countDown(); await(exited);
            due(ref); raw.cleanupRetired(); assertState(ref, "DELETED");
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void startupEnrollsOnlyExactLegacyDatabaseReferencesAndPreservesUnknownObjects() throws Exception {
        var response = ready(); var current = sessions.findByIdAndUserId(response.importSessionId(), 1L);
        jdbcTemplate.update("DELETE FROM transaction_import_file_objects WHERE intended_session_id=?", current.getId());
        String unknown = UUID.randomUUID().toString();
        Files.writeString(rawRoot.resolve(unknown), "unknown synthetic fixture");
        cleanup.cleanupOnStartup();
        assertState(current.getTemporaryStorageReference(), "LIVE");
        assertState(current.getPlanStorageReference(), "LIVE");
        assertThat(objects.find(unknown)).isNull();
        assertThat(Files.readString(rawRoot.resolve(unknown))).isEqualTo("unknown synthetic fixture");
        assertThat(preview.loadFrozenPlan(current).rows()).hasSize(1);
    }

    private StoredImportFile unbound() { return raw.save(1L, UUID.randomUUID(), new ByteArrayInputStream("synthetic".getBytes()), "synthetic.csv"); }
    private TransactionImportPreviewResponse ready() {
        var mapping = new TransactionImportMapping(Map.of("date","date","type","type","amount","amount","account","account","category","category"),
                Map.of("expense","EXPENSE"), Map.of("Cash",1L), Map.of("Food",1L));
        return preview.create(1L, new MockMultipartFile("file","synthetic.csv","text/csv",csvBytes()), new TransactionImportPreviewRequest(null,mapping));
    }
    private byte[] csvBytes() { return "date,type,amount,account,category\n2026-08-01,expense,10.00,Cash,Food\n".getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private void expireReservation(String ref) { jdbcTemplate.update("UPDATE transaction_import_file_objects SET created_at=CURRENT_TIMESTAMP-INTERVAL '25 hours',lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE reference=?", ref); }
    private void due(String ref) { jdbcTemplate.update("UPDATE transaction_import_file_objects SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE reference=?", ref); }
    private void assertState(String ref, String state) { assertThat(objects.find(ref).getState()).isEqualTo(state); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(12, TimeUnit.SECONDS)) throw new AssertionError("Bounded lifecycle latch expired"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
}
