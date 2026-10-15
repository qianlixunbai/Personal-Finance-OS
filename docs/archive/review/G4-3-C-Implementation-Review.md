# G4-3 / Batch C Implementation Review

日期：2026-10-15（Asia/Shanghai）。本轮只实施 Batch C，不关闭 G4-3，不启动 Batch D，不授权 Finance Freeze；没有 commit、push 或 tag。

## 1. 结论

**G4-3 / Batch C — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**。

Windows 与本地受控 Ubuntu 的三个完整目标类分别为 **35 / 0 / 0 / 0**。最终 Windows 完整 Backend 为 **562 / 0 / 3 / 0**；三个 error 全部是范围外 Batch D 的既有前置条件缺失，因此完整 Backend 仍为 BUILD FAILURE，不能称为全量绿灯。两名 Luna Max 只读复审最终均为静态 GO，限定范围内没有未解决的 P0/P1/P2。

符合 Batch C 的提交候选门槛，等待人工技术验收与单独提交授权。本文不关闭 Batch C/G4-3，也不替代远端 ubuntu-latest 验收。

## 2. Preflight 与保护路径

实施前实际核对：

- 分支：`zh-cn`。
- `HEAD`、`refs/remotes/origin/zh-cn` 和独立 `git ls-remote origin refs/heads/zh-cn` 均为 `421872b581840a93af085c31d5cc29404dcc8b13`。
- tracked 和 staged diff 为空。
- 仅有 `?? AGENTS.md`、`?? docs/codex-task.md`；本轮只检查这两个路径的 Git 状态，未读取内容、计算 hash、修改、删除或暂存。
- B2-2 历史报告记载的“实施批准前曾误读 AGENTS.md”过程偏差仍原样保留；本轮没有修改或抹去该历史记录。

源码阅读范围包括 README、文档导航、Testing、B2-2 历史实施报告、三个目标测试、PostgresIntegrationTest、Worker、历史构件校验器、POM 和相关生产存储实现。POM 与生产代码均只读。

## 3. 原 Ubuntu 失败基线及根因

原 [GitHub Actions Run 38581613457](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38581613457) 对应上述 HEAD。本轮通过 `gh run view --log-failed` 读取了真实日志：**552 tests / 1 failure / 11 errors / 1 skipped**。这是历史 CI 证据，不是本轮测试结果。

1. Managed Process 的两个打包场景都使用 `target/finance-os-backend-1.0.0.jar`。`clean test` 不执行 package/repackage，当前 JAR 不存在。持久卷方法直接断言失败；Provisioner 方法没有该前置断言，仍将此路径作为 `/payload/app.jar` 的 bind source，随后报 `PropertiesLauncher` ClassNotFoundException。
2. Guard 的卷查询及 Design Boundary 的句柄探测直接使用 Windows 的 `volume:vsn`、`NOSHARE_DELETE`，Linux 日志明确为 UnsupportedOperationException。
3. Guard 的公共 `start()` 写死 `${java.home}/bin/java.exe`，Linux 日志为 No such file or directory。四个进程协议场景共用该启动路径；其中 stale-writer 原失败也会先遇到不支持的 channel option。

PropertiesLauncher 的历史挂载内容没有保留下来，不能断言当时实际挂载类型。本轮另外创建仓库外的空目录，将它挂载为 `/payload/app.jar`，执行原入口命令，得到 `directory`、同样的 ClassNotFoundException 和退出码 1；这是“缺失 JAR 路径变成目录”机制的受控复现，不是对原容器内容的追溯证明。正确构件的真实入口结果见下文。

## 4. 实际修改范围

| 文件 | 修改目的 |
| --- | --- |
| `backend/src/test/java/com/financeos/integration/TransactionImportLifecycleGuardPrototypePostgresIntegrationTest.java` | OS 专属选项分支、Linux 身份反例、跨平台 Java 路径和退出后锁释放 |
| `backend/src/test/java/com/financeos/integration/TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest.java` | Windows 句柄保证与 Linux advisory lock / SecureDirectoryStream 语义分别验证 |
| `backend/src/test/java/com/financeos/integration/TransactionImportManagedProcessPostgresIntegrationTest.java` | 外部当前构件、实际挂载 SHA、入口退出码、跨平台数据库端口转发及资源回收 |
| `backend/src/test/java/com/financeos/integration/CurrentManagedProcessArtifact.java`（新增） | test-only 当前构件登记及重新校验，可用 Java source-file mode 在 CI 运行 |
| `.github/workflows/ci.yml` | 仅在 Backend job 增加当前 JAR 构建与登记步骤 |
| 本文（新增） | 中文实施、验证及独立审查记录 |

未修改生产 Java、POM、V1–V21 Migration、Batch A/B1/B2 实现、Batch D、Security/JWT 或其他 CI jobs。完整实现 diff 在附录中；本文为新增报告正文，不递归包含自身 diff。

## 5. Windows / Linux 测试合同

Windows 保留真实 FileChannel、NOSHARE_DELETE、根目录及祖先目录的改名探测，并要求句柄关闭后相同操作成功。volume:vsn 仍检查 Integer 类型、重复查询相等，以及目录替换后仍相同。四个 Guard 场景的三次重复、真实 JVM、stdin/stdout 同步、跨进程锁竞争、强杀及清理器重取锁均保留；DENY 后还增加独立锁获取，确认正常退出释放锁。

Linux 的 channel 不传 NOSHARE_DELETE。Design Boundary 真实持有 FileLock 时要求根目录和祖先目录改名成功；替换路径上的新控制文件也能独立加锁，证明 advisory lock 不绑定路径或阻止不合作的命名空间替换。之后真实打开原目录的 SecureDirectoryStream，再次移动该目录，通过相对 channel 创建文件，并断言它只落在原目录。这验证 Linux 原生目录句柄的相对操作，不将 Windows 目录 pinning 虚构为 Linux 保证。

Linux 身份反例记录 FileStore 和 fileKey；key 可用时检查替换后的 key 属于 replacement、归档目录仍为原 key。缺少 key 时只记录 capability，不伪造值。关键反例继续执行：相同路径、复制 Marker、相同未认证 FileStore 仍可把错误根目录上的“缺失”写成 DELETED，而归档原目录的 payload 仍存在。FileStore、fileKey、Marker 都没有被描述成跨重启可信身份。

Standalone GuardWorker 内自行解析 OS 与打开 channel，避免调用继承 Spring/Testcontainers 的外层测试类触发初始化，污染 stdout 协议。真实子进程没有改成 mock；未新增 Disabled、Assumption、直接 return 或失败容忍，RepeatedTest(3) 均保留。

## 6. 当前构件、入口及 CI

唯一显式配置为 `FINANCE_OS_C9_CURRENT_JAR`，旁边的 `<jar>.identity.properties` 由 helper 写入。没有缺失时跳过或自动回退到历史 JAR。

CI 使用 checkout 的当前 HEAD，先检查它等于 GITHUB_SHA，再执行 `./mvnw -B -DskipTests clean package`。当前 JAR 复制到 `$RUNNER_TEMP/finance-os-c9-current.XXXXXX/app.jar`，helper 登记后导出环境变量，最后仍执行原样、无失败容忍的 `./mvnw -B clean test`。既有 V19 的固定 commit、独立构建、源码与身份校验步骤保持原样。

helper 核对 HEAD 下的 `backend/src/main` 和 POM 实际内容及输入清单；允许 Git 在 Windows 导出的 CRLF/LF 差异。登记和测试阶段都校验 manifest、FinanceOsApplication、ImportStorageProvisioner、JarLauncher、PropertiesLauncher、完整打包类/资源与当前 target/classes 字节和清单一致，迁移恰为 V1–V21，计算 SHA-256。测试时重新对照 sidecar 的 HEAD/SHA。因此 clean 不会删除外部 JAR，也不能悄悄换成 V19。

| 构件 | 实际构建 HEAD | 实际 SHA-256 |
| --- | --- | --- |
| Windows 当前 JAR | `421872b581840a93af085c31d5cc29404dcc8b13` | `6909ce19921ec33305679d2dbd745dc08775c5c0e69c23be71df497f2fa4a18d` |
| Linux 当前 JAR | 同上 | `d19181f9433fa876619e54e2cf78482433c242a7be3969ef8b7fbd523b20a090` |

SHA 是本轮构建特定值，未硬编码进 CI。历史 V19 复用原已验证构件及其身份文件，未重新设计或构建；历史 SHA 为 `d9f0d71c8d007b0198f3046f959c3cc1fcbbf5a610e9edfcdc57a0a7468e06bf`，源 commit `7d2d83c23abeaa64f0f5854f729ea919509b551a`。全量回归会再次执行原历史校验门槛。

容器继续只读 bind 当前 JAR 到 `/payload/app.jar`、test-classes 到 `/payload/test-classes`，仅将测试新建命名卷读写挂载到 `/managed`。每次容器启动后在容器内执行 `sha256sum /payload/app.jar`，要求与外部文件一致。Worker 用 JAR 解包的 BOOT-INF/classes 与 lib 启动真实生产存储；PROVISION/CLEANUP 需等真实 JVM 退出码 0 后才就绪。WRITE 仍保留持锁暂停、KILL、持久卷恢复与 DELETED 收敛。

Provisioner 仍执行 `java -Dloader.main=com.financeos.module.importing.storage.ImportStorageProvisioner -cp /payload/app.jar org.springframework.boot.loader.launch.PropertiesLauncher --provision`。首次退出必须为 0，重复必须为 2，分别校验后才输出 ready，避免首次就返回 2 被误判为重复预配拒绝。继续验证目录权限 700 和原有 64-byte binding/control 断言。JDBC host 解析为 loopback 时通过 Testcontainers.exposeHostPorts 和 host.testcontainers.internal 转发；外部 Docker host 地址保持原 URL，避免错误转发到测试 JVM 的 localhost。

本地构件准备方式（从 backend 执行，环境变量通过本地 shell 设置）：

```text
mvnw -B -DskipTests clean package
将 target/finance-os-backend-1.0.0.jar 复制到仓库外的绝对路径
java src/test/java/com/financeos/integration/CurrentManagedProcessArtifact.java <仓库绝对路径> <复制后的JAR绝对路径>
将 FINANCE_OS_C9_CURRENT_JAR 设置为该 JAR，再执行 mvnw -B clean test
```

Windows 使用 `mvnw.cmd`，Linux 使用 `./mvnw`。完整 Backend 还需沿用既有 FINANCE_OS_V19_IDENTITY 和 FINANCE_OS_B2_EVIDENCE_ROOT 前置配置；本文不改变 Batch D 的独立前置条件。

## 7. 实际验证环境与结果

证据根目录：`F:/IDEA/finance-os-evidence/G4-3-C-20261015-7f8c2b19`。日志和 Surefire XML 均在仓库外保存，不混入 Git。

- Windows：Windows 11 `10.0.26200` / amd64，Oracle JDK `21.0.10`，Maven `3.9.9`。
- 受控 Linux：`eclipse-temurin:21-jdk-jammy`，Ubuntu `22.04.5 LTS`，Temurin `21.0.11+10`。这是本地 Linux 容器实测，不是 GitHub ubuntu-latest 远端验收。JUnit 临时文件目录位于 Linux overlay 文件系统。
- 两环境共用本地 Docker Desktop Linux engine `29.6.1`；Testcontainers `1.20.0`；实际 PostgreSQL `17.11`，镜像标签 `postgres:17-alpine`。本轮没有用原报告中的旧版本代替实际版本。
- Linux 只导出 HEAD 的 backend 到仓库外，并覆盖本轮测试修改；Git 查询通过只读元数据执行，未读取两个受保护文件。Windows 导出的 mvnw 有 CRLF，隔离副本仅规范化该 wrapper 的换行；没有修改仓库 wrapper。
- Managed Process 的 Linux 测试 JVM 在能被 Docker daemon 解析的相同 bind source 路径下运行，以真实验证 sibling container 挂载；这是测试环境配置，不是产品或 Workspace 迁移。

以下数字顺序均为 Tests / Failures / Errors / Skipped，来自实际完整类执行和 Surefire XML：

| 完整测试类 | Windows 定向 | 本地 Ubuntu 定向 |
| --- | --- | --- |
| TransactionImportLifecycleGuardPrototypePostgresIntegrationTest | 14 / 0 / 0 / 0 | 14 / 0 / 0 / 0 |
| TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest | 14 / 0 / 0 / 0 | 14 / 0 / 0 / 0 |
| TransactionImportManagedProcessPostgresIntegrationTest | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 |
| 合计 | 35 / 0 / 0 / 0 | 35 / 0 / 0 / 0 |

Windows 的三个完整类在最终全量中也分别再次得到 14/0/0/0、14/0/0/0、7/0/0/0；包括最后新增的 DENY 后独立重取锁断言。Linux 的三个类均保留三次重复，无 OS skip。

两个环境的 PropertiesLauncher 均实际得到 firstExit=0 / repeatExit=2，并通过目录权限和原有 binding/control 长度断言。Guard 验证正常子进程退出码 0、强杀后非 0、清理器重取锁；Managed Process 验证 kill 后容器已停止、recovery JVM 退出 0、payload/part 删除、注册状态收敛且无金融写入。Linux 完成后检查无残留 Java 进程；两个本轮环境容器停止但保留，未删除证据或用户临时文件。

Windows 首次未过滤 `mvnw.cmd -B clean test`：**562 / 0 / 3 / 0**，Maven exit 1、BUILD FAILURE。此时后续只读核验和 Linux 运行引出最后的地址分支修改；为覆盖最终源码，再次运行同样的完整命令。

**最终源码的完整执行结果仍为 562 / 0 / 3 / 0**，结束时间为 2026-10-15 21:03:08（Asia/Shanghai），耗时 10:57，Maven exit 1、BUILD FAILURE。保存的 XML 独立求和同为 562/0/3/0，只有 TransactionImportLegacyUpgradePostgresIntegrationTest 为 3/0/3/0，其三个错误均为 `c9.legacy.evidence must point to owned acceptance artifacts`，发生在原 fixture 第 96 行 / required 第 510 行。没有隐藏、跳过或修改该类。全量中没有其他 failure/error/skipped。

具体日志 / XML（均相对于上述证据根）：

- Windows：`windows-guard-attempt2.log` / `windows-guard-reports/`，`windows-design.log` / `windows-design-reports/`，`windows-managed-attempt3.log` / `windows-managed-reports/`。
- Linux：`linux-initial/linux-guard.log` / `linux-initial/guard-reports/`，`linux-initial/linux-design.log` / `linux-design-reports/`，`linux-managed-attempt3.log` / `linux-managed-final-reports/`。
- 构建与异常复现：`windows-package.log`、`linux-package.log`、`properties-launcher-directory-reproduction.log`、`TarProbe.java`；两个当前 JAR 的 adjacent identity 文件保留。
- 完整 Backend：首次 `windows-full-backend.log` / `windows-full-reports/` / `windows-full-summary.json`；最终 `windows-full-backend-final.log` / `windows-full-final-reports/` / `windows-full-final-summary.json`。

静态和资源检查：新增 Backend step 的 shell 通过 `bash -n`；其他 CI jobs 全文比较不变，原历史 V19 准备块未改。`git diff --check` 通过，新增 helper/报告另用 no-index whitespace check 检查，无空白错误；staged diff 为空。全仓库 backend/src/test 的 java.exe 搜索仅剩三处，均有真实 OS 分支。最终验收 Worker JVM 数为 0，两个受控环境容器已停止；构件、日志和 XML 保留。

额外负例实际验证：不设置变量时明确失败；追加字节改变 JAR 时拒绝 SHA-256 mismatch；篡改 sidecar HEAD 时拒绝 HEAD mismatch。均未跳过测试或使用固定虚假身份。

本轮过程问题与纠正：

- Docker 起初未运行，已通过 docker desktop start 启动；当前进程缺 JAVA_HOME，设置已有本地 JDK 后构建成功。
- 首次 Windows Guard 的 XML 为 14/12/0/0，源于本轮新增外层 channel 调用触发子进程 Testcontainers 初始化，stdout 预期 LOCKED 却收到日志。将 channel/OS 解析放入独立 GuardWorker 后完整类通过，失败 XML 与日志保留。Maven 对重复失败的汇总为 6/4/0/0，不能用该汇总代替 XML 的实际重复次数。
- 曾尝试 Testcontainers copy 以避免不同 daemon 路径。真实执行遇到现有 Commons Compress/Lang 的 `SystemProperties.getUserName(String)` NoSuchMethodError，两个 Managed 尝试被停止，不能计为通过或完整测试结果。独立 tar probe 复现：启用 Testcontainers 所用 BIGNUMBER_POSIX 后，普通短路径复制也失败，因此不是仅长文件名问题。最终恢复既有 bind 方式，未改 POM，也未放宽生产合同。
- copy 尝试中 Reviewer 2 指出把生产 mapper class 当测试夹具复制的 P1；主控确认后移除，最终实现已无任何 copy 夹具清单，该问题已消除。
- Linux Managed Process 首次运行的 XML 为 7/0/3/0（Maven 对重复错误汇总为 5/0/1/0）：嵌套 Docker 的 JDBC host 为外部 host.docker.internal，错误启用 exposeHostPorts 后转发目标却是测试 JVM 的 localhost。现场连接探测证实外部端口可达、JVM loopback 同端口拒绝连接。最终仅对解析为 loopback 的地址转发，外部地址原样保留；没有改变生产行为、超时或重复次数。失败日志/XML 保留，修复后重跑完整类；Windows 完整 Backend 也在最终增量后重跑。
- 地址分支的首次编译漏声明 UnknownHostException，编译失败；已在两个 test-only 方法签名中声明，让异常直接使测试失败。该尝试未运行测试，不计为通过或运行结果。

## 8. 回归边界与独立复审

两名实际独立 Reviewer 均使用 `gpt-6-luna`、`max`，只读，不运行测试、不修改文件、不读取或 hash 保护路径：

1. 文件系统与安全 Reviewer：最终静态 GO，无 P0/P1/P2。核验 Windows 选项、原生句柄与卷查询，Linux advisory lock 与 SecureDirectoryStream 的真实语义，身份反例，四组三次重复，JVM 强杀与锁释放。明确未把 fileKey/FileStore 当认证身份。
2. 构件、进程与 CI Reviewer：初审目标内容 GO；一度把另两份已授权目标误作范围外，主控对照原任务澄清，Reviewer 已撤回该提醒。中途发现 copy 夹具 P1，主控确认并修复。只读复核 bind + SHA 后，又对真实 Linux 失败引出的 loopback/external-host 最小增量复验，最终结论 GO，无残留 P0/P1/P2。

主控没有仅凭报告修改生产逻辑；先核对实际类路径、运行输出及授权范围再处理。两名 Reviewer 的静态 GO 不代替以上实际测试。

最终版本完整 Backend 中，以下类已实际执行通过，顺序仍为 Tests / Failures / Errors / Skipped：

| 回归 | 完整类 | 本轮结果 |
| --- | --- | --- |
| B1 Migration | FlywayMigrationIntegrationTest | 1 / 0 / 0 / 0 |
| B2-1 V17 Digest Runtime | FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest | 1 / 0 / 0 / 0 |
| V19 边界 | FlywayTransferVersionNineteenMigrationIntegrationTest | 1 / 0 / 0 / 0 |
| B2-2 V19 HTTP Confirm / V20 / Replay | FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest | 1 / 0 / 0 / 0 |
| V20 Migration | FlywayTransactionImportVersionTwentyMigrationIntegrationTest | 6 / 0 / 0 / 0 |
| V21 Migration | FlywayTransactionImportVersionTwentyOneMigrationIntegrationTest | 2 / 0 / 0 / 0 |

结果另存 `final-migration-regression.json`。最终全量使用的历史进程证据目录为 `b2-2-implementation-17469901987810352336`；`historical-exit.txt` 实际记录 PID 32532、forced=false、pidAbsent=true、exit=0、remainingChildren=[]。历史源码、构件、HTTP/Receipt/Replay 验证仍由原校验器和原测试完成，没有用新当前 JAR 冒充 V19。

Batch A 浏览器 E2E 本轮未重跑；只静态确认其他 CI jobs 和历史 V19 准备块原样保留。没有重新审查或修改已关闭批次的实现。

## 9. 风险、未验证事项与停止边界

- P0：未发现。P1：copy 夹具路径问题已确认并消除，没有遗留项；Linux host 路由问题已实际复验通过。P2：限定范围内未发现新的待修问题，不处理其他批次的非阻塞优化。
- 远端 GitHub ubuntu-latest workflow 尚未运行本轮修改；没有 push 以获取反馈。本地 Ubuntu 容器与远端 runner 不是同一环境。
- Linux 完整 Backend 未执行；本轮完整 Backend 使用 Windows，Linux 执行三个受影响的完整类。
- 当前构件依赖旁边的身份文件及可访问的 Git 元数据。登记构件与 clean test 应在同一 JDK/构建环境执行；不同构建的 SHA 不要求相等。
- 身份 gate 依赖受控 Maven 构建链，不宣称对抗恶意构建者的外部签名证明。未来新增 Migration 或修改构建链时需同步该测试合同。
- 两个受保护路径仍只允许状态检查；本轮无暂存、提交、推送、tag、历史改写或其他仓库修改。
- Batch D 的 Legacy Upgrade 前置条件错误不在本轮处理，不通过跳过或修改它来使全量绿灯。

## 附录：本轮完整实现 diff

下列 diff 为最终实现的四个 tracked 文件及一个新增 test-only helper；未包含报告自身的递归 diff。

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index c5278d0..2828a4f 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -55,6 +55,19 @@ jobs:
             echo "FINANCE_OS_V19_IDENTITY=$historical_root/identity.properties"
             echo "FINANCE_OS_B2_EVIDENCE_ROOT=$historical_root"
           } >> "$GITHUB_ENV"
+      - name: Build and verify current managed-process application
+        shell: bash
+        run: |
+          set -euo pipefail
+          current_head="$(git -C "$GITHUB_WORKSPACE" rev-parse HEAD)"
+          test "$current_head" = "$GITHUB_SHA"
+          current_root="$(mktemp -d "$RUNNER_TEMP/finance-os-c9-current.XXXXXX")"
+          ./mvnw -B -DskipTests clean package
+          cp target/finance-os-backend-1.0.0.jar "$current_root/app.jar"
+          java src/test/java/com/financeos/integration/CurrentManagedProcessArtifact.java \
+            "$GITHUB_WORKSPACE" "$current_root/app.jar"
+          test "$(git -C "$GITHUB_WORKSPACE" rev-parse HEAD)" = "$current_head"
+          echo "FINANCE_OS_C9_CURRENT_JAR=$current_root/app.jar" >> "$GITHUB_ENV"
       - name: Run backend tests
         run: ./mvnw -B clean test

diff --git a/backend/src/test/java/com/financeos/integration/TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest.java b/backend/src/test/java/com/financeos/integration/TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest.java
index 1f0cfc0..78e2001 100644
--- a/backend/src/test/java/com/financeos/integration/TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest.java
+++ b/backend/src/test/java/com/financeos/integration/TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest.java
@@ -14,6 +14,7 @@ import java.nio.file.SecureDirectoryStream;
 import java.nio.file.StandardOpenOption;
 import java.nio.file.attribute.BasicFileAttributes;
 import java.nio.channels.FileChannel;
+import java.nio.channels.FileLock;
 import com.sun.nio.file.ExtendedOpenOption;
 import java.util.UUID;
 import java.util.concurrent.CountDownLatch;
@@ -206,13 +207,48 @@ class TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest extends Po
         Path control = Files.createFile(root.resolve("control-lock"));
         boolean rootRenameDenied;
         boolean ancestorRenameDenied;
-        try (FileChannel channel = FileChannel.open(control, StandardOpenOption.READ, StandardOpenOption.WRITE,
-                LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE)) {
+        boolean windows = System.getProperty("os.name").startsWith("Windows");
+        try (FileChannel channel = windows
+                ? FileChannel.open(control, StandardOpenOption.READ, StandardOpenOption.WRITE,
+                        LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE)
+                : FileChannel.open(control, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
+             FileLock guard = channel.lock(0, 1, false)) {
             assertThat(channel.isOpen()).isTrue();
+            assertThat(guard.isValid()).isTrue();
             rootRenameDenied = renameDenied(root, parent.resolve("moved-managed"));
             ancestorRenameDenied = renameDenied(parent, directory.resolve("moved-parent"));
+            if (windows) {
+                assertThat(rootRenameDenied).isTrue();
+                assertThat(ancestorRenameDenied).isTrue();
+            } else {
+                // A POSIX advisory lock protects cooperative I/O, not the directory namespace.
+                assertThat(rootRenameDenied).isFalse();
+                assertThat(ancestorRenameDenied).isFalse();
+                Path movedParent = directory.resolve("moved-parent");
+                Path movedRoot = movedParent.resolve("moved-managed");
+                assertThat(Files.isRegularFile(movedRoot.resolve("control-lock"))).isTrue();
+                Files.createDirectories(root);
+                try (FileChannel replacement = FileChannel.open(Files.createFile(root.resolve("control-lock")),
+                        StandardOpenOption.READ, StandardOpenOption.WRITE);
+                     FileLock replacementGuard = replacement.tryLock(0, 1, false);
+                     var stream = Files.newDirectoryStream(movedRoot)) {
+                    assertThat(replacementGuard).as("a new path denotes a different lock inode").isNotNull();
+                    assertThat(stream).isInstanceOf(SecureDirectoryStream.class);
+                    @SuppressWarnings("unchecked")
+                    var secure = (SecureDirectoryStream<Path>) stream;
+                    // The real Linux directory handle keeps relative I/O on the opened original root.
+                    Files.move(movedRoot, movedParent.resolve("renamed-again"));
+                    try (var output = secure.newByteChannel(Path.of("bound-payload"), java.util.Set.of(
+                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
+                        output.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
+                    }
+                    assertThat(Files.exists(movedParent.resolve("renamed-again/bound-payload"))).isTrue();
+                    assertThat(Files.exists(root.resolve("bound-payload"))).isFalse();
+                    assertThat(guard.isValid()).isTrue();
+                }
+            }
         }
-        System.out.println("C9 Windows control-handle capability: rootRenameDenied=" + rootRenameDenied
+        System.out.println("C9 control-handle capability: windows=" + windows + "; rootRenameDenied=" + rootRenameDenied
                 + "; ancestorRenameDenied=" + ancestorRenameDenied);
         if (rootRenameDenied && ancestorRenameDenied) {
             // The same operations must succeed after the handle closes: distinguish pinning from permissions.
diff --git a/backend/src/test/java/com/financeos/integration/TransactionImportLifecycleGuardPrototypePostgresIntegrationTest.java b/backend/src/test/java/com/financeos/integration/TransactionImportLifecycleGuardPrototypePostgresIntegrationTest.java
index 76879fa..21d95c6 100644
--- a/backend/src/test/java/com/financeos/integration/TransactionImportLifecycleGuardPrototypePostgresIntegrationTest.java
+++ b/backend/src/test/java/com/financeos/integration/TransactionImportLifecycleGuardPrototypePostgresIntegrationTest.java
@@ -18,6 +18,7 @@ import java.nio.file.Files;
 import java.nio.file.LinkOption;
 import java.nio.file.Path;
 import java.nio.file.StandardOpenOption;
+import java.nio.file.attribute.BasicFileAttributes;
 import java.util.UUID;
 import java.util.concurrent.ArrayBlockingQueue;
 import java.util.concurrent.BlockingQueue;
@@ -115,6 +116,9 @@ class TransactionImportLifecycleGuardPrototypePostgresIntegrationTest extends Po
             worker.expect("DENIED");
             worker.expectSuccessfulExit();
         }
+        try (FileChannel cleaner = channel(control); FileLock released = cleaner.tryLock(0, 1, false)) {
+            assertThat(released).as("denied child exit must release its native guard").isNotNull();
+        }
         assertThat(Files.exists(directory.resolve(reference.toString()))).isFalse();
         assertState(reference, "DELETED");
         System.out.println("C9 positive guard: authority rechecked inside acquired guard; stale creation denied");
@@ -152,7 +156,10 @@ class TransactionImportLifecycleGuardPrototypePostgresIntegrationTest extends Po
         UUID reference = reserve("LIVE");
         Files.writeString(original.resolve(reference.toString()), "synthetic", StandardOpenOption.CREATE_NEW);
         Path configuredPath = original.toRealPath();
-        Object volumeSerial = Files.getFileStore(original).getAttribute("volume:vsn");
+        var originalStore = Files.getFileStore(original);
+        Object volumeSerial = isWindows() ? originalStore.getAttribute("volume:vsn") : null;
+        Object originalKey = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
+        Object replacementKey = Files.readAttributes(replacement, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
         assertThat(original.toRealPath()).isNotEqualTo(replacement.toRealPath());
         assertThat(Files.readString(original.resolve("storage-identity")))
                 .isEqualTo(Files.readString(replacement.resolve("storage-identity")));
@@ -161,25 +168,46 @@ class TransactionImportLifecycleGuardPrototypePostgresIntegrationTest extends Po
         Files.move(replacement, original);
         assertThat(original.toRealPath()).isEqualTo(configuredPath);
         assertThat(Files.readString(original.resolve("storage-identity"))).isEqualTo(storageId);
-        assertThat(Files.getFileStore(original).getAttribute("volume:vsn")).isEqualTo(volumeSerial);
+        if (isWindows()) {
+            assertThat(Files.getFileStore(original).getAttribute("volume:vsn")).isEqualTo(volumeSerial);
+        } else {
+            assertThat(Files.getFileStore(original)).isEqualTo(originalStore);
+            Object mountedKey = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
+            assertThat(mountedKey).isEqualTo(replacementKey);
+            if (originalKey != null && replacementKey != null) {
+                assertThat(mountedKey).isNotEqualTo(originalKey);
+                assertThat(Files.readAttributes(archived, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())
+                        .isEqualTo(originalKey);
+            }
+            System.out.println("C9 identity capability: fileKeyAvailable=" + (originalKey != null && replacementKey != null)
+                    + "; FileStore/fileKey are not authenticated storage identities");
+        }
         retire(reference);
         assertThat(Files.deleteIfExists(original.resolve(reference.toString()))).isFalse();
         // An intentionally unsafe model accepts marker + serial + path and records false absence.
         jdbcTemplate.update("UPDATE c9_guard_registry SET state='DELETED' WHERE reference=? AND state='RETIRED'", reference);
         assertState(reference, "DELETED");
         assertThat(Files.isRegularFile(archived.resolve(reference.toString()))).isTrue();
-        System.out.println("C9 identity counterexample: same path + copied marker + same volume serial; false DELETED with original payload present");
+        System.out.println("C9 identity counterexample: same path + copied marker + same unauthenticated store; false DELETED with original payload present");
     }

     @Test
     void probesProviderVolumeSerialWithoutClaimingAnAuthenticatedVolumeIdentity() throws Exception {
         var first = Files.getFileStore(directory);
         var second = Files.getFileStore(directory);
-        Object serial = first.getAttribute("volume:vsn");
-        assertThat(serial).isInstanceOf(Integer.class);
-        assertThat(serial).isEqualTo(second.getAttribute("volume:vsn"));
-        System.out.println("C9 Windows volume capability: providerSerialAvailable=true; repeatedQueryEqual=true; filesystem="
-                + first.type());
+        if (isWindows()) {
+            Object serial = first.getAttribute("volume:vsn");
+            assertThat(serial).isInstanceOf(Integer.class);
+            assertThat(serial).isEqualTo(second.getAttribute("volume:vsn"));
+            System.out.println("C9 Windows volume capability: providerSerialAvailable=true; repeatedQueryEqual=true; filesystem="
+                    + first.type());
+        } else {
+            assertThat(second).isEqualTo(first);
+            Object key = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
+            assertThat(Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey()).isEqualTo(key);
+            System.out.println("C9 filesystem capability: filesystem=" + first.type() + "; fileKeyAvailable=" + (key != null)
+                    + "; authenticatedVolumeIdentity=false");
+        }
     }

     private UUID reserve(String state) {
@@ -250,17 +278,18 @@ class TransactionImportLifecycleGuardPrototypePostgresIntegrationTest extends Po

     private Worker start(UUID reference, String operation) throws Exception {
         return new Worker(new ProcessBuilder(
-                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
+                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString(),
                 "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                 GuardWorker.class.getName(), directory.toString(), reference.toString(), operation)
                 .redirectErrorStream(true).start());
     }

     private static FileChannel channel(Path control) throws Exception {
-        return FileChannel.open(control, StandardOpenOption.READ, StandardOpenOption.WRITE,
-                LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE);
+        return GuardWorker.channel(control);
     }

+    private static boolean isWindows() { return GuardWorker.isWindows(); }
+
     private static final class Worker implements AutoCloseable {
         private final Process process;
         private final BufferedWriter commands;
@@ -302,6 +331,7 @@ class TransactionImportLifecycleGuardPrototypePostgresIntegrationTest extends Po
         void killAndAwaitExit() throws Exception {
             process.destroyForcibly();
             assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
+            assertThat(process.exitValue()).isNotZero();
         }

         @Override
@@ -318,13 +348,21 @@ class TransactionImportLifecycleGuardPrototypePostgresIntegrationTest extends Po

     /** Standalone cooperative storage worker; no credentials and no Spring application. */
     public static final class GuardWorker {
+        // Keep the standalone child independent of the enclosing Spring/Testcontainers class initialization.
+        private static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }
+
+        private static FileChannel channel(Path control) throws Exception {
+            return isWindows()
+                    ? FileChannel.open(control, StandardOpenOption.READ, StandardOpenOption.WRITE,
+                            LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE)
+                    : FileChannel.open(control, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
+        }
+
         public static void main(String[] args) throws Exception {
             Path root = Path.of(args[0]);
             Path payload = root.resolve(UUID.fromString(args[1]).toString());
             try (var input = new BufferedReader(new InputStreamReader(System.in));
-                 FileChannel channel = FileChannel.open(root.resolve("control-lock"),
-                         StandardOpenOption.READ, StandardOpenOption.WRITE,
-                         LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE);
+                 FileChannel channel = channel(root.resolve("control-lock"));
                  FileLock guard = channel.tryLock(0, 1, false)) {
                 if (guard == null) throw new IllegalStateException("C9 child guard unavailable");
                 signal("LOCKED");
diff --git a/backend/src/test/java/com/financeos/integration/TransactionImportManagedProcessPostgresIntegrationTest.java b/backend/src/test/java/com/financeos/integration/TransactionImportManagedProcessPostgresIntegrationTest.java
index b5895c2..cfa5fcf 100644
--- a/backend/src/test/java/com/financeos/integration/TransactionImportManagedProcessPostgresIntegrationTest.java
+++ b/backend/src/test/java/com/financeos/integration/TransactionImportManagedProcessPostgresIntegrationTest.java
@@ -11,6 +11,7 @@ import org.junit.jupiter.api.io.TempDir;
 import org.springframework.beans.factory.annotation.Autowired;
 import org.springframework.core.env.Environment;
 import org.testcontainers.DockerClientFactory;
+import org.testcontainers.Testcontainers;
 import org.testcontainers.containers.GenericContainer;
 import org.testcontainers.containers.wait.strategy.Wait;
 import java.io.*;
@@ -36,7 +37,8 @@ class TransactionImportManagedProcessPostgresIntegrationTest extends PostgresInt
         Path root = directory.resolve("raw");
         TrustedImportStorageRoot.provision(root, deployment, storage, "RAW");
         try (var local = new LocalTemporaryImportFileStorage(root, 5 * 1024 * 1024, deployment, storage, "RAW", lifecycle)) {
-            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
+            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin",
+                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                     "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                     ImportManagedStorageProcessWorker.class.getName(), "WRITE").redirectErrorStream(true);
             builder.environment().putAll(workerEnvironment(root.toString(), false));
@@ -83,23 +85,23 @@ class TransactionImportManagedProcessPostgresIntegrationTest extends PostgresInt
                 if (child.isAlive()) child.destroyForcibly();
                 assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
                 child.getOutputStream().close(); reader.join(2000);
+                assertThat(reader.isAlive()).isFalse();
             }
         }
     }

     @RepeatedTest(3) void linuxPersistentVolumeUsesProductionSecureOperationsAcrossKillAndRestart() throws Exception {
-        Path jar = Path.of("target", "finance-os-backend-1.0.0.jar").toAbsolutePath();
-        assertThat(jar).isRegularFile(); // Package the current production sources before this acceptance test.
+        Path jar = CurrentManagedProcessArtifact.required();
         String volume = "finance-os-c9-owned-" + UUID.randomUUID();
         var docker = DockerClientFactory.instance().client();
         docker.createVolumeCmd().withName(volume).exec();
         String ref;
         try {
-            try (var provision = linuxWorker(volume, "PROVISION", jar)) { provision.start(); }
+            try (var provision = linuxWorker(volume, "PROVISION", jar)) { startAndVerifyPayload(provision, jar); }
             try (var writer = linuxWorker(volume, "WRITE", jar)) {
-                writer.start(); ref = onlyReference();
+                startAndVerifyPayload(writer, jar); ref = onlyReference();
                 expire(ref);
-                try (var contender = linuxWorker(volume, "CLEANUP", jar)) { contender.start(); }
+                try (var contender = linuxWorker(volume, "CLEANUP", jar)) { startAndVerifyPayload(contender, jar); }
                 assertThat(objects.find(ref).getState()).isEqualTo("RETIRED");
                 assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM transaction_import_file_objects WHERE reference=?", Integer.class, ref)).isPositive();
                 assertThat(writer.execInContainer("sh", "-c", "test -s /managed/raw/" + ref + ".part").getExitCode()).isZero();
@@ -108,7 +110,7 @@ class TransactionImportManagedProcessPostgresIntegrationTest extends PostgresInt
             }
             due(ref);
             try (var recovery = linuxWorker(volume, "CLEANUP", jar)) {
-                recovery.start();
+                startAndVerifyPayload(recovery, jar);
                 assertThat(objects.find(ref).getState()).isEqualTo("DELETED");
                 assertThat(recovery.execInContainer("sh", "-c", "test ! -e /managed/raw/" + ref + ".part && test ! -e /managed/raw/" + ref).getExitCode()).isZero();
             }
@@ -120,18 +122,22 @@ class TransactionImportManagedProcessPostgresIntegrationTest extends PostgresInt
     }

     @Test void packagedOperatorProvisionerCreatesPersistentBindingsOnceAndRefusesReinitialization() throws Exception {
+        Path jar = CurrentManagedProcessArtifact.required();
         String volume = "finance-os-c9-owned-" + UUID.randomUUID();
         var docker = DockerClientFactory.instance().client();
         docker.createVolumeCmd().withName(volume).exec();
         String command = "java -Dloader.main=com.financeos.module.importing.storage.ImportStorageProvisioner -cp /payload/app.jar org.springframework.boot.loader.launch.PropertiesLauncher --provision";
         try (var container = new GenericContainer<>("eclipse-temurin:21-jdk-jammy")
                 .withCreateContainerCmdModifier(created -> created.getHostConfig().withBinds(new Bind(volume, new Volume("/managed")),
-                        new Bind(Path.of("target", "finance-os-backend-1.0.0.jar").toAbsolutePath().toString(), new Volume("/payload/app.jar"), AccessMode.ro)))
+                        new Bind(jar.toString(), new Volume("/payload/app.jar"), AccessMode.ro)))
                 .withEnv(Map.of("FINANCE_IMPORT_STORAGE_RAW_ROOT", "/managed/raw", "FINANCE_IMPORT_STORAGE_PLAN_ROOT", "/managed/plan",
                         "FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID", deployment.toString(), "FINANCE_IMPORT_STORAGE_RAW_ID", storage.toString(), "FINANCE_IMPORT_STORAGE_PLAN_ID", UUID.randomUUID().toString()))
-                .withCommand("sh", "-c", command + " && " + command + "; result=$?; test \"$result\" -eq 2 && echo CLI_READY; sleep 300")
+                .withCommand("sh", "-c", command + "; first=$?; echo CLI_FIRST_EXIT=$first; test \"$first\" -eq 0 || exit 1; "
+                        + command + "; result=$?; echo CLI_REPEAT_EXIT=$result; test \"$result\" -eq 2 || exit 1; echo CLI_READY; sleep 300")
                 .waitingFor(Wait.forLogMessage("(?m)^CLI_READY\\r?\\n", 1).withStartupTimeout(Duration.ofSeconds(25)))) {
-            container.start();
+            startAndVerifyPayload(container, jar);
+            assertThat(container.getLogs()).contains("CLI_FIRST_EXIT=0", "CLI_REPEAT_EXIT=2");
+            System.out.println("C9 PropertiesLauncher: firstExit=0; repeatExit=2; persistent binding permissions verified");
             assertThat(container.execInContainer("sh", "-c", "test $(stat -c %a /managed/raw) = 700 && test $(stat -c %a /managed/plan) = 700 && test $(wc -c < /managed/raw/.import-binding) -eq 64 && test $(wc -c < /managed/plan/.import-control) -eq 64").getExitCode()).isZero();
         } finally {
             assertThat(volume).startsWith("finance-os-c9-owned-");
@@ -139,24 +145,40 @@ class TransactionImportManagedProcessPostgresIntegrationTest extends PostgresInt
         }
     }

-    private GenericContainer<?> linuxWorker(String volume, String mode, Path jar) {
-        String ready = switch (mode) { case "PROVISION" -> "PROVISIONED"; case "WRITE" -> "WRITING"; default -> "CLEANED"; };
+    private GenericContainer<?> linuxWorker(String volume, String mode, Path jar) throws java.net.UnknownHostException {
+        String ready = "WRITE".equals(mode) ? "WRITING" : "C9_EXIT_0";
         return new GenericContainer<>("eclipse-temurin:21-jdk-jammy")
                 .withCreateContainerCmdModifier(command -> command.getHostConfig().withBinds(
                         new Bind(volume, new Volume("/managed")),
                         new Bind(jar.toString(), new Volume("/payload/app.jar"), AccessMode.ro),
                         new Bind(Path.of("target", "test-classes").toAbsolutePath().toString(), new Volume("/payload/test-classes"), AccessMode.ro)))
                 .withEnv(workerEnvironment("/managed/raw", true))
-                .withCommand("sh", "-c", "mkdir /work; cd /work; jar xf /payload/app.jar; java -cp '/payload/test-classes:BOOT-INF/classes:BOOT-INF/lib/*' com.financeos.integration.ImportManagedStorageProcessWorker " + mode + "; sleep 300")
+                .withCommand("sh", "-c", "mkdir /work && cd /work && jar xf /payload/app.jar && java -cp '/payload/test-classes:BOOT-INF/classes:BOOT-INF/lib/*' com.financeos.integration.ImportManagedStorageProcessWorker "
+                        + mode + "; result=$?; echo C9_EXIT_$result; test \"$result\" -eq 0 || exit 1; sleep 300")
                 .waitingFor(Wait.forLogMessage("(?m)^" + ready + "\\r?\\n", 1).withStartupTimeout(Duration.ofSeconds(25)));
     }
-    private Map<String, String> workerEnvironment(String root, boolean linux) {
+    private Map<String, String> workerEnvironment(String root, boolean linux) throws java.net.UnknownHostException {
         String url = environment.getRequiredProperty("spring.datasource.url");
-        if (linux) url = url.replaceFirst("//[^:/]+:", "//host.docker.internal:");
+        if (linux) {
+            // Forward a JVM-local mapped port; an external Docker host must remain externally addressed.
+            var uri = java.net.URI.create(url.substring("jdbc:".length()));
+            if (java.net.InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
+                Testcontainers.exposeHostPorts(uri.getPort());
+                url = url.replace("//" + uri.getHost() + ":", "//host.testcontainers.internal:");
+            }
+        }
         return Map.of("C9_DB_URL", url, "C9_DB_USER", environment.getRequiredProperty("spring.datasource.username"),
                 "C9_DB_PASSWORD", environment.getRequiredProperty("spring.datasource.password"),
                 "C9_DEPLOYMENT", deployment.toString(), "C9_STORAGE", storage.toString(), "C9_SESSION", UUID.randomUUID().toString(), "C9_ROOT", root);
     }
+    private void startAndVerifyPayload(GenericContainer<?> container, Path jar) throws Exception {
+        container.start();
+        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
+        var actual = container.execInContainer("sha256sum", "/payload/app.jar");
+        assertThat(actual.getExitCode()).isZero();
+        assertThat(actual.getStdout().strip()).isEqualTo(sha + "  /payload/app.jar");
+        System.out.println("C9 container payload: /payload/app.jar sha256=" + sha + "; /managed uses test-owned named volume");
+    }
     private String onlyReference() { return jdbcTemplate.queryForObject("SELECT reference FROM transaction_import_file_objects WHERE storage_id=CAST(? AS UUID)", String.class, storage.toString()); }
     private void expire(String ref) { jdbcTemplate.update("UPDATE transaction_import_file_objects SET created_at=CURRENT_TIMESTAMP-INTERVAL '25 hours',lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second',next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE reference=?", ref); }
     private void due(String ref) { jdbcTemplate.update("UPDATE transaction_import_file_objects SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE reference=?", ref); }
diff --git a/backend/src/test/java/com/financeos/integration/CurrentManagedProcessArtifact.java b/backend/src/test/java/com/financeos/integration/CurrentManagedProcessArtifact.java
new file mode 100644
index 0000000..0cd22d0
--- /dev/null
+++ b/backend/src/test/java/com/financeos/integration/CurrentManagedProcessArtifact.java
@@ -0,0 +1,141 @@
+package com.financeos.integration;
+
+import java.nio.charset.StandardCharsets;
+import java.nio.file.*;
+import java.security.MessageDigest;
+import java.util.*;
+import java.util.concurrent.TimeUnit;
+import java.util.jar.JarFile;
+
+/** Test-only current build gate, also runnable in Java source-file mode before clean test. */
+public final class CurrentManagedProcessArtifact {
+    public static void main(String[] args) throws Exception {
+        if (args.length != 2) throw new IllegalArgumentException("Expected repository and externally copied current JAR");
+        Path repository = Path.of(args[0]).toRealPath();
+        Path jar = Path.of(args[1]).toRealPath();
+        require(!jar.startsWith(repository.resolve("backend/target")), "Current JAR must survive Maven clean");
+        String head = verifySources(repository);
+        String sha = verifyJar(repository, jar);
+        Properties identity = new Properties();
+        identity.setProperty("head", head);
+        identity.setProperty("sha256", sha);
+        try (var out = Files.newOutputStream(identityPath(jar), StandardOpenOption.CREATE_NEW)) {
+            identity.store(out, "Current HEAD build identity; keep adjacent to the JAR");
+        }
+        System.out.println("C9_CURRENT_JAR head=" + head + " sha256=" + sha);
+    }
+
+    public static Path required() throws Exception {
+        String configured = System.getenv("FINANCE_OS_C9_CURRENT_JAR");
+        require(configured != null && !configured.isBlank(),
+                "Missing FINANCE_OS_C9_CURRENT_JAR: run mvnw -B -DskipTests clean package, copy the JAR outside target, "
+                        + "then run java src/test/java/com/financeos/integration/CurrentManagedProcessArtifact.java <repository> <copied-jar>");
+        Path jar = Path.of(configured).toRealPath();
+        Path repository = Path.of("..").toRealPath(); // Maven runs this class from backend.
+        require(!jar.startsWith(repository.resolve("backend/target")), "Current JAR must be outside target");
+        Properties identity = new Properties();
+        try (var in = Files.newInputStream(identityPath(jar))) { identity.load(in); }
+        String head = verifySources(repository);
+        require(head.equals(identity.getProperty("head")), "Current JAR HEAD mismatch");
+        String sha = verifyJar(repository, jar);
+        require(sha.equals(identity.getProperty("sha256")), "Current JAR SHA-256 mismatch");
+        System.out.println("C9_CURRENT_JAR_VERIFIED head=" + head + " sha256=" + sha);
+        return jar;
+    }
+
+    private static Path identityPath(Path jar) { return jar.resolveSibling(jar.getFileName() + ".identity.properties"); }
+
+    private static String verifySources(Path repository) throws Exception {
+        String head = git(repository, "rev-parse", "HEAD").strip();
+        var expected = new TreeSet<String>();
+        // Deliberately inspect only production inputs, never protected user task files.
+        for (String entry : git(repository, "ls-tree", "-r", "HEAD", "backend/src/main", "backend/pom.xml").lines().toList()) {
+            String[] fields = entry.split("\t", 2);
+            String[] metadata = fields[0].split(" ");
+            require(metadata[1].equals("blob"), "Unexpected current source entry");
+            Path file = repository.resolve(fields[1]);
+            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Missing current production input: " + fields[1]);
+            byte[] bytes = Files.readAllBytes(file);
+            if (!blob(bytes).equals(metadata[2])) {
+                require(blob(new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")
+                        .getBytes(StandardCharsets.UTF_8)).equals(metadata[2]), "Production input differs from HEAD: " + fields[1]);
+            }
+            expected.add(fields[1]);
+        }
+        var actual = new TreeSet<String>();
+        actual.add("backend/pom.xml");
+        try (var files = Files.walk(repository.resolve("backend/src/main"))) {
+            files.filter(path -> !Files.isDirectory(path)).forEach(path -> actual.add(repository.relativize(path).toString().replace('\\', '/')));
+        }
+        require(!expected.isEmpty() && actual.equals(expected), "Current production source inventory mismatch");
+        return head;
+    }
+
+    private static String verifyJar(Path repository, Path jar) throws Exception {
+        require(Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS), "Current JAR must be a regular file");
+        Path classes = repository.resolve("backend/target/classes");
+        try (JarFile archive = new JarFile(jar.toFile())) {
+            var manifest = archive.getManifest().getMainAttributes();
+            require("org.springframework.boot.loader.launch.JarLauncher".equals(manifest.getValue("Main-Class")), "Wrong current Boot launcher");
+            require("com.financeos.FinanceOsApplication".equals(manifest.getValue("Start-Class")), "Wrong current application");
+            for (String name : List.of("BOOT-INF/classes/com/financeos/FinanceOsApplication.class",
+                    "BOOT-INF/classes/com/financeos/module/importing/storage/ImportStorageProvisioner.class",
+                    "org/springframework/boot/loader/launch/JarLauncher.class",
+                    "org/springframework/boot/loader/launch/PropertiesLauncher.class")) {
+                require(archive.getJarEntry(name) != null, "Missing current JAR entry: " + name);
+            }
+            var packaged = new TreeSet<String>();
+            var migrations = new TreeSet<Integer>();
+            for (var entry : archive.stream().filter(item -> !item.isDirectory()).toList()) {
+                if (!entry.getName().startsWith("BOOT-INF/classes/")) continue;
+                String relative = entry.getName().substring("BOOT-INF/classes/".length());
+                require(packaged.add(relative), "Duplicate current class/resource: " + relative);
+                try (var in = archive.getInputStream(entry)) {
+                    require(Files.isRegularFile(classes.resolve(relative))
+                                    && MessageDigest.isEqual(in.readAllBytes(), Files.readAllBytes(classes.resolve(relative))),
+                            "Current packaged class/resource differs from fresh build: " + relative);
+                }
+                if (relative.startsWith("db/migration/")) {
+                    var match = java.util.regex.Pattern.compile("db/migration/V(\\d+)__.+\\.sql").matcher(relative);
+                    require(match.matches() && migrations.add(Integer.parseInt(match.group(1))), "Invalid current migration entry");
+                }
+            }
+            var built = new TreeSet<String>();
+            try (var files = Files.walk(classes)) {
+                files.filter(Files::isRegularFile).forEach(path -> built.add(classes.relativize(path).toString().replace('\\', '/')));
+            }
+            require(!packaged.isEmpty() && packaged.equals(built), "Current compiled inventory mismatch");
+            require(migrations.equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1, 21).boxed().toList())),
+                    "Current JAR must contain exactly V1-V21 migrations");
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
+        var command = new ArrayList<>(List.of("git", "-C", repository.toString()));
+        command.addAll(List.of(args));
+        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
+        var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
+            try { return process.getInputStream().readAllBytes(); }
+            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
+        });
+        if (!process.waitFor(20, TimeUnit.SECONDS)) {
+            process.destroyForcibly();
+            require(process.waitFor(5, TimeUnit.SECONDS), "Current identity git process did not exit");
+            throw new IllegalStateException("Current identity git timed out");
+        }
+        String text = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
+        require(process.exitValue() == 0, "Current identity git failed");
+        return text;
+    }
+
+    private static void require(boolean condition, String message) {
+        if (!condition) throw new IllegalStateException(message);
+    }
+}
```
