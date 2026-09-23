# Finance OS — Fix Verification

**基线：** `zh-cn` @ `6040748`（工作树 `zh-cn-6e2e8ee8`）
**日期：** 2026-09-23
**范围：** 仅 P1-1、P1-2、P1-3、P2-1、P2-8。其余 P2/P3 按约定未处理。
**审计依据：** [`FINANCE_TECH_AUDIT.md`](FINANCE_TECH_AUDIT.md)

---

## 1. 修改了什么 / 为什么

### P1-1 — 普通流水金额规则收敛到单一来源

**为什么改：** 同一个 `transactions` 表有两条写入路径，金额校验强度不同。Import 路径用 `TransactionWriteRules` 严格卡 scale / 精度 / 范围；手工 CRUD 路径只校验符号。后果有两个：

1. `POST /transactions` 携带 `1.005` 时，`transactions.amount` 与 `accounts.balance` 都是 `NUMERIC(18,2)`，PostgreSQL 会**静默四舍五入**，而接口响应回显的是内存中的原值 —— 响应值与存储值不一致。
2. 余额写回时 `round(balance + delta)` 与 `round(delta)` 在特定小数组合下方向不同，会让"余额 = Σ effect(流水)"这个不变式漂移最多 1 分。
3. 超出 `NUMERIC(18,2)` 范围的金额（如 `1e20`）会以 PostgreSQL `22003` 失败，落到通用分支返回 503。

**怎么改：** 把金额规则抽成 `TransactionWriteRules.validateAmount(type, amount, description)`，作为**唯一来源**；`TransactionWriteRules.validate(...)`（Import 路径）与 `TransactionService`（手工 CRUD 路径）都调用它。`TransactionService` 原先的私有 `validateAmountAndDescription` 被删除，不再存在第二套规则。

scale 判定从 `amount.scale() != 2` 放宽为 `amount.scale() > MONEY_SCALE`。这是**必要**的：HTTP 请求的 `amount` 由 JSON 反序列化，`50` 的 scale 是 0、`50.0` 是 1，用 `!= 2` 会误拒既有合法请求（前端正是发送 `Number(form.amount)`）。Import 路径的金额在进入 `validate` 前已被 `normalizeAmount()` 规范到 scale 2，因此放宽后行为不变。

### P1-2 — `POST /transactions` 的重复提交保护

**为什么改：** 投资命令与 Import Confirm 都强制 `Idempotency-Key`，而用户最常使用的 `POST /api/v1/transactions` 完全没有重复保护，`transactions` 表也没有任何防重复约束。前端提交按钮没有 in-flight 状态，双击「保存」会真的产生两条流水，并各自正确应用一次余额 delta（余额自洽，但多出一笔本应只有一笔的财务事实）。

**怎么改（前端）：**
- 增加 `submitting` 状态；请求进行中禁用提交按钮与取消按钮，按钮文案变为「保存中…」；`finally` 恢复状态。
- 增加 `submit` 重入守卫。
- 为 CREATE 附带 `Idempotency-Key`。key 的分配规则抽成纯函数 `utils/transactionCreateIntent.ts`：key 与**请求内容签名绑定** —— 内容不变时复用同一个 key（双击/重试会重放权威结果），内容被编辑后换新 key（避免用户改错字重提时被误判为「同 key 不同请求」而 409），两次**有意**的相同记账各自使用新 key（两笔真实相同消费是合法事实）。签名对字段顺序不敏感，避免将来调整 payload 构造顺序时静默破坏重放。
- `crypto.randomUUID()` 只在安全上下文可用，因此加了非安全上下文的回退实现。

**怎么改（后端）：** 新增 V18 迁移（`idempotency_key VARCHAR(100)` 可空 + `request_hash CHAR(64)` 可空 + `(user_id, idempotency_key)` **partial** unique index + 三条 CHECK）；`TransactionService.create` 增加幂等键参数，命中同 key 同 hash 时直接返回首次结果，同 key 不同 hash 时返回 409；新增 `TransactionCommandService`（非事务门面）在唯一索引竞争失败后重放权威结果，复用项目已有的 `InvestmentCommandService` 模式。

**关键约束（按约定执行）：**
- `idempotency_key` 可空 → 既有调用方、Import 行、不传 header 的客户端行为完全不变。
- **没有**基于金额 / 时间 / 账户建任何"防重复唯一约束"——两笔完全相同的真实消费是合法的。
- 只保护 CREATE，`PUT /transactions/{id}` 未改动。

### P1-3 — 补齐 P1-1 / P1-2 的必要回归测试

按约定只补这两项对应的测试，未做测试数量扩张。

### P2-1 — 修正数据库约束异常的 HTTP 语义

**为什么改：** `DataAccessException` 只识别锁冲突，其余一律 503「数据库服务暂时不可用」。这会让"用户名重复"被报告成"数据库挂了"，并诱导客户端重试一个永远不会成功的请求。

**怎么改：** 按 SQLSTATE 分级 —— `23505 → 409`、`22003 → 400`、其余保持原行为。

**⚠️ 实施时发现原审计建议有错并已纠正：** 原建议是「`23514 → 400`」。实施后 `InvestmentDividendFailureIntegrationTest` 立即失败，暴露出这个映射是错的 —— 本项目刻意把 CHECK 约束当作**服务端不变式**的最后防线，其自身 trigger 用 `ERRCODE = '23514'` 表示内部一致性失败；同批测试还要求未知的 `23505` 必须 fail-closed 返回 500。把 23514 一刀切成 400 会把内部缺陷伪装成客户端错误。**最终 23514 → 500（fail-closed，脱敏文案「数据一致性校验失败」）**，并在测试中把这条语义固定下来。

### P2-8 — nginx 与后端 Import 文件大小限制对齐

**为什么改：** 后端 Import 明确支持 5 MiB（`ck_transaction_import_sessions_file_size` 有 CHECK，也有 413 处理与测试），但 nginx 默认 `client_max_body_size` 是 1m。1–5 MiB 的上传会在 nginx 被拒，**根本到不了后端**，后端为此写的结构化 413 错误在生产路径上永远不会触发。本地开发用 Vite 直连后端，不会暴露这个问题。

**怎么改：** `location /api/` 增加 `client_max_body_size 6m;`（略高于 5 MiB），让最终大小校验由后端执行并返回统一 API 错误。

---

## 2. 修改涉及的文件

### 新增（6）

| 文件 | 用途 |
| --- | --- |
| `backend/src/main/resources/db/migration/V18__add_transaction_create_idempotency.sql` | 幂等键列 + partial unique index + CHECK。**未修改 V1–V17** |
| `backend/src/main/java/com/financeos/module/ledger/service/TransactionCommandService.java` | 非事务门面：唯一索引竞争失败后重放权威结果 |
| `backend/src/test/java/com/financeos/module/ledger/service/TransactionWriteRulesTest.java` | 共用金额规则的契约测试 |
| `backend/src/test/java/com/financeos/module/ledger/service/TransactionCreateIdempotencyPostgresIntegrationTest.java` | 幂等与并发重放的真实 PostgreSQL 测试 |
| `frontend/src/utils/transactionCreateIntent.ts` | 「一个 key 对应一个用户意图」的纯函数（可测） |
| `frontend/tests/transaction-create-intent.test.ts` | 上述纯函数的回归测试 |

### 修改（17）

**生产代码（6）**

| 文件 | 改动 |
| --- | --- |
| `module/ledger/service/TransactionWriteRules.java` | 抽出 `validateAmount()` 作为金额规则唯一来源；scale 判定放宽为 `> 2` |
| `module/ledger/service/TransactionService.java` | 注入 `TransactionWriteRules`，删除私有金额校验；`create` 增加幂等键处理、请求哈希与重放 |
| `module/ledger/controller/TransactionController.java` | `POST` 接受可选 `Idempotency-Key` header，改由 `TransactionCommandService` 处理创建 |
| `module/ledger/entity/Transaction.java` | 新增 `idempotencyKey` / `requestHash` 字段 |
| `module/ledger/mapper/TransactionMapper.java` | 新增 `findByUserIdAndIdempotencyKey`；`selectOwnedForUpdate` 补齐新列 |
| `common/GlobalExceptionHandler.java` | 按 SQLSTATE 分级映射（23505/23514/22003） |

**前端（2）**

| 文件 | 改动 |
| --- | --- |
| `frontend/src/pages/Transactions.tsx` | `submitting` 在途状态 + 重入守卫 + 调用 `nextTransactionCreateIntent` 分配 `Idempotency-Key` |
| `frontend/nginx/default.conf.template` | `client_max_body_size 6m` |

**测试（9）** — 均为本次改动直接导致或必须同步的修改

| 文件 | 改动原因 |
| --- | --- |
| `common/GlobalExceptionHandlerTest.java` | 新增 SQLSTATE 映射用例；固定 23514 fail-closed 语义 |
| `module/ledger/service/TransactionServiceTest.java` | 构造器新增参数；新增 3 个 scale / 范围拒绝用例 |
| `module/ledger/controller/TransactionControllerWebMvcTest.java` | 控制器依赖变更；新增 Idempotency-Key 透传用例 |
| `module/ledger/service/TransactionBalanceConcurrencyPostgresIntegrationTest.java` | `create` 签名新增幂等键参数（传 `null`，行为不变） |
| `integration/TransactionImportConfirmIntegrationTest.java` | 同上（2 处调用点） |
| `integration/FlywayVersionNineUpgradeIntegrationTest.java` | 断言"迁移到最新版本"由 `"17"` 更新为 `"18"` |
| `integration/FlywayVersionTenUpgradeIntegrationTest.java` | 同上 |
| `integration/FlywayTransactionImportVersionSixteenUpgradeIntegrationTest.java` | 同上 |
| `integration/FlywayTransactionImportVersionSeventeenUpgradeIntegrationTest.java` | 同上 |

> 后 4 项是新增 V18 的必然结果：这些测试断言"schema 已升级到最新版本"，硬编码了 `"17"`。项目在引入 V17 时做过同样的更新。

**文档（2）** — `FINANCE_TECH_AUDIT.md`（更新修复状态与真实测试数据）、`FINANCE_FIX_VERIFICATION.md`（本文件）

**改动规模：** 23 个文件，+280 / −41 行（后端与前端代码，不含本文件与审计报告）。

---

## 3. 新增与补充的测试

净增 **25** 个后端测试（644 → 669）与 **7** 个前端测试（74 → 81）：

| 测试类 | 基线 | 修复后 | 增量 | 覆盖内容 |
| --- | --- | --- | --- | --- |
| `TransactionWriteRulesTest` | — | 7 | **+7** | scale 0/1/2 保持合法；`1.005` 拒绝；超 `NUMERIC(18,2)` 拒绝；边界值 `9999999999999999.99` 接受；null / 非法类型拒绝；INCOME/EXPENSE 必须为正；ADJUSTMENT 需非零且带原因 |
| `TransactionCreateIdempotencyPostgresIntegrationTest` | — | 6 | **+6** | 同 key 重放不产生第二条流水；余额只应用一次（连提 3 次）；同 key 不同内容返回 409 且余额不变；**并发同 key 只产生一条事实、余额只应用一次、两次返回同一 id**；无 key 时仍创建独立事实；空白/带空格 key 返回 400 |
| `GlobalExceptionHandlerTest` | 11 | 19 | **+8** | 23505→409、23514→500、22003→400、55P03/40P01→409、未知状态→503；23514 fail-closed 语义固定；响应脱敏 |
| `TransactionServiceTest` | 5 | 8 | **+3** | create/update 在触碰余额前拒绝 3 位小数；拒绝超范围金额 |
| `TransactionControllerWebMvcTest` | 9 | 10 | **+1** | `Idempotency-Key` header 正确透传到命令服务 |
| `transaction-create-intent.test.ts`（前端） | — | 7 | **+7** | 首次提交分配新 key；**内容不变复用 key（重试重放）**；内容变更换新 key（不被误判为同 key 不同请求）；改后再改回不复活旧 key；**payload 字段顺序不影响签名**；null 与缺省签名一致；生成的 key 非空且互不相同 |

新增测试全部为**行为测试**（断言可观察结果），未添加任何仅为覆盖率的测试，未引入新依赖。

---

## 4. 最终测试结果

### 4.1 后端（Maven `-B clean test`，Docker 可用，含全部 Testcontainers）

| 轮次 | Test classes | Tests | Failures | Errors | Skipped | 耗时 | 结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **修改前基线**（工作树干净，改动已临时还原） | 122 | **644** | **0** | **0** | 0 | 12:40 | `BUILD SUCCESS` |
| **修改后回归** | 124 | **669** | **0** | **0** | 0 | 11:51 | `BUILD SUCCESS` |

基线取得方式：先把全部改动存为补丁、`git checkout` 还原到 `6040748` 原始状态，确认测试源文件数回到 123，再运行完整套件；随后 `git apply` 恢复改动。（第一次尝试因在编译开始后新增了测试文件而污染，已作废并重跑。）

### 4.2 前端（Node 22.22.2）

```
npm test      ->  tests 81 / pass 81 / fail 0
npm run lint  ->  Found 0 warnings and 0 errors (81 files, 103 rules)
npm run build ->  ✓ built（index.js 1,009 kB / gzip 327 kB）
```

### 4.3 Playwright E2E（Chromium，全部实际执行）

| 套件 | 依赖 | 结果 |
| --- | --- | --- |
| `test:e2e:transaction-import`（mock 浏览器契约） | 仅 Vite | **57 passed** |
| `test:e2e:investment-command`（真实后端） | 后端 + 隔离 PostgreSQL | **27 passed** |
| `test:e2e:transaction-import:real`（真实后端） | 后端 + 隔离 PostgreSQL | **1 passed** |

E2E 使用独立容器化 PostgreSQL（宿主端口 5433，避开本机既有 PostgreSQL 服务）与每次运行随机生成的密钥，**未触碰用户本机的数据库服务**；运行结束后容器已销毁，端口已释放。

### 4.4 修复过程中出现的回归及处理（如实记录）

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| 4 个 Flyway 升级测试失败（`expected "17" but was "18"`） | 断言硬编码"最新 schema 版本" | 更新为 `"18"`（与项目引入 V17 时的做法一致） |
| `InvestmentDividendFailureIntegrationTest` 1 个用例失败（期望 ≥500，实际 400） | **我 P2-1 的原始映射（23514→400）设计错误** | 改为 23514→500 fail-closed；并把该语义写成显式测试 |
| 前端 `npm run build` 报 `Cannot find name 'useRef'` | 遗漏 React 具名导入 | 补上 `useRef` |

三项均已在最终回归中消除。

---

## 5. 是否还有未解决的 P0 / P1

**P0：0 项。**
**P1：0 项。** P1-1、P1-2、P1-3 全部修复并回归通过。

**P2-1 修复过程中新发现的残留缺口（P2 级，未修）：**
`AccountRequest.type` / `AssetRequest.type` 传入非法枚举值时会命中 `23514`，现在返回 500 而不是 400。正确做法是在 Service 层做枚举校验返回 400，而不是靠 SQLSTATE 猜责任方。因超出本轮约定范围（P2-1 被限定为"修正异常 HTTP 语义"）未处理。

**其余未处理项**（按约定保留）：P2-2 ~ P2-7、P2-9、P2-10，以及全部 P3。其中建议下一轮优先处理：

1. **P2-4 Testcontainers 容器复用** —— 124 个测试类中 54 个各自启动一个 PostgreSQL 容器，本地全量回归 12 分钟、CI 作业超时设为 20 分钟。这是"集成测试能否持续被运行"的前提，也是本报告第 21 节唯一未完成的推荐项。
2. 上述 P2-1 残留缺口（Service 层枚举校验）。
3. P2-9（CI 未验证 Compose 实际起栈）。

---

## 6. 是否建议当前版本冻结作为求职展示版本

**建议：可以冻结，但需带一条明确的边界说明。**

### 支持冻结的理由

- **0 个 P0、0 个 P1。** 本次全检发现的两个最高优先级问题（金额校验缺口、无重复提交保护）已修复，且都有针对性回归测试保护。
- **验证是真实且完整的。** 644 → 669 的后端测试全部在真实 PostgreSQL 上跑过（含并发、锁顺序、死锁、失败注入、unknown commit、Flyway 全量迁移与历史升级路径）；前端 74/74；E2E 85/85。这不是"看起来绿色"，而是全部实际执行。
- **修复方式与项目既有设计一致。** 金额规则收敛到既有 `TransactionWriteRules`（未造第三套）；幂等复用了既有 `InvestmentCommandService` 的模式与 `Idempotency-Key` 约定；新迁移只新增、不改历史；唯一索引用 partial 以避免影响既有行为。
- **对既有契约零破坏。** 幂等键 header 可选，不传时行为与修复前完全一致；V18 列全部可空；未改动 `PUT`。

### 必须带上的边界说明

冻结时应当明确（否则面试时会被动）：

1. **这不是转账系统，也没有实现转账。** `TRANSFER` / `REFUND` 在 `transactions.type` 的 CHECK 约束里，但 Service 显式 400 拒绝，`docs/STATUS.md` 将其列为未实现范围。
2. **余额允许为负。** 这是记账系统的有意设计（`ADR-008`、`docs/domain/business-rules.md` §2.3 明确"当前没有 insufficient-balance 拒绝规则"），不是缺陷。
3. **普通流水 CRUD 的严谨度低于投资模块。** 投资侧有 append-only 事实 + 确定性重放 + 完整 receipt；流水侧本次只补齐了金额校验与创建幂等，`PUT` 仍无幂等键（按约定未做）。
4. **仍有 1 项 P2 残留**（非法枚举值返回 500）与 P2-4 的测试基础设施债。

### 一句话

**可以冻结为求职展示版本。** 它现在的状态是"核心资金性质有机制保障、关键缺口已修复、全部结论有实测支撑"；剩下的都是可以坦然承认并说清理由的边界与债务，而不是会被问倒的隐患。

---

*本文件记录本轮受控修复的全部改动与验证结果。详细审计结论、问题分级与 85 个面试问题见 [`FINANCE_TECH_AUDIT.md`](FINANCE_TECH_AUDIT.md)。*
