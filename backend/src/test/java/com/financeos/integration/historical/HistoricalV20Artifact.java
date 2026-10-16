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
public record HistoricalV20Artifact(Path jar, String sha256) {
    public static final String COMMIT = "138bccc2bcb2d5bfea17ea88ecc115604928a5be";
    private static final String BACKEND_TREE = "f182075fc6231580d8b06694b6143f6b07426bf3";

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
            identity.store(out, "Fixed V20 source and locally built artifact identity; hash is build-specific");
        }
        System.out.println("V20_IDENTITY commit=" + COMMIT + " jarSha256=" + digest);
    }

    public static HistoricalV20Artifact required() throws Exception {
        String configured = System.getProperty("c9.legacy.jar");
        require(configured != null && !configured.isBlank(),
                "Missing c9.legacy.jar: prepare the fixed historical V20 JAR and its adjacent identity before running backend tests");
        Path configuredJar = Path.of(configured).toRealPath();
        Properties identity = new Properties();
        try (var in = Files.newInputStream(configuredJar.resolveSibling(configuredJar.getFileName() + ".identity.properties"))) { identity.load(in); }
        require(COMMIT.equals(identity.getProperty("sourceCommit")), "Historical source commit mismatch");
        require(BACKEND_TREE.equals(identity.getProperty("backendTree")), "Historical backend tree mismatch");
        Path jar = Path.of(identity.getProperty("jar")).toRealPath();
        require(jar.equals(configuredJar), "Configured historical JAR differs from registered path");
        String digest = verify(Path.of(identity.getProperty("repository")),
                Path.of(identity.getProperty("sourceBackend")), jar);
        require(digest.equals(identity.getProperty("jarSha256")), "Historical JAR SHA-256 mismatch");
        System.out.println("V20_IDENTITY_VERIFIED commit=" + COMMIT + " jarSha256=" + digest);
        return new HistoricalV20Artifact(jar, digest);
    }

    private static String verify(Path repository, Path source, Path jar) throws Exception {
        require(!source.toRealPath().startsWith(repository.toRealPath()), "Historical source must be outside the repository");
        require(!jar.toRealPath().startsWith(repository.toRealPath()), "Historical JAR must be outside the repository");
        git(repository, "merge-base", "--is-ancestor", COMMIT, "HEAD");
        require(BACKEND_TREE.equals(git(repository, "rev-parse", COMMIT + ":backend").strip()), "Fixed tree mismatch");
        for (String line : git(repository, "ls-tree", "-r", COMMIT, "backend/src/main/resources/db/migration").lines().toList()) {
            String[] entry = line.split("\\t", 2);
            String historicalBlob = entry[0].split(" ")[2];
            require(historicalBlob.equals(git(repository, "rev-parse", "HEAD:" + entry[1]).strip()),
                    "Historical migration differs from current version: " + entry[1]);
        }
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
            for (String name : java.util.List.of("BOOT-INF/classes/com/financeos/FinanceOsApplication.class",
                    "BOOT-INF/classes/com/financeos/module/importing/controller/TransactionImportPreviewController.class",
                    "BOOT-INF/classes/com/financeos/module/importing/entity/TransactionImportSession.class",
                    "BOOT-INF/classes/com/financeos/module/importing/storage/LocalTemporaryImportFileStorage.class",
                    "org/springframework/boot/loader/launch/JarLauncher.class")) {
                require(archive.getJarEntry(name) != null, "Missing V20 application entry: " + name);
            }
            require(archive.getJarEntry("BOOT-INF/classes/com/financeos/module/importing/storage/ImportStorageProvisioner.class") == null,
                    "V20 must not include V21 managed storage provisioning");
            try (var in = archive.getInputStream(archive.getJarEntry("BOOT-INF/classes/com/financeos/FinanceOsApplication.class"))) {
                byte[] bytes = in.readNBytes(8);
                require(bytes.length == 8 && bytes[6] == 0 && Byte.toUnsignedInt(bytes[7]) == 65, "V20 must be built for Java 21");
            }
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
            require(migrations.equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList())),
                    "Historical JAR must contain exactly V1-V20 migrations");
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
