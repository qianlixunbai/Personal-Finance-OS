# 业务规则

## 用户、账户、分类与普通流水

用户拥有的财务事实和投影严格按用户隔离；跨用户资源使用安全 `404`。共享系统分类、Market Quote 和 FX 是例外。账户创建时余额为零，后续余额变更仅由 `AccountBalanceService` 在事务中维护；账户可停用。分类为收入/支出分类，支持用户分类和系统分类。

普通 `Transaction` 与 `InvestmentTransaction` 是不同账本。普通流水仅含 `INCOME`、`EXPENSE`、`ADJUSTMENT`，可创建、更新、删除；写入锁定相关账户，更新/删除先反转旧影响再应用新影响。允许负余额，停用账户不接受新的普通影响。

## Asset、Instrument 与 opening migration

Legacy Asset 是既有手工资产模型，可使用既有创建、价格、关闭、删除语义；系统不会自动推断、合并或迁移它。transaction-driven Asset 是 `(user, account, instrument)` 的唯一受控 Position 投影，不能通过 Legacy Asset API 改写账本字段。

Instrument 是用户级主数据，以 `(user, market, symbol)` 唯一标识。opening migration 必须显式、单 Asset 执行：preview 只读，confirm 需要签名过期 token 和幂等键；它追加一个 `OPENING_POSITION`、重放投影并切换 Asset 模式，但不改变 Account 余额。

## 投资事实与纠正

BUY、SELL、DIVIDEND 记录已发生的手工投资活动，不下单、不连接券商。命令按固定顺序锁定资源，只通过 `AccountBalanceService` 修改现金，重放全历史并更新 Asset 投影和 immutable receipt。SELL 不得超卖；持仓可关闭并被后续 BUY 重新打开；DIVIDEND 不改变数量和成本。

original fact 永不 UPDATE/DELETE。standalone reversal 为一个 eligible 原始 BUY/SELL/DIVIDEND 追加独立 reversal，原事实从有效重放中排除。replacement 是 grouped reversal 加同类型 replacement fact 的原子纠正，不是 `REPLACEMENT` 类型；replacement 占据 original logical slot。grouped reversal 不是独立外部命令，最终回执位于 correction envelope。

posting-time receipt 是不可变审计快照；canonical replay 与当前 Asset projection 才是 corrected current truth。命令的 request hash 和 idempotency key 支持回放恢复；业务失败时事实、余额、投影和回执整体回滚。

## 并发、参考数据与边界

锁超时/死锁映射为可重试 `409`，不自动重试；unknown commit 通过幂等恢复返回持久结果。Dashboard 由后端计算。手工估值、行情、FX 和 reference valuation 仅供展示，不覆盖账务或账本投影。Portfolio/read API、投资 UI、历史收益、多币种、FIFO/lot、公司行动、自动同步和 AI 写入均未实现。
