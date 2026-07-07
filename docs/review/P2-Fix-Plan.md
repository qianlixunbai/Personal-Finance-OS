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
