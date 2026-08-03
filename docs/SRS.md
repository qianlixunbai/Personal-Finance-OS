# SRS — Software Requirements Specification

> **Current status:** `v3.0 Phase 2B Investment Write Foundation: CLOSED — GO`
> **Next phase:** `Phase 2C-1 Investment Read Model Contract — NOT STARTED`

## 1. Purpose and scope

Personal Finance OS is a Java 21 / Spring Boot 3 / PostgreSQL / React + TypeScript personal-finance system. It enables an authenticated user to maintain financial facts and view supported derived information. This SRS is the active requirements baseline; historical design decisions and closure evidence are recorded separately in [ADR](ADR/) and [Review](review/).

The system is a record-management product. It does not execute real payments, transfers, or securities transactions.

## 2. Functional requirements

### 2.1 Identity and data isolation

- The system shall support registration and login.
- Protected operations shall require JWT authentication.
- A user shall access only their own financial data.

### 2.2 Accounts, categories, and ordinary transactions

- The system shall support accounts, user categories, CNY asset snapshots, and ordinary transactions.
- Ordinary transactions currently support `INCOME`, `EXPENSE`, and `ADJUSTMENT`.
- `INCOME` and `EXPENSE` amounts shall be positive; an `ADJUSTMENT` amount shall be non-zero and include a reason.
- Account balances shall be updated by backend-controlled transactional logic.
- `TRANSFER` and `REFUND` are not implemented requirements in this baseline.

### 2.3 Dashboard

- The system shall aggregate supported account, asset, and ordinary-transaction data for Dashboard presentation.
- Frontend presentation shall not independently recalculate authoritative financial results.

### 2.4 Investment write foundation

- Investment transactions shall be represented as append-only facts.
- The backend shall support user-scoped Instruments and unique Account/Instrument/Asset binding.
- The backend shall support legacy opening-position migration and type-specific `BUY`, `SELL`, and `DIVIDEND` posting.
- Investment projections shall use deterministic full-history replay with weighted-average cost.
- The backend shall atomically maintain the affected Account balance and transaction-driven Asset projection.
- The backend shall support append-only standalone reversal and replacement correction, preserving immutable original facts, receipts, and audit records.
- The backend shall provide idempotency recovery and concurrency-safe locking for investment write commands.

### 2.5 Investment reads and UI

- Portfolio-specific read model/API, InvestmentTransaction user query/detail/audit timeline, investment frontend, position detail, and correction UI are not implemented.
- `Phase 2C-1 Investment Read Model Contract` is not started. It will define high-level read semantics before read APIs or frontend scope is implemented.

## 3. Non-functional requirements

### 3.1 Integrity and determinism

- Financial write rules and authoritative projections shall be enforced in the backend.
- Investment-ledger projections shall be reproducible by deterministic replay.
- Append-only facts, idempotency, transaction boundaries, and deterministic lock ordering shall protect the write path.

### 3.2 Security

- Passwords shall be protected using BCrypt.
- JWT authentication, parameter validation, and user isolation shall protect application data.

### 3.3 Operations and verification

- Schema evolution shall use Flyway migrations on PostgreSQL.
- The Phase 2B closure evidence is 92 suites / 496 tests / 0 failures / 0 errors, with PostgreSQL 17.10 and Flyway V13 migration/runtime verification. This is a historical closure baseline, not a claim that tests were rerun by a documentation change.

## 4. Product boundaries and deferred scope

The following are explicitly outside the current implementation: return curves, historical price or position snapshots, multi-currency accounting, FIFO/lot accounting, corporate actions, automatic bank/broker synchronization, AI agent capabilities, and native mobile apps.

The static [demo](https://personal-finance-os-demo.qianlixunbai.chatgpt.site/#/login) uses fictional data, is read-only, has no real backend or market-data provider, and does not demonstrate an investment write-path UI.

## 5. Requirement governance

- Architecture is frozen in [Architecture](03-Architecture/Architecture.md); architecture-level changes require an ADR.
- Detailed domain rules are maintained in [Business Rules](Business%20Rules.md) and [Financial Rules](Financial%20Rules.md).
- The [Requirements Index](SRS详解.md) maps this SRS to related requirements and evidence.
- Changes to implemented scope require aligned SRS, Roadmap, API/Database documentation where applicable, and verification evidence.
