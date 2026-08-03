# Roadmap

> **Current status:** `v3.0 Phase 2B Investment Write Foundation: CLOSED — GO`
> **Next phase:** `Phase 2C-1 Investment Read Model Contract — NOT STARTED`

This roadmap distinguishes completed, verified scope from planned scope. It does not make future capabilities part of the current product.

## Completed foundations

- **v1.0–v1.5:** personal-finance foundation, product/UI polish, quality hardening, and local deployment readiness; see the [closing-review index](review/README.md).
- **v2.0:** market-data foundation for independently cached reference quotes.
- **v2.1:** read-only market reference valuation; it does not change CNY accounting facts or Dashboard accounting valuation.
- **v3.0 Phase 1:** investment ledger foundation and deterministic calculation/replay primitives.
- **v3.0 Phase 2A:** account-balance concurrency and lock ordering.

## v3.0 Phase 2B — Investment Write Foundation (closed)

Phase 2B is **CLOSED — GO**. Its implemented and verified scope is:

- investment projection precision and safety;
- user-scoped Instrument and unique Account/Instrument/Asset binding;
- legacy opening-position migration;
- type-specific `BUY`, `SELL`, and `DIVIDEND` posting;
- weighted-average-cost, full-history deterministic replay;
- atomic Account balance mutation and transaction-driven Asset projection;
- append-only standalone reversal and replacement correction;
- immutable receipts, audit facts, idempotency/unknown-commit recovery, deterministic locks, and lock timeout/deadlock mapping;
- PostgreSQL migrations through Flyway V13 and runtime verification.

The historical final baseline is **92 suites / 496 tests / 0 failures / 0 errors** on **PostgreSQL 17.10**. Detailed leaf-phase and aggregate evidence is in the [Phase 2B overall closing review](review/V3.0-Phase2B-Closing-Review.md).

Phase 2B accepts no expansion. Its closed leaf reviews are [2B-1](review/V3.0-Phase2B-1-Closing-Review.md), [2B-2](review/V3.0-Phase2B-2-Closing-Review.md), [2B-3](review/V3.0-Phase2B-3-Closing-Review.md), [2B-4A](review/V3.0-Phase2B-4A-Closing-Review.md), [2B-4B](review/V3.0-Phase2B-4B-Closing-Review.md), [2B-5A](review/V3.0-Phase2B-5A-Closing-Review.md), [2B-5B-1](review/V3.0-Phase2B-5B-1-Closing-Review.md), and [2B-5B-2](review/V3.0-Phase2B-5B-2-Closing-Review.md).

## v3.0 Phase 2C-1 — Investment Read Model Contract (not started)

Phase 2C-1 is **NOT STARTED**. It will establish only high-level semantics for Portfolio and InvestmentTransaction reads: current projection, effective history, and append-only audit history. It will not implement query APIs, investment frontend, providers, historical market data, multi-currency accounting, AI, or bulk migration.

## Explicitly not implemented

- Portfolio read model/API and InvestmentTransaction user query/detail/audit timeline;
- investment frontend, position detail, and correction UI;
- ordinary `TRANSFER` / `REFUND`;
- return curves, historical prices/snapshots, multi-currency accounting, FIFO/lot accounting, and corporate actions;
- automatic bank/broker synchronization, AI agent capabilities, and native mobile apps.

## Maintenance rule

Update this roadmap only with evidence from implementation, verified tests, ADRs, or Closing Reviews. Architecture-level changes follow the frozen [Architecture](03-Architecture/Architecture.md) baseline and require an ADR.
