# ADR-012: DIVIDEND write path

## Status

Accepted for v3.0 Phase 2B-4B.

## Decision

- `DIVIDEND` records only a manually entered, already received CNY cash dividend. It is not an accrual, declaration, ex-dividend/payment date, stock dividend, interest, cashback, corporate action, broker order, or synchronization feature.
- A dividend is allowed only for an owned `TRANSACTION_DRIVEN` Position with at least one effective `POSTED` BUY or OPENING_POSITION fact. It cannot create a Position; LEGACY Positions and history without such a fact are rejected.
- OPEN and CLOSED Positions are eligible. ACTIVE and INACTIVE accounts and instruments are eligible, provided ownership, CNY, account/instrument compatibility, and the locked binding remain valid.
- Quantity and unit price are null. Gross must be positive; fee and tax are non-negative; `net = gross - fee - tax` and may be zero. Released cost and this transaction's realized PnL are zero. Dividend never changes cumulative realized PnL, quantity, average cost, total cost, or status.
- The command locks Account, Instrument, then Asset; writes one POSTED/MANUAL/CNY fact with its full immutable receipt; applies `+net` exclusively through `AccountBalanceService` with `requireActive=false`; replays the full fact history; and updates only projection fields. It advances `lastTransactionId` and `projectionVersion` even for zero-net dividends.
- `tradeTime` and `settlementTime` are the same server posting Instant. Historical insertion, corrections, reversals, replacements, and a general transaction endpoint are out of scope.
- Every request requires `Idempotency-Key`. DIVIDEND uses the canonical UTF-8/SHA-256 formula version `INVESTMENT_DIVIDEND_V1`, fixed CNY scales, LF delimiters, and length-prefixed optional reference/note values. It does not alter persisted BUY/SELL hash semantics.
- V11 replaces the V10 receipt rule: BUY, SELL, and DIVIDEND require complete receipts; OPENING_POSITION receipts remain all null. V11 fails before DDL if historical dividend facts exist rather than inventing snapshots.

## Consequences

The public endpoint is `POST /api/v1/investment/positions/{assetId}/dividends`. Requests accept only gross amount, optional fee/tax, optional external reference, and optional note. Financial values are strict plain decimal strings and response decimals remain fixed-scale strings. Every command failure rolls back fact, balance, projection, and receipt together.
