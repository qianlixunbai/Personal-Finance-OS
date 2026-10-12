package com.financeos.module.importing.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.financeos.module.importing.entity.TransactionImportFileObject;
import com.financeos.module.importing.service.TransactionImportFileLifecycleService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LocalTemporaryImportFileStorageTest {

    @TempDir
    Path temporaryDirectory;
    private LocalTemporaryImportFileStorage managed;
    private final UUID session = UUID.randomUUID();

    @AfterEach
    void closeStorage() { if (managed != null) managed.close(); }

    private LocalTemporaryImportFileStorage storage(long limit) throws Exception {
        UUID deployment = UUID.randomUUID();
        UUID storage = UUID.randomUUID();
        TrustedImportStorageRoot.provision(temporaryDirectory, deployment, storage, "RAW");
        var lifecycle = mock(TransactionImportFileLifecycleService.class);
        Map<String, TransactionImportFileObject> objects = new HashMap<>();
        when(lifecycle.reserve(anyString(), anyString(), anyLong(), any())).thenAnswer(invocation -> {
            var object = new TransactionImportFileObject();
            object.setReference(UUID.randomUUID().toString()); object.setStorageId(storage.toString());
            object.setKind("RAW"); object.setOwnerUserId(1L); object.setIntendedSessionId(session.toString());
            object.setState("RESERVED"); object.setFenceEpoch(1);
            objects.put(object.getReference(), object);
            return object;
        });
        when(lifecycle.object(anyString())).thenAnswer(invocation -> objects.get(invocation.getArgument(0)));
        when(lifecycle.prepareDeletion(anyString(), anyString())).thenAnswer(invocation -> {
            var object = objects.get(invocation.getArgument(0)); object.setState("RETIRED"); return object;
        });
        doAnswer(invocation -> { objects.get(invocation.getArgument(0)).setState("DELETED"); return null; })
                .when(lifecycle).deleted(anyString(), anyLong());
        managed = new LocalTemporaryImportFileStorage(temporaryDirectory, limit, deployment, storage, "RAW", lifecycle);
        return managed;
    }

    @Test
    void savesWithAnInternalReferenceAndReturnsDigestWithoutTrustingOriginalFilename() throws Exception {
        LocalTemporaryImportFileStorage storage = storage(5 * 1024 * 1024);

        StoredImportFile stored = storage.save(1L, session, new ByteArrayInputStream("import-data".getBytes()), "..\\..\\statement.csv");

        assertThat(stored.reference()).matches("[0-9a-f-]{36}");
        assertThat(stored.fileSize()).isEqualTo(11);
        assertThat(stored.fileDigest()).isEqualTo("1b35d1fd143c41d4c9ce979c8653334ad429a1a6e900babb8130fbe972de0ea5");
        try (var entries = Files.list(temporaryDirectory)) {
            assertThat(entries.map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals(TrustedImportStorageRoot.MARKER) && !name.equals(TrustedImportStorageRoot.CONTROL)))
                    .containsExactly(stored.reference());
        }
        assertThat(storage.open(1L, session, stored.reference()).readAllBytes()).isEqualTo("import-data".getBytes());
    }

    @Test
    void rejectsOversizeContentAndReferencesOutsideTheManagedDirectory() throws Exception {
        LocalTemporaryImportFileStorage storage = storage(3);

        assertThatThrownBy(() -> storage.save(1L, session, new ByteArrayInputStream("four".getBytes()), "statement.csv"))
                .isInstanceOf(InvalidImportFileException.class);
        assertThatThrownBy(() -> storage.open("../outside"))
                .isInstanceOf(InvalidImportFileException.class);
    }

    @Test
    void deletesAStoredFileByItsInternalReference() throws Exception {
        LocalTemporaryImportFileStorage storage = storage(10);
        StoredImportFile stored = storage.save(1L, session, new ByteArrayInputStream("data".getBytes()), "statement.csv");

        assertThat(storage.delete(stored.reference())).isTrue();
        assertThat(storage.exists(stored.reference())).isFalse();
    }

    @Test
    void sanitizesDisplayFilenameWithoutTreatingWindowsOrUnixPathsAsMetadata() {
        assertThat(ImportFileMetadata.sanitizeOriginalFilename("C:\\statements\\..\\August\u0000.csv"))
                .isEqualTo("August.csv");
        assertThat(ImportFileMetadata.sanitizeOriginalFilename("../../statement.csv"))
                .isEqualTo("statement.csv");
    }
}
