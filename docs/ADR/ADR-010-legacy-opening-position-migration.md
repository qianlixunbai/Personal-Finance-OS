# ADR-010：Legacy Opening Position 迁移

## 状态

Accepted（已接受），适用于 v3.0 Phase 2B-3。

## 最终决策

每次只预览并确认一个 Legacy Asset。调用方显式选择一个现有 CNY Account，以及一个 ACTIVE 且属于该用户的 InvestmentInstrument。Migration 写入一条 POSTED `OPENING_POSITION` 事实，并通过账本 replay 引擎重建现有 Asset 投影。

`Asset.totalCost` 非空时是权威值；仅当其为空时，才从 `round(quantity * avgCost, 2, HALF_UP)` 推导。得到的 scale-8 单价必须能够通过现有 calculator 精确重现该成本。持仓为零或无效时继续保持 `LEGACY`，不能迁移。

Preview 为只读操作。Confirm 要求带签名且会过期的 preview token 以及 `X-Idempotency-Key`；它在同一事务内依次锁定 Account、Instrument 和 Asset，不修改 `Account.balance`，并在提交前执行生产一致性检查。成功迁移后，Asset 变为 `TRANSACTION_DRIVEN`，不能再通过 Legacy 写路径改回。

## 影响

V9 保证每个 Asset 只有一条已入账 opening 事实，并要求投资事实的 Account 绑定与其 Asset 一致。本阶段不新增通用投资写 API、BUY / SELL / DIVIDEND、reversal / replacement、Portfolio、批量迁移、UI 工作流或自动 Instrument / Account 选择。
