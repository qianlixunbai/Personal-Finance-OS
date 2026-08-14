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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TransactionImportPreviewApiIntegrationTest extends PostgresIntegrationTest {
    private static final String CSV = "date,type,amount,account,category,description\n2026-08-14,expense,12.34,Cash,Food,lunch\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void enforcesAuthenticationAndFileErrorHttpContracts() throws Exception {
        MockMultipartFile request = requestPart(null);
        mockMvc.perform(multipart("/api/v1/imports/transactions/preview")
                        .file(file("statement.csv", "text/csv", CSV)).file(request))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));

        User user = registerAndLogin("import-http");
        mockMvc.perform(multipart("/api/v1/imports/transactions/preview")
                        .file(file("statement.txt", "text/plain", CSV)).file(requestPart(null)).header("Authorization", bearer(user)))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(415));
        mockMvc.perform(multipart("/api/v1/imports/transactions/preview")
                        .file(file("statement.csv", "text/csv", "date,type\n\"unterminated")).file(requestPart(null)).header("Authorization", bearer(user)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(multipart("/api/v1/imports/transactions/preview")
                        .file(file("statement.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "not-a-zip"))
                        .file(requestPart(null)).header("Authorization", bearer(user)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(multipart("/api/v1/imports/transactions/preview")
                        .file(file("large.csv", "text/csv", "x".repeat(5 * 1024 * 1024 + 1))).file(requestPart(null)).header("Authorization", bearer(user)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));
    }

    @Test
    void previewsCsvAndXlsxAndPersistsAuthoritativeSessionWithoutFinancialWrites() throws Exception {
        User user = registerAndLogin("import-valid");
        long accountId = account(user.id(), "Cash");
        long categoryId = category(user.id(), "Food");
        TransactionImportMapping mapping = mapping(accountId, categoryId);
        Counts before = counts(user.id());

        JsonNode csv = preview(user, file("statement.csv", "text/csv", CSV), mapping, 200);
        JsonNode xlsx = preview(user, file("statement.xlsx", xlsxType(), xlsx()), mapping, 200);

        assertThat(csv.at("/data/sessionStatus").asText()).isEqualTo("PREVIEW_READY");
        assertThat(csv.at("/data/confirmable").asBoolean()).isTrue();
        assertThat(xlsx.at("/data/sessionStatus").asText()).isEqualTo("PREVIEW_READY");
        assertThat(counts(user.id())).isEqualTo(before);
        assertPersistedSession(csv, user.id());
    }

    @Test
    void keepsRowErrorsAndDuplicateWarningsInSuccessfulReadOnlyPreviews() throws Exception {
        User user = registerAndLogin("import-warning");
        long accountId = account(user.id(), "Cash");
        long categoryId = category(user.id(), "Food");
        TransactionImportMapping mapping = mapping(accountId, categoryId);
        Counts before = counts(user.id());

        JsonNode invalid = preview(user, file("invalid.csv", "text/csv", CSV.replace("12.34", "0")), mapping, 200);
        JsonNode warning = preview(user, file("duplicates.csv", "text/csv", CSV + "2026-08-14,expense,12.34,Cash,Food,lunch\n"), mapping, 200);

        assertThat(invalid.at("/data/summary/errorRows").asInt()).isEqualTo(1);
        assertThat(invalid.at("/data/confirmable").asBoolean()).isFalse();
        assertThat(warning.at("/data/summary/warningRows").asInt()).isEqualTo(2);
        assertThat(warning.at("/data/rows/0/warnings/0/code").asText()).isEqualTo("IN_FILE_PROBABLE");
        assertThat(counts(user.id())).isEqualTo(before);
    }

    @Test
    void failClosesForeignSessionAndForeignMappingAndScopesDuplicateLookupToOwner() throws Exception {
        User owner = registerAndLogin("import-owner");
        User other = registerAndLogin("import-other");
        long ownerAccount = account(owner.id(), "Cash");
        long ownerCategory = category(owner.id(), "Food");
        long otherAccount = account(other.id(), "OtherCash");
        long otherCategory = category(other.id(), "OtherFood");
        jdbcTemplate.update("""
                INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, description, transacted_at)
                VALUES (?, ?, ?, 'EXPENSE', 12.34, 'CNY', 'lunch', TIMESTAMP '2026-08-14 00:00:00')
                """, other.id(), otherAccount, otherCategory);

        JsonNode ownerPreview = preview(owner, file("statement.csv", "text/csv", CSV), mapping(ownerAccount, ownerCategory), 200);
        String sessionId = ownerPreview.at("/data/importSessionId").asText();
        mockMvc.perform(get("/api/v1/imports/transactions/{id}/rows", sessionId).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(delete("/api/v1/imports/transactions/{id}", sessionId).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        assertThat(ownerPreview.at("/data/summary/warningRows").asInt()).isZero();

        JsonNode foreignMapping = preview(owner, file("foreign.csv", "text/csv", CSV), mapping(otherAccount, otherCategory), 200);
        assertThat(foreignMapping.at("/data/summary/errorRows").asInt()).isEqualTo(1);
        assertThat(foreignMapping.at("/data/rows/0/errors/0/code").asText()).isIn("ACCOUNT_NOT_FOUND", "ACCOUNT_NOT_MAPPED");
    }

    @Test
    void cancelsOnlyOwnersSessionWithoutChangingFinancialFacts() throws Exception {
        User user = registerAndLogin("import-cancel");
        long accountId = account(user.id(), "Cash");
        long categoryId = category(user.id(), "Food");
        Counts before = counts(user.id());
        JsonNode preview = preview(user, file("statement.csv", "text/csv", CSV), mapping(accountId, categoryId), 200);

        mockMvc.perform(delete("/api/v1/imports/transactions/{id}", preview.at("/data/importSessionId").asText())
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM transaction_import_sessions WHERE id = ?", String.class,
                UUID.fromString(preview.at("/data/importSessionId").asText()))).isEqualTo("CANCELLED");
        assertThat(counts(user.id())).isEqualTo(before);
    }

    private JsonNode preview(User user, MockMultipartFile upload, TransactionImportMapping mapping, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/imports/transactions/preview")
                        .file(upload).file(requestPart(mapping)).header("Authorization", bearer(user)))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertPersistedSession(JsonNode response, long userId) {
        String id = response.at("/data/importSessionId").asText();
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT user_id, file_digest, mapping_digest, normalized_rows_digest, plan_storage_reference,
                       preallocated_batch_id, expires_at FROM transaction_import_sessions WHERE id = ?
                """, UUID.fromString(id));
        assertThat(row.get("user_id")).isEqualTo(userId);
        assertThat(row.get("file_digest")).isEqualTo(response.at("/data/fileDigest").asText());
        assertThat(row.get("mapping_digest")).isEqualTo(response.at("/data/mappingDigest").asText());
        assertThat(row.get("normalized_rows_digest")).isEqualTo(response.at("/data/normalizedRowsDigest").asText());
        assertThat(row.get("plan_storage_reference")).isNotNull();
        assertThat(row.get("preallocated_batch_id")).isNotNull();
        assertThat(row.get("expires_at")).isNotNull();
    }

    private Counts counts(long userId) {
        return new Counts(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId),
                jdbcTemplate.queryForObject("SELECT coalesce(sum(balance), 0) FROM accounts WHERE user_id = ?", String.class, userId),
                jdbcTemplate.queryForObject("SELECT count(*) FROM accounts WHERE user_id = ?", Integer.class, userId),
                jdbcTemplate.queryForObject("SELECT count(*) FROM categories WHERE user_id = ?", Integer.class, userId));
    }

    private long account(long userId, String name) { return jdbcTemplate.queryForObject("""
            INSERT INTO accounts (user_id, name, type, currency, balance, status) VALUES (?, ?, 'CASH', 'CNY', 0, 'ACTIVE') RETURNING id
            """, Long.class, userId, name); }
    private long category(long userId, String name) { return jdbcTemplate.queryForObject("""
            INSERT INTO categories (user_id, name, type, is_system) VALUES (?, ?, 'EXPENSE', false) RETURNING id
            """, Long.class, userId, name); }
    private TransactionImportMapping mapping(long accountId, long categoryId) { return new TransactionImportMapping(
            Map.of("date", "date", "type", "type", "amount", "amount", "account", "account", "category", "category", "description", "description"),
            Map.of("expense", "EXPENSE"), Map.of("Cash", accountId), Map.of("Food", categoryId)); }
    private MockMultipartFile file(String name, String type, String content) { return new MockMultipartFile("file", name, type, content.getBytes(StandardCharsets.UTF_8)); }
    private MockMultipartFile file(String name, String type, byte[] content) { return new MockMultipartFile("file", name, type, content); }
    private MockMultipartFile requestPart(TransactionImportMapping mapping) throws Exception { return new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(new TransactionImportPreviewRequest(TransactionImportFormat.AUTO, mapping))); }
    private String xlsxType() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
    private byte[] xlsx() throws Exception { try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        var sheet = workbook.createSheet(); var header = sheet.createRow(0); String[] headings = {"date", "type", "amount", "account", "category", "description"};
        for (int i = 0; i < headings.length; i++) header.createCell(i).setCellValue(headings[i]);
        var row = sheet.createRow(1); String[] values = {"2026-08-14", "expense", "12.34", "Cash", "Food", "lunch"};
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]); workbook.write(output); return output.toByteArray(); } }
    private User registerAndLogin(String prefix) throws Exception { String suffix = UUID.randomUUID().toString().replace("-", ""); String username = prefix + suffix; String email = username + "@example.com";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/register").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(username, email, "password123")))).andExpect(status().isOk());
        MvcResult login = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(username, "password123")))).andExpect(status().isOk()).andReturn();
        return new User(jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username), objectMapper.readTree(login.getResponse().getContentAsByteArray()).at("/data/token").asText()); }
    private String bearer(User user) { return "Bearer " + user.token(); }
    private record User(long id, String token) { }
    private record Counts(int transactions, String balances, int accounts, int categories) { }
}
