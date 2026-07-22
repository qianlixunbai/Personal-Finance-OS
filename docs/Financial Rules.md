# Financial Rules（金融领域规则）

> v2.1 Phase 3: reference valuation is derived only (`quantity × quotePrice × fxRate`) with `BigDecimal`; final CNY is scale 2, `HALF_UP`. It is never persisted and never changes manual Asset values, Account/Transaction truth, or Dashboard totals.

**项目名称：** Personal Finance OS

**版本：** v1.0

------

# 一、文档目的

本文档定义 Personal Finance OS 中所有涉及金融数据的业务规则。

所有涉及金额计算、资产统计、投资分析、收益计算等功能，必须严格遵循本规则。

任何开发人员或 AI 均不得自行修改计算逻辑。

如需调整规则，应更新本文件，并同步修改相关实现。

------

# 二、基本原则

所有金融计算遵循以下原则：

- 数据真实，不允许虚构计算结果；
- 所有计算过程可追溯；
- 相同输入必须得到相同输出；
- 金额计算优先保证准确性，而非计算速度；
- 所有计算逻辑必须保持一致。

------

# 三、金额规则

## 3.1 数据类型

所有金额统一使用高精度十进制类型（Decimal / BigDecimal）。

禁止使用：

- float
- double

进行金额计算。

------

## 3.2 金额精度

默认保留两位小数。

涉及汇率、基金净值、股票价格等场景，可根据业务需求保留更高精度，但最终展示应符合对应资产类型的显示规则。

------

## 3.3 请求金额、存储金额与余额影响

收入（`INCOME`）：

- 请求 `amount` 必须大于 0；
- 数据库存储正数金额；
- 账户余额增加该金额。

支出（`EXPENSE`）：

- 请求 `amount` 必须大于 0；
- 数据库存储正数金额，前端展示时可以显示为负数；
- 账户余额减少该金额。

转账：

后续完整 `TRANSFER` 模型中不影响总资产，仅改变账户余额；当前版本尚未支持。

退款：

后续完整 `REFUND` 模型中冲减对应支出；当前版本尚未支持。

余额调整（`ADJUSTMENT`）：

- 请求 `amount` 不能为 0，可以为正数或负数；
- 账户余额按该有符号金额调整；
- 必须填写并保留调整原因。

------

# 四、账户规则

每个账户拥有独立余额。

任何流水都必须关联账户。

禁止出现无法归属账户的资金记录。

删除账户前，应确认是否存在历史流水；若存在，则禁止直接删除，应采用"停用"方式保留历史数据。

------

# 五、记账规则

每一笔流水至少包含：

- 金额
- 类型
- 分类
- 所属账户
- 时间
- 币种
- 备注（可选）

流水一经创建，不建议直接删除。

若确需修正，应优先采用修改或冲销方式，并保留历史记录（后续版本可支持操作日志）。

------

# 六、资产规则

系统支持以下资产类型：

- 现金
- 银行存款
- 股票
- ETF
- 基金
- 债券
- 黄金
- 加密货币
- 其他自定义资产（预留）

不同资产类型可拥有不同属性，但统一纳入总资产统计。

------

# 七、投资规则

V1 的投资模块定位为**资产持仓管理**。

系统负责记录持仓与估值，不负责证券交易。

当前“清仓”能力只表示资产持仓快照归零。

清仓不代表完整卖出交易，不修改现金账户余额，不生成流水，不计算实现盈亏，也不处理手续费、税费或现金入账。

每项投资资产至少记录：

- 名称
- 代码（如适用）
- 市场
- 币种
- 持仓数量
- 平均成本
- 当前价格
- 当前市值

------

# 八、成本计算规则

默认采用**加权平均成本法（Weighted Average Cost）**。

示例：

第一次买入：

100 股 × 10 元

第二次买入：

100 股 × 20 元

平均成本：

15 元/股。

V1 不支持 FIFO、LIFO 等其他成本计算方式。

------

# 九、收益计算规则

浮动盈亏：

（当前价格 − 平均成本） × 持仓数量

收益率：

浮动盈亏 ÷ 持仓成本 × 100%

所有收益均基于当前持仓计算。

清仓后持仓数量为 0，因此浮动盈亏和收益率按当前快照重新计算；历史实现盈亏仍不在 V1 中记录。

------

# 十、币种与汇率规则

V1.x 的基础币种固定为 `CNY`。

- `Account.currency` 当前只允许 `CNY`；
- `Asset.currency` 当前只允许 `CNY`；
- `Transaction.currency` 当前只允许 `CNY`；
- 流水币种必须与所属账户币种一致；
- Dashboard 只聚合同一基础币种 `CNY` 的数据。

当前不进行汇率换算，也不允许将不同币种金额直接聚合。数据库保留 `currency` 字段，以支持后续版本扩展。

汇率数据来源、更新时间、自动换算和多币种能力属于后续规划；实现时必须保证汇率可追溯。

------

# 十一、资产统计规则

系统应至少统计：

- 总资产
- 总负债（预留）
- 净资产
- 现金占比
- 投资占比
- 各资产类别占比

统计结果应基于最新有效数据实时计算。

投资资产分布的比例规则为：

投资资产分布 = 单项有效投资资产市值 ÷ 所有有效投资资产总市值。

该分布不包含账户余额，不改变总资产的计算口径。图表只负责展示后端计算的市值和比例，不在前端重新进行金融聚合。

Dashboard 最近 6 个月收支趋势的规则为：

- 使用当前自然月及此前 5 个自然月，按 `YYYY-MM` 升序连续返回；
- 业务时区固定为 `Asia/Shanghai`，无时区 `transactions.transacted_at` 按该本地时间解释；当前月仅统计截至本次请求开始时刻的数据，不包含未来流水；
- 只统计当前用户的 `CNY`、`INCOME`、`EXPENSE` 流水；`ADJUSTMENT`、`TRANSFER`、`REFUND` 不进入趋势；
- 收入和支出使用数据库中的正数金额；月度结余由后端使用 `BigDecimal` 计算：收入减支出，允许为负；
- 无流水月份的收入、支出和结余均返回 0；前端只展示后端返回的三项数值，不重新聚合流水或计算结余。

------

# 十二、AI 使用规则（v4.0 规划）

AI 财务助手当前未实现，以下规则仅作为未来能力边界。

AI 可以：

- 分析财务数据；
- 总结消费趋势；
- 分析资产配置；
- 生成报告。

AI 不允许：

- 修改金融数据；
- 修改账户余额；
- 修改持仓；
- 自动执行投资操作；
- 替代系统进行金额计算。

所有涉及金额和资产的最终计算结果，以系统业务逻辑为准。

------

# 十三、异常处理规则

当出现以下情况时，系统应拒绝操作并返回明确错误信息：

- 金额为空；
- 金额格式错误；
- 币种不存在；
- 持仓数量非法；
- 卖出数量超过持仓（V1 默认禁止）；
- 数据精度超出系统允许范围。

------

# 十四、未来扩展

以下规则将在后续版本逐步完善：

- 多币种资产自动换算；
- 股息与分红处理；
- 股票拆股与合股；
- 配股、送股；
- 手续费与税费统计；
- 多种成本计算方式；
- 投资收益时间加权分析；
- IRR、XIRR 等高级收益率计算。

在未正式纳入版本规划前，不应提前实现上述逻辑。

------

# 十五、v3.0 投资账本基础规则（Phase 1 已完成并正式关闭）

`InvestmentTransaction` 是投资事实，`Asset` 是当前持仓受控投影；Market Quote、FX 和 Reference Valuation 都不是交易、成本或现金余额真值。投资账务第一版为 CNY-only，数据库保留三位 `currency` 字段。

- `quantity`、`unitPrice`、`avgCost` 使用 `NUMERIC(28,8)` / `BigDecimal`；`fee`、`tax`、`gross`、`net`、`totalCost`、已实现盈亏使用 `NUMERIC(28,2)` / `BigDecimal`。
- 输入 scale 超限必须拒绝，禁止静默截断、`float`、`double` 与 `new BigDecimal(double)`；现金金额最终 scale 为 2，平均成本最终 scale 为 8，除法显式 `HALF_UP`。
- BUY：`gross = round(quantity × unitPrice, 2)`，`acquiredCost = gross + fee + tax`，成本和数量增加，`cashDelta = -acquiredCost`。
- SELL：`net = gross - fee - tax`；部分卖出按 `round(totalCost × sellQuantity / currentQuantity, 2)` 释放成本，全部卖出释放全部剩余成本，不留下舍入残值；`realizedProfitLoss = net - releasedCost`。
- DIVIDEND 不改变数量、总成本或平均成本，不生成普通 `INCOME` 流水；`cashDelta = gross - tax - fee` 仅由后续写入阶段处理。
- OPENING_POSITION 仅用于受控迁移或内部流程；`totalCost = round(quantity × unitCost, 2)`、`cashDelta = 0`，本阶段不迁移旧数据。
- Phase 1 只计算和 replay，不修改 `Account.balance`、Asset 旧字段、普通 Transaction 或 Dashboard。

首次独立验收为 NO-GO，发现的 4 项 P1 已通过定向修复和独立聚焦复验全部关闭，最终结论为 GO。V4 保持不变，V5 以 fail-fast 前向迁移加入数据库约束：BUY `net = gross + fee + tax` 且成本/已实现盈亏为零；SELL、DIVIDEND `net = gross - fee - tax`；SELL `releasedCost > 0` 且 `realizedProfitLoss = net - releasedCost`；Opening 的 fee、tax、net、released cost 和已实现盈亏均为零。有效序列中 Opening 只能一次且必须第一个；`quantity > 0` 当且仅当 `totalCost > 0`，无法以 CNY 两位小数表达的低名义 BUY、SELL、Opening 以及会留下正数量零成本的部分 SELL 必须拒绝。Phase 1 已正式关闭，Phase 2A 尚未开始实施，仍无公开 API、余额联动或实际 Opening 迁移。

------

# 十六、最终原则

Financial Rules 是 Personal Finance OS 的金融业务基准。

任何涉及资金、资产、收益或估值的功能，均应以本文件为唯一依据。

当代码实现与本文档存在冲突时，应以本文档为准，并及时修正文档或代码，确保两者保持一致。
# Phase 2A balance mutation note

`INCOME` applies `+amount`, `EXPENSE` applies `-amount`, and signed `ADJUSTMENT` applies `amount`; reversal negates the original effect. The transaction fact and balance mutation share one transaction through AccountBalanceService. Negative balances remain valid; inactive accounts may reverse history but cannot receive new effects.
