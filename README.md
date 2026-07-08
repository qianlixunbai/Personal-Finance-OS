# Personal Finance OS

基于 **Java 21 + Spring Boot 3 + React + TypeScript** 的个人财务管理系统，用于管理账户、资产、交易流水，并通过 Dashboard 聚合分析真实业务数据。

## 项目亮点

- 模块化单体架构，按业务模块组织后端代码
- 前后端分离，后端提供 REST API，前端使用 React + Vite
- JWT 认证，业务接口默认需要 `Authorization: Bearer <token>`
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
- 资产管理
- Transaction / Ledger 基础 CRUD
- 流水创建、编辑、删除时联动账户余额
- Dashboard 聚合账户、资产、流水数据
- 前端页面：`Login`、`Register`、`Dashboard`、`Accounts`、`Assets`、`Transactions`
- 后端分页接口
- 后端测试已通过：`.\mvnw.cmd clean test`
- 前端构建已通过：`npm run build`

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

- `Architecture.md` 已完成 Review 并冻结
- `Database.md` 已完成 Draft + Review
- `API.md` 已完成 Draft + Review，并已同步 Transaction / Ledger 与前端接入状态
- P2 已关闭
- Ledger / Transaction API 已完成第一版最小闭环
- `Transactions` 前端页面已完成第一版接入
- 项目仍处于 v1.0 迭代中

## 后续计划

- 优化 GitHub 作品集展示
- 增强 Dashboard 可视化
- 完善参数校验与异常响应收敛
- 接入更完整的资产 / 投资交易模型
- 后续考虑部署方案、截图和演示内容展示

## 项目定位

Personal Finance OS 不是简单 CRUD Demo，而是面向求职作品集的工程化个人财务管理系统。项目重点展示模块化架构、领域规则、后端金融计算、前后端集成、测试验证和文档同步能力。
