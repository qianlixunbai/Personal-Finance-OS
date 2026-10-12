package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Real historical JAR -> V20 payloads -> V21 -> current production JAR.
 * Requires c9.legacy.jar and c9.legacy.evidence; neither application uses the test profile.
 * Offline migration copies only exact DB-referenced payloads into newly provisioned roots.
 */
@Timeout(150)
class TransactionImportLegacyUpgradePostgresIntegrationTest {
    private static PostgreSQLContainer<?> database;
    private static Process nativePostgres;
    private static String databaseUrl, databaseUser, databasePassword;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final UUID deployment = UUID.randomUUID(), rawId = UUID.randomUUID(), planId = UUID.randomUUID();
    // Generated exclusively for these child applications, shared across the upgrade without rotation.
    private final String jwtSecret = UUID.randomUUID() + "-" + UUID.randomUUID();
    private final String migrationSecret = UUID.randomUUID() + "-" + UUID.randomUUID();
    private final String confirmSecret = UUID.randomUUID() + "-" + UUID.randomUUID();
    private Path evidence, legacyTemp, legacyRaw, legacyPlan;
    private JdbcTemplate db;
    private String bearer;
    private JsonNode mapping;
    private final List<Process> children = new ArrayList<>();

    @BeforeAll static void startOwnedDatabase() throws Exception {
        String binaries = System.getProperty("c9.postgres.bin");
        if (binaries == null) {
            database = new PostgreSQLContainer<>("postgres:17-alpine");
            database.start();
            databaseUrl = database.getJdbcUrl(); databaseUser = database.getUsername(); databasePassword = database.getPassword();
            return;
        }
        // Native fallback starts its own fresh cluster; never connect to an existing local database.
        Path owned = Path.of(required("c9.legacy.evidence")).toAbsolutePath();
        Files.createDirectories(owned);
        Path cluster = Files.createTempDirectory(owned, "owned-postgres-");
        Path data = cluster.resolve("data");
        Process init = new ProcessBuilder(Path.of(binaries, "initdb").toString(), "-D", data.toString(),
                "-U", "c9_upgrade", "--auth=trust", "--encoding=UTF8", "--no-locale")
                .redirectErrorStream(true).redirectOutput(cluster.resolve("initdb.log").toFile()).start();
        try {
            assertThat(init.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(init.exitValue()).isZero();
        } finally { stop(init); }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        nativePostgres = new ProcessBuilder(Path.of(binaries, "postgres").toString(), "-D", data.toString(),
                "-h", "127.0.0.1", "-p", String.valueOf(port))
                .redirectErrorStream(true).redirectOutput(cluster.resolve("postgres.log").toFile()).start();
        databaseUser = "c9_upgrade"; databasePassword = "";
        String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        boolean ready = false;
        while (nativePostgres.isAlive() && System.nanoTime() < deadline) {
            try {
                new JdbcTemplate(new DriverManagerDataSource(adminUrl, databaseUser, databasePassword))
                        .execute("CREATE DATABASE c9_legacy_upgrade");
                ready = true; break;
            } catch (org.springframework.dao.DataAccessException ignored) { Thread.sleep(100); }
        }
        assertThat(ready).as("Owned native PostgreSQL cluster readiness").isTrue();
        databaseUrl = "jdbc:postgresql://127.0.0.1:" + port + "/c9_legacy_upgrade";
        String version = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword))
                .queryForObject("SHOW server_version", String.class);
        Files.writeString(cluster.resolve("identity.json"), new ObjectMapper().writeValueAsString(Map.of(
                "serverVersion", version, "dataDirectory", data.toString(), "loopbackPort", port, "ownedOnly", true)));
    }

    @AfterAll static void stopOwnedDatabase() throws Exception {
        if (database != null) database.stop();
        if (nativePostgres != null) stop(nativePostgres);
    }

    @BeforeEach void isolatedV20DatabaseAndStorage(TestInfo info) throws Exception {
        Path owned = Path.of(required("c9.legacy.evidence")).toAbsolutePath();
        Files.createDirectories(owned);
        evidence = Files.createTempDirectory(owned, info.getTestMethod().orElseThrow().getName() + "-");
        legacyTemp = Files.createDirectory(evidence.resolve("old-jvm-temp"));
        legacyRaw = legacyTemp.resolve("finance-os/transaction-import-preview");
        legacyPlan = legacyTemp.resolve("finance-os/transaction-import-preview-plan");
        db = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
        flyway("20").clean();
        flyway("20").migrate();
    }

    @AfterEach void stopOnlyOwnedProcesses() throws Exception {
        for (Process child : children) stop(child);
        // Keep DB snapshots, exact payload copies, process logs, and evidence directories.
    }

    @Test void officialProvisioningRefusesRealNonemptyV20RootsWithoutMutatingLegacyEvidence() throws Exception {
        Legacy sessions = createRealLegacySessions();
        Map<String, String> before = databaseSnapshot();
        Map<String, String> payloads = preserveReferencedPayloads("v20-payloads");
        assertThat(flyway("21").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(databaseSnapshot()).isEqualTo(before);
        assertThat(registryCount()).isZero();

        Process provision = provision(legacyRaw, legacyPlan, "in-place-provision.log");
        assertThat(provision.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(provision.exitValue()).isEqualTo(2);
        assertThat(Files.readString(evidence.resolve("in-place-provision.log")))
                .contains("Import storage provisioning refused");
        assertThat(Files.exists(legacyRaw.resolve(".import-binding"))).isFalse();
        assertThat(Files.exists(legacyPlan.resolve(".import-control"))).isFalse();

        Running app = startCurrent(legacyRaw, legacyPlan, "unbound-current.log");
        assertThat(app.process().waitFor(45, TimeUnit.SECONDS)).isTrue();
        assertThat(app.process().exitValue()).isNotZero();
        assertThat(Files.readString(evidence.resolve("unbound-current.log")))
                .contains("ImportStorageBindingException");
        assertThat(databaseSnapshot()).isEqualTo(before);
        assertThat(referencedPayloadDigests()).isEqualTo(payloads);
        assertThat(registryCount()).isZero();
        assertThat(bindingCount()).isZero();
        assertThat(status(sessions.mapping())).isEqualTo("MAPPING_REQUIRED");
        assertThat(status(sessions.ready())).isEqualTo("PREVIEW_READY");
        writeEvidence("in-place-result.json", Map.of("provisionExit", 2,
                "currentStartupExit", app.process().exitValue(), "registryRows", registryCount(),
                "payloadDigests", payloads, "databaseUnchanged", true));
    }

    @Test void genuineV20ActiveSessionsSurviveExplicitOfflineMigrationMappingConfirmReceiptAndReplay() throws Exception {
        Legacy sessions = createRealLegacySessions();
        Map<String, String> before = databaseSnapshot();
        Map<String, String> payloads = preserveReferencedPayloads("v20-payloads");
        assertThat(flyway("21").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(databaseSnapshot()).isEqualTo(before);
        assertThat(registryCount()).isZero();

        // Explicit operator migration under Option A: empty-root CLI first, exact payloads only.
        // No binding/control cloning, reference rewriting, Plan rebuilding, or Registry SQL.
        Path raw = evidence.resolve("managed-raw"), plans = evidence.resolve("managed-plan");
        Process provision = provision(raw, plans, "empty-provision.log");
        assertThat(provision.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(provision.exitValue()).isZero();
        migrateExactPayloads(raw, plans);
        Running app = startCurrent(raw, plans, "empty-current.log");
        awaitReady(app);
        assertThat(appliedVersion()).isEqualTo(21);
        assertThat(bindingCount()).isEqualTo(2);
        assertThat(databaseSnapshot()).isEqualTo(before);
        assertLiveRegistry(raw, plans);
        assertThat(registryCount()).isEqualTo(3);

        int missingMappingRows = request(app, "GET", path(sessions.mapping(), "rows"), null, null).statusCode();
        assertThat(missingMappingRows).isEqualTo(409); // Mapping is required by the existing contract.
        HttpResponse<String> rows = request(app, "GET", path(sessions.ready(), "rows"), null, null);
        assertThat(rows.statusCode()).isEqualTo(200);
        assertThat(json.readTree(rows.body()).get("data")).isEqualTo(sessions.ready().get("rows"));
        assertThat(referencedPayloadDigests()).isEqualTo(payloads);
        assertThat(databaseSnapshot()).isEqualTo(before);

        HttpResponse<String> remapped = multipart(app, "PUT", path(sessions.mapping(), "preview"), "mapping", mapping, null);
        assertThat(remapped.statusCode()).isEqualTo(200);
        JsonNode mapped = json.readTree(remapped.body()).get("data");
        assertThat(mapped.get("sessionStatus").asText()).isEqualTo("PREVIEW_READY");
        assertThat(mapped.get("revision").asInt()).isEqualTo(1);
        assertThat(mapped.get("confirmable").asBoolean()).isTrue();
        assertThat(json.readTree(request(app, "GET", path(mapped, "rows"), null, null).body()).get("data")).isEqualTo(mapped.get("rows"));
        assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_file_objects WHERE state='LIVE'", Integer.class)).isEqualTo(4);

        JsonNode confirmBody = json.createObjectNode().put("previewToken", sessions.ready().get("previewToken").asText())
                .set("acknowledgedWarningIds", json.createArrayNode());
        HttpResponse<String> confirmed = request(app, "POST", path(sessions.ready(), "confirm"), confirmBody, "legacy-upgrade-confirm");
        assertThat(confirmed.statusCode()).isEqualTo(200);
        JsonNode result = json.readTree(confirmed.body()).get("data");
        assertThat(result.get("idempotentReplay").asBoolean()).isFalse();
        JsonNode receipt = result.get("receipt");
        assertThat(receipt.get("importBatchId")).isEqualTo(sessions.ready().get("importBatchId"));
        assertThat(receipt.get("fileDigest")).isEqualTo(sessions.ready().get("fileDigest"));
        assertThat(receipt.get("createdCount").asInt()).isEqualTo(1);
        assertThat(receipt.get("transactions")).hasSize(1);
        assertThat(receipt.get("accountImpacts").get(0).get("balanceBefore").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(receipt.get("accountImpacts").get(0).get("delta").decimalValue()).isEqualByComparingTo("-12.34");
        assertThat(receipt.get("accountImpacts").get(0).get("balanceAfter").decimalValue()).isEqualByComparingTo("87.66");
        assertThat(db.queryForObject("SELECT balance FROM accounts WHERE id=1", java.math.BigDecimal.class)).isEqualByComparingTo("87.66");
        assertThat(db.queryForObject("SELECT amount FROM transactions", java.math.BigDecimal.class)).isEqualByComparingTo("12.34");
        assertThat(db.queryForObject("SELECT type FROM transactions", String.class)).isEqualTo("EXPENSE");
        assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_batches", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT created_transaction_id FROM transaction_import_items", Long.class))
                .isEqualTo(receipt.get("transactions").get(0).get("transactionId").asLong());
        assertThat(db.queryForObject("SELECT result_digest FROM transaction_import_batches", String.class))
                .isEqualTo(receipt.get("resultDigest").asText());
        assertThat(status(sessions.ready())).isEqualTo("CONSUMED");
        assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_file_objects WHERE state='DELETED'", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_sessions WHERE status='CONSUMED' AND temporary_storage_reference IS NULL AND plan_storage_reference IS NULL", Integer.class)).isEqualTo(1);
        assertThat(status(sessions.mapping())).isEqualTo("PREVIEW_READY");
        for (var row : db.queryForList("SELECT reference,kind,state FROM transaction_import_file_objects")) {
            boolean present = Files.exists((row.get("kind").equals("RAW") ? raw : plans).resolve(row.get("reference").toString()));
            assertThat(present).isEqualTo(row.get("state").equals("LIVE"));
        }

        Map<String, String> committed = databaseSnapshot();
        HttpResponse<String> replay = request(app, "POST", path(sessions.ready(), "confirm"), confirmBody, "legacy-upgrade-confirm");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).at("/data/idempotentReplay").asBoolean()).isTrue();
        assertThat(json.readTree(replay.body()).at("/data/receipt")).isEqualTo(receipt);
        assertThat(json.readTree(request(app, "GET", "/api/v1/imports/transactions/batches/" + receipt.get("importBatchId").asText(), null, null).body()).get("data"))
                .isEqualTo(receipt);
        assertThat(databaseSnapshot()).isEqualTo(committed);
        stop(app.process());
        Running restarted = startCurrent(raw, plans, "confirmed-restart.log");
        awaitReady(restarted);
        HttpResponse<String> restartReplay = request(restarted, "POST", path(sessions.ready(), "confirm"), confirmBody, "legacy-upgrade-confirm");
        assertThat(restartReplay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(restartReplay.body()).at("/data/idempotentReplay").asBoolean()).isTrue();
        assertThat(json.readTree(restartReplay.body()).at("/data/receipt")).isEqualTo(receipt);
        assertThat(databaseSnapshot()).isEqualTo(committed);
        assertLegacySourcesUnchanged(payloads);
        writeEvidence("successful-upgrade-result.json", Map.of("receipt", receipt, "databaseCommitted", committed,
                "registry", db.queryForList("SELECT reference,kind,state,write_complete,deleted_at FROM transaction_import_file_objects ORDER BY reference"),
                "legacyPayloadDigests", payloads, "replayUnchanged", true));
    }

    @Test void migratedLegacyCancelAndExpiryKeepDurableRetryUntilTheActualGuardAllowsCleanup() throws Exception {
        Legacy sessions = createRealLegacySessions();
        Map<String, String> payloads = preserveReferencedPayloads("v20-payloads");
        assertThat(flyway("21").migrate().migrationsExecuted).isEqualTo(1);
        Path raw = evidence.resolve("managed-raw"), plans = evidence.resolve("managed-plan");
        Process provision = provision(raw, plans, "empty-provision.log");
        assertThat(provision.waitFor(15, TimeUnit.SECONDS)).isTrue();
        assertThat(provision.exitValue()).isZero();
        migrateExactPayloads(raw, plans);
        Running app = startCurrent(raw, plans, "migrated-current.log");
        awaitReady(app);
        assertLiveRegistry(raw, plans);
        // A real independent OS lock forces production unlink to defer; no delete spy or mock.
        try (var control = FileChannel.open(raw.resolve(".import-control"), StandardOpenOption.WRITE);
             var guard = control.lock(64, 1, false)) {
            assertThat(request(app, "DELETE", path(sessions.mapping(), ""), null, null).statusCode()).isEqualTo(503);
            assertThat(status(sessions.mapping())).isEqualTo("CANCELLED");
            db.update("UPDATE transaction_import_sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                    UUID.fromString(sessions.ready().get("importSessionId").asText()));
            assertThat(request(app, "GET", path(sessions.ready(), "rows"), null, null).statusCode()).isEqualTo(503);
            assertThat(status(sessions.ready())).isEqualTo("EXPIRED");
            assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_file_objects WHERE state='RETIRED'", Integer.class)).isEqualTo(3);
            assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_file_objects WHERE state='DELETED'", Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_sessions WHERE temporary_storage_reference IS NOT NULL", Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_sessions WHERE plan_storage_reference IS NOT NULL", Integer.class)).isEqualTo(1);
            for (String key : payloads.keySet()) {
                String[] fields = key.split("/");
                assertThat((fields[0].equals("RAW") ? raw : plans).resolve(fields[1])).isRegularFile();
            }
            writeEvidence("terminal-retry-before-release.json", Map.of("database", databaseSnapshot(),
                    "registry", db.queryForList("SELECT reference,kind,state,attempt_count,next_attempt_at FROM transaction_import_file_objects ORDER BY reference")));
        }
        assertThat(referencedPayloadDigests()).isEqualTo(payloads);
        stop(app.process());
        Running restarted = startCurrent(raw, plans, "terminal-restart.log");
        awaitReady(restarted);
        assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_file_objects WHERE state='DELETED' AND deleted_at IS NOT NULL", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_sessions WHERE temporary_storage_reference IS NOT NULL OR plan_storage_reference IS NOT NULL", Integer.class)).isZero();
        for (String key : payloads.keySet()) {
            String[] fields = key.split("/");
            assertThat(Files.exists((fields[0].equals("RAW") ? raw : plans).resolve(fields[1]))).isFalse();
        }
        assertThat(db.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_batches", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT balance FROM accounts WHERE id=1", java.math.BigDecimal.class)).isEqualByComparingTo("100.00");
        assertLegacySourcesUnchanged(payloads);
        writeEvidence("terminal-cleanup-result.json", Map.of("database", databaseSnapshot(),
                "registry", db.queryForList("SELECT reference,kind,state,attempt_count,deleted_at FROM transaction_import_file_objects ORDER BY reference")));
    }

    private void migrateExactPayloads(Path raw, Path plans) throws Exception {
        Map<Path, String> controls = new HashMap<>();
        for (Path root : List.of(raw, plans))
            for (String name : List.of(".import-binding", ".import-control"))
                controls.put(root.resolve(name), Files.readString(root.resolve(name)));
        for (var row : db.queryForList("SELECT temporary_storage_reference,plan_storage_reference,file_digest,file_size FROM transaction_import_sessions")) {
            for (String kind : List.of("RAW", "PLAN")) {
                String ref = (String) row.get(kind.equals("RAW") ? "temporary_storage_reference" : "plan_storage_reference");
                if (ref == null) continue;
                assertThat(UUID.fromString(ref).toString()).isEqualTo(ref);
                Path source = (kind.equals("RAW") ? legacyRaw : legacyPlan).resolve(ref);
                Path destination = (kind.equals("RAW") ? raw : plans).resolve(ref);
                assertThat(source).isRegularFile();
                assertThat(Files.isSymbolicLink(source)).isFalse();
                Files.copy(source, destination); // No overwrite, binding clone, directory scan, or source deletion.
                assertThat(Files.readAllBytes(destination)).isEqualTo(Files.readAllBytes(source));
                if (kind.equals("RAW")) {
                    assertThat(Files.size(destination)).isEqualTo(((Number) row.get("file_size")).longValue());
                    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(destination))))
                            .isEqualTo(row.get("file_digest"));
                }
            }
        }
        for (var entry : controls.entrySet()) assertThat(Files.readString(entry.getKey())).isEqualTo(entry.getValue());
    }

    private void assertLegacySourcesUnchanged(Map<String, String> payloads) throws Exception {
        for (var entry : payloads.entrySet()) {
            String[] fields = entry.getKey().split("/");
            Path source = (fields[0].equals("RAW") ? legacyRaw : legacyPlan).resolve(fields[1]);
            assertThat(source).isRegularFile();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))))
                    .isEqualTo(entry.getValue());
        }
    }

    private void assertLiveRegistry(Path raw, Path plans) throws Exception {
        assertThat(registryCount()).isEqualTo(3);
        List<String> expected = db.queryForList("""
                SELECT temporary_storage_reference FROM transaction_import_sessions WHERE temporary_storage_reference IS NOT NULL
                UNION ALL SELECT plan_storage_reference FROM transaction_import_sessions WHERE plan_storage_reference IS NOT NULL
                """, String.class);
        assertThat(db.queryForList("SELECT reference FROM transaction_import_file_objects", String.class)).containsExactlyInAnyOrderElementsOf(expected);
        for (var row : db.queryForList("""
                SELECT f.*,s.user_id FROM transaction_import_file_objects f
                JOIN transaction_import_sessions s ON s.id=f.session_id ORDER BY reference
                """)) {
            assertThat(row.get("state")).isEqualTo("LIVE");
            assertThat(row.get("write_complete")).isEqualTo(true);
            assertThat(row.get("owner_user_id")).isEqualTo(row.get("user_id"));
            assertThat(row.get("intended_session_id")).isEqualTo(row.get("session_id"));
            assertThat(row.get("deleted_at")).isNull();
            boolean original = row.get("kind").equals("RAW");
            assertThat(row.get("storage_id")).isEqualTo(original ? rawId : planId);
            assertThat((original ? raw : plans).resolve(row.get("reference").toString())).isRegularFile();
        }
    }

    private Legacy createRealLegacySessions() throws Exception {
        Path jar = Path.of(required("c9.legacy.jar")).toAbsolutePath();
        assertThat(jar).isRegularFile();
        Running legacy = start(jar, legacyTemp, null, null, "legacy-v20.log");
        awaitReady(legacy);
        assertThat(appliedVersion()).isEqualTo(20);
        assertThat(db.queryForObject("SELECT to_regclass('transaction_import_file_objects')::text", String.class)).isNull();
        assertThat(request(legacy, "POST", "/api/v1/register", json.createObjectNode()
                .put("username", "c9-legacy").put("email", "c9-legacy@example.test").put("password", "password123"), null).statusCode()).isEqualTo(200);
        HttpResponse<String> login = request(legacy, "POST", "/api/v1/login", json.createObjectNode()
                .put("username", "c9-legacy").put("password", "password123"), null);
        assertThat(login.statusCode()).isEqualTo(200);
        bearer = json.readTree(login.body()).at("/data/token").asText();
        assertThat(bearer).isNotBlank();
        db.update("INSERT INTO accounts(user_id,name,type,currency,balance) VALUES(1,'Cash','CASH','CNY',100)");
        Long categoryId = db.queryForObject("INSERT INTO categories(user_id,name,type) VALUES(1,'Food','EXPENSE') RETURNING id", Long.class);
        mapping = json.readTree("""
                {"columnMappings":{"date":"date","type":"type","amount":"amount","account":"account","category":"category"},
                 "typeMappings":{"expense":"EXPENSE"},"accountMappings":{"Cash":1},"categoryMappings":{"Food":1}}
                """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) mapping.get("categoryMappings")).put("Food", categoryId);
        JsonNode mappingSession = upload(legacy, json.createObjectNode().put("format", "CSV"), "mapping");
        JsonNode ready = upload(legacy, json.createObjectNode().put("format", "CSV").set("mapping", mapping), "ready");
        writeEvidence("v20-preview-validation.json", ((com.fasterxml.jackson.databind.node.ObjectNode) ready.deepCopy()).remove(List.of("previewToken")));
        assertThat(mappingSession.get("sessionStatus").asText()).isEqualTo("MAPPING_REQUIRED");
        assertThat(ready.get("sessionStatus").asText()).isEqualTo("PREVIEW_READY");
        assertThat(ready.get("confirmable").asBoolean()).isTrue();
        assertThat(ready.get("previewToken").asText()).isNotBlank();
        assertThat(request(legacy, "GET", path(ready, "rows"), null, null).statusCode()).isEqualTo(200);
        stop(legacy.process()); // No old writer or cleanup process remains before V21.
        writeEvidence("v20-database.json", databaseSnapshot());
        // Tokens remain in memory only. Persist business fields, without the signed tokens.
        var identity = json.createObjectNode().put("flyway", 20).put("legacyStopped", true);
        identity.set("mapping", ((com.fasterxml.jackson.databind.node.ObjectNode) mappingSession.deepCopy()).remove(List.of("previewToken")));
        identity.set("ready", ((com.fasterxml.jackson.databind.node.ObjectNode) ready.deepCopy()).remove(List.of("previewToken")));
        writeEvidence("v20-session-identity.json", identity);
        return new Legacy(mappingSession, ready);
    }

    private JsonNode upload(Running app, JsonNode options, String suffix) throws Exception {
        HttpResponse<String> response = multipart(app, "POST", "/api/v1/imports/transactions/preview", "request", options,
                "date,type,amount,account,category\n2026-08-14,expense,12.34,Cash,Food\n");
        assertThat(response.statusCode()).as("V20 production upload " + suffix).isEqualTo(200);
        return json.readTree(response.body()).get("data");
    }

    private Process provision(Path raw, Path plans, String log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(java(), "-Dloader.main=com.financeos.module.importing.storage.ImportStorageProvisioner",
                "-cp", currentJar().toString(), "org.springframework.boot.loader.launch.PropertiesLauncher", "--provision");
        storageEnvironment(builder, raw, plans);
        return child(builder, log);
    }

    private Running startCurrent(Path raw, Path plans, String log) throws Exception {
        Path tmp = Files.createTempDirectory(evidence, "current-jvm-temp-");
        return start(currentJar(), tmp, raw, plans, log);
    }

    private Running start(Path jar, Path tmp, Path raw, Path plans, String log) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        ProcessBuilder builder = new ProcessBuilder(java(), "-Djava.io.tmpdir=" + tmp, "-jar", jar.toString(),
                "--server.address=127.0.0.1", "--server.port=" + port, "--spring.profiles.active=prod",
                "--finance.import.cleanup.enabled=false", "--finance.import.confirm.token-secret=" + confirmSecret);
        builder.environment().put("DB_URL", databaseUrl);
        builder.environment().put("DB_USERNAME", databaseUser);
        builder.environment().put("DB_PASSWORD", databasePassword);
        builder.environment().put("JWT_SECRET", jwtSecret);
        builder.environment().put("MIGRATION_PREVIEW_SECRET", migrationSecret);
        if (raw != null) storageEnvironment(builder, raw, plans);
        return new Running(child(builder, log), "http://127.0.0.1:" + port);
    }

    private void storageEnvironment(ProcessBuilder builder, Path raw, Path plans) {
        builder.environment().put("FINANCE_IMPORT_STORAGE_RAW_ROOT", raw.toString());
        builder.environment().put("FINANCE_IMPORT_STORAGE_PLAN_ROOT", plans.toString());
        builder.environment().put("FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID", deployment.toString());
        builder.environment().put("FINANCE_IMPORT_STORAGE_RAW_ID", rawId.toString());
        builder.environment().put("FINANCE_IMPORT_STORAGE_PLAN_ID", planId.toString());
    }

    private Process child(ProcessBuilder builder, String log) throws Exception {
        builder.redirectErrorStream(true).redirectOutput(evidence.resolve(log).toFile());
        Process process = builder.start(); children.add(process); return process;
    }

    private void awaitReady(Running app) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (app.process().isAlive() && System.nanoTime() < deadline) {
            try {
                if (request(app, "GET", "/actuator/health", null, null).statusCode() == 200) {
                    // Health can precede ApplicationReadyEvent; wait for its production enrollment pass.
                    Thread.sleep(500); return;
                }
            } catch (java.io.IOException ignored) { }
            Thread.sleep(100);
        }
        throw new AssertionError("Owned child application did not become ready; inspect preserved evidence logs");
    }

    private HttpResponse<String> request(Running app, String method, String path, JsonNode body, String key) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(app.base() + path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> multipart(Running app, String method, String path, String part, JsonNode value, String csv) throws Exception {
        String boundary = "c9" + UUID.randomUUID();
        StringBuilder body = new StringBuilder();
        if (csv != null) body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.csv\"\r\nContent-Type: text/csv\r\n\r\n").append(csv).append("\r\n");
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(part)
                .append("\"\r\nContent-Type: application/json\r\n\r\n").append(json.writeValueAsString(value)).append("\r\n--").append(boundary).append("--\r\n");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(app.base() + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> databaseSnapshot() {
        Map<String, String> result = new TreeMap<>();
        for (String table : List.of("users", "accounts", "categories", "transactions", "transaction_import_sessions",
                "transaction_import_batches", "transaction_import_items", "transaction_import_batch_account_impacts"))
            result.put(table, db.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]') FROM " + table + " t", String.class));
        return result;
    }

    private Map<String, String> preserveReferencedPayloads(String name) throws Exception {
        Path copies = Files.createDirectory(evidence.resolve(name));
        Map<String, String> result = referencedPayloadDigests();
        for (String key : result.keySet()) {
            String[] fields = key.split("/");
            Files.copy((fields[0].equals("RAW") ? legacyRaw : legacyPlan).resolve(fields[1]), copies.resolve(fields[0] + "-" + fields[1]));
        }
        writeEvidence("v20-payload-digests.json", result); return result;
    }

    private Map<String, String> referencedPayloadDigests() throws Exception {
        Map<String, String> result = new TreeMap<>();
        for (var row : db.queryForList("SELECT temporary_storage_reference,plan_storage_reference FROM transaction_import_sessions")) {
            for (String kind : List.of("RAW", "PLAN")) {
                String ref = (String) row.get(kind.equals("RAW") ? "temporary_storage_reference" : "plan_storage_reference");
                if (ref != null) {
                    assertThat(UUID.fromString(ref).toString()).isEqualTo(ref);
                    byte[] bytes = Files.readAllBytes((kind.equals("RAW") ? legacyRaw : legacyPlan).resolve(ref));
                    result.put(kind + "/" + ref, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                }
            }
        }
        assertThat(result).hasSize(3); return result;
    }

    private Flyway flyway(String target) { return Flyway.configure().dataSource(databaseUrl, databaseUser, databasePassword)
            .locations("classpath:db/migration").target(target).cleanDisabled(false).load(); }
    private int registryCount() { return db.queryForObject("SELECT count(*) FROM transaction_import_file_objects", Integer.class); }
    private int bindingCount() { return db.queryForObject("SELECT count(*) FROM transaction_import_storage_bindings", Integer.class); }
    private int appliedVersion() { return db.queryForObject("SELECT MAX(version::INTEGER) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class); }
    private String status(JsonNode session) { return db.queryForObject("SELECT status FROM transaction_import_sessions WHERE id=?", String.class, UUID.fromString(session.get("importSessionId").asText())); }
    private String path(JsonNode session, String action) { return "/api/v1/imports/transactions/" + session.get("importSessionId").asText() + (action.isEmpty() ? "" : "/" + action); }
    private void writeEvidence(String name, Object value) throws Exception { Files.writeString(evidence.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    private static Path currentJar() { return Path.of("target/finance-os-backend-1.0.0.jar").toAbsolutePath(); }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private static String required(String property) { return Objects.requireNonNull(System.getProperty(property), property + " must point to owned acceptance artifacts"); }
    private static void stop(Process process) throws Exception {
        if (process.isAlive()) process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue(); }
    }
    private record Legacy(JsonNode mapping, JsonNode ready) { }
    private record Running(Process process, String base) { }
}
