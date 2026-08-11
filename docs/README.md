# 文档中心

> **Phase 2B：CLOSED — GO**
> **Phase 2C-1：CLOSED — GO（仅契约）**
> **Phase 2C-2A：CLOSED — GO（内部读取基础）**
> **Phase 2C-2B：CLOSED — GO（Portfolio & Position Read API）**
> **Phase 2C-2C：CLOSED — GO（Transaction & Audit Read API）**
> **Phase 2C-3：NOT STARTED（Investment Read Frontend）**

本目录以当前实现为准。阅读顺序建议为：根 README → [项目愿景](Project%20Vision.md) → [路线图](Roadmap.md) → 冻结架构与 ADR → Database/API → 业务与金融规则 → 开发/部署指南 → 当前阶段 Closing Review。

## 当前活文档

- [项目愿景](Project%20Vision.md)、[路线图](Roadmap.md)、[SRS](SRS.md)、[需求索引](SRS详解.md)
- [Database](03-Architecture/Database.md)、[API](03-Architecture/API.md)
- [业务规则](Business%20Rules.md)、[金融规则](Financial%20Rules.md)
- [开发指南](Development-Guide.md)、[部署指南](Deployment-Guide.md)、[项目结构](项目结构.md)
- [Definition of Done](Definition%20of%20Done.md)、[Code Review Checklist](Code%20Review%20Checklist.md)、[AI Rules](AI-Rules.md)、[Prompt Guide](Prompt%20Guide.md)

## 冻结与历史记录

[Architecture](03-Architecture/Architecture.md) 是 Reviewed / Frozen 的 v1.0 架构基线，不因当前实现而重写。当前架构演进由 [ADR](ADR/) 和 Closing Review 表达。`design/`、`logs/`、`meeting/`、`decisions/` 以及历史 Review 均保留当时事实，不是当前能力说明。

## ADR 与阶段验收

- 账户并发与锁：[ADR-008](ADR/ADR-008-account-balance-concurrency-and-lock-ordering.md)
- 投资演进：[ADR-007](ADR/ADR-007-investment-ledger-foundation.md) 至 [ADR-015](ADR/ADR-015-investment-read-model-contract.md)
- [Phase 2A](review/V3.0-Phase2A-Closing-Review.md)、[2B-1](review/V3.0-Phase2B-1-Closing-Review.md)、[2B-2](review/V3.0-Phase2B-2-Closing-Review.md)、[2B-3](review/V3.0-Phase2B-3-Closing-Review.md)
- [2B-4A](review/V3.0-Phase2B-4A-Closing-Review.md)、[2B-4B](review/V3.0-Phase2B-4B-Closing-Review.md)、[2B-5A](review/V3.0-Phase2B-5A-Closing-Review.md)、[2B-5B-1](review/V3.0-Phase2B-5B-1-Closing-Review.md)、[2B-5B-2](review/V3.0-Phase2B-5B-2-Closing-Review.md)
- [Phase 2B 聚合 Closing Review](review/V3.0-Phase2B-Closing-Review.md)
- Phase 2C-1：[读取模型契约](design/V3.0-Phase2C-1-Investment-Read-Model-Contract.md)、[ADR-015](ADR/ADR-015-investment-read-model-contract.md)、[Closing Review](review/V3.0-Phase2C-1-Closing-Review.md)
- Phase 2C-2A：[Internal Investment Read Foundation Closing Review](review/V3.0-Phase2C-2A-Closing-Review.md)
- Phase 2C-2B：[Portfolio & Position Read API Closing Review](review/V3.0-Phase2C-2B-Closing-Review.md)
- Phase 2C-2C：[Transaction & Audit Read API Closing Review](review/V3.0-Phase2C-2C-Closing-Review.md)

Opening migration 与 investment write path 已在 Phase 2B 实现；Phase 2C-1 已冻结读取契约，Phase 2C-2A 已实现内部读取基础，Phase 2C-2B 已公开 Portfolio 与 Position API，Phase 2C-2C 已公开 logical transaction 列表/详情和 audit timeline。Phase 2C-3 投资读取前端尚未开始。

## 文档语言与治理

- `zh-cn` 分支的当前活文档、ADR 和 Closing Review 以简体中文为主；
- 类名、字段、API、SQL、命令、正式阶段名称和标准技术术语可以保留英文；
- 历史文档的语言统一只改变叙述表达，不改变测试数字、HEAD、SHA、风险或阶段结论；
- README 负责项目展示，Database / API / Rules 负责当前技术查证，ADR 负责长期决策，Closing Review 负责验收证据；
- 临时 Agent 执行计划不作为长期正式文档，失去引用后应删除或归档。
