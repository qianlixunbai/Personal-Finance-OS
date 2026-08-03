# 开发指南

## 仓库与变更原则

- 当前开发分支是 `zh-cn`；`sites-demo` 是独立的静态只读展示分支，不作为后端工作范围。
- [Architecture](03-Architecture/Architecture.md) 是冻结基线；架构级变更必须通过 ADR，不能用实现或文档悄然回填。
- schema 权威来源是 `backend/src/main/resources/db/migration` 的 Flyway `V1`–`V13`，不得编辑已应用 migration 或恢复旧 `schema.sql` 初始化。
- 当前后端有 11 个 tracked Controller；数量是实现事实，不是长期发布指标。

## 环境与命令

本地需要 Java 21、Node.js/npm、PostgreSQL 或兼容 Docker Compose 的 Docker Engine。配置 `DB_USERNAME`、`DB_PASSWORD`、至少 32 位的 `JWT_SECRET`，以及不同且至少 32 位的 `MIGRATION_PREVIEW_SECRET`。Market Data/FX 默认关闭，密钥不得写入 tracked 文件。

```powershell
cd backend
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test

cd frontend
npm install
npm test
npm run lint
npm run build

Copy-Item docker\.env.example docker\.env
docker compose --env-file docker/.env -f docker/compose.yml up --build -d
docker compose --env-file docker/.env -f docker/compose.yml down
```

## migration、金融写路径与并发

空数据库可正常执行 Flyway；旧 `schema.sql` 数据库若缺少 `flyway_schema_history`，必须先备份并作显式迁移/重建决策。migration 改动采用前向演进，并按风险补充 PostgreSQL/Testcontainers、trigger/deferred constraint 与 runtime smoke 验证。

金融写路径必须由后端权威计算，使用 `BigDecimal`，覆盖事务回滚、幂等、用户隔离和 replay/projection。账户和投资命令必须遵循规定锁顺序；lock timeout/deadlock 与 unknown-commit recovery 是 API 契约的一部分。

Phase 2B 关闭时的历史完整基线为 92 suites / 496 tests / 0 failures / 0 errors，PostgreSQL 17.10、Flyway V13；新改动应运行与范围匹配的实际命令，不能把该历史数值当实时统计。交付前运行 `git diff --check`、检查 `git status --short`，排除无关文件、密钥、构建产物和 `sites-demo` 改动。
