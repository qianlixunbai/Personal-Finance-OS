package com.financeos.integration;

import com.financeos.module.importing.dto.TransactionImportConfirmRequest;
import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.importing.preview.TransactionImportPreviewService;
import com.financeos.module.importing.service.TransactionImportConfirmService;
import com.financeos.module.importing.service.TransactionImportPreviewCleanupService;
import com.financeos.module.importing.service.TransactionImportConfirmTransactionObserver;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.service.TransactionService;
import com.financeos.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

class TransactionImportConfirmIntegrationTest extends PostgresIntegrationTest {

    @Autowired private TransactionImportPreviewService previewService;
    @Autowired private TransactionImportConfirmService confirmService;
    @Autowired private TransactionService transactionService;
    @SpyBean private TransactionImportPreviewCleanupService cleanupService;
    @SpyBean private TransactionImportConfirmTransactionObserver transactionObserver;

    @Test
    void confirmPersistsTransactionsBalancesAndAReplayableReceiptAtomically() {
        long userId = createUser("confirm@example.com");
        long accountId = createAccount(userId);
        long expenseCategoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch\n2026-08-02,expense,30.00,Cash,Food,dinner"),
                new TransactionImportPreviewRequest(null, mapping(accountId, expenseCategoryId)));

        var first = confirmService.confirm(userId, preview.importSessionId(), "confirm-key-1",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
        var replay = confirmService.confirm(userId, preview.importSessionId(), "confirm-key-1",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.receipt()).isEqualTo(first.receipt());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("-40.00");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM transaction_import_sessions WHERE id = ?", String.class, preview.importSessionId())).isEqualTo("CONSUMED");
    }

    @Test
    void confirmRejectsWhenDatabaseDuplicateCandidateEvidenceChangesAfterPreview() {
        long userId = createUser("evidence@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        insertTransaction(userId, accountId, categoryId, "2026-08-01 00:00:00", "lunch");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        List<String> warnings = preview.rows().getFirst().warnings().stream().map(warning -> warning.id()).toList();
        assertThat(warnings).hasSize(1);

        insertTransaction(userId, accountId, categoryId, "2026-08-01 00:00:00", "lunch");

        assertThatThrownBy(() -> confirmService.confirm(userId, preview.importSessionId(), "evidence-key",
                new TransactionImportConfirmRequest(preview.previewToken(), warnings)))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_DUPLICATE_EVIDENCE_CHANGED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches", Integer.class)).isZero();
    }

    @Test
    void confirmNeverAcceptsStaleDuplicateEvidenceWhenAManualWriteRacesTheFinalEvidenceCheck() throws Exception {
        long userId = createUser("evidence-lock-race@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        CountDownLatch accountLocksAcquired = new CountDownLatch(1);
        CountDownLatch releaseFinalEvidenceCheck = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            accountLocksAcquired.countDown();
            if (!releaseFinalEvidenceCheck.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("final duplicate evidence check was not released");
            }
            return invocation.callRealMethod();
        }).when(transactionObserver).accountLocksAcquired(any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<com.financeos.module.importing.dto.TransactionImportConfirmResponse> confirm = executor.submit(() ->
                    confirmService.confirm(userId, preview.importSessionId(), "evidence-lock-race-key",
                            new TransactionImportConfirmRequest(preview.previewToken(), List.of())));
            assertThat(accountLocksAcquired.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> manual = executor.submit(() -> transactionService.create(userId,
                    new TransactionRequest(accountId, categoryId, "EXPENSE", new java.math.BigDecimal("10.00"), "CNY", "lunch",
                            LocalDateTime.of(2026, 8, 1, 0, 0))));

            boolean manualCommittedBeforeEvidenceCheckReleased;
            try {
                manual.get(2, TimeUnit.SECONDS);
                manualCommittedBeforeEvidenceCheckReleased = true;
            } catch (TimeoutException expected) {
                manualCommittedBeforeEvidenceCheckReleased = false;
            }
            releaseFinalEvidenceCheck.countDown();

            if (manualCommittedBeforeEvidenceCheckReleased) {
                assertThatThrownBy(() -> confirm.get(20, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(BusinessException.class)
                        .hasRootCauseMessage("IMPORT_DUPLICATE_EVIDENCE_CHANGED");
            } else {
                assertThat(confirm.get(20, TimeUnit.SECONDS).idempotentReplay()).isFalse();
                manual.get(20, TimeUnit.SECONDS);
            }
        } finally {
            releaseFinalEvidenceCheck.countDown();
            reset(transactionObserver);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(2);
    }

    @Test
    void confirmRejectsSecondSessionWithTheSameFrozenImportAsAnExactDuplicate() {
        long userId = createUser("exact@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var request = new TransactionImportPreviewRequest(null, mapping(accountId, categoryId));
        var firstPreview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request);
        confirmService.confirm(userId, firstPreview.importSessionId(), "exact-first",
                new TransactionImportConfirmRequest(firstPreview.previewToken(), List.of()));
        var secondPreview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request);
        List<String> warnings = secondPreview.rows().getFirst().warnings().stream().map(warning -> warning.id()).toList();

        assertThatThrownBy(() -> confirmService.confirm(userId, secondPreview.importSessionId(), "exact-second",
                new TransactionImportConfirmRequest(secondPreview.previewToken(), warnings)))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_EXACT_DUPLICATE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches", Integer.class)).isEqualTo(1);
    }

    @Test
    void confirmAcceptsTenThousandCanonicalRowsWithinTheFrozenTimeoutAndReplaysWithoutMutation() {
        long userId = createUser("ten-thousand@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        StringBuilder rows = new StringBuilder();
        for (int row = 1; row <= 10_000; row++) {
            rows.append("2026-08-01,expense,1.00,Cash,Food,row-").append(row).append('\n');
        }
        AtomicLong transactionDurationNanos = new AtomicLong();
        org.mockito.Mockito.doAnswer(invocation -> {
            transactionDurationNanos.set(invocation.getArgument(1));
            return invocation.callRealMethod();
        }).when(transactionObserver).committed(any(), org.mockito.ArgumentMatchers.anyLong());
        long started = System.nanoTime();
        var preview = previewService.create(userId, csv(rows.toString()),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        var first = confirmService.confirm(userId, preview.importSessionId(), "ten-thousand-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
        long elapsedMillis = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        var replay = confirmService.confirm(userId, preview.importSessionId(), "ten-thousand-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));

        System.out.println("phase3d-10k totalMillis=" + elapsedMillis + " transactionMillis="
                + java.time.Duration.ofNanos(transactionDurationNanos.get()).toMillis());
        assertThat(elapsedMillis).isLessThan(60_000);
        assertThat(transactionDurationNanos.get()).isPositive().isLessThan(java.time.Duration.ofSeconds(60).toNanos());
        assertThat(preview.summary().totalRows()).isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_items WHERE batch_id = ?", Integer.class, first.receipt().importBatchId())).isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batch_account_impacts WHERE batch_id = ?", Integer.class, first.receipt().importBatchId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("-10000.00");
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.receipt().resultDigest()).isEqualTo(first.receipt().resultDigest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(10_000);
    }

    @Test
    void confirmAcceptsTenThousandRowsAcrossOneHundredAccountsWithinTheFrozenTransactionTimeout() {
        long userId = createUser("ten-thousand-multi-account@example.com");
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        Map<String, Long> accounts = new java.util.LinkedHashMap<>();
        StringBuilder rows = new StringBuilder();
        for (int account = 1; account <= 100; account++) {
            String accountName = "Cash-" + account;
            accounts.put(accountName, createAccount(userId, accountName));
            for (int row = 1; row <= 100; row++) {
                rows.append("2026-08-01,expense,1.00,").append(accountName).append(",Food,")
                        .append(accountName).append('-').append(row).append('\n');
            }
        }
        AtomicLong transactionDurationNanos = new AtomicLong();
        org.mockito.Mockito.doAnswer(invocation -> {
            transactionDurationNanos.set(invocation.getArgument(1));
            return invocation.callRealMethod();
        }).when(transactionObserver).committed(any(), org.mockito.ArgumentMatchers.anyLong());
        long started = System.nanoTime();
        var preview = previewService.create(userId, csv(rows.toString()), new TransactionImportPreviewRequest(null,
                new TransactionImportMapping(Map.of("date", "date", "type", "type", "amount", "amount", "account", "account", "category", "category", "description", "description"),
                        Map.of("expense", "EXPENSE"), accounts, Map.of("Food", categoryId))));
        var first = confirmService.confirm(userId, preview.importSessionId(), "ten-thousand-multi-account-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
        long elapsedMillis = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        var replay = confirmService.confirm(userId, preview.importSessionId(), "ten-thousand-multi-account-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));

        System.out.println("phase3d-10k-multi-account rows=10000 accounts=100 rowsPerAccount=100 totalMillis=" + elapsedMillis
                + " transactionMillis=" + java.time.Duration.ofNanos(transactionDurationNanos.get()).toMillis());
        assertThat(elapsedMillis).isLessThan(60_000);
        assertThat(transactionDurationNanos.get()).isPositive().isLessThan(java.time.Duration.ofSeconds(60).toNanos());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_items WHERE batch_id = ?", Integer.class, first.receipt().importBatchId())).isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batch_account_impacts WHERE batch_id = ?", Integer.class, first.receipt().importBatchId())).isEqualTo(100);
        for (long accountId : accounts.values()) {
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("-100.00");
        }
        assertThat(first.receipt().accountImpacts().stream().map(impact -> impact.accountId()).toList())
                .isSorted();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM transaction_import_sessions WHERE id = ?", String.class, preview.importSessionId())).isEqualTo("CONSUMED");
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.receipt().resultDigest()).isEqualTo(first.receipt().resultDigest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(10_000);
        reset(transactionObserver);
    }

    @Test
    void concurrentConfirmForTheSameSessionProducesOneFinancialCommitAndOneReplay() throws Exception {
        long userId = createUser("concurrent@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> confirmConcurrently(userId, preview, ready, start));
            var second = executor.submit(() -> confirmConcurrently(userId, preview, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var a = first.get(30, TimeUnit.SECONDS);
            var b = second.get(30, TimeUnit.SECONDS);
            assertThat(List.of(a.idempotentReplay(), b.idempotentReplay())).containsExactlyInAnyOrder(false, true);
            assertThat(a.receipt()).isEqualTo(b.receipt());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("-10.00");
    }

    @Test
    void secondConfirmWaitsOnTheSessionRowLockThenReplaysTheCommittedReceipt() throws Exception {
        long userId = createUser("deterministic-confirm@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger lockAcquisitions = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (lockAcquisitions.incrementAndGet() == 1) {
                firstLockAcquired.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("first Confirm was not released");
            }
            return invocation.callRealMethod();
        }).when(transactionObserver).sessionLockAcquired(any());

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<com.financeos.module.importing.dto.TransactionImportConfirmResponse> first = executor.submit(() -> confirmService.confirm(userId,
                    preview.importSessionId(), "deterministic-confirm-key", new TransactionImportConfirmRequest(preview.previewToken(), List.of())));
            assertThat(firstLockAcquired.await(10, TimeUnit.SECONDS)).isTrue();
            Future<com.financeos.module.importing.dto.TransactionImportConfirmResponse> second = executor.submit(() -> confirmService.confirm(userId,
                    preview.importSessionId(), "deterministic-confirm-key", new TransactionImportConfirmRequest(preview.previewToken(), List.of())));
            awaitSessionLockWait();
            assertThat(lockAcquisitions.get()).isEqualTo(1);
            assertThat(second.isDone()).isFalse();

            releaseFirst.countDown();
            var committed = first.get(20, TimeUnit.SECONDS);
            var replay = second.get(20, TimeUnit.SECONDS);
            assertThat(committed.idempotentReplay()).isFalse();
            assertThat(replay.idempotentReplay()).isTrue();
            assertThat(replay.receipt()).isEqualTo(committed.receipt());
            assertThat(replay.receipt().resultDigest()).isEqualTo(committed.receipt().resultDigest());
        } finally {
            releaseFirst.countDown();
            reset(transactionObserver);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("-10.00");
    }

    @Test
    void cleanupCannotInvalidateAConfirmThatAlreadyHoldsTheSessionGuard() throws Exception {
        long userId = createUser("cleanup-race@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            locked.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Confirm was not released");
            return invocation.callRealMethod();
        }).when(transactionObserver).sessionLockAcquired(any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<com.financeos.module.importing.dto.TransactionImportConfirmResponse> confirm = executor.submit(() -> confirmService.confirm(userId,
                    preview.importSessionId(), "cleanup-race-key", new TransactionImportConfirmRequest(preview.previewToken(), List.of())));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            cleanupService.cleanupExpired();
            release.countDown();
            var first = confirm.get(20, TimeUnit.SECONDS);
            var replay = confirmService.confirm(userId, preview.importSessionId(), "cleanup-race-key",
                    new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
            assertThat(first.idempotentReplay()).isFalse();
            assertThat(replay.idempotentReplay()).isTrue();
            assertThat(replay.receipt()).isEqualTo(first.receipt());
        } finally {
            release.countDown();
            reset(transactionObserver);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT temporary_storage_reference IS NULL AND plan_storage_reference IS NULL FROM transaction_import_sessions WHERE id = ?", Boolean.class,
                preview.importSessionId())).isTrue();
    }

    @Test
    void staleRevisionAndForeignUserWarningOrTokenInputsFailClosed() {
        long owner = createUser("matrix-owner@example.com");
        long foreign = createUser("matrix-foreign@example.com");
        long ownerAccount = createAccount(owner);
        long ownerCategory = createCategory(owner, "Food", "EXPENSE");
        long foreignAccount = createAccount(foreign);
        long foreignCategory = createCategory(foreign, "Food", "EXPENSE");
        TransactionImportMapping ownerMapping = mapping(ownerAccount, ownerCategory);
        var stale = previewService.create(owner, csv("2026-08-01,expense,10.00,Cash,Food,stale"), new TransactionImportPreviewRequest(null, ownerMapping));
        var revised = previewService.updateMapping(owner, stale.importSessionId(), ownerMapping);
        assertThat(revised.revision()).isGreaterThan(stale.revision());
        assertThatThrownBy(() -> confirmService.confirm(owner, stale.importSessionId(), "stale-revision-key",
                new TransactionImportConfirmRequest(stale.previewToken(), List.of())))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_PREVIEW_STALE");

        var ownerWarnings = previewService.create(owner, csv("2026-08-02,expense,10.00,Cash,Food,warning\n2026-08-02,expense,10.00,Cash,Food,warning"),
                new TransactionImportPreviewRequest(null, ownerMapping));
        List<String> foreignWarningIds = ownerWarnings.rows().stream().flatMap(row -> row.warnings().stream()).map(warning -> warning.id()).toList();
        var foreignPreview = previewService.create(foreign, csv("2026-08-03,expense,10.00,Cash,Food,foreign\n2026-08-03,expense,10.00,Cash,Food,foreign"),
                new TransactionImportPreviewRequest(null, mapping(foreignAccount, foreignCategory)));
        assertThatThrownBy(() -> confirmService.confirm(foreign, foreignPreview.importSessionId(), "foreign-warning-key",
                new TransactionImportConfirmRequest(foreignPreview.previewToken(), foreignWarningIds)))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_WARNING_ACK_REQUIRED");
        assertThatThrownBy(() -> confirmService.confirm(foreign, foreignPreview.importSessionId(), "foreign-token-key",
                new TransactionImportConfirmRequest(ownerWarnings.previewToken(), List.of())))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_PREVIEW_STALE");
    }

    @Test
    void accountOrCategoryThatBecomesForeignAfterPreviewFailsClosedWithoutFinancialWrites() {
        long owner = createUser("resource-owner@example.com");
        long foreign = createUser("resource-foreign@example.com");
        long accountId = createAccount(owner);
        long categoryId = createCategory(owner, "Food", "EXPENSE");
        var accountPreview = previewService.create(owner, csv("2026-08-01,expense,10.00,Cash,Food,account"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        jdbcTemplate.update("UPDATE accounts SET user_id = ? WHERE id = ?", foreign, accountId);
        assertThatThrownBy(() -> confirmService.confirm(owner, accountPreview.importSessionId(), "foreign-account-key",
                new TransactionImportConfirmRequest(accountPreview.previewToken(), List.of())))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_PREVIEW_STALE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, owner)).isZero();

        long replacementAccount = createAccount(owner);
        long replacementCategory = createCategory(owner, "Bills", "EXPENSE");
        var categoryPreview = previewService.create(owner, csv("2026-08-02,expense,10.00,Cash,Bills,category"),
                new TransactionImportPreviewRequest(null, mapping(replacementAccount, replacementCategory)));
        jdbcTemplate.update("UPDATE categories SET user_id = ? WHERE id = ?", foreign, replacementCategory);
        assertThatThrownBy(() -> confirmService.confirm(owner, categoryPreview.importSessionId(), "foreign-category-key",
                new TransactionImportConfirmRequest(categoryPreview.previewToken(), List.of())))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_PREVIEW_STALE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, owner)).isZero();
    }

    @Test
    void foreignUserCannotConfirmOrReadAnotherUsersSessionOrBatch() {
        long owner = createUser("owner@example.com");
        long foreign = createUser("foreign@example.com");
        long accountId = createAccount(owner);
        long categoryId = createCategory(owner, "Food", "EXPENSE");
        var preview = previewService.create(owner, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));

        assertThatThrownBy(() -> confirmService.confirm(foreign, preview.importSessionId(), "foreign-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of())))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_SESSION_NOT_FOUND");
        var ownerResult = confirmService.confirm(owner, preview.importSessionId(), "owner-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
        assertThatThrownBy(() -> confirmService.getReceipt(foreign, ownerResult.receipt().importBatchId()))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_BATCH_NOT_FOUND");
    }

    @Test
    void databaseFaultsAtEveryConfirmPersistenceStageRollbackTheEntireFinancialMutation() {
        assertRollbackForFault("transactions", "INSERT");
        assertRollbackForFault("accounts", "UPDATE");
        assertRollbackForFault("transaction_import_batches", "INSERT");
        assertRollbackForFault("transaction_import_items", "INSERT");
        assertRollbackForFault("transaction_import_batch_account_impacts", "INSERT");
        assertRollbackForFault("transaction_import_sessions", "UPDATE");
    }

    @Test
    void retryRecoversTheAuthoritativeReceiptWhenCommitSucceededButPostCommitDeliveryFailed() {
        long userId = createUser("unknown-commit@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        doThrow(new IllegalStateException("simulated response delivery failure after commit"))
                .when(cleanupService).cleanupCommittedPayloads(any());

        assertThatThrownBy(() -> confirmService.confirm(userId, preview.importSessionId(), "unknown-commit-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("after commit");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM transaction_import_sessions WHERE id = ?", String.class, preview.importSessionId()))
                .isEqualTo("CONSUMED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("-10.00");

        reset(cleanupService);
        var retry = confirmService.confirm(userId, preview.importSessionId(), "unknown-commit-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
        assertThat(retry.idempotentReplay()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("-10.00");
    }

    @Test
    void concurrentCancelAndConfirmAllowExactlyOneTerminalOutcome() throws Exception {
        long userId = createUser("cancel-race@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var confirm = executor.submit(() -> terminalOutcome(ready, start, () -> confirmService.confirm(userId,
                    preview.importSessionId(), "cancel-race-key", new TransactionImportConfirmRequest(preview.previewToken(), List.of()))));
            var cancel = executor.submit(() -> terminalOutcome(ready, start, () -> {
                previewService.cancel(userId, preview.importSessionId()); return null;
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = List.of(confirm.get(30, TimeUnit.SECONDS), cancel.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("SUCCESS"::equals).count()).isEqualTo(1);
            if (outcomes.getFirst().equals("SUCCESS")) {
                assertThat(jdbcTemplate.queryForObject("SELECT status FROM transaction_import_sessions WHERE id = ?", String.class, preview.importSessionId())).isEqualTo("CONSUMED");
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
            } else {
                assertThat(jdbcTemplate.queryForObject("SELECT status FROM transaction_import_sessions WHERE id = ?", String.class, preview.importSessionId())).isEqualTo("CANCELLED");
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isZero();
            }
        }
    }

    @Test
    void duplicateCandidateRemovalRequiresANewPreviewButAnUnrelatedTransactionDoesNot() {
        long userId = createUser("candidate-edges@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        insertTransaction(userId, accountId, categoryId, "2026-08-01 00:00:00", "candidate");
        var candidatePreview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,candidate"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        List<String> candidateWarnings = candidatePreview.rows().getFirst().warnings().stream().map(warning -> warning.id()).toList();
        jdbcTemplate.update("DELETE FROM transactions WHERE user_id = ?", userId);
        assertThatThrownBy(() -> confirmService.confirm(userId, candidatePreview.importSessionId(), "candidate-removed-key",
                new TransactionImportConfirmRequest(candidatePreview.previewToken(), candidateWarnings)))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_DUPLICATE_EVIDENCE_CHANGED");

        var unrelatedPreview = previewService.create(userId, csv("2026-08-02,expense,10.00,Cash,Food,target"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        insertTransaction(userId, accountId, categoryId, "2026-08-03 00:00:00", "unrelated");
        var confirmed = confirmService.confirm(userId, unrelatedPreview.importSessionId(), "unrelated-key",
                new TransactionImportConfirmRequest(unrelatedPreview.previewToken(), List.of()));
        assertThat(confirmed.idempotentReplay()).isFalse();
    }

    @Test
    void confirmAndManualTransactionSerializeSharedAccountBalanceWrites() throws Exception {
        long userId = createUser("manual-race@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,imported"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var imported = executor.submit(() -> { ready.countDown(); start.await(5, TimeUnit.SECONDS); return confirmService.confirm(userId,
                    preview.importSessionId(), "manual-race-key", new TransactionImportConfirmRequest(preview.previewToken(), List.of())); });
            var manual = executor.submit(() -> { ready.countDown(); start.await(5, TimeUnit.SECONDS); return transactionService.create(userId,
                    new TransactionRequest(accountId, categoryId, "EXPENSE", new java.math.BigDecimal("10.00"), "CNY", "manual", LocalDateTime.of(2026, 8, 2, 0, 0))); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(imported.get(20, TimeUnit.SECONDS).idempotentReplay()).isFalse();
            assertThat(manual.get(20, TimeUnit.SECONDS).description()).isEqualTo("manual");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("-20.00");
    }

    @Test
    void oppositeFrozenAccountOrdersUseAscendingDatabaseLocksWithoutDeadlock() throws Exception {
        long userId = createUser("opposite-order@example.com");
        long firstAccount = createAccount(userId);
        long secondAccount = jdbcTemplate.queryForObject("INSERT INTO accounts (user_id, name, type, currency, balance, status) VALUES (?, 'Bank', 'BANK', 'CNY', 0, 'ACTIVE') RETURNING id", Long.class, userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        TransactionImportMapping mapping = new TransactionImportMapping(
                Map.of("date", "date", "type", "type", "amount", "amount", "account", "account", "category", "category", "description", "description"),
                Map.of("expense", "EXPENSE"), Map.of("Cash", firstAccount, "Bank", secondAccount), Map.of("Food", categoryId));
        var first = previewService.create(userId, csv("2026-08-01,expense,1.00,Cash,Food,a\n2026-08-02,expense,1.00,Bank,Food,b"), new TransactionImportPreviewRequest(null, mapping));
        var second = previewService.create(userId, csv("2026-08-03,expense,1.00,Bank,Food,c\n2026-08-04,expense,1.00,Cash,Food,d"), new TransactionImportPreviewRequest(null, mapping));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> confirmConcurrently(userId, first, ready, start, "opposite-a"));
            var b = executor.submit(() -> confirmConcurrently(userId, second, ready, start, "opposite-b"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS).idempotentReplay()).isFalse();
            assertThat(b.get(20, TimeUnit.SECONDS).idempotentReplay()).isFalse();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, firstAccount)).isEqualTo("-2.00");
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, secondAccount)).isEqualTo("-2.00");
    }

    @Test
    void forgedOrCrossSessionWarningIdsAndTokensFailClosed() {
        long userId = createUser("warning-token@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var first = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch\n2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        var second = previewService.create(userId, csv("2026-08-02,expense,10.00,Cash,Food,dinner\n2026-08-02,expense,10.00,Cash,Food,dinner"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        List<String> firstWarnings = first.rows().stream().flatMap(row -> row.warnings().stream()).map(warning -> warning.id()).toList();
        assertThatThrownBy(() -> confirmService.confirm(userId, first.importSessionId(), "forged-warning",
                new TransactionImportConfirmRequest(first.previewToken(), List.of("forged")))).isInstanceOf(BusinessException.class).hasMessage("IMPORT_WARNING_ACK_REQUIRED");
        assertThatThrownBy(() -> confirmService.confirm(userId, second.importSessionId(), "cross-warning",
                new TransactionImportConfirmRequest(second.previewToken(), firstWarnings))).isInstanceOf(BusinessException.class).hasMessage("IMPORT_WARNING_ACK_REQUIRED");
        assertThatThrownBy(() -> confirmService.confirm(userId, first.importSessionId(), "tampered-token",
                new TransactionImportConfirmRequest(first.previewToken() + "x", firstWarnings))).isInstanceOf(BusinessException.class).hasMessage("IMPORT_PREVIEW_STALE");
        assertThatThrownBy(() -> confirmService.confirm(userId, second.importSessionId(), "cross-token",
                new TransactionImportConfirmRequest(first.previewToken(), firstWarnings))).isInstanceOf(BusinessException.class).hasMessage("IMPORT_PREVIEW_STALE");
    }

    @Test
    void idempotencyKeysAreScopedPerUser() {
        long firstUser = createUser("first-key@example.com");
        long secondUser = createUser("second-key@example.com");
        long firstAccount = createAccount(firstUser);
        long secondAccount = createAccount(secondUser);
        long firstCategory = createCategory(firstUser, "Food", "EXPENSE");
        long secondCategory = createCategory(secondUser, "Food", "EXPENSE");
        var first = previewService.create(firstUser, csv("2026-08-01,expense,10.00,Cash,Food,one"), new TransactionImportPreviewRequest(null, mapping(firstAccount, firstCategory)));
        var second = previewService.create(secondUser, csv("2026-08-01,expense,10.00,Cash,Food,two"), new TransactionImportPreviewRequest(null, mapping(secondAccount, secondCategory)));
        var a = confirmService.confirm(firstUser, first.importSessionId(), "same-user-scoped-key", new TransactionImportConfirmRequest(first.previewToken(), List.of()));
        var b = confirmService.confirm(secondUser, second.importSessionId(), "same-user-scoped-key", new TransactionImportConfirmRequest(second.previewToken(), List.of()));
        assertThat(a.receipt().importBatchId()).isNotEqualTo(b.receipt().importBatchId());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE idempotency_key = ?", Integer.class, "same-user-scoped-key")).isEqualTo(2);
    }

    @Test
    void committedPayloadCleanupKeepsTheBatchAndAuthoritativeReplayAvailable() {
        long userId = createUser("committed-cleanup@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        var first = confirmService.confirm(userId, preview.importSessionId(), "committed-cleanup-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
        cleanupService.cleanupExpired();
        var replay = confirmService.confirm(userId, preview.importSessionId(), "committed-cleanup-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.receipt()).isEqualTo(first.receipt());
        assertThat(jdbcTemplate.queryForObject("SELECT temporary_storage_reference IS NULL AND plan_storage_reference IS NULL FROM transaction_import_sessions WHERE id = ?", Boolean.class, preview.importSessionId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE id = ?", Integer.class, first.receipt().importBatchId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void namedIdempotencyFallbackReplaysOnlyTheCurrentSessionsCommittedAuthoritativeReceipt() {
        long userId = createUser("fallback-identity@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var committed = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,committed"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        var first = confirmService.confirm(userId, committed.importSessionId(), "fallback-identity-key",
                new TransactionImportConfirmRequest(committed.previewToken(), List.of()));
        var otherSession = previewService.create(userId, csv("2026-08-02,expense,11.00,Cash,Food,other"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        String committedHash = jdbcTemplate.queryForObject("SELECT request_hash FROM transaction_import_batches WHERE id = ?", String.class,
                first.receipt().importBatchId());

        assertThatThrownBy(() -> invokeRecovery(userId, otherSession.importSessionId(), "fallback-identity-key", committedHash,
                namedConstraint("23505", "uk_transaction_import_batches_user_idempotency")))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_CONFIRM_INCONSISTENT");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void namedIdempotencyFallbackReconstructsTheSamePersistedReceiptForTheMatchingSessionAndIntent() {
        long userId = createUser("fallback-positive@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,committed"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        var first = confirmService.confirm(userId, preview.importSessionId(), "fallback-positive-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
        String committedHash = jdbcTemplate.queryForObject("SELECT request_hash FROM transaction_import_batches WHERE id = ?", String.class,
                first.receipt().importBatchId());

        var recovered = invokeRecovery(userId, preview.importSessionId(), "fallback-positive-key", committedHash,
                namedConstraint("23505", "uk_transaction_import_batches_user_idempotency"));

        assertThat(recovered.idempotentReplay()).isTrue();
        assertThat(recovered.receipt()).isEqualTo(first.receipt());
    }

    @Test
    void exactDuplicateFallbackFailsClosedWhenNoMatchingCommittedDuplicateExists() {
        long userId = createUser("fallback-exact-missing@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,only-preview"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));

        assertThatThrownBy(() -> invokeRecovery(userId, preview.importSessionId(), "fallback-exact-missing-key", "0".repeat(64),
                namedConstraint("23505", "uk_transaction_import_batches_exact_duplicate")))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_CONFIRM_INCONSISTENT");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    void expectedExactDuplicateFallbackReturnsConflictButNeverAReplay() {
        long userId = createUser("fallback-exact@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var request = new TransactionImportPreviewRequest(null, mapping(accountId, categoryId));
        var firstPreview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,same"), request);
        confirmService.confirm(userId, firstPreview.importSessionId(), "fallback-exact-first",
                new TransactionImportConfirmRequest(firstPreview.previewToken(), List.of()));
        var duplicatePreview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,same"), request);

        assertThatThrownBy(() -> invokeRecovery(userId, duplicatePreview.importSessionId(), "fallback-exact-second", "0".repeat(64),
                namedConstraint("23505", "uk_transaction_import_batches_exact_duplicate")))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_EXACT_DUPLICATE");
    }

    @Test
    void unrelatedOrUnknownConstraintsFailClosedInsteadOfReturningAReplay() {
        long userId = createUser("fallback-negative@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,negative"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));

        for (DataIntegrityViolationException exception : List.of(
                namedConstraint("23505", "uk_unrelated_table_value"),
                namedConstraint("23503", "fk_transaction_import_unrelated"),
                namedConstraint("23514", "ck_transaction_import_unrelated"),
                namedConstraint("23505", "uk_transaction_import_unknown"))) {
            assertThatThrownBy(() -> invokeRecovery(userId, preview.importSessionId(), "fallback-negative-key", "0".repeat(64), exception))
                    .isInstanceOf(BusinessException.class).hasMessage("IMPORT_CONFIRM_INCONSISTENT");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    void namedConstraintFallbackRequiresTheExpectedSqlStateSchemaTableAndConstraint() {
        long userId = createUser("fallback-relation-identity@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,committed"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        var first = confirmService.confirm(userId, preview.importSessionId(), "fallback-relation-identity-key",
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
        String requestHash = jdbcTemplate.queryForObject("SELECT request_hash FROM transaction_import_batches WHERE id = ?", String.class,
                first.receipt().importBatchId());

        for (DataIntegrityViolationException exception : List.of(
                namedConstraint("23505", "other_schema", "transaction_import_batches", "uk_transaction_import_batches_user_idempotency"),
                namedConstraint("23505", "public", "transaction_import_items", "uk_transaction_import_batches_user_idempotency"),
                namedConstraint("23514", "public", "transaction_import_batches", "uk_transaction_import_batches_user_idempotency"))) {
            assertThatThrownBy(() -> invokeRecovery(userId, preview.importSessionId(), "fallback-relation-identity-key", requestHash, exception))
                    .isInstanceOf(BusinessException.class).hasMessage("IMPORT_CONFIRM_INCONSISTENT");
        }
    }

    private long createUser(String email) { return jdbcTemplate.queryForObject("INSERT INTO users (username, password_hash, email) VALUES (?, 'password', ?) RETURNING id", Long.class, email, email); }
    private long createAccount(long userId) { return createAccount(userId, "Cash"); }
    private long createAccount(long userId, String name) { return jdbcTemplate.queryForObject("INSERT INTO accounts (user_id, name, type, currency, balance, status) VALUES (?, ?, 'CASH', 'CNY', 0, 'ACTIVE') RETURNING id", Long.class, userId, name); }
    private long createCategory(long userId, String name, String type) { return jdbcTemplate.queryForObject("INSERT INTO categories (user_id, name, type, is_system) VALUES (?, ?, ?, false) RETURNING id", Long.class, userId, name, type); }
    private TransactionImportMapping mapping(long accountId, long expenseCategoryId) { return new TransactionImportMapping(Map.of("date", "date", "type", "type", "amount", "amount", "account", "account", "category", "category", "description", "description"), Map.of("expense", "EXPENSE"), Map.of("Cash", accountId), Map.of("Food", expenseCategoryId)); }
    private MockMultipartFile csv(String content) { return new MockMultipartFile("file", "statement.csv", "text/csv", ("date,type,amount,account,category,description\n" + content).getBytes(StandardCharsets.UTF_8)); }
    private void insertTransaction(long userId, long accountId, long categoryId, String transactedAt, String description) {
        jdbcTemplate.update("INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, description, transacted_at) VALUES (?, ?, ?, 'EXPENSE', 10.00, 'CNY', ?, ?::timestamp)",
                userId, accountId, categoryId, description, transactedAt);
    }
    private com.financeos.module.importing.dto.TransactionImportConfirmResponse confirmConcurrently(long userId,
                                                                                                      com.financeos.module.importing.dto.TransactionImportPreviewResponse preview,
                                                                                                      CountDownLatch ready, CountDownLatch start) throws Exception {
        return confirmConcurrently(userId, preview, ready, start, "concurrent-key");
    }
    private com.financeos.module.importing.dto.TransactionImportConfirmResponse confirmConcurrently(long userId,
                                                                                                      com.financeos.module.importing.dto.TransactionImportPreviewResponse preview,
                                                                                                      CountDownLatch ready, CountDownLatch start, String idempotencyKey) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent test did not start");
        return confirmService.confirm(userId, preview.importSessionId(), idempotencyKey,
                new TransactionImportConfirmRequest(preview.previewToken(), List.of()));
    }
    private void assertRollbackForFault(String table, String event) {
        long userId = createUser("f" + Integer.toUnsignedString(table.hashCode(), 36) + "@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"),
                new TransactionImportPreviewRequest(null, mapping(accountId, categoryId)));
        String trigger = "phase_3d_fail_" + table;
        jdbcTemplate.execute("CREATE OR REPLACE FUNCTION phase_3d_confirm_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'phase-3d injected fault'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER " + trigger + " BEFORE " + event + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION phase_3d_confirm_fault()");
        try {
            assertThatThrownBy(() -> confirmService.confirm(userId, preview.importSessionId(), "fault-" + table,
                    new TransactionImportConfirmRequest(preview.previewToken(), List.of()))).isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("0.00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_items WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batch_account_impacts WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM transaction_import_sessions WHERE id = ?", String.class, preview.importSessionId())).isEqualTo("PREVIEW_READY");
    }
    private String terminalOutcome(CountDownLatch ready, CountDownLatch start, java.util.concurrent.Callable<?> action) throws Exception {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        try { action.call(); return "SUCCESS"; }
        catch (BusinessException exception) { return exception.getMessage(); }
    }
    private com.financeos.module.importing.dto.TransactionImportConfirmResponse invokeRecovery(long userId, UUID sessionId, String key,
                                                                                                  String requestHash, DataIntegrityViolationException exception) {
        return ReflectionTestUtils.invokeMethod(confirmService, "recoverKnownUnique", userId, sessionId, key, requestHash, exception);
    }
    private DataIntegrityViolationException namedConstraint(String sqlState, String constraint) {
        return namedConstraint(sqlState, "public", "transaction_import_batches", constraint);
    }
    private DataIntegrityViolationException namedConstraint(String sqlState, String schema, String table, String constraint) {
        String message = "SERROR\0VERROR\0C" + sqlState + "\0Mphase-3d controlled constraint branch\0s" + schema
                + "\0t" + table + "\0n" + constraint + "\0\0";
        return new DataIntegrityViolationException("controlled PostgreSQL constraint", new PSQLException(new ServerErrorMessage(message)));
    }
    private void awaitSessionLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock'
                      AND query LIKE 'SELECT * FROM transaction_import_sessions%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("second Confirm never waited on transaction_import_sessions FOR UPDATE");
    }
}
