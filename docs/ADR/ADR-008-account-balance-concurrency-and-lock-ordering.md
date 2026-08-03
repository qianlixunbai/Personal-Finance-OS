# ADR-008：账户余额并发与锁顺序

## 状态

Accepted（已接受），适用于 v3.0 Phase 2A。

## 最终决策

普通流水命令使用 PostgreSQL 悲观行锁。全局锁顺序为：现有事实行、按 `accountId` 升序排列且去重后的 `Account` 行、后续投影。所有加锁查询都包含所有权条件（`user_id` 和资源 ID），因此资源不存在与跨用户访问均返回 404。

Account 模块拥有的 `AccountBalanceService` 是 `Account.balance` 唯一的生产写入者。它提供受控的 `LockedAccounts` 句柄，并以 `Propagation.MANDATORY` 执行。创建 Transaction 时锁定对应账户；更新和删除时先锁定 Transaction，再锁定相关账户。账户元数据与状态变更获取同一账户锁，并使用字段级 SQL，避免覆盖并发余额更新。

每个事务都设置 PostgreSQL 事务本地 `lock_timeout`（默认 `4s`）。锁等待超时和死锁牺牲事务的数据库错误均返回 HTTP 409，并使用净化后的消息 `并发操作冲突，请重试`；不进行自动重试。

## 影响

Transaction 事实与余额增量共享同一个外层事务。CNY 余额允许为负，现有 INCOME、EXPENSE 和带符号 ADJUSTMENT 语义保持不变。本决策不引入乐观版本、Redis、MQ 或重试。后续 InvestmentTransaction、Transfer 与 Asset 写入者必须复用此顺序，不得新增从 Account 到事实的反向加锁路径。
