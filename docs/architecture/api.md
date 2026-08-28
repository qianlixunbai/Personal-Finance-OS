# API

本文记录 `zh-cn` 当前公开 HTTP 契约。事实来源是当前 15 个业务 Controller、DTO、Security 配置和异常映射；数据库表或内部 Service 不自动构成公开 API。

## 1. 通用约定

### 路径与响应

- 业务 API 前缀为 `/api/v1`；
- 成功和错误使用 `ApiResponse<T>`：`code`、`message`、可选 `data`；
- Import domain error 还可以携带 `retryable`；
- offset 分页使用 `PageResult<T>`；
- 投资 Position / logical transaction 使用 opaque cursor seek pagination；
- 时间使用 ISO 格式，投资命令 posting time 由服务端生成。

### 认证与用户隔离

- `POST /api/v1/register` 与 `POST /api/v1/login` 公开；
- 其余业务路由需要 `Authorization: Bearer <JWT>`；
- health、OpenAPI 和 Swagger UI 由 Security 配置单独放行；
- user ID 来自 JWT principal，业务请求不接受 user ID；
- 不存在或跨用户资源采用安全 `404`。

### 金融值

- 投资命令和投资读取的金融值以 JSON string 表达，避免 JavaScript 精度丢失；
- Import Receipt 前端使用 lossless JSON transport 读取 Account impact 金额；
- 客户端不能提交 gross/net/cash delta、余额、receipt、projection、currency、user ID 或服务端 posting time；
- 普通 Account / Asset / Transaction 继续使用各自现有 DTO，不套用投资命令 schema。

### 幂等 Header

| Header | 适用范围 |
| --- | --- |
| `X-Idempotency-Key` | Legacy opening `migration-confirm` |
| `Idempotency-Key` | BUY / SELL / DIVIDEND / reversal / replacement / Transaction Import Confirm |

Header 名称不能互换。key 在用户域内使用，必须非空、无首尾空格且不超过 100 字符。

## 2. Controller 清单

| Controller | 基础路由 | 职责 |
| --- | --- | --- |
| `UserController` | `/api/v1` | 注册、登录 |
| `AccountController` | `/api/v1/accounts` | Account 查询与维护 |
| `CategoryController` | `/api/v1/categories` | Category 查询与创建 |
| `TransactionController` | `/api/v1/transactions` | 普通流水 CRUD |
| `AssetController` | `/api/v1/assets` | Legacy Asset 与参考数据刷新 |
| `DashboardController` | `/api/v1` | Dashboard |
| `InvestmentInstrumentController` | `/api/v1/investment/instruments` | Instrument |
| `LegacyAssetMigrationController` | `/api/v1/investment/legacy-assets` | opening migration |
| `InvestmentCommandController` | `/api/v1/investment/positions` | BUY / SELL / DIVIDEND |
| `InvestmentReversalController` | `/api/v1/investment/transactions` | standalone reversal |
| `InvestmentReplacementController` | `/api/v1/investment/transactions` | replacement |
| `InvestmentReadController` | `/api/v1/investment` | Portfolio / Position / logical transaction / audit |
| `TransactionImportPreviewController` | `/api/v1/imports/transactions` | upload、mapping、Preview、rows、cancel |
| `TransactionImportConfirmController` | `/api/v1/imports/transactions` | Confirm |
| `TransactionImportBatchController` | `/api/v1/imports/transactions/batches` | Receipt GET |

## 3. 基础财务

### User

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/v1/register` | 注册；请求 `username`、`email`、`password` |
| POST | `/api/v1/login` | 登录；返回 token、userId、username |

### Account

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/accounts` | 当前用户 Account 列表 |
| GET | `/api/v1/accounts/page` | `page` / `size` 分页 |
| GET | `/api/v1/accounts/{id}` | 详情 |
| POST | `/api/v1/accounts` | 创建 |
| PUT | `/api/v1/accounts/{id}` | 更新元数据 |
| POST | `/api/v1/accounts/{id}/deactivate` | 停用 |

公开请求 DTO 不接受 balance；创建时余额为零，后续由后端余额服务维护。

### Category

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/categories` | 查询用户与系统分类，可按 type 过滤 |
| POST | `/api/v1/categories` | 创建用户分类 |
| POST | `/api/v1/categories/init` | 初始化系统分类 |

### Transaction

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/transactions/page` | 分页与 account/category/type/time 筛选 |
| GET | `/api/v1/transactions/{id}` | 详情 |
| POST | `/api/v1/transactions` | 创建并应用一次余额 effect |
| PUT | `/api/v1/transactions/{id}` | 更新并反转旧 effect、应用新 effect |
| DELETE | `/api/v1/transactions/{id}` | 删除并反转旧 effect |

请求字段为 accountId、categoryId、type、amount、currency、description、transactedAt。当前只接受 `INCOME`、`EXPENSE`、`ADJUSTMENT`。

### Dashboard

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/dashboard` | 当前用户净资产、收支、趋势、资产分布与最近流水 |

## 4. Asset、Market 与 FX

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/assets` | Asset 列表 |
| GET | `/api/v1/assets/page` | Asset 分页 |
| GET | `/api/v1/assets/{id}` | Asset 详情 |
| POST | `/api/v1/assets` | 创建 Legacy Asset |
| PUT | `/api/v1/assets/{id}/price` | 更新手工参考价格 |
| PUT | `/api/v1/assets/{id}/close` | 关闭 Legacy Asset |
| DELETE | `/api/v1/assets/{id}` | 删除 Legacy Asset |
| POST | `/api/v1/assets/{id}/quote/refresh` | 显式刷新 owned US STOCK / ETF 行情 |
| POST | `/api/v1/assets/{id}/reference-valuation/refresh` | 显式刷新 quote / FX 并计算参考估值 |

transaction-driven Asset 不能通过 Legacy Asset 写接口改写数量、成本、PnL 或投影版本。普通 GET 不调用 Provider。

## 5. Investment Instrument 与 Opening

### Instrument

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/investment/instruments` | 用户级 Instrument 列表 |
| POST | `/api/v1/investment/instruments` | 创建 Instrument |

### Opening Preview

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/investment/legacy-assets/{assetId}/migration-preview` |
| 请求 | `instrumentId`、`accountId` |
| 响应 | source、target、opening calculation、expected Position、validation、signed preview token |
| 写入 | 无；完全只读 |

### Opening Confirm

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/investment/legacy-assets/{assetId}/migration-confirm` |
| Header | `X-Idempotency-Key` |
| 请求 | `previewToken` |
| 效果 | 一个 `OPENING_POSITION`、Asset replay；不修改 Account.balance |

## 6. Investment Command

| 方法 | 路径 | 请求核心 | 用途 |
| --- | --- | --- | --- |
| POST | `/api/v1/investment/positions` | accountId、instrumentId、quantity、unitPrice、feeAmount、taxAmount | first BUY |
| POST | `/api/v1/investment/positions/{assetId}/buy` | quantity、unitPrice、feeAmount、taxAmount | subsequent / reopen BUY |
| POST | `/api/v1/investment/positions/{assetId}/sell` | quantity、unitPrice、feeAmount、taxAmount | partial / full SELL |
| POST | `/api/v1/investment/positions/{assetId}/dividends` | grossAmount、feeAmount、taxAmount、externalReference、note | DIVIDEND |
| POST | `/api/v1/investment/transactions/{transactionId}/reversal` | reason | standalone reversal |
| POST | `/api/v1/investment/transactions/{transactionId}/replacement` | 根据 original type 的同类型字段与 reason | same-type replacement |

全部需要 `Idempotency-Key`。服务端生成 currency、tradeTime、settlementTime、现金 effect、余额和最终 Position。

Command response 返回 transaction / correction identity、金额、cash delta、balanceAfter、`idempotentReplay` 和 finalPosition。DIVIDEND、reversal、replacement 使用更严格的 closed schema；不能据此推断所有投资 DTO 都拒绝未知字段。

## 7. Investment Read

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/v1/investment/portfolio` | transaction-driven Portfolio summary |
| GET | `/api/v1/investment/positions` | Position cursor list |
| GET | `/api/v1/investment/positions/{positionId}` | Position detail |
| GET | `/api/v1/investment/transactions` | logical transaction cursor list |
| GET | `/api/v1/investment/transactions/{logicalTransactionId}` | logical transaction detail |
| GET | `/api/v1/investment/transactions/{logicalTransactionId}/audit-timeline` | correction audit timeline |

Position 顺序为 `instrumentId ASC, accountId ASC, positionId ASC`。logical transaction 使用 original fact ID 作为 logical identity；物理 reversal / replacement fact ID 不作为详情入口。历史 posting receipt、correction final receipt 和请求时点 currentPosition 分层返回。

Portfolio 只包含 transaction-driven Position，不包含 Account 现金、Legacy Asset 或完整净资产。缓存 reference valuation 不触发 Provider refresh。

## 8. Transaction Import Preview

### Upload

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/imports/transactions/preview` |
| Content-Type | `multipart/form-data` |
| Part `file` | CSV 或 XLSX，最大 5 MiB |
| Part `request` | JSON：`format=AUTO|CSV|XLSX`、可选 mapping |
| 响应 | Session/Batch identity、columns、mapping、首批 rows、summary、digests、expiry、confirmable、previewToken |

mapping 不完整时返回 `MAPPING_REQUIRED`，不返回 Batch ID 或 token；完整时生成 `PREVIEW_READY` frozen plan。

### Mapping、分页与取消

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| PUT | `/api/v1/imports/transactions/{importSessionId}/preview` | multipart Part `mapping`；在同一 Session 更新 mapping 与 revision |
| GET | `/api/v1/imports/transactions/{importSessionId}/rows?page=1&size=100` | 服务端 Preview rows；size 限制到 1–500 |
| DELETE | `/api/v1/imports/transactions/{importSessionId}` | 取消可用 Session 并清理临时 payload |

mapping 包含 columnMappings、typeMappings、accountMappings、categoryMappings。必需目标列为 date、type、amount、account、category；time、description、currency 可选。未使用源列必须显式 `IGNORE`。

## 9. Transaction Import Confirm 与 Receipt

### Confirm

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/imports/transactions/{importSessionId}/confirm` |
| Header | `Idempotency-Key` |
| JSON | `previewToken`、`acknowledgedWarningIds` |
| 前置 | `PREVIEW_READY`、未过期、frozen plan 可用、warning 集合精确匹配 |
| 效果 | 创建全部普通 Transaction、按 Account 聚合余额影响、Batch/Items/Impacts、Session `CONSUMED`；单事务提交 |
| 响应 | `receipt` 与 `idempotentReplay` |

Confirm 不接受客户端 rows、余额或 Account impacts。exact duplicate、stale preview、duplicate evidence drift、warning 未确认、锁冲突和幂等冲突均 fail closed。

### Receipt GET

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/imports/transactions/batches/{importBatchId}` | 从 committed persistence 重建用户自己的权威 Receipt |

Receipt 包含 Session/Batch、状态、文件名和 digest、row counts、transaction references、Account impacts、confirmedAt、contractVersion 与 resultDigest。临时 PreviewPlan 清理后仍可读取。

## 10. 错误语义

| HTTP | 当前语义 |
| --- | --- |
| 400 | 参数、请求体、mapping、文件内容、金额或业务输入错误 |
| 401 | JWT 缺失、无效或登录失败 |
| 403 | 已认证但安全层拒绝 |
| 404 | 资源不存在或跨用户不可见 |
| 409 | 幂等、replay、Session、warning、duplicate evidence 或锁冲突 |
| 413 | Import 文件大小或行数超限 |
| 415 | Import 扩展名、请求格式或 content type 不支持 |
| 429 | Market / FX refresh 限流 |
| 500 | 一致性失败或未知内部错误 |
| 502 | Provider 返回无效数据 |
| 503 | 数据库或 Provider 暂时不可用 |

错误响应不得泄露 SQL、堆栈、request hash、幂等键、内部 reason 或敏感财务中间值。

### 当前错误映射不一致

CSV / XLSX parser 的超时分支构造业务 code `408`，但 `GlobalExceptionHandler.resolveHttpStatus` 当前没有 408 映射，会使用 HTTP 500。客户端不能把 HTTP 408 当作当前稳定契约；这是实现层待修复不一致，本轮文档任务未改代码。

## 11. OpenAPI 边界

开发 profile 放行 `/v3/api-docs/**`、`/swagger-ui.html` 与 `/swagger-ui/**`；production profile 关闭 OpenAPI / Swagger UI。本文是人工维护的当前契约摘要，字段级真值仍以 Controller、DTO 和生成的开发环境 OpenAPI 为准。
