# Project Structure

本文给出当前已跟踪仓库的导航结构，不枚举所有类和测试文件。

## 1. 仓库总览

```text
finance-os/
├── .github/                 GitHub Actions
├── backend/                 Java 21 / Spring Boot 模块化单体
├── frontend/                React / TypeScript / Vite
├── docker/                  单机 Compose 拓扑与环境模板
├── scripts/                 本地开发辅助脚本
├── docs/                    正式文档体系
└── README.md                GitHub 项目主页
```

数据库 schema 的唯一来源是 `backend/src/main/resources/db/migration/`，不是独立 `schema.sql` 或根目录数据库脚本。

## 2. 后端主代码

```text
backend/src/main/java/com/financeos/
├── common/                  统一响应、分页、异常
├── config/                  应用级配置
└── module/
    ├── auth/                Spring Security、JWT
    ├── user/                注册、登录、用户
    ├── account/             Account、行锁、余额写入
    ├── category/            用户/系统 Category
    ├── ledger/              普通 Transaction 与余额联动
    ├── asset/
    │   ├── controller/      Legacy Asset API
    │   ├── marketdata/      Market Quote、Provider、FX
    │   └── valuation/       reference valuation
    ├── investment/
    │   ├── instrument/      Instrument 与 Position binding
    │   ├── ledger/          calculator、replay、trace、digest
    │   ├── migration/       Legacy opening preview / confirm
    │   ├── command/         BUY/SELL/DIVIDEND/reversal/replacement
    │   ├── read/            Portfolio/Position/Transaction/Audit
    │   ├── entity/          投资事实与 correction
    │   └── mapper/          投资持久化
    ├── importing/
    │   ├── controller/      Preview、Confirm、Receipt API
    │   ├── preview/         CSV/XLSX parser 与 frozen plan
    │   ├── service/         Session、Confirm、cleanup、token
    │   ├── storage/         私有临时文件存储
    │   ├── dto/、entity/、mapper/
    │   └── config/
    └── dashboard/           财务概览聚合
```

常规模块遵循 Controller → Service → Mapper / Entity；投资和 Import 进一步按领域职责拆分。`common` 不承载具体业务规则。

## 3. Resources 与 Flyway

```text
backend/src/main/resources/
├── application.yml
├── application-prod.yml
├── application-e2e.yml
└── db/migration/
    ├── V1__baseline.sql
    ├── V2__market_quotes.sql
    ├── V3__exchange_rates.sql
    ├── V4__investment_ledger_foundation.sql
    ├── ...
    ├── V14__add_transaction_import_backend_foundation.sql
    ├── V15__allow_cleared_transaction_import_file_references.sql
    ├── V16__add_transaction_import_confirm_receipt.sql
    └── V17__repair_transaction_import_receipt_digest_timestamps.sql
```

V1–V17 是当前唯一 Migration 链。新变化新增后续版本，不修改历史 SQL。

## 4. 后端测试

```text
backend/src/test/java/com/financeos/
├── common/、config/         统一异常与配置
├── integration/            Flyway、PostgreSQL、API、runtime
└── module/
    ├── account/、ledger/    余额、事务与并发
    ├── asset/               Market、FX、valuation
    ├── auth/、user/、category/、dashboard/
    ├── investment/          ledger、write、read、migration、correction
    └── importing/           parser、Session、Preview、Confirm、Receipt、cleanup
```

测试包含 unit、WebMvc、PostgreSQL Testcontainers、Migration upgrade、concurrency、failure injection 和 unknown commit。

## 5. 前端

```text
frontend/
├── src/
│   ├── api/                 Axios 与领域 API
│   ├── components/
│   │   ├── charts/          ECharts
│   │   ├── dashboard/
│   │   ├── importing/       Upload/Mapping/Preview/Confirm/Receipt
│   │   └── investment/      投资命令与 pending recovery
│   ├── hooks/               Investment / Import coordinator
│   ├── pages/               基础财务、Investments、Transaction Import
│   ├── types/               API 与恢复状态类型
│   ├── utils/               格式、cursor、lossless transport、storage
│   ├── App.tsx              路由
│   └── main.tsx
├── tests/                   Node unit / component-style tests
├── tests/e2e/               Playwright real-browser tests
├── nginx/                   反向代理模板
├── package.json
└── Dockerfile
```

当前路由覆盖 Dashboard、Accounts、Assets、Transactions、Investments、Transaction Import 和 Receipt。

## 6. Docker、CI 与脚本

```text
docker/
├── compose.yml              postgres + backend + frontend
└── .env.example

scripts/
└── dev-start-backend.ps1

.github/workflows/
└── ci.yml
```

CI 执行后端测试、前端 test/lint/build、镜像构建和 Compose config。Compose 当前 secret 传递限制见 [Deployment](deployment.md)。

## 7. 文档

```text
docs/
├── README.md
├── STATUS.md
├── product/
├── architecture/
├── domain/
├── engineering/
├── ADR/
├── internal/
├── archive/
│   ├── design/
│   ├── review/
│   └── logs/
└── images/
```

- `STATUS`：当前状态唯一来源；
- `product`、`architecture`、`domain`、`engineering`：Current Truth；
- `ADR`：Long-term Decision；
- `archive`：Historical Evidence；
- `internal`：Agent / Codex 协作规范。
