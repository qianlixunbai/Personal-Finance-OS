# G1-3 / C4 — SQLState Boundary 最小安全补修验收报告

日期：2026-10-11（Asia/Shanghai）

基线：`zh-cn` / `6d828ed2e7d3f3f465de45fd5559f94a2589f4bb`

结论：**C4 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**。

本轮完成用户已授权的最小边界补修，61 项定向测试实际通过。等待再次人工批准正式提交；未标记 CLOSED — GO，未 commit / push / 创建 Tag / 进入 C6。

## 1. Preflight

| 检查 | 本轮结果 |
| --- | --- |
| Branch / HEAD | 与上述预期完全一致；结束时保持不变 |
| 开始时 tracked 修改 | `GlobalExceptionHandler.java`、`TransactionImportPreviewCleanupService.java`，均属于上一轮 C4 成果 |
| 开始时未追踪 C4 文件 | 安全日志 helper、3 个 C4 测试、2 份历史 Review，符合授权基线 |
| 受保护未追踪文件 | `AGENTS.md`、`docs/codex-task.md`；只核对 Git 状态，不读取内容 |
| staged diff | 开始时与结束时均为空 |
| 无关工作区变动 | 未发现 |
| 运行环境 | 现有 JDK `F:\ideajava\jdk21`，Java 21.0.10；Docker Server 29.6.1 |

采用用户在会话中提供的 AGENTS 规则，未打开受保护文件。检查了 README、Testing、Security、Current Architecture、Review 索引、两份历史 C4 Review、异常处理/日志 helper、真实 Controller、测试基类、原 C4 定向测试及 Maven 配置；backend/docs 下未发现额外 AGENTS.md。

主控实际模型无法通过当前工具独立核验，不虚报已确认 GPT-6.1 Sol High。两个独立子 Agent 均以 `model=gpt-6-luna`、`reasoning_effort=max` 创建，接口接受了该配置；不额外推断服务端实际路由。职责分别为只读安全审查、只读工程/测试复核，均不修改文件或执行额外测试。

## 2. 修复前真实漏洞证据

历史证据保持原样：

- [原 C4 敏感日志修复 Review](G1-3-C4-Sensitive-Logging-Remediation-Review.md)；
- [SQLState Boundary Supplemental Review](G1-3-C4-SQLState-Boundary-Supplemental-Review.md)，其 **C4 — NO-GO** 结论不覆盖、不改写。

补充验收已实际证明：已认证真实 socket HTTP 请求进入 Controller，Service spy 注入无 SQLState 的 DataAccessException；真实 handler 执行 `switch(null)` 抛 NPE，Spring resolver 无法完成解析，Tomcat 输出原始消息/root cause，客户端实际收到脱敏 401。两种形态均出现 canary 泄露。

本轮重新读取并核验保留的原始日志：

| 历史证据文件 | 可核验内容 |
| --- | --- |
| `backend/target/c4-sqlstate-boundary-runtime.log:115`、`:397` | 两次 `Failure in @ExceptionHandler` |
| 同日志 `:255`、`:537` | Tomcat ERROR，包含原数据库包装异常的合成 canary |
| 同日志 `:396`、`:663` | 两种形态 HTTP 401、handler NPE；framework formatted / Throwable / rendered / stdout 泄露标志为 true |
| 同日志 `:836`、`:839` | **2 tests / 2 failures / 0 errors / 0 skipped**、BUILD FAILURE |
| `backend/target/c4-sqlstate-boundary-WITHOUT_SQL_CAUSE.txt`、`c4-sqlstate-boundary-WRAPPED_NULL_SQLSTATE.txt` | 历史 HTTP 和逐事件原始诊断，保留未覆盖 |

这些是上一轮实跑的红灯历史证据，本轮没有重新运行旧实现或将历史结果冒充本轮执行。原边界 Surefire XML 在重跑前另存为 `backend/target/c4-boundary-remediation-baseline-surefire.xml`。

## 3. 实际最小源码修改

仅修改 `GlobalExceptionHandler.handleDataAccessException` 的 switch 入口（当前源码第 50–51 行）：

```diff
-        switch (findSqlState(e)) {
+        String sqlState = findSqlState(e);
+        switch (sqlState == null ? "" : sqlState) {
```

null 归一为 `""` 后进入现有 default，记录原有安全事件并正常返回原有 503。其余 case/default、并发判断、cause 遍历、日志 helper 均未改变。未调整 Spring Security、Tomcat、异常解析器、事务、金融计算、Schema/Flyway 或前端。

本轮前的 handler 与测试源码副本保存于 ignored target，可按本轮基线核对差异，避免将上一轮 C4 未提交改动混算为本轮修改。

## 4. 两项真实 HTTP 测试由红转绿

复用现有 `SqlStateBoundaryRuntimeIntegrationTest`，仍采用 `RANDOM_PORT`、`TestRestTemplate`、真实 JWT 认证、真实 Controller、Service spy 故障注入及真实全局 handler。

| 场景 | 历史修复前实跑 | 本轮修复后实跑 |
| --- | --- | --- |
| WITHOUT_SQL_CAUSE | NPE、HTTP 401、框架/容器泄露、FAIL | handler 正常完成、HTTP 503、全层无泄露、PASS |
| WRAPPED_NULL_SQLSTATE | NPE、HTTP 401、框架/容器泄露、FAIL | handler 正常完成、HTTP 503、全层无泄露、PASS |

原始异常注入未删除或绕开：第一例仍是消息含 canary 的无 SQLException cause 异常；第二例仍为 DataAccessException → MyBatis PersistenceException → SQLState=null 的 SQLException，原异常保留 canary 和合成金额 `9876.54`，测试新增正向断言确认它们仍存在于原异常中。

Service 的真实 Spring bean 被 spy，测试验证 `getById(USER_ID, TRANSACTION_ID)` 实际被调用，但该方法体被 `doThrow` 截断以注入受控异常；不能把边界探针描述为执行了 Service 数据读取实现。Controller 和真实 handler 正常执行；handler spy 仅 `callRealMethod()` 并记录返回值/异常，不替换返回合同，通用 handler 未被调用。

修复前观察 NPE 的两处诊断断言已改为正向要求：handler 不抛异常、返回原有 503 body、Spring resolver 无失败事件、Tomcat 无 ERROR 回退。原六项敏感信息检查全部保留，另增加安全事件白名单及全事件各层检查。没有删除、跳过、禁用测试或弱化关键断言。

本轮原始证据：

- `backend/target/c4-boundary-remediation-verification.log:367`：真实 Tomcat 随机端口启动；
- 同日志 `:373`、`:375`：两种 shape 均 `httpStatus=503 handlerThrown=none`，全部 leak 标志 false；
- 同日志 `:378`：边界测试 **2 / 0 / 0 / 0**；
- `backend/target/c4-sqlstate-boundary-remediation-WITHOUT_SQL_CAUSE.txt`；
- `backend/target/c4-sqlstate-boundary-remediation-WRAPPED_NULL_SQLSTATE.txt`；
- `backend/target/surefire-reports/TEST-com.financeos.integration.SqlStateBoundaryRuntimeIntegrationTest.xml`：本轮两项参数化结果，无 failure/error/skipped。

## 5. 全部日志安全断言与实测结果

捕获 root Logback 的线程安全事件队列，涵盖应用、Spring、Tomcat；同时检查实际 stdout/stderr。

| 检查 | WITHOUT_SQL_CAUSE | WRAPPED_NULL_SQLSTATE |
| --- | --- | --- |
| 应用安全事件数量 | 1 | 1 |
| 框架事件数量 | 3，仅 DispatcherServlet 初始化 INFO | 0 |
| handler 抛异常 | 无 | 无 |
| Spring resolver 失败事件 | 无 | 无 |
| Tomcat 原异常 ERROR 回退 | 无 | 无 |
| 应用最终输出含 canary | 否 | 否 |
| 框架 formatted message 含 canary | 否 | 否 |
| 框架 argument array 含 canary | 否 | 否 |
| 框架 ThrowableProxy/cause 含 canary | 否 | 否 |
| 框架 `%msg%n%ex` 最终渲染含 canary | 否 | 否 |
| 实际 stdout/stderr 含 canary | 否 | 否 |

两个应用事件均为 ERROR，固定模板 `event=DB_FAILURE category={} path={} eventId={}`；恰有 3 个 String 参数：`DATABASE_ERROR`、`DATA_ACCESS`、服务端 UUID；`ThrowableProxy=null`。不得把这个受控安全 ERROR 与泄露原异常的 Tomcat ERROR 混同。

除上述保留的六项泄露断言，测试还逐一检查所有事件的 formatted message、argument array、ThrowableProxy 文本和最终渲染不含 canary、合成金额或 failing row；检查 stdout/stderr 没有 NPE 或 handler 失败文本。本轮完整 Maven 日志对三个 C4 合成 canary 和 `Failure in @ExceptionHandler` 的检索为 0 次匹配。

应用日志没有输出原 DataAccessException、SQLException、Throwable、SQL 参数或 failing row。历史红灯日志中保留的合成泄露证据不代表本轮修复后的运行泄露。

## 6. HTTP 503 合同与其他映射

两用例同时断言真实 handler 返回对象与最终 socket HTTP 响应：

```http
HTTP 503
Content-Type: application/json
```

```json
{"code":503,"message":"数据库服务暂时不可用"}
```

完整 JSON 树严格相等，无额外 data/errorCode/retryable 字段；响应不含 canary、金额、SQLException 或 DataIntegrityViolationException。安全事件为 `DB_FAILURE / DATABASE_ERROR / DATA_ACCESS`。

此次变化将已证实的异常回退 401 改为已授权的受控 503，未修改认证机制。原回归继续覆盖 `23505→409`、`23514→500`、`22003→400`、`55P03/40P01→409`、`08006→503` 的既有合同，22 项 AI handler 测试也实际通过。

## 7. 原 59 项受影响回归

本轮一次完整定向命令中的实际结果：

| 测试集合 | Tests | Failures / Errors / Skipped |
| --- | --- | --- |
| SensitiveSqlLoggingTest | 25 | 0 / 0 / 0 |
| SensitiveSqlLoggingIntegrationTest | 3 | 0 / 0 / 0 |
| GlobalExceptionHandlerTest | 8 | 0 / 0 / 0 |
| GlobalAiExceptionHandlerTest | 22 | 0 / 0 / 0 |
| InvestmentReplacementFailureIntegrationTest#unknownCommandUniqueConstraintIsNotMistakenForIdempotentRecovery | 1 | 0 / 0 / 0 |
| 原 C4 集合小计 | **59** | **0 / 0 / 0** |
| SqlStateBoundaryRuntimeIntegrationTest | 2 | 0 / 0 / 0 |
| 本轮合计 | **61** | **0 / 0 / 0** |

本轮日志对应各类结果行：`:41`、`:122`、`:226`、`:306`、`:378`、`:439`；`:443` 为 61 项汇总，`:446` 为 BUILD SUCCESS。6 份 Surefire XML 的更新时间均属于本轮执行，replacement XML 只包含已验收目标方法。未把历史 59 项绿灯或 Agent 只读复审算作本轮测试。

3 项 PostgreSQL 测试继续验证真实 unique/CHECK/trigger 错误和事务回滚；replacement 定向用例继续验证固定脱敏 500、完整财务快照不变及无等待锁。本轮未改任何金融/数据库行为。

边界测试继承 `PostgresIntegrationTest`，datasource 经 DynamicPropertySource 指向临时 PostgreSQL 容器；实际断言 `current_database()=test`、流水数量为 0，未连接日常数据库。它们是实际鉴权 HTTP + 合成异常注入，不是证明 PostgreSQL 服务器自然产生 null SQLState 的测试。

## 8. Maven compile 与 diff 验证

Maven 命令在 `backend` 执行，仅设置当前进程既有 JDK：

```powershell
$env:JAVA_HOME = 'F:\ideajava\jdk21'
.\mvnw.cmd -B '-Dtest=SensitiveSqlLoggingTest,SensitiveSqlLoggingIntegrationTest,GlobalExceptionHandlerTest,GlobalAiExceptionHandlerTest,InvestmentReplacementFailureIntegrationTest#unknownCommandUniqueConstraintIsNotMistakenForIdempotentRecovery,SqlStateBoundaryRuntimeIntegrationTest' test
.\mvnw.cmd -B -DskipTests compile
```

- 定向 test：退出码 0、BUILD SUCCESS，实跑 61 项。该命令日志 `:14`、`:31` 显示实际编译 272 个生产 source、68 个测试 source，这些是编译源文件数量，不是测试数量。
- 单独 compile：退出码 0、BUILD SUCCESS，日志 `backend/target/c4-boundary-remediation-compile.log:13-15`；此轮增量结果为 up to date，不计为额外测试。
- `git diff --check`：退出码 0。只有既有 cleanup 文件的 LF/CRLF 策略提示，无 whitespace error。
- 对全部 C4 未追踪源码、测试和 Review 单独检查行尾空白及 EOF 空白行：通过，包含本报告；未读取两个受保护文件。
- `git diff --cached --stat`：为空；最终 `git status --short` 无无关变动，未暂存文件。

没有运行 Finance full suite、Frontend 或真实付费 Provider/API 测试。

## 9. 两个 Luna Max 独立复审及主控复核

**安全 Agent**（`c4_security_review`）：只读检查本轮基线差异、实际日志、两份 shape 证据与日志出口；确认 null 归一关闭当前绕过，Spring/Tomcat 回退消失，参数白名单且无 ThrowableProxy，未发现新增旁路或 P0/P1；建议 C4 边界补修 GO，保留既有 cause 循环 P2 后续项。

**工程 Agent**（`c4_engineering_review`）：只读核验 6 份本轮 XML 与完整日志，确认 8+22+25+3+2+1=61，全部通过且未跳过；确认目标 replacement 方法、503 完整合同、隔离 DB 断言、原回滚覆盖及仅两行入口修改，未发现本轮 P0/P1/P2。指出 Service 方法体被故障注入截断，以及单独 compile 仅增量确认；本报告明确保留这两项证据边界。

主控独立核对上述源码、基线差异、HTTP/事件诊断、6 份 XML、Maven 日志和工作区状态，确认 Agent 结论。两个 Agent 均没有执行测试；61 项 test 和 compile 全部由主控实际运行。

## 10. 实际修改文件完整清单与历史保留

相对于本轮开始状态，实际修改/新增共 **3 个文件**：

| 文件 | 本轮变更 |
| --- | --- |
| `backend/src/main/java/com/financeos/common/GlobalExceptionHandler.java` | SQLState switch 入口 null 归一，其他现有代码保留 |
| `backend/src/test/java/com/financeos/integration/SqlStateBoundaryRuntimeIntegrationTest.java` | 两项既有边界测试诊断改为安全正向验收并加强合同/日志检查；证据改用新文件名 |
| `docs/archive/review/G1-3-C4-SQLState-Boundary-Remediation-Review.md` | 新增本报告 |

已有且本轮内容保持一致的 **6 个 C4 文件**：

1. `backend/src/main/java/com/financeos/common/DatabaseFailureLog.java`；
2. `backend/src/main/java/com/financeos/module/importing/service/TransactionImportPreviewCleanupService.java`；
3. `backend/src/test/java/com/financeos/common/SensitiveSqlLoggingTest.java`；
4. `backend/src/test/java/com/financeos/integration/SensitiveSqlLoggingIntegrationTest.java`；
5. `docs/archive/review/G1-3-C4-Sensitive-Logging-Remediation-Review.md`；
6. `docs/archive/review/G1-3-C4-SQLState-Boundary-Supplemental-Review.md`。

因此最终工作区累计 C4 文件为 9 个，不能将累计范围说成本轮全部新改。开始时登记上述既有 8 个源码/测试/Review 与 10 个既有 target 证据的 SHA-256；结束时 18 项中仅预期的 handler、边界测试两项变化，其余 **16 项一致**，包含两份历史 Review 和原泄露日志/诊断。

保存摘要：`backend/target/c4-boundary-remediation-preservation.csv` 及 `c4-boundary-remediation-preservation-result.csv`。新建 target 的基线副本、测试/编译日志及诊断均为 ignored 本地构建证据，不进入提交；后续 clean 会清除 target，因此本报告独立保存命令、结果与证据说明。

## 11. P0 / P1 / P2 Findings

| 级别 | 结论 |
| --- | --- |
| P0 | 本轮范围内未发现 |
| P1 | 未发现未解决的本轮阻断问题；已证实的 null SQLState → NPE → Tomcat 敏感日志回退已修复并通过两个实际 HTTP 用例验收，本报告不重新定义历史漏洞等级 |
| P2 | 本轮新增引入的问题未发现；既有 cause-only 遍历未防循环仍为独立非阻塞后续项 |

仅对用户批准的最小补修作出 GO 候选判断，不将未验收范围自动关闭。

## 12. 未解决风险与未验证范围

- **cause 循环问题未解决**：`hasConcurrencySqlState` / `findSqlState` 没有 visited，循环 cause 图可能卡住请求；日志 helper 自身防环不代表 HTTP 映射遍历已防环。按授权不扩修，保留独立待办。
- HTTP 映射仍按 cause-only 遍历，日志分类 helper 可识别 suppressed/nextException；原有复杂异常图分类差异未改动。
- 本轮证明两类无 SQLState 的受控故障不会进入原敏感容器回退，不声称证明所有数据库故障来源或第三方框架/数据库服务器日志均安全。
- 未运行 Finance 全量、生产环境或前端验收；未重新审计其他 C 项。没有修改或关闭 C6。

## 13. 受保护文件与操作边界

`AGENTS.md`、`docs/codex-task.md` 在开始与结束均为受保护 `??`。主控和两个 Agent 均未读取、修改、删除、暂存或提交它们；未对其内容做 hash，不能声称内容比对一致。

保留上一轮 C4 实现、Review 和真实 NO-GO 记录。未 reset/stash/clean/覆盖 checkout/rebase/force push/amend，未创建 branch/worktree/tag，未 commit/push，未操作 Personal AI Workspace 或 Local AI Assistant。

## 14. 最终 GO / NO-GO

**C4 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**。

证据：两个实际 HTTP 红灯用例本轮转绿；原 59 项当轮回归继续通过；61 项合计 0 failures / 0 errors / 0 skipped；503 合同与日志所有层级安全断言通过；Maven compile、diff 和未追踪文件空白检查通过；两位 Luna Max 独立只读复审通过。

完成后暂停，等待用户再次人工批准正式提交。未正式标记 C4 CLOSED — GO，不自动提交、推送或进入 C6；cause 循环问题继续独立待办。
