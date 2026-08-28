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
```

默认 `npm test` 不包含 `tests/e2e/`。

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

GitHub Actions 当前执行：

- backend `clean test`；
- frontend `npm test`、lint、build；
- backend/frontend 镜像构建；
- Compose config。

CI 当前不运行 Playwright E2E，也不启动完整 Compose runtime。Closing Review 如果依赖这些证据，必须记录实际独立命令和环境。

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
