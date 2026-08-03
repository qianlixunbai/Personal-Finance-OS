# Deployment Guide

## Scope

This is the supported minimal single-host Docker Compose path. It builds a React/Nginx frontend, a Spring Boot backend, and PostgreSQL. It provides persistence, service health checks, and a reproducible startup path; it is not a complete production platform for HTTPS, backups, HA, external monitoring, a registry, or automated delivery.

## Compose topology

```text
Browser -> Frontend Nginx -> Backend Spring Boot -> PostgreSQL 17
```

The frontend serves the SPA and proxies `/api/*` over the internal Compose network. The backend uses the `prod` profile, exposes health/readiness only as configured, and runs Flyway migrations `V1` through `V13` at startup. Frontend host access is controlled by `FRONTEND_PORT` (the template uses `8088`).

## Required environment

Copy `docker/.env.example` to ignored `docker/.env`; do not commit the result. Set real values for:

- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`;
- `JWT_SECRET` (at least 32 characters);
- `MIGRATION_PREVIEW_SECRET` (a separate value of at least 32 characters);
- `FRONTEND_PORT`.

`MARKET_DATA_ENABLED=false` is the safe default. If market reference data is enabled, provide `MARKET_DATA_API_KEY` and observe the configured request limits. FX data has no Compose provider configuration and is disabled unless explicitly configured by the deployment environment.

## Start, inspect, and stop

```powershell
Copy-Item docker\.env.example docker\.env
# Replace all placeholder secrets in docker\.env
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
docker compose --env-file docker/.env -f docker/compose.yml ps
docker compose --env-file docker/.env -f docker/compose.yml logs --tail 100 backend
docker compose --env-file docker/.env -f docker/compose.yml down
```

PostgreSQL has a `pg_isready` health check. Backend readiness is `GET /actuator/health/readiness`; the frontend has an HTTP health check. The named `postgres_data` volume retains database data across normal stop/start cycles.

## Database boundary

Use an empty PostgreSQL database for the standard Compose path. Flyway creates its history and applies the tracked migrations. A legacy database created under the old `schema.sql` initialization path may contain business tables without `flyway_schema_history`; do not start it blindly, enable permanent baseline mode, or assume Compose can reconcile it. Back it up and decide on an explicit migration/rebuild procedure first.

## Operational boundary

The health checks establish only the Compose service readiness described here. They do not prove market-provider availability, data correctness, backup recovery, security hardening, or production capacity. The public static demo is not this deployment and has no real backend or provider connection.
