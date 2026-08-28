# Code Review Checklist

按变更范围选择相关项；金融规则、安全、Migration、事务、并发和 Import Confirm 必须重点审查。

## 1. 范围与契约

- [ ] diff 只包含授权文件和必要实现。
- [ ] 行为与 Requirements、Business/Financial Rules、API 和 ADR 一致。
- [ ] 没有提前实现 Roadmap candidate 或隐式改变 Frozen Architecture。
- [ ] 当前状态只更新 `STATUS.md`，没有复制阶段列表。
- [ ] 未实现能力没有从 schema enum、内部 Service 或历史 design 外推。

## 2. 用户隔离与安全

- [ ] user ID 只来自 JWT principal。
- [ ] 查询、更新和 lock SQL 同时使用 `user_id` 与资源 ID。
- [ ] 跨用户与不存在资源返回安全 404。
- [ ] DTO 不暴露 password hash、request hash、内部 receipt metadata 或 secret。
- [ ] 错误和日志不泄露 SQL、stack、JWT、key、reason 或金融中间值。
- [ ] production secret 外部注入且无公开默认值。

## 3. API Boundary

- [ ] 请求只接受完成命令所需字段。
- [ ] user ID、currency、server time、计算字段和 projection 不能由客户端覆盖。
- [ ] 投资/Receipt 金融值保持精确字符串。
- [ ] strict endpoint 拒绝 unknown fields、JSON number 或科学计数法时有测试。
- [ ] 两种 idempotency header 未混用。
- [ ] envelope、分页上限与时间格式保持一致。
- [ ] 状态码与 domain code 映射准确，包括 parser timeout 等边界。

## 4. BigDecimal 与公式

- [ ] 没有 `double`、`float` 或 `new BigDecimal(double)`。
- [ ] money/quantity/price/cost 的 scale、precision 明确。
- [ ] CNY 舍入只在正确边界使用 `HALF_UP`。
- [ ] 普通流水 delta 符号正确。
- [ ] BUY fee/tax 进入成本；SELL released cost/PnL 正确；DIVIDEND 不改成本。
- [ ] reversal/replacement 有符号 cash delta 正确。
- [ ] 零 net、oversell、full sell、reopen 与范围上限有测试。

## 5. 事务与 Rollback

- [ ] facts、balance、projection 与 receipt 在同一事务。
- [ ] `AccountBalanceService` 仍是 balance 写入原语。
- [ ] mapper 写入检查预期影响行数。
- [ ] 任一异常不会留下半个 fact、group、Batch 或 receipt。
- [ ] failure injection 覆盖所有关键写点。
- [ ] 提交后的非金融 cleanup 不反向破坏已提交事实。

## 6. Locks 与并发

- [ ] 普通流水：旧 fact → Accounts ascending。
- [ ] 投资：Account → Instrument → Asset；纠正前置 Original fact。
- [ ] Import：Session → Accounts ascending。
- [ ] 没有新增反向 lock path。
- [ ] transaction-level `lock_timeout` 正确。
- [ ] lock timeout/deadlock 脱敏为冲突。
- [ ] concurrent same key 只有一个 effect。
- [ ] 测试结束无悬挂 lock，重复运行稳定。

## 7. Idempotency 与 Unknown Commit

- [ ] key 规范为 1–100 非空字符、无首尾空格、用户域隔离。
- [ ] canonical hash 固定字段顺序、scale、null marker 和 contract version。
- [ ] 同 key 同 intent 返回持久 receipt，不重新执行 effect。
- [ ] 同 key 不同 intent 返回冲突。
- [ ] 只识别预期 PostgreSQL constraint identity。
- [ ] unknown constraint 不返回伪成功。
- [ ] commit 后 response unknown 可恢复；commit 前 rollback 后只执行一次。

## 8. Append-only 与 Replay

- [ ] original InvestmentTransaction 不被 UPDATE/DELETE。
- [ ] posting receipt 不被后续纠正覆盖。
- [ ] standalone reversal eligibility 与一次纠正规则保持。
- [ ] replacement 与 original 同类型、同 currency/time/anchor。
- [ ] facts-first / command-last 与 deferred integrity 同时成立。
- [ ] canonical ordering、trace 与 digest 未漂移。
- [ ] candidate replay 与持久事实二次 replay 一致。
- [ ] version 与 currentPrice/marketValue 保留规则正确。

## 9. Transaction Import

- [ ] Preview 只读；Confirm 不接受客户端 rows 或 impacts。
- [ ] 文件扩展名、MIME、size、row/column 与 ZIP/XML 防护完整。
- [ ] mapping keys 规范化后唯一，未使用列显式 IGNORE。
- [ ] Account/Category/CNY/amount/date/time/description 服务端校验。
- [ ] warning ID 绑定 Session revision 和 evidence。
- [ ] Account locks 后复核 duplicate evidence。
- [ ] exact duplicate 与 probable duplicate 语义不混淆。
- [ ] Session/Batch/Items/Impacts 所有权与状态约束完整。
- [ ] Receipt immutable、可重建且 digest 校验。
- [ ] cleanup 生命周期不与金融事务错误耦合。

## 10. Frontend Recovery

- [ ] pending intent 在 POST 前保存并绑定 owner。
- [ ] 同一 intent 始终使用相同 key/path/body。
- [ ] unknown outcome 先 GET，只有明确 404 才 POST。
- [ ] committed state 永远 GET-only。
- [ ] Initial POST、unknown GET、fallback POST、committed GET 的 401 continuation 正确。
- [ ] user switch/logout/reload/multi-tab 不泄露或重复提交。
- [ ] Receipt 每次从服务端读取，amount 不转 Number。
- [ ] stale response 和 storage validation fail closed。

## 11. Database 与测试

- [ ] 新 schema 使用 V18+ 前向 Migration，不修改 V1–V17。
- [ ] FK、unique、CHECK、trigger 与 Java 规则一致。
- [ ] PostgreSQL 特性由 PostgreSQL 验证。
- [ ] unit、WebMvc、integration、concurrency、failure 和 E2E 按风险选择。
- [ ] 报告数字来自本轮实际输出，历史数字明确标注。
- [ ] 没有虚构 CI、runtime 或部署状态。

## 12. 文档与交付

- [ ] STATUS、API、Database、Rules 与实现同步。
- [ ] Frozen Architecture、ADR 和历史证据未被改写。
- [ ] 相对链接、图片、Mermaid、CI badge 与 Demo 边界已验证。
- [ ] `git diff --check` 与 `git status --short` 已运行。
- [ ] staged 清单没有 secret、用户文件、`sites-demo` 或无关内容。
- [ ] P0/P1 已修复并复验，非阻塞 P2/P3 已记录并收敛。
