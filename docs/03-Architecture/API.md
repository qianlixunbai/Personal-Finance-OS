# API（接口设计基线）

**项目名称：** Personal Finance OS

**版本：** v1.0

**状态：** Draft

**分支：** zh-cn

**日期：** 2026-07-07

------

# 1. Purpose

本文档用于记录 Personal Finance OS 当前后端 API 实现基线、统一响应规范、认证规则、分页规则、错误处理、前端调用现状、Known Gaps、v1.0 Target Design 以及 Future Evolution。

本文档不是 OpenAPI / Swagger 文档，也不要求立即补实现。未实现能力不得写成已实现能力。

------

# 2. Scope

## 2.1 In Scope

- 当前后端 Controller 已暴露的 API
- 当前 Request DTO / Response DTO
- `ApiResponse<T>` 统一响应格式
- `PageResult<T>` 分页响应格式
- `BusinessException` 与 `GlobalExceptionHandler`
- Spring Security / JWT 认证规则
- 当前前端 `frontend/src/api` 和页面中的实际 API 调用
- API 与 `Architecture.md`、`Database.md`、`Business Rules.md`、`Financial Rules.md` 的一致性
- Current Implementation、Target v1.0 API Design、Known Gaps、Future Evolution

## 2.2 Out of Scope

- Java / Controller / DTO / Service 实现修改
- 前端代码修改
- `schema.sql`、`Architecture.md`、`Database.md` 修改
- OpenAPI / Swagger 配置
- 新接口实现

------

# 3. API Design Principles

1. API 服务于模块化单体架构，不引入微服务边界。
2. Controller 只负责请求入口、参数接收、参数校验和统一返回，不承载业务规则。
3. 业务规则和金融计算必须位于后端 Service 层，前端不得执行最终金融计算。
4. 对外返回使用 DTO，不直接返回 Entity。
5. 普通业务数据必须围绕当前登录用户进行隔离。
6. Dashboard 数据必须来源于真实业务数据，不人工维护统计结果。
7. AI 不得修改金融数据、账户余额、资产持仓或流水数据。
8. Current Implementation、Target Design、Future Evolution 必须明确区分。

------

# 4. Base URL and Versioning

当前 API base path 为：

```text
/api/v1
```

当前前端 axios `baseURL` 也是：

```text
/api/v1
```

当前版本策略：

- `v1` 表示当前 v1.0 API 基线；
- 当前没有多版本并行机制；
- 如后续出现不兼容 API 变更，应优先通过新版本路径或兼容字段演进处理。

------

# 5. Authentication and Authorization

当前认证方式为 JWT。

公开接口：

- `POST /api/v1/register`
- `POST /api/v1/login`

除上述公开接口外，其他业务接口默认需要认证。

客户端通过 HTTP Header 传递 token：

```http
Authorization: Bearer <token>
```

当前认证流程：

1. 用户登录成功后，后端返回 `LoginResponse(token, userId, username)`。
2. 前端将 token 保存到 `localStorage`。
3. 前端 axios request interceptor 自动注入 `Authorization: Bearer <token>`。
4. `JwtAuthFilter` 解析 token。
5. token subject 被解析为 `userId`。
6. 后端查询用户，只有用户存在且 `status == ACTIVE` 时才写入 Spring Security `Authentication`。
7. Controller 当前通过 `Authentication principal` 获取 `userId`。

当前权限模型：

- 当前无角色模型；
- 当前无管理员权限；
- 当前无细粒度权限模型；
- 普通用户只能通过 Service 层的 `userId` 限制访问自己的数据。

------

# 6. Unified Response Format

当前统一响应模型为 `ApiResponse<T>`。

成功响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | `int` | 业务响应码。当前成功通常为 `200`。 |
| `message` | `String` | 响应消息。成功为 `success`。 |
| `data` | `T` | 响应数据。 |

当前实现说明：

- `ApiResponse.ok(data)` 返回 `code = 200`、`message = "success"` 和 `data`；
- `ApiResponse.ok()` 返回 `code = 200`、`message = "success"` 和 `data = null`；
- `ApiResponse.error(code, message)` 返回错误响应；
- `ApiResponse` 使用 `@JsonInclude(JsonInclude.Include.NON_NULL)`；
- 当 `data == null` 时，响应 JSON 中可能不输出 `data` 字段；
- `BusinessException` 的 `code` 与 HTTP status 已做基本映射。

------

# 7. Pagination

当前分页响应模型为 `PageResult<T>`。

响应示例：

```json
{
  "records": [],
  "total": 0,
  "page": 1,
  "size": 20
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `records` | `List<T>` | 当前页数据。 |
| `total` | `long` | 总记录数。 |
| `page` | `int` | 当前页码。 |
| `size` | `int` | 当前页大小。 |

当前分页规则：

- `page < 1` 修正为 `1`；
- `size < 1` 修正为 `1`；
- `size > 100` 修正为 `100`；
- `Account`、`Asset`、`Transaction / Ledger` 已提供分页接口；
- 原列表接口仍保留，用于兼容部分前端选项加载场景；
- 当前前端已接入 `Account`、`Asset`、`Transaction / Ledger` 主列表分页接口。

------

# 8. Error Handling

当前业务异常模型为 `BusinessException`。

`BusinessException` 包含：

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | `int` | 业务错误码。 |
| `message` | `String` | 错误消息。 |

当前 `BusinessException` 到 HTTP Status 的映射：

| `BusinessException.code` | HTTP Status |
|---:|---|
| `400` | `400 Bad Request` |
| `401` | `401 Unauthorized` |
| `403` | `403 Forbidden` |
| `404` | `404 Not Found` |
| 其他 | `400 Bad Request` |

当前错误响应覆盖：

- `BusinessException` 按业务错误码映射 HTTP Status，并返回 `ApiResponse.error(code, message)`；
- 参数校验异常已统一返回 `HTTP 400 + ApiResponse.error`，包括 `MethodArgumentNotValidException` 和 `ConstraintViolationException`；
- 请求参数缺失、参数类型错误、请求体格式错误已统一返回 `HTTP 400 + ApiResponse.error`，包括 `MissingServletRequestParameterException`、`MethodArgumentTypeMismatchException` 和 `HttpMessageNotReadableException`；
- Spring Security 未认证 `401` 返回统一 JSON 响应；
- Spring Security 无权限 `403` 返回统一 JSON 响应；
- 未知异常统一返回 `HTTP 500 + ApiResponse.error(500, "系统异常")`。

当前已知限制：

- 当前错误码体系较简单，暂无稳定 `ErrorCode` 枚举；
- 当前错误消息为字符串，尚未建立国际化错误消息体系。

------

# 9. Current Implementation Baseline

本节只记录当前后端已经实现的 API。

本节只记录当前 Controller 已实现接口，未出现在 Controller 中的接口不得写入本节。

## 9.1 Auth APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `POST` | `/api/v1/register` | 否 | Body: `RegisterRequest(username, email, password)` | `ApiResponse<Void>` | 注册用户。`username` 和 `email` 需要唯一，密码使用 BCrypt 加密保存。 |
| `POST` | `/api/v1/login` | 否 | Body: `LoginRequest(username, password)` | `ApiResponse<LoginResponse>` | 登录成功后返回 `token`、`userId`、`username`。仅 `ACTIVE` 用户可登录。 |

## 9.2 Account APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `GET` | `/api/v1/accounts` | 是 | 无 | `ApiResponse<List<AccountResponse>>` | 查询当前用户账户列表。 |
| `GET` | `/api/v1/accounts/page` | 是 | Query: `page`, `size` | `ApiResponse<PageResult<AccountResponse>>` | 分页查询当前用户账户列表。 |
| `GET` | `/api/v1/accounts/{id}` | 是 | Path: `id` | `ApiResponse<AccountResponse>` | 查询当前用户指定账户。非当前用户账户返回 `404`。 |
| `POST` | `/api/v1/accounts` | 是 | Body: `AccountRequest(name, type, currency)` | `ApiResponse<AccountResponse>` | 创建账户。默认 `currency` 为 `CNY`，初始 `balance` 为 `0`。 |
| `PUT` | `/api/v1/accounts/{id}` | 是 | Path: `id`; Body: `AccountRequest` | `ApiResponse<AccountResponse>` | 更新账户基础信息。 |
| `POST` | `/api/v1/accounts/{id}/deactivate` | 是 | Path: `id` | `ApiResponse<Void>` | 停用账户，将 `status` 更新为 `INACTIVE`。 |

当前说明：

- 账户 API 使用 `Authentication principal` 获取当前 `userId`；
- 查询、详情、更新和停用均校验账户归属；
- V1.x 创建和更新账户时 `currency` 只允许 `CNY`，非 `CNY` 返回 HTTP `400`；
- 当前未提供账户删除 API；
- 当前创建账户时不允许客户端直接设置 `balance`。

## 9.3 Category APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `GET` | `/api/v1/categories` | 是 | Query: `type?` | `ApiResponse<List<CategoryResponse>>` | 查询当前用户分类和系统分类，可按 `type` 过滤。 |
| `POST` | `/api/v1/categories` | 是 | Body: `CategoryRequest(name, type, parentId, sortOrder)` | `ApiResponse<CategoryResponse>` | 创建当前用户自定义分类。 |
| `POST` | `/api/v1/categories/init` | 是 | 无 | `ApiResponse<Void>` | 幂等初始化系统分类。当前保留为开发/兼容入口，不是推荐演示路径。 |

当前说明：

- 分类查询包括当前用户分类和 `isSystem = true` 的系统分类；
- 系统分类采用全局模型：`userId = null`、`isSystem = true`，应用启动时会幂等初始化默认收入/支出分类；
- `type` 当前支持 `INCOME`、`EXPENSE`；
- 当前没有分类更新 API；
- 当前没有分类删除 API；
- `/categories/init` 当前仅作为开发/兼容入口保留；Fresh DB 演示路径依赖应用启动初始化，不需要用户手工调用该接口。

## 9.4 Asset APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `GET` | `/api/v1/assets` | 是 | 无 | `ApiResponse<List<AssetResponse>>` | 查询当前用户资产列表。 |
| `GET` | `/api/v1/assets/page` | 是 | Query: `page`, `size` | `ApiResponse<PageResult<AssetResponse>>` | 分页查询当前用户资产列表。 |
| `GET` | `/api/v1/assets/{id}` | 是 | Path: `id` | `ApiResponse<AssetResponse>` | 查询当前用户指定资产。非当前用户资产返回 `404`。 |
| `POST` | `/api/v1/assets` | 是 | Body: `AssetRequest(name, symbol, type, market, currency, quantity, avgCost)` | `ApiResponse<AssetResponse>` | 创建资产持仓记录。 |
| `PUT` | `/api/v1/assets/{id}/price` | 是 | Path: `id`; Query: `price` | `ApiResponse<AssetResponse>` | 更新当前价格，并计算 `marketValue`。 |
| `PUT` | `/api/v1/assets/{id}/close` | 是 | Path: `id` | `ApiResponse<AssetResponse>` | 将当前用户资产持仓快照清仓，`quantity` 归零。 |
| `DELETE` | `/api/v1/assets/{id}` | 是 | Path: `id` | `ApiResponse<Void>` | 删除资产。当前有持仓数量时拒绝删除。 |

当前说明：

- 资产 API 使用 `Authentication principal` 获取当前 `userId`；
- 查询、详情、更新价格、清仓和删除均校验资产归属；
- V1.x 创建资产时 `currency` 只允许 `CNY`，非 `CNY` 返回 HTTP `400`；
- `quantity` 和 `avgCost` 使用 `BigDecimal`；
- `updatePrice` 要求 `price > 0`；
- `close` 只将资产快照中的 `quantity` 归零，不修改现金账户余额，不生成流水，不计算实现盈亏；
- `profitLoss` 和 `profitLossRate` 在后端计算；
- 当前 `AssetRequest` 不包含 `accountId`；
- 当前没有完整资产更新 API；
- 当前没有资产价格历史 API。

## 9.5 Dashboard APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `GET` | `/api/v1/dashboard` | 是 | 无 | `ApiResponse<DashboardDto>` | 获取当前用户 Dashboard 聚合数据。 |

当前说明：

- Dashboard 当前聚合账户余额、资产市值、月收入、月支出、最近流水；
- Dashboard 当前只聚合基础币种 `CNY` 的账户、资产和流水数据，不执行汇率换算；
- Dashboard 不保存人工统计结果；
- Dashboard 通过 `AccountQueryService`、`AssetQueryService`、`CategoryQueryService`、`TransactionQueryService` 读取真实业务数据；
- 当前 `netWorth = totalAssets`，v1 暂无负债模型；
- 最近流水返回基础字段，并补充当前用户可见范围内的 `category` 和 `account` 展示名称；找不到可见名称时返回 `未知分类` 或 `未知账户`。

## 9.6 Transaction / Ledger APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `GET` | `/api/v1/transactions/page` | 是 | Query: `page`, `size`, `accountId?`, `categoryId?`, `type?`, `start?`, `end?` | `ApiResponse<PageResult<TransactionResponse>>` | 分页查询当前用户流水，默认按 `transactedAt DESC, id DESC` 排序。 |
| `GET` | `/api/v1/transactions/{id}` | 是 | Path: `id` | `ApiResponse<TransactionResponse>` | 查询当前用户指定流水。不存在或不属于当前用户返回 `404`。 |
| `POST` | `/api/v1/transactions` | 是 | Body: `TransactionRequest(accountId, categoryId, type, amount, currency, description, transactedAt)` | `ApiResponse<TransactionResponse>` | 创建流水，并联动账户余额。 |
| `PUT` | `/api/v1/transactions/{id}` | 是 | Path: `id`; Body: `TransactionRequest` | `ApiResponse<TransactionResponse>` | 更新流水，先回滚旧流水余额影响，再应用新流水余额影响。 |
| `DELETE` | `/api/v1/transactions/{id}` | 是 | Path: `id` | `ApiResponse<Void>` | 删除流水，并回滚旧流水对账户余额的影响。当前 V1 为物理删除。 |

当前支持的流水类型：

- `INCOME`
- `EXPENSE`
- `ADJUSTMENT`

当前暂不支持的流水类型：

- `TRANSFER`
- `REFUND`

当请求或历史数据中的流水类型为 `TRANSFER` / `REFUND` 时，当前 Transaction API 返回 `BusinessException(400, "当前版本暂不支持该流水类型")`，不允许查询详情、更新、删除或按该类型分页查询。

流水金额存储与账户余额联动规则：

- `INCOME`: 请求 `amount > 0`，数据库保存正数，`balance += amount`；
- `EXPENSE`: 请求 `amount > 0`，数据库保存正数，`balance -= amount`；前端展示时可以显示为负数；
- `ADJUSTMENT`: 请求 `amount != 0`，数据库保存有符号金额，`balance += amount`，且 `description` 必填；
- `update` 会先回滚旧流水影响，再应用新流水影响；
- `delete` 会回滚旧流水影响；
- `create`、`update`、`delete` 写操作使用 `@Transactional`，保证流水与账户余额在同一事务中提交或回滚。

当前校验规则：

- 账户必须存在且属于当前用户；
- 账户 `status` 必须为 `ACTIVE`；
- 分类必须存在，且属于当前用户或为系统分类；
- `INCOME` 只能使用 `INCOME` 分类；
- `EXPENSE` 只能使用 `EXPENSE` 分类；
- `ADJUSTMENT` 当前不强制匹配分类 `type`，但必须填写 `description`；
- `currency` 为空时默认 `CNY`；V1.x 非 `CNY` 输入返回 HTTP `400`；
- 流水币种必须与所属账户币种一致，否则返回 HTTP `400`。

------

# 10. Frontend Usage Baseline

当前前端 API 客户端位于：

```text
frontend/src/api/index.ts
```

当前配置：

- axios `baseURL` 为 `/api/v1`；
- request interceptor 从 `localStorage` 读取 `token`；
- 若 token 存在，自动注入 `Authorization: Bearer <token>`；
- response interceptor 仅在受保护接口返回 HTTP `401` 且本地存在 token 时删除 token 并跳转 `/login`；`/login`、`/register` 等公开认证请求不触发跳转，已位于登录页时不重复重定向。

当前前端实际调用的接口：

| 前端调用 | 完整后端路径 | 页面 |
|---|---|---|
| `POST /login` | `POST /api/v1/login` | `Login.tsx` |
| `POST /register` | `POST /api/v1/register` | `Register.tsx` |
| `GET /dashboard` | `GET /api/v1/dashboard` | `Dashboard.tsx` |
| `GET /accounts/page?page=...&size=...` | `GET /api/v1/accounts/page?page=...&size=...` | `Accounts.tsx` |
| `POST /accounts` | `POST /api/v1/accounts` | `Accounts.tsx` |
| `POST /accounts/{id}/deactivate` | `POST /api/v1/accounts/{id}/deactivate` | `Accounts.tsx` |
| `GET /assets/page?page=...&size=...` | `GET /api/v1/assets/page?page=...&size=...` | `Assets.tsx` |
| `POST /assets` | `POST /api/v1/assets` | `Assets.tsx` |
| `GET /assets/{id}` | `GET /api/v1/assets/{id}` | `Assets.tsx` |
| `PUT /assets/{id}/price?price=...` | `PUT /api/v1/assets/{id}/price?price=...` | `Assets.tsx` |
| `PUT /assets/{id}/close` | `PUT /api/v1/assets/{id}/close` | `Assets.tsx` |
| `DELETE /assets/{id}` | `DELETE /api/v1/assets/{id}` | `Assets.tsx` |
| `GET /transactions/page?page=...&size=...` | `GET /api/v1/transactions/page?page=...&size=...` | `Transactions.tsx` |
| `POST /transactions` | `POST /api/v1/transactions` | `Transactions.tsx` |
| `PUT /transactions/{id}` | `PUT /api/v1/transactions/{id}` | `Transactions.tsx` |
| `DELETE /transactions/{id}` | `DELETE /api/v1/transactions/{id}` | `Transactions.tsx` |
| `GET /accounts` | `GET /api/v1/accounts` | `Transactions.tsx` |
| `GET /categories` | `GET /api/v1/categories` | `Transactions.tsx` |

当前后端已实现但前端尚未调用的接口包括：

- `GET /api/v1/accounts/{id}`
- `PUT /api/v1/accounts/{id}`
- `GET /api/v1/categories`
- `POST /api/v1/categories`
- `POST /api/v1/categories/init`

说明：

- `Transactions.tsx` 已接入 Transaction / Ledger API，使用 `/transactions/page` 分页查询，并支持创建、编辑、删除。
- `Accounts.tsx` 已接入账户分页、创建、编辑和停用操作。
- `Assets.tsx` 已接入资产分页、创建、详情、更新价格、清仓和删除操作；其中清仓只是持仓快照归零，不等于完整卖出交易模型。
- 当前前端尚未接入分类管理页面。

------

# 11. Consistency with Architecture / Database / Business Rules / Financial Rules

## 11.1 与 Architecture.md 的一致性

当前 API 基本符合架构约束：

- 采用前后端分离；
- 后端 API 位于 Spring Boot 应用内；
- Controller 返回 DTO 和统一响应；
- Controller 没有直接返回 Entity；
- 业务逻辑主要位于 Service 层；
- Dashboard 作为只读聚合接口，不反向影响核心业务模块。

需要注意：

- `/categories/init` 更像开发初始化能力，不适合作为长期普通业务 API；
- Transaction / Ledger 写操作已落在 Service 事务中；
- Dashboard 当前依赖多个 Query Service，后续需要继续保持只读聚合边界。

## 11.2 与 Database.md 的一致性

一致点：

- 当前 API 覆盖 `users`、`accounts`、`categories`、`assets`、`transactions` 的主要访问能力；
- 当前 API 对账户、分类、资产、流水均围绕 `userId` 做用户隔离；
- 当前 API 使用 `BigDecimal` 表达金额、价格、数量；
- Transaction / Ledger API 已按 `transactions` 表设计落地基础 CRUD；
- 当前资产 API 与 Database.md 共同记录了 `assets.account_id` 缺失这一 Known Gap。

不完整点：

- 完整 `TRANSFER` / `REFUND` 模型暂未实现；
- 当前没有 AssetPrice API；
- 当前没有 `AssetPrice` Entity / Mapper；
- 当前 Asset API 缺少 `accountId`；
- 当前 `asset_prices` 表存在，但 API 层尚未开放。

## 11.3 与 Business Rules.md 的一致性

一致点：

- 注册检查用户名和邮箱唯一；
- 登录后才能访问个人业务数据；
- 普通用户只能访问自己的账户、资产和流水；
- 账户不提供删除接口，提供停用接口；
- 停用账户不允许新增流水；
- Dashboard 数据来源于真实业务数据；
- AI 相关接口当前未实现，因此不存在 AI 修改金融数据的问题。

不完整点：

- 分类规则中的更新、删除尚未实现；
- 资产规则中的完整更新尚未实现；
- 资产必须属于投资账户的规则当前因缺少 `accountId` 尚未完整落地；
- 完整转账和退款业务规则尚未实现。

## 11.4 与 Financial Rules.md 的一致性

一致点：

- 金额、价格、数量使用 `BigDecimal`；
- Transaction / Ledger API 已落地基础流水金额方向规则；
- `INCOME`、`EXPENSE`、`ADJUSTMENT` 的余额联动在后端执行；
- V1.x 账户、资产、流水只允许 `CNY`，流水币种与账户一致，Dashboard 只聚合 `CNY` 数据；
- 资产浮动盈亏和收益率由后端计算；
- Dashboard 统计由后端计算；
- 前端不执行最终金融计算。

不完整点：

- 当前资产只表达持仓快照，未表达投资交易流水；
- 当前未实现复杂投资交易、汇率、IRR / XIRR 等未来能力；
- 并发下账户余额更新仍需后续评估行锁、乐观锁或原子 SQL；
- 当前 `marketValue` 同时存在持久化字段和响应实时计算，需要后续继续收敛一致性策略。

------

# 12. Target v1.0 API Design

本节记录 v1.0 目标 API 设计方向。以下内容不代表当前已经实现。

Target v1.0 API Design 表示目标方向，不代表当前 Sprint 必须一次性全部实现。

## 12.1 Transaction / Ledger Remaining Design

Transaction 基础 CRUD 已完成，当前已支持：

- 创建流水；
- 查询流水详情；
- 分页查询流水列表；
- 更新流水；
- 删除流水；
- 按账户、分类、类型、时间范围进行基础筛选；
- `INCOME`、`EXPENSE`、`ADJUSTMENT` 余额联动。

后续规划：

- 更完整的 `TRANSFER` 模型，包括转出账户、转入账户、双边流水或统一转账记录；
- 更完整的 `REFUND` 模型，包括关联原流水与退款方向规则；
- 筛选能力增强，例如金额范围、关键词、排序字段、排序方向；
- 与 Dashboard 最近流水展示字段进一步对齐；
- Controller 层测试补齐；
- Controller 层参数校验测试进一步补齐；
- 并发余额更新策略评估。

## 12.2 Category API Completion

v1.0 目标上应补齐分类更新和删除能力。

目标接口方向：

| Method | Path | 目标说明 |
|---|---|---|
| `PUT` | `/api/v1/categories/{id}` | 更新当前用户自定义分类。 |
| `DELETE` | `/api/v1/categories/{id}` | 删除当前用户自定义分类。 |

设计要求：

- 系统分类不得被普通用户修改或删除；
- 有关联流水的分类不应直接删除；
- 分类类型应限制为 `INCOME` 或 `EXPENSE`；
- 分类归属必须由 Service 层校验。

## 12.3 Asset API Completion

v1.0 目标上应补齐资产完整更新能力，并与账户关联规则对齐。

目标接口方向：

| Method | Path | 目标说明 |
|---|---|---|
| `PUT` | `/api/v1/assets/{id}` | 更新资产基础信息、持仓数量、平均成本、市场、币种等。 |

设计要求：

- `AssetRequest` 目标上应支持 `accountId`；
- `assets.account_id` 补齐后，创建和更新资产必须校验账户归属；
- 资产账户应属于当前用户；
- 资产账户类型应符合投资账户语义；
- `quantity`、`avgCost`、`currentPrice` 必须使用 `BigDecimal`。

## 12.4 Validation and Error Response

v1.0 已完成参数校验和 Spring Security 错误响应统一包装。

当前能力：

- `@Valid` 校验异常统一返回 `ApiResponse`；
- `@RequestParam` 校验异常统一返回 `ApiResponse`；
- JSON 解析错误统一返回 `ApiResponse`；
- Spring Security `401` / `403` 统一返回 `ApiResponse`；
- 未知异常统一返回 `ApiResponse`。

后续目标：

- 业务错误码形成稳定规范。

------

# 13. Known Gaps

当前 API Known Gaps：

1. 缺少完整 Category 更新、删除 API。
2. 缺少完整 Asset 更新 API。
3. Asset API 缺少 `accountId`，与 `Database.md` 中 `assets.account_id` Known Gap 一致。
4. 无 AssetPrice API，且后端无 `AssetPrice` Entity / Mapper。
5. 完整 `TRANSFER` / `REFUND` 模型暂未实现。
6. 投资交易流水暂未实现。
7. 并发下账户余额更新仍需后续评估行锁、乐观锁或原子 SQL。
8. 参数校验、请求参数缺失、参数类型错误、请求体格式错误、Spring Security `401` / `403`、未知异常均已统一返回 `ApiResponse`。
9. 业务错误码体系较简单，暂无稳定 `ErrorCode` 枚举。
10. `/categories/init` 不适合作为长期普通业务 API 暴露。
11. Account / Asset 主列表分页、账户编辑、资产详情、资产删除和资产清仓已接入前端；后续仍可补齐分类管理页面和更完整的资产编辑能力。
12. 无 OpenAPI / Swagger / API contract。
13. 无统一排序、过滤、搜索规范。
14. 无审计日志、幂等、请求追踪 ID。
15. Dashboard 最近流水已补充 `category` 和 `account` 展示名称，后续可继续与交易列表的筛选、分页展示规范对齐。
16. `marketValue` 存在持久化字段与响应实时计算之间的一致性风险。

------

# 14. Future Evolution

以下能力属于未来演进方向，不代表当前已经实现，也不应写入 Current Implementation。

- AI 分析 / 报告接口；
- 汇率接口；
- 第三方行情接口；
- AssetPrice 历史价格管理接口；
- CSV / Excel 导入导出；
- 审计日志查询；
- 软删除恢复；
- 投资交易流水、股息、拆股、手续费、税费；
- 多成本计算方式、IRR / XIRR；
- 多用户协作、家庭账本、管理员接口；
- OpenAPI / Swagger contract；
- 插件系统 / 开放 API。

未来能力约束：

- AI 不得修改金融数据；
- 第三方行情不得直接替代用户确认的业务数据；
- 汇率能力应保证来源和更新时间可追溯；
- 导入能力不得绕过 Service 层业务校验；
- 开放 API 和插件系统不得破坏认证、权限和用户数据隔离边界。

------

# 15. Review Checklist

## 15.1 Current / Target / Future 边界

- 是否把未实现接口写成已实现；
- 是否明确区分 Current Implementation、Target Design 和 Future Evolution；
- 是否避免把未来能力描述成 v1.0 当前能力；
- 是否记录前端实际调用情况。

## 15.2 认证与权限

- 是否明确 `/api/v1/register` 和 `/api/v1/login` 公开；
- 是否明确其他业务接口默认需要 JWT；
- 是否记录 `Authorization: Bearer <token>`；
- 是否说明当前无角色、无管理员权限、无细粒度权限模型；
- 是否说明 Controller 当前通过 `Authentication principal` 获取 `userId`。

## 15.3 响应、分页与错误处理

- 是否记录 `ApiResponse<T>`；
- 是否记录 `PageResult<T>`；
- 是否记录分页参数修正规则；
- 是否记录 `BusinessException` 与 HTTP Status 映射；
- 是否记录参数校验、Spring Security 和未知异常的统一错误响应现状。

## 15.4 架构与实现边界

- 是否避免 Controller 返回 Entity；
- 是否避免 Controller 承载业务逻辑；
- 是否保持 DTO 与 Entity 隔离；
- 是否避免前端执行最终金融计算；
- 是否避免 AI 修改金融数据；
- 是否与模块化单体架构一致。

## 15.5 数据库与业务规则一致性

- 是否与 `Database.md` Known Gaps 一致；
- 是否记录 `assets.account_id` 缺失；
- 是否记录无 `AssetPrice` Entity / Mapper / API；
- 是否记录 `TRANSFER` / `REFUND` 未完整实现；
- 是否符合用户数据隔离规则；
- 是否符合 Dashboard 只读聚合规则；
- 是否符合 `Financial Rules.md` 中的 `BigDecimal` 和后端计算要求。

------

# 16. Review Conclusion

当前 `API.md` 记录了 Personal Finance OS 当前 API 实现基线，并明确区分：

- Current Implementation；
- Frontend Usage Baseline；
- Target v1.0 API Design；
- Known Gaps；
- Future Evolution。

当前后端已经具备认证、账户、分类、资产、Dashboard 以及 Transaction / Ledger 第一版基础 API。Transaction / Ledger API 已落地 `INCOME`、`EXPENSE`、`ADJUSTMENT` 的基础 CRUD、分页查询、用户隔离和账户余额联动；`TRANSFER` / `REFUND` 当前明确返回 `400`，不作为已实现能力。

后续重点是补齐完整 `TRANSFER` / `REFUND` 模型、分类更新删除、资产完整更新、并发余额更新策略、分类管理页面，以及 OpenAPI / Swagger 等工程化能力。
