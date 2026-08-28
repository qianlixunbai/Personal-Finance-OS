# Consistency

本文描述当前金融事务、锁、幂等、重放、回滚和投影一致性机制。具体业务与公式分别见 [Business Rules](../domain/business-rules.md)、[Investment Ledger](../domain/investment-ledger.md) 和 [Transaction Import](../domain/transaction-import.md)。

## 1. 权威边界

- `AccountBalanceService` 是 `Account.balance` 的生产写入原语；
- 普通 Transaction、投资事实和 Import Confirm 通过后端 Service 编排，不允许前端直接计算最终 effect；
- transaction-driven `Asset` 是唯一当前 Position 投影；
- investment receipt 与 Import receipt 是历史命令结果，不替代当前 Account / Asset；
- Market Quote、FX 和 reference valuation 不参与账务一致性。

## 2. 普通 Transaction

创建：

```text
校验 Account / Category / type
→ 锁 Account
→ 插入 Transaction
→ 应用一次 balance delta
→ commit
```

更新/删除先锁定旧 Transaction，再按 Account ID 升序锁定去重后的相关账户。更新使用 `-oldEffect + newEffect`，删除使用 `-oldEffect`。任一校验或写入失败，Transaction 与 balance 一起回滚。

## 3. 投资写路径

### 锁顺序

| 命令 | 顺序 |
| --- | --- |
| first BUY / BUY / SELL / DIVIDEND | Account → Instrument → Asset |
| standalone reversal / replacement | Original InvestmentTransaction → Account → Instrument → Asset |
| opening migration | Account → Instrument → Asset |

### 事务不变量

```text
幂等与资源校验
→ 固定顺序加锁
→ candidate canonical replay
→ 写 append-only fact
→ 一次 Account.balance 更新
→ 从数据库事实二次 replay
→ 一次 Asset 投影
→ receipt / command envelope
→ consistency check
→ commit
```

replacement 写入 grouped reversal 与 same-type replacement fact，最后写 correction envelope。V13 deferred integrity 禁止提交不完整 group。任何 failure injection 点失败，facts、balance、projection、receipt 和 envelope 全部回滚。

## 4. Transaction Import Confirm

锁顺序固定为：

```text
Session FOR UPDATE
→ Account ID ascending
```

锁内重新验证 Session、frozen plan binding、exact duplicate、Account/Category 可见性和 database duplicate evidence。随后：

1. 按 source row 顺序创建普通 Transaction；
2. 按 Account 聚合 balance delta，并对每个 Account 只应用一次；
3. 写 Batch、Items 与 Account Impacts；
4. 将 Session 从 `PREVIEW_READY` 改为 `CONSUMED`；
5. 单个 PostgreSQL transaction 提交；
6. 提交后再清理临时 payload。

临时清理失败不回滚已提交金融事实；receipt 可以只依赖 committed persistence 重建。

## 5. 幂等

投资与 Import 命令都将用户域 key 与 canonical request hash 配对：

- 同 key、同 intent：返回已持久化 receipt，不重复 effect；
- 同 key、不同 intent：409；
- 并发 unique race：只识别预期 PostgreSQL constraint identity；
- 找不到与当前 user、Session/transaction、key、hash 完整一致的 committed result 时 fail closed；
- unknown commit 后只能以同一冻结意图恢复。

Import 还使用预分配 Batch ID 与 `(user_id, session_id)` 唯一约束；normal same-Session concurrency 由 Session row lock 串行，named-constraint recovery 只作为 defense-in-depth fallback。

## 6. 确定性重放

投资 canonical ordering key 为：

```text
(effectiveTradeTime, effectiveAnchorId, replaySequence, factId)
```

- ordinary fact 使用自身 time / id，sequence=0；
- replacement fact 使用 original time / anchor，sequence=1；
- reversal 与被纠正 original 不进入 calculator；
- OPENING 必须是首个有效事实；
- DIVIDEND 前必须存在有效 BUY 或 OPENING。

candidate replay 与数据库内二次 replay 比较 trace/digest。digest 使用规范化数值和逻辑身份，不包含创建时间、随机 correction group 或 replacement 物理 ID。

## 7. 投影版本与回执

- 单个投资命令只更新 Account 一次、投影 Asset 一次；
- `projectionVersion` 每个外部命令只前进一步；
- `lastTransactionId` 指向最终有效 fact；
- posting-time receipt 永远保持当时快照；
- corrected current truth 来自完整 replay、当前 Asset 与相应 correction receipt；
- Import `resultDigest` 绑定 Session、Batch、排序后的 references、Account impacts 与 confirmedAt。

## 8. 并发冲突

事务级 `lock_timeout` 默认 4 秒。PostgreSQL `55P03` 和 `40P01` 映射为冲突，服务端不盲目自动重试金融命令。客户端是否恢复取决于是否拥有同一幂等意图和权威 receipt 查询路径。

普通 Transaction 与 Import Confirm 共享 Account locks 和 balance mutation。Import 在 Account lock 后复核 duplicate evidence，避免 Preview 与 Confirm 之间的并发写入被静默忽略。

## 9. 失败语义

| 失败点 | 结果 |
| --- | --- |
| 参数、ownership、业务规则 | 写入前拒绝 |
| lock timeout / deadlock | 冲突；事务回滚 |
| fact / Transaction insert | 整体回滚 |
| balance update | 整体回滚 |
| replay / projection mismatch | 脱敏 500；整体回滚 |
| receipt / envelope / Batch evidence 写入 | 整体回滚 |
| response delivery unknown | 使用同 key 查询或恢复 committed receipt |
| Import 提交后临时文件清理失败 | 金融提交保持；异步/后续清理 |

## 10. 验证

一致性变更必须使用与风险匹配的 PostgreSQL/Testcontainers、并发、failure-injection、unknown-commit 和 runtime 测试。历史数字只在 [Closing Review](../archive/review/README.md) 保存；当前策略见 [Testing](../engineering/testing.md)。
