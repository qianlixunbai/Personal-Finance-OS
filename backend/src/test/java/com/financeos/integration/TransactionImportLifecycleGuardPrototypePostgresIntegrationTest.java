package com.financeos.integration;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolated positive protocol prototype, NOT production repair. A separate JVM owns the
 * actual I/O guard. The test controller performs real PostgreSQL authorization while that
 * guard is held; the child is not a second Spring application or a JDBC client.
 */
@Timeout(40)
class TransactionImportLifecycleGuardPrototypePostgresIntegrationTest extends PostgresIntegrationTest {
    @TempDir Path directory;
    private Path control;

    @BeforeEach
    void createFixture() throws Exception {
        control = Files.createFile(directory.resolve("control-lock"));
        jdbcTemplate.execute("""
                CREATE TABLE c9_guard_registry (
                    reference UUID PRIMARY KEY,
                    state VARCHAR(12) NOT NULL,
                    epoch BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    lease_until TIMESTAMPTZ NOT NULL,
                    retry_count INT NOT NULL DEFAULT 0
                )
                """);
    }

    @AfterEach
    void dropFixture() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS c9_guard_registry");
    }

    @RepeatedTest(3)
    void actualWriterGuardBlocksDeletionUntilTheWorkerReallyFinishes() throws Exception {
        UUID reference = reserve("RESERVED");
        Path payload = directory.resolve(reference.toString());
        try (Worker worker = start(reference, "WRITE"); FileChannel cleaner = channel(control)) {
            worker.expect("LOCKED");
            assertThat(allowed(reference)).isTrue();
            worker.send("ALLOW");
            worker.expect("READY");
            retire(reference);
            assertBusyAndRecordRetry(cleaner, reference);
            assertState(reference, "RETIRED");
            assertThat(Files.exists(payload)).isFalse();
            worker.send("RELEASE");
            worker.expect("FINISHED");
            worker.expectSuccessfulExit();
            assertThat(Files.isRegularFile(payload)).isTrue();
            assertThat(publish(reference)).isZero();
            deleteUnderGuard(cleaner, reference);
            assertThat(Files.exists(payload)).isFalse();
            assertState(reference, "DELETED");
            System.out.println("C9 positive guard: cross-JVM writer retained protection; retry converged");
        }
    }

    @RepeatedTest(3)
    void actualReaderGuardProtectsAReadAfterTheDatabasePermissionExpires() throws Exception {
        UUID reference = reserve("LIVE");
        Files.writeString(directory.resolve(reference.toString()), "synthetic", StandardOpenOption.CREATE_NEW);
        try (Worker worker = start(reference, "READ"); FileChannel cleaner = channel(control)) {
            worker.expect("LOCKED");
            assertThat(allowed(reference)).isTrue();
            worker.send("ALLOW");
            worker.expect("READY");
            retire(reference);
            assertBusyAndRecordRetry(cleaner, reference);
            assertThat(Files.isRegularFile(directory.resolve(reference.toString()))).isTrue();
            worker.send("RELEASE");
            worker.expect("FINISHED");
            worker.expectSuccessfulExit();
            deleteUnderGuard(cleaner, reference);
            assertState(reference, "DELETED");
            System.out.println("C9 positive guard: read completed before physical unlink despite expired permission");
        }
    }

    @RepeatedTest(3)
    void staleWriterRechecksEpochAfterAcquiringTheGuardAndCannotRecreateDeletedPayload() throws Exception {
        UUID reference = reserve("RESERVED");
        retire(reference);
        try (FileChannel cleaner = channel(control)) {
            deleteUnderGuard(cleaner, reference);
        }
        try (Worker worker = start(reference, "WRITE")) {
            worker.expect("LOCKED");
            assertThat(allowed(reference)).isFalse();
            worker.send("DENY");
            worker.expect("DENIED");
            worker.expectSuccessfulExit();
        }
        assertThat(Files.exists(directory.resolve(reference.toString()))).isFalse();
        assertState(reference, "DELETED");
        System.out.println("C9 positive guard: authority rechecked inside acquired guard; stale creation denied");
    }

    @RepeatedTest(3)
    void realChildProcessTerminationReleasesItsNativeGuardForAnIndependentCleaner() throws Exception {
        UUID reference = reserve("RESERVED");
        // A crash artifact is deliberate synthetic fixture data, not a simulated production save.
        Path part = directory.resolve(reference + ".part");
        Files.writeString(part, "synthetic-part", StandardOpenOption.CREATE_NEW);
        try (Worker worker = start(reference, "WRITE"); FileChannel cleaner = channel(control)) {
            worker.expect("LOCKED");
            assertThat(allowed(reference)).isTrue();
            worker.send("ALLOW");
            worker.expect("READY");
            retire(reference);
            assertBusyAndRecordRetry(cleaner, reference);
            worker.killAndAwaitExit();
            deleteUnderGuard(cleaner, reference);
            assertThat(Files.exists(part)).isFalse();
            assertState(reference, "DELETED");
            System.out.println("C9 positive guard: actual child JVM killed; native lock released; part retry converged");
        }
    }

    @Test
    void aCopiedIdentityMarkerCannotAuthenticateWhichManagedDirectoryWasMountedAfterRestart() throws Exception {
        Path original = Files.createDirectory(directory.resolve("original"));
        Path replacement = Files.createDirectory(directory.resolve("replacement"));
        Path archived = directory.resolve("archived-original");
        String storageId = UUID.randomUUID().toString();
        Files.writeString(original.resolve("storage-identity"), storageId, StandardOpenOption.CREATE_NEW);
        Files.copy(original.resolve("storage-identity"), replacement.resolve("storage-identity"));
        UUID reference = reserve("LIVE");
        Files.writeString(original.resolve(reference.toString()), "synthetic", StandardOpenOption.CREATE_NEW);
        Path configuredPath = original.toRealPath();
        Object volumeSerial = Files.getFileStore(original).getAttribute("volume:vsn");
        assertThat(original.toRealPath()).isNotEqualTo(replacement.toRealPath());
        assertThat(Files.readString(original.resolve("storage-identity")))
                .isEqualTo(Files.readString(replacement.resolve("storage-identity")));
        // Models a stopped process: no old root handle remains to prevent namespace replacement.
        Files.move(original, archived);
        Files.move(replacement, original);
        assertThat(original.toRealPath()).isEqualTo(configuredPath);
        assertThat(Files.readString(original.resolve("storage-identity"))).isEqualTo(storageId);
        assertThat(Files.getFileStore(original).getAttribute("volume:vsn")).isEqualTo(volumeSerial);
        retire(reference);
        assertThat(Files.deleteIfExists(original.resolve(reference.toString()))).isFalse();
        // An intentionally unsafe model accepts marker + serial + path and records false absence.
        jdbcTemplate.update("UPDATE c9_guard_registry SET state='DELETED' WHERE reference=? AND state='RETIRED'", reference);
        assertState(reference, "DELETED");
        assertThat(Files.isRegularFile(archived.resolve(reference.toString()))).isTrue();
        System.out.println("C9 identity counterexample: same path + copied marker + same volume serial; false DELETED with original payload present");
    }

    @Test
    void probesProviderVolumeSerialWithoutClaimingAnAuthenticatedVolumeIdentity() throws Exception {
        var first = Files.getFileStore(directory);
        var second = Files.getFileStore(directory);
        Object serial = first.getAttribute("volume:vsn");
        assertThat(serial).isInstanceOf(Integer.class);
        assertThat(serial).isEqualTo(second.getAttribute("volume:vsn"));
        System.out.println("C9 Windows volume capability: providerSerialAvailable=true; repeatedQueryEqual=true; filesystem="
                + first.type());
    }

    private UUID reserve(String state) {
        UUID reference = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO c9_guard_registry(reference,state,epoch,created_at,lease_until)
                VALUES (?, ?, 1, CURRENT_TIMESTAMP - INTERVAL '25 hours', CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, reference, state);
        return reference;
    }

    private boolean allowed(UUID reference) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT state IN ('RESERVED','LIVE') AND epoch=1 AND lease_until>CURRENT_TIMESTAMP
                FROM c9_guard_registry WHERE reference=?
                """, Boolean.class, reference));
    }

    private int publish(UUID reference) {
        return jdbcTemplate.update("""
                UPDATE c9_guard_registry SET state='LIVE'
                WHERE reference=? AND state='RESERVED' AND epoch=1 AND lease_until>CURRENT_TIMESTAMP
                """, reference);
    }

    private void retire(UUID reference) {
        jdbcTemplate.update("UPDATE c9_guard_registry SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE reference=?", reference);
        assertThat(jdbcTemplate.update("""
                UPDATE c9_guard_registry SET state='RETIRED',epoch=epoch+1
                WHERE reference=? AND state IN ('LIVE','RESERVED') AND epoch=1
                    AND lease_until<CURRENT_TIMESTAMP AND created_at<CURRENT_TIMESTAMP-INTERVAL '24 hours'
                """, reference)).isEqualTo(1);
    }

    private void assertBusyAndRecordRetry(FileChannel cleaner, UUID reference) throws Exception {
        try (FileLock contender = cleaner.tryLock(0, 1, false)) {
            assertThat(contender).as("actual separate-JVM lock must exclude physical deletion").isNull();
        }
        assertThat(jdbcTemplate.update("UPDATE c9_guard_registry SET retry_count=retry_count+1 WHERE reference=? AND state='RETIRED'", reference))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT retry_count FROM c9_guard_registry WHERE reference=?", Integer.class, reference))
                .isEqualTo(1);
    }

    private void deleteUnderGuard(FileChannel cleaner, UUID reference) throws Exception {
        FileLock acquired = cleaner.tryLock(0, 1, false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (acquired == null && System.nanoTime() < deadline) {
            // Process exit notification does not guarantee that OS cleanup has completed yet.
            assertThat(jdbcTemplate.update("UPDATE c9_guard_registry SET retry_count=retry_count+1 WHERE reference=? AND state='RETIRED'", reference))
                    .isEqualTo(1);
            TimeUnit.MILLISECONDS.sleep(25);
            acquired = cleaner.tryLock(0, 1, false);
        }
        try (FileLock guard = acquired) {
            assertThat(guard).isNotNull();
            assertState(reference, "RETIRED");
            Files.deleteIfExists(directory.resolve(reference + ".part"));
            Files.deleteIfExists(directory.resolve(reference.toString()));
            assertThat(jdbcTemplate.update("UPDATE c9_guard_registry SET state='DELETED' WHERE reference=? AND state='RETIRED' AND epoch=2", reference))
                    .isEqualTo(1);
        }
    }

    private void assertState(UUID reference, String state) {
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM c9_guard_registry WHERE reference=?", String.class, reference)).isEqualTo(state);
    }

    private Worker start(UUID reference, String operation) throws Exception {
        return new Worker(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                GuardWorker.class.getName(), directory.toString(), reference.toString(), operation)
                .redirectErrorStream(true).start());
    }

    private static FileChannel channel(Path control) throws Exception {
        return FileChannel.open(control, StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE);
    }

    private static final class Worker implements AutoCloseable {
        private final Process process;
        private final BufferedWriter commands;
        private final BlockingQueue<String> signals = new ArrayBlockingQueue<>(16);
        private final Thread reader;

        Worker(Process process) {
            this.process = process;
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            reader = new Thread(() -> {
                try (var input = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        if (!signals.offer(line)) throw new IllegalStateException("C9 bounded signal queue full");
                    }
                } catch (Exception exception) {
                    signals.offer("READER_FAILURE");
                }
            }, "c9-isolated-child-signals");
            reader.setDaemon(true);
            reader.start();
        }

        void expect(String expected) throws Exception {
            assertThat(signals.poll(10, TimeUnit.SECONDS)).isEqualTo(expected);
        }

        void send(String command) throws Exception {
            commands.write(command);
            commands.newLine();
            commands.flush();
        }

        void expectSuccessfulExit() throws Exception {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        }

        void killAndAwaitExit() throws Exception {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }

        @Override
        public void close() throws Exception {
            try {
                if (process.isAlive()) killAndAwaitExit();
            } finally {
                commands.close();
                reader.join(10000);
                assertThat(reader.isAlive()).isFalse();
            }
        }
    }

    /** Standalone cooperative storage worker; no credentials and no Spring application. */
    public static final class GuardWorker {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            Path payload = root.resolve(UUID.fromString(args[1]).toString());
            try (var input = new BufferedReader(new InputStreamReader(System.in));
                 FileChannel channel = FileChannel.open(root.resolve("control-lock"),
                         StandardOpenOption.READ, StandardOpenOption.WRITE,
                         LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE);
                 FileLock guard = channel.tryLock(0, 1, false)) {
                if (guard == null) throw new IllegalStateException("C9 child guard unavailable");
                signal("LOCKED");
                if (!"ALLOW".equals(input.readLine())) {
                    signal("DENIED");
                    return;
                }
                signal("READY");
                if (!"RELEASE".equals(input.readLine())) throw new IllegalStateException("C9 release absent");
                if ("WRITE".equals(args[2])) {
                    Files.writeString(payload, "synthetic", StandardOpenOption.CREATE_NEW);
                } else if (!"synthetic".equals(Files.readString(payload))) {
                    throw new IllegalStateException("C9 synthetic read mismatch");
                }
            }
            signal("FINISHED");
        }

        private static void signal(String value) {
            System.out.println(value);
            System.out.flush();
        }
    }
}
