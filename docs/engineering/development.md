# Development

## 1. 分支与事实来源

- `zh-cn` 是当前中文主导开发分支；
- `sites-demo` 是独立静态只读展示，不连接真实后端或数据库；
- 当前事实按代码 / Migration / 配置 → 测试 / API → ADR → Closing Review → 活文档核对；
- [Frozen Architecture](../architecture/frozen-v1.0-architecture.md) 不随实现重写，架构级变化必须新增 ADR。

## 2. 本地前置条件

| 工具 | 当前建议 |
| --- | --- |
| Java | 21 |
| Maven | 仓库 Maven Wrapper 3.9.9 |
| Node.js | 22，与 CI 对齐 |
| npm | 使用 lockfile |
| PostgreSQL | 17 |
| Docker | Compose v2；注意当前已知限制 |

## 3. 后端环境变量

| 变量 | 必需 | 说明 |
| --- | --- | --- |
| `DB_URL` | 本地可省略 | 默认 `jdbc:postgresql://localhost:5432/finance_os` |
| `DB_USERNAME` | 是 | PostgreSQL 用户 |
| `DB_PASSWORD` | 是 | PostgreSQL 密码 |
| `JWT_SECRET` | 是 | JWT secret，至少 32 字符 |
| `MIGRATION_PREVIEW_SECRET` | 是 | Legacy opening preview，至少 32 字符 |
| `FINANCE_IMPORT_CONFIRM_TOKEN_SECRET` | 是 | Import Confirm HMAC，至少 32 字符 |
| `MIGRATION_PREVIEW_TTL` | 否 | 默认 10m |
| `ACCOUNT_BALANCE_LOCK_TIMEOUT` | 否 | 默认 4s |
| `FINANCE_IMPORT_CLEANUP_ENABLED` | 否 | 默认 true |
| `MARKET_DATA_ENABLED` | 否 | 默认 false |
| `MARKET_DATA_API_KEY` | 启用行情时 | Twelve Data key |
| `FX_DATA_*` | 启用 FX 时 | Provider 配置 |

PowerShell 示例：

```powershell
$env:DB_USERNAME = "finance_os"
$env:DB_PASSWORD = "your-local-password"
$env:JWT_SECRET = "at-least-32-characters-secret"
$env:MIGRATION_PREVIEW_SECRET = "another-32-characters-secret"
$env:FINANCE_IMPORT_CONFIRM_TOKEN_SECRET = "a-third-32-characters-secret"
```

三个 secret 不应复用。当前 `backend/.env.example` 和 `scripts/dev-start-backend.ps1` 的 required-variable 检查尚未列出 Import secret；使用脚本时仍需在 `backend/.env.local` 手工增加该变量，否则应用会在 bean 初始化时 fail-fast。

## 4. 后端命令

```powershell
cd backend

# 启动；Flyway 执行 V1–V17
.\mvnw.cmd spring-boot:run

# 受影响或全部后端测试
.\mvnw.cmd test
.\mvnw.cmd -B clean test
```

开发环境 Swagger UI：`http://localhost:8080/swagger-ui.html`。production profile 关闭 OpenAPI / Swagger UI，只暴露 health。

## 5. 前端命令

```powershell
cd frontend

npm install
npm run dev

npm test
npm run lint
npm run build

npm run test:e2e:investment-command
npm run test:e2e:transaction-import
```

CI 使用 `npm ci`。Playwright E2E 需要对应真实后端/数据库测试环境或测试配置，不属于默认 `npm test`。

## 6. Flyway

1. `backend/src/main/resources/db/migration/` 是 schema 唯一来源。
2. 不编辑 V1–V17，不恢复 `schema.sql` 并行初始化。
3. 新变化使用后续版本号和可读名称。
4. Migration 先定义历史升级前提和 fail-fast 条件。
5. 不推测缺失 receipt、Instrument binding、Import Batch 或 correction facts。
6. 空库验证 V1→latest；有升级风险时增加旧 snapshot→latest。
7. named constraint、index 和 trigger 名称可能被错误分类与测试依赖。

## 7. PostgreSQL / Testcontainers

复合 FK、partial unique、`NUMERIC`、行锁、`lock_timeout`、deadlock、trigger、deferred integrity 与 Flyway 必须使用 PostgreSQL 验证，不能用 H2 替代。

容器不可用时应明确报告未运行，不得虚构通过结果。

## 8. 金融写路径

涉及 Transaction、Account.balance、InvestmentTransaction、Asset、receipt 或 Import Batch 时，至少核对：

1. `BigDecimal` scale、rounding 与范围；
2. user ownership 和跨用户 404；
3. 事实、余额、投影和 receipt 的事务边界；
4. failure injection 后的完整 rollback；
5. 同 key 同 intent replay 与不同 intent conflict；
6. 固定锁顺序和并发竞争；
7. immutable facts / receipts；
8. unknown commit recovery。

领域规则见 [domain](../domain/)，一致性机制见 [Consistency](../architecture/consistency.md)。

## 9. Import 开发边界

- Preview 不能写普通 Transaction 或余额；
- Confirm 只能执行服务端 frozen plan；
- duplicate evidence 必须在 Account locks 后复核；
- Session、Transactions、balances、Batch、Items、Impacts 同事务；
- token secret 不能有公开默认值；
- committed receipt 不依赖临时 plan；
- 前端恢复保持同一 key/path/body，committed state 只能 GET。

## 10. Runtime smoke

Migration、Compose、production profile、Provider、health/readiness 或反向代理变化不能只依赖 unit tests。smoke 应核对服务状态、health、Flyway 最终版本、关键只读 API 和日志脱敏。

当前 Compose runtime 有已知 secret 传递缺口，详见 [Deployment](deployment.md)。

## 11. CI

`.github/workflows/ci.yml` 在 `zh-cn` push、pull request 和手动触发时执行：

1. Backend：JDK 21，`./mvnw -B clean test`；
2. Frontend：Node 22，`npm ci`、test、lint、build；
3. Deployment artifacts：构建前后端镜像并运行 Compose config。

Compose config 通过只证明 YAML 与必需插值可解析，不证明容器 runtime 成功。

## 12. 提交前检查

```powershell
git diff --check
git diff --cached --check
git status --short
git diff --name-only
git diff --cached --name-only
```

- 只暂存授权文件，不使用无差别 `git add .` / `git add -A`；
- 排除 secret、`.env`、构建产物、日志和用户文件；
- 文档-only 变更检查本地链接、图片、Mermaid、阶段状态和绝对路径；
- 只改 Markdown 时通常不运行完整应用测试，但必须说明原因。
