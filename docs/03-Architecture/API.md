# API 当前契约

本文记录 `zh-cn` 当前公开 Controller 的可查阅契约。实现事实来自 11 个 Controller、DTO、Security 配置和异常映射；未来 Phase 2C-1 路径不得从本文提前推导。

## 1. 通用约定

### 1.1 路径与响应

- 业务 API 前缀为 `/api/v1`。
- 成功和业务错误统一使用 `ApiResponse<T>`：`code`、`message`、可选 `data`。
- 分页响应 `PageResult<T>`：`records`、`total`、`page`、`size`。
- Account、Asset 和普通 Transaction 分页默认 `page=1`、`size=20`；服务端将 size 限制到 1–100。
- 时间采用 ISO 日期时间；投资命令的 `tradeTime` 与 `settlementTime` 为服务端 posting Instant。

### 1.2 认证与用户隔离

- `POST /api/v1/register` 与 `POST /api/v1/login` 公开。
- 其余业务路由需要 `Authorization: Bearer <JWT>`。
- health、OpenAPI 和 Swagger UI 由 Security 配置单独放行，不属于业务 Controller 数量。
- user ID 来自 JWT principal，业务请求体不接受 user ID。
- 不存在或跨用户资源统一返回安全 `404`，避免泄露资源存在性。

### 1.3 JSON 与金融值

- 公共投资命令以 JSON 字符串承载 quantity、unit price 和 amount，例如 `"12.50000000"`、`"99.90"`。调用方应提交普通定点十进制字符串，以保持命令哈希与审计输入稳定。
- 当前严格解析边界并不完全相同：DIVIDEND 与 replacement 的金额字段显式拒绝 JSON number 和科学计数法；首次 BUY、后续 BUY 与 SELL 使用 `BigDecimal(String)` 解析，并显式校验 scale，因此当前实现仍可接受能被 `BigDecimal` 解析且 scale 合法的指数形式字符串。
- DIVIDEND、standalone reversal 与 replacement 使用 closed request schema，显式拒绝未知字段；其他投资请求不得据此推定已具备相同的未知字段拒绝行为。
- 投资响应中的金融值同样是固定 scale 字符串，避免 JavaScript number 丢失精度。
- 请求不接受客户端计算的 gross/net/cash delta、receipt、projection、currency、user ID 或客户端入账时间。
- 普通 Account/Asset/Transaction API 仍使用其现有 DTO，不应套用投资命令字段。

### 1.4 幂等 header

| Header | 使用位置 | 规则 |
| --- | --- | --- |
| `X-Idempotency-Key` | Legacy opening `migration-confirm` | 1–100 字符；同请求恢复，同 key 不同请求冲突 |
| `Idempotency-Key` | first BUY、BUY、SELL、DIVIDEND、reversal、replacement | 非空、1–100 字符、无首尾空格；与 canonical SHA-256 request hash 配对 |

两个 header 名称是当前实现事实，不能统一猜测或互换。

## 2. 11 个 Controller

| Controller | 基础路由 | 职责 |
| --- | --- | --- |
| `UserController` | `/api/v1` | 注册与登录 |
| `AccountController` | `/api/v1/accounts` | 账户查询、创建、更新与停用 |
| `CategoryController` | `/api/v1/categories` | 分类查询、创建与系统分类初始化 |
| `TransactionController` | `/api/v1/transactions` | 普通流水查询与 CRUD |
| `AssetController` | `/api/v1/assets` | Legacy Asset 查询/写入及参考数据刷新 |
| `DashboardController` | `/api/v1/dashboard` | 用户财务概览 |
| `InvestmentInstrumentController` | `/api/v1/investment/instruments` | 用户级投资标的查询与创建 |
| `LegacyAssetMigrationController` | `/api/v1/investment/legacy-assets` | opening migration preview / confirm |
| `InvestmentCommandController` | `/api/v1/investment/positions` | first BUY、BUY、SELL、DIVIDEND |
| `InvestmentReversalController` | `/api/v1/investment/transactions` | standalone reversal |
| `InvestmentReplacementController` | `/api/v1/investment/transactions` | same-type replacement |

## 3. 基础财务与参考数据 endpoint

### 3.1 UserController

| 方法 | 路径 | 用途 | 认证 | 主要请求 | 主要响应 / 错误 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/register` | 注册用户 | 否 | `username`、`email`、`password` | 空 data；重复/校验错误 400 |
| POST | `/api/v1/login` | 登录并签发 JWT | 否 | `username`、`password` | `token`、`userId`、`username`；凭据错误 401 |

### 3.2 AccountController

| 方法 | 路径 | 用途 | 认证 | 主要请求 | 主要响应 / 错误 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/accounts` | 当前用户账户列表 | 是 | 无 | Account 数组 |
| GET | `/api/v1/accounts/page` | 账户分页 | 是 | `page`、`size` | `PageResult<AccountResponse>` |
| GET | `/api/v1/accounts/{id}` | 账户详情 | 是 | path id | id/name/type/currency/balance/status；404 |
| POST | `/api/v1/accounts` | 新建账户 | 是 | `name`、`type`、可选 `currency` | Account；400 |
| PUT | `/api/v1/accounts/{id}` | 更新账户元数据 | 是 | 同创建 | Account；404/409 |
| POST | `/api/v1/accounts/{id}/deactivate` | 停用账户 | 是 | path id | 空 data；404/409 |

账户余额不在公开请求 DTO 中；创建时初始化为零，后续只能由后端余额服务维护。

### 3.3 CategoryController

| 方法 | 路径 | 用途 | 认证 | 主要请求 | 主要响应 / 错误 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/categories` | 查询用户与系统分类 | 是 | 可选 `type` | Category 数组 |
| POST | `/api/v1/categories` | 新建用户分类 | 是 | `name`、`type`、`parentId`、`sortOrder` | Category；400/404 |
| POST | `/api/v1/categories/init` | 初始化系统分类 | 是 | 无 | 空 data |

### 3.4 TransactionController

| 方法 | 路径 | 用途 | 认证 | 主要请求 | 主要响应 / 错误 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/transactions/page` | 普通流水分页与筛选 | 是 | page/size、accountId、categoryId、type、start、end | `PageResult<TransactionResponse>` |
| GET | `/api/v1/transactions/{id}` | 普通流水详情 | 是 | path id | Transaction；404 |
| POST | `/api/v1/transactions` | 创建普通流水 | 是 | accountId、categoryId、type、amount、currency、description、transactedAt | Transaction；400/404/409 |
| PUT | `/api/v1/transactions/{id}` | 更新并重算余额影响 | 是 | 同创建 | Transaction；400/404/409 |
| DELETE | `/api/v1/transactions/{id}` | 删除并反转余额影响 | 是 | path id | 空 data；404/409 |

当前只接受 `INCOME`、`EXPENSE`、`ADJUSTMENT`；`TRANSFER` 与 `REFUND` 虽存在历史 schema 枚举，但 API 明确拒绝。

### 3.5 AssetController

| 方法 | 路径 | 用途 | 认证 | 主要请求 | 主要响应 / 错误 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/assets` | 当前用户 Asset 列表 | 是 | 无 | Asset 数组，可能含缓存行情/参考估值 |
| GET | `/api/v1/assets/page` | Asset 分页 | 是 | `page`、`size` | `PageResult<AssetResponse>` |
| GET | `/api/v1/assets/{id}` | Asset 详情 | 是 | path id | Asset；404 |
| POST | `/api/v1/assets` | 创建 Legacy Asset | 是 | name/symbol/type/market/currency/quantity/avgCost | Asset；400 |
| PUT | `/api/v1/assets/{id}/price` | 更新手工价格 | 是 | query `price` | Asset；400/404 |
| PUT | `/api/v1/assets/{id}/close` | 关闭 Legacy Asset | 是 | path id | Asset；404/409 |
| DELETE | `/api/v1/assets/{id}` | 删除 Legacy Asset | 是 | path id | 空 data；404/409 |
| POST | `/api/v1/assets/{id}/quote/refresh` | 显式刷新 owned US STOCK/ETF 行情 | 是 | path id | quote、freshness、refreshResult、warning；404/429/502/503 |
| POST | `/api/v1/assets/{id}/reference-valuation/refresh` | 显式刷新行情/FX 并计算参考估值 | 是 | path id | quote、FX、CNY value、freshness、warnings；404/429/502/503 |

transaction-driven Asset 不能通过 Legacy Asset 写端点改写受控账本字段。

### 3.6 DashboardController

| 方法 | 路径 | 用途 | 认证 | 主要响应 |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/dashboard` | 后端聚合当前用户财务概览 | 是 | 净资产、收支、趋势、资产分布、最近交易 |

### 3.7 InvestmentInstrumentController

| 方法 | 路径 | 用途 | 认证 | 主要请求 | 主要响应 / 错误 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/investment/instruments` | 用户级 Instrument 列表 | 是 | 无 | id/symbol/name/market/assetClass/quoteCurrency/status |
| POST | `/api/v1/investment/instruments` | 创建 Instrument | 是 | symbol/name/market/assetClass/quoteCurrency | Instrument；400/409 |

## 4. Opening migration contract

### 4.1 Preview

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/investment/legacy-assets/{assetId}/migration-preview` |
| 认证 | Bearer JWT |
| 幂等 header | 无；preview 为只读 |
| 请求字段 | `instrumentId`、`accountId` |
| 主要响应 | source、target、openingTransaction、expectedPosition、validation、confirmation |
| confirmation | `previewToken`、`requestHash`、`sourceVersion`、`expiresAt`、`formulaVersion` |
| 常见错误 | 400 不可迁移状态；404 跨用户/资源不存在；409 绑定冲突 |

Preview 不写事实、不改 Account、不切换 Asset mode。

### 4.2 Confirm

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/investment/legacy-assets/{assetId}/migration-confirm` |
| 认证 | Bearer JWT |
| 幂等 header | `X-Idempotency-Key` |
| 请求字段 | `previewToken` |
| 主要响应 | `assetId`、`transactionId`、`accountId`、`instrumentId`、`requestHash`、`idempotentReplay` |
| 写入效果 | 一个 `OPENING_POSITION`；重放 Asset；不改变 Account.balance |
| 常见错误 | 400 token/幂等键；404 资源；409 stale preview/冲突；500 consistency failure |

## 5. Investment write contract

### 5.1 First BUY

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/investment/positions` |
| 认证 / 幂等 | Bearer JWT；`Idempotency-Key` 必需 |
| 请求字段 | `accountId`、`instrumentId`、`quantity`、`unitPrice`、`feeAmount`、`taxAmount` |
| 用途 | 在同一事务中创建 transaction-driven Asset、BUY fact、余额影响和最终投影 |
| 常见错误 | 400 字段/金额；404 Account/Instrument；409 已有 Position、幂等或并发冲突 |

### 5.2 Subsequent BUY / SELL

| 方法 | 路径 | 请求字段 | 用途 | 常见错误 |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/investment/positions/{assetId}/buy` | quantity、unitPrice、feeAmount、taxAmount | 增持或重开 Position | 400/404/409/500 |
| POST | `/api/v1/investment/positions/{assetId}/sell` | quantity、unitPrice、feeAmount、taxAmount | 部分或全部卖出 | 400/404；oversell/replay/锁冲突 409；consistency 500 |

两者都需要 `Idempotency-Key`，不接受 transaction type、currency、time 或任何计算字段。

### 5.3 DIVIDEND

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/investment/positions/{assetId}/dividends` |
| 认证 / 幂等 | Bearer JWT；`Idempotency-Key` 必需 |
| 请求字段 | `grossAmount`；可选 `feeAmount`、`taxAmount`、`externalReference`、`note` |
| JSON 边界 | 金额必须为 plain decimal string；拒绝 unknown fields |
| 写入效果 | `+net` 现金；数量/成本/状态/PnL 不变；version 与 lastTransactionId 前进 |
| 常见错误 | 400 格式/金额；404 资源；409 history/幂等/锁冲突；500 consistency failure |

### 5.4 公共 InvestmentCommandResponse

BUY、SELL 与 DIVIDEND 返回：

- transactionId、assetId、accountId、instrumentId、transactionType；
- tradeTime、settlementTime；
- grossAmount、feeAmount、taxAmount、netAmount、cashDelta、balanceAfter；
- `idempotentReplay`、createdAt；
- `finalPosition`：quantity、avgCost、totalCost、realizedProfitLoss、status、projectionVersion、lastTransactionId。

## 6. Standalone reversal

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/investment/transactions/{transactionId}/reversal` |
| 认证 / 幂等 | Bearer JWT；`Idempotency-Key` 必需 |
| 请求字段 | 仅 `reason`；拒绝 unknown fields |
| eligible original | 原始 BUY、SELL 或 DIVIDEND；不能是 OPENING、reversal 或 correction fact |
| 写入效果 | 追加一条 REVERSAL；original 不变；一次余额和一次 Asset 投影 |
| 主要响应 | original/reversal ids、cashDelta、balanceAfter、最终 Position、idempotentReplay |
| 常见错误 | 400 reason/key；404 跨用户；409 重复纠正或候选 replay；500 二次 replay/consistency |

## 7. Replacement correction

| 属性 | 内容 |
| --- | --- |
| 方法与路径 | `POST /api/v1/investment/transactions/{transactionId}/replacement` |
| 认证 / 幂等 | Bearer JWT；`Idempotency-Key` 必需 |
| 类型 | 根据 original 自动选择 BUY、SELL 或 DIVIDEND schema；不能由客户端指定 |
| BUY/SELL 字段 | quantity、unitPrice、feeAmount、taxAmount、externalReference、note、reason |
| DIVIDEND 字段 | grossAmount、feeAmount、taxAmount、externalReference、note、reason |
| JSON 边界 | 金额为 plain decimal string；拒绝 unknown fields |
| 写入效果 | grouped reversal + same-type replacement fact + correction envelope |
| 主要响应 | correction/group/original/reversal/replacement ids、三段 cash delta、balanceAfter、finalPosition、lastTransactionId、idempotentReplay |
| 常见错误 | 400 schema/reason/key；404 original；409 ineligible、幂等或 replay；500 consistency failure |

相同 key 和相同 canonical request hash 返回已持久化的 immutable receipt；相同 key 不同请求返回 `409`。unknown commit 后，客户端使用相同 key 重试以恢复已提交结果或安全执行一次。

## 8. 错误语义

| HTTP | 当前语义 |
| --- | --- |
| 400 | 参数校验、请求体、strict decimal、幂等键、业务输入错误 |
| 401 | JWT 缺失、无效或登录失败 |
| 403 | 已认证但无权执行的安全边界 |
| 404 | 资源不存在或跨用户资源 |
| 409 | 幂等冲突、业务/replay 冲突、lock timeout、deadlock |
| 429 | Market/FX 刷新限流 |
| 500 | 脱敏的 replay/projection/receipt consistency failure 或未知内部错误 |
| 502 | Provider 响应格式、币对、价格或时间无效 |
| 503 | 数据库或外部 Provider 暂时不可用 |

错误响应不回显 SQL、堆栈、request hash、幂等键、纠正原因或敏感财务中间值。

## 9. 未实现边界

当前没有：

- Portfolio read API；
- InvestmentTransaction 列表、详情、用户时间线或审计查询 API；
- generic investment write endpoint；
- original fact 的 PUT/PATCH/DELETE；
- Position detail/correction UI contract；
- Phase 2C-1 的预定义 endpoint。

这些能力必须在后续范围中先定义 contract，再实施；不得从现有写接口或数据库表名直接外推。
