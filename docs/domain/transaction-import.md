# Transaction Import

本文是当前普通流水 CSV / XLSX Import 的权威领域规则。HTTP 路径见 [API](../architecture/api.md)，事务机制见 [Consistency](../architecture/consistency.md)。当前验收状态见 [STATUS](../STATUS.md)。

## 1. 范围

Import 只创建普通：

- `INCOME`；
- `EXPENSE`；
- `ADJUSTMENT`。

它不导入 `TRANSFER`、`REFUND`、BUY、SELL、DIVIDEND 或其他投资事实，也不连接银行/券商 API。

## 2. 工作流

```mermaid
flowchart LR
    U[Upload] --> M[Mapping]
    M --> P[Server Preview]
    P --> W[Warning Review]
    W --> C[Confirm]
    C --> R[Authoritative Receipt]
```

1. 上传文件，服务端建立用户级 Session 和预分配 Batch ID；
2. 显式映射源列、交易类型、Account 与 Category；
3. 服务端规范化、校验并生成 frozen PreviewPlan；
4. 用户明确确认全部 warning；
5. 客户端提交 signed preview token、warning IDs 与 `Idempotency-Key`；
6. 后端在单一事务内创建 Transactions、更新 balances 并保存 Receipt evidence；
7. Receipt 页面通过 Batch GET 读取权威结果。

Preview 不创建 Transaction，不写 `transactions`，也不修改 Account.balance。

## 3. 文件契约

### 通用限制

| 项目 | 限制 |
| --- | --- |
| 格式 | CSV、XLSX |
| 文件大小 | 最大 5 MiB |
| 数据行 | 1–10,000 |
| 列数 | 最大 32 |
| header 长度 | 最大 128 Unicode code points |
| cell / CSV value | 最大 4,096 Unicode code points |
| 解析 deadline | 10 秒 |

文件扩展名、请求 format 与 content type 必须一致。客户端原文件名经清洗后只用于展示，不参与服务器存储路径。

### CSV

- 必须为严格 UTF-8，可有 BOM；
- 支持 LF 或 CRLF，拒绝裸 CR；
- header 非空、NFC 规范化后唯一；
- 每行列数必须与 header 一致；
- 空数据行忽略；
- 拒绝 NUL、非法编码和超限内容。

### XLSX

- 必须是安全 ZIP archive；
- 只允许一个可见 worksheet；
- 拒绝隐藏 row/column、merged cell、formula、boolean/error cell；
- 拒绝 external relationship、DTD、entity、XInclude 与外部 schema；
- 限制 archive entries、单 entry、总展开大小、压缩比、XML node、attribute 和 depth；
- 日期 cell 不能携带非零 time component。

完整安全边界见 [Security](../architecture/security.md)。

## 4. Mapping

Mapping 包含：

- `columnMappings`：目标字段 → 源 header；
- `typeMappings`：源 type 值 → `INCOME|EXPENSE|ADJUSTMENT`；
- `accountMappings`：源 Account 值 → owned Account ID；
- `categoryMappings`：源 Category 值 → visible Category ID。

必需目标字段：

```text
date, type, amount, account, category
```

可选目标字段：

```text
time, description, currency
```

一个源列不能映射到多个目标；未使用的源列必须显式标记 `IGNORE`。mapping key 使用 NFC + trim 规范化，规范化后冲突会被拒绝。

不完整 mapping 保持 `MAPPING_REQUIRED`；完整 mapping 在同一 Session 上生成新的 revision 和 `PREVIEW_READY` plan。

## 5. Row 规范化与校验

### Amount

- 只接受普通定点十进制，最多 16 位整数和 2 位小数；
- 规范化为 scale 2；
- `INCOME` / `EXPENSE` 必须大于零；
- `ADJUSTMENT` 必须非零；
- 余额 effect 复用 [Financial Rules](financial-rules.md) 的普通流水公式。

### Date / Time / Currency

- date 必须为 `YYYY-MM-DD`；
- time 可省略，默认 `00:00:00`，否则为 `HH:mm` 或 `HH:mm:ss`；
- currency 可省略，默认 CNY；其他币种拒绝。

### Description

- 最大 500 Unicode code points；
- 规范化 CRLF / CR 为 LF；
- 拒绝控制字符；
- `ADJUSTMENT` 必须有非空 description。

### Account / Category

- Account 必须属于当前用户、ACTIVE 且 currency=CNY；
- Category 必须是用户自己的或系统 Category；
- `INCOME` / `EXPENSE` 必须匹配 Category type；
- Confirm 锁内重新校验当前 Account / Category，Preview 结果不能覆盖后续状态变化。

## 6. Duplicate 语义

### In-file probable duplicate

同一文件中规范化后使用 authoritative Account / Category ID 的 row fingerprint 相同，会生成 `IN_FILE_PROBABLE` warning。

### Database probable duplicate

服务端按 Account、Category、type、amount、transactedAt 和 description 查找现有候选，生成 `DATABASE_PROBABLE` warning。

### Warning identity

warning ID 绑定 Session、revision、canonical row 和证据。用户必须精确 acknowledgement 当前 plan 的全部 warning IDs；伪造、缺失、过期或复用旧 revision 的 ID 会被拒绝。

### Confirm recheck

Account locks 取得后，后端重新查询 database duplicate evidence。候选新增或消失都会返回 `IMPORT_DUPLICATE_EVIDENCE_CHANGED`，要求重新 Preview。

### Exact duplicate

已确认 Batch 的 user、file digest、mapping digest、options digest 与 normalized rows digest 全部相同时是 exact duplicate，Confirm 返回冲突，而不是再次写入。

probable duplicate 是用户可确认 warning；exact duplicate 是服务端拒绝条件，二者不能混淆。

## 7. Session 生命周期

Session TTL 固定为 15 分钟，状态为：

```text
MAPPING_REQUIRED
PREVIEW_READY
EXPIRED
CANCELLED
CONSUMED
```

- 创建时 revision=0；每次 materialized Preview 推进 revision；
- mapping 不完整会使已有 Preview 失效；
- cancel 只作用于仍可用 Session，并清理 payload / plan；
- cleanup 在应用 ready 时执行一次，随后默认每分钟扫描；
- 过期、取消和消费后的 Session 不能再次用于新 Confirm；
- committed replay 可在 token 或临时 plan 失效后通过 Batch persistence 恢复。

## 8. Frozen Preview Token

production token 使用 HMAC-SHA256，secret 对应 `FINANCE_IMPORT_CONFIRM_TOKEN_SECRET`，必须显式配置且至少 32 字符。

token 绑定：

- contract version `TRANSACTION_IMPORT_CONFIRM_V1`；
- user、Session、预分配 Batch；
- revision；
- file / mapping / options / normalized rows digest；
- warning-set digest；
- issuedAt 与 expiresAt。

tamper、cross-user、cross-session、stale revision、digest mismatch、warning mismatch 或过期均 fail closed 为 stale preview。

## 9. Confirm

Confirm 请求只包含：

- path 中的 Session ID；
- `Idempotency-Key`；
- `previewToken`；
- `acknowledgedWarningIds`。

客户端不重新提交 rows、金额、Account impacts、Transaction IDs 或 Receipt。

事务顺序：

```text
配置 transaction lock_timeout
→ Session FOR UPDATE
→ committed/idempotency/exact-duplicate lookup
→ frozen binding 与 token recheck
→ Account ID ascending locks
→ duplicate evidence recheck
→ Category recheck
→ 按 rowNumber 写 Transactions
→ 按 Account 聚合并应用 balance delta
→ 写 Batch / Items / Account Impacts
→ Session CONSUMED
→ commit
```

全部 Transaction、余额、Batch、Items、Impacts 和 Session 状态要么一起提交，要么一起回滚。Confirm transaction timeout 为 60 秒。

## 10. 幂等与并发

- key 在用户域内唯一；
- request hash 绑定 user、Session、Batch、revision、四类 digest 和 warning IDs；
- 同 key 同 intent 返回同一 authoritative Receipt，`idempotentReplay=true`；
- 同 key 不同 intent 返回 `IMPORT_IDEMPOTENCY_CONFLICT`；
- 同一 Session 的并发 Confirm 先由 Session row lock 串行；
- 只对完整匹配 SQLSTATE、schema、table 与 expected named constraint 的 unique race执行 defense-in-depth recovery；
- 找不到完全匹配的 committed Batch 时返回一致性失败，不伪造成功；
- lock timeout / deadlock 返回 `IMPORT_LOCK_CONFLICT`，不自动生成新 key 重试。

## 11. Receipt

Receipt 从 committed persistence 重建，包含：

- Session / Batch ID 与状态；
- source file name 与 file digest；
- total / accepted / created / skipped / warning counts；
- 按 source row 排序的 Transaction references；
- 按 Account 排序的 row count、balanceBefore、delta、balanceAfter；
- confirmedAt、contractVersion 与 resultDigest。

Batch、Item 和 Account impact 由数据库 trigger 保护为不可变。重建时会验证 count、created transaction binding、`balanceAfter = balanceBefore + delta` 与 resultDigest；不一致返回 `IMPORT_CONFIRM_INCONSISTENT`。

临时文件和 PreviewPlan 删除后，Receipt GET 仍应工作。

## 12. 前端恢复

- Confirm 前先持久化 owner-bound frozen intent；
- 同一 intent 的所有 POST 保持完全相同的 key、path 与 `bodyJson`；
- unknown outcome 先 GET Receipt，只有 404 才允许回放同一 POST；
- 一旦观察到 HTTP 200，状态保持 `COMMITTED_AWAITING_RECEIPT`，只允许 GET；
- 401 只暂停并绑定同一用户的 continuation，不改变 frozen intent；
- 用户切换不得恢复另一用户的 pending intent；
- Web Lock 与 storage event 降低多标签页重复提交，后端幂等仍是最终权威；
- Receipt route 每次进入或 reload 都从服务端 GET，不持久化余额或 Receipt 内容；
- 金融字符串使用 lossless transport，不转为 JavaScript Number。

## 13. 当前限制

- Receipt transaction references 后端一次返回全部，前端只做展示分页；
- 只支持本地临时文件存储，不是分布式对象存储方案；
- 不支持异步大批量导入；
- 不支持投资、TRANSFER / REFUND、银行或券商自动导入；
- 当前 Compose 未传递 Import Confirm token secret，部署限制见 [Deployment](../engineering/deployment.md)。
