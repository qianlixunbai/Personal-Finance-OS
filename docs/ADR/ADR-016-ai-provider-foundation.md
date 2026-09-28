# ADR-016：AI Provider Foundation

**日期：** 2026-09-28

## 状态

Accepted（已接受）

## 背景

项目需要先建立可替换的外部 AI Provider 边界。当前没有 AI 模块；金融事实由既有 Java Service 与 PostgreSQL 确定性产生。Phase 1 不提供 AI Analyst 或 Agent 产品能力。

## 决策

1. 在后端模块化单体内新增独立 `com.financeos.module.ai`，由 `AiService` 依赖供应商无关的 `AiProvider`，返回 Finance AI 自有的类型化响应。
2. 首个实现仅支持一个 Cloud Provider。OpenAI-compatible Chat Completions 是该实现内部的最低公共 HTTP 协议；供应商请求和 `choices` 响应结构不得进入 `AiService`、`AiProvider` 或自有 DTO。
3. AI 默认关闭。启用时必须具备有效的 Provider、base URL、API Key 与 model；HTTP 调用设置连接和读取超时，并将失败映射为稳定、脱敏的错误。
4. Phase 1 不提供 Controller、前端界面、Finance 数据查询、数据库写入、Tool Calling、Agent、Provider Router 或 Ollama 实现。AI 不修改金融数据；账本真值继续由现有 Java Service 和 PostgreSQL 维护。

## 选择理由与取舍

复用现有 `RestClient`、配置和 Provider 模式，避免新增框架或第二套金融计算路径。自有 DTO 使未来 Provider 可替换。代价是当前仅能处理最简单的文本请求与响应；模型专属结构化输出、工具调用与多 Provider 策略均需后续独立决策。

## 被拒绝的方案

- 将 Chat Completions 的请求或 `choices` 直接作为上层契约：会把业务层绑定到首个 Cloud 协议。
- 现在引入 Spring AI、Agent Framework、Provider Router 或 Ollama Adapter：当前没有对应产品需求，增加配置与失效路径。
- 让 AI 直接查询或写入 Finance 数据：会绕过既有身份、权限、事务和金融真值边界。

## 风险与约束

外部服务可能超时、限流、返回错误或改变响应结构，因此调用必须设置超时、校验响应并返回脱敏错误。API Key 只从外部环境注入，不写入源码、数据库、前端、日志或文档。Phase 1 无用户入口，不能宣称已具备 AI 分析或 Agent 能力。

## 相关文档

- [Current Architecture](../architecture/current-architecture.md)
- [Security](../architecture/security.md)
