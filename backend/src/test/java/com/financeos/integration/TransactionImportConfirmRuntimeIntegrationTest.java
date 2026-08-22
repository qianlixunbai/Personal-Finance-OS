package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.importing.dto.TransactionImportFormat;
import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionImportConfirmRuntimeIntegrationTest extends PostgresIntegrationTest {
    private static final String CSV = "date,type,amount,account,category,description\n2026-08-14,expense,12.34,Cash,Food,lunch\n";

    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @LocalServerPort private int port;

    @Test
    void csvRuntimeFlowUsesJwtReplaysAuthoritativeReceiptAndRejectsIdempotencyConflict() throws Exception {
        User user = registerAndLogin("runtime-csv");
        long accountId = account(user.id(), "Cash");
        long categoryId = category(user.id(), "Food");
        JsonNode preview = preview(user, "statement.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8), mapping(accountId, categoryId));
        JsonNode first = confirm(user, preview, "runtime-csv-key");
        JsonNode replay = confirm(user, preview, "runtime-csv-key");
        ResponseEntity<String> receiptGet = exchange(HttpMethod.GET,
                "/api/v1/imports/transactions/batches/" + first.at("/data/receipt/importBatchId").asText(), bearer(user, null), null);
        JsonNode retrieved = objectMapper.readTree(receiptGet.getBody());

        assertThat(first.at("/data/idempotentReplay").asBoolean()).isFalse();
        assertThat(replay.at("/data/idempotentReplay").asBoolean()).isTrue();
        assertThat(replay.at("/data/receipt/importBatchId").asText()).isEqualTo(first.at("/data/receipt/importBatchId").asText());
        assertThat(replay.at("/data/receipt/resultDigest").asText()).isEqualTo(first.at("/data/receipt/resultDigest").asText());
        assertThat(receiptGet.getStatusCode().value()).isEqualTo(200);
        assertThat(retrieved.at("/data/resultDigest").asText()).isEqualTo(first.at("/data/receipt/resultDigest").asText());
        assertReceiptMatchesDatabase(user.id(), accountId, preview, first);

        JsonNode conflictPreview = preview(user, "conflict.csv", "text/csv", CSV.replace("lunch", "dinner").getBytes(StandardCharsets.UTF_8), mapping(accountId, categoryId));
        ResponseEntity<String> conflict = exchange(HttpMethod.POST, "/api/v1/imports/transactions/" + conflictPreview.at("/data/importSessionId").asText() + "/confirm",
                bearer(user, "runtime-csv-key"), Map.of("previewToken", conflictPreview.at("/data/previewToken").asText(), "acknowledgedWarningIds", List.of()));
        JsonNode body = objectMapper.readTree(conflict.getBody());
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(body.at("/errorCode").asText()).isEqualTo("IMPORT_IDEMPOTENCY_CONFLICT");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, user.id())).isEqualTo(1);
    }

    @Test
    void xlsxRuntimeFlowConfirmsFrozenPlanAndPersistsTheReceipt() throws Exception {
        User user = registerAndLogin("runtime-xlsx");
        long accountId = account(user.id(), "Cash");
        long categoryId = category(user.id(), "Food");
        JsonNode preview = preview(user, "statement.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(), mapping(accountId, categoryId));
        JsonNode confirmed = confirm(user, preview, "runtime-xlsx-key");
        assertThat(confirmed.at("/data/receipt/totalRows").asInt()).isEqualTo(1);
        assertReceiptMatchesDatabase(user.id(), accountId, preview, confirmed);
    }

    @Test
    void runtimeCancelledAndExpiredSessionsFailClosedWithoutFinancialWrites() throws Exception {
        User user = registerAndLogin("runtime-terminal");
        long accountId = account(user.id(), "Cash");
        long categoryId = category(user.id(), "Food");
        JsonNode cancelled = preview(user, "cancel.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8), mapping(accountId, categoryId));
        ResponseEntity<String> cancelResponse = exchange(HttpMethod.DELETE, "/api/v1/imports/transactions/" + cancelled.at("/data/importSessionId").asText(), bearer(user, null), null);
        assertThat(cancelResponse.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> cancelledConfirm = exchange(HttpMethod.POST, "/api/v1/imports/transactions/" + cancelled.at("/data/importSessionId").asText() + "/confirm",
                bearer(user, "cancelled-key"), Map.of("previewToken", cancelled.at("/data/previewToken").asText(), "acknowledgedWarningIds", List.of()));
        assertThat(cancelledConfirm.getStatusCode().value()).isEqualTo(409);
        assertThat(objectMapper.readTree(cancelledConfirm.getBody()).at("/errorCode").asText()).isEqualTo("IMPORT_SESSION_CANCELLED");

        JsonNode expired = preview(user, "expired.csv", "text/csv", CSV.replace("lunch", "expired").getBytes(StandardCharsets.UTF_8), mapping(accountId, categoryId));
        jdbcTemplate.update("UPDATE transaction_import_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", UUID.fromString(expired.at("/data/importSessionId").asText()));
        ResponseEntity<String> expiredConfirm = exchange(HttpMethod.POST, "/api/v1/imports/transactions/" + expired.at("/data/importSessionId").asText() + "/confirm",
                bearer(user, "expired-key"), Map.of("previewToken", expired.at("/data/previewToken").asText(), "acknowledgedWarningIds", List.of()));
        assertThat(expiredConfirm.getStatusCode().value()).isEqualTo(409);
        assertThat(objectMapper.readTree(expiredConfirm.getBody()).at("/errorCode").asText()).isEqualTo("IMPORT_PREVIEW_EXPIRED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, user.id())).isZero();
    }

    @Test
    void runtimeLockTimeoutReturnsStructuredRetryableImportErrorWithoutFinancialWrites() throws Exception {
        User user = registerAndLogin("runtime-lock");
        long accountId = account(user.id(), "Cash");
        long categoryId = category(user.id(), "Food");
        JsonNode preview = preview(user, "lock.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8), mapping(accountId, categoryId));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", Long.class, accountId);
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test lock holder did not release"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/imports/transactions/" + preview.at("/data/importSessionId").asText() + "/confirm",
                    bearer(user, "runtime-lock-key"), Map.of("previewToken", preview.at("/data/previewToken").asText(), "acknowledgedWarningIds", List.of()));
            JsonNode body = objectMapper.readTree(response.getBody());
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(body.at("/errorCode").asText()).isEqualTo("IMPORT_LOCK_CONFLICT");
            assertThat(body.at("/retryable").asBoolean()).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, user.id())).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("0.00");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE user_id = ?", Integer.class, user.id())).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM transaction_import_sessions WHERE id = ?", String.class, UUID.fromString(preview.at("/data/importSessionId").asText()))).isEqualTo("PREVIEW_READY");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    private JsonNode preview(User user, String name, String contentType, byte[] contents, TransactionImportMapping mapping) throws Exception {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new NamedBytes(contents, name));
        HttpHeaders requestHeaders = new HttpHeaders(); requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        form.add("request", new HttpEntity<>(new TransactionImportPreviewRequest(TransactionImportFormat.AUTO, mapping), requestHeaders));
        HttpHeaders headers = bearer(user, null); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = http.postForEntity(url("/api/v1/imports/transactions/preview"), new HttpEntity<>(form, headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode confirm(User user, JsonNode preview, String key) throws Exception {
        ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/imports/transactions/" + preview.at("/data/importSessionId").asText() + "/confirm",
                bearer(user, key), Map.of("previewToken", preview.at("/data/previewToken").asText(), "acknowledgedWarningIds", List.of()));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return objectMapper.readTree(response.getBody());
    }

    private void assertReceiptMatchesDatabase(long userId, long accountId, JsonNode preview, JsonNode response) {
        UUID sessionId = UUID.fromString(preview.at("/data/importSessionId").asText());
        UUID batchId = UUID.fromString(response.at("/data/receipt/importBatchId").asText());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM transaction_import_sessions WHERE id = ?", String.class, sessionId)).isEqualTo("CONSUMED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE id = ? AND result_digest = ?", Integer.class, batchId, response.at("/data/receipt/resultDigest").asText())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_items WHERE batch_id = ?", Integer.class, batchId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batch_account_impacts WHERE batch_id = ?", Integer.class, batchId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("-12.34");
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, HttpHeaders headers, Object body) {
        return http.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }
    private HttpHeaders bearer(User user, String key) { HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(user.token()); if (key != null) headers.set("Idempotency-Key", key); return headers; }
    private String url(String path) { return "http://localhost:" + port + path; }
    private long account(long userId, String name) { return jdbcTemplate.queryForObject("INSERT INTO accounts (user_id, name, type, currency, balance, status) VALUES (?, ?, 'CASH', 'CNY', 0, 'ACTIVE') RETURNING id", Long.class, userId, name); }
    private long category(long userId, String name) { return jdbcTemplate.queryForObject("INSERT INTO categories (user_id, name, type, is_system) VALUES (?, ?, 'EXPENSE', false) RETURNING id", Long.class, userId, name); }
    private TransactionImportMapping mapping(long accountId, long categoryId) { return new TransactionImportMapping(Map.of("date", "date", "type", "type", "amount", "amount", "account", "account", "category", "category", "description", "description"), Map.of("expense", "EXPENSE"), Map.of("Cash", accountId), Map.of("Food", categoryId)); }
    private User registerAndLogin(String prefix) throws Exception { String suffix = UUID.randomUUID().toString().replace("-", ""); String username = prefix + suffix; String email = username + "@example.com";
        ResponseEntity<String> registered = exchange(HttpMethod.POST, "/api/v1/register", json(), new RegisterRequest(username, email, "password123")); assertThat(registered.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> login = exchange(HttpMethod.POST, "/api/v1/login", json(), new LoginRequest(username, "password123")); assertThat(login.getStatusCode().value()).isEqualTo(200);
        return new User(jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username), objectMapper.readTree(login.getBody()).at("/data/token").asText()); }
    private HttpHeaders json() { HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); return headers; }
    private byte[] xlsx() throws Exception { try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) { var sheet = workbook.createSheet(); String[] headings = {"date", "type", "amount", "account", "category", "description"}; var header = sheet.createRow(0); for (int index = 0; index < headings.length; index++) header.createCell(index).setCellValue(headings[index]); String[] rowValues = {"2026-08-14", "expense", "12.34", "Cash", "Food", "lunch"}; var row = sheet.createRow(1); for (int index = 0; index < rowValues.length; index++) row.createCell(index).setCellValue(rowValues[index]); workbook.write(output); return output.toByteArray(); } }
    private record User(long id, String token) { }
    private static final class NamedBytes extends ByteArrayResource { private final String filename; private NamedBytes(byte[] bytes, String filename) { super(bytes); this.filename = filename; } @Override public String getFilename() { return filename; } }
}
