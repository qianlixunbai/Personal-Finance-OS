# 部署指南

## 范围与拓扑

本指南提供最小单机 Docker Compose 路径：React/Nginx 前端、Spring Boot 后端和 PostgreSQL 17。它提供可复现启动、持久卷和健康检查，不等同于 HTTPS、备份、高可用、外部监控、镜像仓库或自动化交付的完整生产平台。

```text
Browser -> Frontend Nginx -> Backend Spring Boot -> PostgreSQL 17
```

前端代理 `/api/*` 到内部后端网络；后端使用 `prod` profile，启动时执行 Flyway `V1`–`V13`。

## 配置与命令

复制 `docker/.env.example` 为未跟踪的 `docker/.env`，填写 `POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`、至少 32 位的 `JWT_SECRET`、独立的至少 32 位 `MIGRATION_PREVIEW_SECRET` 与 `FRONTEND_PORT`。`MARKET_DATA_ENABLED=false` 为安全默认值；FX 默认关闭。

```powershell
Copy-Item docker\.env.example docker\.env
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
docker compose --env-file docker/.env -f docker/compose.yml ps
docker compose --env-file docker/.env -f docker/compose.yml logs --tail 100 backend
docker compose --env-file docker/.env -f docker/compose.yml down
```

PostgreSQL 使用 `pg_isready`，后端 readiness 是 `/actuator/health/readiness`，前端有 HTTP health check；`postgres_data` 卷保留正常停启间的数据。

## 数据库与运维边界

标准路径要求空数据库。旧 `schema.sql` 数据库若已有业务表却没有 Flyway history，不得盲目启动、永久启用 baseline 或假定 Compose 会自动协调；应先备份并制定迁移/重建方案。健康检查只证明此 Compose 拓扑可用，不证明行情 Provider、数据正确性、恢复能力、安全加固或生产容量。公开静态 Demo 不是该部署，也不连接真实后端。
