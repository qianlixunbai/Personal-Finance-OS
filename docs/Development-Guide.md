# Development Guide

> **Current status:** `v3.0 Phase 2B Investment Write Foundation: CLOSED — GO`
> **Next phase:** `Phase 2C-1 Investment Read Model Contract — NOT STARTED`

## Repository and change discipline

- The active development branch is `zh-cn`. `sites-demo` is a separate static, read-only presentation branch; do not treat it as application source or modify it as part of backend work.
- The architecture baseline in [Architecture](03-Architecture/Architecture.md) is frozen. Architecture-level changes require an ADR; implementation and documentation changes must not silently rewrite that baseline.
- Schema authority is Flyway `V1` through `V13` in `backend/src/main/resources/db/migration`. Never edit an applied migration or restore the retired `schema.sql` initialization path.
- The current backend surface contains 11 tracked Controllers. Controller and endpoint counts are implementation facts, not permanent release metrics.

## Prerequisites and local configuration

- Java 21 and Node.js/npm for local development;
- PostgreSQL for a non-Compose backend run, or a Docker Engine compatible with Docker Compose;
- `DB_USERNAME`, `DB_PASSWORD`, and a `JWT_SECRET` of at least 32 characters;
- a distinct `MIGRATION_PREVIEW_SECRET` of at least 32 characters for opening-position migration preview/confirm.

`DB_URL` defaults to `jdbc:postgresql://localhost:5432/finance_os`. Market Data and FX providers are disabled by default. Do not add real provider keys to tracked files.

## Commands

```powershell
# Backend
cd backend
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test

# Frontend
cd frontend
npm install
npm test
npm run lint
npm run build

# Compose
Copy-Item docker\.env.example docker\.env
# Set non-placeholder secrets in docker\.env
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
docker compose --env-file docker/.env -f docker/compose.yml ps
docker compose --env-file docker/.env -f docker/compose.yml down
```

The current Compose path is documented in [Deployment Guide](Deployment-Guide.md). OpenAPI and Swagger are local-development aids; production profile disables them.

## Migration, financial writes, and concurrency

- Run migrations against an empty database in normal development. A database created by the former `schema.sql` path and lacking `flyway_schema_history` needs an explicit backup/migration decision; it must not be blindly baselined or repaired.
- Migration changes require a forward-only migration, PostgreSQL/Testcontainers coverage where practical, and an isolated runtime smoke check when the migration depends on database trigger or deferred-constraint behavior.
- Financial write-path changes require backend-owned calculations, `BigDecimal`, transactional rollback coverage, idempotency, user isolation, and replay/projection validation as applicable.
- Account and investment commands follow the documented lock order. Lock timeout/deadlock and unknown-commit recovery are API behavior, not incidental implementation details.

## Verification expectations

The historical final Phase 2B closure baseline is **92 suites / 496 tests / 0 failures / 0 errors**, recorded with PostgreSQL 17.10 and Flyway V13 verification. It is not a live test count for later changes; use the commands above to verify a new change.

For a focused change, run the nearest relevant tests plus `git diff --check`. Run Maven, Node, Compose, and runtime smoke checks in proportion to the files and risks touched. Before handoff, inspect `git status --short` and ensure no unrelated files, generated output, secrets, or `sites-demo` changes were included.

## Documentation sources of truth

Read implementation/migrations first, then the relevant ADR, then the latest Closing Review, then active documentation. Current rules and contracts live in [Database](03-Architecture/Database.md), [API](03-Architecture/API.md), [Business Rules](Business%20Rules.md), and [Financial Rules](Financial%20Rules.md); Closing Reviews remain historical evidence.
