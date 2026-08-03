# ADR-012：DIVIDEND 写路径

## 状态

Accepted（已接受），适用于 v3.0 Phase 2B-4B。

## 最终决策

- `DIVIDEND` 只记录手工录入且已实际收到的 CNY 现金股息。它不表示应计、宣告、除息日或支付日、股票股息、利息、返现、公司行动、券商订单或同步功能。
- 只有属于当前用户、处于 `TRANSACTION_DRIVEN` 模式，且至少有一条有效 `POSTED` BUY 或 OPENING_POSITION 事实的 Position 才能记录股息。DIVIDEND 不能创建 Position；LEGACY Position 以及缺少此类事实的历史都会被拒绝。
- OPEN 与 CLOSED Position 均可记录。ACTIVE 与 INACTIVE 的 Account 和 Instrument 均可使用，前提是所有权、CNY、Account / Instrument 兼容性和已锁定绑定仍然有效。
- Quantity 与 unit price 为空。Gross 必须为正；fee 与 tax 必须非负；`net = gross - fee - tax` 且可以为零。Released cost 与本次交易的 realized PnL 均为零。Dividend 永不改变累计 realized PnL、quantity、average cost、total cost 或 status。
- 命令依次锁定 Account、Instrument 和 Asset；写入一条带完整不可变回执的 POSTED / MANUAL / CNY 事实；仅通过 `AccountBalanceService` 并以 `requireActive=false` 应用 `+net`；replay 完整事实历史；且只更新投影字段。即使股息净额为零，也会推进 `lastTransactionId` 与 `projectionVersion`。
- `tradeTime` 与 `settlementTime` 是同一个服务器入账 Instant。历史插入、纠正、冲正、替换和通用 transaction endpoint 均不在范围内。
- 每个请求都要求 `Idempotency-Key`。DIVIDEND 使用规范化 UTF-8 / SHA-256 公式版本 `INVESTMENT_DIVIDEND_V1`、固定 CNY scale、LF 分隔符，以及带长度前缀的可选 reference / note 值；不改变已持久化 BUY / SELL hash 语义。
- V11 替换 V10 回执规则：BUY、SELL 与 DIVIDEND 必须具有完整回执；OPENING_POSITION 回执仍全部为空。如果存在历史 dividend 事实，V11 会在 DDL 前快速失败，而不是虚构快照。

## 影响

公开 endpoint 为 `POST /api/v1/investment/positions/{assetId}/dividends`。请求只接受 gross amount、可选 fee / tax、可选 external reference 和可选 note。金融值必须是严格的普通十进制字符串，响应小数继续使用固定 scale 字符串。任何命令失败都会同时回滚事实、余额、投影和回执。
