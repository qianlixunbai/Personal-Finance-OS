package com.financeos.integration.historical;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/** Test-only provenance gate; also runnable with Java source-file mode after the CI build. */
public record HistoricalV19Artifact(Path jar, String sha256) {
    public static final String COMMIT = "7d2d83c23abeaa64f0f5854f729ea919509b551a";
    private static final String BACKEND_TREE = "7a5eb4e71c9f44724317e1814fb18e873ad3bd03";

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected repository, exported backend, JAR, identity output");
        Path repository = Path.of(args[0]).toRealPath();
        Path source = Path.of(args[1]).toRealPath();
        Path jar = Path.of(args[2]).toRealPath();
        String digest = verify(repository, source, jar);
        Properties identity = new Properties();
        identity.setProperty("sourceCommit", COMMIT);
        identity.setProperty("backendTree", BACKEND_TREE);
        identity.setProperty("repository", repository.toString());
        identity.setProperty("sourceBackend", source.toString());
        identity.setProperty("jar", jar.toString());
        identity.setProperty("jarSha256", digest);
        // CREATE_NEW: preserve previous evidence and reject an accidental overwrite.
        try (var out = Files.newOutputStream(Path.of(args[3]), java.nio.file.StandardOpenOption.CREATE_NEW)) {
            identity.store(out, "Fixed V19 source and locally built artifact identity; hash is build-specific");
        }
        System.out.println("V19_IDENTITY commit=" + COMMIT + " jarSha256=" + digest);
    }

    public static HistoricalV19Artifact required() throws Exception {
        String configured = System.getenv("FINANCE_OS_V19_IDENTITY");
        require(configured != null && !configured.isBlank(),
                "Missing FINANCE_OS_V19_IDENTITY: prepare the fixed historical JAR before running backend tests");
        Properties identity = new Properties();
        try (var in = Files.newInputStream(Path.of(configured))) { identity.load(in); }
        require(COMMIT.equals(identity.getProperty("sourceCommit")), "Historical source commit mismatch");
        require(BACKEND_TREE.equals(identity.getProperty("backendTree")), "Historical backend tree mismatch");
        Path jar = Path.of(identity.getProperty("jar")).toRealPath();
        String digest = verify(Path.of(identity.getProperty("repository")),
                Path.of(identity.getProperty("sourceBackend")), jar);
        require(digest.equals(identity.getProperty("jarSha256")), "Historical JAR SHA-256 mismatch");
        System.out.println("V19_IDENTITY_VERIFIED commit=" + COMMIT + " jarSha256=" + digest);
        return new HistoricalV19Artifact(jar, digest);
    }

    private static String verify(Path repository, Path source, Path jar) throws Exception {
        require(BACKEND_TREE.equals(git(repository, "rev-parse", COMMIT + ":backend").strip()), "Fixed tree mismatch");
        var expectedSources = new TreeSet<String>();
        for (String entry : git(repository, "ls-tree", "-r", COMMIT, "backend").lines().toList()) {
            String[] fields = entry.split("\\t", 2);
            String[] metadata = fields[0].split(" ");
            require(metadata[1].equals("blob"), "Unexpected historical source entry");
            String relative = fields[1].substring("backend/".length());
            expectedSources.add(relative);
            Path file = source.resolve(relative);
            require(Files.isRegularFile(file) && !Files.isSymbolicLink(file), "Missing/unsafe historical source: " + relative);
            byte[] bytes = Files.readAllBytes(file);
            // git archive on Windows may export CRLF. Accept only raw or CRLF->LF Git blob identity.
            if (!blob(bytes).equals(metadata[2])) {
                byte[] canonical = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")
                        .getBytes(StandardCharsets.UTF_8);
                require(blob(canonical).equals(metadata[2]), "Historical source mismatch: " + relative);
            }
        }
        require(!expectedSources.isEmpty(), "No historical sources verified");
        var actualSources = new TreeSet<String>();
        try (var paths = Files.walk(source)) {
            paths.filter(path -> !path.startsWith(source.resolve("target")))
                    .filter(path -> !Files.isDirectory(path)).forEach(path ->
                            actualSources.add(source.relativize(path).toString().replace('\\', '/')));
        }
        require(actualSources.equals(expectedSources), "Unexpected files in historical source export");
        try (JarFile archive = new JarFile(jar.toFile())) {
            var attributes = archive.getManifest().getMainAttributes();
            require("org.springframework.boot.loader.launch.JarLauncher".equals(attributes.getValue("Main-Class")), "Wrong Boot loader");
            require("com.financeos.FinanceOsApplication".equals(attributes.getValue("Start-Class")), "Wrong historical main");
            var migrations = new TreeSet<Integer>();
            var packaged = new HashSet<String>();
            for (var entry : archive.stream().filter(item -> !item.isDirectory()).toList()) {
                String name = entry.getName();
                if (!name.startsWith("BOOT-INF/classes/")) continue;
                String relative = name.substring("BOOT-INF/classes/".length());
                require(packaged.add(relative), "Duplicate historical JAR entry: " + relative);
                Path built = source.resolve("target/classes").resolve(relative);
                try (var in = archive.getInputStream(entry)) {
                    require(Files.isRegularFile(built) && MessageDigest.isEqual(in.readAllBytes(), Files.readAllBytes(built)),
                            "Historical packaged class/resource mismatch: " + relative);
                }
                if (relative.startsWith("db/migration/")) {
                    var match = java.util.regex.Pattern.compile("db/migration/V(\\d+)__.+\\.sql").matcher(relative);
                    require(match.matches(), "Unexpected historical migration: " + relative);
                    require(migrations.add(Integer.parseInt(match.group(1))), "Duplicate historical migration version");
                }
            }
            var builtFiles = new HashSet<String>();
            try (var paths = Files.walk(source.resolve("target/classes"))) {
                paths.filter(Files::isRegularFile).forEach(path -> builtFiles.add(
                        source.resolve("target/classes").relativize(path).toString().replace('\\', '/')));
            }
            require(packaged.equals(builtFiles) && !packaged.isEmpty(), "Historical compiled inventory mismatch");
            require(migrations.equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1, 19).boxed().toList())),
                    "Historical JAR must contain exactly V1-V19 migrations");
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }

    private static String blob(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String git(Path repository, String... args) throws Exception {
        var command = new java.util.ArrayList<String>(java.util.List.of("git", "-C", repository.toString()));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var outputBytes = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return process.getInputStream().readAllBytes(); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Historical identity git command timed out");
        }
        String output = new String(outputBytes.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        require(process.exitValue() == 0, "Historical identity git command failed: " + output);
        return output;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
