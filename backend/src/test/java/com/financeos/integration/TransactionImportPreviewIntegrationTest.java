package com.financeos.integration;

import com.financeos.common.BusinessException;
import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.preview.TransactionImportPreviewService;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionImportPreviewIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionImportPreviewService previewService;

    @Autowired
    private TransactionImportSessionMapper sessionMapper;

    @Autowired
    private TemporaryImportFileStorage temporaryImportFileStorage;

    @Autowired
    @Qualifier("transactionImportPreviewPlanStorage")
    private TemporaryImportFileStorage planStorage;

    @Test
    void expiredPreviewSessionDeletesItsRawFileAndAuthoritativePlanOnAccess() {
        long userId = createUser("expired-preview@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId);

        var response = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));
        TransactionImportSession session = sessionMapper.findByIdAndUserId(response.importSessionId(), userId);
        jdbcTemplate.update("UPDATE transaction_import_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", session.getId());

        assertThatThrownBy(() -> previewService.getRows(userId, session.getId(), 1, 100))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(409);

        assertThatThrownBy(() -> previewService.getRows(userId, session.getId(), 1, 100))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(409);

        assertThat(temporaryImportFileStorage.exists(session.getTemporaryStorageReference())).isFalse();
        assertThat(planStorage.exists(session.getPlanStorageReference())).isFalse();
    }

    @Test
    void previewScenariosNeverCreateFinancialFactsOrMutateReferenceData() {
        long userId = createUser("read-only-preview@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId);
        Snapshot before = snapshot(accountId);

        var valid = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));
        var invalid = previewService.create(userId, csv("2026-08-01,expense,not-a-number,Cash,Food,lunch"), request(accountId, categoryId));
        var duplicate = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch\n2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));

        jdbcTemplate.update("""
                INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, description, transacted_at)
                VALUES (?, ?, ?, 'EXPENSE', 10.00, 'CNY', 'lunch', '2026-08-01T00:00:00')
                """, userId, accountId, categoryId);
        var databaseDuplicate = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));

        assertThat(valid.summary().errorRows()).isZero();
        assertThat(invalid.rows().getFirst().errors()).isNotEmpty();
        assertThat(duplicate.rows()).allSatisfy(row -> assertThat(row.warnings()).isNotEmpty());
        assertThat(databaseDuplicate.rows().getFirst().warnings()).isNotEmpty();
        assertThat(snapshot(accountId)).isEqualTo(before.withTransactions(before.transactions() + 1));
    }

    @Test
    void crossUserResourcesCannotBeMappedAndDoNotAffectDuplicateWarnings() {
        long userA = createUser("preview-owner-a@example.com");
        long userB = createUser("preview-owner-b@example.com");
        long accountA = createAccount(userA);
        long categoryA = createCategory(userA);
        long accountB = createAccount(userB);
        long categoryB = createCategory(userB);

        jdbcTemplate.update("""
                INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, description, transacted_at)
                VALUES (?, ?, ?, 'EXPENSE', 10.00, 'CNY', 'lunch', '2026-08-01T00:00:00')
                """, userB, accountB, categoryB);

        var foreignMapping = previewService.create(userA, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountB, categoryB));
        var ownResources = previewService.create(userA, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountA, categoryA));

        assertThat(foreignMapping.rows().getFirst().errors()).extracting("code")
                .contains("ACCOUNT_NOT_FOUND", "CATEGORY_NOT_MAPPED");
        assertThat(ownResources.rows().getFirst().warnings()).isEmpty();
    }

    @Test
    void differentSourceAliasesMappedToTheSameResourcesAreInFileProbableDuplicates() {
        long userId = createUser("alias-duplicate-preview@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId);
        var mapping = new TransactionImportMapping(
                Map.of("date", "date", "type", "type", "amount", "amount", "account", "account",
                        "category", "category", "description", "description"),
                Map.of("expense", "EXPENSE"), Map.of("Cash", accountId, "Salary card", accountId),
                Map.of("Food", categoryId, "Meals", categoryId));
        var file = new MockMultipartFile("file", "statement.csv", "text/csv", ("date,type,amount,account,category,description\n"
                + "2026-08-01,expense,10.00,Cash,Food,lunch\n"
                + "2026-08-01,expense,10.00,Salary card,Meals,lunch\n").getBytes(StandardCharsets.UTF_8));

        var preview = previewService.create(userId, file, new TransactionImportPreviewRequest(null, mapping));

        assertThat(preview.rows()).allSatisfy(row -> assertThat(row.warnings()).extracting("code").contains("IN_FILE_PROBABLE"));
    }

    @Test
    void changingTheMappingReplacesTheAuthoritativePlanAndItsDigests() {
        long userId = createUser("binding-preview@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId);
        var first = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));

        var adjustmentMapping = new TransactionImportMapping(
                Map.of("date", "date", "type", "type", "amount", "amount", "account", "account",
                        "category", "category", "description", "description"),
                Map.of("expense", "ADJUSTMENT"), Map.of("Cash", accountId), Map.of("Food", categoryId));
        var updated = previewService.updateMapping(userId, first.importSessionId(), adjustmentMapping);

        assertThat(updated.revision()).isEqualTo(first.revision() + 1);
        assertThat(updated.mappingDigest()).isNotEqualTo(first.mappingDigest());
        assertThat(updated.normalizedRowsDigest()).isNotEqualTo(first.normalizedRowsDigest());
        assertThat(updated.rows().getFirst().normalizedValues().get("type")).isEqualTo("ADJUSTMENT");
    }

    @Test
    void changedSourceRowsProduceDifferentFileAndAuthoritativePlanDigests() {
        long userId = createUser("row-binding-preview@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId);

        var first = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));
        var changed = previewService.create(userId, csv("2026-08-02,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));

        assertThat(changed.fileDigest()).isNotEqualTo(first.fileDigest());
        assertThat(changed.normalizedRowsDigest()).isNotEqualTo(first.normalizedRowsDigest());
    }

    @Test
    void incompleteRemappingInvalidatesThePreviousAuthoritativePlan() {
        long userId = createUser("incomplete-remap-preview@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId);
        var first = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));
        TransactionImportSession before = sessionMapper.findByIdAndUserId(first.importSessionId(), userId);
        var incomplete = new TransactionImportMapping(Map.of("date", "date"), Map.of(), Map.of(), Map.of());

        var response = previewService.updateMapping(userId, first.importSessionId(), incomplete);
        TransactionImportSession after = sessionMapper.findByIdAndUserId(first.importSessionId(), userId);

        assertThat(response.sessionStatus()).isEqualTo("MAPPING_REQUIRED");
        assertThat(after.getRevision()).isEqualTo(before.getRevision() + 1);
        assertThat(after.getMappingDigest()).isNull();
        assertThat(after.getNormalizedRowsDigest()).isNull();
        assertThat(after.getPlanStorageReference()).isNull();
        assertThat(planStorage.exists(before.getPlanStorageReference())).isFalse();
        assertThatThrownBy(() -> previewService.getRows(userId, first.importSessionId(), 1, 100))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(409);
    }

    @Test
    void cancellingAnActivePreviewSessionDeletesOnlyItsPrivatePayloads() {
        long userId = createUser("cancel-preview@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId);
        var response = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));
        TransactionImportSession session = sessionMapper.findByIdAndUserId(response.importSessionId(), userId);

        previewService.cancel(userId, session.getId());

        assertThat(sessionMapper.findByIdAndUserId(session.getId(), userId).getStatus()).isEqualTo("CANCELLED");
        assertThat(temporaryImportFileStorage.exists(session.getTemporaryStorageReference())).isFalse();
        assertThat(planStorage.exists(session.getPlanStorageReference())).isFalse();
    }

    @Test
    void cancellingAnExpiredPreviewSessionFailsClosedAndDeletesItsPrivatePayloads() {
        long userId = createUser("expired-cancel-preview@example.com");
        long accountId = createAccount(userId);
        long categoryId = createCategory(userId);
        var response = previewService.create(userId, csv("2026-08-01,expense,10.00,Cash,Food,lunch"), request(accountId, categoryId));
        TransactionImportSession session = sessionMapper.findByIdAndUserId(response.importSessionId(), userId);
        jdbcTemplate.update("UPDATE transaction_import_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", session.getId());

        assertThatThrownBy(() -> previewService.cancel(userId, session.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(409);

        assertThat(sessionMapper.findByIdAndUserId(session.getId(), userId).getStatus()).isEqualTo("EXPIRED");
        assertThat(temporaryImportFileStorage.exists(session.getTemporaryStorageReference())).isFalse();
        assertThat(planStorage.exists(session.getPlanStorageReference())).isFalse();
    }

    private long createUser(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, password_hash, email) VALUES (?, 'password', ?)
                RETURNING id
                """, Long.class, email, email);
    }

    private long createAccount(long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance, status)
                VALUES (?, 'Cash', 'CASH', 'CNY', 0, 'ACTIVE') RETURNING id
                """, Long.class, userId);
    }

    private long createCategory(long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO categories (user_id, name, type, is_system)
                VALUES (?, 'Food', 'EXPENSE', false) RETURNING id
                """, Long.class, userId);
    }

    private MockMultipartFile csv(String rows) {
        String content = "date,type,amount,account,category,description\n" + rows + "\n";
        return new MockMultipartFile("file", "statement.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private Snapshot snapshot(long accountId) {
        return new Snapshot(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class),
                jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Integer.class));
    }

    private TransactionImportPreviewRequest request(long accountId, long categoryId) {
        return new TransactionImportPreviewRequest(null, new TransactionImportMapping(
                Map.of("date", "date", "type", "type", "amount", "amount", "account", "account",
                        "category", "category", "description", "description"),
                Map.of("expense", "EXPENSE"), Map.of("Cash", accountId), Map.of("Food", categoryId)));
    }

    private record Snapshot(int transactions, BigDecimal balance, int accounts, int categories) {
        private Snapshot withTransactions(int count) {
            return new Snapshot(count, balance, accounts, categories);
        }
    }
}
