# Personal Finance OS 文档中心

> v2.1 Phase 4 Assets UI is implemented: Asset responses display cached, read-only reference valuations beside manual valuations. The UI does not calculate financial values; it maps freshness/warnings safely and provides per-asset `POST /api/v1/assets/{id}/reference-valuation/refresh`. Dashboard remains unchanged, real FX providers remain disabled by default, and v2.1 closure awaits independent review.

## 1. 阅读说明

- 根目录 [README.md](../README.md) 是项目首页和作品集入口。
- 当前文件是 `docs` 目录的文档导航页。
- 文档用于记录需求、架构、数据库、API、业务规则、开发规范、Review、ADR 和 Roadmap。
- 文档内容必须区分“已实现”和“规划中”，不要把未来能力写成已完成能力。

## 2. 推荐阅读顺序

1. [根目录 README.md](../README.md)
2. [Architecture.md](./03-Architecture/Architecture.md)
3. [Database.md](./03-Architecture/Database.md)
4. [API.md](./03-Architecture/API.md)
5. [Business Rules.md](./Business%20Rules.md)
6. [Financial Rules.md](./Financial%20Rules.md)
7. [review/](./review/)

## 3. 核心设计文档

- [Architecture](./03-Architecture/Architecture.md)：系统架构、模块边界、技术选型与架构约束。
- [Database](./03-Architecture/Database.md)：数据库表结构、字段说明、关系与 Known Gaps。
- [API](./03-Architecture/API.md)：当前 API 基线、统一响应、分页规则、认证规则和前端接入状态。
- [Business Rules](./Business%20Rules.md)：账户、分类、资产、流水等核心业务规则。
- [Financial Rules](./Financial%20Rules.md)：金额、余额、资产估值、收益率等金融计算规则。

## 4. 需求与项目规划

- [Project Vision](./Project%20Vision.md)：项目愿景与产品边界。
- [SRS](./SRS.md)：软件需求规格说明。
- [SRS 详解](./SRS详解.md)：需求说明补充材料。
- [Roadmap](./Roadmap.md)：阶段性路线图。

## 5. 开发规范与 AI 协作

- [Development Guide](./Development-Guide.md)：开发流程、工程实践与本地开发约定。
- [Deployment Guide](./Deployment-Guide.md)：Docker Compose 单机部署、健康检查、数据卷与 smoke checklist。
- [AI Rules](./AI-Rules.md)：AI 辅助开发边界和协作规则。
- [Prompt Guide](./Prompt%20Guide.md)：提示词协作指南。
- [Code Review Checklist](./Code%20Review%20Checklist.md)：代码审查检查清单。
- [Definition of Done](./Definition%20of%20Done.md)：任务完成标准。

## 6. Review / Fix Plan

- [review/](./review/)：阶段性 Review、修复计划和实现检查记录。
- [Architecture Review](./review/2026-07-06-architecture-review.md)
- [Database Review](./review/Database-Review.md)
- [API Review](./review/API-Review.md)
- [P2 Fix Plan](./review/P2-Fix-Plan.md)
- [Exception Handling Review](./review/Exception-Handling-Review.md)
- [V1.0 Closing Review](./review/V1.0-Closing-Review.md)
- [V1.1 Closing Review](./review/V1.1-Closing-Review.md)
- [V1.2 Closing Review](./review/V1.2-Closing-Review.md)
- [V1.3 Closing Review](./review/V1.3-Closing-Review.md)
- [V1.4 Quality Hardening Closing Review](./review/V1.4-Closing-Review.md)

## 7. ADR / Logs / 其它

- [ADR](./ADR/)：架构决策记录。
- [ADR Template](./ADR/ADR-000-Template.md)：ADR 模板。
- [ADR-001：为什么选择 Java 21](./ADR/ADR-001：为什么选择%20Java%2021.md)
- [ADR-005：采用 Flyway 管理数据库迁移](./ADR/ADR-005：采用%20Flyway%20管理数据库迁移.md)
- [logs/](./logs/)：开发过程日志。
- [decisions/](./decisions/)：决策相关文档入口。
- [meeting/](./meeting/)：会议记录入口。
- [项目结构](./项目结构.md)：项目目录结构说明。

## 8. 文档维护原则

- 不把未来能力写成已实现能力。
- `Architecture.md` 已冻结，架构级变化应通过 ADR 记录。
- `API.md`、`Database.md`、`Business Rules.md` 需要随实现变化保持同步。
- Review 文档用于记录阶段性检查、问题判断、修复计划和修复结论。
- 根目录 `README.md` 负责作品集展示；当前文件只负责文档导航。
