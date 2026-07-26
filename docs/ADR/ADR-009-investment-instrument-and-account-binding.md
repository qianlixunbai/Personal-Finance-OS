# ADR-009: Investment Instrument and Account Binding

**Status:** Accepted
**Date:** 2026-07-26

## Decision

- `InvestmentInstrument` is user-scoped master data and has `user_id NOT NULL`.
- `Asset` remains the single current Position projection; no `investment_positions` table or second Position truth is introduced.
- Instrument identity is `UNIQUE (user_id, market, symbol)`. `market` is a canonical market-data namespace, not a specific exchange or MIC.
- A transaction-driven Position identity is `userId + accountId + instrumentId`. The database requires both bindings and allows only one such Position for a user, Account, and Instrument.
- LEGACY Assets may leave both binding fields null. They are neither inferred, backfilled, merged, nor converted.
- `InvestmentTransaction` keeps its existing `asset_id` relation and does not add `instrument_id`; immutable Asset binding is the indirect relation.
- Phase 2B-2 has no public Instrument, Position, or InvestmentTransaction write API; it does not run an Opening Migration and does not change `Account.balance`.
- No BUY, SELL, DIVIDEND, replay, calculator, Dashboard, or Reference Valuation behavior changes in this phase.

## Consequences

V7 fails before any DDL if a V6 database already contains a `TRANSACTION_DRIVEN` Asset. The migration does not guess an Instrument. V8 then establishes the additive schema binding only. Because ADR-007 defines `Asset` as a fact-driven projection, Phase 2B-2 exposes internal binding validation but deliberately does not create an empty transaction-driven Position without an InvestmentTransaction fact.
