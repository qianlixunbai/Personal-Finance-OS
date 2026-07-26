# Business Rules（业务规则）

**项目名称：** Personal Finance OS

**版本：** v1.0

------

# 一、文档目的

本文档定义 Personal Finance OS 的业务规则与系统行为规范。

业务规则用于约束系统中各模块的运行逻辑，确保不同开发人员或 AI 在实现功能时遵循统一标准。

所有开发均应以本文档为依据，不得自行修改业务行为。

------

# 二、基本原则

整个系统遵循以下原则：

- 一个功能只负责一件事情；
- 一条业务规则只能有一种实现方式；
- 同一业务场景必须保持一致行为；
- 所有业务操作应可追溯；
- 数据优先保证一致性，而不是便利性。

------

# 三、用户规则

## 用户唯一性

每个账号唯一。

禁止重复注册相同账号。

------

## 登录

用户必须登录后才能访问个人数据。

所有业务数据均属于当前登录用户。

任何用户不得访问其他用户的数据。

------

## 用户删除

V1 不提供永久删除用户功能。

如需停用账号，应采用禁用状态保留历史数据。

------

# 四、账户规则

## 创建账户

用户可创建多个账户。

账户类型不限于：

- 现金
- 银行卡
- 信用卡
- 支付平台
- 券商账户
- 加密钱包

------

## 删除账户

存在历史流水的账户：

禁止删除。

仅允许：

停用（Inactive）。

------

## 停用账户

停用账户：

- 不允许新增流水；
- 保留历史数据；
- 保留统计结果。

------

## 默认账户

V1 不强制要求默认账户。

后续版本可支持默认收支账户。

------

# 五、流水规则

所有流水必须：

- 属于一个用户；
- 属于一个账户；
- 属于一个分类；
- 有明确时间。

禁止：

孤立流水。

------

## 修改流水

允许修改。

修改后：

相关统计数据应同步更新。

------

## 删除流水

V1 默认允许删除。

后续版本可支持软删除和操作日志。

------

# 六、分类规则

分类分为：

- 收入分类；
- 支出分类。

系统默认分类：

采用全局系统分类模型，`user_id = null` 且 `is_system = true`。

应用启动时应幂等初始化默认收入 / 支出分类，Fresh DB 下用户注册登录后无需手工初始化即可选择分类创建流水。

禁止删除。

用户自定义分类：

允许：

新增；

修改；

删除（无关联流水时）。

------

# 七、投资资产规则

每项资产必须属于：

一个用户。

一个投资账户（目标规则；当前 API / schema 尚未落地 `accountId`，当前实现至少强制资产属于当前登录用户）。

一个币种。

------

资产允许：

新增。

修改。

清仓（仅将持仓快照数量归零）。

删除（无持仓时）。

------

清仓规则：

只能清仓当前登录用户自己的资产。

清仓会将资产 `quantity` 设置为 `0`。

已清仓资产再次清仓应幂等成功。

清仓不修改现金账户余额，不生成流水，不计算实现盈亏。

清仓不是完整卖出交易模型。

------

卖出数量：

不得超过持仓数量。

V1 不支持：

完整卖出交易。

手续费、税费、股息处理。

实现盈亏计算。

现金账户入账。

融券。

卖空。

杠杆交易。

------

# 八、Dashboard 规则

Dashboard 展示的数据：

始终来源于真实业务数据。

禁止：

人工修改统计结果。

所有图表均由系统计算生成。

------

# 九、数据统计规则

所有统计数据：

默认实时计算。

后续版本可通过缓存优化。

禁止：

手工维护统计表。

------

# 十、AI 规则（v4.0 规划）

AI 财务助手当前未实现，以下规则仅作为后续 v4.0 能力边界。

AI 可以：

分析。

总结。

预测趋势。

生成建议。

AI 不允许：

创建流水；

修改余额；

删除数据；

修改持仓；

执行交易。

------

# 十一、权限规则

普通用户：

仅能访问自己的数据。

管理员功能：

暂不开发。

V1 不考虑：

多角色权限。

------

# 十二、数据一致性规则

任何业务操作必须保证：

数据库数据一致。

统计数据一致。

账户余额一致。

资产数据一致。

若操作失败，应整体回滚。

------

# 十三、异常规则

系统应拒绝以下行为：

- 非法金额；
- 非法账户；
- 非法分类；
- 非法资产；
- 非法用户；
- 非法币种。

所有异常均返回统一错误码与明确错误信息。

------

# 十四、版本规则

V1 不支持：

- 多用户协作；
- 家庭账本；
- 企业账本；
- 自动同步；
- 离线冲突合并；
- 自动投资；
- 自动交易。

这些功能将在后续版本评估是否加入。

------

# 十五、扩展原则

新增功能必须满足：

- 不破坏已有业务规则；
- 保持向后兼容；
- 更新相关文档；
- 通过评审后方可开发。

任何实现不得绕过业务规则。

------

# 十六、v3.0 投资交易边界（Phase 1 已完成并正式关闭）

- `InvestmentTransaction` 独立于普通 `Transaction`；普通流水、现有 Asset API、清仓语义和 Dashboard 不变。
- 领域层定义 `BUY`、`SELL`、`DIVIDEND`、`OPENING_POSITION`，但没有 Controller、公开 API、真实写入 Service 或前端入口。
- 事实不物理删除；未来不得通过 PUT 直接覆盖数量、金额或价格，而应使用冲正和 replacement。`REVERSED` 事实在 replay 中跳过。
- `OPENING_POSITION` 只能由后续受控迁移或内部流程创建，不能作为普通客户端接口。
- Phase 1 不执行账户余额联动、行锁、幂等写入服务、旧 Asset Opening Position 迁移或自动绑定券商账户。

首次独立验收为 NO-GO，发现的 4 项 P1 已通过定向修复和独立聚焦复验全部关闭，最终结论为 GO。V4 保持不变，V5 对金额恒等式、replacement 的同用户同 Asset 关系和自引用施加 fail-fast 数据库约束；Opening 只能是唯一的第一个有效事实；正持仓必须有正成本，CNY 两位小数不能表达的低名义金额会被拒绝。Phase 1 已正式关闭，Phase 2A 尚未开始实施；没有新增公开 InvestmentTransaction API、`Account.balance` 联动或实际 Opening 迁移。

------

# 十七、最终原则

Business Rules 是系统业务行为的统一规范。

当开发实现、AI 输出或数据库设计与本规则冲突时，应优先遵循本文件。

所有业务模块均应保持统一、稳定、可维护的行为，确保系统在长期迭代过程中保持一致性。
# Phase 2A transaction concurrency note

Transaction update and delete lock the owned original fact before affected accounts and use the locked latest fact. Concurrent deletes cannot reverse twice. Account metadata and deactivation do not modify balances, and ownership is enforced in the locking SQL so absent and cross-user resources are both 404.
# Phase 2B-2: Instrument and Position Binding

- An InvestmentInstrument belongs to exactly one user. Its canonical identity is `userId + market + symbol`.
- `market` is one of `US`, `HK`, `CN`, `JP`, `KR`, `CRYPTO`, `OTC`, `FUND`, `OTHER`, or `UNKNOWN`; it is a market-data namespace, not a stock exchange identifier.
- A transaction-driven Position must bind an ACTIVE Account and ACTIVE Instrument. BROKERAGE permits STOCK, ETF, FUND, and BOND; CRYPTO_WALLET permits CRYPTO. Every other Account type is rejected.
- LEGACY Assets retain their original create, update, manual price, close, and delete behavior and are never automatically bound or migrated.
- Transaction-driven projection identity is immutable and cannot be created, closed, or deleted through the existing Asset API.
# Phase 2B-3 business-rule update

Legacy opening migration is explicit and single-Asset. A candidate must be an open CNY `LEGACY` holding with no investment facts and a compatible active Account/Instrument chosen by the user. Zero holdings are not migrated; a successful projection remains transaction-driven.
