# 完成定义（Definition of Done）

按风险选择最小充分验证。所有交付均需可读 diff、`git diff --check` 与 `git status --short` 检查，排除无关文件、生成物、密钥和未授权文件。

## 普通修改与 API

普通低风险修改需满足范围、验收行为和相关文档清晰，并运行就近 lint、测试或手工检查。API 改动还需验证请求/响应、校验、授权、错误语义、兼容性，并同步 API 文档与相关前端契约。

## migration、金融写路径与并发

migration 必须新增前向 Flyway 脚本，不能修改已应用版本；按风险验证空库迁移、PostgreSQL/Testcontainers、约束、索引、trigger/deferred integrity 与旧数据边界。金融写路径需验证 `BigDecimal`、事务边界、余额、append-only fact/receipt、幂等、用户隔离、重放投影和完整回滚。并发/锁改动需验证锁顺序、timeout/deadlock 映射、无重复 effect 与 unknown-commit recovery。

## 文档、运行时与关闭

文档-only 改动需以代码/migration、ADR、最新 Closing Review 核对事实，保持冻结 Architecture 与历史记录不变，并检查链接、导航和阶段状态；不必运行完整应用测试。部署、migration、生产配置或 runtime integration 改动需执行相应 smoke。Closing Review 应记录范围、证据、限制与 GO/NO-GO，不改写历史证据。

提交前运行：

```powershell
git diff --check
git diff --cached --check
git status --short
```

只暂存已授权文件；不得暂存 `AGENTS.md` 等用户文件。
