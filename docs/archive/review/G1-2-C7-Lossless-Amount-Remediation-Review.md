# G1-2 / C7 — Lossless Amount Remediation Review

日期：2026-10-10（Asia/Shanghai）。范围：C7 普通 Transaction 金额录入与编辑往返。

状态：**C7 — IMPLEMENTED / ACCEPTANCE PENDING**。技术验收建议 GO；等待人工 GO，不提交、不推送、不创建 Tag，不进入 C4。

## 1. Preflight

- Branch：`zh-cn`。
- HEAD：`cd66e2796f03f58fccaf859256318396b64b3e55`，本轮未改变。
- 初始 tracked diff / staged diff 均为空。
- 初始 untracked 仅 `AGENTS.md`、`docs/codex-task.md`。用户恢复授权明确确认它们为允许保留的基线，Preflight GO。
- 后续工作区变化均为本报告列出的 C7 修改，没有清理、覆盖或回滚用户文件。

## 2. 根因与旧行为复现

指定 HEAD 的 `Transactions.tsx` 虽然将 `form.amount` 保存为字符串，却在校验及 POST / PUT payload 中调用 `Number(form.amount)`。Axios 默认 JSON 读取也把响应金额转换成 Number；编辑通过 `String(transaction.amount)` 回填，无法恢复已丢失的精度。原列表 formatter 同样接收 number。

修改生产代码之前，Playwright 在 Chromium 的真实表单中填写 `100000000000000.01`，拦截该页面请求的 `postData()`。回归断言失败：预期精确字符串 `.01`，实际请求金额为数值 `100000000000000.02`。本轮 Node 旧表达式复现还确认 `99999999999999.99` 序列化为 `99999999999999.98`。

首次浏览器测试另遇到 fixture 定位超时：包裹 select 的 label 文本包含选项，`getByLabel(..., exact:true)` 未找到目标。只将测试 select 定位改为相应 combobox；随后取得上述金额缺陷复现，没有因此修改生产行为。

## 3. 完整金额链路

```text
浏览器 text/inputMode=decimal 输入
→ event.target.value / TransactionForm.amount:string
→ 精确文本校验，先拒绝超 scale / 超范围 / 非法符号
→ 纯字符串规范化为 scale 2，保持真实数值
→ payload.amount:string / 幂等意图签名
→ Axios JSON 序列化："amount":"100000000000000.01"
→ TransactionController @Valid @RequestBody TransactionRequest
→ Jackson BigDecimal 绑定 / DTO @NotNull
→ TransactionWriteRules 既有 scale、范围、符号与原因校验
→ TransactionService @Transactional / AccountBalanceService BigDecimal
→ PostgreSQL transactions.amount / accounts.balance NUMERIC(18,2)
→ API BigDecimal 仍输出 JSON number
→ 交易页面局部 lossless-json 解码为 amount:string
→ 精确列表展示 / 编辑回填 / 再次修改
```

余额规则仍为 INCOME `+amount`、EXPENSE `-amount`、ADJUSTMENT `amount`；更新反转旧 effect 后应用新 effect；删除反转当前 effect。金融规则、事务、行锁和 ownership 校验均由现有后端承担。

## 4. 最小设计及实施文件

生产行为只涉及 `frontend/src/pages/Transactions.tsx` 和新增 `frontend/src/utils/transactionAmount.ts`：输入、校验、传输、读回、回填和展示都保留精确十进制文本。复用已安装的 `lossless-json`，与 Import 的局部 lossless 读取模式一致，不重构 Import。

先按既有 BigDecimal scale 规则校验，再用字符串处理小数点、符号和补零，规范到两位。没有 `parseFloat`、金额 `Number` 转换、`toFixed`、截断或舍入。合法指数表示也保持原数值；`1.000` 按既有 scale > 2 规则拒绝，而不是延续旧 Number 路径绕过该规则的行为。等值的 `1`、`1.0`、`1.00` 保持同一规范化 payload 和重试幂等键；相邻大额分值产生不同意图。

`docs/architecture/api.md` 补充当前请求绑定兼容性。后端生产 DTO / OpenAPI 的 BigDecimal 类型和数值响应没有改变；真实 HTTP 验证同时接受 JSON number 与十进制 string。没有不兼容 API 改动、数据库 Schema / Flyway 改动或新增依赖。

## 5. 金额精度测试矩阵

当前合同：有效 BigDecimal scale ≤ 2，绝对值 ≤ `9999999999999999.99`；INCOME / EXPENSE > 0；ADJUSTMENT 非零且必须填写原因。

| 输入 | 合同判断 | 浏览器输入 / 请求 / 回填 | 真实 HTTP / BigDecimal / SQL / API |
| --- | --- | --- | --- |
| `0.01` | 合法 | 精确保留，通过 | 精确保留，通过 |
| `1.00` | 合法 | 精确保留，通过 | 精确保留，通过 |
| `1234.56` | 合法 | 精确保留，通过 | 精确保留，通过 |
| `99999999999999.99` | 合法 | 精确保留，通过 | 精确保留，通过 |
| `100000000000000.01` | 合法 | 精确保留，通过 | 精确保留，通过 |
| `100000000000000.02` | 合法 | 精确保留，通过 | 精确保留，通过 |
| `100000000000000.03` | 合法 | 精确保留，通过 | 精确保留，通过 |
| `9999999999999999.99` | 最大合法值 | 精确保留，通过 | 精确保留，通过 |

真实后端对整张矩阵分别覆盖 INCOME、EXPENSE、正 ADJUSTMENT、负 ADJUSTMENT。Node 另覆盖整数、一位小数、前导零、显式正号、`.01`、`1.` 和科学记数法；真实 HTTP 也验证相同合法语法的 BigDecimal 行为。

非法输入结果：

- 浏览器空值由 required 阻止；HTTP missing / null / 空字符串返回 400。
- `abc`、多个小数点拒绝；HTTP 返回 400。
- `1.001`、`1.000`、`1e-3` 按 scale 拒绝，无静默舍入。
- `10000000000000000.00`、`1e16` 越界拒绝。
- INCOME / EXPENSE 负值与零拒绝；ADJUSTMENT 零拒绝、负值允许；缺少调整原因仍提示原错误。
- 非法 POST / PUT 不改变已有事实与余额。

## 6. Create / Update / DELETE 结果

三种类型及正负调整均通过创建、改为矩阵中下一个金额、再次改回原金额和删除验证。每次成功写入后对 SQL 金额、SQL 余额、HTTP 详情、HTTP 分页与账户详情进行 BigDecimal 比较；删除后事实消失，余额恢复基线。

新增真实 HTTP 用例验证账户余额达到 NUMERIC 上限后的 create / update 溢出：返回 400，流水与余额同时回滚；原事实金额不变，随后 delete 正常恢复基线。既有余额并发与故障注入回滚测试也通过。

跨用户 GET / PUT / DELETE 返回 404；借用另一用户账户或分类的创建、改到账外用户账户的更新均拒绝，双方余额与原流水保持不变。

## 7. 真实 HTTP + PostgreSQL 验证

本轮采用**分层验证，不宣称完整联通浏览器 E2E**：

1. Playwright 使用真实 Chromium 输入、页面逻辑和 Axios 序列化，在 HTTP 请求拦截点记录原始请求体；mock 仅负责服务端应答。响应金额使用未经 Number 生成的原始 JSON numeric token，验证列表读回和编辑回填。
2. 新增 `TransactionAmountRuntimeIntegrationTest` 使用 Spring Boot RANDOM_PORT、TestRestTemplate 的真实 socket HTTP 和 PostgreSQL 17 Testcontainers。发送与浏览器相同的金额字符串，不经过测试端 double。
3. SpyBean 只观测既有规则方法，不替换计算：捕获实际 Controller 绑定后进入规则层的 BigDecimal。直接 JDBC 查询 NUMERIC，并通过真实 HTTP 详情、分页及账户接口读取；测试解析显式使用 `USE_BIG_DECIMAL_FOR_FLOATS`。

聚焦金额的实际日志证据：

```text
C7_BROWSER_EVIDENCE input=100000000000000.01
request.amount="100000000000000.01"
edit=100000000000000.01

C7_EVIDENCE real HTTP request.amount="100000000000000.01"
bound BigDecimal=100000000000000.01
PostgreSQL amount=100000000000000.01
PostgreSQL balance=100000000000000.01
HTTP response amount=100000000000000.01
```

完整原始请求体与运行结果保存在本地忽略目录：`frontend/node_modules/.cache/c7-browser-tests.log`、`backend/target/c7-runtime-evidence.log`。实际数据库版本为 PostgreSQL 17.11；真实 HTTP 及 SQL 验证没有使用 MockMvc 或 H2 替代。

## 8. Import 相邻回归

- Import 前端源码、Confirm 请求、Receipt decoder 和语义没有修改。
- 原有 Node lossless Receipt 大额断言通过。
- 原有 Import mock/browser 3 项通过，覆盖同用户恢复、用户切换、CSV 映射 / warning / Confirm / Receipt / reload。
- 该 mock 套件的部分未拦截后台请求产生 `ECONNREFUSED` 日志；断言通过，但不能据此宣称完整服务联通。
- Import Runtime 5 项通过，包含新增高精度 CSV `100000000000000.01` 的真实 HTTP Preview → Confirm → PostgreSQL 流水与余额 → Receipt GET 验证。

## 9. 前端与后端工程检查

以下均为本轮执行结果，不是历史证据：

| 检查 | 本轮结果 |
| --- | --- |
| Node 定向：transaction-amount / transaction-create-intent / transaction-import-confirm / transaction-import | 17 passed，0 failed / skipped |
| C7 Playwright | 5 passed |
| Import Playwright mock/browser | 3 passed |
| Maven 五类定向测试 | 27 run，0 failures / errors / skipped，BUILD SUCCESS |
| 聚焦 Runtime 证据复验 | 5 run，0 failures / errors / skipped，BUILD SUCCESS；不与上面重复计数 |
| 前端 lint | 通过 |
| 前端 tsc + Vite build | 通过；有 chunk > 500 kB 提示，未扩大到拆包优化 |
| 后端 compile | BUILD SUCCESS；增量编译显示现有 class 已 up to date |
| git diff --check | 通过；只有 Windows 行尾转换提示 |

后端首次尝试因进程缺少 `JAVA_HOME` 未进入 Maven 测试。后续仅为执行进程指定已有 `F:\ideajava\jdk21` 后通过，没有安装软件或修改系统环境。

复验命令（分别在 frontend / backend 执行）：

```powershell
node --test tests/transaction-amount.test.ts tests/transaction-create-intent.test.ts tests/transaction-import-confirm.test.ts tests/transaction-import.test.ts
npx playwright test --config playwright.transaction-amount.config.ts
npx playwright test --config playwright.transaction-import.config.ts --output node_modules/.cache/playwright-c7-import
npm run lint
npm run build

$env:JAVA_HOME = 'F:\ideajava\jdk21'
.\mvnw.cmd -B '-Dtest=TransactionAmountRuntimeIntegrationTest,TransactionImportConfirmRuntimeIntegrationTest,TransactionWriteRulesTest,TransactionBalanceConcurrencyPostgresIntegrationTest,TransactionApiIntegrationTest' test
.\mvnw.cmd -B '-Dtest=TransactionAmountRuntimeIntegrationTest' test
.\mvnw.cmd -B '-DskipTests' compile
```

未运行 Finance full suite；未调用真实付费 Provider/API。测试数据库由 Testcontainers 隔离创建，未连接日常 finance_os 数据库或修改历史流水。

## 10. 两个 Luna Max 独立复审

两个只读子 Agent 均通过 `gpt-6-luna` / `reasoning_effort=max` 参数成功创建；实际服务端模型路由无法独立核验。主控实际模型及思考档位也无法从当前工具核验，不将用户要求的 GPT-6.1 Sol High 冒充已验证配置。

Luna 1 负责金额链路和后端合同；独立检查当前实现、测试、Maven / Surefire 和最终金额证据。Luna 2 负责验收矩阵、真实 HTTP / PostgreSQL、隔离、回滚和 Import 回归；独立读取实际文件及日志。两者均未编辑仓库，未独立重跑测试；审查结论依据主控本轮实际产物，不作为额外测试执行次数。

最终结论：Luna 1 与 Luna 2 均给出当前 C7 范围的技术 GO，未发现 P0 / P1 / P2 阻塞项。Luna 1 独立核对 Node 17 项、C7 浏览器 5 项、Maven / Surefire、真实金额快照、compile 和前端 lint/build 日志；Luna 2 独立核对精度 / 事务 / 隔离 / Import 测试及后端证据，并补核最终 Node、lint、build 和浏览器日志。两者均明确这是分层验证，不能宣称浏览器直接联通真实后端；Import mock 背景请求警告也没有被隐去。

## 11. P0 / P1 / P2 Findings

- P0：本轮 C7 范围未发现。
- P1：本轮 C7 范围未发现。
- P2：原 C7 的 Number 精度损失已实施修复，等待人工验收；无剩余 C7 阻塞 finding。

边界：修复局限于交易管理页录入、列表展示、编辑往返；其他消费者继续使用原 DTO / transport。响应与 OpenAPI BigDecimal 数值类型保留，JSON string 请求的现有 Jackson 兼容能力经真实 HTTP 验证并写入 API 文档。没有扩展到全站金融值传输重构。

## 12. 受保护文件状态

`AGENTS.md`、`docs/codex-task.md` 保持 untracked。主控和两个子 Agent 未读取、修改、删除、暂存或提交它们；没有为内容 hash 校验而读取文件。最终检查只核对 Git 路径状态，不宣称取得文件内容 hash 对比。

## 13. 修改文件清单

共 9 个 C7 文件；两个受保护文件不计入修改清单。

1. `frontend/src/pages/Transactions.tsx`：普通流水金额字符串链。
2. `frontend/src/utils/transactionAmount.ts`：精确校验、规范化、解码和展示。
3. `frontend/tests/transaction-amount.test.ts`：精度与幂等 Node 回归。
4. `frontend/tests/e2e/transaction-amount.spec.ts`：真实浏览器输入 / 请求 / 回填、错误、三类型和幂等验证。
5. `frontend/playwright.transaction-amount.config.ts`：C7 定向浏览器配置，输出置于忽略目录。
6. `backend/src/test/java/com/financeos/integration/TransactionAmountRuntimeIntegrationTest.java`：真实 HTTP + BigDecimal + PostgreSQL 验收。
7. `backend/src/test/java/com/financeos/integration/TransactionImportConfirmRuntimeIntegrationTest.java`：新增相邻 Import 精度回归，保留旧测试。
8. `docs/architecture/api.md`：记录普通流水精确传输与绑定兼容性。
9. 本 Review：本轮可复验结果和范围说明。

未修改后端生产代码、数据库迁移、Import 生产代码、投资账本、Transfer、C1 / C5 实现或其他缺陷。staged diff 为空。

## 14. GO / NO-GO

**技术验收建议：GO，可提交人工验收。正式关闭：尚未授权。**

保持 **C7 — IMPLEMENTED / ACCEPTANCE PENDING**。未 commit、push 或创建 Tag；待用户人工 GO 后才可进行后续明确授权操作。报告完成后暂停，不进入 C4、C6、最终冻结或迁移。
