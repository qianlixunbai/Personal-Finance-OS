# ADR-018：受控 AI Tool Calling

**日期：** 2026-09-28

## 状态

Accepted（已接受）

## 背景

ADR-016 建立了供应商无关的单轮 AI Provider，ADR-017 建立了三个内部只读 Finance Tool。模型要基于服务端财务事实回答问题，需要一个有限、可审计的调用链，并保持用户身份、金融真值和 Provider 协议边界。

## 决策

1. 保留 `AiProvider.generate` 的单轮文本契约；另设供应商无关的 `AiToolCallingProvider` 和 Finance 自有会话 DTO。OpenAI-compatible `tools`、`tool_calls`、`choices` 和 tool message 格式只存在于 Cloud Adapter。
2. `FinanceToolDispatcher` 手写三个 Tool 的 allowlist 和参数 schema：`getFinancialOverview`、`getMonthlyCashFlow`、`getInvestmentPortfolioSummary`。禁止扫描 Spring Bean、反射执行、动态注册及任何写入、刷新或任意外部访问 Tool。
3. `FinanceAiOrchestrator.ask(authenticatedUserId, question)` 仅作为内部 Java 入口。`authenticatedUserId` 由受信任调用方传入，模型参数 schema 不含用户 ID；本阶段不新增 HTTP Controller。
4. 模型参数严格解析和校验。财务概览及投资摘要只接受空对象；月度现金流只接受 `fromMonth`、`throughMonth`，格式为 `YYYY-MM`，连续跨度为 1–12 个月。未知 Tool、非法参数和缺失字段立即以稳定错误终止，不交由模型纠错。
5. 每轮可请求多个 Tool；一次请求的调用数量在执行前整体计入预算。最多 3 轮 Tool 请求、总计 5 次 Tool 调用，超限立即失败。Tool 结果仅序列化 ADR-017 的 Finance Fact DTO。
6. 最小系统指令要求金融事实来自 Tool，区分账务真值与缓存市场参考估值，明确缺失数据，并禁止声称执行转账、交易或其他写操作。Provider 和 Tool 失败均以稳定、脱敏错误向上报告。

## 选择理由与取舍

独立的 Tool Calling 契约避免把 Phase 1 的 `AiProvider` 改造成 Chat Completions 专用接口。显式 allowlist 和有限循环使模型只决定需要哪项资料；真实数据、身份与停止条件由 Java 控制。系统指令无法形式化证明模型最终每句文字均正确，因此最终回答仍需被视为 AI 生成内容，而非新的金融账务来源。

## 被拒绝的方案

- 自动扫描 Bean 或反射生成 Tool：扩大可调用面且难以审计。
- 通用 Agent Framework、递归规划、多 Agent runtime、RAG 或 Memory：超出当前只读问答目标。
- 把模型参数中的 `userId` 映射到 Finance Tool：破坏用户隔离。
- 让模型直接访问 Mapper、SQL、Entity、写服务或 Market / FX refresh：绕过确定性查询边界。

## 相关文档

- [ADR-016](ADR-016-ai-provider-foundation.md)
- [ADR-017](ADR-017-read-only-finance-tools.md)
- [Current Architecture](../architecture/current-architecture.md)
- [Security](../architecture/security.md)
