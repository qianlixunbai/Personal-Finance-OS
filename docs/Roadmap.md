# Roadmap（项目发展路线图）

> **v2.1 Market Valuation：已完成并正式关闭。** Assets displays manual valuation beside a read-only, non-persistent market reference valuation. The UI consumes backend values without financial recomputation, maps freshness/warnings safely, and supports a per-asset manual refresh. Dashboard remains unchanged; real FX providers remain disabled by default.

**项目名称：** Personal Finance OS

**当前阶段：** v3.0 Investment Transaction Model — Phase 1 Investment Ledger Foundation（已实施，待独立验收）。

本文档用于记录 Personal Finance OS 的阶段性路线图。文档必须明确区分“已完成”“当前阶段”和“后续规划”，不得把未来能力写成当前已实现能力。

------

# 一、项目定位

Personal Finance OS 是面向求职作品集展示的工程化个人财务管理系统。

项目重点不是一次性堆满功能，而是展示：

- 模块化单体架构；
- 清晰的业务边界；
- 后端金融计算和数据一致性意识；
- 前后端真实 API 集成；
- 测试验证；
- 文档同步和阶段性 Review。

------

# 二、版本原则

- 每个阶段必须稳定可运行；
- 新能力不得破坏已有主链路；
- 文档必须同步当前实现；
- 未实现能力只能出现在后续规划中；
- 每次迭代聚焦一个明确目标。

------

# 三、版本规划

## v1.0 Foundation（已完成）

### 定位

基础平台阶段，目标是完成个人财务管理系统的最小可运行工程闭环。

### 已完成能力

- 用户注册 / 登录；
- JWT 鉴权；
- 用户状态校验；
- 账户基础管理；
- 分类基础能力；
- 默认系统分类启动初始化；
- 资产持仓快照；
- Transaction / Ledger 基础 CRUD；
- `INCOME` / `EXPENSE` / `ADJUSTMENT` 余额联动；
- Dashboard 聚合账户、资产、月度收支和最近流水；
- Account / Asset / Transaction 分页接口；
- `ApiResponse<T>` 统一响应；
- `PageResult<T>` 分页响应；
- 参数异常、业务异常、Security `401` / `403` 统一响应；
- 根 README、docs 导航、Architecture、Database、API、Business Rules、Financial Rules、Review 文档同步。

### 阶段结论

`v1.0 Foundation` 已完成阶段验收，可作为作品集基础版本展示。

------

## v1.1 Showcase Enhancement（已完成）

### 定位

作品集展示增强阶段，目标是提高页面观感、演示完整度和文档一致性，不扩大为复杂业务模型。

### 已完成能力

- 登录 / 注册展示后端错误 `message`；
- Transactions 类型中文化；
- Transactions / Dashboard 金额格式统一；
- Accounts 接入编辑入口；
- Assets 接入详情入口；
- Assets 接入删除入口；
- Assets 接入清仓入口；
- 前端统一空状态和反馈提示组件；
- README / Review 收尾；
- `API.md` 同步账户、资产、流水、Dashboard 当前实现；
- `Business Rules.md` / `Financial Rules.md` 说明资产清仓边界。

### 边界说明

资产清仓只是资产持仓快照归零，不修改现金账户余额，不生成流水，不计算实现盈亏，不等于完整卖出交易模型。

### 阶段结论

`v1.1 Showcase Enhancement` 已完成阶段验收，可作为作品集展示增强版本展示。

------

## v1.2 Visualization Polish（已完成）

### 定位

可视化增强阶段，目标是在不改变核心业务模型的前提下，提高 Dashboard 和资产 / 收支数据的展示表达。

### Phase 1

- 投资资产分布环形图；
- 本月收入 / 支出柱状图；
- Dashboard 图表区域局部响应式布局。

### Phase 2（代码与人工验收已完成）

- 最近 6 个月收入 / 支出 / 结余趋势图；
- Dashboard 后端月度 CNY 收支聚合、连续自然月补零和 `Asia/Shanghai` 时间边界；
- Dashboard 趋势折线图和局部响应式展示。

### 阶段结论

`v1.2 Visualization Polish` 已完成代码验收、独立复审、PostgreSQL 人工验收和浏览器人工验收，可以关闭。项目进入 `v1.3 Engineering Polish`。

### 边界说明

v1.2 不包含行情、历史价格、汇率、完整投资交易模型或 AI。v1.2 不默认包含行情数据、不默认包含完整投资交易模型、不默认包含 AI 财务分析。

------

## v1.3 Engineering Polish（已完成）

### 定位

工程化增强阶段，目标是在业务主链路稳定后，补齐测试、接口契约和数据库演进规范。

### 已完成事项

- PostgreSQL Testcontainers 集成测试基础设施已完成，并以真实 PostgreSQL 验证 Dashboard 月度 CNY 收支趋势 Mapper；运行该测试需要 Docker。
- GitHub Actions CI 已完成远端验证。
- 推送到 `zh-cn`、目标为 `zh-cn` 的 Pull Request，以及手动触发都会执行 Backend 和 Frontend 检查。
- Flyway Phase 1 已完成：主应用和测试 profile 已移除 Spring SQL Init，`schema.sql` 已删除，当前唯一数据库结构来源为 `backend/src/main/resources/db/migration/V1__baseline.sql`。
- Flyway 10.20.0 已在空 PostgreSQL 17 Testcontainers 中验证；Flyway 会创建 `flyway_schema_history` 并执行 V1，V1 包含当前 6 张业务表。
- 当前未配置 `baseline-on-migrate`；旧开发数据库的受控 baseline 或重建仍需单独决策，本项目不会自动处理。
- Controller / API 测试里程碑已完成：六个 Controller 的核心 HTTP 契约均由 MockMvc slice 测试覆盖，并使用真实 Security 配置。
- 真实 API 集成测试使用真实 JWT 和 PostgreSQL Testcontainers，覆盖注册和登录、禁用用户旧 token 返回 401、账户/资产/分类/流水用户隔离、流水创建/更新/删除余额联动，以及分页和组合筛选。
- 后端当前 108 项测试通过；前端当前 9 项测试通过，并通过 lint 和 build。
- OpenAPI 3 与 Swagger UI 已接入，使用 `bearerAuth` JWT 安全方案；六个 Controller 的 24 个接口已生成运行时 API 文档。
- 注册和登录保持公开，其余业务接口在运行时文档中标记 JWT 安全要求；`OpenApiIntegrationTest` 已验证 OpenAPI JSON、Swagger UI、标签和安全声明。
- 前端工程化已完成：共享 `PageResult<T>`、`PageHeader`、受控 `Pagination`、共享页面 / 面板 / 表格样式，Accounts、Assets、Transactions 已完成复用。
- 分页业务逻辑、筛选和删除后的页码回退仍保留在页面内；Pagination 按钮显式使用 `type="button"`，并有对应回归测试。

### 阶段结论

v1.3 的代码、测试、CI、数据库迁移、接口文档和前端工程化均已完成；Closing Review 未发现 P0/P1，非阻塞 Known Gaps 已记录。v1.3 可以正式关闭，下一阶段尚未自动启动。

### 边界说明

v1.3 不默认包含行情数据、不默认包含资产历史价格业务接入、不默认包含汇率、完整投资交易模型、AI 财务分析或生产级部署体系。

------

## v1.4 Quality Hardening（已完成）

### 定位

质量与可验证性加固阶段，目标是在 v1.3 工程化基线上补充 Dashboard 用户隔离、无效 JWT、数据源可配置性和 CI runtime 兼容性验证，并关闭此前 Closing Review 中确认的 P1；不扩大业务模型范围。

### 已完成事项

- Dashboard 真实 API 集成测试覆盖两个用户及其账户、资产、分类、流水数据，验证当前用户只能看到自己的聚合结果；
- 初始 invalid JWT 集成测试覆盖过期、篡改和格式错误 token 的统一 `401` 响应；
- `InvalidJwtApiIntegrationTest` 使用真实注册 / 登录流程创建存在且为 `ACTIVE` 的测试用户；
- 过期 JWT 使用真实用户 subject 构造；
- 篡改 JWT 基于真实登录 token 变更签名；
- 增加有效 JWT 正向控制，并验证无效 JWT 不会调用 Dashboard 业务服务；
- 过期、篡改、格式错误 JWT 均验证统一 `401` 响应及无敏感详情泄露；
- `DB_URL` 已支持通过环境变量覆盖完整 PostgreSQL JDBC URL，并保留本地默认地址；`DataSourceConfigurationTest` 已覆盖默认值和覆盖值；
- GitHub Actions 已将 `actions/setup-java` 更新至 v5、`actions/setup-node` 更新至 v6，以消除已弃用 runtime；
- 后续 P1 加固已完成：invalid JWT 测试不再使用不存在的用户 subject，消除过期和篡改场景的假通过路径；
- 目标测试类共 4 项测试通过，Failures 0、Errors 0、Skipped 0；
- 完整后端测试套件共 115 项测试通过，Failures 0、Errors 0、Skipped 0；
- 相关 Closing Review 已记录 P1 关闭判断和范围边界。

### 阶段结论

`v1.4 Quality Hardening` 的唯一 P1 已关闭，阶段可以正式收口；下一阶段尚未自动启动。

### 边界说明

v1.4 不包含新的 JWT 算法或认证架构、数据库结构和 migration、业务功能、行情、汇率、AI 或完整投资交易模型。CI runtime 更新和 `DB_URL` 配置属于本阶段已完成的工程化收口范围。

------

## v1.5 Deployment Readiness（已完成）

### 定位

在不扩大业务模型、不修改数据库结构的前提下，补齐单机容器化部署的最小可信链路：镜像构建、生产运行配置、健康检查、Nginx 同源代理、Docker Compose 和 CI 部署产物验证。

### 当前范围

- Spring Boot Actuator health、liveness/readiness 和 graceful shutdown；
- 后端与前端多阶段 Dockerfile；
- PostgreSQL、backend、frontend 的 Docker Compose 本地部署；
- Nginx SPA fallback 与同源 `/api` 代理；
- Secret 模板、部署说明与 smoke checklist；
- CI 镜像构建和 Compose 配置解析，不包含镜像推送或自动部署。

### 当前状态

- 后端与前端 Docker 镜像、Docker Compose、健康检查、Nginx 同源代理、named volume 和本地 smoke 已完成验证；
- GitHub Actions [run #29580604608](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/29580604608) 已通过 Backend、Frontend 与 Deployment artifacts；
- v1.5 已正式关闭，可进入 v2.0 Market Data。

### 边界说明

v1.5 不包含 Kubernetes、微服务、Redis、MQ、云厂商、HTTPS、Registry push、自动 CD、备份、高可用、外部监控、不同 Origin CORS、Market Data 或新的 migration。

------

## v2.0 Market Data Foundation（已完成）

### 定位

行情数据阶段已提供可追溯的独立参考行情快照，不替代用户确认的 CNY 资产估值。

### 已完成范围

- 独立最新行情快照与 Twelve Data provider adapter；
- 仅支持 US `STOCK` / `ETF`；
- 手动单 Asset 刷新与 15 分钟 TTL；
- `CACHE_HIT` / `UPDATED` / `STALE_FALLBACK`；
- single-flight 和单进程额度保护；
- Asset GET 缓存行情附加与 Assets 页面参考行情展示；
- 外部行情不参与 CNY 资产或 Dashboard 估值。

### 边界说明

不包含历史价格、Dashboard 行情估值、自动刷新、多币种估值、汇率、盘前盘后、OHLC 或涨跌幅。

v2.1 Market Valuation 已完成四个阶段并正式关闭，详见 [V2.1 Closing Review](./review/V2.1-Closing-Review.md)。后续阶段为 v3.0 Investment Transaction Model — Phase 1 Investment Ledger Foundation；v2.2 History / Charts / Scheduled Refresh 仍为候选规划。

------

## v2.1 Phase 1 FX Foundation（已完成）

### 已完成范围

- `V3__exchange_rates.sql` 新增公共最新成功汇率快照；
- `NUMERIC(24,12)` 汇率精度、货币对唯一键、正数汇率和不同币种约束；
- `ExchangeRate` Entity、原子最新快照 upsert 与批量货币对查询；
- Provider abstraction 与 `fx-data.*` 配置绑定，默认关闭；
- PostgreSQL 17 Testcontainers migration / Mapper 测试；
- ADR-006 已接受。

### 边界说明

Phase 1 不包含真实 FX Provider、网络请求、Controller/API、Reference Valuation、Dashboard、前端、外币 Account 或外币 Transaction。上述内容已在 v2.1 后续阶段按范围完成；v2.1 已正式关闭。

## v2.1 Phase 2 FX Refresh Workflow（已完成）

- FX 缓存以 `fetched_at` 和 60 分钟 TTL 判断 FRESH/STALE/NEVER_FETCHED；
- 内部刷新支持 CACHE_HIT、UPDATED、STALE_FALLBACK、单货币对 single-flight 与单进程用户/全局额度保护；
- 默认关闭，真实 FX Provider 不作为 v2.1 的默认能力；Phase 2 本身无公开 API、无 Reference Valuation、无 Dashboard 或前端改动。Phase 3 Reference Valuation Backend 与 Phase 4 Assets UI 均已完成。

------

## v2.1 Phase 3 Reference Valuation Backend（已完成）

- Reference Valuation Backend 已完成；
- Quote、FX 和 CNY 参考估值组合已完成；
- 普通 GET 只读缓存，不调用 Provider；
- 显式刷新接口已完成；
- `FRESH`、`STALE`、`PARTIAL`、`UNAVAILABLE` 状态已完成；
- 结构化 warning 和 list/page 批量查询已完成；
- 不修改人工估值和 Dashboard；
- Phase 3 P1 修复已完成。

## v2.1 Phase 4 Assets UI（已完成）

- Assets UI 已完成；
- 人工估值与市场参考估值已分区展示；
- Quote、原生市值、FX 和 CNY 参考值已展示；
- 已支持单 Asset 手动刷新；
- warning 已安全映射；
- 前端不重新计算金融金额；
- 刷新失败时保留旧数据；
- 已通过 Sol 只读验收，无 P0/P1。

------

## v2.1 Market Valuation 最终状态（已完成并正式关闭）

### 最终边界

- Account 和普通 Transaction 仍只支持 CNY；
- Asset 人工 `currentPrice`、`marketValue` 仍是账务数据；
- Market Quote 和 FX 是外部参考输入；
- Reference Valuation 是只读、非持久化派生结果；
- 普通 GET 不调用 Provider，只有显式 POST 才可能刷新 Quote 或 FX；
- Provider 失败不会修改账务真值；Dashboard 未接入市场参考估值；真实 FX Provider 默认关闭；
- v2.1 不包含历史行情、定时刷新、自动批量刷新或完整投资交易模型。

### 已知 P2

stale fallback 刷新成功时，详情会显示旧数据 warning，但全局成功提示仍为“市场参考估值已更新”。该问题不阻塞 v2.1 关闭。

------

## v3.0 Investment Transaction Model — Phase 1 Investment Ledger Foundation（P1 已定向修复，仍待独立复验）

### 定位

已完成领域、规则、纯计算/replay 与持久化基础，目标是从“资产持仓快照”演进到可表达交易、成本和实现收益的投资管理模型。最终关闭结论留给独立只读验收。

### 已实施范围

- ADR-007 与 Financial Rules / Business Rules 冻结；
- BigDecimal 投资计算与 replay 内核；
- additive V4 migration、V5 约束加固、InvestmentTransaction Entity / Mapper；
- Asset 兼容投影字段、PostgreSQL 17 Testcontainers migration / Mapper 约束测试；
- 没有 Controller、公开 API、真实交易写入、账户余额联动或前端改动。

### P1 定向修复状态

首次独立验收为 NO-GO，4 项 P1 已定向修复：V4 保持不变，V5 对分类型金额恒等式、replacement 同用户同 Asset 隔离和自引用施加前向数据库约束，并对旧矛盾事实 fail-fast；Opening 只能是唯一的第一个有效事实；正持仓必须有正成本，低至不能以 CNY 两位小数表达的金额会被拒绝。上述修复不等同于 Phase 1 正式关闭，仍须独立复验；Phase 2 未开始，仍无公开 InvestmentTransaction API、Account.balance 联动或实际 Opening 迁移。

### 已接受的设计方向 / 后续实施规划

- `InvestmentTransaction` = 投资事实；
- `Asset` = 当前持仓受控投影；
- `Account.balance` = 现金余额投影；
- `Market Quote / FX` = 外部参考输入；
- `Reference Valuation` = 非持久化参考估值；
- 独立 `InvestmentTransaction`，第一版采用加权平均成本；
- 第一版投资账务继续 CNY-only，支持 `BUY`、`SELL`、`DIVIDEND`、`OPENING_POSITION`；
- `fee`、`tax` 作为交易组成字段，使用必填幂等键、PostgreSQL 行锁和冲正替代物理删除；
- `Opening Position` 用于旧 Asset 迁移，普通流水与投资交易未来共用统一 `AccountBalanceService`。

### Phase 2 后续范围，当前明确不实施

- 开放 BUY / SELL API；
- 修改前端或 Asset 当前行为。

### 边界说明

该阶段会显著扩大业务复杂度，必须在入口规划完成后分阶段实施；本节内容均为已接受的设计方向或后续实施规划，不代表当前已实现能力。

------

## Future（长期规划）

以下能力属于长期规划，不代表当前已实现：

- AI 财务分析；
- 多币种汇率；
- 审计日志；
- 软删除；
- 多语言；
- 部署增强；
- CSV / Excel 导入导出；
- 家庭账本；
- 插件系统；
- 开放 API。

------

# 四、当前不做的事

以下内容未纳入 v1.2 Visualization Polish：

- 行情数据接入；
- 第三方行情 API；
- WebSocket；
- 完整投资交易模型；
- 多币种汇率；
- AI；
- Docker 部署；
- GitHub Actions / CI；
- 大规模前端组件化重构。

------

# 五、路线图维护原则

- 已完成能力必须能在代码、测试或文档中找到对应依据；
- 后续规划不得写成当前实现；
- 每个阶段结束时应新增 Closing Review；
- Roadmap 变更应同步根 README 和 docs 导航；
- 架构级变化应通过 ADR 记录。
