# 开发指南

## 1. 分支与文档定位

- `zh-cn`：当前中文主导开发分支，包含后端、前端、Docker 和活文档。
- `sites-demo`：独立静态只读展示分支，使用虚构数据，不连接真实后端、数据库或 Provider。
- [Architecture](03-Architecture/Architecture.md) 是冻结基线；架构级变化必须先新增 ADR。
- 当前事实以代码、Flyway migration、已接受 ADR 和最新 Closing Review 交叉核对。
- 文档-only 任务不得借机启动后续 phase、改变 API 或修改历史验收正文。

## 2. 本地前置条件

| 工具 | 建议版本 / 说明 |
| --- | --- |
| Java | 21 |
| Maven | 使用仓库 Maven Wrapper 3.9.9 |
| Node.js | 22，与 CI 对齐 |
| npm | 随 Node 安装；使用 lockfile |
| PostgreSQL | 17；也可由 Docker Compose 启动 |
| Docker | 支持 Compose v2 |

## 3. 后端环境变量

| 变量 | 必需 | 说明 |
| --- | --- | --- |
| `DB_URL` | 本地可省略 | 默认 `jdbc:postgresql://localhost:5432/finance_os` |
| `DB_USERNAME` | 是 | PostgreSQL 用户 |
| `DB_PASSWORD` | 是 | PostgreSQL 密码 |
| `JWT_SECRET` | 是 | JWT 密钥，至少 32 位 |
| `MIGRATION_PREVIEW_SECRET` | opening 功能必需 | 与 JWT secret 不同，至少 32 位 |
| `MIGRATION_PREVIEW_TTL` | 否 | 默认 10m |
| `ACCOUNT_BALANCE_LOCK_TIMEOUT` | 否 | 默认 4s |
| `MARKET_DATA_ENABLED` | 否 | 默认 false |
| `MARKET_DATA_API_KEY` | 启用行情时 | Twelve Data key，不得提交 |
| `FX_DATA_ENABLED` | 否 | 默认 false |
| `FX_DATA_PROVIDER/BASE_URL/API_KEY` | 启用 FX 时 | Provider 配置，不得提交 |

PowerShell 示例：

```powershell
$env:DB_USERNAME = "finance_os"
$env:DB_PASSWORD = "your-local-password"
$env:JWT_SECRET = "at-least-32-characters-secret"
$env:MIGRATION_PREVIEW_SECRET = "another-32-characters-secret"
```

密钥只放环境变量或未跟踪的本地 `.env`，不得写入代码、测试输出、截图或 Markdown。

## 4. Maven 命令

```powershell
cd backend

# 启动后端；Flyway 会执行 V1–V13
.\mvnw.cmd spring-boot:run

# 全部后端测试
.\mvnw.cmd test

# 清理后重新测试，与 CI backend job 对齐
.\mvnw.cmd -B clean test
```

开发模式 Swagger UI：`http://localhost:8080/swagger-ui.html`。`prod` profile 禁用 OpenAPI/Swagger UI，仅暴露 health。

## 5. npm 命令

```powershell
cd frontend

# 本地首次安装
npm install

# 本地开发
npm run dev

# 范围匹配验证
npm test
npm run lint
npm run build

# 与 CI 可重复安装方式一致
npm ci
```

前端不得自行重算余额、成本、PnL 或 Dashboard 核心聚合；它只消费后端 contract。

## 6. Docker Compose

```powershell
Copy-Item docker\.env.example docker\.env
# 替换所有 change-me 占位值

docker compose --env-file docker/.env -f docker/compose.yml config
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
docker compose --env-file docker/.env -f docker/compose.yml ps
docker compose --env-file docker/.env -f docker/compose.yml down
```

Compose 服务顺序为 PostgreSQL healthy → backend ready → frontend。默认前端端口由 `FRONTEND_PORT` 指定；数据库数据保存在 named volume。

## 7. Flyway V1–V13

1. `backend/src/main/resources/db/migration/` 是 schema 唯一来源。
2. 不编辑已应用的 V1–V13，不恢复 `schema.sql` 并行初始化。
3. 新变化使用下一个版本号和可读说明，例如 `V14__...sql`。
4. migration 必须在 DDL 前检查无法安全升级的历史状态，并明确 fail-fast。
5. 不为缺失历史事实、receipt、Instrument binding 或 correction group 猜测数据。
6. 空库至少验证 V1→最新；升级风险还需验证相关旧版本→最新。
7. named constraint/index/trigger 可能被错误分类与测试引用，变更需同步代码和文档。

## 8. Testcontainers 与 PostgreSQL

- 约束、partial unique、行锁、`lock_timeout`、deadlock、trigger、deferred integrity 和 Flyway 行为必须用 PostgreSQL 验证。
- 集成测试基类使用 PostgreSQL Testcontainers；不能用 H2 代替 PostgreSQL 语义。
- 容器环境不可用时应明确报告未运行，而不是虚构通过结果。
- 涉及 migration 的变更应检查 `flyway_schema_history`、目标表/约束和 runtime schema。

## 9. 金融写路径验证

涉及 Account.balance、InvestmentTransaction、Asset projection、receipt 或 correction envelope 时，至少覆盖：

1. `BigDecimal` scale/rounding 与边界值；
2. user ownership 和跨用户安全 `404`；
3. 同一事务中的事实、余额、投影和回执；
4. 任一注入失败后的完整 rollback；
5. 同 key 同 hash 回放、同 key 不同 hash 冲突；
6. candidate replay 与数据库事实二次 replay 一致；
7. original/receipt/envelope 不可变；
8. projectionVersion、lastTransactionId 与一次投影语义。

金额计算和业务规则以后端为准。对金融规则修复优先先写回归测试，再实施最小修复。

## 10. 并发、锁与 failure injection

- 普通流水锁序：existing fact → Account rows（升序）。
- BUY/SELL/DIVIDEND：Account → Instrument → Asset。
- reversal/replacement：Original InvestmentTransaction → Account → Instrument → Asset。
- lock timeout 与 deadlock 应映射为脱敏 409，不自动重试。
- 并发测试要验证无重复现金 effect、无重复 fact/receipt、version 只前进一步。
- unknown commit 测试要覆盖“已提交但响应未知”和“提交前回滚但响应未知”。
- failure injection 覆盖 fact、balance、replay、projection、receipt/envelope 等阶段，并在每次失败后核对不变量。

## 11. Runtime smoke

以下变化不能只依赖单元测试：

- migration、数据库驱动、Compose 或生产 profile；
- health/readiness、容器启动顺序、nginx 到 backend 代理；
- 外部 Provider 配置、超时和限流；
- PostgreSQL 实际版本相关 SQL。

推荐 smoke 证据包括服务状态、health endpoint、Flyway 最终版本、关键只读 API 和容器日志中无敏感信息。不要把一次本地 smoke 描述为完整生产验证。

## 12. GitHub Actions CI

`.github/workflows/ci.yml` 在 `zh-cn` push、pull request 和手动触发时执行：

1. Backend：JDK 21，`./mvnw -B clean test`。
2. Frontend：Node 22，`npm ci`、test、lint、build。
3. Deployment artifacts：构建 backend/frontend 镜像，运行 Compose config 校验。

README CI badge 必须指向该 workflow 和 `zh-cn` branch。历史 Closing Review 数字不是当前 CI 实时状态。

## 13. 提交前检查

```powershell
git diff --check
git diff --cached --check
git status --short
git diff --name-only
git diff --cached --name-only
```

- 只暂存任务授权文件；禁止使用无差别 `git add .` / `git add -A`。
- 排除密钥、`.env`、`node_modules`、`target`、`dist`、日志和用户文件。
- 文档-only 变更检查相对链接、图片、Mermaid、阶段状态与本地绝对路径。
- 只改 Markdown 时通常不运行完整 Maven 测试，但必须明确说明未运行及原因。

## 14. 文档-only 边界

文档-only 任务可以读取代码、migration、ADR 和 Closing Review 以核对事实，但不能：

- 改 Java、SQL、TypeScript、React、配置或 workflow；
- 修改冻结 Architecture、已接受 ADR 或历史 Closing Review 正文；
- 以文档方式提前实现或定义未开始的 phase；
- 虚构 Demo、测试、CI、部署或 Provider 状态；
- 修改 `sites-demo` 以配合截图。

文档职责保持：README 展示，活规范查证，ADR 记录决策，Closing Review 保存验收证据。
