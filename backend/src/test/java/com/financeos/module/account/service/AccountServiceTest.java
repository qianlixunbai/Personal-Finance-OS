package com.financeos.module.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financeos.common.BusinessException;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.account.dto.AccountResponse;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountServiceTest {

    @Test
    void updateLocksAccountAndOnlyUpdatesMetadata() {
        AccountMapper mapper = mock(AccountMapper.class);
        AccountBalanceService balances = mock(AccountBalanceService.class);
        Account account = account(10L, "100.00", "ACTIVE");
        when(balances.lockOwnedAccounts(1L, List.of(10L))).thenReturn(new LockedAccounts(List.of(account)));
        when(mapper.updateMetadata(1L, 10L, "Cash", "CASH", "CNY")).thenReturn(1);

        AccountResponse result = new AccountService(mapper, balances)
                .update(1L, 10L, new AccountRequest("Cash", "CASH", "CNY"));

        assertThat(result.balance()).isEqualByComparingTo("100.00");
        verify(mapper).updateMetadata(1L, 10L, "Cash", "CASH", "CNY");
        verify(mapper, never()).updateById(any(Account.class));
    }

    @Test
    void deactivateLocksAccountAndOnlyUpdatesStatus() {
        AccountMapper mapper = mock(AccountMapper.class);
        AccountBalanceService balances = mock(AccountBalanceService.class);
        when(balances.lockOwnedAccounts(1L, List.of(10L)))
                .thenReturn(new LockedAccounts(List.of(account(10L, "100.00", "ACTIVE"))));
        when(mapper.deactivate(1L, 10L)).thenReturn(1);

        new AccountService(mapper, balances).deactivate(1L, 10L);

        verify(mapper).deactivate(1L, 10L);
        verify(mapper, never()).updateById(any(Account.class));
    }

    @Test
    void updateRejectsNonCnyCurrencyAfterLocking() {
        AccountMapper mapper = mock(AccountMapper.class);
        AccountBalanceService balances = mock(AccountBalanceService.class);
        when(balances.lockOwnedAccounts(1L, List.of(10L)))
                .thenReturn(new LockedAccounts(List.of(account(10L, "100.00", "ACTIVE"))));

        assertThatThrownBy(() -> new AccountService(mapper, balances)
                .update(1L, 10L, new AccountRequest("Cash", "CASH", "USD")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(400);
        verify(mapper, never()).updateMetadata(any(), any(), any(), any(), any());
    }

    @Test
    void pageByUserUsesSafePageSize() {
        AccountMapper mapper = mock(AccountMapper.class);
        AccountBalanceService balances = mock(AccountBalanceService.class);
        Page<Account> page = Page.of(1, 100);
        page.setRecords(List.of(account(10L, "100.00", "ACTIVE")));
        page.setTotal(1);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        var result = new AccountService(mapper, balances).pageByUser(1L, 0, 200);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(100);
    }

    private Account account(Long id, String balance, String status) {
        Account account = new Account();
        account.setId(id);
        account.setUserId(1L);
        account.setName("Old");
        account.setType("BANK");
        account.setCurrency("CNY");
        account.setBalance(new BigDecimal(balance));
        account.setStatus(status);
        return account;
    }
}
