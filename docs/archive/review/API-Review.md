# API 审查

## 审查对象

`docs/03-Architecture/API.md`

## 审查状态

通过，含少量修订

## 审查范围

本次 Review 覆盖：

- 当前 Controller 已实现 API 是否准确记录；
- Current Implementation / Target Design / Future Evolution 边界是否清晰；
- JWT 认证规则是否完整记录；
- `ApiResponse<T>` 与 `PageResult<T>` 是否完整记录；
- 当前错误处理与参数校验 Known Gaps 是否记录；
- 前端实际调用接口是否记录；
- 与 `Database.md`、`Business Rules.md`、`Financial Rules.md` 的一致性；
- `/categories/init` 的长期边界风险；
- Target v1.0 API Design 是否避免被误解为当前 Sprint 必须全部实现。

## 已完成的小修

已完成的小修：

- 在 Current Implementation Baseline 中补充说明：本节只记录当前 Controller 已实现接口，未出现在 Controller 中的接口不得写入本节。
- 在 `/categories/init` 相关说明中补充：该接口更适合作为开发/初始化接口，后续应考虑迁移为启动初始化逻辑、管理端能力或 migration / data seed 机制。
- 在 Error Handling 与 Known Gaps 中补充参数校验相关异常类型：`MethodArgumentNotValidException`、`ConstraintViolationException`、`MissingServletRequestParameterException`、`HttpMessageNotReadableException` 当前尚未统一包装为 `ApiResponse`。
- 在 Target v1.0 API Design 中补充说明：Target v1.0 API Design 表示目标方向，不代表当前 Sprint 必须一次性全部实现。

## Transaction / Ledger API 审查

关联提交：

- `b8ca982 新增交易流水基础接口`

新增接口：

| Method | Path | 说明 |
|---|---|---|
| `GET` | `/api/v1/transactions/page` | 分页查询当前用户流水。 |
| `GET` | `/api/v1/transactions/{id}` | 查询当前用户指定流水。 |
| `POST` | `/api/v1/transactions` | 创建流水并联动账户余额。 |
| `PUT` | `/api/v1/transactions/{id}` | 更新流水，先回滚旧余额影响，再应用新余额影响。 |
| `DELETE` | `/api/v1/transactions/{id}` | 删除流水并回滚旧余额影响。 |

当前支持类型：

- `INCOME`
- `EXPENSE`
- `ADJUSTMENT`

当前暂不支持类型：

- `TRANSFER`
- `REFUND`

说明：

- 请求中的 `TRANSFER` / `REFUND` 会返回 `400`；
- 数据库中已存在的旧 `TRANSFER` / `REFUND` 流水也不会被当前 API 查询详情、更新、删除或按类型分页查询；
- `INCOME`: `balance += amount`；
- `EXPENSE`: `balance -= amount`；
- `ADJUSTMENT`: `balance += amount`；
- `update` 会先回滚旧流水影响，再应用新流水影响；
- `delete` 会回滚旧流水影响；
- `create`、`update`、`delete` 使用 `@Transactional`。

测试结果：

```text
.\mvnw.cmd clean test
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
```

后续风险与待办：

1. 并发余额更新存在 lost update 风险，后续需评估行锁、乐观锁或原子 SQL。
2. 完整转账模型尚未实现。
3. `REFUND` 尚未实现关联原流水。
4. Controller 层测试待补。
5. 前端尚未接入 Transaction / Ledger API。

## 审查发现

当前未发现阻塞性 API 文档问题。

非阻塞问题：

1. `API.md` 当前仍是人工维护的设计基线文档，不是 OpenAPI / Swagger contract。
2. 当前错误处理记录了 Known Gaps，但尚未代表代码层面已经完成统一异常包装。
3. Target v1.0 API Design 中的接口仅表示目标方向，后续仍需结合 Sprint 范围拆分实现。
4. Transaction / Ledger API 第一版已落地基础 CRUD，但 `TRANSFER` / `REFUND`、并发余额更新和前端接入仍需后续补齐。

## 审查结论

`API.md` 当前可作为 Personal Finance OS 后续 API Review、Controller 补齐、DTO 演进、前端接入分页、错误处理收敛和 v1.0 API 设计完善的基线。

Transaction / Ledger API 第一版已经通过提交 `b8ca982` 落地，文档已同步将基础 CRUD 从 Target Design 移入 Current Implementation，并保留 `TRANSFER` / `REFUND`、投资交易流水、并发余额更新和前端接入等后续风险。

后续修改 API 实现时，应同步更新 `API.md`，并保持与 `Architecture.md`、`Database.md`、`Business Rules.md`、`Financial Rules.md` 的一致性。
