# 产品需求

本文定义当前产品应提供的能力和质量边界，不记录阶段开发流水。当前交付与正式验收状态见 [STATUS](../STATUS.md)。

## 1. 身份与用户域

系统应支持用户注册和登录，以 JWT 标识当前用户。用户只能读取和修改自己拥有的账户、分类、流水、资产、投资标的、投资事实与导入记录；不存在和跨用户资源应采用不泄露存在性的错误语义。

## 2. 基础财务

系统应支持：

- 创建、查询、更新和停用 Account；
- 查询系统分类，创建用户分类；
- 创建、查询、更新和删除普通 `INCOME`、`EXPENSE`、`ADJUSTMENT`；
- 在同一事务中维护普通流水与 `Account.balance`；
- 对流水进行分页和条件筛选；
- 允许负余额，不实现透支拒绝。

普通流水的具体规则见 [Business Rules](../domain/business-rules.md) 和 [Financial Rules](../domain/financial-rules.md)。

## 3. Dashboard

系统应由后端聚合并返回当前用户的财务概览，包括净资产、月度收入与支出、趋势、资产分布和最近流水。前端只消费聚合结果，不重新计算核心指标。

## 4. 市场参考数据与估值

系统应支持受控的 Market Quote 与 FX snapshot 获取，并基于缓存参考数据计算 CNY reference valuation。普通读取不得隐式调用 Provider；参考估值不得修改账户余额、普通流水、投资事实或成本投影。

## 5. 投资账本

系统应支持：

- 用户级 Investment Instrument 和唯一 Account / Instrument / Asset 绑定；
- Legacy Asset opening migration；
- first BUY、subsequent BUY、partial/full SELL、CLOSED 后 reopen BUY 与 DIVIDEND；
- standalone reversal 与 same-type replacement；
- append-only 投资事实、不可变 receipt 和 correction envelope；
- deterministic replay、当前 Position 投影和一致性校验；
- Portfolio、Position、logical transaction 与 audit timeline 读取；
- 对应的 Web 读取、命令、确认、回执和恢复交互。

权威语义和公式见 [Investment Ledger](../domain/investment-ledger.md)。

## 6. 普通流水导入

系统应支持受认证用户通过 CSV 或 XLSX：

1. 上传文件并建立短生命周期 Session；
2. 显式映射列、交易类型、Account 和 Category；
3. 获取服务端规范化、校验和 probable duplicate Preview；
4. 显式确认 warning；
5. 使用冻结 token 与幂等键执行原子 Confirm；
6. 获取可重建、不可变的权威 Receipt；
7. 在 reload、401、409、unknown outcome 和多标签页场景下安全恢复。

导入只创建普通 `Transaction`，不导入投资交易。完整规则见 [Transaction Import](../domain/transaction-import.md)。

## 7. API 与客户端

- 业务 API 使用 `/api/v1` 前缀和 JWT Bearer 认证；
- 响应采用统一 envelope，金额边界不得导致 JavaScript 精度丢失；
- 开发环境提供 OpenAPI / Swagger UI，production profile 关闭；
- Web 客户端应覆盖基础财务、投资与普通流水导入主流程；
- 客户端不得提交或覆盖服务端计算字段、user ID、余额、投影或 receipt。

当前公开契约见 [API](../architecture/api.md)。

## 8. 数据与一致性

- 当前账务币种为 CNY；
- 金额、数量、价格、成本和 PnL 使用 `BigDecimal` / PostgreSQL `NUMERIC`；
- 金融写入必须定义事务、锁、幂等、回滚和错误分类；
- 投资事实与 correction command 必须保持 append-only；
- schema 只能通过前向 Flyway Migration 演进；
- 外部 Provider 失败不得破坏账务真值。

## 9. 安全与隐私

- 密码使用 BCrypt；
- secret 只来自外部配置，不硬编码到生产代码或文档；
- 错误和日志不得泄露 SQL、堆栈、JWT、幂等键、request hash 或敏感财务中间值；
- 文件导入必须限制格式、大小、行列、解析时间和 XML/ZIP 资源使用；
- 临时文件不得使用客户端文件名作为存储路径，并应在取消、过期或提交后清理。

## 10. 工程与验证

系统应具备单元、WebMvc、PostgreSQL 集成、Migration、并发、failure-injection、前端 unit 和真实浏览器 E2E 验证。CI 应执行后端测试、前端 test/lint/build、镜像构建和 Compose 配置校验。

测试策略见 [Testing](../engineering/testing.md)。测试数字只保存在对应 [Closing Review](../archive/review/README.md) 中。

## 11. 需求治理

- 当前状态由 [STATUS](../STATUS.md) 唯一维护；
- 产品边界由 [Scope and Non-goals](scope-and-non-goals.md) 维护；
- 未来方向由 [Roadmap](roadmap.md) 维护；
- 架构级变化必须通过 ADR；
- 文档与实现冲突时，以当前代码、Migration、配置和公开 API 为事实依据，同时记录违反冻结决策的不一致。
