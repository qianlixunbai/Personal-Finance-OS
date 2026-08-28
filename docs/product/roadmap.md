# 路线图

路线图只描述未来方向，不维护已完成阶段。当前状态与历史关闭证据分别见 [STATUS](../STATUS.md) 和 [Archive Review](../archive/review/README.md)。

## 状态标签

| 标签 | 含义 |
| --- | --- |
| `PLANNED` | 已有明确下一步依据，但仍需按验收门槛执行 |
| `CANDIDATE` | 值得评估，尚未形成实施承诺 |
| `NON-GOAL` | 明确不进入产品目标 |

## 近期

### `PLANNED`：Transaction Import Frontend 最终关闭

- 对 A–D 当前实现和最新 remediation 执行最终独立只读 Closing Re-Review；
- 核对 frozen intent、Receipt GET-first、401 continuation、多标签页和 lossless amount 不变量；
- 只有独立结论满足关闭条件后，才更新 [STATUS](../STATUS.md)。

### `CANDIDATE`：导入交付体验加固

- 修复 Compose / 示例环境对 Import Confirm token secret 的传递缺口；
- 评估 Receipt transaction references 的后端分页；
- 评估导入模板、映射复用和更明确的对账入口。

## 中期候选

- `TRANSFER` / `REFUND` 及其双账户事务和审计语义；
- 对账、重复治理和余额校准；
- 银行或券商账单的受控文件导入；
- 历史持仓与收益曲线；
- PWA 与移动端快速录入。

以上均为 `CANDIDATE`，不是已承诺版本。

## 长期候选

- 多币种账务；
- FIFO / lot 与公司行动；
- 原生移动端；
- 只读或严格受控的 AI Agent；
- 在接口稳定、合法合规且失败恢复明确时评估外部数据同步。

## 明确 Non-goals

- 真实支付与资金划转执行；
- 证券下单、自动交易和投资建议；
- 无实际收益的微服务拆分；
- 为架构展示提前引入 Redis、MQ 或分布式事务；
- 在缺少稳定、合规接口时强行接入银行或券商 API。

## 候选进入实施的门槛

1. 明确用户问题、范围和排除项；
2. 定义后端权威数据与单一事实来源；
3. 明确金额、舍入、幂等、锁、回滚和跨用户语义；
4. 评估 Migration 与历史数据升级；
5. 定义可自动验证的完成标准；
6. 架构级变化先通过 ADR。
