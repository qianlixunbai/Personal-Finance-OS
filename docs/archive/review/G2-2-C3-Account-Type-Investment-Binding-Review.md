# G2-2 / C3 — Account Type & Investment Position Integrity Review

日期：2026-10-11（Asia/Shanghai）。范围仅限 C3，原始严重级别 P2。

结论：**C3 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**。主控已实际完成旧代码 PostgreSQL 复现、红灯转绿、定向回归、三轮核心并发与独立 compile；两名 Luna Max 只读复审无阻断。等待人工验收，不代表已提交或 Finance Freeze。

## 1. Preflight

| 项目 | 本轮实际结果 |
| --- | --- |
| 仓库 | `F:\IDEA\daima\finance-os` |
| 分支 | `zh-cn` |
| HEAD | `c8a2e6a45d8303e30d2ccc46b4a92b316b1066d8` |
| 本地 `origin/zh-cn` | 与 HEAD 相同 |
| `git ls-remote origin refs/heads/zh-cn` | 与 HEAD 相同；独立远端查询，未 fetch/push |
| 初始 tracked / staged diff | 均为空 |
| 初始 untracked | 仅 `AGENTS.md`、`docs/codex-task.md`；授权的受保护基线，不构成阻断 |
| 执行环境 | Java 21.0.10、Docker Desktop Server 29.6.1、Spring Boot 3.3.5、Testcontainers 1.20.0、`postgres:17-alpine` |

两份受保护文件只核对 Git 状态，未读取内容、计算 hash、修改、删除或暂存。仓库规则依据用户直接提供的文本。阅读了 README、投资领域文档、consistency、testing 与相关现有代码；未使用 Superpowers、worktree 或分支流程。

## 2. 当前账户类型变更链路

`AccountController.PUT /api/v1/accounts/{id}` 从 Authentication 取得服务端 userId；`@Valid AccountRequest` 要求 name/type 非空。`AccountService.update` 的原有事务通过 `AccountBalanceService.lockOwnedAccounts` 设置事务级 lock_timeout，按用户和账户取得 `FOR UPDATE` 锁。币种仍只允许 CNY，`AccountMapper.updateMetadata` 只更新 name/type/currency/updated_at，不写 balance/status。

旧版本在锁后没有检查投资绑定，因而真实 PUT 可以把已有仓位账户改为 CASH/OTHER。新版本仅在 type 变化时，于 metadata 写入前调用绑定校验。未修改 Controller、DTO、AccountMapper、账户停用逻辑或余额原语。

## 3. 投资绑定与历史纠正语义

| 账户类型 | 兼容的 Instrument AssetClass |
| --- | --- |
| BROKERAGE | STOCK、ETF、FUND、BOND |
| CRYPTO_WALLET | CRYPTO |
| CASH、OTHER 及其他非投资类型 | 无 |

兼容集合复用 `InvestmentPositionBindingService.isSupportedAccountClass`，存在性查询使用同用户 Instrument 的 `asset_class`，不凭旧账户类型名称或前端输入推断持仓类别。

真实调用链有一项区别：BUY、SELL、DIVIDEND、standalone REVERSAL 在 Account → Instrument → Asset 锁内校验类型；REPLACEMENT 当前只复核不可变绑定身份，没有调用相同的类型兼容校验。本轮没有改动这些写路径。Replay 排除被纠正事实并计算 corrected truth，原 fact/receipt 保持不可变。

CLOSED 不是历史终点：DIVIDEND 可在 CLOSED 上合法入账；冲正 full SELL 可以重开，replacement 减少历史 SELL 数量也可以重开。零数量不代表原绑定已经可丢弃。

## 4. 修复前真实 PostgreSQL 缺陷复现

生产代码仍为指定 HEAD 时，使用继承现有 `PostgresIntegrationTest` 的隔离 PostgreSQL 17 容器，通过真实 Spring Controller/Service/MyBatis 路径执行 authenticated MockMvc PUT；不是直接 SQL 改 account.type，也不是 mock 数据库。Seed 仅创建用户、ACTIVE 账户与 Instrument，Position 和 POSTED facts 均由真实 First BUY 建立。

| 场景 | PUT 前 | 旧 PUT | PUT 后的实际结果 |
| --- | --- | --- | --- |
| OPEN，目标 CASH/OTHER（各一次） | BUY 2 × 10；balance=-20；quantity=2；cost=20；version=1；1 个 POSTED fact | 200，name/type 被持久化 | SELL 1 × 12、DIVIDEND 3、REVERSAL BUY 均 400，message 为 `Account type does not support the investment asset class` |
| CLOSED，目标 CASH/OTHER（各一次） | BUY 2 × 10，full SELL 2 × 12；balance=4；quantity/cost=0；PnL=4；version=2；2 个 POSTED facts | 200 | 原 full SELL 的 REVERSAL、合法 CLOSED DIVIDEND 均因类型校验返回 400 |
| OPEN 的 BUY replacement | 类型已被 PUT 改为 CASH/OTHER | 200 | 用 2 × 11 替换 BUY；balance=-22；quantity=2；cost=22；追加 2 facts + 1 envelope |
| CLOSED 的 SELL replacement | 类型已被 PUT 改为 CASH/OTHER | 200 | 用 SELL 1 × 12 替换 full SELL；balance=-8；quantity=1；cost=10；PnL=2；version=3；在不兼容账户上重开 |

每个 400 后比较 accounts/assets/investment_transactions/investment_transaction_corrections 的完整行快照（包括时间戳、投影、receipt 与审计字段），确认未发生部分写入。OPEN 的 PUT 前后完整快照与成功 replacement 后完整状态，以及 CLOSED 初始/最终状态，已实际输出到 `backend/target/c3-old-behavior-fixture-corrected.log`。报告附录保存一组完整 OPEN 原始状态。

失败阶段是写 fact 前的 `validateBinding`；SELL 数量 1 小于现有 2，DIVIDEND 前确有有效 BUY，CLOSED 的 SELL reversal 在保留账户类型时可成功，排除了持仓不足、交易顺序或无有效 BUY 的混淆。

另行将验收断言改为“类型变更必须 409”后，在旧生产代码上运行 OPEN/CLOSED 共 4 次：全部失败于 expected 409 / actual 200，形成实际红灯证据 `backend/target/c3-regression-red.log`。

## 5. OPEN / CLOSED / LEGACY 边界

| 状态 | 本轮规则及实测 |
| --- | --- |
| type 未改变 | 允许合法元数据修改；含 inactive Account/Instrument；不增加绑定查询 |
| 无 transaction-driven 绑定 | BROKERAGE → CASH、CASH → BROKERAGE 成功 |
| OPEN transaction-driven | 不兼容类型变更 409，完整状态不变；之后 SELL、DIVIDEND、追加 BUY 成功 |
| CLOSED transaction-driven | 同样拒绝不兼容变更；之后 CLOSED DIVIDEND、SELL REVERSAL / REPLACEMENT 成功，历史纠正可重开 |
| CRYPTO_WALLET ↔ BROKERAGE | 按实际 Instrument 类别拒绝不兼容互换；五类 Instrument 分别验收 |
| 仅 LEGACY account binding | 不扩大禁改范围；原类型可改变；不兼容 migration preview 被现有规则阻塞；显式 remap 至兼容账户仍可完成 |
| migration 已成功 | 成为 transaction-driven，目标账户受新规则保护 |
| 其他用户 / 同用户其他账户的绑定 | 不误阻止空账户修改；跨用户 PUT 仍是安全 404 |

查询不筛 quantity、position_status、Instrument status，也不要求仍有未纠正 BUY；transaction-driven 绑定本身仍需合法。本轮不迁移、不解绑、不改事实。

## 6. 方案取舍与 Design Checkpoint

采用用户授权的最小方案：既有 Account 锁内检查绑定，再更新元数据。使用现有 BusinessException / HTTP 409，不引入依赖、Flyway、数据库约束、全局锁或新的锁序。

没有发现与冻结合同冲突的设计要求；CLOSED 保护与当前“纠正可重开、绑定不可变”语义一致，LEGACY 仍能显式 remap。因此本轮未触发需人工选择的 Design Checkpoint。没有以放松 SELL/DIVIDEND/REVERSAL 校验解决问题。

## 7. 实际修改文件完整清单

1. `backend/src/main/java/com/financeos/module/account/service/AccountService.java`：锁后、类型变化时调用校验。
2. `backend/src/main/java/com/financeos/module/asset/mapper/AssetMapper.java`：增加用户/账户域内不兼容 transaction-driven 绑定 EXISTS 查询。
3. `backend/src/main/java/com/financeos/module/investment/instrument/service/InvestmentPositionBindingService.java`：复用兼容规则生成类别集合；MANDATORY 事务内拒绝不兼容修改。
4. `backend/src/test/java/com/financeos/integration/AccountTypeInvestmentBindingPostgresIntegrationTest.java`：23 个 PostgreSQL 参数化实例，含 8 个确定性并发实例。
5. `backend/src/test/java/com/financeos/module/account/service/AccountServiceTest.java`：5 个单元用例。
6. `docs/archive/review/G2-2-C3-Account-Type-Investment-Binding-Review.md`：本报告。

Maven 运行日志和独立留存 XML 位于 Git 忽略的 `backend/target/`，不属于拟提交文件。受保护基线不是本轮新增工作。没有改前端、AI、Import、Dashboard C2 或其他 C 项。

## 8. PostgreSQL 原子性与并发验证

Account 更新事务持续持有原 Account 行锁。校验服务采用 MANDATORY，普通 EXISTS 不锁 Instrument/Asset/InvestmentTransaction，也不扫描其他账户获取写锁；LEFT JOIN 按 userId + instrumentId 连接，空兼容集合检查任意 transaction-driven 绑定，非空集合检查不兼容类别，缺失 Instrument 按不兼容处理。

First BUY、追加 BUY、SELL、Opening Migration 先锁 Account；纠正先锁 original fact，再 Account → Instrument → Asset。账户更新不反向锁 original/Instrument/Asset，因此未引入锁环。在当前默认 PostgreSQL READ_COMMITTED 下，等待 Account 锁后执行的 EXISTS 能看见先提交的绑定；检查与 metadata 写入间仍持有该锁，新的绑定写入无法插入到两者之间。

并发测试在外层真实 TransactionTemplate 内执行成功的真实操作，完成操作后用 latch 暂缓 commit；另一线程执行真实服务/HTTP 操作。主线程以有界查询确认 `pg_stat_activity.wait_event_type='Lock'`、Account `FOR UPDATE` 语句和 `pg_blocking_pids(pid)` 指向 holder，才释放 commit latch。未使用 sleep 证明安全；所有 latch、Future、executor cleanup 都有超时，结束确认无未授予锁。

| 确定性交错 | 最终结果与实际断言 |
| --- | --- |
| First BUY 先持锁，改类型等待 | BUY 200；PUT 409；BROKERAGE/quantity=2/balance=-20/version=1/1 fact，名称未部分更新 |
| 改类型先持锁，First BUY 等待 | PUT 200；BUY 400；CASH，无 Asset/fact/余额 effect |
| 已有仓位 BUY / SELL / REVERSAL / REPLACEMENT 先持锁，改类型等待（4 实例） | 投资命令 200；PUT 409；类型/名称合法；数量、余额、version、fact/envelope 数精确校验；原 fact 完整不变 |
| Opening Migration 先持锁，改类型等待 | migration 200；PUT 409；transaction-driven/quantity=2/version=1/1 opening fact，balance=0 |
| 改类型先持锁，Opening Migration 等待 | PUT 200；migration 409（stale preview）；LEGACY 完整行不变、无 opening fact、balance=0 |

这 8 个实例在修正测试合同后的三轮中均通过，共 24 次执行。对同一合法输入，失败的账户更新没有部分 metadata、balance、facts 或 projection 修改。

## 9. HTTP 合同

新增冲突使用现有 GlobalExceptionHandler 的 BusinessException 映射：HTTP 409、`code=409`、`message=账户类型与已有投资仓位绑定不兼容`。真实 Controller/Service/PostgreSQL 路径已断言。

保持的合同：合法 metadata PUT 200、跨用户资源 404 / code 404、无效 CNY/DTO 400、并发后不兼容 First BUY 400、migration 目标变化 409。测试中的 authenticated MockMvc 是实际 Spring HTTP 处理链，未把它表述为新的 TCP/JWT smoke；现有真实 JWT Account API 定向回归结果见下一节。

## 10. 既有业务定向回归

以下 11 个 suite 已由主控本轮实际运行，全数通过（49 实例，failure/error/skipped 均为 0），对应 `backend/target/c3-regression.log` 与本轮 Surefire XML。未运行整个后端/前端全量套件，也未调用真实付费 Provider。

| Suite | 实际实例数 | 相关回归范围 |
| --- | ---: | --- |
| AuthAccountApiIntegrationTest | 2 | 真实 JWT、账户读取用户隔离、认证边界 |
| TransactionBalanceConcurrencyPostgresIntegrationTest | 7 | AccountBalance 真实并发、余额联动、归属、跨账户锁序与回滚 |
| InvestmentInstrumentMapperPostgresIntegrationTest | 4 | Instrument、绑定规则及用户域 |
| InvestmentCommandApiIntegrationTest | 9 | First/追加 BUY、partial/full SELL、DIVIDEND、幂等与金额/状态合同 |
| InvestmentDividendFailureIntegrationTest | 1 | Dividend 一致性失败后的真实事务回滚 |
| InvestmentReversalApiIntegrationTest | 11 | REVERSAL 合同、合法/非法历史、receipt、原始事实不变 |
| InvestmentReversalConcurrencyIntegrationTest | 1 | 同 original 并发纠正 |
| InvestmentReplacementApiIntegrationTest | 6 | BUY/SELL/DIVIDEND replacement、幂等、历史约束 |
| InvestmentReplacementConcurrencyIntegrationTest | 2 | replacement 并发、与 standalone reversal 竞争 |
| LegacyOpeningMigrationConcurrencyIntegrationTest | 5 | 真实 migration 并发、token/source 漂移、绑定写失败回滚 |
| LegacyOpeningMigrationWebFailureIntegrationTest | 1 | migration 一致性失败回滚及脱敏 HTTP 500 |

## 11. 测试总数、失败与重复记录

计数按 Surefire 的参数化实例统计，重复执行不增加唯一用例数。

| 阶段 / 日志（均在 backend/target） | 执行数 | 失败 / error / skipped | 判定 |
| --- | ---: | --- | --- |
| Maven Wrapper 初次启动 | 0 | JAVA_HOME 缺失，未进入测试 | 环境问题；仅为当前命令设置 `F:\ideajava\jdk21` |
| `c3-old-behavior.log` | 4 | 4 / 0 / 0 | 初始 fixture 未传必填 fee/tax，First BUY 400；未修改生产行为 |
| `c3-old-behavior-fixture-corrected.log` | 4 | 0 / 0 / 0 | 旧缺陷真实行为断言通过，不是旧生产满足验收 |
| `c3-regression-red.log` | 4 | 4 / 0 / 0 | 旧生产的预期红灯：PUT 应 409，实际 200 |
| `c3-green-initial.log` | 23 | 6 / 1 / 0 | 16 通过；剩余为新增测试对既有 DTO/DB/preview 合同的误用 |
| `c3-green-round1.log` | 28 | 0 / 0 / 0 | 修正后 23 PostgreSQL + 5 unit 全绿，含 8 个核心并发 |
| `c3-concurrency-round2.log` | 8 | 0 / 0 / 0 | 仅重复核心并发 |
| `c3-concurrency-round3.log` | 8 | 0 / 0 / 0 | 仅重复核心并发 |
| `c3-regression.log` | 49 | 0 / 0 / 0 | 11 个定向 suite 全绿 |

`c3-green-initial` 的具体根因：5 个 lower-case/space 账户类型成功断言违反既有 V1 type CHECK；1 个 REVERSAL 用例误按 REPLACEMENT 的 nested finalPosition 查询返回字段；1 个 Legacy blocked preview 误假设 confirmation 非空。修正合法输入及真实字段路径，并增加 blockingErrors 断言；未删除测试、Disabled、吞异常、弱化关键业务断言或修改生产以迎合 fixture。

最终验收唯一实例 **77 = 23 新增 PostgreSQL + 5 新增 unit + 49 既有定向回归**。最终绿灯执行次数 **93 = 77 + 16 并发重复**，failure/error/skipped 均为 0；核心并发唯一实例 8，三轮 24 次。旧行为证明、预期红灯和开发期失败单列，不混入最终验收唯一数。

所有开发阶段合计实际测试执行 128 次（113 passed、14 failures、1 error、0 skipped），其中包含旧缺陷行为断言和预期红灯；这不是最终验收唯一用例数。首次修复后整轮因 fixture 失败，虽其中 8 个并发已通过，仍未用该轮替代三轮最终绿灯。

留存 XML：`backend/target/c3-round1.xml`（23）、`c3-unit.xml`（5）、`c3-round2.xml` / `c3-round3.xml`（各 8）。以下为实际执行命令（工作目录 backend，当前进程设置 JAVA_HOME，不写入仓库配置）：

```powershell
$env:JAVA_HOME = 'F:\ideajava\jdk21'
.\mvnw.cmd '-Dtest=AccountTypeInvestmentBindingPostgresIntegrationTest#openPositionRejectsIncompatibleTypeAtomicallyAndKeepsLegalCommands+closedHistoryRejectsTypeChangeAndStillAllowsCorrectionsToReopen' test
.\mvnw.cmd '-Dtest=AccountTypeInvestmentBindingPostgresIntegrationTest,AccountServiceTest' test
.\mvnw.cmd '-Dtest=AccountTypeInvestmentBindingPostgresIntegrationTest#concurrentFirstBuyAndTypeChangeSerializeAtAccountRow+concurrentExistingPositionWriteAndTypeChangePreserveBinding+concurrentOpeningMigrationAndTypeChangeRevalidateUnderAccountLock' test
# 上一条专项命令在 round2、round3 各执行一次。
.\mvnw.cmd '-Dtest=AuthAccountApiIntegrationTest,TransactionBalanceConcurrencyPostgresIntegrationTest,InvestmentCommandApiIntegrationTest,InvestmentDividendFailureIntegrationTest,InvestmentReversalApiIntegrationTest,InvestmentReversalConcurrencyIntegrationTest,InvestmentReplacementApiIntegrationTest,InvestmentReplacementConcurrencyIntegrationTest,LegacyOpeningMigrationConcurrencyIntegrationTest,LegacyOpeningMigrationWebFailureIntegrationTest,InvestmentInstrumentMapperPostgresIntegrationTest' test
.\mvnw.cmd compile
```

第一条红灯命令在生产修复前执行；最终旧行为方法已改为验收测试，不保留会要求错误行为的测试分支。

## 12. Maven compile 与工程检查

独立 `.\mvnw.cmd compile` exit 0、BUILD SUCCESS，记录在 `backend/target/c3-compile.log`（仅编译，不算测试）。最终 `git diff --check` 与完整 diff/status 检查无空白错误或无关改动；仅有 Git LF/CRLF 工作区提示。新增文件另行检查空白，staged diff 为空，HEAD/branch 不变。

## 13. 两个 Luna Max 独立复审

两名 Agent 均为用户指定的 GPT-6 Luna / Max，只读、文件职责明确，不独立执行 Maven。

- Agent 1：领域约束、OPEN/CLOSED、历史纠正、锁序、用户隔离与事务一致性。已检查实际最终生产 diff 和新增测试源码；无 P0/P1，无阻断。确认 replacement 类型校验差异和 LEGACY remap 边界。其结论是独立静态审查，不是独立实跑。
- Agent 2：PostgreSQL 红灯转绿、测试合同、并发可靠性、HTTP 与回归范围。已独立阅读实际代码及日志/XML，复核 4 个旧行为、4 个预期红灯、28 个新增验收、两轮各 8 个并发专项与 49 个回归结果。无 P0/P1 或阻断性缺陷；确认初始失败属于 fixture/响应字段合同。未独立实跑。

主控已自行核验 Agent 1 涉及的真实调用链和 SQL 条件，并独立解析本轮日志/XML复核 Agent 2 的数字，没有直接依据子 Agent 报告修改生产代码。两名复审均未读取受保护文件、未写文件、未执行 Maven；测试执行结果均归主控本轮实跑。

## 14. Findings

| 级别 | 结果 |
| --- | --- |
| P0 | 当前未发现 |
| P1 | 当前未发现 |
| P2 原 C3 | 已有 OPEN/CLOSED 合法绑定可被类型修改破坏：已修复，实际复现/验收/定向回归通过 |
| P2 防御性边界 | 若外部 SQL 或既有脏数据已造成不兼容绑定，REPLACEMENT 不自行校验类型。本轮只防止合法 Account 更新制造该状态；未扩大范围改纠正写路径 |

## 15. 未验证范围与遗留风险

- 未运行全后端、前端、E2E 浏览器、真实 TCP PUT smoke 或外部 Provider；本轮使用与修改匹配的真实 PostgreSQL + authenticated MockMvc 与定向既有 JWT suite。
- 不自动修复历史不兼容数据；type 不变时按授权允许合法 metadata 更新。
- 并发结论覆盖当前 HTTP 服务默认 READ_COMMITTED、既有同 Account 锁写路径。未来新增绕开 Account 锁的绑定写入口、直接 SQL，或把更新调用加入预先建立快照的其他隔离级别事务，需要另行验证。
- 本轮不测试高容量查询性能；EXISTS 可使用既有 `(user, account, instrument)` transaction-driven 部分索引，不新增全局串行化。
- 忽略的 target 日志/留存 XML 是本轮本机证据，后续 clean 可清除；附录与上述状态/结果保留在本报告，测试源码保留可重跑验收。

## 16. 受保护文件与 Git 状态

`AGENTS.md` 与 `docs/codex-task.md` 保持 `??`，未读取/计算 hash/操作内容。未 commit、push、tag、stash、reset、clean、checkout 覆盖、rebase、amend 或建立 branch/worktree。未进入 C8 / Finance Freeze，未操作 Personal AI Workspace / Local AI Assistant。

## 17. 最终 GO / NO-GO

**C3 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**。

本轮无 P0/P1 阻断，原 C3 已有真实旧行为证明、最小生产修复与真实 PostgreSQL 验收。72 个 PostgreSQL / API 回归实例与 5 个 unit 实例通过；核心并发三轮通过；独立 compile、diff 检查通过；两名指定模型只读复审未发现阻断。

下一步仅为人工验收当前 diff 与本报告。本轮停止于此，未 commit/push/tag，未进入其他 C 项或 Finance Freeze。已知防御性边界与未验证范围见第 14–15 节。

## 附录：旧代码完整数据库状态（原始输出）

以下均来自第一次 CASH 场景的实际旧行为日志；只包含隔离测试的合成数据，不含用户凭据。accounts/assets/facts/corrections 取 SELECT *，未裁剪字段。失败后的无部分写入由完整 Map 相等断言验证。

<details>
<summary>C3 OLD OPEN BEFORE </summary>

```json
{"corrections":[],"assets":[{"id":1,"user_id":1,"name":"C3 Instrument","symbol":"C3","type":"FUND","market":"FUND","currency":"CNY","quantity":2.00000000,"avg_cost":10.00000000,"current_price":null,"market_value":null,"created_at":"2026-10-11T07:02:54.590+00:00","updated_at":"2026-10-11T07:02:54.564+00:00","account_id":1,"total_cost":20.00,"realized_profit_loss":0.00,"position_status":"OPEN","last_transaction_id":1,"projection_version":1,"position_mode":"TRANSACTION_DRIVEN","instrument_id":1}],"facts":[{"id":1,"user_id":1,"asset_id":1,"account_id":1,"transaction_type":"BUY","status":"POSTED","quantity":2.00000000,"unit_price":10.00000000,"gross_amount":20.00,"fee_amount":0.00,"tax_amount":0.00,"net_amount":20.00,"released_cost_amount":0.00,"realized_profit_loss":0.00,"currency":"CNY","trade_time":"2026-10-11T07:02:54.606+00:00","settlement_time":"2026-10-11T07:02:54.606+00:00","note":null,"external_reference":null,"source":"MANUAL","idempotency_key":"first","request_hash":"7ab784f32aabdfd271fcf4515b15977f337001dd3b2c3992c21669f831028c43","replaces_transaction_id":null,"reversed_at":null,"reversal_reason":null,"created_at":"2026-10-11T07:02:54.606+00:00","updated_at":"2026-10-11T07:02:54.564+00:00","account_balance_after":-20.00,"position_quantity_after":2.00000000,"position_avg_cost_after":10.00000000,"position_total_cost_after":20.00,"position_realized_profit_loss_after":0.00,"position_status_after":"OPEN","projection_version_after":1,"original_transaction_id":null,"correction_reason":null,"cash_delta":null,"correction_group_id":null,"replay_anchor_transaction_id":null,"replay_sequence":0}],"accounts":[{"id":1,"user_id":1,"name":"Original","type":"BROKERAGE","currency":"CNY","balance":-20.00,"status":"ACTIVE","created_at":"2026-10-11T07:02:54.218+00:00","updated_at":"2026-10-11T07:02:54.564+00:00"}]}
```

</details>

<details>
<summary>C3 OLD OPEN AFTER PUT </summary>

```json
{"corrections":[],"assets":[{"id":1,"user_id":1,"name":"C3 Instrument","symbol":"C3","type":"FUND","market":"FUND","currency":"CNY","quantity":2.00000000,"avg_cost":10.00000000,"current_price":null,"market_value":null,"created_at":"2026-10-11T07:02:54.590+00:00","updated_at":"2026-10-11T07:02:54.564+00:00","account_id":1,"total_cost":20.00,"realized_profit_loss":0.00,"position_status":"OPEN","last_transaction_id":1,"projection_version":1,"position_mode":"TRANSACTION_DRIVEN","instrument_id":1}],"facts":[{"id":1,"user_id":1,"asset_id":1,"account_id":1,"transaction_type":"BUY","status":"POSTED","quantity":2.00000000,"unit_price":10.00000000,"gross_amount":20.00,"fee_amount":0.00,"tax_amount":0.00,"net_amount":20.00,"released_cost_amount":0.00,"realized_profit_loss":0.00,"currency":"CNY","trade_time":"2026-10-11T07:02:54.606+00:00","settlement_time":"2026-10-11T07:02:54.606+00:00","note":null,"external_reference":null,"source":"MANUAL","idempotency_key":"first","request_hash":"7ab784f32aabdfd271fcf4515b15977f337001dd3b2c3992c21669f831028c43","replaces_transaction_id":null,"reversed_at":null,"reversal_reason":null,"created_at":"2026-10-11T07:02:54.606+00:00","updated_at":"2026-10-11T07:02:54.564+00:00","account_balance_after":-20.00,"position_quantity_after":2.00000000,"position_avg_cost_after":10.00000000,"position_total_cost_after":20.00,"position_realized_profit_loss_after":0.00,"position_status_after":"OPEN","projection_version_after":1,"original_transaction_id":null,"correction_reason":null,"cash_delta":null,"correction_group_id":null,"replay_anchor_transaction_id":null,"replay_sequence":0}],"accounts":[{"id":1,"user_id":1,"name":"Changed","type":"CASH","currency":"CNY","balance":-20.00,"status":"ACTIVE","created_at":"2026-10-11T07:02:54.218+00:00","updated_at":"2026-10-11T07:02:54.739+00:00"}]}
```

</details>

<details>
<summary>C3 OLD CLOSED BEFORE </summary>

```json
{"corrections":[],"assets":[{"id":1,"user_id":1,"name":"C3 Instrument","symbol":"C3","type":"FUND","market":"FUND","currency":"CNY","quantity":0E-8,"avg_cost":0E-8,"current_price":null,"market_value":null,"created_at":"2026-10-11T07:02:55.678+00:00","updated_at":"2026-10-11T07:02:55.710+00:00","account_id":1,"total_cost":0.00,"realized_profit_loss":4.00,"position_status":"CLOSED","last_transaction_id":2,"projection_version":2,"position_mode":"TRANSACTION_DRIVEN","instrument_id":1}],"facts":[{"id":1,"user_id":1,"asset_id":1,"account_id":1,"transaction_type":"BUY","status":"POSTED","quantity":2.00000000,"unit_price":10.00000000,"gross_amount":20.00,"fee_amount":0.00,"tax_amount":0.00,"net_amount":20.00,"released_cost_amount":0.00,"realized_profit_loss":0.00,"currency":"CNY","trade_time":"2026-10-11T07:02:55.683+00:00","settlement_time":"2026-10-11T07:02:55.683+00:00","note":null,"external_reference":null,"source":"MANUAL","idempotency_key":"first","request_hash":"7ab784f32aabdfd271fcf4515b15977f337001dd3b2c3992c21669f831028c43","replaces_transaction_id":null,"reversed_at":null,"reversal_reason":null,"created_at":"2026-10-11T07:02:55.683+00:00","updated_at":"2026-10-11T07:02:55.668+00:00","account_balance_after":-20.00,"position_quantity_after":2.00000000,"position_avg_cost_after":10.00000000,"position_total_cost_after":20.00,"position_realized_profit_loss_after":0.00,"position_status_after":"OPEN","projection_version_after":1,"original_transaction_id":null,"correction_reason":null,"cash_delta":null,"correction_group_id":null,"replay_anchor_transaction_id":null,"replay_sequence":0},{"id":2,"user_id":1,"asset_id":1,"account_id":1,"transaction_type":"SELL","status":"POSTED","quantity":2.00000000,"unit_price":12.00000000,"gross_amount":24.00,"fee_amount":0.00,"tax_amount":0.00,"net_amount":24.00,"released_cost_amount":20.00,"realized_profit_loss":4.00,"currency":"CNY","trade_time":"2026-10-11T07:02:55.720+00:00","settlement_time":"2026-10-11T07:02:55.720+00:00","note":null,"external_reference":null,"source":"MANUAL","idempotency_key":"close","request_hash":"28466684a66f98bf4c4b4a6671f7dda1c84bb95798bb46040db217409d568f5d","replaces_transaction_id":null,"reversed_at":null,"reversal_reason":null,"created_at":"2026-10-11T07:02:55.720+00:00","updated_at":"2026-10-11T07:02:55.710+00:00","account_balance_after":4.00,"position_quantity_after":0E-8,"position_avg_cost_after":0E-8,"position_total_cost_after":0.00,"position_realized_profit_loss_after":4.00,"position_status_after":"CLOSED","projection_version_after":2,"original_transaction_id":null,"correction_reason":null,"cash_delta":null,"correction_group_id":null,"replay_anchor_transaction_id":null,"replay_sequence":0}],"accounts":[{"id":1,"user_id":1,"name":"Original","type":"BROKERAGE","currency":"CNY","balance":4.00,"status":"ACTIVE","created_at":"2026-10-11T07:02:55.659+00:00","updated_at":"2026-10-11T07:02:55.710+00:00"}]}
```

</details>

<details>
<summary>C3 OLD CLOSED REPLACEMENT ACCEPTED </summary>

```json
{"corrections":[{"id":1,"correction_group_id":"8966dc99-5a2f-4bfe-98b7-d49044cce3bf","user_id":1,"account_id":1,"asset_id":1,"instrument_id":1,"original_transaction_id":2,"transaction_type":"SELL","correction_kind":"REPLACEMENT","idempotency_key":"replace","request_hash":"0a542ed2932cb8efa5693dc289cdf391b3f0f05247f375ebce4e2b4b94a833c3","correction_reason":"C3 correction","reversal_transaction_id":3,"replacement_transaction_id":4,"reversal_cash_delta":-24.00,"replacement_cash_delta":12.00,"command_cash_delta":-12.00,"balance_after":-8.00,"position_quantity_after":1.00000000,"position_avg_cost_after":10.00000000,"position_total_cost_after":10.00,"position_realized_profit_loss_after":2.00,"position_status_after":"OPEN","projection_version":3,"last_transaction_id":4,"created_at":"2026-10-11T07:02:55.859+00:00"}],"assets":[{"id":1,"user_id":1,"name":"C3 Instrument","symbol":"C3","type":"FUND","market":"FUND","currency":"CNY","quantity":1.00000000,"avg_cost":10.00000000,"current_price":null,"market_value":null,"created_at":"2026-10-11T07:02:55.678+00:00","updated_at":"2026-10-11T07:02:55.823+00:00","account_id":1,"total_cost":10.00,"realized_profit_loss":2.00,"position_status":"OPEN","last_transaction_id":4,"projection_version":3,"position_mode":"TRANSACTION_DRIVEN","instrument_id":1}],"facts":[{"id":1,"user_id":1,"asset_id":1,"account_id":1,"transaction_type":"BUY","status":"POSTED","quantity":2.00000000,"unit_price":10.00000000,"gross_amount":20.00,"fee_amount":0.00,"tax_amount":0.00,"net_amount":20.00,"released_cost_amount":0.00,"realized_profit_loss":0.00,"currency":"CNY","trade_time":"2026-10-11T07:02:55.683+00:00","settlement_time":"2026-10-11T07:02:55.683+00:00","note":null,"external_reference":null,"source":"MANUAL","idempotency_key":"first","request_hash":"7ab784f32aabdfd271fcf4515b15977f337001dd3b2c3992c21669f831028c43","replaces_transaction_id":null,"reversed_at":null,"reversal_reason":null,"created_at":"2026-10-11T07:02:55.683+00:00","updated_at":"2026-10-11T07:02:55.668+00:00","account_balance_after":-20.00,"position_quantity_after":2.00000000,"position_avg_cost_after":10.00000000,"position_total_cost_after":20.00,"position_realized_profit_loss_after":0.00,"position_status_after":"OPEN","projection_version_after":1,"original_transaction_id":null,"correction_reason":null,"cash_delta":null,"correction_group_id":null,"replay_anchor_transaction_id":null,"replay_sequence":0},{"id":2,"user_id":1,"asset_id":1,"account_id":1,"transaction_type":"SELL","status":"POSTED","quantity":2.00000000,"unit_price":12.00000000,"gross_amount":24.00,"fee_amount":0.00,"tax_amount":0.00,"net_amount":24.00,"released_cost_amount":20.00,"realized_profit_loss":4.00,"currency":"CNY","trade_time":"2026-10-11T07:02:55.720+00:00","settlement_time":"2026-10-11T07:02:55.720+00:00","note":null,"external_reference":null,"source":"MANUAL","idempotency_key":"close","request_hash":"28466684a66f98bf4c4b4a6671f7dda1c84bb95798bb46040db217409d568f5d","replaces_transaction_id":null,"reversed_at":null,"reversal_reason":null,"created_at":"2026-10-11T07:02:55.720+00:00","updated_at":"2026-10-11T07:02:55.710+00:00","account_balance_after":4.00,"position_quantity_after":0E-8,"position_avg_cost_after":0E-8,"position_total_cost_after":0.00,"position_realized_profit_loss_after":4.00,"position_status_after":"CLOSED","projection_version_after":2,"original_transaction_id":null,"correction_reason":null,"cash_delta":null,"correction_group_id":null,"replay_anchor_transaction_id":null,"replay_sequence":0},{"id":3,"user_id":1,"asset_id":1,"account_id":1,"transaction_type":"REVERSAL","status":"POSTED","quantity":2.00000000,"unit_price":12.00000000,"gross_amount":24.00,"fee_amount":0.00,"tax_amount":0.00,"net_amount":24.00,"released_cost_amount":0.00,"realized_profit_loss":0.00,"currency":"CNY","trade_time":"2026-10-11T07:02:55.840+00:00","settlement_time":"2026-10-11T07:02:55.840+00:00","note":null,"external_reference":null,"source":"CORRECTION","idempotency_key":"corr:8966dc99-5a2f-4bfe-98b7-d49044cce3bf:reversal","request_hash":"d5a20c327cc0c5efaf1dccdcd6d259b0388e6a5920eedcb2e8439ac717f15c67","replaces_transaction_id":null,"reversed_at":null,"reversal_reason":null,"created_at":"2026-10-11T07:02:55.840+00:00","updated_at":"2026-10-11T07:02:55.823+00:00","account_balance_after":null,"position_quantity_after":null,"position_avg_cost_after":null,"position_total_cost_after":null,"position_realized_profit_loss_after":null,"position_status_after":null,"projection_version_after":null,"original_transaction_id":2,"correction_reason":"C3 correction","cash_delta":-24.00,"correction_group_id":"8966dc99-5a2f-4bfe-98b7-d49044cce3bf","replay_anchor_transaction_id":null,"replay_sequence":0},{"id":4,"user_id":1,"asset_id":1,"account_id":1,"transaction_type":"SELL","status":"POSTED","quantity":1.00000000,"unit_price":12.00000000,"gross_amount":12.00,"fee_amount":0.00,"tax_amount":0.00,"net_amount":12.00,"released_cost_amount":10.00,"realized_profit_loss":2.00,"currency":"CNY","trade_time":"2026-10-11T07:02:55.720+00:00","settlement_time":"2026-10-11T07:02:55.720+00:00","note":null,"external_reference":null,"source":"CORRECTION","idempotency_key":"corr:8966dc99-5a2f-4bfe-98b7-d49044cce3bf:replacement","request_hash":"f3f3f1d8f9013864671dd62eb418159c6a8a1ce41d21e3d376fe1d07b62887e5","replaces_transaction_id":null,"reversed_at":null,"reversal_reason":null,"created_at":"2026-10-11T07:02:55.845+00:00","updated_at":"2026-10-11T07:02:55.823+00:00","account_balance_after":-8.00,"position_quantity_after":1.00000000,"position_avg_cost_after":10.00000000,"position_total_cost_after":10.00,"position_realized_profit_loss_after":2.00,"position_status_after":"OPEN","projection_version_after":3,"original_transaction_id":null,"correction_reason":null,"cash_delta":12.00,"correction_group_id":"8966dc99-5a2f-4bfe-98b7-d49044cce3bf","replay_anchor_transaction_id":2,"replay_sequence":1}],"accounts":[{"id":1,"user_id":1,"name":"Changed","type":"CASH","currency":"CNY","balance":-8.00,"status":"ACTIVE","created_at":"2026-10-11T07:02:55.659+00:00","updated_at":"2026-10-11T07:02:55.823+00:00"}]}
```

</details>
