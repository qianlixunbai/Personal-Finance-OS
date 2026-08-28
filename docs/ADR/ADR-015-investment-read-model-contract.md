# ADR-015：投资读取模型、逻辑交易视图与审计时间线

**日期：** 2026-08-03

## 状态

Accepted（已接受）

## 背景

Phase 2B 已完成不可变投资写路径，但 Portfolio、Position、投资交易查询和审计时间线尚未实现。读取模型必须将 original、standalone reversal、grouped reversal、replacement fact 与 correction envelope 转换为用户可理解的表达，同时保持 `Asset` 为唯一当前 Position 投影，不把历史回执误作当前真值。

详细字段、示例和候选 API 见 [Phase 2C-1 Investment Read Model Contract](../archive/design/V3.0-Phase2C-1-Investment-Read-Model-Contract.md)。

## 决策

1. Portfolio 直接组合当前 `Asset` 投影、Instrument、Account、必要 correction 关系和已有缓存参考数据；不新增持久化 Portfolio / Position read-model。
2. Portfolio 只包含 `TRANSACTION_DRIVEN` Position。Legacy Asset 继续由现有 Asset API 表达，Portfolio 不代表全部资产或净资产。
3. 默认投资交易列表返回逻辑业务事件。每个 original replay slot 最多一条记录，面向用户的派生状态为 `UNCHANGED`、`REVERSED` 或 `REPLACED`。
4. 审计时间线与默认列表分层。时间线保留 original、standalone reversal、grouped reversal、replacement fact 和 correction command 的不可变关系。
5. Standalone reversal 在默认列表中表现为 original 逻辑记录被标记 `REVERSED`，不作为第二笔可计入交易；详情和审计层展示 reversal fact 与最终纠正回执。
6. Replacement 在 original 的 logical replay slot 展示 replacement 后有效值；grouped reversal 不出现在默认业务列表。Grouped reversal 的空 receipt 表示不适用，完整命令结果来自 correction envelope。
7. 当前 Position 真值来自 canonical replay 与当前受控 `Asset` 投影。Posting-time receipt 永远是入账时历史快照；correction final receipt 是纠正命令提交结果，二者均不替代请求时 current truth。
8. Canonical replay 保持 `(effectiveTradeTime, effectiveAnchorId, replaySequence, factId)` 升序。默认逻辑列表使用 `(effectiveTradeTime DESC, logicalTransactionId DESC)`；审计时间线按真实事件创建时间与固定角色顺序升序。
9. Position 与逻辑交易列表采用 opaque cursor seek pagination。Cursor 绑定完整稳定排序键和过滤版本；不使用 offset `page/size` 表达本契约列表。
10. 公开当前 Position 的 quantity、average cost、total cost、累计已实现盈亏和 status；不公开 projectionVersion、lastTransactionId、request hash、幂等键、replay digest、correction group ID 或内部 trace。
11. Portfolio 固定返回只读缓存 reference valuation 摘要对象，但 value 可以缺失；Position 返回相应精简/详情参考对象。所有参考估值显式标记 freshness、coverage、warning 和非账务属性；普通 GET 不调用 Provider，也不把 manual price 混入 cached quote + FX 的 Portfolio 合计。
12. 组合查询只保证单请求内一致读取。单语句使用语句快照；多查询组合使用只读 `REPEATABLE READ` 事务，不获取写锁或引入新写一致性机制。

## 选择理由

- 直接读取受控投影与当前事实避免第二套 Position 真值，与 ADR-007、ADR-009 一致。
- 默认逻辑事件加专门审计层，同时避免普通用户重复计算 correction facts，并保留完整审计能力。
- Cursor 对稳定复合键更适合倒序时间线，减少顶部并发插入导致的 offset 重复或漏项。
- Legacy Asset 缺少统一 Account / Instrument binding，混入 Portfolio 会把手工快照与可审计账本持仓混为一谈。

## 被拒绝的方案

### 新增持久化 read-model 表

拒绝。它复制当前 Position 真值，增加同步、migration、恢复与一致性风险；当前规模没有足够收益。

### 每次读取执行完整历史 replay

拒绝。它与已有受控 `Asset` 投影重复，列表成本高，容易产生逐行 replay 和 N+1。

### 默认返回全部物理事实

拒绝。Original、grouped reversal 与 replacement fact 会被普通用户误认为多笔独立交易，并可能造成前端重复汇总。

### 只返回逻辑事件且不提供审计层

拒绝。它会隐藏不可变事实与 correction command 的可解释关系。

### Portfolio 混合 Legacy Asset

拒绝。Legacy 手工快照与 transaction-driven Position 的绑定、排序、审计和真值语义不同。

### Offset pagination

拒绝用于本契约的 Position 和 transaction list。顶部新增记录会移动 offset 边界；opaque cursor 能基于完整稳定排序键执行 seek。

## 后续实现影响

- 计划中的读取能力为 Portfolio summary、Position list/detail、逻辑 transaction list/detail 和 audit timeline；当前 `API.md` 不变，endpoint 尚未实现。
- 后端负责 logical event 折叠、correction status、current truth 与 receipt 的分层，前端不得重算或拼接事实关系。
- 查询必须带 user ownership，跨用户与不存在资源均为安全 404。
- 列表不得逐行 replay，不得产生明显 N+1；普通 GET 不加行锁、不写状态、不调用 Provider。
- 任何改变 Portfolio 范围、第二真值、逻辑事件折叠或公开字段安全边界的后续方案都必须更新本 ADR 或新增 ADR。

## 范围外

本 ADR 不实现 Controller、Service、Mapper、SQL、DTO、OpenAPI、migration、前端或 Dashboard，也不改变现有写路径、V1–V13、冻结 Architecture、历史 ADR 或历史 Closing Review。
