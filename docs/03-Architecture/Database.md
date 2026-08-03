# 数据库当前基线

本文描述 `zh-cn` 当前实现的数据库事实，面向开发、排障和技术查证。架构决策原因见 [ADR](../ADR/)，阶段验收数字见 [Phase 2B Closing Review](../review/V3.0-Phase2B-Closing-Review.md)。

## 1. 数据库定位

- 数据库：PostgreSQL；Phase 2B 关闭时的验证版本为 PostgreSQL 17.10。
- 迁移工具：Flyway，当前版本链为 `V1`–`V13`。
- 唯一 schema 演进来源：`backend/src/main/resources/db/migration/`。
- 新结构必须新增前向 migration；禁止手工修改已应用 migration。
- 旧 `schema.sql` 数据库不会被自动 baseline，必须先备份并作显式迁移或重建决策。
- V5、V7、V9–V13 对无法安全推导的历史状态采用 fail-fast，不静默伪造绑定、回执或纠正事实。

## 2. Migration 概览

| 版本 | 职责 | 主要表 / 约束 | 所属阶段 |
| --- | --- | --- | --- |
| V1 | 建立用户、账户、分类、普通流水和 Legacy Asset 基线 | `users`、`accounts`、`categories`、`transactions`、`assets`、`asset_prices` | v1.0 |
| V2 | 保存共享市场参考行情 | `market_quotes`；`(market, symbol)` 唯一；价格为正 | v2.0 |
| V3 | 保存共享 FX 参考汇率 | `exchange_rates`；币对唯一、三位币种、正汇率 | v2.1 |
| V4 | 建立投资账本与 Asset 受控投影 | `investment_transactions`；Account/Asset 复合所有权；投影字段 | v3.0 Phase 1 |
| V5 | 强化投资类型金额恒等式与同域 replacement 关系 | BUY/SELL/DIVIDEND/OPENING CHECK；同用户同 Asset 复合 FK | v3.0 Phase 1 hardening |
| V6 | 对齐 Asset 投影精度 | `assets.quantity`、`avg_cost` 升为 `NUMERIC(28,8)` | Phase 2B-1 |
| V7 | 建立用户级投资标的主数据 | `investment_instruments`；`(user, market, symbol)` 唯一 | Phase 2B-2 |
| V8 | 绑定 Account / Instrument / Asset | `instrument_id`、复合 FK、transaction-driven partial unique | Phase 2B-2 |
| V9 | 强化 opening migration | Asset/Account 复合绑定；每个 Asset 至多一个有效 opening | Phase 2B-3 |
| V10 | 为 BUY/SELL 增加不可变回执 | 余额与 Position after 字段；SHA-256 hash；规范幂等键 | Phase 2B-4A |
| V11 | 将 DIVIDEND 纳入完整回执 | BUY/SELL/DIVIDEND 完整 receipt；OPENING receipt 为空 | Phase 2B-4B |
| V12 | 建立 append-only standalone reversal | `REVERSAL`、original binding、现金 delta、不可变 trigger | Phase 2B-5A |
| V13 | 建立 replacement correction envelope | correction 表、replay anchor、pair uniqueness、deferred integrity | Phase 2B-5B |

Migration 文件不复制到本文；发生差异时以仓库中的 SQL 为准。

## 3. 当前表与职责

### 3.1 用户、账户、分类与普通流水

| 表 | 关键字段 / 关系 | 当前职责 |
| --- | --- | --- |
| `users` | username/email 唯一，`password_hash`，`status` | 认证身份和用户域根 |
| `accounts` | `user_id` FK，`currency`，`balance NUMERIC(18,2)`，`status` | CNY 账户元数据与受控现金余额投影 |
| `categories` | 可空 `user_id`，`parent_id`，`is_system` | 用户分类与共享系统分类 |
| `transactions` | user/account/category FK，type、amount、currency、time | 可更新/删除的普通收支与调整事实 |

### 3.2 Asset 与参考数据

| 表 | 关键字段 / 关系 | 当前职责 |
| --- | --- | --- |
| `assets` | user、Account、Instrument；quantity、avg/total cost、realized PnL、mode/status/version | Legacy Asset 或唯一 transaction-driven Position 当前投影 |
| `asset_prices` | `(symbol, price_date)` 唯一 | V1 历史价格存储；当前无对应 Entity/Mapper |
| `market_quotes` | `(market, symbol)` 唯一；price、quote/fetched time、provider | 全局共享的行情参考快照 |
| `exchange_rates` | `(base_currency, quote_currency)` 唯一；rate 与 provider time | 全局共享的 FX 参考快照 |

### 3.3 投资事实与纠正命令

| 表 | 关键字段 / 关系 | 当前职责 |
| --- | --- | --- |
| `investment_instruments` | user-scoped；market/symbol/asset class/currency/status | 投资标的主数据 |
| `investment_transactions` | user、Asset、Account；type、金额、时间、source、request hash、receipt、replay metadata | 不可变投资事实与 posting-time receipt |
| `investment_transaction_corrections` | correction group、original/reversal/replacement ids、三段现金 delta、最终 Position receipt | 不可变 replacement command envelope |

当前一共 11 张业务/参考表。Flyway 自身另维护 `flyway_schema_history`，它不是业务表。

## 4. 核心关系

### 4.1 User ownership

- 用户拥有 `accounts`、用户分类、普通 `transactions`、`assets`、`investment_instruments`、投资事实和 correction command。
- 所有写路径从认证主体取得 `userId`，请求体不接受 user ID。
- 复合 FK 将 `user_id` 与资源 ID 一起约束，避免仅凭主键跨用户绑定。
- 系统分类、Market Quote 与 FX 是明确的共享数据例外。

### 4.2 Account / Asset

- `LEGACY` Asset 可保持 `account_id` 和 `instrument_id` 为空，沿用手工资产语义。
- `TRANSACTION_DRIVEN` Asset 必须同时绑定 Account 与 Instrument。
- `Asset` 是当前 Position 投影，不存在第二张 `investment_positions` 真值表。
- `Account.balance` 是现金余额投影；后续变更只能通过 `AccountBalanceService`。

### 4.3 Account / Instrument / Asset binding

- Instrument 身份为 `(user_id, market, symbol)`。
- transaction-driven Position 身份为 `(user_id, account_id, instrument_id)`。
- partial unique index 只对 `position_mode = 'TRANSACTION_DRIVEN'` 生效，确保同一用户、Account、Instrument 只有一个 Position。
- `investment_transactions` 不直接保存 `instrument_id`；它通过不可变的 Asset binding 关联 Instrument。

### 4.4 两类流水

- `transactions` 表达日常 `INCOME`、`EXPENSE`、`ADJUSTMENT`，当前 API 允许创建、更新、删除。
- `investment_transactions` 表达 `OPENING_POSITION`、`BUY`、`SELL`、`DIVIDEND` 和 `REVERSAL`，禁止修改或删除。
- 两类事实不会互相替代；投资现金影响只联动 Account，不写入普通流水。

### 4.5 original / reversal / replacement

- standalone reversal 通过 `original_transaction_id` 指向一个原始 BUY/SELL/DIVIDEND。
- replacement 生成一个 `correction_group_id`，包含 grouped REVERSAL 与同类型 replacement fact。
- grouped REVERSAL 不携带最终投影 receipt；完整命令结果保存在 correction envelope。
- replacement fact 的 `replay_anchor_transaction_id = original.id`、`replay_sequence = 1`。
- ordinary fact 与 grouped reversal 的 sequence 为 0；original fact 永不修改。

## 5. 数据库不变量

### 5.1 外键与复合外键

- Account、Asset、Instrument 都具有 `(user_id, id)` 唯一键，供所有权复合 FK 使用。
- 投资事实通过 `(user_id, asset_id, account_id)` 绑定到同一个 Asset/Account 组合。
- reversal original 通过 `(user_id, account_id, asset_id, original_transaction_id)` 绑定。
- correction original、reversal、replacement 和 Instrument 均要求同用户、同命令组或同资源域。

### 5.2 唯一与 partial unique

- `(user_id, idempotency_key)` 对投资事实和 correction command 分别唯一。
- 每个 Asset 至多一个有效 `OPENING_POSITION`。
- 每个 original 最多有一个 reversal，不区分 standalone 或 grouped。
- 每个 correction group 恰有一个 grouped reversal 和一个 replacement fact。
- 每个 original replay anchor 最多有一个 replacement。

### 5.3 CHECK 约束

- 投资币种当前必须为 `CNY`；request hash 必须是 64 位小写十六进制 SHA-256。
- 幂等键去除首尾空格后长度为 1–100，数据库要求值本身已经规范化。
- quantity、unit price 为正；fee/tax/released cost 等金额非负。
- BUY：`net_amount = gross_amount + fee_amount + tax_amount`。
- SELL/DIVIDEND：`net_amount = gross_amount - fee_amount - tax_amount` 且不小于零。
- OPENING 的现金、费用、税费、released cost 和 realized PnL 为零。
- OPEN/CLOSED Position receipt 的数量、成本、平均成本和状态必须自洽。
- correction command 要求 `command_cash_delta = reversal_cash_delta + replacement_cash_delta`。

### 5.4 Immutable 与 append-only trigger

- `trg_prevent_investment_transaction_mutation` 拒绝对投资事实执行 UPDATE 或 DELETE。
- reversal insert trigger 校验 original eligibility、用户/Account/Asset binding、复制的审计金额和反向现金 delta。
- replacement fact trigger 校验类型、币种、交易时间、结算时间、anchor 和纠正分组。
- `investment_transaction_corrections` 同样由 mutation trigger 保护，不允许 UPDATE/DELETE。

### 5.5 Deferred integrity

- V13 的 fact-to-command 与 command-to-fact 复合 FK 为 `DEFERRABLE INITIALLY DEFERRED`。
- deferred completion trigger 在事务结束时检查同组事实和 command 是否完整一致。
- 这允许同一事务采用 facts-first / command-last，又不会提交半个 replacement group。

### 5.6 Receipt consistency

- OPENING 的 after receipt 字段为空，因为 migration 不改变 Account 余额且结果通过 Asset 投影表达。
- BUY、SELL、DIVIDEND 与 standalone reversal 保存完整 posting-time receipt。
- grouped reversal 的 receipt 为空；correction envelope 保存 replacement 命令的最终余额和 Position。
- receipt 是历史回执，不能用于替代当前 Account 或 Asset 真值。

## 6. 真值边界

| 数据 | 含义 | 是否可作为当前真值 |
| --- | --- | --- |
| `InvestmentTransaction` | 不可变投资事实 | 是：历史事实 |
| `Asset` transaction-driven fields | 重放后当前 Position 投影 | 是：当前持仓 |
| `Account.balance` | 事务内维护的现金余额投影 | 是：当前现金余额 |
| posting-time receipt | 命令提交时的余额/持仓快照 | 否：仅历史审计 |
| replay + current projection | 应用全部有效纠正后的结果 | 是：corrected current truth |
| correction envelope | replacement 命令的不可变最终回执 | 是：该纠正命令结果 |
| Market Quote / FX | 外部参考快照 | 否：不是账务事实 |
| reference valuation | 读取时计算的参考估值 | 否：不持久化为账本真值 |

旧 SELL 的 released cost、realized PnL 和 receipt 永远保留其 posting-time 含义。发生 reversal 或 replacement 后，当前持仓、累计盈亏与成本必须从 canonical replay 和当前投影读取。

## 7. 写事务边界

普通投资命令在单个数据库事务中完成：

```text
资源校验与加锁
→ 候选历史重放
→ 写入事实
→ 一次 Account.balance 变更
→ 从数据库事实二次重放
→ 一次 Asset 投影
→ 写入 receipt / command envelope
→ 一致性检查
→ 提交
```

任一步失败都回滚事实、余额、Asset 投影、receipt 和 correction command。数据库负责形状、绑定、唯一性和不可变性；Java replay/calculator 负责 oversell、orphan dividend 等需要全历史语义的合法性。

## 8. 迁移维护规则

1. 不修改 V1–V13 历史文件。
2. 新增 migration 前先写明现有数据的升级前提与 fail-fast 条件。
3. named constraint、index 和 trigger 名称是错误分类与测试契约的一部分。
4. 结构变更至少验证空库迁移；涉及历史升级时验证对应旧版本到新版本。
5. 约束、trigger 或 deferred integrity 变更必须使用 PostgreSQL/Testcontainers，不以 H2 替代。
6. 生产或 Compose 迁移还需 runtime smoke，并核对 `flyway_schema_history` 最终版本。
