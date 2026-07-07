package com.financeos.module.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financeos.common.BusinessException;
import com.financeos.common.PageResult;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.dto.TransactionResponse;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionServiceTest {

    @Test
    void createIncomeIncreasesAccountBalance() {
        var fixture = fixture();
        Account account = activeAccount(10L, 1L, "100.00");
        Category category = category(20L, 1L, "INCOME", false);
        when(fixture.accountMapper.selectById(10L)).thenReturn(account);
        when(fixture.categoryMapper.selectById(20L)).thenReturn(category);

        TransactionResponse response = fixture.service.create(1L,
                request(10L, 20L, "INCOME", "50.00", null, "salary"));

        assertEquals(new BigDecimal("150.00"), account.getBalance());
        assertEquals("INCOME", response.type());
        verify(fixture.transactionMapper).insert(any(Transaction.class));
        verify(fixture.accountMapper).updateById(account);
    }

    @Test
    void createExpenseDecreasesAccountBalance() {
        var fixture = fixture();
        Account account = activeAccount(10L, 1L, "100.00");
        Category category = category(20L, 1L, "EXPENSE", false);
        when(fixture.accountMapper.selectById(10L)).thenReturn(account);
        when(fixture.categoryMapper.selectById(20L)).thenReturn(category);

        fixture.service.create(1L, request(10L, 20L, "EXPENSE", "30.00", null, "food"));

        assertEquals(new BigDecimal("70.00"), account.getBalance());
        verify(fixture.accountMapper).updateById(account);
    }

    @Test
    void createAdjustmentRequiresDescriptionAndAppliesSignedAmount() {
        var fixture = fixture();
        Account account = activeAccount(10L, 1L, "100.00");
        Category category = category(20L, 1L, "INCOME", false);
        when(fixture.accountMapper.selectById(10L)).thenReturn(account);
        when(fixture.categoryMapper.selectById(20L)).thenReturn(category);

        assertThrows(BusinessException.class,
                () -> fixture.service.create(1L, request(10L, 20L, "ADJUSTMENT", "10.00", null, " ")));

        fixture.service.create(1L, request(10L, 20L, "ADJUSTMENT", "-15.00", null, "reconcile"));

        assertEquals(new BigDecimal("85.00"), account.getBalance());
    }

    @Test
    void createRejectsTransferAndRefund() {
        var fixture = fixture();

        assertThrows(BusinessException.class,
                () -> fixture.service.create(1L, request(10L, 20L, "TRANSFER", "10.00", null, "move")));
        assertThrows(BusinessException.class,
                () -> fixture.service.create(1L, request(10L, 20L, "REFUND", "10.00", null, "refund")));

        verify(fixture.transactionMapper, never()).insert(any(Transaction.class));
    }

    @Test
    void getByIdRejectsExistingUnsupportedTransactionType() {
        var fixture = fixture();
        when(fixture.transactionMapper.selectById(99L))
                .thenReturn(transaction(99L, 1L, 10L, 20L, "TRANSFER", "10.00"));

        BusinessException ex = assertThrows(BusinessException.class, () -> fixture.service.getById(1L, 99L));

        assertEquals(400, ex.getCode());
    }

    @Test
    void createRejectsInactiveAccount() {
        var fixture = fixture();
        Account account = activeAccount(10L, 1L, "100.00");
        account.setStatus("INACTIVE");
        when(fixture.accountMapper.selectById(10L)).thenReturn(account);

        assertThrows(BusinessException.class,
                () -> fixture.service.create(1L, request(10L, 20L, "INCOME", "10.00", null, "salary")));

        verify(fixture.transactionMapper, never()).insert(any(Transaction.class));
    }

    @Test
    void createRejectsAccountOwnedByAnotherUser() {
        var fixture = fixture();
        when(fixture.accountMapper.selectById(10L)).thenReturn(activeAccount(10L, 2L, "100.00"));

        assertThrows(BusinessException.class,
                () -> fixture.service.create(1L, request(10L, 20L, "INCOME", "10.00", null, "salary")));
    }

    @Test
    void createRejectsCategoryOwnedByAnotherUser() {
        var fixture = fixture();
        when(fixture.accountMapper.selectById(10L)).thenReturn(activeAccount(10L, 1L, "100.00"));
        when(fixture.categoryMapper.selectById(20L)).thenReturn(category(20L, 2L, "INCOME", false));

        assertThrows(BusinessException.class,
                () -> fixture.service.create(1L, request(10L, 20L, "INCOME", "10.00", null, "salary")));
    }

    @Test
    void updateRollsBackOldBalanceImpactBeforeApplyingNewImpact() {
        var fixture = fixture();
        Account oldAccount = activeAccount(10L, 1L, "100.00");
        Account newAccount = activeAccount(11L, 1L, "200.00");
        Transaction oldTx = transaction(99L, 1L, 10L, 20L, "INCOME", "30.00");
        when(fixture.transactionMapper.selectById(99L)).thenReturn(oldTx);
        when(fixture.accountMapper.selectById(10L)).thenReturn(oldAccount);
        when(fixture.accountMapper.selectById(11L)).thenReturn(newAccount);
        when(fixture.categoryMapper.selectById(21L)).thenReturn(category(21L, 1L, "EXPENSE", false));

        fixture.service.update(1L, 99L, request(11L, 21L, "EXPENSE", "40.00", "USD", "rent"));

        assertEquals(new BigDecimal("70.00"), oldAccount.getBalance());
        assertEquals(new BigDecimal("160.00"), newAccount.getBalance());
        verify(fixture.transactionMapper).updateById(oldTx);
        verify(fixture.accountMapper).updateById(oldAccount);
        verify(fixture.accountMapper).updateById(newAccount);
    }

    @Test
    void updateRejectsExistingUnsupportedTransactionType() {
        var fixture = fixture();
        when(fixture.transactionMapper.selectById(99L))
                .thenReturn(transaction(99L, 1L, 10L, 20L, "REFUND", "10.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fixture.service.update(1L, 99L, request(10L, 20L, "INCOME", "10.00", null, "salary")));

        assertEquals(400, ex.getCode());
        verify(fixture.accountMapper, never()).updateById(any(Account.class));
        verify(fixture.transactionMapper, never()).updateById(any(Transaction.class));
    }

    @Test
    void updateOnSameAccountRollsBackAndAppliesNewImpact() {
        var fixture = fixture();
        Account account = activeAccount(10L, 1L, "100.00");
        Account reloadedAccount = activeAccount(10L, 1L, "100.00");
        Transaction oldTx = transaction(99L, 1L, 10L, 20L, "INCOME", "30.00");
        when(fixture.transactionMapper.selectById(99L)).thenReturn(oldTx);
        when(fixture.accountMapper.selectById(10L)).thenReturn(account, reloadedAccount);
        when(fixture.categoryMapper.selectById(21L)).thenReturn(category(21L, 1L, "EXPENSE", false));

        fixture.service.update(1L, 99L, request(10L, 21L, "EXPENSE", "40.00", "CNY", "rent"));

        assertEquals(new BigDecimal("30.00"), account.getBalance());
        verify(fixture.accountMapper).updateById(account);
    }

    @Test
    void deleteRollsBackAccountBalance() {
        var fixture = fixture();
        Account account = activeAccount(10L, 1L, "100.00");
        Transaction tx = transaction(99L, 1L, 10L, 20L, "EXPENSE", "25.00");
        when(fixture.transactionMapper.selectById(99L)).thenReturn(tx);
        when(fixture.accountMapper.selectById(10L)).thenReturn(account);

        fixture.service.delete(1L, 99L);

        assertEquals(new BigDecimal("125.00"), account.getBalance());
        verify(fixture.accountMapper).updateById(account);
        verify(fixture.transactionMapper).deleteById(99L);
    }

    @Test
    void deleteRejectsExistingUnsupportedTransactionType() {
        var fixture = fixture();
        when(fixture.transactionMapper.selectById(99L))
                .thenReturn(transaction(99L, 1L, 10L, 20L, "TRANSFER", "10.00"));

        BusinessException ex = assertThrows(BusinessException.class, () -> fixture.service.delete(1L, 99L));

        assertEquals(400, ex.getCode());
        verify(fixture.accountMapper, never()).updateById(any(Account.class));
        verify(fixture.transactionMapper, never()).deleteById(99L);
    }

    @Test
    void pageByUserOnlyReturnsCurrentUserDataAndUsesSafePaging() {
        var fixture = fixture();
        Transaction tx = transaction(99L, 1L, 10L, 20L, "INCOME", "30.00");
        Page<Transaction> page = Page.of(1, 100);
        page.setRecords(List.of(tx));
        page.setTotal(1);
        when(fixture.transactionMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        PageResult<TransactionResponse> result = fixture.service.pageByUser(
                1L, 0, 200, 10L, 20L, "INCOME",
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 31, 23, 59));

        assertEquals(1, result.page());
        assertEquals(100, result.size());
        assertEquals(1, result.total());
        assertEquals(99L, result.records().get(0).id());

        ArgumentCaptor<LambdaQueryWrapper<Transaction>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.transactionMapper).selectPage(any(Page.class), queryCaptor.capture());
        assertNotNull(queryCaptor.getValue());
    }

    @Test
    void pageByUserRejectsUnsupportedAndUnknownTypeFilter() {
        var fixture = fixture();

        assertEquals(400, assertThrows(BusinessException.class,
                () -> fixture.service.pageByUser(1L, 1, 20, null, null, "TRANSFER", null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> fixture.service.pageByUser(1L, 1, 20, null, null, "REFUND", null, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> fixture.service.pageByUser(1L, 1, 20, null, null, "OTHER", null, null)).getCode());

        verify(fixture.transactionMapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
    }

    private Fixture fixture() {
        TransactionMapper transactionMapper = mock(TransactionMapper.class);
        AccountMapper accountMapper = mock(AccountMapper.class);
        CategoryMapper categoryMapper = mock(CategoryMapper.class);
        return new Fixture(transactionMapper, accountMapper, categoryMapper,
                new TransactionService(transactionMapper, accountMapper, categoryMapper));
    }

    private TransactionRequest request(Long accountId, Long categoryId, String type,
                                       String amount, String currency, String description) {
        return new TransactionRequest(accountId, categoryId, type, new BigDecimal(amount), currency,
                description, LocalDateTime.of(2026, 7, 7, 12, 0));
    }

    private Account activeAccount(Long id, Long userId, String balance) {
        Account account = new Account();
        account.setId(id);
        account.setUserId(userId);
        account.setStatus("ACTIVE");
        account.setCurrency("CNY");
        account.setBalance(new BigDecimal(balance));
        return account;
    }

    private Category category(Long id, Long userId, String type, boolean system) {
        Category category = new Category();
        category.setId(id);
        category.setUserId(userId);
        category.setType(type);
        category.setIsSystem(system);
        return category;
    }

    private Transaction transaction(Long id, Long userId, Long accountId, Long categoryId,
                                    String type, String amount) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setUserId(userId);
        tx.setAccountId(accountId);
        tx.setCategoryId(categoryId);
        tx.setType(type);
        tx.setAmount(new BigDecimal(amount));
        tx.setCurrency("CNY");
        tx.setDescription("note");
        tx.setTransactedAt(LocalDateTime.of(2026, 7, 7, 12, 0));
        tx.setCreatedAt(LocalDateTime.of(2026, 7, 7, 12, 1));
        tx.setUpdatedAt(LocalDateTime.of(2026, 7, 7, 12, 2));
        return tx;
    }

    private record Fixture(TransactionMapper transactionMapper, AccountMapper accountMapper,
                           CategoryMapper categoryMapper, TransactionService service) {
    }
}
