# Architecture Decision Records

ADR 保存长期有效的架构和技术决策。ADR 独立于当前架构和历史阶段 Review；已接受 ADR 的日期、Decision 与 Trade-off 不因后续实现演进而重写。

## 索引

| ADR | 决策 |
| --- | --- |
| [ADR-000](ADR-000-Template.md) | 模板 |
| [ADR-001](ADR-001：为什么选择%20Java%2021.md) | 采用 Java 21 |
| [ADR-005](ADR-005：采用%20Flyway%20管理数据库迁移.md) | 使用 Flyway 管理 schema 演进 |
| [ADR-006](ADR-006-multi-currency-reference-valuation.md) | 多币种参考估值与 FX snapshot |
| [ADR-007](ADR-007-investment-ledger-foundation.md) | 投资账本与受控 Asset 投影 |
| [ADR-008](ADR-008-account-balance-concurrency-and-lock-ordering.md) | Account balance 并发与锁顺序 |
| [ADR-009](ADR-009-investment-instrument-and-account-binding.md) | Instrument 与 Account / Asset 绑定 |
| [ADR-010](ADR-010-legacy-opening-position-migration.md) | Legacy opening migration |
| [ADR-011](ADR-011-investment-buy-sell-write-path.md) | BUY / SELL 写路径 |
| [ADR-012](ADR-012-dividend-write-path.md) | DIVIDEND 写路径 |
| [ADR-013](ADR-013-append-only-investment-reversal.md) | append-only reversal |
| [ADR-014](ADR-014-append-only-investment-transaction-replacement.md) | append-only replacement |
| [ADR-015](ADR-015-investment-read-model-contract.md) | 投资读取模型、逻辑交易与 audit timeline |

编号 002–004 当前没有对应已跟踪 ADR；不为填补编号而创建空文件。

当前系统综合说明见 [Current Architecture](../architecture/current-architecture.md)，当前状态见 [STATUS](../STATUS.md)。
