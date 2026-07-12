package com.financeos.module.dashboard.service;

import com.financeos.module.account.service.AccountQueryService;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.service.AssetQueryService;
import com.financeos.module.category.service.CategoryQueryService;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.ledger.dto.MonthlyCashFlowAggregate;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.service.TransactionQueryService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;

class DashboardServiceTest {

    @Test
    void allocationUsesOnlyInvestmentAssetMarketValueAsItsDenominator() {
        DashboardDto dashboard = dashboard(
                new BigDecimal("900.00"),
                List.of(asset("沪深 300 ETF", "6", "10"), asset("黄金 ETF", "4", "10")),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        assertEquals(new BigDecimal("1000.00"), dashboard.totalAssets());
        assertEquals(new BigDecimal("60.0000"), dashboard.assetAllocation().get(0).percentage());
        assertEquals(new BigDecimal("40.0000"), dashboard.assetAllocation().get(1).percentage());
    }

    @Test
    void allocationPercentageDoesNotChangeWhenAccountBalanceChanges() {
        List<Asset> assets = List.of(asset("沪深 300 ETF", "6", "10"), asset("黄金 ETF", "4", "10"));

        DashboardDto withAccounts = dashboard(new BigDecimal("900.00"), assets, BigDecimal.ZERO, BigDecimal.ZERO);
        DashboardDto withoutAccounts = dashboard(BigDecimal.ZERO, assets, BigDecimal.ZERO, BigDecimal.ZERO);

        assertEquals(withoutAccounts.assetAllocation().get(0).percentage(), withAccounts.assetAllocation().get(0).percentage());
        assertEquals(withoutAccounts.assetAllocation().get(1).percentage(), withAccounts.assetAllocation().get(1).percentage());
    }

    @Test
    void allocationExcludesAssetsWithoutAPositiveCurrentMarketValue() {
        Asset missingPrice = asset("未报价资产", "1", null);
        Asset missingQuantity = asset("数量缺失资产", null, "10");
        Asset zeroQuantity = asset("已清仓资产", "0", "10");
        Asset zeroValue = asset("零市值资产", "1", "0");

        DashboardDto dashboard = assertDoesNotThrow(() -> dashboard(
                BigDecimal.ZERO,
                List.of(asset("有效资产", "2", "10"), missingPrice, missingQuantity, zeroQuantity, zeroValue),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        assertEquals(1, dashboard.assetAllocation().size());
        assertEquals("有效资产", dashboard.assetAllocation().getFirst().name());
        assertEquals(new BigDecimal("100.0000"), dashboard.assetAllocation().getFirst().percentage());
    }

    @Test
    void allocationIsEmptyWhenThereAreNoEffectiveAssets() {
        DashboardDto dashboard = dashboard(
                new BigDecimal("100.00"),
                List.of(asset("未报价资产", "1", null), asset("已清仓资产", "0", "10")),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        assertTrue(dashboard.assetAllocation().isEmpty());
    }

    @Test
    void allocationPercentagesRemainWithinRoundingTolerance() {
        DashboardDto dashboard = dashboard(
                BigDecimal.ZERO,
                List.of(asset("资产 A", "1", "1"), asset("资产 B", "1", "1"), asset("资产 C", "1", "1")),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        BigDecimal percentageSum = dashboard.assetAllocation().stream()
                .map(item -> new BigDecimal(String.valueOf(item.percentage())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertTrue(percentageSum.compareTo(new BigDecimal("99.99")) >= 0);
        assertTrue(percentageSum.compareTo(new BigDecimal("100.01")) <= 0);
    }

    @Test
    void dashboardKeepsMonthlyIncomeExpenseAndNetValues() {
        DashboardDto dashboard = dashboard(
                new BigDecimal("100.00"),
                List.of(asset("有效资产", "2", "10")),
                new BigDecimal("500.00"),
                new BigDecimal("300.00")
        );

        assertEquals(new BigDecimal("500.00"), dashboard.monthIncome());
        assertEquals(new BigDecimal("300.00"), dashboard.monthExpense());
        assertEquals(new BigDecimal("200.00"), dashboard.monthNet());
    }

    @Test
    void monthlyCashFlowTrendUsesOneFixedClockAndFillsCrossYearMonths() {
        AccountQueryService accountQueryService = mock(AccountQueryService.class);
        AssetQueryService assetQueryService = mock(AssetQueryService.class);
        CategoryQueryService categoryQueryService = mock(CategoryQueryService.class);
        TransactionQueryService transactionQueryService = mock(TransactionQueryService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-15T04:30:00Z"), ZoneId.of("Asia/Shanghai"));
        DashboardService service = new DashboardService(
                accountQueryService, assetQueryService, categoryQueryService, transactionQueryService, clock
        );
        LocalDateTime currentMonthStart = LocalDateTime.of(2026, 3, 1, 0, 0);
        LocalDateTime requestNow = LocalDateTime.of(2026, 3, 15, 12, 30);

        when(accountQueryService.sumBalanceByUser(1L)).thenReturn(BigDecimal.ZERO);
        when(assetQueryService.listByUser(1L)).thenReturn(List.of());
        when(transactionQueryService.sumByTypeAndDate(1L, "INCOME", currentMonthStart, requestNow))
                .thenReturn(new BigDecimal("250.00"));
        when(transactionQueryService.sumByTypeAndDate(1L, "EXPENSE", currentMonthStart, requestNow))
                .thenReturn(new BigDecimal("400.00"));
        when(transactionQueryService.monthlyCashFlowByMonth(eq(1L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new MonthlyCashFlowAggregate(LocalDate.of(2025, 10, 1), new BigDecimal("600.00"), new BigDecimal("150.00")),
                        new MonthlyCashFlowAggregate(LocalDate.of(2026, 1, 1), new BigDecimal("100.00"), new BigDecimal("300.00")),
                        new MonthlyCashFlowAggregate(LocalDate.of(2026, 3, 1), new BigDecimal("250.00"), new BigDecimal("400.00"))
                ));
        when(transactionQueryService.listRecentByUser(1L, 5)).thenReturn(List.of());
        when(accountQueryService.mapNamesByUser(1L, Set.of())).thenReturn(Map.of());
        when(categoryQueryService.mapVisibleNamesByUser(1L, Set.of())).thenReturn(Map.of());

        DashboardDto dashboard = service.getDashboard(1L);

        assertThat(dashboard.monthlyCashFlowTrend()).extracting(DashboardDto.MonthlyCashFlow::month)
                .containsExactly("2025-10", "2025-11", "2025-12", "2026-01", "2026-02", "2026-03");
        assertThat(dashboard.monthlyCashFlowTrend().get(0).income()).isEqualByComparingTo(new BigDecimal("600.00"));
        assertThat(dashboard.monthlyCashFlowTrend().get(1).income()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dashboard.monthlyCashFlowTrend().get(3).net()).isEqualByComparingTo(new BigDecimal("-200.00"));
        assertThat(dashboard.monthlyCashFlowTrend().get(5).expense()).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(dashboard.monthlyCashFlowTrend().get(5).net()).isEqualByComparingTo(new BigDecimal("-150.00"));
        assertThat(dashboard.monthNet()).isEqualByComparingTo(new BigDecimal("-150.00"));

        ArgumentCaptor<LocalDateTime> trendStart = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> trendEnd = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(transactionQueryService).monthlyCashFlowByMonth(eq(1L), trendStart.capture(), trendEnd.capture());
        assertEquals(LocalDateTime.of(2025, 10, 1, 0, 0), trendStart.getValue());
        assertEquals(requestNow, trendEnd.getValue());
        verify(transactionQueryService).sumByTypeAndDate(1L, "INCOME", currentMonthStart, requestNow);
        verify(transactionQueryService).sumByTypeAndDate(1L, "EXPENSE", currentMonthStart, requestNow);
    }

    @Test
    void monthlyCashFlowTrendReturnsSixZeroMonthsWhenMapperReturnsNoRows() {
        DashboardDto dashboard = dashboard(
                BigDecimal.ZERO,
                List.of(),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        assertEquals(6, dashboard.monthlyCashFlowTrend().size());
        dashboard.monthlyCashFlowTrend().forEach(month -> {
            assertThat(month.income()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(month.expense()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(month.net()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

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
                transactionQueryService,
                testClock()
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

    private DashboardDto dashboard(BigDecimal accountTotal, List<Asset> assets,
                                   BigDecimal monthIncome, BigDecimal monthExpense) {
        AccountQueryService accountQueryService = mock(AccountQueryService.class);
        AssetQueryService assetQueryService = mock(AssetQueryService.class);
        CategoryQueryService categoryQueryService = mock(CategoryQueryService.class);
        TransactionQueryService transactionQueryService = mock(TransactionQueryService.class);
        DashboardService service = new DashboardService(
                accountQueryService,
                assetQueryService,
                categoryQueryService,
                transactionQueryService,
                testClock()
        );

        when(accountQueryService.sumBalanceByUser(1L)).thenReturn(accountTotal);
        when(assetQueryService.listByUser(1L)).thenReturn(assets);
        when(transactionQueryService.sumByTypeAndDate(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("INCOME"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(monthIncome);
        when(transactionQueryService.sumByTypeAndDate(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("EXPENSE"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(monthExpense);
        when(transactionQueryService.monthlyCashFlowByMonth(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(transactionQueryService.listRecentByUser(1L, 5)).thenReturn(List.of());
        when(accountQueryService.mapNamesByUser(1L, Set.of())).thenReturn(Map.of());
        when(categoryQueryService.mapVisibleNamesByUser(1L, Set.of())).thenReturn(Map.of());

        return service.getDashboard(1L);
    }

    private Clock testClock() {
        return Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    private Asset asset(String name, String quantity, String currentPrice) {
        Asset asset = new Asset();
        asset.setName(name);
        asset.setQuantity(quantity != null ? new BigDecimal(quantity) : null);
        asset.setCurrentPrice(currentPrice != null ? new BigDecimal(currentPrice) : null);
        return asset;
    }
}
