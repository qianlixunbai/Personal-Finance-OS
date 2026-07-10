package com.financeos.module.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financeos.common.BusinessException;
import com.financeos.common.PageResult;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.account.dto.AccountResponse;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    private static final String BASE_CURRENCY = "CNY";

    private final AccountMapper accountMapper;

    public AccountService(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    public List<AccountResponse> listByUser(Long userId) {
        return accountMapper.selectList(
                new LambdaQueryWrapper<Account>().eq(Account::getUserId, userId)
        ).stream().map(this::toResponse).toList();
    }

    public PageResult<AccountResponse> pageByUser(Long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<Account> result = accountMapper.selectPage(
                Page.of(safePage, safeSize),
                new LambdaQueryWrapper<Account>()
                        .eq(Account::getUserId, userId)
                        .orderByDesc(Account::getCreatedAt)
        );
        List<AccountResponse> records = result.getRecords().stream().map(this::toResponse).toList();
        return new PageResult<>(records, result.getTotal(), safePage, safeSize);
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
        String currency = req.currency() != null ? req.currency() : BASE_CURRENCY;
        validateCurrency(currency);
        Account account = new Account();
        account.setUserId(userId);
        account.setName(req.name());
        account.setType(req.type());
        account.setCurrency(currency);
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
        String currency = req.currency() != null ? req.currency() : account.getCurrency();
        validateCurrency(currency);
        account.setName(req.name());
        account.setType(req.type());
        account.setCurrency(currency);
        accountMapper.updateById(account);
        return toResponse(account);
    }

    @Transactional
    public void deactivate(Long userId, Long accountId) {
        Account account = accountMapper.selectById(accountId);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new BusinessException(404, "账户不存在");
        }
        account.setStatus("INACTIVE");
        accountMapper.updateById(account);
    }

    private void validateCurrency(String currency) {
        if (!BASE_CURRENCY.equals(currency)) {
            throw new BusinessException(400, "当前版本仅支持 CNY 币种");
        }
    }

    private AccountResponse toResponse(Account a) {
        return new AccountResponse(a.getId(), a.getName(), a.getType(),
                a.getCurrency(), a.getBalance(), a.getStatus(), a.getCreatedAt());
    }
}
