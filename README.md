# Personal Finance OS

> **Current status:** `v3.0 Phase 2B Investment Write Foundation: CLOSED — GO`
> **Next phase:** `Phase 2C-1 Investment Read Model Contract — NOT STARTED`

Personal Finance OS is an engineered personal-finance system built with **Java 21, Spring Boot 3, PostgreSQL, React, and TypeScript**. Users maintain their own financial facts; investment activity is recorded in an **append-only investment ledger** and produces projections through **deterministic replay**, with idempotency and concurrency safety enforced on the backend.

It records and manages financial information. It does **not** execute real payments, bank transfers, or securities trades.

## Verified static demo

[Open the verified demo](https://personal-finance-os-demo.qianlixunbai.chatgpt.site/#/login)

The demo uses fictional data and is static, read-only presentation content. It has no real backend and no live market-data provider. It must not be interpreted as a completed investment write-path UI.

## What is implemented

- User registration/login and JWT-protected user data;
- accounts, categories, CNY asset snapshots, ordinary `INCOME`, `EXPENSE`, and `ADJUSTMENT` transactions, and Dashboard aggregation;
- Java/Spring Boot REST backend with PostgreSQL, Flyway migrations, React + TypeScript frontend, OpenAPI, and Docker Compose development/deployment entry points;
- investment write foundation: user-scoped Instruments; unique Account/Instrument/Asset binding; legacy opening migration; `BUY`, `SELL`, and `DIVIDEND` posting; weighted-average-cost full-history replay; atomic `Account.balance` updates; transaction-driven Asset projection; append-only reversal and replacement correction; immutable receipts and audit facts; idempotent recovery and deterministic locking.

## Verification baseline

The Phase 2B closure baseline is **92 suites / 496 tests / 0 failures / 0 errors**, recorded against **PostgreSQL 17.10** with the **Flyway V13** migration/runtime verification. See the [Phase 2B Overall Closing Review](docs/review/V3.0-Phase2B-Closing-Review.md) for the historical evidence and scope.

## Not implemented

- Portfolio read model or API;
- user-facing InvestmentTransaction query, detail, or audit timeline;
- investment frontend, position detail, or correction UI;
- `TRANSFER` / `REFUND` ordinary transactions;
- historical price and position snapshots, return curves, multi-currency accounting, FIFO/lot accounting, or corporate actions;
- automatic bank/broker synchronization, AI agent capabilities, or native mobile apps.

## Project links

- [Documentation center](docs/README.md)
- [Frozen Architecture](docs/03-Architecture/Architecture.md)
- [Roadmap](docs/Roadmap.md)
- [SRS](docs/SRS.md)
- [ADR records](docs/ADR/)
- [Review records](docs/review/)

## Local development

Prerequisites: Java 21, Node.js/npm, PostgreSQL, `DB_USERNAME`, `DB_PASSWORD`, and a `JWT_SECRET` of at least 32 characters. `MIGRATION_PREVIEW_SECRET` must be a distinct value of at least 32 characters. `DB_URL` is optional and defaults to `jdbc:postgresql://localhost:5432/finance_os`.

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

```powershell
cd frontend
npm install
npm run dev
```

For local API documentation after the backend starts:

```text
http://localhost:8080/swagger-ui.html
http://localhost:8080/v3/api-docs
```

The project is a personal-finance record system, not a production payment, brokerage, or investment-execution service.
