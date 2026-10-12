package com.financeos.module.importing.service;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransactionImportPreviewCleanupScheduler implements SmartLifecycle, DisposableBean {
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);

    private final TransactionImportPreviewCleanupService cleanupService;
    private final TaskScheduler taskScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> cleanupTask;
    private final Duration interval;

    public TransactionImportPreviewCleanupScheduler(TransactionImportPreviewCleanupService cleanupService,
                                                    TaskScheduler taskScheduler) {
        this(cleanupService, taskScheduler, CLEANUP_INTERVAL);
    }

    public TransactionImportPreviewCleanupScheduler(TransactionImportPreviewCleanupService cleanupService,
                                                     TaskScheduler taskScheduler, Duration interval) {
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("Cleanup interval must be positive");
        this.cleanupService = cleanupService;
        this.taskScheduler = taskScheduler;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            cleanupTask = taskScheduler.scheduleWithFixedDelay(this::runCleanup, interval);
        }
    }

    public void runCleanup() {
        if (running.get()) {
            cleanupService.cleanupExpired();
        }
    }

    @Override
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            cleanupService.stopAcceptingCleanup();
            ScheduledFuture<?> task = cleanupTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void destroy() {
        stop();
    }
}
