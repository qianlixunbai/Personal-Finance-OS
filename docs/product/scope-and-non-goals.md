# Scope and Non-goals

本文定义 Personal Finance OS 的产品边界。当前阶段与验收状态见 [STATUS](../STATUS.md)。

## 当前范围

### 用户与客户端

- 单个自然人维护自己的财务数据；
- Web 是当前主要客户端；
- 每个用户的数据域相互隔离；
- 不提供家庭共享账本、组织租户或管理员业务后台。

### 财务事实

- Account、Category 与普通 `INCOME` / `EXPENSE` / `ADJUSTMENT`；
- Legacy Asset 与 transaction-driven Asset；
- Investment Instrument、不可变投资事实与纠正记录；
- CSV / XLSX 普通流水导入；
- Dashboard 与只读参考估值。

### 当前技术约束

- CNY 单币种账务；
- Java 后端是金额、余额、投资重放和核心聚合的唯一权威；
- PostgreSQL 保存业务事实、审计证据和受控投影；
- 模块化单体，不拆分微服务；
- Market Quote 与 FX 只作为参考数据；
- 外部 Provider 只能通过显式刷新进入系统。

## 当前未覆盖

- 普通流水 `TRANSFER` / `REFUND`；
- 多币种账户余额与跨币种账务；
- FIFO / lot、税务 lot、公司行动与拆股；
- 历史收益曲线和完整绩效归因；
- 银行或券商自动同步；
- 投资文件导入；
- 原生移动端；
- 可写入财务事实的 AI Agent。

这些项目是未实现范围，不应从数据库枚举、历史设计或候选 Roadmap 推断为已有能力。

## 明确 Non-goals

系统不承担：

- 真实支付、收款或银行转账执行；
- 证券下单、自动交易、量化执行或托管；
- 投资建议、税务建议或合规意见；
- 银行、券商或支付机构的正式账本替代；
- 高可用、多区域、灾备、监控和备份齐全的生产运维平台；
- 为展示技术复杂度而引入微服务、Redis、MQ 或分布式事务；
- 在数据来源、权限和失败恢复未冻结前接入外部自动化写入。

## 数据解释边界

- `Account.balance` 是系统内受控现金余额投影，不证明银行真实余额；
- `Asset` 是当前持仓投影，`InvestmentTransaction` 是投资历史事实；
- Market Quote、FX 和 reference valuation 不是账务或税务真值；
- 静态 Demo 使用虚构数据，不代表真实后端或用户数据；
- Import probable duplicate 是需要用户确认的风险提示，不是自动去重裁决。

## 进入范围的条件

候选能力只有在需求、数据模型、业务规则、失败恢复、权限边界和验收标准明确后才能进入实施。涉及架构变化时还必须新增 ADR。
