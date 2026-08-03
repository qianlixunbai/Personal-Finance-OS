# 金融规则

本文给出当前后端可执行的计算口径、精度和纠正规则。变量名与持久化字段保持可对应，但不复制实现代码。

## 1. 精度与舍入

### 1.1 数值类型

- Java 金融计算使用 `BigDecimal`，数据库使用 PostgreSQL `NUMERIC`。
- 禁止使用 `double`、`float` 或 `new BigDecimal(double)` 参与金额、价格、数量、成本和收益计算。
- API 的投资金融值使用 JSON 字符串承载。DIVIDEND 与 replacement 显式要求普通十进制字符串并拒绝科学计数法；首次 BUY、后续 BUY 与 SELL 当前使用 `BigDecimal(String)` 解析，调用方仍应提交普通定点十进制字符串。

### 1.2 Scale

| 数值 | 当前 scale | 典型存储 |
| --- | --- | --- |
| CNY money | 2 | balance、gross/net、fee/tax、cost、PnL、cash delta |
| quantity | 8 | InvestmentTransaction / Asset quantity |
| unit price | 8 | InvestmentTransaction unit price |
| average cost | 8 | Asset / receipt average cost |
| FX rate | 12 | `exchange_rates.rate` |
| quote price | 8 | `market_quotes.price` |

投资 fact 的 quantity/price 精度上限为 `NUMERIC(28,8)`，money 为 `NUMERIC(28,2)`；Account.balance 为 `NUMERIC(18,2)`。DIVIDEND 与 replacement 在 Service 层显式校验 precision 和 scale；首次 BUY、后续 BUY 与 SELL 显式校验 scale，并由数据库 `NUMERIC` 约束最终 precision/range。DIVIDEND、standalone reversal 与 replacement 还会在 Service 层显式检查 Account.balance 范围；首次 BUY、后续 BUY 与 SELL 当前由数据库 `NUMERIC(18,2)` 作最终范围约束。普通流水 DTO 当前没有同级别的显式 scale 校验，调用方仍必须按 CNY scale 2 提交，最终持久化精度由 `NUMERIC(18,2)` 限定。

### 1.3 Rounding

- `money(x) = x.setScale(2, HALF_UP)`。
- `averageCost = totalCost ÷ quantity`，scale 8，`HALF_UP`。
- 投资命令输入 money 必须已满足 scale 2；quantity 和 price 必须满足 scale 8。
- quantity × price 先以原精度相乘，再在形成 CNY gross/total cost 时舍入到 2 位。
- 舍入后应为正的名义金额若变成 0.00，则拒绝该命令。

## 2. 普通流水

定义普通流水的余额影响 `balanceDelta(type, amount)`：

```text
INCOME     → +amount
EXPENSE    → -amount
ADJUSTMENT →  amount（允许正或负）
```

规则：

1. INCOME 与 EXPENSE 保存正数 `amount > 0`。
2. ADJUSTMENT 保存非零有符号金额，且必须填写原因。
3. `newBalance = oldBalance + balanceDelta`。
4. 允许 `newBalance < 0`，当前没有透支拒绝规则。
5. 更新流水时：`commandDelta = -oldEffect + newEffect`。
6. 删除流水时：`commandDelta = -oldEffect`。
7. 更新/删除和余额影响处于同一事务，任何失败整体回滚。
8. 新增或更新的目标账户必须 ACTIVE；删除旧流水可以在 inactive account 上反转历史影响。

## 3. Position 状态

Position 状态由 replay 结果确定：

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

当前 Position state 包含 quantity、totalCost、cumulativeRealizedProfitLoss。averageCost 从前两者计算，不作为 replay 的独立输入。

## 4. OPENING_POSITION

OPENING 只用于 Legacy migration：

```text
quantity       = legacy quantity
unitPrice      = 可精确重现 authoritative totalCost 的 scale-8 单价
grossAmount    = round(quantity × unitPrice, 2, HALF_UP)
feeAmount      = 0.00
taxAmount      = 0.00
netAmount      = 0.00
cashDelta      = 0.00
releasedCost   = 0.00
realizedPnL    = 0.00
newQuantity    = quantity
newTotalCost   = grossAmount
newAverageCost = newTotalCost ÷ newQuantity（scale 8, HALF_UP）
```

- 当前 Position 必须为空。
- Legacy `totalCost` 非空时是 authoritative source；仅 null 时使用 `round(quantity × avgCost, 2)`。
- migration 必须证明 calculator 重新计算后的 totalCost 与 source 一致。
- OPENING 不修改 Account.balance，receipt after 字段保持 null。

## 5. BUY

输入：`quantity > 0`、`unitPrice > 0`、`feeAmount >= 0`、`taxAmount >= 0`。

```text
grossAmount  = round(quantity × unitPrice, 2, HALF_UP)
acquiredCost = grossAmount + feeAmount + taxAmount
netAmount    = acquiredCost
cashDelta    = -acquiredCost

newQuantity  = oldQuantity + quantity
newTotalCost = oldTotalCost + acquiredCost
newAverageCost = newTotalCost ÷ newQuantity（scale 8, HALF_UP）

releasedCost = 0.00
realizedPnL  = 0.00
newCumulativeRealizedPnL = oldCumulativeRealizedPnL
```

说明：

- fee 与 tax 资本化进入持仓成本。
- `grossAmount` 经 CNY 舍入后必须大于 0.00。
- BUY 可使 Account.balance 变负。
- first BUY 从空状态创建 Position；后续 BUY 叠加成本；关闭后的 Position 可被 BUY 重开。

## 6. SELL

输入：`0 < sellQuantity <= oldQuantity`、`unitPrice > 0`、fee/tax 非负。

```text
grossAmount = round(sellQuantity × unitPrice, 2, HALF_UP)
netAmount   = grossAmount - feeAmount - taxAmount
cashDelta   = +netAmount
```

要求 `feeAmount + taxAmount <= grossAmount`，因此 `netAmount >= 0`。

### 6.1 Partial sell

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

部分卖出不得留下 `newQuantity > 0` 且 `newTotalCost <= 0` 的残余状态。

### 6.2 Full sell

```text
releasedCost = oldTotalCost
newQuantity  = 0.00000000
newTotalCost = 0.00
newAverageCost = 0.00000000
status = CLOSED
```

全卖直接释放全部剩余成本，避免按比例除法留下 rounding residual。`realizedPnL = netAmount - oldTotalCost`。

## 7. DIVIDEND

输入：`grossAmount > 0`、fee/tax 非负，且 fee + tax 不超过 gross。

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

- `netAmount` 可以为 0.00。
- DIVIDEND 不进入卖出 realized PnL，也不改变数量与成本。
- 即使 cashDelta 为零，命令仍写事实、保存 receipt，并使 projectionVersion `+1`。
- 必须有更早的有效 BUY 或 OPENING；OPEN/CLOSED Position 都可接收。

## 8. 现金 effect 统一表示

对一个原始业务事实定义：

```text
businessCashEffect(BUY)      = -netAmount
businessCashEffect(SELL)     = +netAmount
businessCashEffect(DIVIDEND) = +netAmount
businessCashEffect(OPENING)  = 0.00
```

投资写路径只把这个有符号 effect 交给 `AccountBalanceService`。所有 fact money 使用 `NUMERIC(28,2)`；更新 `NUMERIC(18,2)` 的 Account.balance 前必须检查可表示范围。

## 9. Standalone reversal

设 original 的业务现金 effect 为 `originalCashEffect`：

```text
reversalCashDelta = -originalCashEffect
commandCashDelta  = reversalCashDelta
newBalance        = oldBalance + commandCashDelta
```

审计规则：

1. original 永不修改。
2. reversal 复制 original 的 gross、fee、tax、net、currency；BUY/SELL 还复制 quantity 与 unitPrice。
3. reversal 自身的 `releasedCostAmount = 0`、`realizedProfitLoss = 0`。
4. replay 将 original 从有效业务事实集合排除，REVERSAL 不进入 calculator。
5. 候选 replay 成功后写 reversal，更新一次 Account，再从数据库事实二次 replay 并更新一次 Asset。
6. receipt 记录纠正后的 balance、quantity、avg/total cost、cumulative PnL、status 和 version。

## 10. Replacement

Replacement 的 `originalNet` 与 `replacementNet` 是各自事实的非负 `netAmount`；计算现金时必须先按事实类型转换为有符号 effect：

```text
originalNet          = original.netAmount
replacementNet       = replacement.netAmount

originalCashEffect   = sign(original.type) × originalNet
replacementCashDelta = sign(replacement.type) × replacementNet
reversalCashDelta    = -originalCashEffect
commandCashDelta     = reversalCashDelta + replacementCashDelta

sign(BUY) = -1
sign(SELL) = +1
sign(DIVIDEND) = +1
```

因此：

- BUY → BUY replacement：`commandCashDelta = originalNet - replacementNet`。
- SELL → SELL replacement：`commandCashDelta = -originalNet + replacementNet`。
- DIVIDEND → DIVIDEND replacement：`commandCashDelta = -originalNet + replacementNet`。

执行不变量：

1. original type 与 replacement type 相同。
2. grouped reversal 与 replacement fact 都先写入，correction envelope 最后写入。
3. Account 只按 `commandCashDelta` 更新一次。
4. Asset 只使用最终 replay 结果投影一次。
5. `projectionVersion` 仅 `+1`，`lastTransactionId` 指向 replacement fact。
6. 任一阶段失败，两个 fact、余额、投影和 envelope 全部回滚。

## 11. Deterministic replay

Canonical ordering key：

```text
(effectiveTradeTime, effectiveAnchorId, replaySequence, factId)
```

- ordinary fact：effective time 为自身 tradeTime，anchor 为自身 id，sequence=0。
- replacement fact：effective time 与 anchor 来自 original，sequence=1。
- grouped/standalone reversal 不进入 calculator。
- 被 reversal 的 original 从有效集合排除。
- OPENING 必须是首个有效事实；DIVIDEND 之前必须存在有效 BUY 或 OPENING。

Replay trace 比较 logical identity、anchor、sequence、type、数量、成本、平均成本、累计 PnL 以及每次 SELL 的 released cost/realized PnL。SHA-256 digest 使用规范化 `toPlainString()` 数值，不包含 createdAt、correction group id 或 replacement 物理 id。

## 12. Receipt 与 corrected truth

| 内容 | 含义 |
| --- | --- |
| original BUY/SELL/DIVIDEND receipt | 原命令 posting-time snapshot |
| standalone reversal receipt | 独立冲正提交后的 snapshot |
| grouped reversal null receipt | 不声称中间状态曾独立提交 |
| replacement fact receipt | replacement 命令最终 Position snapshot |
| correction envelope | replacement 的完整 command receipt |
| current Asset projection | 当前 corrected Position truth |

旧 SELL 的 released cost、realized PnL 与 receipt 不因后来 replacement 改写。当前 corrected truth 来自 replay、Asset projection 和对应 correction receipt。

## 13. 参考估值

对支持的 Asset：

```text
nativeMarketValue = quantity × quotePrice

若 quoteCurrency = CNY：
baseCurrencyMarketValue = round(nativeMarketValue, 2, HALF_UP)

若需要 FX：
baseCurrencyMarketValue = round(
  nativeMarketValue × fxRate,
  2,
  HALF_UP
)
```

manual price、Market Quote、FX 与 reference valuation 都不进入账务真值：

- 不创建普通或投资事实；
- 不修改 Account.balance；
- 不覆盖 InvestmentTransaction；
- 不覆盖 quantity、cost、PnL 或 investment ledger projection；
- 不作为税务、下单或投资建议依据。

当前账务仍是 CNY 单币种。FIFO/lot、税务申报、公司行动、拆股、多币种成本与真实券商执行不属于本规则基线。
