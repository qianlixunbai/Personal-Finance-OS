# ADR-013: Append-only investment reversal

## Status

Accepted

## Decision

- Phase 2B-5A corrects only `BUY`, `SELL`, and `DIVIDEND` through an appended `POSTED` `REVERSAL` fact.
- The original fact, its status, financial fields, request hash, and immutable receipt are never updated or deleted.
- This supersedes ADR-007's future correction semantics that would mutate an original fact to `REVERSED`; ADR-007 remains historical and is not rewritten.
- A reversal references its original through `original_transaction_id`. The replay engine removes that original from the effective business-fact set; the `REVERSAL` fact is never a calculator input.
- Replay processes all remaining effective business facts in `trade_time ASC, id ASC` order. Candidate replay conflicts are HTTP 409. A second replay, projection, or consistency failure is a sanitized HTTP 500 and rolls back the command.
- The lock order is `Original InvestmentTransaction -> Account -> Instrument -> Asset`.
- `OPENING_POSITION`, legacy opening correction, partial reversal, reversal of reversal, duplicate reversal, correction chains, and replacement are out of scope. Replacement belongs to Phase 2B-5B.
- Reversal preserves `current_price` and `market_value`; they are not ledger projection fields.
- Investment money facts, including `cash_delta`, use the existing `NUMERIC(28,2)` precision. Account balance range is checked before its `NUMERIC(18,2)` projection is updated.

## Consequences

V12 fails fast if data from the retired mutable correction model exists. The database enforces original binding, a single reversal per original, reversal audit fields, and investment-fact immutability. Java owns historical replay legality such as oversell and orphan dividends.
