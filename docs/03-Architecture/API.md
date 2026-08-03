# API Baseline

All routes are under `/api/v1`. Responses use the common `ApiResponse` envelope. Except registration and login, routes require bearer authentication and operate only within the authenticated user's scope.

## Implemented controllers and routes

| Controller | Routes |
| --- | --- |
| `UserController` | `POST /register`, `POST /login` |
| `AccountController` | `GET /accounts`, `GET /accounts/page`, `GET /accounts/{id}`, `POST /accounts`, `PUT /accounts/{id}`, `POST /accounts/{id}/deactivate` |
| `CategoryController` | `GET /categories?type=`, `POST /categories`, `POST /categories/init` |
| `TransactionController` | `GET /transactions/page`, `GET /transactions/{id}`, `POST /transactions`, `PUT /transactions/{id}`, `DELETE /transactions/{id}` |
| `AssetController` | `GET /assets`, `GET /assets/page`, `GET /assets/{id}`, `POST /assets`, `PUT /assets/{id}/price`, `PUT /assets/{id}/close`, `DELETE /assets/{id}`, `POST /assets/{id}/quote/refresh`, `POST /assets/{id}/reference-valuation/refresh` |
| `DashboardController` | `GET /dashboard` |
| `InvestmentInstrumentController` | `GET /investment/instruments`, `POST /investment/instruments` |
| `LegacyAssetMigrationController` | `POST /investment/legacy-assets/{assetId}/migration-preview`, `POST /investment/legacy-assets/{assetId}/migration-confirm` |
| `InvestmentCommandController` | `POST /investment/positions` (first BUY), `POST /investment/positions/{assetId}/buy`, `POST /investment/positions/{assetId}/sell`, `POST /investment/positions/{assetId}/dividends` |
| `InvestmentReversalController` | `POST /investment/transactions/{transactionId}/reversal` |
| `InvestmentReplacementController` | `POST /investment/transactions/{transactionId}/replacement` |

There is deliberately no Portfolio read API and no public InvestmentTransaction list/detail/audit-timeline API. No Phase 2C-1 endpoint is implied by this document.

## Investment commands

Investment commands record already-executed facts; they never place orders or integrate with brokers.

- Opening migration is a two-step, single-Asset flow. Preview accepts `instrumentId` and `accountId` and is read-only. Confirm accepts the signed expiring `previewToken` and requires `X-Idempotency-Key`; it creates one `OPENING_POSITION`, changes the Asset to transaction-driven, and does not alter Account balance.
- First BUY requires Account/Instrument identity plus trade fields. Later BUY and SELL are Asset-scoped. BUY/SELL requests carry `quantity`, `unitPrice`, `feeAmount`, and `taxAmount`.
- DIVIDEND is Asset-scoped and carries `grossAmount`, optional `feeAmount`/`taxAmount`, optional `externalReference` and `note`. It records received cash, does not change holding quantity/cost, and may be posted for an open or closed eligible Position.
- Standalone reversal accepts only `reason` and can correct one original BUY, SELL, or DIVIDEND by appending a reversal fact.
- Replacement accepts a type-specific body for the original BUY/SELL/DIVIDEND plus `reason`. It atomically appends a grouped reversal and a replacement fact; it never PUTs or deletes the original.

Every BUY, SELL, DIVIDEND, standalone reversal, and replacement requires `Idempotency-Key`. Reusing a key with the same canonical request recovers the immutable recorded result; a conflicting request is rejected. Investment decimal inputs and outputs are plain fixed-scale JSON strings, not JSON numbers. Dividend, reversal, and replacement bodies reject unknown JSON fields; callers should send exactly the documented type-specific shape. The public investment write surface does not accept user IDs, currency, client posting times, computed ledger fields, receipts, correction-group fields, or an arbitrary transaction type.

## Mutation, consistency, and errors

Ordinary `transactions` retain their own create/update/delete API and are not investment facts. Investment facts are append-only: correction is reversal or replacement only. Investment writes are atomic across fact(s), Account cash delta, full replay, transaction-driven Asset projection, immutable receipt, and replacement envelope. A failed command leaves none of those effects committed.

The API uses these status classes consistently:

| Status | Meaning |
| --- | --- |
| 400 | Invalid parameters, validation failure, malformed/unknown strict JSON fields, or invalid decimal text. |
| 401 | Missing, expired, or invalid authentication. |
| 403 | Authenticated but not permitted for the operation. |
| 404 | Resource absent from the caller's scope; cross-user resources intentionally use the same result. |
| 409 | Idempotency conflict, business conflict, replay conflict, or lock timeout/deadlock concurrency conflict. |
| 429 | Upstream market-data rate limit. |
| 500 | Sanitized unexpected/integrity/replay consistency failure; transaction rolls back. |
| 502 | Invalid or failed upstream market-data response. |
| 503 | Market-data provider/service unavailable. |

Quotes, FX, and reference valuation endpoints are reference-data operations. They do not create investment facts, change Account balance, or overwrite the ledger-derived projection truth.
