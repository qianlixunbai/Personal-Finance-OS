package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.importing.dto.TransactionImportConfirmRequest;
import com.financeos.module.importing.dto.TransactionImportConfirmResponse;
import com.financeos.module.importing.dto.TransactionImportFormat;
import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.importing.dto.TransactionImportReceipt;
import com.financeos.module.importing.preview.TransactionImportPreviewService;
import com.financeos.module.importing.service.TransactionImportConfirmService;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest.LegacyV19FlywayConfiguration.class)
class FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest {
    private static final String CSV = "date,type,amount,account,category,description\n"
            + "2026-08-14,expense,12.34,Cash,Food,lunch\n";
    private static final String IDEMPOTENCY_KEY = "v19-to-v20-receipt-replay-key";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private TransactionImportPreviewService previewService;
    @Autowired private TransactionImportConfirmService confirmService;

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRESQL::getDriverClassName);
    }

    @Test
    void v19ReceiptSurvivesV20UpgradeDeleteAndMockMvcReplayWithoutAnotherFinancialEffect() throws Exception {
        AuthenticatedUser user = registerAndLogin();
        long accountId = insertAccount(user.id());
        long categoryId = insertCategory(user.id());
        TransactionImportMapping mapping = mapping(accountId, categoryId);
        var preview = previewService.create(user.id(), new MockMultipartFile(
                "file", "statement.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)),
                new TransactionImportPreviewRequest(TransactionImportFormat.AUTO, mapping));
        TransactionImportConfirmRequest confirmRequest = new TransactionImportConfirmRequest(preview.previewToken(), List.of());

        TransactionImportConfirmResponse original = confirmService.confirm(
                user.id(), preview.importSessionId(), IDEMPOTENCY_KEY, confirmRequest);
        assertThat(original.idempotentReplay()).isFalse();
        assertThat(appliedVersion()).isEqualTo("19");
        long transactionId = original.receipt().transactions().getFirst().transactionId();
        UUID batchId = original.receipt().importBatchId();
        assertThat(balance(accountId)).isEqualByComparingTo("-12.34");
        assertThat(count("SELECT count(*) FROM transactions WHERE user_id = ?", user.id())).isEqualTo(1);
        HistorySnapshot beforeUpgrade = historySnapshot();

        latestFlyway().migrate();

        assertThat(appliedVersion()).isEqualTo("20");
        assertThat(historySnapshot()).isEqualTo(beforeUpgrade);
        assertThat(jdbcTemplate.queryForObject("SELECT result_digest FROM transaction_import_batches WHERE id = ?",
                String.class, batchId)).isEqualTo(original.receipt().resultDigest());

        mockMvc.perform(delete("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(count("SELECT count(*) FROM transactions WHERE id = ?", transactionId)).isZero();
        assertThat(count("SELECT count(*) FROM transaction_import_items WHERE batch_id = ? AND created_transaction_id = ?",
                batchId, transactionId)).isEqualTo(1);
        assertThat(balance(accountId)).isEqualByComparingTo("0.00");

        MvcResult receiptGetResult = mockMvc.perform(get("/api/v1/imports/transactions/batches/{id}", batchId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        // Deserialize from the response bytes to preserve BigDecimal scale; an
        // intermediate JsonNode would parse decimals as doubles by default.
        TransactionImportReceipt retrievedReceipt = objectMapper.readerFor(TransactionImportReceipt.class)
                .at("/data").readValue(receiptGetResult.getResponse().getContentAsByteArray());
        assertThat(retrievedReceipt).isEqualTo(original.receipt());

        MvcResult replayResult = mockMvc.perform(post("/api/v1/imports/transactions/{id}/confirm", preview.importSessionId())
                        .header("Authorization", "Bearer " + user.token())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(confirmRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.idempotentReplay").value(true))
                .andReturn();
        TransactionImportConfirmResponse replay = objectMapper.readerFor(TransactionImportConfirmResponse.class)
                .at("/data").readValue(replayResult.getResponse().getContentAsByteArray());
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.receipt()).isEqualTo(original.receipt());
        assertThat(replay.receipt().resultDigest()).isEqualTo(original.receipt().resultDigest());
        assertThat(historySnapshot()).isEqualTo(beforeUpgrade);
        assertThat(count("SELECT count(*) FROM transactions WHERE user_id = ?", user.id())).isZero();
        assertThat(balance(accountId)).isEqualByComparingTo("0.00");
    }

    private AuthenticatedUser registerAndLogin() throws Exception {
        String username = "v19v20-" + UUID.randomUUID().toString().replace("-", "");
        mockMvc.perform(post("/api/v1/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new RegisterRequest(
                                username, username + "@example.test", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult login = mockMvc.perform(post("/api/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new LoginRequest(username, "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();
        JsonNode loginBody = objectMapper.readTree(login.getResponse().getContentAsByteArray());
        long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        return new AuthenticatedUser(userId, loginBody.at("/data/token").asText());
    }

    private long insertAccount(long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance, status)
                VALUES (?, ?, 'CASH', 'CNY', 0.00, 'ACTIVE') RETURNING id
                """, Long.class, userId, "Cash");
    }

    private long insertCategory(long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO categories (user_id, name, type, is_system)
                VALUES (?, ?, 'EXPENSE', false) RETURNING id
                """, Long.class, userId, "Food");
    }

    private TransactionImportMapping mapping(long accountId, long categoryId) {
        return new TransactionImportMapping(
                Map.of("date", "date", "type", "type", "amount", "amount", "account", "account",
                        "category", "category", "description", "description"),
                Map.of("expense", "EXPENSE"), Map.of("Cash", accountId), Map.of("Food", categoryId));
    }

    private HistorySnapshot historySnapshot() {
        return new HistorySnapshot(
                snapshotTable("transaction_import_sessions", "id"),
                snapshotTable("transaction_import_batches", "id"),
                snapshotTable("transaction_import_items", "id"),
                snapshotTable("transaction_import_batch_account_impacts", "id"));
    }

    private String snapshotTable(String table, String orderBy) {
        String sql = "SELECT COALESCE(jsonb_agg(to_jsonb(row_value) ORDER BY row_value." + orderBy
                + "), '[]'::jsonb)::text FROM " + table + " row_value";
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private String appliedVersion() {
        return jdbcTemplate.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success = true AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }

    private int count(String sql, Object... parameters) {
        return jdbcTemplate.queryForObject(sql, Integer.class, parameters);
    }

    private BigDecimal balance(long accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    private Flyway latestFlyway() {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
    }

    private record AuthenticatedUser(long id, String token) { }

    private record HistorySnapshot(String sessions, String batches, String items, String impacts) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class LegacyV19FlywayConfiguration {
        @Bean
        FlywayMigrationStrategy migrateOnlyThroughV19(DataSource dataSource) {
            return ignoredFlyway -> Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("19"))
                    .load()
                    .migrate();
        }
    }
}
