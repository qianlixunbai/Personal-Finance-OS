package com.financeos.module.importing.service;

import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.entity.TransactionImportSession;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionImportSessionGuardTest {

    @Test
    void appliesTheFrozenSixtySecondConfirmTransactionTimeout() throws NoSuchMethodException {
        Transactional transactional = TransactionImportSessionService.class
                .getMethod("withReadySessionGuard", Long.class, UUID.class, java.util.function.Function.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeout()).isEqualTo(60);
    }

    @Test
    void allowsAtMostOneAtomicConsumeTransitionForTheSameSession() throws Exception {
        TransactionImportSessionMapper mapper = mock(TransactionImportSessionMapper.class);
        TransactionImportSession session = new TransactionImportSession();
        session.setStatus(TransactionImportSessionStatus.PREVIEW_READY.name());
        session.setExpiresAt(Instant.parse("2026-08-14T12:01:00Z"));
        when(mapper.findByIdAndUserIdForUpdate(any(), any())).thenReturn(session);
        when(mapper.consumeReadySession(any(), any(), any())).thenReturn(1, 0);
        TransactionImportSessionService service = new TransactionImportSessionService(mapper,
                Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC));
        UUID sessionId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> consume = () -> {
                try {
                    return service.withReadySessionGuard(1L, sessionId, ignored -> true);
                } catch (com.financeos.common.BusinessException exception) {
                    return false;
                }
            };
            Future<Boolean> first = executor.submit(consume);
            Future<Boolean> second = executor.submit(consume);

            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }
}
