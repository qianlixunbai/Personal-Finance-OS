package com.financeos.module.importing.config;

import com.financeos.module.importing.storage.LocalTemporaryImportFileStorage;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import com.financeos.module.importing.storage.TrustedImportStorageRoot;
import com.financeos.module.importing.service.TransactionImportFileLifecycleService;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.financeos.module.importing.service.TransactionImportPreviewCleanupScheduler;
import com.financeos.module.importing.service.TransactionImportPreviewCleanupService;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;

@Configuration
public class TransactionImportPreviewConfiguration {
    record Bindings(Path raw, Path plan, UUID deployment, UUID rawId, UUID planId) { }

    @Bean
    Bindings importStorageBindings(Environment environment) throws java.io.IOException {
        if (environment.matchesProfiles("test")) {
            // Only an explicit test profile provisions newly owned isolated directories.
            Path owned = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "finance-os-import-test-");
            Bindings bindings = new Bindings(owned.resolve("raw"), owned.resolve("plan"), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            TrustedImportStorageRoot.provision(bindings.raw(), bindings.deployment(), bindings.rawId(), "RAW");
            TrustedImportStorageRoot.provision(bindings.plan(), bindings.deployment(), bindings.planId(), "PLAN");
            return bindings;
        }
        Bindings bindings = new Bindings(Path.of(environment.getRequiredProperty("finance.import.storage.raw-root")),
                Path.of(environment.getRequiredProperty("finance.import.storage.plan-root")),
                UUID.fromString(environment.getRequiredProperty("finance.import.storage.deployment-id")),
                UUID.fromString(environment.getRequiredProperty("finance.import.storage.raw-id")),
                UUID.fromString(environment.getRequiredProperty("finance.import.storage.plan-id")));
        if (!bindings.raw().isAbsolute() || !bindings.plan().isAbsolute()
                || bindings.rawId().equals(bindings.planId())) throw new IllegalArgumentException("Invalid managed import storage configuration");
        Path raw = bindings.raw().normalize(), plan = bindings.plan().normalize();
        if (raw.equals(plan) || raw.startsWith(plan) || plan.startsWith(raw))
            throw new IllegalArgumentException("Managed import storage roots must be independent");
        return bindings;
    }

    @Bean(destroyMethod = "close")
    @Primary
    LocalTemporaryImportFileStorage temporaryImportFileStorage(Bindings bindings, TransactionImportFileLifecycleService files) {
        return new LocalTemporaryImportFileStorage(bindings.raw(), 5L * 1024 * 1024, bindings.deployment(), bindings.rawId(), "RAW", files);
    }

    @Bean(value = "transactionImportPreviewPlanStorage", destroyMethod = "close")
    LocalTemporaryImportFileStorage transactionImportPreviewPlanStorage(Bindings bindings, TransactionImportFileLifecycleService files) {
        return new LocalTemporaryImportFileStorage(bindings.plan(), 64L * 1024 * 1024, bindings.deployment(), bindings.planId(), "PLAN", files);
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
