package com.financeos.module.account.service;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AccountBalanceService {

    private final AccountMapper accountMapper;
    private final String lockTimeout;

    public AccountBalanceService(AccountMapper accountMapper,
                                 @Value("${account.balance.lock-timeout:4s}") String lockTimeout) {
        this.accountMapper = accountMapper;
        this.lockTimeout = lockTimeout;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LockedAccounts lockOwnedAccounts(Long userId, Collection<Long> accountIds) {
        requireTransaction();
        if (userId == null || userId <= 0 || accountIds == null) {
            throw new IllegalArgumentException("userId and accountIds are required");
        }
        List<Long> orderedIds = accountIds.stream()
                .peek(accountId -> {
                    if (accountId == null || accountId <= 0) {
                        throw new IllegalArgumentException("accountId must be positive");
                    }
                })
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (orderedIds.isEmpty()) {
            return new LockedAccounts(List.of());
        }
        accountMapper.configureLocalLockTimeout(lockTimeout);
        List<Account> lockedAccounts = accountMapper.selectOwnedForUpdate(userId, orderedIds);
        if (lockedAccounts.size() != orderedIds.size()) {
            throw new BusinessException(404, "账户不存在");
        }
        return new LockedAccounts(lockedAccounts);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, BigDecimal> applyDeltas(LockedAccounts lockedAccounts,
                                             Collection<AccountBalanceMutation> mutations) {
        requireTransaction();
        if (lockedAccounts == null || mutations == null) {
            throw new IllegalArgumentException("lockedAccounts and mutations are required");
        }
        Map<Long, MutationSummary> summaries = new LinkedHashMap<>();
        for (AccountBalanceMutation mutation : mutations) {
            if (mutation == null || mutation.accountId() == null || mutation.delta() == null) {
                throw new IllegalArgumentException("a balance mutation must contain accountId and delta");
            }
            if (lockedAccounts.account(mutation.accountId()) == null) {
                throw new IllegalArgumentException("mutation account is not locked");
            }
            summaries.merge(mutation.accountId(), new MutationSummary(mutation.delta(), mutation.requireActive()),
                    MutationSummary::merge);
        }

        Map<Long, BigDecimal> resultingBalances = new LinkedHashMap<>();
        for (Account account : lockedAccounts.accounts()) {
            MutationSummary summary = summaries.get(account.getId());
            if (summary == null) {
                continue;
            }
            if (summary.requireActive() && !"ACTIVE".equals(account.getStatus())) {
                throw new BusinessException(400, "停用账户不允许新增流水");
            }
            BigDecimal newBalance = account.getBalance().add(summary.delta());
            if (summary.delta().compareTo(BigDecimal.ZERO) != 0
                    && accountMapper.updateBalance(account.getUserId(), account.getId(), newBalance) != 1) {
                throw new IllegalStateException("account balance update did not affect exactly one row");
            }
            account.setBalance(newBalance);
            resultingBalances.put(account.getId(), newBalance);
        }
        return Map.copyOf(resultingBalances);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("an active transaction is required");
        }
    }

    private record MutationSummary(BigDecimal delta, boolean requireActive) {
        private MutationSummary merge(MutationSummary other) {
            return new MutationSummary(delta.add(other.delta), requireActive || other.requireActive);
        }
    }
}
