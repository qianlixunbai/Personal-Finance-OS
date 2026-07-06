package com.financeos.module.account.service;

import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
