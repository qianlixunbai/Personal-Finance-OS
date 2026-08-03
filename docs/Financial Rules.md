# 金融规则

## 精度与真值

全部金融计算使用 `BigDecimal` 与 PostgreSQL `NUMERIC`；禁止 `double`、`float` 和 `new BigDecimal(double)`。CNY 金额为 scale 2；quantity、unitPrice、averageCost 为 scale 8；输入范围/scale 不支持即拒绝，除法和最终金额按明确 `HALF_UP` 规则处理。普通流水与投资账本独立，`AccountBalanceService` 是后续余额变更唯一入口。

## 普通流水

`INCOME` 保存正数并增加余额；`EXPENSE` 保存正数并减少余额；`ADJUSTMENT` 使用非零有符号金额。更新/删除在一个事务内反转旧 effect 后应用新 effect；允许负余额。

## 投资事实

`OPENING_POSITION` 是零现金 migration fact，开仓成本为 `round(quantity × unitCost, 2, HALF_UP)`，fee/tax/net/released cost/realized PnL 均为零。

BUY：`gross = round(quantity × unitPrice, 2)`，`acquiredCost = gross + fee + tax`，现金 delta 为负；新总成本与数量累加，平均成本按 scale 8、`HALF_UP` 计算。

SELL：`net = gross - fee - tax`，`releasedCost = round(oldTotalCost × sellQuantity / oldQuantity, 2, HALF_UP)`，`realizedPnL = net - releasedCost`，现金 delta 为正。全卖归零并关闭 Position；部分卖不得超卖或留下正数量零/负成本；后续 BUY 可重开。

DIVIDEND：`net = gross - fee - tax`，现金 delta 为正；不改变数量、成本、状态或累计 realized PnL，但会更新投影版本与现金回执。

## reversal、replacement 与重放

有效事实按确定性顺序全历史重放。普通命令的 immutable receipt 记录命令后余额、数量、平均成本、总成本、累计 realized PnL、状态和 projection version。

standalone reversal 的现金 delta 与原业务 effect 相反，复制原审计金额、released cost/realized PnL 为零，并从重放中排除原事实；账户和 Asset 各更新一次。

replacement 包含：

```text
reversalCashDelta    = 原事实业务现金 effect 的相反数
replacementCashDelta = 修正后 BUY/SELL/DIVIDEND 的 effect
commandCashDelta     = reversalCashDelta + replacementCashDelta
```

grouped reversal 与 replacement fact 原子写入，Account 仅按 `commandCashDelta` 更新一次，Asset 仅投影一次，version 仅 `+1`。旧 SELL 的 released cost、realized PnL 与 receipt 始终是 posting-time snapshot；corrected truth 来自 replay、当前 projection 和 command receipt。

## 参考估值边界

manual valuation、Market Quote、FX 与 reference valuation 不进入账务真值，也不覆盖 investment ledger projection。reference valuation 可按 `quantity × quotePrice × fxRate` 以 `BigDecimal` 计算最终 CNY scale 2 值，但不持久化为账本事实。当前账务仍为 CNY 单币种；FIFO/lot、税务、公司行动、历史表现、多币种账务和券商执行均不在本基线内。
