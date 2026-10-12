# G4-1 / C10 — 文档、迁移与安全状态一致性 Review

日期：2026-10-12（Asia/Shanghai）。核对基线：`zh-cn` @ `dc7e6feb550224aded70c907cc26550bbff9bdac`。

## 1. 结论与授权边界

**实施阶段（人工技术验收前）结论：C10 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。** 本次人工技术验收已 GO；正式 CLOSED 以精确提交、推送和独立远端核验为条件。第 2–11 节保留实施阶段证据。

本轮只修正现行文档中可证实的事实漂移并新增本 Review；不改变生产行为。C1–C9 CLOSED — GO、原始关闭进度 **9/11** 依据本轮用户明确确认。C9 原 Review 的候选/待验收措辞及历史测试数字保留原时间点含义，不由本轮改写，也不将 C9 关闭解释为真实生产部署验收。当前状态见 [STATUS](../../STATUS.md)。

实施阶段（人工技术验收前）：主控按用户指定 GPT-6.1 Sol High 执行；独立复审为两名 GPT-6 Luna Max，只读。未授权 commit、push、tag、C11 或 Finance Freeze；完成后停在人工验收边界。

## 2. Preflight 与受保护文件

| 检查 | 本轮实际结果 |
| --- | --- |
| 当前分支 | `zh-cn` |
| HEAD | `dc7e6feb550224aded70c907cc26550bbff9bdac` |
| 本地 `refs/remotes/origin/zh-cn` | 同上 |
| 独立远端 `git ls-remote --heads origin refs/heads/zh-cn` | 同上 |
| 初始 tracked / staged diff | 均为空 |
| 初始 untracked | 仅 `AGENTS.md`、`docs/codex-task.md` |

两份受保护文件仅通过 Git 路径状态确认存在；主控和两名只读 Agent 均未读取内容、hash、修改、删除、暂存或提交。未创建 branch/worktree，未执行 reset/stash/clean/rebase/amend。任务规则来自本轮用户提供的指令，未为查规则打开受保护文件。

## 3. 修改前漂移 → 权威事实 → 修复描述

下列“修改前”行号指指定 HEAD；源码相对路径均从仓库根解析。证据优先取当前生产源码、配置、SQL 与本轮隔离 catalog；历史 Review 只说明当时的合同和验证。

| 修改前错误或遗漏 | 权威事实与证据 | 修复后的描述 |
| --- | --- | --- |
| README:95、STATUS:42/79、Current Architecture:163 写 V1–V17；Database:8 写 V1–V20 | [迁移目录](../../../backend/src/main/resources/db/migration/) 连续 V1–V21；本轮 Flyway 实际执行记录见第 4 节 | 全部当前版本指向 V21，部署指南原本正确的 V21 保留 |
| Database:9 写 15 张表；概览漏 V18/V19/V21，表清单漏 Transfer 和两张技术表 | 本轮 `pg_catalog.pg_class` 返回 19 张普通表；[V18](../../../backend/src/main/resources/db/migration/V18__add_transaction_create_idempotency.sql)、[V19](../../../backend/src/main/resources/db/migration/V19__add_transfer_ledger_foundation.sql)、[V21](../../../backend/src/main/resources/db/migration/V21__add_import_file_lifecycle_registry.sql) | 分为 16 张业务/参考/审计、2 张 Registry/Binding、1 张 Flyway；补全三版本作用和遗漏表 |
| Database:77 将 Import 表笼统列为 `user_id` 隔离 | V21 binding 无 `user_id`，file object 使用可空 `owner_user_id`、Session 与 storage identity；业务 Session/Batch/Item/Impact 仍有 `user_id` | 区分部署级技术表与用户级业务表，不误称 Registry 是金融事实 |
| STATUS:94、Security:37、Current Architecture:168、Transaction Import:265 写 Compose 未传 Import Confirm Secret | [compose.yml](../../../docker/compose.yml):27–34 明确传三类 secret、RAW/PLAN 根和三个 UUID；[.env.example](../../../docker/.env.example):5–7/13–17 声明示例 | 纠正为已传递；保留首次预配与 runtime 未验收，不宣称配置存在即能生产运行 |
| README 本地启动仅列 secret，未说明当前非 test profile 的存储前置；当前架构/安全/Import 仍按私有临时根或简单路径检查概括 | [TransactionImportPreviewConfiguration](../../../backend/src/main/java/com/financeos/module/importing/config/TransactionImportPreviewConfiguration.java):24–59 要求绝对独立根和 UUID；[ImportStorageProvisioner](../../../backend/src/main/java/com/financeos/module/importing/storage/ImportStorageProvisioner.java):8–42 显式预配；[LocalTemporaryImportFileStorage](../../../backend/src/main/java/com/financeos/module/importing/storage/LocalTemporaryImportFileStorage.java) 使用 Registry 与 root guard | 文件短生命周期、根和绑定持久；预配显式执行、生产启动只验证；异常 fail-closed、终态退休与持久重试 |
| Deployment 升级段仅笼统写精确旧引用登记，未完整列出离线迁移、旧 secret/TTL 保留；缺断电边界 | V21 无历史 DML/文件迁移；[C9 历史 Review 第 31 节](G3-2-C9-Import-Temporary-Storage-Lifecycle-Review.md#31-c9-最终验收补充--真实-v20-活跃-session-升级证据):610–616 记录停写、预配、精确复制与校验；[SessionService](../../../backend/src/main/java/com/financeos/module/importing/service/TransactionImportSessionService.java):19/50 固定 15 分钟 | 补全显式离线升级与原 expiry、secret、Plan/token 保留；未知孤儿不自动删除，多主机/网络共享/断电未验收 |
| Deployment:89 将预配描述为拒绝“已有……目录”，未区分已有空根与已有绑定 | ImportStorageProvisioner:35–39 允许已存在但为空的根；TrustedImportStorageRoot:49–54 使用 CREATE_NEW 创建 marker/control，拒绝覆盖既有元数据 | 精确改为拒绝“已有绑定、部分初始化或非空目录”，不改变预配逻辑 |
| 当前能力摘要没有区分已存在的 Transfer API 与未支持的普通 `TRANSFER` 类型；Security 的用户域枚举漏列 Transfer | [TransferController](../../../backend/src/main/java/com/financeos/module/ledger/controller/TransferController.java):14–28；[TransferService](../../../backend/src/main/java/com/financeos/module/ledger/service/TransferService.java):33–68；[API](../../architecture/api.md) 已列独立 POST | 明确独立 Transfer 已实现、同事务双余额联动且按用户隔离；普通 Transaction/Import 仍为三类型，不执行银行转账 |
| Current Architecture/STATUS 的 Dashboard 摘要漏当前快照隔离，Account binding/quote 摘要漏 C2/C3/C8 修复及边界 | [DashboardService](../../../backend/src/main/java/com/financeos/module/dashboard/service/DashboardService.java):47–48；[AccountService](../../../backend/src/main/java/com/financeos/module/account/service/AccountService.java):81–84；[InvestmentPositionBindingService](../../../backend/src/main/java/com/financeos/module/investment/instrument/service/InvestmentPositionBindingService.java):37–49；[MarketQuoteMapper](../../../backend/src/main/java/com/financeos/module/asset/marketdata/mapper/MarketQuoteMapper.java):55 | 摘要补 `REQUIRES_NEW/REPEATABLE_READ`、类型变更的绑定防御和报价防倒退；保留相关独立 OPEN 风险 |
| API:36–38 幂等 Header 表漏普通创建的可选 Header | [TransactionController](../../../backend/src/main/java/com/financeos/module/ledger/controller/TransactionController.java):67–72；[TransactionService](../../../backend/src/main/java/com/financeos/module/ledger/service/TransactionService.java):80–102；V18 nullable 字段与 partial unique | 增补普通 `POST /api/v1/transactions` 可选 `Idempotency-Key`；仅提供 key 时适用格式要求，不改变既有调用 |
| STATUS 未反映本轮用户确认的 C1–C9 关闭和当前 C10；部署 AI Key/Compose 边界易混淆 | 本轮用户明确给定 9/11；[application.yml](../../../backend/src/main/resources/application.yml):66–75 默认关闭 AI；[AiProperties](../../../backend/src/main/java/com/financeos/module/ai/config/AiProperties.java):25–61 按 enabled 验证；Compose 未传 `FINANCE_AI_*` | 记录本轮基线/候选验收状态和来源，历史 AI 证据保留；明确 AI Key 可选启用，标准 Compose 未开放 AI 变量传递 |

## 4. 本轮真实 Flyway / Schema 验证

Docker daemon 当前不可用（Linux Engine pipe 不存在），不是 migration 代码缺陷。未启动 Docker Desktop、重置配置或接触已有 volume。改用本机 **PostgreSQL 17.10** 二进制，在本轮新建的 `backend/target/c10-evidence-1a12f45ed65/pgdata` 执行 initdb，仅监听 `127.0.0.1` 随机端口（本轮为 3114）。未连接、扫描或迁移真实用户数据库；没有访问 `F:/PostgreSQL/data` 或真实上传根。

自有探针通过 JDK21 source launcher 使用 Flyway 10.20.0，迁移位置直接指向本仓库未修改的 `backend/src/main/resources/db/migration`，不使用旧构件缓存的 migration。依赖 JAR 从本机现有构件只提取 library 到自有 evidence/lib；没有重编译生产代码或启动 Spring 应用。

本轮证据保留在：`backend/target/c10-evidence-1a12f45ed65/`。最终探针 `C10SchemaProbe.java`、成功日志 `schema-probe-verified.log`、initdb/PG 进程日志和停止日志可核验。数据库进程已通过本轮自有 data 路径的 `pg_ctl stop -m fast` 停止，保留 cluster 与失败日志，不删除用户临时资料。

首次探针空库迁移已成功，但升级夹具误写不存在的 `preview_plan_reference`；权威列是 V14 的 `plan_storage_reference`。这次失败发生在夹具 INSERT，未执行 V21 升级，保留 `schema-probe.log` 与 `C10SchemaProbe.first-fixture.java`。第二次命令因 PowerShell 未引用 `-Duser.language=en` 参数而未启动 Java 探针，保留 `schema-probe-final.log`。最终只修正探针字段和 shell 引用，没有改 SQL、生产代码或削弱断言。

| 本轮实际执行 | 结果与范围 |
| --- | --- |
| 新空库 V1 → V21 | 恰执行 21 份迁移；Flyway validate 成功；history 21 条 versioned success |
| 最终 catalog | `public`、`relkind='r'`：19 张；V21 file object 表 3 个索引 |
| 独立升级夹具 V1 → V20 | 恰执行 20 份迁移，再插入虚构用户/账户/普通流水/Receipt 与 MAPPING_REQUIRED、PREVIEW_READY Session |
| V20 → V21 | 恰执行 1 份迁移；全部 16 张 V20 应用表 JSON 行快照完全不变，包含 Receipt、旧 UUID 引用、revision、digests 与 `expires_at`；validate 成功 |
| 升级后的技术表与 catalog | Registry/Binding 各 0 行；旧两条活跃 Session RAW 引用保留；fresh/upgrade 表清单一致 |

本轮为隔离 SQL/catalog 探针，**不是完整业务测试套件或真实旧版本应用 HTTP 升级测试**。升级夹具为 synthetic；C9 的真实旧 Session/Confirm/replay/物理删除实跑仍只作为历史证据。

统计 SQL：

```sql
SELECT c.relname
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
ORDER BY c.relname;
```

统计只计最终 schema 的普通持久表，不计系统 schema、索引、序列、视图、临时表或 SQL 文本出现次数。

| 分类 | 数量 | catalog 表名 |
| --- | --- | --- |
| 用户/基础财务 | 5 | `users`、`accounts`、`categories`、`transactions`、`transfers` |
| Asset/参考 | 4 | `assets`、`asset_prices`、`market_quotes`、`exchange_rates` |
| 投资事实/主数据/纠正 | 3 | `investment_instruments`、`investment_transactions`、`investment_transaction_corrections` |
| Import 业务/Receipt | 4 | `transaction_import_sessions`、`transaction_import_batches`、`transaction_import_items`、`transaction_import_batch_account_impacts` |
| Import 技术 Registry/Binding | 2 | `transaction_import_file_objects`、`transaction_import_storage_bindings` |
| Flyway 技术历史 | 1 | `flyway_schema_history` |
| 合计 | 19 | 应用表 18 = 业务/参考/审计 16 + Registry/Binding 2；再加 Flyway 1 |

本轮 fresh history 中全部实际应用文件：

```text
V1__baseline.sql
V2__market_quotes.sql
V3__exchange_rates.sql
V4__investment_ledger_foundation.sql
V5__harden_investment_ledger_constraints.sql
V6__align_asset_projection_precision.sql
V7__create_investment_instruments.sql
V8__bind_asset_positions_to_instruments.sql
V9__harden_opening_position_migration.sql
V10__harden_investment_write_receipts.sql
V11__harden_dividend_write_receipts.sql
V12__add_append_only_investment_reversals.sql
V13__add_investment_transaction_replacements.sql
V14__add_transaction_import_backend_foundation.sql
V15__allow_cleared_transaction_import_file_references.sql
V16__add_transaction_import_confirm_receipt.sql
V17__repair_transaction_import_receipt_digest_timestamps.sql
V18__add_transaction_create_idempotency.sql
V19__add_transfer_ledger_foundation.sql
V20__decouple_import_receipt_transaction_lifecycle.sql
V21__add_import_file_lifecycle_registry.sql
```

## 5. Secret / Compose 核对

| 变量 | 当前实现 | Compose 与示例 |
| --- | --- | --- |
| `JWT_SECRET` | application.yml 注入；JwtUtil:18–20 使用 UTF-8 key 经 JJWT HMAC 校验（至少 32 字节） | 显式必需传递；有示例占位值 |
| `MIGRATION_PREVIEW_SECRET` | application.yml 映射；MigrationPreviewTokenService:25–32 至少 32 字符、无可用默认值 | 显式必需传递；独立于 Import secret |
| `FINANCE_IMPORT_CONFIRM_TOKEN_SECRET` | TransactionImportPreviewTokenService:31–35 直接读取 `finance.import.confirm.token-secret`，缺失/空白/不足 32 字符 fail-fast；Spring relaxed property binding 接收环境变量，application.yml 无单独显式条目 | compose.yml:29 明确传递；.env.example:7 声明；旧文档“未传”已修 |
| `FINANCE_AI_API_KEY` | AI 默认关闭；enabled=true 时要求 CLOUD、合法 HTTPS baseUrl、Key、model 与有效 timeout | 当前 Compose 未传 `FINANCE_AI_*`、示例未声明；不能仅靠 .env 推断 AI 已启用 |
| `FINANCE_IMPORT_STORAGE_RAW_ROOT` / `PLAN_ROOT` | 非 test profile 要求两个独立绝对路径，根不相等也不互相嵌套 | Compose 固定挂载到持久卷内 `/var/lib/finance-os-import/raw` / `plan` |
| `FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID` / `RAW_ID` / `PLAN_ID` | 非 secret 稳定 UUID；RAW_ID 与 PLAN_ID 不同，需与卷及绑定匹配 | backend 和显式 provision profile 均传递；示例提醒正常重启不重新生成 |

本轮只读取跟踪配置及 `.env.example`，未读取真实 `.env`、API Key、数据库密码或 token；报告不回显真实凭据。Import token 使用原 secret 与 Session expiry，密钥安全与生产根预配都是启动前置条件。

实际运行 `docker compose --env-file docker/.env.example -f docker/compose.yml config --quiet`：退出码 0。此命令只验证声明和插值，**未启动 PostgreSQL/backend/frontend，不是端到端部署验收**。缺失/过短 secret 的 runtime 行为本轮仅按构造器静态核验，未再次启动 production 验证。

## 6. C9 新增部署合同一致性

- V21 additive，两张技术表；V1–V20 不修改，旧 Session/Receipt 不被 migration 重写。
- 方案 A：Windows / Linux 单主机持久本地根及 Linux Docker 明确持久受管卷；跨进程只限同主机、同 DB/root/identity。Windows 依赖 JDK21 `NOSHARE_DELETE` pin，Linux 依赖 `SecureDirectoryStream`；能力缺失拒绝操作。
- RAW/PLAN root、marker/control、三个稳定 UUID 持久保留，部署方提供独占 ACL/所有权和父目录保护。生产启动只验证，显式 Provisioner 才初始化新空根；Compose 正常 up 不自动运行 provision profile。
- 旧 V20 活跃 Session 升级：停旧 writer/cleanup → V21 → 新空根显式预配 → 按 DB 精确引用复制校验 RAW/PLAN → 保留原 Session/revision/digests/Plan/token/secret/expiry → 新应用自然登记。TTL 仍为 15 分钟，不能因升级延长。
- 未知孤儿不自动 inventory/删除；缺文件或身份/摘要/权限不明时保留证据、停写/停清理，人工核定，不自动重建绑定或清空 Registry。
- root 身份丢失/异常持久 quarantine；恢复路径/marker 不自动解隔离。方案 A 依赖运维不静默克隆/替换，不能自动证明高权限停机换根身份。
- 实际 worker 持有 guard 至 I/O 结束；调用方超时不释放正在执行的保护，受阻清理保持 RETIRED 重试；Confirm 内存读取完成后再进入金融事务。
- 多主机、网络共享及断电/fsync 耐久性未验收；进程 kill/restart 不等于断电保证。C9 CLOSED 不等于真实生产部署完成。

## 7. 功能与 API 状态静态核查

| 项目 | 当前真实边界 | 本轮证据类型 |
| --- | --- | --- |
| 普通 Transaction / Transfer | Transaction/Import 仍为 INCOME/EXPENSE/ADJUSTMENT；独立 Transfer POST 已存在，单事务双账户余额联动，无真实银行执行 | Controller/Service/V19 静态审计；未运行金融回归 |
| Investment Ledger / Reversal / Replacement | append-only 投资事实；standalone reversal 与 same-type replacement、receipt/correction envelope；与普通 Transaction 分离 | 当前 investment Controller/Service、V4–V13、现行 API/领域文档；历史 Closing Review 不重跑 |
| Dashboard Snapshot | getDashboard 上 `readOnly=true`、`REQUIRES_NEW`、`REPEATABLE_READ` | 当前生产注解；C2 只作历史验收 |
| Account Type / Binding | 更新类型前在 owned Account lock 内校验不兼容 transaction-driven Position（含 CLOSED） | AccountService/InvestmentPositionBindingService；C3 脏数据/REPLACEMENT 边界不变 |
| Import Confirm / Receipt | 所有流水、余额、Receipt、Session 一事务；GET/同 intent replay 不要求当前流水仍存在；文件退休/清理不改变金融回执 | Confirm/Receipt/cleanup/lifecycle Service、V20/V21；本轮只跑 SQL migration |
| Market Quote | `WHERE EXCLUDED.quote_time >= market_quotes.quote_time` 拒绝旧候选；current persistence 对 0 行回读权威状态 | MarketQuoteMapper / MarketQuotePersistenceService；不延伸到 FX 已修 |
| AI Analyst | getFinancialOverview / getMonthlyCashFlow / getInvestmentPortfolioSummary 三只读工具，服务端 principal，最多 3 轮/5 次 Tool 调用，无写入/refresh | AiController、FinanceToolDispatcher、FinanceAiOrchestrator、Query Service；未调用真实 Provider |
| AI 未实现能力 | 无多轮历史、Memory、RAG、Streaming、自主 Agent | 当前无状态编排/纯文本前端及现行状态；未将计划写成已实现 |
| Import Frontend | A–D 实现/remediation；最终独立 Closing Re-Review 仍待完成 | 现行 STATUS 与原 Frontend Closing Review 明确保留；未运行浏览器测试 |

修改涉及的 `/api/v1/transfers`、`/api/v1/transactions` 与既有 `/api/v1/ai/ask` 均核对真实 Controller；不从 SQL 表推断 API，也不把 Transfer 等同普通 `TRANSFER` 类型。API 原有 parser code 408 对应 HTTP 500 的说明仍与 handler 一致，未顺带修生产异常映射。

## 8. 修改文件与精确行范围

以下范围以最终工作区行号为准；只列实际变更 hunk，不表示整文件被重写。新增 Review 单独列全文件范围。

<!-- C10_CHANGE_RANGES_START -->
| 文件 | 最终变更行范围 |
| --- | --- |
| `README.md` | 81、96、124–125 |
| `docs/STATUS.md` | 7–9、13–14、38–40、46–48、60、67–68、76、87、93、101–105、107–113 |
| `docs/architecture/api.md` | 37、39 |
| `docs/architecture/current-architecture.md` | 15、32、45、52–53、81–82、86–87、105–106、137、140、145、168、173 |
| `docs/architecture/database.md` | 8–9、34–35、37、51、79–87、90–91、162、177 |
| `docs/architecture/security.md` | 18、37、61、63–65、78 |
| `docs/domain/transaction-import.md` | 165、170–173、203–204、267、270 |
| `docs/engineering/deployment.md` | 12、18、49–53、59–60、67–68、79–80、97、107–118、124–125 |
| `docs/archive/review/G4-1-C10-Documentation-Schema-Security-Drift-Review.md` | 新增全文 1–222 |
<!-- C10_CHANGE_RANGES_END -->

除上述权威文档与本 Review 外，仅在已忽略的本轮 `backend/target/c10-evidence-1a12f45ed65/` 保存隔离验证资产。没有新增规划文件或复杂工作流目录。

## 9. 引用、范围与 Git 验证

检查对象是本轮修改文档和新 Review 中的 Markdown 内部相对链接、HTML 图片路径及标题 anchor；不访问远端网站，也不宣称外链可用性已验证。新增 C9 第 31 节 anchor 已按实际标题核对。Flyway 文件名来自本轮真实 history，ADR-020 引用指向现有文件；生产类名/API 与当前源码逐项核对。

链接检查使用本轮自有 `check-doc-links.cjs`，不读取受保护文件内容：**89 个内部目标、2 个标题 anchor、0 错误**，覆盖 8 份现行文档与本 Review。主控静态核对所有新增 API、migration/ADR 引用与类名；旧版本号及“Compose 未传 Import Confirm secret”在这 7 份重点文档中检索无残留。外链可用性未验证。

本轮 `final-check.cjs` 校验 tracked 精确 allowlist、untracked 路径、branch/HEAD 与 staged 状态；结果保存在自有 evidence 的 `scope-final.json`、`doc-links-final.json`。实际 `git diff --check` 通过；本 Review 尚未跟踪，另逐行检查尾随空白通过，不声称 git diff 自动覆盖 untracked。已检查实际 diff 和 staged diff（为空），没有暂存。

最终 tracked 修改仅第 8 节列出的 8 份维护文档；untracked 仅新 Review 与两份原受保护路径。`git diff --exit-code -- backend/src frontend/src docker .github docs/ADR docs/architecture/frozen-v1.0-architecture.md` 通过、无输出；allowlist 同时排除了其他模块与 C1–C9 原 Review 的修改。HEAD、本地 origin 和独立远端仍为指定 SHA；保护路径状态不变，未通过读取/hash 比较其内容。隔离 PG 自有 cluster 的 `pg_ctl status` 返回 no server running，停止日志记录 server stopped。

## 10. 两名 Luna Max 独立只读复审

两名 Agent 均完成修改前事实审计和最终只读复审；主控重新核对其 finding 与源码，没有仅凭子 Agent 报告改代码。

| 独立复审 | 实际范围与结论 | 证据限制 |
| --- | --- | --- |
| Luna Max 1：`luna_schema_review` | Flyway/Schema/表口径、部署合同、V20→V21、C9；确认 catalog 19=16+2+1、additive 升级/快照描述、Transfer 独立 API 与用户确认的 C9 状态来源准确。预配“已有绑定”和 Transfer 用户域枚举文案经源码核验、主控修正后再确认；最终未发现 P0/P1 或 C10 验收阻断 | 只读源码、SQL、diff、主控探针源码/成功日志；没有自行运行数据库、迁移或部署验证 |
| Luna Max 2：`luna_security_review` | Secret、安全配置、功能/API、文档范围；确认 Compose 已传 Import secret且未扩大部署验收、AI 默认关闭/三只读工具、金融/Import/quote 边界及独立 OPEN/9/11 保留；最终 P0/P1/P2 与已证明的阻断 finding 均无 | 只读源码/配置/diff/Review；未读主控探针日志、未执行测试或探针。最后 Transfer 枚举一词增补由主控与复审 1 核验 |

两者均未读取/hash受保护文件、真实凭据或用户数据库，未编辑仓库文件。复审结论是静态独立意见；Compose/link 检查和 PostgreSQL 探针明确标为主控本轮执行，历史测试未被改称独立实跑。

## 11. Findings、未验证与独立 OPEN 项

已确认的 C10 漂移仅需现行文档修复，没有发现需变更生产逻辑、安全合同或 migration 的新阻断事实。主控源码复核、隔离验证与文档终检支持候选结论。

| 等级 | 本轮 C10 结论 |
| --- | --- |
| P0 | 未发现 |
| P1 | 未发现；无尚未解决的安全事实冲突 |
| P2 文档漂移 | 第 3 节已确定漂移全部修正；复审提出的预配措辞和 Transfer 用户域枚举已核对源码修正，无 C10 阻断残留 |
| 独立 OPEN | 下列事项继续保持原状态，不转记为本轮已关闭 |

以下事项保持独立 OPEN，不因 C10 候选结论关闭：

- C8 前端行情提示 P2：忽略服务端旧候选被拒绝的细分 warning；
- FX affected rows 类似风险待调查；
- C4 cause-only 循环遍历；
- C3 不兼容脏数据 / REPLACEMENT 防御边界；
- C11 AI E2E CI 覆盖缺口；
- Import Frontend 最终独立 Closing Re-Review；
- Finance G5 全量最终验收。

本轮未验证：真实生产启动、完整 Compose end-to-end、真实根 ACL/迁移、未知孤儿清理、旧版应用 HTTP 生命周期、Linux/Docker Storage runtime、多主机/网络共享、断电与文件系统损坏、真实 AI/Market/FX Provider、全量后端与前端测试。历史 C1–C9/AI/Frontend 数字均保留历史标签，不拼接为本轮通过数；本轮也未编译生产源码。

## 12. 最终验收边界

已确定文档漂移已修正，隔离 Schema 与引用检查、两名独立只读复审和范围自查已完成，实施阶段（人工技术验收前）结论为 **C10 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**。本次人工技术验收已 GO；正式 CLOSED 以精确提交、推送和独立远端核验为条件，提交前原始审计关闭进度保持 9/11。若后续确认生产逻辑/安全合同/migration 本身冲突，结论必须转为 **C10 — DESIGN CHECKPOINT / NO-GO**，停止等待授权。

实施阶段（人工技术验收前）不 commit / push / tag；本次仅授权九份文档的精确提交、推送和独立远端核验，不进入 C11，不做 Finance Freeze，不修改其他项目或历史审计证据。
