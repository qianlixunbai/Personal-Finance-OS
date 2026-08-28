# Business Rules

本文是当前通用业务规则的权威位置。投资账本和 Transaction Import 的专门规则分别见 [Investment Ledger](investment-ledger.md) 与 [Transaction Import](transaction-import.md)；金额公式见 [Financial Rules](financial-rules.md)。

## 1. 用户与数据隔离

1. JWT principal 是当前 user ID，业务请求不得自行声明 user ID。
2. Account、用户 Category、普通 Transaction、Asset、Instrument、投资事实、correction command 与 Import 记录按用户隔离。
3. 查询、锁定和写入必须同时使用 `user_id` 与资源 ID。
4. 不存在与跨用户资源使用相同安全 `404`，不得泄露资源存在性。
5. 系统 Category、Market Quote 与 FX snapshot 是共享例外，但不能借此读取其他用户的财务事实。

## 2. Account

1. 新 Account 的余额初始化为零，公开请求 DTO 不接受 balance。
2. `AccountBalanceService` 是 `Account.balance` 的生产写入原语。
3. 余额允许为负；当前没有 insufficient-balance 拒绝规则。
4. Account 元数据更新与余额写入使用兼容的账户行锁，避免覆盖并发余额。
5. 停用 Account 不接受新的普通流水、Import rows 或 first BUY；纠正历史影响的受控路径可处理 inactive Account。
6. 当前账务 Account currency 为 CNY。

## 3. Category

1. Category type 只允许 `INCOME` 或 `EXPENSE`。
2. 用户 Category 只属于创建者；系统 Category 可被所有用户读取和用于普通流水。
3. `INCOME` 必须使用收入 Category，`EXPENSE` 必须使用支出 Category。
4. `ADJUSTMENT` 不要求 Category type 与金额正负方向对应，但必须提供非空 description / reason。
5. 父子关系与排序只组织分类，不改变金额计算。

## 4. 普通 Transaction

1. 当前只支持 `INCOME`、`EXPENSE`、`ADJUSTMENT`；`TRANSFER`、`REFUND` 不是已实现契约。
2. `INCOME` / `EXPENSE` 保存正数金额；`ADJUSTMENT` 保存非零有符号金额。
3. 创建在同一事务中插入 Transaction 并应用一次余额 delta。
4. 更新先锁定旧 Transaction，再按 Account ID 升序锁定涉及账户，反转旧 effect 后应用新 effect。
5. 删除先锁定 Transaction 与 Account，删除事实后反转旧 effect。
6. 任一校验、写入或余额更新失败时，Transaction 与余额整体回滚。
7. 新增和更新的目标 Account 必须 ACTIVE；删除旧流水可以在 inactive Account 上反转历史影响。
8. 普通 Transaction 可更新/删除；该语义不得用于 `InvestmentTransaction`。

## 5. Legacy Asset

1. Legacy Asset 是用户手工维护的资产快照，可创建、更新手工价格、关闭和删除。
2. Legacy Asset 可以不绑定 Account 或 Instrument，系统不自动推断或合并。
3. Legacy Asset API 不得修改 transaction-driven Asset 的受控账本字段。
4. `currentPrice` 与 `marketValue` 是参考展示，不是投资成本真值。
5. 迁移到投资账本必须走显式 opening preview / confirm，不能直接切换 mode。

## 6. Transaction-driven Asset

1. `Asset` 是唯一当前 Position 投影，不新增第二套 Position 真值。
2. transaction-driven Asset 必须绑定同一用户的 Account 与 Instrument。
3. 同一 `(user, account, instrument)` 只允许一个 transaction-driven Position。
4. quantity、average/total cost、cumulative realized PnL、status、lastTransactionId 与 projectionVersion 只能由投资写路径和 replay 更新。
5. 全卖关闭 Position；后续 BUY 可以重新打开。
6. currentPrice 与 marketValue 不属于 replay 字段，纠正投资事实时必须保留。

具体投资命令、纠正和 replay 规则见 [Investment Ledger](investment-ledger.md)。

## 7. Market Quote、FX 与参考估值

1. Market Quote 与 FX 只通过显式 refresh 调用 Provider；普通 GET 不访问外部服务。
2. Provider 默认关闭，启用后仍受 timeout、TTL、用户级/全局限流和 single-flight 约束。
3. reference valuation 使用 Asset quantity、quote 和 FX 计算参考 CNY 值，不持久化为账务事实。
4. 参考数据不得修改 Account.balance、普通 Transaction、InvestmentTransaction、Asset 成本投影或 Dashboard 账务真值。
5. Provider 不可用时应返回受控错误或 stale fallback，不得伪造价格。

## 8. Dashboard

1. Dashboard 聚合由后端完成，前端只展示后端结果。
2. 普通收支趋势只基于当前支持的普通 Transaction 语义。
3. Market Quote、FX 与 reference valuation 不覆盖 Dashboard 账务真值。
4. 静态 Demo 使用虚构数据，不代表真实后端聚合或用户数据。

## 9. Transaction Import

1. Import 只创建普通 Transaction，不创建投资事实。
2. mapping、规范化、校验、duplicate warning、Confirm 和 Receipt 由服务端权威执行。
3. Preview 完全不写 `transactions` 或 Account.balance。
4. Confirm 必须消费同一用户、未过期、未取消的 frozen Preview，并在单个数据库事务内提交全部 rows 或全部回滚。
5. probable duplicate 是 warning，需要显式 acknowledgement；exact duplicate 和 evidence drift 会拒绝 Confirm。
6. Import 创建的普通 Transaction 后续遵循普通 Transaction 的正式 CRUD 语义。

完整文件、安全、Session、幂等和恢复规则见 [Transaction Import](transaction-import.md)。

## 10. 错误边界

| 类别 | 语义 |
| --- | --- |
| 400 | 参数、格式、mapping 或业务输入错误 |
| 401 | 未认证或 token 无效 |
| 403 | 安全层拒绝 |
| 404 | 资源不存在或跨用户不可见 |
| 409 | 幂等、并发、replay、Session、warning 或 duplicate 冲突 |
| 413 / 415 | Import 资源限制或媒体类型不支持 |
| 429 / 502 / 503 | Provider 限流、坏响应或暂时不可用 |
| 500 | 脱敏的一致性失败或未知错误 |

错误和日志不得泄露 SQL、堆栈、JWT、secret、幂等键、request hash、纠正 reason 或敏感金融中间值。

## 11. 当前边界

当前阶段、已实现能力与未完成范围只在 [STATUS](../STATUS.md) 维护。产品明确边界见 [Scope and Non-goals](../product/scope-and-non-goals.md)。
