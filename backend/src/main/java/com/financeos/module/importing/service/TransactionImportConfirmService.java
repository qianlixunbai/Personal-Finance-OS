package com.financeos.module.importing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountBalanceMutation;
import com.financeos.module.account.service.AccountBalanceService;
import com.financeos.module.account.service.LockedAccounts;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.importing.dto.TransactionImportConfirmRequest;
import com.financeos.module.importing.dto.TransactionImportConfirmResponse;
import com.financeos.module.importing.dto.TransactionImportPreviewRow;
import com.financeos.module.importing.dto.TransactionImportReceipt;
import com.financeos.module.importing.dto.TransactionImportValidationMessage;
import com.financeos.module.importing.entity.TransactionImportBatch;
import com.financeos.module.importing.entity.TransactionImportBatchAccountImpact;
import com.financeos.module.importing.entity.TransactionImportItem;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportBatchAccountImpactMapper;
import com.financeos.module.importing.mapper.TransactionImportBatchMapper;
import com.financeos.module.importing.mapper.TransactionImportItemMapper;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.preview.TransactionImportPreviewPlan;
import com.financeos.module.importing.preview.TransactionImportPreviewService;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.dto.TransactionDuplicateProbe;
import com.financeos.module.ledger.mapper.TransactionMapper;
import com.financeos.module.ledger.service.TransactionWriteRules;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionImportConfirmService {
    private final TransactionImportSessionMapper sessionMapper;
    private final TransactionImportBatchMapper batchMapper;
    private final TransactionImportItemMapper itemMapper;
    private final TransactionImportBatchAccountImpactMapper impactMapper;
    private final TransactionImportPreviewService previewService;
    private final TransactionImportPreviewTokenService tokenService;
    private final AccountBalanceService accountBalanceService;
    private final CategoryMapper categoryMapper;
    private final TransactionMapper transactionMapper;
    private final TransactionWriteRules writeRules;
    private final TransactionImportPreviewCleanupService cleanupService;
    private final TransactionImportConfirmTransactionObserver transactionObserver;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TransactionImportConfirmService(TransactionImportSessionMapper sessionMapper, TransactionImportBatchMapper batchMapper,
                                           TransactionImportItemMapper itemMapper, TransactionImportBatchAccountImpactMapper impactMapper,
                                           TransactionImportPreviewService previewService, TransactionImportPreviewTokenService tokenService,
                                           AccountBalanceService accountBalanceService, CategoryMapper categoryMapper,
                                           TransactionMapper transactionMapper, TransactionWriteRules writeRules,
                                           TransactionImportPreviewCleanupService cleanupService,
                                           TransactionImportConfirmTransactionObserver transactionObserver,
                                           @Qualifier("businessClock") Clock clock, PlatformTransactionManager transactionManager) {
        this.sessionMapper = sessionMapper; this.batchMapper = batchMapper; this.itemMapper = itemMapper; this.impactMapper = impactMapper;
        this.previewService = previewService; this.tokenService = tokenService; this.accountBalanceService = accountBalanceService;
        this.categoryMapper = categoryMapper; this.transactionMapper = transactionMapper; this.writeRules = writeRules; this.cleanupService = cleanupService; this.transactionObserver = transactionObserver; this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager); this.transactionTemplate.setTimeout(60);
    }

    public TransactionImportConfirmResponse confirm(Long userId, UUID sessionId, String idempotencyKey, TransactionImportConfirmRequest request) {
        validateRequest(idempotencyKey, request);
        TransactionImportSession preflightSession = sessionMapper.findByIdAndUserId(sessionId, userId);
        if (preflightSession == null) throw new BusinessException(404, "IMPORT_SESSION_NOT_FOUND");
        List<String> acknowledged = canonicalWarnings(request.acknowledgedWarningIds());
        String requestHash = requestHash(userId, preflightSession, acknowledged);

        TransactionImportBatch existingForSession = batchMapper.findConfirmedByUserIdAndSessionId(userId, sessionId);
        if (existingForSession != null) return replayOrBatchConflict(existingForSession, idempotencyKey, requestHash);
        TransactionImportBatch existingForKey = batchMapper.findConfirmedByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existingForKey != null) return replayOrIdempotencyConflict(existingForKey, requestHash);
        tokenService.verify(request.previewToken(), userId, preflightSession);
        if ("CANCELLED".equals(preflightSession.getStatus())) throw new BusinessException(409, "IMPORT_SESSION_CANCELLED");
        Instant preflightNow = clock.instant();
        if (!preflightSession.getExpiresAt().isAfter(preflightNow)) {
            sessionMapper.markExpired(sessionId, userId, preflightNow);
            throw new BusinessException(409, "IMPORT_PREVIEW_EXPIRED");
        }
        if (!"PREVIEW_READY".equals(preflightSession.getStatus())) throw new BusinessException(409, "IMPORT_PREVIEW_STALE");
        TransactionImportBatch exactDuplicate = findExactDuplicate(userId, preflightSession);
        if (exactDuplicate != null) throw new BusinessException(409, "IMPORT_EXACT_DUPLICATE");
        if (preflightSession.getPlanStorageReference() == null) throw new BusinessException(409, "IMPORT_PREVIEW_UNAVAILABLE");
        TransactionImportPreviewPlan plan = previewService.loadFrozenPlan(preflightSession.getPlanStorageReference());
        validatePlan(plan, acknowledged);
        tokenService.verify(request.previewToken(), userId, preflightSession, acknowledged);
        TransactionImportConfirmResponse response;
        long[] transactionStartedNanos = {0L};
        try {
            response = transactionTemplate.execute(status -> {
                transactionStartedNanos[0] = System.nanoTime();
                return confirmInTransaction(userId, sessionId, idempotencyKey, requestHash, request, plan, acknowledged, preflightSession);
            });
        } catch (DataIntegrityViolationException exception) {
            response = recoverKnownUnique(userId, sessionId, idempotencyKey, requestHash, exception);
        } catch (DataAccessException exception) {
            if (isPostgresLockConflict(exception)) throw new BusinessException(409, "IMPORT_LOCK_CONFLICT");
            throw exception;
        }
        if (!response.idempotentReplay()) {
            transactionObserver.committed(sessionId, System.nanoTime() - transactionStartedNanos[0]);
        }
        if (!response.idempotentReplay()) cleanupService.cleanupCommittedPayloads(preflightSession);
        return response;
    }

    private TransactionImportConfirmResponse confirmInTransaction(Long userId, UUID sessionId, String key, String requestHash,
                                                                    TransactionImportConfirmRequest request, TransactionImportPreviewPlan plan,
                                                                    List<String> acknowledged, TransactionImportSession preflight) {
        accountBalanceService.configureLockTimeoutForCurrentTransaction();
        TransactionImportSession session = sessionMapper.findByIdAndUserIdForUpdate(sessionId, userId);
        if (session == null) throw new BusinessException(404, "IMPORT_SESSION_NOT_FOUND");
        transactionObserver.sessionLockAcquired(sessionId);
        TransactionImportBatch existing = batchMapper.findConfirmedByUserIdAndSessionId(userId, sessionId);
        if (existing != null) return replayOrBatchConflict(existing, key, requestHash);
        TransactionImportBatch existingKey = batchMapper.findConfirmedByUserIdAndIdempotencyKey(userId, key);
        if (existingKey != null) return replayOrIdempotencyConflict(existingKey, requestHash);
        verifyFrozenBinding(userId, session, preflight, request.previewToken(), acknowledged);
        if (findExactDuplicate(userId, session) != null) throw new BusinessException(409, "IMPORT_EXACT_DUPLICATE");
        Instant now = clock.instant();
        if (!session.getExpiresAt().isAfter(now)) {
            sessionMapper.markExpired(sessionId, userId, now);
            throw new BusinessException(409, "IMPORT_PREVIEW_EXPIRED");
        }
        if ("CANCELLED".equals(session.getStatus())) throw new BusinessException(409, "IMPORT_SESSION_CANCELLED");
        if (!"PREVIEW_READY".equals(session.getStatus())) throw new BusinessException(409, "IMPORT_PREVIEW_STALE");

        List<Long> accountIds = plan.rows().stream().map(row -> plan.mapping().accountMappings().get(row.normalizedValues().get("account"))).distinct().sorted().toList();
        LockedAccounts lockedAccounts = lockCurrentOwnedAccounts(userId, accountIds);
        transactionObserver.accountLocksAcquired(sessionId);
        verifyDuplicateEvidence(userId, session, plan);
        Map<Long, Category> categories = loadCurrentVisibleCategories(userId, plan);
        Map<Long, BigDecimal> balanceBefore = lockedAccounts.accounts().stream().collect(Collectors.toMap(Account::getId, Account::getBalance));
        List<TransactionImportReceipt.TransactionReference> references = new ArrayList<>();
        Map<Long, BigDecimal> deltas = new TreeMap<>();
        Map<Long, Integer> rowCounts = new TreeMap<>();
        List<TransactionImportItem> items = new ArrayList<>();
        for (TransactionImportPreviewRow row : plan.rows().stream().sorted(Comparator.comparingInt(TransactionImportPreviewRow::rowNumber)).toList()) {
            Long accountId = plan.mapping().accountMappings().get(row.normalizedValues().get("account"));
            Long categoryId = plan.mapping().categoryMappings().get(row.normalizedValues().get("category"));
            Account account = lockedAccounts.account(accountId);
            Category category = categories.get(categoryId);
            BigDecimal amount = new BigDecimal(row.normalizedValues().get("amount"));
            String type = row.normalizedValues().get("type");
            writeRules.validate(type, amount, row.normalizedValues().get("currency"), row.normalizedValues().get("description"), account, category, userId);
            Transaction transaction = new Transaction(); transaction.setUserId(userId); transaction.setAccountId(accountId); transaction.setCategoryId(categoryId);
            transaction.setType(type); transaction.setAmount(amount); transaction.setCurrency(row.normalizedValues().get("currency"));
            transaction.setDescription(row.normalizedValues().get("description")); transaction.setTransactedAt(LocalDateTime.parse(row.normalizedValues().get("transactedAt")));
            if (transactionMapper.insert(transaction) != 1) throw new IllegalStateException("transaction import insert did not affect exactly one row");
            references.add(new TransactionImportReceipt.TransactionReference(row.rowNumber(), transaction.getId()));
            deltas.merge(accountId, writeRules.balanceDelta(type, amount), BigDecimal::add); rowCounts.merge(accountId, 1, Integer::sum);
            TransactionImportItem item = new TransactionImportItem(); item.setUserId(userId); item.setBatchId(session.getPreallocatedBatchId()); item.setSourceRowNumber(row.rowNumber());
            item.setCanonicalRowFingerprint(sha256(row.normalizedValues().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("|"))));
            item.setWarningCodes(row.warnings().stream().map(warning -> warning.code()).sorted().collect(Collectors.joining(","))); item.setCreatedTransactionId(transaction.getId()); items.add(item);
        }
        Map<Long, BigDecimal> after = accountBalanceService.applyDeltas(lockedAccounts, deltas.entrySet().stream().map(entry -> new AccountBalanceMutation(entry.getKey(), entry.getValue(), true)).toList());
        // PostgreSQL timestamp precision is microseconds; persist and return the same immutable instant.
        Instant confirmedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<TransactionImportReceipt.AccountImpact> impacts = deltas.entrySet().stream().map(entry -> new TransactionImportReceipt.AccountImpact(entry.getKey(), rowCounts.get(entry.getKey()), balanceBefore.get(entry.getKey()), entry.getValue(), after.get(entry.getKey()))).toList();
        String resultDigest = resultDigest(session, references, impacts, confirmedAt);
        TransactionImportBatch batch = new TransactionImportBatch(); batch.setId(session.getPreallocatedBatchId()); batch.setUserId(userId); batch.setSessionId(sessionId);
        batch.setOriginalFileName(session.getOriginalFileName()); batch.setFileDigest(session.getFileDigest()); batch.setMappingDigest(session.getMappingDigest()); batch.setOptionsDigest(session.getOptionsDigest()); batch.setNormalizedRowsDigest(session.getNormalizedRowsDigest());
        batch.setContractVersion(TransactionImportPreviewTokenService.CONTRACT_VERSION); batch.setIdempotencyKey(key); batch.setRequestHash(requestHash); batch.setResultDigest(resultDigest); batch.setStatus("CONFIRMED"); batch.setTotalRows(plan.rows().size());
        batch.setWarningCount(acknowledged.size()); batch.setConfirmedAt(confirmedAt);
        if (batchMapper.insert(batch) != 1) throw new IllegalStateException("transaction import batch insert did not affect exactly one row");
        for (TransactionImportItem item : items) if (itemMapper.insert(item) != 1) throw new IllegalStateException("transaction import item insert did not affect exactly one row");
        for (TransactionImportReceipt.AccountImpact impact : impacts) { TransactionImportBatchAccountImpact entity = new TransactionImportBatchAccountImpact(); entity.setUserId(userId); entity.setBatchId(batch.getId()); entity.setAccountId(impact.accountId()); entity.setRowCount(impact.rowCount()); entity.setBalanceBefore(impact.balanceBefore()); entity.setDelta(impact.delta()); entity.setBalanceAfter(impact.balanceAfter()); if (impactMapper.insert(entity) != 1) throw new IllegalStateException("transaction import account impact insert did not affect exactly one row"); }
        if (sessionMapper.consumeReadySession(sessionId, userId, now) != 1) throw new BusinessException(409, "IMPORT_PREVIEW_STALE");
        return new TransactionImportConfirmResponse(receipt(batch, references, impacts), false);
    }

    private LockedAccounts lockCurrentOwnedAccounts(Long userId, List<Long> accountIds) {
        try {
            return accountBalanceService.lockOwnedAccounts(userId, accountIds);
        } catch (BusinessException exception) {
            if (exception.getCode() == 404) throw new BusinessException(409, "IMPORT_PREVIEW_STALE");
            throw exception;
        }
    }
    private Map<Long, Category> loadCurrentVisibleCategories(Long userId, TransactionImportPreviewPlan plan) {
        List<Long> ids = plan.rows().stream().map(row -> plan.mapping().categoryMappings().get(row.normalizedValues().get("category"))).distinct().toList();
        Map<Long, Category> categories = categoryMapper.selectByIds(ids).stream().collect(Collectors.toMap(Category::getId, category -> category));
        boolean visible = categories.size() == ids.size() && categories.values().stream().allMatch(category -> userId.equals(category.getUserId())
                || (category.getUserId() == null && Boolean.TRUE.equals(category.getIsSystem())));
        if (!visible) throw new BusinessException(409, "IMPORT_PREVIEW_STALE");
        return categories;
    }
    private TransactionImportBatch findExactDuplicate(Long userId, TransactionImportSession session) {
        return batchMapper.findConfirmedExactDuplicate(userId, session.getFileDigest(), session.getMappingDigest(),
                session.getOptionsDigest(), session.getNormalizedRowsDigest());
    }
    private TransactionImportConfirmResponse recoverKnownUnique(Long userId, UUID sessionId, String key, String requestHash,
                                                                  DataIntegrityViolationException exception) {
        TransactionImportSession session = sessionMapper.findByIdAndUserId(sessionId, userId);
        if (session == null) throw inconsistent();
        if (isExpectedUniqueConstraint(exception, "uk_transaction_import_batches_user_idempotency")) {
            TransactionImportBatch batch = batchMapper.findConfirmedByUserIdAndIdempotencyKey(userId, key);
            return replayForCurrentIntent(batch, session, key, requestHash);
        }
        if (isExpectedUniqueConstraint(exception, "uk_transaction_import_batches_exact_duplicate")) {
            TransactionImportBatch batch = findExactDuplicate(userId, session);
            if (!isCommittedExactDuplicate(batch, userId, session)) throw inconsistent();
            throw new BusinessException(409, "IMPORT_EXACT_DUPLICATE");
        }
        if (isExpectedUniqueConstraint(exception, "uk_transaction_import_batches_user_session")) {
            TransactionImportBatch batch = batchMapper.findConfirmedByUserIdAndSessionId(userId, sessionId);
            return replayForCurrentIntent(batch, session, key, requestHash);
        }
        throw inconsistent();
    }
    private TransactionImportConfirmResponse replayForCurrentIntent(TransactionImportBatch batch, TransactionImportSession session,
                                                                      String key, String requestHash) {
        if (batch == null || !"CONFIRMED".equals(batch.getStatus())
                || !java.util.Objects.equals(batch.getUserId(), session.getUserId())
                || !java.util.Objects.equals(batch.getSessionId(), session.getId())
                || !java.util.Objects.equals(batch.getId(), session.getPreallocatedBatchId())
                || !java.util.Objects.equals(batch.getIdempotencyKey(), key)
                || !java.util.Objects.equals(batch.getRequestHash(), requestHash)) throw inconsistent();
        return new TransactionImportConfirmResponse(reconstruct(batch), true);
    }
    private boolean isCommittedExactDuplicate(TransactionImportBatch batch, Long userId, TransactionImportSession session) {
        return batch != null && "CONFIRMED".equals(batch.getStatus()) && java.util.Objects.equals(batch.getUserId(), userId)
                && java.util.Objects.equals(batch.getFileDigest(), session.getFileDigest())
                && java.util.Objects.equals(batch.getMappingDigest(), session.getMappingDigest())
                && java.util.Objects.equals(batch.getOptionsDigest(), session.getOptionsDigest())
                && java.util.Objects.equals(batch.getNormalizedRowsDigest(), session.getNormalizedRowsDigest());
    }
    private BusinessException inconsistent() { return new BusinessException(500, "IMPORT_CONFIRM_INCONSISTENT"); }
    private boolean isExpectedUniqueConstraint(Throwable throwable, String expectedConstraint) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof org.postgresql.util.PSQLException sqlException && sqlException.getServerErrorMessage() != null) {
                org.postgresql.util.ServerErrorMessage message = sqlException.getServerErrorMessage();
                return "23505".equals(sqlException.getSQLState())
                        && "public".equals(message.getSchema())
                        && "transaction_import_batches".equals(message.getTable())
                        && expectedConstraint.equals(message.getConstraint());
            }
        }
        return false;
    }
    private boolean isPostgresLockConflict(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sqlException
                    && ("55P03".equals(sqlException.getSQLState()) || "40P01".equals(sqlException.getSQLState()))) return true;
        }
        return false;
    }
    private void verifyDuplicateEvidence(Long userId, TransactionImportSession session, TransactionImportPreviewPlan plan) {
        List<TransactionDuplicateProbe> probes = plan.rows().stream().map(row -> duplicateProbe(row, plan)).toList();
        Map<String, List<Long>> currentCandidates = transactionMapper.findProbableDuplicateCandidates(userId, probes).stream()
                .collect(Collectors.groupingBy(this::duplicateKey,
                        Collectors.mapping(Transaction::getId, Collectors.toList())));
        for (TransactionImportPreviewRow row : plan.rows()) {
            List<String> previewIds = row.warnings().stream().filter(warning -> "DATABASE_PROBABLE".equals(warning.code()))
                    .map(TransactionImportValidationMessage::id).toList();
            List<Long> candidateIds = currentCandidates.getOrDefault(duplicateKey(duplicateProbe(row, plan)), List.of());
            List<String> currentIds = candidateIds.isEmpty() ? List.of() : List.of(boundDatabaseWarningId(session, row, candidateIds));
            if (!previewIds.equals(currentIds)) throw new BusinessException(409, "IMPORT_DUPLICATE_EVIDENCE_CHANGED");
        }
    }
    private TransactionDuplicateProbe duplicateProbe(TransactionImportPreviewRow row, TransactionImportPreviewPlan plan) {
        return new TransactionDuplicateProbe(plan.mapping().accountMappings().get(row.normalizedValues().get("account")),
                plan.mapping().categoryMappings().get(row.normalizedValues().get("category")), row.normalizedValues().get("type"),
                new BigDecimal(row.normalizedValues().get("amount")), LocalDateTime.parse(row.normalizedValues().get("transactedAt")),
                row.normalizedValues().get("description"));
    }
    private String duplicateKey(TransactionDuplicateProbe probe) {
        return probe.accountId() + "|" + probe.categoryId() + "|" + probe.type() + "|" + probe.amount().toPlainString()
                + "|" + probe.transactedAt() + "|" + probe.description();
    }
    private String duplicateKey(Transaction transaction) {
        return duplicateKey(new TransactionDuplicateProbe(transaction.getAccountId(), transaction.getCategoryId(), transaction.getType(),
                transaction.getAmount(), transaction.getTransactedAt(), transaction.getDescription()));
    }
    private String boundDatabaseWarningId(TransactionImportSession session, TransactionImportPreviewRow row, List<Long> candidateIds) {
        String raw = sha256("DATABASE_PROBABLE|" + row.rowNumber() + "|duplicate|" + candidateIds);
        return sha256(session.getId() + "|" + session.getRevision() + "|" + canonicalMap(row.normalizedValues()) + "|" + raw);
    }
    private void verifyFrozenBinding(Long userId, TransactionImportSession current, TransactionImportSession preflight, String token, List<String> warnings) {
        if (!current.getRevision().equals(preflight.getRevision()) || !current.getFileDigest().equals(preflight.getFileDigest()) || !current.getMappingDigest().equals(preflight.getMappingDigest()) || !current.getOptionsDigest().equals(preflight.getOptionsDigest()) || !current.getNormalizedRowsDigest().equals(preflight.getNormalizedRowsDigest()) || !java.util.Objects.equals(current.getPlanStorageReference(), preflight.getPlanStorageReference())) throw new BusinessException(409, "IMPORT_PREVIEW_STALE");
        tokenService.verify(token, userId, current, warnings);
    }
    private void validatePlan(TransactionImportPreviewPlan plan, List<String> acknowledged) {
        if (plan.rows().isEmpty() || plan.rows().size() > 10_000 || plan.rows().stream().anyMatch(row -> !row.importable() || !row.errors().isEmpty())) throw new BusinessException(409, "IMPORT_PREVIEW_STALE");
        List<String> required = plan.rows().stream().flatMap(row -> row.warnings().stream()).map(warning -> warning.id()).distinct().sorted().toList();
        if (!required.equals(acknowledged)) throw new BusinessException(409, "IMPORT_WARNING_ACK_REQUIRED");
    }
    private TransactionImportConfirmResponse replayOrBatchConflict(TransactionImportBatch batch, String key, String hash) { if (batch.getIdempotencyKey().equals(key) && batch.getRequestHash().equals(hash)) return new TransactionImportConfirmResponse(reconstruct(batch), true); throw new BusinessException(409, "IMPORT_BATCH_ALREADY_CONFIRMED"); }
    private TransactionImportConfirmResponse replayOrIdempotencyConflict(TransactionImportBatch batch, String hash) { if (batch.getRequestHash().equals(hash)) return new TransactionImportConfirmResponse(reconstruct(batch), true); throw new BusinessException(409, "IMPORT_IDEMPOTENCY_CONFLICT"); }
    public TransactionImportReceipt getReceipt(Long userId, UUID batchId) { TransactionImportBatch batch = batchMapper.findConfirmedByIdAndUserId(batchId, userId); if (batch == null) throw new BusinessException(404, "IMPORT_BATCH_NOT_FOUND"); return reconstruct(batch); }
    private TransactionImportReceipt reconstruct(TransactionImportBatch batch) {
        List<TransactionImportItem> items = itemMapper.selectList(new LambdaQueryWrapper<TransactionImportItem>().eq(TransactionImportItem::getUserId, batch.getUserId()).eq(TransactionImportItem::getBatchId, batch.getId()).orderByAsc(TransactionImportItem::getSourceRowNumber));
        if (batch.getTotalRows() == null || items.size() != batch.getTotalRows() || items.stream().anyMatch(item -> item.getCreatedTransactionId() == null)) throw inconsistent();
        List<TransactionImportReceipt.TransactionReference> refs = items.stream().map(item -> new TransactionImportReceipt.TransactionReference(item.getSourceRowNumber(), item.getCreatedTransactionId())).toList();
        List<TransactionImportBatchAccountImpact> rows = impactMapper.selectList(new LambdaQueryWrapper<TransactionImportBatchAccountImpact>().eq(TransactionImportBatchAccountImpact::getUserId, batch.getUserId()).eq(TransactionImportBatchAccountImpact::getBatchId, batch.getId()).orderByAsc(TransactionImportBatchAccountImpact::getAccountId));
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getBalanceAfter() == null || row.getBalanceBefore() == null || row.getDelta() == null
                || row.getBalanceAfter().compareTo(row.getBalanceBefore().add(row.getDelta())) != 0)) throw inconsistent();
        List<TransactionImportReceipt.AccountImpact> impacts = rows.stream().map(row -> new TransactionImportReceipt.AccountImpact(row.getAccountId(), row.getRowCount(), row.getBalanceBefore(), row.getDelta(), row.getBalanceAfter())).toList();
        if (!java.util.Objects.equals(batch.getResultDigest(), resultDigest(batch.getSessionId(), batch.getId(), refs, impacts, batch.getConfirmedAt()))) throw inconsistent();
        return receipt(batch, refs, impacts);
    }
    private TransactionImportReceipt receipt(TransactionImportBatch batch, List<TransactionImportReceipt.TransactionReference> refs, List<TransactionImportReceipt.AccountImpact> impacts) { return new TransactionImportReceipt(batch.getSessionId(), batch.getId(), batch.getStatus(), batch.getOriginalFileName(), batch.getFileDigest(), batch.getTotalRows(), batch.getTotalRows(), batch.getTotalRows(), 0, batch.getWarningCount(), refs, impacts, batch.getConfirmedAt(), batch.getContractVersion(), batch.getResultDigest()); }
    private void validateRequest(String key, TransactionImportConfirmRequest request) { if (key == null || key.length() > 100 || key.isBlank() || !key.equals(key.strip()) || request == null || request.previewToken() == null || request.previewToken().isBlank() || request.acknowledgedWarningIds() == null) throw new BusinessException(400, "IMPORT_CONFIRM_REQUEST_INVALID"); }
    private List<String> canonicalWarnings(List<String> ids) { if (ids.stream().anyMatch(id -> id == null || id.isBlank())) throw new BusinessException(400, "IMPORT_CONFIRM_REQUEST_INVALID"); return ids.stream().distinct().sorted().toList(); }
    private String requestHash(Long userId, TransactionImportSession session, List<String> warnings) { return sha256(String.join("|", "TRANSACTION_IMPORT_CONFIRM", TransactionImportPreviewTokenService.CONTRACT_VERSION, String.valueOf(userId), session.getId().toString(), session.getPreallocatedBatchId().toString(), String.valueOf(session.getRevision()), session.getFileDigest(), session.getMappingDigest(), session.getOptionsDigest(), session.getNormalizedRowsDigest(), String.join(",", warnings))); }
    private String resultDigest(TransactionImportSession session, List<TransactionImportReceipt.TransactionReference> refs, List<TransactionImportReceipt.AccountImpact> impacts, Instant confirmedAt) { return resultDigest(session.getId(), session.getPreallocatedBatchId(), refs, impacts, confirmedAt); }
    private String resultDigest(UUID sessionId, UUID batchId, List<TransactionImportReceipt.TransactionReference> refs, List<TransactionImportReceipt.AccountImpact> impacts, Instant confirmedAt) { return sha256(sessionId + "|" + batchId + "|" + confirmedAt + "|" + refs + "|" + impacts); }
    private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String canonicalMap(Map<String, String> map) { return map.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> atom(entry.getKey()) + atom(entry.getValue())).collect(Collectors.joining()); }
    private String atom(String value) { return value.length() + ":" + value; }
}
