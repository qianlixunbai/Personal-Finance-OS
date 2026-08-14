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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void waitsForInFlightCleanupAndPreventsNewDatabaseAccessAfterShutdownFence() throws Exception {
        TransactionImportSessionMapper sessions = mock(TransactionImportSessionMapper.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch allowQueryToFinish = new CountDownLatch(1);
        when(sessions.findExpiredForCleanup(CLOCK.instant())).thenAnswer(invocation -> {
            queryStarted.countDown();
            allowQueryToFinish.await();
            return List.of();
        });
        TransactionImportPreviewCleanupService cleanup = new TransactionImportPreviewCleanupService(
                sessions,
                mock(TemporaryImportFileStorage.class),
                mock(TemporaryImportFileStorage.class),
                CLOCK);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var inFlightCleanup = executor.submit(cleanup::cleanupExpired);
            assertThat(queryStarted.await(2, TimeUnit.SECONDS)).isTrue();

            var shutdown = executor.submit(cleanup::stopAcceptingCleanup);
            assertThat(shutdown.isDone()).isFalse();

            allowQueryToFinish.countDown();
            inFlightCleanup.get(2, TimeUnit.SECONDS);
            shutdown.get(2, TimeUnit.SECONDS);

            cleanup.cleanupExpired();

            verify(sessions).findExpiredForCleanup(CLOCK.instant());
        } finally {
            allowQueryToFinish.countDown();
            executor.shutdownNow();
        }
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
