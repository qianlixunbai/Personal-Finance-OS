# Exception Handling Review

本文档用于记录 v1.0 阶段统一异常处理补强的背景、处理范围、验证结果与后续建议。

## 1. Review 背景

在 API 基线文档与后端实现 Review 中，参数校验相关异常被识别为 Known Gap。

修复前，`GlobalExceptionHandler` 已经处理：

- `BusinessException`
- 通用 `Exception`

但常见参数错误尚未显式统一包装为 `ApiResponse`，容易导致不同异常来源的响应格式不一致。

## 2. 修复前问题

修复前主要问题：

- 部分参数校验异常可能不返回统一的 `ApiResponse`。
- JSON 请求体格式错误可能不符合当前 API 响应规范。
- 请求参数缺失、类型错误等场景缺少明确的中文错误消息。
- 参数类错误与未知系统异常之间的边界不够清晰。

本次修复目标是补强 v1.0 阶段的常见参数错误处理，而不是一次性建立完整企业级异常体系。

## 3. 本次处理的异常类型

本次新增处理：

- `MethodArgumentNotValidException`
- `ConstraintViolationException`
- `MissingServletRequestParameterException`
- `MethodArgumentTypeMismatchException`
- `HttpMessageNotReadableException`

## 4. 当前统一响应规则

参数类异常统一返回：

```json
{
  "code": 400,
  "message": "... "
}
```

响应体使用：

```java
ApiResponse.error(400, "...")
```

当前规则：

- `MethodArgumentNotValidException` 优先返回第一个字段校验错误信息。
- `ConstraintViolationException` 返回简洁中文参数校验错误。
- `MissingServletRequestParameterException` 返回缺少必要请求参数。
- `MethodArgumentTypeMismatchException` 返回请求参数格式错误。
- `HttpMessageNotReadableException` 返回请求体格式错误。
- 不暴露 Java 堆栈、类名或内部实现细节到响应体。

## 5. HTTP 状态码规则

当前 HTTP 状态码规则：

- 参数类异常统一返回 HTTP 400。
- `BusinessException(400, "...")` 返回 HTTP 400。
- `BusinessException(401, "...")` 返回 HTTP 401。
- `BusinessException(403, "...")` 返回 HTTP 403。
- `BusinessException(404, "...")` 返回 HTTP 404。
- 未知异常返回 HTTP 500。

`BusinessException` 原有 400 / 401 / 403 / 404 到 HTTP Status 的映射保持不变。

## 6. 影响范围

影响范围：

- 后端统一异常处理逻辑。
- 参数错误响应格式。
- JSON 请求体格式错误响应。
- 请求参数缺失与类型错误响应。
- 后端异常处理测试覆盖。

对外行为影响：

- 常见参数错误现在会以统一 `ApiResponse` 结构返回。
- 客户端可继续通过 `code` 和 `message` 读取错误信息。

## 7. 未修改内容

本次未修改：

- Controller。
- Service。
- DTO。
- Entity。
- Mapper。
- 前端代码。
- 数据库 schema。
- `Architecture.md`。
- `Database.md`。
- `API.md`。
- `README.md`。
- Spring Security 认证失败 `401` / 权限不足 `403` 的响应体处理。

## 8. 测试命令

```powershell
cd backend
.\mvnw.cmd clean test
```

## 9. 测试结果

```text
Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 10. 后续建议

后续可继续补强：

- 统一 Spring Security 认证失败 `401` 的响应体。
- 统一 Spring Security 权限不足 `403` 的响应体。
- 后续如错误场景继续增加，可评估稳定的 `ErrorCode` 枚举。
- 当前不把本次修复夸大为完整企业级异常体系，本次仅作为 v1.0 阶段的异常处理补强。

## 11. Spring Security 401/403 响应统一

### 11.1 修改背景

参数类异常已通过 `GlobalExceptionHandler` 统一返回 `ApiResponse`，但 Spring Security 在过滤器链中产生的认证失败和权限不足不经过 Controller 异常处理，因此需要在 Security 配置中单独接入统一响应处理。

### 11.2 修改内容

本次新增 `SecurityErrorResponseHandler`，同时实现：

- `AuthenticationEntryPoint`
- `AccessDeniedHandler`

并在 `SecurityConfig` 的 `exceptionHandling` 中接入：

- `authenticationEntryPoint(securityErrorResponseHandler)`
- `accessDeniedHandler(securityErrorResponseHandler)`

本次未修改：

- Controller
- Service
- DTO
- Entity
- Mapper
- 前端代码
- 数据库 schema
- `Architecture.md`
- `Database.md`
- 角色 / 权限模型

### 11.3 响应规则

未认证、未携带 token、token 无效或登录已过期访问受保护接口时，返回：

```json
{
  "code": 401,
  "message": "未认证或登录已过期"
}
```

HTTP Status 为 `401 Unauthorized`，`Content-Type` 为 `application/json;charset=UTF-8`。

权限不足时，返回：

```json
{
  "code": 403,
  "message": "无权限访问该资源"
}
```

HTTP Status 为 `403 Forbidden`，`Content-Type` 为 `application/json;charset=UTF-8`。

### 11.4 测试覆盖

本次新增测试覆盖：

- 未携带 token 访问受保护接口 `/api/v1/dashboard` 时，返回 HTTP 401。
- 401 响应体使用统一 `ApiResponse` JSON。
- `AuthenticationEntryPoint` 分支直接返回统一 401 JSON。
- `AccessDeniedHandler` 分支直接返回统一 403 JSON。

当前项目没有角色 / 权限模型，因此未为了触发完整业务链路 403 而新增角色体系或复杂权限规则。403 响应通过 handler 单元测试覆盖。

### 11.5 测试命令

```powershell
cd backend
.\mvnw.cmd clean test
```

### 11.6 测试结果

```text
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 11.7 后续建议

- 后续如引入角色、资源级权限或管理端能力，应补充真实 `403` Web 链路测试。
- 当前阶段不引入 `ErrorCode` 枚举，继续保持简单 `code + message` 响应模型。
- 若未来增加审计、请求追踪或国际化错误消息，可再评估是否扩展统一错误响应结构。
