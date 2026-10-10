# G1-3 / C4 — 数据库异常敏感日志修复验收报告

日期：2026-10-11（Asia/Shanghai）

基线：`zh-cn` / `6d828ed2e7d3f3f465de45fd5559f94a2589f4bb`

结论：**C4 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**

本轮完成后暂停，等待人工 GO；尚未标记 CLOSED — GO，未 commit / push。

## 1. Preflight 与执行边界

| 检查 | 本轮结果 |
| --- | --- |
| Branch | `zh-cn` |
| HEAD | `6d828ed2e7d3f3f465de45fd5559f94a2589f4bb` |
| `origin/zh-cn` | 本地 remote-tracking ref 与 HEAD 相同；未 fetch / pull，不代表独立核验服务器最新状态 |
| tracked diff / staged diff | 开始时均为空 |
| untracked | 开始时只有 `AGENTS.md`、`docs/codex-task.md`，符合允许基线 |
| 工作区操作 | 未 reset、stash、clean、restore、checkout 覆盖、merge、rebase、amend；未创建 branch / tag / worktree |
| 受保护文件 | 未读取内容、修改、删除、暂存或提交；最终仍为 `??` |

仓库规则采用用户在会话中提供的内容；读取了根 README、文档导航、Review 索引、Testing、Security、Current Architecture 及相关实际代码，未打开受保护文件。

主控的实际运行模型无法通过本会话工具独立核验，不能声称已确认是 GPT-6.1 Sol High。两个子 Agent 的创建参数均为 `gpt-6-luna` / `max`，接口接受了该配置；报告不额外推断服务端实际路由。两个 Agent 均独立只读，代码由主控实施。

## 2. 修复前真实泄露复现

新增 `SensitiveSqlLoggingIntegrationTest`，在**修改生产代码之前**运行，继承现有 `PostgresIntegrationTest`：

- Testcontainers 使用 `postgres:17-alpine`，实跑日志确认 PostgreSQL 17.11；
- `@DynamicPropertySource` 将 datasource 覆盖为临时容器的 JDBC URL、用户及密码；
- 使用测试 profile 的 JWT 与虚构用户、账户、流水，经认证后的 MockMvc 请求走真实 Controller → Service → MyBatis → PostgreSQL；
- 只在容器测试数据库建立临时 unique index、CHECK 与 trigger，均在 `finally` 删除；未修改生产 Schema / Flyway；
- 所有敏感标记均为合成 canary，未连接或读取真实财务数据库。

稳定基线结果：**3 tests / 3 failures / 0 errors / 0 skipped**。三条测试均先通过 HTTP、driver 异常内容及数据库状态断言，再在 `ThrowableProxy` 非空断言处失败。

| 场景 | SQLState / HTTP | 原始 driver 内容 | 回滚基线 |
| --- | --- | --- | --- |
| 流水 description 唯一冲突 | `23505` / 409 | `Key (description)=(C4_SYNTHETIC_PRIVATE_FINANCE_CANARY) already exists.` | 仅首次成功流水保留，余额 `112.34`；失败请求不追加事实或余额 effect |
| 先插入流水，再更新账户余额触发 CHECK | `23514` / 500 | `Failing row contains`，含账户名称 canary、余额 `9976.54`、其他行字段 | 0 条流水，余额仍为 `100.00` |
| 账户更新 trigger 失败 | `23514` / 500 | 异常消息含 canary，DETAIL 含 `amount=9976.54` | 0 条流水，余额仍为 `100.00` |

本地证据（未暂存，位于 ignored 的 `backend/target`）：

- `backend/target/c4-baseline.log:107`：`GlobalExceptionHandler` 的 `Check constraint violated`；
- `:345`：`Caused by: org.postgresql.util.PSQLException`，含 trigger canary；
- `:419`、`:651`：failing row 含账户 canary 和 `9976.54`；
- `:719`：`Unique constraint violated`；`:723`、`:942`：重复键值 canary；
- `:1104`：稳定基线汇总 `Tests run: 3, Failures: 3, Errors: 0, Skipped: 0`。

对稳定基线日志全文的 canary 检索为 **21 次匹配**，这是重复堆栈/包装输出中的出现次数，不是 21 个独立泄露事件。此日志只含虚构测试数据。

原 HTTP 响应已经使用固定脱敏消息，测试证明其不含 canary、SQL、driver DETAIL 或测试金额。本问题发生在服务端日志。

## 3. 根因与相邻可达出口

基线 `GlobalExceptionHandler` 的 `23505`、`23514`、`22003`、默认分支均执行 `log.warn/error("固定消息", exception)`。

固定 formatted message 本身没有 canary；但 SLF4J 将最后的异常参数解释为 Throwable，Logback 生成 `ThrowableProxy`。Spring/MyBatis 包装异常消息中包含 SQL，PostgreSQL cause 中包含重复键或失败行详情，最终堆栈输出仍泄露这些内容。不能仅检查主消息或 argument array。

真实源码还有以下直接相关出口：

- `InvestmentCommandService` 将数据库失败保留为 reversal / replacement 一致性异常的 cause；专用 handler 原来继续输出完整 Throwable；
- 通用 `handleException` 原来在非 AI 路径输出整个异常，可能记录数据库包装的 cause / suppressed；
- `TransactionImportPreviewCleanupService` 的过期清理 catch 覆盖 mapper 写入，原来将 RuntimeException 作为日志最后参数；
- 未发现这些请求业务服务在交给全局 handler 之前重复记录同一 SQL 异常；MyBatis 当前配置为 `NoLoggingImpl`。

## 4. 最小安全设计与修改

新增小型 `DatabaseFailureLog`，复用现有 SLF4J / Logback，不引入依赖或日志平台。固定输出：

```text
event=DB_FAILURE category=CHECK_CONSTRAINT path=DATA_ACCESS eventId=<server-generated UUID>
```

允许字段只有：

- 固定事件 `DB_FAILURE`；
- enum 白名单分类：`UNIQUE_CONFLICT`、`CHECK_CONSTRAINT`、`NUMERIC_OUT_OF_RANGE`、`LOCK_CONFLICT`、`DATABASE_ERROR`；
- enum 白名单处理路径；
- 服务端随机生成的 UUID，未使用客户端标识、用户 ID、账户 ID 或 URL。

SQLState 只参与内部白名单分类，未原样输出；未知、null 或含伪造换行的 SQLState 使用安全的固定默认分类。未输出异常名称、异常消息、SQL、参数、constraint 名称、failing row、stack trace、cause 或 `toString()`。logger 的三个参数全部为安全 String，不传 Throwable。

识别覆盖 `DataAccessException`、`SQLException`、MyBatis `PersistenceException`、Spring `TransactionException`，遍历 cause、suppressed、`SQLException.nextException`，使用 identity set 防止辅助日志识别在循环图中卡住。

DataAccess 分支直接记录安全事件；Business、四类一致性、通用异常与 Import 过期清理出口在识别到数据库异常图时记录安全事件。普通非数据库异常保持原来的处理与诊断。并发 SQLState 路径增加同样的安全 WARN，不改原 409 返回。

未修改 SQLState → HTTP 映射、Error Code、API DTO、数据库约束、事务、业务规则、C1/C5/C7 实现或前端。Import 仅修改已定位的日志出口，未改其清理或 Confirm 语义。

## 5. 实际修改文件清单

| 文件 | 变更 |
| --- | --- |
| `backend/src/main/java/com/financeos/common/DatabaseFailureLog.java` | 新增白名单安全日志辅助类 |
| `backend/src/main/java/com/financeos/common/GlobalExceptionHandler.java` | 替换数据库日志并保护包装出口 |
| `backend/src/main/java/com/financeos/module/importing/service/TransactionImportPreviewCleanupService.java` | 保护过期清理 mapper 失败日志 |
| `backend/src/test/java/com/financeos/common/SensitiveSqlLoggingTest.java` | 新增 25 条定向日志/合同单测 |
| `backend/src/test/java/com/financeos/integration/SensitiveSqlLoggingIntegrationTest.java` | 新增 3 条真实 PostgreSQL 集成测试 |
| `docs/archive/review/G1-3-C4-Sensitive-Logging-Remediation-Review.md` | 本报告 |

两个新增测试文件属于本轮工作，不能描述为 HEAD 中的历史测试。既有测试未删除、禁用或弱化。

## 6. 修复后日志安全验证

新增测试同时检查：

1. Logback event 的 raw message 模板与 formatted message；
2. 原始 logger arguments（单测要求只有三个 String，并分别核验白名单值和 UUID）；
3. `ThrowableProxy == null`；
4. `PatternLayout` 的 `%ex` 最终渲染，涵盖完整 Throwable / nested cause 输出；
5. `OutputCaptureExtension` 捕获的实际测试 stdout/stderr；
6. root logger 下全部 `com.financeos.*` 应用事件，包含相邻日志出口。

`backend/target/c4-verification.log` 全文检索：集成 canary **0 次**，单测 canary **0 次**。测试还排除测试金额、`INSERT INTO`、`Failing row contains` 等数据库内容。

实跑集成日志只保留安全事件：

```text
event=DB_FAILURE category=CHECK_CONSTRAINT path=DATA_ACCESS eventId=e849e446-7e9e-4eb2-aea0-fad88d9c8963
event=DB_FAILURE category=CHECK_CONSTRAINT path=DATA_ACCESS eventId=4e6159fb-85ac-4e3a-9789-b51b69615ce9
event=DB_FAILURE category=UNIQUE_CONFLICT path=DATA_ACCESS eventId=3bcaec1d-e15b-47fd-904d-e6e1316b0e2e
```

driver 原异常仍含 canary（仅在测试内断言），证明修复没有消除数据库错误或改变业务结果，而是在日志边界停止转发不可信内容。

## 7. PostgreSQL / Spring / 包装路径验证

| 路径 | 证据级别 |
| --- | --- |
| PostgreSQL unique / CHECK failing row / trigger DETAIL → MyBatis → Spring DataIntegrityViolationException → 全局 handler | 3 条新增集成测试实际通过 |
| 六种 DataAccess SQLState 与默认数据库分类 | 定向单测实际通过 |
| 原始 SQLException、嵌套包装、Spring 无 SQL cause、MyBatis、Transaction、suppressed、nextException、循环图、未知及 null SQLState | 通用 handler 的 10 条参数化单测实际通过 |
| Business 与四类一致性包装 | 5 条参数化单测实际通过；migration/write 的 SQL cause 是测试构造防御覆盖，不宣称真实生产触发 |
| 过期 Import cleanup mapper 失败 | Mockito 定向单测实际通过；未额外创建真实 Import 清理数据库错误场景 |
| 真实 investment replacement 未知 unique → InvestmentReplacementConsistencyException | 既有 `unknownCommandUniqueConstraintIsNotMistakenForIdempotentRecovery` 定向实跑通过 |

最后一条真实 PostgreSQL 日志为 `category=UNIQUE_CONFLICT path=INVESTMENT_REPLACEMENT_CONSISTENCY`。该既有测试本身校验 500、脱敏响应及完整财务快照不变；针对其日志参数/Throwable 的完整安全断言由新增专用 wrapper 单测提供，不夸称既有测试已有这些断言。

## 8. HTTP Status / Error Code 回归

| SQLState | HTTP / `code` | 原消息，保持不变 |
| --- | --- | --- |
| `23505` | 409 | 资源已存在或与现有数据冲突 |
| `23514` | 500 | 数据一致性校验失败 |
| `22003` | 400 | 数值超出允许范围 |
| `55P03`、`40P01` | 409 | 并发操作冲突，请重试 |
| `08006`（默认分支） | 503 | 数据库服务暂时不可用 |

单测对完整 `ApiResponse.error` record 断言；三条新增 MockMvc 集成测试严格匹配原 JSON。普通非数据库未知异常仍为 500 / 服务器内部错误，普通 `IMPORT_LOCK_CONFLICT` 的 errorCode / retryable 合同未变。既有 GlobalAiExceptionHandlerTest 的 22 条测试也通过。

## 9. 事务及数据库一致性

unique 冲突测试确认仅第一次成功请求产生一条流水和一次余额 effect。CHECK 与 trigger 失败发生在流水插入后的余额 UPDATE，均验证 0 条流水及账户余额 `100.00`，没有部分事实或余额变化。

额外的既有 replacement 失败测试核验数据库财务快照完全不变和无遗留等待锁。测试容器是隔离数据库，没有使用真实用户数据或真实外部 Provider；未运行 Finance 全量 Maven 测试。

## 10. 诊断能力

保留原 WARN / ERROR 级别，通过分类判断 unique、内部一致性、数值范围、锁冲突或其他数据库失败，通过 path 区分 DataAccess、unexpected、各一致性及 cleanup 出口。

每次失败生成独立 UUID，测试验证不同失败的 ID 不同，可用于定位单次日志事件。当前源码未发现现有 MDC / request ID 关联机制，因此本轮没有声称 eventId 能关联客户端响应或贯穿整个请求，也没有新增 HTTP 字段或请求追踪基础设施。

非数据库未知异常仍输出原 Throwable 诊断；实跑回归保留 `ordinary non-database failure`。普通 Import domain error 的处理也保留。

## 11. 实际命令、测试数量与失败记录

所有 Maven 命令在 `backend` 执行，进程中设置现有 `JAVA_HOME=F:\ideajava\jdk21`；Java 21.0.10、Docker 29.6.1。命令中的 secret 全部由现有测试配置/容器提供，不使用真实凭据。

```powershell
.\mvnw.cmd -B '-Dtest=SensitiveSqlLoggingIntegrationTest' test
.\mvnw.cmd -B '-Dtest=SensitiveSqlLoggingTest,GlobalExceptionHandlerTest,GlobalAiExceptionHandlerTest,SensitiveSqlLoggingIntegrationTest' test
.\mvnw.cmd -B '-Dtest=InvestmentReplacementFailureIntegrationTest#unknownCommandUniqueConstraintIsNotMistakenForIdempotentRecovery' test
.\mvnw.cmd -B -DskipTests compile
git diff --check
git diff --cached --stat
git status --short
```

| 阶段 | Tests / Failures / Errors / Skipped | 说明 |
| --- | --- | --- |
| 首次 Maven 启动 | 未运行测试 | JAVA_HOME 缺失；环境问题，指定已安装 JDK 后解决 |
| 初版新增测试编译 | 未运行测试 | `getContentAsString()` 的 checked exception 未声明；修正测试方法签名 |
| 首次完成的旧代码 PostgreSQL 运行 | 3 / 2 / 1 / 0 | 两条泄露断言失败；另一条新测试误传空数组给 doesNotContain，已修正 fixture；日志保存在 `c4-baseline-initial.log` |
| 修正 fixture 后的稳定旧代码基线 | 3 / 3 / 0 / 0 | 全部因旧日志 ThrowableProxy 非空失败，`c4-baseline.log` |
| 修复后四类定向测试 | 58 / 0 / 0 / 0 | `c4-verification.log`，BUILD SUCCESS |
| 既有 replacement PostgreSQL 定向回归 | 1 / 0 / 0 / 0 | `c4-wrapper-regression.log`，BUILD SUCCESS |
| 单独 compile | 不计测试 | `c4-compile.log`，BUILD SUCCESS；该次增量编译显示 up to date，生产代码已在前面的 test 命令中实际重编译 |
| diff / 状态自查 | 不计测试 | tracked diff 无空白错误，staged diff 为空，文件范围符合任务 |

修复后累计 **59 条测试通过，0 failures / 0 errors / 0 skipped**。细分为：GlobalExceptionHandlerTest 8、GlobalAiExceptionHandlerTest 22、新增 SensitiveSqlLoggingTest 25、新增 PostgreSQL 集成 3、既有 replacement 定向集成 1。基线失败与中间测试修正单列，不计入修复后成功数，也不作为历史证据冒充本轮成功。

Surefire XML 保存于 `backend/target/surefire-reports`，对应最终实跑的 5 个测试类。target 证据是本地临时构建输出，未纳入 Git，之后 clean 会移除；本报告记录了可复核的命令、片段和结果。

## 12. 两个 Luna Max 的独立意见及主控复核

- **安全审查 Agent**：初审确认四个 DataAccess Throwable 出口、真实 reversal/replacement SQL cause 包装和过期 cleanup mapper 出口。实施后独立只读复审认为修复覆盖已识别出口，未发现新增 P0/P1 或 C4 明文绕过，建议 GO 候选。未运行 Maven；对 tracked diff 实跑 diff check 通过。提示旧映射遍历未防环及 null SQLState 边界。
- **测试和工程复审 Agent**：实施后独立只读复审认为日志模板/参数、完整异常图识别、HTTP 合同、真实 PostgreSQL 与原子回滚覆盖有效，未发现 P0/P1 或阻断问题。读取主控 58 条实跑结果，未自行运行 Maven。提示无 SQLState 的既有映射问题及多异常图日志分类可能与首个 cause 的 HTTP 分类不同。

主控重新检查源码、diff、日志与 Surefire XML，确认两个 Agent 的结论及限制；修正了工程 Agent 初审中把本轮新增测试误称历史 tracked 文件、漏报 22003 Throwable 的描述。本报告以实际源码和本轮命令输出为准。额外的 replacement 实跑与 compile 由主控完成。

## 13. Findings 与范围外事项

新增引入的 P0 / P1 / P2：**未发现**。C4 原 P2 泄露点已有上述实际修复及验证。

本轮静态检查新识别出的两个**既有、非阻塞后续 P2 事项**，未修改：

1. `findSqlState` 无 SQLState 时返回 null，`handleDataAccessException` 随后执行 `switch(null)` 会抛 NPE。该结论来自实际源码；未额外构造真实无 SQLState 的 PostgreSQL HTTP 场景，不能宣称此类 HTTP 映射已验证。本轮 null SQLState 单测针对通用 handler 和安全日志辅助类，不能混同此分支。修改该映射会涉及原 HTTP 行为，留待后续决策。
2. 旧 `hasConcurrencySqlState` / `findSqlState` 的 cause-only 遍历没有 visited，理论上人为循环的 DataAccess cause 图可卡住。正常 JDBC 错误链的实际复现没有此问题；新增 helper 自身已防环，但未改旧映射遍历。

日志分类可以识别 suppressed / nextException 中的白名单错误，而 HTTP 映射保留原 cause-only 算法；多错误图中二者可能分类不同，不影响敏感数据保护。未将此理论边缘场景扩成业务错误码重构。

## 14. 未验证范围与风险

- 本结论覆盖当前识别的应用 SQL 异常日志出口及上述测试路径；不声称所有第三方框架、容器启动日志、数据库服务器日志或未来新增出口均完成全局审计。
- 未验证真实无 SQLState DataAccess HTTP 场景；未运行完整 Finance、Frontend、Provider 或生产环境测试。
- 非数据库异常的原日志行为保留；没有数据库类型或 cause 标识、仅复制数据库原文的任意异常无法靠本次类型识别保证覆盖，当前业务调用链未发现这种新增可达出口。
- 安全事件舍弃原始 SQL 与堆栈；类别、处理路径与 eventId 保留有限诊断信息，原文不转存到其他通道。
- 未修改 C6、其他 C 项、金融模型、账户转账、AI Provider / Tool Calling、Frontend 或个人 AI 工作区。

## 15. 最终工作区状态与保护文件

最终允许差异为本报告列出的 6 个文件；新增文件保持未追踪，修改文件未暂存。staged diff 为空。`AGENTS.md` 与 `docs/codex-task.md` 仍为受保护未追踪文件；没有读取内容做 hash 校验，因此仅报告其 Git 状态及本轮未操作事实，不虚报内容比对。

`git diff --check` 通过；Git 对 cleanup 文件有既有 checkout 策略导致的 LF → CRLF 提示，没有 whitespace error。未引入无关文件、未自动提交或推送。

## 16. 最终判断与下一步

**C4 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**。

证据：稳定旧代码真实 PostgreSQL 泄露复现；修复后 59 条定向测试全部通过；HTTP 合同、财务回滚与诊断回归通过；compile 和 diff check 通过；两名只读独立复审未发现阻断问题。

下一步仅等待用户人工验收 / GO。主控到此暂停，不自动标记 CLOSED — GO，不 commit / push，不进入 C6 或最终 Freeze。
