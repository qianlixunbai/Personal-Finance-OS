package com.financeos.module.importing.storage;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Option A: an externally provisioned binding, not native cross-restart root authentication. */
public final class TrustedImportStorageRoot implements AutoCloseable {
    public static final String MARKER = ".import-binding";
    public static final String CONTROL = ".import-control";
    private static final Map<Path, Context> CONTEXTS = new HashMap<>();
    private final Context context;
    private boolean closed;

    public TrustedImportStorageRoot(Path root, UUID deployment, UUID storage, String kind) {
        Path path = root.toAbsolutePath().normalize();
        String digest = bindingDigest(path, deployment, storage, kind);
        synchronized (CONTEXTS) {
            Context existing = CONTEXTS.get(path);
            if (existing == null) {
                existing = new Context(path, digest);
                CONTEXTS.put(path, existing);
            }
            if (!existing.digest.equals(digest) || existing.poisoned.get()) throw new ImportStorageBindingException();
            existing.users++;
            context = existing;
        }
    }

    public String digest() { return context.digest; }

    public static String bindingDigest(Path root, UUID deployment, UUID storage, String kind) {
        try {
            String value = "finance-os-import-v1|" + deployment + "|" + storage + "|" + kind + "|" + root.toAbsolutePath().normalize();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Import binding digest unavailable"); }
    }

    /** Explicit provisioning utility. Production application startup never calls this. */
    public static void provision(Path root, UUID deployment, UUID storage, String kind) throws IOException {
        Files.createDirectories(root);
        String digest = bindingDigest(root, deployment, storage, kind);
        // Never rewrite an existing binding/control object, including a partially provisioned root.
        Files.writeString(root.resolve(MARKER), digest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.writeString(root.resolve(CONTROL), digest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    @FunctionalInterface
    public interface Operation<T> { T run(Access access) throws Exception; }

    public <T> T execute(Operation<T> operation) {
        if (closed || context.poisoned.get()) unavailable();
        Future<T> future;
        try {
            future = context.workers.submit(() -> {
                if (!context.mutex.tryLock(4, TimeUnit.SECONDS)) unavailable();
                try {
                    context.verify();
                    long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
                    FileLock acquired = null;
                    while (acquired == null && System.nanoTime() < deadline) {
                        // Windows region locks also deny reads: keep the mutex byte outside the 64-byte binding.
                        acquired = context.control.tryLock(64, 1, false);
                        if (acquired == null) TimeUnit.MILLISECONDS.sleep(25);
                    }
                    if (acquired == null) unavailable();
                    try (FileLock guard = acquired) {
                        context.verify();
                        T result = operation.run(new Access(context));
                        context.verify();
                        return result;
                    }
                } finally { context.mutex.unlock(); }
            });
        } catch (RejectedExecutionException exception) { throw new InvalidImportFileException(503, "Managed import storage is busy"); }
        try { return future.get(30, TimeUnit.SECONDS); }
        catch (TimeoutException exception) {
            // Do not cancel or release the real worker's guard. It remains protected until its finally runs.
            throw new InvalidImportFileException(503, "Managed import storage operation timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InvalidImportFileException(503, "Managed import storage operation interrupted");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Error error) throw error;
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new InvalidImportFileException(400, "Managed import storage operation failed");
        }
    }

    public final static class Access {
        private final Context context;
        private Access(Context context) { this.context = context; }

        public void verify() { context.verify(); }

        public BasicFileAttributes attributes(String name) throws IOException {
            Path relative = relative(name);
            return context.secure == null
                    ? Files.readAttributes(context.path.resolve(relative), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    : context.secure.getFileAttributeView(relative, java.nio.file.attribute.BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes();
        }

        public boolean exists(String name) throws IOException {
            try {
                BasicFileAttributes attributes = attributes(name);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) unavailable();
                return true;
            } catch (NoSuchFileException exception) { context.verify(); return false; }
        }

        public SeekableByteChannel create(String name) throws IOException {
            Path relative = relative(name);
            Set<OpenOption> options = Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
            return context.secure == null ? Files.newByteChannel(context.path.resolve(relative), options)
                    : context.secure.newByteChannel(relative, options);
        }

        public SeekableByteChannel read(String name) throws IOException {
            if (!exists(name)) throw new NoSuchFileException("Managed import object missing");
            Path relative = relative(name);
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            return context.secure == null ? Files.newByteChannel(context.path.resolve(relative), options)
                    : context.secure.newByteChannel(relative, options);
        }

        public void move(String source, String target) throws IOException {
            if (exists(target)) unavailable();
            if (context.secure == null) {
                try { Files.move(context.path.resolve(relative(source)), context.path.resolve(relative(target)), StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException exception) { Files.move(context.path.resolve(relative(source)), context.path.resolve(relative(target))); }
            } else context.secure.move(relative(source), context.secure, relative(target));
        }

        public boolean delete(String name) throws IOException {
            if (!exists(name)) return false;
            try {
                if (context.secure == null) Files.delete(context.path.resolve(relative(name)));
                else context.secure.deleteFile(relative(name));
                return true;
            } catch (NoSuchFileException exception) { context.verify(); return false; }
        }

        private Path relative(String name) {
            String id = name.endsWith(".part") ? name.substring(0, name.length() - 5) : name;
            try { if (!UUID.fromString(id).toString().equals(id)) unavailable(); }
            catch (IllegalArgumentException exception) { unavailable(); }
            return context.path.getFileSystem().getPath(name);
        }
    }

    private static final class Context {
        final Path path;
        final String digest;
        final ReentrantLock mutex = new ReentrantLock();
        final AtomicBoolean poisoned = new AtomicBoolean();
        final ThreadPoolExecutor workers;
        final DirectoryStream<Path> directory;
        final SecureDirectoryStream<Path> secure;
        final FileChannel control;
        final Object rootKey;
        final Object controlKey;
        int users;

        Context(Path path, String digest) {
            this.path = path; this.digest = digest;
            DirectoryStream<Path> opened = null;
            FileChannel channel = null;
            try {
                validatePath(path);
                if (!readBinding(path.resolve(MARKER)).equals(digest) || !readBinding(path.resolve(CONTROL)).equals(digest)) unavailable();
                rootKey = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                controlKey = Files.readAttributes(path.resolve(CONTROL), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                opened = Files.newDirectoryStream(path);
                directory = opened;
                secure = opened instanceof SecureDirectoryStream<?> ? (SecureDirectoryStream<Path>) opened : null;
                boolean windows = path.getFileSystem().provider().getClass().getSimpleName().equals("WindowsFileSystemProvider");
                if (windows) {
                    channel = FileChannel.open(path.resolve(CONTROL), StandardOpenOption.READ, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE);
                } else {
                    if (secure == null || rootKey == null || controlKey == null) unavailable();
                    SeekableByteChannel nativeChannel = secure.newByteChannel(path.getFileSystem().getPath(CONTROL),
                            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
                    if (!(nativeChannel instanceof FileChannel fileChannel)) { nativeChannel.close(); unavailable(); }
                    channel = (FileChannel) nativeChannel;
                }
                control = channel;
                workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                    Thread thread = new Thread(runnable, "transaction-import-storage-io");
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
                verify();
            } catch (Exception exception) {
                try { if (channel != null) channel.close(); } catch (IOException ignored) { }
                try { if (opened != null) opened.close(); } catch (IOException ignored) { }
                throw new ImportStorageBindingException();
            }
        }

        void verify() {
            if (poisoned.get()) unavailable();
            try {
                validatePath(path);
                if (!Objects.equals(rootKey, Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())
                        || !Objects.equals(controlKey, Files.readAttributes(path.resolve(CONTROL), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())
                        || !readBinding(path.resolve(MARKER)).equals(digest) || !readControlBinding().equals(digest)
                        || !control.isOpen()) unavailable();
            } catch (Exception exception) {
                poisoned.set(true);
                throw new ImportStorageBindingException();
            }
        }

        String readControlBinding() throws IOException {
            // Linux POSIX locks can be lost when another descriptor for the same file closes.
            // Never open a second channel to the control file after this unique channel exists.
            if (control.size() != 64) unavailable();
            // Do not probe one byte beyond EOF: that byte is the Windows mutex region.
            ByteBuffer buffer = ByteBuffer.allocate(64);
            while (buffer.hasRemaining()) {
                int read = control.read(buffer, buffer.position());
                if (read < 0) break;
            }
            if (buffer.position() != 64) unavailable();
            return new String(buffer.array(), 0, 64, java.nio.charset.StandardCharsets.US_ASCII);
        }

        static String readBinding(Path file) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isOther() || attributes.isSymbolicLink() || attributes.size() != 64) unavailable();
            try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.allocate(65);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
                if (buffer.position() != 64) unavailable();
                return new String(buffer.array(), 0, 64, java.nio.charset.StandardCharsets.US_ASCII);
            }
        }

        static void validatePath(Path root) throws IOException {
            if (!root.toRealPath().equals(root)) unavailable();
            for (Path path = root; path != null; path = path.getParent()) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) unavailable();
            }
            if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS);
                if (permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
                        || permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)) unavailable();
            }
        }
    }

    @Override public void close() {
        synchronized (CONTEXTS) {
            if (closed) return;
            closed = true;
            if (--context.users != 0) return;
            context.workers.shutdown();
            try {
                if (!context.workers.awaitTermination(4, TimeUnit.SECONDS)) {
                    context.poisoned.set(true);
                    // A hung real worker retains pin/guard until process termination. Never close it from this caller.
                    return;
                }
                context.control.close(); context.directory.close();
                CONTEXTS.remove(context.path, context);
            } catch (IOException | InterruptedException exception) {
                context.poisoned.set(true);
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }

    private static void unavailable() { throw new InvalidImportFileException(503, "Trusted import storage protection is unavailable"); }
}
