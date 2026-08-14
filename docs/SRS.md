# 当前产品需求基线

> Phase 3C Transaction Import Preview & Validation 已 `CLOSED — GO`；本阶段不包含 Confirm 金融写入或前端，它们仍为 `NOT STARTED`。

> 覆盖 `Phase 2C Investment Read`、`Investment Command & Correction UI`、`Phase 3A Transaction Import Contract Design` 与 `Phase 3B Transaction Import Backend Foundation`；均已关闭并获得 GO。Phase 3B 仅交付后端基础，Preview、Confirm、解析和前端仍为 `NOT STARTED`。

## 已实现需求

- Auth、JWT 与用户隔离；Account、Category、普通 Transaction、Dashboard；
- Market Quote、FX 与只读 reference valuation；
- `InvestmentInstrument`、opening migration、`BUY` / `SELL` / `DIVIDEND`、standalone reversal、replacement；
- 内部 Position list、logical transaction list、logical correction 折叠与稳定 cursor 基础；
- 公开 Portfolio、Position 列表与 Position 详情只读 API；
- 公开 logical transaction 列表/详情与 audit timeline，只接受原始事实 ID 作为 `logicalTransactionId`；
- `/investments` 读取工作区，包含 Position / logical transaction 筛选与 opaque cursor 分页、详情、三层回执/快照及 correction audit 展示；
- Investment Command & Correction UI：First BUY、后续 BUY、partial/full SELL、CLOSED → reopen BUY、DIVIDEND、standalone reversal 与 same-type replacement；
- confirmation、server-authoritative receipt、401 recovery、409 reconcile、cross-tab recovery、duplicate-write protection 与 authoritative eligibility fail-closed；
- 后端权威计算、追加式审计、幂等、并发与确定性重放；
- Flyway、Docker Compose、CI 与 PostgreSQL Testcontainers 验证基础。

普通流水只支持 `INCOME`、`EXPENSE`、`ADJUSTMENT`；账户余额由后端事务维护。投资事实为不可变账本，不是普通 Transaction，也不执行真实交易。报价、FX 与参考估值不改变账务真值。

投资读取模型契约与各层实现分别由 [Phase 2C-1 design](design/V3.0-Phase2C-1-Investment-Read-Model-Contract.md)、[ADR-015](ADR/ADR-015-investment-read-model-contract.md)、[Phase 2C-2A Review](review/V3.0-Phase2C-2A-Closing-Review.md)、[Phase 2C-2B Review](review/V3.0-Phase2C-2B-Closing-Review.md)、[Phase 2C-2C Review](review/V3.0-Phase2C-2C-Closing-Review.md) 与 [Phase 2C-3 Review](review/V3.0-Phase2C-3-Closing-Review.md) 冻结并验收；Investment Command & Correction UI 的当前实现由 [Contract](design/V3.0-Investment-Command-Correction-UI-Contract.md) 与 [Closing Review](review/V3.0-Investment-Command-Correction-UI-Closing-Review.md) 约束并验收。

## 已冻结但未实现

`Phase 3A Transaction Import Contract Design` 已 `CLOSED — GO`，冻结普通 `Transaction` 的 CSV / XLSX 格式、canonical row、显式 Account / Category mapping、只读 Preview、Confirm binding、duplicate、batch idempotency、unknown-outcome recovery、atomicity、audit、文件生命周期与安全边界。契约见 [Phase 3A Transaction Import Contract](design/V3.0-Phase3A-Transaction-Import-Contract.md)。

Phase 3B 已实现 Import Session / Batch / Item 的 Flyway schema、用户隔离、持久化幂等约束、预分配 Batch ID、15 分钟服务端 Session TTL、60 秒 Confirm guard 事务、临时文件本地存储基础，以及已确认 Batch 的用户域状态查询。它不包含上传端点、CSV / XLSX 解析、Mapping、Preview、Confirm 财务写入或前端页面；完整导入流程仍为 `NOT STARTED`。

## 仍规划但未实现

其他未实现能力包括 `TRANSFER` / `REFUND`、历史收益曲线、多币种账务、FIFO/lot、公司行动、银行/券商自动同步、AI Agent 与原生移动端。

## 明确排除

系统不处理真实支付、资金划转、证券下单、托管或投资建议。当前 CNY 账务不因参考 FX 数据而成为多币种账务。

## 需求治理

架构基线冻结在 [Architecture](03-Architecture/Architecture.md)，架构级变更需要 ADR。当前细则见 [业务规则](Business%20Rules.md) 与 [金融规则](Financial%20Rules.md)；历史阶段证据见 [Review](review/)。
