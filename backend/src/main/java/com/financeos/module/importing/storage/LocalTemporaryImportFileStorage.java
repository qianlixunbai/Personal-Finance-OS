package com.financeos.module.importing.storage;

import com.financeos.module.importing.entity.TransactionImportFileObject;
import com.financeos.module.importing.service.TransactionImportFileLifecycleService;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Every managed write is persistently reserved before part/final creation. */
public class LocalTemporaryImportFileStorage implements TemporaryImportFileStorage, AutoCloseable {
    private record Identity(Long user, UUID session) { }
    private final long maximumFileSize;
    private final String storageId;
    private final String kind;
    private final TrustedImportStorageRoot root;
    private final TransactionImportFileLifecycleService lifecycle;
    private final ThreadLocal<Identity> identity = new ThreadLocal<>();

    public LocalTemporaryImportFileStorage(Path path, long maximumFileSize, UUID deployment, UUID storage,
                                           String kind, TransactionImportFileLifecycleService lifecycle) {
        this.maximumFileSize = maximumFileSize;
        this.storageId = storage.toString();
        this.kind = kind;
        this.lifecycle = lifecycle;
        try { this.root = new TrustedImportStorageRoot(path, deployment, storage, kind); }
        catch (ImportStorageBindingException exception) { lifecycle.quarantine(storageId); throw exception; }
        try { lifecycle.bind(storageId, deployment.toString(), kind, root.digest()); }
        catch (RuntimeException exception) { root.close(); lifecycle.quarantine(storageId); throw exception; }
    }

    @Override public <T> T withIdentity(Long user, UUID session, Supplier<T> operation) {
        if (user == null || session == null) unavailable();
        Identity previous = identity.get();
        identity.set(new Identity(user, session));
        try { return operation.get(); }
        finally { if (previous == null) identity.remove(); else identity.set(previous); }
    }

    @Override public StoredImportFile save(InputStream content, String originalFilename) {
        Identity owner = requireIdentity();
        TransactionImportFileObject reservation = lifecycle.reserve(storageId, kind, owner.user(), owner.session());
        return guarded(access -> {
            lifecycle.authorizeWrite(reservation);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (content; var output = access.create(reservation.getReference() + ".part")) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    size += read;
                    if (size > maximumFileSize) throw new InvalidImportFileException(413, "Import file exceeds the maximum size");
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                    while (bytes.hasRemaining()) output.write(bytes);
                    digest.update(buffer, 0, read);
                }
                if (size == 0) throw new InvalidImportFileException(400, "Import file must not be empty");
            }
            access.move(reservation.getReference() + ".part", reservation.getReference());
            access.verify();
            lifecycle.completed(reservation);
            return new StoredImportFile(reservation.getReference(), size, HexFormat.of().formatHex(digest.digest()));
        });
    }

    @Override public InputStream open(String reference) {
        Identity owner = requireIdentity();
        validateReference(reference);
        byte[] bytes = guarded(access -> {
            enrollLegacy(access, reference);
            lifecycle.authorizeRead(reference, storageId, owner.user(), owner.session());
            try (var channel = access.read(reference); var input = Channels.newInputStream(channel)) {
                byte[] content = input.readNBytes(Math.toIntExact(maximumFileSize + 1));
                if (content.length > maximumFileSize) unavailable();
                return content;
            }
        });
        // Confirm receives bounded memory after the actual protected read has completed.
        return new ByteArrayInputStream(bytes);
    }

    @Override public boolean delete(String reference) {
        validateReference(reference);
        TransactionImportFileObject existing = lifecycle.object(reference);
        if (existing != null) lifecycle.prepareDeletion(reference, storageId);
        try {
            return guarded(access -> {
                enrollLegacy(access, reference);
                TransactionImportFileObject object = lifecycle.prepareDeletion(reference, storageId);
                if ("DELETED".equals(object.getState())) {
                    if (access.exists(reference) || access.exists(reference + ".part")) unavailable();
                    return false;
                }
                if (!"RETIRED".equals(object.getState())) unavailable();
                boolean part = access.delete(reference + ".part");
                boolean payload = access.delete(reference);
                if (access.exists(reference) || access.exists(reference + ".part")) unavailable();
                access.verify();
                lifecycle.deleted(reference, object.getFenceEpoch());
                return part || payload;
            });
        } catch (RuntimeException exception) {
            try { lifecycle.retry(reference); } catch (RuntimeException retryFailure) { exception.addSuppressed(retryFailure); }
            throw exception;
        }
    }

    @Override public boolean exists(String reference) {
        validateReference(reference);
        return guarded(access -> {
            boolean present = access.exists(reference);
            if (present) enrollLegacy(access, reference);
            TransactionImportFileObject object = lifecycle.object(reference);
            if (object == null || !storageId.equals(object.getStorageId()) || !kind.equals(object.getKind())) unavailable();
            if ("DELETED".equals(object.getState()) && present) unavailable();
            return present;
        });
    }

    @Override public void cleanupRetired() {
        cleanupRetired(() -> true);
    }

    @Override public void cleanupRetired(java.util.function.BooleanSupplier accepting) {
        for (String reference : lifecycle.candidates(storageId)) {
            if (!accepting.getAsBoolean()) return;
            try { delete(reference); }
            catch (RuntimeException exception) {
                // Durable rows survive; do not log filesystem exception paths.
                try { lifecycle.retry(reference); } catch (RuntimeException ignored) { }
            }
        }
    }

    private void enrollLegacy(TrustedImportStorageRoot.Access access, String reference) throws IOException {
        TransactionImportFileObject object = lifecycle.object(reference);
        if (object == null) {
            if (!access.exists(reference) || access.attributes(reference).size() > maximumFileSize) unavailable();
            lifecycle.adopt(reference, storageId, kind);
            object = lifecycle.object(reference);
        }
        if (object == null || !storageId.equals(object.getStorageId()) || !kind.equals(object.getKind())) unavailable();
    }

    private Identity requireIdentity() {
        Identity owner = identity.get();
        if (owner == null) unavailable();
        return owner;
    }

    private <T> T guarded(TrustedImportStorageRoot.Operation<T> operation) {
        try { return root.execute(operation); }
        catch (ImportStorageBindingException exception) {
            try { lifecycle.quarantine(storageId); } catch (RuntimeException failure) { exception.addSuppressed(failure); }
            throw exception;
        }
    }

    private void validateReference(String reference) {
        try { if (!UUID.fromString(reference).toString().equals(reference)) unavailable(); }
        catch (IllegalArgumentException | NullPointerException exception) { unavailable(); }
    }
    private void unavailable() { throw new InvalidImportFileException(409, "Managed import file is unavailable"); }
    @Override public void close() { root.close(); }
}
