# ADR-010: Legacy opening-position migration

## Status

Accepted for v3.0 Phase 2B-3.

## Decision

Only one Legacy Asset is previewed and confirmed at a time. The caller explicitly selects an existing CNY Account and an ACTIVE, user-owned InvestmentInstrument. The migration writes one POSTED `OPENING_POSITION` fact and rebuilds the existing Asset projection through the ledger replay engine.

`Asset.totalCost` is authoritative when present. Only a null value is derived from `round(quantity * avgCost, 2, HALF_UP)`. The resulting scale-8 unit price must reproduce that cost exactly through the existing calculator. Zero or invalid holdings remain `LEGACY` and cannot be migrated.

Preview is read-only. Confirm requires a signed, expiring preview token and `X-Idempotency-Key`; it locks Account, Instrument, then Asset in one transaction, does not mutate `Account.balance`, and performs a production consistency check before commit. A successful Asset becomes `TRANSACTION_DRIVEN` and cannot be edited back through the Legacy write path.

## Consequences

V9 protects one posted opening fact per Asset and requires an investment fact's Account binding to match its Asset. This phase does not add a generic investment write API, BUY/SELL/DIVIDEND, reversal/replacement, Portfolio, batch migration, UI workflow, or automatic Instrument/Account selection.
