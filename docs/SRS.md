# 当前产品需求基线

> 覆盖至 `v3.0 Phase 2C-2A` 内部读取实现基线；公开投资读取 API 与前端尚未开始。

## 已实现需求

- Auth、JWT 与用户隔离；Account、Category、普通 Transaction、Dashboard；
- Market Quote、FX 与只读 reference valuation；
- `InvestmentInstrument`、opening migration、`BUY` / `SELL` / `DIVIDEND`、standalone reversal、replacement；
- 内部 Position list、logical transaction list、logical correction 折叠与稳定 cursor 基础；
- 后端权威计算、追加式审计、幂等、并发与确定性重放；
- Flyway、Docker Compose、CI 与 PostgreSQL Testcontainers 验证基础。

普通流水只支持 `INCOME`、`EXPENSE`、`ADJUSTMENT`；账户余额由后端事务维护。投资事实为不可变账本，不是普通 Transaction，也不执行真实交易。报价、FX 与参考估值不改变账务真值。

## 已规划但未实现

投资读取模型契约已通过 [Phase 2C-1 design](design/V3.0-Phase2C-1-Investment-Read-Model-Contract.md) 与 [ADR-015](ADR/ADR-015-investment-read-model-contract.md) 冻结，内部列表基础由 [Phase 2C-2A Review](review/V3.0-Phase2C-2A-Closing-Review.md) 验收。公开 Controller、Portfolio summary、详情、审计时间线和投资前端仍未实现，当前公开 API 与前端能力没有变化。

其他未实现能力包括 `TRANSFER` / `REFUND`、历史收益曲线、多币种账务、FIFO/lot、公司行动、银行/券商自动同步、AI Agent 与原生移动端。

## 明确排除

系统不处理真实支付、资金划转、证券下单、托管或投资建议。当前 CNY 账务不因参考 FX 数据而成为多币种账务。

## 需求治理

架构基线冻结在 [Architecture](03-Architecture/Architecture.md)，架构级变更需要 ADR。当前细则见 [业务规则](Business%20Rules.md) 与 [金融规则](Financial%20Rules.md)；历史阶段证据见 [Review](review/)。
