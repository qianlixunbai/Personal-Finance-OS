package com.financeos.module.importing.service;

import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionImportPreviewCleanupSchedulerLifecycleTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void closingSpringContextDrainsActiveSchedulerCleanupAndFencesFutureDatabaseAccess() throws Exception {
        TransactionImportSessionMapper sessions = mock(TransactionImportSessionMapper.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch allowQueryToFinish = new CountDownLatch(1);
        when(sessions.findExpiredForCleanup(CLOCK.instant())).thenAnswer(invocation -> {
            queryStarted.countDown();
            allowQueryToFinish.await();
            return List.of();
        });

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ThreadPoolTaskScheduler.class, () -> {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            return scheduler;
        });
        context.registerBean(TransactionImportPreviewCleanupService.class,
                () -> new TransactionImportPreviewCleanupService(
                        sessions, mock(TemporaryImportFileStorage.class), mock(TemporaryImportFileStorage.class), CLOCK));
        context.registerBean(TransactionImportPreviewCleanupScheduler.class,
                () -> new TransactionImportPreviewCleanupScheduler(
                        context.getBean(TransactionImportPreviewCleanupService.class),
                        context.getBean(ThreadPoolTaskScheduler.class)));
        context.refresh();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TransactionImportPreviewCleanupScheduler scheduler = context.getBean(TransactionImportPreviewCleanupScheduler.class);
            assertThat(queryStarted.await(2, TimeUnit.SECONDS)).isTrue();

            var closing = executor.submit(context::close);
            assertThat(closing.isDone()).isFalse();

            allowQueryToFinish.countDown();
            closing.get(2, TimeUnit.SECONDS);

            scheduler.runCleanup();
            verify(sessions).findExpiredForCleanup(CLOCK.instant());
        } finally {
            allowQueryToFinish.countDown();
            if (context.isActive()) {
                context.close();
            }
            executor.shutdownNow();
        }
    }
}
