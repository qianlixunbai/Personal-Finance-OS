# Development Guide

Version: 1.0

---

# 1. Project Goal

Personal Finance OS 是一个现代化个人金融管理系统。

项目目标：

- 企业级架构
- 长期维护
- 高可扩展性
- 高可读性
- 高可测试性
- 可持续迭代

本项目不是 Demo，而是长期维护的核心项目。

---

# 2. Development Principles

整个项目遵循：

- KISS（Keep It Simple）
- DRY（Don't Repeat Yourself）
- SOLID
- Clean Architecture
- High Cohesion
- Low Coupling

代码质量优先于开发速度。

---

# 3. Architecture Principles

采用模块化单体（Modular Monolith）。

禁止为了规模较小的项目引入微服务。

所有模块必须保持独立职责。

模块之间通过 Service 或事件通信，不允许随意跨模块调用。

---

# 4. Layer Rules

Controller

负责：

- 参数接收
- 参数校验
- 返回结果

禁止：

业务逻辑

数据库操作

--------------------

Service

负责：

业务逻辑

数据计算

事务控制

--------------------

Repository

负责：

数据库访问

禁止：

业务逻辑

--------------------

Entity

数据库映射。

禁止：

业务代码。

---

# 5. Database Rules

统一 PostgreSQL。

所有表采用 snake_case。

所有字段采用 snake_case。

金额统一 Decimal。

时间统一 UTC。

所有表默认包含：

- id
- created_at
- updated_at

禁止：

数据库字段随意修改。

当前项目使用 Flyway 10.20.0 管理数据库迁移，依赖 `flyway-core` 和 `flyway-database-postgresql`。唯一数据库结构来源为 `backend/src/main/resources/db/migration/V1__baseline.sql`；原 `schema.sql` 已删除，主应用和测试 profile 均已移除 Spring SQL Init。

数据库变更必须新增版本化 migration，并同步 `Database.md`。当前未配置 `baseline-on-migrate`，新的空 PostgreSQL 数据库会自动执行 V1；已有旧开发数据库不会被项目自动 baseline 或重建。

---

# 6. API Rules

全部 RESTful。

统一返回：

{
    "code": 200,
    "message": "success",
    "data": {}
}

统一异常。

统一错误码。

---

# 7. Naming Rules

类：

PascalCase

方法：

camelCase

变量：

camelCase

数据库：

snake_case

常量：

UPPER_SNAKE_CASE

---

# 8. Git Rules

Branch

main

develop

feature/*

bugfix/*

hotfix/*

Commit

feat:

fix:

docs:

refactor:

test:

style:

---

# 9. Logging

统一日志。

INFO：

正常业务。

WARN：

业务异常。

ERROR：

系统异常。

禁止：

System.out.println()

---

# 10. Security

JWT。

密码：

BCrypt。

禁止：

SQL 拼接。

禁止：

硬编码密钥。

---

# 11. Performance

所有列表分页。

Dashboard 避免重复查询。

避免 N+1 Query。

优先优化 SQL。

缓存作为 V2 功能接入。

---

# 12. Documentation

任何重大修改：

必须更新：

- 文档
- API
- 数据库设计

文档优先于代码。

---

# 13. Development Workflow

需求

↓

设计

↓

Review

↓

开发

↓

测试

↓

Review

↓

Merge

任何阶段不得跳过 Review。

---

# 14. Local Backend Run

数据库迁移提示：

- 全新或空数据库可以正常启动；Flyway 会自动创建 `flyway_schema_history` 并执行 V1。
- 如果旧开发数据库由旧版 `schema.sql` 创建、已有业务表但没有 `flyway_schema_history`，不要直接启动新版应用，也不要永久启用 `baseline-on-migrate`。
- 对旧数据库应先决定备份后重建空数据库，或经过 schema 比对后执行一次受控 baseline；本项目不会自动替用户处理已有数据库。

后端本地启动依赖以下环境变量：

- `JWT_SECRET`
- `DB_USERNAME`
- `DB_PASSWORD`
- 可选 `DB_URL`

`JWT_SECRET` 用于创建 JWT 签名密钥，长度必须至少 32 字符。`DB_USERNAME` 和 `DB_PASSWORD` 用于连接 PostgreSQL。`DB_URL` 未设置时默认为 `jdbc:postgresql://localhost:5432/finance_os`；如需连接其他实例，可仅覆盖完整 JDBC URL，用户名和密码仍通过各自变量配置，不要将密码嵌入 URL。

第一次使用：

1. 复制 `backend/.env.example` 为 `backend/.env.local`
2. 在 `backend/.env.local` 中填写本地 PostgreSQL 用户名和密码；如需覆盖本地默认地址，再填写可选的 `DB_URL`
3. 确保 `JWT_SECRET` 至少 32 字符
4. 在项目根目录运行：

```powershell
.\scripts\dev-start-backend.ps1
```

后续启动后端时，直接在项目根目录运行：

```powershell
.\scripts\dev-start-backend.ps1
```

注意：

- `backend/.env.local` 只保存本地真实配置，不要提交到 Git
- `backend/.env.example` 只保存模板值，可以提交
- 启动脚本只把变量设置到当前 PowerShell 进程，不会打印真实 secret
- 如果前端出现 Vite proxy `ECONNREFUSED`，先确认后端是否启动成功
- 后端启动成功时，应看到 Tomcat started on port 8080

## OpenAPI / Swagger

后端基于 Spring Boot `3.3.5` 接入 `springdoc-openapi-starter-webmvc-ui:2.6.0`，提供 OpenAPI 3 JSON 和 Swagger UI：

- Swagger UI：`http://localhost:8080/swagger-ui.html`（最终页面可跳转到 `/swagger-ui/index.html`）
- OpenAPI JSON：`http://localhost:8080/v3/api-docs`
- 文档标题为 `Personal Finance OS API`，版本为 `v1`
- `bearerAuth` 使用 HTTP Bearer，`bearerFormat` 为 JWT
- 注册和登录为公开接口，其余业务接口要求 JWT
- 六个 Controller 的 24 个接口均有运行时 operation summary

Swagger UI 的 `Authorize` 可用于输入 JWT Bearer token。`OpenApiIntegrationTest` 使用真实 Spring Boot、Security 和 PostgreSQL Testcontainers 上下文，验证 OpenAPI JSON、Swagger UI、六个标签、公开接口和受保护接口的安全声明，共 2 项测试。

---

# 15. Controller / API Test Strategy

后端当前共有 115 项测试，前端当前共有 9 项测试。六个 Controller（User、Account、Asset、Category、Transaction 和 Dashboard）的核心 HTTP 契约由 MockMvc slice 测试覆盖；这些测试使用真实 Security 配置，并以 pass-through 的 JWT Filter 保持安全链参与测试。

前端第 9 项测试位于 `frontend/tests/pagination.test.ts`，验证 Pagination 的上一页、下一页按钮显式使用 `type="button"`。前端测试当前仍使用 Node 内置 test runner，未引入 Jest、Vitest、React Testing Library 或 jsdom。

真实 API 集成测试使用真实 JWT 和 Testcontainers 启动的 `postgres:17-alpine`，通过 Spring 的动态数据源属性连接容器；测试使用正式的 Flyway `V1__baseline.sql` 初始化结构，不会连接本地开发数据库。`FlywayMigrationIntegrationTest` 额外验证空数据库迁移、`flyway_schema_history`、6 张业务表及关键结构。集成测试覆盖注册和登录、禁用用户旧 token 返回 401、有效 / 过期 / 篡改 / 格式错误 JWT 的统一 401 响应、账户/资产/分类/流水用户隔离、流水创建/更新/删除时的账户余额联动，以及分页和组合筛选。

运行完整后端测试前，请先启动 Docker Desktop（或提供兼容的 Docker daemon），然后执行：

```powershell
cd backend
.\mvnw.cmd clean test
```

Docker Engine 29 requires Docker API 1.40 or later. The backend Surefire configuration supplies `api.version=1.40` only to the test JVM, so the command above needs no extra parameters or user-level environment variables. This does not affect production runtime configuration.

---

# 16. 持续集成 / GitHub Actions

CI 工作流文件为 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)，使用最小的 `contents: read` 权限，不需要 GitHub Secrets，也不执行部署；CI 执行后端测试，以及前端 test、lint 和 build。

工作流在以下情况触发：

- push 到 `zh-cn`；
- 目标为 `zh-cn` 的 `pull_request`；
- `workflow_dispatch` 手动触发。

向 `sites-demo` 分支的提交不会触发面向 `zh-cn` 的完整 CI。

## Backend Job

- 运行环境：`ubuntu-latest`；
- 使用 Temurin Java 21 和 Maven Wrapper；
- 在 `backend` 目录执行 `./mvnw -B clean test`；
- 执行全部 108 项后端测试；
- Testcontainers 会启动 `postgres:17-alpine`，因此不需要额外的 PostgreSQL service；
- `pom.xml` 的测试范围配置会提供 `api.version=1.40`，开发者不需要在 CI 命令中手工传参。

## Frontend Job

- 运行环境：`ubuntu-latest`；
- 使用 Node 22；
- 在 `frontend` 目录依次执行 `npm ci`、`npm test`、`npm run lint` 和 `npm run build`；
- 当前前端测试总计 9 项。

任一命令失败都会使对应 Job 和整个工作流失败。当前 CI 只负责验证后端和前端，不包含 coverage、artifact、部署或分支保护配置。

# Market data refresh

`POST /api/v1/assets/{id}/quote/refresh` is disabled by default. It refreshes only an authenticated user's `STOCK` or `ETF` asset and returns an independent USD reference quote. It never writes `assets.current_price`, `assets.market_value`, or Dashboard data.

The cache TTL defaults to 15 minutes. A stale cached quote is returned with a warning if the provider is unavailable. Provider calls use an in-process single-flight key per `(US, symbol)` and configurable per-user/global per-minute limits; cache hits do not consume either limit.
