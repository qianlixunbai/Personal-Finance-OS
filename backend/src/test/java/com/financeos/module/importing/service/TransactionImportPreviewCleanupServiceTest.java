package com.financeos.module.importing.service;

import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionImportPreviewCleanupServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void keepsExpiredPayloadReferencesEligibleForTheNextCleanupCycleWhenDeletionFails() {
        TransactionImportSessionMapper sessions = mock(TransactionImportSessionMapper.class);
        TemporaryImportFileStorage uploads = mock(TemporaryImportFileStorage.class);
        TemporaryImportFileStorage plans = mock(TemporaryImportFileStorage.class);
        TransactionImportSession session = expiredSession();
        when(sessions.findExpiredForCleanup(CLOCK.instant())).thenReturn(List.of(session));
        when(uploads.delete("upload-ref")).thenThrow(new RuntimeException("locked")).thenReturn(false);
        TransactionImportPreviewCleanupService cleanup = new TransactionImportPreviewCleanupService(sessions, uploads, plans, CLOCK);

        cleanup.cleanupExpired();
        cleanup.cleanupExpired();

        verify(uploads, org.mockito.Mockito.times(2)).delete("upload-ref");
        verify(plans).delete("plan-ref");
        verify(sessions).markExpired(session.getId(), session.getUserId(), CLOCK.instant());
        verify(sessions).clearCleanupReferences(session.getId(), session.getUserId());
    }

    private TransactionImportSession expiredSession() {
        TransactionImportSession session = new TransactionImportSession();
        session.setId(UUID.randomUUID());
        session.setUserId(1L);
        session.setStatus("PREVIEW_READY");
        session.setTemporaryStorageReference("upload-ref");
        session.setPlanStorageReference("plan-ref");
        return session;
    }
}
