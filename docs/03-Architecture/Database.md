# Database Baseline

## Scope and migration baseline

The current schema is the ordered Flyway baseline `V1` through `V13`, verified in the closed Phase 2B work against PostgreSQL **17.10**. Migrations are cumulative and must run in order; a later migration may deliberately fail before DDL when existing rows cannot be safely represented under the newer invariant. It never silently invents receipts, bindings, opening facts, or correction history.

| Migration | Current effect |
| --- | --- |
| V1 | Core users, accounts, categories, ordinary transactions, assets, asset prices |
| V2–V3 | Reference market quotes and exchange rates |
| V4–V6 | Investment fact ledger, controlled Asset projection fields, composite ownership bindings, projection precision |
| V7–V8 | User-scoped investment instruments and transaction-driven Account/Instrument binding |
| V9 | Explicit opening-position migration safeguards |
| V10–V11 | Immutable command-result receipts for BUY, SELL, and DIVIDEND |
| V12 | Append-only standalone reversal, immutable investment facts |
| V13 | Replacement correction envelope, canonical replay anchor, deferred group integrity |

## Tables, entities, and authority

| Table | Entity / Mapper | Authority and responsibility |
| --- | --- | --- |
| `users` | `User` / `UserMapper` | Authentication identity and user scope. |
| `accounts` | `Account` / `AccountMapper` | CNY cash-balance projection and account metadata. Account creation initializes `balance` to zero; `AccountBalanceService` is the only production writer for post-creation balance mutations. |
| `categories` | `Category` / `CategoryMapper` | User or system income/expense categories and optional hierarchy. |
| `transactions` | `Transaction` / `TransactionMapper` | Ordinary income/expense/adjustment facts; not investment facts. |
| `assets` | `Asset` / `AssetMapper` | Legacy manual holding, or the single transaction-driven current Position projection. There is no `investment_positions` table. |
| `asset_prices` | no current application Entity/Mapper | Historical symbol/date price store retained from the core schema. |
| `market_quotes` | `MarketQuote` / `MarketQuoteMapper` | Latest external quote by market and symbol; reference data only. |
| `exchange_rates` | `ExchangeRate` / `ExchangeRateMapper` | Latest external FX pair; reference data only. |
| `investment_instruments` | `InvestmentInstrument` / `InvestmentInstrumentMapper` | User-scoped instrument master data. |
| `investment_transactions` | `InvestmentTransaction` / `InvestmentTransactionMapper` | Immutable investment facts and their posting-time receipts. |
| `investment_transaction_corrections` | `InvestmentTransactionCorrection` / `InvestmentTransactionCorrectionMapper` | Immutable, completed replacement-command envelope; one envelope per correction group. |

`market_quotes`, `exchange_rates`, and reference valuation are inputs for display/reference only. They never rewrite ordinary ledger facts, investment facts, `assets` ledger projection fields, or `accounts.balance`.

## Ownership, relationships, and projection boundary

User-owned financial facts and projections are user-scoped. System categories and global market-quote/FX reference rows are explicit shared-data exceptions. The schema uses composite foreign keys where an ID alone would risk cross-user binding:

- `assets(user_id, account_id)` references `accounts(user_id, id)`; transaction-driven Assets must also bind `investment_instruments(user_id, id)`.
- Every investment fact is bound to its owned Asset and Account, including the composite `(user_id, asset_id, account_id)` binding to the Asset. An opening/BUY/SELL/DIVIDEND fact therefore cannot point at an unbound or different-account Position.
- A correction's original fact is bound by `(user_id, account_id, asset_id, original_transaction_id)`; its Instrument is also user-bound.
- Replacement envelopes and their two facts reference one another through deferred composite foreign keys, so the application can write facts first and the completed command last while commit still proves the group is complete.

`assets` has two modes:

- `LEGACY` is the existing manual Asset model. It can use the existing Asset CRUD/manual price/close semantics and need not have Account or Instrument bindings.
- `TRANSACTION_DRIVEN` is a controlled projection identified by `(user_id, account_id, instrument_id)`. Both bindings are mandatory and a partial unique index permits only one such Position. Its quantity, cost, status, last fact, and projection version are written from effective investment facts, not the legacy Asset API.

The Account/Instrument/Asset compatibility check belongs to the write path: active `BROKERAGE` accounts permit STOCK/ETF/FUND/BOND, and active `CRYPTO_WALLET` accounts permit CRYPTO. Other account types are rejected for transaction-driven Positions.

## Investment fact and correction model

`investment_transactions` is append-only. Business types are `OPENING_POSITION`, `BUY`, `SELL`, `DIVIDEND`, and `REVERSAL`; status is always `POSTED`. The original fact keeps its amount, quantity, request hash, and posting receipt forever.

- `OPENING_POSITION` is created only by the single-Asset Legacy migration. It is a zero-cash migration fact and has no command receipt.
- BUY/SELL/DIVIDEND and standalone REVERSAL record a complete immutable receipt: account balance after, Position quantity/average cost/total cost/cumulative realized PnL/status after, and `projection_version_after`.
- A standalone reversal references one eligible original BUY/SELL/DIVIDEND and has its own complete resulting receipt. A partial unique index permits only one reversal per original.
- A replacement is not a `REPLACEMENT` transaction type. It consists of one grouped REVERSAL and one replacement BUY/SELL/DIVIDEND fact, plus exactly one row in `investment_transaction_corrections`.

The correction envelope stores the external idempotency key/request hash, correction reason, original/fact IDs, the three cash deltas (`reversal_cash_delta`, `replacement_cash_delta`, `command_cash_delta`), and the final Account/Position receipt. Its `correction_group_id` binds the two facts. It is an immutable completion record, not a pending workflow.

Replacement facts carry `replay_anchor_transaction_id = original.id` and `replay_sequence = 1`; ordinary facts have a null anchor and sequence 0. The replay key is logically `(effectiveTradeTime, effectiveAnchorId, replaySequence, fact.id)`: the original is excluded, and the replacement occupies the original's logical slot even though its physical ID is newer. This distinguishes immutable posting snapshots (for example an old SELL's released cost and receipt) from corrected current truth (full replay plus the Asset projection).

## Database-enforced invariants

- Investment currency is CNY; numeric amount, quantity, time, source, type, and receipt shape constraints reject malformed facts. `quantity`/unit price/average cost use scale 8; investment money, costs, cash deltas, and realized PnL use `NUMERIC(28,2)`; Account balance remains `NUMERIC(18,2)`.
- Opening facts are `MIGRATION` facts, zero cash effect, and limited by a partial unique index to one posted opening fact per Asset.
- User-scoped idempotency keys are unique. Canonical keys are trimmed 1–100 characters and request hashes are lowercase SHA-256 values.
- Partial unique indexes enforce the one transaction-driven Position identity, one reversal per original, one grouped reversal and one grouped replacement per correction group, and one replacement anchor.
- `validate_append_only_investment_reversal` validates the original binding, copied audit values, reversal cash delta, and eligible original. `validate_investment_replacement_fact` validates anchor/type/currency/time/cash-delta inheritance. Deferred completion triggers validate the correction envelope plus exactly one fact pair at commit.
- `prevent_investment_transaction_mutation` rejects update/delete of investment facts. `prevent_investment_transaction_correction_mutation` does the same for correction envelopes. Retired mutable fields (`replaces_transaction_id`, `reversed_at`, `reversal_reason`) must remain empty.

## Write and migration compatibility policy

The database protects shape and ownership; Java owns replay legality such as oversell and orphan dividends. A command locks its resources in the prescribed order and runs fact insertion, `AccountBalanceService` mutation, replay, Asset projection update, receipt/conformance check, and correction-envelope insertion (if applicable) in one database transaction. Any failure rolls back all effects.

Schema evolution remains additive/hardening rather than data invention. V7 refuses pre-existing transaction-driven Assets without explicit opening migration; V9 rejects unbound/mismatched historical facts; V10/V11 reject facts whose historical receipts cannot be reconstructed; V12 rejects the retired mutable correction state; V13 validates the expected V12 trigger definitions before adding replacement integrity. New writes must use the current append-only correction model and must never reintroduce direct Asset-ledger edits, mutable original facts, unbound transaction-driven Assets, or the retired replacement link.
