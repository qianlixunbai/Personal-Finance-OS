package com.financeos.module.importing.service;

import com.financeos.common.BusinessException;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

@Service
public class TransactionImportSessionService {
    private static final Duration SESSION_TTL = Duration.ofMinutes(15);
    private final TransactionImportSessionMapper sessionMapper;
    private final Clock clock;

    @Autowired
    public TransactionImportSessionService(TransactionImportSessionMapper sessionMapper) {
        this(sessionMapper, Clock.systemUTC());
    }

    TransactionImportSessionService(TransactionImportSessionMapper sessionMapper, Clock clock) {
        this.sessionMapper = sessionMapper;
        this.clock = clock;
    }

    public TransactionImportSession createMappingRequiredSession(Long userId, TransactionImportSession draft) {
        Instant now = clock.instant();
        draft.setId(UUID.randomUUID());
        draft.setUserId(userId);
        draft.setPreallocatedBatchId(UUID.randomUUID());
        draft.setStatus(TransactionImportSessionStatus.MAPPING_REQUIRED.name());
        draft.setRevision(0);
        draft.setExpiresAt(now.plus(SESSION_TTL));
        sessionMapper.insert(draft);
        return draft;
    }

    public TransactionImportSession requireUsableSession(Long userId, UUID sessionId) {
        TransactionImportSession session = sessionMapper.findByIdAndUserId(sessionId, userId);
        if (session == null) {
            throw new BusinessException(404, "Import session not found");
        }
        Instant now = clock.instant();
        if (!session.getExpiresAt().isAfter(now)) {
            sessionMapper.markExpired(sessionId, userId, now);
            throw new BusinessException(409, "Import session expired");
        }
        if (TransactionImportSessionStatus.CANCELLED.name().equals(session.getStatus())
                || TransactionImportSessionStatus.CONSUMED.name().equals(session.getStatus())
                || TransactionImportSessionStatus.EXPIRED.name().equals(session.getStatus())) {
            throw new BusinessException(409, "Import session is not usable");
        }
        return session;
    }

    @Transactional(timeout = 60)
    public <T> T withReadySessionGuard(Long userId, UUID sessionId, Function<TransactionImportSession, T> operation) {
        TransactionImportSession session = sessionMapper.findByIdAndUserIdForUpdate(sessionId, userId);
        if (session == null) {
            throw new BusinessException(404, "Import session not found");
        }
        Instant now = clock.instant();
        if (!session.getExpiresAt().isAfter(now)) {
            sessionMapper.markExpired(sessionId, userId, now);
            throw new BusinessException(409, "Import session expired");
        }
        if (!TransactionImportSessionStatus.PREVIEW_READY.name().equals(session.getStatus())) {
            throw new BusinessException(409, "Import session is not ready for confirmation");
        }
        T result = operation.apply(session);
        if (sessionMapper.consumeReadySession(sessionId, userId, now) != 1) {
            throw new BusinessException(409, "Import session confirmation conflict");
        }
        return result;
    }
}
