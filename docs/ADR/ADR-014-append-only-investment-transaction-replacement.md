# ADR-014：追加式投资交易替换

## 状态

Accepted（已接受）

## 最终决策

一次 replacement 由一条不可变纠正命令、一条分组 `REVERSAL` 事实和一条与原始 `BUY`、`SELL` 或 `DIVIDEND` 类型相同的替换事实组成。原始事实永不更新。本决策不定义独立的 `REPLACEMENT` transaction type，不在事实中复制外部幂等键，也不提供 5B-2 写 API。

纠正命令拥有外部幂等键和 request hash。只有两条事实、账户变更、第二次 replay 与投影更新全部成功后才插入该命令。因此，命令存在就表示一项完整、不可变的命令；不存在 `PENDING` 或可恢复的命令状态。延迟检查的事实到命令、命令到事实复合外键，加上延迟完成 trigger，使事实优先、命令最后的顺序成为可能，同时要求同一用户的一对事实只能绑定一个原始事实。

分组冲正保留原始金额审计值、冲正现金增量与原因，但其旧版最终回执列全部为 `NULL`：它们不声称存在一个从未提交的投影状态。独立冲正继续保留 V12 的完整回执语义。

替换事实使用 `replay_anchor_transaction_id = original.id` 与 `replay_sequence = 1`；普通事实的 anchor 为空且 sequence 为零；分组冲正没有 replay anchor，sequence 也为零。Replacement 继承原始事实的类型、币种、trade time 与 settlement time。纠正事实不能成为后续纠正的原始事实。命令的 `createdAt` 是实际纠正时间，绝不回填为原始入账时间。

规范 replay key 为 `(effectiveTradeTime, effectiveAnchorId, replaySequence, fact.id)`；replacement 的 effective trade time 与 anchor 来自原始事实。被冲正的原始事实会被排除，冲正事实永不作为 calculator 输入。即使替换事实具有更大的物理 ID，这也能保留原始逻辑位置。同一时间戳下的普通事实继续使用原始事实 ID 作为稳定的顺序决胜字段。

候选 replay 与第二次 replay 会比较有序逻辑事实轨迹、anchor、replay sequence、type、quantity、total cost、average cost、累计 realized PnL、每个 SELL 的 released cost 与 realized PnL、替换金额和现金结果、最终 Position 以及规范 replay digest。Replay 会记录这些确定性的有效轨迹步骤。其 SHA-256 digest 使用 `toPlainString()` 序列化规范化金额，并排除 `createdAt`、纠正组 ID 和替换事实的物理 ID。该 digest 描述业务 replay，而不是存储随机性。

现有 SELL 的 released cost、realized PnL 与回执继续作为不可变的入账时审计快照。纠正后的当前真值由完整 replay 与 Asset 投影表达；纠正命令封装表达已完成的替换结果。

## 影响

PostgreSQL 校验绑定、配对完整性、类型 / 币种 / 时间继承、命令回执完整性、事实不可变性、命令不可变性以及规范 replay anchor 形状。Java 继续负责超卖等业务 replay 合法性。候选 / 第二次 replay 比较以及实际 replacement 写路径推迟到 Phase 2B-5B-2。
