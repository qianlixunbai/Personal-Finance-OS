# 完成定义（Definition of Done）

所有任务先满足通用门槛，再按变更类型满足附加门槛。验证应与风险和范围匹配，不用历史基线冒充本轮实时结果。

## 1. 通用门槛

- [ ] 实现只覆盖已授权范围，没有顺手修改无关 P2/P3。
- [ ] 代码、测试、配置和文档对同一事实使用一致口径。
- [ ] 不含密钥、真实财务数据、本地绝对路径、构建产物或用户文件。
- [ ] 相关测试/lint/build/smoke 已实际运行，或明确记录未运行原因。
- [ ] `git diff --check` 与 `git diff --cached --check` 通过。
- [ ] `git status --short` 和 diff 文件清单已人工核对。
- [ ] 只暂存授权文件；提交信息准确描述变化。

## 2. 普通代码修改

- [ ] 验收行为清晰，采用最小修改。
- [ ] 输入校验、null/空值、错误路径和边界条件有匹配验证。
- [ ] 新逻辑有就近单元测试或合理的已有覆盖证据。
- [ ] lint/build 与受影响模块测试通过。
- [ ] 没有无关重构、依赖升级或格式化噪音。
- [ ] 活文档在行为变化时同步更新。

## 3. API 修改

- [ ] 路径、方法、认证和 user ownership 明确。
- [ ] DTO 只暴露允许字段，不接受 user ID 或服务端计算字段。
- [ ] 请求/响应类型、金额字符串、分页和时间格式有契约测试。
- [ ] 400/401/403/404/409/429/500/502/503 分类与脱敏保持。
- [ ] 跨用户资源返回安全 404。
- [ ] 幂等 header 名称与适用 endpoint 准确。
- [ ] OpenAPI、API 文档和相关前端类型同步。
- [ ] 未实现 endpoint 没有被提前写入文档。

## 4. Migration

- [ ] 使用新的前向 Flyway 版本，不修改已应用 migration。
- [ ] 明确升级前提、数据 backfill 策略与 fail-fast 条件。
- [ ] 空库从 V1 迁移到最新成功。
- [ ] 涉及历史升级时，相关旧版本→最新路径成功。
- [ ] 表、列、类型、默认值、named constraint/index/trigger 均核对。
- [ ] 组合 FK、partial unique、CHECK、immutable/deferred integrity 有 PostgreSQL 验证。
- [ ] migration 失败不会留下部分 DDL 或伪造历史事实。
- [ ] Testcontainers 与必要 runtime smoke 通过。
- [ ] Database、Development Guide 和部署说明同步。

## 5. 金融写路径

- [ ] 所有金额、数量、价格、成本和 PnL 使用 `BigDecimal` / `NUMERIC`。
- [ ] scale、precision、`HALF_UP` 舍入和零/负边界有测试。
- [ ] 金融公式只有一个后端权威来源，前端未重新聚合。
- [ ] 事实、Account.balance、Asset projection、receipt/envelope 在同一事务。
- [ ] 任一中途失败都完整 rollback。
- [ ] original fact、posting receipt 和 correction envelope 保持不可变。
- [ ] candidate replay、数据库二次 replay 和当前投影一致。
- [ ] projectionVersion、lastTransactionId、一次余额/一次投影不变量成立。
- [ ] opening、BUY、SELL、DIVIDEND、reversal 或 replacement 的适用边界有回归测试。
- [ ] corrected current truth 与 posting-time snapshot 没有混淆。

## 6. 并发修改

- [ ] 锁查询包含 user ownership。
- [ ] 全局锁顺序未反转；多 Account 按 ID 去重升序。
- [ ] 事务级 `lock_timeout` 仍生效。
- [ ] lock timeout/deadlock 映射为脱敏 409，未引入盲目自动重试。
- [ ] 并发同 key 只产生一个 effect 和一份 receipt。
- [ ] 同 key 不同 hash 返回 409。
- [ ] unknown unique constraint 不误判为幂等恢复。
- [ ] unknown commit 的 committed/uncommitted 两条路径均验证。
- [ ] 连续运行并发套件，确认没有悬挂锁或偶发重复写。

## 7. 文档-only

- [ ] 事实已与当前代码、V1–V13、ADR 和最新 Closing Review 交叉核对。
- [ ] README 适合快速展示，深度内容留在 Database/API/Rules。
- [ ] 简体中文为主，必要类名、字段和标准技术术语保留英文。
- [ ] 所有相对链接和图片路径存在。
- [ ] Mermaid 可在 GitHub Markdown 渲染，节点和边界语义准确。
- [ ] Demo、截图、测试和 CI 状态有可验证来源与边界声明。
- [ ] 历史测试数字明确标为历史 Closing Review 基线。
- [ ] 没有本地绝对路径、个人数据或真实财务数据。
- [ ] Architecture、ADR、历史 Closing Review、代码和配置未被修改。
- [ ] 不要求完整 Maven 测试时，最终报告明确说明原因。

## 8. Runtime / 部署修改

- [ ] 目标 profile、环境变量和 secret 来源明确。
- [ ] Compose config 或部署配置校验通过。
- [ ] 数据库 healthy、backend readiness、frontend health 通过。
- [ ] Flyway 最终版本与预期一致。
- [ ] 关键只读 API 与反向代理路径 smoke 通过。
- [ ] 日志不包含 secret、JWT、SQL 明细或敏感财务数据。
- [ ] 回滚/恢复步骤与数据风险已记录。

## 9. 阶段 Closing Review

- [ ] 范围、明确不在范围、实现 commit 和验证环境完整。
- [ ] 测试套件/测试数/failures/errors 来自实际命令输出。
- [ ] PostgreSQL、Flyway、runtime smoke 与并发证据可追溯。
- [ ] 独立 review 记录 P0/P1/P2/P3 与 GO/NO-GO。
- [ ] P0/P1 在关闭前修复并复验为 0。
- [ ] 非阻塞 P2/P3 明确记录，不无限循环 review。
- [ ] 历史 Closing Review 一旦关闭，不用后续文档修改改写证据。
- [ ] 下一阶段不会因关闭动作被自动启动。

## 10. 最终交付检查

```powershell
git diff --check
git diff --cached --check
git status --short
git diff --name-only
git diff --cached --name-only
```

完成声明必须引用本轮新鲜验证证据。仅在用户明确要求时提交和推送，并在提交后复查工作区与 ahead/behind。
