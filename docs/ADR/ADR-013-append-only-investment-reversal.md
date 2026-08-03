# ADR-013：追加式投资交易冲正

## 状态

Accepted（已接受）

## 最终决策

- Phase 2B-5A 只通过追加一条 `POSTED` `REVERSAL` 事实纠正 `BUY`、`SELL` 和 `DIVIDEND`。
- 原始事实及其状态、金融字段、request hash 和不可变回执永不更新或删除。
- 本决策取代 ADR-007 中将原始事实修改为 `REVERSED` 的后续纠正语义；ADR-007 仍作为历史记录保留，不予改写。
- 冲正通过 `original_transaction_id` 引用原始事实。Replay 引擎将该原始事实从有效业务事实集合中移除；`REVERSAL` 事实永不作为 calculator 输入。
- Replay 按 `trade_time ASC, id ASC` 顺序处理所有剩余有效业务事实。候选 replay 冲突返回 HTTP 409；第二次 replay、投影或一致性失败返回净化后的 HTTP 500，并回滚命令。
- 锁顺序为 `Original InvestmentTransaction -> Account -> Instrument -> Asset`。
- `OPENING_POSITION`、Legacy opening 纠正、部分冲正、冲正的冲正、重复冲正、纠正链和 replacement 均不在范围内。Replacement 属于 Phase 2B-5B。
- 冲正保留 `current_price` 与 `market_value`；它们不是账本投影字段。
- 包括 `cash_delta` 在内的投资金额事实沿用现有 `NUMERIC(28,2)` 精度。在更新 `NUMERIC(18,2)` 的 Account balance 投影前检查其取值范围。

## 影响

如果存在已废弃可变纠正模型的数据，V12 会快速失败。数据库强制原始事实绑定、每个原始事实最多一次冲正、冲正审计字段以及投资事实不可变性。Java 负责超卖、孤立股息等历史 replay 合法性。
