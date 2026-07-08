package com.financeos.module.dashboard.service;

import com.financeos.module.account.service.AccountQueryService;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.service.AssetQueryService;
import com.financeos.module.category.service.CategoryQueryService;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.service.TransactionQueryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private final AccountQueryService accountQueryService;
    private final AssetQueryService assetQueryService;
    private final CategoryQueryService categoryQueryService;
    private final TransactionQueryService transactionQueryService;

    public DashboardService(AccountQueryService accountQueryService,
                            AssetQueryService assetQueryService,
                            CategoryQueryService categoryQueryService,
                            TransactionQueryService transactionQueryService) {
        this.accountQueryService = accountQueryService;
        this.assetQueryService = assetQueryService;
        this.categoryQueryService = categoryQueryService;
        this.transactionQueryService = transactionQueryService;
    }

    public DashboardDto getDashboard(Long userId) {
        var now = LocalDateTime.now();
        var monthStart = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        var monthEnd = now;

        // Total assets: account balances + asset market value
        BigDecimal accountTotal = accountQueryService.sumBalanceByUser(userId);
        List<Asset> assets = assetQueryService.listByUser(userId);

        BigDecimal assetTotal = assets.stream()
                .map(a -> {
                    BigDecimal price = a.getCurrentPrice() != null ? a.getCurrentPrice() : BigDecimal.ZERO;
                    return price.multiply(a.getQuantity());
                }).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalAssets = accountTotal.add(assetTotal);

        // Asset allocation
        List<DashboardDto.AssetAllocation> allocation = assets.stream()
                .filter(a -> {
                    BigDecimal price = a.getCurrentPrice() != null ? a.getCurrentPrice() : BigDecimal.ZERO;
                    return price.multiply(a.getQuantity()).compareTo(BigDecimal.ZERO) > 0;
                })
                .map(a -> {
                    BigDecimal val = a.getCurrentPrice().multiply(a.getQuantity());
                    double pct = totalAssets.compareTo(BigDecimal.ZERO) > 0
                            ? val.divide(totalAssets, 4, RoundingMode.HALF_UP).doubleValue() * 100
                            : 0;
                    return new DashboardDto.AssetAllocation(a.getName(), val, pct);
                }).toList();

        // Monthly income/expense
        BigDecimal monthIncome = transactionQueryService.sumByTypeAndDate(userId, "INCOME", monthStart, monthEnd);
        BigDecimal monthExpense = transactionQueryService.sumByTypeAndDate(userId, "EXPENSE", monthStart, monthEnd);
        BigDecimal netWorth = totalAssets; // V1: no liabilities tracking

        // Recent transactions
        List<Transaction> recentTransactions = transactionQueryService.listRecentByUser(userId, 5);
        Set<Long> accountIds = recentTransactions.stream()
                .map(Transaction::getAccountId)
                .collect(Collectors.toSet());
        Set<Long> categoryIds = recentTransactions.stream()
                .map(Transaction::getCategoryId)
                .collect(Collectors.toSet());
        Map<Long, String> accountNames = accountQueryService.mapNamesByUser(userId, accountIds);
        Map<Long, String> categoryNames = categoryQueryService.mapVisibleNamesByUser(userId, categoryIds);
        List<DashboardDto.RecentTransaction> recent = recentTransactions.stream()
                .map(tx -> new DashboardDto.RecentTransaction(
                        tx.getId(),
                        tx.getType(),
                        tx.getAmount(),
                        categoryNames.getOrDefault(tx.getCategoryId(), "未知分类"),
                        accountNames.getOrDefault(tx.getAccountId(), "未知账户"),
                        tx.getTransactedAt().toString()
                )).toList();

        return new DashboardDto(totalAssets, netWorth, monthIncome, monthExpense,
                monthIncome.subtract(monthExpense), allocation, recent);
    }
}
