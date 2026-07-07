# P2 修复计划

本文件用于记录 P2 Review 问题的修复状态、处理方案与验证结果。

## P2-01 DashboardService 跨模块 Mapper 依赖

Status: Fixed

Problem:

`DashboardService` 直接依赖 `AccountMapper`、`AssetMapper`、`TransactionMapper`，导致 Dashboard 聚合服务直接访问多个模块的持久化层。

Resolution:

`DashboardService` 不再直接依赖 `AccountMapper`、`AssetMapper` 或 `TransactionMapper`。

读取逻辑已移动到以下 QueryService 后面：

- `AccountQueryService`
- `AssetQueryService`
- `TransactionQueryService`

当前依赖方向：

```text
DashboardController
  -> DashboardService
      -> AccountQueryService
      -> AssetQueryService
      -> TransactionQueryService
          -> Mapper
```

Impact:

- `DashboardController` 未修改。
- `DashboardDto` 未修改。
- `/api/v1/dashboard` 接口路径未修改。
- 返回 JSON 字段未修改。
- `Architecture.md` 未修改。

Verification:

```powershell
cd backend
.\mvnw.cmd clean test
```

Result:

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## P2-03 CategoryController 直接返回 Entity

Status: Fixed

Problem:

`CategoryController` 在分类列表和创建分类接口中直接返回 `Category` Entity，导致 API 响应结构与持久化实体绑定。

Resolution:

新增 `CategoryResponse`，并将 `CategoryController` / `CategoryService` 的返回类型从 `Category` / `List<Category>` 改为 `CategoryResponse` / `List<CategoryResponse>`。

Compatibility:

响应字段尽量保持与原 Entity 直接返回时的 JSON 结构兼容，包括：

- `id`
- `userId`
- `name`
- `type`
- `parentId`
- `isSystem`
- `sortOrder`
- `createdAt`
- `updatedAt`

Impact:

- `CategoryController` 不再返回 `Category` Entity。
- `CategoryRequest` 未修改。
- `/api/v1/categories` 接口路径未修改。
- `/api/v1/categories/init` 行为未修改。
- `Architecture.md` 未修改。

Verification:

```powershell
cd backend
.\mvnw.cmd clean test
```

Result:

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## P2-04 BusinessException HTTP 状态不一致

Status: Fixed

Problem:

`GlobalExceptionHandler` 处理 `BusinessException` 时总是返回 HTTP 400，即使业务错误码是 401、403 或 404。

Resolution:

更新 `GlobalExceptionHandler`，根据 `BusinessException.code` 映射对应的 HTTP status。

Mapping:

- `400` -> HTTP 400 Bad Request
- `401` -> HTTP 401 Unauthorized
- `403` -> HTTP 403 Forbidden
- `404` -> HTTP 404 Not Found
- unknown code -> HTTP 400 Bad Request

Impact:

- `BusinessException(400, "...")` 返回 HTTP 400 + body.code 400。
- `BusinessException(401, "...")` 返回 HTTP 401 + body.code 401。
- `BusinessException(403, "...")` 返回 HTTP 403 + body.code 403。
- `BusinessException(404, "...")` 返回 HTTP 404 + body.code 404。
- `BusinessException.java` 未修改。
- `ApiResponse.java` 未修改。
- Controller / Service 未修改。
- `Architecture.md` 未修改。

Verification:

```powershell
cd backend
.\mvnw.cmd clean test
```

Result:

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
