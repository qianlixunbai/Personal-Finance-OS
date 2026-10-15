package com.financeos.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/** Test-only current build gate, also runnable in Java source-file mode before clean test. */
public final class CurrentManagedProcessArtifact {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected repository and externally copied current JAR");
        Path repository = Path.of(args[0]).toRealPath();
        Path jar = Path.of(args[1]).toRealPath();
        require(!jar.startsWith(repository.resolve("backend/target")), "Current JAR must survive Maven clean");
        String head = verifySources(repository);
        String sha = verifyJar(repository, jar);
        Properties identity = new Properties();
        identity.setProperty("head", head);
        identity.setProperty("sha256", sha);
        try (var out = Files.newOutputStream(identityPath(jar), StandardOpenOption.CREATE_NEW)) {
            identity.store(out, "Current HEAD build identity; keep adjacent to the JAR");
        }
        System.out.println("C9_CURRENT_JAR head=" + head + " sha256=" + sha);
    }

    public static Path required() throws Exception {
        String configured = System.getenv("FINANCE_OS_C9_CURRENT_JAR");
        require(configured != null && !configured.isBlank(),
                "Missing FINANCE_OS_C9_CURRENT_JAR: run mvnw -B -DskipTests clean package, copy the JAR outside target, "
                        + "then run java src/test/java/com/financeos/integration/CurrentManagedProcessArtifact.java <repository> <copied-jar>");
        Path jar = Path.of(configured).toRealPath();
        Path repository = Path.of("..").toRealPath(); // Maven runs this class from backend.
        require(!jar.startsWith(repository.resolve("backend/target")), "Current JAR must be outside target");
        Properties identity = new Properties();
        try (var in = Files.newInputStream(identityPath(jar))) { identity.load(in); }
        String head = verifySources(repository);
        require(head.equals(identity.getProperty("head")), "Current JAR HEAD mismatch");
        String sha = verifyJar(repository, jar);
        require(sha.equals(identity.getProperty("sha256")), "Current JAR SHA-256 mismatch");
        System.out.println("C9_CURRENT_JAR_VERIFIED head=" + head + " sha256=" + sha);
        return jar;
    }

    private static Path identityPath(Path jar) { return jar.resolveSibling(jar.getFileName() + ".identity.properties"); }

    private static String verifySources(Path repository) throws Exception {
        String head = git(repository, "rev-parse", "HEAD").strip();
        var expected = new TreeSet<String>();
        // Deliberately inspect only production inputs, never protected user task files.
        for (String entry : git(repository, "ls-tree", "-r", "HEAD", "backend/src/main", "backend/pom.xml").lines().toList()) {
            String[] fields = entry.split("\t", 2);
            String[] metadata = fields[0].split(" ");
            require(metadata[1].equals("blob"), "Unexpected current source entry");
            Path file = repository.resolve(fields[1]);
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Missing current production input: " + fields[1]);
            byte[] bytes = Files.readAllBytes(file);
            if (!blob(bytes).equals(metadata[2])) {
                require(blob(new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")
                        .getBytes(StandardCharsets.UTF_8)).equals(metadata[2]), "Production input differs from HEAD: " + fields[1]);
            }
            expected.add(fields[1]);
        }
        var actual = new TreeSet<String>();
        actual.add("backend/pom.xml");
        try (var files = Files.walk(repository.resolve("backend/src/main"))) {
            files.filter(path -> !Files.isDirectory(path)).forEach(path -> actual.add(repository.relativize(path).toString().replace('\\', '/')));
        }
        require(!expected.isEmpty() && actual.equals(expected), "Current production source inventory mismatch");
        return head;
    }

    private static String verifyJar(Path repository, Path jar) throws Exception {
        require(Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS), "Current JAR must be a regular file");
        Path classes = repository.resolve("backend/target/classes");
        try (JarFile archive = new JarFile(jar.toFile())) {
            var manifest = archive.getManifest().getMainAttributes();
            require("org.springframework.boot.loader.launch.JarLauncher".equals(manifest.getValue("Main-Class")), "Wrong current Boot launcher");
            require("com.financeos.FinanceOsApplication".equals(manifest.getValue("Start-Class")), "Wrong current application");
            for (String name : List.of("BOOT-INF/classes/com/financeos/FinanceOsApplication.class",
                    "BOOT-INF/classes/com/financeos/module/importing/storage/ImportStorageProvisioner.class",
                    "org/springframework/boot/loader/launch/JarLauncher.class",
                    "org/springframework/boot/loader/launch/PropertiesLauncher.class")) {
                require(archive.getJarEntry(name) != null, "Missing current JAR entry: " + name);
            }
            var packaged = new TreeSet<String>();
            var migrations = new TreeSet<Integer>();
            for (var entry : archive.stream().filter(item -> !item.isDirectory()).toList()) {
                if (!entry.getName().startsWith("BOOT-INF/classes/")) continue;
                String relative = entry.getName().substring("BOOT-INF/classes/".length());
                require(packaged.add(relative), "Duplicate current class/resource: " + relative);
                try (var in = archive.getInputStream(entry)) {
                    require(Files.isRegularFile(classes.resolve(relative))
                                    && MessageDigest.isEqual(in.readAllBytes(), Files.readAllBytes(classes.resolve(relative))),
                            "Current packaged class/resource differs from fresh build: " + relative);
                }
                if (relative.startsWith("db/migration/")) {
                    var match = java.util.regex.Pattern.compile("db/migration/V(\\d+)__.+\\.sql").matcher(relative);
                    require(match.matches() && migrations.add(Integer.parseInt(match.group(1))), "Invalid current migration entry");
                }
            }
            var built = new TreeSet<String>();
            try (var files = Files.walk(classes)) {
                files.filter(Files::isRegularFile).forEach(path -> built.add(classes.relativize(path).toString().replace('\\', '/')));
            }
            require(!packaged.isEmpty() && packaged.equals(built), "Current compiled inventory mismatch");
            require(migrations.equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1, 21).boxed().toList())),
                    "Current JAR must contain exactly V1-V21 migrations");
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }

    private static String blob(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String git(Path repository, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return process.getInputStream().readAllBytes(); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            require(process.waitFor(5, TimeUnit.SECONDS), "Current identity git process did not exit");
            throw new IllegalStateException("Current identity git timed out");
        }
        String text = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        require(process.exitValue() == 0, "Current identity git failed");
        return text;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
