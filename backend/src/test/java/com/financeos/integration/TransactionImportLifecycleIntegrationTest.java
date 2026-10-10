package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.importing.dto.TransactionImportConfirmRequest;
import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.importing.preview.TransactionImportPreviewService;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.service.TransactionService;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TransactionImportLifecycleIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionImportPreviewService previewService;
    @Autowired private TransactionService transactionService;

    @Test
    void deleteKeepsReceiptAndItemAndConfirmReplayDoesNotRecreateTransactionOrBalanceEffect() throws Exception {
        TestUser user = registerUser("import-delete");
        TestUser foreign = registerUser("import-delete-foreign");
        long userId = userId(user);
        long accountId = createAccount(userId);
        long expenseCategoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = preview(userId, accountId, expenseCategoryId, null,
                "2026-08-01,expense,100.00,Cash,Food,imported expense");

        String idempotencyKey = "delete-replay-" + UUID.randomUUID();
        JsonNode confirmed = confirm(user, preview, idempotencyKey);
        JsonNode originalReceipt = confirmed.at("/data/receipt");
        UUID batchId = UUID.fromString(originalReceipt.path("importBatchId").asText());
        long transactionId = originalReceipt.at("/transactions/0/transactionId").asLong();
        String resultDigest = originalReceipt.path("resultDigest").asText();
        List<Map<String, Object>> itemsBeforeDelete = itemSnapshot(batchId);
        List<Map<String, Object>> impactsBeforeDelete = impactSnapshot(batchId);
        Map<String, Object> batchBeforeDelete = batchSnapshot(batchId);

        assertThat(confirmed.at("/data/idempotentReplay").asBoolean()).isFalse();
        assertThat(balance(accountId)).isEqualByComparingTo("-100.00");
        assertThat(transactionCount(userId)).isEqualTo(1);

        deleteTransaction(user, transactionId);

        assertThat(transactionCount(userId)).isZero();
        assertThat(balance(accountId)).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE id = ?", Integer.class, transactionId)).isZero();
        mockMvc.perform(delete("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", bearer(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", bearer(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        assertThat(balance(accountId)).isEqualByComparingTo("0.00");
        assertThat(itemSnapshot(batchId)).isEqualTo(itemsBeforeDelete);
        assertThat(impactsBeforeDelete).hasSize(1);
        assertThat(impactsBeforeDelete.getFirst()).containsAllEntriesOf(Map.of(
                "account_id", accountId,
                "row_count", 1,
                "balance_before", new BigDecimal("0.00"),
                "delta", new BigDecimal("-100.00"),
                "balance_after", new BigDecimal("-100.00")));
        assertThat(impactSnapshot(batchId)).isEqualTo(impactsBeforeDelete);
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBeforeDelete);
        assertThat(batchResultDigest(batchId)).isEqualTo(resultDigest);

        JsonNode receiptAfterDelete = getReceipt(user, batchId);
        assertThat(receiptAfterDelete).isEqualTo(originalReceipt);
        assertThat(receiptAfterDelete.at("/transactions/0/transactionId").asLong()).isEqualTo(transactionId);

        mockMvc.perform(post("/api/v1/imports/transactions/{sessionId}/confirm", preview.importSessionId())
                        .header("Authorization", bearer(foreign))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionImportConfirmRequest(preview.previewToken(), List.of()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        assertThat(transactionCount(userId)).isZero();
        assertThat(balance(accountId)).isEqualByComparingTo("0.00");

        JsonNode replayed = confirm(user, preview, idempotencyKey);
        assertThat(replayed.at("/data/idempotentReplay").asBoolean()).isTrue();
        assertThat(replayed.at("/data/receipt")).isEqualTo(originalReceipt);
        assertThat(transactionCount(userId)).isZero();
        assertThat(balance(accountId)).isEqualByComparingTo("0.00");
        assertThat(itemSnapshot(batchId)).isEqualTo(itemsBeforeDelete);
        assertThat(impactSnapshot(batchId)).isEqualTo(impactsBeforeDelete);
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBeforeDelete);
        assertThat(batchResultDigest(batchId)).isEqualTo(resultDigest);
    }

    @Test
    void updateThenDeleteReversesTheCurrentTransactionEffectAndLeavesOriginalReceiptUnchanged() throws Exception {
        TestUser user = registerUser("import-update-delete");
        long userId = userId(user);
        long accountId = createAccount(userId);
        long expenseCategoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = preview(userId, accountId, expenseCategoryId, null,
                "2026-08-01,expense,100.00,Cash,Food,imported expense");
        JsonNode confirmed = confirm(user, preview, "update-delete-" + UUID.randomUUID());
        JsonNode originalReceipt = confirmed.at("/data/receipt");
        UUID batchId = UUID.fromString(originalReceipt.path("importBatchId").asText());
        long transactionId = originalReceipt.at("/transactions/0/transactionId").asLong();
        List<Map<String, Object>> originalItems = itemSnapshot(batchId);
        List<Map<String, Object>> originalImpacts = impactSnapshot(batchId);
        Map<String, Object> originalBatch = batchSnapshot(batchId);

        mockMvc.perform(put("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionRequest(
                                accountId, expenseCategoryId, "EXPENSE", new BigDecimal("200.00"), "CNY",
                                "edited expense", LocalDateTime.of(2026, 8, 2, 9, 0)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(balance(accountId)).isEqualByComparingTo("-200.00");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM transactions WHERE id = ?", transactionId))
                .containsEntry("amount", new BigDecimal("200.00"))
                .containsEntry("type", "EXPENSE")
                .containsEntry("account_id", accountId);
        assertThat(getReceipt(user, batchId)).isEqualTo(originalReceipt);

        deleteTransaction(user, transactionId);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE id = ?", Integer.class, transactionId)).isZero();
        assertThat(balance(accountId)).isEqualByComparingTo("0.00");
        assertThat(itemSnapshot(batchId)).isEqualTo(originalItems);
        assertThat(impactSnapshot(batchId)).isEqualTo(originalImpacts);
        assertThat(batchSnapshot(batchId)).isEqualTo(originalBatch);
        assertThat(batchResultDigest(batchId)).isEqualTo(originalReceipt.path("resultDigest").asText());
        assertThat(getReceipt(user, batchId)).isEqualTo(originalReceipt);
    }

    @Test
    void updateToAnotherAccountAndTypeThenDeleteReversesTheNewAccountEffect() throws Exception {
        TestUser user = registerUser("import-move-update-delete");
        long userId = userId(user);
        long importedAccountId = createAccount(userId, "Cash");
        long updatedAccountId = createAccount(userId, "Bank");
        long expenseCategoryId = createCategory(userId, "Food", "EXPENSE");
        long incomeCategoryId = createCategory(userId, "Income", "INCOME");
        var preview = preview(userId, importedAccountId, expenseCategoryId, null,
                "2026-08-01,expense,100.00,Cash,Food,imported expense");
        JsonNode confirmed = confirm(user, preview, "move-update-delete-" + UUID.randomUUID());
        JsonNode originalReceipt = confirmed.at("/data/receipt");
        UUID batchId = UUID.fromString(originalReceipt.path("importBatchId").asText());
        long transactionId = originalReceipt.at("/transactions/0/transactionId").asLong();
        List<Map<String, Object>> originalItems = itemSnapshot(batchId);
        List<Map<String, Object>> originalImpacts = impactSnapshot(batchId);
        Map<String, Object> originalBatch = batchSnapshot(batchId);

        mockMvc.perform(put("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionRequest(
                                updatedAccountId, incomeCategoryId, "INCOME", new BigDecimal("200.00"), "CNY",
                                "moved income", LocalDateTime.of(2026, 8, 2, 9, 0)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(balance(importedAccountId)).isEqualByComparingTo("0.00");
        assertThat(balance(updatedAccountId)).isEqualByComparingTo("200.00");
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM transactions WHERE id = ?", transactionId))
                .containsEntry("amount", new BigDecimal("200.00"))
                .containsEntry("type", "INCOME")
                .containsEntry("account_id", updatedAccountId);
        assertThat(getReceipt(user, batchId)).isEqualTo(originalReceipt);
        assertThat(itemSnapshot(batchId)).isEqualTo(originalItems);
        assertThat(impactSnapshot(batchId)).isEqualTo(originalImpacts);
        assertThat(batchSnapshot(batchId)).isEqualTo(originalBatch);

        deleteTransaction(user, transactionId);

        assertThat(balance(importedAccountId)).isEqualByComparingTo("0.00");
        assertThat(balance(updatedAccountId)).isEqualByComparingTo("0.00");
        assertThat(transactionCount(userId)).isZero();
        assertThat(itemSnapshot(batchId)).isEqualTo(originalItems);
        assertThat(impactSnapshot(batchId)).isEqualTo(originalImpacts);
        assertThat(batchSnapshot(batchId)).isEqualTo(originalBatch);
        assertThat(getReceipt(user, batchId)).isEqualTo(originalReceipt);
    }

    @Test
    void ordinaryManualTransactionDeleteRemainsSuccessfulAndRepeatedDeleteHasNoExtraEffect() throws Exception {
        TestUser user = registerUser("manual-delete");
        long userId = userId(user);
        long accountId = createAccount(userId);
        long expenseCategoryId = createCategory(userId, "Food", "EXPENSE");
        var created = transactionService.create(userId, null, new TransactionRequest(
                accountId, expenseCategoryId, "EXPENSE", new BigDecimal("40.00"), "CNY",
                "manual expense", LocalDateTime.of(2026, 8, 4, 9, 0)));
        assertThat(balance(accountId)).isEqualByComparingTo("-40.00");

        deleteTransaction(user, created.id());

        assertThat(balance(accountId)).isEqualByComparingTo("0.00");
        assertThat(transactionCount(userId)).isZero();
        mockMvc.perform(delete("/api/v1/transactions/{id}", created.id())
                        .header("Authorization", bearer(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/v1/transactions/{id}", created.id())
                        .header("Authorization", bearer(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        assertThat(balance(accountId)).isEqualByComparingTo("0.00");
        assertThat(transactionCount(userId)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"25.00, -75.00", "-25.00, -125.00"})
    void incomeAndPositiveOrNegativeAdjustmentCanBeDeletedIndividuallyFromAMultirowBatch(
            String adjustmentAmount, String cashBalanceBeforeAdjustmentDelete) throws Exception {
        TestUser owner = registerUser("import-multi-owner");
        TestUser foreign = registerUser("import-multi-foreign");
        long ownerId = userId(owner);
        long accountId = createAccount(ownerId, "Cash");
        long savingsAccountId = createAccount(ownerId, "Savings");
        long expenseCategoryId = createCategory(ownerId, "Food", "EXPENSE");
        long incomeCategoryId = createCategory(ownerId, "Income", "INCOME");
        var preview = preview(ownerId, Map.of("Cash", accountId, "Savings", savingsAccountId),
                expenseCategoryId, incomeCategoryId,
                "2026-08-01,expense,100.00,Cash,Food,expense row\n"
                        + "2026-08-02,income,250.00,Savings,Income,income row\n"
                        + "2026-08-03,adjustment," + adjustmentAmount + ",Cash,Food,adjustment row");
        JsonNode confirmed = confirm(owner, preview, "multi-delete-" + UUID.randomUUID());
        JsonNode originalReceipt = confirmed.at("/data/receipt");
        UUID batchId = UUID.fromString(originalReceipt.path("importBatchId").asText());
        long expenseId = originalReceipt.at("/transactions/0/transactionId").asLong();
        long incomeId = originalReceipt.at("/transactions/1/transactionId").asLong();
        long adjustmentId = originalReceipt.at("/transactions/2/transactionId").asLong();
        List<Map<String, Object>> itemsBeforeDelete = itemSnapshot(batchId);
        List<Map<String, Object>> impactsBeforeDelete = impactSnapshot(batchId);
        Map<String, Object> batchBeforeDelete = batchSnapshot(batchId);
        String resultDigest = originalReceipt.path("resultDigest").asText();

        List<Map<String, Object>> ownerTransactions = transactionSnapshot(ownerId);

        assertThat(balance(accountId)).isEqualByComparingTo(cashBalanceBeforeAdjustmentDelete);
        assertThat(balance(savingsAccountId)).isEqualByComparingTo("250.00");
        mockMvc.perform(delete("/api/v1/transactions/{id}", incomeId)
                        .header("Authorization", bearer(foreign)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/v1/imports/transactions/batches/{batchId}", batchId)
                        .header("Authorization", bearer(foreign)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        assertThat(balance(accountId)).isEqualByComparingTo(cashBalanceBeforeAdjustmentDelete);
        assertThat(balance(savingsAccountId)).isEqualByComparingTo("250.00");
        assertThat(transactionCount(ownerId)).isEqualTo(3);
        assertThat(transactionSnapshot(ownerId)).isEqualTo(ownerTransactions);
        assertThat(itemSnapshot(batchId)).isEqualTo(itemsBeforeDelete);
        assertThat(impactSnapshot(batchId)).isEqualTo(impactsBeforeDelete);
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBeforeDelete);

        deleteTransaction(owner, incomeId);
        assertThat(balance(accountId)).isEqualByComparingTo(cashBalanceBeforeAdjustmentDelete);
        assertThat(balance(savingsAccountId)).isEqualByComparingTo("0.00");
        assertThat(transactionCount(ownerId)).isEqualTo(2);
        assertThat(transactionSnapshot(ownerId)).isEqualTo(ownerTransactions.stream()
                .filter(row -> !row.get("id").equals(incomeId)).toList());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE id = ?", Integer.class, expenseId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE id = ?", Integer.class, adjustmentId)).isEqualTo(1);
        assertThat(itemSnapshot(batchId)).isEqualTo(itemsBeforeDelete);
        assertThat(impactSnapshot(batchId)).isEqualTo(impactsBeforeDelete);
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBeforeDelete);
        assertThat(getReceipt(owner, batchId)).isEqualTo(originalReceipt);

        deleteTransaction(owner, adjustmentId);
        assertThat(balance(accountId)).isEqualByComparingTo("-100.00");
        assertThat(balance(savingsAccountId)).isEqualByComparingTo("0.00");
        assertThat(transactionCount(ownerId)).isEqualTo(1);
        assertThat(transactionSnapshot(ownerId)).isEqualTo(ownerTransactions.stream()
                .filter(row -> row.get("id").equals(expenseId)).toList());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE id = ?", Integer.class, expenseId)).isEqualTo(1);
        assertThat(itemSnapshot(batchId)).isEqualTo(itemsBeforeDelete);
        assertThat(itemSnapshot(batchId)).hasSize(3);
        assertThat(impactSnapshot(batchId)).isEqualTo(impactsBeforeDelete);
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBeforeDelete);
        assertThat(batchResultDigest(batchId)).isEqualTo(resultDigest);
        assertThat(getReceipt(owner, batchId)).isEqualTo(originalReceipt);
    }

    @ParameterizedTest
    @CsvSource({"INCOME, 100.00, 100.00", "EXPENSE, 100.00, -100.00",
            "ADJUSTMENT, 25.00, 25.00", "ADJUSTMENT, -25.00, -25.00"})
    void deleteReversesOnlyTargetEffectAndPreservesLaterBalanceChanges(
            String type, String amount, String effect) throws Exception {
        TestUser user = registerUser("later-balance");
        long userId = userId(user);
        long accountId = createAccount(userId);
        long expenseCategoryId = createCategory(userId, "Food", "EXPENSE");
        long incomeCategoryId = createCategory(userId, "Income", "INCOME");
        String sourceCategory = "INCOME".equals(type) ? "Income" : "Food";
        var preview = preview(userId, accountId, expenseCategoryId, incomeCategoryId,
                "2026-08-01," + type.toLowerCase(java.util.Locale.ROOT) + "," + amount
                        + ",Cash," + sourceCategory + ",target effect");
        JsonNode receipt = confirm(user, preview, "later-balance-" + UUID.randomUUID()).at("/data/receipt");
        UUID batchId = UUID.fromString(receipt.path("importBatchId").asText());
        long targetId = receipt.at("/transactions/0/transactionId").asLong();
        var later = transactionService.create(userId, null, new TransactionRequest(
                accountId, incomeCategoryId, "INCOME", new BigDecimal("37.00"), "CNY",
                "later balance change", LocalDateTime.of(2026, 8, 5, 9, 0)));
        Map<String, Object> laterBefore = jdbcTemplate.queryForMap("SELECT * FROM transactions WHERE id = ?", later.id());
        Map<String, Object> batchBefore = batchSnapshot(batchId);
        var itemsBefore = itemSnapshot(batchId);
        var impactsBefore = impactSnapshot(batchId);
        assertThat(balance(accountId)).isEqualByComparingTo(new BigDecimal(effect).add(new BigDecimal("37.00")));

        deleteTransaction(user, targetId);

        assertThat(balance(accountId)).isEqualByComparingTo("37.00");
        assertThat(transactionSnapshot(userId)).containsExactly(laterBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE id = ?", Integer.class, targetId)).isZero();
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBefore);
        assertThat(itemSnapshot(batchId)).isEqualTo(itemsBefore);
        assertThat(impactSnapshot(batchId)).isEqualTo(impactsBefore);
        assertThat(getReceipt(user, batchId)).isEqualTo(receipt);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactDuplicateRemainsRejectedAfterPartialOrCompleteDeletion(boolean deleteAll) throws Exception {
        TestUser user = registerUser("deleted-duplicate");
        long userId = userId(user);
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        String rows = "2026-08-01,expense,100.00,Cash,Food,first row\n"
                + "2026-08-02,expense,50.00,Cash,Food,second row";
        var originalPreview = preview(userId, accountId, categoryId, null, rows);
        JsonNode receipt = confirm(user, originalPreview, "original-duplicate-" + UUID.randomUUID()).at("/data/receipt");
        UUID batchId = UUID.fromString(receipt.path("importBatchId").asText());
        deleteTransaction(user, receipt.at("/transactions/0/transactionId").asLong());
        if (deleteAll) deleteTransaction(user, receipt.at("/transactions/1/transactionId").asLong());
        var transactionsBefore = transactionSnapshot(userId);
        BigDecimal balanceBefore = balance(accountId);
        var batchBefore = batchSnapshot(batchId);
        var itemsBefore = itemSnapshot(batchId);
        var impactsBefore = impactSnapshot(batchId);
        var duplicatePreview = preview(userId, accountId, categoryId, null, rows);
        assertThat(duplicatePreview.importSessionId()).isNotEqualTo(originalPreview.importSessionId());
        List<String> warnings = duplicatePreview.rows().stream().flatMap(row -> row.warnings().stream())
                .map(warning -> warning.id()).distinct().sorted().toList();

        mockMvc.perform(post("/api/v1/imports/transactions/{sessionId}/confirm", duplicatePreview.importSessionId())
                        .header("Authorization", bearer(user))
                        .header("Idempotency-Key", "new-duplicate-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionImportConfirmRequest(
                                duplicatePreview.previewToken(), warnings))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IMPORT_EXACT_DUPLICATE"));

        assertThat(transactionSnapshot(userId)).isEqualTo(transactionsBefore);
        assertThat(balance(accountId)).isEqualByComparingTo(balanceBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batches WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBefore);
        assertThat(itemSnapshot(batchId)).isEqualTo(itemsBefore);
        assertThat(impactSnapshot(batchId)).isEqualTo(impactsBefore);
        assertThat(getReceipt(user, batchId)).isEqualTo(receipt);
    }

    @Test
    void deleteBalanceUpdateFailureRollsBackTransactionBalanceAndReceiptEvidence() throws Exception {
        TestUser user = registerUser("delete-rollback");
        long userId = userId(user);
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = preview(userId, accountId, categoryId, null,
                "2026-08-01,expense,100.00,Cash,Food,rollback target");
        JsonNode receipt = confirm(user, preview, "delete-rollback-" + UUID.randomUUID()).at("/data/receipt");
        UUID batchId = UUID.fromString(receipt.path("importBatchId").asText());
        long targetId = receipt.at("/transactions/0/transactionId").asLong();
        var transactionsBefore = transactionSnapshot(userId);
        var accountBefore = jdbcTemplate.queryForMap("SELECT * FROM accounts WHERE id = ?", accountId);
        var batchBefore = batchSnapshot(batchId);
        var itemsBefore = itemSnapshot(batchId);
        var impactsBefore = impactSnapshot(batchId);
        jdbcTemplate.execute("""
                CREATE FUNCTION test_c1_delete_balance_fault() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM public.transactions WHERE id = %d) THEN
                        RAISE EXCEPTION 'C1 test expected DELETE before the balance UPDATE';
                    END IF;
                    RAISE EXCEPTION 'C1 injected DELETE balance UPDATE failure' USING ERRCODE = '23514';
                END;
                $$
                """.formatted(targetId));
        try {
            jdbcTemplate.execute("""
                    CREATE TRIGGER test_c1_delete_balance_fault BEFORE UPDATE OF balance ON accounts
                    FOR EACH ROW EXECUTE FUNCTION test_c1_delete_balance_fault()
                    """);
            MvcResult failure = mockMvc.perform(delete("/api/v1/transactions/{id}", targetId)
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(500)).andReturn();
            assertThat(failure.getResolvedException()).isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("C1 injected DELETE balance UPDATE failure");
            assertThat(((SQLException) ((DataIntegrityViolationException) failure.getResolvedException())
                    .getMostSpecificCause()).getSQLState()).isEqualTo("23514");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_c1_delete_balance_fault ON accounts");
            jdbcTemplate.execute("DROP FUNCTION test_c1_delete_balance_fault()");
        }
        assertThat(transactionSnapshot(userId)).isEqualTo(transactionsBefore);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM accounts WHERE id = ?", accountId)).isEqualTo(accountBefore);
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBefore);
        assertThat(itemSnapshot(batchId)).isEqualTo(itemsBefore);
        assertThat(impactSnapshot(batchId)).isEqualTo(impactsBefore);
        assertThat(getReceipt(user, batchId)).isEqualTo(receipt);
    }

    @Test
    void immutableReceiptTriggersRejectAllMutationsBeforeAndAfterTransactionDeletion() throws Exception {
        TestUser user = registerUser("immutable-delete");
        long userId = userId(user);
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId, "Food", "EXPENSE");
        var preview = preview(userId, accountId, categoryId, null,
                "2026-08-01,expense,100.00,Cash,Food,immutable target");
        JsonNode receipt = confirm(user, preview, "immutable-delete-" + UUID.randomUUID()).at("/data/receipt");
        UUID batchId = UUID.fromString(receipt.path("importBatchId").asText());
        var batchBefore = batchSnapshot(batchId);
        var itemsBefore = itemSnapshot(batchId);
        var impactsBefore = impactSnapshot(batchId);
        assertImmutableReceiptMutationsRejected(batchId);
        deleteTransaction(user, receipt.at("/transactions/0/transactionId").asLong());
        assertImmutableReceiptMutationsRejected(batchId);
        assertThat(batchSnapshot(batchId)).isEqualTo(batchBefore);
        assertThat(itemSnapshot(batchId)).isEqualTo(itemsBefore);
        assertThat(impactSnapshot(batchId)).isEqualTo(impactsBefore);
        assertThat(getReceipt(user, batchId)).isEqualTo(receipt);
    }

    private void assertImmutableReceiptMutationsRejected(UUID batchId) {
        for (String table : List.of("transaction_import_batches", "transaction_import_items", "transaction_import_batch_account_impacts")) {
            String key = "transaction_import_batches".equals(table) ? "id" : "batch_id";
            String field = switch (table) {
                case "transaction_import_batches" -> "warning_count";
                case "transaction_import_items" -> "warning_codes";
                default -> "delta";
            };
            // No-op assignments are otherwise valid: rejection must come from the immutable trigger.
            for (String sql : List.of("UPDATE " + table + " SET " + field + " = " + field + " WHERE " + key + " = ?",
                    "DELETE FROM " + table + " WHERE " + key + " = ?")) {
                assertThatThrownBy(() -> jdbcTemplate.update(sql, batchId))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .rootCause().isInstanceOf(SQLException.class)
                        .hasMessageContaining("transaction import receipt data is immutable")
                        .satisfies(cause -> assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514"));
            }
        }
    }

    private JsonNode confirm(TestUser user,
                             com.financeos.module.importing.dto.TransactionImportPreviewResponse preview,
                             String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/imports/transactions/{sessionId}/confirm", preview.importSessionId())
                        .header("Authorization", bearer(user))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionImportConfirmRequest(preview.previewToken(), List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void deleteTransaction(TestUser user, long transactionId) throws Exception {
        mockMvc.perform(delete("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private JsonNode getReceipt(TestUser user, UUID batchId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/imports/transactions/batches/{batchId}", batchId)
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private com.financeos.module.importing.dto.TransactionImportPreviewResponse preview(long userId,
                                                                                           long accountId,
                                                                                           long expenseCategoryId,
                                                                                           Long incomeCategoryId,
                                                                                           String rows) {
        return preview(userId, Map.of("Cash", accountId), expenseCategoryId, incomeCategoryId, rows);
    }

    private com.financeos.module.importing.dto.TransactionImportPreviewResponse preview(long userId,
                                                                                           Map<String, Long> accountMappings,
                                                                                           long expenseCategoryId,
                                                                                           Long incomeCategoryId,
                                                                                           String rows) {
        Map<String, String> columns = Map.of("date", "date", "type", "type", "amount", "amount",
                "account", "account", "category", "category", "description", "description");
        Map<String, String> types = Map.of("expense", "EXPENSE", "income", "INCOME", "adjustment", "ADJUSTMENT");
        Map<String, Long> categories = incomeCategoryId == null
                ? Map.of("Food", expenseCategoryId)
                : Map.of("Food", expenseCategoryId, "Income", incomeCategoryId);
        TransactionImportMapping mapping = new TransactionImportMapping(columns, types, accountMappings, categories);
        String content = "date,type,amount,account,category,description\n" + rows;
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
        return previewService.create(userId, file, new TransactionImportPreviewRequest(null, mapping));
    }

    private TestUser registerUser(String prefix) throws Exception {
        String username = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mockMvc.perform(post("/api/v1/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, username + "@example.com", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        MvcResult login = mockMvc.perform(post("/api/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();
        return new TestUser(username, objectMapper.readTree(login.getResponse().getContentAsByteArray())
                .at("/data/token").asText());
    }

    private long userId(TestUser user) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, user.username());
    }

    private long createAccount(long userId) {
        return createAccount(userId, "Cash");
    }

    private long createAccount(long userId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance, status)
                VALUES (?, ?, 'CASH', 'CNY', 0, 'ACTIVE') RETURNING id
                """, Long.class, userId, name);
    }

    private long createCategory(long userId, String name, String type) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO categories (user_id, name, type, is_system)
                VALUES (?, ?, ?, false) RETURNING id
                """, Long.class, userId, name, type);
    }

    private BigDecimal balance(long accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    private int transactionCount(long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId);
    }

    private List<Map<String, Object>> itemSnapshot(UUID batchId) {
        return jdbcTemplate.queryForList("""
                SELECT *
                FROM transaction_import_items WHERE batch_id = ? ORDER BY source_row_number
                """, batchId);
    }

    private List<Map<String, Object>> impactSnapshot(UUID batchId) {
        return jdbcTemplate.queryForList("""
                SELECT *
                FROM transaction_import_batch_account_impacts WHERE batch_id = ? ORDER BY account_id
                """, batchId);
    }

    private String batchResultDigest(UUID batchId) {
        return jdbcTemplate.queryForObject("SELECT result_digest FROM transaction_import_batches WHERE id = ?", String.class, batchId);
    }

    private List<Map<String, Object>> transactionSnapshot(long userId) {
        return jdbcTemplate.queryForList("SELECT * FROM transactions WHERE user_id = ? ORDER BY id", userId);
    }

    private Map<String, Object> batchSnapshot(UUID batchId) {
        return jdbcTemplate.queryForMap("SELECT * FROM transaction_import_batches WHERE id = ?", batchId);
    }

    private String bearer(TestUser user) {
        return "Bearer " + user.token();
    }

    private record TestUser(String username, String token) { }
}
