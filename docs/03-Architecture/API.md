# API（接口设计基线）

**项目名称：** Personal Finance OS

**版本：** v1.0

**状态：** Draft

**分支：** zh-cn

**日期：** 2026-07-07

------

# 1. Purpose

本文档用于记录 Personal Finance OS 当前后端 API 实现基线、统一响应格式、认证规则、分页规则、错误处理方式、前端调用现状、Known Gaps、v1.0 Target Design 以及 Future Evolution。

本文档不是 OpenAPI / Swagger 文档，不替代接口自动化契约，也不要求立即补齐未实现能力。本文档的核心目标是：

- 记录当前已经实现的 API；
- 明确当前 API 的认证、响应、分页和错误处理规则；
- 区分 Current Implementation、Target Design 和 Future Evolution；
- 为后续 Controller、DTO、Service、前端调用和 Review 提供基线；
- 保证 API 设计与 `Architecture.md`、`Database.md`、`Business Rules.md`、`Financial Rules.md` 保持一致。

本文档不得把未实现能力描述为已实现能力。

------

# 2. Scope

## 2.1 In Scope

本文档覆盖：

- 当前后端 Controller 暴露的 API；
- 当前 Request DTO / Response DTO；
- `ApiResponse<T>` 统一响应格式；
- `PageResult<T>` 分页响应格式；
- `BusinessException` 与 `GlobalExceptionHandler`；
- 当前 Spring Security / JWT 认证规则；
- 当前前端 `frontend/src/api` 和页面中的实际 API 调用；
- 当前 API 与数据库设计、业务规则和金融规则的一致性；
- 当前 Known Gaps；
- v1.0 Target API Design；
- Future Evolution；
- Review Checklist。

## 2.2 Out of Scope

本文档不包含：

- Java 代码修改；
- Controller / DTO / Service 实现修改；
- 前端代码修改；
- `schema.sql` 修改；
- `Architecture.md` 或 `Database.md` 修改；
- OpenAPI / Swagger 配置；
- 接口自动生成文档；
- 新接口实现；
- 数据库 migration；
- 具体前端页面交互设计。

------

# 3. API Design Principles

当前 API 设计遵循以下原则：

1. API 必须服务于模块化单体架构，不引入微服务边界。
2. Controller 只负责请求入口、参数接收、参数校验和统一返回，不承载业务规则。
3. 业务规则和金融计算必须位于后端 Service 层，前端不得执行最终金融计算。
4. 对外返回使用 DTO，不直接返回 Entity。
5. 普通业务数据必须围绕当前登录用户进行隔离。
6. Dashboard 数据必须来源于真实业务数据，不人工维护统计结果。
7. AI 不得修改金融数据、账户余额、资产持仓或流水数据。
8. 当前实现与目标设计必须明确区分，未实现能力不得写入 Current Implementation。
9. Future Evolution 只记录未来扩展方向，不代表当前 v1.0 已实现或必须立即实现。

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
- 目前没有多版本并行机制；
- 如后续出现不兼容 API 变更，应优先通过新版本路径或兼容字段演进处理；
- 不应在未评审的情况下破坏当前前端已使用接口。

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
- 当前普通用户只能通过 Service 层的 `userId` 限制访问自己的数据；
- 当前不支持多用户协作、家庭账本或企业账本。

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
- 当 `data == null` 时，响应 JSON 中可能不输出 `data` 字段。

错误响应示例：

```json
{
  "code": 404,
  "message": "resource not found"
}
```

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

当前分页实现：

- `Account` 已提供分页接口；
- `Asset` 已提供分页接口；
- `page < 1` 时修正为 `1`；
- `size < 1` 时修正为 `1`；
- `size > 100` 时修正为 `100`；
- 原列表接口仍保留，用于兼容当前前端；
- 当前前端尚未接入分页接口。

当前分页接口：

- `GET /api/v1/accounts/page?page=1&size=20`
- `GET /api/v1/assets/page?page=1&size=20`

------

# 8. Error Handling

当前业务异常模型为 `BusinessException`。

`BusinessException` 包含：

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | `int` | 业务错误码。 |
| `message` | `String` | 错误消息。 |

当前统一异常处理位于 `GlobalExceptionHandler`。

当前 `BusinessException` 到 HTTP Status 的映射：

| `BusinessException.code` | HTTP Status |
|---:|---|
| `400` | `400 Bad Request` |
| `401` | `401 Unauthorized` |
| `403` | `403 Forbidden` |
| `404` | `404 Not Found` |
| 其他 | `400 Bad Request` |

未知异常：

- 未捕获 `Exception` 返回 HTTP `500`；
- 响应体使用 `ApiResponse.error(500, message)`。

当前已知限制：

- 参数校验异常尚未显式统一包装为 `ApiResponse`；
- `MethodArgumentNotValidException`、`ConstraintViolationException`、`MissingServletRequestParameterException`、`HttpMessageNotReadableException` 当前尚未统一包装为 `ApiResponse`；
- Spring Security 自身产生的 `401` 尚未显式统一包装为 `ApiResponse`；
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

相关 DTO：

- `RegisterRequest`
- `LoginRequest`
- `LoginResponse`

## 9.2 Account APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `GET` | `/api/v1/accounts` | 是 | 无 | `ApiResponse<List<AccountResponse>>` | 查询当前用户账户列表。 |
| `GET` | `/api/v1/accounts/page` | 是 | Query: `page`, `size` | `ApiResponse<PageResult<AccountResponse>>` | 分页查询当前用户账户列表。 |
| `GET` | `/api/v1/accounts/{id}` | 是 | Path: `id` | `ApiResponse<AccountResponse>` | 查询当前用户指定账户。非当前用户账户返回 `404`。 |
| `POST` | `/api/v1/accounts` | 是 | Body: `AccountRequest(name, type, currency)` | `ApiResponse<AccountResponse>` | 创建账户。默认 `currency` 为 `CNY`，初始 `balance` 为 `0`。 |
| `PUT` | `/api/v1/accounts/{id}` | 是 | Path: `id`; Body: `AccountRequest` | `ApiResponse<AccountResponse>` | 更新账户基础信息。 |
| `POST` | `/api/v1/accounts/{id}/deactivate` | 是 | Path: `id` | `ApiResponse<Void>` | 停用账户，将 `status` 更新为 `INACTIVE`。 |

相关 DTO：

- `AccountRequest`
- `AccountResponse`

当前说明：

- 当前账户 API 使用 `Authentication principal` 获取当前 `userId`；
- 查询、详情、更新和停用均校验账户归属；
- 当前未提供账户删除 API；
- 当前停用账户符合业务规则中“保留历史数据”的方向；
- 当前创建账户时不允许客户端直接设置 `balance`。

## 9.3 Category APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `GET` | `/api/v1/categories` | 是 | Query: `type?` | `ApiResponse<List<CategoryResponse>>` | 查询当前用户分类和系统分类，可按 `type` 过滤。 |
| `POST` | `/api/v1/categories` | 是 | Body: `CategoryRequest(name, type, parentId, sortOrder)` | `ApiResponse<CategoryResponse>` | 创建当前用户自定义分类。 |
| `POST` | `/api/v1/categories/init` | 是 | 无 | `ApiResponse<Void>` | 初始化系统分类。当前作为接口暴露，但不适合作为长期普通业务 API。 |

相关 DTO：

- `CategoryRequest`
- `CategoryResponse`

当前说明：

- 分类查询包括当前用户分类和 `isSystem = true` 的系统分类；
- `type` 当前支持 `INCOME`、`EXPENSE`；
- 当前没有分类更新 API；
- 当前没有分类删除 API；
- 系统分类初始化接口当前由登录用户可调用，后续应重新评估边界。
- `/categories/init` 更适合作为开发/初始化接口，后续应考虑迁移为启动初始化逻辑、管理端能力或 migration / data seed 机制。

## 9.4 Asset APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `GET` | `/api/v1/assets` | 是 | 无 | `ApiResponse<List<AssetResponse>>` | 查询当前用户资产列表。 |
| `GET` | `/api/v1/assets/page` | 是 | Query: `page`, `size` | `ApiResponse<PageResult<AssetResponse>>` | 分页查询当前用户资产列表。 |
| `GET` | `/api/v1/assets/{id}` | 是 | Path: `id` | `ApiResponse<AssetResponse>` | 查询当前用户指定资产。非当前用户资产返回 `404`。 |
| `POST` | `/api/v1/assets` | 是 | Body: `AssetRequest(name, symbol, type, market, currency, quantity, avgCost)` | `ApiResponse<AssetResponse>` | 创建资产持仓记录。 |
| `PUT` | `/api/v1/assets/{id}/price` | 是 | Path: `id`; Query: `price` | `ApiResponse<AssetResponse>` | 更新当前价格，并计算 `marketValue`。 |
| `DELETE` | `/api/v1/assets/{id}` | 是 | Path: `id` | `ApiResponse<Void>` | 删除资产。当前有持仓数量时拒绝删除。 |

相关 DTO：

- `AssetRequest`
- `AssetResponse`

当前说明：

- 当前资产 API 使用 `Authentication principal` 获取当前 `userId`；
- 查询、详情、更新价格和删除均校验资产归属；
- `quantity` 和 `avgCost` 使用 `BigDecimal`；
- `updatePrice` 要求 `price > 0`；
- `profitLoss` 和 `profitLossRate` 在后端计算；
- 当前 `AssetRequest` 不包含 `accountId`；
- 当前没有完整资产更新 API；
- 当前没有资产价格历史 API。

## 9.5 Dashboard APIs

| Method | Path | 认证 | 请求参数 / 请求体 | 响应类型 | 当前说明 |
|---|---|---|---|---|---|
| `GET` | `/api/v1/dashboard` | 是 | 无 | `ApiResponse<DashboardDto>` | 获取当前用户 Dashboard 聚合数据。 |

相关 DTO：

- `DashboardDto`
- `DashboardDto.AssetAllocation`
- `DashboardDto.RecentTransaction`

当前说明：

- Dashboard 当前聚合账户余额、资产市值、月收入、月支出、最近流水；
- Dashboard 不保存人工统计结果；
- Dashboard 通过 `AccountQueryService`、`AssetQueryService`、`TransactionQueryService` 读取真实业务数据；
- 当前 `netWorth = totalAssets`，v1 暂无负债模型；
- 最近流水当前只展示基础字段，`category` 和 `account` 当前为空字符串。

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
- response interceptor 遇到 HTTP `401` 时删除本地 token 并跳转 `/login`。

当前前端实际调用的接口：

| 前端调用 | 完整后端路径 | 页面 |
|---|---|---|
| `POST /login` | `POST /api/v1/login` | `Login.tsx` |
| `POST /register` | `POST /api/v1/register` | `Register.tsx` |
| `GET /dashboard` | `GET /api/v1/dashboard` | `Dashboard.tsx` |
| `GET /accounts` | `GET /api/v1/accounts` | `Accounts.tsx` |
| `POST /accounts` | `POST /api/v1/accounts` | `Accounts.tsx` |
| `POST /accounts/{id}/deactivate` | `POST /api/v1/accounts/{id}/deactivate` | `Accounts.tsx` |
| `GET /assets` | `GET /api/v1/assets` | `Assets.tsx` |
| `POST /assets` | `POST /api/v1/assets` | `Assets.tsx` |
| `PUT /assets/{id}/price?price=...` | `PUT /api/v1/assets/{id}/price?price=...` | `Assets.tsx` |

当前后端已实现但前端尚未调用的接口：

- `GET /api/v1/accounts/page`
- `GET /api/v1/accounts/{id}`
- `PUT /api/v1/accounts/{id}`
- `GET /api/v1/categories`
- `POST /api/v1/categories`
- `POST /api/v1/categories/init`
- `GET /api/v1/assets/page`
- `GET /api/v1/assets/{id}`
- `DELETE /api/v1/assets/{id}`

说明：

- 分页接口当前后端已实现，但前端尚未接入；
- 当前前端仍使用非分页列表接口；
- 当前前端未接入分类管理页面；
- 当前前端未接入资产详情和删除能力。

------

# 11. Consistency with Architecture / Database / Business Rules / Financial Rules

## 11.1 与 Architecture.md 的一致性

当前 API 基本符合架构约束：

- 采用前后端分离；
- 后端 API 位于 Spring Boot 应用内；
- Controller 返回 DTO 和统一响应；
- Controller 没有直接返回 Entity；
- 业务逻辑主要位于 Service 层；
- Dashboard 作为聚合展示接口，不反向影响核心业务模块。

需要注意：

- `CategoryController` 的 `/categories/init` 更像开发初始化能力，不适合作为长期普通业务 API；
- Dashboard 当前依赖多个 Query Service，后续需要继续保持只读聚合边界。

## 11.2 与 Database.md 的一致性

一致点：

- 当前 API 覆盖 `users`、`accounts`、`categories`、`assets` 的主要访问能力；
- 当前 API 对账户、分类、资产均围绕 `userId` 做用户隔离；
- 当前 API 使用 `BigDecimal` 表达金额、价格、数量；
- 当前 Dashboard 不维护人工统计表；
- 当前资产 API 与 Database.md 共同记录了 `assets.account_id` 缺失这一 Known Gap。

不完整点：

- 当前没有 Transaction / Ledger Controller；
- 当前没有 AssetPrice API；
- 当前没有 `AssetPrice` Entity / Mapper；
- 当前 Asset API 缺少 `accountId`；
- 当前 `asset_prices` 表存在，但 API 层尚未开放。

## 11.3 与 Business Rules.md 的一致性

一致点：

- 注册检查用户名和邮箱唯一；
- 登录后才能访问个人业务数据；
- 普通用户只能访问自己的账户和资产；
- 账户不提供删除接口，提供停用接口；
- Dashboard 数据来源于真实业务数据；
- AI 相关接口当前未实现，因此不存在 AI 修改金融数据的问题。

不完整点：

- 流水规则尚无对应 CRUD API；
- 分类规则中的更新、删除尚未实现；
- 资产规则中的完整更新尚未实现；
- 资产必须属于投资账户的规则当前因缺少 `accountId` 尚未完整落地。

## 11.4 与 Financial Rules.md 的一致性

一致点：

- 金额、价格、数量使用 `BigDecimal`；
- 资产浮动盈亏和收益率由后端计算；
- Dashboard 统计由后端计算；
- 前端不执行最终金融计算；
- 当前未实现复杂投资交易、汇率、IRR / XIRR 等未来能力。

不完整点：

- 当前流水金额方向规则尚无 API 落地；
- 当前资产只表达持仓快照，未表达投资交易流水；
- 当前 `marketValue` 同时存在持久化字段和响应实时计算，需要后续继续收敛一致性策略。

------

# 12. Target v1.0 API Design

本节记录 v1.0 目标 API 设计方向。以下内容不代表当前已经实现。

Target v1.0 API Design 表示目标方向，不代表当前 Sprint 必须一次性全部实现。

## 12.1 Transaction / Ledger APIs

v1.0 目标上应补齐日常财务流水 API。

目标能力：

- 创建流水；
- 查询流水详情；
- 分页查询流水列表；
- 更新流水；
- 删除流水；
- 按账户筛选；
- 按分类筛选；
- 按类型筛选；
- 按时间范围筛选。

目标接口方向：

| Method | Path | 目标说明 |
|---|---|---|
| `GET` | `/api/v1/transactions/page` | 分页查询当前用户流水，支持账户、分类、类型、时间筛选。 |
| `GET` | `/api/v1/transactions/{id}` | 查询当前用户指定流水。 |
| `POST` | `/api/v1/transactions` | 创建流水。 |
| `PUT` | `/api/v1/transactions/{id}` | 更新流水。 |
| `DELETE` | `/api/v1/transactions/{id}` | 删除流水。 |

设计要求：

- 每条流水必须归属当前用户；
- 每条流水必须关联合法账户；
- 每条流水必须关联合法分类；
- 账户和分类必须属于当前用户，或分类为系统分类；
- 停用账户不允许新增流水；
- 金额方向规则必须符合 `Financial Rules.md`；
- 修改或删除流水时必须保证账户余额和统计结果一致。

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
- `quantity`、`avgCost`、`currentPrice` 必须使用 `BigDecimal`；
- `marketValue` 的持久化或实时计算策略需要统一。

## 12.4 Validation and Error Response

v1.0 目标上应统一参数校验和错误响应。

目标能力：

- `@Valid` 校验异常统一返回 `ApiResponse`；
- `@RequestParam` 校验异常统一返回 `ApiResponse`；
- JSON 解析错误统一返回 `ApiResponse`；
- Spring Security `401` 统一返回 `ApiResponse`；
- Spring Security `403` 统一返回 `ApiResponse`；
- 业务错误码形成稳定规范。

目标错误码方向：

- 建立 `ErrorCode` 枚举或等价规范；
- 区分认证错误、权限错误、参数错误、资源不存在、业务规则冲突、系统错误；
- 避免不同业务场景随意复用模糊错误码；
- 保持 HTTP Status 与业务 `code` 的映射清晰。

------

# 13. Known Gaps

当前 API Known Gaps：

1. 缺少 Transaction / Ledger CRUD API。
2. 缺少完整 Category 更新、删除 API。
3. 缺少完整 Asset 更新 API。
4. Asset API 缺少 `accountId`，与 `Database.md` 中 `assets.account_id` Known Gap 一致。
5. 无 AssetPrice API，且后端无 `AssetPrice` Entity / Mapper。
6. 参数校验异常未统一包装为 `ApiResponse`。
   当前包括但不限于 `MethodArgumentNotValidException`、`ConstraintViolationException`、`MissingServletRequestParameterException`、`HttpMessageNotReadableException`。
7. Spring Security `401` 未统一包装为 `ApiResponse`。
8. 业务错误码体系较简单，暂无稳定 `ErrorCode` 枚举。
9. `/categories/init` 不适合作为长期普通业务 API 暴露。
10. 前端尚未使用分页接口。
11. 无 OpenAPI / Swagger / API contract。
12. 无统一排序、过滤、搜索规范。
13. 无审计日志、幂等、请求追踪 ID。
14. Dashboard 最近流水中的 `category` 和 `account` 当前为空字符串。
15. `marketValue` 存在持久化字段与响应实时计算之间的一致性风险。

------

# 14. Future Evolution

以下能力属于未来演进方向，不代表当前已经实现，也不应写入 Current Implementation。

未来可评估能力：

- AI 分析 / 报告接口；
- 汇率接口；
- 第三方行情接口；
- AssetPrice 历史价格管理接口；
- CSV / Excel 导入导出；
- 审计日志查询；
- 软删除恢复；
- 投资交易流水；
- 股息、拆股、手续费、税费；
- 多成本计算方式；
- IRR / XIRR；
- 多用户协作；
- 家庭账本；
- 管理员接口；
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

后续 Review `API.md` 时应检查以下事项：

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
- 是否记录参数校验和 Spring Security 错误响应 Known Gap。

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
- 是否记录 Transaction / Ledger API 缺失；
- 是否符合用户数据隔离规则；
- 是否符合 Dashboard 只读聚合规则；
- 是否符合 `Financial Rules.md` 中的 `BigDecimal` 和后端计算要求。

------

# 16. Review Conclusion

当前 `API.md` 第一版草稿记录了 Personal Finance OS 当前 API 实现基线，并明确区分了：

- Current Implementation；
- Frontend Usage Baseline；
- Target v1.0 API Design；
- Known Gaps；
- Future Evolution。

当前后端已经具备认证、账户、分类、资产和 Dashboard 的基础 API，但仍缺少完整 Ledger / Transaction API、分类更新删除、资产完整更新、统一参数校验错误响应和更稳定的错误码体系。

本文档可作为后续 API Review、Controller 补齐、DTO 演进、前端接入分页、错误处理收敛和 v1.0 API 设计完善的基线。后续修改 API 实现时，应同步更新本文档，并保持与 `Architecture.md`、`Database.md`、`Business Rules.md`、`Financial Rules.md` 的一致性。
