# ADR-014: Append-Only Investment Transaction Replacement

## Status

Accepted

## Decision

A replacement is one immutable correction command, one grouped `REVERSAL` fact, and one replacement fact of the original `BUY`, `SELL`, or `DIVIDEND` type. Original facts are never updated. There is no standalone `REPLACEMENT` transaction type, no copied external idempotency key on facts, and no 5B-2 write API in this decision.

The correction command owns the external idempotency key and request hash. It is inserted only after the two facts, account mutation, second replay, and projection update have succeeded. Its presence therefore means a complete immutable command; neither `PENDING` nor a recoverable command status exists. Deferred composite fact-to-command and command-to-fact foreign keys, plus a deferred completion trigger, allow this facts-first / command-last order while requiring one same-user pair with a single original binding.

Grouped reversals retain original monetary audit values, reversal cash delta, and reason, but their legacy final-receipt columns are all `NULL`: they do not claim a projection state that was never committed. Standalone reversals retain the V12 complete receipt semantics.

Replacement facts use `replay_anchor_transaction_id = original.id` and `replay_sequence = 1`; ordinary facts have a null anchor and sequence zero. Grouped reversals have no replay anchor and sequence zero. A replacement inherits the original type, currency, trade time, and settlement time. Correction facts cannot become a later correction's original. The command's `createdAt` is the real time of the correction; it is never backdated to the original posting time.

The canonical replay key is `(effectiveTradeTime, effectiveAnchorId, replaySequence, fact.id)`, where a replacement's effective trade time and anchor come from the original fact. The reversed original is excluded and reversals are never calculator inputs. This preserves the original logical slot even when a replacement has a larger physical ID. Ordinary facts at the same timestamp continue to use their original fact ID as the stable ordering tie-breaker.

Candidate replay and the second replay compare the ordered logical fact trace, anchor, replay sequence, type, quantity, total cost, average cost, cumulative realized PnL, each SELL's released cost and realized PnL, the replacement amounts and cash results, the final Position, and the canonical replay digest. Replay records these deterministic effective trace steps. Its SHA-256 digest serializes normalized monetary values with `toPlainString()` and excludes `createdAt`, correction group ids, and physical replacement IDs. The digest describes business replay, not storage randomness.

Existing SELL released cost, realized PnL, and receipts remain immutable posting-time audit snapshots. Corrected current truth is expressed by complete replay and the Asset projection; the correction command envelope expresses the completed replacement outcome.

## Consequences

PostgreSQL validates binding, pair completeness, type/currency/time inheritance, command receipt completeness, immutable facts, immutable commands, and the canonical replay anchor shape. Java remains responsible for business replay legality such as oversell. Candidate/second-replay comparison and the actual replacement write path are deferred to Phase 2B-5B-2.
