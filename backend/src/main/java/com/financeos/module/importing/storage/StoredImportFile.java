package com.financeos.module.importing.storage;

public record StoredImportFile(String reference, long fileSize, String fileDigest) {
}
