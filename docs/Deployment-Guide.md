# Deployment Guide

## 1. Scope

This guide describes the project's minimal single-host container deployment path. It provides reproducible image builds, Docker Compose startup, PostgreSQL persistence, health probes, and a smoke checklist. It does not provide HTTPS, cloud deployment, automated backups, high availability, external monitoring, a container registry, or automatic CD.

## 2. Topology

```text
Browser
  -> Frontend Nginx
      - React static files and SPA fallback
      - /api/* -> Spring Boot backend
                       -> PostgreSQL
```

The browser always uses the existing relative `/api/v1` path. Nginx forwards `/api/*` to the backend on the internal Compose network, so this deployment does not require a CORS configuration or a production API URL embedded in the Vite bundle.

## 3. Prerequisites

- Docker Desktop or a compatible Docker Engine with Compose;
- an available host port (the template uses `8088`);
- sufficient resources for PostgreSQL, Java, and Node image builds.

Java, Node.js, and PostgreSQL do not need to be installed on the host for the Compose path.

## 4. Configure and start

From the repository root:

```powershell
Copy-Item docker/.env.example docker/.env
```

Edit `docker/.env` before startup. Replace at least `POSTGRES_PASSWORD` and `JWT_SECRET` with local, non-production secrets. `JWT_SECRET` must contain at least 32 characters.

```powershell
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
docker compose --env-file docker/.env -f docker/compose.yml ps
```

The frontend is available at `http://localhost:8088` when the template port is unchanged. PostgreSQL and the backend are intentionally not published to the host; the frontend is the single browser entry point.

## 5. Health and logs

```powershell
docker compose --env-file docker/.env -f docker/compose.yml logs
docker compose --env-file docker/.env -f docker/compose.yml logs backend
```

The backend uses the `prod` Spring profile. It exposes only the Actuator health endpoint group and does not expose health details. The probes are available through Nginx at:

```text
http://localhost:8088/actuator/health/liveness
http://localhost:8088/actuator/health/readiness
```

Liveness reports only the application availability state. Readiness additionally checks the PostgreSQL datasource, so the backend Compose healthcheck uses readiness.

## 6. Smoke checklist

1. `docker compose ... ps` reports PostgreSQL and backend as healthy and frontend as running.
2. The frontend home page opens at the mapped host port.
3. Refreshing a React deep link such as `/accounts` does not return an Nginx 404.
4. Registration and login work through the frontend's same-origin `/api` requests.
5. A protected API request without a JWT receives the existing unified `401` response.
6. `/actuator/health/liveness` and `/actuator/health/readiness` return `UP` without database URLs, credentials, JWT secrets, or stack traces.
7. Stop and restart the stack without `-v`; data in the named PostgreSQL volume remains available.

## 7. Stop safely

```powershell
docker compose --env-file docker/.env -f docker/compose.yml down
```

The backend has a 30-second Compose stop grace period and a 20-second Spring graceful-shutdown timeout. Do not use `down -v` for ordinary shutdowns.

> Warning: `docker compose --env-file docker/.env -f docker/compose.yml down -v` deletes the named PostgreSQL volume and its local data.

After local validation, remove the secret file if it is no longer needed:

```powershell
Remove-Item docker/.env
```

## 8. Database safety

Use an empty PostgreSQL database for this Compose path. Flyway initializes it from the tracked migration at application startup. A database created by the project's former `schema.sql` path, with business tables but no `flyway_schema_history`, must not be started blindly. This phase does not baseline, repair, or migrate legacy databases automatically.

## 9. Troubleshooting

| Symptom | Check |
|---|---|
| Backend fails before startup | Verify all required values in `docker/.env`, especially `JWT_SECRET`, `POSTGRES_PASSWORD`, and the port. |
| PostgreSQL never becomes healthy | Inspect `docker compose ... logs postgres`; remove an incompatible local volume only after preserving data. |
| Backend readiness fails | Inspect backend logs and verify it is using `jdbc:postgresql://postgres:5432/...`, not `localhost`. |
| Nginx returns `502` for `/api` | Wait for backend health, then inspect backend logs and the frontend container environment. |
| Deep link returns `404` | Confirm the frontend is built from the supplied Nginx template and is not served by a different web server. |
| Host port is unavailable | Change `FRONTEND_PORT` in `docker/.env` and restart the stack. |

## 10. Security boundaries

- Never commit `docker/.env` or other real Secret files.
- Never place real database passwords or JWT secrets in Dockerfiles, Compose files, image build arguments, or image environment defaults.
- `docker/.env.example` documents only variable structure and placeholder values.
- This is a local/single-host deployment foundation, not a complete production operations platform.
