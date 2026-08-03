# ADR-011：投资 BUY / SELL 写路径

## 状态

Accepted（已接受），适用于 v3.0 Phase 2B-4A。

## 最终决策

- Phase 2B-4 拆分为 2B-4A BUY / SELL 与 2B-4B DIVIDEND。本阶段只实现对已执行 BUY 和 SELL 事实的手工记录，不涉及下单、撮合、券商集成或支付执行。
- Reversal 与 replacement 保留给后续专门的纠正阶段。2B-4A API 永不更新或物理删除投资事实。
- `Account.balance` 仍允许为负。BUY 应用负现金增量且不引入余额不足规则；SELL 应用正现金增量。
- 现有 `InvestmentLedgerCalculator` 是金融公式的唯一来源，现有 replay 引擎是 Position 重建的唯一来源。BUY 将 fee 与 tax 资本化；SELL 从 proceeds 中扣除 fee 与 tax、释放加权平均成本，并在全部卖出时将 quantity、total cost 与 average cost 清零。
- 首次 BUY 在一个事务内创建交易驱动 Asset Position、BUY 事实和最终投影。临时空 Position 永不提交。
- 全局写锁顺序为 Account、Instrument、Asset / Position。`AccountBalanceService` 继续作为 `Account.balance` 唯一的生产写入者。
- 所有公开写请求都要求 `Idempotency-Key`。Request hash 是规范化 UTF-8 SHA-256 值，包含固定字段顺序、固定小数 scale、null 标记、公式版本和 `SERVER_POST_TIME_V1` 策略，不包含服务器生成的 Instant。
- V10 为 BUY 与 SELL 事实新增不可变的命令结果回执。回执用于幂等响应恢复和一致性检查；它们不替代当前 Account 或 Position 真值，也不会被后续命令更新。
- `tradeTime` 与 `settlementTime` 是命令入账时由服务器生成的同一个 Instant。不支持历史插入。
- 公开 API 按类型区分，不接受 transaction type、OPENING_POSITION、计算金额、投影字段、纠正字段、币种、用户 ID 或客户端时间戳。
- 事实插入、余额变更、replay、投影写入或一致性检查中的任何失败都会回滚整个命令。

## 影响

API 提供首次 BUY 的 Position 创建 endpoint，以及以 Asset 为范围的后续 BUY 与 SELL endpoint。所有金融小数输入和输出均使用固定 scale 字符串。本阶段关闭前，PostgreSQL 17 集成测试必须覆盖回执、幂等、回滚以及锁和并发行为。
