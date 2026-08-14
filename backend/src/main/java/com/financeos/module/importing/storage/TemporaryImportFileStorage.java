package com.financeos.module.importing.storage;

import java.io.InputStream;

public interface TemporaryImportFileStorage {
    StoredImportFile save(InputStream content, String originalFilename);

    InputStream open(String reference);

    boolean delete(String reference);

    boolean exists(String reference);
}
