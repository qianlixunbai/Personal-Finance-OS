# ADR-008: Account Balance Concurrency and Lock Ordering

## Status

Accepted for v3.0 Phase 2A.

## Decision

Ordinary transaction commands use PostgreSQL pessimistic row locks. The global order is existing fact row, then deduplicated `Account` rows in ascending `accountId`, then future projections. Ownership is part of every locking query (`user_id` and resource ID), so missing and cross-user resources both produce 404.

`AccountBalanceService`, owned by the Account module, is the only production writer of `Account.balance`. It exposes a controlled `LockedAccounts` handle and executes with `Propagation.MANDATORY`. Transaction create locks its account; update and delete lock the transaction first and then the relevant accounts. Account metadata and status changes take the same account lock and use field-level SQL so they cannot overwrite a concurrent balance.

Each transaction sets a transaction-local PostgreSQL `lock_timeout` (default `4s`). Lock timeout and deadlock-victim database errors are returned as HTTP 409 with the sanitized message `并发操作冲突，请重试`; there is no automatic retry.

## Consequences

Transaction facts and balance deltas share one outer transaction. Negative CNY balances and the existing INCOME, EXPENSE, and signed ADJUSTMENT semantics are unchanged. Optimistic versions, Redis, MQ, and retries are not introduced. Future InvestmentTransaction, Transfer, and Asset writers must reuse this order instead of adding an Account-to-fact locking path.
