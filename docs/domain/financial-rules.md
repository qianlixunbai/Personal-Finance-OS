# Financial Rules

本文是跨领域数值类型、精度、舍入、普通流水与参考估值公式的权威位置。投资账本专用公式见 [Investment Ledger](investment-ledger.md)，Import 复用普通流水公式见 [Transaction Import](transaction-import.md)。

## 1. 数值类型

- Java 金融计算使用 `BigDecimal`；
- PostgreSQL 使用 `NUMERIC`；
- 禁止使用 `double`、`float` 或 `new BigDecimal(double)` 参与金额、价格、数量、成本和 PnL 计算；
- 对外需要保持精度的投资与 Receipt 金融值使用 JSON string；
- hash / digest 输入使用规范化 `toPlainString()`，不依赖 locale 或科学计数法。

## 2. Scale 与存储

| 数值 | Scale | 典型存储 |
| --- | --- | --- |
| CNY money | 2 | Account.balance、Transaction.amount、gross/net、fee/tax、cost、PnL、cash delta |
| investment quantity | 8 | InvestmentTransaction / Asset quantity |
| investment unit price | 8 | InvestmentTransaction unit price |
| average cost | 8 | Asset / receipt average cost |
| Market Quote | 8 | `market_quotes.price` |
| FX rate | 12 | `exchange_rates.rate` |

Account.balance 和普通 Transaction amount 使用 `NUMERIC(18,2)`；投资 fact money 通常使用 `NUMERIC(28,2)`，quantity/price 使用 `NUMERIC(28,8)`。

## 3. 舍入

```text
money(x) = x.setScale(2, HALF_UP)
averageCost = totalCost ÷ quantity，scale 8，HALF_UP
```

- quantity × price 先以原精度相乘，形成 CNY gross / cost 时再舍入到 2 位；
- 投资命令 money 输入必须满足 scale 2，quantity / price 必须满足 scale 8；
- 普通流水和 Import amount 必须能无损规范化为 scale 2；
- 舍入后按业务应为正的名义金额若成为 `0.00`，命令必须拒绝；
- full SELL 直接释放全部剩余成本，不能因比例除法留下 rounding residual。

## 4. 普通 Transaction 余额影响

定义：

```text
balanceDelta(INCOME, amount)     = +amount
balanceDelta(EXPENSE, amount)    = -amount
balanceDelta(ADJUSTMENT, amount) =  amount
```

规则：

1. `INCOME`、`EXPENSE` 的 amount 必须大于零；
2. `ADJUSTMENT` 是非零有符号金额；
3. `newBalance = oldBalance + balanceDelta`；
4. 允许 `newBalance < 0`；
5. 更新使用 `commandDelta = -oldEffect + newEffect`；
6. 删除使用 `commandDelta = -oldEffect`；
7. Transaction 与余额 effect 位于同一事务。

Transaction Import 对每一规范化 row 使用相同 `balanceDelta`，再按 Account 聚合后一次应用；它不定义第二套余额公式。

## 5. 投资现金与持仓

BUY、SELL、DIVIDEND、opening、reversal、replacement、released cost、realized PnL 和 deterministic replay 的唯一完整公式位于 [Investment Ledger](investment-ledger.md)。

跨领域不变量是：投资命令只向余额服务提交一个有符号 `commandCashDelta`；Account.balance 与最终 Position 必须在同一事务收敛。

## 6. 参考估值

```text
nativeMarketValue = quantity × quotePrice
```

若 quote currency 为 CNY：

```text
baseCurrencyMarketValue = round(nativeMarketValue, 2, HALF_UP)
```

若需要 FX：

```text
baseCurrencyMarketValue = round(
  nativeMarketValue × fxRate,
  2,
  HALF_UP
)
```

manual price、Market Quote、FX 和 reference valuation：

- 不创建普通或投资事实；
- 不修改 Account.balance；
- 不覆盖 InvestmentTransaction；
- 不覆盖 quantity、cost、PnL 或投资投影；
- 不作为税务、下单或投资建议依据。

## 7. 当前边界

当前账务仍为 CNY 单币种。多币种成本、FIFO / lot、税务申报、公司行动、拆股和真实券商执行不属于本规则基线。
