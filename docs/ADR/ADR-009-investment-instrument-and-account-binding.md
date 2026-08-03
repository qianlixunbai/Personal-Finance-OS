# ADR-009：Investment Instrument 与账户绑定

**状态：** Accepted（已接受）

**日期：** 2026-07-26

## 最终决策

- `InvestmentInstrument` 是用户范围内的主数据，具有 `user_id NOT NULL`。
- `Asset` 继续作为唯一的当前 Position 投影；不引入 `investment_positions` 表或第二份 Position 真值。
- Instrument 的唯一身份为 `UNIQUE (user_id, market, symbol)`。`market` 是规范化的市场数据命名空间，不代表特定交易所或 MIC。
- 交易驱动 Position 的身份为 `userId + accountId + instrumentId`。数据库要求两项绑定同时存在，并且每个用户、Account 与 Instrument 组合只允许一个此类 Position。
- LEGACY Asset 的两个绑定字段均可为空；系统不推断、不回填、不合并，也不转换它们。
- `InvestmentTransaction` 保留现有 `asset_id` 关系，不新增 `instrument_id`；不可变的 Asset 绑定构成间接关系。
- Phase 2B-2 不提供公开的 Instrument、Position 或 InvestmentTransaction 写 API；不执行 Opening Migration，也不修改 `Account.balance`。
- 本阶段不改变 BUY、SELL、DIVIDEND、replay、calculator、Dashboard 或 Reference Valuation 行为。

## 影响

如果 V6 数据库已包含 `TRANSACTION_DRIVEN` Asset，V7 会在任何 DDL 之前快速失败。Migration 不猜测 Instrument；V8 随后只建立追加式 schema 绑定。由于 ADR-007 将 `Asset` 定义为事实驱动投影，Phase 2B-2 提供内部绑定校验，但刻意不在缺少 InvestmentTransaction 事实时创建空的交易驱动 Position。
