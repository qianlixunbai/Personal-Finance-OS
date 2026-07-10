package com.financeos.module.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AccountQueryService {

    private static final String BASE_CURRENCY = "CNY";

    private final AccountMapper accountMapper;

    public AccountQueryService(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    public BigDecimal sumBalanceByUser(Long userId) {
        return accountMapper.selectList(
                new LambdaQueryWrapper<Account>()
                        .eq(Account::getUserId, userId)
                        .eq(Account::getCurrency, BASE_CURRENCY)
        ).stream()
                .map(Account::getBalance)
                .filter(balance -> balance != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Map<Long, String> mapNamesByUser(Long userId, Set<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        return accountMapper.selectList(
                new LambdaQueryWrapper<Account>()
                        .eq(Account::getUserId, userId)
                        .in(Account::getId, accountIds)
        ).stream().collect(Collectors.toMap(Account::getId, Account::getName, (first, second) -> first));
    }
}
