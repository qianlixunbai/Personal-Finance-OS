# 项目状态

> 本文件是 Personal Finance OS 当前状态的唯一事实来源。

**本阶段实施起点：** `zh-cn` @ `bb2ff37948782a40180a312374c5a986087019cf`

**当前 Finance 核对基线：** `zh-cn` @ `dc7e6feb550224aded70c907cc26550bbff9bdac`

**核对日期：** 2026-10-12

## 当前产品阶段

**Finance G4-1 / C10 — 文档、迁移与安全状态一致性修复：技术验收 GO；正式 CLOSED 以精确提交、推送和独立远端核验为条件。** C1–C9 已正式 CLOSED — GO，原始审计关闭进度在提交前保持 **9/11**；C10 提交前尚未正式关闭，C11 未开始，Finance 仍处于 Final Stabilization，G5 全量最终验收未完成。C9 的正式关闭依据本轮用户确认；其原 Review 保留当时的候选/待验收结论和历史测试数字，不回写历史。C10 本轮证据见 [C10 Review](archive/review/G4-1-C10-Documentation-Schema-Security-Drift-Review.md)。

**AI Phase 5 — Frontend AI Analyst Entry：CLOSED — GO。** `/ai` 位于既有认证路由内，桌面侧栏与移动底栏均提供“AI 分析”入口。页面通过既有 Axios client 提交单个问题，只展示纯文本回答；快捷问题仅填充输入，新请求与失败保留上一条成功结果，且不保存历史或携带多轮上下文。本阶段保持后端零修改、无新增依赖。

2026-09-30 离线验证：实现阶段 `npm run test:e2e:ai` 的 9 个定向 Playwright mock 用例实际通过（10.9s），覆盖认证导航、空白/trim/3000 上限、JWT/请求体、请求防重、旧结果保留/替换、429/502/503/500、401、HTML 文本安全和无历史持久化。1280px、820px、768px、390px、320px 的长回答无横向溢出；390px / 320px 的移动底栏六项点击区域分别约为 65 × 60px / 53 × 60px。另已查看虚构 mock 回答的桌面与窄屏截图。最终封板时再次运行 `npm run build`、`npm run lint`，均通过；E2E 通过后无生产代码修改，未重复 E2E 或真实 AI 调用。Vite 保留主 bundle 超过 500 kB 的构建提示，本阶段未调整打包结构。

真实 runtime UI 验收依据：用户于 2026-09-30 完成 backend + frontend 人工 UI smoke 并确认 GO。正式导航可进入受认证保护的 `/ai`，建议问题只填充，用户提交后 loading 正常；真实 `POST /api/v1/ai/ask` 成功，回答符合当前登录用户的数据状态，且未暴露 Tool/Provider/userId 等内部结构。回答以安全纯文本展示，刷新不恢复问题或回答，390px / 320px 未发现明显横向溢出或导航不可用问题。此为用户确认的人工验证，区别于上述 mock 测试。

**AI Phase 4 — Authenticated AI Analyst HTTP API** 已实现并由用户确认 GO。以下为历史证据：离线定向测试与后端编译通过；据用户 2026-09-29 在 IDEA 启动后完成的真实 HTTP smoke：未认证请求返回 401，附加 `userId` 与空白问题均返回 400，认证用户问答返回 200、回答非空且响应只含统一包装与 `answer`。用户在本机核对了当前账号的数据范围，以及回答正文和后端日志未暴露 Tool/Provider 内部内容或敏感信息。Phase 3 已正式完成并确认 GO；其真实百炼 Tool Calling smoke 是前一阶段的历史证据。Phase 1 Provider Foundation 与 Phase 2 Read-only Finance Tools 已进入正式基线。当前仍无多轮聊天入口或自主 Agent。

Transaction Import Frontend A–D 已实现，当前代码覆盖 CSV / XLSX 上传、同一 Session 字段映射、服务端分页 Preview、warning acknowledgement、Confirm、未知结果恢复、权威 Receipt 路由、reload、多标签页保护和真实浏览器 E2E。其最终独立 Closing Re-Review 仍待完成。

最新 Frontend Closing Review 的结论仍是：

```text
Implementation remediation complete — Ready for Final Independent Closing Re-Review
```

因此当前可以陈述“功能已实现并完成 remediation”，但不能陈述为“最终独立验收已 CLOSED — GO”。

## 当前能力概览

| 领域 | 当前能力 |
| --- | --- |
| 身份与安全 | 注册、登录、JWT、BCrypt、用户数据隔离、安全 `404` |
| 基础财务 | Account、Category、普通 `INCOME` / `EXPENSE` / `ADJUSTMENT`、后端余额联动；独立 `POST /api/v1/transfers` 在同事务内联动两侧 CNY 余额，不生成普通收支流水 |
| Dashboard | 净资产、月度收支、趋势、资产分布、最近流水的后端聚合；`REQUIRES_NEW` + `REPEATABLE_READ` 只读快照隔离 |
| 市场参考数据 | Market Quote、FX snapshot、显式刷新、只读 CNY reference valuation；Market Quote 持久化拒绝旧 `quote_time` 覆盖新报价，FX 类似 affected rows 风险仍待调查 |
| 投资账本 | Opening、BUY、SELL、DIVIDEND、reversal、replacement、确定性 replay |
| 投资读取与 UI | Portfolio、Position、logical transaction、audit timeline、投资命令与纠正 UI |
| Transaction Import Backend | CSV / XLSX、mapping、Preview、validation、Confirm、权威 Receipt |
| Transaction Import Frontend | 上传到回执的完整 UI、恢复、lossless amount transport 与 E2E；最终独立 Closing Re-Review 待完成 |
| AI | `/ai` 前端只读分析入口，复用 Axios / JWT，最多 3000 字符、请求防重、纯文本回答与稳定错误提示；JWT 认证单轮问答 `POST /api/v1/ai/ask`、用户级内存限流、脱敏错误；单一 Cloud Provider、三个只读 Finance Tools，以及受控 Tool Calling；手写 allowlist、服务端身份传入、最多 3 轮 / 5 次 Tool 调用；无历史、Memory 或 streaming |
| Import 存储 | V21 持久 Registry / Storage Binding、RAW / PLAN 持久受管根、显式预配、方案 A 单主机受信部署；旧 V20 活跃 Session 需显式离线迁移 |
| Account / Investment Binding | 账户类型更新在 owned Account 锁内拒绝不兼容的 transaction-driven 绑定，包括 CLOSED 仓位；保留 C3 脏数据与 REPLACEMENT 防御边界 |
| 工程基础 | Flyway V1–V21、PostgreSQL Testcontainers、Docker、GitHub Actions |

## 已关闭的重要阶段

| 范围 | 正式状态 | 验收入口 |
| --- | --- | --- |
| v1.x Foundation / Engineering | CLOSED | [v1.x Reviews](archive/review/README.md) |
| v2.0 Market Data | CLOSED — GO | [V2.0 Closing Review](archive/review/V2.0-Closing-Review.md) |
| v2.1 Market Valuation | CLOSED — GO | [V2.1 Closing Review](archive/review/V2.1-Closing-Review.md) |
| v3.0 Investment Ledger / Write / Read | CLOSED — GO | [Investment Reviews](archive/review/README.md) |
| Investment Command & Correction UI | CLOSED — GO | [Closing Review](archive/review/V3.0-Investment-Command-Correction-UI-Closing-Review.md) |
| Transaction Import Contract / Backend Foundation / Preview / Confirm | CLOSED — GO | [Phase 3D Closing Review](archive/review/V3.0-Phase3D-Transaction-Import-Confirm-Closing-Review.md) |
| C1–C9 原始审计整改 | CLOSED — GO（9/11） | 本轮用户确认；[C9 历史实施与升级证据](archive/review/G3-2-C9-Import-Temporary-Storage-Lifecycle-Review.md)，其候选结论保留原时间点含义 |
| AI Phase 5 — Frontend AI Analyst Entry | CLOSED — GO | 本文件记录的离线验证与用户 2026-09-30 真实人工 UI smoke 确认 |

各阶段的 HEAD、测试数字、P0/P1/P2/P3 和 GO/NO-GO 只以对应 Closing Review 的历史快照为准。

## 当前正在推进的工作

1. C10 技术验收 GO；正式 CLOSED 以精确提交、推送和独立远端核验为条件；不自动进入 C11 或 Finance Freeze。
2. Transaction Import Frontend A–D 的最终独立、只读 Closing Re-Review 仍待完成。

## 下一核心能力

AI Phase 5 已完成；下一项 AI 能力尚未确定。多轮聊天、会话持久化、Memory、RAG、流式输出与自主 Agent 均不属于已完成能力；Transaction Import Frontend 最终独立 Closing Re-Review 仍待完成。

## 主要未完成范围

- 普通 Transaction 的 `TRANSFER` / `REFUND` 类型语义与 Import 支持；独立 Transfer 创建 API 已实现，不代表真实银行转账；
- 对账、去重治理和余额校准；
- 历史持仓与收益曲线；
- 多币种账务、FIFO / lot、公司行动；
- 银行或券商自动同步；
- PWA、原生移动端和受控 AI Agent；
- 真实支付、资金划转、证券交易执行和投资建议明确不属于产品目标。

## 当前稳定技术边界

- 前后端分离的 Spring Boot 模块化单体；
- PostgreSQL 是持久化与事务一致性基础，schema 由 Flyway V1–V21 管理；表统计口径见 [Database](architecture/database.md)；
- 当前账务为 CNY 单币种，金额使用 `BigDecimal` / `NUMERIC`；
- 金融计算、余额、核心聚合和 Import Preview/Receipt 以后端为权威；
- 投资事实 append-only，当前 Position 由 canonical replay 与受控 `Asset` 投影表达；
- Market Quote、FX 与 reference valuation 是参考数据，不是账务真值；
- 架构级变化必须新增 ADR，不直接改写冻结的 v1.0 Architecture。
- C9 方案 A 仅覆盖 Windows / Linux 单主机持久本地根及 Linux Docker 持久受管卷；多主机、网络共享、高权限停机克隆识别与断电耐久性未验收。C9 CLOSED 不代表真实生产部署完成。

## 最近正式验收依据

- AI Phase 5：本文件记录的 9 个定向 mock E2E 通过、封板 build/lint 复验，以及用户 2026-09-30 真实人工 UI smoke GO。
- 后端 Import Confirm：[Phase 3D Closing Review](archive/review/V3.0-Phase3D-Transaction-Import-Confirm-Closing-Review.md)，结论为 `CLOSED — GO`。
- Import Frontend：[Frontend Closing Review](archive/review/V3.0-Transaction-Import-Frontend-Closing-Review.md)，记录 A–D 实现、两次最新 remediation 与验证证据，但明确保留最终独立 Closing Re-Review。

## 部署状态与独立 OPEN 项

当前 Compose 与示例环境文件已声明并传递 `JWT_SECRET`、`MIGRATION_PREVIEW_SECRET`、`FINANCE_IMPORT_CONFIRM_TOKEN_SECRET`、Import 持久 RAW / PLAN 根和三个稳定 UUID。生产根仍须显式预配，缺失 secret 或绑定异常会 fail-fast / fail-closed；配置存在及静态配置检查均不等于标准 Compose 端到端部署验收。本轮未启动真实生产部署，详见 [Deployment](engineering/deployment.md)。

以下独立事项保持 OPEN，C10 不关闭或实施这些任务：

- C8 前端行情提示 P2；
- FX affected rows 类似风险待调查；
- C4 cause-only 循环遍历；
- C3 不兼容脏数据 / REPLACEMENT 防御边界；
- C11 AI E2E CI 覆盖缺口；
- Import Frontend 最终独立 Closing Re-Review；
- Finance G5 全量最终验收。

## 状态维护规则

- 只有本文件维护当前阶段、当前工作、下一核心能力和未完成范围。
- README、Requirements、Roadmap 与 Architecture 只链接本文件，不复制完整阶段清单。
- 历史 design、review 和 logs 中的状态保持原样，不随当前进展改写。
