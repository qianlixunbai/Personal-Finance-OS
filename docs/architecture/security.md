# Security

本文描述当前认证、授权、secret、文件和外部 Provider 安全边界。接口细节见 [API](api.md)，业务规则见 [Business Rules](../domain/business-rules.md)。

## 1. 认证

- 用户通过 `/api/v1/login` 获取 JWT；
- `JwtAuthFilter` 校验 token，并把 user ID 放入 Spring Security principal；
- 后端无状态运行，Session creation policy 为 `STATELESS`；
- 密码使用 BCrypt hash；
- 除注册、登录、health、开发环境 OpenAPI / Swagger 外，业务路由均需认证；
- production profile 关闭 OpenAPI / Swagger UI。

JWT expiration 当前为 24 小时。客户端不能通过请求体声明 user ID。

## 2. 用户数据隔离

- Account、用户 Category、普通 Transaction、Asset、Instrument、投资事实、correction command 与 Import Session/Batch/Item/Impact 均按 user 隔离；
- 查询、锁与写入同时使用 `user_id` 和资源 ID；
- 关键数据库关系使用 owned composite FK；
- 不存在与跨用户资源采用相同安全 `404`；
- 系统 Category、Market Quote 与 FX snapshot 是共享数据例外，但不能成为访问用户财务数据的桥梁。

## 3. Secret 管理

当前至少需要三类独立 secret：

| Secret | 用途 |
| --- | --- |
| `JWT_SECRET` | JWT 签名 |
| `MIGRATION_PREVIEW_SECRET` | Legacy opening preview token |
| `FINANCE_IMPORT_CONFIRM_TOKEN_SECRET` | Transaction Import Confirm preview token |

生产代码不提供 Import Confirm 的公开默认 secret；未显式配置或长度不足 32 字符时应用安全 fail-fast。secret 不得写入源码、已跟踪 `.env`、日志、截图或 Markdown 示例的真实值。

当前 Compose 未传递第三个 secret，详见 [Deployment](../engineering/deployment.md)。

## 4. Import token 与幂等边界

Import Preview token 使用 HMAC-SHA256，绑定：

- contractVersion；
- user、Session、预分配 Batch；
- Session revision；
- file、mapping、options、normalized rows digest；
- warning-set digest；
- issuedAt 与 Session expiresAt。

tampered、过期、跨用户、跨 Session、stale revision 或 warning mismatch 均返回冲突，不降级到不安全默认行为。幂等键与 canonical request hash 单独约束 exactly-once，不以 token 替代幂等。

## 5. 文件上传安全

- 只接受 CSV 和 XLSX，扩展名、声明 format 与 content type 必须一致；
- 文件最大 5 MiB，数据最多 10,000 行、32 列；
- CSV 必须是严格 UTF-8，限制 header/value 长度并拒绝 NUL 和异常行结构；
- XLSX 只允许一个可见 worksheet，拒绝隐藏行列、合并单元格、formula、boolean/error cell 和外部 relationship；
- XLSX ZIP entry、展开大小、压缩比、XML node/attribute/depth 和解析时间均有限制；
- XML DTD、entity、XInclude 和外部 schema 解析被禁止；
- 客户端文件名只用于清洗后的展示，不参与服务器路径；
- 私有存储引用是受格式限制的 UUID，并在 normalize 后验证仍位于指定 root。

临时 payload 在取消、过期或成功提交后清理。清理失败只记录脱敏 warning，不回滚已经提交的金融事务。

## 6. 外部 Provider 边界

- Market Data 与 FX 默认关闭；
- 只有显式 refresh 才能调用 Provider；
- connect/read timeout、TTL、用户级/全局限流与 single-flight 限制调用；
- 普通 GET 不调用外部服务；
- Provider 返回的 symbol、币对、价格和时间必须校验；
- Provider 失败不得修改 Account、Transaction、InvestmentTransaction 或成本投影。

## 7. 错误与日志

对外错误不得回显：

- SQL、constraint 细节或 stack trace；
- JWT、secret、idempotency key、request hash；
- correction reason、replay digest 或内部金融中间值；
- 其他用户资源是否存在。

一致性失败返回脱敏 500；锁超时和 deadlock 映射为可重试冲突；数据库不可用统一返回服务不可用。Import domain error 只暴露稳定 code 和必要的 retryable 属性。

## 8. 客户端恢复安全

- pending investment / Import command 必须绑定当前用户；
- 用户切换不能恢复或展示上一用户的 intent / receipt；
- Import unknown outcome 先查询权威 Receipt，只有明确 404 才能以同一 frozen key/path/body 回放 POST；
- 已观察到 HTTP 200 的 committed state 保持单调，只允许 Receipt GET 恢复；
- Receipt 页面每次进入或 reload 都从服务端重新读取，不信任本地回执内容；
- 多标签页协调只是客户端防重复保护，后端幂等仍是正确性权威。

## 9. 验证重点

安全相关变更至少验证 JWT、跨用户 404、owned locks、DTO 字段、secret fail-fast、文件解析资源限制、错误脱敏、401 continuation、用户切换和 unknown-outcome recovery。测试策略见 [Testing](../engineering/testing.md)。
