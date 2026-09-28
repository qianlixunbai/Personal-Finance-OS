# ADR-017：AI 只读 Finance Tool 边界

**日期：** 2026-09-28

## 状态

Accepted（已接受）

## 背景

ADR-016 建立了独立的 AI Provider 边界，但没有 Finance 数据读取能力。后续模型若需要财务事实，必须复用现有服务端查询口径，并保持用户隔离、账务真值和市场参考估值的区别。

## 决策

1. 在 `ai.tool` 内提供独立的只读 Finance Tools。首批为财务概览、月度现金流和投资组合摘要，分别只调用既有 `DashboardService`、`TransactionQueryService` 和 `InvestmentPortfolioQueryService`；不新增 Mapper、SQL、持久化模型或写路径。
2. Tool 使用 Finance 自有的类型化事实 DTO，不向未来模型暴露 Entity、Mapper 结果或 Provider 响应。金额为 `BigDecimal`，币种和口径明确；市场参考估值保留覆盖率、时效状态以及缺失值。
3. `authenticatedUserId` 只能来自受信任的服务端认证上下文，不属于模型可指定的查询参数。所有用户财务查询继续由既有 Query Service 按用户限定；共享行情和汇率仅用于已归属用户的持仓估值。
4. 财务概览沿用 Dashboard 的账户余额和 `Asset.currentPrice` 口径；Portfolio 仅包含 transaction-driven Position，其参考估值只读取缓存 Market Quote 和 FX snapshot，不代表总资产或账务净值。
5. Tool 不调用 Provider、刷新服务或任何写服务。当前仅建立内部 Java 调用边界，没有 Controller、模型 Tool Calling、Agent 循环或用户入口。接入未来入口时必须先绑定认证上下文。

## 选择理由与取舍

现有 Query Service 已提供所需确定性读取及用户限定。三个独立 Tool 的依赖清晰，暂不需要汇总 facade。投资组合摘要已包含参考估值，再新增同口径的独立 Tool 只会重复接口。当前不提供 Position 明细或费用分类等需要额外查询契约的能力。

## 风险与约束

内部方法的 `authenticatedUserId` 由调用者提供；在没有用户入口的当前阶段，无法以端到端认证测试证明未来调用者的身份来源。未来 Orchestrator 不得把模型输出映射到此参数。Dashboard 当前不记录负债，`accountingNetWorth` 与资产合计相等；参考估值缺失时必须保留 `null`，不得解释为零。

## 相关文档

- [ADR-016](ADR-016-ai-provider-foundation.md)
- [Current Architecture](../architecture/current-architecture.md)
- [Security](../architecture/security.md)
