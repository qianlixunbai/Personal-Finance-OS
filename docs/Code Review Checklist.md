# Code Review Checklist

## Scope and contracts

- Is the implementation limited to the approved requirement and compatible API/documentation contract?
- Are user isolation and security-safe cross-user `404` semantics preserved?
- Are validation, authentication, authorization, and error classification (`400/401/403/404/409/429/500/502/503`) appropriate?

## Financial and transactional correctness

- Are financial values represented with `BigDecimal`/database `NUMERIC`, never `double`?
- Is the transaction boundary complete: facts, Account balance, projection, receipt, and correction envelope commit or roll back together?
- Is `AccountBalanceService` still the only balance writer?
- Are append-only facts, immutable receipts, and database mutation guards preserved?
- Does replay remain deterministic, and is the Asset projection clearly distinguished from immutable posting snapshots?

## Concurrency and idempotency

- Does the change follow the declared lock order, including original-fact locking for correction work?
- Are lock timeout/deadlock responses, idempotency conflict/recovery, and unknown-commit recovery correct?
- Can concurrent commands double-apply a fact, cash delta, receipt, or projection version?

## Database and test evidence

- Are named constraints, composite foreign keys, partial unique indexes, triggers, and deferred integrity rules maintained where applicable?
- Do migration and Testcontainers tests cover new invariants and failure rollback?
- Do focused tests demonstrate both expected results and relevant rejected/rollback paths?

## Handoff

- Are active docs synchronized without rewriting frozen Architecture, ADRs, or historical Closing Reviews?
- Does `git diff --check` pass, and are unrelated or user-owned files excluded?
