# G1-4 / C6 — AI Analyst 跨用户状态隔离 Review

日期：2026-10-11（Asia/Shanghai）。

## 正式人工验收与提交授权

人工已审阅下方最终兼容安全补修版本，正式批准 **C6 — ACCEPTANCE GO**，并授权将首次实施与 Fail-Closed 补修的累计 10 个文件精确提交、推送至 `origin/zh-cn`。以下技术验收、候选状态及暂停叙述保留为各自阶段的历史记录。

本次提交 Parent HEAD 为 `bf163569fbb4cd058e6548aad35173c3467d78a0`。提交前独立 `git ls-remote --heads origin refs/heads/zh-cn` 返回相同 SHA；分支、空暂存区、批准文件范围均已核对。当前源码/测试与连续实施记录一致，修改时间早于对应最终验收日志；本轮只新增本段正式验收记录，没有变更生产代码、测试或 config，也没有机械重复 58/3/45 回归。对批准文件记录 Git blob 和内容身份，并在暂存及提交树继续核对；没有读取或计算两项受保护文件的内容 hash。

提交范围为：`authSession.ts`、`Login.tsx`、`Layout.tsx`、`AiAnalyst.tsx`、`api/ai.ts`、`api/index.ts`、`auth-session.test.ts`、`ai-state-isolation.spec.ts`、`playwright.ai.config.ts` 和本 Review；完整仓库路径见下方累计实施清单。截图、日志及 `test-results` 仅在本地保留，未纳入提交；因此 Review 的本地证据链接不代表远端包含这些 ignored 产物。

只有实际提交、推送及独立线上 SHA 核验全部成功后，最终输出的《C6 Formal Closing Report》才标记 **C6 — CLOSED — GO**，并记录 New HEAD 和提交身份。本段不预先宣称推送成功，也不通过 amend 将提交自身 SHA 写回本文件。

按本次人工决定，C1/C4/C5/C7 保持 CLOSED — GO，G1 的四个修复项在 C6 正式关闭后全部关闭。Finance 整体仍为 Final Stabilization，不是最终 Freeze；**C4 遗留 cause-only 循环遍历问题仍 OPEN，最终 Freeze 前需独立处置**，本次不实施其他修复。

兼容边界继续保留：无 Web Locks 时 AI Fail-Closed；Firefox/WebKit 未实测；真实 Provider、JWT/数据库端到端未执行；Import mock 的既有 `ECONNREFUSED` 不代表真实后端联通成功。任意 Axios 覆盖配置和其他未验证边界见补修 7。本次 C6 是前端会话隔离修复，不作为后端越权漏洞已被证明或修复的结论。

主控按指定 GPT-6.1 Sol High 工作；此前两位 Luna Max 只读复审结果保留，均非独立实跑。本次未新增子 Agent，主控及子 Agent 的实际服务端模型路由无法另行核验。不修改 Workspace/Local AI Assistant 或其他 C 项，不创建 Freeze Tag，不自动进入 G2。

## 最终兼容安全补修：结论与授权范围

**C6 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。等待人工 GO 和正式提交；未标记 CLOSED — GO。**

本节是最新验收记录。下方“历史：首次实施”中的 28/3/39、无锁 P2 和候选范围是上一阶段的原始结论，完整保留；更下方 Design Checkpoint 的四项红灯也保留。本轮以 Fail-Closed 处置剩余无锁 P2：无法获取认证互斥时停止 AI 操作，不再执行无锁 AI 认证处理。没有新增跨标签锁算法或重构全站认证。

执行前核对完整实现、历史第 5 节及无锁分支，分支 `zh-cn`，HEAD `bf163569fbb4cd058e6548aad35173c3467d78a0`。既有未提交 C6 修改、报告和证据均保留；没有无关工作区修改。主控按指定 GPT-6.1 Sol High 执行，两位子 Agent 配置见下文；实际服务端模型路由未另行核验。

## 补修 1. 绕过路径核对与实际设计

旧 `withAuthSessionMutation` 在没有 `navigator.locks` 时直接调用 operation；旧 AI 请求 guard 还依赖显式 `aiSession` 和局部 URL，直接共享 client 调用可以遗漏选择启用字段。这两条路径均可能避开 AI 认证互斥。本轮将安全边界放在共享 AI 请求/响应拦截器，而不是仅在页面提前判断浏览器能力。

helper 增加默认 `false` 的 `requireLock` 参数。AI 发送与 AI 401 清理均传 `true`：API 缺失、`request` 非函数、读取 API 抛错、锁请求同步抛错或异步拒绝，均抛 `AiAuthSafetyError`，code 为 `AI_AUTH_SAFETY_UNAVAILABLE`，不执行 operation。锁 Promise 未进入回调即完成时也安全拒绝。锁内 operation 原有错误（例如过期会话取消）原样传递，没有重试无锁分支。继续使用现有原生独占锁 `finance-os:auth-session-mutation:v1`。

共享 client 使用 `api.getUri(config)` 的最终组合路径识别 `/api/v1/ai/ask`，涵盖现有 `/ai/ask`、相对路径、绝对 URL、带 query 和等价 baseURL；不再靠调用方选择启用 guard。未显式传快照的直接 AI 调用也要读取当前快照并获取强制锁，显式失效快照仍取消。取得锁后核对 `{ id, token, userId }`，从已核对快照设置 Bearer 并记录 `aiSentSession`。本地 nonce 的生成、通知和非凭据属性保持首次实施设计。

AI 401 的当前身份和 Authorization 判断、恢复回调、恢复记录写入、清 token/userId/nonce、跳转仍在同一个锁回调内；若取锁失败，整个回调不执行，原 Axios 401 作为安全错误的 cause 保留并以 rejected Promise 结束。无锁时连当前会话 AI 401 也不冒险清认证；支持锁时真正当前会话 401 继续既有失效和 Login 跳转语义。

非 AI 请求、Login、Logout 调用默认兼容模式；缺少 API/方法时仍执行原操作，原生锁请求失败仍保留原失败语义。本轮未更改其恢复回调顺序、`skipGlobal401Redirect`、JWT 或待恢复记录合同。没有清空 localStorage。

## 补修 2. 页面与异步有效性

页面针对安全错误显示固定提示：“当前环境无法安全保护 AI 会话，本次分析已停止。请使用新版浏览器或稍后重试。”锁内部异常和旧后端错误不会替换该提示。只对仍有效的当前页面请求显示提示。

首次实施的会话订阅、同标签通知、storage/focus/visibility 校验、卸载资格及请求对象锁保持不变：切换身份先清 question/lastSuccess/error/status 和旧请求资格；迟到 success/catch/finally 仍先核对会话和请求归属。旧响应不能污染新状态、释放新请求锁或清新认证。同一有效会话保留上一次成功回答；安全拒绝会释放自己的页面请求锁，恢复真实 Web Locks 后可以正常重试。没有持久化 AI 历史。

## 补修 3. 本轮增量文件

| 文件 | 本轮兼容补修增量 |
| --- | --- |
| `frontend/src/utils/authSession.ts` | 强制锁模式、安全错误类型；默认非 AI 兼容行为保留 |
| `frontend/src/api/index.ts` | 按实际 URI 识别 AI，发送和 401 强制获取锁，取锁失败不触发副作用 |
| `frontend/src/pages/AiAnalyst.tsx` | 当前请求安全兼容提示 |
| `frontend/tests/auth-session.test.ts` | 增加 6 项锁失败/兼容语义测试 |
| `frontend/tests/e2e/ai-state-isolation.spec.ts` | 原测试保留，新增 30 项浏览器兼容安全/非 AI 回归 |
| 本报告 | 新验收置顶，历史证据和结论完整保留 |

本轮未再改 `Login.tsx`、`Layout.tsx`、`api/ai.ts` 或 AI Playwright config；其工作区 diff 来自首次实施。原 AI UX、Investment/Import Node 和 Import 浏览器测试没有修改。无新增依赖、后端、数据库、JWT 合同、其他 C 项或个人 AI 项目修改。

## 补修 4. 真实双标签页验证

实跑 headless **Chromium 151.0.7922.34**，每个双标签用例均在同一 BrowserContext 内 `context.newPage()`，共享真实 localStorage。Login/Logout、React 生命周期、实际 Axios 拦截器全部运行；端点/Provider 使用虚构 fixture。故障直接注入浏览器的 `navigator.locks` 或原生 LockManager 的 `request`，并断言 API 的实际形态；没有 mock 应用 helper 返回 false。

五种故障：`locks` 不存在、`request` 不存在、`request` Promise 拒绝、`request` 同步抛错、读取 `locks` getter 抛错。每种各执行五个用例，共 25 项：

- 新 AI 提交不发送 HTTP，当前 token/userId/nonce 和 Investment pending 原样保留，提示可见；恢复原生 API 后同会话重试成功。
- AI 已实际发送后失去锁，再返回当前会话 401：两个恢复回调计数均为 0、不清认证、不跳转，显示安全提示。
- A pending 的 200、503、401 分别在另一标签真实登录 B 后释放：B 认证三字段完全不变，A 的回答和错误均不复活。

第三类用例还在释放旧 A 响应前恢复 A 页 API并发出 B 新请求，再使 A 页 API 失效。旧响应完成时 B 的 question/loading/请求锁保持，重复表单提交不增加网络请求，随后 B 已发出的成功请求正常完成；继续提交则安全拒绝。等待真实 Axios 完成观察器及两帧 React 渲染后才作负断言。

另有 2 项同用户重登录/快速 A→B→A：A 两次获得相同 token 和 userId、nonce 不同；两个标签均缺少 locks，另一标签通过 addInitScript 在重新导航后继续缺锁。旧 401 无副作用，新 A 的 AI 操作安全拒绝。原 28 项中的同用户/ABA 旧 200/503/401 与新会话正常成功同时重新通过。

其余新增 3 项：1 项直接共享 Axios 的五种等价 AI URL 均被无锁拦截且实际发送数为 0；2 项缺 locks/缺 request 的非 AI 401 从真实 Accounts 页面清认证并跳 Login，保留 Investment/Import 证据，随后真实 Login 成功且只有 AI 操作拒绝。五种故障的跨用户用例中，A 页故障而 B 页保持原生 API；仅同用户/ABA 缺锁用例将两页都置为无锁。没有宣称所有组合均实测。

安全提示截图：[无 locks 安全拒绝](../../../frontend/test-results/c6-compat-final-fixed/ai-state-isolation-C6-comp-a2483-s-auth-and-recovery-records/missing-safe-refusal.png)。认证和请求锁结论依据测试断言，截图只证明 UI。

## 补修 5. 历史红灯、本轮失败及绿灯

最早四项双标签红灯和首次实施 **28 Chromium / 3 Import / 39 Node** 均为历史证据，仍保留在下方，不作为本轮结果冒充。

本轮生产补修前实际运行两项新增测试，**2 failed**：[兼容补修前日志](../../../frontend/test-results/c6-compat-before-run.log)。缺 locks 时没有安全提示且请求得到 mock 回答；直接 Axios 前两种调用显示 `sent`。第三种带 query 的 URL 当时未被 fixture 拦截，得到 `ERR_BAD_RESPONSE`/代理连接拒绝，这项环境错误不冒充新的安全漏洞证据。随后拓展 route 和等价 URL 断言。

补修后中间完整 **51/51** 绿灯保留于 [中间日志](../../../frontend/test-results/c6-compat-after-1-run.log)。加入 getter 和非 AI 回归后首次 58 项运行 **56 passed / 2 failed**：[首次扩展运行](../../../frontend/test-results/c6-compat-final-run.log)。两项失败是新 fixture 错拦 `/accounts`，真实 Accounts GET 为 `/accounts/page`，导致没有收到预期 401。核对真实调用链后仅修 fixture 路径，原清认证/跳转/记录断言不变；[定向重跑 2/2](../../../frontend/test-results/c6-compat-fixture-fixed-run.log)，再完整重跑 **58/58**。未删除、跳过或弱化安全断言。

## 补修 6. 本轮新执行的回归与工程检查

以下测试均由主控运行，生产代码为本轮最终版本。Node/lint/build/Import 之后只添加浏览器测试及修正新 fixture，未再变更生产代码。

| 命令（除 Git 外在 `frontend`） | 本轮实际结果 / 证据 |
| --- | --- |
| `npx playwright test --config playwright.ai.config.ts --output test-results/c6-compat-final-fixed` | **58 passed / 0 failed / 0 skipped，2.0m**；[最终完整日志](../../../frontend/test-results/c6-compat-final-fixed-run.log) |
| `npx playwright test --config playwright.transaction-import.config.ts --output test-results/c6-compat-import` | **3 passed，10.1s**；[Import 日志](../../../frontend/test-results/c6-compat-import-run.log) |
| `npm test` | **45 passed / 0 failed / 0 skipped**（原 39 + 新 6）；[Node 日志](../../../frontend/test-results/c6-compat-node-1-run.log) |
| `npm run lint` | exit 0；[lint 日志](../../../frontend/test-results/c6-compat-lint-1-run.log) |
| `npm run build` | exit 0，tsc + Vite 成功；既有大 chunk 提示仍在；[build 日志](../../../frontend/test-results/c6-compat-build-1-run.log) |
| `git diff --check` | 通过；Git 有 LF/CRLF 提示，无 whitespace error；未追踪新增源码/测试/报告另查行尾空白 |

原支持 Web Locks 的 **28/28** 全部重新通过，原 AI UX **9/9** 文件/断言未改，含上次成功保留、真正 401 清认证/跳转。相关 Investment 浏览器两项继续验证原 intent/body/idempotency key、AUTH_REQUIRED、同用户恢复一次、换用户隔离和注销确认；Import pending 阻止退出也继续通过。全部 Investment Command/Import Node 身份与恢复测试随 45 项实际运行。

原 Import 三项继续覆盖同用户 401 GET-first/原 POST 恢复、换用户拒绝恢复、CSV warning/Receipt/reload。既有 user-switch fixture 的未 mock accounts/categories/rows 仍有 `ECONNREFUSED`，断言全部通过；不声称真实后端读取成功。没有修改该旧 fixture 来隐藏日志。

## 补修 7. P0 / P1 / P2 与未验证范围

本轮授权范围未确认剩余 P0/P1 或可达的无锁 AI 认证竞态。剩余无锁 P2 已以“停止 AI 发送及认证副作用”处理；浏览器不支持时 AI 不可用是已批准的安全兼容行为。正常支持锁路径仍沿用原有 nonce/锁内身份判断，真实当前会话 401 回归通过。

未验证 Firefox/WebKit、操作系统窗口焦点、后台冻结/崩溃、存储不可用、真实 Provider、真实 JWT/数据库、Maven/Finance 全量、Investment 后端或 Import real；数据库写入不在本轮授权范围。非 AI 默认兼容行为保持，但本轮不提供全站无锁写入原子性的新合同。非 AI 401/Login/Logout 在锁 getter 抛错、request 同步抛错或异步拒绝时的浏览器流程未实跑；helper 的默认模式保留原失败语义，Node 已核对 native rejection 原样传递，未承诺这些故障下登录/退出仍成功。

安全复审另检查了任意直接 Axios 配置：可信调用方若自行设置 `auth`、自定义 `transformRequest` 或 adapter，可能在拦截之后改变实际请求身份；本轮没有验证或扩展这一合同。主控和安全 Agent 核对实际业务调用，只有 `AiAnalyst → askFinanceAi → api.post('/ai/ask', { question }, { aiSession })`，没有这些覆盖字段。它们仍先经过强制锁，无锁不能到达 adapter；未认定为本轮业务路径可达的无锁绕过。未来若增加此类 AI 调用，需要重新核对 wire 身份。绕过 helper/锁直接改认证 key 的外部脚本也不属于现有认证提交合同。

## 补修 8. 独立只读复审

本轮两个独立上下文子 Agent 均明确请求 `gpt-6-luna`、`max`：`c6_compat_security` 安全审查、`c6_compat_tests` 测试审查。仅只读源码和日志，无文件修改，也**没有独立实跑测试**。测试数字是主控实跑结果。

安全 Agent 最终核对源码与 58/58 日志，未发现本轮批准 AI 无锁 fail-closed 范围内确认的 P0/P1/P2 阻断；确认强制锁拒绝、旧 401 副作用资格、真实业务调用快照 Bearer 绑定，以及 Login/Layout/非 AI 默认兼容模式。提出任意 Axios `auth`/变换/adapter 的静态边界；主控核对实际调用和 Axios 实现后，按补修 7 明确未验证扩展合同，没有扩大授权修改。

测试 Agent 最终确认 58/58、原 28 项保留且未弱化、新 API getter 故障与非 AI fixture 修正有效，复核 Import 3/3、Node 45/45、lint/build 日志。指出非 AI 浏览器只测 missing/no-request，其他锁故障不应外推为登录/退出成功；主控核对旧 helper 和当前默认语义后，保留原失败合同并明确记录限制。两页同时无锁、A 页单独失锁及其他浏览器的覆盖边界均按其意见写明。没有将只读日志核对称为独立实跑。

## 补修 9. 文件保护与人工验收

受保护 `AGENTS.md`、`docs/codex-task.md` 仍为未追踪基线；没有读取内容、计算内容 hash、修改、删除、暂存或提交。工作区所有既有 C6 修改保留；历史红灯/绿灯、本轮补修前红灯、中间绿灯及 fixture 失败日志均保留，新最终结果使用独立 `c6-compat-final-fixed/`。没有清理工作区或覆盖历史证据。

没有 commit、push、branch/tag、G2、其他 C 项、后端/数据库/JWT 或个人 AI 项目改动。两位 Luna Max 独立只读复审和最终工程检查已完成，未确认本轮批准范围内剩余安全阻断；报告本地证据链接已核对。现在暂停，等待人工 GO 和正式提交授权；状态为 **C6 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**，不得视为 CLOSED — GO。

# 历史：首次实施与锁支持环境验收（保留）

以下内容原样保留；其中“本轮”指首次实施阶段，未处置无锁 P2 的叙述是历史状态，最新结论以上方兼容补修为准。

**C6 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。等待人工复审，未标记 CLOSED — GO。**

本结论限定于本轮实际验证的、支持 Web Locks 的 Chromium 环境；不支持 Web Locks 的兼容分支仍有下述 P2 限制。以下先记录本轮实施与绿灯证据，再完整保留此前 Design Checkpoint 的历史报告和红灯证据。历史段落中的“本轮”“尚未实施”“NO-GO”均指修复前阶段，不代表最新状态。

## 本轮 1. 授权与工作区核对

执行前已对照历史报告第 5 节，实施只覆盖局部会话标识、AI 状态隔离、仅 AI 请求选择启用的共享认证 guard，以及 Login / Layout / 会话 helper 的必要配套。没有开展全站认证重构。

实际分支为 `zh-cn`，HEAD 为 `bf163569fbb4cd058e6548aad35173c3467d78a0`；完成核对时仍相同。本轮开始时已有 `frontend/playwright.ai.config.ts` 修改，以及未追踪 C6 报告、C6 测试和两个受保护文件；均为用户允许保留的基线，未发现无关 working tree 修改。没有重新核验线上 remote；历史线上 SHA 只作为历史信息。

没有 commit、push、暂存、创建 branch/tag、清理、回滚或改写历史；没有进入 G2，也未修改 C1/C4/C5/C7、后端、Finance 数据库/金融模型、Personal AI Workspace 或 Local AI Assistant。没有新增依赖、全局状态库或认证凭据合同。

## 本轮 2. 实际会话标识与认证变更配套

`authSession.ts` 以 `{ id, token, userId }` 内存快照核对会话。新增 localStorage key `finance-os:auth-session:v1` 只存本地 nonce；每次真实成功登录用 `crypto.randomUUID()` 生成新值，同用户、相同 JWT 的重登录也不会复用。nonce 不传给后端，不作为授权凭据，不包含财务或问答数据。token/userId 继续沿用原 key。

Login 在原 userId 校验通过后建立新会话；先将 nonce key 设为 `changing`，再写原认证字段，最后发布新 nonce。注销/401 清理也先进入 `changing`，再删除三个认证 key。中间状态、缺少 token、缺少有效 userId，以及连续读取不一致时，AI 视为无有效会话。既有无 nonce 的登录可读取为 `id: null`；之后实际 Login 一律生成新 nonce，Node 和既有 AI 浏览器回归覆盖此兼容路径。

同标签变更同步发出局部 `finance-os:auth-session-changed` 通知，解决自身标签不会收到 storage 事件的问题。没有清空 localStorage；Investment / Import 待恢复记录继续保留，退出前的既有检查和用户范围规则继续执行。

复审指出“401 校验到清认证之间的跨标签写入交错”后，新增一把原生 Web Locks 独占锁 `finance-os:auth-session-mutation:v1`。Login 的认证提交、Layout 的实际认证清理、Axios 401 的校验及全部副作用均在同一锁内执行。当前生产写入经 `rg` 核对只来自这三条路径；锁内没有网络等待，也没有嵌套获取同锁。此改动直接服务于局部会话写入的原子资格判断，没有改变后端或恢复协议。

Web Locks 不可用时保留兼容执行分支；该环境的极窄跨标签交错保证不成立，不能由本轮 Chromium 结果外推，详见本轮 13 节。

## 本轮 3. AI 状态与异步有效性

`AiAnalyst.tsx` 将页面状态绑定当前会话快照。会话变化清空 `question`、`lastSuccess`、`error`、`status`，同时置空旧请求锁，使新会话立即能够提交。每次请求使用独立对象作为请求代际身份；成功、catch 和 finally 都要求组件仍挂载、当前会话一致、当前锁仍归该请求所有。

旧 finally 无权释放新请求的锁。卸载时置为未挂载、作废请求并移除订阅。已经发出的旧请求仍可正常结束，但结果不能写入新页面；未增加问答历史持久化，也没有依赖网络取消来保证隔离。

页面监听认证相关 storage、同标签通知、focus 和 visibilitychange；输入、建议问题、提交，以及异步完成阶段再次读取认证。focus / visibility 用例通过真实页面监听器及同标签 raw storage 写入后派发事件，验证不依赖 storage 通知；没有将其描述为操作系统级窗口焦点或后台冻结实测。

同一有效会话内原 UX 保留：新请求和错误仍保留上一次成功回答，下一次成功才替换。原有 UX 测试文件未修改。

## 本轮 4. AI 专用 401 guard

`api/ai.ts` 通过既有 Axios client 发出 `/ai/ask`，显式传递 `aiSession`。共享请求拦截器只在该路径且选择启用 guard 时发送前核对会话；失效请求以 `ERR_CANCELED` 结束，问题不会被附上新用户 token 发出。有效请求使用被核对快照中的 token 设置 Bearer，并复制为 `aiSentSession`。

共享响应拦截器获得认证变更锁后，针对该 AI 请求核对 `aiSentSession` 与当前会话，并核对 config 的 Authorization。失效时在恢复回调、恢复记录写入、清 token/userId/nonce 和跳转之前返回；原错误随后仍以 rejected Promise 传递给页面。

当前会话真正 401 仍清认证、执行原失效流程并跳转 Login。没有让全部 AI 401 忽略认证。非 AI 请求不新增会话归属 guard；原投资/导入回调 → 恢复记录 → 清认证 → 按 `skipGlobal401Redirect` 决定跳转的顺序保留，认证清理与 Login 现在通过同锁串行。

## 本轮 5. 生产代码与测试修改清单

| 文件 | 本轮实际改动 |
| --- | --- |
| `frontend/src/utils/authSession.ts` | 新增局部 nonce、快照核对、变更通知和认证写锁 helper |
| `frontend/src/pages/Login.tsx` | 成功登录建立新 nonce；原用户恢复跳转保留 |
| `frontend/src/components/Layout.tsx` | 原退出保护通过后，在锁内清认证并通知 |
| `frontend/src/pages/AiAnalyst.tsx` | 状态会话绑定、订阅与重验、请求对象资格、卸载和锁隔离 |
| `frontend/src/api/ai.ts` | AI 请求携带局部会话快照 |
| `frontend/src/api/index.ts` | AI 发送前/401 guard、实际 Bearer 快照与锁内认证副作用 |
| `frontend/tests/auth-session.test.ts` | 新增 3 个 Node 会话生命周期测试 |
| `frontend/tests/e2e/ai-state-isolation.spec.ts` | 保留原 4 个红灯场景的安全意图，补齐 19 个 C6 / 配套浏览器测试 |
| `frontend/playwright.ai.config.ts` | 保留先前将 C6 文件纳入 AI 测试的配置改动，本轮未进一步改动 |
| `docs/archive/review/G1-4-C6-Cross-User-AI-State-Isolation-Review.md` | 本轮验收、历史证据和只读复审 |

既有 `ai-analyst.spec.ts`、Investment / Import Node 测试、Import Playwright、Investment 恢复生产代码均无修改。无删除、禁用或弱化旧测试；pending 测试的空状态断言移到释放旧响应前，之后补强新请求重叠和锁断言。

## 本轮 6. 双标签页 A/B/A 与迟到响应绿灯

本轮真实 headless Chromium，同一 BrowserContext 的两个 Page，共享实际浏览器 localStorage。身份切换通过实际 Login 表单和 Layout 退出按钮，运行实际 React 生命周期和 Axios 拦截器；API / Provider 为虚构 mock，不使用真实付费服务。

| 验收场景 | 本轮新执行结果 |
| --- | --- |
| A 成功回答和草稿 → 另一标签退出并登录 B | 即时清空 question、回答、错误、loading |
| A pending 200 → B 登录 | 释放响应前页面已清空；旧回答不出现 |
| A pending 503 → B 登录 | 旧错误不出现 |
| A pending 401 → B 登录 | B token、userId、nonce 完整保留；旧页不跳转 |
| 以上三种旧响应处理时 B 新请求仍 pending | B loading、问题和锁保留，重复 form submit 不增加请求，随后 B 成功 |
| A 退出后以相同 token 重新登录 A；旧 200/503/401 | 退出时认证和页面清空；新 nonce 不同，旧结果均不复活，新 A 可成功 |
| A → B → A；A 两次使用相同 token；旧 200/503/401 | 新 nonce 不同，旧成功、失败、401 均无副作用，新 A 成功 |
| 请求排队前切换到 B | 发送拦截取消旧问题，AI HTTP 请求数为 0 |
| AI 卸载再挂载 | 旧结果不进入新页面，新页面可以提交 |
| focus / visibilitychange | 无 storage 事件时也能重新校验并清空 |
| B Login 与旧 A 401 竞争认证写锁 | B Login 先入队，旧 401 后入队；放锁后 B 认证保留，旧页不跳转 |

跨用户 pending 用例还核对实际网络 `Bearer A`、`Bearer B`。响应负断言先等待真实 Axios 响应链观察器，再排空 Promise 后的两帧渲染；不再使用历史固定 200 ms 等待。B pending 时再次触发表单，确认请求总数仍为 2，证明旧 finally 未释放新锁。

最终日志：[C6 与完整 AI 回归](../../../frontend/test-results/c6-acceptance-run.log)。测试进程输出 `C6 实跑浏览器：chromium 151.0.7922.34`。没有 `PLAYWRIGHT_EXECUTABLE_PATH` 覆盖；另有 [独立浏览器启动信息](../../../frontend/test-results/c6-browser-run.log)，其 executablePath 不冒充测试进程路径追踪。

绿灯截图示例：[已完成状态清除](../../../frontend/test-results/c6-acceptance/ai-state-isolation-C6-comp-b438b-cond-tab-logout-and-B-login/completed-after-switch.png)、[旧 200 / B pending](../../../frontend/test-results/c6-acceptance/ai-state-isolation-C6-pend-b859a-r-UI-after-second-tab-login/pending-200-after-switch.png)、[旧 503 / B pending](../../../frontend/test-results/c6-acceptance/ai-state-isolation-C6-pend-3f804-r-UI-after-second-tab-login/pending-503-after-switch.png)、[旧 401 / B pending](../../../frontend/test-results/c6-acceptance/ai-state-isolation-C6-pend-37f2a-r-UI-after-second-tab-login/pending-401-after-switch.png)、[ABA 旧 401 后新 A 成功](../../../frontend/test-results/c6-acceptance/ai-state-isolation-C6-ABA--c57dd-cal-A-token-rejects-old-401/ABA-401.png)。截图证明 UI，认证保留和网络锁结论以实跑断言为证据。

## 本轮 7. 当前有效会话真正 401

既有无 nonce 认证的 AI 401 回归与新增真实 Login nonce 会话 401 回归都实际通过。新增用例确认 token、userId、nonce 均被清除，跳转 `/login`，无关存储记录保留。Investment 非 AI 401 与 Import 401 恢复均另行实跑通过；没有改变非 AI guard 合同。

## 本轮 8. 正常 AI UX

原有 AI Playwright **9/9 本轮实际通过**，文件和断言未改。覆盖 trim、空白/3000 字符限制、Bearer、重复提交锁、loading/失败保留上次成功、成功替换、429/502/503/500、401、未登录保护、刷新不持久化、HTML 纯文本以及桌面/平板/窄屏布局。

本轮 AI 配置总计 **28/28**：原有 9 项 + C6 隔离/生命周期/竞争 16 项 + Investment / Import 认证配套 3 项。最终运行 56.2s；先前本轮 24/24、27/27 的中间绿灯日志也保留，但不代替最终实现后的结果。

## 本轮 9. Investment / Import 身份恢复

新增定向 Investment 浏览器用例实际运行既有 React coordinator、navigator.locks、Axios 和 storage：OUTCOME_UNKNOWN 的原 POST 401 后写 AUTH_REQUIRED，保留原 body 和 idempotency key；实际同用户 Login 后恢复一次相同 POST、显示恢复回执及刷新完成，最后清除已完成记录。另一个用例验证退出确认、恢复记录不变、B 初始读取和 React 扫描完成后没有 A 的恢复入口、A 再登录后入口恢复；身份切换过程没有恢复 POST。

新增 Import 退出用例验证当前用户 pending 记录（包括无效但应保留的证据）阻止退出，导向 Import，token/userId/nonce、pending 和 draft 原样保留。

原有 Import Playwright **3/3 本轮实际通过**：同用户 401 后 GET-first + 原 POST 重放、换用户不恢复 A intent、CSV 全流程含 warning/Receipt/reload。[最终日志](../../../frontend/test-results/c6-import-final-run.log)。用户切换 fixture 的 accounts/categories/rows 未全部 mock，Vite 日志有 `ECONNREFUSED`；安全/恢复断言通过，但真实后端读取成功未验证，没有通过改生产或旧 fixture 掩盖日志。

Node **39/39** 包含未修改的 Investment Command / Import 恢复、身份/存储校验及新增 3 项会话测试。[最终日志](../../../frontend/test-results/c6-node-final-run.log)。真实后端 Investment 全套和 Import real 未执行：其 fixture 会写数据库，本轮禁止修改 Finance 数据库，采用相关 deterministic browser/Node 回归；不冒充数据库金融行为验收。

## 本轮 10. 工程验证

执行目录为 `frontend`；以下全部是最终生产实现后的新执行结果：

| 命令 | 实际结果 |
| --- | --- |
| `npx playwright test --config playwright.ai.config.ts --output test-results/c6-acceptance` | 28 passed / 0 failed，56.2s |
| `npx playwright test --config playwright.transaction-import.config.ts --output test-results/c6-import-final` | 3 passed / 0 failed，9.9s；含上节环境日志限制 |
| `npm test` | 39 passed / 0 failed / 0 skipped |
| `npm run lint` | exit 0，[日志](../../../frontend/test-results/c6-lint-final-run.log) |
| `npm run build` | exit 0，TypeScript + Vite 通过，[日志](../../../frontend/test-results/c6-build-final-run.log) |
| `git diff --check` | exit 0；报告完成后再次核验 |
| `git status --short` / 实际 diff / staged diff | 仅列出的 C6 文件和受保护基线；staged diff 空 |

新未追踪源码/测试另做尾部空白检查，无命中；报告本地链接完成后检查。既有超过 500 kB bundle 提示仍在，不为此扩大打包修改。

## 本轮 11. 两个 Luna Max 独立只读复审

本轮明确以两个独立上下文请求 `gpt-6-luna`、`max`：`c6_security_review` 和 `c6_test_review`；不是自动并行实施。主控按用户指定 GPT-6.1 Sol High 工作，实际服务端路由无法另行核验。两位 Agent 均未修改文件、未读取受保护文件，**没有独立运行测试**；测试数字属于主控实跑，Agent 只做独立静态审查和日志复核。

安全 Agent 首次未确认 P0/P1，指出校验到清理间的理论 P2 交错。主控核对其真实性后补共享认证写锁和真实排队竞争用例；修复后仅做一次只读复验。最终未发现锁支持环境下的阻断项，支持支持 Web Locks 的 Chromium 候选 GO；要求保留无锁兼容分支限制。身份快照、同 token 新 nonce、success/catch/finally/卸载资格和实际请求 token 绑定均获静态确认；未见同步锁回调重入/死锁。

测试 Agent 首次指出 B Investment 无入口的 count=0 可能早于 useEffect。主控验证后，补等初始交易表真实渲染及两帧，再作负断言。修复后一次复验确认该 P2 已解决；确认同 BrowserContext 双页、真实 Login/Logout、相同 token 同用户/ABA、旧 200/503/401、B pending 锁、当前 401、卸载及事件订阅；核对真实 Bearer 和新增锁排队测试。最终未发现 P0/P1/阻断项，同意仅在本轮支持锁环境下候选验收。

## 本轮 12. P0 / P1 / P2 findings

| 级别 / 项目 | 状态与证据 |
| --- | --- |
| P0 / P1 | 本轮实现和重点只读复审未确认新增 P0/P1，不代表未测范围已安全通过 |
| 历史 P2 / C6 阻断：私有问题/草稿/回答残留 | 本轮原场景绿灯，保留历史红灯 |
| 历史 P2 / C6 阻断：迟到成功/错误/401 | 本轮 200/503/401、相同 token 重登录、ABA 及新锁重叠绿灯 |
| 本轮 review P2：校验和清认证交错 | 支持锁环境已用锁内 guard 与竞争用例解决；无锁环境仍为限制 |
| 本轮 review P2：Investment 负断言过早 | 已等初始读取和 React 扫描后复验通过 |
| Known Limitation / P2：无 Web Locks | 兼容分支无法保证跨标签认证写入串行；不纳入本轮原子性通过声明 |

## 本轮 13. 未验证范围与已知限制

- 不支持 Web Locks 或未提供该 API 的运行上下文，仍走兼容分支，极窄旧 401 / 新登录交错风险尚存；没有将其标为已修复。当前候选范围要求可用 Web Locks。若人工验收要求无锁浏览器也提供完全相同保证，C6 不应正式 GO，需追加兼容设计批准。
- 当前验证为 Chromium 151.0.7922.34；Firefox/WebKit、操作系统窗口焦点、后台冻结/恢复、崩溃与浏览器存储不可用未实跑。
- 页面外直接修改认证 key、绕过 helper/锁的脚本不在认证提交合同内。历史无 nonce 会话兼容已测，未来实际 Login 生成新 nonce；不推断绕过 Login 且重复恢复同 token/key 的脚本安全。
- 旧网络请求未强制取消，仍可能由原会话 Provider 完成；本轮保证其不能改新状态。真实 Provider、真实 JWT/数据库集成、Maven/Finance 全量未执行。
- Import 部分未 mock 的读取出现环境拒绝连接；本轮报告仅声明相应 browser/mock 断言通过。

## 本轮 14. 受保护文件与历史证据

`AGENTS.md` 和 `docs/codex-task.md` 仍为 untracked；本轮未读取内容、修改、删除、暂存或提交。没有为了字节核对读取或计算内容 hash；状态与本轮操作记录是保护证据。

修复前 `c6-before-run.log`、`c6-before/` 截图及 error-context、`c6-baseline-run.log` 均保留；没有重跑覆盖、清理或重写。最终绿灯放在独立 `test-results/c6-acceptance/` 和独立日志，全部使用仓库已有 ignored `test-results` 位置，不混入 Git。以下历史报告完整保留。

## 本轮 15. 最终结论与人工复审

**C6 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。**

批准的生产修改已实施，本轮支持 Web Locks 的 Chromium 双标签页安全场景、AI UX、相关 Investment / Import 回归及工程检查通过，两位 Luna Max 完成独立只读审查及一次修复后复验。无锁兼容风险明确留作 P2 / 未验证范围；候选结论不外推到该环境。

现在暂停实施，等待人工复审本报告、实际 diff、绿灯/历史红灯及兼容边界。没有提交，也没有标记 CLOSED — GO、进入 G2 或推进其他检查项。

# 历史 Design Checkpoint 与修复前红灯（保留）

**历史结论：C6 — NO-GO。当时停在 Design Checkpoint，尚未实施生产修复。**

## 1. Preflight

| 检查 | 本轮实际结果 |
| --- | --- |
| Branch | `zh-cn` |
| HEAD | `bf163569fbb4cd058e6548aad35173c3467d78a0` |
| 本地 `origin/zh-cn` | 与 HEAD 相同 |
| 线上 `origin/zh-cn` | `git ls-remote origin refs/heads/zh-cn` 返回相同 SHA |
| 初始 tracked diff / staged diff | 均为空 |
| 初始 untracked | 仅 `AGENTS.md`、`docs/codex-task.md`，属任务允许的基线 |

本轮未读取两个受保护文件的内容；使用用户直接提供的仓库规则。未执行 commit、push、创建分支、历史操作、暂存或删除用户文件。

## 2. 当前认证与 AI 状态结构

`App.tsx` 的 `PrivateRoute` 在渲染时读取 `localStorage.token`，不订阅认证变更。`Login.tsx` 写入 token 与 `finance-os:auth-user-id:v1`；`Layout.tsx` 退出时移除这两个 key，并沿用 Investment Command / Transaction Import 的恢复约束。

`AiAnalyst.tsx` 使用本地 `question`、`status`、`error`、`lastSuccess` 和 `requestLock`。成功、失败与 finally 均无认证会话、请求 generation 或卸载校验；页面未订阅 storage、focus、visibilitychange。新请求及失败保留上次成功回答是既有 UX。

`api/ai.ts` 复用共享 Axios client。`api/index.ts` 在发送时读取当前 token；收到受保护请求的 401 后，只检查当前是否存在 token，随后执行恢复回调、写恢复记录、清除认证及跳转，未检查失败请求是否属于当前认证会话。

## 3. 修复前真实浏览器证据

使用 Playwright 1.62.1 的真实 headless Chromium，同一个 BrowserContext 内创建两个 Page。通过实际 `Login` 表单与 `Layout` 的退出按钮改变认证，没有用直接 localStorage 写入替代身份切换。API 被 mock，用户、密码、token、问题和回答全部为虚构 fixture；未调用真实 AI Provider。

执行目录为 `frontend`：

```powershell
npx playwright test --config playwright.ai.config.ts ai-state-isolation.spec.ts --output test-results/c6-before
```

**4 个安全测试全部失败，属于修复前缺陷复现，不是环境失败。**

| 场景 | 实际观察 | 安全断言 |
| --- | --- | --- |
| A 成功后在另一标签退出并登录 B | 原标签保留 A 的回答、问题和未提交草稿；截图人工复核一致 | answer count 预期 0、实际 1 |
| A pending → B 登录 → A 200 | A 的迟到回答出现；B 的认证仍存在 | answer count 预期 0、实际 1 |
| A pending → B 登录 → A 503 | 原页面显示 A 的旧请求错误；B 的认证仍存在 | alert count 预期 0、实际 1 |
| A pending → B 登录 → A 401 | 另一标签中 B 的 token 与 userId 均被清除 | 认证预期保留、实际两项均 null |

本地证据保存在 Git 已忽略的 `frontend/test-results/`，没有提交截图或日志：

- [复现命令输出](../../../frontend/test-results/c6-before-run.log)
- [已完成回答残留截图](../../../frontend/test-results/c6-before/ai-state-isolation-C6-comp-b438b-cond-tab-logout-and-B-login/completed-after-switch.png)
- [迟到成功截图](../../../frontend/test-results/c6-before/ai-state-isolation-C6-pend-b859a-r-UI-after-second-tab-login/pending-200-after-switch.png)
- [旧错误截图](../../../frontend/test-results/c6-before/ai-state-isolation-C6-pend-3f804-r-UI-after-second-tab-login/pending-503-after-switch.png)
- [401 认证断言证据](../../../frontend/test-results/c6-before/ai-state-isolation-C6-pend-37f2a-r-UI-after-second-tab-login/error-context.md)

401 截图处于文档跳转中的空白画面，不用于证明最终登录页；清除 B 认证的证据是浏览器实际读取 localStorage 的失败断言。

## 4. 竞态与根因

客户端状态没有绑定认证生命周期，因此认证存储变化不会让 AI 页面已有私有状态失效。旧请求的 success/catch/finally 也没有资格校验；即使先清空页面，旧请求仍能重新写回状态或释放后续请求的锁。

401 的污染发生在页面 catch 之前的共享响应拦截器，单独在页面忽略旧异常不足以保护 B 的认证。`skipGlobal401Redirect` 当前仅阻止跳转，不阻止清认证与恢复回调，因此不能作为隔离修复。

此外，`JwtUtil.generateToken` 未包含随机 `jti`；同用户快速重新登录是否总能获得不同 token 无法保证。仅比较 userId 不足以识别会话，仅比较当前 token 也需要评估重复 token 和 A → B → A。

## 5. Design Checkpoint：待授权的最小方案

任务文本第六、七节明确要求：发现直接相关的共享 401 竞态时先记录证据；如必须修改共享认证拦截器或引入新认证基础设施，先输出 Design Checkpoint 并等待进一步授权。本轮按此停在生产修改之前。

建议授权以下范围：

1. `AiAnalyst.tsx` 将所有私有状态绑定认证会话 snapshot；使用单调 generation 与每次请求的独立有效性身份。会话变化同步作废旧请求，清空输入、回答、错误和 loading；旧 finally 只能释放其所属的锁。
2. 监听认证相关 storage 事件、focus 与 visibilitychange，并在输入交互、提交前、请求成功/失败/finally 时重新校验。卸载作废请求；取消可作辅助措施，资格校验仍必须独立成立。
3. `api/ai.ts` 为 AI 请求传递会话有效性检查；`api/index.ts` 为此增加可选、仅 AI 启用的 guard。请求拦截器发送前再次检查会话，避免把旧页面问题用新用户 token 发出；响应拦截器在任何恢复回调、恢复记录写入、清认证或跳转之前拒绝失效 401 的副作用。未传此 guard 的既有调用方保持现有路径。
4. 为可靠覆盖重复 token 的同用户新会话，建议限定在 `Login.tsx` / `Layout.tsx` 和一个小型会话 helper 引入每次成功登录的非 secret 随机 nonce。nonce 不包含问答、财务信息或凭据，也不作为服务端授权依据；需明确中间写入状态和已有登录会话的兼容策略。单靠观察当前 userId/token 与本地事件计数不能证明所有 ABA 时序安全。本项属于待授权设计，未实施。

取舍：页面清空只能修已有回答；页面 catch guard 无法拦住共享 401 的先行副作用；只取消网络请求无法保证取消前已进入响应链的结果；另建 AI Axios client 会重复认证逻辑。本方案继续复用既有 client，并将新增 401 行为限制在显式选择的 AI 请求。

授权后的额外回归：实际登录/退出、正常本会话 401、Investment Command 与 Transaction Import 的用户身份恢复及恢复记录保留。生产后端、数据库、金融规则和 AI Tool 不在修改范围内。

## 6. 本轮修改文件完整清单

| 文件 | 实际修改 |
| --- | --- |
| `frontend/playwright.ai.config.ts` | testMatch 纳入 C6 独立测试文件 |
| `frontend/tests/e2e/ai-state-isolation.spec.ts` | 新增 4 个真实双标签页安全回归及截图证据输出 |
| `docs/archive/review/G1-4-C6-Cross-User-AI-State-Isolation-Review.md` | 本报告与待授权设计 |

生产前端、后端和共享认证模块均未修改。没有新增依赖。

## 7. 双标签页浏览器测试

已实际完成：两个标签共享 BrowserContext，通过真实页面表单和按钮执行 A 登录、退出、B 登录。completed、pending 成功、pending 503 和 pending 401 均已复现缺陷。

新增测试当前作为修复前安全回归保留为失败；没有删除、禁用或弱化安全断言。pending 用例在响应释放后等待实际 HTTP response，再给任务队列 200 ms；该固定等待仅用于本轮阳性缺陷证据，修复后应补强应用消费/新请求重叠的同步证据，不能仅凭短暂的负面 DOM 断言宣布隔离成功。

## 8. Pending、401 与快速切换

| 验收内容 | 本轮状态 |
| --- | --- |
| 旧 200 不能写入新会话 | 实际失败 |
| 旧 503 不污染新 UI | 实际失败 |
| 旧 401 不清新认证 | 实际失败 |
| B 在旧请求 pending 时正常发送新请求 | 尚未验证；新增用例在前置安全断言处失败 |
| 旧 finally 不释放 B 的新锁 | 尚未验证 |
| 同用户新会话、相同 token | 尚未验证 |
| A → B → A 快速切换 | 尚未验证 |
| 卸载/取消后的迟到响应 | 尚未验证 |
| focus / visibility 与认证中间状态 | 尚未验证 |

## 9. 正常 AI UX 回归

既有 `ai-analyst.spec.ts` 本轮实际 **9/9 通过，11.7s**。覆盖空白问题、trim、3000 字符/超长阻止、正常 Bearer 请求、重复提交锁、loading/失败保留上次成功、成功替换、429/502/503/500、正常 401 清认证与跳转、未登录保护、刷新不持久化、HTML 作为纯文本，以及桌面/窄屏布局。

这是未改生产代码的基线回归，不是修复后验收证据。

```powershell
npx playwright test --config playwright.ai.config.ts ai-analyst.spec.ts --output test-results/c6-baseline
```

[本地基线输出](../../../frontend/test-results/c6-baseline-run.log)。

## 10. 共享认证与后端边界

共享认证模块未修改。直接相关的共享 401 竞态已得到真实浏览器证据，等待 Design Checkpoint 授权。

静态复核 `JwtAuthFilter`、`AiController`、Dispatcher 及三个 Finance Tool：HTTP 身份来自有效 JWT 及 ACTIVE 用户，Controller 从 Authentication 的 Long principal 传入 userId，Dispatcher 将该身份传给既有只读查询服务。本轮未发现足以升级为后端越权的证据，未修改后端，也未实际重跑后端权限集成测试。前端 fixture 的 userId 不充当服务端授权依据。

## 11. 工程检查统计

| 命令/检查 | 本轮实际结果 |
| --- | --- |
| C6 定向 Playwright | 0 passed / 4 failed；修复前阳性复现 |
| 既有 AI Playwright | 9 passed / 0 failed |
| `npm test` | 36 passed / 0 failed / 0 skipped |
| `npm run lint` | exit 0 |
| `npm run build` | exit 0；TypeScript 编译与 Vite 构建通过 |
| `git diff --check` | 通过；报告完成后再次核验 |
| staged diff | 空 |

Node v22.22.2；Playwright 1.62.1；Vite 实际构建输出为 v8.1.3。配置默认 browser 为 Chromium，无 `PLAYWRIGHT_EXECUTABLE_PATH` 环境覆盖。另用同一安装的 `chromium.launch({ headless: true })` 成功启动并读取版本 151.0.7922.34；默认 executablePath 为本机 `ms-playwright/chromium-1234/chrome-win64/chrome.exe`（独立启动核验，不冒充测试进程的可执行文件追踪）。构建仍有超过 500 kB 的 bundle 提示，未扩大范围调整打包。没有运行 Maven 全量、真实 AI Provider 或 Finance 全量验收。

现有 Node suite 包括 Investment Command 和 Import 的 storage/身份恢复测试，但本轮并非共享认证修改后的完整浏览器回归。授权并实施后仍需按影响范围补验。

## 12. 两个 Luna Max 独立只读意见

请求配置均为 `gpt-6-luna`、`max`，以独立上下文启动。主控与子 Agent 的实际服务端模型路由和思考档位无法独立核验。

认证边界 Agent（`auth_review`）：**NO-GO**。独立静态确认页面没有会话绑定、401 没有核对请求归属；赞成仅 AI opt-in 的共享 guard，要求发送前也核验会话，并建议每次成功登录使用随机 nonce 来区分重复 token 的新会话。其后端结论仅为静态身份传递检查，不作为实际后端越权测试通过证据。未运行测试、未修改文件。

竞态/浏览器 Agent（`race_review`）：**NO-GO**。独立阅读复现日志与新测试，确认四类失败与生产代码吻合。指出应在释放旧响应之前断言即时清空；补同 token 重登录、A → B → A、focus/visibility、卸载、旧 finally 与 B pending 锁重叠；固定 200 ms 不能证明 Axios / React 全部处理完成。未运行测试、未修改文件。其无法从当时提供的日志独立确认 Chromium binary；主控后续做了上一节的独立版本核验。

两名 Agent 的意见均为修复前只读审查，不是修复后独立验收。主控对核心 findings 已用实际浏览器断言和生产代码复核。

## 13. P0 / P1 / P2 Findings

P2 / C6 阻断：已完成私有回答/草稿残留；迟到成功和失败污染 UI；旧 401 清除新用户认证。前三类页面生命周期根因与最后一类拦截器副作用均有当前浏览器证据。保留原 C6 严重级别，未擅自扩展为后端跨用户数据泄露。

本轮没有确认新增 P0/P1；未验证的验收项不视为安全通过。由于生产修复尚未实施，不能确认不存在其他阻断。

## 14. 受保护文件状态

`AGENTS.md`、`docs/codex-task.md` 保持 untracked；未读取、修改、删除、暂存或提交内容。其余工作区变化仅为第六节三项本轮文件。证据文件在已有 ignored `test-results` 下，不参与提交。

## 15. GO / NO-GO 与下一步

**C6 — NO-GO。** 已完成 preflight、修复前真实复现、基线验证及设计收敛；尚未实施修复，不能报告 IMPLEMENTED / ACCEPTANCE GO CANDIDATE，更不能标记 CLOSED — GO。

等待人工批准第五节的局部会话与 AI 专用共享 401 guard 修改范围，再实施、补齐完整浏览器验收矩阵、复验工程检查并由两个只读 Agent 进行修复后独立复审。没有进入 G2 或启动 Finance Freeze。
