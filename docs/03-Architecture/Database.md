# Database（数据库设计）

**项目名称：** Personal Finance OS

**版本：** v1.0

**状态：** Draft

**分支：** zh-cn

**日期：** 2026-07-07

------

# 1. Purpose

本文档用于记录 Personal Finance OS 当前数据库实现基线、目标数据库设计、约束策略、索引策略、已知差距和后续演进方向。

本文档不是 `schema.sql` 的替代品，不直接修改数据库结构，也不等同于 migration。实际数据库初始化脚本当前位于 `backend/src/main/resources/schema.sql`。

本文档的作用是：

- 解释当前数据库设计现状；
- 约束后续数据库结构调整；
- 明确当前实现与目标设计之间的差距；
- 为后续 API、Service、Module Design 和 migration 演进提供依据；
- 保证数据库设计与 `Architecture.md`、`Business Rules.md`、`Financial Rules.md` 保持一致。

------

# 2. Scope

## 2.1 In Scope

本文档覆盖：

- 当前 PostgreSQL 数据库表结构基线；
- 当前 Java Entity 与 Mapper 覆盖情况；
- 核心表字段、关系、约束与索引策略；
- 用户数据隔离规则；
- 金额、价格、数量精度规则；
- 派生数据与一致性要求；
- 已知差距；
- 后续演进方向。

## 2.2 Out of Scope

本文档不包含：

- API URL、请求参数和响应结构；
- Java Service 业务逻辑实现；
- Controller、DTO、Mapper 代码修改；
- `schema.sql` 修改；
- migration 文件；
- Dashboard 或 Analytics 具体计算公式；
- AI 分析算法；
- 前端页面设计。

------

# 3. Database Technology

当前数据库技术栈为：

- Database：PostgreSQL；
- 当前 schema 文件：`backend/src/main/resources/schema.sql`；
- ORM / Data Access：MyBatis-Plus；
- Java 金额类型：`BigDecimal`；
- SQL 金额类型：`DECIMAL`；
- 当前初始化方式：Spring Boot `spring.sql.init` 加载 `schema.sql`。

`spring.sql.init.mode: always` 适合当前开发阶段初始化 schema，便于快速重建本地数据库结构。正式部署或生产环境应迁移到 Flyway / Liquibase，避免依赖 `schema.sql` 反复初始化数据库结构。

当前 `application.yml` 中数据库相关配置包括：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/finance_os
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
```

当前 MyBatis-Plus 配置包括：

- 开启 PostgreSQL 分页插件：`PaginationInnerInterceptor(DbType.POSTGRE_SQL)`；
- 开启下划线转驼峰：`map-underscore-to-camel-case: true`；
- 全局主键策略：`id-type: auto`。

当前配置中存在一个需要后续处理的 mismatch：

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted
```

当前所有表结构中均不存在 `deleted` 字段，因此逻辑删除配置与当前 schema 不匹配。该问题应作为 Known Gap 记录，不应被视为当前已实现能力。

------

# 4. Design Principles

数据库设计遵循以下原则：

1. 数据真实性优先。数据库保存真实业务数据，不保存人工维护的 Dashboard 统计结果。
2. 用户数据隔离优先。所有用户业务数据必须围绕 `user_id` 建模。
3. 金融精度优先。金额、价格、数量使用 `DECIMAL / BigDecimal`，禁止使用 `float / double` 参与金融计算。
4. 约束分层。数据库负责基础完整性约束，复杂业务规则由 Service 层保证。
5. 当前实现诚实记录。未实现能力不得在本文档中描述为已实现。
6. V1 简单优先。暂不引入复杂审计、软删除、汇率、投资交易流水和 AI 结果持久化。
7. Dashboard / Analytics 只读取真实业务数据并计算结果，不直接维护人工统计表。

------

# 5. Naming Conventions

当前数据库命名约定如下：

- 表名使用 snake_case 复数形式，例如 `users`、`accounts`、`transactions`；
- 字段名使用 snake_case，例如 `user_id`、`created_at`、`market_value`；
- Java Entity 使用 PascalCase，例如 `User`、`Account`、`Transaction`；
- Java 字段使用 camelCase，例如 `userId`、`createdAt`、`marketValue`；
- 主键字段统一使用 `id`；
- 外键字段使用 `{target}_id` 形式，例如 `user_id`、`account_id`、`category_id`；
- 索引命名当前采用 `idx_{table}_{column}` 形式。

------

# 6. Common Fields

目标上，核心业务表应尽量包含以下通用字段：

| 字段 | 说明 |
|---|---|
| `id` | 主键，自增 ID |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

当前实现中：

- `users`、`accounts`、`categories`、`transactions`、`assets` 均包含 `id`、`created_at`、`updated_at`；
- `asset_prices` 包含 `id`、`created_at`，但缺少 `updated_at`；
- `asset_prices` 缺少 `updated_at` 与通用字段约定不完全一致，应进入 Known Gaps。

当前 Entity 中，`createdAt` 和 `updatedAt` 通过 MyBatis-Plus `FieldFill` 进行应用层填充：

- `createdAt`：`FieldFill.INSERT`；
- `updatedAt`：`FieldFill.INSERT_UPDATE`。

数据库层面的 `DEFAULT CURRENT_TIMESTAMP` 只能保证插入默认值，不会自动保证每次更新时刷新 `updated_at`。

------

# 7. Current Implementation Baseline

当前 `schema.sql` 实际定义 6 张表：

| 表名 | 当前用途 |
|---|---|
| `users` | 用户账户信息 |
| `accounts` | 用户资金账户 |
| `categories` | 收入 / 支出分类 |
| `transactions` | 日常财务流水 |
| `assets` | 投资资产 / 持仓 |
| `asset_prices` | 资产历史价格 |

当前 Java Entity 覆盖 5 张核心表：

| 表名 | Entity | Mapper |
|---|---|---|
| `users` | `User` | `UserMapper` |
| `accounts` | `Account` | `AccountMapper` |
| `categories` | `Category` | `CategoryMapper` |
| `transactions` | `Transaction` | `TransactionMapper` |
| `assets` | `Asset` | `AssetMapper` |
| `asset_prices` | 无 | 无 |

`asset_prices` 当前有数据库表，但没有 `AssetPrice` Entity，也没有 `AssetPriceMapper`。因此它目前是 schema 中存在但 Java 持久化层尚未覆盖的表。

当前 Mapper 基本基于 MyBatis-Plus `BaseMapper`：

- `UserMapper extends BaseMapper<User>`；
- `AccountMapper extends BaseMapper<Account>`；
- `CategoryMapper extends BaseMapper<Category>`；
- `AssetMapper extends BaseMapper<Asset>`；
- `TransactionMapper extends BaseMapper<Transaction>`。

`TransactionMapper` 当前额外包含聚合查询：

- `sumByType(userId, type)`；
- `sumByTypeAndDate(userId, type, start, end)`。

这些聚合查询服务于 Dashboard / Analytics 的读模型，但并不表示系统存在人工维护的统计表。

------

# 8. Core Table Design

## 8.1 users

`users` 表保存用户基础账户信息。

当前字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| `id` | `BIGSERIAL` | 是 | 自增 | 主键 |
| `username` | `VARCHAR(50)` | 是 | 无 | 用户名 |
| `email` | `VARCHAR(255)` | 是 | 无 | 邮箱 |
| `password_hash` | `VARCHAR(255)` | 是 | 无 | BCrypt 后的密码摘要 |
| `status` | `VARCHAR(20)` | 是 | `'ACTIVE'` | 用户状态；当前仍是 `VARCHAR`，尚未通过 `CHECK` 约束限制合法值 |
| `created_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 更新时间 |

当前约束：

- `id` 为主键；
- `username` 唯一；
- `email` 唯一；
- `password_hash` 非空；
- `status` 非空。

当前 Entity：

- `User`；
- `password_hash` 映射为 `passwordHash`；
- `created_at` 映射为 `createdAt`；
- `updated_at` 映射为 `updatedAt`。

目标设计说明：

- V1 不提供用户永久删除能力；
- 若后续支持停用账户，应继续通过 `status` 表达，而不是物理删除用户数据；
- `status` 的合法枚举值后续应明确约束。

## 8.2 accounts

`accounts` 表保存用户资金账户。

当前字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| `id` | `BIGSERIAL` | 是 | 自增 | 主键 |
| `user_id` | `BIGINT` | 是 | 无 | 所属用户 |
| `name` | `VARCHAR(100)` | 是 | 无 | 账户名称 |
| `type` | `VARCHAR(30)` | 是 | 无 | 账户类型 |
| `currency` | `VARCHAR(3)` | 是 | `'CNY'` | 币种 |
| `balance` | `DECIMAL(18,2)` | 是 | `0` | 账户余额 |
| `status` | `VARCHAR(20)` | 是 | `'ACTIVE'` | 账户状态；当前仍是 `VARCHAR`，尚未通过 `CHECK` 约束限制合法值 |
| `created_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 更新时间 |

当前约束：

- `id` 为主键；
- `user_id REFERENCES users(id)`；
- `type` 使用 `CHECK` 限制；
- `currency` 默认 `CNY`；
- `balance` 默认 `0`。

当前账户类型：

- `CASH`；
- `BANK`；
- `CREDIT_CARD`；
- `PAYMENT_PLATFORM`；
- `BROKERAGE`；
- `CRYPTO_WALLET`；
- `OTHER`。

当前索引：

- `idx_accounts_user_id(user_id)`。

目标设计说明：

- 账户必须归属某个用户；
- 存在历史流水的账户不应直接删除；
- 停用账户不允许新增流水，该规则主要由 Service 层保证；
- 投资资产应能关联投资账户，见 `assets.account_id` Known Gap。

## 8.3 categories

`categories` 表保存收入 / 支出分类。

当前字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| `id` | `BIGSERIAL` | 是 | 自增 | 主键 |
| `user_id` | `BIGINT` | 否 | 无 | 所属用户，系统分类可为空 |
| `name` | `VARCHAR(50)` | 是 | 无 | 分类名称 |
| `type` | `VARCHAR(10)` | 是 | 无 | 分类类型 |
| `parent_id` | `BIGINT` | 否 | 无 | 父分类 |
| `is_system` | `BOOLEAN` | 是 | `FALSE` | 是否系统分类 |
| `sort_order` | `INT` | 是 | `0` | 排序值 |
| `created_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 更新时间 |

当前约束：

- `id` 为主键；
- `user_id REFERENCES users(id)`，可为空；
- `parent_id REFERENCES categories(id)`，可为空；
- `type` 使用 `CHECK` 限制为 `INCOME` 或 `EXPENSE`；
- `is_system` 默认 `FALSE`；
- `sort_order` 默认 `0`。

当前索引：

- `idx_categories_user_id(user_id)`。

目标设计说明：

- 分类是小规模字典数据；
- Category 暂时不是分页设计重点；
- 系统分类禁止删除；
- 用户自定义分类允许新增、修改、删除，但有关联流水时不应直接删除；
- 系统分类与用户分类的可见性规则需要由 Service 层明确保证。

## 8.4 transactions

`transactions` 表保存用户日常财务流水。

当前字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| `id` | `BIGSERIAL` | 是 | 自增 | 主键 |
| `user_id` | `BIGINT` | 是 | 无 | 所属用户 |
| `account_id` | `BIGINT` | 是 | 无 | 所属账户 |
| `category_id` | `BIGINT` | 是 | 无 | 所属分类 |
| `type` | `VARCHAR(20)` | 是 | 无 | 流水类型 |
| `amount` | `DECIMAL(18,2)` | 是 | 无 | 金额 |
| `currency` | `VARCHAR(3)` | 是 | `'CNY'` | 币种 |
| `description` | `VARCHAR(500)` | 否 | 无 | 描述 / 备注 |
| `transacted_at` | `TIMESTAMP` | 是 | 无 | 交易发生时间 |
| `created_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 更新时间 |

当前约束：

- `id` 为主键；
- `user_id REFERENCES users(id)`；
- `account_id REFERENCES accounts(id)`；
- `category_id REFERENCES categories(id)`；
- `type` 使用 `CHECK` 限制。

当前流水类型：

- `INCOME`；
- `EXPENSE`；
- `TRANSFER`；
- `REFUND`；
- `ADJUSTMENT`。

数据库枚举范围包含 `TRANSFER` / `REFUND`，但当前后端 Transaction API 仅支持 `INCOME`、`EXPENSE`、`ADJUSTMENT`。完整转账 / 退款业务模型属于后续规划，不能视为当前已实现能力。

当前索引：

- `idx_transactions_user_id(user_id)`；
- `idx_transactions_account_id(account_id)`；
- `idx_transactions_category_id(category_id)`；
- `idx_transactions_transacted(transacted_at)`。
- `idx_transactions_user_currency_type_time(user_id, currency, type, transacted_at)`，用于 Dashboard 按当前用户、CNY、类型和时间范围聚合月度收支趋势。

当前已知限制：

- 当前数据库层只保证 `user_id`、`account_id`、`category_id` 分别引用合法记录；
- 当前数据库层尚未保证 `account_id` 所属用户与 `transactions.user_id` 一致；
- 当前数据库层尚未保证 `category_id` 所属用户与 `transactions.user_id` 一致；
- 当前转账模型只有一个 `account_id`，尚不能完整表达来源账户与目标账户；
- 金额方向规则尚未由数据库层强制保证。

目标设计说明：

- 每笔流水必须归属一个用户、一个账户和一个分类；
- 所有关联对象必须与当前用户一致；
- 收入、支出、转账、退款、余额调整的金额方向和业务含义由 `Financial Rules.md` 约束；
- 余额调整必须保留原因或备注，当前可先通过 `description` 表达；
- 复杂转账模型可在 Future Evolution 中演进。

## 8.5 assets

`assets` 表保存投资资产与持仓信息。

当前字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| `id` | `BIGSERIAL` | 是 | 自增 | 主键 |
| `user_id` | `BIGINT` | 是 | 无 | 所属用户 |
| `name` | `VARCHAR(100)` | 是 | 无 | 资产名称 |
| `symbol` | `VARCHAR(30)` | 否 | 无 | 资产代码 |
| `type` | `VARCHAR(20)` | 是 | 无 | 资产类型 |
| `market` | `VARCHAR(30)` | 否 | 无 | 市场 |
| `currency` | `VARCHAR(3)` | 是 | `'CNY'` | 币种 |
| `quantity` | `DECIMAL(18,8)` | 是 | `0` | 持仓数量 |
| `avg_cost` | `DECIMAL(18,4)` | 是 | `0` | 平均成本 |
| `current_price` | `DECIMAL(18,4)` | 否 | 无 | 当前价格 |
| `market_value` | `DECIMAL(18,2)` | 否 | 无 | 当前市值 |
| `created_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 更新时间 |

当前约束：

- `id` 为主键；
- `user_id REFERENCES users(id)`；
- `type` 使用 `CHECK` 限制；
- `currency` 默认 `CNY`；
- `quantity` 默认 `0`；
- `avg_cost` 默认 `0`。

当前资产类型：

- `STOCK`；
- `ETF`；
- `FUND`；
- `BOND`；
- `GOLD`；
- `CRYPTO`；
- `CASH`；
- `OTHER`。

当前索引：

- `idx_assets_user_id(user_id)`。

当前已知限制：

- `assets` 当前缺少 `account_id`；
- 当前结构无法表达某项资产属于哪个投资账户；
- 当前结构只能通过 `user_id` 表达用户归属；
- `market_value` 是派生字段，存在与 `quantity * current_price` 不一致的风险。

目标设计说明：

- `assets.account_id` 应关联 `accounts.id`；
- 资产应同时归属某个用户和某个投资账户；
- `account_id` 对应账户应属于同一 `user_id`；
- `market_value` 若继续持久化，必须由 Service 层保证与 `quantity`、`current_price` 的一致性；
- 后续也可以重新设计为实时计算字段，减少派生数据漂移风险。

## 8.6 asset_prices

`asset_prices` 表用于保存资产历史价格。它更接近市场参考数据，不属于用户私有业务数据，因此当前不包含 `user_id`。

当前字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| `id` | `BIGSERIAL` | 是 | 自增 | 主键 |
| `symbol` | `VARCHAR(30)` | 是 | 无 | 资产代码 |
| `price` | `DECIMAL(18,4)` | 是 | 无 | 价格 |
| `price_date` | `DATE` | 是 | 无 | 价格日期 |
| `created_at` | `TIMESTAMP` | 是 | `CURRENT_TIMESTAMP` | 创建时间 |

当前约束：

- `id` 为主键；
- `UNIQUE(symbol, price_date)`。

当前索引：

- `idx_asset_prices_symbol(symbol)`。

当前已知限制：

- 当前没有 `AssetPrice` Entity；
- 当前没有 `AssetPriceMapper`；
- 当前缺少 `updated_at`；
- 当前仅使用 `symbol` 区分资产价格，未包含 `market`、`currency` 或 `asset_id`；
- 相同 `symbol` 在不同市场或币种下可能产生歧义。

目标设计说明：

- 若 `asset_prices` 进入正式行情能力，应补齐 Java Entity 和 Mapper；
- 价格数据应具备可追溯性；
- 后续可评估是否以 `asset_id`、`symbol + market + currency` 或其他组合建立价格归属关系。

------

# 9. Relationship Model

当前核心关系如下：

```text
users
  ├── accounts
  ├── categories
  ├── transactions
  └── assets

accounts
  └── transactions

categories
  ├── categories(parent_id)
  └── transactions

asset_prices
  └── currently linked by symbol only
```

当前数据库层已经存在的外键关系：

- `accounts.user_id -> users.id`；
- `categories.user_id -> users.id`；
- `categories.parent_id -> categories.id`；
- `transactions.user_id -> users.id`；
- `transactions.account_id -> accounts.id`；
- `transactions.category_id -> categories.id`；
- `assets.user_id -> users.id`。

目标关系中应补齐：

- `assets.account_id -> accounts.id`。

需要注意的是，当前数据库层没有复合外键或其他机制保证：

- `transactions.account_id` 对应的账户属于 `transactions.user_id`；
- `transactions.category_id` 对应的分类属于 `transactions.user_id`；
- 未来 `assets.account_id` 对应的账户属于 `assets.user_id`。

这些一致性当前必须由 Service 层保证，后续可评估是否通过复合唯一约束、复合外键或更严格的数据访问策略增强。

------

# 10. User Data Isolation

Personal Finance OS 是个人财务管理系统。所有业务数据必须围绕 `user_id` 做用户数据隔离。

当前包含 `user_id` 的表：

- `accounts`；
- `categories`；
- `transactions`；
- `assets`。

当前不包含 `user_id` 的表：

- `users`：自身即用户主体；
- `asset_prices`：当前按 `symbol` 保存历史价格，更接近市场参考数据，不属于用户私有业务数据。

系统分类是用户数据隔离规则中的明确例外：当 `categories.user_id` 为 `NULL` 且 `is_system = TRUE` 时，表示全局系统分类。系统分类可被多个用户读取，但普通用户不得修改或删除。

用户数据隔离规则：

1. 普通用户只能访问自己的数据；
2. 查询账户、流水、分类、资产时必须带当前用户上下文；
3. 创建业务数据时必须写入当前用户 `user_id`；
4. 跨表读取时不得只依赖单个外键存在性，必须校验用户归属；
5. Dashboard / Analytics 聚合查询必须基于当前用户数据计算。

当前数据库层只能提供基础外键完整性。用户隔离的最终执行责任在 Service 层和安全上下文。

------

# 11. Amount and Precision Rules

金额、价格、数量遵循 `Financial Rules.md`。

核心规则：

- SQL 层使用 `DECIMAL`；
- Java 层使用 `BigDecimal`；
- 禁止使用 `float` 或 `double` 参与金融计算；
- 金额默认保留两位小数；
- 资产数量、成本、价格可使用更高精度；
- 所有最终展示和统计结果必须基于真实业务数据。

当前精度设计：

| 字段 | 类型 | 说明 |
|---|---|---|
| `accounts.balance` | `DECIMAL(18,2)` | 账户余额 |
| `transactions.amount` | `DECIMAL(18,2)` | 流水金额 |
| `assets.quantity` | `DECIMAL(18,8)` | 持仓数量 |
| `assets.avg_cost` | `DECIMAL(18,4)` | 平均成本 |
| `assets.current_price` | `DECIMAL(18,4)` | 当前价格 |
| `assets.market_value` | `DECIMAL(18,2)` | 当前市值 |
| `asset_prices.price` | `DECIMAL(18,4)` | 历史价格 |

当前数据库层尚未完整表达金额方向规则，例如：

- 收入为正；
- 支出为负；
- 转账不影响总资产；
- 退款冲减对应支出；
- 余额调整必须保留原因。

这些规则当前应由 Service 层根据 `Financial Rules.md` 统一执行。

------

# 12. Constraint Strategy

当前数据库约束策略以基础完整性为主。

## 12.1 已实现约束

主键：

- 所有当前表均使用 `id BIGSERIAL PRIMARY KEY`。

唯一约束：

- `users.username UNIQUE`；
- `users.email UNIQUE`；
- `asset_prices UNIQUE(symbol, price_date)`。

外键约束：

- 已为用户、账户、分类、流水、资产建立基础外键。

检查约束：

- `accounts.type`；
- `categories.type`；
- `transactions.type`；
- `assets.type`。

非空约束：

- 核心必填字段均设置 `NOT NULL`。

## 12.2 当前未实现但目标上需要关注的约束

当前尚未实现：

- `assets.account_id` 外键；
- `transactions.account_id` 与 `transactions.user_id` 的用户归属一致性；
- `transactions.category_id` 与 `transactions.user_id` 的用户归属一致性；
- `assets.account_id` 与 `assets.user_id` 的用户归属一致性；
- `status` 字段枚举约束；
- 金额方向约束；
- 非负数量、非负价格等金融合法性约束；
- 系统分类禁止删除的数据库级约束；
- 账户停用后禁止新增流水的数据库级约束。

当前策略是：

- 数据库保证基础结构合法；
- Service 层保证业务语义合法；
- 后续根据复杂度评估是否将部分业务约束下沉到数据库。

------

# 13. Index Strategy

当前索引主要围绕用户隔离、外键查询和基础聚合查询建立。

当前索引：

| 表名 | 索引 |
|---|---|
| `accounts` | `idx_accounts_user_id(user_id)` |
| `categories` | `idx_categories_user_id(user_id)` |
| `transactions` | `idx_transactions_user_id(user_id)` |
| `transactions` | `idx_transactions_account_id(account_id)` |
| `transactions` | `idx_transactions_category_id(category_id)` |
| `transactions` | `idx_transactions_transacted(transacted_at)` |
| `transactions` | `idx_transactions_user_currency_type_time(user_id, currency, type, transacted_at)` |
| `assets` | `idx_assets_user_id(user_id)` |
| `asset_prices` | `idx_asset_prices_symbol(symbol)` |

当前索引可以支持：

- 按用户查询账户；
- 按用户查询分类；
- 按用户查询流水；
- 按账户查询流水；
- 按分类查询流水；
- 按时间查询流水；
- 按当前用户、CNY、收入/支出类型和时间范围聚合 Dashboard 月度收支趋势；
- 按用户查询资产；
- 按 symbol 查询历史价格。

后续可评估的索引：

- `transactions(user_id, type)`；
- `transactions(user_id, transacted_at)`；
- `transactions(user_id, type, transacted_at)`；
- `accounts(user_id, status)`；
- `assets(user_id, type)`；
- `assets(user_id, account_id)`，前提是补齐 `account_id`；
- `asset_prices(symbol, price_date)` 已有唯一约束，通常可支持相关查询。

`asset_prices` 的 `UNIQUE(symbol, price_date)` 通常已经可以支持 `symbol + price_date` 查询，不需要额外重复创建相同组合的普通索引。

Category 当前是小规模字典数据，分类分页不是当前数据库设计重点。分类查询应优先保持简单，除非后续分类规模或查询模式发生明显变化。

------

# 14. Derived Data and Consistency

系统中存在部分可计算数据。此类数据必须明确来源、更新责任和一致性策略。

## 14.1 Dashboard / Analytics

Dashboard / Analytics 不保存人工统计结果。

V1 中统计数据应通过以下真实业务数据计算得出：

- `accounts.balance`；
- `transactions.amount`；
- `transactions.type`；
- `transactions.transacted_at`；
- `assets.quantity`；
- `assets.avg_cost`；
- `assets.current_price`；
- `assets.market_value`。

禁止：

- 手工维护 Dashboard 统计表；
- AI 修改统计结果；
- 前端绕过后端直接计算最终金融结果；
- 为了展示方便写入不可追溯的统计数据。

## 14.2 market_value

`assets.market_value` 当前是持久化字段，但它本质上是派生字段。

通常情况下：

```text
market_value = quantity * current_price
```

当前风险：

- `quantity` 更新后，`market_value` 可能未同步；
- `current_price` 更新后，`market_value` 可能未同步；
- 手动写入 `market_value` 可能导致与真实持仓不一致。

当前要求：

- 如果继续持久化 `market_value`，必须由 Service 层统一维护；
- 不应允许多个模块各自计算并写入；
- 后续可评估将 `market_value` 改为实时计算结果，减少一致性风险。

------

# 15. Known Gaps

当前已知差距如下。

## 15.1 assets 缺少 account_id

当前 `assets` 表没有 `account_id` 字段。

这与业务规则中“每项资产必须属于一个用户、一个投资账户、一个币种”的要求不完全一致。

目标设计中：

- `assets.account_id` 应关联 `accounts.id`；
- `assets.account_id` 对应账户应属于同一 `user_id`；
- 投资账户类型可优先使用 `BROKERAGE`、`CRYPTO_WALLET` 或其他后续明确的账户类型。

## 15.2 asset_prices 有表但无 Entity / Mapper

当前 `asset_prices` 存在于 `schema.sql`，但 Java 侧没有：

- `AssetPrice` Entity；
- `AssetPriceMapper`。

因此当前代码无法通过 MyBatis-Plus 标准 Entity / Mapper 访问该表。

## 15.3 asset_prices 缺少 updated_at

`asset_prices` 当前只有 `created_at`，没有 `updated_at`。

这与当前 schema 文件头部“all tables include id/created_at/updated_at”的约定不完全一致。

## 15.4 transactions 用户归属一致性未由数据库层保证

当前 `transactions` 有：

- `user_id`；
- `account_id`；
- `category_id`。

数据库层目前只保证这些字段引用的记录存在，但没有保证：

- `account_id` 属于同一个 `user_id`；
- `category_id` 属于同一个 `user_id`。

该一致性当前必须由 Service 层保证。

## 15.5 market_value 派生数据一致性风险

`assets.market_value` 可能与 `quantity * current_price` 不一致。

该问题需要通过 Service 层统一维护，或在后续版本中重新设计。

## 15.6 logic-delete 配置与表结构不匹配

当前 `application.yml` 配置了：

- `logic-delete-field: deleted`；
- `logic-delete-value: 1`；
- `logic-not-delete-value: 0`。

但当前所有表均没有 `deleted` 字段。

因此当前不能把逻辑删除视为已实现能力。

## 15.7 转账模型仍较简化

当前 `transactions` 只有一个 `account_id`。

这对于收入、支出、退款、调整较直接，但对转账场景不足以完整表达来源账户与目标账户。

更完整的转账模型应在后续版本中评估。

## 15.8 金融业务约束未完全下沉到数据库

当前数据库层尚未完整约束：

- 金额正负方向；
- 非法金额；
- 非法持仓数量；
- 卖出数量不得超过持仓；
- 余额调整原因；
- 停用账户禁止新增流水；
- 系统分类禁止删除。

这些规则当前由 Service 层根据 `Business Rules.md` 和 `Financial Rules.md` 保证。

------

# 16. Future Evolution

后续数据库演进方向如下。

## 16.1 Migration 管理

当前仍使用 `schema.sql` 初始化数据库。`spring.sql.init.mode: always` 适合当前开发阶段初始化 schema，但正式部署或生产环境应迁移到 Flyway / Liquibase，避免依赖 `schema.sql` 反复初始化数据库结构。

后续可引入：

- Flyway；
- Liquibase。

目标是让数据库结构演进可追踪、可回滚、可审查，而不是依赖手工修改初始化脚本。

## 16.2 asset account_id 修正

后续应补齐：

- `assets.account_id`；
- `assets.account_id -> accounts.id` 外键；
- `Asset` Entity 中的 `accountId` 字段；
- `assets(user_id, account_id)` 相关索引；
- Service 层资产账户归属校验。

## 16.3 AssetPrice Entity / Mapper

如果 `asset_prices` 进入正式能力，应补齐：

- `AssetPrice` Entity；
- `AssetPriceMapper`；
- 价格查询 Service；
- `updated_at` 字段；
- 更明确的价格归属模型。

## 16.4 汇率表

V1 当前不实现自动汇率。

后续多币种能力可评估新增汇率表，例如记录：

- 基准币种；
- 目标币种；
- 汇率；
- 来源；
- 生效日期；
- 更新时间。

## 16.5 审计日志

后续可增加审计日志记录关键操作，例如：

- 创建账户；
- 修改余额；
- 创建 / 修改 / 删除流水；
- 修改资产持仓；
- 修改价格数据。

V1 当前不实现完整审计日志。

## 16.6 软删除

后续可评估为部分表增加软删除能力。

优先候选：

- `transactions`；
- `accounts`；
- `categories`；
- `assets`。

在正式引入前，不应仅依赖当前 `logic-delete-field: deleted` 配置，因为当前表结构并没有 `deleted` 字段。

## 16.7 投资交易流水

当前 `assets` 更接近持仓快照。

后续可引入投资交易流水，用于表达：

- 买入；
- 卖出；
- 分红；
- 手续费；
- 税费；
- 拆股；
- 合股；
- 成本重算。

V1 当前不实现复杂投资交易流水。

## 16.8 更完整的转账模型

后续可评估改进转账表达方式，例如：

- 使用 `from_account_id` 和 `to_account_id`；
- 使用成组流水；
- 使用 transfer group / correlation id；
- 明确手续费和汇率差额。

V1 当前保留简化模型。

## 16.9 AI 分析结果表

AI 分析结果表属于 v4.0 AI Finance Assistant 后续规划，当前不进入 v1.0 / v1.1。

未来 AI 财务助手不应修改金融数据，不应替代系统进行最终金额计算，也不应成为真实业务数据来源。

如后续引入 AI 分析结果持久化，应明确：

- 输入数据范围；
- 生成时间；
- 模型来源；
- 结果有效期；
- 用户可见性；
- 不可作为账务真实数据。

------

# 17. Review Checklist

Database.md 后续 Review 应检查以下事项。

## 17.1 与架构约束一致

- 是否保持模块化单体架构；
- 是否未引入数据库驱动的跨模块业务流程；
- 是否未让 Dashboard / Analytics 保存人工统计结果；
- 是否未让 AI 修改金融数据；
- 是否未越界设计 API 或 Java 实现。

## 17.2 与业务规则一致

- 是否保证所有业务数据围绕 `user_id`；
- 是否明确普通用户只能访问自己的数据；
- 是否明确账户、分类、流水、资产的归属关系；
- 是否明确系统分类保护；
- 是否明确停用账户不得新增流水；
- 是否明确 V1 不提供用户永久删除。

## 17.3 与金融规则一致

- 是否使用 `DECIMAL / BigDecimal`；
- 是否禁止 `float / double` 参与金融计算；
- 是否明确金额、数量、价格精度；
- 是否明确 `market_value` 的派生数据风险；
- 是否明确统计结果来自真实业务数据；
- 是否未提前实现复杂成本、汇率或投资交易规则。

## 17.4 与当前实现一致

- 是否明确当前 6 张表；
- 是否明确当前 5 个 Entity；
- 是否明确 `asset_prices` 无 Entity / Mapper；
- 是否明确 `assets` 缺少 `account_id`；
- 是否明确 `asset_prices` 缺少 `updated_at`；
- 是否明确逻辑删除配置与表结构不匹配；
- 是否未把未来能力写成当前已实现。

## 17.5 后续演进清晰

- 是否明确 Flyway / Liquibase 作为后续 migration 方向；
- 是否明确 `assets.account_id` 修正方向；
- 是否明确 `AssetPrice` Entity / Mapper 补齐方向；
- 是否明确汇率表、审计日志、软删除、投资交易流水、完整转账模型为 Future Evolution；
- 是否明确 AI 分析结果表暂不进入 V1。

------

# Review Conclusion

当前 Database.md 第一版草稿记录了 Personal Finance OS 当前数据库实现基线，并明确区分 Current Implementation、Target Design、Known Gaps 和 Future Evolution。

本文档可作为后续数据库 Review、schema 修正、migration 规划和 API 设计的上游依据，但不直接替代 `schema.sql`，也不代表本文档中提到的 Target Design 已经实现。
