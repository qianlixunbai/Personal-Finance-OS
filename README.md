# Personal Finance OS

> **当前状态：** `v3.0 Phase 2B Investment Write Foundation：CLOSED — GO`
> **下一阶段：** `Phase 2C-1 Investment Read Model Contract（尚未开始）`

Personal Finance OS 是一个基于 Java 21、Spring Boot 3、PostgreSQL、React 与 TypeScript 构建的工程化个人财务管理系统。用户自主维护财务事实；系统负责可靠计算、审计、汇总与展示。投资账本采用不可变审计（immutable audit）、确定性重放（deterministic replay）、幂等恢复和并发一致性保障，因此不是普通 CRUD 示例。

系统不执行真实支付、银行转账或证券交易，也不提供投资建议。

## 项目入口与静态 Demo

- [项目文档中心](docs/README.md)
- [冻结架构基线](docs/03-Architecture/Architecture.md)

当前未保留可匿名验证的公开静态 Demo 链接。若后续恢复公开展示，它必须使用虚构数据、保持静态只读、不连接真实后端或行情 Provider，且不能代表投资写路径前端已经实现。

## 当前核心能力

### 基础财务

- 注册、登录、JWT、用户状态与数据隔离；
- Account、Category、普通 `INCOME` / `EXPENSE` / `ADJUSTMENT`；
- 后端原子维护 `Account.balance`，并提供 Dashboard、分页、筛选、统一响应和错误处理。

### 市场参考估值

- US `STOCK` / `ETF` 参考行情、手动刷新、TTL、single-flight 与请求额度保护；
- FX snapshot 与 reference valuation；普通 GET 不调用 Provider；
- 最终估值只读、非持久化，不修改账务真值、投资账本投影或 Dashboard；Provider 默认关闭。

### 投资账本

- `InvestmentInstrument` 与 Account / Instrument / Asset 绑定；
- Legacy opening migration、`BUY`、`SELL`、`DIVIDEND` 与加权平均成本；
- 全历史确定性重放、`Account.balance` 联动、transaction-driven Asset projection；
- standalone reversal、replacement correction、immutable receipt、append-only audit；
- request hash、幂等回放和恢复、确定性锁顺序、lock timeout / deadlock、unknown commit recovery；
- replay anchor、trace、SHA-256 digest，以及任意中途失败的完整回滚。

## 技术栈与数据边界

后端以 Java 21、Spring Boot 3、MyBatis-Plus、PostgreSQL、Flyway、Spring Security、JWT 与 Testcontainers 为核心；前端使用 React、TypeScript 与 Vite；仓库提供 Docker Compose 单机部署基础。

后端是金融计算与投影的唯一权威。行情、FX、手动估值和 reference valuation 仅是参考输入，不是账务事实。系统当前为 CNY 单币种账务，不提前实现真实银行/券商同步、真实交易执行或投资读取模型。

## 测试与工程验证

**Phase 2B 关闭时的最后完整验证基线：**

- 92 个测试套件；496 项测试；0 failures；0 errors；
- PostgreSQL 17.10；Flyway V13。

这是关闭阶段的历史验证基线，详情见 [Phase 2B 聚合 Closing Review](docs/review/V3.0-Phase2B-Closing-Review.md)，不是本次文档修改重新运行后的实时统计。

## 本地运行

```powershell
# 后端：需配置 DB_USERNAME、DB_PASSWORD、JWT_SECRET，
# 并使用与 JWT_SECRET 不同且至少 32 位的 MIGRATION_PREVIEW_SECRET
cd backend
.\mvnw.cmd spring-boot:run

# 前端
cd frontend
npm install
npm run dev
```

启动后可访问本地 Swagger UI：`http://localhost:8080/swagger-ui.html`。完整命令和迁移边界见 [开发指南](docs/Development-Guide.md)。

## Docker Compose

```powershell
Copy-Item docker\.env.example docker\.env
# 填写 docker\.env 中的非占位密钥
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
```

该路径是单机容器化部署基础，不是完整生产运维平台；详见 [部署指南](docs/Deployment-Guide.md)。

## 核心文档

- [项目愿景](docs/Project%20Vision.md)｜[路线图](docs/Roadmap.md)｜[需求规格](docs/SRS.md)
- [数据库基线](docs/03-Architecture/Database.md)｜[API 基线](docs/03-Architecture/API.md)
- [业务规则](docs/Business%20Rules.md)｜[金融规则](docs/Financial%20Rules.md)
- [ADR](docs/ADR/)｜[阶段 Review](docs/review/)

## 下一阶段与未实现能力

下一阶段仅为 `Phase 2C-1 Investment Read Model Contract`，尚未开始。Portfolio read API、InvestmentTransaction 用户查询/详情/时间线、投资前端、`TRANSFER` / `REFUND`、收益曲线、多币种账务、FIFO/lot、公司行动、银行/券商自动同步、AI Agent 与原生移动端均未实现。
