package com.financeos.module.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class AccountQueryService {

    private final AccountMapper accountMapper;

    public AccountQueryService(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    public BigDecimal sumBalanceByUser(Long userId) {
        return accountMapper.selectList(
                new LambdaQueryWrapper<Account>().eq(Account::getUserId, userId)
        ).stream()
                .map(Account::getBalance)
                .filter(balance -> balance != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
