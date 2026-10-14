package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.FinanceOsApplication;
import com.financeos.integration.historical.HistoricalV19Artifact;
import com.financeos.integration.historical.HistoricalV19Process;
import com.financeos.module.importing.dto.TransactionImportConfirmResponse;
import com.financeos.module.importing.dto.TransactionImportFormat;
import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.importing.dto.TransactionImportReceipt;
import com.financeos.module.importing.service.TransactionImportPreviewCleanupScheduler;
import com.financeos.module.importing.storage.LocalTemporaryImportFileStorage;
import com.financeos.module.importing.storage.TrustedImportStorageRoot;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest {
    private static final String CSV = "date,type,amount,account,category,description\n"
            + "2026-08-14,expense,12.34,Cash,Food,lunch\n";
    private static final String IDEMPOTENCY_KEY = "v19-to-v20-receipt-replay-key";
    private static final List<String> REGISTRY = List.of("transaction_import_storage_bindings", "transaction_import_file_objects");
    private static final List<String> RECEIPT_TABLES = List.of("transaction_import_sessions", "transaction_import_batches",
            "transaction_import_items", "transaction_import_batch_account_impacts");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    // No pool or current application exists during the historical and offline migration phases.
    private final JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword()));

    @Test
    void v19ReceiptSurvivesV20AndV21UpgradeDeleteAndHttpReplayWithoutAnotherFinancialEffect() throws Exception {
        HistoricalV19Artifact artifact = HistoricalV19Artifact.required();
        Path root = Path.of(System.getenv().getOrDefault("FINANCE_OS_B2_EVIDENCE_ROOT", System.getProperty("java.io.tmpdir")));
        Files.createDirectories(root);
        Path evidence = Files.createTempDirectory(root, "b2-2-implementation-");
        System.out.println("B2_2_EVIDENCE=" + evidence);
        String username = "v19v20-" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        String tokenSecret = UUID.randomUUID() + "-" + UUID.randomUUID();
        Map<String, String> environment = Map.of("DB_URL", POSTGRESQL.getJdbcUrl(),
                "DB_USERNAME", POSTGRESQL.getUsername(), "DB_PASSWORD", POSTGRESQL.getPassword(),
                "JWT_SECRET", UUID.randomUUID() + "-" + UUID.randomUUID(),
                "MIGRATION_PREVIEW_SECRET", UUID.randomUUID() + "-" + UUID.randomUUID(),
                "FINANCE_IMPORT_CONFIRM_TOKEN_SECRET", tokenSecret);
        assertThat(db.queryForObject("SELECT current_setting('server_version_num')::integer", Integer.class))
                .isBetween(170000, 179999);
        assertThat(tables()).isEmpty();

        long accountId;
        long transactionId;
        UUID sessionId;
        TransactionImportReceipt receipt;
        byte[] originalRequest;
        Map<String, String> historical;
        String initialAccounts;
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            try (HistoricalV19Process legacy = HistoricalV19Process.start(artifact, evidence, environment)) {
                assertVersion(19);
                assertThat(tables()).doesNotContainAnyElementsOf(REGISTRY);
                assertThat(db.queryForObject("""
                        SELECT count(*) FROM pg_constraint WHERE conrelid='transaction_import_items'::regclass
                        AND conname='fk_transaction_import_items_owned_transaction' AND contype='f' AND convalidated
                        """, Integer.class)).isEqualTo(1);
                api(http, legacy.port(), "POST", "/api/v1/register", null, null,
                        json.writeValueAsBytes(Map.of("username", username, "email", username + "@example.test", "password", password)));
                String bearer = login(http, legacy.port(), username, password);
                accountId = json.readTree(api(http, legacy.port(), "POST", "/api/v1/accounts", bearer, null,
                        json.writeValueAsBytes(Map.of("name", "Cash", "type", "CASH", "currency", "CNY")))).at("/data/id").longValue();
                long categoryId = json.readTree(api(http, legacy.port(), "POST", "/api/v1/categories", bearer, null,
                        json.writeValueAsBytes(Map.of("name", "Food", "type", "EXPENSE")))).at("/data/id").longValue();
                assertThat(accountId).isPositive();
                assertThat(categoryId).isPositive();
                assertThat(balance(accountId)).isEqualByComparingTo("0.00");
                initialAccounts = accountsWithoutUpdatedAt();
                assertThat(db.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
                JsonNode preview = preview(http, legacy.port(), bearer, accountId, categoryId);
                sessionId = UUID.fromString(preview.at("/data/importSessionId").asText());
                assertThat(preview.at("/data/previewToken").asText()).isNotBlank();
                originalRequest = json.writeValueAsBytes(Map.of("previewToken", preview.at("/data/previewToken").asText(),
                        "acknowledgedWarningIds", List.of()));
                // Exact request bytes are reused unchanged by the current application's Replay.
                Files.write(evidence.resolve("original-confirm-request.json"), originalRequest);
                Files.writeString(evidence.resolve("idempotency-key.txt"), IDEMPOTENCY_KEY);
                byte[] confirmed = api(http, legacy.port(), "POST", confirmPath(sessionId), bearer, IDEMPOTENCY_KEY, originalRequest);
                Files.write(evidence.resolve("original-confirm-response.json"), confirmed);
                TransactionImportConfirmResponse original = json.readerFor(TransactionImportConfirmResponse.class).at("/data").readValue(confirmed);
                assertThat(original.idempotentReplay()).isFalse();
                receipt = original.receipt();
                assertThat(receipt.importSessionId()).isEqualTo(sessionId);
                assertThat(receipt.totalRows()).isEqualTo(1);
                assertThat(receipt.createdCount()).isEqualTo(1);
                assertThat(receipt.acceptedRows()).isEqualTo(1);
                assertThat(receipt.skippedCount()).isZero();
                assertThat(receipt.warningCount()).isZero();
                assertThat(receipt.transactions()).hasSize(1);
                transactionId = receipt.transactions().getFirst().transactionId();
                // CSV physical row 1 is the header; the imported data is physical row 2.
                assertThat(receipt.transactions()).containsExactly(new TransactionImportReceipt.TransactionReference(2, transactionId));
                assertThat(receipt.accountImpacts()).containsExactly(new TransactionImportReceipt.AccountImpact(accountId, 1,
                        new BigDecimal("0.00"), new BigDecimal("-12.34"), new BigDecimal("-12.34")));
                assertThat(receipt.resultDigest()).isEqualTo(sha256(sessionId + "|" + receipt.importBatchId() + "|"
                        + receipt.confirmedAt() + "|" + receipt.transactions() + "|" + receipt.accountImpacts()));
                assertThat(balance(accountId)).isEqualByComparingTo("-12.34");
                assertThat(db.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(1);
                assertThat(db.queryForObject("SELECT amount FROM transactions WHERE id=? AND account_id=? AND type='EXPENSE'",
                        BigDecimal.class, transactionId, accountId)).isEqualByComparingTo("12.34");
                assertConsumedWithoutReferences(sessionId); // HOLD on failure; no SQL repair or C9 bypass.
                assertThat(db.queryForObject("SELECT idempotency_key FROM transaction_import_batches WHERE id=?", String.class,
                        receipt.importBatchId())).isEqualTo(IDEMPOTENCY_KEY);
                historical = snapshot(tables());
                saveSnapshot(evidence, "v19-after-confirm", historical);
                assertVersion(19);
            }
            assertNoOtherConnections();
            assertThat(snapshot(tables())).isEqualTo(historical);
            migrateOne(20);
            assertThat(snapshot(tables())).isEqualTo(historical);
            assertV20Catalog();
            saveSnapshot(evidence, "v20-offline", snapshot(tables()));

            assertNoOtherConnections();
            migrateOne(21);
            assertThat(tables()).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.Stream.concat(historical.keySet().stream(), REGISTRY.stream()).toList());
            assertThat(snapshot(historical.keySet().stream().toList())).isEqualTo(historical);
            assertRegistryEmpty();
            assertV20Catalog();
            saveSnapshot(evidence, "v21-offline", snapshot(tables()));

            try (CurrentRuntime current = startCurrent(tokenSecret)) {
                int port = current.context.getWebServer().getPort();
                assertVersion(21);
                assertThat(snapshot(historical.keySet().stream().toList())).isEqualTo(historical);
                assertThat(db.queryForList("SELECT kind FROM transaction_import_storage_bindings WHERE NOT quarantined ORDER BY kind", String.class))
                        .containsExactly("PLAN", "RAW");
                assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_storage_bindings", Integer.class)).isEqualTo(2);
                assertThat(db.queryForObject("SELECT count(*) FROM transaction_import_file_objects", Integer.class)).isZero();
                assertConsumedWithoutReferences(sessionId);
                // Fresh login uses current password/JWT authentication, without injected Authentication or service calls.
                String bearer = login(http, port, username, password);
                api(http, port, "DELETE", "/api/v1/transactions/" + transactionId, bearer, null, null);
                assertThat(db.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
                assertThat(balance(accountId)).isEqualByComparingTo("0.00");
                assertThat(accountsWithoutUpdatedAt()).isEqualTo(initialAccounts);
                assertThat(snapshot(RECEIPT_TABLES)).isEqualTo(subset(historical, RECEIPT_TABLES));
                assertThat(db.queryForObject("SELECT created_transaction_id FROM transaction_import_items WHERE batch_id=?",
                        Long.class, receipt.importBatchId())).isEqualTo(transactionId);
                Map<String, String> afterDelete = snapshot(tables());
                var unaffected = new java.util.ArrayList<>(historical.keySet());
                unaffected.removeAll(List.of("accounts", "transactions"));
                assertThat(subset(afterDelete, unaffected)).isEqualTo(subset(historical, unaffected));
                byte[] retrieved = api(http, port, "GET", "/api/v1/imports/transactions/batches/" + receipt.importBatchId(), bearer, null, null);
                TransactionImportReceipt retrievedReceipt = json.readerFor(TransactionImportReceipt.class).at("/data").readValue(retrieved);
                assertThat(retrievedReceipt).isEqualTo(receipt);
                assertThat(snapshot(tables())).isEqualTo(afterDelete);
                byte[] replayBytes = api(http, port, "POST", confirmPath(sessionId), bearer, IDEMPOTENCY_KEY, originalRequest);
                TransactionImportConfirmResponse replay = json.readerFor(TransactionImportConfirmResponse.class).at("/data").readValue(replayBytes);
                assertThat(replay.idempotentReplay()).isTrue();
                assertThat(replay.receipt()).isEqualTo(receipt);
                assertThat(snapshot(tables())).isEqualTo(afterDelete);
                assertThat(balance(accountId)).isEqualByComparingTo("0.00");
                Files.write(evidence.resolve("current-receipt.json"), retrieved);
                Files.write(evidence.resolve("current-replay.json"), replayBytes);
                saveSnapshot(evidence, "v21-after-delete-and-replay", afterDelete);
            }
            assertNoOtherConnections();
        }
        System.out.println("B2_2_CHAIN_PASS V19_HTTP_CONFIRM -> OFFLINE_V20 -> OFFLINE_V21 -> CURRENT_HTTP_DELETE_RECEIPT_REPLAY");
    }

    private JsonNode preview(HttpClient http, int port, String bearer, long accountId, long categoryId) throws Exception {
        var mapping = new TransactionImportMapping(Map.of("date", "date", "type", "type", "amount", "amount",
                "account", "account", "category", "category", "description", "description"),
                Map.of("expense", "EXPENSE"), Map.of("Cash", accountId), Map.of("Food", categoryId));
        String boundary = "b2-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"statement.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n" + CSV + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n"
                + json.writeValueAsString(new TransactionImportPreviewRequest(TransactionImportFormat.AUTO, mapping))
                + "\r\n--" + boundary + "--\r\n";
        return json.readTree(send(http, port, "POST", "/api/v1/imports/transactions/preview", bearer, null,
                body.getBytes(StandardCharsets.UTF_8), "multipart/form-data; boundary=" + boundary));
    }

    private String login(HttpClient http, int port, String username, String password) throws Exception {
        String token = json.readTree(api(http, port, "POST", "/api/v1/login", null, null,
                json.writeValueAsBytes(Map.of("username", username, "password", password)))).at("/data/token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private byte[] api(HttpClient http, int port, String method, String path, String bearer, String key, byte[] body) throws Exception {
        return send(http, port, method, path, bearer, key, body, "application/json");
    }

    private byte[] send(HttpClient http, int port, String method, String path, String bearer, String key,
                        byte[] body, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", contentType);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(200);
        assertThat(json.readTree(response.body()).at("/code").intValue()).isEqualTo(200);
        return response.body();
    }

    private String confirmPath(UUID session) { return "/api/v1/imports/transactions/" + session + "/confirm"; }

    private void assertConsumedWithoutReferences(UUID session) {
        assertThat(db.queryForObject("""
                SELECT count(*) FROM transaction_import_sessions WHERE id=? AND status='CONSUMED'
                AND consumed_at IS NOT NULL AND temporary_storage_reference IS NULL AND plan_storage_reference IS NULL
                """, Integer.class, session)).as("HOLD: historical consumed Session must have no active RAW/PLAN references").isEqualTo(1);
    }

    private void assertVersion(int version) {
        assertThat(db.queryForList("SELECT version::integer FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", Integer.class))
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, version).boxed().toList());
        assertThat(db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class)).isZero();
    }

    private void migrateOne(int target) {
        assertNoOtherConnections();
        var flyway = Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations("classpath:db/migration").target(Integer.toString(target)).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertVersion(target);
        assertNoOtherConnections();
    }

    private void assertV20Catalog() {
        assertThat(db.queryForList("SELECT conname FROM pg_constraint WHERE conrelid='transaction_import_items'::regclass AND contype='f'", String.class))
                .contains("fk_transaction_import_items_owned_batch").doesNotContain("fk_transaction_import_items_owned_transaction");
        assertThat(db.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='transaction_import_items' AND column_name='created_transaction_id'", String.class)).isEqualTo("NO");
        assertThat(db.queryForList("SELECT tgname FROM pg_trigger WHERE NOT tgisinternal AND tgenabled='O' AND tgrelid IN ('transaction_import_batches'::regclass,'transaction_import_items'::regclass,'transaction_import_batch_account_impacts'::regclass)", String.class))
                .containsExactlyInAnyOrder("trg_transaction_import_batches_immutable", "trg_transaction_import_items_immutable",
                        "trg_transaction_import_impacts_immutable", "trg_transaction_import_items_validate_transaction_reference");
        assertThat(db.queryForObject("SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgrelid='transaction_import_items'::regclass AND tgname='trg_transaction_import_items_validate_transaction_reference'", String.class))
                .contains("BEFORE INSERT", "EXECUTE FUNCTION validate_transaction_import_item_transaction_reference()");
        assertThat(db.queryForObject("SELECT pg_get_functiondef('validate_transaction_import_item_transaction_reference()'::regprocedure)", String.class))
                .contains("FOR KEY SHARE", "public.transactions", "transaction.user_id = NEW.user_id", "transaction.id = NEW.created_transaction_id");
    }

    private void assertRegistryEmpty() {
        for (String table : REGISTRY) assertThat(db.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
    }

    private List<String> tables() {
        return db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename", String.class);
    }

    private Map<String, String> snapshot(List<String> tables) {
        Map<String, String> result = new TreeMap<>();
        for (String table : tables) result.put(table, db.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM " + table + " t", String.class));
        return result;
    }

    private Map<String, String> subset(Map<String, String> original, List<String> tables) {
        Map<String, String> result = new TreeMap<>();
        tables.forEach(table -> result.put(table, original.get(table)));
        return result;
    }

    private void saveSnapshot(Path evidence, String name, Map<String, String> snapshot) throws Exception {
        Files.write(evidence.resolve(name + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
    }

    private String accountsWithoutUpdatedAt() {
        return db.queryForObject("SELECT jsonb_agg(to_jsonb(a)-'updated_at' ORDER BY id)::text FROM accounts a", String.class);
    }

    private BigDecimal balance(long accountId) {
        return db.queryForObject("SELECT balance FROM accounts WHERE id=?", BigDecimal.class, accountId);
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertNoOtherConnections() {
        assertThat(db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND backend_type='client backend' AND pid<>pg_backend_pid()", Integer.class)).isZero();
    }

    private CurrentRuntime startCurrent(String tokenSecret) {
        var application = new SpringApplication(FinanceOsApplication.class);
        application.setAdditionalProfiles("test");
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("historical-confirm-secret",
                        Map.of("finance.import.confirm.token-secret", tokenSecret))));
        var context = (ServletWebServerApplicationContext) application.run("--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + POSTGRESQL.getJdbcUrl(), "--spring.datasource.username=" + POSTGRESQL.getUsername(),
                "--spring.datasource.password=" + POSTGRESQL.getPassword(), "--spring.datasource.hikari.minimum-idle=0",
                "--spring.datasource.hikari.maximum-pool-size=2", "--finance.import.cleanup.enabled=false");
        try {
            return new CurrentRuntime(context);
        } catch (Exception | AssertionError failure) {
            context.close();
            throw failure;
        }
    }

    private record StorageResources(TrustedImportStorageRoot root, ThreadPoolExecutor workers, FileChannel control) {
        static StorageResources capture(LocalTemporaryImportFileStorage storage) {
            var root = (TrustedImportStorageRoot) ReflectionTestUtils.getField(storage, "root");
            Object state = ReflectionTestUtils.getField(root, "context");
            return new StorageResources(root, (ThreadPoolExecutor) ReflectionTestUtils.getField(state, "workers"),
                    (FileChannel) ReflectionTestUtils.getField(state, "control"));
        }
    }

    private class CurrentRuntime implements AutoCloseable {
        final ServletWebServerApplicationContext context;
        final HikariDataSource pool;
        final List<StorageResources> storage;

        CurrentRuntime(ServletWebServerApplicationContext context) {
            this.context = context;
            this.pool = context.getBean(HikariDataSource.class);
            this.storage = context.getBeansOfType(LocalTemporaryImportFileStorage.class).values().stream()
                    .map(StorageResources::capture).toList();
            assertThat(context.isActive()).isTrue();
            assertThat(storage).hasSize(2);
            assertThat(context.getBeansOfType(TransactionImportPreviewCleanupScheduler.class)).isEmpty();
        }

        @Override public void close() {
            context.close();
            assertThat(context.isActive()).isFalse();
            assertThat(pool.isClosed()).isTrue();
            for (StorageResources resource : storage) {
                assertThat(ReflectionTestUtils.getField(resource.root(), "closed")).isEqualTo(true);
                assertThat(resource.workers().isTerminated()).isTrue();
                assertThat(resource.control().isOpen()).isFalse();
            }
            assertNoOtherConnections();
        }
    }
}
