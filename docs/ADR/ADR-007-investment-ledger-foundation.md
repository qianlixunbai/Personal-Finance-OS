# ADR-007：投资账本基础与持仓受控投影

**项目名称：** Personal Finance OS

**ADR 编号：** ADR-007

**英文标题：** Investment Ledger Foundation and Controlled Position Projection

**状态：** Accepted（已接受）

**日期：** 2026-07-22

---

## 背景

现有 `Asset` 是用户维护的持仓快照，`Account.balance` 是现金余额快照；普通 `Transaction` 只表达日常收支。v3.0 需要为投资事实、持仓重建和后续一致性校验建立独立基础，同时不能改变现有资产、账户、普通流水、Dashboard 或 v2.1 市场参考估值边界。

## 最终决策

- `InvestmentTransaction` 是投资交易事实；`Asset` 是可由有效投资事实重建的当前持仓受控投影；不新增与 `Asset` 重复的 `investment_positions` 表。
- 市场行情、FX 与 Reference Valuation 都是外部或只读参考输入，绝不修改投资事实、成本或 `Account.balance`。
- 第一版领域类型为 `BUY`、`SELL`、`DIVIDEND`、`OPENING_POSITION`；`fee` 与 `tax` 是交易组成字段，不是独立交易类型。
- 成本口径采用加权平均成本法，用于产品内持仓成本与收益展示，不声明为跨国税务申报结论。
- 投资账务第一版保持 CNY-only，但存储 `currency` 三位代码，不在字段名中写死 CNY。
- V4 不提升既有 `assets.quantity` / `assets.avg_cost` 的精度类型，避免在 Phase 1 改变旧 Asset API 与快照行为；投资事实和投影新字段分别使用 `NUMERIC(28,8)`、`NUMERIC(28,2)`，旧数据精度提升留给受控迁移阶段。
- 金融事实不物理删除，也不允许未来通过 PUT 覆盖金额、数量或价格；后续使用冲正和 replacement。`OPENING_POSITION` 只允许受控迁移或内部流程创建。
- V4 只建立 schema、领域类型、纯计算/replay 内核、Entity/Mapper 与测试；不开放 Controller、公开 API、真实写入服务、余额联动、行锁或旧数据 Opening Position 迁移。

首次独立验收为 NO-GO，发现的 4 项 P1 已通过定向修复和独立聚焦复验全部关闭，最终结论为 GO。V4 保持不变；V5 fail-fast 拒绝旧矛盾事实，并新增分类型金额恒等式及 replacement 同用户、同 Asset 隔离约束。Opening 只能是唯一首个有效事实；正持仓必须有正成本，无法用 CNY 两位小数表达的低名义金额必须拒绝。Phase 1 已正式关闭，仍不开放 API、不联动 `Account.balance`、不迁移 Opening；Phase 2A 尚未开始实施。

## 数据完整性与后续写入边界

- `investment_transactions` 使用 `(user_id, idempotency_key)` 唯一约束，并以 `(user_id, asset_id)`、`(user_id, account_id)` 组合外键保持用户隔离。
- 后续写入阶段必须要求 `Idempotency-Key` 和 request hash，并在同一事务中协调 Account、Asset 与 InvestmentTransaction；PostgreSQL 行锁是后续阶段要求，不在本阶段实现。
- 原交易金额、数量和价格永久保留。`REVERSED` 事实由 replay 跳过；replacement 关系只记录关联，不重写原交易。

## 影响

V4 为历史 replay、冲正、持仓重建和后续并发控制提供可审计的基础，但现有 `Asset` 继续以 `LEGACY` 模式运行。不会自动生成 Opening Position，不会改变任何已有账户余额，也不会把投资事实加入普通月收入/支出统计。

## 被拒绝的方案

- 拒绝把投资交易塞入普通 `transactions`：日常收支和投资事实的字段、成本和冲正口径不同。
- 拒绝另建 `investment_positions`：会与 `Asset` 形成两个持仓真值。
- 拒绝在 Phase 1 直接切换旧 Asset 或生成 Opening Position：旧数据预检、幂等迁移和余额边界应在后续专门阶段完成。
- 拒绝在本阶段实现 FIFO、lot、复杂税务、公司行动、拆股、多币种账户或真实写入链路。
