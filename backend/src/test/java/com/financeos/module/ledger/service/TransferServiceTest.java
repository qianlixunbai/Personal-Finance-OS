package com.financeos.module.ledger.service;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountBalanceMutation;
import com.financeos.module.account.service.AccountBalanceService;
import com.financeos.module.account.service.LockedAccounts;
import com.financeos.module.ledger.dto.TransferRequest;
import com.financeos.module.ledger.dto.TransferResponse;
import com.financeos.module.ledger.entity.Transfer;
import com.financeos.module.ledger.mapper.TransferMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransferServiceTest {

    @Test
    void createPersistsTransferAndAppliesBothBalanceDeltas() {
        Fixture fixture = fixture();
        Account from = account(20L, 1L, "100.00", "ACTIVE", "CNY");
        Account to = account(10L, 1L, "200.00", "ACTIVE", "CNY");
        LockedAccounts locked = locked(from, to);
        when(fixture.balances.lockOwnedAccounts(1L, List.of(20L, 10L))).thenReturn(locked);
        when(fixture.transfers.insert(any(Transfer.class))).thenAnswer(invocation -> {
            ((Transfer) invocation.getArgument(0)).setId(321L);
            return 1;
        });

        TransferResponse response = fixture.service.create(1L, request(20L, 10L, "25.30"));

        ArgumentCaptor<Transfer> transfer = ArgumentCaptor.forClass(Transfer.class);
        verify(fixture.transfers).insert(transfer.capture());
        assertThat(transfer.getValue().getUserId()).isEqualTo(1L);
        assertThat(transfer.getValue().getFromAccountId()).isEqualTo(20L);
        assertThat(transfer.getValue().getToAccountId()).isEqualTo(10L);
        assertThat(transfer.getValue().getAmount()).isEqualByComparingTo("25.30");
        assertThat(transfer.getValue().getCurrency()).isEqualTo("CNY");
        assertThat(transfer.getValue().getDescription()).isEqualTo("test transfer");
        assertThat(transfer.getValue().getTransactedAt()).isEqualTo(request(20L, 10L, "25.30").transactedAt());

        ArgumentCaptor<List<AccountBalanceMutation>> mutations = ArgumentCaptor.forClass(List.class);
        verify(fixture.balances).applyDeltas(eq(locked), mutations.capture());
        assertThat(mutations.getValue()).containsExactlyInAnyOrder(
                new AccountBalanceMutation(20L, new BigDecimal("-25.30"), true),
                new AccountBalanceMutation(10L, new BigDecimal("25.30"), true));
        assertThat(response.id()).isEqualTo(321L);
        assertThat(response.fromAccountId()).isEqualTo(20L);
        assertThat(response.toAccountId()).isEqualTo(10L);
        assertThat(response.amount()).isEqualByComparingTo("25.30");
        assertThat(response.currency()).isEqualTo("CNY");
    }

    @Test
    void createRejectsSameAccountBeforeLockingOrWriting() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.create(1L, request(10L, 10L, "1.00")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);

        verifyNoInteractions(fixture.balances, fixture.transfers);
    }

    @Test
    void createRejectsNonPositiveOverScaleAndOutOfRangeAmountsBeforeLocking() {
        for (String amount : List.of("0.00", "-1.00", "1.001", "10000000000000000.00")) {
            Fixture fixture = fixture();

            assertThatThrownBy(() -> fixture.service.create(1L, request(10L, 20L, amount)))
                    .as("amount %s", amount)
                    .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);

            verifyNoInteractions(fixture.balances, fixture.transfers);
        }
    }

    @Test
    void createRejectsMissingAmountBeforeLocking() {
        Fixture fixture = fixture();
        TransferRequest request = new TransferRequest(10L, 20L, null,
                LocalDateTime.of(2026, 9, 23, 12, 0), "test transfer");

        assertThatThrownBy(() -> fixture.service.create(1L, request))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);

        verifyNoInteractions(fixture.balances, fixture.transfers);
    }

    @Test
    void createRejectsInactiveSourceOrDestinationWithoutWritingFactOrApplyingDeltas() {
        for (boolean inactiveSource : List.of(true, false)) {
            Fixture fixture = fixture();
            LockedAccounts locked = locked(
                    account(10L, 1L, "100.00", inactiveSource ? "INACTIVE" : "ACTIVE", "CNY"),
                    account(20L, 1L, "200.00", inactiveSource ? "ACTIVE" : "INACTIVE", "CNY"));
            when(fixture.balances.lockOwnedAccounts(1L, List.of(10L, 20L))).thenReturn(locked);

            assertThatThrownBy(() -> fixture.service.create(1L, request(10L, 20L, "1.00")))
                    .as("inactive source: %s", inactiveSource)
                    .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);

            verify(fixture.transfers, never()).insert(any(Transfer.class));
            verify(fixture.balances, never()).applyDeltas(any(), any());
        }
    }

    @Test
    void createRejectsNonCnySourceOrDestinationWithoutWritingFactOrApplyingDeltas() {
        for (boolean nonCnySource : List.of(true, false)) {
            Fixture fixture = fixture();
            LockedAccounts locked = locked(
                    account(10L, 1L, "100.00", "ACTIVE", nonCnySource ? "USD" : "CNY"),
                    account(20L, 1L, "200.00", "ACTIVE", nonCnySource ? "CNY" : "USD"));
            when(fixture.balances.lockOwnedAccounts(1L, List.of(10L, 20L))).thenReturn(locked);

            assertThatThrownBy(() -> fixture.service.create(1L, request(10L, 20L, "1.00")))
                    .as("non-CNY source: %s", nonCnySource)
                    .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);

            verify(fixture.transfers, never()).insert(any(Transfer.class));
            verify(fixture.balances, never()).applyDeltas(any(), any());
        }
    }

    @Test
    void createReturnsNotFoundWhenOwnerScopedAccountLockDoesNotFindEveryAccount() {
        Fixture fixture = fixture();
        when(fixture.balances.lockOwnedAccounts(1L, List.of(10L, 20L)))
                .thenThrow(new BusinessException(404, "账户不存在"));

        assertThatThrownBy(() -> fixture.service.create(1L, request(10L, 20L, "1.00")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(404);

        verify(fixture.transfers, never()).insert(any(Transfer.class));
        verify(fixture.balances, never()).applyDeltas(any(), any());
    }

    private Fixture fixture() {
        TransferMapper transfers = mock(TransferMapper.class);
        AccountBalanceService balances = mock(AccountBalanceService.class);
        return new Fixture(transfers, balances,
                new TransferService(transfers, balances, new TransactionWriteRules()));
    }

    private LockedAccounts locked(Account... accounts) {
        LockedAccounts locked = mock(LockedAccounts.class);
        for (Account account : accounts) {
            when(locked.account(account.getId())).thenReturn(account);
        }
        when(locked.accounts()).thenReturn(List.of(accounts));
        return locked;
    }

    private Account account(Long id, Long userId, String balance, String status, String currency) {
        Account account = new Account();
        account.setId(id);
        account.setUserId(userId);
        account.setCurrency(currency);
        account.setBalance(new BigDecimal(balance));
        account.setStatus(status);
        return account;
    }

    private TransferRequest request(Long fromAccountId, Long toAccountId, String amount) {
        return new TransferRequest(fromAccountId, toAccountId, new BigDecimal(amount),
                LocalDateTime.of(2026, 9, 23, 12, 0), "test transfer");
    }

    private record Fixture(TransferMapper transfers, AccountBalanceService balances, TransferService service) { }
}
