package com.financeos.module.importing.storage;

/** Fixed diagnostic; no absolute path, marker contents or filesystem Throwable is exposed. */
public final class ImportStorageBindingException extends InvalidImportFileException {
    public ImportStorageBindingException() { super(503, "Trusted import storage binding requires verification"); }
}
