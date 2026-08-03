# API 当前基线

所有路由位于 `/api/v1`，响应使用 `ApiResponse`。注册和登录公开；其余接口需 Bearer JWT，并只在调用者数据域内工作。

## 11 个 Controller

| Controller | 主要路由 |
| --- | --- |
| UserController | `POST /register`、`POST /login` |
| AccountController | `/accounts` 列表、分页、详情、新建、更新、停用 |
| CategoryController | `GET/POST /categories`、`POST /categories/init` |
| TransactionController | `/transactions` 分页、详情、新建、更新、删除 |
| AssetController | `/assets` 查询、创建、手工价格、关闭、删除、quote/reference valuation refresh |
| DashboardController | `GET /dashboard` |
| InvestmentInstrumentController | `GET/POST /investment/instruments` |
| LegacyAssetMigrationController | legacy asset migration preview / confirm |
| InvestmentCommandController | first BUY、Asset BUY、SELL、DIVIDEND |
| InvestmentReversalController | transaction standalone reversal |
| InvestmentReplacementController | transaction replacement |

当前没有 Portfolio read API，也没有公开的 InvestmentTransaction 列表、详情或审计时间线 API；不得据此推导 Phase 2C-1 endpoint。

## 投资命令契约

- opening migration 是单 Asset 两步流程：preview 只读；confirm 使用签名且过期的 `previewToken` 与 `X-Idempotency-Key`，创建一个 `OPENING_POSITION`，不改余额。
- first BUY 创建 Position；后续 BUY/SELL/DIVIDEND 以 Asset 为作用域。BUY/SELL 接收 `quantity`、`unitPrice`、`feeAmount`、`taxAmount`；DIVIDEND 接收 `grossAmount` 和可选费用/税费。
- standalone reversal 仅接收 `reason`，为一个 eligible BUY/SELL/DIVIDEND 追加 reversal；replacement 以同类型请求体和 `reason` 原子追加 grouped reversal 与 replacement fact，绝不 PUT/DELETE 原事实。
- BUY、SELL、DIVIDEND、reversal、replacement 均需要 `Idempotency-Key`。相同规范请求返回已记录回执；同 key 不同请求返回冲突。
- 金额输入输出均为固定 scale 的 JSON 字符串而不是 JSON number。严格 JSON 命令拒绝未知字段，也不接收用户 ID、币种、客户端入账时间、计算字段、回执或 correction 字段。

## 一致性与错误

投资写入在一个事务内完成事实、现金 delta、全历史重放、transaction-driven Asset 投影、回执和 replacement envelope；失败时全部回滚。投资事实只能通过 append-only reversal/replacement 纠正，普通 `transactions` 的 CRUD 与投资事实不同。

`400` 参数/严格 JSON/金额无效；`401` 认证失败；`403` 无权限；`404` 不存在或跨用户资源；`409` 幂等、业务、重放或锁冲突；`429` 行情限流；`500` 已脱敏的内部一致性失败；`502` Provider 响应无效；`503` Provider 不可用。行情、FX 和 reference valuation 不创建投资事实、不改余额或账本投影。
