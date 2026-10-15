package com.financeos.integration;

import com.financeos.module.importing.mapper.TransactionImportFileObjectMapper;
import com.financeos.module.importing.service.TransactionImportFileLifecycleService;
import com.financeos.module.importing.storage.*;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.AccessMode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Every process executes the production managed storage and durable registry, against isolated PostgreSQL. */
@Timeout(100)
class TransactionImportManagedProcessPostgresIntegrationTest extends PostgresIntegrationTest {
    @TempDir Path directory;
    @Autowired Environment environment;
    @Autowired TransactionImportFileLifecycleService lifecycle;
    @Autowired TransactionImportFileObjectMapper objects;
    UUID deployment = UUID.randomUUID(), storage = UUID.randomUUID();
    @BeforeEach void user() {
        jdbcTemplate.update("INSERT INTO users(username,email,password_hash) VALUES('c9-process','c9-process@example.com','synthetic-fixture')");
    }

    @RepeatedTest(3) void realChildJvmKillRetainsPartAndRecoversThroughNewProductionStorage() throws Exception {
        Path root = directory.resolve("raw");
        TrustedImportStorageRoot.provision(root, deployment, storage, "RAW");
        try (var local = new LocalTemporaryImportFileStorage(root, 5 * 1024 * 1024, deployment, storage, "RAW", lifecycle)) {
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    ImportManagedStorageProcessWorker.class.getName(), "WRITE").redirectErrorStream(true);
            builder.environment().putAll(workerEnvironment(root.toString(), false));
            Process child = builder.start();
            BlockingQueue<String> signals = new ArrayBlockingQueue<>(8);
            Thread reader = new Thread(() -> {
                try (var input = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    for (String line; (line = input.readLine()) != null;) {
                        if (Set.of("WRITING", "SAVED", "C9_WORKER_FAILURE").contains(line)) signals.offer(line);
                    }
                } catch (IOException ignored) { }
            }, "c9-owned-child-signals");
            reader.setDaemon(true); reader.start();
            try {
                assertThat(signals.poll(15, TimeUnit.SECONDS)).isEqualTo("WRITING");
                String ref = onlyReference();
                assertThat(Files.size(root.resolve(ref + ".part"))).isPositive();
                expire(ref);
                local.cleanupRetired();
                assertThat(objects.find(ref).getState()).isEqualTo("RETIRED");
                assertThat(Files.exists(root.resolve(ref + ".part"))).isTrue();
                assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM transaction_import_file_objects WHERE reference=?", Integer.class, ref)).isPositive();
                child.destroyForcibly();
                assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).isNotZero();
                due(ref);
                builder.command().set(builder.command().size() - 1, "CLEANUP");
                Path recoveryLog = directory.resolve("owned-recovery.log");
                builder.redirectOutput(recoveryLog.toFile());
                Process restarted = builder.start();
                try {
                    assertThat(restarted.waitFor(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(restarted.exitValue()).isZero();
                    assertThat(Files.readAllLines(recoveryLog)).contains("CLEANED");
                } finally {
                    if (restarted.isAlive()) restarted.destroyForcibly();
                    assertThat(restarted.waitFor(10, TimeUnit.SECONDS)).isTrue();
                }
                assertThat(objects.find(ref).getState()).isEqualTo("DELETED");
                assertThat(Files.exists(root.resolve(ref + ".part"))).isFalse();
                assertThat(Files.exists(root.resolve(ref))).isFalse();
                assertNoFinancialWrites();
            } finally {
                if (child.isAlive()) child.destroyForcibly();
                assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
                child.getOutputStream().close(); reader.join(2000);
                assertThat(reader.isAlive()).isFalse();
            }
        }
    }

    @RepeatedTest(3) void linuxPersistentVolumeUsesProductionSecureOperationsAcrossKillAndRestart() throws Exception {
        Path jar = CurrentManagedProcessArtifact.required();
        String volume = "finance-os-c9-owned-" + UUID.randomUUID();
        var docker = DockerClientFactory.instance().client();
        docker.createVolumeCmd().withName(volume).exec();
        String ref;
        try {
            try (var provision = linuxWorker(volume, "PROVISION", jar)) { startAndVerifyPayload(provision, jar); }
            try (var writer = linuxWorker(volume, "WRITE", jar)) {
                startAndVerifyPayload(writer, jar); ref = onlyReference();
                expire(ref);
                try (var contender = linuxWorker(volume, "CLEANUP", jar)) { startAndVerifyPayload(contender, jar); }
                assertThat(objects.find(ref).getState()).isEqualTo("RETIRED");
                assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM transaction_import_file_objects WHERE reference=?", Integer.class, ref)).isPositive();
                assertThat(writer.execInContainer("sh", "-c", "test -s /managed/raw/" + ref + ".part").getExitCode()).isZero();
                docker.killContainerCmd(writer.getContainerId()).withSignal("KILL").exec();
                assertThat(docker.inspectContainerCmd(writer.getContainerId()).exec().getState().getRunning()).isFalse();
            }
            due(ref);
            try (var recovery = linuxWorker(volume, "CLEANUP", jar)) {
                startAndVerifyPayload(recovery, jar);
                assertThat(objects.find(ref).getState()).isEqualTo("DELETED");
                assertThat(recovery.execInContainer("sh", "-c", "test ! -e /managed/raw/" + ref + ".part && test ! -e /managed/raw/" + ref).getExitCode()).isZero();
            }
            assertNoFinancialWrites();
        } finally {
            assertThat(volume).startsWith("finance-os-c9-owned-");
            docker.removeVolumeCmd(volume).exec(); // Only this test's newly created volume.
        }
    }

    @Test void packagedOperatorProvisionerCreatesPersistentBindingsOnceAndRefusesReinitialization() throws Exception {
        Path jar = CurrentManagedProcessArtifact.required();
        String volume = "finance-os-c9-owned-" + UUID.randomUUID();
        var docker = DockerClientFactory.instance().client();
        docker.createVolumeCmd().withName(volume).exec();
        String command = "java -Dloader.main=com.financeos.module.importing.storage.ImportStorageProvisioner -cp /payload/app.jar org.springframework.boot.loader.launch.PropertiesLauncher --provision";
        try (var container = new GenericContainer<>("eclipse-temurin:21-jdk-jammy")
                .withCreateContainerCmdModifier(created -> created.getHostConfig().withBinds(new Bind(volume, new Volume("/managed")),
                        new Bind(jar.toString(), new Volume("/payload/app.jar"), AccessMode.ro)))
                .withEnv(Map.of("FINANCE_IMPORT_STORAGE_RAW_ROOT", "/managed/raw", "FINANCE_IMPORT_STORAGE_PLAN_ROOT", "/managed/plan",
                        "FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID", deployment.toString(), "FINANCE_IMPORT_STORAGE_RAW_ID", storage.toString(), "FINANCE_IMPORT_STORAGE_PLAN_ID", UUID.randomUUID().toString()))
                .withCommand("sh", "-c", command + "; first=$?; echo CLI_FIRST_EXIT=$first; test \"$first\" -eq 0 || exit 1; "
                        + command + "; result=$?; echo CLI_REPEAT_EXIT=$result; test \"$result\" -eq 2 || exit 1; echo CLI_READY; sleep 300")
                .waitingFor(Wait.forLogMessage("(?m)^CLI_READY\\r?\\n", 1).withStartupTimeout(Duration.ofSeconds(25)))) {
            startAndVerifyPayload(container, jar);
            assertThat(container.getLogs()).contains("CLI_FIRST_EXIT=0", "CLI_REPEAT_EXIT=2");
            System.out.println("C9 PropertiesLauncher: firstExit=0; repeatExit=2; persistent binding permissions verified");
            assertThat(container.execInContainer("sh", "-c", "test $(stat -c %a /managed/raw) = 700 && test $(stat -c %a /managed/plan) = 700 && test $(wc -c < /managed/raw/.import-binding) -eq 64 && test $(wc -c < /managed/plan/.import-control) -eq 64").getExitCode()).isZero();
        } finally {
            assertThat(volume).startsWith("finance-os-c9-owned-");
            docker.removeVolumeCmd(volume).exec();
        }
    }

    private GenericContainer<?> linuxWorker(String volume, String mode, Path jar) throws java.net.UnknownHostException {
        String ready = "WRITE".equals(mode) ? "WRITING" : "C9_EXIT_0";
        return new GenericContainer<>("eclipse-temurin:21-jdk-jammy")
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withBinds(
                        new Bind(volume, new Volume("/managed")),
                        new Bind(jar.toString(), new Volume("/payload/app.jar"), AccessMode.ro),
                        new Bind(Path.of("target", "test-classes").toAbsolutePath().toString(), new Volume("/payload/test-classes"), AccessMode.ro)))
                .withEnv(workerEnvironment("/managed/raw", true))
                .withCommand("sh", "-c", "mkdir /work && cd /work && jar xf /payload/app.jar && java -cp '/payload/test-classes:BOOT-INF/classes:BOOT-INF/lib/*' com.financeos.integration.ImportManagedStorageProcessWorker "
                        + mode + "; result=$?; echo C9_EXIT_$result; test \"$result\" -eq 0 || exit 1; sleep 300")
                .waitingFor(Wait.forLogMessage("(?m)^" + ready + "\\r?\\n", 1).withStartupTimeout(Duration.ofSeconds(25)));
    }
    private Map<String, String> workerEnvironment(String root, boolean linux) throws java.net.UnknownHostException {
        String url = environment.getRequiredProperty("spring.datasource.url");
        if (linux) {
            // Forward a JVM-local mapped port; an external Docker host must remain externally addressed.
            var uri = java.net.URI.create(url.substring("jdbc:".length()));
            if (java.net.InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
                Testcontainers.exposeHostPorts(uri.getPort());
                url = url.replace("//" + uri.getHost() + ":", "//host.testcontainers.internal:");
            }
        }
        return Map.of("C9_DB_URL", url, "C9_DB_USER", environment.getRequiredProperty("spring.datasource.username"),
                "C9_DB_PASSWORD", environment.getRequiredProperty("spring.datasource.password"),
                "C9_DEPLOYMENT", deployment.toString(), "C9_STORAGE", storage.toString(), "C9_SESSION", UUID.randomUUID().toString(), "C9_ROOT", root);
    }
    private void startAndVerifyPayload(GenericContainer<?> container, Path jar) throws Exception {
        container.start();
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        var actual = container.execInContainer("sha256sum", "/payload/app.jar");
        assertThat(actual.getExitCode()).isZero();
        assertThat(actual.getStdout().strip()).isEqualTo(sha + "  /payload/app.jar");
        System.out.println("C9 container payload: /payload/app.jar sha256=" + sha + "; /managed uses test-owned named volume");
    }
    private String onlyReference() { return jdbcTemplate.queryForObject("SELECT reference FROM transaction_import_file_objects WHERE storage_id=CAST(? AS UUID)", String.class, storage.toString()); }
    private void expire(String ref) { jdbcTemplate.update("UPDATE transaction_import_file_objects SET created_at=CURRENT_TIMESTAMP-INTERVAL '25 hours',lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second',next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE reference=?", ref); }
    private void due(String ref) { jdbcTemplate.update("UPDATE transaction_import_file_objects SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE reference=?", ref); }
    private void assertNoFinancialWrites() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transaction_import_batches", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transaction_import_sessions", Integer.class)).isZero();
    }
}
