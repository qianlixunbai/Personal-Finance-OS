# G4-3 Batch B2-2 Implementation Review

日期：2026-10-14（Asia/Shanghai）。用户明确批准本轮限定实施、构建、测试及两名 Luna Max 只读复审；不 commit、push、tag，不关闭 B2-2，不启动 Batch C/D，不授权 Finance Freeze。

## 1. 结论

**B2-2 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**

固定历史 V19 JAR 的真实 HTTP Confirm → 旧 JVM 完全退出 → 离线 V20 → 独立 V21 → 全新当前 Context 的 HTTP Delete / Receipt GET / 原请求同 Key Replay 已实际通过。目标完整类在独立定向运行和 Backend 全量运行中各通过一次，均为 1 test、0 failures/errors/skipped。V20/V21 关联完整类定向运行共 8 tests，全部通过；两名指定 `gpt-6-luna / max` 只读复审未发现本轮 P0/P1。

**Backend 全量未绿，Hosted Ubuntu CI 未验证。** 本轮实际全量为 562 tests、0 failures、3 errors、0 skipped；3 errors 全部来自未修改的 `TransactionImportLegacyUpgradePostgresIntegrationTest` 缺少独立 C9 验收构件参数。此结果不能宣传为整个 Backend、CI、Batch B2 或 G4-3 已恢复绿灯。本结论仅提交 B2-2 限定实施的人工验收候选。

## 2. 实施依据、仓库与边界

- 分支：`zh-cn`；开始及完成 HEAD：`e4a5eea57d7ff292ba219ba0c6373f8d1b110075`。
- 初始 tracked/staged diff 为空，untracked 仅 `AGENTS.md`、`docs/codex-task.md`。
- B2 Design Review 由用户在聊天中补充：V19 采用真实历史进程，消费且引用清空后才升级；禁止当前代码伪造 V19 业务、禁止 SQL 制造 Confirm 成功。
- [Phase 0 正式报告](F:/IDEA/finance-os-evidence/G4-3-B2-2-Phase0-20261014-5befc478-3554-4744-89a5-a0b62f5023be/G4-3-B2-2-Phase0-Feasibility-Review.md) 已为 FEASIBILITY GO。本轮只读核对其报告及身份、运行证据，复用构件，没有重复 Phase 0 smoke。
- 受保护路径没有修改、hash、删除或暂存。首轮实施批准前的只读预检曾误读根 `AGENTS.md`，已向用户说明；后续实施不再读取。`docs/codex-task.md` 未读取。不能将整轮描述为两个文件都仅检查过状态。

实际改动仅有以下文件：

| 文件 | 用途 |
| --- | --- |
| `backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest.java` | 替换当前 Bean 限制旧 Schema 的错误组合，实施真实历史升级与 Receipt/Replay 测试 |
| `backend/src/test/java/com/financeos/integration/historical/HistoricalV19Artifact.java` | 固定源码、构件与迁移集合的 fail-closed 身份检查；CI 构建后生成本次身份记录 |
| `backend/src/test/java/com/financeos/integration/historical/HistoricalV19BootLauncher.java` | 独立进程 stdin 正常退出控制，调用真实历史 Boot loader / 生产 main |
| `backend/src/test/java/com/financeos/integration/historical/HistoricalV19Process.java` | 隔离 classpath、目录、HTTP 端口及 JVM 启停验证 |
| `.github/workflows/ci.yml` | 仅在原 Ubuntu Backend job 增加固定历史构件准备步骤 |
| 本 Review | 实施事实、结果与限制 |

没有修改生产 Java、Migration SQL、POM、其他测试或其他 CI job；没有新依赖、分支、worktree、提交、推送或 tag。`git diff --check` 通过，staged diff 为空，最终路径状态无范围外新增改动。

## 3. 历史构件身份及 CI

| 项目 | 本轮实际核对 |
| --- | --- |
| 固定历史 commit | `7d2d83c23abeaa64f0f5854f729ea919509b551a` |
| 固定 backend tree | `7a5eb4e71c9f44724317e1814fb18e873ad3bd03` |
| 本机复用 JAR SHA-256 | `D9F0D71C8D007B0198F3046F959C3CC1FCBBF5A610E9EDFCDC57A0A7468E06BF` |
| 本机 JAR | Phase 0 独立 `source/backend/target/finance-os-backend-1.0.0.jar`，位于当前仓库 `backend/target` 之外 |
| 源码 | 与固定 Git tree 逐文件核对；允许 raw blob 或 CRLF→LF 后的 blob 身份，不修改导出源码 |
| 构件 | Manifest loader/main、所有打包应用类/资源与该次 `target/classes` 对照，迁移恰好 V1–V19，无 V20/V21 |
| 运行前后 | 重算该次构件 SHA-256，要求不变 |

CI 使用 `git fetch` 固定 SHA、校验 tree、`git archive` 导出 backend，在 `RUNNER_TEMP` 独立目录执行 `bash ./mvnw -B -DskipTests package`，随后使用 JDK source-file mode 运行身份 helper。构建仅打包，历史业务测试明确跳过；它不是历史测试 PASS。

本次构建的 JAR SHA 写入 `identity.properties`，通过 `FINANCE_OS_V19_IDENTITY` 提供给当前测试；当前应用仍执行原 `./mvnw -B clean test`，不会删除仓库外的历史构件。没有将 Windows 的 JAR SHA 写成 Ubuntu 必须相同的常量。helper 在目标测试运行时再次验证源码、打包内容与该次 SHA。

本轮实际执行了缺失身份配置、JAR 缺失、错误 commit、错误 SHA-256 四种门槛探针，均得到预期失败；没有 skip、Assumption 或失败容忍。版本检查要求成功历史恰为 1…19、1…20、1…21，迁移阶段各恰好执行一条 migration。探针只调用身份 helper，不代表另外四次集成测试。

Workflow 已通过 YAML 解析、新增 Bash 段 `bash -n`，并比较确认其他 Backend steps 和其他 jobs 不变。Bash 语法检查运行于本机 Git Bash，**不是 Hosted Ubuntu 实跑**。

## 4. 实际业务与迁移证据

1. 测试独占 `postgres:17-alpine`，实际 Flyway 日志为 PostgreSQL 17.11；数据库业务表在启动前为空。没有连接日常开发数据库。
2. 历史 JVM 的 classpath 仅含单独复制的测试控制器 class 与固定历史 JAR；不加载当前应用 classes。控制器核对历史 `JarLauncher` CodeSource，再调用 JAR 自带 loader 和未修改的生产 main；main 返回 marker 与 readiness 200/UP 联合确认启动。
3. 历史应用使用 prod profile、真实 Cleanup，HTTP 只绑定 `127.0.0.1` 随机端口。经真实 HTTP 注册、登录、创建账户/分类、上传 CSV / Preview、Confirm；没有使用 SQL 插入业务成功 fixture，也没有直接调用 Confirm Service。
4. 首次 Confirm 要求 `idempotentReplay=false`；真实创建 1 笔 12.34 元 EXPENSE，账户 0.00→-12.34。校验 Receipt 行号 2、账户 Impact 的完整金额、数量和独立重算 Digest。精确原请求字节、幂等 Key、原 Confirm 响应及全部业务表快照保存在仓库外。
5. 迁移前硬断言 Session 为 CONSUMED、`consumed_at` 非空、`temporary_storage_reference` 和 `plan_storage_reference` 均 NULL。不满足即失败/HOLD，不清字段、不搬运未知 payload、不绕过 C9。V19 历史 FK 必须存在且 validated。
6. 发送 stdin STOP，让真实 Spring shutdown hook 关闭旧应用；要求正常退出、exit=0、PID 消失、捕获的子进程均退出、graceful shutdown/Hikari 关闭日志齐全、构件未变。超时的强制终止仅用于失败清理，不能满足迁移门槛。随后独立 SQL 观察要求该数据库其他 client connections=0。
7. 独立 target=20：恰好 1 条 migration、当前 V20、validate 通过。全部既有业务表逐行 JSONB 快照不变；owned Batch FK、transaction ID 非空与三个 immutable trigger 保留，旧 Transaction FK 移除，新 INSERT reference trigger/function 存在且启用，函数包含 owned reference 与 `FOR KEY SHARE`。
8. 再独立 target=21：恰好 1 条 migration、当前 V21、validate 通过。旧业务表快照不变，只新增两张 Registry/Binding 表，离线阶段两表均空；V20 catalog 合同再次成立。
9. 此后才创建全新当前 Spring Context，使用生产 Storage bean 和真实 `test` profile 的隔离 C9 预配，不注入 Mock Storage。保留历史 Confirm token secret；同步启动清理返回后，RAW/PLAN 两个非 quarantine Binding 存在、File Registry 为空，全部旧业务快照不变。当前周期 Cleanup 在此测试中禁用，启动清理未绕过。
10. 在当前应用重新 HTTP 登录，走真实密码/JWT 链；Delete 后交易数为 0，余额恢复 0.00。账户除 `updated_at` 外恢复为 Confirm 前完整记录，Session/Batch/Item/Impact 逐字段不变，Item 仍保留原 transaction ID。
11. 当前 HTTP Receipt GET 必须完整等于原 Receipt；使用同一 Key、原请求字节 Replay 必须 `idempotentReplay=true`、Receipt 完整相同。GET 和 Replay 各自前后均比较全部当前业务表，断言无新 Transaction、第二次余额变化或 Registry 变化。
12. 新 Context 关闭后验证 Hikari closed、真实 root closed、worker terminated、control channel closed、数据库无其他 client connections。

以上生命周期、catalog 和金融检查均为本轮通过测试中的无条件断言。日志未逐项打印的内部值以通过断言为证据，不伪称每个内部字段都有独立日志记录。此夹具只覆盖已消费且无活跃引用的升级；不代替 C9 活跃 Session/payload 离线迁移验收。

## 5. 本轮实际测试结果

命令在 `backend` 执行，`JAVA_HOME=F:/ideajava/jdk21`，同时设置 `FINANCE_OS_V19_IDENTITY` 和 `FINANCE_OS_B2_EVIDENCE_ROOT` 指向本轮仓库外证据目录。没有对目标测试使用 skip、@Disabled、Assumption 或修改生产行为。

| 执行 | tests | failures | errors | skipped | 结束时间（+08:00） |
| --- | ---: | ---: | ---: | ---: | --- |
| 目标完整类 attempt1 | 1 | 1 | 0 | 0 | 21:50:36 |
| 修正后目标完整类 attempt2 | 1 | 0 | 0 | 0 | 21:52:25 |
| V20 + V21 关联完整类 | 8 | 0 | 0 | 0 | 21:53:19 |
| Backend 全量 | 562 | 0 | 3 | 0 | 22:03:37 |

实际 Maven 命令：

```powershell
.\mvnw.cmd -B '-Dtest=FlywayTransactionImportVersionTwentyUpgradeRuntimeIntegrationTest' test
.\mvnw.cmd -B '-Dtest=FlywayTransactionImportVersionTwentyMigrationIntegrationTest,FlywayTransactionImportVersionTwentyOneMigrationIntegrationTest' test
.\mvnw.cmd -B test
```

attempt1 根因是新增测试误将 CSV 首条数据行预期为 1；固定历史 `CsvTransactionImportParser` 按物理行定位，表头占第 1 行，故应为 2。核对旧源码后修正精确行号断言，未弱化金融断言、删除测试或修改生产代码。该次历史 Confirm 已真实成功且 JVM 正常退出，失败记录独立保留。

全量运行耗时 09:28，81 份 XML 汇总与 Maven 最终统计一致。全量中的目标完整类再次为 1/0/0/0；V20 为 6/0/0/0，V21 为 2/0/0/0；基础 Flyway、V17、Transfer V19 三个关联类各 1/0/0/0。这些数字包含在 562 中，不额外累加。

3 个 errors 全部是 `TransactionImportLegacyUpgradePostgresIntegrationTest` 的 `@BeforeEach isolatedV20DatabaseAndStorage:96` 调用 `required:510` 时缺少 Java system property `c9.legacy.evidence`。源码后续还需要单独的 `c9.legacy.jar` 和当前打包 JAR；本轮的 V19 构件不能冒充该测试要求的 V20 构件。没有修改该测试、POM 或增加这项范围外 CI 准备。没有运行修改前的全量 baseline；此归因来自本轮报错与未修改源码的参数读取链，不能描述成修改前曾实际复现。

## 6. 两名 Luna Max 只读复审

用户指定 `gpt-6-luna`、`max`、`fork_turns=none`；文件职责划分明确，两名审查员均不修改文件、不运行 Maven/Java/Docker。

| 审查员 | 范围 | 结论 |
| --- | --- | --- |
| `/root/b2_2_business_review` | 历史业务、金融一致性、Receipt/Replay；只读核对 attempt2 日志、结果及快照 | 无 P0/P1；原 Receipt、余额恢复与 Replay 合同成立 |
| `/root/b2_2_runtime_review` | JVM 生命周期、构件身份、Storage、Ubuntu CI | 无 P0/P1；保留下面的路径校验 P2；独立核对既有 C9 测试参数缺失的源码链 |

主控复核了上述结论所依据的实际代码和证据，没有因子 Agent 结论而修改生产行为，也没有将只读复审表述为另一次独立实跑。

全量结束后，两名审查员均只读补核最终 XML/日志汇总，确认 81 suites、562/0/3/0、唯一 C9 setup error suite 与目标类再次 1/0/0/0。生命周期审查员还核对全量成功目录的 PID 32832 退出记录及关闭日志；没有重新运行实现 review 或测试。

## 7. 证据与重跑入口

本轮证据根：

`F:/IDEA/finance-os-evidence/G4-3-B2-2-Implementation-20261014-c3e79af9-eb4f-402e-a897-12babc941b34`

- [身份核验输出](F:/IDEA/finance-os-evidence/G4-3-B2-2-Implementation-20261014-c3e79af9-eb4f-402e-a897-12babc941b34/identity-verification.log)、`identity.properties`；四种 `gate-*.log` 保留门槛负例。
- [目标成功日志](F:/IDEA/finance-os-evidence/G4-3-B2-2-Implementation-20261014-c3e79af9-eb4f-402e-a897-12babc941b34/target-class-attempt2.log)，对应 `target-class-attempt2.xml/.txt`；attempt1 文件独立保留。
- [迁移回归日志](F:/IDEA/finance-os-evidence/G4-3-B2-2-Implementation-20261014-c3e79af9-eb4f-402e-a897-12babc941b34/v20-v21-regression.log)，XML/TXT 在 `migration-reports/`。
- [全量日志](F:/IDEA/finance-os-evidence/G4-3-B2-2-Implementation-20261014-c3e79af9-eb4f-402e-a897-12babc941b34/full-backend.log)、[XML 汇总](F:/IDEA/finance-os-evidence/G4-3-B2-2-Implementation-20261014-c3e79af9-eb4f-402e-a897-12babc941b34/full-backend-summary.json)，本次实际生成的报告保存在 `full-backend-reports/`，未混入旧报告。
- [CI 静态检查](F:/IDEA/finance-os-evidence/G4-3-B2-2-Implementation-20261014-c3e79af9-eb4f-402e-a897-12babc941b34/ci-static-check.log)。
- 成功定向运行目录 `b2-2-implementation-13314726857015519903/`，历史 PID 25804；全量中的独立成功运行目录 `b2-2-implementation-4607744581233604566/`，历史 PID 32832。两者均保留历史启动/关闭日志、退出状态、原请求/Confirm 响应、Key、V19/V20/V21 快照及当前 Receipt/Replay 响应。

本地重跑时将上述 `identity.properties` 的绝对路径赋给 `FINANCE_OS_V19_IDENTITY`，证据根赋给 `FINANCE_OS_B2_EVIDENCE_ROOT`，然后执行目标 Maven 命令。身份文件引用 Phase 0 历史源码与构件，二者须继续保留。临时随机 secret 仅存在于进程环境或内存，不写入本 Review、源码、身份文件或 Git；Receipt 测试材料均为本轮合成数据。

## 8. 残余风险与停止点

- **全量/CI 阻塞**：既有 C9 Legacy Upgrade 的独立构件参数未配置，3 errors 保持可见。本轮没有修复或跳过它，不扩大到其他 Batch。
- **未验证**：Hosted Ubuntu runner、该环境的新历史 JAR 构建及完整 workflow 尚未实际运行。配置与本机语法通过不能替代远端实跑证据；未授权 push，未触发远端工作流。
- **非阻塞 P2**：身份 helper 验证内容与来源，但没有额外拒绝本地将 JAR 放在当前 `backend/target` 的路径误配置。本次实际路径和 CI `RUNNER_TEMP` 路径均满足隔离要求；应保留独立构件路径，以免后续 `clean` 删除误放文件。
- **覆盖边界**：单笔 CNY expense、无 warning、已消费且引用清空的历史 Session。多账户、未消费/过期引用、活跃 payload 搬迁、Cleanup 周期矩阵及生产部署不在本轮验收声明中。

**B2-2 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**。完成后停止，等待独立验收；不自行提交、推送、关闭 B2-2 或启动后续批次。
