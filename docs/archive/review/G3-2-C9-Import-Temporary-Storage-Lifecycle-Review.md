# G3-2 / C9 — Import Temporary Storage Lifecycle Review

日期：2026-10-11。主控：GPT-6.1 Sol High。独立复审：两名 GPT-6 Luna Max，仅只读。

更新说明：第 1–18 节保留原调查与第一次 Checkpoint；本轮人工设计批准、Design Addendum 和实施进展从第 19 节开始记录。当前结论应以最新章节为准。

## 1. 决定与授权边界

**C9 — DESIGN CHECKPOINT，暂停等待人工授权。**

真实 PostgreSQL 与隔离文件系统已确认：现有实现可以留下无引用原始文件、无引用 Plan、未完成 `.part`，也会在 Session 已插入后删除仍被引用的原始文件。现有清理查询只看到 Session 当前引用；丢失引用后，即使调用新的 Cleanup 实例的启动回调也无法回收。

局部异常补偿不能覆盖文件落盘与引用提交之间的中断，也不能持久保存被替换 Plan 的删除重试依据。完整处理需要新的生命周期机制、Storage 合同及数据库迁移，超出本轮直接实施授权。用户任务第一、六、七、十一节明确要求先提交 Design Checkpoint，等待确认。本报告提出可审阅方案，未实现该方案；C9 尚不能验收 GO。

本轮只增加调查测试和本报告，没有修改生产源码、现有测试、Flyway、金融规则、Confirm 幂等、warning acknowledgement 或 Receipt 合同。没有 commit、push、Tag，也没有进入 C10/C11、Finance Freeze 或其他项目。

## 2. Preflight

| 项目 | 实际结果 |
| --- | --- |
| 仓库 / 分支 | `F:\IDEA\daima\finance-os` / `zh-cn` |
| 指定 HEAD / 实际 HEAD | `138bccc2bcb2d5bfea17ea88ecc115604928a5be` |
| 本地 `origin/zh-cn` | 同上 |
| 独立 `git ls-remote origin refs/heads/zh-cn` | 同上；调查期间再次核对也一致 |
| 初始 tracked / staged diff | 均为空 |
| 初始 untracked | 仅 `AGENTS.md`、`docs/codex-task.md` |
| 受保护文件 | 仅通过 Git 路径状态确认；未读取、hash、修改、删除或暂存 |

已核对 README、文档导航、`docs/domain/transaction-import.md`、`docs/architecture/consistency.md` 及既有 Import 设计/验收资料。原审计关闭进度仍为 **8/11**，C1–C8 CLOSED — GO，C9–C11 未关闭；Finance 仍为 Final Stabilization。

## 3. 当前完整生命周期

```text
Preview Controller：上传用户身份 / Mapping / Rows / Cancel
  create
    → Storage.save：upload-*.part → UUID 原始文件
    → parse（实际顺序：先 parse，后插入 Session）
    → Session INSERT：MAPPING_REQUIRED，TTL 15 分钟
    → Mapping 不完整：返回，保留原始文件
    → Mapping 完整：materialize
        → 验证与冻结 rows / digests / warning identities
        → Plan Storage.save：part → UUID Plan
        → markPreviewReady：revision + 1、替换 Plan 引用
        → 删除调用开始时快照中的旧 Plan
        → 回读 Session，返回 Preview
  create 捕获 RuntimeException → 无条件删除已保存原始文件
  updateMapping → 读取 Session 快照 → parse 原始文件 → materialize / invalidate
  invalidate → 清空 DB Plan 引用 → 删除旧快照 Plan
  cancel → 读取快照 → 条件取消 → 删除快照文件 → 清空 DB 引用
  requireUsableSession 发现 EXPIRED → 删除引用文件 → 清空 DB 引用

Confirm Controller
  → 先查 committed 幂等结果（可直接重放）
  → 校验 token / state / TTL；事务外读取冻结 Plan
  → 单一金融事务：锁 Session → 锁 Accounts → 写 Transaction / Batch / Items / Impacts
     → Session CONSUMED → COMMIT
  → 事务提交后 cleanupCommittedPayloads；清理异常不回滚金融事实
Batch Controller / Receipt GET → 仅从 committed DB evidence 重建 Receipt

ApplicationReadyEvent / 现有每分钟 Scheduler
  → findExpiredForCleanup：仅终态或过期 Session 且仍有 raw / Plan 引用
  → 删除文件 → markExpired → clearCleanupReferences
  → 异常时保留引用以便下次重试；完全无引用的文件不在查询结果中
```

`finance.import.cleanup.enabled=false` 控制定时器 Bean，未关闭 Cleanup 的 ApplicationReadyEvent 回调。Cleanup 的进程内读写锁用于停止接收和排空清理工作，不是写文件 / 发布引用 / 跨实例清理的互斥协议。

## 4. 源码定位与引用状态

主要证据位置，行号以本轮未改动生产源码为准：

| 文件 / 行 | 非原子边界 |
| --- | --- |
| `TransactionImportPreviewService.java:101–125` | 保存、parse、独立 Session INSERT、materialize；外层 catch 不区分 Session 是否已提交 |
| 同文件 `:144–177` | Cancel / invalidate 用早先快照删文件，之后清空 DB 引用 |
| 同文件 `:180–199` | 先落盘 Plan，再更新引用；只有 affected rows != 1 有候选删除；数据库抛异常绕过该删除 |
| `TransactionImportSessionService.java:30–39` | Session 创建没有包住后续文件步骤的事务；不检查 INSERT 返回计数 |
| `TransactionImportSessionMapper.java:39–79` | 发布按 state / TTL 条件更新，但无 expected revision；清空引用只按 id / user |
| `TransactionImportPreviewCleanupService.java:85–104` | 只遍历 Session 引用；旧替换文件或未发布文件缺少查询入口 |
| `LocalTemporaryImportFileStorage.java:28–78` | `.part` / UUID 在 DB 引用之前创建；捕获特定异常，不覆盖模拟 Error 中断或真正进程死亡 |
| `TransactionImportConfirmService.java:85–139,314` | 金融事务、提交后清理和 DB Receipt 重建的边界 |

状态区分：有效活跃引用必须保留；候选 Plan 尚未发布不能作为 Confirm 权威；替换旧 Plan 已无当前引用但仍需持久删除重试；无引用 UUID / `.part` 是孤儿；引用存在而文件被删除是悬空引用。不能把所有残留文件都当作可立即删除的垃圾。

## 5. 故障矩阵与实际红灯

真实环境：Java 21、PostgreSQL `17-alpine` Testcontainers，实际服务端 **17.11**。测试使用 JUnit `@TempDir` 下独立 raw / plans 目录、合成 CSV/XLSX；没有读取或清理真实用户上传目录。Storage spy 调用真实文件操作，仅在指定边界注入异常或有界 latch；数据库失败由真实 PostgreSQL trigger `RAISE EXCEPTION` 注入，非 Mapper 返回值模拟。

| 场景 | 实际旧行为 | 状态 |
| --- | --- | --- |
| 原始文件已保存，Session INSERT 真正失败 | Session 0，正常补偿删 raw，金融无写入 | 绿 |
| INSERT 失败且补偿 delete 失败 | Session 0，raw 1；恢复删除能力、新 Cleanup 启动后仍 raw 1；删除异常覆盖原 DB 异常 | 红：孤儿 raw |
| Session 已插入，materialize 保存前 RuntimeException | MAPPING_REQUIRED 引用 raw，但 raw 已被外层 catch 删除 | 红：活跃悬空引用 |
| create 中 Plan 已保存，markPreviewReady DB 异常 | Session 仍 MAPPING_REQUIRED；raw 0、未发布 Plan 1 | 红：悬空 raw + 孤儿 Plan |
| updateMapping 中发布 DB 异常 | 原 revision / Plan 引用不变且有效；第二个候选 Plan 留下，过期清理后仍残留 1 | 红，三轮 |
| 替换成功，旧 Plan delete 失败 | 新 revision / Plan 有效；请求抛异常，旧 Plan 丢失重试依据；过期清理新 Plan 后旧 Plan 仍 1 | 红 |
| 两个 Mapping 同时持有旧快照，依次发布 | revision + 2；当前 Plan 有效，但中间新 Plan 未被删除，总 Plan 2、引用 1 | 红，三轮 |
| Cancel 先于候选发布 | markPreviewReady 实际条件更新 0；候选被删，Session CANCELLED，无残留 | 绿 |
| Expire / Cleanup 先于候选发布 | 候选拒绝并删除，Session EXPIRED，无残留 | 绿 |
| Confirm 先于候选发布 | Session CONSUMED，候选拒绝并删除，Receipt / Replay / 金融快照不变 | 绿 |
| Cancel 先读旧快照，Mapping 再发布新 Plan | 真实 cancel SQL 成功，Session CANCELLED 且引用清空；启动清理仍剩新 Plan 1 | 红，三轮 |
| raw 保存返回前注入 Error | Session 0，raw 1；新的 Cleanup 启动回调后仍 1 | 红 |
| Plan 保存返回前注入 Error | 原有效 Plan 保留；过期清理后未发布候选仍 1 | 红 |
| 文件写入途中注入 Error | 无 Session；`.part` 1，新 Cleanup 启动回调不能发现 | 红 |

实际 create 先 parse 后 INSERT，因此“Session 插入后 parse 失败”不是当前 create 的真实顺序；已覆盖插入后的 materialize 失败，未伪造不存在的调用顺序。未知提交结果、磁盘断电和文件系统损坏未实跑。

## 6. 中断与恢复证据边界

中断采用自定义 `Error` 在真实保存完成或写流途中注入；恢复采用同一持久 DB 与目录上的全新 Storage / Cleanup 实例调用 `cleanupOnStartup()`，时间推进一天以排除保留期因素。

这是**确定性模拟中断与等价启动回调**，不是杀 JVM、重启 Spring 应用或系统断电。它实际验证恢复查询无法定位孤儿文件；不证明真正进程重启的连接回收、事务回滚、文件系统持久化或多实例协调正确。

模拟 `Error` 仍会展开调用栈，执行 Java 的 `finally` / try-with-resources 关闭逻辑；SIGKILL 或断电不执行这些用户态清理。因此这里“等价”只限定为在相同持久状态上调用启动清理入口，不代表两种中断方式的执行过程等价。

## 7. Findings 与当前技术门槛

| 级别 | Finding | 证据与影响 |
| --- | --- | --- |
| P1 | Session 已提交后 create 无条件删除 raw | 真实悬空引用，活跃 MAPPING_REQUIRED 后续 Mapping 无法读取；违反有效 Session 的文件可用性 |
| P1 | 无引用文件缺少恢复入口 | DB 异常 / 删除失败 / 中断均复现；无法提供可靠最终回收，C9 必须处置 |
| P1 | Mapping 替换竞争与旧文件删除失败丢失回收依据 | 当前 Plan 仍有效，但被替换的 Plan 永久不可由现有查询定位，三轮竞争稳定复现 |
| P1 | Cancel / Mapping 陈旧快照丢失新 Plan | 真实 Mapper 条件取消成功后只删除旧快照 Plan，清空实际新引用；恢复清理仍 Plan 1，三轮复现 |
| P2 / 待验 | 仅剩 Plan 引用的清理候选先执行 raw.delete(null) | 静态代码可见，可能阻断后续 Plan 重试；本轮未独立构造该 DB 状态 |
| P2 / 待验 | 并发 Preview 响应 revision / warning 与本次 rows 的绑定 | SQL 无 expected revision，回读与响应组合需后续独立断言；本轮同 Mapping 竞争仅证明文件残留 |

本轮未证明金融事实丢失、越权删除或全局文件清理，未发现已实证 P0。未将磁盘容量耗尽、敏感数据留存扩大成未经实跑的事故。以上 P1 为本 C9 验收阻断项，仍 OPEN。

## 8. 最小修复判断与候选方案比较

| 方案 | 可以处理 | 不能保证 / 代价 |
| --- | --- | --- |
| 仅局部 catch / finally、事务后回调 | 部分正常异常补偿，避免直接删除已引用 raw | 文件已落盘但无引用时进程死亡；删除重试依据丢失；不可作为完整 C9 修复 |
| 目录扫描 + age / DB 引用判断 | 可以发现未登记文件 | age 和“当前未引用”不能证明没有写入或即将发布；需要共享写入 / 清理栅栏、存储归属和历史文件处置；同样必须先获授权 |
| **持久化生命周期登记，复用现有 Cleanup** | 在落盘前建立恢复依据；发布 / 替换同事务登记引用与退休；失败保留重试 | 新增 Schema、Storage 合同、数据库协调协议；推荐提交本次 Design Checkpoint 后再实现 |

## 9. 请求批准的具体 Design Checkpoint

以下是拟实施范围，**全部尚未实现**，详细字段和锁顺序应在批准后编码前复核：

1. 新增一份 additive Flyway migration 和文件生命周期表（例如 `transaction_import_file_objects`），不改已有 migration。记录随机 reference、RAW / PLAN、storage identity、user / Session 归属、状态和时间；不保存 CSV/XLSX、文件内容、原始文件名或绝对敏感路径。登记同时承担清理重试职责，属于本轮明确需授权的持久化机制。
2. **先提交 RESERVED 记录，再允许创建文件**。Storage 增加显式 reference 的受管写入；`.part` 与最终 UUID 能由同一登记定位。登记失败不写文件；写入失败、崩溃或发布失败留下可查询的 RESERVED。不能让旧 `save()` 绕过登记。
3. 写入 / 发布 / 清理通过同一数据库锁协议互斥。已有 Session 的路径统一按 **Session → 文件登记行按 reference 排序** 获取锁；创建 raw 尚无 Session 时只锁自己的 RESERVED，建立新 Session 与 LIVE 引用同事务完成，不再反向锁已有 Session。需要重新校验 state / TTL / expected revision，禁止用陈旧快照退休实际当前文件；锁等待、I/O 和事务时限必须有界。不得一边持有文件行锁，一边反向争用已有 Session 锁。
4. 发布把真实 Session 引用与 LIVE 状态同一 DB 事务提交；替换同时把真实旧 reference 记为 RETIRED。未成功发布者不能产生可 Confirm 的 token。未知提交结果先在锁内回读权威状态，不能凭客户端异常直接删除候选。
5. 复用现有启动回调和每分钟 Scheduler；不增新后台调度器。终态引用的脱离与 RETIRED 登记在 DB 中原子完成，物理删除在金融提交之后。删除失败保留登记和重试状态；重复删除已不存在文件可成功收敛。只删除登记中的受管文件，删除前在锁内检查真实 Session 引用；任何有效未过期 Session 当前引用均禁止删除。
6. RESERVED 建议至少保留 **24 小时**，RETIRED 在确认脱离引用后可回收；保留期只是附加限制，不能替代互斥与真实引用检查。Cleaner 跳过被锁定的在写文件，发布方必须重新检查候选未被退休。PostgreSQL 行锁会阻塞同一行的修改并持续到事务结束；`SKIP LOCKED` 可用于领取时跳过正在处理的登记。[PostgreSQL 17 行锁说明](https://www.postgresql.org/docs/17/explicit-locking.html)、[SELECT / SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html)。
7. storage identity 必须区分 RAW / PLAN，绑定稳定、持久且实际可达的卷 / 根目录；这需要把当前 `java.io.tmpdir` 根配置的生命周期明确化，并纳入待批准的 Storage 配置范围。登记表不能恢复外部卷丢失的字节：须定义存储不可达时的失败语义，拒绝不可用 Preview / Confirm，不用 200 掩盖；不可达期间保留 retry。多个实例访问同一受管目录时共享 DB 协议，访问不到同一实际存储的实例不得因为自己的本地路径不存在就登记 DELETED。路径只允许受管 reference，校验边界与符号链接 / reparse point，拒绝逃出 root；多主机共享文件系统未实测时明确限制支持范围。
8. 迁移现有 **DB 已引用** 文件的登记，保留活跃文件。迁移前已有的无引用 UUID / part 无法从当前 DB 恢复归属，不能假装 registry 自动解决历史孤儿：申请后续**仅限定应用管理根目录的只读 inventory**，不读取内容、不删除真实用户文件；身份不明文件保留并列待人工处置。历史 orphan 的安全处置或明确验收结论完成前，不宣称 C9 全部风险关闭。
9. 保持 Confirm 的 Session → Accounts 金融锁顺序、幂等和 warning acknowledgement、CSV/XLSX 金额规则、Receipt 重建不变。生命周期登记与金融事实解耦，清理故障不能追加 balance delta、补建 Transaction 或修改 committed evidence。
10. **读取也必须参与删除协调**：`updateMapping` 读取 raw、`getRows` 读取 Plan、Confirm 事务外读取冻结 Plan，应在短生命周期事务中按 Session → 文件登记顺序校验并读取到内存，或采用等效可证明的读租约。引用检查、读取 / unlink、确认删除的相关临界区必须被同一协议覆盖；不能仅依赖 Unix 的 open/unlink 特性，Windows 和共享卷行为要实测。Confirm 读取结束先释放生命周期锁，再进入原金融事务，仍由原 revision / digest / Plan 引用复核决定是否提交，不把文件锁带入 Session → Accounts 金融写入链。
11. 启用登记清理前，必须阻止旧版实例或其他写者绕过登记；迁移 / rollout 栅栏与失败回退需要设计审核。只有权威存储可达且受管文件确认不存在，重复 delete 才能收敛为成功；未知或不可达状态保留重试依据。

实施前设计验收点：行锁跨文件 I/O 的事务长度和等待时间必须具体化并测试。拟评估沿用现有 4 秒 lock timeout / 60 秒事务上限，但 Spring 事务 timeout 本身不能保证阻塞的文件 I/O 被取消；必须另定义 I/O 超时、失败收敛、连接回收和重试策略。仅写“有界”不是已验证证据。登记重试记录也不能因 Session 删除级联而丢失，迁移和记录保留策略需一并审核。

批准后须补齐登记失败、写入中断、事务回滚 / unknown outcome、退休失败、删除重试、多实例锁与真正受控进程重启测试；对不同 Mapping / warning 绑定、反向 Cancel / Confirm / Expire 交错逐项断言。此方案是可审阅的实施方向，不是已证明正确的实现。

## 10. 实际变更文件

仅以下两个新文件，无生产修改：

- `backend/src/test/java/com/financeos/integration/TransactionImportTemporaryLifecyclePostgresIntegrationTest.java`
- `docs/archive/review/G3-2-C9-Import-Temporary-Storage-Lifecycle-Review.md`

新增测试故意保留安全不变量红灯；没有改 expected 迎合旧行为，没有删断言、`@Disabled` 或吞掉缺陷。默认全套测试目前会因新增红灯失败，这不是修复完成状态。

## 11. 真实测试统计与开发失败保留

新增类共有 **17 个唯一测试方法**；三项 `@RepeatedTest(3)` 带来额外 6 次，完整运行应为 **23 次执行**。重复项为 DB 更新失败、双 Mapping 发布、Cancel 陈旧快照；每项三轮用真实文件 / DB 和有界 latch / Future 检查，无 sleep。

| 阶段 / 日志（均在 `backend/target/`） | 实际结果 |
| --- | --- |
| `c9-old-lifecycle.log`，初版 14 唯一方法 | 18 执行，13 failure，0 error，0 skipped；真实旧行为红灯 |
| `c9-old-lifecycle-expanded.log` | 23 执行，13 failure，3 error，0 skipped；新增 Cancel 的 `callRealMethod()` 无法调用 MyBatis 抽象方法，属于夹具错误 |
| `c9-old-lifecycle-final.log` | 23 执行，16 failure，0 error，0 skipped；其中 Cancel 三次是 `getSpiedInstance()==null` 的夹具断言，不是 C9 产品证据 |
| `c9-old-lifecycle-verified.log`，最终旧实现证据 | **23 执行，16 failure，0 error，0 skipped；17 唯一方法中 10 红、7 绿**。Cancel 使用独立 `SqlSessionTemplate.getMapper()` 获取真实 MyBatis 代理，最终三轮均到达孤儿文件断言 |
| `c9-regression.log` | **68 执行，63 个唯一方法，0 failure，0 error，0 skipped，BUILD SUCCESS**；Lifecycle 包含参数化展开 |
| `c9-compile.log` | `mvnw.cmd -B -DskipTests compile`，**BUILD SUCCESS**；生产类已是最新，没有重新编译需求 |

初版、第一次夹具错误、第二次夹具错误的 XML / TXT 分别保存在 `c9-evidence-initial/`、`c9-evidence-expanded-fixture-failure/`、`c9-evidence-expanded-fixture-failure-two/`；最终真实红灯保存在 `c9-evidence-verified-red/`。`c9-evidence-final-red/` 是第二次夹具错误运行时的早期备份，不能根据目录名字冒充最终产品证据。Surefire 类级 / XML 计数包含重复执行；Maven 末尾 summary 将重复方法合并，报告以 XML 实例数和源码唯一方法数同时说明，不混淆 17 与 23。

四轮调查运行共 87 次执行，包含同方法重复实跑与两次夹具修正过程；不等于 87 个唯一测试。最终故障类 17 个唯一方法、既有回归 63 个唯一方法（60 个 `@Test`、3 个参数化方法），合计 **80 个唯一方法**。既有 Lifecycle 的 9 个方法展开为 14 次执行。最终两类运行合计 91 次执行，75 通过、16 失败、0 error / skipped；不能把 68 个既有绿灯覆盖 16 个新增安全断言红灯。

没有编译错误或 PostgreSQL 环境失败被记为 C9 缺陷。没有“修复后最终绿灯”日志，因为生产修复尚未获授权。

## 12. 回归、Cleanup 与 Scheduler

本轮既有回归选定 Preview Service / API、Confirm、Import Lifecycle、Storage、Session 状态、Token、Mapping、CSV / XLSX Parser 共十类；Java `java.io.tmpdir` 指向新建的 `backend/target/c9-regression-temp-<随机值>`，避免使用真实上传根目录。以下均为本轮实际运行，全部 0 failure / error / skipped：

| 实际测试类 | 执行数 |
| --- | ---: |
| `TransactionImportConfirmIntegrationTest` | 17 |
| `TransactionImportLifecycleIntegrationTest` | 14 |
| `TransactionImportPreviewApiIntegrationTest` | 4 |
| `TransactionImportPreviewIntegrationTest` | 6 |
| `TransactionImportMappingTest` | 1 |
| `CsvTransactionImportParserTest` | 2 |
| `XlsxTransactionImportParserTest` | 13 |
| `TransactionImportPreviewTokenServiceTest` | 3 |
| `TransactionImportSessionStateTest` | 4 |
| `LocalTemporaryImportFileStorageTest` | 4 |
| 合计 | **68** |

上表是执行实例数；其中 Lifecycle 为 6 个普通方法 + 3 个参数化方法，参数分别展开 2 / 4 / 2 次，总执行 14。其余九类均为普通 `@Test`。不把参数展开数量当成新增唯一方法。

故障类命令为 `mvnw.cmd -B -Dtest=TransactionImportTemporaryLifecyclePostgresIntegrationTest test`。既有回归使用上表十类精确 `-Dtest` 列表和独立 `-Djava.io.tmpdir`；全部原始命令结果保留在对应日志，不运行 Finance 全套。

新增故障类实际覆盖应用启动 Cleanup 回调、正常活跃引用保留、Cancel 删除失败再试、Expire 清理、Confirm 提交后清理失败 / 再试和终态先于 Mapping 发布。Scheduler 当前每分钟/停止排空代码只做静态核对；未新增或实跑独立 Scheduler 停止 / 重启 / 多实例测试。测试 profile 禁用定时器，不能把直接 Cleanup 调用宣称为定时线程实跑。

## 13. Receipt 与金融事实

新增用例以真实 Confirm 金融事务写入一次支出 10，验证余额为 -10；故意让提交后 raw 删除失败，然后恢复 Cleanup，文件删完后仍可 GET Receipt 和同 key 同 intent Replay。

用数据库完整 JSON 快照比较 `accounts`、`transactions`、`transaction_import_batches`、`transaction_import_items`、`transaction_import_batch_account_impacts`，证明本用例清理 / Replay 前后 persisted facts 完全相同。返回 Receipt 对象相同，包含 Result Digest 与 Account Impacts。另有 Confirm 先赢 Mapping 发布用例检查相同性质。该证据限于受测合成场景，不代表 Finance 全量最终验收。

## 14. 工程检查

本轮 Maven compile **PASS**；`git diff --check` 和 `git diff --cached --check` 均通过。因为 tracked / staged diff 为空，这两个命令不覆盖新增 untracked 文件；两份新增文件另外逐行检查：尾部空白 0、冲突标记 0、均有文件末尾换行。

主控读取新增文件并进行凭据关键字筛查，只有既有 token 服务引用和合成测试 `fixture-hash` / 幂等 key，没有真实金融数据、Provider Key、JWT、密码或其他凭据。测试诊断只输出数量、状态和存在性，不输出 CSV/XLSX 内容或上传敏感路径。无源码/Review 含 target、日志、Surefire XML 或临时文件的误加入；没有暂存任何文件。

## 15. 两名 Luna Max 独立只读复审

复审一 `/root/c9_storage_review`：**已完成**，文件 / DB 非原子边界、崩溃恢复、孤儿与误删风险；仅只读，未独立实跑。第一次被额度限制中断，恢复同一指定 Agent 后独立读取源码、verified XML、日志和报告，并核对 HEAD；未读取或 hash 两个受保护文件。

结论：Design Checkpoint 正确；无已证 P0，活跃 raw 悬空和无回收入口等为本 C9 的阻断 P1。确认 23 执行 / 16 failure / 0 error / skipped 和 Cancel 三轮真实 SQL 红灯。建议补明读取 / 删除互斥、稳定持久存储 identity、unknown / unreachable store 不得登记 DELETED、旧版写者绕过登记，以及 Error 栈展开与 SIGKILL 的区别。主控已在第 6、9 节纳入这些待设计验收条件；未实施新机制。

复审二 `/root/c9_contract_review`：**已完成**，Preview / Confirm / Receipt 合同、故障注入、并发与回归真实性；仅只读，未独立实跑。先被额度限制中断，恢复后读取源码、主控日志、最终 Surefire XML 与本报告。

结论：维持 Design Checkpoint 合理，当前实现技术上不满足 GO；未发现已证明 P0。确认 raw 悬空、不可定位孤儿、双 Mapping 中间 Plan、Cancel 陈旧快照均为 C9 阻断项；核对 17 唯一 / 23 执行 / 16 failure / 0 error / skip、68 既有回归及 compile 证据。Confirm / Receipt 和受测金融快照证据成立，但只覆盖合成案例。建议把数据库行锁跨 I/O 的事务时长与等待上限列为实施前设计验收点；主控已在第 9 节补充，未擅自实施。主控负责核实发现和最终状态，复审不代替真实测试。

## 16. 未验证范围

真实杀 JVM / 应用重启 / 断电，文件系统耐久性、磁盘满 / 权限错误、多实例共享实际卷、未知提交结果、未来 registry / 迁移、实际 legacy orphan 数量及处置均未验证或未授权。没有扫描真实目录。反向交错和不同 Mapping warning identity 完整矩阵留待方案批准后测试。Finance 全量后端、前端、E2E 最终验收未进行。

## 17. 保留的原有开放项与受保护文件

继续保持 OPEN：C8 前端提示 P2（`frontend/src/utils/marketQuote.ts`、`frontend/src/pages/Assets.tsx`）、FX 类似迹象待独立调查、C4 cause-only 异常循环、C3 不兼容脏数据 / REPLACEMENT 防御边界，以及最终 Finance 回归。没有顺带修复或因统计关闭这些事项。

`AGENTS.md`、`docs/codex-task.md` 全程只确认路径状态 `??`，最终核对仍为 `??`，不计算内容 hash。最终 tracked / staged diff 均为空；HEAD、本地 `origin/zh-cn`、独立远端 SHA 仍为指定值。工作区只有两份本轮新增文件和两个原受保护文件，均未暂存。

## 18. 最终状态

**C9 — DESIGN CHECKPOINT，暂停等待人工授权。**

红灯是调查结论，不能宣称 IMPLEMENTED / ACCEPTANCE GO CANDIDATE。下一步需要人工决定是否批准第 9 节的新生命周期登记、additive migration、Storage 合同及协调协议和受控只读 inventory 范围；批准前不实施新机制，不 commit / push，不进入 C10/C11 或 Finance Freeze。

## 19. Design Addendum — 人工批准后的编码前补充

本轮人工决定 **DESIGN APPROVED / IMPLEMENTATION AUTHORIZED** 已生效，授权总体 Registry / 一份 additive migration / 受管 Storage / Cleanup 复用 / 受控只读 inventory。以下细化在任何生产修改前记录。批准不代表 C9 红灯已转绿。

重新 Preflight：分支 `zh-cn`；HEAD、本地 `origin/zh-cn`、独立远端均为 `138bccc2bcb2d5bfea17ea88ecc115604928a5be`；tracked / staged diff 空；仅原两份 C9 文件及受保护两路径未追踪。全部既有证据保留。尚未确定、核定真实上传根，未进行真实目录 inventory 或清理。

### 19.1 Registry Schema 候选

拟一份 **V21 additive migration**（尚未创建），在同一 migration 中定义所需表与索引；不改 V1–V20：

| 字段 / 约束 | 含义与不变量 |
| --- | --- |
| `reference UUID PRIMARY KEY` | 全局不重用 reference；RAW / PLAN 也不复用同 UUID。额外 `(storage_id, reference)` 唯一性用于归属检查 |
| `storage_id UUID NOT NULL` | 对应受管存储身份表；身份记录 kind、卷身份摘要与部署模式，不保存敏感绝对路径 |
| `kind` | 约束 RAW / PLAN；与 storage kind 一致，不能将 RAW 的引用交给 PLAN Storage |
| `owner_user_id` | 用户 FK，删除用户时 SET NULL，清理依据不级联删除；所有业务访问仍须验证实际 Session 所有权 |
| `intended_session_id UUID NOT NULL` | 预留时确定的 Session 关联标识，尚无 Session 行也可登记，不设级联删除 FK |
| `session_id UUID NULL` | LIVE 后绑定实际 Session；FK `ON DELETE SET NULL`，保留原 intended id、状态与重试记录 |
| `state` | RESERVED / LIVE / RETIRED / DELETED；DELETED 墓碑保留，不重开同 reference |
| `write_complete BOOLEAN` | 文件关闭并完成受管最终路径后才置 true；LIVE 必须 true，不能把 `.part` 当作可发布内容 |
| `fence_epoch BIGINT` | 非负单调递增；lease 接管、退休、清理改变 epoch；旧 epoch 无权发布 |
| `lease_owner UUID, lease_until` | 写入者身份与期限；由 DB 时间判断，不依赖各实例本机时钟；过期不等于物理写入已停止 |
| `created_at, completed_at, retired_at, deleted_at, next_attempt_at` | RESERVED 初始保留不少于 24 小时；删除资格还取决于状态、引用与实际 I/O 互斥 |
| `attempt_count, last_failure_code` | 持久重试与固定错误分类，避免保存 Throwable、CSV/XLSX、文件名或路径 |
| 索引 | `(state,next_attempt_at,created_at)` 候选领取；`(intended_session_id,kind,state)` 查询；Session 原引用仍需权威检查 |

读租约若采用持久表，同一 migration 中定义 `lease_id / reference / epoch / owner / expires_at` 与索引；租约过期不能单独授权 unlink。只通过 FK 和状态约束无法让普通文件系统原子验证 DB epoch。

### 19.2 状态转换、事务和恢复

所有需要已有 Session 的数据库临界区统一 **Session FOR UPDATE → 文件登记行按 reference 排序**；数据库事务不等待文件锁、不进行文件 I/O。Confirm 在读取完整冻结 Plan 后释放生命周期保护，再进入原 Session → Accounts 金融事务。

| 转换 | 前置条件与短 DB 事务 | 非原子窗口及恢复 |
| --- | --- | --- |
| 无对象 → RESERVED | 用户有效，UUID 全新；提交登记后才准许任何 part / final 写入 | 提交结果未知先按 UUID 回读；未能确认登记则不写文件 |
| RESERVED → 写入中 | 核验 owner / epoch / lease，存储身份可达；领取持久写入资格 | DB 校验与下一次 FS 操作之间存在暂停窗口，必须增加存储侧强制互斥 / fencing，见 19.3 |
| 写入中 → write_complete | part 与最终路径使用同 reference；实际写入、关闭、移动之后 CAS 更新完成标志 | FS 成功而 DB 失败仍 RESERVED，可发现；不发布未确认完成的对象 |
| RESERVED → LIVE | 当前 Session 所有权、state、TTL、expected revision、文件完成、epoch / lease 全部满足；提交引用与 LIVE 同事务 | 未知提交结果回读权威 Session / Registry；不能直接危险补偿删除 |
| 新 Plan 发布 / 旧 Plan → RETIRED | 锁内取得真实旧引用；expected revision CAS；新 LIVE / 引用 / revision 与旧退休原子提交 | 旧文件删除失败保留 RETIRED；不依赖调用开始快照 |
| Cancel / Expire / CONSUMED → RETIRED | 锁内读取实际当前引用；终态引用脱离与退休登记原子提交 | Confirm 金融提交保持原合同；原引用仍存在时不得根据旧快照强删 |
| RESERVED → RETIRED | 满 24 小时、lease 失效、无真实当前引用，并可证明旧执行者无法再写 | 仅 age / epoch 不足以完成该证明；不满足则保留重试，禁止 unlink |
| RETIRED → 删除中 → DELETED | epoch 作废，确认无引用，取得存储侧排它保护；删除 part / final 并验证实际存储可达及对象确实不存在 | 删除失败 / 不可达仍 RETIRED，记录重试；未知删除结果回读受管对象，不提前 DELETED |
| LIVE 读取 | 用户 / Session 绑定有效，读取资格与 unlink 互斥；保护覆盖实际 open/read，不止 DB 校验 | 读取完成释放保护；不能只根据读租约年龄推断读操作已结束 |

数据库语句 / 锁等待应有明确 deadline，短事务仅承担元数据；不把“事务 timeout”写成文件 I/O 的超时证明。文件操作需独立资源边界和确切终止 / 保留保护语义。

### 19.3 待验证的关键实现条件

优先验证短数据库事务 + lease / epoch 的方案。必须排除：旧执行者在 epoch 校验通过后暂停，Cleaner 作废 lease、删除并标记 DELETED，旧执行者恢复后又创建同 reference。发布 CAS 拒绝旧 epoch 只保护数据库发布，不能撤回一次已经发生的文件写入。

读取同样需要覆盖校验后、open/read 前的暂停。`Future.get(timeout)` 限制调用者等待，`cancel(true)` 是中断请求，不能当成底层 I/O 已终止或可释放互斥的证明。应验证不可协作的 InputStream / 文件操作，而不是只测试正常快速磁盘。[Java 21 Future](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Future.html)。

候选补强是在数据库事务外持有跨进程文件操作互斥，取得后重新验证 epoch，覆盖写入、完成、read 和 unlink。Java FileLock 的内容保护依赖平台，应按协作锁使用；同 JVM 还需线程协调，并需固定控制文件 / 唯一 channel 管理，不能由某实例关闭另一执行者的保护。[Java 21 FileLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html)。必须确认控制文件、根目录身份与 reparse 替换之间不会产生两套锁。

路径校验与实际文件操作也有检查 / 使用窗口。需验证当前 Windows Provider 是否提供 handle-relative 的安全目录操作；仅 normalize、toRealPath、NOFOLLOW 的预检查不构成完整的目录替换防护。[Java 21 SecureDirectoryStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/SecureDirectoryStream.html)。这些条件未满足前，不把 lease 或 FileLock 方案写成可靠实现。

### 19.4 部署、迁移与失败回退

拟要求显式配置稳定的应用专用持久 root / storage identity，RAW / PLAN 分离；不自动把任意同名本地目录认作相同卷。未挂载、marker / 卷身份不匹配、身份不能证明时 Import fail-closed，不能登记 DELETED。初始支持范围需通过本机多进程 / 独立组件验证；多主机共享卷未验收时禁止启用该部署模式。

旧根只接受经核定的显式 legacy 配置，按数据库已引用 UUID 逐个登记，保留活跃文件；不扫描默认系统临时目录、不移动或删除真实用户数据。未知历史文件 inventory 只在明确核定的应用专用目录执行，不读取内容、不删除。尚无真实目录核定，因此本轮先验证隔离 fixture，不执行真实 inventory。

顺序：停止旧版写者 → 升级 V21 → 验证所有实例看到同一持久卷及控制锁身份 → 登记既有 DB 引用 → 验证读写与清理互斥 → 启用新版本 Cleanup。失败时停止新写入与物理清理，保留 Registry / 引用 / 文件；不能回退到绕过 Registry 的旧版写入。数据库 migration 成功不能代替存储迁移成功。

### 19.5 本轮编码门槛

人工审批第三节明确要求：无法安全确定协调实现时暂停并提交新的详细 Design Checkpoint。因此先用独立、真实 PostgreSQL / 隔离文件测试验证上述关键机制。设计反例测试与能力探测不构成生产修复；不改或弱化原 17 个安全测试。若关键条件不成立，记录具体反例、支持范围和待决方案，停止生产实施，不堆叠一份尚不能保证安全的 migration / Service。

## 20. 批准后的协调验证 — 正向路径与具体阻断

本节是编码前隔离原型验证，**没有修改生产 Service / Mapper / Storage / 配置 / Scheduler，没有创建 V21**。原 17 方法安全故障类保持原样，红灯尚未转绿。不存在最终生产 diff 可供验收。

### 20.1 不能只使用 DB lease / epoch 或 Future 超时

新增 `TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest` 使用真实 PostgreSQL 17.11、仅测试 fixture 表 `c9_design_registry` 和 JUnit 自有目录，4 组反例各三轮加 2 个能力探测，共 **6 唯一方法 / 14 执行 / 0 failure / 0 error / 0 skip**。绿表示观察到了反例，不能解释为修复通过。

- 写者已通过 DB 校验后暂停，Cleaner 令 lease 失效、epoch 增加并记录 DELETED；写者恢复仍能创建文件，发布 CAS 返回 0。DB 防止发布不能撤销 FS 创建。
- 读权限校验后、open 前暂停；Cleaner 删除后 reader 收到真实 NoSuchFileException。这是校验 / open 窗口，**没有实现实际持久读租约**，不能声称已验证真实读租约过期。
- `Future.cancel(true)` 后 Future done，但拒绝中断的 worker 尚未退出；释放 worker 后仍建文件。保护必须覆盖实际 worker 生命周期。
- `NOFOLLOW_LINKS` 限制末级，未固定父目录的预检查后，替换根会令创建落到新目录。该反例没有 root pin，不能否定所有有 pin 的方案。

当前 Windows Provider 探测 `SecureDirectoryStream=false`、`fileKeyAvailable=false`；这是当前 Provider 的结果，不是跨平台结论。另一探测中，控制句柄采用 `NOSHARE_DELETE`，根与其祖先 rename 均被拒；关闭句柄后同样 rename 成功。它证明了本机普通目录运行期 pin 能力，未证明全套 reparse / mount / 重启身份协议。

### 20.2 正向 OS guard 原型

新增 `TransactionImportLifecycleGuardPrototypePostgresIntegrationTest`：父 JUnit JVM 在 child 已取得真实 OS FileLock 后执行真实 PostgreSQL 授权检查，通过管道允许操作；child 不依赖 Spring 或 JDBC。Cleaner 使用另一进程中的 channel 做非阻塞取锁，busy 时只在 fixture Registry 留下重试，不进行 unlink / DELETED。实际读写完成并释放 guard 后，取得 guard 的 Cleaner 才删除、收敛。

最终 **6 唯一方法 / 14 执行 / 14 pass / 0 failure / 0 error / 0 skip**。其中以下 4 组各三轮：

1. 实际 writer 未结束时清理被拒；writer 后续创建仍受 guard 保护，退休后的发布 CAS 失败；之后 Cleaner 取得 guard 才删除。
2. reader 权限失效 / 对象退休时 guard 仍保护真实 read，文件读完后才允许删除。
3. 已 DELETED 的旧 writer 取得 guard 后重新核验 DB epoch / state，DENY 后没有重新建文件。
4. 对独立 child JVM 执行真实 `destroyForcibly()` 并有界 wait；OS guard 释放后，Cleanup 最多 10 秒非阻塞重试收敛。等待通知后锁不一定立即可得，busy 期间 Registry 仍 RETIRED。

另外两个普通测试是 Windows volume serial 能力和第 20.3 节的根身份反例。该类未验证生产 Service 协调、同 JVM 公共 channel 管理或两个 Spring 实例；`.part` 是父测试预先创建的合成 fixture，kill 时 child 正等待 RELEASE，**不是保存中途杀生产应用**。不能用此证明实际挂起内核 I/O 会被 Java interrupt 终止。

### 20.3 同一路径 / 同 marker / 同卷序列号仍可指向错误根

本机 `Files.getFileStore(root).getAttribute("volume:vsn")` 确实能返回 Integer，重复查询相等，文件系统 NTFS。不能由 `fileKey=null` 推断 Java 没有任何卷序列值。OpenJDK Windows 实现将此属性标注为用于测试的 Windows 特定属性；Java FileStore 并不承诺跨平台属性一致。[OpenJDK 21u WindowsFileStore](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileStore.java)、[Java 21 FileStore](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/FileStore.html)。

最终隔离反例进一步验证：无旧进程句柄的停机窗口，将含原 payload 的根 rename 为 archive，复制 marker 的空目录移到完全相同配置路径。`toRealPath()`、marker 与卷序列号均等于旧值；错误候选模型在新空根确认文件不存在并记录 DELETED，但 archive 中原 payload 仍真实存在。所有目录 / 内容均为 JUnit 合成 fixture，没有扫描用户目录。该反例否决 **path + marker + volume serial** 作为完整的跨重启根对象认证；它不证明 native 持久 file ID 或受信部署绑定方案不可实现。

卷 serial 本身是格式化时生成的 32 位值，规范不要求唯一，不能单独当成硬件身份。[Microsoft FileFsVolumeInformation](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-fscc/bf691378-c34e-4a13-976e-404ea1a87738)。

### 20.4 实际 Linux / Docker 能力探测

没有将生产支持擅自缩成 Windows。仓库 `backend/Dockerfile` 运行 Temurin 21 JRE Jammy；现有 Compose 仅 PostgreSQL 有持久 volume，backend 尚未声明 Import 文件持久卷。配置持久根与必要卷本来就在已批准实现范围内，不以此单独要求再审批。

使用本机已缓存 `eclipse-temurin:21-jdk-jammy`，执行 `docker run --rm --network none`，只读挂载自有 `backend/target/C9LinuxStorageCapabilityProbe.java`，使用新建、随容器自动删除的匿名测试 volume。**1 次独立命令 / exit 0**，不计入 JUnit 测试数：

| 当前隔离 Docker volume 结果 | 实测 |
| --- | --- |
| FileStore type | ext4 |
| SecureDirectoryStream | true |
| Basic fileKey / unix:dev | 可取得 |
| Windows volume:vsn | 不支持 |
| 持 FileLock 时 root rename | 仍可成功，FileLock 不能充当目录 pin |
| SDS 相对创建，root 被替换后 | 仍落在原目录，未落到替换目录 |

因此 Linux 存在可行的 handle-relative I/O 原语；并未证明 Linux 路径不可实现。探测没有验证两个容器共享卷、跨容器重启后的身份认证、control 文件被 unlink / 替换后是否形成两套锁、完整应用启动 / Scheduler 或 FSYNC 耐久性。安全实现仍需把根和 control 对象的身份绑定到持久 Registry，任何不匹配须 fail-closed。

## 21. 第二次 Design Checkpoint — 聚焦存储对象身份与失败合同

**C9 — DESIGN CHECKPOINT。** 原批准总体方案继续有效；不重复申请 Registry、短 DB 事务、FileLock、稳定根配置或 Cleanup 复用的授权。暂停理由是：当前尚不能安全确定跨重启的根 / control 对象认证合同；实测已否决 path + marker + Windows serial 的简化模型。运行期 root pin 或 Linux SDS 不能独自解决停机窗口后的对象替换，不能在该前提未定时写一个会错误登记 DELETED 的生产实现。

用户本轮授权第三节明确规定：“如果无法安全确定实现方式，暂停并提交新的详细 Design Checkpoint。” 本节依该条款停止生产实施，而非以普通实现复杂度或未测试 Linux 为由请求重复授权。

### 21.1 需要人工决定的具体支持 / 信任合同

| 候选 | 具体实施与部署合同 | 代价 / 验收条件 |
| --- | --- | --- |
| A：受信部署绑定，最小实现候选 | 仅单主机本地持久存储；部署系统负责 root / control 对象预配、独占所有权和同一实际卷绑定；禁止停机时复制身份到替换根、在线替换控制对象。Windows pin 与 Linux SDS 分别保护运行期；绑定变化须停写 / 停清理并人工重新登记 | 必须明确接受该运维信任边界，不能宣传任意 mount / clone 场景都可自行识别；仍需对正常不可达、缺 marker、绑定不符、legacy 引用与故障恢复 fail-closed 实测 |
| B：跨重启原生对象身份适配 | 通过受支持 OS 接口取得并校验持久 volume / directory / control file 身份；Windows 需要具体 root file ID 的受支持获取方式，Linux 需要固定根 / control 并绑定设备 / inode 与持久卷身份；必要时引入 native 适配或部署证明 | 需先确定支持的 OS / FS / 卷类型、接口与依赖；仍不能把可克隆 serial 宣传成绝对唯一。需要原生能力与容器卷、重启、替换、拒绝路径完整测试；不擅自引入 preview FFM、JDK 内部反射或新 native 依赖 |

A 并非已实现，也不是默认弱化现有安全要求；需要明确产品 / 部署处置。B 是待设计接口，不宣称不可实现。两者均保留：多主机 / 网络共享卷未验收则不支持；所有旧版绕过 Registry 的写者必须停止；未知提交结果权威回读，不危险补偿；原 17 安全测试不得弱化。

### 21.2 不可中断 I/O 的安全失败与恢复合同

建议后续实现：固定数量 worker、有界队列、明确调用 deadline；数据库只持短事务且绝不等 FS guard / I/O。guard 由实际执行 I/O 的 worker 持有，调用 timeout / cancel 不能释放；清理 busy 保留 RETIRED 和持久重试。若 worker 实际不再返回，保持安全拒绝，停止接收新写入，由受控终止进程、验证 guard 释放后启动恢复；不得承诺 Java 级硬超时能终止任意磁盘 I/O。

原型证明的是协作子进程 guard 与进程退出后的有界重试，不是内核 I/O 卡死实验。该资源 / 活性合同须在后续实现验收中明确；无法取得保护或确认存储对象时不登记 DELETED。不得为有限时间内必删而释放尚活跃的保护。

### 21.3 后续实施验收清单

选定身份合同后，才创建一份 V21 并实施第 19 节协议。后续必须完成：空库迁移与既有 V20 升级；生产 Registry 的 Session → Registry 锁顺序与 expected revision；所有原 C9 红灯转绿；两个独立 Storage / Cleanup 组件实际协调；startup / Scheduler 实际调度停止；中断写入 / part 恢复；unknown commit、用户隔离与必要 JWT；Import 68 项回归、Receipt / Replay 与金融快照。Windows / Linux 支持范围及存储故障逐项验收，未验收的多主机继续不支持。实际用户根未核定，inventory 不执行。

## 22. 本轮执行证据、失败保留与工程状态

所有计数以展开后的 XML `testcase` 为准。Surefire 对重复失败方法可能在命令末尾折叠计数，不能照抄为实际执行数。

| 本轮命令 / 留存日志与 XML | 唯一方法 / 实际执行 | 结果与解释 |
| --- | --- | --- |
| `c9-design-boundaries.log` / `c9-design-boundaries-first-fixture` | 5 / 13 | 3 failure；Windows fileKey 非空假设错误，属夹具前提失败 |
| `c9-design-boundaries-corrected.log` / corrected evidence | 6 / 14 | 14 pass；反例 / 能力探测 |
| `c9-approved-design-checkpoint-tests.log` / `c9-approved-design-checkpoint-evidence` | 原 17 + 边界 6 = 23 / **37** | 原类 23 执行 / 16 failure；边界 14 / 0；合计 16 failure、0 error / skip。命令结尾 31 / 10 为重复方法折叠，非真实执行数 |
| `c9-positive-guard-prototype.log` / `c9-positive-guard-first-fixture` | 5 / 13 | 12 failure；child 调外层 static helper 触发 Testcontainers 初始化，日志污染握手；未改生产行为 |
| `c9-positive-guard-prototype-corrected.log` / `c9-positive-guard-first-kill-recovery` | 6 / 14 | 3 failure；真实进程退出后的立即 tryLock 仍忙，属于原型恢复语义发现，不归为既有生产缺陷 |
| `c9-positive-guard-prototype-bounded-recovery.log` / bounded evidence | 6 / 14 | 14 pass；增加真实取锁的有界重试；未放松删除前须持 guard 的断言 |
| `c9-positive-guard-prototype-final.log` / `c9-positive-guard-final-evidence` | 6 / 14 | **14 pass**；最终源码，强化同 path / marker / serial 错误根反例 |
| `c9-linux-storage-capabilities.log` | 1 个独立命令，非 JUnit | exit 0；匿名卷隔离能力探测 |
| `c9-approved-regression.log` / `c9-approved-regression-evidence` | 63 / 68 | **68 pass / 0 failure / error / skip**；重新运行原十类，包括 Preview → Mapping → Confirm → Receipt、幂等 / 恢复和原合同 |
| `c9-approved-compile.log` | Maven compile | **PASS**；生产代码未改 |

上一轮第 10–18 节全部旧红灯与两次 Mockito fixture 失败证据保持原样。当前三个新增类的最终留存结果为 **29 唯一方法 / 51 执行 / 16 failure / 0 error / skip**：17 / 23 原故障类 + 6 / 14 边界类 + 6 / 14 guard 原型。它们来自分别留存命令，**不是一次联合 51 测试绿灯**。本轮新增两类设计样例共 12 唯一方法 / 28 最终执行，无生产修复验收意义。重跑次数不当成新增测试数。

HEAD、origin 与独立 ls-remote 仍为 `138bccc2bcb2d5bfea17ea88ecc115604928a5be`；分支 `zh-cn`。tracked / staged diff 空，未暂存、commit / push / tag。变更范围目前恰为四个未追踪 C9 成果：

1. 原 `backend/src/test/java/com/financeos/integration/TransactionImportTemporaryLifecyclePostgresIntegrationTest.java`，本轮未编辑。
2. 新 `backend/src/test/java/com/financeos/integration/TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest.java`。
3. 新 `backend/src/test/java/com/financeos/integration/TransactionImportLifecycleGuardPrototypePostgresIntegrationTest.java`。
4. 原 `docs/archive/review/G3-2-C9-Import-Temporary-Storage-Lifecycle-Review.md`，追加补充与证据。

日志、XML、fixture source 与临时目录都在已忽略的 `backend/target`，未纳入提交。两份受保护文件仍仅以路径状态确认 `??`，未读取、hash、修改或暂存。无真实用户目录 inventory / 文件内容扫描 / 删除；Linux 探针只使用新匿名 volume，容器正常退出时自动删除自身测试卷。

## 23. 最终复审与项目状态

两名指定 Luna Max 完成本轮最终只读复核；均未独立执行 Maven，也未修改文件，不将读主控日志 / XML 等同于独立实跑。它们审查的是设计补充、原型源码与实际证据；因为没有生产修改，不能称“最终生产 diff 验收”。

存储一致性复审 `/root/c9_storage_review`：确认最终 guard XML 14 / 0、Linux 正向能力、同 path / marker / serial 的具体错误根反例；纠正之前“Java 无卷序列值”的过强判断。建议聚焦根对象身份 / 部署信任合同的第二次 Checkpoint；无已证 P0，未定的身份绑定是设计 P1 风险，原 C9 生产红灯仍在。

Import 合同复审 `/root/c9_contract_review`：确认最终原型 14 / 0，原 C9 23 / 16，边界 14 / 0，拒绝把各轮折叠计数和原型绿灯混成修复验收；确认没有生产金融语义修改。对停止理由持不同意见：认为现授权可先实现 Linux 单主机持久根 / SDS，Windows 能力不足时 fail-closed，或明确 trusted provisioner 合同，普通 Provider 适配不应再次审批。

主控核实两名意见，不声称其一致同意停止。Linux 能力和 guarded I/O 路线已实际验证，未将它们判为不可实现；当前无法安全选定的是 Windows 跨重启根身份的具体适配 / 接受的信任边界。主控不默认把当前 Windows Import 功能改为不可用，也不默认把错误根识别要求降低为未经明确接受的停机操作信任假设。故依人工授权第三节提交第 21 节的具体支持 / 身份合同决策；这是新的设计门槛，不是重新申请总体实现授权。暂停后不继续生产改动。

最终工程检查：`git diff --check`、`git diff --cached --check` 通过，tracked / staged diff 为空；四份精确 C9 文件额外检查尾部空白、冲突标记与末尾换行。两保护路径仅 Git 状态确认仍 `??`，无读取 / hash。没有新增依赖、历史迁移修改或生产配置变更。

当前 **C9 — DESIGN CHECKPOINT**，尚未 IMPLEMENTED、尚无 ACCEPTANCE GO CANDIDATE。C1–C8 CLOSED — GO，审计进度保持 **8/11**；C9 / C10 / C11 未关闭，Finance 仍 Final Stabilization。C8 前端 P2、FX 待查、C4 cause-only 循环、C3 脏数据 / REPLACEMENT 边界与 G5 Finance 全量验收均 OPEN，没有顺带修改。

## 24. 第二次人工决定 — OPTION A APPROVED / IMPLEMENTATION AUTHORIZED

第 1–23 节保留为历史证据；最新人工决定批准 Trusted Deployment Binding，暂停采用 native root file ID。支持 Windows / Linux 单主机持久本地存储及 Linux Docker 明确持久卷；同主机多进程须通过实际互斥 / 恢复验收。多主机、未经验证网络卷、任意临时根、自动恢复换卷及未知历史孤儿删除不支持。

部署负责预配独占 root / marker / control、稳定部署与存储 UUID、权限隔离、停机时不静默复制 / 替换绑定、停止旧写者；迁移 / 重建后人工重新核定。应用启动及每次敏感操作验证绑定，缺失 / 不符 / 不可达 fail-closed，不自动创建生产 root 或控制对象。正常重启无需重复人工确认。相同路径 / marker / serial 的高权限停机替换属于明确排除边界，原反例继续保留，不宣称自动识别。

生产实施沿用第 19 节短 DB 事务和状态机：Session → Registry 排序锁；raw Session 插入与 LIVE 发布原子；Plan expected revision CAS、发布新引用与退休权威旧引用原子；终态退休保留重试；未知结果回读权威状态。文件 guard 在 DB 事务外，由实际 worker 持有到 I/O 结束。固定 worker / 有界队列 / 调用 deadline，timeout 不释放活跃保护；无法取得 guard 只重试，不 DELETED。Confirm 完整读取内存后释放 guard，再进入原 Session → Account 金融事务。

本轮 Preflight 符合：`zh-cn`，HEAD / origin / 独立远端均 `138bccc2bcb2d5bfea17ea88ecc115604928a5be`，tracked / staged diff 空，原四份 C9 与两个保护路径保持。没有真实根 inventory / 内容扫描 / 删除。以下生产与验收进展另行记录，批准不能代替验收。

## 25. 方案 A 的生产实施与部署合同

本节及后续记录第二次人工决策后的实施；第 1–23 节的调查、夹具失败、真实红灯、设计原型及检查点结论全部保留为历史证据。第 24 节的 Option A 授权没有自动关闭 C9。

### 25.1 支持矩阵与真实保护

| 环境 | 生产实现 | 支持条件 |
| --- | --- | --- |
| Windows / JDK 21 单主机本地持久根 | control 长期 `NOSHARE_DELETE` pin、共享 JVM Context / mutex、OS FileLock | 部署方独占 ACL、保护父目录、同一持久身份 |
| Linux / JDK 21 单主机本地持久根 | `SecureDirectoryStream` 固定目录句柄、相对 create/read/move/unlink、root/control fileKey 运行期核验 | provider 必须具备 SDS；目录禁止 group/other write；部署方保证 control 不被外部 unlink / 替换 |
| Linux Docker 持久卷 | 同一生产 Linux 实现；Compose `import_storage` named volume | 显式一次预配、稳定 UUID、服务账号权限、正常重启复用卷 |
| 同主机跨 JVM / 容器 | 同一 control 的 OS FileLock，真实 worker 持有 guard | 同一 root、Registry、deployment/storage identity；须使用实际互斥和终止恢复证据 |

不支持多主机、未经验证网络共享、任意系统临时目录、自动恢复缺卷、自动清理未知历史孤儿。方案 A 依赖部署方不在停机期间静默克隆、替换或重新绑定 root；同 path + 复制 marker + 相同 volume serial 的历史反例继续保留，不能宣称自动跨重启强身份认证。运行期间 control 由部署方隔离，任何外部替换 / unlink 均违反部署合同。Windows 依赖 JDK provider 的 `ExtendedOpenOption.NOSHARE_DELETE`，无内部反射、Native 依赖或 Preview FFM；编译器对此扩展 API 的兼容性警告保留，不能泛化至未经验证 JDK / provider。

生产配置必须提供两个互不嵌套的绝对路径和三个不同用途的稳定 UUID；RAW / PLAN storage UUID 不得相同。应用启动不会预配 root、marker 或 control。显式 `ImportStorageProvisioner --provision` 仅针对经核定的新空专用根，已有 / 部分初始化 / 非空目录拒绝重建。Compose 预配服务只在 `import-provision` profile 下人工运行，不作为 backend 自动依赖。详细合同及命令见 `docs/engineering/deployment.md`。

已知缺失、不匹配或不可达导致安全拒绝；已建立 binding 的异常会持久隔离 `quarantined`，恢复路径或 marker 不自动解除。重新核定目录、卷、Registry 和 Session 后，由运维明确解除隔离；应用不自动重建身份，不将错误根中的文件缺失登记 DELETED。

### 25.2 Registry、事务、读写和清理

V21 仅追加 storage binding 与 file object Registry，两张表记录状态、所有者、intended Session、当前 Session、lease、epoch、完成标记、退休 / 删除时间、重试次数及固定失败码，不修改 V1–V20。UUID 在写文件之前持久 RESERVED，`.part` 与最终文件使用同一 reference。写入前、完成和发布都核验 lease / epoch / 权威状态；新写入不得绕过 Registry。

RAW 与新 Session 在短事务中发布；Mapping 基于 Session → 按 reference 排序的 Registry 锁及 expected revision CAS，将新 PLAN 置 LIVE、更新权威 Session 引用、退休实际旧 PLAN 原子提交。Cancel、Expire、Confirm 后清理重新读取权威 Session 引用，退休后才 unlink。未知提交结果先权威回读；已提交且仍为当前引用的 LIVE 文件不能作为补偿删除。

RESERVED 后台回收要求数据库时间已满 24 小时且 lease 到期。Session 记录删除时 Registry 不级联消失；保留 intended Session 并收回失去 Session 的 LIVE。退休记录、失败次数和下一次尝试持久保留，重复删除幂等。只在可信 guard 内确认 `.part` 和最终文件均不存在后登记 DELETED，清理全部成功后清空终态 Session 引用。

实际读写 / 删除使用同一 Context 和 guard。读取在 guard 内完成有界内容加载，随后交付内存流；Confirm 在原 Session → Account 金融事务之前读取，金融事务、余额、Receipt、Replay、金额计算与 digest 语义没有更改。Cleanup Startup / Scheduler 复用既有组件；按数据库确切旧引用核验登记，不扫描磁盘，不登记或删除未知文件。

每个 root 最多 2 个 I/O worker、8 个排队任务，互斥等待 4 秒，调用方等待 30 秒；超时不 cancel worker 或提前释放 pin / guard，实际 worker 的 finally 决定释放。元数据事务 REQUIRES_NEW、timeout 5 秒，事务内无文件 I/O。关闭时停止接收清理并有界等待，长期阻塞保留保护，必要时受控终止进程恢复；不承诺固定时间完成 unlink。

## 26. 本轮实现期间失败与修正记录

- 首次生产 compile 成功；首次定向 testCompile 因日志测试夹具缺失 `never()` 静态导入失败，没有实际运行测试。限定为测试夹具修正后，原生命周期 23 次、低层存储 4 次、敏感日志 25 次，共 52 次实际通过，原 17 个方法首次全绿。
- 初次新增受管存储矩阵 12 个唯一方法 / 16 次执行通过，包含两个三轮并发方法、真实调度启停、失联提交确认和绑定隔离。随后增加调用方真实 30 秒 deadline、生产 Windows pin、精确旧引用登记等测试，最终数量以后文最终 XML 为准。
- 初次跨进程 / 容器尝试失败：Testcontainers copy 的 tar 依赖出现 `NoSuchMethodError`，属于夹具兼容性；使用现有 Docker 只读 bind 挂载构件修正，未引入或升级依赖。
- Windows 实际跨进程暴露 control 的强制区间锁与身份读取冲突：最初 byte 0 锁覆盖身份内容，移到 byte 64 后仍因 65 字节读取探测 EOF 碰到锁区而隔离。最终采用 `control.size()==64` 和精确读取 64 字节，互斥 byte 64 保持独立。保留两轮真实失败日志和 XML，不能称作环境错误。
- 修正后 Windows 实际 writer 子 JVM 在 `.part` 持有生产 guard 时强制终止，新 CLEANUP 子 JVM 基于同一持久绑定恢复，三轮通过（`c9-windows-process-bounded-read.log`）。Linux 实际生产 Storage / Registry 在新建持久测试卷上竞争、SIGKILL、重新启动恢复的三轮先行通过；最终统一验收重新验证当前打包生产代码。
- V21 空库及真实 V20 → V21 升级两方法先行通过；升级前后 users、accounts、categories、transactions、Session、Batch、Item、AccountImpact JSON 快照相同；遗留 Session 引用保持原值、Registry 不进行破坏性回填。
- 误用 Maven wrapper 工作目录的命令未运行测试；已统一在 backend 下调用 wrapper。一次证据目录路径重复创建了 `backend/backend/target/c9-managed-process-first-evidence` 空目录，删除命令被自动审批策略阻止；该空目录保留，无用户文件或 Git 变更。正确证据保存在 `backend/target/`。

原 17 个方法未删除、禁用或改名。并发 Mapping 的原双成功断言按已批准 expected revision 调整为一个成功、一个 409 冲突，仍严格断言权威引用存活且没有 PLAN 孤儿；文件数量只排除新增的两个固定管理元数据名，所有 payload 和 `.part` 仍被计入。Cancel 陈旧快照竞争夹具移到获取锁前的实际读取边界，避免在持有 Session 锁时等待另一个 Mapping，保留真实数据库竞争。RESERVED 恢复夹具使用隔离 Registry 的数据库时间，不通过只推进应用 Clock 绕过 lease。

### 26.1 完整回归补充发现 — Confirm 并发 Replay

首次完整 18 类验证执行 155 次，其中 154 次成功、1 次 error：既有 `concurrentConfirmForTheSameSessionProducesOneFinancialCommitAndOneReplay` 暴露真实兼容性缺陷。第二请求首次 Receipt 查询之后，第一请求可已经提交并退休 Plan；新受管读取正确拒绝已消耗 Session 的文件，导致第二请求无法进入原事务内 Replay。该失败不是夹具或环境问题，保留 `c9-option-a-final-acceptance.log` 及 `c9-full-acceptance-first-evidence`。

新增确定性三轮测试在第二请求真实 `loadFrozenPlan(Session)` 前暂停，第一请求完成真实 Confirm / cleanup，并断言两份 Registry 已 DELETED，再恢复读取。原并发断言保持不变，新增断言要求同一 Receipt、唯一金融事实和余额 -10.00。生产修复只在前置 Plan 读取抛 `InvalidImportFileException` 时，按 Session 权威回读已确认 Batch，复用既有 `replayOrBatchConflict` 的 key / requestHash 校验；没有已提交 Receipt 则原样抛出读取异常。Session → Account 金融事务及写入 / Receipt 计算没有更改。最终通过状态以修复后证据为准。

### 26.2 验证能力边界

真实进程强制终止和卷上的进程重启恢复不等于系统断电测试。当前没有显式文件 / 目录 fsync / `FileChannel.force()` 的全套断电耐久合同，本轮未验证 OS 崩溃、突然断电或文件系统损坏，不能据进程杀停结果声称具备这些保证。此项作为已知能力边界保留，不扩大已批准 C9 的进程中断范围。旧未知孤儿与未核定真实上传目录仍由人工后续处置，本轮没有访问真实上传内容或执行真实存储清理。

## 27. 最终实际验收证据（组合，不是单次 Maven 158 项）

首次完整执行 18 类、155 次，保留 1 个真实 Replay error。针对实际受影响的 Confirm / HTTP 范围，新增三轮确定性红灯并完成修复，随后重跑 Confirm 20 次及真实 HTTP 5 次，全部通过。未受这项恢复分支影响的 Storage、Registry、迁移、解析、安全和原 C9 的成功结果保持有效，没有机械重复测试。

最终选择每类最新有效成功 XML，保存在 `backend/target/c9-option-a-final-evidence/`；其中 Confirm / Runtime 来自修复后结果，其余来自完整执行的成功类。逐个 XML 独立统计，共 **18 类、119 个唯一方法、158 次执行，0 failure / 0 error / 0 skipped**。`summary.json` 保存逐类统计。不得把这些数字表述为一条 Maven 命令的 158 次全绿，也不得累加历史设计原型或重复执行来扩大通过数量。

| 范围 | 唯一方法 | 实际执行 | 最终证据状态 |
| --- | ---: | ---: | --- |
| 既有十类 Import 回归（原方法） | 63 | 68 | 全部通过；Confirm 原 17 在修复后重跑 |
| 新增确定性 Confirm Replay 竞争 | 1 | 3 | 3 次真实红灯 → 3 次绿灯 |
| 原 C9 故障回归 | 17 | 23 | 全绿；与原红灯 XML 对比方法集合差异 0 |
| 生产受管 Storage / Registry 矩阵 | 15 | 19 | 全绿，writer / reader 竞争各三轮 |
| 生产进程 / Linux 持久卷 / 打包预配入口 | 3 | 7 | Windows kill/restart 3、Linux SIGKILL/restart 3、CLI 1 |
| V21 空库及真实 V20 → V21 | 2 | 2 | 全绿，旧数据八表快照不变 |
| 生产配置缺失 / 独立根 / 禁止自动创建 | 3 | 3 | 全绿 |
| Confirm 真实 HTTP | 5 | 5 | 修复后全绿，JWT / Receipt / Replay / 金额与余额 |
| Invalid JWT | 3 | 3 | 全绿；跨用户 Import 另由 API / Confirm 回归覆盖 |
| Sensitive SQL Logging（参数化） | 7 | 25 | 全绿，不能写成 25 个唯一方法 |
| 合计 | **119** | **158** | **0 / 0 / 0** |

最终源码的 Maven compile 阶段和 package 成功，日志 `c9-option-a-final-package-after-replay.log`；该 package 使用 `-DskipTests`，只作为编译 / 构件证据，不作为额外测试通过。实际测试使用各自日志 / XML。Compose `config --quiet` 成功，是静态配置检查；不是整套 Compose 部署验收。Linux 测试实际运行最终 Storage / Registry 组件、实际 PostgreSQL、SDS 和自己创建的持久 named volume，两个独立容器争用真实 guard，再 SIGKILL / 新容器恢复；Windows 实际独立 JVM 对应同一流程，不再用设计原型冒充生产证据。

Final evidence 的 `source-sha256.json` 仅记录本任务生产 / 测试 / Docker 文件内容身份，不包含受保护文件。原 17 个方法没有删除或禁用；所有测试均未使用真实 Provider / 付费 API。

## 28. 最终独立复审与文件边界

两名 GPT-6 Luna Max 已按用户要求独立只读检查：存储复审检查最终 Windows pin、Linux SDS、唯一 control channel、锁区读取、有界 worker、quarantine 与部署合同；事务 / 合同复审检查 Session → Registry、expected revision、实际旧引用退休、未知结果、删除与读取、Receipt / Replay 及最终 XML。存储复审的首次结论及 Confirm delta 均未发现 P0/P1 阻断。事务复审已独立解析最终 18 XML，确认 158 次 / 119 个唯一方法、0 failure / error / skip，并复核了有限 catch 分支；最终结论随下文补记。

工作区成果路径如下（16 个已追踪文件修改、16 个未追踪 C9 成果，其中两份设计原型仅保留）；没有暂存。这份清单不包含两个受保护文件：
- `backend/src/main/java/com/financeos/module/importing/config/TransactionImportPreviewConfiguration.java`
- `backend/src/main/java/com/financeos/module/importing/entity/TransactionImportFileObject.java`
- `backend/src/main/java/com/financeos/module/importing/mapper/TransactionImportFileObjectMapper.java`
- `backend/src/main/java/com/financeos/module/importing/mapper/TransactionImportSessionMapper.java`
- `backend/src/main/java/com/financeos/module/importing/preview/TransactionImportPreviewService.java`
- `backend/src/main/java/com/financeos/module/importing/service/TransactionImportConfirmService.java`
- `backend/src/main/java/com/financeos/module/importing/service/TransactionImportFileLifecycleService.java`
- `backend/src/main/java/com/financeos/module/importing/service/TransactionImportPreviewCleanupScheduler.java`
- `backend/src/main/java/com/financeos/module/importing/service/TransactionImportPreviewCleanupService.java`
- `backend/src/main/java/com/financeos/module/importing/service/TransactionImportSessionService.java`
- `backend/src/main/java/com/financeos/module/importing/storage/ImportStorageBindingException.java`
- `backend/src/main/java/com/financeos/module/importing/storage/ImportStorageProvisioner.java`
- `backend/src/main/java/com/financeos/module/importing/storage/LocalTemporaryImportFileStorage.java`
- `backend/src/main/java/com/financeos/module/importing/storage/TemporaryImportFileStorage.java`
- `backend/src/main/java/com/financeos/module/importing/storage/TrustedImportStorageRoot.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V21__add_import_file_lifecycle_registry.sql`
- `backend/src/test/java/com/financeos/common/SensitiveSqlLoggingTest.java`
- `backend/src/test/java/com/financeos/integration/FlywayTransactionImportVersionTwentyOneMigrationIntegrationTest.java`
- `backend/src/test/java/com/financeos/integration/ImportManagedStorageProcessWorker.java`
- `backend/src/test/java/com/financeos/integration/TransactionImportConfirmIntegrationTest.java`
- `backend/src/test/java/com/financeos/integration/TransactionImportLifecycleDesignBoundaryPostgresIntegrationTest.java`
- `backend/src/test/java/com/financeos/integration/TransactionImportLifecycleGuardPrototypePostgresIntegrationTest.java`
- `backend/src/test/java/com/financeos/integration/TransactionImportManagedProcessPostgresIntegrationTest.java`
- `backend/src/test/java/com/financeos/integration/TransactionImportManagedStoragePostgresIntegrationTest.java`
- `backend/src/test/java/com/financeos/integration/TransactionImportTemporaryLifecyclePostgresIntegrationTest.java`
- `backend/src/test/java/com/financeos/module/importing/config/TransactionImportStorageConfigurationTest.java`
- `backend/src/test/java/com/financeos/module/importing/storage/LocalTemporaryImportFileStorageTest.java`
- `docker/.env.example`
- `docker/compose.yml`
- `docs/archive/review/G3-2-C9-Import-Temporary-Storage-Lifecycle-Review.md`
- `docs/engineering/deployment.md`

HEAD、local `origin/zh-cn` 与独立 `git ls-remote origin refs/heads/zh-cn` 在最终核对均为 `138bccc2bcb2d5bfea17ea88ecc115604928a5be`，branch 保持 `zh-cn`，没有 commit / push / tag，staged diff 为空。`git diff --check` 通过；包含未追踪 C9 文件的额外空白 / 冲突标记检查通过。V1–V20、金融账本实现、前端、FX、行情模块没有修改；工作区只包含本任务成果及受保护的 `AGENTS.md`、`docs/codex-task.md`（均保持 `??`，只作路径状态检查，没有读取 / hash / 修改 / 暂存）。本轮没有操作 Personal AI Workspace / Local AI Assistant，也没有进入 C10 / C11 或 Finance Freeze。

未引入真实金融数据、API Key、JWT、密码、私钥或其他凭据；测试仅使用新建隔离 PostgreSQL 的夹具凭据，独立进程通过环境传递，不在命令行或报告回显。日志 / XML / JAR / 临时目录留在被忽略的 `backend/target/`，不进入成果文件清单。真实用户上传目录未扫描、未读取内容、未移动或删除。

## 29. 保留事项与人工验收边界

- C8 前端 `frontend/src/utils/marketQuote.ts` / `frontend/src/pages/Assets.tsx` 提示：**OPEN — 非阻断 P2 / C8 后续项**，最终 Finance Freeze 前须独立修复验证或明确产品验收处置；本轮未改前端。
- `ExchangeRatePersistenceService` 类似 affected rows 静态迹象：**OPEN — 待独立调查**，没有真实 PostgreSQL 复现，不能称作已确认漏洞；本轮未改 FX。
- C4 cause-only 异常循环遍历风险、C3 不兼容脏数据 / REPLACEMENT 防御边界继续 OPEN，未顺带处理。
- G5 Finance 全量后端、前端、E2E 最终验收尚未进行。18 类定向验证不等于 Finance 全量最终验收。
- 旧未知上传孤儿保持人工待处置；外部部署须核定本地持久根、独占 ACL / 所有权、稳定身份、禁止旧写入实例及 control 外部替换；高权限停机克隆和断电耐久性限制见第 25 / 26 节。

C1–C8 仍为 CLOSED — GO，原始审计关闭进度保持 **8/11**。C9 实施候选不等于正式关闭，C10 / C11 仍未关闭，Finance 仍 Final Stabilization。后续只等待人工 C9 技术验收及单独 Git 精确提交授权。

## 30. 最终结论 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE

存储复审与事务 / 合同复审均已完成最终代码及 Confirm 修复 delta 的独立只读复核，明确未发现 P0/P1 阻断；事务复审独立解析最终 XML，确认组合统计 **119 个唯一方法 / 158 次执行，0 failure / error / skip**。复审没有自行运行 Maven 或修改文件，测试运行及证据核验由主控完成。

当前生产源码的 compile / package 成功（`c9-option-a-final-package-after-replay.log`），打包 JAR 中 Import 模块所有 class 与已编译 / 已验证的 target/classes 逐个 SHA-256 核对一致；最终任务源码 manifest 未变化。原始环境问题、夹具错误、真实红灯、两个检查点和设计反例均保留。主控在明确的 Option A 信任与支持边界内给出：

**C9 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**

这不是 C9 CLOSED — GO，也不是 Git 提交授权。工作停在人工验收边界，不 commit / push / tag，不进入 C10 / C11 或 Finance Freeze。原始审计关闭进度仍 **8/11**，全部独立 OPEN 事项保持原状态。

## 31. C9 最终验收补充 — 真实 V20 活跃 Session 升级证据

日期：2026-10-12。本轮只补旧版 Session 的 V20 → V21 生命周期证据。第 1–30 节及已有红灯、原型、最终绿灯、源码身份记录全部保留；原 **119 个唯一方法 / 158 次执行**属于上一轮历史结果，本轮没有重跑，也不将它们与新增测试拼成一次实跑。

### 31.1 范围与源码身份

实际 HEAD 仍为 `138bccc2bcb2d5bfea17ea88ecc115604928a5be`。本轮起点已经包含上一轮 C9 未提交实现；开始时将其现状与 `backend/target/c9-option-a-final-evidence/source-sha256.json` 的 **30 项**逐项核对，全部一致。本轮没有修改生产 Java、配置、V21 或 V1–V20，也没有修改已有测试。新增一份 `TransactionImportLegacyUpgradePostgresIntegrationTest.java`，并在本 Review 追加本节。

两份受保护文件 `AGENTS.md`、`docs/codex-task.md` 只出现在 Git 路径状态中；没有读取、hash、修改、删除或暂存。没有访问真实用户上传目录，没有清理此前保留的空测试目录，没有 commit / push / tag，也没有进入 C10 / C11 或 Finance Freeze。

旧版应用来自对指定 HEAD 的 `git archive ... HEAD backend`，解压到本轮新建且已忽略的 `backend/target/c9-legacy-upgrade-07b936a9-c78c-4066-b692-74cb8edc7d1a/source/backend`，随后单独打包，不 checkout 覆盖用户文件、不创建 branch / worktree、不编辑旧源码。历史代码明确使用旧版临时文件 Storage、Session 创建及冻结 Plan 写路径。旧 JAR SHA-256：`00C0225178B2BCD2EF01B105437F0533DC147BE3BE4676A8D6A749A03267E1AA`；当前生产 JAR SHA-256：`F9FF1F17E76CDC2BC32FCCA212AEE7B631F157F4C95133A2633E83AB77A2557F`；最终新增测试源码 SHA-256：`B456838E1DBB180D50C80BC1FBBF1C4663E6312768933AD3FEB7E85BB0CA4469`。构件、日志、manifest、XML 和真实旧 payload 副本均保留在上述自有 evidence 根下。

### 31.2 实跑环境与前置失败的归因

本轮初次 Maven 因 `JAVA_HOME` 未设置未执行构建；使用本机 `F:/ideajava/jdk21` 后，另有 wrapper 工作目录与 PowerShell 参数引用错误，修正命令后旧版和当前版生产 JAR 均 **package 成功**，构建时跳过测试，不将打包解释为业务测试通过。

Docker Desktop 起初未运行；启动本机已有应用后，其 backend 日志确认 Inference manager 因已有 `dockerInference` socket 访问错误崩溃。两次 Testcontainers 尝试都在容器创建前报 Docker environment unavailable，分别为类初始化 **1 error**，没有业务方法通过。没有修改 Docker 配置、删除 socket、重置 Docker 或接触已有数据库。

改用本机已有 **PostgreSQL 17.10** 二进制。测试 `BeforeAll` 在本轮 evidence 下创建全新 `owned-postgres-*/data`，运行真实 `initdb`，启动只监听 `127.0.0.1` 随机端口的独立 postgres 进程，创建专用 `c9_legacy_upgrade` 数据库；不连接或读取 `F:/PostgreSQL/data`。测试结束仅停止自己启动的应用和数据库进程，保留测试 cluster、payload、状态快照和日志。旧、新应用均使用真实生产 JAR、`prod` profile；没有用 `test` profile 绕过生产预配/绑定检查，没有付费外部 Provider 调用。native 参数未配置时测试仍使用隔离 Testcontainers。

首轮 native 三个方法和随后单个诊断方法均在 V20 Preview 的 `confirmable` 断言失败：完整应用启动会初始化系统分类，夹具硬编码 `Food → id=1` 实际指向 INCOME，真实 Preview 返回 `CATEGORY_TYPE_MISMATCH`。改为 `INSERT ... RETURNING id` 后使用实际 EXPENSE 分类 ID；生产代码及关键断言未改变。该 **3 failure** 与诊断 **1 failure** 的日志/XML保留，不解释为升级兼容缺陷或修复证据。

### 31.3 实际验证的方案 A 人工升级流程

这是第 24–25 节已批准的“迁移后人工重新核定”在本轮隔离环境中的具体执行，不是自动迁移、不提供原位旧临时根升级，也没有降低保留活跃 Session 的要求：

1. 在真实 V20 schema 上启动指定 HEAD 的旧版生产 JAR；注册测试用户，通过真实 HTTP 上传创建一个 `MAPPING_REQUIRED` 和一个可 Confirm 的 `PREVIEW_READY`。旧版真实保存两个原始 CSV 和一份冻结 Plan；旧 Rows 读取返回 200。保存业务表快照和 DB 精确引用的三个文件副本及摘要，旧 token 只保留在进程内存。
2. 停止旧应用，确保旧 writer / cleanup 不再运行。通过真实 Flyway 将 V20 升到 V21，断言恰执行一份 migration，业务表完整快照不变、Registry 尚为空。不会人为延长 Session TTL；升级耗时超过 TTL 时按原有过期合同处理，不能伪造活跃性。
3. 在新的空、互不嵌套专用 RAW / PLAN 根上，用当前生产 JAR 的正式 `ImportStorageProvisioner --provision` 入口建立稳定 deployment/storage UUID、marker/control。部署方仍须履行已批准的独占权限、父目录保护和同一实际存储保证。旧非空临时根不被当成可信根，也不调用底层 `TrustedImportStorageRoot.provision` 绕过 CLI 的非空拒绝。
4. 新应用尚未启动时，仅根据停写后 DB 的确切 UUID 引用逐个复制 RAW / PLAN payload 到对应新根。保留 UUID、Session、revision、digests、冻结 Plan 内容；不重建 Plan/token，不复制根目录或 marker/control、不插 Registry、不覆盖目标、不删除源、不扫描未知文件。RAW 与 DB `file_size/file_digest` 核对；RAW/PLAN 均逐字节核对源和目标；新 marker/control 内容保持不变。
5. 显式配置新根与稳定身份，启动当前生产 JAR。真实 startup cleanup 在 guard 内核验确切引用并由生产 `adopt` 自然登记。先断言业务表快照完全不变、Registry 恰有原三个引用且均为 `LIVE/write_complete`，owner、Session、intended Session、storage identity 与实际对象对应，再验证业务行为。

本轮没有复制 `.import-binding` / `.import-control` 或静默克隆既有绑定。迁移任一步骤缺文件、类型不符、大小/摘要不符、身份/权限无法核定时，合同要求停止新写入和清理、保留 DB/源/目标证据，人工核定后再启用；不得回退到旧版绕过 Registry 继续写 V21。旧源副本和未知历史孤儿的真实处置未授权，本轮没有删除。此流程要求旧 token 的 Confirm 签名 secret 按原值保留；测试只生成并跨版本复用隔离测试 secret，未取得或记录用户真实凭据。

### 31.4 最终实际结果

`legacy-upgrade-native-corrected.log` **BUILD SUCCESS**；最终 XML 独立计数为 **3 个唯一方法 / 3 次执行 / 0 failure / 0 error / 0 skip**。没有 retry 合并、参数化展开或被折叠的重复方法。

| 定向场景 | 最终可核验结果 |
| --- | --- |
| 真实非空 V20 根原位预配 / 新版访问 | 正式 CLI exit 2；新版生产启动拒绝无绑定根。Session 状态、业务表、原始文件与 Plan 摘要不变，binding / Registry 都为 0；没有误删、清空引用或登记 DELETED。此负向安全结果不是正向迁移通过的替代。 |
| 真实旧 MAPPING_REQUIRED 经迁移 | 初始 Rows 按合同 409；Mapping PUT 200，读取原 RAW、生成正常受管新 Plan，revision 0 → 1、状态 PREVIEW_READY、Rows 200 且与响应一致。旧原始文件仍 LIVE；没有重建旧 Session。 |
| 真实旧 PREVIEW_READY 经迁移 | 原 Plan 内容原样迁移，Rows 200 且等于 V20 预览 rows；此前业务表快照保持不变。直接用旧 V20 previewToken Confirm 成功，无需重新预览/签发 token。 |
| Confirm / Receipt / 金融事实 | 一条 EXPENSE 12.34、一份 Batch、一条 Item；交易 ID 与 Receipt 对应，result digest 与 DB 一致。Receipt impact 与实际余额同为 100.00 − 12.34 = 87.66。Session CONSUMED，引用清空，其 RAW/PLAN 实际不存在且对应 Registry 为 DELETED；另一有效 Session 仍 PREVIEW_READY，其 LIVE 对象实际存在。 |
| Replay / 新进程恢复 | 同进程 Replay 与真实新版 JVM 重启后的 Replay 均成功，`idempotentReplay=true`，Receipt 与首次 Confirm 完全相同；Receipt GET 一致，完整业务表快照不变，无第二次金融 effect。 |
| Cancel / Expire 的删除重试 | 另一隔离 fixture 在自然登记后，由测试进程持有真实 RAW control OS FileLock。Cancel 与到期访问实际返回 503，DB 分别 CANCELLED / EXPIRED；三个对象均 RETIRED、没有 DELETED，Session 引用和实际目标文件保留，重试元数据持久保存。释放真实 guard 后，新版应用实际重启，startup cleanup 将三个对象全部安全删除并登记 DELETED，终态引用清空；余额仍 100.00、无 Transaction / Batch。 |
| 旧源与身份 | Confirm 及终态回收后的源 V20 payload 仍存在且摘要不变；只清理新受管根内的已登记退休对象。新 storage marker/control 未被复制或覆盖。 |

### 31.5 证据入口与复现前置条件

全部本轮输出根：`backend/target/c9-legacy-upgrade-07b936a9-c78c-4066-b692-74cb8edc7d1a`。`final-evidence/summary.json` 与该目录内的唯一新增测试 XML记录最终计数/源码/构件 SHA；`preflight-source-sha256.json` 保留起点 30 项匹配结果；`jar-sha256.json`、`source/backend` 及两个 package log 记录构件来源。`native-corrected-evidence` 的三个 scenario 目录保留旧 DB、Session、payload SHA、真实文件副本、应用进程日志、成功 Receipt/DB snapshot、锁未释放时 RETIRED 重试状态及最后 DELETED 状态。原 `c9-option-a-final-evidence` 和更早证据没有改写。

新增测试要求先准备指定历史 HEAD 的独立 JAR 和当前生产 JAR；这是两个真实生产版本的升级验证，不能用当前代码伪造旧文件/Session替代。最终实际命令（在 `backend` 下，`$taskRoot` 指向上文自有输出根）：

```powershell
$env:JAVA_HOME = 'F:/ideajava/jdk21'
./mvnw.cmd -B '-Dtest=TransactionImportLegacyUpgradePostgresIntegrationTest' `
  '-Dc9.postgres.bin=F:/PostgreSQL/bin' `
  "-Dc9.legacy.jar=$taskRoot/source/backend/target/finance-os-backend-1.0.0.jar" `
  "-Dc9.legacy.evidence=$taskRoot/native-corrected-evidence" test
```

这是本机 PG17.10 / Windows / JDK21 的真实补充验证，不把它写成容器或 Linux 的本轮实跑。此前 Linux/Docker 支持证据仍见第 27–30 节，本轮未重跑；多主机/网络卷、断电/FSYNC、未知历史孤儿 inventory/删除、真实部署 ACL 和真实上传根迁移仍在原支持与授权边界内，未声称完成。

### 31.6 独立复核与决定

按用户要求，调度记录分别使用 `gpt-6-luna` / `max` 配置创建 `/root/c9_legacy_storage_review` 与 `/root/c9_legacy_contract_review`；两个 Agent 均只读、没有修改文件或运行 Maven。模型名称依据主控实际调度参数，不依据子 Agent 自我推测。两者独立核对最终源码、XML、summary、日志与三场景状态快照，均未发现 P0/P1，支持 **READY FOR FINAL ACCEPTANCE**。不把读取主控 XML/log 解释为复审独立实跑。

存储复核确认真实旧 Session、显式 payload 迁移、自然登记、物理对象/状态/源摘要与重启回收一致，并提醒锁阻塞时两个 RAW 的 `attempt_count=1`，PLAN 为 0：RAW 优先删除失败阻止 PLAN 尝试，但三个对象均已持久 RETIRED，重启后全部安全 DELETED。金融复核确认旧 token、Receipt、余额与事实、两次 Replay 和新增计数一致；未知历史 orphan / `.part` 处置没有在本轮直接造夹具验证，也没有据此扩张验收声明。

主控核对当前生产 JAR 中 **60 个 Import class** 与 `target/classes` 逐项 SHA-256 一致；原 30 项源码身份仍与本轮起点及上一轮最终记录一致。最终测试源码与实跑记录的 SHA 一致。`git diff --check`、staged diff check 通过；新增测试与追加 Review 另行检查无尾部空白/冲突标记。HEAD 未改变，staged diff 为空，本轮实际改动只有新增定向测试和本 Review 追加，未混入其他改动。

主控结合真实实跑和两名只读复核结论，在原 Option A 单主机受信部署合同及本节明确的人工迁移流程内给出：

**C9 — READY FOR FINAL ACCEPTANCE**

本轮补充验收完成后暂停，等待人工最终验收；不宣称 C9 CLOSED — GO，不 commit / push / tag，不启动 C10 / C11 或 Finance Freeze。未授权的真实上传根迁移、历史孤儿处置及原支持矩阵外场景不纳入本次完成声明。
