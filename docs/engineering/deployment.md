# Deployment

## 1. 当前拓扑

仓库定义最小单机 Docker Compose 拓扑：

```text
Browser → Frontend Nginx → Spring Boot Backend → PostgreSQL 17
```

- PostgreSQL 使用 named volume；
- backend 在启动时执行 Flyway V1–V21；
- frontend 代理 `/api/*` 到内部 backend；
- 启动依赖为 PostgreSQL healthy → backend readiness → frontend health；
- production profile 关闭 OpenAPI / Swagger，只暴露 health。

该拓扑不是包含 HTTPS、备份、监控、告警、镜像仓库、高可用和灾备的完整生产平台。

## 2. Import Confirm secret

`TransactionImportPreviewTokenService` 要求 `finance.import.confirm.token-secret`，对应环境变量 `FINANCE_IMPORT_CONFIRM_TOKEN_SECRET`，且长度至少 32 字符。缺失时应用按设计安全 fail-fast。

`docker/.env.example` 声明该变量，`docker/compose.yml` 将其显式传入 backend。它与 `MIGRATION_PREVIEW_SECRET` 相互独立；后者用于 Legacy opening，不能作为 Import secret 的替代。

启动前，将本地 `.env` 中的示例值替换为各自独立、长度至少 32 字符的 secret：

```powershell
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
```

首次启动前还必须完成下文 Import 持久存储预配。仓库不包含生产 secret；生产环境必须由部署环境提供该变量。

## 3. Compose 配置检查

以下命令仍可验证 Compose 结构和当前已声明变量：

```powershell
docker compose --env-file docker/.env.example -f docker/compose.yml config
```

它不会启动 backend，不能替代 runtime smoke；但会校验 Compose 对必需 secret 的变量契约。

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

至少验证：

1. PostgreSQL healthy；
2. backend readiness；
3. Flyway 最终版本 V21；
4. frontend health 与 `/api/*` 反向代理；
5. 注册/登录及关键只读 API；
6. Import Preview token bean 正常初始化；
7. 日志不含 secret、JWT、SQL 细节或敏感数据。

健康检查只证明当前拓扑可达，不证明数据正确性、备份恢复、安全加固或生产容量。

## 7. Import Trusted Deployment Binding（C9 方案 A）

支持 Windows / Linux 单主机持久本地目录，以及 Linux Docker 明确挂载的持久受管卷。跨进程仅限同一主机、同一数据库、同一 root 和 identity；不支持多主机、网络共享卷或任意系统临时目录。Windows 依赖 JDK 21 文件 provider 的 `NOSHARE_DELETE` pin；Linux 依赖 `SecureDirectoryStream` 相对操作。能力无法取得时拒绝操作。

部署方须为 RAW 和 PLAN 提供互不嵌套的独占 root，以及三个稳定 UUID：`FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID`、`FINANCE_IMPORT_STORAGE_RAW_ID`、`FINANCE_IMPORT_STORAGE_PLAN_ID`。目录、`.import-binding` 和 `.import-control` 必须持久保留，服务账号具备读写权限，无关普通进程不得修改；control 对象在应用运行期间不得被任何外部流程替换或 unlink。Windows 由部署方配置服务账号专用 ACL，Linux 使用服务账号所有权及目录 `0700`。保护父目录，禁止旧版实例绕过 Registry 写入。

应用启动只验证配置、可达性及受信绑定，**不会创建缺失的生产 root、marker 或 control**。每次正常重启复用相同身份和卷，无须重复人工确认。首次新建且为空的专用存储才运行以下显式命令；不要将预配任务加入自动启动依赖：

```powershell
docker compose --env-file docker/.env -f docker/compose.yml --profile import-provision run --rm import-storage-provision
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
```

Compose 将 RAW / PLAN 挂载至 `import_storage` named volume 的 `/var/lib/finance-os-import/raw`、`/var/lib/finance-os-import/plan`。预配任务拒绝已有、部分初始化或非空目录，创建后设置后端服务账号所有权；正常 `up` 不运行它。不要通过 `down -v` 删除该卷。非 Docker 部署还须配置两个绝对路径环境变量 `FINANCE_IMPORT_STORAGE_RAW_ROOT`、`FINANCE_IMPORT_STORAGE_PLAN_ROOT`；在已核定的空专用根上，使用打包 JAR 的显式预配入口：

```text
java -Dloader.main=com.financeos.module.importing.storage.ImportStorageProvisioner -cp finance-os-backend-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher --provision
```

该入口不连接数据库、不修改已有绑定。预配完成后核实服务账号及父目录权限再启用应用。生产应用不得启用用于隔离夹具的 `test` profile。

V21 只追加 Registry / binding 表，不修改 V1–V20。升级时先停止所有旧写入实例，核定旧 Session 引用所在专用根和稳定身份；启动清理只按数据库中**确切引用**核验并登记，不枚举磁盘，不自动迁移或删除历史未知孤儿。旧引用无法安全核定时保留 Session，人工处置；不能将旧临时目录直接视为可信持久根。

备份 / 恢复须协调数据库与此卷。迁移、卷重建、身份变化或已知绑定异常后，先停止写入和清理，核对 Registry、Session、物理对象及身份，再作人工恢复决定。检测到异常时持久记录 `quarantined`，恢复 marker 或路径不会自动解除；数据库隔离位只能由经过核定的运维操作解除，禁止自动重建绑定、批量标记 DELETED 或清空 Registry。

方案 A **不能自动识别高权限停机期间的同路径目录克隆、复制 marker 或错误恢复到具有相同身份的卷**，也不能以 path、marker、volume serial 证明跨重启物理对象身份。部署系统保证不静默替换或克隆受管根；丧失该保证必须停止并重新核定，不能继续以路径存在作为授权。

每个 root 最多 2 个 I/O worker、8 个排队请求；互斥等待最多 4 秒，调用方等待最多 30 秒。超时不取消或释放实际 worker 的 guard。短数据库事务不持有文件 I/O；Confirm 先完成有界内存读取，再保持原 Session → Account 金融事务。受阻清理保留 RETIRED 和持久重试。不可中断 I/O 可要求受控终止进程后恢复，不能承诺固定秒数内物理删除。
