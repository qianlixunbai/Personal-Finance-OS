package com.financeos.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import com.sun.nio.file.ExtendedOpenOption;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterization of unsafe design samples, NOT the production Registry or a C9 repair.
 * Green means the counterexample was observed. The original C9 safety tests remain unchanged.
 * Only isolated Testcontainers SQL and JUnit-owned directories are used.
 */
@Timeout(30)
class TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest extends PostgresIntegrationTest {
    @TempDir Path directory;

    @BeforeEach
    void createDesignFixture() {
        jdbcTemplate.execute("""
                CREATE TABLE c9_design_registry (
                    reference UUID PRIMARY KEY,
                    state VARCHAR(12) NOT NULL,
                    epoch BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    lease_until TIMESTAMPTZ NOT NULL
                )
                """);
    }

    @AfterEach
    void removeDesignFixture() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS c9_design_registry");
    }

    @RepeatedTest(3)
    void databaseEpochRejectsPublicationButCannotStopLateFilesystemCreation() throws Exception {
        UUID reference = reserve();
        Path payload = directory.resolve(reference.toString());
        CountDownLatch permissionChecked = new CountDownLatch(1);
        CountDownLatch resumeWriter = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> writer = executor.submit(() -> {
                assertThat(allowed(reference)).isTrue();
                permissionChecked.countDown();
                await(resumeWriter);
                Files.writeString(payload, "synthetic", StandardOpenOption.CREATE_NEW);
                return publish(reference);
            });
            await(permissionChecked);
            expireRetireAndDelete(reference, payload);
            assertThat(Files.exists(payload)).isFalse();
            resumeWriter.countDown();
            assertThat(writer.get(10, TimeUnit.SECONDS)).isZero();
            assertDeleted(reference);
            assertThat(Files.isRegularFile(payload)).isTrue();
            System.out.println("C9 design counterexample: stale publish=0; DELETED record; late file=true");
        } finally {
            resumeWriter.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @RepeatedTest(3)
    void expiredReadPermissionDoesNotProveThatTheActualReadHasFinished() throws Exception {
        UUID reference = reserve();
        Path payload = directory.resolve(reference.toString());
        Files.writeString(payload, "synthetic", StandardOpenOption.CREATE_NEW);
        CountDownLatch permissionChecked = new CountDownLatch(1);
        CountDownLatch resumeReader = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> reader = executor.submit(() -> {
                assertThat(allowed(reference)).isTrue();
                permissionChecked.countDown();
                await(resumeReader);
                return Files.readString(payload);
            });
            await(permissionChecked);
            // Represents a retired Plan: no current Session references it, but a reader has not opened it yet.
            expireRetireAndDelete(reference, payload);
            resumeReader.countDown();
            assertThatThrownBy(() -> reader.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(java.nio.file.NoSuchFileException.class);
            assertDeleted(reference);
            System.out.println("C9 design counterexample: read permission expired; unfinished reader loses file");
        } finally {
            resumeReader.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @RepeatedTest(3)
    void cancelledFutureCanStillHaveAWorkerThatWritesAfterCleanup() throws Exception {
        UUID reference = reserve();
        Path payload = directory.resolve(reference.toString());
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch interruptObserved = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch actualWorkerStopped = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> {
                try {
                    assertThat(allowed(reference)).isTrue();
                    workerEntered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = releaseWorker.await(10, TimeUnit.SECONDS);
                            if (!released) throw new AssertionError("C9 design worker timed out");
                        } catch (InterruptedException ignored) {
                            // Deliberately models an operation that does not cooperate with interruption.
                            interruptObserved.countDown();
                        }
                    }
                    Files.writeString(payload, "synthetic", StandardOpenOption.CREATE_NEW);
                    assertThat(publish(reference)).isZero();
                } catch (java.io.IOException exception) {
                    throw new AssertionError("C9 isolated file operation failed");
                } finally {
                    actualWorkerStopped.countDown();
                }
            });
            await(workerEntered);
            assertThat(writer.cancel(true)).isTrue();
            await(interruptObserved);
            assertThat(writer.isDone()).isTrue();
            assertThat(actualWorkerStopped.getCount()).isEqualTo(1);
            expireRetireAndDelete(reference, payload);
            releaseWorker.countDown();
            await(actualWorkerStopped);
            assertDeleted(reference);
            assertThat(Files.isRegularFile(payload)).isTrue();
            System.out.println("C9 design counterexample: Future done != worker stopped; late file=true");
        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @RepeatedTest(3)
    void pathAndIdentityPrecheckDoesNotBindTheNextCreationToTheCheckedDirectory() throws Exception {
        Path root = Files.createDirectory(directory.resolve("managed"));
        Path original = directory.resolve("original-managed");
        UUID reference = UUID.randomUUID();
        Path candidate = root.resolve(reference.toString()).normalize();
        String identity = UUID.randomUUID().toString();
        Files.writeString(root.resolve("volume-marker"), identity, StandardOpenOption.CREATE_NEW);
        assertThat(root.toRealPath()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(candidate.getParent()).isEqualTo(root);
        // Deterministic namespace replacement after the last validation and before the actual operation.
        Files.move(root, original);
        Files.createDirectory(root);
        try (var output = Files.newByteChannel(candidate,
                java.util.Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS))) {
            output.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertThat(Files.exists(root.resolve("volume-marker"))).isFalse();
        assertThat(Files.readString(original.resolve("volume-marker"))).isEqualTo(identity);
        assertThat(Files.exists(original.resolve(reference.toString()))).isFalse();
        assertThat(Files.exists(candidate)).isTrue();
        System.out.println("C9 design counterexample: NOFOLLOW final component still writes in replaced parent");
    }

    @Test
    void recordsSecureDirectoryPrimitiveAvailabilityWithoutPretendingItIsPortable() throws Exception {
        try (var stream = Files.newDirectoryStream(directory)) {
            System.out.println("C9 storage capability: provider=" + directory.getFileSystem().provider().getClass().getSimpleName()
                    + "; secureDirectoryStream=" + (stream instanceof SecureDirectoryStream<?>)
                    + "; fileKeyAvailable=" + (Files.readAttributes(directory, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey() != null));
            assertThat(stream).isNotNull();
        }
    }

    @Test
    void probesWhetherAnExistingControlHandlePreventsRootAndAncestorReplacement() throws Exception {
        Path parent = Files.createDirectory(directory.resolve("pinned-parent"));
        Path root = Files.createDirectory(parent.resolve("managed"));
        Path control = Files.createFile(root.resolve("control-lock"));
        boolean rootRenameDenied;
        boolean ancestorRenameDenied;
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        try (FileChannel channel = windows
                ? FileChannel.open(control, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE)
                : FileChannel.open(control, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock guard = channel.lock(0, 1, false)) {
            assertThat(channel.isOpen()).isTrue();
            assertThat(guard.isValid()).isTrue();
            rootRenameDenied = renameDenied(root, parent.resolve("moved-managed"));
            ancestorRenameDenied = renameDenied(parent, directory.resolve("moved-parent"));
            if (windows) {
                assertThat(rootRenameDenied).isTrue();
                assertThat(ancestorRenameDenied).isTrue();
            } else {
                // A POSIX advisory lock protects cooperative I/O, not the directory namespace.
                assertThat(rootRenameDenied).isFalse();
                assertThat(ancestorRenameDenied).isFalse();
                Path movedParent = directory.resolve("moved-parent");
                Path movedRoot = movedParent.resolve("moved-managed");
                assertThat(Files.isRegularFile(movedRoot.resolve("control-lock"))).isTrue();
                Files.createDirectories(root);
                try (FileChannel replacement = FileChannel.open(Files.createFile(root.resolve("control-lock")),
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                     FileLock replacementGuard = replacement.tryLock(0, 1, false);
                     var stream = Files.newDirectoryStream(movedRoot)) {
                    assertThat(replacementGuard).as("a new path denotes a different lock inode").isNotNull();
                    assertThat(stream).isInstanceOf(SecureDirectoryStream.class);
                    @SuppressWarnings("unchecked")
                    var secure = (SecureDirectoryStream<Path>) stream;
                    // The real Linux directory handle keeps relative I/O on the opened original root.
                    Files.move(movedRoot, movedParent.resolve("renamed-again"));
                    try (var output = secure.newByteChannel(Path.of("bound-payload"), java.util.Set.of(
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                        output.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
                    }
                    assertThat(Files.exists(movedParent.resolve("renamed-again/bound-payload"))).isTrue();
                    assertThat(Files.exists(root.resolve("bound-payload"))).isFalse();
                    assertThat(guard.isValid()).isTrue();
                }
            }
        }
        System.out.println("C9 control-handle capability: windows=" + windows + "; rootRenameDenied=" + rootRenameDenied
                + "; ancestorRenameDenied=" + ancestorRenameDenied);
        if (rootRenameDenied && ancestorRenameDenied) {
            // The same operations must succeed after the handle closes: distinguish pinning from permissions.
            Files.move(root, parent.resolve("after-close-managed"));
            Files.move(parent, directory.resolve("after-close-parent"));
        }
    }

    private static boolean renameDenied(Path source, Path target) throws Exception {
        try {
            Files.move(source, target);
            return false;
        } catch (java.nio.file.FileSystemException exception) {
            assertThat(Files.exists(source)).isTrue();
            assertThat(Files.exists(target)).isFalse();
            return true;
        }
    }

    private UUID reserve() {
        UUID reference = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO c9_design_registry VALUES (?, 'RESERVED', 1,
                    CURRENT_TIMESTAMP - INTERVAL '25 hours', CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, reference);
        return reference;
    }

    private boolean allowed(UUID reference) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT state = 'RESERVED' AND epoch = 1 AND lease_until > CURRENT_TIMESTAMP
                FROM c9_design_registry WHERE reference = ?
                """, Boolean.class, reference));
    }

    private int publish(UUID reference) {
        return jdbcTemplate.update("""
                UPDATE c9_design_registry SET state = 'LIVE'
                WHERE reference = ? AND state = 'RESERVED' AND epoch = 1 AND lease_until > CURRENT_TIMESTAMP
                """, reference);
    }

    private void expireRetireAndDelete(UUID reference, Path payload) throws Exception {
        jdbcTemplate.update("UPDATE c9_design_registry SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE reference = ?", reference);
        assertThat(jdbcTemplate.update("""
                UPDATE c9_design_registry SET state = 'RETIRED', epoch = epoch + 1
                WHERE reference = ? AND state = 'RESERVED' AND lease_until < CURRENT_TIMESTAMP
                  AND created_at < CURRENT_TIMESTAMP - INTERVAL '24 hours'
                """, reference)).isEqualTo(1);
        Files.deleteIfExists(payload);
        assertThat(jdbcTemplate.update("UPDATE c9_design_registry SET state = 'DELETED' WHERE reference = ? AND state = 'RETIRED'", reference))
                .isEqualTo(1);
    }

    private void assertDeleted(UUID reference) {
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM c9_design_registry WHERE reference = ?", String.class, reference))
                .isEqualTo("DELETED");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("C9 bounded design latch").isTrue();
    }
}
