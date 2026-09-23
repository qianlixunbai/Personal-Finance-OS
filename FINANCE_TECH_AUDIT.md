# Personal Finance OS Technical Audit

**审计对象：** `Personal Finance OS`（工作树 `zh-cn-6e2e8ee8`，基线 `zh-cn` @ `6040748`）
**审计类型：** 上线前全检（代码审查 + 安全审计 + QA 测试）
**审计日期：** 2026-09-23
**审计范围：** 后端为主，前端仅覆盖与后端正确性相关的部分
**审计原则：** Correctness > Consistency > Security > Maintainability > Elegance

---

## 1. Executive Summary

**结论：这是一个工程质量显著高于普通个人项目/校招项目的资金类系统，但它不是转账系统，也没有实现转账语义。本轮未发现 P0 级缺陷。**

### 1.0 修复状态（2026-09-23 更新）

审计完成后已进入受控修复阶段。本轮处理的 5 项全部完成并通过回归验证，逐项证据见 [`FINANCE_FIX_VERIFICATION.md`](FINANCE_FIX_VERIFICATION.md)。

| 编号 | 问题 | 状态 |
| --- | --- | --- |
| P1-1 | 普通流水 CRUD 金额校验与 Import 路径不一致（不校验 scale / 精度 / 范围） | **已修复**（收敛到 `TransactionWriteRules` 单一来源） |
| P1-2 | `POST /transactions` 无幂等保护 + 前端提交无 in-flight 保护 | **已修复**（V18 + `Idempotency-Key` + 前端 submitting 状态） |
| P1-3 | 上述两条路径零测试覆盖 | **已修复**（新增 25 个测试） |
| P2-1 | 数据库约束异常的 HTTP 语义错误 | **已修复**（23505→409 / 22003→400 / 23514 保持 fail-closed 500） |
| P2-8 | nginx 与后端 Import 文件大小限制不一致 | **已修复**（`client_max_body_size 6m`） |

**未处理**（按约定保留）：P2-2 ~ P2-7、P2-9、P2-10，以及全部 P3。其中 **P2-1 修复过程中新发现一项残留缺口**，见第 17 节 P2-1 末尾。

**修复后的最终回归：669 tests / 0 failures / 0 errors / 124 classes；前端 81/81；E2E 85/85。**

### 1.1 总体成熟度

| 维度 | 评级 | 依据（可核查） |
| --- | --- | --- |
| Correctness（金额正确性） | **强，原 1 处边界缺口已修复** | 主代码 235 个 Java 文件中 **0 处 `float` / `double`**；金额全部 `BigDecimal` + PostgreSQL `NUMERIC`；投资计算有独立的 `InvestmentLedgerCalculator`，对 scale / precision / 舍入方向逐项断言。原缺口 P1-1（流水 CRUD 未校验 scale）已收敛到 `TransactionWriteRules` 单一来源 |
| Transaction（事务原子性） | **强** | 事实写入 + 余额变更 + 投影更新在同一 PostgreSQL 事务内；`AccountBalanceService` 用 `Propagation.MANDATORY` + 运行时 `TransactionSynchronizationManager.isActualTransactionActive()` 双重强制；Import Confirm 用 `TransactionTemplate` 编程式事务包裹整个批次 |
| Concurrency（并发正确性） | **强（已实测验证）** | 所有余额路径走 `SELECT ... FOR UPDATE`，账户 ID **去重 + 升序排序**后加锁；每事务设置本地 `lock_timeout`（默认 4s）；`55P03`/`40P01` 映射为 HTTP 409 |
| Deadlock（死锁） | **强（已实测验证）** | 全局锁顺序一致：事实行 → 账户行（ID 升序）→ Instrument → Asset 投影。已逐路径核对 6 条写路径，无反向加锁；反向并发 swap 测试实跑通过 |
| Security（认证与隔离） | **强** | 所有按 ID 查询的路径都带 `user_id` 条件或显式归属校验；不存在与跨用户统一返回 404；JWT secret 仅来自环境变量且无默认值；密码 BCrypt |
| Testing（测试有效性） | **强（已实测验证）** | 124 个测试类；修改前基线 **644 tests / 0 失败**，修改后 **669 tests / 0 失败**（均含全部 Testcontainers 集成测试）；前端 74/74；Playwright E2E 85/85 |
| Deployment / CI | **良好，有一处盲区** | 三作业 CI（后端测试 / 前端 lint+build / E2E）+ 镜像构建 + Compose 配置校验；但 CI 不验证 Compose 实际起栈 |

### 1.2 实际执行的验证（原始数据）

> **本节已更新。** 审计首轮执行时本机 Docker 守护进程未运行，54 个 Testcontainers 类无法执行。随后 Docker 恢复可用，已补跑**修改前完整基线**与**修改后完整回归**，两轮均为 0 失败。以下为最终真实数字。

**后端（Maven，`-B clean test`，Docker 可用，含全部 Testcontainers 集成测试）**

| 轮次 | Test classes | Tests | Failures | Errors | Skipped | 耗时 |
| --- | --- | --- | --- | --- | --- | --- |
| 修改前基线（工作树干净） | 122 | **644** | **0** | **0** | 0 | 12:40 |
| 修改后回归 | 124 | **669** | **0** | **0** | 0 | 11:51 |

两轮 `BUILD SUCCESS`。669 − 644 = +25 个测试，全部来自本次修复新增/补充的用例。

首轮"54 errors"的根因已确认为纯环境问题：`java.lang.IllegalStateException: Previous attempts to find a Docker environment failed`，**没有一个是断言失败**。恢复 Docker 后这 54 个类全部通过 —— 这也反过来证实了本报告第 4/5/6/10 节对并发、锁、死锁、迁移的结论。

- 静态统计：124 个测试类、628+ 个 `@Test`/`@ParameterizedTest` 方法、20,869+ 行测试代码（主代码 12,296 行）。

**前端（Node 22.22.2）**

```
npm test      ->  tests 81 / pass 81 / fail 0
npm run lint  ->  Found 0 warnings and 0 errors (81 files, 103 rules)
npm run build ->  ✓ built（index.js 1,009 kB / gzip 327 kB）
```

**Playwright E2E（Chromium，全部实际执行）**

| 套件 | 依赖 | 结果 |
| --- | --- | --- |
| `test:e2e:transaction-import`（mock 浏览器契约） | 仅 Vite | **57 passed** |
| `test:e2e:investment-command`（真实后端） | 后端 + 隔离 PostgreSQL | **27 passed** |
| `test:e2e:transaction-import:real`（真实后端） | 后端 + 隔离 PostgreSQL | **1 passed** |

E2E 使用独立容器化 PostgreSQL（端口 5433）与独立密钥，未触碰本机既有的 PostgreSQL 服务；运行后容器已销毁。

**仍未执行的部分（诚实声明）**

- `docker compose up` 级别的部署冒烟（CI 中同样只做 `docker compose config` 校验）。

构建有一条非阻塞警告：单 chunk > 500 kB，未做代码分割。

### 1.3 我主动排查并排除掉的"经典 P0 候选"

审计报告的价值很大程度上取决于它没有冤枉代码。以下是本轮逐一验证后**排除**的高危假设：

| 假设 | 验证方式 | 结论 |
| --- | --- | --- |
| `@Transactional` 挂在包级私有方法上不生效（`InvestmentCommandTransactionalService` / `InvestmentReplacementTransactionalService` / `LegacyAssetMigrationTransactionalService` 都是包级私有类 + 包级私有方法） | 写最小 Spring 探针复现同构场景，含"无注解"对照组 | **不成立。** 对照组返回 `false`，包级私有方法返回 `true`，事务代理确实生效 |
| 余额并发扣款产生 Lost Update | 阅读 `AccountMapper.selectOwnedForUpdate` + `AccountBalanceService.applyDeltas` | **不成立。** `FOR UPDATE` + `ORDER BY id ASC`，Java 侧 read-modify-write 在锁内完成 |
| Transfer 非原子（A 扣款成功、B 未入账） | 全仓检索 `TRANSFER` | **不成立（因为不存在）。** `TRANSFER`/`REFUND` 在 `transactions.type` CHECK 约束里，但 `TransactionService.ensureSupportedType()` 显式抛 400 拒绝；`docs/STATUS.md` 与 `docs/domain/business-rules.md` 均将其列为未实现范围 |
| IDOR：用户 A 通过 `accountId=20` 操作用户 B 的资源 | 穷举全部 `selectById` / `findById` / `selectOne` / `selectList` 调用点 | **不成立。** 每个调用点都有 `user_id` 条件或显式归属校验；跨用户统一 404 |
| JWT secret 硬编码在仓库 | 全仓密钥扫描（yml/properties/java/ts/docker） | **不成立。** `jwt.secret: ${JWT_SECRET}` 无默认值，缺失即启动失败；Compose 用 `:?` 强制要求 |
| 前端金额走 `double` 导致精度丢失 | 检查前端金额传输与 `formatCurrency` | **部分成立但不构成 P0。** 见 P2-5：投资命令与 Import 走 lossless 字符串，普通流水走 `number` |
| 重复点击"转账"产生两笔真实转账 | 检索幂等键 | **不成立（因为不存在转账）。** 但同类问题落在 `POST /transactions` 上，见 P1-2 |

---

## 2. Architecture Overview

### 2.1 真实架构

Spring Boot 3.3.5 模块化单体（Java 21）+ PostgreSQL 17 + React 19/Vite，schema 由 Flyway V1–V17 管理。

```
backend/src/main/java/com/financeos/
├── common/           ApiResponse / BusinessException / ConcurrencyConflictException / GlobalExceptionHandler / PageResult
├── config/           MybatisPlusConfig（分页插件 + UUID TypeHandler）/ OpenApiConfig
└── module/
    ├── auth/         SecurityConfig / SecurityErrorResponseHandler / JwtUtil / JwtAuthFilter
    ├── user/         注册、登录、BCrypt
    ├── account/      Account 元数据 + AccountBalanceService（余额唯一写原语）
    ├── category/     用户分类 + 系统分类
    ├── ledger/       普通流水 CRUD（INCOME / EXPENSE / ADJUSTMENT）
    ├── asset/        Legacy Asset + marketdata（quote/fx）+ valuation（参考估值）
    ├── dashboard/    服务端聚合
    ├── importing/    流水文件导入：preview → confirm → 权威 receipt
    └── investment/   command（BUY/SELL/DIVIDEND/REVERSAL/REPLACEMENT）
                      ledger（calculator + replay engine）
                      read（portfolio / position / logical transaction + cursor 分页）
                      instrument / migration（legacy opening）
```

规模：主代码 235 文件 / 12,296 行；测试 123 文件 / 20,869 行；迁移 17 文件 / 1,728 行；前端 63 文件 / 3,293 行；文档 92 个 md / 13,680 行。

### 2.2 资金数据链路（回答"钱以什么为准"）

```
用户 (JWT subject = userId)
  │
  ├─ Account ──── balance NUMERIC(18,2)   ← 【存储值】，不是计算值
  │                │
  │                └── 唯一写原语：AccountBalanceService
  │                      · lockOwnedAccounts(userId, ids)  → SELECT ... FOR UPDATE，按 id 升序
  │                      · applyDeltas(locked, mutations)   → 锁内 read-modify-write + UPDATE
  │
  ├─ Transaction ─ amount NUMERIC(18,2)   ← 【普通流水事实】
  │                · INCOME 存正数、EXPENSE 存正数、ADJUSTMENT 存带符号数
  │                · effect(type, amount) = EXPENSE ? -amount : amount  ← 符号语义唯一入口
  │                · 余额 = Σ effect(流水)  ← 不变式，由同一事务维护，但数据库不强制
  │
  └─ InvestmentTransaction ──────────────── ← 【投资事实，append-only】
                   · 被 DB trigger 禁止 UPDATE/DELETE（V12）
                   · quantity NUMERIC(28,8) / 金额 NUMERIC(28,2)
                   · 现金影响 = cashDelta（BUY 为负、SELL/DIVIDEND 为正）
                   · 同样驱动 Account.balance
                   │
                   └─ Asset ──────────────── ← 【当前持仓投影】，非事实
                        · quantity / avg_cost / total_cost / realized_profit_loss
                        · projection_version 乐观并发控制
                        · 由 canonical replay 重算，不是人工维护
```

关键回答：

- **Balance 是存储值。** 由 `AccountBalanceService` 在事务内维护，不是 `SUM(transactions)` 的实时计算结果。因此"流水正确"和"余额正确"是两个必须分别保证的性质，靠同一事务 + 锁来绑定。
- **Ledger 是事实来源。** 普通流水可 CRUD（`docs/domain/business-rules.md` §4.8 明确允许），投资事实不可变，只能通过 append-only `REVERSAL` 或 `REPLACEMENT` 纠正。
- **投资侧有两层真值**：`InvestmentTransaction`（append-only 事实）+ `Asset`（重放得到的当前投影）。`InvestmentWriteConsistencyChecker` 在写入后立刻比对两者，不一致就抛异常回滚。
- **一个投资命令写多少条记录**：BUY/SELL/DIVIDEND = 1 条 `investment_transactions` + 1 次 `accounts.balance` 更新 + 1 次 `assets` 投影更新；REVERSAL = 1 条事实 + 余额 + 投影；REPLACEMENT = 2 条事实（reversal + replacement）+ 余额 + 投影 + 1 条 `investment_transaction_corrections`。
- **一个 Import Confirm 写多少条记录**：N 条 `transactions` + M 次 `accounts.balance` 更新（按账户聚合）+ 1 条 batch + N 条 items + M 条 account_impacts + 1 次 session 状态流转。

---

## 3. Money Correctness

### 3.1 BigDecimal 使用

- 主代码 **0 处 `float` / `double`**（含 `Float`/`Double` 包装类）。
- 金额比较一律 `compareTo()` / `signum()`；**未发现任何 `BigDecimal.equals()` 误用于数值等价判断**。唯一相关的 `equals` 出现在 `TransactionImportConfirmService` 的 `Objects.equals(batch.getRequestHash(), ...)` 等字符串/digest 比较上，语义正确。
- 数据库精度分层清晰且被测试覆盖：

| 语义 | 类型 | 位置 |
| --- | --- | --- |
| 账户余额 | `NUMERIC(18,2)` | `accounts.balance` |
| 普通流水金额 | `NUMERIC(18,2)` | `transactions.amount` |
| 投资现金金额 | `NUMERIC(28,2)` | `investment_transactions.*_amount` |
| 数量 / 单价 | `NUMERIC(28,8)` | `quantity` / `unit_price` |
| 投影数量 / 均价 | `NUMERIC(28,8)` | `assets.quantity` / `avg_cost` |

### 3.2 精度策略是否统一

**投资模块：统一且严格。** `InvestmentLedgerCalculator` 对每个输入调用 `requireScale(value, scale, name)`（quantity/price ≤ 8，money ≤ 2），超出即 `InvestmentLedgerValidationException`；`money()` 统一 `setScale(2, HALF_UP)`；`requirePositiveRoundedMoney()` 专门处理"相乘后四舍五入才判正负"的边界（例如 quantity=0.001、price=0.01 这类会被舍成 0 的组合）。

**Import 模块：统一且严格。** `TransactionImportPreviewService.normalizeAmount()` 用正则 `-?(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?` 先卡字面量，再 `setScale(2, RoundingMode.UNNECESSARY)`（scale 不合法直接抛），并卡 `|amount| ≤ 9999999999999999.99`。写库前再经 `TransactionWriteRules.validate()` 二次校验 `amount.scale() != 2` 拒绝。

**普通流水 CRUD：不统一。** 这是本轮唯一的金额正确性缺口，见 **P1-1**。

### 3.3 结论

主代码在金额类型选择、比较语义、舍入方向、边界断言上是"刻意设计过"的，不是碰巧写对。`TransactionWriteRules` 与 `InvestmentLedgerCalculator` 都明确拒绝"契约外的输入"，而不是依赖数据库默默处理。

唯一的问题是：**同一个 `transactions` 表有两条写入路径，一条（Import）严格卡 scale=2，另一条（手工 CRUD）完全不卡。**

---

## 4. Transaction Safety

### 4.1 关键事务清单

| 入口 | 事务边界 | 同事务内写入 |
| --- | --- | --- |
| `TransactionService.create` | `@Transactional` | transaction 行 + balance |
| `TransactionService.update` | `@Transactional` | 锁定 transaction 行 → 按 id 升序锁账户 → update 事实 + 两个 delta |
| `TransactionService.delete` | `@Transactional` | 锁定 → delete 事实 + 反向 delta |
| `AccountService.create/update/deactivate` | `@Transactional` | accounts 行（update 走 `updateMetadata` 字段级 SQL） |
| `InvestmentCommandTransactionalService.firstBuy/trade/dividend/reverse` | `@Transactional` | 投资事实 + balance + asset 投影（+ correction） |
| `InvestmentReplacementTransactionalService.replace` | `@Transactional` | reversal + replacement 两条事实 + balance + 投影 + correction |
| `LegacyAssetMigrationTransactionalService.confirm` | `@Transactional` | opening 事实 + asset 绑定 + 投影 |
| `TransactionImportConfirmService.confirm` | `TransactionTemplate`（编程式，timeout 60s） | N 条 transactions + balance + batch + items + impacts + session 流转 |
| `TransactionImportSessionService`（cancel 等） | `@Transactional(timeout = 60)` | session 状态 |

### 4.2 事务是否"真的生效"——不只数注解

我核对了四类常见失效场景：

1. **self-invocation**：未发现同类内部调用带 `@Transactional` 的方法。`AccountBalanceService` 的 `configureLockTimeoutForCurrentTransaction()` 虽然被同类内部调用（`lockOwnedAccounts` → 它），但它本身只做 `requireTransaction()` + `set_config`，不依赖自己的事务语义；真正的传播约束由调用方的外层事务提供。
2. **包级私有方法**：三个 `*TransactionalService` 都是包级私有类 + 包级私有方法。**我用最小 Spring 探针实测了这一点**（含无注解对照组），确认代理生效、事务真实开启。这是本报告中被主动排除的一个高危假设。
3. **异常吞掉导致不回滚**：`JwtAuthFilter` 里 `catch (Exception e)` 吞异常，但它不在任何事务内，且吞掉后结果是未认证（401），安全方向正确。业务代码中没有"catch 后返回 success"的模式；`GlobalExceptionHandler` 只做映射，不吞异常。所有 `BusinessException` / `IllegalStateException` / `*ConsistencyException` 都是 `RuntimeException`，默认触发回滚。
4. **checked exception**：业务代码抛出的都是非受检异常，不存在"受检异常默认不回滚"的坑。

### 4.3 `Propagation.MANDATORY` 的价值

`AccountBalanceService` 的三个方法都是 `@Transactional(propagation = MANDATORY)`，并且每个方法第一行都调用 `requireTransaction()`：

```java
private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
        throw new IllegalStateException("an active transaction is required");
    }
}
```

这是一个**双层保险**：MANDATORY 保证"没有外层事务就拒绝"，`isActualTransactionActive()` 保证"即使有人用编程式事务或测试环境绕过了代理，也仍然拒绝"。这让"余额变更脱离事务"从"可能发生"变成"不可能发生"。这是我在本项目里认为最值得讲的设计之一。

### 4.4 原子性结论

**未发现"部分写入"路径。** 所有余额变更都与触发它的事实写入共享同一个事务；任何一步失败都会整体回滚。测试侧也有对应覆盖（`TransactionBalanceConcurrencyPostgresIntegrationTest` 用 PostgreSQL trigger 注入余额更新失败，断言事实与余额同时回滚）。

关于 Transfer：**当前版本不存在 Transfer 实现**。`transactions.type` 的 CHECK 约束包含 `TRANSFER`，但 `TransactionService.ensureSupportedType()` 对其显式返回 400「当前版本暂不支持该流水类型」。因此"Transfer 一边成功一边失败"这一风险在本版本中不可达 —— 这是"因为不存在而安全"，不是"因为写对了而安全"，两者在面试中必须区分清楚。

---

## 5. Concurrency Safety

### 5.1 余额并发处理机制

三层结构：

**第一层：数据库行锁**

```sql
SELECT id, user_id, name, type, currency, balance, status, created_at, updated_at
FROM accounts
WHERE user_id = #{userId}
  AND id IN (...)
ORDER BY id ASC
FOR UPDATE
```

注意这里同时做了三件事：**归属过滤（`user_id`）**、**固定锁顺序（`ORDER BY id ASC`）**、**行锁（`FOR UPDATE`）**。

**第二层：应用层归一化**

```java
List<Long> orderedIds = accountIds.stream()
        .peek(accountId -> { if (accountId == null || accountId <= 0) throw new IllegalArgumentException(...); })
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();
if (lockedAccounts.size() != orderedIds.size()) throw new BusinessException(404, "账户不存在");
```

去重 + 升序 + 数量校验（少一行就说明有账户不属于当前用户或不存在 → 404，**不会部分加锁后继续**）。

**第三层：事务本地锁超时**

```sql
SELECT set_config('lock_timeout', #{timeout}, true)   -- true = transaction-local
```

默认 4s（`account.balance.lock-timeout`）。超时产生 SQLSTATE `55P03`，死锁产生 `40P01`，`GlobalExceptionHandler.hasConcurrencySqlState()` 把两者都映射为 **HTTP 409 + 净化消息「并发操作冲突，请重试」**，不做自动重试（`ADR-008` 明确决策）。

### 5.2 场景推演

**场景 A：余额 100，两个请求同时支出 80**

请求 1 与请求 2 都要先 `SELECT ... FOR UPDATE` 同一账户行。请求 2 阻塞在行锁上，直到请求 1 提交。请求 1 提交后余额变为 20；请求 2 重新读到 **20**（不是缓存的 100），执行 `20 - 80 = -80`。

- **Lost Update：不可能。** 因为 `applyDeltas` 里的 `account.getBalance()` 来自 `FOR UPDATE` 的读结果，而该行在事务结束前不会被别人修改。
- **Double Spend：本项目不适用。** `docs/domain/business-rules.md` §2.3 与 `ADR-008` 明确：**CNY 余额允许为负，当前没有 insufficient-balance 拒绝规则**。所以两个 80 都成功、余额变成 -80 是**设计内行为**，不是 Bug。
  - 如果把它当作"资金系统"来要求，这里确实缺少透支保护；但本项目自我定位是记账系统而非支付系统（`docs/product/scope-and-non-goals.md`：真实支付、资金划转不属于产品目标）。审计结论：**这不是缺陷，但如果产品定位改变，这是第一个需要补的规则。**

**场景 B：两个请求同时更新同一账户的元数据 + 新增流水**

`AccountService.update` 走 `lockOwnedAccount()` 拿到同一把账户行锁，并且用字段级 SQL 更新（`SET name=..., type=..., currency=...`，**不写 balance**）。因此元数据更新不会覆盖并发的余额变更。测试 `metadataAndCreateOnSameAccountPreserveBothBalanceAndMetadata` 覆盖了这个场景。

**场景 C：Import Confirm 与手工流水并发**

两者都通过 `AccountBalanceService`，且 Import 先锁 session 行、再按升序锁账户，与手工路径的"事实行 → 账户行"顺序一致，不构成循环。

### 5.3 结论

**余额并发是本项目工程质量最高的部分之一。** 用数据库行锁而不是乐观版本号，配合固定锁顺序和事务本地超时，是一个在单体 + 单库场景下正确且简单的选择。

需要指出的两个"边界而非缺陷"：

1. **没有超时重试**。4s 锁超时后直接 409 让用户重试，意味着高竞争下用户可见失败率上升。这是 `ADR-008` 的显式决策（避免重试放大竞争），不是遗漏。
2. **余额可变负**。如上，是设计内行为。

---

## 6. Deadlock Analysis

### 6.1 全局锁顺序

`ADR-008` 规定的顺序是：**现有事实行 → 按 `accountId` 升序去重后的 Account 行 → 后续投影**。我逐条核对了全部 6 条写路径，实际实现与 ADR 一致：

| 写路径 | 加锁顺序 |
| --- | --- |
| `TransactionService.create` | accounts(id asc) |
| `TransactionService.update` | `transactions` FOR UPDATE → accounts(id asc) |
| `TransactionService.delete` | `transactions` FOR UPDATE → accounts(id asc) |
| `AccountService.update / deactivate` | accounts(单行) |
| `InvestmentCommandTransactionalService.trade` | accounts → `investment_instruments` FOR UPDATE → `assets` FOR UPDATE |
| `InvestmentCommandTransactionalService.reverse` | `investment_transactions` FOR UPDATE → accounts → instrument → assets |
| `InvestmentReplacementTransactionalService.replace` | `investment_transactions` FOR UPDATE → accounts → instrument → assets |
| `LegacyAssetMigrationTransactionalService.confirm` | accounts → instrument → assets |
| `TransactionImportConfirmService.confirmInTransaction` | `transaction_import_sessions` FOR UPDATE → accounts(id asc) |

关键性质：**账户锁永远先于 Instrument 锁，Instrument 锁永远先于 Asset 锁**；账户集合永远按 ID 升序。因此不存在"持有 B 等 A"的路径，也就不存在环。

### 6.2 经典反向转账场景

原题设想的 `Transfer A: 1→2` 与 `Transfer B: 2→1` 在本版本中不可达（无 Transfer）。但**同构场景是可构造的**：两条流水在两个账户之间互换（`update` 把流水 1 从账户 1 移到账户 2，同时把流水 2 从账户 2 移到账户 1）。因为 `lockOwnedAccounts` 内部强制 `sorted()`，两条路径都以「先小 ID、后大 ID」加锁，不会死锁。

这个场景**有测试覆盖**：`TransactionBalanceConcurrencyPostgresIntegrationTest#crossingAccountSwapsCompleteWithoutReverseOrderDeadlock`。

### 6.3 残余风险

- 锁顺序的正确性**依赖每个新写入者自觉复用 `AccountBalanceService`**。`ADR-008` 用文字约束了这一点，但**代码层面没有强制**（没有 ArchUnit 之类的架构测试去断言"没有任何地方直接 UPDATE accounts.balance"）。当前 0 处违规，但这是一个靠约定而非靠机制维持的性质。列为 P3。
- `lock_timeout` 只能防"永久等待"，不能防"锁竞争导致的吞吐下降"。单用户个人财务场景下不构成问题。

---

## 7. User Isolation

### 7.1 用户资源清单与隔离方式

| 资源 | 隔离实现 | 结论 |
| --- | --- | --- |
| Account | `AccountMapper.findByUserIdAndId` / `selectOwnedForUpdate`（SQL 内含 `user_id`）；`AccountService.getById` 用 `selectById` + 显式 `userId` 比对 | ✅ |
| Transaction | `selectOwnedForUpdate(userId, id)`；`findOwnedTransaction` 用 `selectById` + 显式比对；分页查询 `eq(Transaction::getUserId, userId)` | ✅ |
| Category | 用户分类按 `user_id`；系统分类为 `user_id IS NULL AND is_system = true` 的共享例外；`validateCategory` 显式判定二者 | ✅ |
| Asset | `selectOwnedForUpdate` / `findByUserIdAndId`；`AssetService.getById` 显式比对 | ✅ |
| InvestmentInstrument | `findByUserIdAndIdForUpdate` / `findByUserIdAndId` | ✅ |
| InvestmentTransaction | `findByUserIdAndIdForUpdate` / `findByUserIdAndId` | ✅ |
| Investment Read Model | 全部 SQL 带 `WHERE ... user_id = #{userId}`，含多表 JOIN 也逐表带 `user_id` 条件 | ✅ |
| TransactionImportSession | `findByIdAndUserId` / `findByIdAndUserIdForUpdate` | ✅ |
| TransactionImportBatch | `findConfirmedByIdAndUserId` | ✅ |
| Dashboard 聚合 | 全部经 `*QueryService` 且带 `userId` | ✅ |
| MarketQuote / ExchangeRate | 共享参考数据（无 user 维度），不承载用户财务事实 | ✅ |

### 7.2 关键设计：统一 404 而非 403

跨用户访问返回 **404**（"不存在"），不是 403（"无权限"）。这样不会泄露"该 ID 的资源确实存在"。`docs/domain/business-rules.md` §1.4 明确规定了这一点，代码一致遵守。

测试覆盖：`TransactionBalanceConcurrencyPostgresIntegrationTest#crossUserTransactionUpdateReturnsNotFoundWithoutChangingAnyBalance` 断言越权更新抛 404 且双方余额不变。

### 7.3 结论

**未发现 IDOR 或越权路径。** 我穷举了全部 `selectById` / `findById` / `selectOne` / `selectList` 调用点（这是最容易漏的地方，因为 `selectById` 天然不带用户条件），每一个都有后续归属校验或直接使用了带 `user_id` 的专用查询。

值得肯定的模式：**"锁查询即隔离查询"** —— `selectOwnedForUpdate` 同时承担归属校验和加锁职责。这意味着"忘了校验归属"和"忘了加锁"这两个错误被合并成一个不可能单独犯的错误。

---

## 8. Security

### 8.1 JWT

| 检查项 | 实现 | 评价 |
| --- | --- | --- |
| 生成 | `Jwts.builder().subject(userId).claim("username").issuedAt(now).expiration(now + 86400000).signWith(key)` | ✅ HS256，24h 有效期 |
| 校验 | `Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload()` | ✅ 强制验签，`parseSignedClaims` 拒绝无签名 token |
| Secret | `@Value("${jwt.secret}")` ← `${JWT_SECRET}`，**无默认值** | ✅ 缺失即启动失败；Compose 用 `${JWT_SECRET:?...}` 强制；仓库内无硬编码生产密钥（扫描确认） |
| 密钥强度 | `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` | ✅ 弱于 256 bit 会抛 `WeakKeyException`，fail-fast |
| 过滤 | `JwtAuthFilter`（`OncePerRequestFilter`） | ✅ 见下 |
| 撤销 | 无 `jti` / 无黑名单 | ⚠️ P2-6 |
| 算法固定 | 未显式 `require` alg / issuer / audience | ⚠️ P3 |

`JwtAuthFilter` 有一个很好的设计：**每次请求都回查数据库确认用户存在且 `status = ACTIVE`**：

```java
User user = userMapper.selectById(userId);
if (user != null && "ACTIVE".equals(user.getStatus())) { ...设置 Authentication... }
```

这让"禁用账号"能立即生效，而不必等 token 过期 —— 弥补了一部分"无撤销机制"的缺陷。代价是每请求一次 `users` 主键查询（可接受）。

`catch (Exception e) { SecurityContextHolder.clearContext(); }` —— 吞异常但不吞权限：异常后继续走过滤器链，最终由 `AuthenticationEntryPoint` 返回 401。**没有把 token 内容写进日志**，符合要求。

### 8.2 密码

`BCryptPasswordEncoder`（Spring Security 默认强度 10）。`UserService.login` 用 `passwordEncoder.matches()` 常量时间比对；用户名不存在与密码错误返回**同一条消息**「用户名或密码错误」，不泄露用户存在性。✅

`RegisterRequest.password` 仅 `@Size(min = 6, max = 100)` —— 强度偏弱，但没有明文存储风险。P3。

### 8.3 认证 vs 授权

| 状态 | 触发点 | 实现 |
| --- | --- | --- |
| **401 Unauthorized** | 未带 / 无效 / 过期 token | `SecurityErrorResponseHandler.commence()` |
| **403 Forbidden** | 认证通过但授权失败 | `SecurityErrorResponseHandler.handle()` |

`SecurityConfig` 是**白名单式**的：只有 `/api/v1/register`、`/api/v1/login`、`/actuator/health(/**)`、`/v3/api-docs/**`、`/swagger-ui*` 是 `permitAll()`，其余 `anyRequest().authenticated()`。这是安全默认值（fail-closed），比黑名单式配置可靠得多。

`SecurityConfigTest` 与 `InvalidJwtApiIntegrationTest` 覆盖了这两条路径。

**未发现"登录后即可访问任意用户资源"的问题**（见第 7 节）。

### 8.4 CSRF / CORS / Session

- **CSRF 已禁用**：`csrf(csrf -> csrf.disable())`。对无 Cookie、纯 Bearer token 的 stateless API 是正确的。
- **Session 策略**：`STATELESS`，`UsernamePasswordAuthenticationToken` 的 credentials 传 `null`，权限集为空 —— 无服务端会话可劫持。✅
- **CORS 未配置**：生产部署是同源（nginx 把 `/api/` 反代到 backend），因此不需要 CORS，**且不配置反而更安全**（不会有 `Access-Control-Allow-Origin: *`）。但这是一个隐式耦合：未来若把前端拆到独立域名，会静默失败。P3（记录即可）。

### 8.5 敏感信息与日志

- 主代码 **0 处 `System.out.println` / `printStackTrace`**。
- MyBatis-Plus 日志实现显式设为 `NoLoggingImpl`（`application.yml`），**不会打印 SQL 参数** —— 这点很重要，因为 SQL 参数里就是金额和 user_id。
- `application-prod.yml`：`springdoc` 与 `swagger-ui` **在生产关闭**，`management.endpoints.web.exposure.include: health`，`show-details: never`。✅
- `GlobalExceptionHandler` 对 5xx 返回的都是**脱敏固定文案**（"服务器内部错误"、"Investment write consistency check failed"），不返回堆栈或 SQL。`BusinessException` 的 message 会回传，但这些 message 都是业务文案，不含敏感值。✅
- 仓库内无生产密钥（`grep` 扫描确认）。仅有的"密钥字符串"在测试源码里，属正常。`.gitignore` 覆盖 `docker/.env`、`.env.local`、`application-local.yml`。

### 8.6 安全结论

**安全设计是合格的，且在若干细节上超出一般水平**（白名单授权、登录失败消息统一、请求级 ACTIVE 校验、生产关闭 Swagger、MyBatis 关 SQL 日志、错误脱敏）。遗留项都是 P2/P3 级别的纵深防御，不是可利用漏洞。

---

## 9. Database

### 9.1 约束完整性

这个 schema 的约束密度是我在本类项目中见过较高的。摘录几处：

**复合外键做"用户维度"引用完整性**（比单纯 `REFERENCES accounts(id)` 强得多）：

```sql
-- V4
ALTER TABLE accounts ADD CONSTRAINT uk_accounts_user_id_id UNIQUE (user_id, id);
ALTER TABLE assets   ADD CONSTRAINT fk_assets_user_account
    FOREIGN KEY (user_id, account_id) REFERENCES accounts (user_id, id);
```

这样 `assets` 不可能引用到**别的用户**的 account —— 数据库层面阻断了一整类越权写入，而不是靠应用层自觉。

**CHECK 约束编码业务不变式**（V5）：

```sql
CONSTRAINT ck_investment_transactions_buy_amounts CHECK (
    transaction_type <> 'BUY'
    OR (gross_amount > 0
        AND net_amount = gross_amount + fee_amount + tax_amount
        AND released_cost_amount = 0
        AND realized_profit_loss = 0)
),
CONSTRAINT ck_investment_transactions_sell_amounts CHECK (
    transaction_type <> 'SELL'
    OR (gross_amount > 0
        AND net_amount = gross_amount - fee_amount - tax_amount
        AND net_amount >= 0
        AND released_cost_amount > 0
        AND realized_profit_loss = net_amount - released_cost_amount)
)
```

**这些不是"防御性冗余"，而是把金额公式写进了数据库。** 即使应用层有 Bug，也不可能写入一条 `net_amount ≠ gross + fee + tax` 的 BUY 事实。

**Trigger 实现 append-only**（V12）：

```sql
CREATE TRIGGER trg_prevent_investment_transaction_mutation
BEFORE UPDATE OR DELETE ON investment_transactions
FOR EACH ROW EXECUTE FUNCTION prevent_investment_transaction_mutation();
```

投资事实在数据库层不可修改/删除。加上 `validate_append_only_investment_reversal()` 在 INSERT 时校验 reversal 的审计值必须与原始事实严格对应（`cash_delta IS DISTINCT FROM expected_cash_delta` 就拒绝），**"投资历史被静默篡改"成为不可能事件**。

Import receipt 同样不可变（`trg_transaction_import_batches_immutable` 等三个 trigger，V16）。

### 9.2 唯一约束：应用层校验 + 数据库约束

审计要求检查"Service 先查询再插入，并发下两个请求都认为不存在"。逐项核对：

| 场景 | 应用层 | 数据库层 | 并发结果 |
| --- | --- | --- | --- |
| 注册用户名 | `selectCount` 预检 | `users.username UNIQUE` | 一个成功、一个 23505 → 见 P2-1（状态码错，数据不错） |
| 注册邮箱 | `selectCount` 预检 | `users.email UNIQUE` | 同上 |
| 投资命令幂等 | 先查 `findByUserIdAndIdempotencyKey` | `uk_investment_transactions_user_idempotency` | 一个成功、一个 23505 → `InvestmentCommandService` 捕获并重放权威结果 ✅ |
| 同一事实被反向两次 | 先查 reversal | `uk_investment_transactions_reversal_original` | 一个成功、一个 23505 → 409「已被反向」✅ |
| 同一 (user,account,instrument) 两个 transaction-driven Position | `existsTransactionDrivenPosition` | `uk_assets_transaction_driven_user_account_instrument`（partial unique index） | 数据库兜底 ✅ |
| 同一 asset 两条 OPENING_POSITION | replay 校验 | `uk_investment_transactions_posted_opening_asset`（partial unique index） | 数据库兜底 ✅ |
| Import 幂等 / 精确重复 | 先查 batch | `uk_..._user_idempotency` / `uk_..._exact_duplicate` / `uk_..._user_session` | `recoverKnownUnique()` 按**具名约束**区分三种冲突并各自重放或拒绝 ✅ |

**结论：`Java 校验 + 数据库唯一约束` 的双保险模式在资金路径上是落实了的**，并且 `TransactionImportConfirmService` 进一步做到了"按约束名分派恢复策略"，而不是笼统 catch。

### 9.3 索引

覆盖了主要访问模式，且索引设计跟着查询走：

- `transactions(user_id, currency, type, transacted_at)` —— 对应 Dashboard 的月度聚合；
- `investment_transactions(user_id, trade_time DESC, id DESC)` —— 对应 cursor 分页；
- `investment_transactions(user_id, asset_id, trade_time, id)` —— 对应 replay 的顺序读取；
- partial index（`WHERE transaction_type = 'OPENING_POSITION' AND status = 'POSTED'`）避免了对全表建唯一索引。

### 9.4 值得注意的问题

1. **`transactions` 表没有任何防重复约束**。同名同额同时间的两条流水在数据库层完全合法。Import 侧用 digest + 唯一约束挡了，手工侧没有。见 P1-2。
2. **`transactions` 表没有 `version` 列**。乐观并发在这里不需要（因为走行锁），但意味着无法做无锁读一致性校验。
3. **`logic-delete-field: deleted` 配置是"哑弹"**。`application.yml` 里配了全局逻辑删除字段 `deleted`，但没有任何实体有 `deleted` 字段、没有任何表有 `deleted` 列。MyBatis-Plus 只在实体存在该字段时才生效，所以**当前无影响**；但一旦有人给某个实体加了 `deleted` 字段，MP 会静默开始过滤（且所有手写 SQL 不过滤），造成读写不一致。P3。

---

## 10. Flyway

### 10.1 迁移健康状态

| 版本 | 主题 | 特征 |
| --- | --- | --- |
| V1 | baseline | `IF NOT EXISTS` 幂等风格 |
| V2 / V3 | market_quotes / exchange_rates | 参考数据 |
| V4 | investment ledger foundation | 复合 FK、CHECK、索引 |
| V5 | harden ledger constraints | **先 `DO $$ ... RAISE EXCEPTION` 预检既有数据，再 DDL** |
| V6 | align asset projection precision | 精度对齐 |
| V7 | investment instruments | 预检：存在 TRANSACTION_DRIVEN 就拒绝 |
| V8 | bind positions to instruments | partial unique index |
| V9 | harden opening migration | 三条数据一致性预检 |
| V10 | immutable write receipts | 预检 + 新增 receipt 列 + 形状 CHECK |
| V11 | dividend receipts | **校验 V10 约束定义文本存在**再改 |
| V12 | append-only reversals | trigger + 撤销旧 CHECK 重建 |
| V13 | transaction replacements | （46 KB，最重的一条） |
| V14 | import foundation | UUID session/batch/item |
| V15 | allow cleared file references | 单行 DROP NOT NULL |
| V16 | import confirm receipt | pgcrypto + digest 回填 + 不可变 trigger |
| V17 | repair digest timestamps | **DISABLE TRIGGER → 数据修复 → ENABLE TRIGGER** |

### 10.2 逐项检查

- **版本顺序**：V1–V17 连续无缺号，命名规范统一（`V{n}__{snake_case}.sql`）。
- **是否可重复执行**：DDL 迁移本身不是幂等的（`ADD CONSTRAINT` 重复执行会失败），但这是 Flyway 版本化迁移的正常前提（靠 `flyway_schema_history` 保证只执行一次），**不构成问题**。V1 用了 `IF NOT EXISTS`，属于风格不一致而非缺陷。
- **是否修改历史 migration**：`git log` 与文件内容看，历史迁移未被改写；V15/V17 都是"新增迁移修复既有问题"的正确做法（V15 修 V14 的过度约束，V17 修 V16 的 digest 时间戳格式）。
- **destructive change**：**未发现** `DROP TABLE` / `DROP COLUMN` / 数据删除。V5/V12 有 `DROP CONSTRAINT`，但都是"撤销后立即用更严格的版本重建"，属于约束收紧而非数据破坏。
- **NOT NULL 迁移**：V14 建 `temporary_storage_reference NOT NULL`，V15 再 `DROP NOT NULL` —— 这是"先紧后松"，对已有数据无风险。V16 的 `ALTER COLUMN ... SET NOT NULL` 前有 `DO $$ ... RAISE EXCEPTION` 预检空值。✅
- **data migration**：V16 用 CTE 从 `transaction_import_items` / `accounts` 回填 `transaction_import_batch_account_impacts`，回填后有 `RAISE EXCEPTION` 校验"每个 batch 都必须有 impact 证据"。V17 重算 digest。**回填逻辑有断言保护**，不是"跑完就算"。✅
- **Fresh Database 从 V1 到最新**：`FlywayMigrationIntegrationTest` 覆盖空库全量迁移；另有 12 个 `FlywayVersion*UpgradeIntegrationTest` 分别验证"历史 snapshot → latest"的升级路径（V2、V3、V4、V5、V7+V8、V9、V10、V16、V17、replacement、dividend receipt、append-only reversal）。
  **⚠️ 这些测试本轮全部因 Docker 不可用而未执行。**

### 10.3 评价

**Flyway 使用方式是这个项目里工程成熟度的第二个高点。** 最值得肯定的模式是"**迁移先验证既有数据，不满足前提就 RAISE EXCEPTION 而不是静默强改**"：

```sql
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM investment_transactions
               WHERE NOT (...合法形状...)) THEN
        RAISE EXCEPTION 'V5 cannot enforce investment transaction amount constraints: existing invalid transaction facts found';
    END IF;
END $$;
```

这意味着：如果生产库里存在不满足新约束的历史数据，迁移会**失败并保持旧 schema**，而不是删数据或强行加约束。对资金系统而言这是正确的失败方向。

`V11` 更进一步 —— 用 `pg_get_constraintdef()` 读取 V10 建立的约束定义文本，确认它确实是预期的形状，**才**在其基础上改造。这是在防止"迁移被跳过或被手工改过"的静默错位。

**残余风险（P3）**：`V5` 用 `DROP CONSTRAINT investment_transactions_replaces_transaction_id_fkey` 删除 PostgreSQL 自动命名的外键。这个名字是 PG 的默认生成规则，在当前 PG 版本下稳定，但属于"依赖隐式命名"的脆弱写法。建议未来新增迁移时对需要后续操作的约束一律显式命名。

---

## 11. REST & Validation

### 11.1 接口设计

统一前缀 `/api/v1`，统一响应信封：

```java
public record ApiResponse<T>(int code, String message, T data, String errorCode, Boolean retryable)
```

`@JsonInclude(NON_NULL)` 保证 `data`/`errorCode`/`retryable` 在不需要时不出现。分页统一 `PageResult<T>(records, total, page, size)`，投资读模型额外提供 cursor 分页。

### 11.2 分页规范

```java
int safePage = Math.max(page, 1);
int safeSize = Math.min(Math.max(size, 1), 100);
```

**上限 100 是硬编码在 Service 里的**，不依赖客户端。`AccountService`、`AssetService`、`TransactionService` 一致。✅

### 11.3 参数校验

| DTO | 校验 | 评价 |
| --- | --- | --- |
| `RegisterRequest` | `@NotBlank @Size(3,50)` / `@Email` / `@Size(6,100)` | 基本 |
| `LoginRequest` | `@NotBlank` | 足够 |
| `AccountRequest` | `@NotBlank name/type` | **`type` 未校验枚举**，非法值打到 DB CHECK → 503（P2-1） |
| `AssetRequest` | `@NotBlank name/type`、`@NotNull @Positive quantity/avgCost` | **无 scale/precision 校验**（P2-2）；`type`/`market` 同上 |
| `TransactionRequest` | `@NotNull accountId/categoryId/amount/transactedAt`、`@NotBlank type` | **`amount` 无 scale/精度/范围校验**（P1-1） |
| 投资 DTO | 金额走 `String` + `StrictDecimalStringDeserializer`，Service 内 `validateScale` / `validatePrecision` | 严格 |

投资模块的输入处理方式明显更严格：**金额以字符串传输、在 Service 里做字面量级校验**，避免了"JSON 数字 → double → BigDecimal"的隐式精度损失。这个模式值得推广到普通流水。

### 11.4 HTTP 状态码

| 场景 | 返回 | 评价 |
| --- | --- | --- |
| 创建成功 | **200** + `ApiResponse.ok` | 未用 201，但项目内一致，属约定 |
| 参数错误 | 400 | ✅ |
| 未认证 / token 无效 | 401 | ✅ |
| 授权失败 | 403 | ✅ |
| 资源不存在 / 跨用户 | 404 | ✅ 统一 |
| 幂等冲突 / 并发冲突 / replay 冲突 | 409 | ✅ |
| 导入超限 / 媒体类型 | 413 / 415 | ✅ |
| Provider 限流 / 坏响应 / 不可用 | 429 / 502 / 503 | ✅ |
| 一致性校验失败 | 500 + 脱敏文案 | ✅ |

映射在 `GlobalExceptionHandler.resolveHttpStatus()` 里集中实现，无散落的 `@ResponseStatus`。**未发现 Controller 里有大量业务逻辑** —— 全部 Controller 都只做「取 userId → 调 Service → 包 `ApiResponse`」。

### 11.5 唯一的接口层缺口

`POST /api/v1/transactions` 与 `PUT /api/v1/transactions/{id}` **不要求任何幂等标识**，而同一系统的投资命令（`Idempotency-Key` header + SHA-256 request hash）和 Import Confirm（`Idempotency-Key` + 三重 digest）都强制要求。这是系统内**最明显的一致性断层**，见 P1-2。

---

## 12. Test Quality

### 12.1 统计（必须项）

> **本节已更新为 Docker 可用后的真实执行结果。**

**静态统计**

| 指标 | 数值 |
| --- | --- |
| Test classes | **124**（修复后） |
| `@Test` / `@ParameterizedTest` 方法总数 | **628+** |
| 测试代码行数 | 20,869+ |
| 主代码行数 | 12,296 |
| 测试/主代码比 | **> 1.70 : 1** |

**后端实际执行（含全部 Testcontainers 集成测试）**

| 指标 | 修改前基线 | 修改后回归 |
| --- | --- | --- |
| Test classes | 122 | **124** |
| Tests run | **644** | **669** |
| Failures（断言失败） | **0** | **0** |
| Errors | **0** | **0** |
| Skipped | **0** | **0** |
| 耗时 | 12:40 | 11:51 |
| 结果 | `BUILD SUCCESS` | `BUILD SUCCESS` |

**前端与 E2E 实际执行**

| 指标 | 数值 |
| --- | --- |
| Node tests | 81 run / **81 pass** / 0 fail |
| lint | 0 warnings, 0 errors（81 files, 103 rules） |
| build | 成功（1,009 kB / gzip 327 kB） |
| Playwright E2E | **85 passed**（57 mock 契约 + 27 investment-command 真实后端 + 1 import 真实后端） |

**关于首轮 54 个 Errors**：根因确认为 `java.lang.IllegalStateException: Previous attempts to find a Docker environment failed`（Testcontainers 无法连接 Docker 守护进程），**没有一个是断言失败**。Docker 恢复后这 54 个类全部通过，包括并发、锁、死锁、失败注入、unknown commit 与 Flyway 全量迁移 —— 本节原先"评价基于测试代码内容而非执行结果"的保留意见已解除。

### 12.2 分类分析

**A. 高价值测试（真正的资产）**

| 测试类 | 为什么有价值 |
| --- | --- |
| `TransactionBalanceConcurrencyPostgresIntegrationTest`（13 个测试） | 用真实 PostgreSQL 验证：并发创建保持两个 delta、并发删除只反向一次、并发更新用最新锁定事实、跨账户 swap 不死锁、跨用户 404、元数据与流水并发互不覆盖、**用临时 trigger 注入余额更新失败验证事实+余额整体回滚**、`updateThenDelete` / `deleteThenUpdate` 用 `pg_locks` 确认第二个事务真的进入了锁等待。这是"并发正确性"的实证，不是 mock 出来的 |
| `InvestmentDividendConcurrencyIntegrationTest` / `InvestmentReversalConcurrencyIntegrationTest` / `InvestmentReplacementConcurrencyIntegrationTest` | 投资写路径的并发竞争 |
| `Investment*LockIntegrationTest`（dividend / reversal / replacement） | 锁顺序与锁等待 |
| `Investment*FailureIntegrationTest`（dividend / reversal / replacement，其中 replacement 22 个测试） | 中途失败必须整体回滚 |
| `Investment*UnknownCommitIntegrationTest`（firstBuy / replacement） | "已提交但响应未知"的幂等恢复 —— 这是分布式系统里最难测也最容易被忽略的一类 |
| `FlywayMigrationIntegrationTest` + 12 个 `FlywayVersion*UpgradeIntegrationTest` | 空库全量迁移 + 历史 snapshot 升级路径 |
| `TransactionImportConfirmIntegrationTest`（27 个测试） | 幂等、具名约束恢复、exact duplicate、evidence drift |
| `InvestmentLedgerCalculatorTest`（15）/ `InvestmentReplayEngineTest`（20） | 纯计算与重放引擎，含舍入残差与边界 |
| `TransactionImportConfirmPersistenceIntegrationTest` | 持久化层与 receipt 重建 |
| `InvalidJwtApiIntegrationTest` / `SecurityConfigTest` | 认证边界 |
| `InvestmentReadQueryPostgresIntegrationTest`（18） | 读模型 SQL 正确性 |

这些测试的共性是：**它们测的是"系统在异常情况下的行为"，而不是"系统在正常情况下的行为"。** 这正是资金系统最需要的。

**B. 普通测试**

`*ControllerWebMvcTest`（8 个类）、`*ServiceTest`（Mockito 单元测试）、`*PropertiesTest`、`*MapperTest`、`ExchangeRateServiceConcurrencyTest` 等 —— 正常的分层测试，价值合理。

**C. 低价值 / 可能重复的测试**

- **`passThroughJwtFilter` 出现 10 次**（同名方法分散在 10 个 WebMvc 测试类里）。每个 Controller 的 WebMvc 测试都重新复制了一份"JWT 过滤器放行"的辅助逻辑。功能上不算错，但属于 10 份重复的测试脚手架。
- **`assertUnchanged` 6 次、`insertCompleteReplacementGroup` 5 次、`assertNoWaitingLocks` 4 次、`awaitDatabaseLockWait` 3 次、`assertSafeInternalFailure` 3 次** —— 大量测试辅助方法在不同类里重复实现。这些是**测试基础设施缺失**的信号：应该抽到一个共享的测试基类/工具类里。
- **`FlywayVersion*UpgradeIntegrationTest` 12 个类各只有 1 个测试方法**，但每个类都独立启动一个 `PostgreSQLContainer`。测试本身有价值（覆盖不同升级路径），但**启动成本与测试体量严重不成比例**。
- `SecurityConfigTest`（1）、`MarketQuoteServiceAvailabilityTest`（1）、`AssetQueryServiceTest`（1）、`AccountQueryServiceTest`（1）等单测试类，价值密度尚可，不必动。

**未发现**：getter/setter 测试、纯 DTO 测试、`verify(mock).method()` 式的"只验证被调用"测试。这一点做得很好 —— **没有为覆盖率而写的测试**。

### 12.3 一个结构性观察

`InvestmentReplacementFailureIntegrationTest` 有 22 个测试、`InvestmentReplacementMigrationIntegrationTest` 有 22 个、`TransactionImportConfirmIntegrationTest` 有 27 个。这些超大测试类**内部很可能存在可参数化的重复路径**（例如同一失败注入点在不同输入组合下重复）。但由于这 54 个类本轮未执行，我**不做删除建议**，只标记为"值得人工复核"。见第 20 节。

---

## 13. Critical Test Gaps

只列真正高风险、且**当前确实没有覆盖**的缺口。

### GAP-1（对应 P1-1）：普通流水 CRUD 的金额 scale/精度边界

`TransactionService` 的 `validateAmountAndDescription` 只校验符号，**不校验 scale、不校验精度上限、不校验 NUMERIC(18,2) 范围**。对应地：

- 没有任何测试断言 `POST /transactions` 携带 `amount = 1.005`（3 位小数）时的行为。
- 没有任何测试断言 `amount = 1e20`（超出 NUMERIC(18,2)）时的行为与状态码。
- 对比：`TransactionWriteRules` 的 scale=2 规则**有测试**（通过 Import 路径），但 `TransactionService` 的等价规则**没有**。

### GAP-2（对应 P1-2）：`POST /transactions` 的重复提交

- 没有任何测试模拟"同一请求体连续提交两次"并断言只产生一条流水。
- 没有任何测试断言并发两次相同 POST 的结果。
- 对比：投资命令有 `*UnknownCommitIntegrationTest` 覆盖同类场景；普通流水路径完全没有对应测试。

### GAP-3：前端提交按钮的在途状态

`Transactions.tsx` 的提交按钮没有 `disabled` 状态、没有 in-flight 标志。**没有测试覆盖"双击保存"**。而投资命令 UI 有 `useInvestmentCommandCoordinator` + `PendingCommandRecoveryBanner` 的完整在途状态机，并且有对应测试 —— 两条路径的严谨度差距很大。

### GAP-4：`AccountService` / `AssetService` 的非法枚举输入

`AccountRequest.type` 与 `AssetRequest.type` 未做枚举校验，非法值由数据库 CHECK 拒绝。**没有测试断言这种情况下的状态码与错误消息**（当前实际返回 503，见 P2-1）。

### GAP-5：`@Transactional` 传播行为的回归保护

`AccountBalanceService.requireTransaction()` 是一个很好的守卫，`AccountBalanceServiceTest#refusesCallsWithoutTransaction` 也覆盖了它。但**没有任何架构级测试**去断言：

- 没有其他类直接 `UPDATE accounts SET balance`（当前 0 处违规，靠约定维持）；
- 没有从 Account 到事实的反向加锁路径（`ADR-008` 的约束）。

这类"全局性质"目前只能靠人工 review 维持。

### 已经覆盖、无需重复添加的

以下审计要求中列举的场景，**项目已有覆盖**，不应重复添加：

- 并发扣款（`concurrentCreatesAgainstTheSameAccountPreserveBothDeltas`）
- 并发删除只反向一次（`concurrentDeletesReverseTheOriginalEffectOnlyOnce`）
- 跨账户更新的两个 delta（`crossAccountUpdateReversesOldEffectAndAppliesNewEffect`）
- 反向并发 swap 不死锁（`crossingAccountSwapsCompleteWithoutReverseOrderDeadlock`）
- 跨用户读取/更新（`crossUserTransactionUpdateReturnsNotFoundWithoutChangingAnyBalance`）
- 中途异常回滚（`createRollsBackTransactionWhenBalanceUpdateFails` 等 3 个 + `assertBalanceUpdateFailureRollsBack` 注入机制）
- 停用账户拒绝新流水（`deactivateBeforeCreateRejectsNewTransactionAndDoesNotChangeBalance`）
- 投资卖超（`insufficientSellReturnsConflictAndRollsBackWithoutChangingFactBalanceOrProjection`）
- 投资幂等与 unknown commit
- 空库与历史 snapshot 的 Flyway 迁移

---

## 14. Deployment & CI

### 14.1 Dockerfile（backend）

多阶段构建，`eclipse-temurin:21-jdk-jammy` → `21-jre-jammy`。✅ 值得肯定的点：

```dockerfile
&& groupadd --system financeos \
&& useradd --system --gid financeos --home /app --shell /usr/sbin/nologin financeos \
...
USER financeos
```

**非 root 用户运行**，且 `chown` 只给 jar 文件。同时用 `dependency:go-offline` 单独分层，利用构建缓存。

小问题：`sed -i 's/\r$//' mvnw` 说明开发者意识到 Windows 换行问题 —— 这是有效的防御（`.gitattributes` 缺失的情况下）。

### 14.2 Dockerfile（frontend）+ nginx

`node:22-alpine` 构建 → `nginx:1.27-alpine` 运行。`default.conf.template` 用官方镜像的 envsubst 机制注入 `BACKEND_HOST`/`BACKEND_PORT`。

```nginx
location /api/ { proxy_pass http://${BACKEND_HOST}:${BACKEND_PORT}; ... }
location / { try_files $uri $uri/ /index.html; }
```

SPA fallback 正确，`/api/` 与 `/actuator/` 反代正确。✅

**未发现**：`client_max_body_size`。后端 Import 限制 5 MiB，nginx 默认 `client_max_body_size 1m` —— **大于 1 MiB 的上传会被 nginx 以 413 拒绝，且不会到达后端**。后端有 413 处理逻辑和测试，但生产路径上 nginx 会先拦。这是一处**配置不一致**（P2）。

### 14.3 docker-compose

- 三个服务：postgres / backend / frontend。
- 启动顺序用 `depends_on: condition: service_healthy`（不是裸 `depends_on`），✅ 正确。
- postgres 健康检查 `pg_isready`；backend 健康检查打 `/actuator/health/readiness`；frontend 健康检查 `wget`。
- 所有必需密钥用 `${VAR:?...}` 强制，**缺失即 compose 拒绝启动**。✅
- `stop_grace_period: 30s` + `application-prod.yml` 的 `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s`，三者配合正确。✅
- postgres 数据用 named volume 持久化。✅

**评价**：Actuator readiness 的配置是正确的 —— `management.endpoint.health.group.readiness.include: readinessState,db` 意味着 **readiness 探针会检查数据库连通性**，即"应用可以接收请求"这个语义被真实表达，而不是只看进程存活。这是一个经常被写错的点。

### 14.4 GitHub Actions

三个作业：

| 作业 | 内容 | 评价 |
| --- | --- | --- |
| `backend` | JDK 21 + `./mvnw -B clean test`（20 分钟超时） | ✅ 真跑测试。GitHub runner 有 Docker，Testcontainers 可用 |
| `frontend` | Node 22 + `npm ci` + `npm test` + `npm run lint` + `npm run build` | ✅ 四步都跑 |
| `e2e` | postgres service + 启动真实后端 + Vite dev server + 3 个 Playwright 配置 | ✅ 这是超出一般水平的 |
| `deployment` | `docker build` 两个镜像 + `docker compose config` | ✅ 校验 Compose 配置有效性 |

值得肯定的细节：
- E2E 作业用 `openssl rand -base64 48` **为每次运行生成隔离密钥**，而不是复用固定值。
- E2E 后端的启动脚本会**检测进程是否提前退出并打印日志**，而不是干等 60 次。
- 失败时打印服务日志（`if: failure()`），`always()` 阶段用进程组 `kill -TERM -- "-$pid"` 清理。
- `concurrency` + `cancel-in-progress` 避免重复构建。

**CI 盲区（P2）**：

1. **不验证 Compose 实际起栈**。`docker compose config` 只做语法/变量校验，**不会发现**"backend 起来了但连不上 postgres"、"healthcheck 路径写错"、"nginx 模板变量没替换"这类运行时问题。当前 backend 健康检查路径 `/actuator/health/readiness` 只有在 `prod` profile 下才存在（`application-prod.yml` 定义了 readiness group），而 `config` 校验不会执行到这一步。
2. **`./mvnw` 在本机不可用**（wrapper jar 无法作为 `-cp` 主类启动，且 wrapper 下载的 distribution 缺少 `lib/`），CI 上是可用的（干净环境会重新下载），但这说明**本地开发体验与 CI 不一致**。
3. **无依赖漏洞扫描**（无 Dependabot / OWASP dependency-check / `npm audit` gate）。

### 14.5 依赖

- Spring Boot 3.3.5、Java 21、jjwt 0.12.6、MyBatis-Plus 3.5.9、Flyway 10.20.0、Testcontainers 1.20.0、POI 5.5.1、commons-csv 1.14.1。
- **无重复依赖**，测试依赖都标了 `<scope>test</scope>`，Lombok 标了 `<optional>true</optional>` 并在 spring-boot-maven-plugin 里排除。✅
- **无已知高风险用法**。jjwt 0.12.x 是当前 API 风格（`verifyWith` / `parseSignedClaims`），不是废弃的 0.9 风格。
- 前端 `npm audit` 未执行（`npm ci --no-audit`），无自动漏洞门禁。P2。

**关于升级**：Spring Boot 3.3.x 已不是最新，但**不建议在没有具体理由的情况下升级** —— 3.3.5 是受支持版本，升级会引入 MyBatis-Plus / springdoc / Flyway 的兼容面。仅在出现 CVE 时升级。

### 14.6 本轮环境风险（必须记录）

**本机的 Docker 守护进程未运行**（`\\.\pipe\dockerDesktopLinuxEngine` 不存在，进程列表无任何 docker 进程）。因此：

- 54 个 PostgreSQL 集成测试类无法执行；
- Playwright E2E 无法执行；
- `docker compose` 起栈无法验证。

**这不是代码问题，但它直接影响本次审计的证据强度**：项目的核心安全性质（并发、锁、原子性、迁移）**全部依赖这 54 个测试来证明**，而它们在本环境不可运行。

**建议**：把「集成测试可在本地一键运行」纳入 Definition of Done 的前置条件，并在 README/`docs/engineering/development.md` 里显式声明"运行后端测试需要 Docker Desktop 已启动"。见第 21 节推荐工作项。

---

## 15. P0 Issues

**本轮未发现 P0 级缺陷。**

为避免"没找到"和"没找"混淆，列出我逐一验证后排除的 P0 候选及排除依据：

| P0 候选类别 | 验证方式 | 结论 |
| --- | --- | --- |
| 金额算错 | 全量检查 BigDecimal 用法、比较语义、scale/rounding 策略、DB 类型 | 排除（唯一缺口是输入校验不对称，损害有界，见 P1-1） |
| 数据丢失 | 检查全部 `DELETE` / `DROP` / 迁移 destructive change | 排除（投资事实 DB 层禁止 DELETE；迁移无 DROP TABLE/COLUMN） |
| 用户越权 / IDOR | 穷举全部按 ID 查询的调用点 | 排除（全部带 `user_id` 或显式归属校验） |
| 密码泄漏 | 检查存储、日志、错误消息 | 排除（BCrypt；无日志泄漏；登录失败消息统一） |
| JWT Secret 泄漏 | 全仓密钥扫描 | 排除（仅环境变量，无默认值，无硬编码） |
| Transfer 非原子 | 检索 Transfer 实现 | 排除（不存在，显式 400 拒绝 + 文档列为未实现） |
| 严重并发错误（Lost Update / Double Spend） | 阅读锁实现 + 并发测试内容 | 排除（行锁 + 固定锁顺序；Double Spend 因"无透支规则"而是设计内行为） |
| 数据库损坏风险 | 检查约束、trigger、迁移预检 | 排除（约束密度高，迁移 fail-closed） |
| `@Transactional` 未生效 | **写 Spring 探针实测**（含对照组） | 排除（代理生效） |

---

## 16. P1 Issues

### P1-1

> **状态：已修复。** 金额规则已收敛到 `TransactionWriteRules.validateAmount()` 作为唯一来源，两条写入路径共用；`TransactionService` 私有校验已删除。新增 `TransactionWriteRulesTest`（7）与 `TransactionServiceTest`（+3）。证据见 [`FINANCE_FIX_VERIFICATION.md`](FINANCE_FIX_VERIFICATION.md) §2。

**Location:**
`backend/src/main/java/com/financeos/module/ledger/service/TransactionService.java:170-178`（`validateAmountAndDescription`）
`backend/src/main/java/com/financeos/module/ledger/dto/TransactionRequest.java:13`（`@NotNull BigDecimal amount`）
对照实现：`backend/src/main/java/com/financeos/module/ledger/service/TransactionWriteRules.java:16`

**Problem:**
同一个 `transactions` 表有两条写入路径，金额校验强度完全不同：

```java
// TransactionWriteRules（Import 路径使用）—— 严格
if (amount == null || amount.scale() != 2 || amount.abs().compareTo(new BigDecimal("9999999999999999.99")) > 0)
    throw new BusinessException(400, "流水金额不合法");

// TransactionService.validateAmountAndDescription（手工 CRUD 路径）—— 只校验符号
if (TYPE_INCOME.equals(req.type()) && amount.compareTo(BigDecimal.ZERO) <= 0) throw ...;
if (TYPE_EXPENSE.equals(req.type()) && amount.compareTo(BigDecimal.ZERO) <= 0) throw ...;
if (TYPE_ADJUSTMENT.equals(req.type()) && amount.compareTo(BigDecimal.ZERO) == 0) throw ...;
```

`TransactionRequest.amount` 只有 `@NotNull`，没有 `@Digits`、没有 `@DecimalMin`、Service 内也没有 scale / 精度 / 范围校验。而 `transactions.amount` 与 `accounts.balance` 都是 `NUMERIC(18,2)`。

**Risk:**
两个具体后果：

1. **响应值与存储值可能不一致。** `create()` 用内存中的 `transaction` 对象构造响应（`toResponse(transaction)`），而数据库会把 `NUMERIC(18,2)` 的越界小数**静默四舍五入**。客户端提交 `1.005` 会收到 `1.005`，但库里的值是 `1.01`。`PUT` 同理。
2. **流水与余额可能出现 ≤ 1 分的漂移。** `applyDeltas` 计算 `newBalance = balance + delta` 后写回，PG 对 `newBalance` 也做一次四舍五入。当 `balance` 与 `delta` 的小数位组合落入"两次舍入方向不同"的区间时，流水记录的 effect 与余额实际变化量不再相等。例如余额 `0.01`、支出 `0.005`：流水存为 `-0.01`，而 `round(0.01 - 0.005) = round(0.005) = 0.01`，**余额不变**。此时"余额 = Σ effect(流水)"这个不变式被破坏 1 分。

此外，超出 `NUMERIC(18,2)` 范围的金额（如 `1e20`）会以 PostgreSQL `22003 numeric_value_out_of_range` 失败，被 `GlobalExceptionHandler` 归入通用 `DataAccessException` 分支返回 **503「数据库服务暂时不可用」**，而不是 400。

**为什么定级 P1 而不是 P0：** 触发需要客户端提交契约外的输入（前端 `<input type="number" step="0.01">` 与 Import 路径都不会产生 3 位小数）；损害有界（≤1 分）；失败方向安全（超范围时整体回滚，不会写入坏数据）。但它是**唯一一处"金额可以带着未校验的精度进入资金表"的路径**，且修复成本极低。

**Evidence:**

```java
// TransactionService.java:82-88
transaction.setAmount(req.amount());          // 未经 scale 校验
requireExactlyOne(transactionMapper.insert(transaction), "transaction insert");
accountBalanceService.applyDeltas(lockedAccounts,
        List.of(new AccountBalanceMutation(account.getId(), effect(req.type(), req.amount()), true)));
return toResponse(transaction);               // 回显内存对象，非 DB 读回值
```

```sql
-- V1__baseline.sql
amount  DECIMAL(18,2) NOT NULL,
balance DECIMAL(18,2) NOT NULL DEFAULT 0,
```

**Recommended Fix:**
让手工 CRUD 路径复用已有的 `TransactionWriteRules`，消除第二套规则。`TransactionWriteRules.validate(type, amount, currency, description, account, category, userId)` 的签名已经能覆盖 `TransactionService` 现有的全部校验（类型合法性、金额符号、ADJUSTMENT 必须带 description、账户归属与状态、币种、分类可见性与类型匹配），因此这既是修 Bug 也是去重：

- `TransactionService` 注入 `TransactionWriteRules`，删除私有的 `ensureSupportedType` / `validateCategory` / `validateAmountAndDescription` / `requireActive` / `validateCurrency`（或保留为薄封装）。
- `TransactionRequest.amount` 增加 `@Digits(integer = 16, fraction = 2)` 作为 Controller 层的第一道拦截（让 `1.005` 与 `1e20` 都在 400 被拒，而不是打到数据库）。
- 补充 GAP-1 的测试：`amount = 1.005`、`amount = 1e20`、`amount` 精度 17 位。

**不要顺带做的事**：不要因为这次改动去重构 `TransactionService` 的其他部分，也不要改变 `effect()` 的符号语义。

---

### P1-2

> **状态：已修复。** 前端增加 `submitting` in-flight 状态；「一个 key 对应一个用户意图」的逻辑抽到 `utils/transactionCreateIntent.ts`（内容不变复用 key、内容改了换新 key、签名对 key 顺序不敏感）；后端新增 V18 迁移（nullable `idempotency_key` + `(user_id, idempotency_key)` partial unique index）与 `TransactionCommandService` 并发重放。新增 `TransactionCreateIdempotencyPostgresIntegrationTest`（6）与 `transaction-create-intent.test.ts`（7）。证据见 [`FINANCE_FIX_VERIFICATION.md`](FINANCE_FIX_VERIFICATION.md) §3。
>
> **注意**：`PUT /transactions/{id}` 按约定**未**改动。

**Location:**
`backend/src/main/java/com/financeos/module/ledger/controller/TransactionController.java:62-75`
`backend/src/main/java/com/financeos/module/ledger/service/TransactionService.java:73-112`
`frontend/src/pages/Transactions.tsx:35`（`submit`）、`:44`（提交按钮）

**Problem:**
`POST /api/v1/transactions` 与 `PUT /api/v1/transactions/{id}` **没有任何幂等或重复保护**，而同一系统的资金写入接口都有：

| 接口 | 幂等机制 |
| --- | --- |
| `POST /investment/positions` 等 4 个投资命令 | `Idempotency-Key` header + SHA-256 request hash + `uk_investment_transactions_user_idempotency` |
| `POST /investment/transactions/{id}/reversal` / `replacement` | 同上 + `uk_..._reversal_original` |
| `POST /imports/{sessionId}/confirm` | `Idempotency-Key` + 三重 digest + 3 个具名唯一约束 + `recoverKnownUnique()` |
| **`POST /transactions`** | **无** |
| **`PUT /transactions/{id}`** | **无** |

`transactions` 表也没有任何防重复的约束（`V1__baseline.sql` 只建了普通索引）。

前端进一步放大了这个缺口：`Transactions.tsx` 的提交按钮**没有 in-flight 状态、没有 `disabled`**：

```tsx
<button type="submit" className="button button--primary">{editingId ? '保存修改' : '保存'}</button>
```

用户双击「保存」或网络重试会直接产生两条流水，并**各自正确地应用一次余额 delta**（余额本身不会错，但会多出一笔记录和一次重复扣款/入账）。

**Risk:**
资金**记录**被重复创建。注意这不是"余额算错"——因为两次写入是串行且各自完整的，余额与流水始终自洽；问题是**存在两笔本应只有一笔的财务事实**。用户必须手动发现并删除（而删除本身又会正确反向余额，所以是可恢复的）。

对于记账系统，"多出一笔重复支出"是用户可见、需要人工介入的数据质量问题。这是本项目**风险最高的未保护写入接口**。

**为什么定级 P1 而不是 P0：** 数据可自洽、可恢复（用户能删除）；项目定位是记账系统而非支付系统（`docs/product/scope-and-non-goals.md` 明确排除真实资金划转）。如果这是支付系统，同样的问题会是 P0。

**Evidence:**

```java
// TransactionController.java:62-67 —— 无 Idempotency-Key
@PostMapping
public ApiResponse<TransactionResponse> create(@Valid @RequestBody TransactionRequest req, Authentication auth) {
    return ApiResponse.ok(transactionService.create(userId(auth), req));
}
```

```java
// TransactionMapper.java —— 仅有"探测"能力，没有"阻止"能力
@Select("SELECT EXISTS (SELECT 1 FROM transactions WHERE user_id = #{userId} AND account_id = #{accountId} ...)")
boolean existsProbableDuplicate(...);   // 且此方法在 main 代码中从未被调用
```

对比 `TransactionImportConfirmController.java:27`：

```java
@RequestHeader("Idempotency-Key") String idempotencyKey,
```

**Recommended Fix:**
分两步，第一步先做成本最低、收益最大的一半：

1. **前端**：给 `submit` 加 in-flight 标志（`const [submitting, setSubmitting] = useState(false)`），提交期间 `disabled={submitting}` 并阻止重复 `preventDefault` 后的第二次调用。这是 3 行改动，直接消除"双击"这个最高频的触发路径。
2. **后端**：为 `POST /transactions` 引入与投资命令同构的 `Idempotency-Key`（可选 header，缺省时不启用，避免破坏既有客户端），并在 `transactions` 上新增一列 `idempotency_key` + partial unique index `UNIQUE (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL`（新增迁移 V18，不改历史迁移）。命中唯一冲突时按 `InvestmentCommandService.execute()` 的模式重放首次结果。

**不要做的事**：不要为了幂等去改 `TransactionService` 的事务结构，也不要在 `transactions` 上加"业务键唯一约束"（同一账户同一天可能真的有两笔相同金额的支出，唯一约束会误拒）。

---

### P1-3

> **状态：已修复。** 按约定只补 P1-1 / P1-2 的必要回归测试，未做测试数量扩张。净增 25 个后端测试 + 7 个前端测试：`TransactionWriteRulesTest` 7、`TransactionServiceTest` +3、`TransactionCreateIdempotencyPostgresIntegrationTest` 6、`TransactionControllerWebMvcTest` +1、`GlobalExceptionHandlerTest` +8、前端 `transaction-create-intent.test.ts` 7。
>
> P1-2 的前端部分是本次修复中唯一无法用后端测试覆盖的一环（`Transactions.tsx` 没有组件测试环境），因此把「key 何时复用 / 何时重新生成」抽成纯函数 `utils/transactionCreateIntent.ts`，用项目既有的 `node:test` + `utils` 测试约定覆盖。**未引入任何新依赖。**

**Location:**
测试缺口，对应 `GAP-1` / `GAP-2` / `GAP-3`。

**Problem:**
P1-1 与 P1-2 所在的两条路径**完全没有测试**，而系统内与之对等的路径（Import 的金额校验、投资命令的幂等）都有密集测试。

**Risk:**
这两个缺口不会被现有测试发现，也不会被未来重构保护。更具体地说：项目当前的安全感来自"投资和导入路径测得很细"，但**普通流水 CRUD 是被测得最薄的一条路径，而它恰恰是最常被用户使用的路径**。

**Evidence:**

- `TransactionServiceTest`（5 个测试）全部是 Mockito 单元测试，验证"锁了账户、插了事实、应用了 delta"的调用顺序，**没有一个测试覆盖金额 scale / 精度边界**。
- `TransactionControllerWebMvcTest`（9 个测试）覆盖认证、DTO、状态码，**没有重复提交场景**。
- 全仓检索无任何测试断言 `POST /transactions` 的重复提交行为。
- 前端 74 个 Node 测试中无 `Transactions.tsx` 相关测试（该文件没有测试）。

**Recommended Fix:**
随 P1-1 / P1-2 的修复一起补：

- `TransactionServiceTest` 增加：`amount = 1.005` → 400；`amount` 精度 17 位 → 400；`amount = 1e20` → 400（修 P1-1 后）。
- 新增 `TransactionDuplicatePostgresIntegrationTest`（继承 `PostgresIntegrationTest`）：并发两次相同 `Idempotency-Key` 的 POST，断言只产生一条流水、余额只变一次、第二次返回首次结果（修 P1-2 后）。
- 前端增加 `Transactions` 的提交在途状态测试。

**注意**：补测试要在修复之后，让测试成为修复的回归保护，而不是先写测试再猜实现。

---

## 17. P2 Issues

### P2-1：数据库约束冲突被映射为 503 而非 400/409

> **状态：已修复，但修复方案与本文原始建议不同 —— 实施时发现原建议有错。**
>
> 原始建议是「23514 → 400」。实际实施后 `InvestmentDividendFailureIntegrationTest` 立刻失败，暴露出这个映射是**错的**：本项目刻意把 CHECK 约束当作服务端不变式的最后一道防线，其自身的 trigger 用 `ERRCODE = '23514'` 表示**内部一致性失败**；同一批测试还要求未知的 `23505` 必须 fail-closed 返回 500（`unknownCommandUniqueConstraintIsNotMistakenForIdempotentRecovery`）。把 23514 一刀切成 400 会把内部缺陷伪装成客户端错误。
>
> 最终实现：`23505 → 409`、`22003 → 400`、**`23514 → 500`（fail-closed，脱敏文案「数据一致性校验失败」）**、其余保持 503。
>
> **残留缺口（新发现，未修）**：`AccountRequest.type` / `AssetRequest.type` 非法枚举值仍会走到 23514 → 500。正确做法是在 Service 层做枚举校验返回 400，而不是靠 SQLSTATE 猜责任方。因超出本轮约定范围未处理，保留为待办。

**Location:** `backend/src/main/java/com/financeos/common/GlobalExceptionHandler.java:30-38`

**Problem:** `DataAccessException` 的处理只识别 `55P03`（锁不可用）与 `40P01`（死锁），其余全部落到：

```java
log.error("Database operation failed", e);
return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(503, "数据库服务暂时不可用"));
```

而以下情况都会走到这里：

| SQLSTATE | 场景 | 当前返回 | 应为 |
| --- | --- | --- | --- |
| `23505` unique_violation | 并发注册同名用户 / 同邮箱（`users.username UNIQUE`） | 503 | 400「用户名已存在」 |
| `23514` check_violation | `AccountRequest.type` 非法枚举值（`accounts.type` CHECK） | 503 | 400 |
| `23514` check_violation | `AssetRequest.type` / `market` 非法枚举值 | 503 | 400 |
| `22003` numeric_value_out_of_range | 金额超出 `NUMERIC(18,2)`（见 P1-1） | 503 | 400 |

**Risk:** 数据完整性由数据库约束保护住了（这部分是对的），但**状态码在撒谎**。503 的语义是"服务暂时不可用，可重试"，会诱导客户端自动重试一个**永远不会成功**的请求（如非法枚举值），也会掩盖真实的功能缺陷（"用户注册重复"被报告成"数据库挂了"）。

**Evidence:** `UserService.register:28-33` 先 `selectCount` 预检再 `insert`，并发下必然出现一个 23505；`AccountRequest.type` / `AssetRequest.type` 只有 `@NotBlank`，非法值直接打到 CHECK 约束。

**Recommended Fix:** 在 `handleDataAccessException` 中按 SQLSTATE 分派：`23505` → 409（或按约束名映射为 400），`23514` → 400，`22003` → 400。项目已有现成的模式可复用 —— `TransactionImportConfirmService.isExpectedUniqueConstraint()` 就是按**具名约束**精确分派的做法，把它提升为通用工具即可。同时把 `AccountRequest.type` / `AssetRequest.type` 改为枚举校验，让绝大多数情况在 Controller 层就被拒。

---

### P2-2：`AssetRequest` 的 quantity / avgCost 缺少 scale 与精度校验

**Location:** `backend/src/main/java/com/financeos/module/asset/dto/AssetRequest.java`、`AssetService.create:77-92`

**Problem:** `quantity` 与 `avgCost` 只有 `@NotNull @Positive`。数据库是 `NUMERIC(28,8)`（`V6__align_asset_projection_precision.sql`）。提交 `1.000000001` 会被静默舍入，`create` 的响应回显内存值而非 DB 读回值，与 P1-1 是同一类问题。`AssetService` 只校验 currency，不校验 `type` / `market` 枚举。

**Risk:** 与 P1-1 同源但影响面较小（Legacy Asset 的 quantity/avgCost 是用户手工快照，不是账务真值 —— `docs/domain/business-rules.md` §5.4 明确 `currentPrice`/`marketValue` 是参考展示）。**注意 `transaction-driven` Asset 的同类字段是由 replay 写入的，不走这个 DTO**，所以不会污染投资账本。

**Recommended Fix:** 加 `@Digits(integer = 20, fraction = 8)`，并在 `AssetService.create/update` 校验 `type` / `market` 枚举。

---

### P2-3：`requireAccountBalanceRange` 在投资写路径上不一致

**Location:** `InvestmentCommandTransactionalService.java:256`（BUY/SELL 的 `post()` 未调用）vs `:307`（`postDividend` 调用）vs `:208`（`reverse` 调用）；`InvestmentReplacementTransactionalService.java:110` 用的是内联 `balanceAfter.precision() > 18` 判断。

**Problem:** 同一组写路径里，余额范围校验的实现方式和覆盖范围都不一致：`post()` 完全没有这个校验，另两条路径有，replacement 用的是手写判断。

**Risk:** `post()` 在余额溢出时不会给出干净的 400，而是让 INSERT 打到 `NUMERIC(18,2)` 溢出 → `22003` → 503。**失败方向是安全的**（整体回滚，不会写入坏数据），所以只是错误语义问题。

**Recommended Fix:** 把 `requireAccountBalanceRange` 抽到共享位置，三条路径统一调用。

---

### P2-4：Testcontainers 每个测试类启动一个容器

**Location:** `backend/src/test/java/com/financeos/integration/PostgresIntegrationTest.java:23-24` + 14 个自带 `@Container` 的类

**Problem:**

```java
@Container
private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");
```

`@Container` 是**每个测试类一个实例**，没有 `withReuse(true)`、没有静态单例容器。实测：**40 个类继承 `PostgresIntegrationTest` + 14 个类自带 `@Container` = 54 次容器启动**。叠加 `@DirtiesContext(AFTER_CLASS)` 导致的 Spring 上下文重建。

**Risk:** CI 的 `backend` 作业超时设为 20 分钟。54 次容器启动 + 54 次 Spring 上下文加载，在 GitHub runner 上是一个真实的超时风险，也是本地开发"不愿跑全量测试"的直接原因（**本次审计就因此没能验证核心并发测试**）。

**Recommended Evidence:** 本地实测 54 个类全部因容器启动失败而 error，其余 68 个类 318 个测试约在 1 分钟内完成。容器启动是这条流水线的绝对瓶颈。

**Recommended Fix:** 两种方案，按侵入性从低到高：

1. **最小改动**：在 `PostgresIntegrationTest` 里把容器改为静态单例（`static PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(...).withReuse(true)`，配合 `~/.testcontainers.properties` 的 `testcontainers.reuse.enable=true`），并把 `@Container` 换成手工 `@BeforeAll` 启停。让 40 个子类复用同一个容器。
2. **更彻底**：把 14 个自带容器的类也改为继承基类，统一到单一容器 + 每个测试前 `TRUNCATE`。

**注意**：不要为了提速而放弃"真实 PostgreSQL 验证"（`docs/engineering/testing.md` 明确禁止用 H2 推断 PostgreSQL 行为）。这个问题的解是**复用容器**，不是**换内存数据库**。

---

### P2-5：前端金额传输路径不统一

**Location:** `frontend/src/pages/Transactions.tsx:35`、`frontend/src/utils/format.ts`、对照 `frontend/src/utils/transactionImportTransport.ts`

**Problem:** 同一个前端里有两种金额处理标准：

- **投资命令与 Import**：用 `lossless-json` 解析 + 正则校验 `^-?(?:0|[1-9]\d*)(?:\.\d+)?$`，金额以**字符串**在前后端间传递，不经过 IEEE-754。
- **普通流水**：`amount: Number(form.amount)` 直接发 JSON number；`formatCurrency(value: number)` 用 number 渲染。

**Risk:** `Number` 是 IEEE-754 double。对 CNY 两位小数、绝对值 < 2^53 的场景，double 能精确表示（因此**当前不会出错**），但这是一个依赖数值范围的隐式假设，而不是被强制的不变式。同时系统内部标准不一致，会让人误以为"哪边更严格"没有区别。

**Recommended Fix:** 统一到字符串传输（`amount: form.amount`，后端 `TransactionRequest.amount` 改为 `String` + 复用 `StrictDecimalStringDeserializer`）。这是与投资/导入路径对齐的做法。**优先级低于 P1-1/P1-2**，因为当前没有实际精度损失。

---

### P2-6：JWT 无撤销机制、存放于 localStorage

**Location:** `frontend/src/api/index.ts:28`、`backend/src/main/java/com/financeos/module/auth/util/JwtUtil.java`

**Problem:** token 存在 `localStorage`（任意 XSS 可读取）；24 小时有效期内无 `jti`、无黑名单、无 refresh token；改密码不会使已签发的 token 失效。

**已有的缓解：** `JwtAuthFilter` 每请求回查 `users` 并校验 `status = ACTIVE`，所以**禁用账号立即生效**。这是有效缓解，但不能覆盖"改密码后旧 token 仍可用"。

**Risk:** 中等。对本项目（无第三方脚本、无用户生成内容注入面）实际风险低。前端目前未使用 `dangerouslySetInnerHTML`，唯一的 HTML 注入点是 ECharts 的 `tooltip.formatter` 返回 HTML 字符串（`MonthlyCashFlowChart.tsx:27` 等）—— 但那里拼接的是数值和固定标签，不含用户输入。

**Recommended Fix:** 记录为已知取舍即可。若要加固：加 `password_changed_at` 声明并在过滤器里比对；或引入 refresh token + 短 access token。

---

### P2-7：登录接口无限流

**Location:** `backend/src/main/java/com/financeos/module/user/service/UserService.java:41-51`

**Problem:** `POST /api/v1/login` 是 `permitAll()`，无速率限制、无失败计数、无锁定。`BCryptPasswordEncoder` 默认强度 10 使暴力破解成本较高（每次尝试约数十毫秒），但没有请求层防护意味着可以无限尝试。

**Risk:** 低到中。单实例部署下，攻击者可持续尝试。项目已有 `ExchangeRateRateLimiter` / `MarketQuoteRateLimiter` 的现成模式可以复用。

**Recommended Fix:** 加一个按 IP + username 的简单内存限流（项目已有同类实现范式），或在 nginx 层加 `limit_req`。

---

### P2-8：nginx 缺少 `client_max_body_size`

> **状态：已修复。** `location /api/` 增加 `client_max_body_size 6m;`（略高于后端 5 MiB 限制），最终大小校验仍由后端执行并返回统一 API 错误。见 [`FINANCE_FIX_VERIFICATION.md`](FINANCE_FIX_VERIFICATION.md) §5。

**Location:** `frontend/nginx/default.conf.template`

**Problem:** 后端 Import 明确支持 5 MiB 文件（`ck_transaction_import_sessions_file_size CHECK (file_size > 0 AND file_size <= 5242880)`，并有 413 处理与测试），但 nginx 默认 `client_max_body_size 1m`。

**Risk:** 1–5 MiB 的上传在生产环境会被 nginx 以 413 拒绝，**根本不会到达后端**。后端为此写的 413 错误信封与前端错误处理逻辑在生产路径上永远不会触发。这是一个"后端契约与部署配置不一致"的典型问题，本地开发（Vite dev server 直连后端）不会暴露它。

**Recommended Fix:** 在 `location /api/` 里加 `client_max_body_size 6m;`（略大于后端限制，让后端成为真正的裁判并返回结构化错误）。

---

### P2-9：CI 未验证 Compose 实际起栈

**Location:** `.github/workflows/ci.yml`（`deployment` 作业）

**Problem:** `docker compose --env-file docker/.env.example -f docker/compose.yml config` 只做配置解析与变量插值校验。它不会发现：healthcheck 路径错误、服务间网络不通、nginx 模板变量未替换、backend 因缺环境变量启动失败。

**Risk:** "CI 绿色"与"部署能起来"之间存在缺口。特别地，backend 的 healthcheck 打 `/actuator/health/readiness`，该 endpoint 只在 `prod` profile 下定义 —— 如果有人改了 profile 名或 health 配置，`config` 校验不会有任何反应。

**Recommended Fix:** 在 `deployment` 作业里加一步 `docker compose up -d --wait`（Compose v2 的 `--wait` 会等待所有 healthcheck 通过），然后 `docker compose down -v`。这会把部署冒烟纳入 CI。

---

### P2-10：投资模块内部输入加固不对称

**Location:** `InvestmentCommandTransactionalService.java:594-609`（`amounts`）vs `:611-634`（`dividendAmounts`）

**Problem:** `amounts()` 对 trade 的四个金额只调用 `validateScale`，**不调用 `validatePrecision`**；`dividendAmounts()` 两者都调用。

**Risk:** trade 的 `quantity` / `unitPrice`（`NUMERIC(28,8)`）若提交 29 位以上精度，不会被应用层拒绝，而是打到数据库。失败方向安全（回滚），但状态码会是 503 而非 400。

**Recommended Fix:** `amounts()` 也补 `validatePrecision`。

---

## 18. P3 Technical Debt

仅记录，本轮不建议动。

1. **`logic-delete-field: deleted` 是哑弹。** `application.yml` 配置了全局逻辑删除字段，但无实体/表使用。当前无影响；一旦有人给实体加 `deleted` 字段，MyBatis-Plus 会静默开始过滤，而所有手写 SQL 不过滤，造成读写视图不一致。建议删除该配置项。
2. **`TransactionMapper` 有三个死方法**：`existsProbableDuplicate`、`findProbableDuplicateIds`、`sumByType` 在 main 代码中零调用（已核实）。`findProbableDuplicateCandidates` 才是实际使用的那个。
3. **`V5` 依赖 PostgreSQL 自动生成的外键名**（`DROP CONSTRAINT investment_transactions_replaces_transaction_id_fkey`）。当前 PG 版本下稳定，但属于隐式契约。建议后续迁移对需要再次操作的约束一律显式命名。
4. **无架构级回归测试。** `ADR-008` 的两个全局约束（"只有 `AccountBalanceService` 能写 `accounts.balance`"、"不得有 Account → 事实 的反向加锁路径"）目前只靠文档和人工 review 维持，代码零违规但没有机制防止未来违规。可考虑 ArchUnit。
5. **测试辅助方法大量重复。** `passThroughJwtFilter`（10 份）、`assertUnchanged`（6 份）、`insertCompleteReplacementGroup`（5 份）、`awaitDatabaseLockWait`（3 份）、`assertSafeInternalFailure`（3 份）。应抽到共享测试基类。
6. **前端 bundle 未做代码分割。** 单 chunk 1,009 kB（gzip 327 kB），Vite 已给出警告。ECharts 是主要体积来源，可按页面动态 import。
7. **`RegisterRequest.password` 仅 `@Size(min = 6)`。** 无强度要求（无大小写/数字/符号要求），无常见弱口令黑名单。
8. **`JwtUtil.parseToken` 未固定算法/issuer/audience。** jjwt 0.12 的 `verifyWith(SecretKey)` 已能拒绝 `alg=none` 与非 HMAC 算法，因此不是漏洞；但显式 `requireIssuer()` 能防止"多系统共用 secret"的误接受。
9. **`AccountController` 用 `@GetMapping("/{id}")` 与 `@GetMapping("/page")` 并存。** Spring 的精确匹配优先规则保证正确，但 `/page` 作为保留字与 `/{id}` 共处一 Controller 是易踩的坑（如果未来有人把 id 改成 `String`，`/page` 会开始报类型转换错误）。
10. **`V1__baseline.sql` 用 `CREATE TABLE IF NOT EXISTS`，其余迁移不用。** 风格不一致（无实际影响）。
11. **README 引用了外部 Demo 站点**（`personal-finance-os-demo.qianlixunbai.chatgpt.site`）和 GitHub Actions badge。Demo 声明为"独立静态只读展示，不连接本仓库后端"，属于诚实的表述；但外部链接会随时间失效。
12. **`@Transactional` 未显式指定 `rollbackFor`。** 当前全部异常都是 `RuntimeException` 子类，行为正确；显式声明能在未来有人引入受检异常时防止静默不回滚。
13. **`TransactionService.create/update` 的响应在事务提交前构造。** 由于提交发生在方法返回时（Controller 拿到返回值之前），异常会正确传播为 5xx，行为正确。记录为可读性债务。

---

## 19. Tests Worth Keeping

按"保留价值"排序，这些是项目最应该保护的测试资产：

**第一梯队 —— 直接证明资金安全性质**

1. **`TransactionBalanceConcurrencyPostgresIntegrationTest`（13 个）** —— 用真实 PostgreSQL + `pg_locks` 观测锁等待 + 临时 trigger 注入失败。这是全项目最有价值的测试类。它同时证明了：并发不丢更新、并发删除只反向一次、跨账户 swap 不死锁、跨用户 404、失败整体回滚。
2. **`Investment*FailureIntegrationTest`（dividend / reversal / replacement）** —— 中途失败必须整体回滚。资金系统里"部分写入"是最致命的一类 Bug，这些测试直接针对它。
3. **`Investment*UnknownCommitIntegrationTest`（firstBuy / replacement）** —— 覆盖"已提交但响应未知"。这是分布式/网络语义下最难测的一类，能测它说明作者理解幂等的真实场景。
4. **`Investment*LockIntegrationTest` + `Investment*ConcurrencyIntegrationTest`** —— 锁顺序与竞争的实证。
5. **`FlywayMigrationIntegrationTest` + 12 个 `FlywayVersion*UpgradeIntegrationTest`** —— 保证历史库能升到最新。这是"生产可升级性"的唯一证明。

**第二梯队 —— 直接证明业务正确性**

6. **`InvestmentLedgerCalculatorTest`（15）+ `InvestmentReplayEngineTest`（20）** —— 纯函数级验证金额公式、舍入残差、partial/full sell、reopen、reversal 重放排除。
7. **`TransactionImportConfirmIntegrationTest`（27）** —— 幂等、具名约束恢复、exact duplicate、evidence drift、receipt 重建。
8. **`InvalidJwtApiIntegrationTest` + `SecurityConfigTest`** —— 认证边界。
9. **`InvestmentReadQueryPostgresIntegrationTest`（18）** —— 读模型 SQL（多表 JOIN 的 `user_id` 条件）的正确性。

**第三梯队 —— 必要的分层回归**

10. `*ControllerWebMvcTest`（8 个类）—— 状态码、认证、DTO 契约。
11. `*ServiceTest`（Mockito）—— 调用顺序与分支。
12. 前端 74 个 Node 测试 —— 特别是 `transaction-import` 相关（lossless 金额传输、recovery 状态机）。

**保留原则：** 任何断言了"异常情况下系统如何表现"的测试，价值都高于断言"正常路径返回什么"的测试。这个项目的高价值测试几乎全部属于前者。

---

## 20. Tests Worth Reviewing

**只标记，不删除。** 以下都需要人工复核后再决定，且**本轮的 54 个 Docker 依赖类未执行，因此我对它们内部是否重复的判断依据是静态结构，不是执行结果**。

| 对象 | 观察 | 建议动作 |
| --- | --- | --- |
| 10 个 WebMvc 测试类中的 `passThroughJwtFilter` | 同名辅助方法复制了 10 份 | 抽到共享测试基类/工具类。**不删测试，只抽公共代码** |
| 6 份 `assertUnchanged`、5 份 `insertCompleteReplacementGroup`、4 份 `assertNoWaitingLocks`、3 份 `awaitDatabaseLockWait`、3 份 `assertSafeInternalFailure` | 测试基础设施重复实现 | 同上 |
| `InvestmentReplacementFailureIntegrationTest`（22 个测试）、`InvestmentReplacementMigrationIntegrationTest`（22）、`TransactionImportConfirmIntegrationTest`（27） | 类体量大，**内部可能存在可参数化的同构失败路径** | **需在 Docker 可用后实际执行一次**，查看是否有测试方法只改了输入常量而断言结构完全相同。若有，参数化合并；若各测试注入点不同，保持原样 |
| 12 个 `FlywayVersion*UpgradeIntegrationTest` | 每个类 1 个测试、各自启动一个容器 | 测试价值保留，但**应改为共享容器**（见 P2-4）。也可考虑合并为参数化的单类 |
| 单测试类：`SecurityConfigTest`、`MarketQuoteServiceAvailabilityTest`、`AssetQueryServiceTest`、`AccountQueryServiceTest`、`AssetReferenceValuationServiceTest`、`ExchangeRateQueryServiceTest`、`TransactionImportConfirmPersistenceIntegrationTest` | 价值密度尚可（各自验证一个关键性质） | **保持不动** |

**明确不需要做的事：**

- 不要因为"测试类太大"就拆分或删除 —— 大测试类在资金系统里通常是好事（共享 fixture、共享失败注入机制）。
- 不要为了提升覆盖率补测试。当前 1.70 的测试/主代码比已经很高，问题不是"少"，而是"两条路径没覆盖"（已列为 P1-3）。
- 不要删除任何 `Flyway*` 或 `*Concurrency*` / `*Lock*` / `*Failure*` / `*UnknownCommit*` 测试。

---

## 21. Recommended Next Work

> **执行状态（2026-09-23 更新）：第 1–4 项已完成。** 第 1 项（打通集成测试可执行性）已达成 —— Docker 恢复后拿到 644 tests / 0 失败的干净基线。第 2–4 项对应的 P1-1、P1-2、P2-1、P2-8 均已修复并回归通过。**第 5 项（Testcontainers 容器复用）按约定未处理，仍是当前最高优先级的剩余工作。**

只列 5 项，按 Impact / Risk / Effort 排序。

### 1. 打通集成测试的可执行性（最高优先级，虽然它不是代码缺陷）

- **Impact：高** —— 这是解锁其余所有工作的前提。54 个测试类（含全部并发、锁、原子性、迁移验证）当前在本机不可运行，而它们是本项目核心安全性质的唯一证据来源。
- **Risk：低** —— 不改任何业务代码。
- **Effort：低** —— 在 `docs/engineering/development.md` 与 README 显式声明"运行后端测试需要 Docker 已启动"；确认 Docker Desktop 启动后重跑 `mvn test`，得到一份真实的绿色基线；记录真实的测试数（预期 600+）与耗时。
- **为什么排第一**：在拿到这份绿色基线之前，本报告第 4/5/6/10 节的结论都建立在静态推演上。**先让证据成立，再谈改动。**

### 2. 修复 P1-1：普通流水金额校验对齐 `TransactionWriteRules`

- **Impact：高** —— 消除唯一一处"未校验精度可进入资金表"的路径，同时消除第二套业务规则。
- **Risk：低** —— 改动是"收紧校验"，只会让原本被静默接受的非法输入变成 400。唯一需要确认的是没有既有客户端依赖 3 位小数输入。
- **Effort：低** —— 复用现成的 `TransactionWriteRules`，删除重复的私有校验方法；`TransactionRequest.amount` 加 `@Digits`。
- **交付**：代码改动 + GAP-1 的测试（`amount = 1.005` / `1e20` / 17 位精度 → 400）。

### 3. 修复 P1-2：`POST /transactions` 的重复提交保护

- **Impact：高** —— 这是用户最常使用的写入接口，也是唯一没有重复保护的。
- **Risk：中** —— 后端引入 `Idempotency-Key` 需要新增迁移（V18，新增列 + partial unique index）和恢复逻辑，要小心不要改变既有客户端的可用性（**header 可选、缺省不启用**）。
- **Effort：中** —— 前端 3 行 + 后端迁移 + 按 `InvestmentCommandService.execute()` 的同构模式实现重放。
- **建议拆成两次提交**：先做前端 in-flight 保护（立刻消除双击，风险接近零），再做后端幂等键。

### 4. 修复 P2-1 + P2-8：错误语义与部署配置对齐

- **Impact：中高** —— P2-1 影响所有数据库约束冲突的状态码（把"用户名重复"报成"数据库不可用"）；P2-8 让生产环境的 1–5 MiB 导入直接失效。
- **Risk：低** —— 两者都是纯映射/配置修正，不动业务逻辑。
- **Effort：低** —— P2-1 按 SQLSTATE 分派（复用 `isExpectedUniqueConstraint` 的具名约束模式）；P2-8 在 nginx 加一行 `client_max_body_size 6m;`。
- **额外收益**：P2-8 修完后，后端已有的 413 处理与测试才真正在生产路径上生效。

### 5. 修复 P2-4：Testcontainers 容器复用

- **Impact：中高** —— 直接把 CI 的 `backend` 作业（20 分钟超时）从 54 次容器启动降到 1–2 次，同时让本地全量测试变得可行。这是第 1 项能长期维持的前提。
- **Risk：中** —— 容器复用 + `TRUNCATE` 隔离需要确认没有测试依赖"全新数据库"（例如 Flyway 迁移测试**必须**用干净库，它们可能不能共享容器）。
- **Effort：中** —— 改 `PostgresIntegrationTest` 基类为静态单例；把 14 个自带容器的类统一。**但必须区分对待**：迁移测试保持独立容器，业务集成测试共享容器。

### 明确不建议现在做的事

- ❌ 引入 Transfer 功能（超出当前冻结范围，且是本项目风险最高的一类新能力）
- ❌ 升级 Spring Boot / 引入新框架
- ❌ 为 P2/P3 项做批量重构
- ❌ 为了覆盖率补测试
- ❌ 重构 `TransactionService` 的整体结构（只做第 2 项的校验对齐）

---

## 22. Final Assessment

### 22.1 作为校招简历「第二核心项目」是否够格

**够格，而且是偏强的一侧。**

判断依据不是"代码写得漂亮"，而是三条可核查的事实：

1. **它解决了一个真实的困难问题。** 金额的确定性、余额的并发安全、投资事实的不可变与可重放，这三件事有客观的对错标准，而项目在每一件上都给出了可验证的实现（`NUMERIC` + CHECK 约束编码金额公式、`FOR UPDATE` + 固定锁顺序、append-only trigger + replay engine）。
2. **它的正确性不依赖开发者的自觉。** 数据库约束、trigger、`Propagation.MANDATORY` + `isActualTransactionActive()` 双重守卫、具名约束冲突恢复 —— 这些都是"让错误不可能发生"的机制，而不是"记得不要犯错"的纪律。这是区分"会写 CRUD"和"理解数据一致性"的分水岭。
3. **它的测试在测异常而不是测正常。** 并发、锁等待（用 `pg_locks` 观测）、失败注入（用临时 trigger 强制余额更新失败）、unknown commit、历史迁移升级路径 —— 这些测试的存在本身就证明作者思考过"系统会怎么坏"。

需要诚实说明的两点局限：

- **它不是转账系统，也没有实现转账。** 项目自己把 `TRANSFER`/`REFUND` 列为未实现、把真实资金划转列为非目标。面试时**必须主动说清楚这一点**，否则一旦被追问"你的转账怎么保证两边原子性"，会非常被动。
- **它的并发结论依赖 54 个测试类，而这 54 个类需要在 Docker 环境下运行。** 如果面试时被要求现场演示，先确认环境。

### 22.2 从面试官角度：最值得讲的 5 个技术点

**① 为什么余额用行锁而不是乐观锁，以及锁顺序怎么定**

讲 `ADR-008` 的决策：全局锁顺序为「事实行 → accountId 升序去重的 Account 行 → 投影」。讲 `AccountBalanceService.lockOwnedAccounts` 里 `distinct() + sorted()` 的作用 —— 它是把"死锁"从"概率问题"变成"结构上不可能"。再讲 `set_config('lock_timeout', '4s', true)` 的第三个参数 `true` 是 transaction-local，以及为什么超时要映射成 409 而不是 500。**这一个点就能区分"用过 `@Transactional`"和"想过事务边界"的人。**

**② `Propagation.MANDATORY` + `isActualTransactionActive()` 的双重守卫**

```java
@Transactional(propagation = Propagation.MANDATORY)
public LockedAccounts lockOwnedAccounts(...) {
    requireTransaction();   // TransactionSynchronizationManager.isActualTransactionActive()
    ...
}
```

讲清楚：MANDATORY 保证"没有外层事务就拒绝"，但 MANDATORY 依赖 Spring 代理；`isActualTransactionActive()` 是不依赖代理的运行时事实检查。两者叠加后，"余额变更脱离事务"从"可能发生"变成"不可能发生"。**这是一个非常具体、非常好讲的工程判断。**

**③ 把金额公式写进数据库 CHECK 约束**

```sql
CONSTRAINT ck_investment_transactions_sell_amounts CHECK (
    transaction_type <> 'SELL'
    OR (gross_amount > 0
        AND net_amount = gross_amount - fee_amount - tax_amount
        AND net_amount >= 0
        AND released_cost_amount > 0
        AND realized_profit_loss = net_amount - released_cost_amount)
)
```

讲：为什么"应用层已经校验了"还不够（应用层会有 Bug、会有新写入者、会有手工 SQL），为什么资金系统的数据库必须作为最后一道防线。再讲 `uk_accounts_user_id_id UNIQUE (user_id, id)` + 复合外键 `(user_id, account_id) REFERENCES accounts(user_id, id)` —— 用数据库结构在物理上阻断跨用户引用。

**④ append-only 投资事实 + 确定性重放**

讲 `trg_prevent_investment_transaction_mutation` 让投资事实在数据库层不可 UPDATE/DELETE；讲错误交易如何通过 `REVERSAL`（排除原始事实后重放）或 `REPLACEMENT`（reversal + 新事实 + correction 记录）纠正；讲 `InvestmentReplayEngine` 如何用 `(effectiveTradeTime, anchorId, replaySequence, id)` 四元组定义确定的排序，从而让"当前持仓"成为事实集的纯函数。**这是项目里技术含量最高、最不容易被普通 CRUD 项目替代的部分。**

**⑤ 幂等 + unknown commit 恢复，以及"按具名约束分派恢复策略"**

讲投资命令的 `Idempotency-Key` + SHA-256 request hash + `uk_investment_transactions_user_idempotency`：同一 key 同 hash → 重放首次结果；同 key 不同 hash → 409 拒绝。讲 `InvestmentCommandService.execute()` 如何捕获 `23505` 后重查并重放，以及为什么这是安全的（PG 的 INSERT 遇到未提交冲突会阻塞到对方提交，所以拿到 23505 时对方必然已提交，重查一定看得到）。

再讲 `TransactionImportConfirmService.recoverKnownUnique()` 更进一步：它**解析 PostgreSQL 错误里的约束名**，为 `uk_..._user_idempotency` / `uk_..._exact_duplicate` / `uk_..._user_session` 三种冲突分派不同的恢复策略，其余一律 fail-closed 返回 500。**这个"不笼统 catch 唯一冲突"的处理，是很有说服力的细节。**

### 22.3 最可能被追问的风险点

按"被问到的概率 × 答不上来的代价"排序。

| 追问 | 为什么会被问 | 必须准备什么 |
| --- | --- | --- |
| **「你的转账怎么保证两边原子性？」** | 项目叫 Finance OS，简历上很容易让人以为是支付系统 | **主动澄清**：本版本不实现 TRANSFER，`transactions.type` 里有这个枚举但 Service 显式 400 拒绝，且文档把真实资金划转列为非目标。然后说明"如果要做，会复用 `lockOwnedAccounts` 的升序锁 + 单事务写两条流水"。**被动挨问会非常难看** |
| **「余额允许为负，那还叫资金系统吗？」** | `business-rules.md` §2.3 明确写"没有 insufficient-balance 拒绝规则" | 说明这是记账系统（记录已经发生的事实）而非支付系统（阻止未发生的交易）的有意设计；并说明如果要加透支保护，会在 `applyDeltas` 的锁内做 `newBalance.signum() < 0` 检查 —— 因为锁已持有，检查是安全的 |
| **「`TransactionService` 和 `TransactionWriteRules` 为什么有两套金额校验？」** | 这是 P1-1，代码里客观存在 | **承认这是缺陷**（同一个表两条写入路径校验强度不同，已列为 P1 并给出修复方案）。不要辩解。承认并说出修复路径，比假装没问题得分更高 |
| **「`POST /transactions` 为什么没有幂等键？双击会怎样？」** | 同一系统的投资命令和 Import 都有幂等键，对比非常明显 | **承认这是不一致**（P1-2）。说明现状：会创建两条流水、余额各自正确应用（所以余额自洽，但事实重复）；说明前端按钮也没有 in-flight 保护；说明修复方案（header 可选 + partial unique index + 重放）。**主动提出这个对比，比被问出来好** |
| **「你的并发测试真的跑过吗？怎么证明不会 Lost Update？」** | 这是最容易造假的地方，面试官会追问细节 | 讲 `concurrentCreatesAgainstTheSameAccountPreserveBothDeltas` 的具体机制：`CyclicBarrier` 让两个线程同时进入、断言最终余额 130.00、断言 `COUNT(*) = 2`。更关键的是讲 `updateThenDelete` 那个测试：它用 `CountDownLatch` 控制第一个事务在提交前挂起，然后轮询 `SELECT COUNT(*) FROM pg_locks WHERE NOT granted` 确认第二个事务**真的进入了锁等待**，再释放。**能讲出"怎么确认第二个事务真的被阻塞了"，比讲"断言了最终值"有力得多** |
| **「`@Transactional` 加在包级私有方法上还有效吗？」** | 三个 `*TransactionalService` 都是包级私有类 + 包级私有方法，这是懂 Spring 的人一眼会注意到的地方 | 这是一个陷阱问题。**正确答案是"有效"** —— 我实测过：包级私有方法确实被 CGLIB 代理并开启了事务（对照组返回 false，测试组返回 true）。如果你答"无效"，说明只是背了结论没验证过。答"我专门测过"会非常加分 |
| **「投资事实为什么不能直接 UPDATE？纠正一笔错误交易要走几步？」** | append-only 是投资模块的核心设计 | 讲 trigger 层的不可变约束 + `REVERSAL`（1 条事实 + 余额 + 投影）与 `REPLACEMENT`（2 条事实 + 余额 + 投影 + correction 记录）两条纠正路径的区别；讲 `validate_append_only_investment_reversal()` 在 INSERT 时校验 reversal 的审计值必须与原始事实严格一致（`cash_delta`、`gross_amount`、`fee_amount`… 逐字段比对） |
| **「为什么 Service 已经检查了用户名唯一，数据库还需要 UNIQUE 约束？」** | 标准问题，但这个项目有具体答案 | 讲 `UserService.register` 的 `selectCount` 预检在并发下是 TOCTOU；数据库 `users.username UNIQUE` 是真正的裁判。**然后诚实指出当前实现的一个问题**：拿到 23505 后 `GlobalExceptionHandler` 会返回 503 而不是 400（P2-1）—— 约束挡住了数据问题，但状态码是错的。**能指出自己项目的这个瑕疵，说明真的读过代码** |
| **「54 个集成测试为什么要跑 54 个容器？」** | 如果面试官看 CI 日志会发现耗时异常 | 承认这是 P2-4（每个测试类一个 `@Container`，无 reuse），说明改进方案（静态单例 + `withReuse`），并说明**迁移测试必须保持独立容器**（需要干净库）。不要说"无所谓" |
| **「金额为什么不能用 double？」** | 少量八股是必要的 | 标准答案 + **落到本项目**：`amount` 与 `balance` 都是 `NUMERIC(18,2)`，Java 侧 `BigDecimal`，比较一律 `compareTo`。**然后主动指出前端普通流水路径用的是 JS `number`（P2-5）** —— 在 CNY 两位小数、值域受限时不出错，但这是依赖范围的隐式假设，投资和导入路径已经改用 lossless 字符串传输。**这个"我们自己也没完全统一"的坦白，比宣称"我们完全避免了 double"可信得多** |

### 22.4 一句话总结

**这是一个"技术选择有理由、正确性有机制保障、异常路径有测试证明"的项目，足以作为第二核心项目；但它需要候选人主动、准确地界定自己的边界（没有 Transfer、余额可为负、普通流水路径弱于投资路径），而不是把投资模块的严谨性顺延成对整个系统的宣称。**

---

## Interview Deep Dive

以下 85 个问题（编号 1–85）全部围绕本仓库的真实实现。标 **[重点复习]** 的是代码现状与"教科书答案"存在张力、面试时最容易被问住的问题。

### A. BigDecimal 与金额正确性

1. 你的项目具体在哪些地方使用 `BigDecimal`？数据库对应什么类型？
2. 为什么资金不能使用 `double`？请用一个具体数值说明。
3. `BigDecimal.equals()` 和 `compareTo()` 在金额比较上的区别是什么？你的项目里怎么保证没有误用？
4. 你的项目里金额的 scale 策略是什么？`accounts.balance`、`transactions.amount`、`investment_transactions.net_amount` 分别是多少位？
5. `quantity` 用 `NUMERIC(28,8)`、金额用 `NUMERIC(28,2)`，为什么不是统一精度？
6. `InvestmentLedgerCalculator.money()` 用 `setScale(2, RoundingMode.HALF_UP)`。为什么是 HALF_UP 而不是 HALF_EVEN？如果换成银行家舍入，哪些测试会失败？
7. `requirePositiveRoundedMoney()` 解决的是什么问题？为什么不能先判 `signum() > 0` 再舍入？
8. 买入时 `grossAmount = quantity × unitPrice` 然后舍入到 2 位。这个舍入误差记在哪里？会不会丢失？
9. 部分卖出时 `releasedCost = totalCost × quantity ÷ currentQuantity`（2 位 HALF_UP）。全卖时 `releasedCost = totalCost`。为什么要区别对待？
10. 部分卖出后 `newTotalCost` 可能变成 0 但 `newQuantity > 0`，代码为什么直接拒绝这种情况？
11. **[重点复习]** `TransactionRequest.amount` 只有 `@NotNull`，`TransactionService` 也不校验 scale。客户端提交 `1.005` 会发生什么？为什么 `TransactionWriteRules` 校验了而它没校验？
12. 超范围金额（如 `1e20`）打到 `NUMERIC(18,2)` 会怎样？当前返回什么状态码？应该返回什么？

### B. 事务与原子性

13. 你的项目里哪些业务操作是多写入的？它们的事务边界在哪里？
14. `@Transactional` 加在**包级私有方法**上，Spring 还会开启事务吗？你怎么验证的？
15. `@Transactional` 自调用（self-invocation）为什么会失效？你的代码里有这个问题吗？
16. `AccountBalanceService` 为什么用 `Propagation.MANDATORY` 而不是 `REQUIRED`？
17. `requireTransaction()` 里的 `TransactionSynchronizationManager.isActualTransactionActive()` 和 MANDATORY 是不是重复了？为什么两个都要？
18. 如果 `transactionMapper.insert()` 成功了但 `applyDeltas()` 抛异常，会发生什么？请指出代码里保证这一点的具体机制。
19. 你有哪些测试证明了"中途失败整体回滚"？它们是怎么注入失败的？
20. `TransactionImportConfirmService` 为什么用 `TransactionTemplate` 而不是 `@Transactional`？
21. 一个 Import Confirm 会写多少条数据库记录？它们都在同一个事务里吗？
22. **[重点复习]** `InvestmentCommandService.execute()` 捕获 `DataIntegrityViolationException` 后**重新执行**了一遍命令。为什么这是安全的？为什么第一次执行的部分写入不会残留？

### C. 并发与锁

23. 余额 100，两个请求同时支出 80。请逐步描述数据库层面发生了什么。
24. 你的系统能防止 Lost Update 吗？靠什么机制？请指出具体的 SQL。
25. 你的系统能防止 Double Spend 吗？
26. `selectOwnedForUpdate` 里的 `ORDER BY id ASC` 是做什么的？如果去掉会发生什么？
27. `lockOwnedAccounts` 里的 `distinct()` 和 `sorted()` 分别解决什么问题？
28. `set_config('lock_timeout', '4s', true)` 的第三个参数 `true` 是什么意思？如果传 `false` 会怎样？
29. 锁等待超时和死锁分别产生什么 SQLSTATE？你的项目把它们映射成什么 HTTP 状态码？
30. 为什么超时后返回 409 让客户端重试，而不是服务端自动重试？
31. `crossingAccountSwapsCompleteWithoutReverseOrderDeadlock` 这个测试在测什么？它怎么构造出"可能死锁"的场景？
32. `updateThenDelete` 测试怎么确认第二个事务**真的进入了锁等待**，而不是碰巧先执行完了？
33. **[重点复习]** 你的系统没有悲观锁之外的并发控制吗？`assets.projection_version` 是什么？为什么投资投影用乐观锁而余额用悲观锁？

### D. 死锁与锁顺序

34. 为什么转账必须锁两个账户？如果只锁一个会怎样？（注意：本项目没有实现转账，请说明你会怎么做）
35. 为什么锁多个账户时要保持固定顺序？请构造一个不保持顺序会死锁的具体例子。
36. 你的全局锁顺序是什么？为什么是"事实 → 账户 → Instrument → Asset"而不是别的顺序？
37. 如果未来有人新增一个写路径，先锁 Instrument 再锁 Account，会发生什么？你的代码能拦住吗？
38. 你的项目怎么防止有人绕过 `AccountBalanceService` 直接 `UPDATE accounts SET balance`？

### E. 用户隔离与安全

39. 如何确保用户 A 无法构造 `accountId` 操作用户 B 的账户？请指出具体的代码路径。
40. 你的项目为什么跨用户访问返回 404 而不是 403？这两种选择各有什么安全含义？
41. `AccountService.getById` 用的是 `selectById`（不带用户条件），为什么这是安全的？
42. 系统分类（`user_id IS NULL`）是一个共享资源，它会不会成为越权读取的跳板？
43. **[重点复习]** `AssetMapper.selectOwnedForUpdate` 带 `user_id` 条件，如果某个新开发者写了一个不带 `user_id` 的 `FOR UPDATE` 查询，会有什么后果？你能设计一个测试来防止这种回归吗？
44. 复合外键 `(user_id, account_id) REFERENCES accounts(user_id, id)` 比 `account_id REFERENCES accounts(id)` 多防住了什么？

### F. JWT 与 Spring Security

45. 你的 JWT 用的是什么签名算法？secret 从哪里来？如果没配会怎样？
46. `JwtAuthFilter` 每次请求都查一次数据库，为什么？这弥补了什么缺陷？
47. 你的 JWT 有撤销机制吗？用户改了密码后，旧 token 还能用吗？
48. 401 和 403 在你的项目里分别由谁触发？
49. `SecurityConfig` 用的是白名单还是黑名单？为什么这个选择重要？
50. CSRF 被禁用了，安全吗？为什么？
51. 你的 token 存在哪里？有什么风险？为什么这个风险在当前项目里是可接受的？
52. **[重点复习]** `JwtUtil.parseToken` 没有显式固定算法和 issuer。这是漏洞吗？为什么？（提示：`verifyWith(SecretKey)` 的行为）

### G. 数据库约束与 Flyway

53. 为什么 Service 已经检查了用户名唯一，数据库还需要 `UNIQUE` 约束？
54. 拿到 `23505` 唯一冲突后，你的项目返回什么状态码？应该返回什么？
55. 你的 `CHECK` 约束里写了金额公式（`net_amount = gross_amount + fee_amount + tax_amount`）。这不是把业务逻辑放到数据库里了吗？这样做好不好？
56. `uk_investment_transactions_posted_opening_asset` 是一个 partial unique index。为什么不能建普通唯一索引？
57. `investment_transactions` 上的 trigger 禁止 UPDATE/DELETE。如果有紧急数据修复需求怎么办？
58. V5 迁移为什么先 `RAISE EXCEPTION` 再 DDL？如果生产库里存在不满足新约束的历史数据，迁移会怎么表现？
59. V11 为什么要用 `pg_get_constraintdef()` 读取 V10 建立的约束定义？
60. V17 先 `DISABLE TRIGGER` 再 `UPDATE` 再 `ENABLE TRIGGER`。为什么需要这样？
61. 你的迁移脚本有没有修改过历史迁移？为什么不能改？
62. 你有哪些测试证明"空库能从 V1 迁到最新"？"历史 snapshot 能升级到最新"？
63. **[重点复习]** `application.yml` 里配了 `logic-delete-field: deleted`，但没有任何表有 `deleted` 列。这个配置有影响吗？会有什么隐患？

### H. REST、验证与错误处理

64. 你的项目为什么创建资源返回 200 而不是 201？
65. `PageResult` 的分页上限是多少？在哪里强制的？为什么不依赖客户端？
66. 同一个 `transactions` 表有两条写入路径（手工 CRUD 和 Import），它们的金额校验一致吗？
67. **[重点复习]** `POST /transactions` 有幂等键吗？用户双击保存会发生什么？
68. 投资命令和 Import 的幂等键是怎么工作的？"同一 key 不同请求体"会怎么处理？
69. 你的 `GlobalExceptionHandler` 把所有 `DataAccessException` 都映射成 503 吗？这样做有什么问题？
70. 为什么错误消息里不能包含 SQL、堆栈、幂等键？你的项目怎么保证？

### I. Docker、CI 与测试

71. 你的后端 Dockerfile 为什么要用非 root 用户？多阶段构建的好处是什么？
72. `docker compose` 里 `depends_on: condition: service_healthy` 和裸 `depends_on` 有什么区别？
73. 你的 readiness 探针检查了什么？为什么 `include: readinessState,db` 很重要？
74. `stop_grace_period: 30s` 和 `server.shutdown: graceful` 是怎么配合的？
75. nginx 默认 `client_max_body_size` 是多少？你的后端 Import 支持多大文件？这里有什么问题？
76. 你的 CI 有哪几个作业？它们分别验证什么？
77. `docker compose config` 能验证什么？不能验证什么？
78. 你的 54 个 PostgreSQL 集成测试类为什么要启动 54 个容器？怎么优化？
79. 为什么 PostgreSQL 相关的测试不能用 H2 代替？
80. **[重点复习]** 你的项目有 628 个 `@Test`。请挑出你认为**最有价值**的 3 个，并说明为什么它们的价值高于其他测试。哪些测试你认为可能是重复的？

### J. 综合设计判断

81. 如果现在要你实现 Transfer（账户 A → 账户 B），你会怎么设计？需要保证哪些性质？会复用哪些现有机制？
82. 如果要把系统从单库改成读写分离，你的余额逻辑会出什么问题？
83. 如果要加"余额不能为负"的规则，你会加在哪一层？为什么不能在 `applyDeltas` 之外加？
84. 如果并发压力上升 100 倍，你的行锁方案会成为瓶颈吗？你会怎么改？
85. 你觉得这个项目里**最脆弱**的一个假设是什么？

---

*本报告基于 `zh-cn` @ `6040748` 的实际代码、文档与测试生成。所有"未验证"的结论都已显式标注。*
