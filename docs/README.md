# 文档中心

本目录按三类事实组织：

- **Current Truth**：当前产品、架构、规则与工程实践；
- **Long-term Decision**：长期有效的架构决策；
- **Historical Evidence**：阶段设计、Review 和开发日志的历史快照。

当前状态只在 [STATUS](STATUS.md) 维护。历史文档中的 `CLOSED`、`GO`、`NO-GO`、测试数字和阶段名称保留其当时含义，不代表当前状态。

## 我想知道什么

| 目标 | 入口 |
| --- | --- |
| 项目现在做到哪 | [STATUS](STATUS.md) |
| 产品为什么存在 | [产品愿景](product/vision.md) |
| 当前需要支持什么 | [产品需求](product/requirements.md) |
| 当前范围和明确不做什么 | [Scope and Non-goals](product/scope-and-non-goals.md) |
| 未来可能往哪里走 | [Roadmap](product/roadmap.md) |
| 系统现在如何设计 | [Current Architecture](architecture/current-architecture.md) |
| v1.0 冻结架构是什么 | [Frozen v1.0 Architecture](architecture/frozen-v1.0-architecture.md) |
| 数据库当前有哪些事实 | [Database](architecture/database.md) |
| 当前有哪些公开接口 | [API](architecture/api.md) |
| 安全边界是什么 | [Security](architecture/security.md) |
| 事务与一致性如何保证 | [Consistency](architecture/consistency.md) |
| 查询业务规则 | [Business Rules](domain/business-rules.md) |
| 查询金额和舍入口径 | [Financial Rules](domain/financial-rules.md) |
| 查询投资账本规则 | [Investment Ledger](domain/investment-ledger.md) |
| 查询普通流水导入规则 | [Transaction Import](domain/transaction-import.md) |
| 本地开发与验证 | [Development](engineering/development.md) |
| 部署边界 | [Deployment](engineering/deployment.md) |
| 测试策略 | [Testing](engineering/testing.md) |
| 仓库结构 | [Project Structure](engineering/project-structure.md) |
| 为什么做某项长期决策 | [ADR](ADR/README.md) |
| 某阶段当时如何设计 | [Archive Design](archive/design/) |
| 某阶段是否通过验收 | [Archive Review](archive/review/README.md) |
| 查看早期排障记录 | [Archive Logs](archive/logs/README.md) |
| Agent / Codex 协作规则 | [Internal](internal/) |

## 生命周期规则

1. 当前状态只写入 `STATUS.md`；其他活文档只做摘要并链接。
2. 产品、架构、领域和工程文档描述当前可执行事实，不记录阶段流水。
3. ADR 独立保存长期决策，不因实现演进而改写原始 Decision 与 Trade-off。
4. `archive/design/` 保存实施前设计，`archive/review/` 保存验收证据，`archive/logs/` 保存过程记录。
5. 代码、Flyway Migration、配置和当前 API 与文档冲突时，以实现为事实依据；若实现违反已冻结决策，应记录不一致而不是擅改历史。

根 [README](../README.md) 面向仓库访客；本文件只负责导航。
