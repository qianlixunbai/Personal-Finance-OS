# Development Guide

Version: 1.0

---

# 1. Project Goal

Personal Finance OS 是一个现代化个人金融管理系统。

项目目标：

- 企业级架构
- 长期维护
- 高可扩展性
- 高可读性
- 高可测试性
- 可持续迭代

本项目不是 Demo，而是长期维护的核心项目。

---

# 2. Development Principles

整个项目遵循：

- KISS（Keep It Simple）
- DRY（Don't Repeat Yourself）
- SOLID
- Clean Architecture
- High Cohesion
- Low Coupling

代码质量优先于开发速度。

---

# 3. Architecture Principles

采用模块化单体（Modular Monolith）。

禁止为了规模较小的项目引入微服务。

所有模块必须保持独立职责。

模块之间通过 Service 或事件通信，不允许随意跨模块调用。

---

# 4. Layer Rules

Controller

负责：

- 参数接收
- 参数校验
- 返回结果

禁止：

业务逻辑

数据库操作

--------------------

Service

负责：

业务逻辑

数据计算

事务控制

--------------------

Repository

负责：

数据库访问

禁止：

业务逻辑

--------------------

Entity

数据库映射。

禁止：

业务代码。

---

# 5. Database Rules

统一 PostgreSQL。

所有表采用 snake_case。

所有字段采用 snake_case。

金额统一 Decimal。

时间统一 UTC。

所有表默认包含：

- id
- created_at
- updated_at

禁止：

数据库字段随意修改。

当前项目仍使用 `schema.sql` 作为数据库初始化基线；数据库变更必须同步 `schema.sql` 和 `Database.md`。后续引入 Flyway / Liquibase 后，数据库变更必须记录 Migration。

---

# 6. API Rules

全部 RESTful。

统一返回：

{
    "code": 200,
    "message": "success",
    "data": {}
}

统一异常。

统一错误码。

---

# 7. Naming Rules

类：

PascalCase

方法：

camelCase

变量：

camelCase

数据库：

snake_case

常量：

UPPER_SNAKE_CASE

---

# 8. Git Rules

Branch

main

develop

feature/*

bugfix/*

hotfix/*

Commit

feat:

fix:

docs:

refactor:

test:

style:

---

# 9. Logging

统一日志。

INFO：

正常业务。

WARN：

业务异常。

ERROR：

系统异常。

禁止：

System.out.println()

---

# 10. Security

JWT。

密码：

BCrypt。

禁止：

SQL 拼接。

禁止：

硬编码密钥。

---

# 11. Performance

所有列表分页。

Dashboard 避免重复查询。

避免 N+1 Query。

优先优化 SQL。

缓存作为 V2 功能接入。

---

# 12. Documentation

任何重大修改：

必须更新：

- 文档
- API
- 数据库设计

文档优先于代码。

---

# 13. Development Workflow

需求

↓

设计

↓

Review

↓

开发

↓

测试

↓

Review

↓

Merge

任何阶段不得跳过 Review。

---

# 14. Local Backend Run

后端本地启动依赖以下环境变量：

- `JWT_SECRET`
- `DB_USERNAME`
- `DB_PASSWORD`

`JWT_SECRET` 用于创建 JWT 签名密钥，长度必须至少 32 字符。`DB_USERNAME` 和 `DB_PASSWORD` 用于连接本地 PostgreSQL。

第一次使用：

1. 复制 `backend/.env.example` 为 `backend/.env.local`
2. 在 `backend/.env.local` 中填写本地 PostgreSQL 用户名和密码
3. 确保 `JWT_SECRET` 至少 32 字符
4. 在项目根目录运行：

```powershell
.\scripts\dev-start-backend.ps1
```

后续启动后端时，直接在项目根目录运行：

```powershell
.\scripts\dev-start-backend.ps1
```

注意：

- `backend/.env.local` 只保存本地真实配置，不要提交到 Git
- `backend/.env.example` 只保存模板值，可以提交
- 启动脚本只把变量设置到当前 PowerShell 进程，不会打印真实 secret
- 如果前端出现 Vite proxy `ECONNREFUSED`，先确认后端是否启动成功
- 后端启动成功时，应看到 Tomcat started on port 8080

---

# 15. Backend Integration Tests

月度收支趋势 Mapper 的集成测试使用 Testcontainers 启动 `postgres:17-alpine`，并通过 Spring 的动态数据源属性连接容器；测试会复用正式的 `classpath:schema.sql` 初始化结构，不会连接本地开发数据库。

运行完整后端测试前，请先启动 Docker Desktop（或提供兼容的 Docker daemon），然后执行：

```powershell
cd backend
.\mvnw.cmd clean test
```
