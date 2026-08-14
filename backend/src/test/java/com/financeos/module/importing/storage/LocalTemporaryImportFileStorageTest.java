package com.financeos.module.importing.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTemporaryImportFileStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesWithAnInternalReferenceAndReturnsDigestWithoutTrustingOriginalFilename() throws Exception {
        LocalTemporaryImportFileStorage storage = new LocalTemporaryImportFileStorage(temporaryDirectory, 5 * 1024 * 1024);

        StoredImportFile stored = storage.save(new ByteArrayInputStream("import-data".getBytes()), "..\\..\\statement.csv");

        assertThat(stored.reference()).matches("[0-9a-f-]{36}");
        assertThat(stored.fileSize()).isEqualTo(11);
        assertThat(stored.fileDigest()).isEqualTo("1b35d1fd143c41d4c9ce979c8653334ad429a1a6e900babb8130fbe972de0ea5");
        assertThat(Files.list(temporaryDirectory).map(path -> path.getFileName().toString()))
                .containsExactly(stored.reference());
        assertThat(storage.open(stored.reference()).readAllBytes()).isEqualTo("import-data".getBytes());
    }

    @Test
    void rejectsOversizeContentAndReferencesOutsideTheManagedDirectory() {
        LocalTemporaryImportFileStorage storage = new LocalTemporaryImportFileStorage(temporaryDirectory, 3);

        assertThatThrownBy(() -> storage.save(new ByteArrayInputStream("four".getBytes()), "statement.csv"))
                .isInstanceOf(InvalidImportFileException.class);
        assertThatThrownBy(() -> storage.open("../outside"))
                .isInstanceOf(InvalidImportFileException.class);
    }

    @Test
    void deletesAStoredFileByItsInternalReference() throws Exception {
        LocalTemporaryImportFileStorage storage = new LocalTemporaryImportFileStorage(temporaryDirectory, 10);
        StoredImportFile stored = storage.save(new ByteArrayInputStream("data".getBytes()), "statement.csv");

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
