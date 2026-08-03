# ADR-006：多币种参考估值与汇率快照

**项目名称：** Personal Finance OS

**ADR 编号：** ADR-006

**英文标题：** Multi-Currency Reference Valuation and Exchange-Rate Snapshots

**状态：** Accepted（已接受）

**日期：** 2026-07-19

**负责人：** 项目维护者

---

## 背景

v2.0 已提供独立、可追溯的 US `STOCK` / `ETF` 最新参考行情，但明确不修改 `Asset.currentPrice`、`Asset.marketValue` 或 Dashboard（`docs/review/V2.0-Closing-Review.md:64-68`）。v2.1 需要在保持该隔离边界的前提下，为外币行情生成 CNY 参考估值。

当前金融基线为：

- 基础币种为 CNY，Account、Asset、Transaction 均只允许 CNY，Dashboard 只聚合 CNY（`docs/Financial Rules.md:195-205`）。
- 金额计算必须使用 `BigDecimal`，禁止 `float` / `double`（`docs/Financial Rules.md:35-44`）。
- 汇率必须可追溯（`docs/Financial Rules.md:207`）。
- `Architecture.md` 已冻结；架构级变化必须通过 ADR（`docs/03-Architecture/Architecture.md:15-19`）。
- Asset 查询已批量读取行情快照且 GET 不调用 Provider（`backend/src/main/java/com/financeos/module/asset/marketdata/service/MarketQuoteQueryService.java:42-58`）。

## 当前约束

- `AccountService`、`AssetService`、`TransactionService` 在 Service 层拒绝非 CNY 输入。
- `DashboardService` 使用 CNY Account、Asset、Transaction QueryService 的结果计算总资产。
- `assets.avg_cost`、`assets.current_price` 和 `assets.market_value` 是现有 CNY 人工估值链路；实际列名是 `avg_cost`，不是 `average_cost`。
- `market_quotes.currency` 是 Provider 原始报价币种；行情只写入 `market_quotes`。
- `asset_prices` 存在于 V1，但没有 Entity / Mapper，不能复用为 FX 或参考估值存储。
- v2.1 不实现完整投资交易模型、外币现金账户或 Dashboard 跨币种聚合。

## 决策驱动因素

1. 旧 CNY 数据和 API 必须零语义迁移继续工作。
2. 原始持仓、原始行情、汇率快照和派生估值必须分离。
3. 不同币种不得直接相加。
4. 汇率必须包含来源、报价时间和获取时间。
5. GET 与 Dashboard 不得隐式访问外部 Provider。
6. 外部失败不得修改 Account、Transaction 或 Asset 人工估值。
7. 设计必须可按独立阶段实施和验证。

## 备选方案

### 方案 A：完整多币种账本

开放 Account、Transaction、Asset 和用户基础币种配置，并改造余额联动与 Dashboard。该方案需要汇兑交易、历史汇率口径、跨币种转账、数据回填和大规模兼容测试，超出 v2.1 安全范围。

### 方案 B：外币资产参考估值

保持账本和人工估值为 CNY，保存原始行情与最新成功 FX 快照，请求时生成 CNY 参考估值。该方案不修改核心表语义，可复用 v2.0 刷新模式，并能独立测试。

### 方案 C：外币资产正式估值

把 Asset 成本、价格和市值改为原生币种，并让 Dashboard 使用折算值。该方案会重新解释既有字段，引入回填和双重存储一致性风险，不适合 v2.1。

## 最终决策

选择 **方案 B：外币资产参考估值**。

- v2.1 系统基础币种继续固定为 CNY，不开放用户配置。
- Account 与 Transaction 继续只允许 CNY。
- `Asset.currency` 继续表示人工估值币种，v2.1 中仍为 CNY。
- `Asset.avgCost`、`Asset.currentPrice`、`Asset.marketValue` 保持现有 CNY 语义。
- `MarketQuote.currency` 表示 Provider 原始行情币种。
- 新增公共的最新成功 `exchange_rates` 快照。
- 最终 CNY 参考估值不持久化，根据持仓、行情与 FX 快照请求时计算。
- v2.1 不修改 Dashboard；最早在后续独立 ADR 中增加并列的“市场参考总资产”。

本 ADR 已在 Phase 1 FX Foundation 的 migration、持久化边界与 PostgreSQL 测试通过后转为 `Accepted`。Phase 2 的内部 refresh workflow 已实现并通过最终验证；随后 Phase 3 Reference Valuation Backend 和 Phase 4 Assets UI 也已完成并通过验收。`Accepted` 表示架构决策生效，v2.1 的最终关闭状态另见 Closing Review。

## 基础币种

基础币种固定为 `CNY`。原因是 Account 余额、Transaction 余额联动、Asset 人工估值与 Dashboard 已形成同一 CNY 口径。用户可配置基础币种将把参考估值问题扩大为完整多币种账本问题。

## 币种语义

| 数据 | 币种语义 | 数据类别 |
|---|---|---|
| `accounts.balance` | `accounts.currency`，v2.1 仅 CNY | 原始业务数据 |
| `transactions.amount` | `transactions.currency`，必须与 Account 一致，v2.1 仅 CNY | 原始业务数据 |
| `assets.avg_cost` | `assets.currency`，v2.1 仅 CNY | 用户确认的人工成本 |
| `assets.current_price` | `assets.currency`，v2.1 仅 CNY | 用户确认的人工价格 |
| `assets.market_value` | CNY 人工估值链路的持久化派生字段 | 现有派生数据 |
| `market_quotes.price` | `market_quotes.currency` | Provider 原始参考行情 |
| `exchange_rates.rate` | 明确货币对方向 | Provider 原始 FX 快照 |
| Reference Valuation | CNY | 请求时派生结果，非账务真值 |

禁止把 USD 行情或 USD 市值写入现有 Asset 人工字段，也禁止让前端自行完成跨币种聚合。

## 汇率方向

采用直接、不可歧义的方向：

```text
base_currency = USD
quote_currency = CNY
rate = 7.25

1 USD = 7.25 CNY
```

v2.1 估值只解析 `quoteCurrency/CNY`。不存储反向倒数；`CNY/CNY` 由服务返回固定 `1`，不写入数据库。

## 数据模型

Phase 1 计划新增 `V3__exchange_rates.sql`，只创建 `exchange_rates`：

| 字段 | 类型 | 约束 |
|---|---|---|
| `id` | `BIGSERIAL` | PK |
| `base_currency` | `VARCHAR(3)` | NOT NULL，`^[A-Z]{3}$` |
| `quote_currency` | `VARCHAR(3)` | NOT NULL，`^[A-Z]{3}$`，且不同于 base |
| `rate` | `NUMERIC(24,12)` | NOT NULL，`rate > 0` |
| `rate_time` | `TIMESTAMPTZ` | NOT NULL |
| `fetched_at` | `TIMESTAMPTZ` | NOT NULL |
| `provider` | `VARCHAR(30)` | NOT NULL |
| `created_at` | `TIMESTAMPTZ` | NOT NULL，默认当前时间 |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL，默认当前时间 |

唯一键为 `(base_currency, quote_currency)`。唯一索引已覆盖 Phase 1 的货币对查询，不创建重复普通索引。表不含 `user_id`、状态或最终估值字段。

只保存最新成功快照。upsert 仅在新 `rate_time` 更晚，或 `rate_time` 相同但 `fetched_at` 更新时覆盖。失败响应、非正 rate、币种不匹配或时间缺失不得覆盖旧快照。

## 参考估值公式

```text
nativeMarketValue = quantity × quotePrice
baseCurrencyMarketValue = nativeMarketValue × fxRateToCny
```

当 `quoteCurrency = CNY` 时使用系统恒等汇率 `1`。缺少行情或所需 FX 时不生成 CNY 金额；存在过期快照时允许计算，但必须标记为 `STALE` 并返回结构化 warning。

## 精度与舍入

- quantity 沿用 `DECIMAL(18,8)`。
- quote price 沿用 `NUMERIC(20,8)`。
- FX rate 使用 `NUMERIC(24,12)`。
- Java 全程使用 `BigDecimal`；两个乘法均使用精确十进制乘法，不使用 `double`、`float` 或中间舍入。
- `nativeMarketValue` 保留精确乘积，最多 16 位小数。
- 最终 `baseCurrencyMarketValue` 使用 `setScale(2, RoundingMode.HALF_UP)`。
- 前端只格式化后端结果，不重算公式。

## API 边界

- 普通 Asset GET 只读取数据库快照并计算，不调用 Provider。
- `AssetResponse` 可新增向后兼容的 nullable `referenceValuation` 嵌套字段；list/page 必须批量读取行情和去重后的货币对。
- 新增显式 `POST /api/v1/assets/{id}/reference-valuation/refresh`，根据各自 freshness 只刷新需要的行情或 FX。
- 保留现有 `POST /api/v1/assets/{id}/quote/refresh` 语义，不静默扩展为 FX 刷新。
- 前端只能提交 Asset ID，不得提交 symbol、market、currency、rate、provider 或 API Key。

## Provider 边界

设计 `ExchangeRateProvider.fetchRate(baseCurrency, quoteCurrency)`、`ExchangeRateQuote` 与 `ExchangeRateProviderException`。Phase 2 仅实现内部 workflow；真实 Provider、价格、许可证、覆盖范围和额度仍须在 adapter 实施前独立核实，不假定 Twelve Data 适用。

Provider adapter 只负责调用、校验和标准化。API Key 仅来自环境变量或 Secret Store；功能默认关闭；启用但缺 Key 时 fail-fast。401/403、429、5xx、timeout、malformed response、不支持货币对、非正 rate、时间缺失和币种不匹配均映射为内部分类，原始响应不返回客户端。

## 安全边界

- 所有刷新和读取接口要求认证。
- Service 先验证 Asset 归属；他人 Asset 返回 404。
- FX 快照按货币对公共复用，不关联用户。
- 用户级与全局 FX 额度和行情额度分别计算。
- single-flight 等待者不重复消费额度。
- single-flight 与额度保护继续明确为单进程能力。

## 失败行为

- Provider 成功且持久化成功后，才更新最新快照。
- 刷新失败且存在旧快照：返回旧快照，标记 `STALE`，附结构化 warning。
- 刷新失败且无快照：按语义返回 404、429、502 或 503。
- 行情或 FX 仅一项存在：返回 `PARTIAL`，缺失值与最终 CNY 估值为 `null`。
- 两项均不可用：返回 `UNAVAILABLE`。
- 任何失败路径均不得修改 Asset、Account、Transaction 或 Dashboard 数据。

## Dashboard 边界

v2.1 四个阶段均不修改现有 Dashboard。参考估值只在 Assets 范围展示。后续如接入，应新增独立“市场参考总资产”或显式口径切换，不能替换现有人工 CNY 总资产。

## 影响

正面影响：旧数据和 API 语义稳定；外部输入可追溯；估值与账务真值隔离；可以复用 v2.0 的缓存与失败模式；实施可拆分。

代价与限制：最新快照不能重建历史估值；Asset 的“人工估值币种”和行情币种需在 UI 清晰区分；运行时计算增加少量批量查询与 CPU 成本；Provider 与 TTL 仍需实施前确认。

## 被拒绝的方案

- 拒绝完整多币种账本，因为会改变余额联动、交易模型与 Dashboard。
- 拒绝把 USD 行情写入 `assets.current_price` 或 `assets.market_value`。
- 拒绝持久化最终 CNY 参考估值，因为会与 quote、FX 和 quantity 形成第二套同步真值。
- 拒绝复用 `asset_prices`，因为其当前是未接入的历史价格表，语义与 FX 不同。
- 拒绝 GET 或 Dashboard 隐式联网。

## Migration 策略

Phase 1 只新增 `V3__exchange_rates.sql`；不修改 V1/V2，不修改 `accounts`、`assets`、`transactions`、`users` 或 `market_quotes`。该 additive migration 对旧 CNY 数据无回填要求。回退功能时停止读写新表；如未来必须删除表，应通过新的 forward migration 执行，而不是修改 V3。

## 测试策略

- PostgreSQL 17 Testcontainers 验证空库 V1→V2→V3 与 V2→V3 升级。
- 验证表、精度、CHECK、唯一键、时间类型和最新快照 upsert。
- Provider、配置、TTL、single-flight、额度、fallback 全部离线测试。
- 估值覆盖精度、大数、零持仓、缺失、过期、CNY 恒等转换和用户隔离。
- 保持现有 CNY、余额联动、Dashboard、行情不污染人工估值、GET 不调用 Provider 等回归测试不变。

## 范围外事项

- 外币现金账户、外币收支、跨币种转账、汇兑流水和汇兑损益。
- 历史汇率、历史估值、定时/自动/批量刷新和图表。
- BUY、SELL、DIVIDEND、手续费、税费和实现盈亏。
- Dashboard 参考总资产、用户基础币种配置、更多市场与分布式协调。

## 待确认问题

1. Phase 2 使用哪个 FX Provider，其许可证、报价方向、额度和覆盖范围是否满足需求？
2. FX 默认 TTL 是否采用 1 小时，以及最终额度默认值应如何匹配 Provider 套餐？
3. `referenceValuation` 是否在 Phase 3 即加入 Asset list/page，还是到 Phase 4 UI 一并开放？推荐 Phase 3 完成批量后端能力，Phase 4 才由 UI 消费。
4. Phase 4 原生币种市值的展示小数位是否按币种规则配置，还是统一最多 8 位？

## 审批记录

- 提出时间：2026-07-19
- 接受日期：2026-07-19
- 当前完成范围：v2.1 Phase 1 FX Foundation、Phase 2 FX Refresh Workflow、Phase 3 Reference Valuation Backend 和 Phase 4 Assets UI 均已实现并完成验证；Phase 3 包含 Reference Valuation API、只读缓存计算、显式 refresh、stale fallback 与结构化 warning，Phase 4 完成 Assets 页面只读验收。
- 最终边界：Account / Transaction 仍为 CNY-only；Asset 人工估值字段仍是账务数据；Reference Valuation 只读且不持久化；普通 GET 不调用 Provider；Provider 失败不修改账务真值；Dashboard 不接入市场参考估值；真实 FX Provider 默认关闭。
- 正式生效：是；v2.1 Market Valuation 已完成并正式关闭，关闭记录见 `docs/review/V2.1-Closing-Review.md`。
