package com.financeos.module.importing.config;

import com.financeos.module.importing.storage.LocalTemporaryImportFileStorage;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
}
