# G2-1 / C2 — Dashboard Snapshot Consistency Review

日期：2026-10-11（Asia/Shanghai）

**结论：C2 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。等待人工验收。**

真实 PostgreSQL 旧行为红灯已取得。用户答复 Design Checkpoint，明确授权 Dashboard 局部 REQUIRES_NEW；生产修改仅一个读取入口注解和三个 imports。修复后 6 类、48 个唯一用例最后一次全部通过，7 个新增用例各运行 3 轮；修复后总计 62 次执行，0 failures/errors/skipped。实际连接、RR/readOnly、固定快照、并发 BUY 提交、外层事务挂起/恢复均已验证。两个 GPT-6 Luna Max 只读复审未发现阻塞问题；Maven compile 和 diff 检查完成。未 commit/push/Tag，未进入 C3 或 Finance Freeze。

## 1. Preflight

实际执行 `git status --short`、`git branch --show-current`、`git rev-parse HEAD`、`git rev-parse origin/zh-cn`、`git ls-remote --heads origin zh-cn`、tracked/staged name-status diff 和 untracked 清单。

| 项目 | 结果 |
| --- | --- |
| Branch | `zh-cn` |
| HEAD | `f4aa66a9fe678a8c708a4ac49cd8875c26c3ef4b` |
| 本地 origin/zh-cn | 同一 SHA |
| 独立远端 refs/heads/zh-cn | 同一 SHA |
| tracked diff / staged diff | 均为空 |
| 原有 untracked | 仅 `AGENTS.md`、`docs/codex-task.md` |
| 异常工作区修改 | 未发现 |

用户授权恢复时重新核对上述分支、HEAD、origin 与独立远端 SHA，仍全部相同；tracked/staged diff 为空，除两个受保护文件外仅存在上一阶段生成的 C2 测试与报告，均为已知任务成果，没有异常修改。

受保护文件只检查路径状态及元数据，未读取内容、未计算 hash。未读取仓库 AGENTS.md；本轮遵循用户消息中直接提供的规则。相关 README、Testing、Consistency、BUY/SELL 和只读 Finance Tool ADR、实际业务代码及既有测试已阅读。

## 2. Dashboard 原始读取链路

`DashboardController.dashboard` → 注入的 `DashboardService.getDashboard(userId)`。

Dashboard 先用同一个 Clock 计算本月起点与当前时间，再顺序调用：

| 顺序 | Service / Mapper | 读取内容 |
| --- | --- | --- |
| 1 | AccountQueryService / AccountMapper | 该用户 CNY 账户余额，Java 合计 |
| 2 | AssetQueryService / AssetMapper | 该用户 CNY Asset，currentPrice × quantity |
| 3 | TransactionQueryService / TransactionMapper | 当月 INCOME |
| 4 | 同上 | 当月 EXPENSE |
| 5 | 同上 | 最近六个月分月现金流 |
| 6 | 同上 | 最近 5 条普通 Transaction |
| 7 | AccountQueryService / AccountMapper | 最近流水中的所属账户名称 |
| 8 | CategoryQueryService / CategoryMapper | 用户可见分类名称，含系统分类 |

名称集合为空时 7/8 会短路。本轮 fixture 有两条普通流水，八条查询都实际执行。DashboardDto 未修改。Dashboard 不使用缓存行情/FX 估值服务；此处资产值仍是既有 Asset.currentPrice × quantity，不是 Investment Portfolio 的另一套参考估值口径。

## 3. 原事务与实际 PostgreSQL 隔离级别

DashboardService 和上述四个 QueryService 无 `@Transactional`。生产源码没有这些查询的 REQUIRES_NEW；单 DataSource，由项目现有 Spring/MyBatis 事务基础设施管理，没有发现另设的事务管理器或 Dashboard 默认隔离配置。

真实测试的八次 MyBatis prepare 均记录：Spring transaction=false、Spring readOnly=false、JDBC autocommit=true、JDBC isolation=2（READ COMMITTED）、PostgreSQL `transaction_isolation=read committed`、`transaction_read_only=off`。

同一 PostgreSQL PID 被池复用不能证明同一事务；日志中的 Hikari JDBC wrapper 为多次借还，快照随 BUY 提交发生变化。

PostgreSQL READ COMMITTED 的连续 SELECT 即使在同一事务内也可能使用不同快照；REPEATABLE READ 固定首次非事务控制语句的快照。[PostgreSQL 17 官方说明](https://www.postgresql.org/docs/17/transaction-iso.html)

## 4. 真实并发旧行为复现

新增 `DashboardSnapshotConsistencyPostgresIntegrationTest`，继承既有 `PostgresIntegrationTest`，使用 `postgres:17-alpine` 隔离容器。实际日志报告 PostgreSQL 17.11，动态 JDBC URL 为容器临时端口的 `/test`，未连接真实用户财务数据库。

fixture 的 User/Account/Instrument/Category 用隔离数据库初始 SQL 创建。普通收入、支出通过真实 TransactionService 创建；首次 BUY 和交错中的追加 BUY 都通过真实 InvestmentCommandService；参考价通过真实 AssetService.updatePrice 设置。没有伪造投资账本、mock 财务结果或改变业务规则。

| 财务字段 | BUY 前已提交状态 | BUY 后已提交状态 | 旧 Dashboard 交错响应 |
| --- | ---: | ---: | ---: |
| Account.balance | 980.00 | 957.00 | 使用旧余额 980.00 |
| Asset.quantity | 2.00000000 | 4.00000000 | 使用新数量 4.00000000 |
| Asset.currentPrice | 20.0000 | 20.0000 | 20.0000 |
| Asset.totalCost | 20.00 | 43.00 | DTO 不暴露此字段 |
| projectionVersion | 1 | 2 | DTO 不暴露此字段 |
| Asset 分布值 | 40 | 80 | 80 |
| totalAssets / netWorth | 1020 | 1037 | **1060** |
| 当月 INCOME / EXPENSE / net | 1100 / 100 / 1000 | 同左 | 同左 |
| 普通流水数量 | 2 | 2 | 2 |
| 投资事实数量 | 1 | 2 | DTO 不暴露投资事实 |

追加 BUY：数量 2、成交单价 10、fee 3、tax 0，真实 cashDelta=-23。BUY 不写普通 Transaction，故不计入普通收入/支出及最近普通流水，保持既有口径。

最终 direct 用例实际读取、断言并输出完整 before 与 after Dashboard；交错值为 980 + 80 = 1060，既不等于 before 1020，也不等于 after 1037。latest 日志的 direct 查询前证据快照为 `823:823:` → `824:824:`；首个有效单用例红灯为 `792:792:` → `793:793:`。

## 5. BUY 与 Dashboard 精确交错

1. 主测试线程建立并提交 fixture，取得完整 before Dashboard。
2. A 通过真实 Service / MockMvc / ToolDispatcher 开始 Dashboard。
3. AccountQueryService 的 test spy 执行 `callRealMethod()`；真实余额 SELECT 完成，确认结果为 980.00。
4. A 发出 `balanceRead` latch，然后等待 `resume` latch，尚未读 Asset。
5. B 调用真实 InvestmentCommandService.buy → transactional service.trade；Account → Instrument → Asset 锁序及写事务保持原样。
6. B 插入 POSTED BUY，更新一次 Account.balance，真实 replay/投影与 receipt 检查，事务提交；非事务 facade 返回，主线程 `writer.get` 获得回执。
7. A 仍处于暂停。主线程用独立 JdbcTemplate 查回余额 957、数量 4、成本 43、版本 2、lastTransactionId 与回执一致、两条投资事实、BUY net_amount=23；因此不仅凭方法返回推断 commit。
8. 主线程释放 `resume`；A 执行 Asset 及余下查询，形成响应。
9. 主线程再次检查受影响持久化状态，direct 用例再读完整 after Dashboard。

没有 sleep 或固定延迟。latch 等待最多 15 秒，BUY Future 最多 10 秒，reader Future 最多 15 秒。finally 无条件释放 latch、取消 Future、shutdownNow，并最多 5 秒等待线程退出。BUY 在 Dashboard 继续读取前成功提交，因此旧行为复现没有靠串行化查询制造“通过”。

## 6. 设计选择、替代方案与 Design Checkpoint

首选基础是 Dashboard 局部 `@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)`；不改全局隔离级别、不扩展其他读服务。

但默认 REQUIRED 遇到已有事务，会加入外层，不自动提升隔离或只读属性。Spring 默认宽松接受外层事务，不能仅以注解存在推断保证。[Spring 官方传播说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)

**实际提交的 Design Checkpoint 与用户选择：**

| 方案 | 保证与代价 |
| --- | --- |
| 主控推荐：局部 REQUIRES_NEW + RR + readOnly | **用户已明确授权并实施。** Dashboard 始终新建独立已提交快照；外层挂起、返回后恢复。外层未提交写不可见，嵌套调用需要额外连接，并发嵌套存在池容量压力。当前生产路径无外层，因此现有路径不多占外层连接。 |
| 替代：REQUIRED + 局部 fail-closed 校验 | 兼容外层可共用快照，不兼容隔离/只读属性必须拒绝，不能静默降级。须确定检测位置及拒绝是否使外层 rollback-only；不得通过全局 validateExistingTransaction 扩大影响。 |
| 暂停生产修复 | 曾暂停并等待明确选择；收到授权后已恢复执行。 |

当前源码的 HTTP 和 AI 调用路径没有外层事务；这是静态核验结论。未把这一事实当作所有未来/程序化调用的保证。设计阶段 Luna 1 倾向 REQUIRED + 局部 fail-closed，主控倾向独立 committed snapshot，已提交用户决定。用户实际答复：**“授权 Dashboard 局部 REQUIRES_NEW（推荐）”**。最终按此选择实施；不会将兼容或不兼容外层的隔离属性静默带入 Dashboard。

不采用：全局 RR、排他锁串行化、缓存、单次大 SQL 重构、调整 BUY 事务或金融聚合。

## 7. 实际最小代码修改

**生产代码只修改 DashboardService：4 行新增。** 三个 Spring transaction imports，并在公共 `getDashboard` 入口添加：

```java
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
```

没有调整调用方、BUY/SELL、锁序、账本、Flyway、DTO 或计算。Controller 与 Tool 通过注入的 Spring Dashboard 代理进入事务，没有自调用；读取共用事务连接已由实际 MyBatis prepare/JDBC 验证。

新增一个测试类，最终共 7 个用例：原三个并发路径、外层 RC 内的 Dashboard/BUY 交错、外层未提交真实 INCOME 的 commit/rollback 两个参数化用例，以及空 Dashboard 回归。test spy 只协调真实余额查询，不替换其结果；测试专用 MyBatis Interceptor 记录实际 StatementHandler.prepare 的 Connection 和 SQL，使用该 Connection 查询 PG 状态。测试固定 Clock 到 2026-10-11 12:30 Asia/Shanghai，两个 fixture 流水时间相差一分钟，以消除月初和同时间排序偶然性；没有改变生产时间区间或排序。

HTTP JSON 通过 JsonNode 解析可能归一化 BigDecimal scale，因此 HTTP 用例按 BigDecimal 数值递归比较，防止金额相等却因表示 scale 误红；生产序列化和 DTO 未改。首次测试错误地假定 BUY cash_delta 列非空，已按真实生产模型改为核对 net_amount=23 与回执 cashDelta=-23，未修改生产使错误 fixture 通过。

## 8. REPEATABLE READ 运行时证据

**真实 PostgreSQL/JDBC 验证通过，且在三轮新增用例中重复验证。**

八个业务查询实际断言通过：同一 Connection、同一 PG PID、同一 snapshot，Spring active/readOnly=true，autocommit=false，JDBC isolation=4，PG `repeatable read` / readOnly `on`，实际 SQL 为 SELECT 且无 FOR UPDATE。空状态为六次查询，跳过两个名称查询，其余保证相同。直调用例另断言 DashboardService 是 Spring AOP proxy。

首轮 green 的运行时例证：外层未提交写用例 outer PID=63、inner PID=61，内层八条查询都为 RR/readOnly；commit 参数的 snapshot 均为 `845:845:`，rollback 参数均为 `856:856:`。外层 RC 并发 BUY 用例还断言 inner PID != outer PID，返回后原 outer Connection/PID/RC/read-write 恢复。实际隔离保证来自数据库/JDBC，而非仅检查注解。

证据边界：拦截器的 PG snapshot 查询在业务 SQL prepare 前执行；旧 autocommit 模式下证据查询与业务 SQL 是独立事务，不能把记录的 snapshot 称为业务 SQL 自身的精确 snapshot。确定性交错、真实业务结果及提交前后状态共同证明旧读偏斜。修复后业务查询与探针处于同连接的同一个 RR 事务，固定 snapshot 断言实际通过。测试探针会稍早建立首次快照，但仍在 BUY 提交之前；生产首次余额 SELECT 建立快照，符合相同交错边界。

## 9. PostgreSQL 并发修复后验证

**修复后验证通过。** 同一时序下并发响应等于完整 before（totalAssets/netWorth=1020、Asset 分布值=40），新请求读取完整 after（1037、80），不再返回混合值 1060。整份 DashboardDto 的服务路径对比保留收入、支出、六个月趋势、分布、金额 scale 和最近流水/名称行为。

外层事务新增实跑：

- READ COMMITTED/read-write 外层调用 Dashboard 时，内层独立 RR/readOnly；同用户 BUY 仍在读线程暂停期间真实提交，外层连接和属性返回后恢复。
- 真实 TransactionService 在外层创建 INCOME +7，外层余额=987、未提交；独立 Dashboard 仍返回已提交 before 的余额口径与完整 DTO（total=1020），内层 PID 不同。
- Dashboard 返回后外层仍可见自己的 987，保持 RC/read-write 且没有 rollback-only；commit 后独立读取余额987、3条普通流水、Dashboard total1027，rollback 后余额980、2条普通流水、完整 Dashboard 等于 before。
- 空 Dashboard 六个月补零、金额零值、空分布与空交易列表通过。

四条 BUY 交错用例（直调、HTTP、Tool、外层 RC）各实跑三轮，共 12 次。每次 writer Future 在 10 秒超时内完成、主线程在 resume 前查回已提交财务状态且 reader 尚未完成；没有把 BUY 串行化到 Dashboard 结束之后。读取 SQL 全部 SELECT、无 FOR UPDATE，PG transaction_read_only=on；Dashboard 后受影响账户/投影/投资事实/普通流水仍为预期提交状态。未声称完成普遍死锁压力证明。

## 10. HTTP 和 FinancialOverviewTool 回归

实际生产链路：`AiController.ask` → `FinanceAiOrchestrator.ask` → `FinanceToolDispatcher.execute` → `FinancialOverviewTool.getFinancialOverview` → 注入的 DashboardService。上述调用方无事务注解，没有发现其它生产 getDashboard 调用点或自调用绕过。

新测试分别实际执行 MockMvc `/api/v1/dashboard` 与真实 Dispatcher/Tool/Service，不依赖 mock Dashboard 财务输出。两条路径旧实现复现 1060 混合值，修复后均通过一致性验收及 RR 连接证据。HTTP 新并发用例使用 Spring Security 测试认证；既有 DashboardApiIntegrationTest 使用真实注册/login/JWT，核对双用户、月份、金额、资产分布和最近流水，修复前后均实际通过。

既有 FinancialOverviewToolTest 的两例为 mock Dashboard 的映射/参数单元测试，本轮通过，不能替代新 PostgreSQL 并发测试。未调用真实 AI Provider，未运行完整 `/ai/ask` Provider 集成。ToolDispatcher 的 28 例通过仅说明其现有契约回归通过。

新双用户并发测试修复后完整通过：用户 A 响应等于其 before、BUY 后新响应为 A 的 after；用户 B 全部 DashboardDto 等于其 otherBefore，账户/分类/资产使用不同用户 marker。既有 JWT API 双用户断言也继续通过，未新增跨用户聚合或 fixture 共享。

## 11. Maven、重复运行与工程检查

所有 Maven 命令在 `backend` 执行，使用已有 JDK21，当前进程设置 `$env:JAVA_HOME='F:/ideajava/jdk21'`。首次进程没有 JAVA_HOME，Maven 未进入编译/测试；这是环境限制，已纠正，不算测试失败。

| 顺序 / 日志（backend/target） | 实际用例执行 | Failures | Errors | Skipped | 解释 |
| --- | ---: | ---: | ---: | ---: | --- |
| c2-red.log | 1 | 1 | 0 | 0 | 测试对 BUY cash_delta 的模型假设错误，不算 C2 复现 |
| c2-red-mvcc.log | 1 | 1 | 0 | 0 | 首次有效 direct 金额混合红灯 |
| c2-baseline-regression.log | 41 | 0 | 0 | 0 | 五类既有定向回归 |
| c2-red-all-paths.log | 3 | 3 | 0 | 0 | direct / HTTP / Tool 三路径均金额混合 |
| c2-red-final.log | 3 | 3 | 0 | 0 | 最终测试源码三路径重复红灯 |
| c2-green.log | 7 | 0 | 0 | 0 | 授权修复后首轮，含新增外层/空状态 |
| c2-final-regression.log | 48 | 0 | 0 | 0 | 最终 6 类定向回归，含 7 个新增用例 |
| c2-repeat.log | 7 | 0 | 0 | 0 | 新增用例第三轮重复 |
| 全部执行合计 | **111** | **8** | **0** | **0** | 103 PASS；7 次有效 C2 旧行为红灯；1 次测试假设失败 |

按最后一次结果去重：**48 个唯一用例全部 PASS**（41 个既有 + 7 个新增），0 failures/errors/skipped。旧实现 direct 有效红灯重复 3 次（另有一次 fixture 假设失败），HTTP 和 Tool 各重复 2 次。修复后新增 7 个用例各运行 **3 轮**，共 21 次 PASS；包含最终回归中的第二轮，不能把 21 算成 21 个唯一新用例。四条 BUY 并发交错每条 3 轮，共 12 次成功。修复后所有实际执行合计 7+48+7=**62**，均通过。

五类回归的实际分项：DashboardApiIntegrationTest 1、InvestmentCommandApiIntegrationTest 9、InvestmentFirstBuyUnknownCommitIntegrationTest 1、FinancialOverviewToolTest 2、FinanceToolDispatcherTest 28，全部 0 failures/errors/skipped。投资类覆盖首次/追加 BUY、SELL、同 key 并发与 unknown committed receipt 等既有行为；不等于 Finance 全量验收。

执行命令：

```powershell
.\mvnw.cmd '-Dtest=DashboardSnapshotConsistencyPostgresIntegrationTest#dashboardKeepsOneCommittedSnapshotWhileSameUsersBuyCommits' test
.\mvnw.cmd '-Dtest=DashboardApiIntegrationTest,InvestmentCommandApiIntegrationTest,InvestmentFirstBuyUnknownCommitIntegrationTest,FinancialOverviewToolTest,FinanceToolDispatcherTest' test
.\mvnw.cmd '-Dtest=DashboardSnapshotConsistencyPostgresIntegrationTest' test
.\mvnw.cmd '-Dtest=DashboardSnapshotConsistencyPostgresIntegrationTest,DashboardApiIntegrationTest,InvestmentCommandApiIntegrationTest,InvestmentFirstBuyUnknownCommitIntegrationTest,FinancialOverviewToolTest,FinanceToolDispatcherTest' test
.\mvnw.cmd compile
```

首轮 green 的 compile/testCompile 实际编译了生产修改及新增测试。最后 Maven compile：BUILD SUCCESS（2026-10-11 14:13:03 +08:00），输出 Nothing to compile（测试阶段已编译更新源码）。final-regression 完成于 14:11:15，repeat 完成于 14:12:18。日志为 backend/target/c2-final-compile.log 等。未执行 clean，未清理用户资料。

`git diff --check` 实际退出码 0；新增未追踪文件另用 `git -c core.autocrlf=false diff --no-index --check -- NUL <file>` 核验，两次退出码 1（no-index 检测到新增文件差异），无 whitespace error 输出；另外扫描两份文件的行尾空白和冲突标记，均无匹配。最终状态检查见第 16 节。日志保存在被 Git 忽略的 backend/target，未作为提交内容。

## 12. 两个 GPT-6 Luna Max 独立复审

按用户要求实际启动两个 `gpt-6-luna`，reasoning_effort=`max`。均只读，未独立运行 Maven/测试，未改生产、测试或文档，未访问受保护内容。

**Luna Max 1：MVCC / 事务边界。** 核实八次读取、原无事务、HTTP/AI 无生产外层事务、Spring 外部注入路径和 MyBatis 单 DataSource。设计阶段审查 REQUIRED/REQUIRES_NEW 取舍后，用户授权恢复。最终复核生产实际 diff 仅 4 行新增，读取 c2-green.log，核对独立内层 RR/readOnly、固定快照、outer PID/连接恢复及 commit/rollback；明确未独立实跑。结论：对授权语义可 GO，未发现 P0/P1 阻塞，保留未做高并发连接池压力测试的限制。

**Luna Max 2：并发测试真实性。** 核对 Testcontainers、真实写 facade/transactional service、独立主线程提交后查回、spy callRealMethod、latch/finally/超时。读取实际红灯日志，确认 1020→1060 是业务数值偏斜，并指出诊断 snapshot 的旧 autocommit 证据边界（第 8 节已保留）。授权后复核新增外层/空状态测试、green/final-regression/repeat 日志及红转绿断言；最终结论未发现阻断项，明确未独立实跑。

## 13. 修改文件完整清单

仅三份任务文件：

1. 修改 `backend/src/main/java/com/financeos/module/dashboard/service/DashboardService.java`（4 行新增）。
2. 新增 `backend/src/test/java/com/financeos/integration/DashboardSnapshotConsistencyPostgresIntegrationTest.java`。
3. 新增 `docs/archive/review/G2-1-C2-Dashboard-Snapshot-Consistency-Review.md`。

tracked diff 仅 DashboardService，staged diff 为空。backend/target 下测试/编译输出为忽略的构建证据，不属于源码改动。AGENTS.md 与 docs/codex-task.md 属于原有受保护 untracked，不混入任务成果。

## 14. P0 / P1 / P2 Findings

| 级别 | Finding | 状态 |
| --- | --- | --- |
| P0 | 本轮范围内未发现新增项 | 不代表全项目审计 |
| P1 | 本轮范围内未发现新增项 | 不代表全项目审计 |
| P2 | 原 C2：同一 Dashboard 组合 BUY 前余额与 BUY 后 Asset | 真实红灯转绿，局部修复并重复验证 |
| 原设计阻断 | 默认 REQUIRED 不能保证已有低隔离外层不降级 | 用户已授权 REQUIRES_NEW，真实外层测试通过；已解除 |
| Known Limitation | 嵌套 REQUIRES_NEW 需要额外连接 | 已授权取舍；当前生产无外层路径，未做高并发连接池压力测试，不阻断本轮 C2 |

不新增原始问题严重级别，不进入 C3。

## 15. 未验证范围

全量 Finance（保留 G5）、前端 build/lint/E2E、真实 Provider、完整 AI HTTP 调用、高并发连接池压力、长期运行下的 MVCC 清理成本未验证。未单独对 serializable/其它事务管理器或其它数据源进行实验；本项目实际只有当前这一 DataSource/事务管理器。未把确定性并发样本扩展为普遍无死锁或性能 SLA 结论。

金额/月份/分布/空状态/最近交易生产规则均未改；既有 Dashboard API 和新空状态回归实际通过，仍不宣称覆盖了所有金融数值或日期边界。FinancialOverviewTool 验证到真实内部调用链，未虚报 Provider 集成。

## 16. 受保护文件状态与 Git 边界

两个受保护文件始终作为原有未追踪路径保留。全程未读取内容、未修改、未删除、未暂存、未提交、未计算内容 hash；仅 Git 状态及长度/时间元数据检查。记录的元数据：AGENTS.md 长度 5628，LastWriteTimeUtc=2026-07-12 14:30:08；docs/codex-task.md 长度 0，LastWriteTimeUtc=2026-09-23 10:39:11。该元数据在 preflight 之后才采集，不伪称拥有任务开始时的内容快照或 hash 对照。

授权恢复后再次核验受保护文件元数据与上次记录相同；全程没有访问其内容或 hash。未 commit/push/tag；未创建分支/worktree；未 reset/stash/clean/checkout 覆盖/rebase/amend；未修改 Personal AI Workspace 或 Local AI Assistant。HEAD 保持预期值，暂存区为空。最终工作区仅两个受保护文件、第 13 节两个新增任务文件，以及一个 DashboardService tracked 修改。

## 17. GO / NO-GO 与下一步

**C2 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。** 真实旧行为复现、局部修复、运行时 RR/同连接快照、写并发、双用户隔离、HTTP/Tool、外层事务语义、既有回归和重复验证均已完成，未发现本轮范围内 P0/P1 或阻断 P2。

等待用户人工验收。当前暂停，保留未提交的三份任务成果；不自行 commit、push、创建 Tag、进入 C3 或 Finance Freeze。
