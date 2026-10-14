# G4-3 / Batch B1：Flyway Version Boundary Repair Review

日期：2026-10-13。仅授权 B1 最小测试修复、定向 PostgreSQL 测试与两名 Luna Max 只读复审；未 commit、push 或 tag。

## 1. 结论与范围

授权的三个修改类分别全类通过（1、1、6 tests），既有 V21 迁移类全类通过（2 tests）；两名 GPT-6 Luna Max 对 B1 修改范围均给出 GO。两个直接关联的未修改旧版本运行时升级类仍各有 1 个 ApplicationContext error，不能宣称关联回归全部通过或 Backend 全绿。

**G4-3 / Batch B1 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。**

本轮结论仅限B1实施验收候选范围，不宣称B1已提交或正式关闭。完整Backend仍为失败，整体GitHub Actions仍保持原指定Run的FAILURE。

## 2. Preflight 和保护边界

| 项目 | 本轮实际核验 |
| --- | --- |
| 仓库 / 分支 | F:/IDEA/daima/finance-os / zh-cn |
| 指定 / 实际 HEAD | 974cf17ebf6ec3f9c8689b73b5258cc65d8d0bc2 |
| 本地 origin/zh-cn | 同一 SHA |
| 独立 git ls-remote origin refs/heads/zh-cn | 同一 SHA |
| 初始 tracked / staged diff | 均为空 |
| 初始 untracked | 仅 AGENTS.md、docs/codex-task.md |

受保护路径始终只检查 Git 路径状态，没有读取内容或 hash，没有修改、删除、暂存或提交。仓库指令依据用户聊天中提供的规则；未打开受保护 AGENTS.md。没有破坏性 Git 操作、分支、worktree、提交、推送或 tag。

## 3. 五个原始失败方法及根因

已通过 GitHub API 核对 [CI Run 38404419766](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38404419766) 和 [Backend Job 115320155535](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38404419766/job/115320155535)：head_sha 为指定 HEAD，run_attempt=1，Backend failure。远端汇总 552 tests、6 failures、13 errors、1 skipped 是该 Run 的真实基线，不冒充本轮修复后结果。

五项在该 Job 原始日志中均明确为 expected "20" / actual "21"：

| 原始方法 | 原始日志行 | 根因与本轮处理 |
| --- | --- | --- |
| FlywayMigrationIntegrationTest.migratesAnEmptyPostgresDatabaseThroughVersionTwenty | 3461–3468 | Spring Boot 自动迁移 current latest=21，测试末版本仍写20；改名 ThroughVersionTwentyOne 并明确断言21，保留原历史迁移和 Schema 断言 |
| FlywayTransferVersionNineteenMigrationIntegrationTest.upgradesVersionEighteenAndEnforcesOwnedTransferFacts | 11097–11104 | 原18后调用无target迁移直达21却断言20；改为先18→19验证规则，再19→latest21验证规则延续与数据保持 |
| FlywayTransactionImportVersionTwentyMigrationIntegrationTest.upgradesV19WithoutChangingHistoricalReceiptsAndEnforcesTheNewLifecycleBoundary | 11933–11940 | V20专项调用latest；显式target20，原V20断言及所有业务断言保留 |
| 同类 freshV1MigrationAppliesThroughV20WithTheExpectedReceiptConstraintsAndTriggers | 11921–11928 | 新空库专项未限制target；显式target20，保留V20 Schema/Trigger/FK断言 |
| 同类 migrationHoldsBothTableLocksAcrossTheTriggerToFkCutoverAndQueuesConcurrentWrites | 11909–11916 | 受控V20切换之后继续迁移到21；只将异步迁移target限制20，保留所有阻塞、锁、并发写和SQLState断言 |

修改前在本机隔离 Testcontainers 中运行以上三个完整类，得到 8 tests、5 failures、0 errors、0 skips，五项逐一复现同一错误。没有把此失败误判为生产 Migration SQL 缺陷。

## 4. 精确修改及 V20 / V21 区分

仅修改：

1. backend/src/test/java/com/financeos/integration/FlywayMigrationIntegrationTest.java：+2 / -2。
2. backend/src/test/java/com/financeos/integration/FlywayTransferVersionNineteenMigrationIntegrationTest.java：+44 / -18。
3. backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyMigrationIntegrationTest.java：+3 / -3。

新增文档仅本 Review。三个测试文件合计 +49 / -23；完整实际 diff 将附于本报告末尾。

### current latest 空库验证

Spring Boot 迁移仍为当前 latest，不增加历史 target。方法名和终态断言明确 V21。原 16 个指定历史业务表的查询并非所有public表总数，其16断言保留；原 V19/V20 已执行断言、其他迁移历史、金额精度、列、约束及索引不变。V21新登记表的独立约束由既有 V21 专项类覆盖。

### V19 规则延续性

- 先迁至18并创建原 users/accounts fixture。
- 再显式迁至19，插入原合法转账、核对版本19，执行原四项非法转账断言。
- 保存 accounts/transfers 的全部行 JSONB 快照（排序固定，包含余额、用户归属、金额、currency、时间和全部其他字段）。
- 再迁至current latest并断言21。
- 比较全部行快照一致，重新执行同一组原四项非法转账断言。

原四项断言仅提取为私有 helper 供两个阶段复用，仍要求 SQLException：同账户转账、跨用户目标账户、零金额、USD转账全部拒绝。合法历史转账跨升级保持，不用空表或简单版本号替代业务验证。

### V20 专项

只在指定三个原失败方法将 flyway(null).migrate() 改为 flyway(MigrationVersion.fromVersion("20")).migrate()。不修改任何预期20、不修改Schema helper、不修改其他三个方法。反向替换这三处后，该测试文件与HEAD全文一致，六个方法集合一致。

两项异常迁移方法仍在V20失败并回滚，另一项锁顺序回归仍运行于latest。没有全局替换20→21，也没有删除测试、@Disabled、mock PostgreSQL、修改SQL或禁用Flyway validate。target20的实际日志仍先validate21个迁移，再只apply至20。

## 5. 保留的重要断言

- V19/V20已执行历史记录与既有Schema、数值精度、索引、FK/Check约束。
- V19合法转账及四项非法转账在历史/最新两个阶段的校验；accounts/transfers全部行跨V21不变。
- V20 ReceiptSnapshot仍比较 Session、Batch、Item、Account Impact 四表全部行，不缩成行数或空表。
- created_transaction_id NOT NULL、owned Batch FK保留、owned Transaction旧FK移除。
- BEFORE INSERT验证Trigger启用，Receipt不可变Trigger仍启用；函数仍包含FOR KEY SHARE及user/id归属校验。
- 非法历史reference拒绝、function/trigger/FK切换失败回滚，版本仍19且不留下V20历史记录。
- SQLState 23503、23514及repeatable-read的40001/23503原样保留。
- 两表ACCESS EXCLUSIVE锁、pg_blocking_pids阻塞关系、Future未完成，以及Trigger→FK切换期间INSERT/DELETE等待。
- 门闩释放后非法跨用户INSERT拒绝、DELETE成功、Transaction删除但Item/Receipt保存。
- INSERT-first、DELETE-first、DELETE rollback、repeatable-read并发与数据断言均保留。

## 6. 本轮定向测试

环境：本机Java21.0.10、Maven3.9.9、Docker Desktop29.6.1；Testcontainers1.20.0，镜像postgres:17-alpine，日志确认实际PostgreSQL17.11；Flyway10.20.0由本轮XML classpath及pom对应依赖确认。使用新隔离容器及随机端口的test数据库，不连接日常finance_os。

首次mvnw -version发现当前进程JAVA_HOME缺失；仅给执行进程设置已有JDK路径F:/ideajava/jdk21后成功。此为环境配置，未改系统或仓库配置；后续基线及所有测试均实际执行。没有新增依赖或真实付费Provider调用。

三个修改类按以下顺序分别执行完整类，均mvn exit0：

| 类 | tests | PASS | FAIL | ERROR | SKIP | 真实版本路径 |
| --- | --- | --- | --- | --- | --- | --- |
| FlywayMigrationIntegrationTest | 1 | 1 | 0 | 0 | 0 | 新空库→V21；日志apply21份迁移 |
| FlywayTransferVersionNineteenMigrationIntegrationTest | 1 | 1 | 0 | 0 | 0 | 空库→18→19→latest21；日志后两段分别apply1、2份迁移 |
| FlywayTransactionImportVersionTwentyMigrationIntegrationTest | 6 | 6 | 0 | 0 | 0 | 三个专项真停20；异常路径真停19；行锁回归保留latest21 |

随后分别执行既有V21及两个直接关联的历史升级类：

| 类 | tests | PASS | FAIL | ERROR | SKIP | 真实结果 |
| --- | --- | --- | --- | --- | --- | --- |
| FlywayTransactionImportVersionTwentyOneMigrationIntegrationTest | 2 | 2 | 0 | 0 | 0 | 真空库→21、真20→21；registry constraints、财务与Receipt全行快照保持、legacy reference保留、Flyway validate均通过 |
| FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest | 1 | 0 | 0 | 1 | 0 | ApplicationContext初始化失败；未进入方法后的业务迁移/Replay验收 |
| FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest | 1 | 0 | 0 | 1 | 0 | ApplicationContext初始化失败；未进入方法后的Receipt修复验收 |

定向修复后六类合计12 tests：10 PASS、0 FAIL、2 ERROR、0 SKIP。授权三类及V21四类合计10/10通过；不称六类全部通过。基线、定向和后续完整执行的次数分开，不累加成唯一测试数量。

两个未修改旧版本运行时类的直接异常均为 PostgreSQL relation "transaction_import_storage_bindings" does not exist：应用上下文按其历史target建库，Storage初始化需要V21 registry。不是外部网络/TLS问题；不通过修改正确生产行为或历史迁移SQL解决，保留给未授权后续范围。没有重新打开C9/A/C11。

命令均在backend执行（分别独立调用，每次selector是完整类）：

    $env:JAVA_HOME = 'F:/ideajava/jdk21'
    .\mvnw.cmd -B "-Dtest=<完整类名>" test

六类selector为上表实际类名；此前baseline selector为三个修改类以逗号连接。没有只选五个失败方法冒充全类验证，没有clean删除既有target历史证据。

## 7. 两名 Luna Max 独立只读复审

按用户指定，实际调度两名gpt-6-luna / max，fork_turns=none；均只读、不改文件、不运行Maven/Docker，职责不重叠核心修改。主控统一核对生产SQL语义、实际diff、测试XML和最终门禁。

| Agent | 职责 | 独立结论 |
| --- | --- | --- |
| /root/b1_migration_review | V19/V20/V21迁移语义、历史Schema与数据不变量 | 授权三文件范围GO；未见P0/P1或范围内P2，版本阶段清晰且历史不变量未削弱；关联旧版本运行时错误不能解释为整体GO |
| /root/b1_test_fidelity_review | 测试保真、锁/并发、Receipt、真实PostgreSQL证据与范围 | B1三类修复GO；六方法集合和锁/回滚/SQLState/四表Receipt快照保持，实际三类全绿；关联两类仍ERROR，整体Backend不能判全绿 |

复审员直接读取本轮外部日志/XML核对结果，不将其阅读证据称为复审独立实跑。复审结论形成时完整Backend仍执行中；完整结果由主控在最终报告核验。

## 8. 本轮完整 Backend 实际结果

定向验证后执行 .\mvnw.cmd -B test，没有clean、跳过参数、测试过滤或修复其他类。真实退出码1，BUILD FAILURE，耗时08:45；结束时间2026-10-13 22:44:10（Asia/Shanghai）。

Maven汇总与仅复制本轮更新的81份XML独立汇总一致：

| tests | PASS | FAIL | ERROR | SKIP |
| --- | --- | --- | --- | --- |
| 562 | 557 | 0 | 5 | 0 |

PASS按tests减FAIL/ERROR/SKIP计；并非562全部通过。完整运行中的三个B1类和V21类再次分别为1、1、6、2 tests，均0 failure/error/skip。

剩余错误逐项真实保留：

| 类 / 方法 | 本轮结果 | 已确认直接原因 |
| --- | --- | --- |
| FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.forwardFixesV16DigestsForEveryJavaInstantCanonicalPrecisionAndRestoresGetAndReplay | 1 ERROR | 旧Schema的应用上下文初始化需要transaction_import_storage_bindings，表不存在 |
| FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest.v19ReceiptSurvivesV20UpgradeDeleteAndMockMvcReplayWithoutAnotherFinancialEffect | 1 ERROR | 同类旧Schema/当前Registry初始化冲突 |
| TransactionImportLegacyUpgradePostgresIntegrationTest.officialProvisioningRefusesRealNonemptyV20RootsWithoutMutatingLegacyEvidence | 1 ERROR | c9.legacy.evidence must point to owned acceptance artifacts |
| 同类 migratedLegacyCancelAndExpiryKeepDurableRetryUntilTheActualGuardAllowsCleanup | 1 ERROR | 同一验收fixture参数缺失 |
| 同类 genuineV20ActiveSessionsSurviveExplicitOfflineMigrationMappingConfirmReceiptAndReplay | 1 ERROR | 同一验收fixture参数缺失 |

后二组共三个错误发生在测试fixture初始化，不代表本轮发现C9生产行为回归；没有猜测或补造既有验收artifact，也没有为全绿修改该类。前两个错误不能归为外部环境/下载故障，本轮只定位直接原因并保留，不继续未经授权的整改。

远端Ubuntu基线552与本轮Windows完整执行562属于不同环境的实际统计；本轮没有完成跨环境逐用例对照，未确认总数差异来源，不通过两次总数相减推算修复覆盖。五个B1失败修复的依据是同机三个完整类的8/5失败→8/0失败，以及完整运行中再次通过，不是跨环境总数差。

## 9. P0/P1/P2 与未验证事项

- B1授权修改范围：主控复核未发现新增P0/P1/P2；两名只读复审均未提出该范围的阻断finding。
- 整体Backend：NO-GO，五个实际ERROR保持；整体GitHub Actions仍FAILURE。
- 未验证：未提交/推送，因此没有包含B1修改的新SHA或新Hosted Runner验收；不能声称远端五个失败已绿。
- 未验证：两个旧版本Runtime类因初始化错误而没有进入后续Receipt修复/Replay业务断言；不能把V21专项成功替代这些场景。
- 未验证：缺少c9.legacy.evidence的三项历史真实旧JAR验收没有在本次完整运行中完成业务路径。此前C9历史成功证据不是本轮PASS。
- 未验证：本轮完整套件与原UbuntuCI总测试数差异没有逐项追溯。
- 本轮仅调整测试版本边界，不修改金融规则、账户余额逻辑、事务生产行为、V1–V21 SQL、pom.xml、CI Workflow或任何其他测试。
- Batch B2/C/D、其他独立风险及Finance Freeze保持OPEN；C1–C11与Batch A维持用户确认的CLOSED — GO。

## 10. 最终 Git 状态及结论

HEAD和分支保持指定值；没有commit、push、tag、amend或任何破坏性Git操作。staged diff为空。最终tracked修改精确为三个授权测试文件；新增仅本Review。两个受保护路径仍为原有untracked状态，仅作Git路径核对：

    ?? AGENTS.md
    ?? docs/codex-task.md

git diff --check通过，V20文件仅有Windows LF/CRLF转换提示。Review也单独检查尾空白/冲突标记与本地链接。没有读取或hash两个受保护文件。

**G4-3 / Batch B1 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。**

这是B1最小测试修复的实施验收候选，不是完整Backend恢复绿灯，不是Git提交授权。后续只有人工确认才能进入精确提交与远端验收；本轮立即停止。

保持：C1–C11 CLOSED — GO（11/11）、Batch A CLOSED — GO、整体G4-3 OPEN、整体GitHub Actions FAILURE、Batch B2/C/D未授权、Finance Final Stabilization、Finance Freeze未授权。

## 11. 本轮可核验证据

仓库外证据目录：[B1证据目录](F:/Downloads/finance-os-g4-3-b1-20261013-be80ca32430d49299692c6a0191fd626)。

- environment.txt：本轮Maven/Java/Docker版本命令输出。
- baseline.log、baseline-xml：修改前完整三类8 tests与五个版本失败。
- 六个同名类.log、各类-xml目录、directed-results.json：分别独立执行的全部类结果。
- backend-full.log、backend-full-xml、full-results.json、full-totals.json：本轮完整运行、仅本轮更新XML及独立统计。
- scope-validation.json：精确授权范围、staged为空、V20三个调用之外全文一致、六方法集合保持及定向结果。
- test-files.diff：三个修改测试的实际完整diff，与下列附录一致。

这些日志/XML只构成本轮实际证据，没有把历史target报告算入本轮；构件、日志和任何凭据均不提交。

## 附录：三个测试文件完整实际 diff

下列hunk保留实际改动；Markdown展示去掉空context行的尾空白。字节级完整diff保存在test-files.diff，未以附录排版替代Git核验。


```diff
diff --git a/backend/src/test/java/com/financeos/integration/FlywayMigrationIntegrationTest.java b/backend/src/test/java/com/financeos/integration/FlywayMigrationIntegrationTest.java
index 73ea710..3d4ae77 100644
--- a/backend/src/test/java/com/financeos/integration/FlywayMigrationIntegrationTest.java
+++ b/backend/src/test/java/com/financeos/integration/FlywayMigrationIntegrationTest.java
@@ -37,7 +37,7 @@ class FlywayMigrationIntegrationTest {
     }

     @Test
-    void migratesAnEmptyPostgresDatabaseThroughVersionTwenty() {
+    void migratesAnEmptyPostgresDatabaseThroughVersionTwentyOne() {
         Integer applicationTableCount = jdbcTemplate.queryForObject("""
                 SELECT count(*)
                 FROM information_schema.tables
@@ -208,7 +208,7 @@ class FlywayMigrationIntegrationTest {
                 """, String.class);

         assertThat(applicationTableCount).isEqualTo(16);
-        assertThat(latestSuccessfulVersion).isEqualTo("20");
+        assertThat(latestSuccessfulVersion).isEqualTo("21");
         assertThat(jdbcTemplate.queryForObject("""
                 SELECT count(*) FROM flyway_schema_history
                 WHERE version = '20' AND description = 'decouple import receipt transaction lifecycle' AND success = true
diff --git a/backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyMigrationIntegrationTest.java b/backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyMigrationIntegrationTest.java
index 2095bb7..07a0310 100644
--- a/backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyMigrationIntegrationTest.java
+++ b/backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyMigrationIntegrationTest.java
@@ -53,7 +53,7 @@ class FlywayTransactionImportVersionTwentyMigrationIntegrationTest {
             before = receiptSnapshot(connection);
         }

-        flyway(null).migrate();
+        flyway(MigrationVersion.fromVersion("20")).migrate();

         try (Connection connection = connection()) {
             assertThat(appliedVersion(connection)).isEqualTo("20");
@@ -74,7 +74,7 @@ class FlywayTransactionImportVersionTwentyMigrationIntegrationTest {

     @Test
     void freshV1MigrationAppliesThroughV20WithTheExpectedReceiptConstraintsAndTriggers() throws Exception {
-        flyway(null).migrate();
+        flyway(MigrationVersion.fromVersion("20")).migrate();

         try (Connection connection = connection()) {
             assertThat(appliedVersion(connection)).isEqualTo("20");
@@ -181,7 +181,7 @@ class FlywayTransactionImportVersionTwentyMigrationIntegrationTest {
             installMigrationPauseEventTrigger(pauseGate);
             eventTriggerInstalled = true;

-            migration = executor.submit(() -> flyway(null).migrate());
+            migration = executor.submit(() -> flyway(MigrationVersion.fromVersion("20")).migrate());
             int migrationPid = awaitMigrationLock();
             int gatePid = scalarInt(pauseGate, "SELECT pg_backend_pid()");
             awaitCondition(() -> blockedBy(migrationPid, gatePid), "V20 migration paused after creating its INSERT trigger");
diff --git a/backend/src/test/java/com/financeos/integration/FlywayTransferVersionNineteenMigrationIntegrationTest.java b/backend/src/test/java/com/financeos/integration/FlywayTransferVersionNineteenMigrationIntegrationTest.java
index d5b3e95..6f1a317 100644
--- a/backend/src/test/java/com/financeos/integration/FlywayTransferVersionNineteenMigrationIntegrationTest.java
+++ b/backend/src/test/java/com/financeos/integration/FlywayTransferVersionNineteenMigrationIntegrationTest.java
@@ -41,31 +41,57 @@ class FlywayTransferVersionNineteenMigrationIntegrationTest {
                     """);
         }

-        flyway(null).migrate();
+        flyway(MigrationVersion.fromVersion("19")).migrate();

+        String historicalFacts;
         try (Connection connection = connection()) {
             connection.createStatement().execute("""
                     INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, description, transacted_at)
                     VALUES (1, 1, 2, 10.00, 'CNY', 'valid transfer', CURRENT_TIMESTAMP)
                     """);

-            assertThat(appliedVersion(connection)).isEqualTo("20");
-            assertThatThrownBy(() -> connection.createStatement().execute("""
-                    INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
-                    VALUES (1, 1, 1, 10.00, 'CNY', CURRENT_TIMESTAMP)
-                    """)).isInstanceOf(SQLException.class);
-            assertThatThrownBy(() -> connection.createStatement().execute("""
-                    INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
-                    VALUES (1, 1, 3, 10.00, 'CNY', CURRENT_TIMESTAMP)
-                    """)).isInstanceOf(SQLException.class);
-            assertThatThrownBy(() -> connection.createStatement().execute("""
-                    INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
-                    VALUES (1, 1, 2, 0.00, 'CNY', CURRENT_TIMESTAMP)
-                    """)).isInstanceOf(SQLException.class);
-            assertThatThrownBy(() -> connection.createStatement().execute("""
-                    INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
-                    VALUES (1, 1, 2, 10.00, 'USD', CURRENT_TIMESTAMP)
-                    """)).isInstanceOf(SQLException.class);
+            assertThat(appliedVersion(connection)).isEqualTo("19");
+            assertOwnedTransferConstraints(connection);
+            historicalFacts = transferFacts(connection);
+        }
+
+        flyway(null).migrate();
+
+        try (Connection connection = connection()) {
+            assertThat(appliedVersion(connection)).isEqualTo("21");
+            assertThat(transferFacts(connection)).isEqualTo(historicalFacts);
+            assertOwnedTransferConstraints(connection);
+        }
+    }
+
+    private void assertOwnedTransferConstraints(Connection connection) {
+        assertThatThrownBy(() -> connection.createStatement().execute("""
+                INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
+                VALUES (1, 1, 1, 10.00, 'CNY', CURRENT_TIMESTAMP)
+                """)).isInstanceOf(SQLException.class);
+        assertThatThrownBy(() -> connection.createStatement().execute("""
+                INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
+                VALUES (1, 1, 3, 10.00, 'CNY', CURRENT_TIMESTAMP)
+                """)).isInstanceOf(SQLException.class);
+        assertThatThrownBy(() -> connection.createStatement().execute("""
+                INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
+                VALUES (1, 1, 2, 0.00, 'CNY', CURRENT_TIMESTAMP)
+                """)).isInstanceOf(SQLException.class);
+        assertThatThrownBy(() -> connection.createStatement().execute("""
+                INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, currency, transacted_at)
+                VALUES (1, 1, 2, 10.00, 'USD', CURRENT_TIMESTAMP)
+                """)).isInstanceOf(SQLException.class);
+    }
+
+    private String transferFacts(Connection connection) throws SQLException {
+        try (var resultSet = connection.createStatement().executeQuery("""
+                SELECT jsonb_build_object(
+                    'accounts', (SELECT jsonb_agg(to_jsonb(account) ORDER BY id) FROM accounts account),
+                    'transfers', (SELECT jsonb_agg(to_jsonb(transfer) ORDER BY id) FROM transfers transfer)
+                )::text
+                """)) {
+            assertThat(resultSet.next()).isTrue();
+            return resultSet.getString(1);
         }
     }
```
