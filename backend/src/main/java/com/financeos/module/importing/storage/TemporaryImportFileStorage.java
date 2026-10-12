package com.financeos.module.importing.storage;

import java.io.InputStream;
import java.util.UUID;
import java.util.function.Supplier;

public interface TemporaryImportFileStorage {
    StoredImportFile save(InputStream content, String originalFilename);

    InputStream open(String reference);

    boolean delete(String reference);

    boolean exists(String reference);

    default <T> T withIdentity(Long user, UUID session, Supplier<T> operation) { return operation.get(); }

    default StoredImportFile save(Long user, UUID session, InputStream content, String filename) {
        return withIdentity(user, session, () -> save(content, filename));
    }

    default InputStream open(Long user, UUID session, String reference) {
        return withIdentity(user, session, () -> open(reference));
    }

    void cleanupRetired();
    default void cleanupRetired(java.util.function.BooleanSupplier accepting) { if (accepting.getAsBoolean()) cleanupRetired(); }
}
