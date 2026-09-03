# Testing

本文描述当前测试类型、适用场景和命令，不维护容易过期的总测试数。具体阶段数字保存在 [Closing Review](../archive/review/README.md)。

## 1. 后端测试层次

| 类型 | 目标 |
| --- | --- |
| Unit | calculator、validation、hash、state machine、service 分支 |
| WebMvc | Controller、认证、DTO、状态码和响应契约 |
| PostgreSQL integration | Mapper、复合 FK、NUMERIC、trigger、locks、transactions |
| Migration | 空库 V1→latest 与历史 snapshot→latest |
| Concurrency | lock order、same-key race、deadlock/timeout、lost update |
| Failure injection | fact、balance、projection、receipt、Batch 等中途失败回滚 |
| Unknown commit | 已提交但响应未知 / 提交前回滚的幂等恢复 |
| Runtime | Spring Boot + PostgreSQL + JWT 的真实 HTTP 路径 |

命令：

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd -B clean test
```

PostgreSQL 特性必须由 PostgreSQL Testcontainers 或真实 PostgreSQL 验证，不能用 H2 推断。

## 2. 前端测试层次

| 类型 | 目标 |
| --- | --- |
| Node tests | formatter、cursor、transport、storage、domain mapping、coordinator 状态 |
| Build / lint | TypeScript、Vite bundle 与静态检查 |
| Playwright Investment | 投资命令、确认、回执、恢复和浏览器交互 |
| Playwright Import | upload/mapping/Preview/Confirm/Receipt、401、reload、多标签页、lossless amount |

命令：

```powershell
cd frontend
npm test
npm run lint
npm run build
npm run test:e2e:investment-command
npm run test:e2e:transaction-import
npm run test:e2e:transaction-import:real
```

默认 `npm test` 不包含 `tests/e2e/`。

`test:e2e:transaction-import` 是确定性的 mock/browser 契约测试；`test:e2e:transaction-import:real` 不自行启动服务，要求调用方已将 `E2E_BASE_URL` 指向配置为真实后端与隔离数据库的 Vite server。

## 3. 金融规则验证

金额或余额变更至少覆盖：

- scale、precision、`HALF_UP` 与边界值；
- 正负现金 effect；
- partial/full SELL、rounding residual、reopen；
- reversal/replacement 与 corrected truth；
- Transaction / Import 与 Account.balance 同事务；
- user ownership、跨用户 404；
- rollback 与 unknown commit。

## 4. Import 验证

- CSV UTF-8、XLSX ZIP/XML 资源限制；
- 5 MiB、10,000 rows、32 columns 与 parser deadline；
- mapping、NFC key、Account/Category、currency、date/time、amount；
- in-file/database probable duplicate 与 evidence drift；
- warning acknowledgement 与 signed token；
- Session expiration/cancel/cleanup；
- multi-account lock order、atomic Confirm、receipt reconstruction；
- idempotency、exact duplicate、named constraint negative matrix；
- frontend frozen body、GET-first recovery、401 continuation、user switch、multi-tab 与 Receipt reload。

## 5. CI

GitHub Actions 已配置以下 quality gate；首次 GitHub Actions 实际运行前，这些 Playwright E2E 仅为 configured，不能表述为 verified green：

- backend `clean test`；
- frontend `npm test`、lint、build；
- 独立 PostgreSQL `finance_os_e2e`、Spring Boot `e2e` profile 与 Vite server 上的 Investment Command Playwright E2E；
- Transaction Import 的 mock/browser 契约 Playwright E2E，以及经由 Vite `:5180` → Spring Boot `:8081` → PostgreSQL `finance_os_e2e` 的真实 critical-path Playwright E2E；
- backend/frontend 镜像构建；
- Compose config。

E2E job 使用临时生成的测试 secret，并且不连接日常开发数据库 `finance_os`。它不启动完整 Compose runtime；Closing Review 如果依赖额外 runtime 证据，仍须记录实际独立命令和环境。

## 6. 文档-only 验证

仅修改 `README.md` 与 `docs/**` 时不要求运行 Maven、npm 或应用 smoke。必须运行：

```text
git diff --check
Markdown local link validation
legacy path scan
status drift scan
git status --short
```

最终报告应明确未运行应用测试及原因。
