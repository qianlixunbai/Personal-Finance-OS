package com.financeos.module.dashboard.service;

import com.financeos.module.account.service.AccountQueryService;
import com.financeos.module.asset.service.AssetQueryService;
import com.financeos.module.category.service.CategoryQueryService;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.service.TransactionQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    @Test
    void recentTransactionsIncludeVisibleAccountAndCategoryNames() {
        AccountQueryService accountQueryService = mock(AccountQueryService.class);
        AssetQueryService assetQueryService = mock(AssetQueryService.class);
        CategoryQueryService categoryQueryService = mock(CategoryQueryService.class);
        TransactionQueryService transactionQueryService = mock(TransactionQueryService.class);
        DashboardService service = new DashboardService(
                accountQueryService,
                assetQueryService,
                categoryQueryService,
                transactionQueryService
        );

        when(accountQueryService.sumBalanceByUser(1L)).thenReturn(new BigDecimal("100.00"));
        when(assetQueryService.listByUser(1L)).thenReturn(List.of());
        when(transactionQueryService.sumByTypeAndDate(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(BigDecimal.ZERO);
        when(transactionQueryService.listRecentByUser(1L, 5)).thenReturn(List.of(
                transaction(100L, 10L, 20L, "EXPENSE", "30.00"),
                transaction(101L, 99L, 21L, "INCOME", "50.00")
        ));
        when(accountQueryService.mapNamesByUser(1L, Set.of(10L, 99L)))
                .thenReturn(Map.of(10L, "现金账户"));
        when(categoryQueryService.mapVisibleNamesByUser(1L, Set.of(20L, 21L)))
                .thenReturn(Map.of(20L, "餐饮", 21L, "工资"));

        DashboardDto dashboard = service.getDashboard(1L);

        assertEquals("现金账户", dashboard.recentTransactions().get(0).account());
        assertEquals("餐饮", dashboard.recentTransactions().get(0).category());
        assertEquals("未知账户", dashboard.recentTransactions().get(1).account());
        assertEquals("工资", dashboard.recentTransactions().get(1).category());
        verify(accountQueryService).mapNamesByUser(1L, Set.of(10L, 99L));
        verify(categoryQueryService).mapVisibleNamesByUser(1L, Set.of(20L, 21L));
    }

    private Transaction transaction(Long id, Long accountId, Long categoryId, String type, String amount) {
        Transaction transaction = new Transaction();
        transaction.setId(id);
        transaction.setUserId(1L);
        transaction.setAccountId(accountId);
        transaction.setCategoryId(categoryId);
        transaction.setType(type);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setTransactedAt(LocalDateTime.of(2026, 7, 8, 9, 0));
        return transaction;
    }
}
