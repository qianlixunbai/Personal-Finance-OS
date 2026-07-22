package com.financeos.module.account.service;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountBalanceServiceTest {

    private AccountMapper mapper;
    private AccountBalanceService service;

    @BeforeEach
    void startTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        mapper = mock(AccountMapper.class);
        service = new AccountBalanceService(mapper, "4s");
    }

    @AfterEach
    void endTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void locksDistinctAccountsInAscendingIdOrder() {
        Account account10 = account(10L, "100.00", "ACTIVE");
        Account account20 = account(20L, "50.00", "ACTIVE");
        when(mapper.selectOwnedForUpdate(1L, List.of(10L, 20L))).thenReturn(List.of(account10, account20));

        LockedAccounts locked = service.lockOwnedAccounts(1L, List.of(20L, 10L, 20L));

        assertThat(locked.accounts()).extracting(Account::getId).containsExactly(10L, 20L);
        verify(mapper).configureLocalLockTimeout("4s");
        verify(mapper).selectOwnedForUpdate(1L, List.of(10L, 20L));
    }

    @Test
    void missingOwnedAccountReturnsNotFoundWithoutPartialMutation() {
        when(mapper.selectOwnedForUpdate(1L, List.of(10L, 20L))).thenReturn(List.of(account(10L, "100.00", "ACTIVE")));

        assertThatThrownBy(() -> service.lockOwnedAccounts(1L, List.of(10L, 20L)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(404);
        verify(mapper, never()).updateBalance(any(), any(), any());
    }

    @Test
    void mergesDeltasAndRequiresActiveWhenAnyMutationRequiresIt() {
        Account account = account(10L, "100.00", "ACTIVE");
        when(mapper.selectOwnedForUpdate(1L, List.of(10L))).thenReturn(List.of(account));
        when(mapper.updateBalance(1L, 10L, new BigDecimal("105.00"))).thenReturn(1);
        LockedAccounts locked = service.lockOwnedAccounts(1L, List.of(10L));

        var balances = service.applyDeltas(locked, List.of(
                new AccountBalanceMutation(10L, new BigDecimal("10.00"), false),
                new AccountBalanceMutation(10L, new BigDecimal("-5.00"), true)));

        assertThat(balances).containsEntry(10L, new BigDecimal("105.00"));
        verify(mapper).updateBalance(1L, 10L, new BigDecimal("105.00"));
    }

    @Test
    void zeroNetDeltaStillValidatesActiveStateButDoesNotWriteBalance() {
        Account account = account(10L, "100.00", "INACTIVE");
        when(mapper.selectOwnedForUpdate(1L, List.of(10L))).thenReturn(List.of(account));
        LockedAccounts locked = service.lockOwnedAccounts(1L, List.of(10L));

        assertThatThrownBy(() -> service.applyDeltas(locked, List.of(
                new AccountBalanceMutation(10L, new BigDecimal("10.00"), false),
                new AccountBalanceMutation(10L, new BigDecimal("-10.00"), true))))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        verify(mapper, never()).updateBalance(any(), any(), any());
    }

    @Test
    void refusesCallsWithoutTransaction() {
        TransactionSynchronizationManager.clear();

        assertThatThrownBy(() -> service.lockOwnedAccounts(1L, List.of(10L)))
                .isInstanceOf(IllegalStateException.class);
    }

    private Account account(Long id, String balance, String status) {
        Account account = new Account();
        account.setId(id); account.setUserId(1L); account.setBalance(new BigDecimal(balance)); account.setStatus(status);
        return account;
    }
}
