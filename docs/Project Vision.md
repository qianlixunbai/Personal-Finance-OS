# Project Vision

> **Current status:** `v3.0 Phase 2B Investment Write Foundation: CLOSED — GO`
> **Next phase:** `Phase 2C-1 Investment Read Model Contract — NOT STARTED`

## Purpose

Personal Finance OS is an engineered system for an individual to maintain financial facts: accounts, ordinary transactions, assets, and investment-ledger facts. It uses Java 21, Spring Boot 3, PostgreSQL, React, and TypeScript to demonstrate a maintainable, tested personal-finance product rather than a generic CRUD sample.

The product treats investment transactions as append-only facts. Backend projections are rebuilt by deterministic replay; idempotency, user isolation, atomic balance mutation, and deterministic locking protect the write path. Financial computation remains a backend responsibility rather than a frontend aggregation concern.

## Product boundary

The system records, organizes, and presents a user's financial information. It does not process real payments, connect to a bank for automatic transfers, place or execute securities orders, perform brokerage custody, or offer financial advice.

The public [static demo](https://personal-finance-os-demo.qianlixunbai.chatgpt.site/#/login) is verified presentation material using fictional data. It is read-only, has no real backend or market-data provider, and is not evidence that an investment write-path UI exists.

## Current product capability

- Authentication and per-user data isolation;
- account, category, CNY asset snapshot, and ordinary transaction management;
- Dashboard aggregation from application data;
- append-only investment write foundation: Instrument and Account/Instrument/Asset binding, opening migration, `BUY`/`SELL`/`DIVIDEND`, weighted-average-cost replay, balance and Asset projections, reversal, replacement correction, receipts, idempotency recovery, and concurrency safety.

Phase 2B closure evidence is 92 suites / 496 tests / 0 failures / 0 errors, using PostgreSQL 17.10 and Flyway V13 migration/runtime verification. The [overall closing review](review/V3.0-Phase2B-Closing-Review.md) remains the authoritative closure record.

## Deliberate non-goals and future scope

Portfolio read model/API, user-facing InvestmentTransaction timeline, investment frontend, `TRANSFER`/`REFUND`, return curves, multi-currency accounting, FIFO/lot accounting, corporate actions, automatic bank/broker synchronization, AI agent capabilities, and native mobile apps are not implemented.

`Phase 2C-1 Investment Read Model Contract` is not started. Its scope is to freeze high-level read semantics for Portfolio and InvestmentTransaction; it does not itself implement query APIs, frontend screens, providers, or trading capabilities.

## Product principles

1. Financial facts are user-owned, isolated, and auditable.
2. Append-only investment facts take precedence over mutable shortcuts.
3. Derived projections must be reproducible through deterministic replay.
4. The backend owns financial rules and concurrency-sensitive mutations.
5. Scope expands only after a documented, verified phase boundary.

The architecture baseline is frozen in [Architecture](03-Architecture/Architecture.md); architecture-level changes require an ADR.
