package com.financeos.module.importing.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public class LocalTemporaryImportFileStorage implements TemporaryImportFileStorage {
    private static final String REFERENCE_PATTERN = "[0-9a-f-]{36}";
    private final Path root;
    private final long maximumFileSize;

    public LocalTemporaryImportFileStorage(Path root, long maximumFileSize) {
        this.root = root.toAbsolutePath().normalize();
        this.maximumFileSize = maximumFileSize;
    }

    @Override
    public StoredImportFile save(InputStream content, String originalFilename) {
        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, "upload-", ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    size += read;
                    if (size > maximumFileSize) {
                        throw new InvalidImportFileException(413, "Import file exceeds the maximum size");
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }
            if (size == 0) {
                throw new InvalidImportFileException(400, "Import file must not be empty");
            }
            String reference = UUID.randomUUID().toString();
            Path target = resolve(reference);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
            return new StoredImportFile(reference, size, HexFormat.of().formatHex(digest.digest()));
        } catch (InvalidImportFileException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteQuietly(temporary);
            throw new InvalidImportFileException(400, "Import file storage is unavailable");
        }
    }

    @Override
    public InputStream open(String reference) {
        try {
            return Files.newInputStream(resolve(reference));
        } catch (IOException exception) {
            throw new InvalidImportFileException(400, "Import file is unavailable");
        }
    }

    @Override
    public boolean delete(String reference) {
        try {
            return Files.deleteIfExists(resolve(reference));
        } catch (IOException exception) {
            throw new InvalidImportFileException(400, "Import file cleanup failed");
        }
    }

    @Override
    public boolean exists(String reference) {
        return Files.isRegularFile(resolve(reference));
    }

    private Path resolve(String reference) {
        if (reference == null || !reference.matches(REFERENCE_PATTERN)) {
            throw new InvalidImportFileException(400, "Invalid import file reference");
        }
        Path resolved = root.resolve(reference).normalize();
        if (!resolved.getParent().equals(root)) {
            throw new InvalidImportFileException(400, "Invalid import file reference");
        }
        return resolved;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The incomplete payload is never made addressable by a Session reference.
        }
    }
}
