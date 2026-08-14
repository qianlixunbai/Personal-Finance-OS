package com.financeos.module.importing.config;

import com.financeos.module.importing.storage.LocalTemporaryImportFileStorage;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.financeos.module.importing.service.TransactionImportPreviewCleanupScheduler;
import com.financeos.module.importing.service.TransactionImportPreviewCleanupService;

import java.nio.file.Path;

@Configuration
public class TransactionImportPreviewConfiguration {
    @Bean
    @Primary
    TemporaryImportFileStorage temporaryImportFileStorage() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "finance-os", "transaction-import-preview");
        return new LocalTemporaryImportFileStorage(root, 5L * 1024 * 1024);
    }

    @Bean("transactionImportPreviewPlanStorage")
    TemporaryImportFileStorage transactionImportPreviewPlanStorage() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "finance-os", "transaction-import-preview-plan");
        return new LocalTemporaryImportFileStorage(root, 64L * 1024 * 1024);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "finance.import.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskScheduler transactionImportPreviewCleanupTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("transaction-import-cleanup-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "finance.import.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
    TransactionImportPreviewCleanupScheduler transactionImportPreviewCleanupScheduler(
            TransactionImportPreviewCleanupService cleanupService,
            ThreadPoolTaskScheduler transactionImportPreviewCleanupTaskScheduler) {
        return new TransactionImportPreviewCleanupScheduler(cleanupService, transactionImportPreviewCleanupTaskScheduler);
    }
}
