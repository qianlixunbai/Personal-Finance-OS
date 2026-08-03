# 数据库当前基线

当前 schema 由 Flyway `V1`–`V13` 按序演进，并在 Phase 2B 关闭时以 PostgreSQL 17.10 验证。迁移会在无法安全补全历史事实、回执或绑定时 fail-fast；不会静默伪造数据。

## migration 演进

| 版本 | 当前效果 |
| --- | --- |
| V1 | `users`、`accounts`、`categories`、`transactions`、`assets`、`asset_prices` |
| V2–V3 | `market_quotes`、`exchange_rates` 参考数据 |
| V4–V6 | 投资账本、受控 Asset 投影、精度和复合所有权 |
| V7–V8 | `investment_instruments` 与 transaction-driven 绑定 |
| V9 | opening position migration 约束 |
| V10–V11 | BUY/SELL/DIVIDEND immutable receipt |
| V12 | append-only standalone reversal 与不可变触发器 |
| V13 | replacement correction envelope、replay anchor、延迟完整性 |

## 表、Entity 与权威数据

| 表 | Entity / Mapper | 职责 |
| --- | --- | --- |
| `users` | `User` / `UserMapper` | 认证身份与用户域 |
| `accounts` | `Account` / `AccountMapper` | CNY 余额投影和账户元数据；创建时初始化为零，后续余额变更仅由 `AccountBalanceService` 写入 |
| `categories` | `Category` / `CategoryMapper` | 用户分类及共享系统分类 |
| `transactions` | `Transaction` / `TransactionMapper` | 普通收支/调整事实 |
| `assets` | `Asset` / `AssetMapper` | Legacy Asset 或唯一 transaction-driven Position 投影 |
| `asset_prices` | 无当前 Entity/Mapper | 历史价格存储 |
| `market_quotes` | `MarketQuote` / `MarketQuoteMapper` | 全局参考行情 |
| `exchange_rates` | `ExchangeRate` / `ExchangeRateMapper` | 全局 FX 参考数据 |
| `investment_instruments` | `InvestmentInstrument` / `InvestmentInstrumentMapper` | 用户级 Instrument 主数据 |
| `investment_transactions` | `InvestmentTransaction` / `InvestmentTransactionMapper` | 不可变投资事实和 posting-time receipt |
| `investment_transaction_corrections` | `InvestmentTransactionCorrection` / Mapper | 不可变 replacement command envelope |

用户拥有的财务事实与投影均按用户隔离；系统分类、行情和 FX 是明确的共享数据例外。行情/FX/reference valuation 不改写 `accounts.balance`、普通流水、投资事实或账本投影。

## 关系、投影与不变量

- `LEGACY` Asset 保留手工资产语义；`TRANSACTION_DRIVEN` Asset 必须绑定 Account 和 Instrument，且通过 partial unique index 保证 `(user, account, instrument)` 仅有一个 Position。
- 投资事实通过 `(user_id, asset_id, account_id)` 等组合外键绑定所属 Asset/Account；纠正原事实同样受用户、账户和资产组合约束，避免跨用户绑定。
- `OPENING_POSITION` 是零现金的 migration 事实；`BUY`、`SELL`、`DIVIDEND`、standalone `REVERSAL` 有完整不可变回执。
- replacement 不是新的交易类型：它由 grouped reversal、同类型 replacement fact 和一条 correction envelope 构成。`reversal_cash_delta`、`replacement_cash_delta`、`command_cash_delta` 与最终回执写入 envelope。
- replay 以 effective trade time、anchor、sequence 和 fact ID 确定顺序；replacement 使用 original logical slot。原始 SELL 回执是不可变快照，当前真值来自重放后的投影。

## 约束与迁移边界

金额、类型、来源、时间、回执形状、CNY、SHA-256 request hash 和规范化 idempotency key 均有数据库约束。partial unique index 限制 opening、reversal、correction group 与 replacement anchor。不可变、reversal 和 replacement trigger 校验审计复制值、锚点、现金 delta，V13 通过 deferred trigger 在提交时验证 correction group 完整性。

投资事实禁止 UPDATE/DELETE；废弃的可变 replacement/reversal 字段必须为空。数据库负责形状和所有权，Java 负责 oversell 等重放合法性。一次写命令在同一事务中完成事实、`AccountBalanceService` 余额变更、重放、Asset 投影、回执和 envelope；任一步失败即整体回滚。旧 `schema.sql` 数据库不得被自动 baseline；V7–V13 的兼容性检查会拒绝不可安全升级的数据。
