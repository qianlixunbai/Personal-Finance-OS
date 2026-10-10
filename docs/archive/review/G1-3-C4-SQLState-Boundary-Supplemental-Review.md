# C4 SQLState Boundary Supplemental Review

日期：2026-10-11（Asia/Shanghai）

基线：`zh-cn` / `6d828ed2e7d3f3f465de45fd5559f94a2589f4bb`

最终建议：**C4 — NO-GO（等待最小补修授权）**。

null SQLState 边界已通过真实 socket HTTP 证明会绕过安全日志出口。原 59 项验收证据保留且对其覆盖路径仍有效，但不能证明这一新增边界安全。未修改任何生产代码，未 commit / push / 创建 Tag / 进入 C6，完成后暂停等待人工确认。

## 1. 检查范围与保护

本轮仅检查 `GlobalExceptionHandler.handleDataAccessException` 接收无 SQLState 异常时的 NPE、Spring 解析器回退、Tomcat 日志及 HTTP 结果。

开始时工作区与上一轮成果相符：两个 tracked 生产文件修改，既有安全日志辅助类、两份新测试及原 Review 为未追踪文件。staged diff 为空，没有额外异常变动。`AGENTS.md`、`docs/codex-task.md` 保持正常受保护 `??` 状态；未读取、修改、删除、暂存或提交其内容。

原有三份生产代码、两份 C4 测试、原 Review 和两份 59 项实跑日志，均在开始时登记 SHA-256；结束时 **8 个文件全部一致**。登记文件为 ignored 的 `backend/target/c4-supplement-preservation.csv`，不含受保护文件。

主控实际模型仍无法通过现有工具独立核验，不能虚报已确认 GPT-6.1 Sol High。本轮未启动子 Agent。

## 2. 新增真实 HTTP 补充测试

新增：`backend/src/test/java/com/financeos/integration/SqlStateBoundaryRuntimeIntegrationTest.java`。

复用已有 `PostgresIntegrationTest`、`@SpringBootTest(webEnvironment = RANDOM_PORT)`、`TestRestTemplate`、JWT 和 Spring Boot 测试机制：

- Tomcat 10.1.31 使用随机端口，通过 `http://localhost:<port>/api/v1/transactions/1` 发出真实 HTTP GET；不是 MockMvc，也不是直接调用 handler；
- Testcontainers `postgres:17-alpine`，运行日志确认 PostgreSQL 17.11，datasource 动态指向容器的 `test` 数据库；不连接日常数据库；
- 在容器内创建虚构 ACTIVE 用户，使用测试 profile 的 JWT，通过真实 `JwtAuthFilter` 查询及认证；
- 在现有 `TransactionService.getById` 的 spy 边界注入合成异常，保留真实 Controller、全局 advice、Spring 解析器、Tomcat、Security 错误分派；
- handler 的 spy 只执行 `invocation.callRealMethod()`、观察并重新抛出同一个 Throwable，不替换返回值或处理行为；核验真实 handler 被调用，通用 `handleException` 没有被调用；
- root Logback 捕获器采用线程安全队列，跨 Tomcat 请求线程捕获应用和框架事件；同时使用 `OutputCaptureExtension` 捕获实际控制台输出。

两种异常形态：

| 用例 | 合成异常 |
| --- | --- |
| `WITHOUT_SQL_CAUSE` | `DataIntegrityViolationException`，消息含虚构 SQL 参数 canary，无 SQLException cause，因此没有 SQLState |
| `WRAPPED_NULL_SQLSTATE` | `DataIntegrityViolationException → PersistenceException → SQLException`，三层消息均含虚构 canary，最内层金额为合成 `9876.54`，`SQLException.getSQLState()` 显式断言为 null |

这是**真实 HTTP 解析/容器路径 + 合成 Service 异常注入**的证明，不是 PostgreSQL 服务器自然产生无 SQLState 错误的证明。本轮不声称证明了特定真实数据库故障发生无 SQLState 的频率或来源。

## 3. 实际运行与失败结果

命令在 `backend` 执行，使用现有 JDK 21：

```powershell
$env:JAVA_HOME = 'F:\ideajava\jdk21'
.\mvnw.cmd -B '-Dtest=SqlStateBoundaryRuntimeIntegrationTest' test
```

| 运行 | Tests / Failures / Errors / Skipped | 根因与证据性质 |
| --- | --- | --- |
| 首轮 fixture | 2 / 2 / 0 / 0 | 虚构 JWT 对应用户未入库，未调用 Service；修正新增测试 fixture 后重跑。这次不能作为泄露证据 |
| 完整真实 HTTP 补充检查 | **2 / 2 / 0 / 0** | 两用例均成功进入真实 handler 并观察 NPE；HTTP 响应脱敏、容器数据库隔离及未产生流水断言通过，随后日志安全断言失败 |

最终 Maven **BUILD FAILURE** 是确认的敏感日志泄露导致的安全红灯，不是环境或编译失败。每个用例有四项泄露断言失败，但测试数量仍为 2，不能报告为 8 项测试。

未删除、禁用或弱化断言以使其变绿；未更改既有 C4 测试。新增测试保留红灯，供获授权后的边界修复回归使用；届时其观察 NPE 的诊断断言也应随正常行为调整。

## 4. 实际行为与日志回退

两用例共同路径：

```text
已认证真实 HTTP GET
→ TransactionController.get
→ Service 注入无 SQLState DataAccessException
→ GlobalExceptionHandler.handleDataAccessException
→ findSqlState 返回 null
→ String switch 抛出 NullPointerException
→ ExceptionHandlerExceptionResolver 记录 NPE，返回 null
→ 原 DataAccessException 继续传播
→ Tomcat StandardWrapperValve 记录原消息和 root cause
→ 客户端收到脱敏 401
```

实际 handler NPE 消息：

```text
Cannot invoke "String.hashCode()" because "<local5>" is null
```

Spring WARN 来自：

```text
org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver
Failure in @ExceptionHandler com.financeos.common.GlobalExceptionHandler#handleDataAccessException(DataAccessException)
```

该 WARN 记录的是新的 NPE，事件本身未含 canary；**敏感回退发生在随后 Tomcat ERROR**：

```text
org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]
Servlet.service() ... threw exception [Request processing failed:
org.springframework.dao.DataIntegrityViolationException:
synthetic Spring SQL bindings=C4_SQLSTATE_BOUNDARY_SYNTHETIC_PRIVATE_CANARY] with root cause
java.sql.SQLException: synthetic failing row=C4_SQLSTATE_BOUNDARY_SYNTHETIC_PRIVATE_CANARY amount=9876.54
```

这是截取后的合成数据证据；无真实财务信息或凭据。

## 5. 日志各层检查结果

| 检查层 | WITHOUT_SQL_CAUSE | WRAPPED_NULL_SQLSTATE |
| --- | --- | --- |
| `com.financeos.*` 应用事件数 | 0 | 0 |
| 应用 logger 最终输出含 canary | 否 | 否 |
| 框架/容器 formatted message 含 canary | **是** | **是** |
| 框架/容器 argument array 含 canary | 否；相关事件 arguments 为 null | 否；相关事件 arguments 为 null |
| 框架/容器 ThrowableProxy / cause 含 canary | **是** | **是** |
| `%msg%n%ex` 最终渲染含 canary | **是** | **是** |
| 实际 stdout/stderr 含 canary | **是** | **是** |

argument array 不含数据不能证明安全：Tomcat 先把 `ServletException.getMessage()` 拼进最终消息，同时传入 root cause；两种方式都泄露。应用 logger 没有事件也不能证明安全：NPE 发生在安全日志分支之前，原异常已交给框架/容器。

本地原始证据（ignored target，未提交）：

- `backend/target/c4-sqlstate-boundary-runtime.log:117`：第一用例的 handler NPE；
- `:255`、`:257`：第一用例 Tomcat ERROR 及原 DataAccessException canary；
- `:396`：第一用例的 HTTP / 日志检查摘要；
- `:399`：第二用例 handler NPE；`:537`、`:539`：Tomcat 原包装消息及最内层 SQLException canary、金额；
- `:663`：第二用例摘要；`:836`：最终 2 tests / 2 failures / 0 errors / 0 skipped；
- `backend/target/c4-sqlstate-boundary-WITHOUT_SQL_CAUSE.txt` 和 `c4-sqlstate-boundary-WRAPPED_NULL_SQLSTATE.txt`：每个事件的完整 logger 名称、参数、Throwable 渲染与 HTTP 结果；
- `backend/target/surefire-reports/TEST-com.financeos.integration.SqlStateBoundaryRuntimeIntegrationTest.xml`：最终测试结果；
- `backend/target/c4-sqlstate-boundary-fixture-initial.log`：首轮 fixture 失败，单独保留以区分证据。

## 6. 依赖源码交叉核验

读取的是 Maven 本机已有的、与实跑版本一致的 sources jar，没有引入依赖或联网下载：

- Spring WebMVC 6.1.14 的 `ExceptionHandlerExceptionResolver.java:434-444`：捕获 invocation Throwable，WARN 记录处理器的新异常，注释明确继续处理原异常，返回 null；
- Tomcat embed core 10.1.31 的 `StandardWrapperValve.java:198-205`：捕获 ServletException，取 root cause，以包含 `e.getMessage()` 的格式化消息和 root cause 调用 `container.getLogger().error(...)`。

因此回退不只是理论判断，源码与真实事件、ThrowableProxy、实际控制台输出一致。

## 7. HTTP 与隔离数据库行为

两用例均实际收到：

```http
HTTP 401
```

```json
{"code":401,"message":"未认证或登录已过期"}
```

响应不含 canary、合成金额、SQLException 或 DataIntegrityViolationException；客户端未收到原始数据库详情。但是它也不是全局 handler 正常返回的数据库 503 或内部错误 500。

Service 和 advice 的实调用已验证，因此该 401 发生在初次认证并进入业务接口之后，不能解释为合成异常未触发。结合 `SecurityConfig` 对未单独放行的 `/error` 要求认证、`SecurityErrorResponseHandler` 的 401 合同，推断其由错误分派阶段的认证回退产生；本轮未额外插桩 ERROR dispatcher，因此不将该具体来源写成独立实测结论。

`SELECT current_database()` 实际返回 `test`，流水计数为 0。此为只读 GET / 合成失败，没有修改账户或金融写路径，不重复宣称本轮另行运行了原子写入回滚测试。

## 8. 是否需要 C4 内补修及最小方案

**需要。** 这一 null SQLState 问题已经证明是绕过 C4 安全日志的直接根因，不能继续仅作为独立非阻塞 HTTP 映射缺陷处理。

建议只在 `handleDataAccessException` 的 switch 入口将 null 归一到未识别值，复用已有 default 安全日志和固定响应：

```java
// 仅为待授权方案，本轮没有应用到生产代码。
String sqlState = findSqlState(e);
switch (sqlState == null ? "" : sqlState) {
    // 原有各分支和 default 保持原样。
}
```

结果预期：null SQLState 进入已有 `DATABASE_ERROR / DATA_ACCESS` 安全事件及固定 **503 / 数据库服务暂时不可用** 响应，不再从 NPE 回退到容器记录原 Throwable。

必须明确：这会将本轮实测的异常错误分派结果 **401 改为受控 503**，需人工确认该响应选择；其他非 null SQLState 映射、DTO、错误码合同与业务行为无需改动。不改 `findSqlState` 遍历规则，不修 cause 循环，不调整全局框架日志，不吞掉异常，不把原文转存另一个通道。

获授权后应让两种真实 HTTP 用例验证 handler 不再抛 NPE、原异常不进入 Spring/Tomcat ERROR 回退、日志所有层级无 canary、明确的 HTTP 合同，并保持原 59 项安全断言。此处是具体可审阅方案，不是已完成修复。

## 9. 原 59 项证据的有效性

原有 3 份生产文件、2 份 C4 测试、原 Review 和两份实跑日志的 SHA-256 均未改变；原 5 个 Surefire XML 仍记录：8、22、25、3、1，共 59 项通过。

这些证据仍有效，证明上一轮覆盖的 SQLState、包装出口、HTTP 和回滚路径通过。本轮未重跑这 59 项，不能将它们称为本轮新增通过结果。

本补充检查发现它们未覆盖的 handler null SQLState → 容器日志回退，因此**整体 C4 正式 GO 暂不成立**。原 Review 作为上一轮历史快照保留不覆盖；当前判断以本补充报告为准。

## 10. 最终范围自查与判断

本轮新增且仅新增：

1. `backend/src/test/java/com/financeos/integration/SqlStateBoundaryRuntimeIntegrationTest.java`；
2. `docs/archive/review/G1-3-C4-SQLState-Boundary-Supplemental-Review.md`。

本地 target 另保存诊断和保护摘要，均 ignored。tracked diff 与本轮开始相同，staged diff 为空，原成果内容未变化。`git diff --check` 及新增文件空白检查通过，只有 LF/CRLF 策略提示。

**C4 — NO-GO**：两种真实 HTTP 场景均证实原敏感数据库异常经 Tomcat formatted message 和 Throwable 输出泄露，需要最小边界补修。

已按要求完成补充检查和报告，暂停等待用户对具体补修方案及 HTTP 503 选择的人工确认。未改生产代码、未修无关缺陷、未提交或推送、未进入 C6。
