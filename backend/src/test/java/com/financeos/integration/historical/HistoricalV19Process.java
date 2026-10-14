package com.financeos.integration.historical;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Owns one historical JVM; its classpath contains only the copied controller and historical JAR. */
public final class HistoricalV19Process implements AutoCloseable {
    private final Process process;
    private final HistoricalV19Artifact artifact;
    private final Path log;
    private final Path evidence;
    private int port;

    private HistoricalV19Process(HistoricalV19Artifact artifact, Path evidence, Map<String, String> environment) throws Exception {
        this.artifact = artifact;
        this.evidence = evidence;
        this.log = evidence.resolve("historical-runtime.log");
        Path controlClasses = Files.createDirectory(evidence.resolve("controller-classes"));
        String resource = HistoricalV19BootLauncher.class.getName().replace('.', '/') + ".class";
        Path controller = controlClasses.resolve(resource);
        Files.createDirectories(controller.getParent());
        try (var in = HistoricalV19BootLauncher.class.getResourceAsStream("/" + resource)) {
            if (in == null) throw new IOException("Missing historical test controller bytecode");
            Files.copy(in, controller);
        }
        Path temp = Files.createDirectory(evidence.resolve("historical-temp"));
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(java, "-Djava.io.tmpdir=" + temp,
                "-cp", controlClasses + File.pathSeparator + artifact.jar(), HistoricalV19BootLauncher.class.getName(),
                evidence.resolve("historical-main-returned").toString(), artifact.jar().toString(),
                "--spring.profiles.active=prod", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.hikari.minimum-idle=0", "--spring.datasource.hikari.maximum-pool-size=2",
                "--finance.import.cleanup.enabled=true", "--market-data.enabled=false", "--fx-data.enabled=false",
                "--finance-ai.enabled=false");
        builder.directory(evidence.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        this.process = builder.start();
        Files.writeString(evidence.resolve("historical-pid.txt"), Long.toString(process.pid()));
    }

    public static HistoricalV19Process start(HistoricalV19Artifact artifact, Path evidence,
                                             Map<String, String> environment) throws Exception {
        HistoricalV19Process runtime = new HistoricalV19Process(artifact, evidence, environment);
        try {
            runtime.awaitReady();
            return runtime;
        } catch (Exception | AssertionError failure) {
            try { runtime.close(); } catch (Exception | AssertionError cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public int port() { return port; }

    private void awaitReady() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try (http) {
            while (System.nanoTime() < deadline) {
                assertThat(process.isAlive()).as("Historical JVM exited before readiness; see %s", log).isTrue();
                String output = Files.readString(log);
                var matcher = Pattern.compile("Tomcat started on port (\\d+)").matcher(output);
                if (matcher.find() && Files.exists(evidence.resolve("historical-main-returned"))) {
                    port = Integer.parseInt(matcher.group(1));
                    var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/actuator/health/readiness")).timeout(Duration.ofSeconds(3)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).contains("\"status\":\"UP\"");
                    Files.writeString(evidence.resolve("historical-readiness.json"), response.body());
                    System.out.println("V19_READY pid=" + process.pid() + " port=" + port);
                    return;
                }
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Historical JVM readiness timed out; see " + log);
    }

    @Override
    public void close() throws Exception {
        List<ProcessHandle> children = process.descendants().toList();
        boolean forced = false;
        if (process.isAlive()) {
            try {
                process.getOutputStream().write("STOP\n".getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
            } finally {
                if (!process.waitFor(45, TimeUnit.SECONDS)) {
                    // Cleanup only: this makes the test fail and cannot satisfy the offline migration gate.
                    forced = true;
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            }
        }
        boolean pidAbsent = ProcessHandle.of(process.pid()).map(handle -> !handle.isAlive()).orElse(true);
        Files.writeString(evidence.resolve("historical-exit.txt"), "pid=" + process.pid() + "\nforced=" + forced
                + "\npidAbsent=" + pidAbsent + "\nexit=" + (process.isAlive() ? "alive" : process.exitValue())
                + "\nremainingChildren=" + children.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList() + "\n");
        assertThat(forced).as("Historical JVM must exit normally").isFalse();
        assertThat(pidAbsent).isTrue();
        assertThat(children).allMatch(handle -> !handle.isAlive());
        assertThat(process.exitValue()).isZero();
        assertThat(Files.readString(log)).contains("V19_NORMAL_EXIT_REQUESTED", "Graceful shutdown complete", "Shutdown completed");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact.jar())));
        assertThat(digest).isEqualTo(artifact.sha256());
        System.out.println("V19_EXIT pid=" + process.pid() + " exit=0 pidAbsent=true forced=false");
    }
}
