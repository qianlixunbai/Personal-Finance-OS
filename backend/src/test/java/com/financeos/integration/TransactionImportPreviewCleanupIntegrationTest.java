package com.financeos.integration;

import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.storage.StoredImportFile;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@TestPropertySource(properties = "finance.import.cleanup.enabled=true")
class TransactionImportPreviewCleanupIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionImportSessionMapper sessionMapper;

    @Autowired
    private TemporaryImportFileStorage storage;

    @Autowired
    @Qualifier("transactionImportPreviewPlanStorage")
    private TemporaryImportFileStorage planStorage;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private com.financeos.module.importing.service.TransactionImportPreviewCleanupScheduler cleanupScheduler;

    @Test
    void expiresPersistedSessionAndDeletesItsPrivatePayloads() {
        long userId = createUser("import-cleanup@example.com");
        StoredImportFile upload = storage.save(bytes("date,type,amount,account,category\n2026-08-14,expense,1,Cash,Food\n"), "statement.csv");
        StoredImportFile plan = planStorage.save(bytes("{}"), "preview-plan.json");
        TransactionImportSession session = expiredSession(userId, upload.reference(), plan.reference());
        sessionMapper.insert(session);

        assertThat(storage.exists(upload.reference())).isTrue();
        assertThat(planStorage.exists(plan.reference())).isTrue();

        assertThatCode(this::invokeCleanup).doesNotThrowAnyException();

        TransactionImportSession cleaned = sessionMapper.findByIdAndUserId(session.getId(), userId);
        assertThat(cleaned.getStatus()).isEqualTo("EXPIRED");
        assertThat(cleaned.getTemporaryStorageReference()).isNull();
        assertThat(cleaned.getPlanStorageReference()).isNull();
        assertThat(storage.exists(upload.reference())).isFalse();
        assertThat(planStorage.exists(plan.reference())).isFalse();
        assertThatCode(this::invokeCleanup).doesNotThrowAnyException();
    }

    @Test
    void schedulerPathCleansExpiredPersistedSessionAndItsPayloads() {
        long userId = createUser("import-scheduled-cleanup@example.com");
        StoredImportFile upload = storage.save(bytes("date,type,amount,account,category\n2026-08-14,expense,1,Cash,Food\n"), "statement.csv");
        StoredImportFile plan = planStorage.save(bytes("{}"), "preview-plan.json");
        TransactionImportSession session = expiredSession(userId, upload.reference(), plan.reference());
        sessionMapper.insert(session);

        cleanupScheduler.runCleanup();

        TransactionImportSession cleaned = sessionMapper.findByIdAndUserId(session.getId(), userId);
        assertThat(cleaned.getStatus()).isEqualTo("EXPIRED");
        assertThat(cleaned.getTemporaryStorageReference()).isNull();
        assertThat(cleaned.getPlanStorageReference()).isNull();
        assertThat(storage.exists(upload.reference())).isFalse();
        assertThat(planStorage.exists(plan.reference())).isFalse();
    }

    private void invokeCleanup() throws Exception {
        Class<?> cleanupType = Class.forName("com.financeos.module.importing.service.TransactionImportPreviewCleanupService");
        Object cleanupService = applicationContext.getBean(cleanupType);
        cleanupType.getMethod("cleanupExpired").invoke(cleanupService);
    }

    private long createUser(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, password_hash, email) VALUES (?, 'password', ?)
                RETURNING id
                """, Long.class, email, email);
    }

    private TransactionImportSession expiredSession(long userId, String uploadReference, String planReference) {
        TransactionImportSession session = new TransactionImportSession();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setPreallocatedBatchId(UUID.randomUUID());
        session.setStatus("PREVIEW_READY");
        session.setRevision(1);
        session.setOriginalFileName("statement.csv");
        session.setContentType("text/csv");
        session.setFileSize(70L);
        session.setFileDigest("a".repeat(64));
        session.setTemporaryStorageReference(uploadReference);
        session.setOptionsDigest("b".repeat(64));
        session.setMappingDigest("c".repeat(64));
        session.setNormalizedRowsDigest("d".repeat(64));
        session.setPlanStorageReference(planReference);
        session.setExpiresAt(Instant.now().minusSeconds(1));
        return session;
    }

    private ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
