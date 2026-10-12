package com.financeos.module.importing.service;

import com.financeos.common.DatabaseFailureLog;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Service
public class TransactionImportPreviewCleanupService {
    private final TransactionImportSessionMapper sessionMapper;
    private final TemporaryImportFileStorage storage;
    private final TemporaryImportFileStorage planStorage;
    private final Clock clock;
    private final TransactionImportFileLifecycleService files;
    private final AtomicBoolean acceptingCleanup = new AtomicBoolean(true);
    private final ReentrantReadWriteLock cleanupLifecycleLock = new ReentrantReadWriteLock();

    public TransactionImportPreviewCleanupService(TransactionImportSessionMapper sessionMapper,
                                                  TemporaryImportFileStorage storage,
                                                  @Qualifier("transactionImportPreviewPlanStorage") TemporaryImportFileStorage planStorage,
                                                  @Qualifier("businessClock") Clock clock,
                                                  TransactionImportFileLifecycleService files) {
        this.sessionMapper = sessionMapper;
        this.storage = storage;
        this.planStorage = planStorage;
        this.clock = clock;
        this.files = files;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        cleanupExpired();
    }

    public void cleanupExpired() {
        if (!acceptingCleanup.get()) {
            return;
        }
        cleanupLifecycleLock.readLock().lock();
        try {
            if (!acceptingCleanup.get()) {
                return;
            }
            cleanupExpiredSessions();
            storage.cleanupRetired(acceptingCleanup::get);
            planStorage.cleanupRetired(acceptingCleanup::get);
        } finally {
            cleanupLifecycleLock.readLock().unlock();
        }
    }

    /** Runs only after the financial transaction has committed; cleanup never participates in receipt atomicity. */
    public void cleanupCommittedPayloads(TransactionImportSession session) {
        try {
            TransactionImportSession current = files.reconcileTerminal(session.getUserId(), session.getId(), clock.instant());
            if (current != null) deleteCurrentReferences(current);
        } catch (RuntimeException exception) {
            log.warn("Transaction import committed payload cleanup failed for session {}", session.getId());
        }
    }

    void stopAcceptingCleanup() {
        acceptingCleanup.set(false);
        boolean drained = false;
        try {
            drained = cleanupLifecycleLock.writeLock().tryLock(4, java.util.concurrent.TimeUnit.SECONDS);
            if (!drained) log.warn("Transaction import cleanup drain pending; active I/O protection remains held");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (drained) cleanupLifecycleLock.writeLock().unlock();
        }
    }

    @PreDestroy
    void stopAcceptingCleanupDuringBeanDestruction() {
        stopAcceptingCleanup();
    }

    private void cleanupExpiredSessions() {
        Instant now = clock.instant();
        // Enrollment is limited to exact DB-owned references, never a filesystem inventory.
        for (TransactionImportSession session : sessionMapper.findMissingFileRegistrations()) {
            if (!acceptingCleanup.get()) return;
            try {
                if (session.getTemporaryStorageReference() != null) storage.exists(session.getTemporaryStorageReference());
                if (session.getPlanStorageReference() != null) planStorage.exists(session.getPlanStorageReference());
            } catch (RuntimeException exception) { reportFailure(session, exception); }
        }
        for (TransactionImportSession session : sessionMapper.findExpiredForCleanup(now)) {
            if (!acceptingCleanup.get()) return;
            try {
                TransactionImportSession current = files.reconcileTerminal(session.getUserId(), session.getId(), now);
                if (current != null) deleteCurrentReferences(current);
            } catch (RuntimeException exception) {
                reportFailure(session, exception);
            }
        }
    }

    private void deleteCurrentReferences(TransactionImportSession current) {
        if (current.getTemporaryStorageReference() != null) storage.delete(current.getTemporaryStorageReference());
        if (current.getPlanStorageReference() != null) planStorage.delete(current.getPlanStorageReference());
        files.clearDeletedReferences(current.getUserId(), current.getId());
    }

    private void reportFailure(TransactionImportSession session, RuntimeException exception) {
        if (DatabaseFailureLog.hasDatabaseFailure(exception))
            DatabaseFailureLog.warn(log, DatabaseFailureLog.Path.IMPORT_PREVIEW_CLEANUP, exception);
        else log.warn("Transaction import preview cleanup deferred for session {}", session.getId());
    }
}
