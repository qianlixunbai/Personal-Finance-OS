package com.financeos.module.account.service;

import com.financeos.common.BusinessException;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.investment.instrument.service.InvestmentPositionBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    @Mock private AccountMapper accountMapper;
    @Mock private AccountBalanceService balanceService;
    @Mock private InvestmentPositionBindingService bindingService;
    private AccountService service;
    private Account account;

    @BeforeEach
    void setUp() {
        service = new AccountService(accountMapper, balanceService, bindingService);
        account = new Account();
        account.setId(7L);
        account.setUserId(3L);
        account.setType("BROKERAGE");
        account.setName("Original");
        account.setCurrency("CNY");
        account.setBalance(new BigDecimal("-20.00"));
        account.setStatus("ACTIVE");
    }

    @Test
    void incompatibleTypeIsCheckedAfterOwnedLockAndBeforeAnyMetadataWrite() {
        lockedAccount();
        doThrow(new BusinessException(409, "账户类型与已有投资仓位绑定不兼容"))
                .when(bindingService).validateAccountTypeChange(3L, 7L, "CASH");
        assertThatThrownBy(() -> service.update(3L, 7L, new AccountRequest("Must not persist", "CASH", "CNY")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        var order = inOrder(balanceService, bindingService);
        order.verify(balanceService).lockOwnedAccounts(3L, List.of(7L));
        order.verify(bindingService).validateAccountTypeChange(3L, 7L, "CASH");
        verify(accountMapper, never()).updateMetadata(anyLong(), anyLong(), anyString(), anyString(), anyString());
        assertThat(account.getName()).isEqualTo("Original");
        assertThat(account.getType()).isEqualTo("BROKERAGE");
        assertThat(account.getBalance()).isEqualByComparingTo("-20.00");
    }

    @Test
    void unchangedTypeAllowsNameChangeWithoutBindingQueryOrBalanceMutation() {
        lockedAccount();
        when(accountMapper.updateMetadata(3L, 7L, "Renamed", "BROKERAGE", "CNY")).thenReturn(1);
        var result = service.update(3L, 7L, new AccountRequest("Renamed", "BROKERAGE", null));
        assertThat(result.name()).isEqualTo("Renamed");
        assertThat(result.balance()).isEqualByComparingTo("-20.00");
        verifyNoInteractions(bindingService);
        verify(balanceService, never()).applyDeltas(any(), any());
    }

    @Test
    void compatibleTypeChangeUpdatesOnlyAfterValidation() {
        lockedAccount();
        when(accountMapper.updateMetadata(3L, 7L, "Cash", "CASH", "CNY")).thenReturn(1);
        assertThat(service.update(3L, 7L, new AccountRequest("Cash", "CASH", "CNY")).type()).isEqualTo("CASH");
        var order = inOrder(balanceService, bindingService, accountMapper);
        order.verify(balanceService).lockOwnedAccounts(3L, List.of(7L));
        order.verify(bindingService).validateAccountTypeChange(3L, 7L, "CASH");
        order.verify(accountMapper).updateMetadata(3L, 7L, "Cash", "CASH", "CNY");
    }

    @Test
    void missingOrForeignAccountKeeps404WithoutBindingQuery() {
        when(balanceService.lockOwnedAccounts(3L, List.of(7L))).thenThrow(new BusinessException(404, "账户不存在"));
        assertThatThrownBy(() -> service.update(3L, 7L, new AccountRequest("Foreign", "CASH", "CNY")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(404);
        verifyNoInteractions(accountMapper, bindingService);
    }

    @Test
    void invalidCurrencyKeeps400BeforeBindingCheckOrMetadataWrite() {
        lockedAccount();
        assertThatThrownBy(() -> service.update(3L, 7L, new AccountRequest("Invalid", "CASH", "USD")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        verifyNoInteractions(accountMapper, bindingService);
    }

    private void lockedAccount() {
        when(balanceService.lockOwnedAccounts(3L, List.of(7L))).thenReturn(new LockedAccounts(List.of(account)));
    }
}
