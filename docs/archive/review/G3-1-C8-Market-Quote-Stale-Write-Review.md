# G3-1 / C8 — Market Quote Stale Write 验收报告

日期：2026-10-11（Asia/Shanghai）。范围仅为 C8；不进入 C9，不执行 commit、push、tag 或 Finance Freeze。

## 1. 结论

**C8 — IMPLEMENTED / ACCEPTANCE GO CANDIDATE**

主控实现、47 项定向验证及两名 Luna Max 独立只读复审已完成。未发现剩余 P0/P1，记录一项非阻断 P2；等待人工复审，不代表已获得最终发布或阶段关闭授权。

数据库防倒退条件保持原样。被拒绝的旧候选不再自动进入持久化失败路径；只有同事务回读到同 key、严格更新且可用的权威行，才能按缓存新鲜度返回已有刷新结果。成功写入也回读数据库，避免响应包含未经持久化的候选精度或价格。

## 2. Preflight 与保护边界

本轮实际执行：

```text
git branch --show-current                         => zh-cn
git rev-parse HEAD                                => 75f5e1e1bd93ea00ea5e905253577ea0045935a7
git rev-parse origin/zh-cn                         => 75f5e1e1bd93ea00ea5e905253577ea0045935a7
git ls-remote origin refs/heads/zh-cn              => 75f5e1e1bd93ea00ea5e905253577ea0045935a7
git diff --stat / git diff --cached --stat         => 空
git status --short                                => 两个正常受保护 untracked 文件
```

受保护文件为 `AGENTS.md`、`docs/codex-task.md`。主控及两名复审均被明确要求不得读取内容、计算内容 hash、修改、删除、暂存或提交。主控只使用用户聊天中提供的规则，没有打开这两个文件。结束时路径级状态仍均为 `??`；不以内容 hash 验证保护状态，两名复审也分别确认未读取或修改。

启动前没有其他工作区修改；没有 fetch、checkout、reset、stash、clean、rebase、amend、创建分支或改写历史。

## 3. 原始读取、刷新与写入链路

源码核对结果：

- Asset 普通 GET：`AssetController → AssetService → MarketQuoteQueryService`，读取缓存并组合只读 reference valuation，不调用 Provider。
- 显式 POST `/api/v1/assets/{id}/quote/refresh`：`AssetController → MarketQuoteService.refresh`，先校验 Asset 归属及类型、market、symbol；随后检查 TTL、single-flight 和额度。
- Provider 候选：`MarketDataProvider.fetchQuote → validateProviderQuote → toEntity`。
- 写入：`MarketQuotePersistenceService.upsert` 经 Spring 事务代理调用 `MarketQuoteMapper.upsertLatest → upsertLatestInternal`。
- 原 Mapper 使用 `ON CONFLICT (market, symbol) DO UPDATE ... WHERE EXCLUDED.quote_time >= market_quotes.quote_time`；相同时间允许更新，真正旧时间跳过更新。
- 原 Persistence 对所有非 1 的影响行数抛出 `IllegalStateException("Could not persist market quote")`。
- 原刷新成功路径用 Provider 转换得到的候选 entity 直接构造 `UPDATED/FRESH`；没有回读持久化行。
- 原公开枚举只有 `UPDATED / CACHE_HIT / STALE_FALLBACK`；本轮没有扩展 DTO、HTTP 接口或前端合同。

历史 v2.0 / v2.1 文档描述了 TTL、single-flight、额度、fallback 和参考数据与账务分离。历史引用的 `MarketQuoteMapperPostgresIntegrationTest`、`MarketQuoteRefreshApiIntegrationTest`、`ReferenceValuationServiceTest` 等类在当前源码中不存在，不能视为本轮已执行。现存 Investment 读取集成测试和本轮新增类见下文。

## 4. 两种时间的语义

`quoteTime` 是行情本身的时间，是 PostgreSQL 防倒退比较依据。`fetchedAt` 是系统记录该报价的获取时间，是 TTL 比较依据。数据库已存在较新的 quoteTime，并不表示 fetchedAt 仍在 TTL 内。

TTL 原逻辑未变：`clock.instant().isAfter(fetchedAt + cacheTtl)` 才是 STALE，恰好位于期限边界仍为 FRESH。被拒绝候选的 fetchedAt 不能写回、续期或替代权威行。

## 5. 环境、首次 fixture 错误与原实现红灯

环境：Java 21.0.10、Docker Desktop Server 29.6.1、Testcontainers 1.20.0、隔离镜像 `postgres:17-alpine`；Flyway 日志确认运行的是 PostgreSQL 17.11。没有访问日常数据库或真实付费 Provider。

所有日志保留在 `backend/target/`，未覆盖或删除。该目录受 Git ignore 规则影响，日志是本机原始证据，不会随报告自动进入 Git；关键红绿证据同时摘录在本报告。

| 阶段 | 本机原始日志 | 实际结果 |
| --- | --- | --- |
| 首次环境启动 | `c8-old-behavior.log` | Maven Wrapper 因进程未设置 JAVA_HOME 提前退出，没有执行测试 |
| 首次测试 fixture | `c8-red.log` | MyBatis 内部默认方法调用绕过 spy；错误观察内部入口使影响行数列表为空。Controller 已出现预期 500，但这轮不能当作完整业务复现 |
| fixture 修正后的旧行为 | `c8-red-corrected.log` | 真实 PostgreSQL class 级 6 次测试执行、5 次失败、0 error、0 skipped；Mapper 正常行为通过，其余失败由原生产缺陷触发 |
| 首轮最小修复 | `c8-green-initial.log` | 10 项通过，0 failure/error/skipped |
| 完整定向回归 | `c8-regression.log` | 47 项通过，0 failure/error/skipped |
| 独立 compile 命令 | `c8-compile.log` | BUILD SUCCESS；已由前述 test 命令编译，独立 compile 显示 classes up to date |

JAVA_HOME 使用本机已有 `F:\ideajava\jdk21`，没有安装软件或修改永久环境配置。首次 fixture 修正只将 spy 观测点移到 `upsertLatest` 公开入口，并让 Controller 的时间 fixture 使用 PostgreSQL 微秒精度；没有削弱生产行为断言。

旧行为核心输出：

```text
C8 separate instances: affected=[1, 0]; final=T2/200/NOW/NEWER;
older failure=java.util.concurrent.ExecutionException: java.lang.IllegalStateException: Could not persist market quote
（三轮均如此）

C8 stale candidate: database retained T2/200/NEWER;
service failure=java.lang.IllegalStateException: Could not persist market quote

controllerReturnsStoredStaleQuoteInsteadOf500:
Status expected:<200> but was:<500>

Tests run: 6, Failures: 5, Errors: 0, Skipped: 0
```

注意：旧红灯日志 Maven 最终 Results 按重复方法分组显示 `4 / 3`，而测试类报告逐次计算重复执行显示 `6 / 5`。上表采用测试类的实际执行次数；不混用两种口径。

## 6. 影响行数 0 与并发证据

确定性数据：`NOW=2026-10-11T02:00:00Z`，`T1=00:00:00Z`，`T2=01:00:00Z`；旧候选 `100/OLDER`，新行情 `200/NEWER`。

过期缓存场景：数据库已保存 T2/200，但 fetchedAt 为 NOW−1800 秒，超过 15 分钟 TTL。Provider 成功返回 T1/100。PostgreSQL 因比较条件不满足而返回 0；quoteTime、price、fetchedAt、provider 均保持原权威值。

竞争场景使用两个通过独立构造得到的 `MarketQuoteService` 实例，每个实例有自己的 single-flight map、Provider 和 limiter，共享真实 Spring 事务代理 Persistence 和真实 Mapper。线程池启动旧路径并在 Provider latch 等待；第二线程完成新路径，Future 返回证明持久化事务已提交；之后才释放旧路径去执行冲突 UPSERT。测试记录实际 SQL 返回值 `[1, 0]`，同时核验最终数据库行及两个服务响应。

这不是同实例 single-flight 合并，也不是 mock Mapper 返回影响行数。`@SpyBean` 仅记录真实 Mapper 的结果，不替换 SQL。latch 等待和 Future 均有超时；finally 释放 latch、关闭线程池并等待终止，没有 sleep 控制时序。

PostgreSQL 文档说明，冲突更新的 WHERE 条件不满足时不更新该行，但仍取得冲突行锁；命令 count 仅计实际插入或更新的行。因此 0 是保护性跳过的合法结果，但单靠 0 无法证明存在可用权威行。[PostgreSQL 17 INSERT](https://www.postgresql.org/docs/17/sql-insert.html)

## 7. 最小设计与响应合同

Persistence 返回内部 `UpsertResult(quote, written)`：

1. 影响行数只能是 0 或 1，其余仍失败。
2. 在原 `@Transactional` 方法内调用 UPSERT，再回读同 key 权威行。UPSERT 取得的行锁直到事务结束才释放，避免中间更新或删除；READ COMMITTED 下一语句可看到已提交竞争结果，MyBatis 写语句清理本地查询缓存。
3. 核验 market、symbol、quoteTime、fetchedAt、正价格、非空 currency/provider。
4. 0 行必须回读到严格晚于候选 quoteTime 的权威行情；不存在、不同 key、相同/更旧时间或不可用权威行均失败。
5. 1 行也返回真实数据库行，并拒绝回读到较旧时间；时间下界考虑 PostgreSQL 微秒精度，响应使用数据库实际精度。
6. 不吞数据库异常，不把真实数据库失败改成 Provider fallback，不改变原异常 HTTP 映射。

| 实际结果 | HTTP / DTO | 理由 |
| --- | --- | --- |
| 实际插入、相同时间更新、真正较新行情更新 | 200 / UPDATED；freshness 按权威 fetchedAt 计算 | 已实际写入，响应来自持久化回读 |
| 旧候选被拒绝，权威行 FRESH | 200 / CACHE_HIT / FRESH | 返回仍有效的缓存，未声称候选已写入 |
| 旧候选被拒绝，权威行 STALE | 200 / STALE_FALLBACK / STALE | 刷新未推进快照，展示持久化的较新旧快照 |
| 0 行却无法核验更新的权威行 | 沿用持久化异常失败；无成功 DTO | fail-closed，不能伪造成功 |
| Provider/额度失败，有旧缓存 | 原有 200 / STALE_FALLBACK | 原异常处理保持 |
| Provider 失败，无缓存 | 原有 404 / 429 / 502 / 503 | 原错误映射保持 |

保护性跳过的 warning 为 `Provider returned an older quote; the most recently persisted reference quote is shown.`，与 Provider 失败的原 warning 区分。既有枚举可表达上述结果，无需额外 Design Checkpoint 授权或公开 API 扩展。FRESH 的 CACHE_HIT 可能已消费 Provider 额度，因为这是竞争后命中权威缓存，不是调用前的 TTL 命中；没有退还已发生请求的额度。

兼容性：DTO 字段、枚举和 HTTP 成功结构未变。前端已有 STALE_FALLBACK 固定说明可继续使用。CACHE_HIT 在保护性跳过时附带 warning；现有前端不会展示其更细分原因，这是非阻塞展示限制。

## 8. 实际修改文件

| 文件 | 改动 |
| --- | --- |
| `backend/src/main/java/com/financeos/module/asset/marketdata/service/MarketQuotePersistenceService.java` | 内部结果、同事务回读与核验 |
| `backend/src/main/java/com/financeos/module/asset/marketdata/service/MarketQuoteService.java` | 使用权威行构建响应，按写入及 freshness 选择现有结果 |
| `backend/src/test/java/com/financeos/integration/MarketQuoteStaleWritePostgresIntegrationTest.java` | 真实 PostgreSQL / API / 竞争 / 参考估值及事实不变验证 |
| `backend/src/test/java/com/financeos/module/asset/marketdata/service/MarketQuotePersistenceServiceTest.java` | 任意 0 行不能成功、回读失败、异常和写入结果单元验证 |
| `backend/src/test/java/com/financeos/module/asset/marketdata/service/MarketQuoteServiceTest.java` | TTL、额度、single-flight、输入校验及失败清理回归 |
| `docs/archive/review/G3-1-C8-Market-Quote-Stale-Write-Review.md` | 本报告 |

没有修改 Mapper、Flyway、Provider 协议、DTO、前端、Asset/Account/账本代码或 FX Service。

## 9. 真实 PostgreSQL 验收矩阵

| 要求 | 本轮实际证据 |
| --- | --- |
| 首次插入、新行情更新、相同时间更新 | Mapper 及真实 Controller 的插入/相等/较新更新测试通过 |
| 旧行情拒绝，权威值不变 | stale 场景及三轮竞争核验 price、quoteTime、fetchedAt、provider |
| 旧行情拒绝后不再 500 | `controllerReturnsStoredStaleQuoteInsteadOf500` 从原 500 转 200 |
| 响应与持久化值一致 | stale/竞争逐字段核验；正常写入 Controller 对 NUMERIC 舍入后的价格及微秒 fetchedAt 核验 |
| 不同 market/symbol 无交叉污染 | `US/C8`、`CN/C8`、`US/OTHER` 独立行验证 |
| 跨实例乱序及竞争后权威回读 | 两个 Service 实例、两个任务、提交 latch、真实影响行数，三轮全部通过 |
| 无权访问 Asset | Controller 返回 404，cache/limiter/Provider 均无调用 |
| Provider 失败 STALE_FALLBACK | 真实 API 测试保持缓存不变 |
| 无缓存错误映射 | 10 个参数化 Provider 错误场景覆盖 404/429/502/503；本地额度拒绝另验证 429 |
| TTL / CACHE_HIT / 额度 | 精确 TTL 边界，过界 1ns 刷新；每用户/全局上限及下一分钟复位；跳过候选仍消耗额度 |
| 同实例 single-flight | latch 控制 leader；观察 follower 实际处于 CompletableFuture.join 后释放；共享同一结果且只调用一次 Provider/写入/额度 |
| 财务事实及人工估值不变 | seeded Account、普通流水、通过真实投资 POST 创建的 BUY 事实及资产投影；刷新前后完整 JSON 状态相等 |
| 普通 GET 只读 | detail/list/page 三条真实 API 路径均不调用 Quote/FX Provider |

没有删除测试、禁用用例、弱化关键断言、修改 expected 迎合旧错误或吞掉异常。

## 10. Reference Valuation 与 Investment 回归

新增真实 Controller → Service → PostgreSQL reference valuation 测试：数据库保存 T2/200 USD，Provider 返回旧 T1/100；数量 2、缓存 FX=7，响应仍为 quotePrice=200、quoteProvider=NEWER、quoteTime=T2、quoteFreshness=STALE、CNY reference value=2800。Quote fetchedAt 不变，普通 GET 不触发 Provider。该测试真实创建了 BUY 事实，断言投资事实非空；核对 Account、普通流水、所有 Asset、InvestmentTransaction 和 InvestmentInstrument 的刷新前后数据一致。

现存 `InvestmentReadQueryPostgresIntegrationTest` 全类 17 个用例本轮实际通过，涵盖 cached reference valuation、缺失/过期 Quote/FX、读取快照及用户隔离。该类原有 MockBean 刷新 Service/Provider，只验证 Investment 的缓存读取行为；不将其称作新的真实行情刷新证明。

## 11. 实际定向执行清单

在 `F:\IDEA\daima\finance-os\backend` 执行：

```powershell
$env:JAVA_HOME = 'F:\ideajava\jdk21'
.\mvnw.cmd -B '-Dtest=MarketQuoteStaleWritePostgresIntegrationTest,MarketQuotePersistenceServiceTest,MarketQuoteServiceTest,InvestmentReadQueryPostgresIntegrationTest' test
.\mvnw.cmd -B -DskipTests compile
```

| 实际测试类 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| `MarketQuoteStaleWritePostgresIntegrationTest` | 20 | 0 | 0 | 0 |
| `MarketQuotePersistenceServiceTest` | 4 | 0 | 0 | 0 |
| `MarketQuoteServiceTest` | 6 | 0 | 0 | 0 |
| `InvestmentReadQueryPostgresIntegrationTest` | 17 | 0 | 0 | 0 |
| 合计 | 47 | 0 | 0 | 0 |

以上来自本轮 Maven 日志和 Surefire XML，不采用历史验收数字。

核心竞争重复统计：旧行为红灯 3/3 确认缺陷；首修 3/3 通过；完整定向回归 3/3 通过。正式完整回归统计为 3 轮，不把多次构建累计轮数算成新增独立测试项。

## 12. 独立复审

用户指定两名 `GPT-6 Luna Max`，实际启动工具配置为 `gpt-6-luna` / `reasoning_effort=max`，均只读，未分配实现文件编辑权限。

- 复审 1（`c8_database_review`）：静态核查数据库防倒退、0 行、事务可见性、跨实例竞争和权威状态；没有 P0/P1 或需阻断的 P2，给出数据库部分 GO 建议。确认当前 Controller / ReferenceValuation 调用路径无外围事务或隔离级别覆盖；读取本轮 corrected 红灯和首修绿灯，47 项最终回归及 compile 采用主控提供摘要，没有自行读取最终回归日志。
- 复审 2（`c8_api_test_review`）：静态核查响应、异常分流、TTL、额度、single-flight 及测试可靠性，独立读取最终回归与 compile 日志；没有 P0/P1，发现一项前端 warning 消费 P2，建议 ACCEPTANCE GO CANDIDATE。

二者未独立运行 Maven；证据来源是独立静态审查与主控本轮日志。不能描述为两名复审各自实跑测试。

## 13. Findings、未验证范围与遗留风险

- 原 C8 是阻断性缺陷：保护性跳过误报持久化失败，以及候选直接作为 UPDATED 响应；本轮已复现并修复。
- 剩余 P0：0；P1：0。原 C8 由真实红灯和同路径绿灯确认修复。
- P2（非阻断，复审 2 提出、主控已核查）：`frontend/src/utils/marketQuote.ts` 的 `marketQuoteRefreshWarning` 只检查 STALE_FALLBACK，忽略服务端 warning；`frontend/src/pages/Assets.tsx` 的 `refreshQuote` 因此显示固定“刷新失败”或“参考行情已更新”，不能呈现 Provider 成功但旧候选被拒绝的细分原因。DTO 已如实区分原因且数值正确，本轮未获授权更改前端合同，按收敛原则记录后续项，不扩大修复。
- FX 待办：`ExchangeRatePersistenceService` 同样对 `upsertLatest != 1` 抛异常，`ExchangeRateService` 有自己的失败 fallback 路径。存在相似风险的静态迹象；本轮未真实复现、未定级、未修改，需单独任务核验。
- single-flight、额度仍是进程内机制；本轮没有实现跨实例限流或合并。数据库负责跨实例防倒退。
- 响应是本次持久化事务确认的权威快照；事务提交后其他请求仍可能写入更新数据，不能承诺 HTTP 到达时永久等于数据库最新状态。
- 未验证：全量 Maven、前端 test/lint/build、E2E、真实付费 Quote/FX Provider、长期压力测试；按授权留给 G5 或独立 smoke。
- 金融账本、账户和其他 C 项没有生产改动；未执行架构调整或新增基础设施。

## 14. 最终检查与人工复审

本轮 `git diff --check` 通过；新增测试和报告的独立空白及冲突标记扫描通过。实际 production diff 仅涉及上述两个 Service；staged diff 为空。最终 `git status --short --untracked-files=all` 包含两个 Service 修改、三个新增测试、本报告及两个原有受保护 untracked 文件，没有混入其他改动。分支、本地 HEAD、origin/zh-cn 与独立远端在结束前复查仍均为指定 SHA。

原始环境失败、fixture 失败、业务红灯、首修绿灯、最终回归与 compile 日志均保留在上述六个独立文件；没有使用 Maven clean 删除这些证据。

报告完成后暂停，等待人工复审；不提交、不推送、不创建 Tag、不进入 C9。
