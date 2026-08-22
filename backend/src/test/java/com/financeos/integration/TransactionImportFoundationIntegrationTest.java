package com.financeos.integration;

import com.financeos.module.importing.entity.TransactionImportBatch;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.entity.TransactionImportItem;
import com.financeos.module.importing.mapper.TransactionImportBatchMapper;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.mapper.TransactionImportItemMapper;
import com.financeos.module.importing.service.TransactionImportSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionImportFoundationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionImportSessionMapper sessionMapper;

    @Autowired
    private TransactionImportBatchMapper batchMapper;

    @Autowired
    private TransactionImportItemMapper itemMapper;

    @Autowired
    private TransactionImportSessionService sessionService;

    @Test
    void persistsUserScopedSessionMetadataAndPreallocatedBatchIdentity() {
        long firstUser = createUser("import-owner@example.com");
        UUID sessionId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        TransactionImportSession session = session(sessionId, firstUser, batchId);

        sessionMapper.insert(session);

        assertThat(sessionMapper.findByIdAndUserId(sessionId, firstUser)).isNotNull()
                .extracting(TransactionImportSession::getFileDigest, TransactionImportSession::getPreallocatedBatchId)
                .containsExactly("a".repeat(64), batchId);
        assertThat(sessionMapper.findByIdAndUserId(sessionId, createUser("import-other@example.com"))).isNull();
    }

    @Test
    void enforcesUserScopedIdempotencyAndExactBatchUniqueness() {
        long firstUser = createUser("import-batch-owner@example.com");
        long secondUser = createUser("import-batch-other@example.com");
        batchMapper.insert(batch(UUID.randomUUID(), firstUser, "same-key", "a".repeat(64)));

        assertThatThrownBy(() -> batchMapper.insert(batch(UUID.randomUUID(), firstUser, "same-key", "b".repeat(64))))
                .isInstanceOf(DuplicateKeyException.class);
        batchMapper.insert(batch(UUID.randomUUID(), secondUser, "same-key", "a".repeat(64)));
        assertThatThrownBy(() -> batchMapper.insert(batch(UUID.randomUUID(), firstUser, "different-key", "a".repeat(64))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void rejectsAnItemThatAttemptsToReferenceAnotherUsersTransaction() {
        long batchOwner = createUser("import-item-owner@example.com");
        long transactionOwner = createUser("import-item-transaction-owner@example.com");
        UUID batchId = UUID.randomUUID();
        batchMapper.insert(batch(batchId, batchOwner, "item-key", "a".repeat(64)));
        long accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance, status)
                VALUES (?, 'Cash', 'CASH', 'CNY', 0, 'ACTIVE') RETURNING id
                """, Long.class, transactionOwner);
        long categoryId = jdbcTemplate.queryForObject("""
                INSERT INTO categories (user_id, name, type, is_system) VALUES (?, 'Food', 'EXPENSE', false) RETURNING id
                """, Long.class, transactionOwner);
        long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, transacted_at)
                VALUES (?, ?, ?, 'EXPENSE', 1, 'CNY', CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, transactionOwner, accountId, categoryId);
        TransactionImportItem item = new TransactionImportItem();
        item.setUserId(batchOwner);
        item.setBatchId(batchId);
        item.setSourceRowNumber(1);
        item.setCanonicalRowFingerprint("f".repeat(64));
        item.setWarningCodes("");
        item.setCreatedTransactionId(transactionId);

        assertThatThrownBy(() -> itemMapper.insert(item)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsAtMostOnePostgresBackedConsumeTransitionForTheSameSession() throws Exception {
        long userId = createUser("import-guard-owner@example.com");
        UUID sessionId = UUID.randomUUID();
        TransactionImportSession session = session(sessionId, userId, UUID.randomUUID());
        session.setExpiresAt(Instant.now().plusSeconds(60));
        sessionMapper.insert(session);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> consumeAfterStart(ready, start, userId, sessionId));
            Future<Boolean> second = executor.submit(() -> consumeAfterStart(ready, start, userId, sessionId));
            ready.await();
            start.countDown();

            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean consumeAfterStart(CountDownLatch ready, CountDownLatch start, long userId, UUID sessionId) throws Exception {
        ready.countDown();
        start.await();
        try {
            return sessionService.withReadySessionGuard(userId, sessionId, ignored -> true);
        } catch (com.financeos.common.BusinessException exception) {
            assertThat(exception.getCode()).isEqualTo(409);
            return false;
        }
    }

    private long createUser(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, password_hash, email) VALUES (?, 'password', ?)
                RETURNING id
                """, Long.class, email, email);
    }

    private TransactionImportSession session(UUID sessionId, long userId, UUID batchId) {
        TransactionImportSession session = new TransactionImportSession();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setPreallocatedBatchId(batchId);
        session.setStatus("PREVIEW_READY");
        session.setOriginalFileName("statement.csv");
        session.setContentType("text/csv");
        session.setFileSize(11L);
        session.setFileDigest("a".repeat(64));
        session.setTemporaryStorageReference(UUID.randomUUID().toString());
        session.setOptionsDigest("b".repeat(64));
        session.setMappingDigest("c".repeat(64));
        session.setNormalizedRowsDigest("d".repeat(64));
        session.setPlanStorageReference(UUID.randomUUID().toString());
        session.setRevision(1);
        session.setExpiresAt(Instant.parse("2026-08-14T12:15:00Z"));
        return session;
    }

    private TransactionImportBatch batch(UUID id, long userId, String key, String fileDigest) {
        TransactionImportSession session = session(UUID.randomUUID(), userId, id);
        sessionMapper.insert(session);
        TransactionImportBatch batch = new TransactionImportBatch();
        batch.setId(id);
        batch.setUserId(userId);
        batch.setSessionId(session.getId());
        batch.setOriginalFileName("statement.csv");
        batch.setFileDigest(fileDigest);
        batch.setMappingDigest("c".repeat(64));
        batch.setOptionsDigest("b".repeat(64));
        batch.setNormalizedRowsDigest("d".repeat(64));
        batch.setContractVersion("3.0");
        batch.setIdempotencyKey(key);
        batch.setRequestHash("e".repeat(64));
        batch.setResultDigest("f".repeat(64));
        batch.setStatus("CONFIRMED");
        batch.setTotalRows(1);
        batch.setWarningCount(0);
        batch.setConfirmedAt(Instant.parse("2026-08-14T12:01:00Z"));
        return batch;
    }
}
