# Requirements Index

> This is an index to the active [SRS](SRS.md), not a second and potentially divergent specification.

> **Current status:** `v3.0 Phase 2B Investment Write Foundation: CLOSED — GO`
> **Next phase:** `Phase 2C-1 Investment Read Model Contract — NOT STARTED`

## Core requirement map

| Requirement area | Authoritative SRS section | Supporting specification/evidence |
| --- | --- | --- |
| Product purpose and system boundary | [SRS 1](SRS.md#1-purpose-and-scope) | [Project Vision](Project%20Vision.md), [Architecture](03-Architecture/Architecture.md) |
| Authentication and user isolation | [SRS 2.1](SRS.md#21-identity-and-data-isolation) | [API](03-Architecture/API.md), [Business Rules](Business%20Rules.md) |
| Accounts, categories, and ordinary transactions | [SRS 2.2](SRS.md#22-accounts-categories-and-ordinary-transactions) | [Business Rules](Business%20Rules.md), [Financial Rules](Financial%20Rules.md), [ADR-008](ADR/ADR-008-account-balance-concurrency-and-lock-ordering.md) |
| Dashboard presentation boundary | [SRS 2.3](SRS.md#23-dashboard) | [API](03-Architecture/API.md), [Financial Rules](Financial%20Rules.md) |
| Append-only investment write foundation | [SRS 2.4](SRS.md#24-investment-write-foundation) | [ADR-007](ADR/ADR-007-investment-ledger-foundation.md), [ADR-009](ADR/ADR-009-investment-instrument-and-account-binding.md) through [ADR-014](ADR/ADR-014-append-only-investment-transaction-replacement.md) |
| Investment read/UI deferral | [SRS 2.5](SRS.md#25-investment-reads-and-ui) | [Roadmap](Roadmap.md), [Phase 2B overall closure](review/V3.0-Phase2B-Closing-Review.md) |
| Integrity, replay, idempotency, and locking | [SRS 3.1](SRS.md#31-integrity-and-determinism) | [Financial Rules](Financial%20Rules.md), [ADR-008](ADR/ADR-008-account-balance-concurrency-and-lock-ordering.md), [ADR-013](ADR/ADR-013-append-only-investment-reversal.md), [ADR-014](ADR/ADR-014-append-only-investment-transaction-replacement.md) |
| Security | [SRS 3.2](SRS.md#32-security) | [API](03-Architecture/API.md), [Development Guide](Development-Guide.md) |
| Migration and verification baseline | [SRS 3.3](SRS.md#33-operations-and-verification) | [Phase 2B overall closure](review/V3.0-Phase2B-Closing-Review.md) |
| Deferred scope and static demo boundary | [SRS 4](SRS.md#4-product-boundaries-and-deferred-scope) | [Root README](../README.md), [Roadmap](Roadmap.md) |
| Change control | [SRS 5](SRS.md#5-requirement-governance) | [ADR index](ADR/), [Documentation center](README.md) |

## Current baseline

Phase 2B is closed with GO. It provides the investment write foundation, not a portfolio read experience. The verified historical closure baseline is 92 suites / 496 tests / 0 failures / 0 errors on PostgreSQL 17.10 with Flyway V13 migration/runtime verification.

## Explicitly not requirements yet

Portfolio read model/API, InvestmentTransaction timeline, investment frontend, `TRANSFER`/`REFUND`, return curves, multi-currency accounting, FIFO/lot accounting, corporate actions, automatic bank/broker synchronization, AI agent functionality, and native mobile apps are deferred. `Phase 2C-1 Investment Read Model Contract` is not started.
