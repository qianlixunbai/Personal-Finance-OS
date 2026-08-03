# 代码审查清单

- 范围是否符合需求和 API/文档契约；用户隔离与跨用户安全 `404` 是否保持；校验、认证、授权和 `400/401/403/404/409/429/500/502/503` 分类是否正确。
- 金融值是否使用 `BigDecimal` / `NUMERIC`，没有 `double`；事实、余额、投影、回执和 envelope 是否在同一事务中提交或回滚；`AccountBalanceService` 是否仍是后续余额变更唯一入口。
- append-only fact、immutable receipt、数据库 mutation guard、deterministic replay 与 projection truth 是否保持；是否把 posting snapshot 错当当前真值。
- 锁顺序、idempotency、lock timeout/deadlock、unknown commit recovery 是否正确；并发是否可能重复写入现金、事实、回执或 version。
- named constraint、组合外键、partial unique index、trigger、deferred integrity 是否随改动保持；Testcontainers 与失败回滚测试是否覆盖新增不变量。
- 是否同步活文档且不改冻结 Architecture、ADR 或历史 Closing Review；`git diff --check` 是否通过，是否混入无关或用户文件。
