# ADR-005：采用 Flyway 管理数据库迁移

**项目名称：** Personal Finance OS

**ADR 编号：** ADR-005

**标题：** 采用 Flyway 管理数据库迁移

**状态：** Accepted（已采纳）

**日期：** 2026-07-17

**负责人：** 项目维护者

------

# 一、背景（Context）

Phase 1 之前，项目通过 Spring Boot SQL Init 加载 `schema.sql` 初始化数据库结构。这种方式适合早期开发阶段，但无法为数据库结构演进提供版本、顺序和执行历史记录，也不能清晰区分全新数据库与已经存在业务表的旧开发数据库。

当前项目需要在保持 Spring Boot 3.3.5、Java 21 和 PostgreSQL 17 不变的前提下，让数据库结构具备可追踪、可审查的演进入口。Phase 1 已将当前结构整理为 `backend/src/main/resources/db/migration/V1__baseline.sql`，并使用 PostgreSQL Testcontainers 验证空数据库迁移。

本 ADR 只记录数据库迁移管理方案，不代表已对本地 `finance_os` 或任何生产数据库执行 migration、baseline、重建或修改。

------

# 二、决策目标（Decision Goal）

- 使用版本化文件记录数据库结构演进；
- 让新的空 PostgreSQL 数据库能够在应用启动时得到确定的结构；
- 保留 migration 执行历史，便于验证和审查；
- 让 CI 可以通过 Testcontainers 验证迁移，而不依赖独立 PostgreSQL service；
- 明确已有旧开发数据库的处理边界，避免应用启动时隐式修改未知结构。

------

# 三、备选方案（Options）

## 方案 A：继续使用 Spring SQL Init 和 `schema.sql`

优点：

- 配置简单，早期开发上手成本低；
- 可以直接阅读单个初始化脚本。

缺点：

- 缺少版本化 migration 和执行历史；
- 初始化脚本容易被重复执行或被直接修改，难以追踪结构演进；
- 无法清晰表达已存在数据库应执行哪些增量变更。

适用场景：一次性原型或不需要持续演进数据库结构的项目。

## 方案 B：采用 Flyway 管理版本化 migration

优点：

- 通过版本号和描述记录结构演进顺序；
- 使用 `flyway_schema_history` 保留执行历史；
- 与 Spring Boot 启动流程和 PostgreSQL 集成自然；
- 可以用 Testcontainers 在空数据库中验证真实迁移路径。

缺点：

- 团队必须遵守 migration 命名、顺序和 checksum 纪律；
- 旧数据库已有表但没有 history 时，仍需要单独的重建或受控 baseline 决策；
- 本方案不自动提供任意业务结构变更的安全回滚。

## 方案 C：采用 Liquibase 管理变更集

优点：

- 支持 XML、YAML、JSON 或 SQL 等多种变更集表达方式；
- 具有较丰富的变更集元数据能力。

缺点：

- 对当前规模的项目引入了额外表达方式和维护成本；
- 当前需求只需要清晰的 SQL migration 和执行历史，Liquibase 的能力超出必要范围。

------

# 四、最终决策（Decision）

项目采用 Flyway 10.20.0 管理 PostgreSQL 数据库迁移，依赖：

- `org.flywaydb:flyway-core`；
- `org.flywaydb:flyway-database-postgresql`。

具体约定如下：

1. 当前唯一数据库结构来源为 `backend/src/main/resources/db/migration/V1__baseline.sql`。
2. 原 `schema.sql` 已删除，主应用和测试 profile 已移除 Spring SQL Init。
3. Spring Boot 使用 Flyway 默认的 `classpath:db/migration` 位置执行版本化 migration。
4. V1 包含当前 6 张业务表，不夹带 Known Gaps 修复。
5. 新的空 PostgreSQL 17 数据库启动时自动执行 V1，并在 `flyway_schema_history` 中记录成功的 migration。
6. 当前未配置 `baseline-on-migrate`。对于由旧版 `schema.sql` 创建、已有表但没有 history 的开发数据库，不直接启动新版应用；应在备份后重建空数据库，或经过 schema 比对后执行一次受控 baseline。
7. 后续结构变更使用 `V2__...sql`、`V3__...sql` 等新文件，不修改已经应用的 migration，也不在本 ADR 中创建 V2。

------

# 五、影响分析（Consequences）

## 正面影响

- 数据库结构演进有明确版本、顺序和执行历史；
- 新空数据库的初始化路径与测试路径一致；
- `FlywayMigrationIntegrationTest` 使用 PostgreSQL 17 Testcontainers 验证空数据库、history、6 张业务表及关键结构；
- CI 使用 Testcontainers 验证迁移，不需要额外的 PostgreSQL service；
- 数据库结构变更可以在代码评审中与对应 migration 一起审查。

## 负面影响

- 修改已应用的 migration 可能导致 checksum 不一致，因此必须新增版本化 migration；
- 旧开发数据库的 history 缺失不能由应用安全推断，需人工制定受控处理方案；
- migration 失败后的数据恢复和业务回滚仍需要备份、修复 migration 或环境级恢复策略，不能假设自动回滚。

------

# 六、实施计划（Implementation）

- 在 `backend/pom.xml` 中显式覆盖 `flyway.version=10.20.0`，引入 Flyway 核心与 PostgreSQL 模块；
- 从 `application.yml` 和 `application-test.yml` 移除 Spring SQL Init；
- 将原数据库结构迁移为 `V1__baseline.sql`；
- 新增 `FlywayMigrationIntegrationTest`，仅使用 PostgreSQL Testcontainers 验证空库迁移；
- 同步 README、Roadmap、Development Guide 和 Database 文档；
- 使用本 ADR 固化迁移管理决策。

------

# 七、风险分析（Risks）

- 迁移文件命名或版本顺序错误会阻止应用启动；通过统一 `V{version}__{description}.sql` 约定和代码评审缓解；
- 已有旧数据库缺少 history 时，错误的 baseline 可能掩盖结构差异；通过备份、schema 比对和一次性受控操作缓解；
- 将 Known Gaps 混入 V1 会使基线含义不清；V1 保持当前实现结构，差距修复留给后续明确的 migration 和 ADR；
- 数据库 migration 不等于业务数据备份，也不等于自动回滚方案；发布前仍需按环境制定备份与恢复策略。

------

# 八、是否需要回滚（Rollback）

本决策本身可以通过后续 ADR 取代，但不应通过修改 ADR-005 或重写已应用 migration 来回滚历史。

数据库结构变更不依赖自动回滚。若后续需要撤销某项结构变更，应根据环境备份、数据影响和兼容性选择新的前向 migration 或环境恢复方案，并在执行前完成单独评审。

------

# 九、相关文档（References）

- [README](../../README.md)
- [Roadmap](../product/roadmap.md)
- [Development Guide](../engineering/development.md)
- [Database](../architecture/database.md)
- `backend/pom.xml`
- `backend/src/main/resources/db/migration/V1__baseline.sql`
- `backend/src/test/java/com/financeos/integration/FlywayMigrationIntegrationTest.java`
- [Database Review](../archive/review/Database-Review.md)

------

# 十、后续行动（Follow-up）

- 为旧开发数据库制定独立的 schema 比对、备份和受控 baseline 操作说明；
- 只有在明确业务需求和兼容策略后，才新增 V2 或后续 migration；
- 继续将 `assets.account_id`、`AssetPrice` Entity / Mapper、`asset_prices.updated_at`、逻辑删除对齐等 Known Gaps 作为后续评估项；
- 保持每个结构变更同时更新 migration、Database 文档和必要的测试。

------

# 十一、审批记录（Approval）

- 提出时间：2026-07-17
- 确认时间：2026-07-17
- 状态：Accepted

------

# 十二、变更记录（Change Log）

| 日期 | 变更内容 | 变更人 |
|---|---|---|
| 2026-07-17 | 创建 ADR-005，记录采用 Flyway 管理数据库迁移 | 项目维护者 |
