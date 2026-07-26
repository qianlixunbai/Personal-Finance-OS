# Personal Finance OS

> v3.0 Phase 1 Investment Ledger Foundation 已完成并正式关闭，最终结论为 GO。首次独立验收发现的 4 项 P1 已通过定向修复和独立聚焦复验全部关闭；V4 保持不变，V5 加固金额恒等式和 replacement 隔离。没有公开投资交易 API、真实 BUY/SELL 写入、账户余额联动或前端改动。

[![CI](https://github.com/qianlixunbai/Personal-Finance-OS/actions/workflows/ci.yml/badge.svg?branch=zh-cn)](https://github.com/qianlixunbai/Personal-Finance-OS/actions/workflows/ci.yml)

基于 **Java 21、Spring Boot 3、React 和 PostgreSQL** 的工程化个人财务管理系统，用于管理账户、资产与交易流水，并通过 Dashboard 聚合真实业务数据。它不是简单 CRUD Demo：项目强调用户隔离、金融规则、数据库迁移、端到端验证与持续文档收口。

| 工程指标 | 当前状态 |
| --- | --- |
| Backend tests | automated; run `backend\\mvnw.cmd test` for the current count |
| Frontend tests | 19/19 |
| Java | 21 |
| PostgreSQL | 17.10 |
| Market Data | US `STOCK` / `ETF` 独立参考行情 |
| 项目规模 | 约 8.7k 行有效代码（不含文档、依赖与构建产物） |

## 项目入口

- [GitHub 仓库](https://github.com/qianlixunbai/Personal-Finance-OS)
- [Swagger / OpenAPI 本地访问说明](#启动后端)
- [核心架构文档](docs/03-Architecture/Architecture.md)
- [v2.0 Market Data Foundation Closing Review](docs/review/V2.0-Closing-Review.md)
- [v2.1 Market Valuation Closing Review](docs/review/V2.1-Closing-Review.md)
- 在线 Demo：当前未提供公开后端地址，避免展示不可验证的 API 链接；静态展示不调用真实 Market Data API。

## 项目亮点

- 模块化单体架构，按业务模块组织后端代码
- 前后端分离，后端提供 REST API，前端使用 React + Vite
- JWT 认证，业务接口默认需要 `Authorization: Bearer <token>`
- 已接入 OpenAPI 3 与 Swagger UI，支持 JWT Bearer Authorize
- 统一 API 响应模型 `ApiResponse<T>` 与分页模型 `PageResult<T>`
- 已实现账户、资产、分类、Transaction / Ledger 基础管理能力
- Transaction / Ledger 创建、编辑、删除会联动账户余额
- Dashboard 聚合账户、资产、流水等真实业务数据
- 文档驱动开发，Architecture / Database / API 文档持续同步
- 使用 Flyway 管理 PostgreSQL schema 演进
- 数据库结构由版本化 migration 管理
- Testcontainers 在空 PostgreSQL 17 容器中验证 V1 migration
- v2.0 Market Data Foundation 已完成：仅支持 US `STOCK` / `ETF` 的独立参考行情快照
- 行情只可手动刷新；页面加载仅读取缓存，不参与 CNY 资产或 Dashboard 估值
- 后端自动化测试与前端 14 项测试覆盖关键业务与展示边界，并通过 lint 和生产构建验证
- 保留 Review / Fix Plan 记录，体现设计、实现、评审、修复闭环

## 技术栈

**后端**

- Java 21
- Spring Boot 3
- Maven Wrapper
- MyBatis-Plus
- PostgreSQL
- Flyway 10.20.0
- Spring Security
- JWT

**前端**

- React
- TypeScript
- Vite
- Axios
- React Router

## 当前已实现功能

- 用户注册 / 登录
- JWT 鉴权
- 账户管理
- 分类基础能力
- 资产持仓快照管理
- Transaction / Ledger 基础 CRUD
- 流水创建、编辑、删除时联动账户余额
- Dashboard 聚合账户、资产、流水数据
- Dashboard 投资资产分布、本月收支和最近 6 个月收支趋势图
- 前端页面：`Login`、`Register`、`Dashboard`、`Accounts`、`Assets`、`Transactions`
- 后端分页接口
- 登录 / 注册展示后端错误信息
- Transactions 类型中文化
- Transactions / Dashboard 金额格式统一
- Accounts 接入编辑入口
- Assets 接入详情、删除、清仓入口
- 前端统一空状态和反馈提示
- 前端共享 `PageResult<T>`、`PageHeader` 和受控展示型 `Pagination`
- Accounts、Assets、Transactions 已复用共享页面布局和分页能力；分页业务逻辑、筛选和删除回退仍保留在页面内
- Pagination 上一页 / 下一页按钮显式使用 `type="button"`
- 后端使用 PostgreSQL Testcontainers 进行真实数据库集成测试；六个 Controller 的核心 HTTP 契约已覆盖
- 数据库初始化已从 `schema.sql` 切换到 Flyway，当前基线 migration 为 `V1__baseline.sql`
- 空 PostgreSQL 数据库启动时自动执行 V1，并由 `flyway_schema_history` 记录 migration
- Testcontainers 验证 `flyway_schema_history`、6 张业务表及关键结构
- 真实 API 集成测试覆盖 JWT、安全链、用户隔离和交易余额联动
- 已接入 OpenAPI 3 与 Swagger UI，六个 Controller 的 24 个接口已生成运行时 API 文档
- 注册和登录为公开接口，其余业务接口在运行时文档中显示 JWT 安全要求
- 后端测试数量以 `.\mvnw.cmd test` 的当前结果为准
- 前端当前 14 项测试通过，并通过 `npm run lint` 和 `npm run build`

当前 Transaction / Ledger 支持：

- `INCOME`
- `EXPENSE`
- `ADJUSTMENT`

当前暂不支持：

- `TRANSFER`
- `REFUND`

## 项目结构

```text
finance-os/
├─ backend/      # Java 21 + Spring Boot 3 后端
├─ frontend/     # React + TypeScript + Vite 前端
├─ docs/         # 架构、数据库、API、业务规则、Review 文档
├─ database/     # 数据库相关目录
├─ scripts/      # 本地开发脚本
└─ docker/       # Docker Compose 本地部署入口与环境变量模板
```

## 本地运行

### 前置条件

- Java 21
- Node.js / npm
- PostgreSQL
- 本地 PostgreSQL 用户名和密码

后端需要配置：

- `DB_USERNAME`
- `DB_PASSWORD`
- `JWT_SECRET`
- 可选 `DB_URL`：未设置时默认为 `jdbc:postgresql://localhost:5432/finance_os`

`JWT_SECRET` 长度至少 32 个字符。

### 启动后端

数据库迁移提示：

- 全新或空数据库可以正常启动后端；Flyway 会自动创建 migration history 并执行 V1。
- 如果已有旧开发数据库由旧版 `schema.sql` 创建、已有业务表但没有 `flyway_schema_history`，不要直接启动新版应用，也不要永久启用 `baseline-on-migrate`。
- 对旧数据库应先决定是备份后重建空数据库，还是经过 schema 比对后执行一次受控 baseline。本项目目前不会自动替用户处理已有数据库。

主要方式：在配置好环境变量后使用 Maven Wrapper 启动。

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

可选方式：仓库提供了本地 PowerShell 启动脚本。该脚本会读取 `backend/.env.local`。

```powershell
copy backend\.env.example backend\.env.local
# 编辑 backend\.env.local，填写 DB_USERNAME、DB_PASSWORD、JWT_SECRET；DB_URL 可按需覆盖
.\scripts\dev-start-backend.ps1
```

运行后端测试：

```powershell
cd backend
.\mvnw.cmd clean test
```

后端启动后可访问：

```text
Swagger UI：
http://localhost:8080/swagger-ui.html

OpenAPI JSON：
http://localhost:8080/v3/api-docs
```

### 启动前端

```powershell
cd frontend
npm install
npm run dev
```

前端构建：

```powershell
cd frontend
npm run build
```

## Docker Compose 启动

项目提供 PostgreSQL、Spring Boot backend 和 Nginx frontend 的最小单机容器化部署入口。复制 `docker/.env.example` 为忽略的 `docker/.env`，填写本地密码和 JWT Secret 后启动：

```powershell
Copy-Item docker/.env.example docker/.env
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
```

完整的健康检查、数据卷、停止和安全说明见 [Deployment Guide](docs/Deployment-Guide.md)。本能力是单机部署基础，不代表完整生产运维能力。

## 核心文档入口

- [系统架构](docs/03-Architecture/Architecture.md)
- [数据库设计](docs/03-Architecture/Database.md)
- [API 设计](docs/03-Architecture/API.md)
- [业务规则](docs/Business%20Rules.md)
- [金融规则](docs/Financial%20Rules.md)
- [开发指南](docs/Development-Guide.md)
- [部署指南](docs/Deployment-Guide.md)
- [Review 记录](docs/review/)
- [v1.3 Engineering Polish Closing Review](docs/review/V1.3-Closing-Review.md)
- [v1.4 Quality Hardening Closing Review](docs/review/V1.4-Closing-Review.md)
- [v2.0 Market Data Foundation Closing Review](docs/review/V2.0-Closing-Review.md)
- [v2.1 Market Valuation Closing Review](docs/review/V2.1-Closing-Review.md)

## 当前状态

- `v1.0 Foundation` 已完成阶段验收
- `v1.1 Showcase Enhancement` 已完成阶段验收
- `v1.2 Visualization Polish` 已完成阶段验收
- `v1.3 Engineering Polish` 已完成阶段验收
- `v1.4 Quality Hardening` 已完成阶段验收
- `v1.5 Deployment Readiness` 已完成本地 Docker、Compose、健康检查、smoke 与远端 CI 验证
- Flyway 数据库迁移里程碑已完成，V1 已在 PostgreSQL 17 Testcontainers 中验证
- Testcontainers 基础设施已完成，后端集成测试使用真实 PostgreSQL
- GitHub Actions CI 已完成，自动执行后端测试、前端测试、lint 和构建
- Controller / API 测试里程碑已完成：六个 Controller 的核心 HTTP 契约已覆盖
- OpenAPI 3 与 Swagger UI 已接入，六个 Controller 的 24 个接口已生成运行时 API 文档
- 后端测试数量以 `.\mvnw.cmd test` 的当前结果为准；前端当前 14 项测试
- `Architecture.md` 已完成 Review 并冻结
- `Database.md`、`API.md` 已同步当前实现状态
- Accounts 已接入账户编辑入口
- Assets 已接入详情、删除和清仓入口
- Transactions 已完成类型中文化和金额格式统一
- 登录 / 注册已展示后端错误信息
- 前端空状态和反馈提示已统一
- 前端共享页面、面板、表格和分页样式已统一，详见 `V1.3 Closing Review`
- 资产清仓只是持仓快照归零，不等于完整卖出交易模型
- v2.0 Market Data Foundation 已完成：独立最新行情快照、Twelve Data adapter、15 分钟 TTL、`CACHE_HIT` / `UPDATED` / `STALE_FALLBACK`、single-flight 和单进程额度保护；功能默认关闭。
- 参考行情仅支持 US `STOCK` / `ETF`，不参与 CNY 资产或 Dashboard 估值；Assets 页面只读缓存，刷新为单 Asset 手动操作。公开 `sites-demo` 不调用真实 Market Data API。
- v2.1 Market Valuation 已完成并正式关闭：Phase 1 FX Foundation、Phase 2 FX Refresh Workflow、Phase 3 Reference Valuation Backend、Phase 4 Assets UI 均已完成；参考估值为只读、非持久化派生结果，不修改账务真值或 Dashboard。
- v2.1 仍保持 CNY-only Account / Transaction；普通 GET 不调用 Provider，只有显式 POST 才可能刷新 Quote 或 FX；真实 FX Provider 默认关闭。
- v3.0 Phase 1 Investment Ledger Foundation 已完成并正式关闭：InvestmentTransaction 是事实、Asset 是受控投影；纯计算和 replay 内核、V4/V5 migration、Entity/Mapper 与测试已具备，但没有开放任何投资交易入口或改变现有余额、Asset / Dashboard 行为。首次 NO-GO、4 项 P1 定向修复和最终 GO 详见 [Phase 1 Closing Review](docs/review/V3.0-Phase1-Closing-Review.md)。

## 后续计划

- 已关闭：v2.1 Market Valuation、v3.0 Phase 1 Investment Ledger Foundation、Phase 2A Account Balance Concurrency，以及 Phase 2B-1 Investment Projection Safety and Precision（GO）。
- 下一阶段：`v3.0 Phase 2B-2 — Investment Instrument and Account Binding`（只读设计）。
- 后续顺序：Phase 2B-2 Instrument / Account Binding → Phase 2B-3 Legacy Preflight / Opening Migration → Phase 2B-4 Investment Write Path → Portfolio Read Model / Frontend。
- Instrument、Opening Migration、BUY/SELL API、余额联动和 Portfolio 当前均未实现。
- 资产历史价格、定时或自动刷新、AI 财务分析和完整生产运维能力仍属于后续规划。

## 项目定位

Personal Finance OS 不是简单 CRUD Demo，而是面向求职作品集的工程化个人财务管理系统。项目重点展示模块化架构、领域规则、后端金融计算、前后端集成、测试验证和文档同步能力。
# Phase 2A Account Balance Concurrency

Ordinary transaction balance mutations now use PostgreSQL pessimistic locks and the protocol defined in [ADR-008](docs/ADR/ADR-008-account-balance-concurrency-and-lock-ordering.md). This does not add Transfer, investment writes, multi-currency, Redis, MQ, or automatic retries.
