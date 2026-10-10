# G1-1 / C5 — Tooltip XSS Remediation Review

日期：2026-10-10。范围：仅 C5；未提交、未推送、未创建 Tag。

**C5 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**

本轮消除了已确认的同账户 Tooltip HTML injection / self-XSS。等待人工 GO 后才能正式提交并关闭为 CLOSED — GO。本报告不重新验收 C1，也不代表 Finance 全量冻结。

## 1. Preflight：Branch / HEAD / 工作区

实际执行 `git branch --show-current`、`git rev-parse HEAD`、`git status --short`、`git diff --stat`、`git diff --cached --stat`。

- Branch：`zh-cn`。
- HEAD：`6183bf8c6b4950c1ad6dfa013fb1c50995882589`，与任务预期一致。
- 初始工作区：只有 `?? AGENTS.md` 和 `?? docs/codex-task.md`；tracked diff 和 staged diff 为空。
- 主控模型和思考档位没有可核验的运行时信息，因此无法确认 Sol High；未声称满足。两个子 Agent 的启动参数均为 `gpt-6-luna` / `max`。

## 2. C5 根因及准确触发路径

资产表单名称 → POST `/assets` → `AssetRequest.name`（仅 `@NotBlank`）→ `AssetService.create` 原样保存名称 → `AssetQueryService.listByUser(userId)` → `DashboardService` 将名称放入 `DashboardDto.AssetAllocation` → GET `/dashboard` → `Dashboard.tsx` → `buildAssetAllocationData`（仅添加重名编号）→ `AssetAllocationChart` HTML formatter。

修复前 formatter 将 `item.data.name` 直接拼入包含 `<br/>` 的 HTML。当前本地 ECharts 的浏览器 Tooltip 采用 HTML 渲染，`TooltipHTMLContent.setContent` 对字符串执行 `el.innerHTML = content + arrow`。因此用户名称进入了可执行 HTML 上下文。

维持已确认的 **C5 / P2** 分级。已证实的影响是同账户 self-XSS；没有证明跨账户攻击。

## 3. 实际漏洞复现证据

在生产源码尚未修改时，对预期 HEAD 运行：

```powershell
cd frontend
npx playwright test --config playwright.tooltip.config.ts --project desktop --grep 'event-handler payloads' --output test-results/c5-baseline-evidence
```

Chromium 实际鼠标移动到环形图扇区后，严格安全断言失败：`Expected: false; Received: true`。

载荷为 `<img src=x onerror="window.__financeTooltipXss=true">`。基线 DOM 证据包含真实 `<img src="x" onerror="window.__financeTooltipXss=true">`，标记为 `executed: true`，名称没有作为文本显示。

本地证据：[基线 hover JSON](../../../frontend/test-results/c5-baseline-evidence/asset-allocation-tooltip-s-5e722-yloads-and-literal-entities-desktop/hover-0-0.json)。测试响应由 Playwright 本地 fulfill；API 的非 Dashboard 路径 abort，不连接真实后端、数据库或外部 Provider。

首次测试启动曾因 fixture 路由 `**/api/**` 误拦截 `/src/api/index.ts` 而失败；仅将 fixture 收窄至 `**/api/v1/**`，没有改生产行为或安全断言。该环境/fixture 失败未计为漏洞复现。随后实际取得上述 baseline hover 证据。

## 4. 修复方案及安全机制

新增局部 `formatAssetAllocationTooltip`，复用已有 ECharts `format.encodeHTML`，在 HTML 拼接边界编码完整资产名称，包括重名后缀。`&`、`<`、`>`、双引号、单引号均得到编码；已存在的实体作为字面文本保留，不提前解码。

只编码不可信名称，不编码整个 Tooltip。可信布局 `<br/>` 与 ECharts marker 保留；marker 使用图表固定颜色，由 ECharts 生成。金额继续由 `formatCurrency(number)` 输出，占比继续由数值 `.toFixed(2)` 输出。

未过滤关键词、未删除合法字符、未限制用户输入、未新增 `innerHTML` / `dangerouslySetInnerHTML`、未新增依赖。原有饼图数据、排序、其他项合并和响应式样式均未修改。

## 5. 实际修改文件清单

| 文件 | 变更 |
| --- | --- |
| `frontend/src/components/charts/AssetAllocationChart.tsx` | formatter 委托给安全输出函数 |
| `frontend/src/components/charts/assetAllocationTooltip.ts` | HTML 上下文编码与原布局输出 |
| `frontend/tests/asset-allocation-tooltip.test.ts` | 8 个定向 Node 测试 |
| `frontend/tests/e2e/asset-allocation-tooltip.spec.ts` | 真实 hover 安全和正常功能回归 |
| `frontend/playwright.tooltip.config.ts` | 隔离 Vite、desktop/narrow 配置 |
| `frontend/package.json` | 增加 `test:e2e:tooltip` 脚本 |
| `docs/archive/review/G1-1-C5-Tooltip-XSS-Remediation-Review.md` | 本报告 |

截图、hover JSON 及窄屏基线对照脚本保存在忽略的 `frontend/test-results/` 下，属于本地验证产物，不纳入提交文件。

## 6. 恶意载荷测试结果

`node --test tests/asset-allocation-tooltip.test.ts`：**8/8 实际运行通过**。

覆盖中文、英文、HTML 标签、`& < > " '`、无害事件处理器载荷、字面 HTML 实体、重复恶意名称、空数据；验证完整名称编码、合法字符保留、金额和两行换行布局、无 marker 情况及重名编号。

## 7. 真实浏览器 hover 结果

`npm run test:e2e:tooltip -- --output test-results/c5-fixed`：**12/12 实际运行通过**，耗时输出为 26.8s，无跳过、无重试。

浏览器实际使用 `page.mouse.move` 悬停 canvas 扇区，未调用 ECharts `showTip` 代替 hover。桌面和窄屏均验证 `<img onerror>`、`<svg onload>`、HTML 标签、特殊字符及连续资产切换。每次 Tooltip 检查完整文本、金额、占比、两处换行、marker、危险节点不存在和标记保持 `false`。

修复后证据：[桌面恶意名称 hover JSON](../../../frontend/test-results/c5-fixed/asset-allocation-tooltip-s-5e722-yloads-and-literal-entities-desktop/hover-0-0.json)、[窄屏恶意名称 hover JSON](../../../frontend/test-results/c5-fixed/asset-allocation-tooltip-s-5e722-yloads-and-literal-entities-narrow/hover-0-0.json)。两者名称都是完整字面文本，`executed: false`。

## 8. 正常 Tooltip 回归结果

实际浏览器通过：图表 canvas 绘制、单资产、多资产连续切换、重复名称编号、超过四项的“其他”扇区、金额/占比、中文/英文/特殊字符、空数据、非正值空态、1280/390/2400px resize 与 2200px 字号断点。

桌面截图已检查，名称和三行结构正常。窄屏 hover 和 DOM 完整文本通过，但不能声明所有内容均位于 viewport 内：Tooltip 存在已有左边界裁切。

额外实际运行 `node test-results/c5-visual-comparison.mjs`，将 `git show HEAD:frontend/src/components/charts/AssetAllocationChart.tsx` 的精确源码通过 Vite pre-transform 在内存提供，与修复版本用相同 390×844 viewport、fixture 和真实 hover 对照；没有覆盖磁盘源码。普通中文 Tooltip 的 baseline/fixed 均为 `x=-12, width=103.125, height=76`；长合法英文均为 `x=-460, width=516.5, height=76`。文本及所有 bounds 完全一致（断言容差 0.5px），确认裁切为基线已有行为。

证据：[comparison.json](../../../frontend/test-results/c5-visual-comparison/comparison.json)。此限制非本轮新增，不扩大 C5 为 UI 定位修复。

## 9. lint、build、diff 检查

| 验证 | 本轮结果 |
| --- | --- |
| 定向 Node 测试 | 实际运行 PASS，8/8 |
| Tooltip Playwright | 实际运行 PASS，12/12 |
| 窄屏原始组件/修复组件对照 | 实际运行 PASS，文本和位置相同 |
| `npm run lint` | 实际运行 PASS，独立命令 exit 0 |
| `npm run build` | 实际编译/构建 PASS，exit 0 |
| `git diff --check` | PASS |
| 新增文件 `git diff --no-index --check` | PASS，无 whitespace 错误 |
| staged diff | 为空 |

build 有大于 500 kB 的 chunk 提示，非阻断；未借本任务做拆包优化。后端未修改，本轮未运行 Maven 全量测试；没有将历史 C1 测试或全量回归算入上述结果。

## 10. 两个 Luna Max 的独立复审

- **安全审查 Agent**：只读核对源码闭环、ECharts sink、最终修复及相邻动态字段；未发现 C5 残留阻断或同源绕过。重名后缀整体编码，`originalName` 不进入 Tooltip，图形 label 和 React 排名文本没有绕过。保留 C5/P2 与同账户 self-XSS 边界。该 Agent 未重跑主控测试。
- **测试与回归 Agent**：只读核对测试断言、修复前证据、修复后 hover JSON 和截图；修复后名称、金额、占比全部匹配，标记均为 false，没有危险 HTML 节点；确认测试产物状态为 passed。额外独立核对窄屏对照脚本与 JSON，重新计算 HEAD 源码 hash，确认相符；两组 baseline/fixed 截图各自 SHA-256 相同，裁切已存在于 HEAD。指出不应将 DOM 完整等同于 viewport 完整可见。

相邻 `MonthlyCashFlowChart` 名称来自固定文案；`MonthlyTrendChart` 月份来自后端生成的 `YearMonth`，seriesName 来自代码常量。没有发现独立的其他可利用 XSS 路径，未修改这些组件。

## 11. 新增 P0 / P1 / P2 findings

未发现新增 P0/P1/P2 安全或 C5 阻断 finding。记录一个非阻断的既有 UI limitation：窄屏 Tooltip 默认定位可能使内容越过左边界；修复前后对照一致，留待后续单独决定。

## 12. 是否涉及范围外代码

否。没有修改 C1、C7、C4、C6、C2–C3、C8–C11、后端金融规则、数据库、Migration、AI Provider 或其他项目仓库。未执行全量冻结。

## 13. 受保护文件状态

`AGENTS.md` 和 `docs/codex-task.md` 始终保持未跟踪。主控与两个子 Agent 未读取、修改、删除、暂存或提交这两个文件；仅通过 Git status 核对状态，没有读取内容作 hash 校验。

## 14. 最终 C5 GO / NO-GO

**C5 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**。

根因和当前基线漏洞均已核实；修复后真实 hover 无执行、完整合法文本保留，定向测试、工程检查和独立安全审查通过。窄屏已有裁切单独记录，未出现本轮新增定位退化。等待人工复审和 GO；目前未正式 CLOSED — GO，未 commit / push / Tag。
