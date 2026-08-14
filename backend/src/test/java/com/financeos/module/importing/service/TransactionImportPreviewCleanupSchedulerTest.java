package com.financeos.module.importing.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class TransactionImportPreviewCleanupSchedulerTest {

    @Test
    void schedulesCleanupEveryMinuteAndStopsItBeforeDependencyShutdown() {
        TransactionImportPreviewCleanupService cleanup = mock(TransactionImportPreviewCleanupService.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(1)));
        TransactionImportPreviewCleanupScheduler scheduler = new TransactionImportPreviewCleanupScheduler(cleanup, taskScheduler);

        scheduler.start();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).scheduleWithFixedDelay(task.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(1)));
        task.getValue().run();
        verify(cleanup).cleanupExpired();

        scheduler.stop();
        task.getValue().run();

        verify(cleanup).stopAcceptingCleanup();
        verify(future).cancel(false);
        verifyNoMoreInteractions(cleanup);
    }
}
