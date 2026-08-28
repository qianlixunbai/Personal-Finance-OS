# 项目状态

> 本文件是 Personal Finance OS 当前状态的唯一事实来源。

**核对基线：** `zh-cn` @ `0fef63c`

**核对日期：** 2026-08-29

## 当前产品阶段

当前处于 **v3.0 Transaction Import Frontend 最终验收准备阶段**。

Transaction Import Frontend A–D 已实现，当前代码覆盖 CSV / XLSX 上传、同一 Session 字段映射、服务端分页 Preview、warning acknowledgement、Confirm、未知结果恢复、权威 Receipt 路由、reload、多标签页保护和真实浏览器 E2E。

最新 Frontend Closing Review 的结论仍是：

```text
Implementation remediation complete — Ready for Final Independent Closing Re-Review
```

因此当前可以陈述“功能已实现并完成 remediation”，但不能陈述为“最终独立验收已 CLOSED — GO”。

## 当前能力概览

| 领域 | 当前能力 |
| --- | --- |
| 身份与安全 | 注册、登录、JWT、BCrypt、用户数据隔离、安全 `404` |
| 基础财务 | Account、Category、普通 `INCOME` / `EXPENSE` / `ADJUSTMENT`、后端余额联动 |
| Dashboard | 净资产、月度收支、趋势、资产分布、最近流水的后端聚合 |
| 市场参考数据 | Market Quote、FX snapshot、显式刷新、只读 CNY reference valuation |
| 投资账本 | Opening、BUY、SELL、DIVIDEND、reversal、replacement、确定性 replay |
| 投资读取与 UI | Portfolio、Position、logical transaction、audit timeline、投资命令与纠正 UI |
| Transaction Import Backend | CSV / XLSX、mapping、Preview、validation、Confirm、权威 Receipt |
| Transaction Import Frontend | 上传到回执的完整 UI、恢复、lossless amount transport 与 E2E；最终独立 Closing Re-Review 待完成 |
| 工程基础 | Flyway V1–V17、PostgreSQL Testcontainers、Docker、GitHub Actions |

## 已关闭的重要阶段

| 范围 | 正式状态 | 验收入口 |
| --- | --- | --- |
| v1.x Foundation / Engineering | CLOSED | [v1.x Reviews](archive/review/README.md) |
| v2.0 Market Data | CLOSED — GO | [V2.0 Closing Review](archive/review/V2.0-Closing-Review.md) |
| v2.1 Market Valuation | CLOSED — GO | [V2.1 Closing Review](archive/review/V2.1-Closing-Review.md) |
| v3.0 Investment Ledger / Write / Read | CLOSED — GO | [Investment Reviews](archive/review/README.md) |
| Investment Command & Correction UI | CLOSED — GO | [Closing Review](archive/review/V3.0-Investment-Command-Correction-UI-Closing-Review.md) |
| Transaction Import Contract / Backend Foundation / Preview / Confirm | CLOSED — GO | [Phase 3D Closing Review](archive/review/V3.0-Phase3D-Transaction-Import-Confirm-Closing-Review.md) |

各阶段的 HEAD、测试数字、P0/P1/P2/P3 和 GO/NO-GO 只以对应 Closing Review 的历史快照为准。

## 当前正在推进的工作

1. 对 Transaction Import Frontend A–D 执行最终独立、只读 Closing Re-Review。
2. 在最终关闭前复核最近两次 auth/reload recovery remediation 是否继续满足 frozen contract。
3. 保持当前功能边界，不自动启动下一项产品能力。

## 下一核心能力

下一项确定工作是完成 **Transaction Import Frontend 最终独立 Closing Re-Review**。其后尚未冻结新的产品实施阶段；[Roadmap](product/roadmap.md) 中的能力均须经过范围与验收标准确认后才能进入实施。

## 主要未完成范围

- `TRANSFER` / `REFUND` 普通流水语义；
- 对账、去重治理和余额校准；
- 历史持仓与收益曲线；
- 多币种账务、FIFO / lot、公司行动；
- 银行或券商自动同步；
- PWA、原生移动端和受控 AI Agent；
- 真实支付、资金划转、证券交易执行和投资建议明确不属于产品目标。

## 当前稳定技术边界

- 前后端分离的 Spring Boot 模块化单体；
- PostgreSQL 是持久化与事务一致性基础，schema 由 Flyway V1–V17 管理；
- 当前账务为 CNY 单币种，金额使用 `BigDecimal` / `NUMERIC`；
- 金融计算、余额、核心聚合和 Import Preview/Receipt 以后端为权威；
- 投资事实 append-only，当前 Position 由 canonical replay 与受控 `Asset` 投影表达；
- Market Quote、FX 与 reference valuation 是参考数据，不是账务真值；
- 架构级变化必须新增 ADR，不直接改写冻结的 v1.0 Architecture。

## 最近正式验收依据

- 后端 Import Confirm：[Phase 3D Closing Review](archive/review/V3.0-Phase3D-Transaction-Import-Confirm-Closing-Review.md)，结论为 `CLOSED — GO`。
- Import Frontend：[Frontend Closing Review](archive/review/V3.0-Transaction-Import-Frontend-Closing-Review.md)，记录 A–D 实现、两次最新 remediation 与验证证据，但明确保留最终独立 Closing Re-Review。

## 已知实现不一致

当前 Compose 与示例环境文件没有把 `FINANCE_IMPORT_CONFIRM_TOKEN_SECRET` 传入后端，而 `TransactionImportPreviewTokenService` 在 production 缺少该 secret 时会安全 fail-fast。该问题不改变 Import 金融语义，但意味着现有标准 Compose 启动路径不能被文档宣称为已完整可运行。详见 [Deployment](engineering/deployment.md)。

## 状态维护规则

- 只有本文件维护当前阶段、当前工作、下一核心能力和未完成范围。
- README、Requirements、Roadmap 与 Architecture 只链接本文件，不复制完整阶段清单。
- 历史 design、review 和 logs 中的状态保持原样，不随当前进展改写。
