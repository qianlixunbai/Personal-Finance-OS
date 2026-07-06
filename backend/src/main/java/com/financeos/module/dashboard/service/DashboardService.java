package com.financeos.module.dashboard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DashboardService {

    private final AccountMapper accountMapper;
    private final AssetMapper assetMapper;
    private final TransactionMapper transactionMapper;

    public DashboardService(AccountMapper accountMapper, AssetMapper assetMapper, TransactionMapper transactionMapper) {
        this.accountMapper = accountMapper;
        this.assetMapper = assetMapper;
        this.transactionMapper = transactionMapper;
    }

    public DashboardDto getDashboard(Long userId) {
        var now = LocalDateTime.now();
        var monthStart = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        var monthEnd = now;

        // Total assets: account balances + asset market value
        BigDecimal accountTotal = accountMapper.selectList(
                new LambdaQueryWrapper<Account>().eq(Account::getUserId, userId)
        ).stream().map(Account::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Asset> assets = assetMapper.selectList(
                new LambdaQueryWrapper<Asset>().eq(Asset::getUserId, userId));

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
        BigDecimal monthIncome = transactionMapper.sumByTypeAndDate(userId, "INCOME", monthStart, monthEnd);
        BigDecimal monthExpense = transactionMapper.sumByTypeAndDate(userId, "EXPENSE", monthStart, monthEnd);
        if (monthIncome == null) monthIncome = BigDecimal.ZERO;
        if (monthExpense == null) monthExpense = BigDecimal.ZERO;
        BigDecimal netWorth = totalAssets; // V1: no liabilities tracking

        // Recent transactions
        List<DashboardDto.RecentTransaction> recent = transactionMapper.selectList(
                new LambdaQueryWrapper<Transaction>()
                        .eq(Transaction::getUserId, userId)
                        .orderByDesc(Transaction::getTransactedAt)
                        .last("LIMIT 5")
        ).stream().map(tx -> new DashboardDto.RecentTransaction(
                tx.getId(), tx.getType(), tx.getAmount(),
                "", "", tx.getTransactedAt().toString()
        )).toList();

        return new DashboardDto(totalAssets, netWorth, monthIncome, monthExpense,
                monthIncome.subtract(monthExpense), allocation, recent);
    }
}
