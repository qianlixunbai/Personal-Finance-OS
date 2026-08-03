# Personal Finance OS documentation

> **Current status:** `v3.0 Phase 2B Investment Write Foundation: CLOSED — GO`
> **Next phase:** `Phase 2C-1 Investment Read Model Contract — NOT STARTED`

This index separates live product documents, the frozen architecture baseline, and historical decision/review records. Future work must not be described as implemented.

## Live product documents

- [Root README](../README.md): product entry point, verified static demo, current implementation boundary, and local start instructions.
- [Project Vision](./Project%20Vision.md): product purpose and explicit non-goals.
- [SRS](./SRS.md): current requirements baseline and scope.
- [Requirements Index](./SRS详解.md): links to the relevant SRS sections and implementation boundary.
- [Roadmap](./Roadmap.md): completed phases, current closure, and next-phase boundary.
- [Business Rules](./Business%20Rules.md) and [Financial Rules](./Financial%20Rules.md): current domain and calculation rules.
- [Development Guide](./Development-Guide.md) and [Deployment Guide](./Deployment-Guide.md): engineering and local deployment guidance.

## Frozen architecture baseline

- [Architecture](./03-Architecture/Architecture.md) is frozen. Architecture-level changes require an ADR.
- [Database](./03-Architecture/Database.md) and [API](./03-Architecture/API.md) describe the implementation baseline; they do not make unimplemented read-model or frontend capabilities available.
- [V3.0 Investment Ledger Foundation Design](./design/V3.0-Investment-Ledger-Foundation-Design.md) is historical design context for the ledger foundation.

## ADR history

- [ADR-001: Java 21](./ADR/ADR-001：为什么选择%20Java%2021.md)
- [ADR-005: Flyway migrations](./ADR/ADR-005：采用%20Flyway%20管理数据库迁移.md)
- [ADR-006: multi-currency reference valuation](./ADR/ADR-006-multi-currency-reference-valuation.md)
- [ADR-007: investment ledger foundation](./ADR/ADR-007-investment-ledger-foundation.md)
- [ADR-008: account-balance concurrency and lock ordering](./ADR/ADR-008-account-balance-concurrency-and-lock-ordering.md)
- [ADR-009: investment instrument and account binding](./ADR/ADR-009-investment-instrument-and-account-binding.md)
- [ADR-010: legacy opening-position migration](./ADR/ADR-010-legacy-opening-position-migration.md)
- [ADR-011: investment BUY/SELL write path](./ADR/ADR-011-investment-buy-sell-write-path.md)
- [ADR-012: dividend write path](./ADR/ADR-012-dividend-write-path.md)
- [ADR-013: append-only investment reversal](./ADR/ADR-013-append-only-investment-reversal.md)
- [ADR-014: append-only investment-transaction replacement](./ADR/ADR-014-append-only-investment-transaction-replacement.md)

## Closing-review history

- [Review index](./review/README.md)
- [V1.0](./review/V1.0-Closing-Review.md), [V1.1](./review/V1.1-Closing-Review.md), [V1.2](./review/V1.2-Closing-Review.md), [V1.3](./review/V1.3-Closing-Review.md), [V1.4](./review/V1.4-Closing-Review.md), [V1.5](./review/V1.5-Closing-Review.md), [V2.0](./review/V2.0-Closing-Review.md), and [V2.1](./review/V2.1-Closing-Review.md)
- [V3.0 Phase 1](./review/V3.0-Phase1-Closing-Review.md) and [Phase 2A](./review/V3.0-Phase2A-Closing-Review.md)
- Phase 2B: [2B-1](./review/V3.0-Phase2B-1-Closing-Review.md), [2B-2](./review/V3.0-Phase2B-2-Closing-Review.md), [2B-3](./review/V3.0-Phase2B-3-Closing-Review.md), [2B-4A](./review/V3.0-Phase2B-4A-Closing-Review.md), [2B-4B](./review/V3.0-Phase2B-4B-Closing-Review.md), [2B-5A](./review/V3.0-Phase2B-5A-Closing-Review.md), [2B-5B-1](./review/V3.0-Phase2B-5B-1-Closing-Review.md), [2B-5B-2](./review/V3.0-Phase2B-5B-2-Closing-Review.md), and [overall closure](./review/V3.0-Phase2B-Closing-Review.md).

## Supporting records

- [Review and fix-plan records](./review/)
- [Decision index](./decisions/)
- [Development logs](./logs/)
- [Meeting records](./meeting/)
- [Project structure](./项目结构.md)
