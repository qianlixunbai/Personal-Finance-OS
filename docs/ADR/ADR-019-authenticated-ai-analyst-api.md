# ADR-019：认证用户单轮 AI Analyst HTTP API

**日期：** 2026-09-28

## 状态

Accepted（已接受）

## 背景

ADR-016 至 ADR-018 已建立 Cloud AI Provider、三个只读 Finance Tool 和有限的内部 Tool Calling。要允许用户调用 `FinanceAiOrchestrator.ask`，HTTP 边界必须固定身份来源、请求与响应契约、错误和付费调用保护。

## 决策

1. 新增 `POST /api/v1/ai/ask`，仅供 JWT 认证用户使用。每次请求是无状态、单轮问答；请求只含 `question`，响应 `ApiResponse.data` 只含 `answer`。
2. Controller 从 Spring Security `Authentication` 的 `Long` principal 取得 user ID。请求体、查询参数、模型和 Tool 参数均不能声明或覆盖该身份。
3. Controller 只做 HTTP 绑定、校验、身份提取、用户级限流和调用 `FinanceAiOrchestrator.ask`；不直接查询 Mapper、拼 Provider 请求或执行 Tool loop。
4. `question` 拒绝空白和超过 3000 字符的内容。AI 请求使用局部严格 JSON 字段契约，不改变其他 API 的 Jackson 行为。
5. 每个用户默认每分钟最多 8 次请求，阈值通过 `FINANCE_AI_USER_REQUEST_LIMIT_PER_MINUTE` 配置。单实例内存限流在 Provider/Tool 调用前执行；超限返回 429。
6. Provider 和编排错误按固定、脱敏的 HTTP 响应映射。默认 `FINANCE_AI_ENABLED=false` 时返回明确的服务不可用错误。不得记录完整问题、Tool 结果、财务金额、Provider 响应或凭据。
7. 可调用能力仍限于 ADR-018 的三个手写只读 Tool，不提供写入或 Market/FX 隐式刷新。AI 自然语言回答是对 Tool Fact 的解释，不写回金融真值表，也不成为新的金融事实来源。
8. 本阶段不提供聊天历史、会话持久化、Memory、RAG、SSE、WebSocket 或流式输出。

## 取舍

内存限流不跨多个后端实例共享计数，后续多实例部署需要单独设计统一保护。单轮同步请求也不提供流式体验；先把认证、隔离、错误和成本边界固定。

## 相关文档

- [ADR-018](ADR-018-controlled-ai-tool-calling.md)
- [API](../architecture/api.md)
- [Security](../architecture/security.md)
