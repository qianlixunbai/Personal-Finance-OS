package com.financeos.module.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financeos.common.BusinessException;
import com.financeos.common.PageResult;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountBalanceMutation;
import com.financeos.module.account.service.AccountBalanceService;
import com.financeos.module.account.service.LockedAccounts;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.dto.TransactionResponse;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class TransactionService {

    private static final String BASE_CURRENCY = "CNY";
    private static final String TYPE_INCOME = "INCOME";
    private static final String TYPE_EXPENSE = "EXPENSE";
    private static final String TYPE_ADJUSTMENT = "ADJUSTMENT";
    private static final String TYPE_TRANSFER = "TRANSFER";
    private static final String TYPE_REFUND = "REFUND";

    private final TransactionMapper transactionMapper;
    private final CategoryMapper categoryMapper;
    private final AccountBalanceService accountBalanceService;
    private final TransactionWriteRules writeRules;

    public TransactionService(TransactionMapper transactionMapper, CategoryMapper categoryMapper,
                              AccountBalanceService accountBalanceService, TransactionWriteRules writeRules) {
        this.transactionMapper = transactionMapper;
        this.categoryMapper = categoryMapper;
        this.accountBalanceService = accountBalanceService;
        this.writeRules = writeRules;
    }

    public PageResult<TransactionResponse> pageByUser(Long userId, int page, int size,
                                                       Long accountId, Long categoryId, String type,
                                                       LocalDateTime start, LocalDateTime end) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        if (type != null && !type.isBlank()) {
            ensureSupportedType(type);
        }
        LambdaQueryWrapper<Transaction> query = new LambdaQueryWrapper<Transaction>()
                .eq(Transaction::getUserId, userId)
                .orderByDesc(Transaction::getTransactedAt)
                .orderByDesc(Transaction::getId);
        if (accountId != null) query.eq(Transaction::getAccountId, accountId);
        if (categoryId != null) query.eq(Transaction::getCategoryId, categoryId);
        if (type != null && !type.isBlank()) query.eq(Transaction::getType, type);
        if (start != null) query.ge(Transaction::getTransactedAt, start);
        if (end != null) query.le(Transaction::getTransactedAt, end);
        Page<Transaction> result = transactionMapper.selectPage(Page.of(safePage, safeSize), query);
        return new PageResult<>(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), safePage, safeSize);
    }

    public TransactionResponse getById(Long userId, Long id) {
        Transaction transaction = findOwnedTransaction(userId, id);
        ensureSupportedType(transaction.getType());
        return toResponse(transaction);
    }

    @Transactional
    public TransactionResponse create(Long userId, String idempotencyKey, TransactionRequest req) {
        ensureSupportedType(req.type());
        validateCategory(userId, req.categoryId(), req.type());
        writeRules.validateAmount(req.type(), req.amount(), req.description());
        String key = canonicalIdempotencyKey(idempotencyKey);
        String hash = key == null ? null : requestHash(userId, req);
        if (key != null) {
            Transaction existing = transactionMapper.findByUserIdAndIdempotencyKey(userId, key);
            if (existing != null) return replayOf(existing, hash);
        }
        LockedAccounts lockedAccounts = accountBalanceService.lockOwnedAccounts(userId, List.of(req.accountId()));
        Account account = lockedAccounts.accounts().getFirst();
        requireActive(account);
        String currency = validateCurrency(req.currency(), account);
        Transaction transaction = new Transaction();
        transaction.setUserId(userId);
        applyRequest(transaction, req, currency);
        transaction.setIdempotencyKey(key);
        transaction.setRequestHash(hash);
        requireExactlyOne(transactionMapper.insert(transaction), "transaction insert");
        accountBalanceService.applyDeltas(lockedAccounts,
                List.of(new AccountBalanceMutation(account.getId(), effect(req.type(), req.amount()), true)));
        return toResponse(transaction);
    }

    /**
     * Idempotent replay for a concurrent duplicate that lost the unique-index race.
     * Runs outside a transaction: the losing attempt already rolled back, so this is a
     * plain read of the fact committed by the winner.
     */
    public TransactionResponse replayConcurrentCreate(Long userId, String idempotencyKey, TransactionRequest req) {
        String key = canonicalIdempotencyKey(idempotencyKey);
        if (key == null) throw new IllegalStateException("idempotent replay requires an Idempotency-Key");
        Transaction existing = transactionMapper.findByUserIdAndIdempotencyKey(userId, key);
        if (existing == null) throw new IllegalStateException("idempotent replay target is missing");
        return replayOf(existing, requestHash(userId, req));
    }

    private TransactionResponse replayOf(Transaction existing, String hash) {
        if (!hash.equals(existing.getRequestHash())) {
            throw new BusinessException(409, "Idempotency-Key 已用于不同的请求内容");
        }
        return toResponse(existing);
    }

    private String canonicalIdempotencyKey(String value) {
        if (value == null) return null;
        if (value.isBlank() || value.length() > 100 || !value.equals(value.trim())) {
            throw new BusinessException(400, "Idempotency-Key 必须为 1 到 100 个字符且首尾无空白");
        }
        return value;
    }

    private String requestHash(Long userId, TransactionRequest req) {
        String canonical = String.join("\n",
                "operation=TRANSACTION_CREATE",
                "formulaVersion=TRANSACTION_CREATE_V1",
                "userId=" + userId,
                "accountId=" + req.accountId(),
                "categoryId=" + req.categoryId(),
                "type=" + req.type(),
                "amount=" + req.amount().setScale(TransactionWriteRules.MONEY_SCALE).toPlainString(),
                "currency=" + canonicalOptional(req.currency()),
                "description=" + canonicalOptional(req.description()),
                "transactedAt=" + req.transactedAt());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String canonicalOptional(String value) {
        return value == null ? "NULL" : "VALUE:" + value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    @Transactional
    public TransactionResponse update(Long userId, Long id, TransactionRequest req) {
        ensureSupportedType(req.type());
        Transaction transaction = lockOwnedTransaction(userId, id);
        ensureSupportedType(transaction.getType());
        validateCategory(userId, req.categoryId(), req.type());
        writeRules.validateAmount(req.type(), req.amount(), req.description());
        Long oldAccountId = transaction.getAccountId();
        LockedAccounts lockedAccounts = accountBalanceService.lockOwnedAccounts(
                userId, List.of(oldAccountId, req.accountId()));
        Account newAccount = lockedAccounts.account(req.accountId());
        requireActive(newAccount);
        String currency = validateCurrency(req.currency(), newAccount);
        BigDecimal reverseOldEffect = effect(transaction.getType(), transaction.getAmount()).negate();
        BigDecimal newEffect = effect(req.type(), req.amount());
        applyRequest(transaction, req, currency);
        requireExactlyOne(transactionMapper.updateById(transaction), "transaction update");
        accountBalanceService.applyDeltas(lockedAccounts, List.of(
                new AccountBalanceMutation(oldAccountId, reverseOldEffect, false),
                new AccountBalanceMutation(req.accountId(), newEffect, true)));
        return toResponse(transaction);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Transaction transaction = lockOwnedTransaction(userId, id);
        ensureSupportedType(transaction.getType());
        LockedAccounts lockedAccounts = accountBalanceService.lockOwnedAccounts(userId, List.of(transaction.getAccountId()));
        requireExactlyOne(transactionMapper.deleteById(id), "transaction delete");
        accountBalanceService.applyDeltas(lockedAccounts, List.of(new AccountBalanceMutation(
                transaction.getAccountId(), effect(transaction.getType(), transaction.getAmount()).negate(), false)));
    }

    private Transaction lockOwnedTransaction(Long userId, Long id) {
        Transaction transaction = transactionMapper.selectOwnedForUpdate(userId, id);
        if (transaction == null) throw new BusinessException(404, "流水不存在");
        return transaction;
    }

    private Transaction findOwnedTransaction(Long userId, Long id) {
        Transaction transaction = transactionMapper.selectById(id);
        if (transaction == null || !userId.equals(transaction.getUserId())) {
            throw new BusinessException(404, "流水不存在");
        }
        return transaction;
    }

    private void validateCategory(Long userId, Long categoryId, String type) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null || !(userId.equals(category.getUserId())
                || (category.getUserId() == null && Boolean.TRUE.equals(category.getIsSystem())))) {
            throw new BusinessException(404, "分类不存在");
        }
        if (TYPE_INCOME.equals(type) && !TYPE_INCOME.equals(category.getType())) {
            throw new BusinessException(400, "收入流水只能使用收入分类");
        }
        if (TYPE_EXPENSE.equals(type) && !TYPE_EXPENSE.equals(category.getType())) {
            throw new BusinessException(400, "支出流水只能使用支出分类");
        }
    }

    private String validateCurrency(String requestedCurrency, Account account) {
        String currency = requestedCurrency != null ? requestedCurrency : BASE_CURRENCY;
        if (!BASE_CURRENCY.equals(currency)) throw new BusinessException(400, "当前版本仅支持 CNY 币种");
        if (!currency.equals(account.getCurrency())) throw new BusinessException(400, "流水币种必须与账户币种一致");
        return currency;
    }

    private void requireActive(Account account) {
        if (!"ACTIVE".equals(account.getStatus())) throw new BusinessException(400, "停用账户不允许新增流水");
    }

    private void ensureSupportedType(String type) {
        if (TYPE_TRANSFER.equals(type) || TYPE_REFUND.equals(type)) throw new BusinessException(400, "当前版本暂不支持该流水类型");
        if (!TYPE_INCOME.equals(type) && !TYPE_EXPENSE.equals(type) && !TYPE_ADJUSTMENT.equals(type)) {
            throw new BusinessException(400, "非法流水类型");
        }
    }

    private BigDecimal effect(String type, BigDecimal amount) {
        return TYPE_EXPENSE.equals(type) ? amount.negate() : amount;
    }

    private void applyRequest(Transaction transaction, TransactionRequest req, String currency) {
        transaction.setAccountId(req.accountId());
        transaction.setCategoryId(req.categoryId());
        transaction.setType(req.type());
        transaction.setAmount(req.amount());
        transaction.setCurrency(currency);
        transaction.setDescription(req.description());
        transaction.setTransactedAt(req.transactedAt());
    }

    private void requireExactlyOne(int updatedRows, String operation) {
        if (updatedRows != 1) throw new IllegalStateException(operation + " did not affect exactly one row");
    }

    private TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getAccountId(), transaction.getCategoryId(),
                transaction.getType(), transaction.getAmount(), transaction.getCurrency(), transaction.getDescription(),
                transaction.getTransactedAt(), transaction.getCreatedAt(), transaction.getUpdatedAt());
    }
}
