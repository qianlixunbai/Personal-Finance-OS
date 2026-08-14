package com.financeos.module.importing.service;

import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Slf4j
@Service
public class TransactionImportPreviewCleanupService {
    private final TransactionImportSessionMapper sessionMapper;
    private final TemporaryImportFileStorage storage;
    private final TemporaryImportFileStorage planStorage;
    private final Clock clock;

    public TransactionImportPreviewCleanupService(TransactionImportSessionMapper sessionMapper,
                                                  TemporaryImportFileStorage storage,
                                                  @Qualifier("transactionImportPreviewPlanStorage") TemporaryImportFileStorage planStorage,
                                                  @Qualifier("businessClock") Clock clock) {
        this.sessionMapper = sessionMapper;
        this.storage = storage;
        this.planStorage = planStorage;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        cleanupExpired();
    }

    public void cleanupExpired() {
        Instant now = clock.instant();
        for (TransactionImportSession session : sessionMapper.findExpiredForCleanup(now)) {
            try {
                storage.delete(session.getTemporaryStorageReference());
                if (session.getPlanStorageReference() != null) {
                    planStorage.delete(session.getPlanStorageReference());
                }
                sessionMapper.markExpired(session.getId(), session.getUserId(), now);
                sessionMapper.clearCleanupReferences(session.getId(), session.getUserId());
            } catch (RuntimeException exception) {
                log.warn("Transaction import preview cleanup failed for session {}", session.getId());
            }
        }
    }
}
