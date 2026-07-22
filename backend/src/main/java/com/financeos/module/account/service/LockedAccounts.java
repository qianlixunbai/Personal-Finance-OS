package com.financeos.module.account.service;

import com.financeos.module.account.entity.Account;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;

public final class LockedAccounts {

    private final Map<Long, Account> accountsById;

    LockedAccounts(Collection<Account> accounts) {
        Map<Long, Account> ordered = new LinkedHashMap<>();
        accounts.forEach(account -> ordered.put(account.getId(), account));
        this.accountsById = Collections.unmodifiableMap(ordered);
    }

    public Account account(Long accountId) {
        return accountsById.get(accountId);
    }

    public List<Account> accounts() {
        return List.copyOf(accountsById.values());
    }
}
