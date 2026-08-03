# 代码审查清单

按变更范围选择相关项；金融规则、安全、事务、migration 与并发改动必须重点审查。

## 1. 范围与契约

- [ ] diff 只包含授权文件和必要实现，没有无关重构或生成物。
- [ ] 行为与 SRS、Business Rules、Financial Rules、API 和 ADR 一致。
- [ ] 没有提前实现未开始的 phase 或隐藏改变冻结 Architecture。
- [ ] 新旧命名和路径没有形成冲突契约。
- [ ] 当前未实现能力仍被明确标注，而不是以占位 API 冒充完成。

## 2. 用户隔离与安全

- [ ] user ID 只来自 JWT principal，不信任请求体中的所有者字段。
- [ ] 查询、更新和锁 SQL 同时使用 `user_id` 与资源 ID。
- [ ] 跨用户与不存在资源返回安全 404。
- [ ] 系统分类、Market Quote 和 FX 的共享边界明确且不会泄露用户数据。
- [ ] DTO 不暴露 password hash、request hash、内部 receipt 或 correction 元数据。
- [ ] 错误和日志不泄露 SQL、堆栈、JWT、幂等键、原因或财务中间值。

## 3. DTO 与 API boundary

- [ ] 请求只接受完成业务命令所需字段。
- [ ] user ID、currency、server time、计算字段、projection 与 receipt 不可由客户端覆盖。
- [ ] 投资金额/数量/价格按 contract 使用普通十进制字符串和固定 scale。
- [ ] strict endpoint 拒绝 unknown fields、JSON number 与科学计数法。
- [ ] `X-Idempotency-Key` 与 `Idempotency-Key` 没有混用。
- [ ] ApiResponse、PageResult、分页上限与时间格式保持一致。
- [ ] 400/401/403/404/409/429/500/502/503 映射准确。

## 4. BigDecimal 与金融公式

- [ ] 没有 `double`、`float` 或 `new BigDecimal(double)`。
- [ ] money/quantity/price/average cost 的 scale 与 precision 明确。
- [ ] 只有需要形成 CNY 金额时才按 `HALF_UP` 舍入到 2 位。
- [ ] BUY fee/tax 进入 acquired cost，cash delta 符号正确。
- [ ] SELL partial/full released cost、rounding residual 与 realized PnL 正确。
- [ ] DIVIDEND 不改变数量、成本、状态或累计 PnL。
- [ ] reversal/replacement 的有符号 cash delta 由 original/replacement type 正确推导。
- [ ] 边界值、零 net、oversell、全卖、重开和 scale 超限有测试。

## 5. 事务与 rollback

- [ ] 事实、Account.balance、Asset projection、receipt/envelope 位于同一事务。
- [ ] `AccountBalanceService` 仍是余额唯一生产写入者。
- [ ] 使用 `Propagation.MANDATORY` 的余额服务只能在活动事务中调用。
- [ ] 所有 mapper 写入检查预期影响行数。
- [ ] 任一异常都会回滚全部 effect，没有半个 fact、半个 replacement group 或孤儿 receipt。
- [ ] failure injection 覆盖关键写入阶段并验证数据库不变量。

## 6. 锁顺序与并发

- [ ] 普通流水遵守 fact → Account(s ascending) 顺序。
- [ ] BUY/SELL/DIVIDEND 遵守 Account → Instrument → Asset。
- [ ] reversal/replacement 遵守 Original fact → Account → Instrument → Asset。
- [ ] 没有新增反向锁路径或跨模块先锁 Asset 再锁 Account。
- [ ] 事务级 lock timeout 配置正确。
- [ ] `55P03`/`40P01` 映射为脱敏 409，不自动重试。
- [ ] 并发创建、同 key 竞争和 version compare-and-set 无 lost update。
- [ ] 测试结束无悬挂锁，重复运行结果稳定。

## 7. 幂等与 unknown commit

- [ ] key 规范为 1–100 非空字符、无首尾空格、用户域唯一。
- [ ] canonical hash 固定字段顺序、scale、null marker、formula/replay policy。
- [ ] 同 key 同 hash 返回持久 receipt，不重新执行 effect。
- [ ] 同 key 不同 hash 返回 409。
- [ ] 只对预期 named unique constraint 执行恢复。
- [ ] unknown unique violation 不被吞掉或返回伪成功。
- [ ] commit 后响应未知可恢复已提交 receipt。
- [ ] commit 前回滚后重试只执行一次。

## 8. Append-only 与审计

- [ ] original InvestmentTransaction 不被 UPDATE/DELETE。
- [ ] posting-time receipt 不被后续纠正覆盖。
- [ ] standalone reversal eligibility 与一原事实一次纠正规则保持。
- [ ] grouped reversal 不伪造未提交的中间 Position receipt。
- [ ] replacement 与 original 同类型、同币种、同 trade/settlement time。
- [ ] facts-first / command-last 与 deferred integrity 同时成立。
- [ ] correction envelope 不可变，完整表达 command 结果。

## 9. Deterministic replay 与 projection

- [ ] 排序键保持 `(effectiveTradeTime, effectiveAnchorId, replaySequence, factId)`。
- [ ] replacement 使用 original replay anchor，sequence=1。
- [ ] reversal 和被 reversal original 不进入 calculator。
- [ ] OPENING 首事实与 DIVIDEND 前置历史规则保持。
- [ ] candidate replay 和持久事实二次 replay 比较完整 trace/digest。
- [ ] 旧 SELL receipt 保持 snapshot，current truth 来自 replay/projection。
- [ ] Account 只更新一次，Asset 只投影一次，projectionVersion 只 `+1`。
- [ ] currentPrice/marketValue 不被账本纠正覆盖。

## 10. 数据库约束

- [ ] 新 schema 使用前向 migration，不修改 V1–V13。
- [ ] FK/复合 FK 同时保护关系与 user ownership。
- [ ] unique/partial unique 与业务身份一致。
- [ ] CHECK 覆盖类型字段形状、金额恒等式、币种、key/hash 和 receipt。
- [ ] immutable/append-only trigger 与 Java 规则一致。
- [ ] deferred FK/constraint trigger 不允许提交不完整 correction group。
- [ ] named constraint 的错误分类只匹配预期名称。
- [ ] migration 有 fail-fast、空库和必要升级测试。

## 11. Testcontainers 与测试证据

- [ ] PostgreSQL 特性由 PostgreSQL Testcontainers 验证，不用 H2 替代。
- [ ] 单元、WebMvc、integration、concurrency 和 failure 测试按风险选择。
- [ ] 测试证明原始 symptom/边界，而不只覆盖 happy path。
- [ ] runtime smoke 用于 migration、Compose、profile 或 Provider 风险。
- [ ] 报告中的 suites/tests/failures/errors 来自本轮实际输出，或明确标为历史基线。
- [ ] 没有虚构前端测试数、CI run 或部署状态。

## 12. 文档与交付

- [ ] API、Database、Business/Financial Rules 与实现同步。
- [ ] Architecture、ADR 和历史 Closing Review 未被改写。
- [ ] 相对链接、图片、Mermaid、CI badge 和 Demo 边界已验证。
- [ ] `git diff --check`、`git diff --cached --check`、`git status --short` 已运行。
- [ ] staged 文件清单没有密钥、用户文件、`sites-demo` 或无关内容。
- [ ] P0/P1 已修复并复验；仅剩非阻塞 P2/P3 时记录并收敛。
