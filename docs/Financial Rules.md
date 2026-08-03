# Financial Rules

## 1. Precision and truth sources

All financial calculation uses `BigDecimal` / PostgreSQL `NUMERIC`; `float`, `double`, and `new BigDecimal(double)` are forbidden. Inputs whose scale/format is unsupported are rejected, never silently truncated. CNY cash values are scale 2; investment quantity, unit price, and average cost are scale 8; division and final monetary rounding use explicit `HALF_UP` where required.

Ordinary Transaction and InvestmentTransaction are independent ledgers. `accounts.balance` is changed only by `AccountBalanceService` in the same transaction as the applicable command. `assets` is the only current Position projection; effective investment facts are its ledger truth. Market quotes, FX, manual prices, and reference valuations are not accounting facts.

## 2. Ordinary cash ledger

For ordinary Transactions, stored INCOME/EXPENSE amounts are positive: INCOME contributes `+amount`, EXPENSE contributes `-amount`, and signed ADJUSTMENT contributes its signed amount. Updating/deleting a fact reverses its old effect then applies its new effect as one transaction. Negative CNY balances are valid.

## 3. Investment fact formulas

`OPENING_POSITION` is a zero-cash migration fact. Its opening cost is `round(quantity × unitCost, 2, HALF_UP)`; fee, tax, net, released cost, and realized PnL are zero. The scale-8 unit cost must reproduce the CNY opening cost exactly. It establishes a projection without changing Account balance.

For `BUY`:

```text
gross = round(quantity × unitPrice, 2)
acquiredCost = gross + fee + tax
cashDelta = -acquiredCost
newTotalCost = oldTotalCost + acquiredCost
newQuantity = oldQuantity + quantity
newAvgCost = newTotalCost / newQuantity (scale 8, HALF_UP)
```

For `SELL`:

```text
gross = round(quantity × unitPrice, 2)
net = gross - fee - tax
releasedCost = round(oldTotalCost × sellQuantity / oldQuantity, 2, HALF_UP)
realizedPnL = net - releasedCost
cashDelta = +net
```

For a full SELL, release the complete remaining cost and set quantity, total cost, and average cost to zero; status becomes `CLOSED`. A partial SELL must not oversell or leave a positive quantity with zero/negative total cost. A later BUY can reopen a closed Position. Cumulative realized PnL is projection state, updated by effective SELL history.

For `DIVIDEND`:

```text
net = gross - fee - tax
cashDelta = +net
```

Gross is positive; fee/tax are non-negative; net may be zero. Quantity and unit price are null. Dividend changes neither quantity, total cost, average cost, position status, nor this/cumulative realized PnL, but it still advances the Position projection version and records the cash receipt.

## 4. Replay, receipts, and corrections

Every effective fact sequence is replayed deterministically. A normal posting stores an immutable receipt containing post-command Account balance and Position quantity, average cost, total cost, cumulative realized PnL, status, and projection version. Each successful ledger-affecting command advances the Asset projection version exactly once.

A standalone reversal reverses exactly one eligible original BUY/SELL/DIVIDEND. Its cash delta is the opposite of the original's business cash effect (`BUY: +net`; `SELL`/`DIVIDEND`: `-net`), copies the original audit amounts, has zero released cost/realized PnL, excludes the original from replay, and is itself not a calculator input. The Account and Asset projection are mutated once for the completed reversal.

A replacement comprises three deltas:

```text
reversalCashDelta     = inverse of original business cash effect
replacementCashDelta  = effect of corrected BUY/SELL/DIVIDEND
commandCashDelta      = reversalCashDelta + replacementCashDelta
```

The grouped reversal and replacement fact are inserted as one immutable correction group. Account balance and Asset projection are updated only once by `commandCashDelta`; projection version advances once. The replacement takes the original logical replay slot via anchor/sequence, so later replay uses corrected truth without changing physical historical facts. The old SELL receipt and its released-cost/realized-PnL snapshot remain immutable posting evidence; replay and the final projection express the corrected truth.

## 5. Valuation boundary

Legacy Asset `currentPrice`/`marketValue`, quote refresh, FX data, and derived reference valuation are separate from ledger accounting. Reference valuation may compute `quantity × quotePrice × fxRate` with `BigDecimal`, producing final CNY scale 2 with `HALF_UP`, but it is not persisted as ledger truth. It must not create or change a Transaction/InvestmentTransaction, Account balance, transaction-driven quantity/cost/PnL/version, or Dashboard accounting totals.

Current accounting is CNY-only. Instrument quote currency and stored FX data are reference metadata/future support, not a foreign-currency cash balance or exchange gain/loss calculation. FIFO/lots, tax reporting, corporate actions, historical performance, multi-currency accounting, and broker execution are outside this financial-rule baseline.
