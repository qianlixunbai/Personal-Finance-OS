package com.financeos.module.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financeos.common.PageResult;
import com.financeos.module.account.dto.AccountResponse;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    @Test
    void deactivateAllowsAccountWithHistoricalTransactions() {
        AccountMapper accountMapper = mock(AccountMapper.class);
        AccountService service = new AccountService(accountMapper);

        Account account = new Account();
        account.setId(10L);
        account.setUserId(1L);
        account.setStatus("ACTIVE");

        when(accountMapper.selectById(10L)).thenReturn(account);

        assertDoesNotThrow(() -> service.deactivate(1L, 10L));

        verify(accountMapper).updateById(account);
    }

    @Test
    void pageByUserUsesSafePageSizeAndMapsRecords() {
        AccountMapper accountMapper = mock(AccountMapper.class);
        AccountService service = new AccountService(accountMapper);

        Account account = new Account();
        account.setId(10L);
        account.setUserId(1L);
        account.setName("现金账户");
        account.setType("CASH");
        account.setCurrency("CNY");
        account.setBalance(new BigDecimal("100.00"));
        account.setStatus("ACTIVE");
        account.setCreatedAt(LocalDateTime.of(2026, 7, 7, 10, 0));

        Page<Account> page = Page.of(1, 100);
        page.setRecords(List.of(account));
        page.setTotal(1);

        when(accountMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        PageResult<AccountResponse> result = service.pageByUser(1L, 0, 200);

        assertEquals(1, result.page());
        assertEquals(100, result.size());
        assertEquals(1, result.total());
        assertEquals(1, result.records().size());
        assertEquals("现金账户", result.records().get(0).name());
    }
}
