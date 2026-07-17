# Personal Finance OS

[![CI](https://github.com/qianlixunbai/Personal-Finance-OS/actions/workflows/ci.yml/badge.svg?branch=zh-cn)](https://github.com/qianlixunbai/Personal-Finance-OS/actions/workflows/ci.yml)

基于 **Java 21 + Spring Boot 3 + React + TypeScript** 的个人财务管理系统，用于管理账户、资产、交易流水，并通过 Dashboard 聚合分析真实业务数据。

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
- 保留 Review / Fix Plan 记录，体现设计、实现、评审、修复闭环

## 技术栈

**后端**

- Java 21
- Spring Boot 3
- Maven Wrapper
- MyBatis-Plus
- PostgreSQL
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
- 后端使用 PostgreSQL Testcontainers 进行真实数据库集成测试；六个 Controller 的核心 HTTP 契约已覆盖
- 真实 API 集成测试覆盖 JWT、安全链、用户隔离和交易余额联动
- 已接入 OpenAPI 3 与 Swagger UI，六个 Controller 的 24 个接口已生成运行时 API 文档
- 注册和登录为公开接口，其余业务接口在运行时文档中显示 JWT 安全要求
- 后端当前 107 项测试通过：`.\mvnw.cmd clean test`
- 前端当前 8 项测试通过，并通过 `npm run lint` 和 `npm run build`

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
└─ docker/       # Docker 相关预留目录，当前 README 不提供 Docker 启动方式
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

`JWT_SECRET` 长度至少 32 个字符。

### 启动后端

主要方式：在配置好环境变量后使用 Maven Wrapper 启动。

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

可选方式：仓库提供了本地 PowerShell 启动脚本。该脚本会读取 `backend/.env.local`。

```powershell
copy backend\.env.example backend\.env.local
# 编辑 backend\.env.local，填写 DB_USERNAME、DB_PASSWORD、JWT_SECRET
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

## 核心文档入口

- [系统架构](docs/03-Architecture/Architecture.md)
- [数据库设计](docs/03-Architecture/Database.md)
- [API 设计](docs/03-Architecture/API.md)
- [业务规则](docs/Business%20Rules.md)
- [金融规则](docs/Financial%20Rules.md)
- [开发指南](docs/Development-Guide.md)
- [Review 记录](docs/review/)

## 当前状态

- `v1.0 Foundation` 已完成阶段验收
- `v1.1 Showcase Enhancement` 已完成阶段验收
- `v1.2 Visualization Polish` 已完成阶段验收
- `v1.3 Engineering Polish` 正在进行
- Testcontainers 基础设施已完成，后端集成测试使用真实 PostgreSQL
- GitHub Actions CI 已完成，自动执行后端测试、前端测试、lint 和构建
- Controller / API 测试里程碑已完成：六个 Controller 的核心 HTTP 契约已覆盖
- OpenAPI 3 与 Swagger UI 已接入，六个 Controller 的 24 个接口已生成运行时 API 文档
- 后端当前 107 项测试，前端当前 8 项测试
- `Architecture.md` 已完成 Review 并冻结
- `Database.md`、`API.md` 已同步当前实现状态
- Accounts 已接入账户编辑入口
- Assets 已接入详情、删除和清仓入口
- Transactions 已完成类型中文化和金额格式统一
- 登录 / 注册已展示后端错误信息
- 前端空状态和反馈提示已统一
- 资产清仓只是持仓快照归零，不等于完整卖出交易模型

## 后续计划

- 前端组件抽取和工程化整理
- Flyway / Liquibase 数据库迁移
- 行情数据、资产历史价格、多币种汇率、AI 财务分析、部署增强等仍属于后续版本
- 完整投资交易模型，包括买入、卖出、股息、手续费、税费、实现盈亏和现金账户联动，仍属于后续版本

## 项目定位

Personal Finance OS 不是简单 CRUD Demo，而是面向求职作品集的工程化个人财务管理系统。项目重点展示模块化架构、领域规则、后端金融计算、前后端集成、测试验证和文档同步能力。
