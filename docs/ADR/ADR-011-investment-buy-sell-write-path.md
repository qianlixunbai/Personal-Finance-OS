# ADR-011: Investment BUY / SELL write path

## Status

Accepted for v3.0 Phase 2B-4A.

## Decision

- Phase 2B-4 is split into 2B-4A BUY / SELL and 2B-4B DIVIDEND. This phase implements only manual recording of already-executed BUY and SELL facts; it is not order placement, matching, brokerage integration, or payment execution.
- Reversal and replacement remain a dedicated future correction phase. Investment facts are never updated or physically deleted by the 2B-4A API.
- `Account.balance` remains allowed to become negative. BUY applies a negative cash delta and does not introduce an insufficient-balance rule; SELL applies a positive cash delta.
- The existing `InvestmentLedgerCalculator` is the only financial formula source and the existing replay engine is the only Position reconstruction source. BUY capitalizes fee and tax. SELL deducts fee and tax from proceeds, releases weighted-average cost, and clears quantity, total cost, and average cost on a full sell.
- A first BUY creates its transaction-driven Asset Position, BUY fact, and final projection in one transaction. The temporary empty Position is never committed.
- The global write lock order is Account, Instrument, then Asset/Position. `AccountBalanceService` remains the only production writer of `Account.balance`.
- All public write requests require `Idempotency-Key`. Request hashes are canonical UTF-8 SHA-256 values with fixed field order, fixed decimal scales, a null marker, a formula version, and the `SERVER_POST_TIME_V1` policy. They do not include the generated server Instant.
- V10 adds immutable command-result receipts to BUY and SELL facts. Receipts are used for idempotent response recovery and consistency checks; they never replace current Account or Position truth and are never updated by later commands.
- `tradeTime` and `settlementTime` are one server-generated Instant at command posting time. Historical insertion is not supported.
- The public API is type-specific. It does not accept a transaction type, OPENING_POSITION, computed amounts, projection fields, correction fields, currency, user ID, or client timestamps.
- Any failure across fact insertion, balance mutation, replay, projection write, or consistency checking rolls back the whole command.

## Consequences

The API exposes a first-BUY Position creation endpoint plus asset-scoped subsequent BUY and SELL endpoints. All financial decimal inputs and outputs use fixed-scale strings. PostgreSQL 17 integration tests cover receipts, idempotency, rollback, and lock/concurrency behavior before this phase can close.
