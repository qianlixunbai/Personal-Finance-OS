# 路线图

## 1. 项目长期方向

Personal Finance OS 以 Web 为主要使用方式，由用户自主维护账户、普通流水、资产和投资账本等财务事实。Java 后端是计算、事务和审计的唯一权威；系统负责可靠计算、审计、汇总和展示，不执行真实支付、资金划转或证券交易。

市场行情、FX 和参考估值始终是只读参考输入，不替代账务真值。投资交易采用追加式不可变事实，通过确定性重放恢复当前 `Asset` 持仓投影。

## 2. 当前稳定基线

当前稳定基线为：

```text
v3.0 Phase 2B Investment Write Foundation：CLOSED — GO
Phase 2C-1 Investment Read Model Contract：CLOSED — GO
Phase 2C-2A Internal Investment Read Foundation：CLOSED — GO
Phase 2C-2B Portfolio & Position Read API：CLOSED — GO
Phase 2C-2C Transaction & Audit Read API：CLOSED — GO
Phase 2C-3 Investment Read Frontend：NOT STARTED
```

当前已具备账户与普通流水、Dashboard、市场行情与参考估值、不可变投资账本、Legacy opening migration、`BUY` / `SELL` / `DIVIDEND`、standalone reversal、replacement，以及公开 Portfolio、Position、logical transaction 和 audit timeline 只读 API、幂等、并发与锁、PostgreSQL Testcontainers、Docker Compose 和 CI。完整功能边界见根 [README](../README.md)，当前阶段证据见 [Phase 2C-2C Closing Review](review/V3.0-Phase2C-2C-Closing-Review.md)。

## 3. 已完成阶段

| 阶段 | 状态 | 核心交付 | 证据 |
| --- | --- | --- | --- |
| v1.x Foundation / Engineering | CLOSED | 基础工程、账户、流水、测试与部署 | [v1.x Closing Review](review/) |
| v2.0 Market Data | CLOSED — GO | 市场参考行情 | [V2.0 Review](review/V2.0-Closing-Review.md) |
| v2.1 Market Valuation | CLOSED — GO | FX 与只读参考估值 | [V2.1 Review](review/V2.1-Closing-Review.md) |
| v3.0 Phase 1 | CLOSED — GO | 投资账本基础 | [Phase 1 Review](review/V3.0-Phase1-Closing-Review.md) |
| v3.0 Phase 2A | CLOSED — GO | 账户余额并发安全 | [Phase 2A Review](review/V3.0-Phase2A-Closing-Review.md) |
| v3.0 Phase 2B | CLOSED — GO | 投资写路径 | [Phase 2B Review](review/V3.0-Phase2B-Closing-Review.md) |
| v3.0 Phase 2C-1 | CLOSED — GO | 投资读取模型契约 | [Phase 2C-1 Review](review/V3.0-Phase2C-1-Closing-Review.md) |
| v3.0 Phase 2C-2A | CLOSED — GO | 内部 Position / logical transaction list 读取基础 | [Phase 2C-2A Review](review/V3.0-Phase2C-2A-Closing-Review.md) |
| v3.0 Phase 2C-2B | CLOSED — GO | Portfolio、Position 列表与详情只读 API | [Phase 2C-2B Review](review/V3.0-Phase2C-2B-Closing-Review.md) |
| v3.0 Phase 2C-2C | CLOSED — GO | Logical transaction 列表/详情与 audit timeline API | [Phase 2C-2C Review](review/V3.0-Phase2C-2C-Closing-Review.md) |

各阶段当时的范围、测试数字、风险和 GO / NO-GO 结论以对应 Closing Review 为准，不用当前结果覆盖历史证据。

## 4. Phase 2C 当前进度

`Phase 2C-1 Investment Read Model Contract` 已冻结并关闭。冻结内容包括：

- Portfolio read semantics 与 Position summary；
- `InvestmentTransaction` 查询语义；
- correction / audit timeline；
- 当前值与历史回执的边界；
- 分页、排序、用户隔离和金额格式；
- 默认逻辑事件与独立审计时间线的分层。

完整契约见 [Phase 2C-1 design](design/V3.0-Phase2C-1-Investment-Read-Model-Contract.md) 与 [ADR-015](ADR/ADR-015-investment-read-model-contract.md)。Phase 2C-2A 已在不新增 migration 或第二套真值的前提下实现内部 DTO、Service、Mapper SQL 与 cursor；Phase 2C-2B 已公开 Portfolio、Position 列表和 Position 详情；Phase 2C-2C 已公开 logical transaction 列表/详情与 audit timeline，并继续以不可变事实和当前 `Asset` 投影分别表达历史回执与请求时点真值。

Phase 2C-3 Investment Read Frontend 尚未开始；投资读取页面与 correction UI 仍未实现。

## 5. Phase 2C 后续候选

以下均为规划候选，不构成承诺。

### 近期候选

- 投资前端只读页面；
- correction 展示。

### 中期候选

- `TRANSFER` / `REFUND`；
- CSV / Excel 导入；
- 银行或券商账单导入；
- 历史持仓与收益曲线；
- 对账、去重和余额校准；
- PWA 与移动端快速录入。

### 长期候选

- 多币种账务；
- FIFO / lot；
- 公司行动；
- 可控 AI Agent；
- 原生移动端；
- 在合规且接口稳定可用前提下的外部数据集成。

## 6. 中期产品方向

中期重点是把现有可靠写路径转化为可理解、可审计的读取体验，并降低手工数据录入成本。Portfolio、投资事实查询与纠正时间线应继续由后端定义语义；CSV / Excel 和账单导入需要明确字段映射、去重、校准与错误恢复规则，不能绕过现有业务不变量。

## 7. 长期演进方向

长期可评估多币种、成本 lot、公司行动、移动端和受控 AI 协作。任何长期能力都必须先明确数据来源、审计边界、权限模型和失败恢复方式；涉及核心架构时须通过 ADR，不因路线图候选而提前引入实现。

## 8. 明确暂不实施的能力

- 真实支付与银行转账执行；
- 证券下单与自动交易；
- 投资建议；
- 微服务拆分；
- Redis / MQ；
- 无明确收益的分布式架构；
- 在无法获得稳定合规接口时强行接入银行或券商 API。

## 9. 路线图治理原则

- 已完成、尚未开始、规划候选和明确排除必须清晰区分；
- 路线图描述方向与阶段边界，不替代 SRS、ADR、业务规则或 Closing Review；
- 金融计算和业务规则以后端为准，前端不得自行重新聚合核心金融数据；
- 冻结的 v1.0 架构基线与当前 CNY 单币种边界保持不变，架构级变化必须走 ADR；
- 候选能力在范围、风险与验收标准冻结前不得表述为承诺或已实现能力。
