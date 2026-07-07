package com.financeos.module.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financeos.common.BusinessException;
import com.financeos.common.PageResult;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.dto.TransactionResponse;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TransactionService {

    private static final String TYPE_INCOME = "INCOME";
    private static final String TYPE_EXPENSE = "EXPENSE";
    private static final String TYPE_ADJUSTMENT = "ADJUSTMENT";
    private static final String TYPE_TRANSFER = "TRANSFER";
    private static final String TYPE_REFUND = "REFUND";

    private final TransactionMapper transactionMapper;
    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;

    public TransactionService(TransactionMapper transactionMapper,
                              AccountMapper accountMapper,
                              CategoryMapper categoryMapper) {
        this.transactionMapper = transactionMapper;
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
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
        if (accountId != null) {
            query.eq(Transaction::getAccountId, accountId);
        }
        if (categoryId != null) {
            query.eq(Transaction::getCategoryId, categoryId);
        }
        if (type != null && !type.isBlank()) {
            query.eq(Transaction::getType, type);
        }
        if (start != null) {
            query.ge(Transaction::getTransactedAt, start);
        }
        if (end != null) {
            query.le(Transaction::getTransactedAt, end);
        }

        Page<Transaction> result = transactionMapper.selectPage(Page.of(safePage, safeSize), query);
        List<TransactionResponse> records = result.getRecords().stream().map(this::toResponse).toList();
        return new PageResult<>(records, result.getTotal(), safePage, safeSize);
    }

    public TransactionResponse getById(Long userId, Long id) {
        Transaction transaction = findOwnedTransaction(userId, id);
        ensureSupportedType(transaction.getType());
        return toResponse(transaction);
    }

    @Transactional
    public TransactionResponse create(Long userId, TransactionRequest req) {
        ensureSupportedType(req.type());
        Account account = validateAccount(userId, req.accountId(), true);
        validateCategory(userId, req.categoryId(), req.type());
        validateAmountAndDescription(req);

        Transaction transaction = new Transaction();
        transaction.setUserId(userId);
        applyRequest(transaction, req);

        applyBalance(account, req.type(), req.amount());
        transactionMapper.insert(transaction);
        accountMapper.updateById(account);
        return toResponse(transaction);
    }

    @Transactional
    public TransactionResponse update(Long userId, Long id, TransactionRequest req) {
        ensureSupportedType(req.type());
        Transaction transaction = findOwnedTransaction(userId, id);
        ensureSupportedType(transaction.getType());
        Account oldAccount = validateAccount(userId, transaction.getAccountId(), false);
        Account newAccount = oldAccount.getId().equals(req.accountId())
                ? oldAccount
                : validateAccount(userId, req.accountId(), true);
        if (!"ACTIVE".equals(newAccount.getStatus())) {
            throw new BusinessException(400, "停用账户不允许新增流水");
        }
        validateCategory(userId, req.categoryId(), req.type());
        validateAmountAndDescription(req);

        reverseBalance(oldAccount, transaction.getType(), transaction.getAmount());
        applyBalance(newAccount, req.type(), req.amount());
        applyRequest(transaction, req);

        transactionMapper.updateById(transaction);
        accountMapper.updateById(oldAccount);
        if (!oldAccount.getId().equals(newAccount.getId())) {
            accountMapper.updateById(newAccount);
        }
        return toResponse(transaction);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Transaction transaction = findOwnedTransaction(userId, id);
        ensureSupportedType(transaction.getType());
        Account account = validateAccount(userId, transaction.getAccountId(), false);
        reverseBalance(account, transaction.getType(), transaction.getAmount());
        accountMapper.updateById(account);
        transactionMapper.deleteById(id);
    }

    private Transaction findOwnedTransaction(Long userId, Long id) {
        Transaction transaction = transactionMapper.selectById(id);
        if (transaction == null || !userId.equals(transaction.getUserId())) {
            throw new BusinessException(404, "流水不存在");
        }
        return transaction;
    }

    private Account validateAccount(Long userId, Long accountId, boolean requireActive) {
        Account account = accountMapper.selectById(accountId);
        if (account == null || !userId.equals(account.getUserId())) {
            throw new BusinessException(404, "账户不存在");
        }
        if (requireActive && !"ACTIVE".equals(account.getStatus())) {
            throw new BusinessException(400, "停用账户不允许新增流水");
        }
        return account;
    }

    private void validateCategory(Long userId, Long categoryId, String type) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null || !(userId.equals(category.getUserId()) || Boolean.TRUE.equals(category.getIsSystem()))) {
            throw new BusinessException(404, "分类不存在");
        }
        if (TYPE_INCOME.equals(type) && !TYPE_INCOME.equals(category.getType())) {
            throw new BusinessException(400, "收入流水只能使用收入分类");
        }
        if (TYPE_EXPENSE.equals(type) && !TYPE_EXPENSE.equals(category.getType())) {
            throw new BusinessException(400, "支出流水只能使用支出分类");
        }
    }

    private void ensureSupportedType(String type) {
        if (TYPE_TRANSFER.equals(type) || TYPE_REFUND.equals(type)) {
            throw new BusinessException(400, "当前版本暂不支持该流水类型");
        }
        if (!TYPE_INCOME.equals(type) && !TYPE_EXPENSE.equals(type) && !TYPE_ADJUSTMENT.equals(type)) {
            throw new BusinessException(400, "非法流水类型");
        }
    }

    private void validateAmountAndDescription(TransactionRequest req) {
        BigDecimal amount = req.amount();
        if (TYPE_INCOME.equals(req.type()) && amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "收入金额必须大于 0");
        }
        if (TYPE_EXPENSE.equals(req.type()) && amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "支出金额必须大于 0");
        }
        if (TYPE_ADJUSTMENT.equals(req.type())) {
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                throw new BusinessException(400, "余额调整金额不能为 0");
            }
            if (req.description() == null || req.description().isBlank()) {
                throw new BusinessException(400, "余额调整必须填写原因");
            }
        }
    }

    private void applyBalance(Account account, String type, BigDecimal amount) {
        if (TYPE_INCOME.equals(type) || TYPE_ADJUSTMENT.equals(type)) {
            account.setBalance(account.getBalance().add(amount));
            return;
        }
        if (TYPE_EXPENSE.equals(type)) {
            account.setBalance(account.getBalance().subtract(amount));
        }
    }

    private void reverseBalance(Account account, String type, BigDecimal amount) {
        if (TYPE_INCOME.equals(type) || TYPE_ADJUSTMENT.equals(type)) {
            account.setBalance(account.getBalance().subtract(amount));
            return;
        }
        if (TYPE_EXPENSE.equals(type)) {
            account.setBalance(account.getBalance().add(amount));
        }
    }

    private void applyRequest(Transaction transaction, TransactionRequest req) {
        transaction.setAccountId(req.accountId());
        transaction.setCategoryId(req.categoryId());
        transaction.setType(req.type());
        transaction.setAmount(req.amount());
        transaction.setCurrency(req.currency() != null && !req.currency().isBlank() ? req.currency() : "CNY");
        transaction.setDescription(req.description());
        transaction.setTransactedAt(req.transactedAt());
    }

    private TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getCategoryId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription(),
                transaction.getTransactedAt(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt()
        );
    }
}
