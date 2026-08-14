package com.financeos.module.importing.service;

import com.financeos.common.BusinessException;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;

class TransactionImportSessionStateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void expiresAReadySessionUsingServerTime() {
        TransactionImportSessionMapper mapper = mock(TransactionImportSessionMapper.class);
        UUID sessionId = UUID.randomUUID();
        TransactionImportSession session = readySession(sessionId, CLOCK.instant().minusSeconds(1));
        when(mapper.findByIdAndUserId(sessionId, 1L)).thenReturn(session);

        TransactionImportSessionService service = new TransactionImportSessionService(mapper, CLOCK);

        assertThatThrownBy(() -> service.requireUsableSession(1L, sessionId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
        verify(mapper).markExpired(sessionId, 1L, CLOCK.instant());
    }

    @Test
    void keepsAReadySessionUsableBeforeTheServerSideExpiry() {
        TransactionImportSessionMapper mapper = mock(TransactionImportSessionMapper.class);
        UUID sessionId = UUID.randomUUID();
        TransactionImportSession session = readySession(sessionId, CLOCK.instant().plusSeconds(1));
        when(mapper.findByIdAndUserId(sessionId, 1L)).thenReturn(session);

        TransactionImportSessionService service = new TransactionImportSessionService(mapper, CLOCK);

        assertThat(service.requireUsableSession(1L, sessionId)).isSameAs(session);
    }

    @Test
    void rejectsCrossUserAndInvalidStateWithoutRevealingTheSession() {
        TransactionImportSessionMapper mapper = mock(TransactionImportSessionMapper.class);
        UUID sessionId = UUID.randomUUID();
        when(mapper.findByIdAndUserId(sessionId, 2L)).thenReturn(null);

        TransactionImportSessionService service = new TransactionImportSessionService(mapper, CLOCK);

        assertThatThrownBy(() -> service.requireUsableSession(2L, sessionId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void createsSessionWithServerGeneratedIdentifiersAndAFixedFifteenMinuteExpiry() {
        TransactionImportSessionMapper mapper = mock(TransactionImportSessionMapper.class);
        TransactionImportSessionService service = new TransactionImportSessionService(mapper, CLOCK);
        TransactionImportSession draft = new TransactionImportSession();
        draft.setOriginalFileName("statement.csv");
        draft.setFileSize(1L);
        draft.setFileDigest("a".repeat(64));
        draft.setTemporaryStorageReference(UUID.randomUUID().toString());
        draft.setOptionsDigest("b".repeat(64));

        TransactionImportSession created = service.createMappingRequiredSession(1L, draft);

        var captor = forClass(TransactionImportSession.class);
        verify(mapper).insert(captor.capture());
        assertThat(created.getId()).isNotNull();
        assertThat(created.getPreallocatedBatchId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(TransactionImportSessionStatus.MAPPING_REQUIRED.name());
        assertThat(created.getExpiresAt()).isEqualTo(CLOCK.instant().plusSeconds(15 * 60));
        assertThat(captor.getValue()).isSameAs(created);
    }

    private TransactionImportSession readySession(UUID id, Instant expiresAt) {
        TransactionImportSession session = new TransactionImportSession();
        session.setId(id);
        session.setUserId(1L);
        session.setStatus(TransactionImportSessionStatus.PREVIEW_READY.name());
        session.setExpiresAt(expiresAt);
        session.setPreallocatedBatchId(UUID.randomUUID());
        return session;
    }
}
