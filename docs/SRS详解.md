# 需求索引

本文件是 [SRS](SRS.md) 的索引，不是第二份可能分叉的需求规格。

| 需求领域 | 当前规范 | 支撑资料 |
| --- | --- | --- |
| 产品定位与边界 | [SRS](SRS.md) | [项目愿景](Project%20Vision.md) |
| 身份与隔离 | SRS 已实现需求 | [API](03-Architecture/API.md)、[业务规则](Business%20Rules.md) |
| 账户和普通流水 | SRS 已实现需求 | [金融规则](Financial%20Rules.md)、ADR-008 |
| 投资写路径 | SRS 已实现需求 | ADR-007 至 ADR-014、[Phase 2B Review](review/V3.0-Phase2B-Closing-Review.md) |
| 投资读取模型 | 契约、内部基础与后端公开读取 API 已实现 | [Phase 2C-1 design](design/V3.0-Phase2C-1-Investment-Read-Model-Contract.md)、[ADR-015](ADR/ADR-015-investment-read-model-contract.md)、[2C-2A Review](review/V3.0-Phase2C-2A-Closing-Review.md)、[2C-2B Review](review/V3.0-Phase2C-2B-Closing-Review.md)、[2C-2C Review](review/V3.0-Phase2C-2C-Closing-Review.md) |
| 数据库与 API | 当前实现基线 | [Database](03-Architecture/Database.md)、[API](03-Architecture/API.md) |
| 未实现前端 | 投资读取前端与 correction UI 尚未实现 | [路线图](Roadmap.md) |

Phase 2B、Phase 2C-1、Phase 2C-2A、Phase 2C-2B 与 Phase 2C-2C 已关闭；后端已提供 Portfolio、Position、logical transaction 与 audit timeline 读取能力，投资前端仍未成为已实现需求。
