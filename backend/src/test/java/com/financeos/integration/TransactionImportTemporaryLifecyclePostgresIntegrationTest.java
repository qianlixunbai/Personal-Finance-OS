package com.financeos.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.importing.dto.*;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.preview.TransactionImportPreviewService;
import com.financeos.module.importing.service.*;
import com.financeos.module.importing.storage.*;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.mybatis.spring.SqlSessionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Safety assertions deliberately stay red until C9's lifecycle design is approved and implemented. */
@Timeout(30)
class TransactionImportTemporaryLifecyclePostgresIntegrationTest extends PostgresIntegrationTest {
    @TempDir Path directory;
    @SpyBean private TransactionImportSessionMapper sessions;
    @Autowired private AccountMapper accounts;
    @Autowired private CategoryMapper categories;
    @Autowired private TransactionMapper transactions;
    @Autowired private ObjectMapper json;
    @Autowired private TransactionImportPreviewTokenService tokens;
    @Autowired private TransactionImportConfirmService confirm;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private TransactionImportFileLifecycleService files;
    private java.util.UUID deployment;
    private java.util.UUID rawId;
    private java.util.UUID planId;
    private LocalTemporaryImportFileStorage raw;
    private LocalTemporaryImportFileStorage plans;
    private TransactionImportPreviewService preview;
    private TransactionImportPreviewCleanupService cleanup;
    private Clock clock;
    private Path rawRoot;
    private Path planRoot;

    @BeforeEach
    void fixture() throws Exception {
        rawRoot = directory.resolve("raw");
        planRoot = directory.resolve("plans");
        deployment = java.util.UUID.randomUUID();
        rawId = java.util.UUID.randomUUID();
        planId = java.util.UUID.randomUUID();
        TrustedImportStorageRoot.provision(rawRoot, deployment, rawId, "RAW");
        TrustedImportStorageRoot.provision(planRoot, deployment, planId, "PLAN");
        raw = spy(new LocalTemporaryImportFileStorage(rawRoot, 5 * 1024 * 1024, deployment, rawId, "RAW", files));
        plans = spy(new LocalTemporaryImportFileStorage(planRoot, 64 * 1024 * 1024, deployment, planId, "PLAN", files));
        clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        preview = new TransactionImportPreviewService(new TransactionImportSessionService(sessions, clock, files), sessions,
                raw, accounts, categories, transactions, plans, json, clock, tokens);
        cleanup = new TransactionImportPreviewCleanupService(sessions, raw, plans, clock, files);
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash) VALUES ('c9', 'c9@example.com', 'fixture-hash')");
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Cash', 'CASH', 'CNY', 0)");
        jdbcTemplate.update("INSERT INTO categories (user_id, name, type) VALUES (1, 'Food', 'EXPENSE')");
    }

    @AfterEach
    void dropFaultTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS c9_fault ON transaction_import_sessions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS c9_fault()");
    }

    @AfterEach
    void closeManagedStorage() {
        if (raw != null) raw.close();
        if (plans != null) plans.close();
    }

    @Test
    void sessionInsertFailureDeletesUnpublishedRawFile() throws Exception {
        installDatabaseFault("INSERT");
        assertThatThrownBy(this::ready).isInstanceOf(DataAccessException.class);
        assertThat(sessionCount()).isZero();
        assertThat(fileCount(rawRoot)).isZero();
        assertNoFinancialWrites();
    }

    @Test
    void failedRawCompensationMustRemainRecoverableWithoutSession() throws Exception {
        installDatabaseFault("INSERT");
        doThrow(new InvalidImportFileException(400, "Injected delete failure")).when(raw).delete(anyString());
        assertThatThrownBy(this::ready).isInstanceOf(InvalidImportFileException.class);
        assertThat(sessionCount()).isZero();
        assertThat(fileCount(rawRoot)).isEqualTo(1);
        reset(raw);
        restartCleanup();
        System.out.println("C9 insert + delete failure: sessions=0; raw remaining=" + fileCount(rawRoot));
        assertThat(fileCount(rawRoot)).as("Unreferenced raw payload must be recoverable").isZero();
    }

    @Test
    void runtimeAfterSessionInsertMustNotLeaveMappingRequiredWithMissingRawFile() throws Exception {
        doThrow(new IllegalStateException("Injected plan serialization/storage failure"))
                .when(plans).save(any(InputStream.class), anyString());
        assertThatThrownBy(this::ready).isInstanceOf(IllegalStateException.class);
        TransactionImportSession session = onlySession();
        assertThat(session.getStatus()).isEqualTo("MAPPING_REQUIRED");
        System.out.println("C9 materialize failure after Session insert: live Session raw exists="
                + raw.exists(session.getTemporaryStorageReference()));
        assertThat(raw.exists(session.getTemporaryStorageReference()))
                .as("Live MAPPING_REQUIRED Session must retain its referenced original payload").isTrue();
    }

    @Test
    void createDatabaseUpdateFailureMustNotLeaveDanglingRawAndOrphanPlan() throws Exception {
        installDatabaseFault("UPDATE");
        assertThatThrownBy(this::ready).isInstanceOf(DataAccessException.class);
        TransactionImportSession session = onlySession();
        assertThat(session.getStatus()).isEqualTo("MAPPING_REQUIRED");
        assertThat(session.getPlanStorageReference()).isNull();
        System.out.println("C9 create UPDATE failure: sessions=1; raw=" + fileCount(rawRoot)
                + "; unpublished plans=" + fileCount(planRoot));
        assertThat(raw.exists(session.getTemporaryStorageReference())).isTrue();
        assertThat(fileCount(planRoot)).isZero();
    }

    @RepeatedTest(3)
    void updateDatabaseFailureMustNotLeaveAnUnrecoverablePlan() throws Exception {
        var initial = ready();
        TransactionImportSession before = sessions.findByIdAndUserId(initial.importSessionId(), 1L);
        installDatabaseFault("UPDATE");
        assertThatThrownBy(() -> preview.updateMapping(1L, initial.importSessionId(), mapping()))
                .isInstanceOf(DataAccessException.class);
        TransactionImportSession after = sessions.findByIdAndUserId(initial.importSessionId(), 1L);
        assertThat(after.getRevision()).isEqualTo(before.getRevision());
        assertThat(after.getPlanStorageReference()).isEqualTo(before.getPlanStorageReference());
        assertThat(raw.exists(after.getTemporaryStorageReference())).isTrue();
        assertThat(plans.exists(after.getPlanStorageReference())).isTrue();
        assertThat(fileCount(planRoot)).isEqualTo(2);
        dropFaultTrigger();
        expire(initial.importSessionId());
        restartCleanup();
        assertThat(onlySession().getPlanStorageReference()).isNull();
        System.out.println("C9 mapping UPDATE failure: current plan recovered; orphan plans=" + fileCount(planRoot));
        assertThat(fileCount(planRoot)).as("Failed publication plan must be reclaimable").isZero();
    }

    @Test
    void oldPlanDeleteFailureMustNotLoseRetirementRetry() throws Exception {
        var initial = ready();
        String oldPlan = onlySession().getPlanStorageReference();
        doThrow(new InvalidImportFileException(400, "Injected old plan delete failure")).when(plans).delete(oldPlan);
        assertThatThrownBy(() -> preview.updateMapping(1L, initial.importSessionId(), mapping()))
                .isInstanceOf(InvalidImportFileException.class);
        TransactionImportSession current = onlySession();
        assertThat(current.getRevision()).isEqualTo(initial.revision() + 1);
        assertThat(current.getPlanStorageReference()).isNotEqualTo(oldPlan);
        assertThat(plans.exists(current.getPlanStorageReference())).isTrue();
        assertThat(plans.exists(oldPlan)).isTrue();
        reset(plans);
        expire(initial.importSessionId());
        restartCleanup();
        System.out.println("C9 replaced plan deletion failure: old unreferenced plans=" + fileCount(planRoot));
        assertThat(fileCount(planRoot)).as("Retired old plan must retain a cleanup retry path").isZero();
    }

    @RepeatedTest(3)
    void twoConcurrentMappingPublicationsMustNotLoseAnIntermediatePlan() throws Exception {
        var initial = ready();
        CountDownLatch saved = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        doAnswer(invocation -> {
            StoredImportFile result = (StoredImportFile) invocation.callRealMethod();
            saved.countDown();
            await(Thread.currentThread().getName().equals("c9-first") ? releaseFirst : releaseSecond);
            return result;
        }).when(plans).save(any(InputStream.class), anyString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<TransactionImportPreviewResponse> first = pool.submit(() -> {
                Thread.currentThread().setName("c9-first");
                return preview.updateMapping(1L, initial.importSessionId(), mapping());
            });
            Future<TransactionImportPreviewResponse> second = pool.submit(() -> {
                Thread.currentThread().setName("c9-second");
                return preview.updateMapping(1L, initial.importSessionId(), mapping());
            });
            assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            releaseSecond.countDown();
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
            TransactionImportSession current = onlySession();
            assertThat(current.getRevision()).isEqualTo(initial.revision() + 1);
            assertThat(plans.exists(current.getPlanStorageReference())).isTrue();
            assertNoFinancialWrites();
            System.out.println("C9 concurrent Mapping: current plan valid; files=" + fileCount(planRoot)
                    + "; database current references=1");
            assertThat(fileCount(planRoot)).as("Only the current plan should remain after replacement").isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancelWinningBeforePublicationRejectsZeroRowsAndDeletesUnpublishedPlan() throws Exception {
        var initial = ready();
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            StoredImportFile result = (StoredImportFile) invocation.callRealMethod();
            saved.countDown();
            await(release);
            return result;
        }).when(plans).save(any(InputStream.class), anyString());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> updating = pool.submit(() -> preview.updateMapping(1L, initial.importSessionId(), mapping()));
            assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();
            preview.cancel(1L, initial.importSessionId());
            release.countDown();
            assertThatThrownBy(() -> updating.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
            assertThat(onlySession().getStatus()).isEqualTo("CANCELLED");
            assertThat(fileCount(rawRoot)).isZero();
            assertThat(fileCount(planRoot)).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void simulatedInterruptionAfterRawSaveMustBeRecoverableOnStartup() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedInterruption();
        }).when(raw).save(any(InputStream.class), anyString());
        assertThatThrownBy(this::ready).isInstanceOf(SimulatedInterruption.class);
        assertThat(sessionCount()).isZero();
        restartCleanup();
        System.out.println("C9 simulated interruption after raw save: startup remaining=" + fileCount(rawRoot));
        assertThat(fileCount(rawRoot)).isZero();
    }

    @RepeatedTest(3)
    void mappingPublishedAfterCancelSnapshotMustStillBeReclaimed() throws Exception {
        var initial = ready();
        CountDownLatch cancelSnapshotRead = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        TransactionImportSessionMapper realMapper = sqlSession.getMapper(TransactionImportSessionMapper.class);
        doAnswer(invocation -> {
            TransactionImportSession snapshot = realMapper.findByIdAndUserId(initial.importSessionId(), 1L);
            if (Thread.currentThread().getName().equals("c9-cancel")) {
                cancelSnapshotRead.countDown();
                await(releaseCancel);
            }
            return snapshot;
        }).when(sessions).findByIdAndUserId(initial.importSessionId(), 1L);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> cancelling = pool.submit(() -> {
                Thread.currentThread().setName("c9-cancel");
                preview.cancel(1L, initial.importSessionId());
            });
            assertThat(cancelSnapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            preview.updateMapping(1L, initial.importSessionId(), mapping());
            releaseCancel.countDown();
            cancelling.get(10, TimeUnit.SECONDS);
            assertThat(onlySession().getStatus()).isEqualTo("CANCELLED");
            assertThat(onlySession().getPlanStorageReference()).isNull();
            restartCleanup();
            assertNoFinancialWrites();
            System.out.println("C9 Cancel stale snapshot: cleared database references; orphan plans=" + fileCount(planRoot));
            assertThat(fileCount(planRoot)).as("Cancel must reclaim the actual published plan").isZero();
        } finally {
            releaseCancel.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void expiryWinningBeforePublicationRejectsCandidateAndCleanupRetainsNoFiles() throws Exception {
        var initial = ready();
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            StoredImportFile result = (StoredImportFile) invocation.callRealMethod();
            saved.countDown();
            await(release);
            return result;
        }).when(plans).save(any(InputStream.class), anyString());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> updating = pool.submit(() -> preview.updateMapping(1L, initial.importSessionId(), mapping()));
            assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();
            expire(initial.importSessionId());
            cleanup.cleanupExpired();
            release.countDown();
            assertThatThrownBy(() -> updating.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
            assertThat(onlySession().getStatus()).isEqualTo("EXPIRED");
            assertThat(fileCount(rawRoot)).isZero();
            assertThat(fileCount(planRoot)).isZero();
            assertNoFinancialWrites();
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void confirmWinningBeforeMappingPublicationKeepsDurableReceiptAndRejectsCandidate() throws Exception {
        var initial = ready();
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            StoredImportFile result = (StoredImportFile) invocation.callRealMethod();
            saved.countDown();
            await(release);
            return result;
        }).when(plans).save(any(InputStream.class), anyString());
        Object originalPreview = ReflectionTestUtils.getField(confirm, "previewService");
        Object originalCleanup = ReflectionTestUtils.getField(confirm, "cleanupService");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ReflectionTestUtils.setField(confirm, "previewService", preview);
            ReflectionTestUtils.setField(confirm, "cleanupService", cleanup);
            Future<?> updating = pool.submit(() -> preview.updateMapping(1L, initial.importSessionId(), mapping()));
            assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();
            var request = new TransactionImportConfirmRequest(initial.previewToken(), List.of());
            var committed = confirm.confirm(1L, initial.importSessionId(), "c9-race-confirm-key", request);
            Map<String, String> facts = financialState();
            release.countDown();
            assertThatThrownBy(() -> updating.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
            assertThat(onlySession().getStatus()).isEqualTo("CONSUMED");
            assertThat(fileCount(rawRoot)).isZero();
            assertThat(fileCount(planRoot)).isZero();
            assertThat(confirm.getReceipt(1L, committed.receipt().importBatchId())).isEqualTo(committed.receipt());
            assertThat(confirm.confirm(1L, initial.importSessionId(), "c9-race-confirm-key", request).idempotentReplay()).isTrue();
            assertThat(financialState()).isEqualTo(facts);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            ReflectionTestUtils.setField(confirm, "previewService", originalPreview);
            ReflectionTestUtils.setField(confirm, "cleanupService", originalCleanup);
        }
    }

    @Test
    void simulatedInterruptionAfterPlanSaveMustBeRecoverableOnStartup() throws Exception {
        var initial = ready();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedInterruption();
        }).when(plans).save(any(InputStream.class), anyString());
        assertThatThrownBy(() -> preview.updateMapping(1L, initial.importSessionId(), mapping()))
                .isInstanceOf(SimulatedInterruption.class);
        assertThat(plans.exists(onlySession().getPlanStorageReference())).isTrue();
        expire(initial.importSessionId());
        restartCleanup();
        System.out.println("C9 simulated interruption after plan save: startup orphan plans=" + fileCount(planRoot));
        assertThat(fileCount(planRoot)).isZero();
    }

    @Test
    void simulatedInterruptionDuringWriteMustNotLeaveUndiscoverablePart() throws Exception {
        InputStream interrupted = new ByteArrayInputStream("synthetic".getBytes(StandardCharsets.UTF_8)) {
            private boolean read;
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                if (read) throw new SimulatedInterruption();
                read = true;
                return super.read(buffer, offset, length);
            }
        };
        assertThatThrownBy(() -> raw.save(1L, java.util.UUID.randomUUID(), interrupted, "synthetic.csv"))
                .isInstanceOf(SimulatedInterruption.class);
        assertThat(sessionCount()).isZero();
        restartCleanup();
        System.out.println("C9 simulated interruption during write: partial files=" + fileCount(rawRoot));
        assertThat(fileCount(rawRoot)).isZero();
    }

    @Test
    void csvXlsxMappingRepeatedUpdatesAndActiveCleanupKeepCurrentFiles() throws Exception {
        var incomplete = preview.create(1L, csv(), new TransactionImportPreviewRequest(null, null));
        assertThat(incomplete.sessionStatus()).isEqualTo("MAPPING_REQUIRED");
        assertThat(raw.exists(onlySession().getTemporaryStorageReference())).isTrue();
        var first = preview.updateMapping(1L, incomplete.importSessionId(), mapping());
        var second = preview.updateMapping(1L, first.importSessionId(), mapping());
        assertThat(second.revision()).isEqualTo(first.revision() + 1);
        assertThat(fileCount(planRoot)).isEqualTo(1);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            String[][] cells = {{"date", "type", "amount", "account", "category"},
                    {"2026-08-01", "expense", "10.00", "Cash", "Food"}};
            for (int row = 0; row < cells.length; row++) {
                var target = sheet.createRow(row);
                for (int column = 0; column < cells[row].length; column++) target.createCell(column).setCellValue(cells[row][column]);
            }
            workbook.write(output);
            var xlsx = preview.create(1L, new MockMultipartFile("file", "synthetic.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray()),
                    new TransactionImportPreviewRequest(null, mapping()));
            assertThat(xlsx.sessionStatus()).isEqualTo("PREVIEW_READY");
            assertThat(xlsx.summary().errorRows()).isZero();
        }
        cleanup.cleanupOnStartup();
        assertThat(fileCount(rawRoot)).isEqualTo(2);
        assertThat(fileCount(planRoot)).isEqualTo(2);
        assertNoFinancialWrites();
    }

    @Test
    void foreignCancelIsRejectedAndFailedOwnerDeleteCanRetry() throws Exception {
        var initial = ready();
        assertThatThrownBy(() -> preview.cancel(2L, initial.importSessionId()))
                .isInstanceOf(BusinessException.class).extracting(error -> ((BusinessException) error).getCode()).isEqualTo(404);
        assertThat(fileCount(rawRoot)).isEqualTo(1);
        assertThat(fileCount(planRoot)).isEqualTo(1);
        doThrow(new InvalidImportFileException(400, "Injected delete failure")).when(raw).delete(anyString());
        assertThatThrownBy(() -> preview.cancel(1L, initial.importSessionId())).isInstanceOf(InvalidImportFileException.class);
        assertThat(onlySession().getStatus()).isEqualTo("CANCELLED");
        assertThat(onlySession().getTemporaryStorageReference()).isNotNull();
        reset(raw);
        cleanup.cleanupExpired();
        assertThat(onlySession().getTemporaryStorageReference()).isNull();
        assertThat(fileCount(rawRoot)).isZero();
        assertThat(fileCount(planRoot)).isZero();
        assertNoFinancialWrites();
    }

    @Test
    void committedReceiptReplayAndFinancialFactsSurviveDeletionFailureAndRetry() throws Exception {
        var initial = ready();
        Object originalPreview = ReflectionTestUtils.getField(confirm, "previewService");
        Object originalCleanup = ReflectionTestUtils.getField(confirm, "cleanupService");
        try {
            ReflectionTestUtils.setField(confirm, "previewService", preview);
            ReflectionTestUtils.setField(confirm, "cleanupService", cleanup);
            doThrow(new InvalidImportFileException(400, "Injected committed cleanup failure")).when(raw).delete(anyString());
            var request = new TransactionImportConfirmRequest(initial.previewToken(), List.of());
            var first = confirm.confirm(1L, initial.importSessionId(), "c9-confirm-key", request);
            assertThat(first.idempotentReplay()).isFalse();
            assertThat(onlySession().getStatus()).isEqualTo("CONSUMED");
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", java.math.BigDecimal.class))
                    .isEqualByComparingTo("-10");
            Map<String, String> facts = financialState();
            reset(raw);
            cleanup.cleanupExpired();
            assertThat(fileCount(rawRoot)).isZero();
            assertThat(fileCount(planRoot)).isZero();
            assertThat(confirm.getReceipt(1L, first.receipt().importBatchId())).isEqualTo(first.receipt());
            var replay = confirm.confirm(1L, initial.importSessionId(), "c9-confirm-key", request);
            assertThat(replay.idempotentReplay()).isTrue();
            assertThat(replay.receipt()).isEqualTo(first.receipt());
            assertThat(financialState()).isEqualTo(facts);
        } finally {
            ReflectionTestUtils.setField(confirm, "previewService", originalPreview);
            ReflectionTestUtils.setField(confirm, "cleanupService", originalCleanup);
        }
    }

    private TransactionImportPreviewResponse ready() {
        return preview.create(1L, csv(), new TransactionImportPreviewRequest(null, mapping()));
    }

    private static MockMultipartFile csv() {
        return new MockMultipartFile("file", "synthetic.csv", "text/csv", ("date,type,amount,account,category\n"
                + "2026-08-01,expense,10.00,Cash,Food\n").getBytes(StandardCharsets.UTF_8));
    }

    private static TransactionImportMapping mapping() {
        return new TransactionImportMapping(Map.of("date", "date", "type", "type", "amount", "amount",
                "account", "account", "category", "category"), Map.of("expense", "EXPENSE"),
                Map.of("Cash", 1L), Map.of("Food", 1L));
    }

    private TransactionImportSession onlySession() {
        assertThat(sessionCount()).isEqualTo(1);
        return sessions.selectList(null).getFirst();
    }

    private int sessionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_sessions", Integer.class);
    }

    private static long fileCount(Path root) throws Exception {
        if (!Files.exists(root)) return 0;
        try (var files = Files.list(root)) {
            return files.filter(path -> !path.getFileName().toString().equals(TrustedImportStorageRoot.MARKER)
                    && !path.getFileName().toString().equals(TrustedImportStorageRoot.CONTROL)).count();
        }
    }

    private void expire(java.util.UUID session) {
        jdbcTemplate.update("UPDATE transaction_import_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE id = ?", session);
    }

    private void restartCleanup() {
        // Equivalent startup callback on a new service/storage over the same persisted DB and files.
        // This is deterministic interruption injection, not an actual JVM kill/restart.
        // DB time is authoritative for reservation leases/retention, so age the isolated fixture too.
        jdbcTemplate.update("UPDATE transaction_import_file_objects SET created_at=CURRENT_TIMESTAMP-INTERVAL '25 hours', lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second',next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        try (var restartedRaw = new LocalTemporaryImportFileStorage(rawRoot, 5 * 1024 * 1024, deployment, rawId, "RAW", files);
             var restartedPlan = new LocalTemporaryImportFileStorage(planRoot, 64 * 1024 * 1024, deployment, planId, "PLAN", files)) {
            new TransactionImportPreviewCleanupService(sessions, restartedRaw, restartedPlan,
                    Clock.fixed(clock.instant().plusSeconds(86400), ZoneOffset.UTC), files).cleanupOnStartup();
        }
    }

    private void installDatabaseFault(String operation) {
        jdbcTemplate.execute("""
                CREATE FUNCTION c9_fault() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'C9 injected database failure' USING ERRCODE = '23514'; END; $$
                """);
        jdbcTemplate.execute("CREATE TRIGGER c9_fault BEFORE " + operation
                + " ON transaction_import_sessions FOR EACH ROW EXECUTE FUNCTION c9_fault()");
    }

    private void assertNoFinancialWrites() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", java.math.BigDecimal.class))
                .isEqualByComparingTo("0");
    }

    private Map<String, String> financialState() {
        return List.of("accounts", "transactions", "transaction_import_batches", "transaction_import_items", "transaction_import_batch_account_impacts")
                .stream().collect(java.util.stream.Collectors.toMap(table -> table,
                        table -> jdbcTemplate.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text, '[]') FROM "
                                + table + " t", String.class)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("C9 latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class SimulatedInterruption extends Error { }
}
