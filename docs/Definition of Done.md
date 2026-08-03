# Definition of Done

Use the smallest verification set that proves the changed risk. Completion always includes a readable diff, `git diff --check`, and an inspection of `git status --short` for unrelated files, generated artifacts, secrets, and unapproved changes.

## Ordinary low-risk change

- Scope, acceptance behavior, and affected documentation are clear.
- Relevant lint, focused test, or manual check has been run where available.
- No unrelated refactor or generated output is included.

## API change

- Request/response, validation, authorization, error semantics, and backward compatibility are considered.
- The API documentation and relevant frontend contract are updated together.
- Controller/service tests cover the changed contract, including user-scope behavior where relevant.

## Migration or database constraint

- A new forward-only Flyway migration is provided; no applied migration is edited.
- Empty-database migration and the relevant PostgreSQL/Testcontainers path are verified.
- Constraints, indexes, trigger/deferred-integrity behavior, rollback, and legacy-data compatibility are documented and tested in proportion to risk.

## Financial write path

- Backend is the sole authority for calculations and projections; `BigDecimal`/`NUMERIC` precision and rounding are explicit.
- Transaction boundaries, account-balance mutation, append-only facts/receipts, idempotency, user isolation, and replay/projection rules are verified as applicable.
- Success, validation failure, business conflict, and complete rollback paths are covered.

## Concurrency or lock change

- Lock order and timeout/deadlock mapping are explicit and tested.
- Concurrent commands cannot double-apply cash, facts, or a projection; unknown-commit/idempotency recovery is preserved where applicable.

## Documentation-only change

- Facts are checked against code/migrations, ADRs, and the latest Closing Review.
- Frozen Architecture and historical ADR/Review/design/log records remain untouched.
- Relative links, navigation, phase status, and terminology are checked. Full application tests are not required unless a code/configuration file was also changed.

## Runtime smoke and phase closure

- Compose/runtime smoke is required when deployment, migration, production configuration, or runtime integration is changed.
- A Closing Review records scope, evidence, known limitations, and a GO/NO-GO result without rewriting historical evidence.

## Final hygiene

Run the relevant checks before handoff:

```powershell
git diff --check
git diff --cached --check
git status --short
```

Only stage or commit files explicitly in scope. Do not stage `AGENTS.md` or any other untracked/user-owned file unless the user has expressly authorized it.
