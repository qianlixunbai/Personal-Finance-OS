# C1 — Final Acceptance Review

日期：2026-10-10（Asia/Shanghai）

**结论：C1 — GO（技术验收通过，等待用户人工 GO 后才能精确暂存、提交）。**

原始 14 项验收全部 PASS；本轮 8 个定向测试类、45 个唯一用例的最终结果全部通过，0 failures、0 errors、0 skipped。未发现 C1 范围内新增 P0/P1。未 commit、未 push，未修改项目正式阶段状态。

## 1. 恢复时 Preflight 与工作区保护

实际只读检查了 `git branch --show-current`、`git log -1 --oneline`、`git rev-parse HEAD`、`git status --short`、`git diff --stat`、`git diff --cached --stat`、`git diff --check`。

| 项目 | 本轮结果 |
| --- | --- |
| Branch | `zh-cn` |
| HEAD | `7d2d83c23abeaa64f0f5854f729ea919509b551a` |
| Commit | `feat: add AI analyst frontend` |
| 恢复时已跟踪修改 | 7 个 |
| 恢复时未跟踪文件 | 7 个：5 个 C1 文件、2 个受保护文件 |
| 恢复时 C1 总文件数 | 12 个 |
| 暂存区 | 空 |
| 与交接记录的差异 | 未发现分支、HEAD 或文件清单差异 |
| 原有成果保留 | 12 个原 C1 文件均保留；本轮仅修正一个补测文件的 HTTP 解析，其他 11 个文件 SHA-256 与恢复快照一致 |

两个受保护文件 `AGENTS.md`、`docs/codex-task.md` 全程未读取内容、未修改、未删除、未暂存、未提交。仅通过 Git 状态和文件元数据检查其存在及状态；最终仍为未跟踪文件，长度与 LastWriteTimeUtc 与恢复时一致。未对受保护文件做内容 hash。

未执行 reset、stash、clean、restore、checkout 覆盖、pull、rebase、merge、add、commit 或 push；未创建 branch、worktree 或迁移 WIP。Maven `clean` 仅清理后端构建产物 `backend/target`，测试证据在最终 clean compile 前已另行保存。

主控统一执行编译、测试、集成和 GO 判断；按用户指定使用两个 `gpt-6-luna`、`max` 子 Agent 做只读生命周期和 migration 复核，二者均未修改文件、未运行 Maven。主控重新核对了其引用的关键源码与断言。

## 2. 历史基线、本轮补测及实际执行结果

**历史证据：**交接文本记录原 36 项通过、0 failures/errors/skipped；其中 12 项曾重复用 Docker PostgreSQL 验证，不能算成 48 个独立用例。旧 clean compile 和旧测试记录不作为新增补测本轮通过的依据。

**暂停前新增、此次实际验证的 5 个方法：**

| Test Class | Test Method | 本轮执行用例数 |
| --- | --- | ---: |
| `TransactionImportLifecycleIntegrationTest` | `deleteReversesOnlyTargetEffectAndPreservesLaterBalanceChanges` | 4 |
| `TransactionImportLifecycleIntegrationTest` | `exactDuplicateRemainsRejectedAfterPartialOrCompleteDeletion` | 2 |
| `TransactionImportLifecycleIntegrationTest` | `deleteBalanceUpdateFailureRollsBackTransactionBalanceAndReceiptEvidence` | 1 |
| `TransactionImportLifecycleIntegrationTest` | `immutableReceiptTriggersRejectAllMutationsBeforeAndAfterTransactionDeletion` | 1 |
| `FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest` | `v19ReceiptSurvivesV20UpgradeDeleteAndMockMvcReplayWithoutAnotherFinancialEffect` | 1 |
| 合计 | 5 个方法 | **9** |

multirow 的正负 ADJUSTMENT 参数化方法为既有 2 个用例，已包含在历史基线中，不再次计入新增 9 项。

**本轮命令与结果：**所有 Maven 命令在 `backend` 执行，当前进程设置 `$env:JAVA_HOME = 'F:\ideajava\jdk21'`。

```powershell
.\mvnw.cmd -B clean test-compile
.\mvnw.cmd -B '-Dtest=TransactionImportLifecycleIntegrationTest,FlywayTransactionImportVersionTwentyMigrationIntegrationTest,FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest' test
.\mvnw.cmd -B '-Dtest=FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest,FlywayMigrationIntegrationTest,FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest,FlywayTransferVersionNineteenMigrationIntegrationTest,TransactionImportConfirmIntegrationTest,TransactionImportConfirmRuntimeIntegrationTest' test
.\mvnw.cmd -B clean compile
```

| 执行阶段 | 实际结果 |
| --- | --- |
| 首次 Maven 尝试 | 当前进程缺少 JAVA_HOME，未进入编译；设置已有 JDK 路径后恢复 |
| 当前全部源码 clean test-compile | BUILD SUCCESS，生产源码及暂停前补测均编译通过 |
| 首轮三个 C1 测试类 | 21 次执行：20 PASS、1 failure、0 errors、0 skipped |
| 修复后六个定向测试类 | 25 次执行：25 PASS、0 failures/errors/skipped |
| 按每个用例最后一次结果去重 | **45 个唯一用例全部 PASS** |
| 本轮执行次数 | **46 次**，含同一个 UpgradeRuntime 用例失败后复验 1 次 |
| 最后一次 backend clean compile | BUILD SUCCESS（20:32:26 +08:00），无编译错误 |

首轮失败的根因是补测的 `readTree → treeToValue`：默认 JSON 浮点节点将响应中的 `0.00` 转为 `0.0`，导致 BigDecimal scale 不同、record 全对象 equals 失败。其余 Receipt 字段、余额和 digest 一致。修正为 `objectMapper.readerFor(...).at("/data").readValue(responseBytes)`，直接从 HTTP 响应字节绑定 DTO，保留 BigDecimal scale；原有 Receipt/ConfirmResponse 全对象相等断言完整保留。GET 与 Replay 均使用此解析方式。未修改 production behavior、expected、异常处理或 migration。

最终 clean compile 有 `javax.annotation.meta.When.MAYBE` 缺少 annotation 类的 warning；同样 warning 在本轮初次编译中已出现，不影响编译和测试，未扩大范围处理。

| Test Class | 最终通过数 | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `TransactionImportLifecycleIntegrationTest` | 14 | 0 | 0 | 0 |
| `FlywayTransactionImportVersionTwentyMigrationIntegrationTest` | 6 | 0 | 0 | 0 |
| `FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest` | 1 | 0 | 0 | 0 |
| `FlywayMigrationIntegrationTest` | 1 | 0 | 0 | 0 |
| `FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest` | 1 | 0 | 0 | 0 |
| `FlywayTransferVersionNineteenMigrationIntegrationTest` | 1 | 0 | 0 | 0 |
| `TransactionImportConfirmIntegrationTest` | 17 | 0 | 0 | 0 |
| `TransactionImportConfirmRuntimeIntegrationTest` | 4 | 0 | 0 | 0 |
| 合计 | **45** | **0** | **0** | **0** |

测试使用隔离 Testcontainers `postgres:17-alpine`；Docker Desktop 启动后 daemon 可用，Docker client/server 为 29.6.1，Java 为 21.0.10。未连接日常业务数据库，未调用真实付费 Provider，未运行 full suite 或无关前端测试。

## 3. 原始 14 项最终 Evidence Mapping

以下 Class 缩写仅用于表格可读性，完整映射及源码链接如下；所有类的包名均为 `com.financeos.integration`。

- **L**：[TransactionImportLifecycleIntegrationTest](../../../backend/src/test/java/com/financeos/integration/TransactionImportLifecycleIntegrationTest.java)
- **M**：[FlywayTransactionImportVersionTwentyMigrationIntegrationTest](../../../backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyMigrationIntegrationTest.java)
- **U**：[FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest](../../../backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest.java)

| # | 验收项 | Test Class / Test Method | 实际关键断言 | 本轮实际运行结果 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1 | 普通创建后 DELETE | L / `ordinaryManualTransactionDeleteRemainsSuccessfulAndRepeatedDeleteHasNoExtraEffect` | 经正式 Service 创建支出 40，余额 -40；标准 DELETE HTTP/code 200；余额恢复 0、流水数 0；重删与 GET 均 404，余额仍 0 | 1/1 通过 | PASS |
| 2 | Import 后标准 DELETE，其他流水不受影响 | L / `incomeAndPositiveOrNegativeAdjustmentCanBeDeletedIndividuallyFromAMultirowBatch`；`deleteKeepsReceiptAndItemAndConfirmReplayDoesNotRecreateTransactionOrBalanceEffect` | JWT DELETE HTTP/code 200；目标 ID 真实消失；多行场景 transaction 全字段快照仅去掉指定 ID，其他 income/expense/adjustment 及其他账户余额按阶段保留 | multirow 2/2、单行 1/1 通过 | PASS |
| 3 | INCOME、EXPENSE、正负 ADJUSTMENT，只撤销目标 effect并保留后续变化 | L / `deleteReversesOnlyTargetEffectAndPreservesLaterBalanceChanges` | 4 组 target effect；其后同账户新建 INCOME 37；删除前余额为 target effect +37，删除后为 37；只剩后续流水且完整行不变；历史三表和 Receipt 不变 | 4/4 通过 | PASS |
| 4 | 原始 Item 所有字段完全不变 | L / `deleteKeepsReceiptAndItemAndConfirmReplayDoesNotRecreateTransactionOrBalanceEffect`；M / `upgradesV19WithoutChangingHistoricalReceiptsAndEnforcesTheNewLifecycleBoundary` | L 的 `itemSnapshot` 使用 `SELECT *`，DELETE/Replay 前后整行 Map 列表相等，包含 `created_transaction_id`；M 使用每行 `to_jsonb` 的全部字段快照 | L 1/1、M 1/1 通过 | PASS |
| 5 | 删除后 Receipt GET 200，历史引用/counts/impacts 保留 | L / `deleteKeepsReceiptAndItemAndConfirmReplayDoesNotRecreateTransactionOrBalanceEffect` | GET helper 校验 HTTP/code 200；整个 Receipt JSON 与原对象相等，历史 transactionId 保留；DB impact row_count、balance_before、delta、balance_after 明确断言并完整快照相等 | 1/1 通过 | PASS |
| 6 | DB/API resultDigest 与 Receipt 内容保持 | L / `deleteKeepsReceiptAndItemAndConfirmReplayDoesNotRecreateTransactionOrBalanceEffect`；U / `v19ReceiptSurvivesV20UpgradeDeleteAndMockMvcReplayWithoutAnotherFinancialEffect` | DB batch result_digest 与原 API digest 相等；DELETE/Replay 后 Batch 全字段不变；GET/Replay Receipt 全对象相等；U 的升级前后历史快照相等，并对 typed Receipt 与 digest 做相等断言 | L 1/1、U 修复后 1/1 通过 | PASS |
| 7 | 原 Session/key/intent Replay 不重建、不改余额 | L / `deleteKeepsReceiptAndItemAndConfirmReplayDoesNotRecreateTransactionOrBalanceEffect` | 同 preview session、同 key、同 confirm body；`idempotentReplay=true`、receipt 整对象相等；流水数仍 0、余额仍 0，三表完整快照与 DB digest 不变 | 1/1 通过 | PASS |
| 8 | 部分或全部删除后新 Session Exact Duplicate 拒绝 | L / `exactDuplicateRemainsRejectedAfterPartialOrCompleteDeletion` | 新 session ID 与原 session 不同；相同输入及 mapping，用新 key 并确认 warning；HTTP 409 / `IMPORT_EXACT_DUPLICATE`；transaction、余额、Batch/Item/Impact/Receipt 不变，Batch 数仍 1 | 部分/全部删除 2/2 通过 | PASS |
| 9 | 异用户 DELETE 与 Receipt GET 404，无状态变化 | L / `incomeAndPositiveOrNegativeAdjustmentCanBeDeletedIndividuallyFromAMultirowBatch` | foreign JWT 对 owner 导入流水 DELETE 404/code404；foreign Receipt GET 404/code404；两个账户余额、owner transactions 全字段快照、三张证据表完整快照均不变 | 正负参数 2/2 通过 | PASS |
| 10 | balance UPDATE 阶段注入异常，DELETE 全事务回滚 | L / `deleteBalanceUpdateFailureRollsBackTransactionBalanceAndReceiptEvidence` | BEFORE UPDATE OF balance trigger 先确认 target 已被 DELETE，再抛指定 23514；HTTP/code500、DataIntegrityViolationException、指定注入消息和 SQLSTATE；事后 transactions/account 全字段、三表证据及 Receipt 均恢复原状；finally 清理注入 trigger/function | 1/1 通过 | PASS |
| 11 | V19→V20 带历史数据升级及完整生命周期 | U / `v19ReceiptSurvivesV20UpgradeDeleteAndMockMvcReplayWithoutAnotherFinancialEffect`；M / `upgradesV19WithoutChangingHistoricalReceiptsAndEnforcesTheNewLifecycleBoundary` | U 启动停在 V19，用真实 Confirm 写历史数据；显式 migrate 到20，Session/Batch/Items/Impacts 全字段相等且 DB digest 不变；DELETE200/余额0；GET200与原 typed Receipt 相等；原 key/intent Replay=true且不重建、不改历史 | U 修复后 1/1、M 1/1 通过 | PASS |
| 12 | Fresh V1→V20 与约束/trigger 状态 | M / `freshV1MigrationAppliesThroughV20WithTheExpectedReceiptConstraintsAndTriggers` | 从空库 migrate latest，appliedVersion=20；仍有 owned Batch FK、无持续 Transaction FK；created_transaction_id NOT NULL；immutable及INSERT trigger启用，BEFORE INSERT及函数所有权条件/FOR KEY SHARE 存在 | 1/1 通过；通用 fresh migration 回归亦 1/1 通过 | PASS |
| 13 | 删除前后 Batch/Item/Impact UPDATE/DELETE 均拒绝 | L / `immutableReceiptTriggersRejectAllMutationsBeforeAndAfterTransactionDeletion` | 三表分别执行合法 no-op UPDATE 和 DELETE；流水删除前后各执行六种操作，均要求 immutable 消息、DataIntegrityViolationException、SQLSTATE23514；最终三表全字段和Receipt不变 | 1/1 通过（方法内 12 次 mutation 拒绝断言，不另计12个用例） | PASS |
| 14 | PUT金额/类型/账户后 DELETE撤销当前effect，Receipt不变 | L / `updateThenDeleteReversesTheCurrentTransactionEffectAndLeavesOriginalReceiptUnchanged`；`updateToAnotherAccountAndTypeThenDeleteReversesTheNewAccountEffect` | 同账户 EXPENSE100→200，DELETE撤销200；另一场景旧账户EXPENSE100→新账户INCOME200，旧账户恢复0、新账户200；DELETE后双方0；校验当前数据库amount/type/account及删除；原Receipt与三表快照不变 | 两方法各 1/1 通过 | PASS |

**矩阵结果：14 PASS、0 PARTIAL、0 MISSING。** 表中同一用例可支撑多个验收项，不重复累加为测试总数。

## 4. V20、余额、Receipt、Replay、Duplicate 与并发结论

[V20 migration](../../../backend/src/main/resources/db/migration/V20__decouple_import_receipt_transaction_lifecycle.sql) 仍为批准的方案 A，未重写。迁移首先依次锁定 `transactions`、`transaction_import_items` 两表为 ACCESS EXCLUSIVE，在同一 Flyway PostgreSQL 事务中检查历史同用户引用、安装 INSERT validation，再删除持续 Transaction FK。无历史 Receipt DML，无 CASCADE / SET NULL；保留历史 ID、Batch ownership FK、NOT NULL、immutable triggers、counts、impacts、digest。

`assertInsertValidation` 真实验证已存在引用、同事务新建引用、缺失引用、跨用户引用、异用户 Batch 归属失败及临时同名表遮蔽；后者不会绕过明确限定的 `public.transactions`。负向校验使用 SQLSTATE 23503。

`serializesInsertAndDeleteInEitherRowLockOrder` 通过 `pg_blocking_pids` 验证等待关系：INSERT先取得KEY SHARE，DELETE等其提交后成功且历史Item保留；DELETE先取得行锁，INSERT等待，DELETE提交后INSERT23503且无Item；DELETE回滚则INSERT成功。额外 repeatable-read 快照分支接受序列化/引用失败并验证无Item写入，不将更强隔离级别的失败伪装成成功。

`migrationHoldsBothTableLocksAcrossTheTriggerToFkCutoverAndQueuesConcurrentWrites` 在trigger建立后、FK移除前暂停迁移，核对两表锁及并发INSERT/DELETE阻塞；恢复后V20完成，非法引用INSERT23503、原流水DELETE成功，历史Item不变。

`rollsBackFunctionTriggerAndConstraintChangesWhenTheFinalDropFails` 在最后DROP阶段注入DDL失败，验证仍为V19、无V20 history、已创建function/trigger回滚、原FK保留、全字段历史快照不变。`rejectsAnInvalidHistoricalReferenceBeforeChangingTheV19Schema` 独立验证旧坏引用fail-fast，并保留原V19数据和schema。

余额撤销继续走既有 TransactionService 事务：锁用户流水及账户，删除流水，撤销当前 type/amount effect；无生产 Java 修改。GET/Replay 继续从 Batch/Items/Impacts 重建原历史 Receipt并验证digest，不依赖当前流水存在。Exact Duplicate继续依赖不可变Batch的输入digest，因此删除部分或全部流水不能绕过。

**迁移风险：**ACCESS EXCLUSIVE会短暂阻塞两表读写，仍需维护窗口；相反锁顺序的其他事务可能触发PostgreSQL deadlock检测，失败应整体回滚后重试。该取舍已经写入ADR-020，不是本轮新增问题。

## 5. 最终范围、文件清单与静态检查

| 检查 | 本轮结果 |
| --- | --- |
| backend clean compile | 实际运行通过；测试解析修正后的源码已编译并执行通过 |
| C1 targeted tests | 45 个唯一用例最终全部通过 |
| git diff --check / cached --check | 通过；暂存区为空 |
| 未跟踪 C1 文件空白检查 | 使用逐文件 git diff --no-index --check 验证，通过 |
| 文档本地链接 | 5 份原 C1 文档共34个本地链接有效；新增本报告链接另行检查通过 |
| V1–V19 blob不变性 | 19 个历史migration经 Git属性规范化后hash-object与HEAD blob逐一相等，0 mismatch |
| production Java | 与HEAD无diff，保持零修改 |
| Secret扫描 | 对C1修改文件做高置信度secret模式扫描和凭据引用静态检查，无疑似真实凭据；测试密码/动态JWT/Testcontainers凭据属于隔离fixture，未使用用户真实secret；模式扫描不能证明任意编码secret不存在 |
| 受保护文件 | 均保持未跟踪；元数据未变；无内容读取/改写/暂存 |
| C2–C11 | 未实施或顺手修改 |
| 最终Git状态 | 7个已跟踪修改、8个未跟踪文件：13个C1文件（含本报告）与2个受保护文件；暂存区为空 |

相对于HEAD的实际C1文件清单如下。前12个是恢复时已有成果；本轮只修正第10个的测试解析，并新增第13个报告。

| 文件 | 最终工作区状态 / 本轮动作 |
| --- | --- |
| `backend/src/test/java/com/financeos/integration/FlywayMigrationIntegrationTest.java` | 已跟踪修改，原成果保留 |
| `backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.java` | 已跟踪修改，原成果保留 |
| `backend/src/test/java/com/financeos/integration/FlywayTransferVersionNineteenMigrationIntegrationTest.java` | 已跟踪修改，原成果保留 |
| `docs/ADR/README.md` | 已跟踪修改，原成果保留 |
| `docs/architecture/api.md` | 已跟踪修改，原成果保留 |
| `docs/architecture/database.md` | 已跟踪修改，原成果保留 |
| `docs/domain/transaction-import.md` | 已跟踪修改，原成果保留 |
| `backend/src/main/resources/db/migration/V20__decouple_import_receipt_transaction_lifecycle.sql` | 未跟踪，原成果保留 |
| `backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyMigrationIntegrationTest.java` | 未跟踪，原成果保留 |
| `backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest.java` | 未跟踪，本轮修正GET/Replay响应解析，保留全对象equals |
| `backend/src/test/java/com/financeos/integration/TransactionImportLifecycleIntegrationTest.java` | 未跟踪，原成果保留 |
| `docs/ADR/ADR-020-import-receipt-historical-transaction-reference.md` | 未跟踪，原成果保留 |
| `docs/archive/review/C1-Final-Acceptance-Review.md` | 未跟踪，本轮新增验收报告 |

两个受保护文件不属于以上C1清单，不得纳入后续暂存/提交。

## 6. Findings、验证限制及下一步

| Severity / 项目 | 结论 |
| --- | --- |
| C1 P1 | 验收通过：持续FK阻断DELETE已解除，余额和不可变历史证据保持正确 |
| 本轮新增P0/P1 | C1范围定向复核与测试未发现 |
| 本轮补测失败 | 测试JSON中间节点丢失BigDecimal scale；已最小修正并复验，无生产代码改动 |
| 已有C2–C10/P2、C11/P3 | 延续交接记录，不在本轮重新审计或修复 |
| C10文档漂移 | 本轮只读见database仍写15张表，而fresh migration测试验证16张，版本概览未列V18/V19；按用户排除范围保留，未顺手修复 |

GO只覆盖C1；本轮不是全仓复审，不能由此声称C2–C11已解决或整个项目已获生产发布验收。迁移在隔离PostgreSQL17上验证，真实业务数据库部署未执行。历史36项数字仅为交接证据；本轮最终45项数字来自实际Surefire结果，彼此分开记录。

本轮日志、最终8份Surefire XML、按用例提取的 `c1-results.json` 和最终 `c1-clean-compile.log` 保存于本机目录：

```text
C:\Users\28421\.codex\visualizations\2026\10\10\01a125c4-3c0b-7893-9cfa-1d13ce122b49\c1-acceptance-evidence
```

初次失败日志也保留，未用最终通过记录覆盖初次失败事实。最终clean compile会重建target，故不依赖target保留这些证据。

**下一步：等待用户人工GO。** 当前未暂存、未commit、未push；只有收到人工GO，才可按明确C1文件清单精确暂存并提交到正式 `zh-cn`，继续排除两个受保护文件。
