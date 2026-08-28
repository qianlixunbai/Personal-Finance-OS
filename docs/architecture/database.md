# Database

本文描述 `zh-cn` 当前数据库事实。唯一 schema 演进来源是 `backend/src/main/resources/db/migration/`；发生差异时，以 Flyway SQL 为准。

## 1. 数据库定位

- PostgreSQL 17；
- Flyway 当前版本链为 `V1`–`V17`；
- 业务、参考与审计表共 15 张，另有 Flyway 自身的 `flyway_schema_history`；
- 新结构只能新增前向 Migration，不能修改已经应用的历史文件；
- 不能安全推导的历史状态使用 fail-fast，不伪造绑定、receipt 或 correction evidence。

## 2. Migration 概览

| 版本 | 主要职责 |
| --- | --- |
| V1 | 用户、账户、分类、普通流水、Legacy Asset 与历史价格基线 |
| V2 | `market_quotes` 共享行情快照 |
| V3 | `exchange_rates` 共享 FX 快照 |
| V4 | `investment_transactions` 与 Asset 投资投影字段 |
| V5 | 投资类型、金额恒等式、同域关系与约束加固 |
| V6 | Asset quantity / average cost 精度对齐 |
| V7 | 用户级 `investment_instruments` |
| V8 | Account / Instrument / Asset 绑定与 transaction-driven 唯一性 |
| V9 | Legacy opening migration 不变量 |
| V10 | BUY / SELL receipt 与幂等约束 |
| V11 | DIVIDEND receipt 加固 |
| V12 | append-only standalone reversal |
| V13 | replacement facts、correction envelope、deferred integrity |
| V14 | Transaction Import Session、Batch、Item 基础与持久幂等约束 |
| V15 | 允许清理 Session 的临时文件引用 |
| V16 | Session–Batch 绑定、Account impact、result digest 与 Import receipt 不可变 trigger |
| V17 | 前向修复历史 Import receipt digest 的 timestamp canonicalization |

V17 只在权威重建结果与历史 digest 不一致时更新 Batch，并在同一 Migration 中临时禁用和恢复对应 immutable trigger；它没有修改 V16 checksum。

## 3. 当前表

### 3.1 用户与基础财务

| 表 | 角色 |
| --- | --- |
| `users` | 用户身份、password hash 与状态 |
| `accounts` | 用户 Account 元数据与 `balance NUMERIC(18,2)` 现金投影 |
| `categories` | 用户分类和共享系统分类 |
| `transactions` | 可更新/删除的普通 `INCOME`、`EXPENSE`、`ADJUSTMENT` |

### 3.2 Asset 与参考数据

| 表 | 角色 |
| --- | --- |
| `assets` | Legacy Asset 或唯一 transaction-driven Position 当前投影 |
| `asset_prices` | V1 历史价格存储；当前没有对应 Entity / Mapper 写路径 |
| `market_quotes` | `(market, symbol)` 共享行情快照 |
| `exchange_rates` | 币对唯一的共享 FX 快照 |

### 3.3 投资事实

| 表 | 角色 |
| --- | --- |
| `investment_instruments` | 用户级 Instrument 主数据 |
| `investment_transactions` | append-only 投资事实、replay metadata 与 posting-time receipt |
| `investment_transaction_corrections` | append-only replacement command envelope |

### 3.4 Transaction Import

| 表 | 角色 |
| --- | --- |
| `transaction_import_sessions` | 用户级短生命周期 Session、revision、digest、临时引用与状态 |
| `transaction_import_batches` | 已确认 Batch、幂等/请求摘要、contract version 与 result digest |
| `transaction_import_items` | source row、canonical fingerprint、warning codes 与创建的 Transaction |
| `transaction_import_batch_account_impacts` | 每个 Batch / Account 的 row count、before、delta 与 after |

## 4. 用户域与关系

- `accounts`、用户分类、`transactions`、`assets`、`investment_instruments`、投资事实、correction command 和 Import 表均按 `user_id` 隔离；
- 关键关系使用 `(user_id, id)` 复合唯一键和复合 FK，避免只凭主键跨用户绑定；
- 系统分类、Market Quote 和 FX 是明确的共享数据例外；
- transaction-driven Asset 必须绑定同一用户的 Account 与 Instrument；
- `(user_id, account_id, instrument_id)` 对 transaction-driven Position 唯一；
- `investment_transactions` 通过不可变 Asset binding 关联 Instrument，不直接保存 `instrument_id`。

## 5. 普通流水与 Import 关系

- 手工录入与 Import Confirm 都创建 `transactions`；没有第二张“导入流水真值表”；
- V14 为 `transactions(user_id, id)` 增加复合唯一键，供 Import Item 的 owned FK 使用；
- `transaction_import_items.created_transaction_id` 在 V16 后非空，并通过 `(user_id, transaction_id)` 指向用户自己的普通流水；
- Import Batch 与 Session 使用 `(user_id, session_id)` 唯一绑定；一个 Session 只能形成一个 committed Batch；
- 每个 Batch / source row 唯一，每个 Batch / Account 只有一条 Account impact；
- Import receipt 数据由 immutable trigger 拒绝 UPDATE / DELETE。

## 6. 投资账本不变量

### 6.1 两类事实

- `transactions` 表达日常普通流水，允许正式 CRUD；
- `investment_transactions` 表达 `OPENING_POSITION`、`BUY`、`SELL`、`DIVIDEND` 和 `REVERSAL`，禁止 UPDATE / DELETE；
- 投资现金影响只联动 Account，不生成普通 `Transaction`。

### 6.2 reversal 与 replacement

- standalone reversal 通过 `original_transaction_id` 指向原始 BUY / SELL / DIVIDEND；
- replacement 由 grouped REVERSAL、同类型 replacement fact 和 correction envelope 组成；
- replacement fact 使用 original replay anchor 与 `replay_sequence = 1`；
- 每个 original 最多存在一次 standalone reversal 或 replacement group；
- V13 的双向复合 FK 与 completion trigger 为 deferred，允许 facts-first / command-last，但禁止提交半个 group。

### 6.3 receipt

- BUY、SELL、DIVIDEND 和 standalone reversal 保存完整 posting-time receipt；
- OPENING receipt after 字段为空；
- grouped reversal 不伪造中间 Position receipt；
- replacement command 最终结果保存在 correction envelope；
- receipt 是历史快照，不替代当前 Account 或 Asset。

## 7. 数值与约束

| 数值 | 典型类型 |
| --- | --- |
| Account balance | `NUMERIC(18,2)` |
| 投资 money / cash delta / PnL | `NUMERIC(28,2)` |
| quantity / unit price / average cost | `NUMERIC(28,8)` |
| Market Quote | `NUMERIC(...,8)` |
| FX rate | scale 12 |

数据库 CHECK 约束保护正负、币种、金额恒等式、receipt 自洽、规范幂等键、SHA-256 digest 和 Session / Batch 状态。需要全历史语义的 oversell、orphan dividend 与 replay 合法性由 Java calculator 负责。

## 8. Import Session 与 Receipt 不变量

- Session 状态只允许 `MAPPING_REQUIRED`、`PREVIEW_READY`、`EXPIRED`、`CANCELLED`、`CONSUMED`；
- 文件最大 5 MiB，Session 保存 SHA-256 file/mapping/options/normalized rows digest；
- Batch 状态固定为 `CONFIRMED`；`(user_id, idempotency_key)`、exact duplicate identity 与 `(user_id, session_id)` 均唯一；
- Batch count 必须为正，warning count 不能超过 total rows；
- Account impact 强制 `balance_after = balance_before + delta`；
- `result_digest` 从 Session、Batch、按 row 排序的 transaction references、按 Account 排序的 impacts 与 confirmedAt 规范化生成；
- Batch、Item、Account impact 均为不可变 receipt evidence。

## 9. 当前真值边界

| 数据 | 含义 | 当前真值角色 |
| --- | --- | --- |
| `transactions` | 普通流水事实 | 是 |
| `Account.balance` | 当前现金余额投影 | 是 |
| `InvestmentTransaction` | 投资历史事实 | 是 |
| transaction-driven `Asset` | 当前 Position 投影 | 是 |
| investment posting receipt | 命令提交时快照 | 否，不替代当前投影 |
| Import Batch / Items / Impacts | 已提交批次的权威回执证据 | 是，针对该批次结果 |
| Market Quote / FX | 外部参考快照 | 否 |
| reference valuation | 读取时参考计算 | 否 |

## 10. 写事务边界

普通流水、投资命令和 Import Confirm 的详细事务与锁顺序见 [Consistency](consistency.md)。数据库负责结构、所有权、唯一性和不可变性；Service 负责业务规则、金融计算、candidate replay、重复证据和事务编排。

## 11. Migration 维护

1. 不修改 V1–V17 历史文件；
2. 新变化使用下一个前向版本；
3. 升级前明确历史数据前提与 fail-fast 条件；
4. named constraint、index 和 trigger 名称可能属于错误分类契约；
5. 至少验证空库 V1→latest；有历史升级风险时验证对应 snapshot→latest；
6. PostgreSQL 特性必须使用 PostgreSQL / Testcontainers，不以 H2 替代；
7. 生产或 Compose smoke 还需核对 `flyway_schema_history` 最终版本。
