# 需求索引

本文件是 [SRS](SRS.md) 的索引，不是第二份可能分叉的需求规格。

| 需求领域 | 当前规范 | 支撑资料 |
| --- | --- | --- |
| 产品定位与边界 | [SRS](SRS.md) | [项目愿景](Project%20Vision.md) |
| 身份与隔离 | SRS 已实现需求 | [API](03-Architecture/API.md)、[业务规则](Business%20Rules.md) |
| 账户和普通流水 | SRS 已实现需求 | [金融规则](Financial%20Rules.md)、ADR-008 |
| 投资写路径 | SRS 已实现需求 | ADR-007 至 ADR-014、[Phase 2B Review](review/V3.0-Phase2B-Closing-Review.md) |
| 投资读取模型 | 契约、内部基础、公开读取 API 与前端工作区已实现 | [Phase 2C-1 design](design/V3.0-Phase2C-1-Investment-Read-Model-Contract.md)、[ADR-015](ADR/ADR-015-investment-read-model-contract.md)、[2C-2A Review](review/V3.0-Phase2C-2A-Closing-Review.md)、[2C-2B Review](review/V3.0-Phase2C-2B-Closing-Review.md)、[2C-2C Review](review/V3.0-Phase2C-2C-Closing-Review.md)、[2C-3 Review](review/V3.0-Phase2C-3-Closing-Review.md) |
| 数据库与 API | 当前实现基线 | [Database](03-Architecture/Database.md)、[API](03-Architecture/API.md) |
| Investment Command & Correction UI | 已实现：BUY / SELL / DIVIDEND、standalone reversal、same-type replacement 及确认、回执与恢复流程 | [Contract](design/V3.0-Investment-Command-Correction-UI-Contract.md)、[Closing Review](review/V3.0-Investment-Command-Correction-UI-Closing-Review.md) |

Phase 2B 与 Phase 2C 各子阶段均已关闭；后端提供投资写入与 Portfolio、Position、logical transaction、audit timeline API，前端 `/investments` 已提供读取工作区与 Investment Command & Correction UI。`sites-demo` 仍是静态只读虚构数据展示，不连接真实后端。
