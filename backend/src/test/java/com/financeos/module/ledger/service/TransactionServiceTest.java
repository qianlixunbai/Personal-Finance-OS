package com.financeos.module.ledger.service;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountBalanceMutation;
import com.financeos.module.account.service.AccountBalanceService;
import com.financeos.module.account.service.LockedAccounts;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionServiceTest {

    @Test
    void createIncomeLocksAccountInsertsFactThenAppliesPositiveDelta() {
        Fixture fixture = fixture();
        LockedAccounts locked = locked(account(10L, "100.00", "ACTIVE"));
        when(fixture.categories.selectById(20L)).thenReturn(category(20L, "INCOME"));
        when(fixture.balances.lockOwnedAccounts(1L, List.of(10L))).thenReturn(locked);
        when(fixture.transactions.insert(any(Transaction.class))).thenReturn(1);

        fixture.service.create(1L, request(10L, 20L, "INCOME", "50.00"));

        ArgumentCaptor<List<AccountBalanceMutation>> mutations = ArgumentCaptor.forClass(List.class);
        verify(fixture.balances).applyDeltas(eq(locked), mutations.capture());
        assertThat(mutations.getValue()).containsExactly(new AccountBalanceMutation(10L, new BigDecimal("50.00"), true));
        verify(fixture.transactions).insert(any(Transaction.class));
        verify(fixture.transactions, never()).selectById(any());
    }

    @Test
    void updateLocksTransactionBeforeAccountsAndMergesSameAccountDelta() {
        Fixture fixture = fixture();
        Transaction original = transaction(99L, 10L, "INCOME", "30.00");
        LockedAccounts locked = locked(account(10L, "100.00", "ACTIVE"));
        when(fixture.transactions.selectOwnedForUpdate(1L, 99L)).thenReturn(original);
        when(fixture.categories.selectById(21L)).thenReturn(category(21L, "EXPENSE"));
        when(fixture.balances.lockOwnedAccounts(1L, List.of(10L, 10L))).thenReturn(locked);
        when(fixture.transactions.updateById(any(Transaction.class))).thenReturn(1);

        fixture.service.update(1L, 99L, request(10L, 21L, "EXPENSE", "40.00"));

        ArgumentCaptor<List<AccountBalanceMutation>> mutations = ArgumentCaptor.forClass(List.class);
        verify(fixture.balances).applyDeltas(eq(locked), mutations.capture());
        assertThat(mutations.getValue()).containsExactly(
                new AccountBalanceMutation(10L, new BigDecimal("-30.00"), false),
                new AccountBalanceMutation(10L, new BigDecimal("-40.00"), true));
        verify(fixture.transactions, never()).selectById(any());
    }

    @Test
    void updateAcrossAccountsReversesTheOriginalAccount() {
        Fixture fixture = fixture();
        Transaction original = transaction(99L, 10L, "INCOME", "30.00");
        Account oldAccount = account(10L, "100.00", "ACTIVE");
        Account newAccount = account(11L, "200.00", "ACTIVE");
        LockedAccounts locked = mock(LockedAccounts.class);
        when(locked.account(11L)).thenReturn(newAccount);
        when(fixture.transactions.selectOwnedForUpdate(1L, 99L)).thenReturn(original);
        when(fixture.categories.selectById(21L)).thenReturn(category(21L, "EXPENSE"));
        when(fixture.balances.lockOwnedAccounts(1L, List.of(10L, 11L))).thenReturn(locked);
        when(fixture.transactions.updateById(any(Transaction.class))).thenReturn(1);

        fixture.service.update(1L, 99L, request(11L, 21L, "EXPENSE", "40.00"));

        verify(fixture.balances).applyDeltas(locked, List.of(
                new AccountBalanceMutation(10L, new BigDecimal("-30.00"), false),
                new AccountBalanceMutation(11L, new BigDecimal("-40.00"), true)));
    }

    @Test
    void deleteLocksTransactionThenReversesOriginalEffectOnce() {
        Fixture fixture = fixture();
        Transaction original = transaction(99L, 10L, "EXPENSE", "25.00");
        LockedAccounts locked = locked(account(10L, "100.00", "INACTIVE"));
        when(fixture.transactions.selectOwnedForUpdate(1L, 99L)).thenReturn(original);
        when(fixture.balances.lockOwnedAccounts(1L, List.of(10L))).thenReturn(locked);
        when(fixture.transactions.deleteById(99L)).thenReturn(1);

        fixture.service.delete(1L, 99L);

        verify(fixture.balances).applyDeltas(locked,
                List.of(new AccountBalanceMutation(10L, new BigDecimal("25.00"), false)));
    }

    @Test
    void updateRejectsInactiveNewAccount() {
        Fixture fixture = fixture();
        Transaction original = transaction(99L, 10L, "INCOME", "30.00");
        LockedAccounts locked = locked(account(10L, "100.00", "INACTIVE"));
        when(fixture.transactions.selectOwnedForUpdate(1L, 99L)).thenReturn(original);
        when(fixture.categories.selectById(21L)).thenReturn(category(21L, "EXPENSE"));
        when(fixture.balances.lockOwnedAccounts(1L, List.of(10L, 10L))).thenReturn(locked);

        assertThatThrownBy(() -> fixture.service.update(1L, 99L, request(10L, 21L, "EXPENSE", "40.00")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        verify(fixture.transactions, never()).updateById(any(Transaction.class));
    }

    private Fixture fixture() {
        TransactionMapper transactions = mock(TransactionMapper.class);
        CategoryMapper categories = mock(CategoryMapper.class);
        AccountBalanceService balances = mock(AccountBalanceService.class);
        return new Fixture(transactions, categories, balances, new TransactionService(transactions, categories, balances));
    }

    private LockedAccounts locked(Account account) {
        LockedAccounts locked = mock(LockedAccounts.class);
        when(locked.accounts()).thenReturn(List.of(account));
        when(locked.account(account.getId())).thenReturn(account);
        return locked;
    }

    private Account account(Long id, String balance, String status) {
        Account account = new Account();
        account.setId(id); account.setUserId(1L); account.setCurrency("CNY");
        account.setBalance(new BigDecimal(balance)); account.setStatus(status);
        return account;
    }

    private Category category(Long id, String type) {
        Category category = new Category();
        category.setId(id); category.setUserId(1L); category.setType(type);
        return category;
    }

    private Transaction transaction(Long id, Long accountId, String type, String amount) {
        Transaction transaction = new Transaction();
        transaction.setId(id); transaction.setUserId(1L); transaction.setAccountId(accountId);
        transaction.setCategoryId(20L); transaction.setType(type); transaction.setAmount(new BigDecimal(amount));
        transaction.setCurrency("CNY"); transaction.setTransactedAt(LocalDateTime.of(2026, 7, 7, 12, 0));
        return transaction;
    }

    private TransactionRequest request(Long accountId, Long categoryId, String type, String amount) {
        return new TransactionRequest(accountId, categoryId, type, new BigDecimal(amount), "CNY", "note",
                LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    private record Fixture(TransactionMapper transactions, CategoryMapper categories, AccountBalanceService balances,
                           TransactionService service) { }
}
