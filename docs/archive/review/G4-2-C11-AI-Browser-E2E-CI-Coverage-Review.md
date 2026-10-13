# G4-2 / C11 — AI Browser E2E CI Coverage Review

核对日期：2026-10-13（Asia/Shanghai）。范围：独立 AI 浏览器 CI Job、失败诊断、现行测试说明；本轮不提交或推送。

## 1. 结论与状态边界

**C11 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE。** Windows 修改前后完整测试通过；Linux 等价命令实际退出 0；工程验证与两名独立只读复审通过。没有已发现的 C11 P0/P1 或失败门禁绕过路径；正式关闭条件尚未满足。

人工授权背景为 C1–C10 CLOSED — GO、原始审计进度 10/11，C11 OPEN、Finance Final Stabilization。本轮不将进度改为 11/11，不宣称 C11 CLOSED、整个 CI 绿灯或 Finance Freeze。`docs/STATUS.md` 在指定 HEAD 保留 C10 提交前的历史措辞；本轮按任务给定的人工正式状态执行，不扩大到状态文件维护。

## 2. Preflight

| 检查 | 本轮结果 |
| --- | --- |
| 当前分支 | `zh-cn` |
| 本地 HEAD | `788e4120128b97ccce51d8aaa5b2b13646b920a3` |
| 本地 `origin/zh-cn` | 同一 SHA |
| 独立 `git ls-remote origin refs/heads/zh-cn` | 同一 SHA |
| 初始 tracked diff | `git diff --quiet` exit 0 |
| 初始 staged diff | `git diff --cached --quiet` exit 0 |
| 初始工作区路径 | 仅 `?? AGENTS.md`、`?? docs/codex-task.md` |

两份受保护文件未读取内容或 hash，未修改、删除、暂存或提交。主控与两名只读审查员均遵守内容/hash 禁令。未执行 commit、push、tag、branch、stash、restore、reset、clean、rebase、amend 或 force push。

## 3. 修改前 CI 缺口

已重新阅读指定 HEAD 的 workflow、package、AI Playwright 配置、两份 Spec、Vite 配置、README、现行测试说明和 STATUS。`frontend/package.json` 原已定义 `test:e2e:ai`；配置原已匹配两份 AI Spec，并以独立 Vite `127.0.0.1:5182` 为 webServer。

原 CI 的 `backend`、`frontend`、`e2e`、`deployment` 均存在；`e2e` 只包含 Investment Command、Import mock/browser、Import real-backend Playwright 命令，未执行 AI Spec。`npm test` 也不收集 `tests/e2e/`。本轮复用已有测试与服务启动方式。

## 4. 实际测试收集与覆盖

主控执行 `npm run test:e2e:ai -- --list`，再通过现有 Playwright CLI 的 JSON list reporter 解析测试文件、声明行列与预期运行状态；浏览器审查员另行独立执行了 `--list`。

| Spec | 唯一 `test(...)` 声明位置 | 参数化展开执行数 |
| --- | ---: | ---: |
| `ai-analyst.spec.ts` | 6 | 9 |
| `ai-state-isolation.spec.ts` | 17 | 49 |
| 合计 | 23 | 58 |

这里的 23 是源码声明位置，不是 23 次浏览器执行。58 个展开用例全部预期运行；两份 Spec 与 HEAD 内容一致（规范化 CRLF/LF 后逐字比对），没有 skip、fixme 或 only，未删除或放宽断言。

- Analyst：认证路由与 Dashboard 导航；JWT header 与只含 trim 后问题的请求体；空白与 3000 字符边界；防重复提交；保留上一成功结果及成功替换；纯文本回答、HTML/XSS 不执行；401、429、502、503、500；刷新不恢复聊天；1280、820、768、390、320px 下布局及导航。
- State isolation：A/B 切换与第二标签页 Logout/Login；旧 200/503/401 响应；同 token 的 same-user 与 ABA 新 Session；当前/旧 401 的区别；发送时 Auth Session Guard；focus/visibilitychange；页面卸载；登录提交与旧 401 的跨标签页锁竞争。
- 恢复与兼容：Investment intent 保留、同用户恢复及跨用户拒绝；Import pending 阻止退出且保留 draft；Web Locks missing、no-request、rejected、throws、getter-throws；旧响应不得改变新请求 pending 状态；无 guard 的直接 Axios AI 请求被拒绝；非 AI 401/Login 保留兼容与恢复记录。

这些是模拟 HTTP 响应的浏览器契约测试。真实用户权限校验、后端 JWT 验证、Cloud Provider 和 Finance Tool 执行不属于本组 mock E2E 的实跑证据。

## 5. 最终 Job 结构

| Job ID / name | 结构 |
| --- | --- |
| `backend` / Backend | 原配置保留 |
| `frontend` / Frontend | 原配置保留 |
| `ai-e2e` / AI Browser E2E | 新增，独立 Ubuntu runner，无 `needs`、services 或业务 secret；15 分钟 timeout |
| `e2e` / Playwright E2E | 原 PostgreSQL / Spring Boot / Investment / Import 配置保留 |
| `deployment` / Deployment artifacts | 原 `needs: [backend, frontend]` 保留；镜像构建及 Compose config |

新 Job 顺序为 checkout → Node 22 / npm cache → `npm ci` → `npx playwright install --with-deps chromium` → `npm run test:e2e:ai` → 失败时上传诊断。所有 run step 的默认工作目录为 `frontend`；Action 的上传路径按 workspace 写为 `frontend/test-results/`。

push / pull_request 均仍针对 `zh-cn`，workflow_dispatch、`contents: read` 与既有 concurrency 保留。测试步骤没有额外条件、`continue-on-error`、管道或 `|| true`，Playwright 非零退出直接传播。

## 6. 实际修改与 diff

| 文件 | 实际变更 |
| --- | --- |
| `.github/workflows/ci.yml` | 新增 30 行独立 AI Job；原 Job 未改 |
| `frontend/playwright.ai.config.ts` | 新增 3 行：CI forbidOnly、失败 trace、失败 screenshot |
| `docs/engineering/testing.md` | 新增 9 行：AI 类型、命令、mock 边界、CI 诊断和远端验收口径 |
| 本 Review | 新增本轮证据与独立结论 |

运行配置的实际新增 diff 如下；没有更改任何 Spec、生产代码、依赖或锁文件。

```diff
+  ai-e2e:
+    name: AI Browser E2E
+    runs-on: ubuntu-latest
+    timeout-minutes: 15
+    defaults:
+      run:
+        working-directory: frontend
+    steps:
+      - uses: actions/checkout@v6
+      - name: Set up Node.js 22
+        uses: actions/setup-node@v6
+        with:
+          node-version: '22'
+          cache: npm
+          cache-dependency-path: frontend/package-lock.json
+      - name: Install dependencies
+        run: npm ci
+      - name: Install Playwright Chromium
+        run: npx playwright install --with-deps chromium
+      - name: Run AI browser contract Playwright E2E
+        run: npm run test:e2e:ai
+      - name: Upload AI E2E failure diagnostics
+        if: failure()
+        uses: actions/upload-artifact@v7
+        with:
+          name: ai-e2e-failure-diagnostics
+          path: frontend/test-results/
+          if-no-files-found: warn
+          retention-days: 7
+
```

```diff
     testMatch: ['ai-analyst.spec.ts', 'ai-state-isolation.spec.ts'],
+    forbidOnly: !!process.env.CI,
     ...
         headless: true,
+        trace: 'retain-on-failure',
+        screenshot: 'only-on-failure',
```

## 7. Windows 本轮实跑

环境：Windows 11 家庭版 中文版 `10.0.26200`；Node `v22.22.2`、npm `10.9.7`；Playwright `1.62.1`；实际 Chromium `151.0.7922.34`，与锁文件及 Playwright browser revision `1234` 一致。

| 执行 | PASS / FAIL / SKIP | 持续时间 / 退出码 |
| --- | --- | --- |
| 修改前 `npm run test:e2e:ai` | 58 / 0 / 0 | Playwright 输出 `2.2m`，exit 0 |
| 修改后 `$env:CI='true'; npm run test:e2e:ai` | 58 / 0 / 0 | Playwright 输出 `4.3m`，exit 0 |

两轮均完整执行两份 Spec。普通完整运行没有失败日志；存在 NO_COLOR / FORCE_COLOR 的工具提示，不影响结果。

## 8. Linux 等价运行

在 Docker Linux engine `29.6.1` 中运行独立 `node:22-bookworm-slim` 容器（Debian GNU/Linux 12、x86_64；Node `v22.23.3`、npm `10.9.9`）。宿主 frontend 只读挂载，仅复制 package/lock、Vite/Playwright/TS 配置、index、src/public/tests 到容器 `/app`；不复制本机 node_modules、环境文件或日志，不传入业务凭据，不启动后端或数据库。

容器内以 `CI=true` 顺序实际执行：

```sh
npm ci
npx playwright install --with-deps chromium
npm run test:e2e:ai -- --list
npm run test:e2e:ai
```

上述顺序命令及容器实际 **exit 0**。Docker `die` 事件再次确认 `exitCode: "0"`、`execDuration: "765"`；765 秒约为 12 分 45 秒，包含 npm/系统依赖/浏览器安装和测试，不是纯 Playwright 测试时长。容器已按 `--rm` 正常结束，宿主源码挂载只读。

实际日志确认 Chromium `151.0.7922.34`、两份 Spec，且观察到连续成功执行至第 57 个用例；随后命令成功退出。Docker run / logs-follow 工具输出流没有保留最后的 Playwright 汇总行。因此本报告确认 Linux 同命令实跑成功，但不把未捕获的最终 PASS/FAIL/SKIP 汇总或纯测试时长写成直接日志证据；准确 58 次收集口径来自第 4 节独立 list/JSON 核对，不能冒充为 Linux 最终汇总行。

首次镜像拉取因 Docker Registry TLS handshake timeout 失败；重试后下载成功。这是环境下载问题，未修改项目代码。Linux 使用 Debian 12 容器，不能等同于实际 GitHub `ubuntu-latest` runner。镜像 digest 为 `sha256:c3de60bf2f9dd0ac6370e6117950ff62d6e339527e7472301c9c78a017978392`。

## 9. 工程验证

| 检查 | 本轮实际结果 |
| --- | --- |
| `npm test` | 修改后复验 45 PASS、0 FAIL、0 SKIP，exit 0；TAP duration `1292.0622ms` |
| `npm run lint` | exit 0 |
| `npm run build` | exit 0，TypeScript + Vite `8.1.3`；主 bundle 超过 500 kB 的已有提示保留 |
| Workflow YAML | 使用现有 Playwright bundled YAML parser，unique-key 解析无错误 |
| Workflow 结构比对 | triggers、permissions、concurrency 和原 4 个 Job 与 HEAD 的解析结果逐项完全相同 |
| AI Job 静态断言 | 独立性、工作目录、Node/lock、安装/测试命令、无失败容忍、失败 artifact 路径及 7 天保留均通过 |
| 测试保真 | 两份 Spec 与 HEAD 一致、无 skip/fixme/only；JSON list 独立确认 6/17 个声明位置、9/49 次执行 |
| `git diff --check` | 已执行，无 whitespace error；Git 行尾转换提示不属于失败 |

npm test / lint / build 修改前已通过，修改后按第四阶段要求再次执行且通过；表中采用修改后结果。修改后完整 Playwright 已实跑。未安装新验证依赖；未运行 actionlint。现行测试说明和本 Review 的本地链接、尾随空白及结尾换行检查通过。

## 10. 失败传播、诊断与凭据

保留 `retries: 0`。因此使用 `trace: 'retain-on-failure'`，而非仅首轮 retry 录制，保证首次测试失败也留下 trace；截图为 `only-on-failure`。已有 Spec 的显式虚构截图保持原样。

另行进行故障探针，CLI 只收集 `--grep trimmed` 的一个既有用例，再以 `--timeout 1000` 人工收紧执行时限，输出到仓库外的独立目录，不修改用例或默认 30 秒 timeout：

```text
Test timeout of 1000ms exceeded.
1 failed
diagnostic_probe_exit=1
```

实际生成 `test-failed-1.png`（108145 bytes）、`trace.zip`（207039 bytes）及 `error-context.md`，并用 ZipFile 成功读取 trace 条目。目录为 `C:\Users\28421\.codex\visualizations\2026\10\13\01a132b3-6100-7933-b05d-c32feefda9e3\c11-diagnostic-probe`。这是预期的诊断故障注入，不是完整套件失败或产品缺陷；此前带锚点的 grep 未匹配完整测试标题，返回 `No tests found` / exit 1，修正匹配后才进行此有效探针。

新 Job 无 Provider、真实 JWT 或业务 secret 注入。登录密码、token、用户和回答均为虚构 fixture；上传仅限干净 AI runner 的 `frontend/test-results/`，不上传 env、整个 workspace 或业务日志，保留 7 天，默认不包含 hidden files。trace 可包含虚构 Authorization/request/DOM/source 信息，这是允许的诊断边界，不能将真实身份或金融数据接入此 fixture。

`failure()` 只控制诊断上传；`if-no-files-found: warn` 处理依赖或启动前失败时没有浏览器文件的情形，不改变先前测试失败。上传 action 自身失败也不会令 Job 成功。真实 GitHub 上传尚未执行；本轮只验证本地诊断生成与 YAML 失败传播结构。

配置依据：[Playwright recording options](https://playwright.dev/docs/test-use-options#recording-options)、[upload-artifact v7 action 定义](https://github.com/actions/upload-artifact/blob/v7/action.yml)、[GitHub workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)。v7 action 内部 Node 24 runtime 与测试环境 Node 22 是不同层次，适用于 hosted Ubuntu runner。

## 11. C9 / C10 CI 基线风险（独立 OPEN）

本轮直接读取 GitHub run、latest-attempt Job 和四份失败 Job 日志；收尾又查询 `zh-cn` 的最新 runs。以下为远端既有运行证据，不是本轮重新执行的后端结果。

| Run | SHA | 最终状态 | Backend / Frontend / Playwright E2E / Deployment |
| --- | --- | --- | --- |
| [C9 38211464713](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38211464713) | `dc7e6feb550224aded70c907cc26550bbff9bdac` | completed / failure | failure / success / failure / skipped |
| [C10 38244595452](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38244595452) | `788e4120128b97ccce51d8aaa5b2b13646b920a3` | completed / failure（最新） | failure / success / failure / skipped |

C9 updated_at 为 `2026-10-12T06:19:13Z`，C10 为 `2026-10-12T11:50:19Z`；没有沿用 in_progress。两次 Backend 的日志摘要均为 `Tests run: 552, Failures: 6, Errors: 13, Skipped: 1`。

| 独立风险 | 本轮重新确认的证据 |
| --- | --- |
| E2E backend 启动 | 两次均在 `Start E2E backend` 失败；`importStorageBindings` 导致 `Invalid UUID string:`；所有后续 Investment/Import 测试步骤被 skipped |
| Flyway 最终版本断言 | Backend 日志包含 `expected: "20"` / `but was: "21"` 与多个 Flyway migration 测试失败 |
| Linux 平台限制 | `java.exe` 文件不存在、`NOSHARE_DELETE not supported`、`'volume:vsn' not recognized` |
| Legacy upgrade 专用环境 | `c9.legacy.evidence must point to owned acceptance artifacts`；只读源码进一步确认需要 `c9.legacy.jar` 和 `c9.legacy.evidence` |
| Managed process / container | `TransactionImportManagedProcessPostgresIntegrationTest` 的容器启动/进程测试仍失败；完整根因本轮未确认 |

日志入口：[C9 E2E](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38211464713/job/114686360172)、[C9 Backend](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38211464713/job/114686360434)、[C10 E2E](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38244595452/job/114795224814)、[C10 Backend](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38244595452/job/114795224890)。

这些问题没有因独立 AI Job 新增而被修复或隐藏，本轮不修改 Import Storage、Flyway、旧测试或整个后端矩阵。Linux `npm ci` 另输出既有锁文件的 `6 high severity vulnerabilities` 自动摘要；未展开依赖漏洞审计或执行 npm audit fix，不将其认定为本轮新增依赖风险。

## 12. 两名独立只读复审

两名审查员均通过指定 `gpt-6-luna`、reasoning `max` 启动，职责分别为 CI 工程与浏览器覆盖，不拥有写文件职责；均未独立实跑浏览器测试。

| 审查员 | 独立检查与结论 |
| --- | --- |
| Luna Max 1 / `ci_review` | 静态审查 GO；未发现 P0/P1/P2。确认触发语义、Node/lock/Chromium、Job 独立性、失败传播、15 分钟 timeout、固定 artifact 路径/7 天保留、原 Job 保留、无越界修改；其远端 CI 状态依据主控提供证据，不冒充独立核验 |
| Luna Max 2 / `browser_review` | 代码与配置审查 GO；无 P0/P1 或 workflow 吞掉 AI 失败路径。独立 `--list` 得到 58 / 2 files，核对 6+17 / 9+49、原 Spec 无 diff、Auth/延迟响应/401/XSS/Web Locks/恢复隔离及 mock 边界。未独立实跑浏览器；指出 Deployment 并行与分支必需检查未确认的限制 |

主控逐项核对复审提出的限制：原 `deployment.needs` 为 backend/frontend，Job 仅镜像构建与 Compose config，没有实际发布动作；新增 AI 独立执行，失败使 workflow 整体失败。按“保留原 Job”授权不改其依赖；作为非阻塞限制记录。分支保护不是仓库 YAML 的可核实事实，本轮不宣称 PR 合并必然被该新检查阻止，也不修改远端规则。

## 13. P0 / P1 / P2 与未验证范围

- C11 修改范围：无已发现 P0/P1，没有门禁被绕过；两份原测试未弱化。
- 非阻塞 P2 / limitation：Deployment artifacts 可在 AI 失败时继续构建，原依赖保留；分支保护是否要求 `AI Browser E2E` 未验证。均不等于 workflow 整体成功。
- 未验证：精确新 Commit SHA 上的真实 GitHub Ubuntu AI Job、远端 artifact 上传、runner timeout/cancellation 分支、真实 Provider/Finance Tool/browser backend 路径。
- 证据采集限制：Linux 命令及 Docker 事件均 exit 0，但最终 Playwright 汇总行及纯测试时长未保留；未据此虚报一份 Linux 汇总日志。
- 未重跑：全量后端、C9 重型矩阵、现有 Investment/Import E2E 和部署 runtime；CI 历史失败作为独立风险记录，不以 AI mock 成功推断它们恢复。
- 持续 OPEN：C9 后 CI 基线红灯、C8 前端行情提示 P2、FX affected rows、C4 cause-only 遍历、C3 历史脏数据/REPLACEMENT 防御边界、Import Frontend 最终独立 Closing Re-Review、Finance G5 全量验收。

## 14. 最终工作区与下一步

最终分支与 HEAD 仍为 `zh-cn` / `788e4120128b97ccce51d8aaa5b2b13646b920a3`，收尾独立远端查询一致。tracked 修改仅为 `.github/workflows/ci.yml`、`frontend/playwright.ai.config.ts`、`docs/engineering/testing.md`；仅新增本 Review。两份受保护文件仍为原 `?? AGENTS.md`、`?? docs/codex-task.md`，不以 hash 验证它们；staged diff 为空。`git diff --check` 和文档本地链接/whitespace 检查通过。

完成本地验证后停止等待人工技术验收，不 commit/push。未来正式关闭仍须人工 GO、已验收最小修改的正式提交及精确推送、独立远端 SHA 核验、该 SHA 上 AI Job 实际 Success、两份 Spec 完整执行且无 skip/失败隐藏。届时才可将 C11 CLOSED — GO、10/11 → 11/11；其他 CI OPEN 项和 Finance Freeze 授权保持独立。
