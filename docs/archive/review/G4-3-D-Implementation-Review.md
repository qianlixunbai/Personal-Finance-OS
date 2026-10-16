# G4-3 / Batch D Implementation Review

日期：2026-10-15（Asia/Shanghai）。范围仅为 Batch D 的 Legacy Upgrade 验收恢复；没有 commit、push、tag，不关闭 G4-3，不执行 Finance Freeze 或 Workspace 迁移。

## 1. 结论与证据边界

**IMPLEMENTED / REMOTE VALIDATION REQUIRED**。

本轮实际执行结果：Windows 三个 Legacy 方法 **3/0/0/0**，本地 Ubuntu 三个 Legacy 方法 **3/0/0/0**；Windows 七类关联回归 **37/0/0/0**；Windows 完整 Backend **562/0/0/0**，Maven exit 0。四批的独立 XML 求和分别与各自 Maven 最终统计一致；完整 Backend 的 81 份 XML 不含前次运行残留。数字顺序均为 tests/failures/errors/skipped。

两名指定的 GPT-6 Luna / Max 只读复审均给出范围内静态 GO。没有未解决的 P0/P1；未报告本轮范围内 P2。当前结论是本地实施结果与远端待验证状态，不宣称 Batch D、G4-3、Final Stabilization 或 Finance Freeze 已关闭。

## 2. Preflight、授权与受保护文件

实际分支为 `zh-cn`。开始与结束核对的 HEAD、本地 `origin/zh-cn` 和独立 `git ls-remote origin refs/heads/zh-cn` 均为：

`7bed6bedb596574746359e7e40b6a6e9e3d4b4c7`。

初始 tracked/staged diff 为空，仅有预期的 `?? AGENTS.md` 和 `?? docs/codex-task.md`。没有无关工作区修改。规则依据用户在聊天中提供的仓库指令与粘贴任务，未读取受保护文件内容。

过程偏差如实记录：首轮工具批次尚未读到附件保护要求时，执行过根目录 `AGENTS.md` 的目录元数据列举和文件名发现；未读其内容、计算 hash 或复制。随后对两路径仅执行 Git 路径状态检查，未修改、删除、暂存或提交。两名 Reviewer 均遵守只通过 Git 路径状态确认的要求。结束时两路径仍为原来的 `??`；只能据路径状态与本轮操作记录确认，未用内容/hash比较它们。

没有执行 reset、stash、clean、rebase、checkout 覆盖、amend、force push、分支或 worktree 操作；没有操作 Local AI Assistant 或 Personal AI Workspace。

## 3. 原 CI 三个 Error 与前置根因

本轮重新读取了 [Ubuntu CI Run 38744237663](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38744237663) 的真实失败日志，并核对其 headSha 等于上述 SHA。历史统计是 **562 tests / 0 failures / 3 errors / 1 skipped**。

三个 Error 均为本报告第 7 节列出的方法，在 `@BeforeEach` 中因 `c9.legacy.evidence must point to owned acceptance artifacts` 提前失败。继续核源码发现还缺固定真实 V20 JAR，以及能够在 `clean test` 后存在的当前生产 JAR。原 `currentJar()` 写死 `target/finance-os-backend-1.0.0.jar`，会被 clean 删除。因此只补 evidence 属性不足以恢复验收。

原 CI 统计和本文本轮运行统计分开保存，原 CI 的 skipped 没有被冒充为本轮结果。

## 4. 固定 V20 历史源码与独立构件

| 核验项 | 实际结果 |
| --- | --- |
| 固定 V20 commit | `138bccc2bcb2d5bfea17ea88ecc115604928a5be` |
| backend tree | `f182075fc6231580d8b06694b6143f6b07426bf3` |
| 历史关系 | `git merge-base --is-ancestor <V20> HEAD` 成功 |
| Migration | 恰好 V1–V20；与当前同版本 Git blob 一致；没有 V21 |
| 真实实现 | Preview Controller、Mapping、Session、冻结 Plan、旧 LocalTemporaryImportFileStorage；两旧引用字段均存在 |
| 旧根路径 | `java.io.tmpdir/finance-os/transaction-import-preview` 和 `transaction-import-preview-plan` |
| V21 独立性 | 旧 JAR 没有 ImportStorageProvisioner，不依赖 Registry/Binding 初始化 |
| 构建 | 仓库外 `git archive <fixed commit> backend`，Java 21，历史 Maven Wrapper，`-DskipTests clean package` |
| 生产入口 | Main-Class = Boot JarLauncher，Start-Class = FinanceOsApplication；实际历史 JVM 启动并经 HTTP 生成两种 Session |

这是 C9 原验收使用的真实 V20 提交，不是 B2-2 的 V19 构件，也未人工拼接源码。新 helper 延续 HistoricalV19Artifact 的源文件 Git blob、源清单、打包类/资源与该次 target/classes、Manifest、Migration 集合和 SHA 校验模式，未修改已关闭的 B2 合同；额外核对祖先关系、同版本 Migration 一致性、Java 21 class major 和禁止 V21 Provisioner。

`c9.legacy.jar` 必须有相邻 `<jar>.identity.properties`；测试重新校验 commit/tree/source/JAR 内容和 SHA。源与 JAR 均必须位于当前仓库之外。缺失时失败，不跳过，不回退。

| 实际构件 | SHA-256 |
| --- | --- |
| Windows V20 | `c226a387a53769dbaa5d975ddd0d88f93150b1cb05a43b67baaba0bb5020ac65` |
| Windows 当前 V21 | `4d55d90ee0916126672801b677e857139dce6e7612b58f04659107f5af2bfbc5` |
| 本地 Ubuntu V20 | `de2835fdede1a5e1cc0a4d42d6bf7241a824aff9ce7598b52dc0060a4ea17744` |
| 本地 Ubuntu 当前 V21 | `5072543f7c3c88ae8da4519be8a9560297a757d1338df3168f68a58128e969f7` |

这些 SHA 是本次各 OS 构建的实际值，不是跨 OS 常量。历史独立打包使用 skipTests，不代表历史测试已运行；历史业务证据来自本轮真实 JAR HTTP 升级测试。

9 项独立负向探针实际全部通过：缺少属性、缺少 JAR、错 commit、错 SHA、V21 替代、V19 替代、错误启动入口、移除 V20 migration、篡改 Session class 均被拒绝。探针独立于 JUnit 统计，不加入 562 个测试。

## 5. 当前 JAR 复用

没有修改 CurrentManagedProcessArtifact。Windows 和本地 Ubuntu 各自只构建一份本轮 HEAD 当前生产 JAR，复制到各自仓库外目录后登记身份；Batch D 的 Provisioner 与当前应用均共用 `verifiedCurrentJar`，由现有 `FINANCE_OS_C9_CURRENT_JAR` 与 `CurrentManagedProcessArtifact.required()` 提供。

既有 helper 仍核验 HEAD 的生产输入、SHA、Manifest、两个 Boot launcher、生产应用/Provisioner、完整类资源清单和 V1–V21，外部副本不受后续 clean 删除。Windows 关联回归及完整 Backend 的 Batch C 同样使用这一份外部当前 JAR。没有为 Batch D 单独重复构建当前 JAR，也没有使用旧 Batch C HEAD 的旧构件冒充本轮构件。

## 6. 实际修改范围与 CI

仅修改/新增四个文件：

| 文件 | 作用 |
| --- | --- |
| `backend/src/test/java/com/financeos/integration/TransactionImportLegacyUpgradePostgresIntegrationTest.java` | 接入 V20/当前构件、证据根、迁移/停机证据、脱敏，保留三个真实业务测试 |
| `backend/src/test/java/com/financeos/integration/historical/HistoricalV20Artifact.java` | 新增专用 test-only V20 构件身份 gate |
| `.github/workflows/ci.yml` | 仅 Backend job 增加 V20 前置构建、独占证据目录、显式 Surefire 属性和失败诊断 |
| 本文 | 中文实施与验收记录，附完整实现 diff |

生产 Java、V1–V21 SQL、POM、前端及其他测试未改。V19/current 原构建步骤逐项结构比较完全一致；其他 CI jobs 结构完全一致。Backend checkout 改为完整历史，供祖先关系校验；新增 V20 step 在 RUNNER_TEMP 独立构建。既有 Backend timeout 20 分钟保持不变，本轮没有远端超时证据，不凭推测放宽。

完整 Backend 命令保留无过滤的 `./mvnw -B clean test`，并显式追加：

```text
"-Dc9.legacy.jar=$FINANCE_OS_C9_LEGACY_JAR"
"-Dc9.legacy.evidence=$FINANCE_OS_C9_LEGACY_EVIDENCE"
```

本轮真实 Surefire XML 的 system properties 含两项实际路径，不是只设置 workflow 环境变量。没有 skipTests、maven.test.skip、testFailureIgnore、continue-on-error 或 -Dtest 过滤进入完整 Backend 命令。

YAML 经现有 SnakeYAML 解析、重复 key 检查、与 HEAD 的结构比较通过；新增 V20 构建与完整测试 shell 均通过 bash -n。失败诊断上传有 evidence 变量非空 guard，避免前置失败时 glob 退化为根路径；上传 Legacy XML、过程日志和脱敏 JSON，不上传 JAR、原 payload、源码导出或环境变量文件。本地静态检查及隔离运行不能代替 Hosted CI。

## 7. 三个真实 Legacy 方法的结果

| 完整方法 | Windows 定向 | 本地 Ubuntu 定向 | Windows 完整 Backend 中 |
| --- | --- | --- | --- |
| `officialProvisioningRefusesRealNonemptyV20RootsWithoutMutatingLegacyEvidence` | PASS / 26.805s | PASS / 22.049s | PASS / 29.66s |
| `migratedLegacyCancelAndExpiryKeepDurableRetryUntilTheActualGuardAllowsCleanup` | PASS / 49.067s | PASS / 42.432s | PASS / 52.979s |
| `genuineV20ActiveSessionsSurviveExplicitOfflineMigrationMappingConfirmReceiptAndReplay` | PASS / 42.894s | PASS / 39.764s | PASS / 44.574s |

- **D-1**：真实 V20 应用生成非空旧 RAW/PLAN；停机和连接归零后独立迁移 V21。真实官方 Provisioner exit 2，未产生四种 binding/control 文件；当前真实应用在未绑定旧根拒绝启动。Registry/Binding 仍 0，业务快照、Session 状态、RAW/PLAN 摘要不变。
- **D-2**：V20 通过真实 HTTP 注册、登录、CSV 上传，生成 MAPPING_REQUIRED 与 PREVIEW_READY。只按停写后 DB 精确 UUID 引用复制 2 RAW + 1 PLAN 到官方 CLI 新预配空根；不扫描未知文件、不复制 control、不覆盖、不改引用、不预插 Registry。当前应用自然登记三个 LIVE 对象，Mapping 后四个 LIVE。旧 ready 的原 previewToken 直接经 HTTP Confirm 生成一笔 EXPENSE 12.34，余额 100.00→87.66；Item、Batch、resultDigest 和 Receipt 对应一致。原 Key Replay、GET Receipt 和重启后 Replay 均返回同一 Receipt，Replay 标志为 true，完整金融快照不变。旧源三个文件仍在且摘要相同。
- **D-3**：测试 JVM 在当前应用 JVM 之外持有真实 FileChannel 的 control byte 64/length 1 锁，生产 guard 确实无法获得该区域。Cancel/Expiry 返回原 503 合同但 Session 转为 CANCELLED/EXPIRED，三对象 RETIRED、0 DELETED，必要引用和文件保留。释放锁后重启当前真实生产应用，由 ApplicationReadyEvent Cleanup 完成重试；三对象 DELETED 且 deleted_at 非空，三个目标文件均不存在，引用清空，无 Transaction/Batch 新增，余额仍为 100.00。原历史 payload 不变。没有手工将 Registry 设成 DELETED；只用 SQL 将目标 Session 置于过期时间以驱动原生产过期流程。

每个场景均保存真实 V20/V21 Flyway 历史；V20 20 条 migration，V20→V21 恰执行 1 条并 validate。迁移前后八业务表逐行快照相等，Registry/Binding 初始均空。users 全行快照保存 SHA-256 而非密码 hash 明文，仍覆盖全行一致性。真实旧 JVM 停止后 `pg_stat_activity` 的其余 client backend 数为 0；最终子进程 PID、alive=false、exit code 均保留。

## 8. 回归、完整 Backend 与平台边界

| 完整类 / 组合 | Windows 定向 | Windows 完整 Backend 中 |
| --- | --- | --- |
| `FlywayMigrationIntegrationTest` | 1/0/0/0 | 1/0/0/0 |
| `FlywayTransferVersionNineteenMigrationIntegrationTest` | 未单独定向 | 1/0/0/0 |
| `FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest` | 1/0/0/0 | 1/0/0/0 |
| `FlywayTransactionImportVersionTwentyMigrationIntegrationTest` | 6/0/0/0 | 6/0/0/0 |
| `FlywayTransactionImportVersionTwentyOneMigrationIntegrationTest` | 2/0/0/0 | 2/0/0/0 |
| `FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest` | 1/0/0/0 | 1/0/0/0 |
| `TransactionImportManagedProcessPostgresIntegrationTest` | 7/0/0/0 | 7/0/0/0 |
| `TransactionImportManagedStoragePostgresIntegrationTest` | 19/0/0/0 | 19/0/0/0 |
| `TransactionImportLifecycleGuardPrototypePostgresIntegrationTest` | 未单独定向 | 14/0/0/0 |
| `TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest` | 未单独定向 | 14/0/0/0 |
| `TransactionImportConfirmRuntimeIntegrationTest` | 未单独定向 | 5/0/0/0 |

B1 的基础 Flyway/Transfer V19/V20 三类在本轮全量均通过；B2-1 V17 与 B2-2 V19→V20→V21 运行链路均在定向及全量通过。Batch C 的 Guard/Design Boundary/Managed Process 三个完整类在全量合计 35/0/0/0；Storage 19/0/0/0 与 Confirm Runtime 5/0/0/0 也在全量通过。Receipt/Replay 的直接业务证据来自 B2 与 D 的真实 HTTP 流程；相关其余测试均由无过滤完整 Backend 执行。

Windows 定向 Legacy：Maven 耗时 02:43，三个方法合计测试类耗时 136.2 秒。Windows 七类关联回归：37/0/0/0，Maven 耗时 04:35。这两批与完整 Backend 是不同运行，不相加虚报唯一测试数。

Windows 使用 Java 21.0.10、Maven Wrapper 3.9.9、Docker Desktop 29.6.1；本地 Ubuntu 为 eclipse-temurin:21-jdk-jammy 中的 Ubuntu 22.04.5 LTS、Temurin 21.0.11+10，Testcontainers PostgreSQL 17-alpine 实际 server 17.11。Linux 证据目录及受管存储位于容器 Linux overlay 文件系统；数据库为独占 Testcontainers 容器，不连接日常数据库。

环境前置失败全部保留：首次 Windows 打包因当前进程未设置 JAVA_HOME，在 Maven 启动前退出；为该进程设置已有 JDK 路径后打包通过。首次 Linux 历史打包因 Windows git archive 导出 Wrapper CRLF 而退出，未运行测试；仅在仓库外副本把两个 Wrapper 还原为 Git canonical LF 后通过身份检查并继续。未改历史业务代码或 Migration，没有把环境失败当生产缺陷。

Windows 完整 Backend 从 22:23:11 开始，结束于 22:35:50（2026-10-15，Asia/Shanghai）；Maven 统计耗时 **12:35**，exit 0、BUILD SUCCESS，独立 XML 求和 **562/0/0/0**。本地 Ubuntu Legacy Maven 耗时 **02:12**，exit 0，独立 XML 求和 **3/0/0/0**。构件打包耗时分别为 Windows V20 34.469s、Windows 当前 36.221s、本地 Ubuntu V20 16.769s、本地 Ubuntu 当前 17.783s。未把本地耗时当作 Hosted job 耗时，也未据此声称远端 20 分钟门槛已通过。

本轮未推送，因此 Hosted Ubuntu 对本轮 diff **尚未验证**；本地 Ubuntu 容器不是 ubuntu-latest runner。本地 Linux 完整 Backend 未执行，只执行授权优先要求的三个 Legacy 方法；Windows 完整 Backend 实际结果如上。不将旧报告的测试数字作为本轮证据。

## 9. 两名 Luna Max 独立只读复审

- Reviewer 1（GPT-6 Luna / Max，Legacy 业务与数据安全）：实际读取实现、相关生产调用链和 Windows 本轮证据，独立核对历史 SHA/tree、金额、快照、原源文件、Mapping/Confirm/Receipt/Replay 与迁移；未发现可复现 P0/P1/P2，范围内静态 GO。
- Reviewer 2（GPT-6 Luna / Max，Storage/构件/CI）：核对历史/当前身份与隔离、9 项负向 gate、current helper 复用、Surefire 属性、非空根拒绝、真实 guard、进程/连接退出和 CI；范围内静态 GO，未发现 P0/P1 或需扩大生产安全合同的问题。

两名 Reviewer 均只读、不运行测试、不替代真实验证。其首轮结论时 Windows 全量及 Linux 仍在执行，因此没有用审查结论冒充当时尚未产生的动态结果。

随后两名 Reviewer 均只读补核了本轮 Ubuntu XML、退出码、独立构件与流程证据，均确认与先前 GO 一致、没有新增阻断。Windows 全量最终统计由主控核对 Maven 输出及独立 XML 求和，不虚构 Reviewer 在首轮已验证全量。

## 10. 证据、风险与后续验收

仓库外专属根：

`F:/IDEA/finance-os-evidence/G4-3-D-20261015-ed2a5794`

主要入口：

- `provenance.json`、`original-ci-summary.log`：本轮 Git/构件身份和原 CI 失败摘录。
- `v20-source.tar`、`v20-source/backend`、`v20-build-attempt2.log`、`v20-identity.log`：固定历史导出、构建、classes/JAR 与身份。
- `current-build-attempt2.log`、`current-app.jar.identity.properties`：本轮当前 JAR 构建与身份。
- `windows-legacy-attempt1.log`、`windows-legacy-attempt1-reports`、`windows-legacy-attempt1`：三个方法首次真实完整通过的 XML、Session/快照/摘要/payload 副本、进程/CLI 日志。
- `windows-regression.log`、`windows-regression-executed-reports`：七个本轮实际执行类。另保留的原目录 `windows-regression-reports` 是未 clean 的原始目录快照，含前次 Legacy XML；不对它全目录求和来宣称 37。
- `windows-full.log`、`windows-full-reports`、`windows-full-legacy`、`windows-full-b2`：本轮完整运行日志/XML与其中真实历史流程。
- `linux-environment.txt`、`linux-*-build*.log`、`linux-*-identity.log`、`linux-legacy.log`、`linux-legacy-reports`、`linux-legacy-evidence`：本地 Linux 独立构建及三个方法实测。
- `v20-gate-probes.log`、`ci-static-check.log`、`implementation.diff`、`validation-summary.json`：负向验证、CI 静态检查、完整实现 diff 和 XML 独立汇总。

Legacy 夹具不持久化 Token；previewToken 从持久证据移除，临时应用 secret 仅通过子进程环境传递。既有 B2 测试在本轮生成的两份 original-confirm-request.json 在该类结束后另行脱敏 previewToken，原请求字节摘要与说明保存在 evidence-redactions.json；不改变其内存中的实际 Replay 断言或源码。users 快照仅持久化摘要；不提交任何 JAR、payload、secret 或运行证据目录。报告中的结果来自真实执行输出与通过断言，不把人工归纳 JSON 当成应用原始响应。

剩余事项仅为人工审阅和单独授权后正式远端 Ubuntu CI 验证；远端正式结果、整体 G4-3 关闭、Final Stabilization、Finance Freeze 和迁移均不在本轮完成范围。若远端暴露新失败，仍须按真实原因处理，不削弱现有安全/金融断言。

## 11. 完成前检查

本轮最终核对：分支/HEAD/origin/独立远端一致；实际实现 diff 与附录一致；staged diff 为空；git diff --check 通过。工作区仅有上述四个成果文件及两个原有受保护未追踪路径。生产 Java、POM、前端、Migration 和其他测试没有 diff；YAML 结构比较确认其他 jobs 未变。报告添加后只复核文档、diff 和状态，不重复运行已通过且源码未再改变的完整测试。

**最终判定：IMPLEMENTED / REMOTE VALIDATION REQUIRED。** 本轮实施完成后停止，等待人工验收和单独后续授权。

## 12. 完整实现 diff

以下包含三个实现文件的完整实际 diff；本文为新增报告正文，不递归包含自身 diff。

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index 2828a4f..acacde7 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -24,6 +24,8 @@ jobs:
         working-directory: backend
     steps:
       - uses: actions/checkout@v6
+        with:
+          fetch-depth: 0
       - name: Set up JDK 21
         uses: actions/setup-java@v5
         with:
@@ -55,6 +57,31 @@ jobs:
             echo "FINANCE_OS_V19_IDENTITY=$historical_root/identity.properties"
             echo "FINANCE_OS_B2_EVIDENCE_ROOT=$historical_root"
           } >> "$GITHUB_ENV"
+      - name: Build and verify fixed historical V20 application
+        shell: bash
+        run: |
+          set -euo pipefail
+          umask 077
+          historical_commit=138bccc2bcb2d5bfea17ea88ecc115604928a5be
+          historical_root="$(mktemp -d "$RUNNER_TEMP/finance-os-v20.XXXXXX")"
+          git -C "$GITHUB_WORKSPACE" merge-base --is-ancestor "$historical_commit" HEAD
+          test "$(git -C "$GITHUB_WORKSPACE" rev-parse "$historical_commit:backend")" = 'f182075fc6231580d8b06694b6143f6b07426bf3'
+          git -C "$GITHUB_WORKSPACE" archive --format=tar --output="$historical_root/source.tar" "$historical_commit" backend
+          mkdir "$historical_root/source"
+          tar -xf "$historical_root/source.tar" -C "$historical_root/source"
+          (
+            cd "$historical_root/source/backend"
+            bash ./mvnw -B -DskipTests clean package
+          ) 2>&1 | tee "$historical_root/build.log"
+          historical_jar="$historical_root/source/backend/target/finance-os-backend-1.0.0.jar"
+          java "$GITHUB_WORKSPACE/backend/src/test/java/com/financeos/integration/historical/HistoricalV20Artifact.java" \
+            "$GITHUB_WORKSPACE" "$historical_root/source/backend" \
+            "$historical_jar" "$historical_jar.identity.properties"
+          evidence_root="$(mktemp -d "$RUNNER_TEMP/finance-os-c9-legacy-evidence.XXXXXX")"
+          {
+            echo "FINANCE_OS_C9_LEGACY_JAR=$historical_jar"
+            echo "FINANCE_OS_C9_LEGACY_EVIDENCE=$evidence_root"
+          } >> "$GITHUB_ENV"
       - name: Build and verify current managed-process application
         shell: bash
         run: |
@@ -69,7 +96,21 @@ jobs:
           test "$(git -C "$GITHUB_WORKSPACE" rev-parse HEAD)" = "$current_head"
           echo "FINANCE_OS_C9_CURRENT_JAR=$current_root/app.jar" >> "$GITHUB_ENV"
       - name: Run backend tests
-        run: ./mvnw -B clean test
+        run: >-
+          ./mvnw -B clean test
+          "-Dc9.legacy.jar=$FINANCE_OS_C9_LEGACY_JAR"
+          "-Dc9.legacy.evidence=$FINANCE_OS_C9_LEGACY_EVIDENCE"
+      - name: Upload Legacy upgrade failure diagnostics
+        if: ${{ failure() && env.FINANCE_OS_C9_LEGACY_EVIDENCE != '' }}
+        uses: actions/upload-artifact@v7
+        with:
+          name: backend-legacy-upgrade-failure-diagnostics
+          path: |
+            backend/target/surefire-reports/TEST-com.financeos.integration.TransactionImportLegacyUpgradePostgresIntegrationTest.xml
+            ${{ env.FINANCE_OS_C9_LEGACY_EVIDENCE }}/**/*.log
+            ${{ env.FINANCE_OS_C9_LEGACY_EVIDENCE }}/**/*.json
+          if-no-files-found: warn
+          retention-days: 7

   frontend:
     name: Frontend
diff --git a/backend/src/test/java/com/financeos/integration/TransactionImportLegacyUpgradePostgresIntegrationTest.java b/backend/src/test/java/com/financeos/integration/TransactionImportLegacyUpgradePostgresIntegrationTest.java
index 5efbd9d..6a3a183 100644
--- a/backend/src/test/java/com/financeos/integration/TransactionImportLegacyUpgradePostgresIntegrationTest.java
+++ b/backend/src/test/java/com/financeos/integration/TransactionImportLegacyUpgradePostgresIntegrationTest.java
@@ -1,5 +1,6 @@
 package com.financeos.integration;

+import com.financeos.integration.historical.HistoricalV20Artifact;
 import com.fasterxml.jackson.databind.JsonNode;
 import com.fasterxml.jackson.databind.ObjectMapper;
 import org.flywaydb.core.Flyway;
@@ -30,6 +31,8 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
     private static PostgreSQLContainer<?> database;
     private static Process nativePostgres;
     private static String databaseUrl, databaseUser, databasePassword;
+    private static Path ownedEvidence, verifiedCurrentJar;
+    private static HistoricalV20Artifact verifiedLegacy;
     private final ObjectMapper json = new ObjectMapper();
     private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
     private final UUID deployment = UUID.randomUUID(), rawId = UUID.randomUUID(), planId = UUID.randomUUID();
@@ -44,6 +47,15 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
     private final List<Process> children = new ArrayList<>();

     @BeforeAll static void startOwnedDatabase() throws Exception {
+        Path repository = Path.of("..").toRealPath();
+        Path configured = Path.of(required("c9.legacy.evidence")).toAbsolutePath().normalize();
+        assertThat(configured.startsWith(repository)).as("Evidence must be outside the repository").isFalse();
+        Files.createDirectories(configured);
+        ownedEvidence = configured.toRealPath();
+        assertThat(ownedEvidence).isEqualTo(configured);
+        verifiedLegacy = HistoricalV20Artifact.required();
+        verifiedCurrentJar = CurrentManagedProcessArtifact.required();
+        assertThat(verifiedLegacy.jar()).isNotEqualTo(verifiedCurrentJar);
         String binaries = System.getProperty("c9.postgres.bin");
         if (binaries == null) {
             database = new PostgreSQLContainer<>("postgres:17-alpine");
@@ -52,9 +64,7 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
             return;
         }
         // Native fallback starts its own fresh cluster; never connect to an existing local database.
-        Path owned = Path.of(required("c9.legacy.evidence")).toAbsolutePath();
-        Files.createDirectories(owned);
-        Path cluster = Files.createTempDirectory(owned, "owned-postgres-");
+        Path cluster = Files.createTempDirectory(ownedEvidence, "owned-postgres-");
         Path data = cluster.resolve("data");
         Process init = new ProcessBuilder(Path.of(binaries, "initdb").toString(), "-D", data.toString(),
                 "-U", "c9_upgrade", "--auth=trust", "--encoding=UTF8", "--no-locale")
@@ -93,19 +103,23 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
     }

     @BeforeEach void isolatedV20DatabaseAndStorage(TestInfo info) throws Exception {
-        Path owned = Path.of(required("c9.legacy.evidence")).toAbsolutePath();
-        Files.createDirectories(owned);
-        evidence = Files.createTempDirectory(owned, info.getTestMethod().orElseThrow().getName() + "-");
+        evidence = Files.createTempDirectory(ownedEvidence, info.getTestMethod().orElseThrow().getName() + "-");
         legacyTemp = Files.createDirectory(evidence.resolve("old-jvm-temp"));
         legacyRaw = legacyTemp.resolve("finance-os/transaction-import-preview");
         legacyPlan = legacyTemp.resolve("finance-os/transaction-import-preview-plan");
         db = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
         flyway("20").clean();
         flyway("20").migrate();
+        writeEvidence("artifact-inputs.json", Map.of("legacyCommit", HistoricalV20Artifact.COMMIT,
+                "legacyJar", verifiedLegacy.jar().toString(), "legacySha256", verifiedLegacy.sha256(),
+                "currentJar", verifiedCurrentJar.toString(), "evidenceRoot", ownedEvidence.toString()));
+        writeMigrationEvidence("v20-flyway.json");
     }

     @AfterEach void stopOnlyOwnedProcesses() throws Exception {
         for (Process child : children) stop(child);
+        if (evidence != null) writeEvidence("stopped-processes.json", children.stream().map(child ->
+                Map.of("pid", child.pid(), "alive", child.isAlive(), "exit", child.exitValue())).toList());
         // Keep DB snapshots, exact payload copies, process logs, and evidence directories.
     }

@@ -113,7 +127,7 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
         Legacy sessions = createRealLegacySessions();
         Map<String, String> before = databaseSnapshot();
         Map<String, String> payloads = preserveReferencedPayloads("v20-payloads");
-        assertThat(flyway("21").migrate().migrationsExecuted).isEqualTo(1);
+        migrateToV21();
         assertThat(databaseSnapshot()).isEqualTo(before);
         assertThat(registryCount()).isZero();

@@ -122,8 +136,9 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
         assertThat(provision.exitValue()).isEqualTo(2);
         assertThat(Files.readString(evidence.resolve("in-place-provision.log")))
                 .contains("Import storage provisioning refused");
-        assertThat(Files.exists(legacyRaw.resolve(".import-binding"))).isFalse();
-        assertThat(Files.exists(legacyPlan.resolve(".import-control"))).isFalse();
+        for (Path root : List.of(legacyRaw, legacyPlan))
+            for (String name : List.of(".import-binding", ".import-control"))
+                assertThat(Files.exists(root.resolve(name))).isFalse();

         Running app = startCurrent(legacyRaw, legacyPlan, "unbound-current.log");
         assertThat(app.process().waitFor(45, TimeUnit.SECONDS)).isTrue();
@@ -145,7 +160,7 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
         Legacy sessions = createRealLegacySessions();
         Map<String, String> before = databaseSnapshot();
         Map<String, String> payloads = preserveReferencedPayloads("v20-payloads");
-        assertThat(flyway("21").migrate().migrationsExecuted).isEqualTo(1);
+        migrateToV21();
         assertThat(databaseSnapshot()).isEqualTo(before);
         assertThat(registryCount()).isZero();

@@ -237,7 +252,7 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
     @Test void migratedLegacyCancelAndExpiryKeepDurableRetryUntilTheActualGuardAllowsCleanup() throws Exception {
         Legacy sessions = createRealLegacySessions();
         Map<String, String> payloads = preserveReferencedPayloads("v20-payloads");
-        assertThat(flyway("21").migrate().migrationsExecuted).isEqualTo(1);
+        migrateToV21();
         Path raw = evidence.resolve("managed-raw"), plans = evidence.resolve("managed-plan");
         Process provision = provision(raw, plans, "empty-provision.log");
         assertThat(provision.waitFor(15, TimeUnit.SECONDS)).isTrue();
@@ -343,9 +358,7 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
     }

     private Legacy createRealLegacySessions() throws Exception {
-        Path jar = Path.of(required("c9.legacy.jar")).toAbsolutePath();
-        assertThat(jar).isRegularFile();
-        Running legacy = start(jar, legacyTemp, null, null, "legacy-v20.log");
+        Running legacy = start(verifiedLegacy.jar(), legacyTemp, null, null, "legacy-v20.log");
         awaitReady(legacy);
         assertThat(appliedVersion()).isEqualTo(20);
         assertThat(db.queryForObject("SELECT to_regclass('transaction_import_file_objects')::text", String.class)).isNull();
@@ -372,6 +385,10 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
         assertThat(ready.get("previewToken").asText()).isNotBlank();
         assertThat(request(legacy, "GET", path(ready, "rows"), null, null).statusCode()).isEqualTo(200);
         stop(legacy.process()); // No old writer or cleanup process remains before V21.
+        assertThat(db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
+                + "AND pid<>pg_backend_pid() AND backend_type='client backend'", Integer.class)).isZero();
+        writeEvidence("legacy-process-exit.json", Map.of("pid", legacy.process().pid(),
+                "exit", legacy.process().exitValue(), "alive", legacy.process().isAlive(), "remainingConnections", 0));
         writeEvidence("v20-database.json", databaseSnapshot());
         // Tokens remain in memory only. Persist business fields, without the signed tokens.
         var identity = json.createObjectNode().put("flyway", 20).put("legacyStopped", true);
@@ -405,7 +422,8 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
         try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
         ProcessBuilder builder = new ProcessBuilder(java(), "-Djava.io.tmpdir=" + tmp, "-jar", jar.toString(),
                 "--server.address=127.0.0.1", "--server.port=" + port, "--spring.profiles.active=prod",
-                "--finance.import.cleanup.enabled=false", "--finance.import.confirm.token-secret=" + confirmSecret);
+                "--finance.import.cleanup.enabled=false");
+        builder.environment().put("FINANCE_IMPORT_CONFIRM_TOKEN_SECRET", confirmSecret);
         builder.environment().put("DB_URL", databaseUrl);
         builder.environment().put("DB_USERNAME", databaseUser);
         builder.environment().put("DB_PASSWORD", databasePassword);
@@ -467,11 +485,35 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
     private Map<String, String> databaseSnapshot() {
         Map<String, String> result = new TreeMap<>();
         for (String table : List.of("users", "accounts", "categories", "transactions", "transaction_import_sessions",
-                "transaction_import_batches", "transaction_import_items", "transaction_import_batch_account_impacts"))
-            result.put(table, db.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]') FROM " + table + " t", String.class));
+                "transaction_import_batches", "transaction_import_items", "transaction_import_batch_account_impacts")) {
+            String rows = db.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]') FROM " + table + " t", String.class);
+            // Preserve exact full-row equality without persisting password hashes in acceptance artifacts.
+            if (table.equals("users")) {
+                try { rows = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rows.getBytes(StandardCharsets.UTF_8))); }
+                catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
+            }
+            result.put(table, rows);
+        }
         return result;
     }

+    private void migrateToV21() throws Exception {
+        Map<String, String> before = databaseSnapshot();
+        writeEvidence("pre-v21-database.json", before);
+        assertThat(flyway("21").migrate().migrationsExecuted).isEqualTo(1);
+        flyway("21").validate();
+        assertThat(databaseSnapshot()).isEqualTo(before);
+        assertThat(registryCount()).isZero();
+        assertThat(bindingCount()).isZero();
+        writeMigrationEvidence("v21-flyway.json");
+        writeEvidence("post-v21-database.json", databaseSnapshot());
+    }
+
+    private void writeMigrationEvidence(String name) throws Exception {
+        writeEvidence(name, db.queryForList("SELECT installed_rank,version,description,script,checksum,success "
+                + "FROM flyway_schema_history ORDER BY installed_rank"));
+    }
+
     private Map<String, String> preserveReferencedPayloads(String name) throws Exception {
         Path copies = Files.createDirectory(evidence.resolve(name));
         Map<String, String> result = referencedPayloadDigests();
@@ -505,7 +547,7 @@ class TransactionImportLegacyUpgradePostgresIntegrationTest {
     private String status(JsonNode session) { return db.queryForObject("SELECT status FROM transaction_import_sessions WHERE id=?", String.class, UUID.fromString(session.get("importSessionId").asText())); }
     private String path(JsonNode session, String action) { return "/api/v1/imports/transactions/" + session.get("importSessionId").asText() + (action.isEmpty() ? "" : "/" + action); }
     private void writeEvidence(String name, Object value) throws Exception { Files.writeString(evidence.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
-    private static Path currentJar() { return Path.of("target/finance-os-backend-1.0.0.jar").toAbsolutePath(); }
+    private static Path currentJar() { return verifiedCurrentJar; }
     private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
     private static String required(String property) { return Objects.requireNonNull(System.getProperty(property), property + " must point to owned acceptance artifacts"); }
     private static void stop(Process process) throws Exception {
diff --git a/backend/src/test/java/com/financeos/integration/historical/HistoricalV20Artifact.java b/backend/src/test/java/com/financeos/integration/historical/HistoricalV20Artifact.java
new file mode 100644
index 0000000..654e9bf
--- /dev/null
+++ b/backend/src/test/java/com/financeos/integration/historical/HistoricalV20Artifact.java
@@ -0,0 +1,167 @@
+package com.financeos.integration.historical;
+
+import java.io.IOException;
+import java.nio.charset.StandardCharsets;
+import java.nio.file.Files;
+import java.nio.file.Path;
+import java.security.MessageDigest;
+import java.util.HashSet;
+import java.util.HexFormat;
+import java.util.Properties;
+import java.util.TreeSet;
+import java.util.concurrent.TimeUnit;
+import java.util.jar.JarFile;
+
+/** Test-only provenance gate; also runnable with Java source-file mode after the CI build. */
+public record HistoricalV20Artifact(Path jar, String sha256) {
+    public static final String COMMIT = "138bccc2bcb2d5bfea17ea88ecc115604928a5be";
+    private static final String BACKEND_TREE = "f182075fc6231580d8b06694b6143f6b07426bf3";
+
+    public static void main(String[] args) throws Exception {
+        if (args.length != 4) throw new IllegalArgumentException("Expected repository, exported backend, JAR, identity output");
+        Path repository = Path.of(args[0]).toRealPath();
+        Path source = Path.of(args[1]).toRealPath();
+        Path jar = Path.of(args[2]).toRealPath();
+        String digest = verify(repository, source, jar);
+        Properties identity = new Properties();
+        identity.setProperty("sourceCommit", COMMIT);
+        identity.setProperty("backendTree", BACKEND_TREE);
+        identity.setProperty("repository", repository.toString());
+        identity.setProperty("sourceBackend", source.toString());
+        identity.setProperty("jar", jar.toString());
+        identity.setProperty("jarSha256", digest);
+        // CREATE_NEW: preserve previous evidence and reject an accidental overwrite.
+        try (var out = Files.newOutputStream(Path.of(args[3]), java.nio.file.StandardOpenOption.CREATE_NEW)) {
+            identity.store(out, "Fixed V20 source and locally built artifact identity; hash is build-specific");
+        }
+        System.out.println("V20_IDENTITY commit=" + COMMIT + " jarSha256=" + digest);
+    }
+
+    public static HistoricalV20Artifact required() throws Exception {
+        String configured = System.getProperty("c9.legacy.jar");
+        require(configured != null && !configured.isBlank(),
+                "Missing c9.legacy.jar: prepare the fixed historical V20 JAR and its adjacent identity before running backend tests");
+        Path configuredJar = Path.of(configured).toRealPath();
+        Properties identity = new Properties();
+        try (var in = Files.newInputStream(configuredJar.resolveSibling(configuredJar.getFileName() + ".identity.properties"))) { identity.load(in); }
+        require(COMMIT.equals(identity.getProperty("sourceCommit")), "Historical source commit mismatch");
+        require(BACKEND_TREE.equals(identity.getProperty("backendTree")), "Historical backend tree mismatch");
+        Path jar = Path.of(identity.getProperty("jar")).toRealPath();
+        require(jar.equals(configuredJar), "Configured historical JAR differs from registered path");
+        String digest = verify(Path.of(identity.getProperty("repository")),
+                Path.of(identity.getProperty("sourceBackend")), jar);
+        require(digest.equals(identity.getProperty("jarSha256")), "Historical JAR SHA-256 mismatch");
+        System.out.println("V20_IDENTITY_VERIFIED commit=" + COMMIT + " jarSha256=" + digest);
+        return new HistoricalV20Artifact(jar, digest);
+    }
+
+    private static String verify(Path repository, Path source, Path jar) throws Exception {
+        require(!source.toRealPath().startsWith(repository.toRealPath()), "Historical source must be outside the repository");
+        require(!jar.toRealPath().startsWith(repository.toRealPath()), "Historical JAR must be outside the repository");
+        git(repository, "merge-base", "--is-ancestor", COMMIT, "HEAD");
+        require(BACKEND_TREE.equals(git(repository, "rev-parse", COMMIT + ":backend").strip()), "Fixed tree mismatch");
+        for (String line : git(repository, "ls-tree", "-r", COMMIT, "backend/src/main/resources/db/migration").lines().toList()) {
+            String[] entry = line.split("\\t", 2);
+            String historicalBlob = entry[0].split(" ")[2];
+            require(historicalBlob.equals(git(repository, "rev-parse", "HEAD:" + entry[1]).strip()),
+                    "Historical migration differs from current version: " + entry[1]);
+        }
+        var expectedSources = new TreeSet<String>();
+        for (String entry : git(repository, "ls-tree", "-r", COMMIT, "backend").lines().toList()) {
+            String[] fields = entry.split("\\t", 2);
+            String[] metadata = fields[0].split(" ");
+            require(metadata[1].equals("blob"), "Unexpected historical source entry");
+            String relative = fields[1].substring("backend/".length());
+            expectedSources.add(relative);
+            Path file = source.resolve(relative);
+            require(Files.isRegularFile(file) && !Files.isSymbolicLink(file), "Missing/unsafe historical source: " + relative);
+            byte[] bytes = Files.readAllBytes(file);
+            // git archive on Windows may export CRLF. Accept only raw or CRLF->LF Git blob identity.
+            if (!blob(bytes).equals(metadata[2])) {
+                byte[] canonical = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")
+                        .getBytes(StandardCharsets.UTF_8);
+                require(blob(canonical).equals(metadata[2]), "Historical source mismatch: " + relative);
+            }
+        }
+        require(!expectedSources.isEmpty(), "No historical sources verified");
+        var actualSources = new TreeSet<String>();
+        try (var paths = Files.walk(source)) {
+            paths.filter(path -> !path.startsWith(source.resolve("target")))
+                    .filter(path -> !Files.isDirectory(path)).forEach(path ->
+                            actualSources.add(source.relativize(path).toString().replace('\\', '/')));
+        }
+        require(actualSources.equals(expectedSources), "Unexpected files in historical source export");
+        try (JarFile archive = new JarFile(jar.toFile())) {
+            var attributes = archive.getManifest().getMainAttributes();
+            require("org.springframework.boot.loader.launch.JarLauncher".equals(attributes.getValue("Main-Class")), "Wrong Boot loader");
+            require("com.financeos.FinanceOsApplication".equals(attributes.getValue("Start-Class")), "Wrong historical main");
+            for (String name : java.util.List.of("BOOT-INF/classes/com/financeos/FinanceOsApplication.class",
+                    "BOOT-INF/classes/com/financeos/module/importing/controller/TransactionImportPreviewController.class",
+                    "BOOT-INF/classes/com/financeos/module/importing/entity/TransactionImportSession.class",
+                    "BOOT-INF/classes/com/financeos/module/importing/storage/LocalTemporaryImportFileStorage.class",
+                    "org/springframework/boot/loader/launch/JarLauncher.class")) {
+                require(archive.getJarEntry(name) != null, "Missing V20 application entry: " + name);
+            }
+            require(archive.getJarEntry("BOOT-INF/classes/com/financeos/module/importing/storage/ImportStorageProvisioner.class") == null,
+                    "V20 must not include V21 managed storage provisioning");
+            try (var in = archive.getInputStream(archive.getJarEntry("BOOT-INF/classes/com/financeos/FinanceOsApplication.class"))) {
+                byte[] bytes = in.readNBytes(8);
+                require(bytes.length == 8 && bytes[6] == 0 && Byte.toUnsignedInt(bytes[7]) == 65, "V20 must be built for Java 21");
+            }
+            var migrations = new TreeSet<Integer>();
+            var packaged = new HashSet<String>();
+            for (var entry : archive.stream().filter(item -> !item.isDirectory()).toList()) {
+                String name = entry.getName();
+                if (!name.startsWith("BOOT-INF/classes/")) continue;
+                String relative = name.substring("BOOT-INF/classes/".length());
+                require(packaged.add(relative), "Duplicate historical JAR entry: " + relative);
+                Path built = source.resolve("target/classes").resolve(relative);
+                try (var in = archive.getInputStream(entry)) {
+                    require(Files.isRegularFile(built) && MessageDigest.isEqual(in.readAllBytes(), Files.readAllBytes(built)),
+                            "Historical packaged class/resource mismatch: " + relative);
+                }
+                if (relative.startsWith("db/migration/")) {
+                    var match = java.util.regex.Pattern.compile("db/migration/V(\\d+)__.+\\.sql").matcher(relative);
+                    require(match.matches(), "Unexpected historical migration: " + relative);
+                    require(migrations.add(Integer.parseInt(match.group(1))), "Duplicate historical migration version");
+                }
+            }
+            var builtFiles = new HashSet<String>();
+            try (var paths = Files.walk(source.resolve("target/classes"))) {
+                paths.filter(Files::isRegularFile).forEach(path -> builtFiles.add(
+                        source.resolve("target/classes").relativize(path).toString().replace('\\', '/')));
+            }
+            require(packaged.equals(builtFiles) && !packaged.isEmpty(), "Historical compiled inventory mismatch");
+            require(migrations.equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList())),
+                    "Historical JAR must contain exactly V1-V20 migrations");
+        }
+        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
+    }
+
+    private static String blob(byte[] bytes) throws Exception {
+        MessageDigest digest = MessageDigest.getInstance("SHA-1");
+        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
+        return HexFormat.of().formatHex(digest.digest(bytes));
+    }
+
+    private static String git(Path repository, String... args) throws Exception {
+        var command = new java.util.ArrayList<String>(java.util.List.of("git", "-C", repository.toString()));
+        command.addAll(java.util.List.of(args));
+        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
+        var outputBytes = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
+            try { return process.getInputStream().readAllBytes(); }
+            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
+        });
+        if (!process.waitFor(20, TimeUnit.SECONDS)) {
+            process.destroyForcibly();
+            throw new IOException("Historical identity git command timed out");
+        }
+        String output = new String(outputBytes.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
+        require(process.exitValue() == 0, "Historical identity git command failed: " + output);
+        return output;
+    }
+
+    private static void require(boolean condition, String message) {
+        if (!condition) throw new IllegalStateException(message);
+    }
+}
```
