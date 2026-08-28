# Investment Ledger

本文是当前投资账本业务语义与计算公式的权威位置。跨领域精度规则见 [Financial Rules](financial-rules.md)，事务与锁机制见 [Consistency](../architecture/consistency.md)。

## 1. 模型与真值

- `InvestmentInstrument` 是用户级投资标的主数据，身份为 `(user, market, symbol)`；
- transaction-driven `Asset` 是唯一当前 Position 投影；
- `InvestmentTransaction` 是 append-only 投资事实；
- `InvestmentTransactionCorrection` 是 append-only replacement command envelope；
- posting-time receipt 表达命令提交时的结果，不替代当前 Position；
- corrected current truth 来自全部有效事实的 canonical replay 与当前 Asset 投影。

`InvestmentTransaction` 不保存 instrumentId；它通过不可变的 Asset binding 关联 Instrument。

## 2. Instrument 与 Position 绑定

1. Instrument 归当前用户所有，market、symbol、assetClass、quoteCurrency 必须满足规范。
2. 当前投资账务 currency 为 CNY。
3. first BUY 需要 ACTIVE Account 和 ACTIVE Instrument。
4. 已有 Position 的 SELL、DIVIDEND 和纠正可以处理 inactive Account / Instrument 上的历史事实，但绑定必须仍有效。
5. `(user, account, instrument)` 只允许一个 transaction-driven Position。
6. 全卖关闭 Position；后续 BUY 可在相同绑定上重新打开。

## 3. Position 状态

```text
quantity = 0
→ totalCost = 0
→ averageCost = 0
→ status = CLOSED

quantity > 0
→ totalCost > 0
→ averageCost > 0
→ status = OPEN
```

replay state 直接保存 quantity、totalCost 和 cumulativeRealizedProfitLoss；averageCost 从 quantity 与 totalCost 计算。

## 4. Opening Migration

Opening 只用于单个 Legacy Asset 的显式迁移：

1. 用户选择同域 CNY Account 和 ACTIVE Instrument；
2. preview 完全只读，返回来源、目标、计算、阻塞项、warning 与签名 token；
3. confirm 需要未过期、与当前 source version 一致的 token 和 `X-Idempotency-Key`；
4. 锁顺序为 Account → Instrument → Asset；
5. 成功时只追加一个 `OPENING_POSITION`，重放并切换 Asset mode；
6. Account.balance 不变；
7. Legacy `totalCost` 非空时为权威，只有 null 时才从 quantity × avgCost 推导；
8. 无法精确重现成本、零持仓或状态无效时保持 LEGACY。

公式：

```text
quantity       = legacy quantity
grossAmount    = round(quantity × unitPrice, 2, HALF_UP)
feeAmount      = 0.00
taxAmount      = 0.00
netAmount      = 0.00
cashDelta      = 0.00
releasedCost   = 0.00
realizedPnL    = 0.00
newTotalCost   = grossAmount
newAverageCost = newTotalCost ÷ quantity（scale 8, HALF_UP）
```

calculator 必须能从 opening fact 精确恢复 authoritative source cost。

## 5. BUY

输入：`quantity > 0`、`unitPrice > 0`、fee/tax 非负。

```text
grossAmount  = round(quantity × unitPrice, 2, HALF_UP)
acquiredCost = grossAmount + feeAmount + taxAmount
netAmount    = acquiredCost
cashDelta    = -acquiredCost

newQuantity    = oldQuantity + quantity
newTotalCost   = oldTotalCost + acquiredCost
newAverageCost = newTotalCost ÷ newQuantity（scale 8, HALF_UP）

releasedCost = 0.00
realizedPnL  = 0.00
```

- fee 与 tax 资本化进入成本；
- BUY 可以使 Account.balance 为负；
- first BUY 原子创建 Position、fact、余额影响和最终投影；
- subsequent BUY 增持，CLOSED Position 可被 BUY 重开。

## 6. SELL

输入：`0 < sellQuantity <= oldQuantity`、`unitPrice > 0`、fee/tax 非负。

```text
grossAmount = round(sellQuantity × unitPrice, 2, HALF_UP)
netAmount   = grossAmount - feeAmount - taxAmount
cashDelta   = +netAmount
```

要求 `feeAmount + taxAmount <= grossAmount`。

### Partial SELL

```text
releasedCost = round(
  oldTotalCost × sellQuantity ÷ oldQuantity,
  2,
  HALF_UP
)

newQuantity  = oldQuantity - sellQuantity
newTotalCost = oldTotalCost - releasedCost
realizedPnL  = netAmount - releasedCost
newCumulativeRealizedPnL = oldCumulativeRealizedPnL + realizedPnL
newAverageCost = newTotalCost ÷ newQuantity（scale 8, HALF_UP）
```

部分卖出不能留下 `newQuantity > 0` 且 `newTotalCost <= 0`。

### Full SELL

```text
releasedCost  = oldTotalCost
newQuantity   = 0.00000000
newTotalCost  = 0.00
newAverageCost = 0.00000000
status        = CLOSED
realizedPnL   = netAmount - oldTotalCost
```

全卖直接释放全部剩余成本，避免 rounding residual。

## 7. DIVIDEND

输入：`grossAmount > 0`，fee/tax 非负且总和不超过 gross。

```text
netAmount = grossAmount - feeAmount - taxAmount
cashDelta = +netAmount

newQuantity  = oldQuantity
newTotalCost = oldTotalCost
newAverageCost = oldAverageCost
realizedPnL  = 0.00
newCumulativeRealizedPnL = oldCumulativeRealizedPnL
status = oldStatus
```

- net 可以为 `0.00`；
- DIVIDEND 不进入 SELL realized PnL；
- 必须存在更早的有效 BUY 或 OPENING；
- OPEN / CLOSED Position 都可接收；
- 即使 cash delta 为零，也写 fact、receipt，并推进 version 与 lastTransactionId；
- 当前语义是已收到的手工 CNY 现金分红，不是应计、公司行动或券商同步。

## 8. 统一现金 Effect

```text
businessCashEffect(BUY)      = -netAmount
businessCashEffect(SELL)     = +netAmount
businessCashEffect(DIVIDEND) = +netAmount
businessCashEffect(OPENING)  = 0.00
```

写路径只把有符号 effect 交给 `AccountBalanceService`。更新 Account.balance 前必须保证结果可由 `NUMERIC(18,2)` 表达。

## 9. Standalone Reversal

1. eligible original 只允许原始 BUY、SELL、DIVIDEND。
2. original fact、状态、金额、request hash 与 receipt 永不修改或删除。
3. reversal 追加 POSTED / CORRECTION / REVERSAL fact，并复制必要审计值。
4. `reversalCashDelta = -originalCashEffect`。
5. replay 排除 original，REVERSAL 自身不进入 calculator。
6. 一个 original 最多被纠正一次；OPENING、reversal、replacement fact 或已纠正事实不可再次 reversal。
7. candidate replay 成功后写 reversal，一次更新 Account，再从数据库事实二次 replay 并一次更新 Asset。
8. receipt 保存纠正后的 balance、Position 与 version。

## 10. Replacement

Replacement 只允许同类型替换原始 BUY、SELL 或 DIVIDEND：

```text
grouped reversal
+ same-type replacement fact
+ correction envelope
```

现金计算：

```text
originalCashEffect   = sign(original.type) × original.netAmount
replacementCashDelta = sign(replacement.type) × replacement.netAmount
reversalCashDelta    = -originalCashEffect
commandCashDelta     = reversalCashDelta + replacementCashDelta

sign(BUY) = -1
sign(SELL) = +1
sign(DIVIDEND) = +1
```

所以：

```text
BUY → BUY:           originalNet - replacementNet
SELL → SELL:        -originalNet + replacementNet
DIVIDEND → DIVIDEND:-originalNet + replacementNet
```

执行不变量：

1. grouped reversal 与 replacement fact 先写，envelope 最后写；
2. original 不修改；
3. replacement 使用 original 的 currency、tradeTime、settlementTime 与 replay slot；
4. `replay_anchor_transaction_id = original.id`，`replay_sequence = 1`；
5. Account 只按 commandCashDelta 更新一次；
6. Asset 只投影最终 replay 一次；
7. projectionVersion 只 `+1`，lastTransactionId 指向 replacement fact；
8. 任一阶段失败，两个 facts、余额、投影和 envelope 全部回滚；
9. 一个 original 不能形成 correction chain。

## 11. Deterministic Replay

Canonical ordering key：

```text
(effectiveTradeTime, effectiveAnchorId, replaySequence, factId)
```

- ordinary fact：自身 tradeTime、自身 id、sequence=0；
- replacement fact：original tradeTime、original anchor、sequence=1；
- grouped / standalone reversal 不进入 calculator；
- 被 reversal 的 original 从有效集合排除；
- OPENING 必须是首个有效事实；
- DIVIDEND 之前必须存在有效 BUY 或 OPENING。

Replay trace 比较逻辑身份、anchor、sequence、type、数量、成本、平均成本、累计 PnL 及 SELL 的 released cost / realized PnL。SHA-256 digest 不包含 createdAt、correction group id 或 replacement 物理 id。

## 12. 幂等与并发

- opening confirm 使用 `X-Idempotency-Key`；其他投资写命令使用 `Idempotency-Key`；
- canonical request hash 固定字段顺序、scale 和 null marker；
- 同 key 同 hash 返回 immutable receipt；同 key 不同 hash 返回 409；
- unknown unique 不能被误判为成功 replay；
- unknown commit 使用同一 key 恢复；
- first BUY / BUY / SELL / DIVIDEND 锁顺序为 Account → Instrument → Asset；
- reversal / replacement 先锁 Original fact，再锁 Account → Instrument → Asset；
- `55P03` / `40P01` 映射为可重试冲突，服务端不自动重试。

完整机制见 [Consistency](../architecture/consistency.md)。

## 13. Receipt 与读取模型

| 内容 | 含义 |
| --- | --- |
| original receipt | 原命令 posting-time snapshot |
| standalone reversal receipt | 冲正提交后的 snapshot |
| grouped reversal null receipt | 中间状态未独立提交 |
| replacement fact receipt | replacement 最终 Position snapshot |
| correction envelope | replacement 完整 command receipt |
| current Asset | 当前 corrected Position truth |

Portfolio 只聚合 transaction-driven Position。Position 与 logical transaction 使用 opaque cursor。logical transaction 以 original fact ID 为 identity；correction physical facts 在 audit timeline 中表达，不作为独立业务交易。

普通 GET 不 replay、不调用 Provider、不刷新数据，也不获取写锁。reference valuation 只读取缓存 quote / FX。

## 14. 当前排除

投资账本当前不支持多币种成本、FIFO / lot、公司行动、拆股、税务申报、投资文件导入、真实券商同步或交易执行。
