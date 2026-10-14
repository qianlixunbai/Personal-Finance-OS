# G4-3 Batch B2-1 V17 Historical Runtime Review

日期：2026-10-14（Asia/Shanghai）。授权范围：V17 历史 Runtime 测试最小修复、定向测试和两名 GPT-6 Luna Max 只读复审。未授权提交、推送、Tag、生产修改或 B2-2。

## 1. 结论

**G4-3 / Batch B2-1 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。**

用户恢复 Docker 后，指定 V17 完整类在两个独立 Maven 进程和两个独立 PostgreSQL 17.11 容器中连续通过，每次 1 test、0 failures/errors/skipped。V21 Migration 与 B1 三个关联完整类也实际通过，共 10 tests、0 failures/errors/skipped。两名指定 Luna Max 已只读核验新增日志/XML，确认 Digest/Replay 与 Context 生命周期的阶段验收候选门槛成立；此前环境 HOLD 解除。

结论仅限 B2-1 实施验收候选，不关闭整个 Batch B2，不宣称完整 Backend 或 Hosted CI 已全绿。保留 HTTP 随机端口监听 P2 与历史余额夹具限制，等待人工技术验收；本轮未提交、推送或 Tag。早前 Docker 失败和编译修正过程在第 11 节保留，未冒充成功结果。

## 2. Preflight

| 核对项 | 实际结果 |
| --- | --- |
| 仓库 / 分支 | `F:/IDEA/daima/finance-os` / `zh-cn` |
| 指定 / 本地 HEAD | `0f0da9a205ed8725ef9de60474c707cce87e6204` |
| 本地 `origin/zh-cn` | 同一 SHA |
| 独立 `git ls-remote origin refs/heads/zh-cn` | 同一 SHA |
| 初始 tracked / staged | 均为空 |
| 初始 untracked | 仅 `AGENTS.md`、`docs/codex-task.md` |

受保护路径只核对 Git 路径状态。依据用户聊天中完整仓库规则执行，没有打开受保护文件、计算 hash、修改、删除、暂存或提交。未创建分支/worktree，没有破坏性 Git 操作。

Docker 恢复后复核：分支/HEAD 不变，tracked 仅指定测试、untracked 仅本 Review 与两个受保护路径，staged 为空；本地 `origin/zh-cn` 与再次独立 `ls-remote` 仍等于指定 SHA，没有新增无关改动。

## 3. 实际修改及 diff

唯一修改测试：`backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.java`；唯一新增文档：本 Review。未修改生产 Java、V1–V21 SQL、其他测试、pom.xml 或 CI Workflow。

保留原测试方法名、六个原时间 Fixture、原历史数据插入和错误 Digest 注入逻辑。移除导致旧 Schema 启动冲突的 `@SpringBootTest` / V15 启动策略，改为测试内显式创建与关闭两次当前 Spring Context。最终测试 diff 为 +291 / -49；完整实际测试 diff 附在本文末尾。

## 4. 原始根因

静态调用链：原 `LegacyV15FlywayConfiguration` 将应用启动 Schema 限制为 V15；当前 `TransactionImportPreviewConfiguration` 初始化真实 `LocalTemporaryImportFileStorage`，构造器调用 `TransactionImportFileLifecycleService.bind()`，需要 V21 `transaction_import_storage_bindings`，因此旧 Schema 与当前 Bean 初始化要求冲突。

此前 [B1 Review](G4-3-B1-Flyway-Version-Boundary-Review.md) 保存了该类 `relation "transaction_import_storage_bindings" does not exist` 的历史实际失败。本轮原文件 baseline 在 Testcontainers `beforeAll` 已因 Docker 不可用失败，未重新实跑原文件的 Registry 错误；Docker 恢复后仅执行最终修复版本，两次完整方法均通过。这里明确区分代码审计、历史失败证据与本轮修复后实际运行证据。

## 5. Context 生命周期

以下生命周期断言已在两次独立 PostgreSQL 完整执行中通过：

1. 使用 `SpringApplication(FinanceOsApplication.class)`、显式 `test` profile、独占容器 JDBC URL、随机服务端口创建当前 Context，自动迁移至 V21 并初始化真实 C9 RAW/PLAN Binding。
2. `SpringApplication.run()` 返回后断言 ready event 恰好一次、Context active、当前版本 21、两个非 quarantine Binding。生产启动清理为同步 `@EventListener`，没有 `@Async`；测试没有重新发布 ready event。
3. 断言生产 Cleanup Scheduler 与对应 TaskScheduler Bean 均不存在；检查 Cleanup 读写锁无持有者、Hikari active connections=0、两个 Storage worker active=0 且队列为空。
4. 历史窗口只调用一次旧 Context 的 `getReceipt()`。没有 HTTP、Upload、Preview、Confirm、Storage save/open/exists/delete 或手动 Cleanup 调用；fixture SQL 是历史构造，不是 Runtime 金融写入。
5. try-with-resources 关闭旧 Context；捕获销毁阶段 WARN/ERROR 并断言为空，断言 Hikari 已关闭、root closed、Storage worker terminated、control FileChannel 已关闭、无其他数据库客户端连接。任何失败阻止进入离线迁移，不吞掉异常。
6. V17→V21 离线迁移完成后创建全新 Context；通过生产 `test` profile 的隔离目录与随机 UUID 初始化真实新 Binding，断言新 Storage IDs 不复用旧 IDs。最后再次关闭并核对资源状态。

离线观察使用无池 `DriverManagerDataSource` / `JdbcTemplate`，每次操作自行打开、释放连接。容器由 Testcontainers 管理。测试未删除生产配置创建的临时目录或任何用户临时资料。

两次日志均明确显示旧 HikariPool-1 的 `Shutdown completed` 早于 V17 migration，之后启动新 HikariPool-2，最终关闭并 clean 数据库。Storage worker/control、ready event=1、scheduler absence 和无其他数据库连接的证据来自实际执行通过的测试断言，日志并不逐项打印这些内部值。两次成功方法没有绕过或跳过这些断言。

## 6. 历史 Schema 重建

已实际两次通过：首次 Context 启动完成、静止断言成立后，仅在本类独占 `postgres:17-alpine` 容器中 clean 当前 Schema 并显式迁移到 V15。核对 V15 版本、6 条流水、账户 -60.00、六个 `confirmed_at` 与 Session 预分配 Batch 绑定。

V15→V16 比较 users/accounts/categories/transactions/sessions/items 及既有 Batch 字段全行 JSONB 快照。仅排除 V16 新增的 `session_id`、`result_digest`；随后单独检查新增 Session 绑定、Digest SHA-256 形状、6 条 Backfill Impact 及其完整余额字段。

保留原六个时间输入：秒（无小数）、`.123Z`、`.123456Z`、`.123400Z`、`.123450Z`、`.000001Z`。实际覆盖目标为 PostgreSQL 0/3/6 位和尾零/最小微秒样本，不宣称纳秒全精度。

## 7. V16 负例

已实际两次通过：先断言 `standard_conforming_strings=on`，对未注入的 V16 Backfill index=1 验证存储 Digest 精确等于使用 `.123000Z` 的独立 SHA-256，且不等于 Java canonical `.123Z` Digest；同时明确 index=3 的自然 V16 Digest 在注入前等于 canonical。原 V16 普通字符串的双反斜线与测试 `E'...'` 的解析行为不同，不能把二者当作同一自然输出。该判断最初结合本地 SQL 与 [PostgreSQL 17 字符串规则](https://www.postgresql.org/docs/17/sql-syntax-lexical.html#SQL-SYNTAX-STRINGS-ESCAPE) 推得，现在已由两次实际数据库断言验证。

随后完整保留用户要求的原定向损坏注入：仅临时禁用 Batch immutable trigger，`finally` 恢复；将 trailing-zero fixture index=3 改写为 trimmed `.1234Z` Digest。此项是明确构造的历史错误摘要负例，不能表述为这个 fixture 经原 V16 SQL 自然生成的结果。先断言存储 Digest 不等于独立 Java canonical Digest，再执行唯一历史 Runtime 调用 `getReceipt(1L, batchId(3))`。

生产审计确认 `getReceipt()`→`reconstruct()` 仅读取 Batch、Item、Impact，不访问 V21 Storage Registry、Session 或新金融写路径。Backfill 的 Item 数量、非空交易引用及 Impact 数值由 fixture 与前置断言保证。负例必须是 `BusinessException`、code=500、message=`IMPORT_CONFIRM_INCONSISTENT`；SQL/Storage 异常无法满足断言。负例前后四表 Receipt 快照必须一致。

## 8. V17 修复

已实际两次通过：旧 Context 和全部连接关闭后，显式 target=17；断言 `migrationsExecuted=1`、current version=17、历史表存在唯一成功 V17 记录。两次日志分别在第 142 行记录单独成功执行 1 条迁移至 v17。

分别验证六个存储 Digest 等于 Java canonical Digest。对金融数据、用户/类别、Session、Item、Impact 及 Batch 除 `result_digest` 以外的全部字段比较快照。该断言在 V18–V21 之前完成，不用最终 V21 版本号替代 V17 修复效果。

## 9. V21 最终迁移

已实际两次通过：V17 修复断言完成后再 migrate latest，明确断言 version=`21`，比较完整 Session/Batch/Item/Impact 快照不变。V18 新增交易 `idempotency_key`、`request_hash`，跨版本金融快照仅排除这两个新增列，同时单独断言两列全为 NULL，其余字段保持一致。离线 V21 Registry 必须为空；只有新 Context 的真实 Binding 初始化可以写入 Registry。两次日志分别在第 150 行记录后续 4 条迁移至 v21，新 Context 启动时 Flyway validate 21 条成功且无需进一步迁移。

## 10. Receipt、Replay 与金融快照

已实际两次通过：新 Context 对六个 fixture 逐一 GET Receipt 和同一幂等意图 Confirm Replay。断言确认时间、Session/Batch IDs、交易引用、完整 Impact、行数、createdCount、独立 canonical resultDigest、`idempotentReplay=true`、Replay Receipt 与 GET Receipt 相等及数据库 Digest 一致。

每次 Replay 后比较 accounts/transactions 全行快照（含当前新增列）及 Session/Batch/Item/Impact 四表全行快照，两次执行中的六个 fixture 全部一致。由此证明在指定夹具范围内 Replay 没有产生第二次金融效果，且不可变回执保持不变。负例错误码与各个 Digest 值没有逐项打印到日志；依据是无条件执行的完整测试方法及其通过断言，不将日志没有打印的值伪称为日志直接记录。

## 11. 本轮实际验证

命令均在 `backend` 下执行，`JAVA_HOME=F:/ideajava/jdk21`；没有 Maven clean、skip、Assumption 或 @Disabled。测试内的 Flyway clean 仅针对本类独占容器。

### Docker 恢复后的最终运行证据

Docker Server `29.6.1` 已实际响应。测试镜像 `postgres:17-alpine`，Flyway 日志确认数据库为 PostgreSQL `17.11`。

| 执行 | tests | failures | errors | skipped | 结束时间（+08:00） |
| --- | --- | --- | --- | --- | --- |
| V17 完整类独立执行 1 | 1 | 0 | 0 | 0 | 2026-10-14 16:12:24 |
| V17 完整类独立执行 2 | 1 | 0 | 0 | 0 | 2026-10-14 16:13:17 |
| V21 Migration 完整类 | 2 | 0 | 0 | 0 | 关联合并运行于 16:14:22 结束 |
| B1 FlywayMigration 完整类 | 1 | 0 | 0 | 0 | 同上 |
| B1 Transfer V19 完整类 | 1 | 0 | 0 | 0 | 同上 |
| B1 Import V20 Migration 完整类 | 6 | 0 | 0 | 0 | 同上 |

三个 Maven 命令均 exit=0 / BUILD SUCCESS，耗时分别 32.777s、30.903s、47.212s。两个 V17 执行分别使用 JVM PID `26868`、`14144`，容器 ID `aa95aa9c48d593a27a80dafe8ce6af6e3536f4548b44a37e25cd00261bade700`、`98d2a67a706c707783aa4f7fcdb910d061b4433ad5a774895de3a24eca8fa696`，数据库映射端口分别 9104、3910，排除复用同一 JVM Spring Context 缓存或同一历史数据库。

V17 是同一完整方法两次独立成功执行，不能把这说成两个不同测试方法；其完整类有 1 个方法。四个关联类实际合计 10 个方法全部通过。执行次数与唯一方法数分开，不将重复执行或早前初始化错误累加成新增测试覆盖。

两次 V17 日志有相同阶段证据位置：第 47 行容器 ID；第 97 行首次 current V21；第 130 行重建至 V15；第 135 行单条 V16；第 136–137 行旧连接池关闭；第 142 行单条 V17；第 150 行后续四条至 V21；第 183–184 行新连接池关闭；第 186–189 行最终独占数据库清理；第 190/194 行测试结果。相关类日志第 105/314/390/436 行分别汇总 1/6/2/1 tests，第 440 行总计 10 tests。所有 XML 的测试方法名及 tests/failures/errors/skipped 均与日志核对。

实际证据保存在系统临时目录：`finance-os-b2-1-v17-docker-restored-1.log/.xml`、`finance-os-b2-1-v17-docker-restored-2.log/.xml`、`finance-os-b2-1-related-docker-restored.log` 及四份 `finance-os-b2-1-restored-<完整类名>.xml`。XML 在覆盖前单独保存，不依赖旧 Surefire 结果冒充本轮成功。测试结束后核对 `docker ps --filter ancestor=postgres:17-alpine`，没有本轮残留 PostgreSQL 容器；未删除用户容器或临时目录。

### Docker 恢复前的失败记录（保留）

| 执行 | 实际结果 | 证据性质 |
| --- | --- | --- |
| 原文件 V17 完整类 baseline | 1 test / 0 failures / 1 error / 0 skipped | Docker 初始化失败，未进入测试方法 |
| 修改后前两次 Maven test 尝试 | 编译失败，随后修正 | 没有有效测试数量，不计入 Runtime 覆盖 |
| 修正后 V17 完整类（run3） | 1 test / 0 failures / 1 error / 0 skipped | testCompile 通过，Docker 初始化失败，未进入测试方法 |
| V21 Migration + B1 三个完整类 | 4 tests / 0 failures / 4 errors / 0 skipped | 四类均 beforeAll 初始化错误；不是 10 个业务方法执行完成 |
| 独立 `mvnw.cmd -B test-compile` | BUILD SUCCESS / exit 0 | 首次仅编译通过，结束于 2026-10-14 15:58:23 +08:00 |
| 复审补充后最终源码 `test-compile` | BUILD SUCCESS / exit 0 | 仅编译通过，结束于 2026-10-14 16:03:38 +08:00 |
| 复审补充后最终源码 V17 完整类 | 1 test / 0 failures / 1 error / 0 skipped | Docker 初始化失败，结束于 2026-10-14 16:05:04 +08:00，未进入测试方法 |

四个关联类是 `FlywayTransactionImportVersionTwentyOneMigrationIntegrationTest`、`FlywayMigrationIntegrationTest`、`FlywayTransferVersionNineteenMigrationIntegrationTest`、`FlywayTransactionImportVersionTwentyMigrationIntegrationTest`。Docker 恢复前的 Surefire XML 当时均核对为 tests=1、failures=0、errors=1、skipped=0；框架报告的 class initialization testcase 不能冒充实际方法执行数。这些 XML 后来被恢复后的成功执行覆盖，失败证据仍保留在前述早期日志中。

实际命令：

```powershell
$env:JAVA_HOME = 'F:\ideajava\jdk21'
.\mvnw.cmd -B '-Dtest=FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest' test
.\mvnw.cmd -B '-Dtest=FlywayTransactionImportVersionTwentyOneMigrationIntegrationTest,FlywayMigrationIntegrationTest,FlywayTransferVersionNineteenMigrationIntegrationTest,FlywayTransactionImportVersionTwentyMigrationIntegrationTest' test
.\mvnw.cmd -B test-compile
```

本轮独立输出日志位于系统临时目录：`finance-os-b2-1-baseline.log`、`finance-os-b2-1-v17-run1.log`（首个编译失败）、`finance-os-b2-1-v17-run2.log`（第二个编译失败）、`finance-os-b2-1-v17-run3.log`（编译通过但容器失败）、`finance-os-b2-1-related.log`、`finance-os-b2-1-compile.log`、`finance-os-b2-1-final-compile.log`、`finance-os-b2-1-v17-final.log`。最终编译中其他既有测试的 `ExtendedOpenOption` 内部 API 警告如实保留，没有为消除警告修改其他文件。

环境排查：Docker daemon 起初未运行，尝试启动已安装的 `F:/Apps/DockerDesktop/Docker Desktop.exe`。Docker backend 自身报错 `initializing Inference manager`，`dockerInference` socket 无法访问，随后崩溃。没有删除 socket、修改 Docker 配置、重置 Docker、安装/升级软件或猜测凭据。已询问用户恢复 Docker；只停止了本轮启动并挂起的 `docker info` CLI 客户端，未停止用户 Docker backend 进程。

复审修改后早期最终源码仍因环境失败；用户恢复 Docker 后按原授权继续两次完整类及关联回归，已经取得本节最终成功结果。没有执行完整 Backend：本阶段必要定向验证已完成，其他已知范围外错误未重测或修改，不以定向全绿推断整个 Backend 全绿。

## 12. 独立复审

两名用户指定的 `gpt-6-luna` / `max`，`fork_turns=none`，只读、不修改文件、不运行 Maven/Docker。Digest/数据语义与生命周期职责分别划分。主控验证反馈后，仅在同一授权测试内修正，并各进行一次限定复验；复审不是独立运行 PostgreSQL 的证据。

| 复审员 | 初审及主控核验 | 修复后只读复验 |
| --- | --- | --- |
| `/root/b2_1_digest_review` | P2：原注入为定向合成损坏，不能混同 V16 自然 Backfill；初审静态 HOLD。主控核对 SQL/官方字符串规则并确认其真实性 | 增加自然 V16 毫秒 Digest 精确/不一致断言、注入前尾零样本 canonical 断言，保留原注入；最终静态 GO，运行验收仍 HOLD |
| `/root/b2_1_lifecycle_review` | 初审静态 GO，无 P0/P1；P2：RuntimeContext 构造位于关闭保护之前；P2：随机 HTTP 端口监听。主控核对代码确认两项存在 | 构造移入 try，构造失败关闭 context、后续失败关闭 runtime，异常传播；失败清理 P2 解决。HTTP 监听按 Known Limitation 收敛，最终静态 GO，运行验收仍 HOLD |

以上表格保留环境恢复前的复验状态。Docker 恢复后没有继续修改测试或发起新代码 Review 轮次，仅由同两名复审员只读核验新的实际日志/XML：

- Digest 复审员确认两次完整目标方法执行、自然 V16 Digest 差异、原注入负例及 500 错误、V17 修复和 V21 Receipt/Replay/金融快照断言均实际通过，认可 B2-1 阶段验收 GO 候选；不关闭 B2，不替代完整 Backend 验收。
- 生命周期复审员确认两次不同 JVM/容器中的旧池关闭早于离线迁移、新 Context/新池初始化和最终关闭，ready/quiescent、scheduler absence、Storage worker/control、无其他数据库连接和新 Binding 隔离断言通过；此前生命周期环境 HOLD 可解除，HTTP 监听 P2 保留。

二者均未运行 Maven/Docker、修改文件或读取/hash 受保护路径；主控实际执行并核对测试结果。

## 13. P0 / P1 / P2 与历史余额限制

授权测试范围未发现新增 P0/P1；两项 P2（自然 Digest 与注入区分、setup 失败清理）已在授权文件修正并获得只读复验。保留一项 P2 Known Limitation：默认 Servlet Context 监听随机 HTTP 端口；本测试没有发送 HTTP 请求，也未共享该 Context，但代码没有禁止外部入站请求。因此不能声称“端点绝对不可进入”。若人工验收将该条件解释为必须禁止任何入站 HTTP，应在授权测试范围内先设计调整并重新验证；本轮不修改生产 Security 或 Web 配置。

已知历史余额夹具限制：原六个 Batch 共用同一账户，账户终态余额 -60.00；V16 根据迁移时账户余额为每个 Batch Backfill `balanceBefore=-50.00`、`delta=-10.00`、`balanceAfter=-60.00`。这不代表六次交易的逐次历史运行余额。该既有夹具适合时间 canonical Digest 修复及 Replay 不产生第二次效果验证，不构成真实逐批次余额审计证据。本轮保留其原意，不重构历史 fixture 或 Migration SQL。

## 14. 未验证事项

- 未执行完整 Backend；没有重测/修改范围外 V20 Runtime 和 C9 真实旧 JAR 夹具测试，不能宣称已知其他错误已消失。
- 未提交/推送，因此没有包含本轮修复的新 SHA 或 Hosted CI 验收，不能宣称远端 CI 全绿。
- 未在其他操作系统、PostgreSQL 小版本或纳秒时间精度上扩大验证；本轮只覆盖上述 Windows + PostgreSQL 17.11、0/3/6 位六个指定样本。
- 未进行历史逐批次运行余额重建、真实外部入站 HTTP 干扰或生产部署验收。

Context、Ready/Cleanup 静止、Storage/pool 关闭、V15→V16→V17→V21、负例/修复、真实新 Binding、六个 Receipt/Replay 与快照、两次独立执行和定向回归现在均有本轮实际通过证据。此前 C1–C11、Batch A/B1 的 CLOSED — GO 为用户给定阶段背景；本轮没有重开它们，也未关闭整个 Batch B2 或授权 Finance Freeze。

## 15. 受保护文件与最终边界

最终 `git status --short` 仅有指定测试 tracked 修改、本 Review 新增和两个原受保护 untracked 路径。`AGENTS.md`、`docs/codex-task.md` 保持原 untracked 路径状态；只核对路径，没有内容/hash 操作。staged diff 为空，HEAD 保持指定 SHA。实际 diff 已检查，`git diff --check` 通过；新增 Review 另行检查尾空白与冲突标记。未 commit、push 或 Tag。

## 16. 下一步与门禁

两次独立 V17 完整类和四个关联完整类已完成，实际 tests/failures/errors/skips、迁移顺序和生命周期证据已核对；没有观察到需要生产修改或扩大范围的设计阻断。

**G4-3 / Batch B2-1 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。**

完成后停止等待人工技术验收。本轮不 commit、push 或 Tag，不关闭整个 Batch B2，不启动 B2-2/Batch C/D，不授权 Finance Freeze。


## 附录：最终实际测试 diff

```diff
diff --git a/backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.java b/backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.java
index 6f9e736..d7f6e49 100644
--- a/backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.java
+++ b/backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.java
@@ -3,39 +3,48 @@ package com.financeos.integration;
 import com.financeos.FinanceOsApplication;
 import com.financeos.common.BusinessException;
 import com.financeos.module.importing.dto.TransactionImportConfirmRequest;
+import com.financeos.module.importing.dto.TransactionImportReceipt;
 import com.financeos.module.importing.service.TransactionImportConfirmService;
+import com.financeos.module.importing.service.TransactionImportPreviewCleanupScheduler;
+import com.financeos.module.importing.service.TransactionImportPreviewCleanupService;
+import com.financeos.module.importing.storage.LocalTemporaryImportFileStorage;
+import com.financeos.module.importing.storage.TrustedImportStorageRoot;
+import com.zaxxer.hikari.HikariDataSource;
+import ch.qos.logback.classic.Level;
+import ch.qos.logback.classic.Logger;
+import ch.qos.logback.classic.spi.ILoggingEvent;
+import ch.qos.logback.core.read.ListAppender;
+import org.slf4j.LoggerFactory;
 import org.flywaydb.core.Flyway;
 import org.flywaydb.core.api.MigrationVersion;
 import org.junit.jupiter.api.AfterEach;
 import org.junit.jupiter.api.Test;
-import org.springframework.beans.factory.annotation.Autowired;
-import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
-import org.springframework.boot.test.context.SpringBootTest;
-import org.springframework.boot.test.context.TestConfiguration;
-import org.springframework.context.annotation.Bean;
-import org.springframework.context.annotation.Import;
+import org.springframework.boot.SpringApplication;
+import org.springframework.boot.context.event.ApplicationReadyEvent;
+import org.springframework.context.ConfigurableApplicationContext;
 import org.springframework.jdbc.core.JdbcTemplate;
-import org.springframework.test.context.ActiveProfiles;
-import org.springframework.test.context.DynamicPropertyRegistry;
-import org.springframework.test.context.DynamicPropertySource;
+import org.springframework.jdbc.datasource.DriverManagerDataSource;
+import org.springframework.test.util.ReflectionTestUtils;
 import org.testcontainers.containers.PostgreSQLContainer;
 import org.testcontainers.junit.jupiter.Container;
 import org.testcontainers.junit.jupiter.Testcontainers;

+import java.math.BigDecimal;
+import java.nio.channels.FileChannel;
 import java.nio.charset.StandardCharsets;
 import java.security.MessageDigest;
 import java.time.Instant;
 import java.util.HexFormat;
 import java.util.List;
 import java.util.UUID;
+import java.util.concurrent.ThreadPoolExecutor;
+import java.util.concurrent.atomic.AtomicInteger;
+import java.util.concurrent.locks.ReentrantReadWriteLock;

 import static org.assertj.core.api.Assertions.assertThat;
 import static org.assertj.core.api.Assertions.assertThatThrownBy;

-@SpringBootTest(classes = FinanceOsApplication.class)
-@ActiveProfiles("test")
 @Testcontainers
-@Import(FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.LegacyV15FlywayConfiguration.class)
 class FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest {
     private static final List<TimestampFixture> TIMESTAMPS = List.of(
             new TimestampFixture("2026-08-01T00:01:00Z"),
@@ -49,54 +58,108 @@ class FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest {
     @Container
     private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

-    @Autowired
+    // Each operation opens/closes its own connection; this is also the offline migration observer.
     private JdbcTemplate jdbcTemplate;

-    @Autowired
-    private TransactionImportConfirmService confirmService;
-
-    @DynamicPropertySource
-    static void configureDataSource(DynamicPropertyRegistry registry) {
-        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
-        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
-        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
-        registry.add("spring.datasource.driver-class-name", POSTGRESQL::getDriverClassName);
-    }
-
     @AfterEach
     void cleanDatabase() {
+        assertNoOtherDatabaseConnections();
         flyway(null).clean();
     }

     @Test
     void forwardFixesV16DigestsForEveryJavaInstantCanonicalPrecisionAndRestoresGetAndReplay() {
-        insertRepresentativeV15Data();
+        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
+                POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword()));
+        List<String> beforeRepair;
+        List<String> oldBindings;
+        try (RuntimeContext legacy = startCurrentContext()) {
+            oldBindings = storageBindingSnapshot();
+            // Startup cleanup has returned. No scheduler or external caller can enter this window.
+            flyway(null).clean();
+            flyway(MigrationVersion.fromVersion("15")).migrate();
+            assertThat(appliedVersion()).isEqualTo("15");
+            insertRepresentativeV15Data();
+            assertV15Fixtures();
+            List<String> v15 = historicalSnapshot(false);

-        flyway(MigrationVersion.fromVersion("16")).migrate();
-        injectLegacyTrimmedDigestForTrailingZeroFixture();
+            flyway(MigrationVersion.fromVersion("16")).migrate();
+            assertThat(appliedVersion()).isEqualTo("16");
+            assertThat(historicalSnapshot(false)).isEqualTo(v15);
+            assertV16Backfill();
+            // V16's ordinary SQL string preserves doubled backslashes: milliseconds stay at six digits.
+            assertThat(jdbcTemplate.queryForObject("SHOW standard_conforming_strings", String.class)).isEqualTo("on");
+            assertThat(storedDigest(1)).isEqualTo(sha256(sessionId(1) + "|" + batchId(1)
+                    + "|2026-08-01T00:01:00.123000Z|" + references(1) + "|" + impacts()));
+            assertThat(storedDigest(1)).isNotEqualTo(canonicalDigest(1));
+            assertThat(storedDigest(3)).isEqualTo(canonicalDigest(3));
+            // Preserve the original separately injected trimmed-timestamp corruption negative case.
+            injectLegacyTrimmedDigestForTrailingZeroFixture();
+            assertThat(storedDigest(3)).isNotEqualTo(canonicalDigest(3));
+            beforeRepair = historicalSnapshot(true);
+            List<String> negativeSnapshot = receiptSnapshot();

-        assertThatThrownBy(() -> confirmService.getReceipt(1L, batchId(3)))
-                .isInstanceOf(BusinessException.class)
-                .hasMessage("IMPORT_CONFIRM_INCONSISTENT");
+            // The only runtime operation permitted on V16: Batch / Item / Impact reads.
+            assertThatThrownBy(() -> legacy.confirmService().getReceipt(1L, batchId(3)))
+                    .isInstanceOfSatisfying(BusinessException.class,
+                            exception -> assertThat(exception.getCode()).isEqualTo(500))
+                    .hasMessage("IMPORT_CONFIRM_INCONSISTENT");
+            assertThat(receiptSnapshot()).isEqualTo(negativeSnapshot);
+            legacy.assertQuiescent();
+        }
+        assertNoOtherDatabaseConnections();

+        var repair = flyway(MigrationVersion.fromVersion("17")).migrate();
+        assertThat(repair.migrationsExecuted).isEqualTo(1);
+        assertThat(appliedVersion()).isEqualTo("17");
+        assertThat(jdbcTemplate.queryForObject(
+                "SELECT count(*) FROM flyway_schema_history WHERE version = '17' AND success", Integer.class)).isEqualTo(1);
+        assertThat(historicalSnapshot(true)).isEqualTo(beforeRepair);
+        for (int index = 0; index < TIMESTAMPS.size(); index++) {
+            assertThat(storedDigest(index)).isEqualTo(canonicalDigest(index));
+        }
+        List<String> repairedReceipts = receiptSnapshot();
+        List<String> financialBeforeLatest = legacyFinancialSnapshot();
         flyway(null).migrate();
+        assertThat(appliedVersion()).isEqualTo("21");
+        assertThat(receiptSnapshot()).isEqualTo(repairedReceipts);
+        // V18 adds two nullable columns to transactions; compare the pre-existing fields.
+        assertThat(legacyFinancialSnapshot()).isEqualTo(financialBeforeLatest);
+        assertThat(jdbcTemplate.queryForObject(
+                "SELECT count(*) FROM transactions WHERE idempotency_key IS NOT NULL OR request_hash IS NOT NULL", Integer.class)).isZero();
+        assertThat(storageBindingSnapshot()).isEmpty();

-        assertThat(appliedVersion()).isEqualTo("20");
-        for (int index = 0; index < TIMESTAMPS.size(); index++) {
-            TimestampFixture fixture = TIMESTAMPS.get(index);
-            UUID batchId = batchId(index);
-            assertThat(Instant.parse(fixture.value()).toString()).isEqualTo(fixture.value());
+        try (RuntimeContext current = startCurrentContext()) {
+            assertThat(storageBindingSnapshot()).doesNotContainAnyElementsOf(oldBindings);
+            var confirmService = current.confirmService();
+            List<String> financialBeforeReplay = financialSnapshot();
+            List<String> receiptsBeforeReplay = receiptSnapshot();
+            for (int index = 0; index < TIMESTAMPS.size(); index++) {
+                TimestampFixture fixture = TIMESTAMPS.get(index);
+                UUID batchId = batchId(index);
+                assertThat(Instant.parse(fixture.value()).toString()).isEqualTo(fixture.value());

-            var receipt = confirmService.getReceipt(1L, batchId);
-            var replay = confirmService.confirm(1L, sessionId(index), "legacy-import-key-" + index,
-                    new TransactionImportConfirmRequest("legacy-replay-token", List.of()));
+                var receipt = confirmService.getReceipt(1L, batchId);
+                var replay = confirmService.confirm(1L, sessionId(index), "legacy-import-key-" + index,
+                        new TransactionImportConfirmRequest("legacy-replay-token", List.of()));

-            assertThat(receipt.confirmedAt()).isEqualTo(Instant.parse(fixture.value()));
-            assertThat(replay.idempotentReplay()).isTrue();
-            assertThat(replay.receipt()).isEqualTo(receipt);
-            assertThat(jdbcTemplate.queryForObject("SELECT result_digest FROM transaction_import_batches WHERE id = ?", String.class, batchId))
-                    .isEqualTo(receipt.resultDigest());
+                assertThat(receipt.confirmedAt()).isEqualTo(Instant.parse(fixture.value()));
+                assertThat(receipt.importSessionId()).isEqualTo(sessionId(index));
+                assertThat(receipt.importBatchId()).isEqualTo(batchId);
+                assertThat(receipt.transactions()).isEqualTo(references(index));
+                assertThat(receipt.accountImpacts()).isEqualTo(impacts());
+                assertThat(receipt.totalRows()).isEqualTo(1);
+                assertThat(receipt.createdCount()).isEqualTo(1);
+                assertThat(receipt.resultDigest()).isEqualTo(canonicalDigest(index));
+                assertThat(replay.idempotentReplay()).isTrue();
+                assertThat(replay.receipt()).isEqualTo(receipt);
+                assertThat(storedDigest(index)).isEqualTo(receipt.resultDigest());
+                assertThat(financialSnapshot()).isEqualTo(financialBeforeReplay);
+                assertThat(receiptSnapshot()).isEqualTo(receiptsBeforeReplay);
+            }
+            current.assertQuiescent();
         }
+        assertNoOtherDatabaseConnections();
     }

     private void insertRepresentativeV15Data() {
@@ -198,12 +261,191 @@ class FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest {

     private record TimestampFixture(String value) { }

-    @TestConfiguration(proxyBeanMethods = false)
-    static class LegacyV15FlywayConfiguration {
-        @Bean
-        FlywayMigrationStrategy migrateOnlyThroughV15() {
-            return flyway -> Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
-                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("15")).load().migrate();
+    private List<TransactionImportReceipt.TransactionReference> references(int index) {
+        return List.of(new TransactionImportReceipt.TransactionReference(1, index + 1L));
+    }
+
+    private List<TransactionImportReceipt.AccountImpact> impacts() {
+        // Historical V16 backfill uses the final account balance for every batch, not a running balance.
+        return List.of(new TransactionImportReceipt.AccountImpact(1L, 1,
+                new BigDecimal("-50.00"), new BigDecimal("-10.00"), new BigDecimal("-60.00")));
+    }
+
+    private String canonicalDigest(int index) {
+        return sha256(sessionId(index) + "|" + batchId(index) + "|" + Instant.parse(TIMESTAMPS.get(index).value())
+                + "|" + references(index) + "|" + impacts());
+    }
+
+    private String storedDigest(int index) {
+        return jdbcTemplate.queryForObject("SELECT result_digest FROM transaction_import_batches WHERE id = ?",
+                String.class, batchId(index));
+    }
+
+    private void assertV15Fixtures() {
+        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(6);
+        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
+                .isEqualByComparingTo("-60.00");
+        for (int index = 0; index < TIMESTAMPS.size(); index++) {
+            Instant confirmedAt = jdbcTemplate.queryForObject("SELECT confirmed_at FROM transaction_import_batches WHERE id = ?",
+                    (row, number) -> row.getTimestamp(1).toInstant(), batchId(index));
+            assertThat(confirmedAt).isEqualTo(Instant.parse(TIMESTAMPS.get(index).value()));
+            assertThat(jdbcTemplate.queryForObject("SELECT preallocated_batch_id FROM transaction_import_sessions WHERE id = ?",
+                    UUID.class, sessionId(index))).isEqualTo(batchId(index));
+        }
+    }
+
+    private void assertV16Backfill() {
+        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_import_batch_account_impacts", Integer.class)).isEqualTo(6);
+        for (int index = 0; index < TIMESTAMPS.size(); index++) {
+            assertThat(jdbcTemplate.queryForObject("SELECT session_id FROM transaction_import_batches WHERE id = ?",
+                    UUID.class, batchId(index))).isEqualTo(sessionId(index));
+            assertThat(storedDigest(index)).matches("[0-9a-f]{64}");
+            assertThat(jdbcTemplate.query("""
+                    SELECT account_id, row_count, balance_before, delta, balance_after
+                    FROM transaction_import_batch_account_impacts WHERE batch_id = ? ORDER BY account_id
+                    """, (row, number) -> new TransactionImportReceipt.AccountImpact(row.getLong(1), row.getInt(2),
+                    row.getBigDecimal(3), row.getBigDecimal(4), row.getBigDecimal(5)), batchId(index)))
+                    .isEqualTo(impacts());
+        }
+    }
+
+    private String snapshotTable(String table, String omittedFields) {
+        return jdbcTemplate.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(row_value)" + omittedFields
+                + " ORDER BY row_value.id), '[]'::jsonb)::text FROM " + table + " row_value", String.class);
+    }
+
+    private List<String> financialSnapshot() {
+        return List.of(snapshotTable("accounts", ""), snapshotTable("transactions", ""));
+    }
+
+    private List<String> legacyFinancialSnapshot() {
+        return List.of(snapshotTable("accounts", ""), snapshotTable("transactions", " - 'idempotency_key' - 'request_hash'"));
+    }
+
+    private List<String> receiptSnapshot() {
+        return List.of(snapshotTable("transaction_import_sessions", ""), snapshotTable("transaction_import_batches", ""),
+                snapshotTable("transaction_import_items", ""), snapshotTable("transaction_import_batch_account_impacts", ""));
+    }
+
+    private List<String> historicalSnapshot(boolean includeImpacts) {
+        var snapshot = new java.util.ArrayList<>(financialSnapshot());
+        snapshot.add(snapshotTable("users", ""));
+        snapshot.add(snapshotTable("categories", ""));
+        snapshot.add(snapshotTable("transaction_import_sessions", ""));
+        snapshot.add(snapshotTable("transaction_import_batches", includeImpacts ? " - 'result_digest'" : " - 'session_id' - 'result_digest'"));
+        snapshot.add(snapshotTable("transaction_import_items", ""));
+        if (includeImpacts) snapshot.add(snapshotTable("transaction_import_batch_account_impacts", ""));
+        return List.copyOf(snapshot);
+    }
+
+    private List<String> storageBindingSnapshot() {
+        return jdbcTemplate.queryForList("""
+                SELECT storage_id::text FROM transaction_import_storage_bindings
+                WHERE NOT quarantined ORDER BY kind
+                """, String.class);
+    }
+
+    private void assertNoOtherDatabaseConnections() {
+        assertThat(jdbcTemplate.queryForObject("""
+                SELECT count(*) FROM pg_stat_activity
+                WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid()
+                """, Integer.class)).isZero();
+    }
+
+    private RuntimeContext startCurrentContext() {
+        AtomicInteger readyEvents = new AtomicInteger();
+        SpringApplication application = new SpringApplication(FinanceOsApplication.class);
+        application.setAdditionalProfiles("test");
+        application.addListeners(event -> {
+            if (event instanceof ApplicationReadyEvent) readyEvents.incrementAndGet();
+        });
+        var context = application.run(
+                "--server.port=0", "--spring.datasource.url=" + POSTGRESQL.getJdbcUrl(),
+                "--spring.datasource.username=" + POSTGRESQL.getUsername(),
+                "--spring.datasource.password=" + POSTGRESQL.getPassword(),
+                "--spring.datasource.hikari.minimum-idle=0", "--spring.datasource.hikari.maximum-pool-size=2");
+        RuntimeContext runtime = null;
+        try {
+            runtime = new RuntimeContext(context, readyEvents);
+            assertThat(context.isActive()).isTrue();
+            assertThat(appliedVersion()).isEqualTo("21");
+            assertThat(storageBindingSnapshot()).hasSize(2);
+            runtime.assertQuiescent();
+            return runtime;
+        } catch (RuntimeException | AssertionError exception) {
+            if (runtime == null) context.close();
+            else runtime.close();
+            throw exception;
+        }
+    }
+
+    private record StorageResources(TrustedImportStorageRoot root, ThreadPoolExecutor workers, FileChannel control) {
+        static StorageResources capture(LocalTemporaryImportFileStorage storage) {
+            var root = (TrustedImportStorageRoot) ReflectionTestUtils.getField(storage, "root");
+            Object state = ReflectionTestUtils.getField(root, "context");
+            return new StorageResources(root, (ThreadPoolExecutor) ReflectionTestUtils.getField(state, "workers"),
+                    (FileChannel) ReflectionTestUtils.getField(state, "control"));
+        }
+    }
+
+    private class RuntimeContext implements AutoCloseable {
+        private final ConfigurableApplicationContext context;
+        private final AtomicInteger readyEvents;
+        private final HikariDataSource pool;
+        private final TransactionImportPreviewCleanupService cleanup;
+        private final List<StorageResources> storage;
+
+        RuntimeContext(ConfigurableApplicationContext context, AtomicInteger readyEvents) {
+            this.context = context;
+            this.readyEvents = readyEvents;
+            this.pool = context.getBean(HikariDataSource.class);
+            this.cleanup = context.getBean(TransactionImportPreviewCleanupService.class);
+            this.storage = context.getBeansOfType(LocalTemporaryImportFileStorage.class).values().stream()
+                    .map(StorageResources::capture).toList();
+            assertThat(storage).hasSize(2);
+        }
+
+        TransactionImportConfirmService confirmService() {
+            return context.getBean(TransactionImportConfirmService.class);
+        }
+
+        void assertQuiescent() {
+            // Default event multicaster is synchronous: run() returns after startup cleanup.
+            assertThat(readyEvents.get()).isEqualTo(1);
+            assertThat(context.getBeansOfType(TransactionImportPreviewCleanupScheduler.class)).isEmpty();
+            assertThat(context.containsBean("transactionImportPreviewCleanupTaskScheduler")).isFalse();
+            var lock = (ReentrantReadWriteLock) ReflectionTestUtils.getField(cleanup, "cleanupLifecycleLock");
+            assertThat(lock.getReadLockCount()).isZero();
+            assertThat(lock.isWriteLocked()).isFalse();
+            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
+            for (StorageResources resource : storage) {
+                assertThat(resource.workers().getActiveCount()).isZero();
+                assertThat(resource.workers().getQueue()).isEmpty();
+            }
+        }
+
+        @Override
+        public void close() {
+            Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
+            ListAppender<ILoggingEvent> shutdownLogs = new ListAppender<>();
+            shutdownLogs.start();
+            logger.addAppender(shutdownLogs);
+            try {
+                context.close();
+            } finally {
+                logger.detachAppender(shutdownLogs);
+                shutdownLogs.stop();
+            }
+            assertThat(shutdownLogs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList()).isEmpty();
+            assertThat(context.isActive()).isFalse();
+            assertThat(pool.isClosed()).isTrue();
+            for (StorageResources resource : storage) {
+                assertThat(ReflectionTestUtils.getField(resource.root(), "closed")).isEqualTo(true);
+                assertThat(resource.workers().isTerminated()).isTrue();
+                assertThat(resource.control().isOpen()).isFalse();
+            }
+            assertThat(readyEvents.get()).isEqualTo(1);
+            assertNoOtherDatabaseConnections();
         }
     }
 }
```
