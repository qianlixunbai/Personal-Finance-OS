package com.financeos.module.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.common.BusinessException;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.account.dto.AccountResponse;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.ledger.mapper.TransactionMapper;
import com.financeos.module.ledger.entity.Transaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    private final AccountMapper accountMapper;
    private final TransactionMapper transactionMapper;

    public AccountService(AccountMapper accountMapper, TransactionMapper transactionMapper) {
        this.accountMapper = accountMapper;
        this.transactionMapper = transactionMapper;
    }

    public List<AccountResponse> listByUser(Long userId) {
        return accountMapper.selectList(
                new LambdaQueryWrapper<Account>().eq(Account::getUserId, userId)
        ).stream().map(this::toResponse).toList();
    }

    public AccountResponse getById(Long userId, Long accountId) {
        Account account = accountMapper.selectById(accountId);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BusinessException(404, "账户不存在");
        }
        return toResponse(account);
    }

    @Transactional
    public AccountResponse create(Long userId, AccountRequest req) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName(req.name());
        account.setType(req.type());
        account.setCurrency(req.currency() != null ? req.currency() : "CNY");
        account.setBalance(BigDecimal.ZERO);
        accountMapper.insert(account);
        return toResponse(account);
    }

    @Transactional
    public AccountResponse update(Long userId, Long accountId, AccountRequest req) {
        Account account = accountMapper.selectById(accountId);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BusinessException(404, "账户不存在");
        }
        account.setName(req.name());
        account.setType(req.type());
        account.setCurrency(req.currency() != null ? req.currency() : account.getCurrency());
        accountMapper.updateById(account);
        return toResponse(account);
    }

    @Transactional
    public void deactivate(Long userId, Long accountId) {
        Account account = accountMapper.selectById(accountId);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BusinessException(404, "账户不存在");
        }
        Long txnCount = transactionMapper.selectCount(
                new LambdaQueryWrapper<Transaction>().eq(Transaction::getAccountId, accountId));
        if (txnCount > 0) {
            throw new BusinessException(400, "该账户存在历史流水，禁止删除，请使用停用功能");
        }
        account.setStatus("INACTIVE");
        accountMapper.updateById(account);
    }

    private AccountResponse toResponse(Account a) {
        return new AccountResponse(a.getId(), a.getName(), a.getType(),
                a.getCurrency(), a.getBalance(), a.getStatus(), a.getCreatedAt());
    }
}
