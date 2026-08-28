# Architecture 文档

本目录同时保存当前架构与冻结历史基线，二者职责不同。

| 文档 | 职责 |
| --- | --- |
| [Current Architecture](current-architecture.md) | 综合当前代码、Migration、配置与已接受 ADR，描述系统现在如何工作 |
| [Frozen v1.0 Architecture](frozen-v1.0-architecture.md) | 2026-07-06 Reviewed / Frozen 的历史架构基线，不随当前实现重写 |
| [Database](database.md) | Flyway V1–V17 与当前持久化事实 |
| [API](api.md) | 当前公开 Controller 与 HTTP 契约 |
| [Security](security.md) | 认证、用户隔离、secret、文件与 Provider 安全边界 |
| [Consistency](consistency.md) | 事务、锁、幂等、回滚、重放与投影一致性 |

长期架构决策独立保存在 [ADR](../ADR/README.md)。阶段实施前设计位于 [archive/design](../archive/design/)，历史验收证据位于 [archive/review](../archive/review/README.md)。

## 事实优先级

```text
当前代码 / Flyway Migration / 配置
        ↓
自动化测试与公开 API 实现
        ↓
ADR
        ↓
最新正式 Closing Review
        ↓
当前活文档
        ↓
历史 design / review / logs
```

如果当前实现与冻结决策冲突，应记录为 implementation inconsistency；不得通过重写冻结文档消除冲突。
