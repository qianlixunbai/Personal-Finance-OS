# Definition of Done

所有任务先满足通用门槛，再按变更类型选择附加门槛。验证必须来自本轮新鲜证据，不能用历史 Closing Review 冒充当前结果。

## 1. 通用门槛

- [ ] 只覆盖授权范围，没有无关重构或 P2/P3 顺手修复。
- [ ] 代码、测试、配置和活文档对同一事实使用一致口径。
- [ ] 不含 secret、真实财务数据、本地绝对路径、构建产物或用户文件。
- [ ] 相关测试/lint/build/smoke 已实际运行，或记录未运行原因。
- [ ] `git diff --check` 通过。
- [ ] `git status --short` 与文件清单已核对。
- [ ] 只在用户要求时暂存、commit 或 push。

## 2. 普通代码修改

- [ ] 验收行为清晰，采用最小修改。
- [ ] 输入、null/空值、错误路径和边界条件有匹配验证。
- [ ] 新逻辑有就近测试或明确已有覆盖。
- [ ] 受影响模块测试、lint/build 通过。
- [ ] 无依赖升级、格式化噪音或无关重构。
- [ ] 行为变化同步更新 Current Truth 文档。

## 3. API

- [ ] path、method、authentication 与 user ownership 明确。
- [ ] DTO 不接受 user ID、余额、projection、receipt 或服务端计算字段。
- [ ] 金额字符串、分页、时间与 unknown-field 边界有契约测试。
- [ ] 400/401/403/404/409/413/415/429/500/502/503 分类与脱敏正确。
- [ ] 跨用户资源保持安全 404。
- [ ] `X-Idempotency-Key` 与 `Idempotency-Key` 没有混用。
- [ ] OpenAPI、[API](../architecture/api.md) 和前端类型同步。

## 4. Migration

- [ ] 使用新的前向 Flyway 版本，不修改 V1–V17。
- [ ] 定义历史升级前提、backfill 与 fail-fast 条件。
- [ ] 空库 V1→latest 成功。
- [ ] 有历史风险时对应 snapshot→latest 成功。
- [ ] 表、列、类型、named constraint/index/trigger 已核对。
- [ ] 复合 FK、partial unique、CHECK、immutable/deferred integrity 有 PostgreSQL 验证。
- [ ] Migration 失败不留下部分事实或伪造历史。
- [ ] [Database](../architecture/database.md) 与开发/部署说明同步。

## 5. 金融写路径

- [ ] 所有金融值使用 `BigDecimal` / `NUMERIC`。
- [ ] scale、precision、rounding 与零/负边界有测试。
- [ ] 后端是公式唯一权威，前端未重新聚合。
- [ ] facts、Account.balance、projection、receipt/envelope 同事务。
- [ ] 任一中途失败完整 rollback。
- [ ] original fact、receipt 与 command envelope 不可变。
- [ ] candidate replay、数据库二次 replay 与当前投影一致。
- [ ] version、lastTransactionId、一次余额/一次投影不变量成立。
- [ ] user isolation、locks、idempotency 与 unknown commit 已验证。

## 6. Transaction Import

- [ ] Preview 保持只读，不写 Transaction 或 balance。
- [ ] CSV/XLSX 文件、ZIP/XML 与资源上限有回归测试。
- [ ] mapping、规范化、Account/Category 与 CNY 规则由服务端验证。
- [ ] probable duplicate、warning ID、acknowledgement 与 evidence drift 正确。
- [ ] token 绑定 user/Session/revision/digests/warnings/lifetime，production 无默认 secret。
- [ ] Confirm 锁序为 Session → Accounts ascending。
- [ ] Transactions、balances、Batch、Items、Impacts、Session 同事务。
- [ ] 同 key 同 intent replay，不同 intent conflict；exact duplicate fail closed。
- [ ] Receipt 可在临时 plan 清理后从 persistence 重建并验证 digest。
- [ ] 前端 unknown outcome GET-first、committed GET-only、401/user-switch/multi-tab 安全。

## 7. 并发

- [ ] 所有锁查询包含 user ownership。
- [ ] 全局锁顺序未反转，多 Account 去重升序。
- [ ] `lock_timeout` 生效。
- [ ] `55P03` / `40P01` 映射为脱敏冲突，不盲目自动重试。
- [ ] 同 key 并发只产生一个 effect 和 receipt。
- [ ] unknown unique constraint 不被误判为成功。
- [ ] committed/uncommitted unknown outcome 均验证。
- [ ] 重复运行无悬挂锁或偶发重复写。

## 8. 前端

- [ ] 服务端金融值未转为有损 JavaScript Number。
- [ ] pending intent、draft、Receipt route 按 user 隔离。
- [ ] stale response、reload、cross-tab 和 duplicate click 不产生新意图。
- [ ] 401 continuation 保留正确来源状态。
- [ ] 409 action 按 authoritative reconcile 处理，不自动危险重发。
- [ ] dialog、focus、键盘与错误反馈满足可访问性要求。
- [ ] unit、lint、build 与必要 Playwright E2E 通过。

## 9. 文档-only

- [ ] 事实与当前代码、V1–V17、配置、ADR 和最新 Review 交叉核对。
- [ ] 当前状态只在 `STATUS.md` 维护。
- [ ] README 适合快速展示，深度内容留在正式文档。
- [ ] 所有相对链接、图片与 Mermaid 有效。
- [ ] Frozen Architecture、ADR、design、Closing Review 和 logs 的历史结论未被改写。
- [ ] 历史测试数字只保留在历史证据中。
- [ ] 没有本地绝对路径、真实数据或虚构能力。
- [ ] 未运行应用测试时说明本轮仅修改文档。

## 10. Runtime / Deployment

- [ ] profile、环境变量和 secret 来源明确。
- [ ] Compose config 或部署配置通过。
- [ ] PostgreSQL healthy、backend readiness、frontend health 通过。
- [ ] Flyway 最终版本符合预期。
- [ ] 关键 API 与反向代理 smoke 通过。
- [ ] 日志脱敏。
- [ ] 当前 Import secret 传递缺口已处理后，才能宣称标准 Compose runtime 可用。

## 11. Closing Review

- [ ] 范围、排除项、实现 commit 与环境完整。
- [ ] suite/test/failure/error 数字来自实际输出。
- [ ] PostgreSQL、Migration、runtime 与并发证据可追溯。
- [ ] 独立 review 记录 P0/P1/P2/P3 和 GO/NO-GO。
- [ ] P0/P1 在关闭前修复并复验为 0。
- [ ] P2/P3 明确记录，不无限循环 review。
- [ ] 关闭后不改写历史证据，也不自动启动下一阶段。

## 12. 最终交付

```powershell
git diff --check
git diff --cached --check
git status --short
git diff --name-only
git diff --cached --name-only
```

完成声明必须引用本轮验证结果。
