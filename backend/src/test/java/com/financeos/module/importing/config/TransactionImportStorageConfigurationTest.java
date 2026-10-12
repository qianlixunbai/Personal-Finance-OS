package com.financeos.module.importing.config;

import com.financeos.module.importing.service.TransactionImportFileLifecycleService;
import com.financeos.module.importing.storage.ImportStorageBindingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionImportStorageConfigurationTest {
    @TempDir Path directory;
    final TransactionImportPreviewConfiguration configuration = new TransactionImportPreviewConfiguration();
    @Test void productionRequiresExplicitCompleteConfiguration() {
        assertThatThrownBy(() -> configuration.importStorageBindings(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }
    @Test void productionRejectsRelativeNestedAndDuplicateIdentityRoots() {
        assertThatThrownBy(() -> configuration.importStorageBindings(environment().withProperty("finance.import.storage.raw-root", "relative-root"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.importStorageBindings(environment().withProperty("finance.import.storage.plan-root", directory.resolve("raw/nested").toString()))).isInstanceOf(IllegalArgumentException.class);
        var properties = environment();
        properties.withProperty("finance.import.storage.plan-id", properties.getRequiredProperty("finance.import.storage.raw-id"));
        assertThatThrownBy(() -> configuration.importStorageBindings(properties)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void productionNeverCreatesMissingDirectoriesOrBindings() throws Exception {
        var bindings = configuration.importStorageBindings(environment());
        var lifecycle = mock(TransactionImportFileLifecycleService.class);
        assertThatThrownBy(() -> configuration.temporaryImportFileStorage(bindings, lifecycle)).isInstanceOf(ImportStorageBindingException.class);
        assertThat(Files.exists(bindings.raw())).isFalse();
        assertThat(Files.exists(bindings.plan())).isFalse();
        verify(lifecycle).quarantine(bindings.rawId().toString());
        verifyNoMoreInteractions(lifecycle);
    }
    MockEnvironment environment() {
        return new MockEnvironment().withProperty("finance.import.storage.raw-root", directory.resolve("raw").toString())
                .withProperty("finance.import.storage.plan-root", directory.resolve("plan").toString())
                .withProperty("finance.import.storage.deployment-id", UUID.randomUUID().toString())
                .withProperty("finance.import.storage.raw-id", UUID.randomUUID().toString())
                .withProperty("finance.import.storage.plan-id", UUID.randomUUID().toString());
    }
}
