# ADR-020：Import Receipt 历史流水引用与 INSERT 校验

**日期：** 2026-09-30

## 状态

Accepted（方案 A 已通过人工设计复审；C1 实施待人工终审）

## 背景

普通 `Transaction` 允许正式 PUT / DELETE，而 Import Batch、Item、Account Impact 是不可变的确认回执。V14 的 Item → Transaction 持续外键将这两种生命周期绑定：删除导入流水会被阻止，无法正常撤销当前流水的余额 effect。

## 决策

1. `created_transaction_id` 保留为非空、不可变的历史创建引用。它证明 Confirm 创建过该流水，不保证流水在查询 Receipt 时仍存在，也不表达流水的当前内容。
2. 新增前向迁移 `V20__decouple_import_receipt_transaction_lifecycle.sql`，不修改 V1–V19。迁移通过 Flyway 的 PostgreSQL 事务执行；不允许以非事务方式逐句运行。
3. 迁移按 `transactions` → `transaction_import_items` 顺序取得 `ACCESS EXCLUSIVE` 表锁，覆盖旧引用检查、trigger 安装与 FK 移除，锁持有至提交。锁模式覆盖移除 FK 及其父表内部 trigger 所需的 DDL 锁，避免中途提升锁。迁移会短暂阻塞这些表的读写，应在维护窗口执行；锁竞争或任一步失败须整体回滚后重试，不跳过校验。
4. 锁内检查每个历史 Item 的 `(user_id, created_transaction_id)` 都对应真实的同用户 Transaction。不一致即 fail-fast，不重建、伪造或改写任何历史证据。
5. 先建立 `BEFORE INSERT` trigger，再移除 `fk_transaction_import_items_owned_transaction`。trigger 按 `user_id` 与 Transaction `id` 同时匹配，使用 `FOR KEY SHARE`；查询限定项目现有的 `public.transactions`，避免会话临时同名表遮蔽真实表。缺失或跨用户引用返回 SQLSTATE `23503`。诊断中的旧 constraint 名用于标识该引用校验，不代表持续 FK 仍存在。
6. INSERT 先取得 key-share 时，并发 DELETE 等待 INSERT 事务结束；INSERT 提交后 DELETE 可以成功，留下历史引用。DELETE 先取得行锁时，INSERT 等待；DELETE 提交后 INSERT 因引用不存在而失败；DELETE 回滚则允许 INSERT。更强隔离级别可能产生序列化失败，不能把该失败伪装为成功。
7. 保留 `created_transaction_id NOT NULL`、Item → Batch 所有权 FK、全部既有 immutable triggers、其他唯一性与 CHECK 约束。V20 不更新 Batch、Item、Impact 或 `resultDigest`。
8. DELETE 沿用标准 Transaction 事务和余额算法，撤销当前 Transaction 的 effect。PUT 后再 DELETE 必须撤销 PUT 后的 effect，不能用 Receipt 的原始 Impact 替代。
9. Receipt GET 和原 Confirm Replay 返回原始创建引用、原始 Account Impact 与原始 digest；`idempotentReplay=true`。Replay 不补建已删除 Transaction，也不再次影响余额。

## 取舍

持续引用完整性改为创建证据时的引用真实性与所有权校验；删除后历史 ID 可以不再解析为当前流水。Receipt 是批次提交时的权威历史结果，不能用于推导当前余额或当前流水数量。该方案不新增删除状态、不使用 CASCADE / SET NULL，也不提供批次撤销功能。

两个表的强 DDL 锁会造成迁移期间的短暂不可用。已有写事务须先结束；相反锁顺序的并发事务仍可能触发 PostgreSQL deadlock 检测，失败事务应回滚，不承诺在线无等待迁移。

## 验证边界

C1 使用隔离 PostgreSQL 17 验证 Fresh Migration、V19 → V20、历史证据保留、INSERT / DELETE 行锁竞争、迁移锁窗口与失败回滚，并通过实际 API 验证 DELETE、PUT → DELETE、Receipt 和 Replay。具体本轮结果见实施终审报告；设计 Accepted 不代表实施已获终审。

## 相关文档

- [Transaction Import](../domain/transaction-import.md)
- [API](../architecture/api.md)
- [Database](../architecture/database.md)
- [ADR-008](ADR-008-account-balance-concurrency-and-lock-ordering.md)
