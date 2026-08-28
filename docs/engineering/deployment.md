# Deployment

## 1. 当前拓扑

仓库定义最小单机 Docker Compose 拓扑：

```text
Browser → Frontend Nginx → Spring Boot Backend → PostgreSQL 17
```

- PostgreSQL 使用 named volume；
- backend 在启动时执行 Flyway V1–V17；
- frontend 代理 `/api/*` 到内部 backend；
- 启动依赖为 PostgreSQL healthy → backend readiness → frontend health；
- production profile 关闭 OpenAPI / Swagger，只暴露 health。

该拓扑不是包含 HTTPS、备份、监控、告警、镜像仓库、高可用和灾备的完整生产平台。

## 2. 当前阻塞性配置不一致

`TransactionImportPreviewTokenService` 要求 `finance.import.confirm.token-secret`，对应环境变量 `FINANCE_IMPORT_CONFIRM_TOKEN_SECRET`，且长度至少 32 字符。缺失时应用按设计安全 fail-fast。

当前：

- `docker/.env.example` 没有该变量；
- `docker/compose.yml` 没有把该变量传入 backend；
- Compose 只传入了 `MIGRATION_PREVIEW_SECRET`，它用于 Legacy opening，不能当作 Import secret 的隐式替代。

因此当前 HEAD 的标准：

```powershell
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
```

不能被宣称为完整可运行路径。修复需要同步 Compose environment、示例 env 和本地启动校验；这些属于配置/代码任务，超出本轮文档重构范围。

## 3. Compose 配置检查

以下命令仍可验证 Compose 结构和当前已声明变量：

```powershell
docker compose --env-file docker/.env.example -f docker/compose.yml config
```

它不会启动 backend，也不会发现缺失的应用级 Import secret，因此不能作为 runtime smoke。

## 4. 预期必需配置

| 变量 | 用途 |
| --- | --- |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | PostgreSQL |
| `JWT_SECRET` | JWT |
| `MIGRATION_PREVIEW_SECRET` | Legacy opening token |
| `FINANCE_IMPORT_CONFIRM_TOKEN_SECRET` | Transaction Import token |
| `FRONTEND_PORT` | 宿主机前端端口 |
| `MARKET_DATA_*` | 可选行情 Provider |

三个 secret 应彼此独立，不写入 Git。

## 5. 数据库边界

标准路径应使用由 Flyway 管理的数据库。旧 `schema.sql` 数据库若已有业务表但没有 Flyway history，不得盲目启动或永久启用 baseline；应先备份并制定迁移或重建方案。

V16/V17 包含 Import Receipt backfill、immutable trigger 和 digest forward repair。已有持久 volume 的升级必须核对 `flyway_schema_history`、Batch receipt 重建和应用 readiness。

## 6. Runtime 验收

配置缺口修复后，至少验证：

1. PostgreSQL healthy；
2. backend readiness；
3. Flyway 最终版本 V17；
4. frontend health 与 `/api/*` 反向代理；
5. 注册/登录及关键只读 API；
6. Import Preview token bean 正常初始化；
7. 日志不含 secret、JWT、SQL 细节或敏感数据。

健康检查只证明当前拓扑可达，不证明数据正确性、备份恢复、安全加固或生产容量。
