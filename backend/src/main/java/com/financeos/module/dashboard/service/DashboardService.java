package com.financeos.module.dashboard.service;

import com.financeos.module.account.service.AccountQueryService;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.service.AssetQueryService;
import com.financeos.module.category.service.CategoryQueryService;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.ledger.dto.MonthlyCashFlowAggregate;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.service.TransactionQueryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
    private final Clock clock;

    public DashboardService(AccountQueryService accountQueryService,
                            AssetQueryService assetQueryService,
                            CategoryQueryService categoryQueryService,
                            TransactionQueryService transactionQueryService,
                            Clock clock) {
        this.accountQueryService = accountQueryService;
        this.assetQueryService = assetQueryService;
        this.categoryQueryService = categoryQueryService;
        this.transactionQueryService = transactionQueryService;
        this.clock = clock;
    }

    public DashboardDto getDashboard(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        YearMonth currentMonth = YearMonth.from(now);
        LocalDateTime monthStart = currentMonth.atDay(1).atStartOfDay();

        // Total assets: account balances + asset market value
        BigDecimal accountTotal = accountQueryService.sumBalanceByUser(userId);
        List<Asset> assets = assetQueryService.listByUser(userId);

        BigDecimal assetTotal = assets.stream()
                .map(this::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalAssets = accountTotal.add(assetTotal);

        // Asset allocation
        List<InvestmentAssetValue> investmentAssets = assets.stream()
                .filter(asset -> asset.getCurrentPrice() != null
                        && asset.getQuantity() != null
                        && asset.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .map(asset -> new InvestmentAssetValue(asset.getName(), marketValue(asset)))
                .filter(asset -> asset.value().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        BigDecimal investmentAssetTotal = investmentAssets.stream()
                .map(InvestmentAssetValue::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<DashboardDto.AssetAllocation> allocation = investmentAssets.stream()
                .map(asset -> new DashboardDto.AssetAllocation(
                        asset.name(),
                        asset.value(),
                        asset.value().multiply(BigDecimal.valueOf(100))
                                .divide(investmentAssetTotal, 4, RoundingMode.HALF_UP)
                ))
                .toList();

        // Monthly income/expense
        BigDecimal monthIncome = transactionQueryService.sumByTypeAndDate(userId, "INCOME", monthStart, now);
        BigDecimal monthExpense = transactionQueryService.sumByTypeAndDate(userId, "EXPENSE", monthStart, now);
        List<DashboardDto.MonthlyCashFlow> monthlyCashFlowTrend = monthlyCashFlowTrend(userId, currentMonth, now);
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
                monthIncome.subtract(monthExpense), allocation, monthlyCashFlowTrend, recent);
    }

    private List<DashboardDto.MonthlyCashFlow> monthlyCashFlowTrend(
            Long userId, YearMonth currentMonth, LocalDateTime endExclusive) {
        YearMonth firstMonth = currentMonth.minusMonths(5);
        LocalDateTime startInclusive = firstMonth.atDay(1).atStartOfDay();
        Map<YearMonth, MonthlyCashFlowAggregate> aggregates = transactionQueryService
                .monthlyCashFlowByMonth(userId, startInclusive, endExclusive)
                .stream()
                .collect(Collectors.toMap(
                        aggregate -> YearMonth.from(aggregate.monthStart()),
                        aggregate -> aggregate,
                        (first, second) -> first
                ));

        return java.util.stream.IntStream.range(0, 6)
                .mapToObj(firstMonth::plusMonths)
                .map(month -> monthlyCashFlow(month, aggregates.get(month)))
                .toList();
    }

    private DashboardDto.MonthlyCashFlow monthlyCashFlow(YearMonth month, MonthlyCashFlowAggregate aggregate) {
        BigDecimal income = aggregate != null && aggregate.income() != null ? aggregate.income() : BigDecimal.ZERO;
        BigDecimal expense = aggregate != null && aggregate.expense() != null ? aggregate.expense() : BigDecimal.ZERO;
        return new DashboardDto.MonthlyCashFlow(month.toString(), income, expense, income.subtract(expense));
    }

    private BigDecimal marketValue(Asset asset) {
        if (asset.getCurrentPrice() == null || asset.getQuantity() == null) {
            return BigDecimal.ZERO;
        }
        return asset.getCurrentPrice().multiply(asset.getQuantity());
    }

    private record InvestmentAssetValue(String name, BigDecimal value) {
    }
}
