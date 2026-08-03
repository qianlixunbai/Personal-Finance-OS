# 业务规则

本文是当前实现的正式业务规则，不记录阶段开发流水。金融公式见 [Financial Rules](Financial%20Rules.md)，数据库约束见 [Database](03-Architecture/Database.md)，接口字段见 [API](03-Architecture/API.md)。

## 1. 用户与数据隔离

1. JWT 认证后的 principal 是当前 user ID；业务请求不能自行声明 user ID。
2. Account、用户分类、普通流水、Asset、Instrument、投资事实与纠正命令均按用户隔离。
3. 查询和写入必须同时使用 `user_id` 与资源 ID；不存在和跨用户资源都返回安全 `404`。
4. 系统分类、Market Quote 与 FX snapshot 是共享数据例外，但不能借此访问其他用户的财务事实。
5. 错误响应不得泄露 SQL、堆栈、request hash、幂等键、纠正原因或内部财务中间值。

## 2. 账户

1. 新账户余额初始化为零；公开 Account DTO 不接受 balance。
2. `AccountBalanceService` 是后续 `Account.balance` 的唯一生产写入者。
3. 余额允许为负；当前没有 insufficient balance 规则。
4. 账户状态和元数据更新使用与余额写入一致的账户行锁，避免覆盖并发余额。
5. 停用账户不接受新的普通流水影响或 BUY；历史删除/反转可在不要求 active 的路径中修正旧影响。
6. CNY v1.x 中，普通流水币种必须为 CNY 且与账户币种一致。

## 3. 分类

1. 分类类型仅为 `INCOME` 或 `EXPENSE`。
2. 用户分类只属于创建者；系统分类可被所有用户读取和用于普通流水。
3. INCOME 流水必须使用收入分类，EXPENSE 必须使用支出分类。
4. ADJUSTMENT 不要求分类类型与正负方向对应，但必须提供非空原因。
5. 父分类关系和排序仅用于分类组织，不改变账务计算。

## 4. 普通流水

1. 普通 `Transaction` 只支持 `INCOME`、`EXPENSE`、`ADJUSTMENT`；`TRANSFER`、`REFUND` 当前拒绝。
2. INCOME/EXPENSE 金额必须为正；ADJUSTMENT 为非零有符号金额。
3. 创建在一个事务中写流水并应用一次余额 delta。
4. 更新先锁定旧流水，再按 account ID 升序锁定涉及的账户；先反转旧 effect，再应用新 effect。
5. 删除先锁定流水和账户，删除事实后反转旧 effect。
6. 任一校验、写入或余额更新失败时，流水与余额整体回滚。
7. 普通流水可更新/删除；该语义不得套用到 `InvestmentTransaction`。

## 5. Legacy Asset

1. Legacy Asset 是用户手工维护的资产快照，可使用既有创建、价格更新、关闭和删除语义。
2. Legacy Asset 可不绑定 Account 或 Instrument，系统不会自动推断、合并或迁移。
3. Legacy Asset API 不得修改 transaction-driven Asset 的账本字段。
4. 手工 `currentPrice` 和 `marketValue` 属于参考展示，不成为投资账本成本真值。

## 6. Transaction-driven Asset

1. `Asset` 是唯一当前 Position 投影，不另建第二套 Position 真值。
2. transaction-driven Asset 必须绑定同一用户的 Account 与 Instrument。
3. `(user, account, instrument)` 只能存在一个 transaction-driven Position。
4. quantity、avgCost、totalCost、realized PnL、status、lastTransactionId 与 projectionVersion 只由 replay/write path 更新。
5. 全卖使 quantity、avgCost、totalCost 归零并关闭 Position；后续 BUY 可以重新打开。
6. currentPrice 与 marketValue 不属于账本重放字段，reversal/replacement 必须保留它们。

## 7. Investment Instrument

1. Instrument 是用户级主数据，身份为 `(user, market, symbol)`。
2. symbol、market、assetClass、quoteCurrency 必须满足数据库规范；当前投资账务 currency 为 CNY。
3. first BUY 需要 ACTIVE Account 和 ACTIVE Instrument。
4. 已存在 Position 的 SELL、DIVIDEND 和纠正允许在 ACTIVE/INACTIVE Account 与 Instrument 上处理历史事实，但绑定必须仍然有效。
5. `InvestmentTransaction` 不保存 instrumentId；通过不可变 Asset binding 关联 Instrument。

## 8. Opening Migration

1. 只允许一次处理一个 Legacy Asset；不提供批量或自动迁移。
2. 调用者显式选择已存在的 CNY Account 和 ACTIVE、同用户 Instrument。
3. preview 完全只读，返回来源、目标、OPENING 计算、预期 Position、阻塞项、warning 与签名 token。
4. confirm 要求未过期且与当前 source version 一致的 preview token，以及 `X-Idempotency-Key`。
5. confirm 的锁顺序是 Account → Instrument → Asset。
6. 成功时只追加一个 `OPENING_POSITION`，重放并切换 Asset 为 transaction-driven；Account.balance 不变。
7. `Asset.totalCost` 非空时为权威；仅为 null 时才从 quantity × avgCost 推导。
8. 零持仓、无效成本、stale preview 或无法精确重现总成本的 Asset 保持 LEGACY。

## 9. BUY / SELL / DIVIDEND

### BUY

1. first BUY 原子创建 Position、BUY fact、余额影响与最终投影，临时空 Position 不会提交。
2. subsequent BUY 作用于已绑定 Asset；关闭 Position 可被 BUY 重新打开。
3. BUY 资本化 fee 和 tax，现金 delta 为负，不检查余额是否足够。

### SELL

1. SELL 只能作用于有效持仓，数量不得超过当前 replay quantity。
2. 部分卖出按加权平均成本释放成本，不得留下正数量但零/负成本。
3. 全卖归零并关闭 Position；现金 delta 为扣费税后的正 net。

### DIVIDEND

1. DIVIDEND 仅记录已收到的手工 CNY 现金分红，不是应计、公司行动或券商同步。
2. 必须存在更早的有效 BUY 或 OPENING；不能创建 Position。
3. OPEN 和 CLOSED Position 均可记录 DIVIDEND。
4. DIVIDEND 不改变数量、成本、状态或累计 realized PnL，但会推进 version 与 lastTransactionId。
5. net 可为零；fee + tax 不得超过 gross。

三类命令都由服务端生成相同的 trade/settlement posting Instant，不支持历史回填。

## 10. Standalone Reversal

1. 可纠正的 original 仅为原始 BUY、SELL、DIVIDEND。
2. original fact、status、金额、request hash 与 receipt 永不修改或删除。
3. reversal 追加一条 POSTED/CORRECTION/REVERSAL fact，复制必要审计值并应用相反现金 delta。
4. replay 从有效业务事实集合中排除 original；REVERSAL 自身不进入 calculator。
5. 一个 original 最多纠正一次；不能 reversal OPENING、reversal、replacement fact 或已有纠正的事实。
6. standalone reversal 保存完整最终 receipt，并使 Account 与 Asset 各更新一次。

## 11. Replacement Correction

1. replacement 只针对原始 BUY、SELL、DIVIDEND，且 replacement fact 必须与 original 同类型。
2. 一个 replacement 由 grouped reversal、same-type replacement fact 和 correction envelope 组成。
3. grouped reversal 不是外部 standalone command，不保存最终 Position receipt。
4. replacement fact 使用 original 的币种、trade time、settlement time 与 logical replay slot。
5. `replay_anchor_transaction_id = original.id`，`replay_sequence = 1`；原始事实不修改。
6. 写入采用 facts-first / command-last；envelope 存在即表示完整命令已提交，不存在 PENDING 状态。
7. correction envelope 保存 original/reversal/replacement ids、三段 cash delta、最终余额、最终 Position 和 version。
8. original 最多有一个 standalone reversal 或 replacement group，不能形成 correction chain。

## 12. 幂等

1. opening confirm 使用 `X-Idempotency-Key`；其他投资写命令使用 `Idempotency-Key`。
2. key 在用户域内唯一，长度 1–100，不能为空或带首尾空格。
3. canonical request hash 使用固定字段顺序、固定 scale、明确 null marker 与 SHA-256。
4. 同 key、同 hash 返回已保存的 immutable receipt，并标记 `idempotentReplay=true`。
5. 同 key、不同 hash 返回 `409`，不会执行第二次 effect。
6. 并发唯一键竞争只识别预期 named constraint；未知 unique violation 不得误判为成功回放。
7. unknown commit 后使用同 key 重试：若已提交则恢复结果，若未提交则安全执行一次。

## 13. 并发与锁

1. 普通流水全局顺序：existing fact → deduplicated Account rows（accountId 升序）→ future projection。
2. first BUY/BUY/SELL/DIVIDEND：Account → Instrument → Asset。
3. standalone reversal/replacement：Original InvestmentTransaction → Account → Instrument → Asset。
4. 所有锁查询都带 user ownership；跨用户与不存在保持同一 `404`。
5. PostgreSQL `lock_timeout` 默认 4 秒，只对当前事务生效。
6. lock timeout (`55P03`) 和 deadlock victim (`40P01`) 映射为可重试 `409`。
7. 服务端不自动重试金融写命令；客户端通过幂等 key 控制重试。

## 14. 审计与不可变性

1. InvestmentTransaction、posting receipt 与 correction command 都是不可变审计记录。
2. 数据库 trigger 拒绝投资事实和 correction command 的 UPDATE/DELETE。
3. posting-time snapshot 只描述当时结果，不因后续纠正而变化。
4. corrected current truth 来自完整 canonical replay 与当前 Asset projection。
5. replay trace 与 digest 描述业务重放，不包含创建时间、随机 group id 或 replacement 物理 ID。

## 15. 行情、FX 与参考估值

1. Market Quote 与 FX 只通过显式 refresh 访问 Provider；普通 GET 不调用外部服务。
2. Provider 默认关闭，启用时受超时、TTL、用户级/全局限流和 single-flight 约束。
3. reference valuation 使用 quantity、quote 和 FX 计算参考 CNY 值，不持久化为账本事实。
4. 参考数据不得修改 Account.balance、普通流水、InvestmentTransaction、Asset 成本投影或 Dashboard 账务真值。

## 16. Dashboard

1. Dashboard 聚合由后端完成，前端只展示后端结果。
2. 普通收支趋势只基于当前支持的普通流水语义。
3. 投资参考行情与 FX 不覆盖 Dashboard 中的账务真值。
4. 静态 Demo 使用虚构数据，不代表真实后端聚合结果或投资写前端。

## 17. 错误与安全边界

1. 校验和请求格式错误为 400；认证失败为 401；授权边界可为 403。
2. 资源缺失与跨用户访问为 404。
3. 幂等、业务 replay、锁和并发冲突为 409。
4. 内部 replay/projection/receipt consistency failure 脱敏后为 500，并整体回滚。
5. 外部 Provider 限流/坏响应/不可用分别映射为 429/502/503。

## 18. 未实现能力

当前未实现 Portfolio/read API、InvestmentTransaction 查询/详情/时间线、投资前端、Position 纠正 UI、TRANSFER/REFUND、多币种账务、FIFO/lot、公司行动、历史收益、银行或券商同步、真实交易执行与 AI 写入。

Phase 2C-1 尚未开始；不得在当前规则中提前定义其 endpoint 或读模型。
