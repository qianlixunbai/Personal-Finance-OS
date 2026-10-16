# Personal Finance OS — 家庭电脑 GitHub 交接指南

收档日期：2026-10-16（Asia/Shanghai）。本指南可从 GitHub 独立取得，不依赖学校电脑、原工程盘符、仓库外报告、IDE 配置、旧数据库或 Docker Volume。

## 项目身份与交接状态

| 项目 | 收档信息 |
| --- | --- |
| 名称 | Personal Finance OS |
| 仓库 | [qianlixunbai/Personal-Finance-OS](https://github.com/qianlixunbai/Personal-Finance-OS) |
| 主开发分支 | `zh-cn` |
| 稳定代码提交 | `cc37844d90654f2e6872c50717a49633e10b142a` |
| 该代码正式 CI | [Run 38836950964](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38836950964) |
| 正式结项 | **G4-3 — CLOSED — GO**；A、B1、B2-1、B2-2、C、D 已技术验收；C1–C11 CLOSED — GO（11/11） |
| 本轮性质 | 稳定代码基线收档，结束当前集中开发阶段 |
| Finance Freeze | **CONDITIONAL GO；尚未正式执行**，需独立签核及用户单独授权 |

当前状态权威入口是 [STATUS](../STATUS.md)。用户本轮授权按已有独立结论 `GO — Recommend Formal Closure` 正式关闭 G4-3。Import Frontend 的最终独立结项仍待完成，不能据此宣称整个产品或 Finance Freeze 已完成。

本次归档提交只改变 `docs/STATUS.md` 和本文。默认 Clone 得到的是包含这两个文档的最新 `zh-cn`，其 HEAD 与上面的稳定代码 SHA 不同。文档提交 SHA 请通过以下命令取得；文件无法把包含自身的新提交 SHA 预先写入自身。收档报告在仓库外记录实际新 HEAD、Parent 与两个文档 blob，但不是恢复项目的必要输入。

```powershell
git log -1 --format='%H%n%P%n%s' -- docs/engineering/finance-home-github-handoff.md
git rev-parse HEAD:docs/STATUS.md
git rev-parse HEAD:docs/engineering/finance-home-github-handoff.md
git diff --exit-code cc37844d90654f2e6872c50717a49633e10b142a HEAD -- backend frontend docker scripts .github
```

最后一条在本轮仅文档收档的 HEAD 应退出 0；未来新增实现后不再适用。新文档提交的 CI 与原代码 CI 必须分开判断，不能沿用旧 Run 冒充新 HEAD 验收。

## 结项证据与历史边界

本轮 GitHub API 只读核实：原 Run 的 `head_sha` 等于稳定代码 SHA，状态 `completed / success`，Backend、Frontend、AI Browser E2E、Playwright E2E、Deployment artifacts 五个 Job 全部 success。

以下测试数字继承用户提供的最终独立验收记录，本轮没有运行 Maven、npm、浏览器、Docker 或数据库测试：

| 历史验收范围 | 最终结果 |
| --- | --- |
| Backend | 562 tests / 0 failures / 0 errors / 1 Windows-only skip |
| Frontend | 45 tests 通过；lint/build 成功 |
| AI Browser E2E | 58 passed |
| Playwright E2E | Investment 2、Import browser 3、真实 Backend Import 1，均通过 |
| Deployment artifacts | Backend / Frontend 镜像构建、Compose config 成功 |

已受版本控制的实施证据：

| 批次 | 证据入口 | 当前验收 |
| --- | --- | --- |
| A（含补充整改） | [A](../archive/review/G4-3-A-E2E-Storage-CI-Recovery-Review.md)、[A2](../archive/review/G4-3-A2-E2E-Flyway-Evidence-Review.md)、[A3](../archive/review/G4-3-A3-E2E-Database-Credential-Logging-Review.md) | GO |
| B1 | [Version Boundary Review](../archive/review/G4-3-B1-Flyway-Version-Boundary-Review.md) | GO |
| B2-1 | [V17 Runtime Review](../archive/review/G4-3-B2-1-V17-Historical-Runtime-Review.md) | GO |
| B2-2 | [Implementation Review](../archive/review/G4-3-B2-2-Implementation-Review.md) | GO |
| C | [Implementation Review](../archive/review/G4-3-C-Implementation-Review.md) | GO |
| D | [Implementation Review](../archive/review/G4-3-D-Implementation-Review.md) | GO |

这些 Review 保留实施时的候选、HOLD 或远端待验收文字；本表的当前技术验收状态来自后续最终验收、对应 SHA 的全绿 CI 和本轮正式结项授权，不回写历史。A2 / A3 已记载 C1–C11 正式关闭（11/11），覆盖 STATUS 原有 C10 提交前、C11 未开始的过期文字。C9 历史候选结论见 [C9 Review](../archive/review/G3-2-C9-Import-Temporary-Storage-Lifecycle-Review.md)。

外部最终 Closing Report 和大体积日志没有上传 GitHub；以上摘要、提交 SHA、CI URL、仓库内 Review 足以理解本次收档。CI 全绿不等于生产部署、真实数据恢复、家庭电脑运行或所有平台绝对正确性已经验证。

## 原开发环境快照与家庭电脑重建要求

### 当前机器实际检测结果

本轮仅执行只读版本、服务、端口与非敏感 Java 配置状态查询，没有读取 `.env`、IDE 私有配置、数据库内容、Storage Binding 或 payload。

| 项目 | 实际检测结果 | 家庭电脑要求 |
| --- | --- | --- |
| Windows | Windows 11 家庭版 中文版；`10.0.26200`，Build 26200；系统报告 64 位 | 使用能运行所需工具的 Windows；CPU 具体指令架构未核实，不据 64 位推断 x64 / ARM64 |
| Git | `2.45.1.windows.1` | 安装 Git，能 Clone GitHub；不依赖旧电脑认证配置 |
| JDK | Oracle Java `21.0.10`，64-Bit Server VM | JDK 21；容器构建使用 Temurin 21，无须复刻旧安装路径 |
| Java 环境 | 当前进程 `JAVA_HOME` 未设置；User、Machine 均已配置且各自 `bin/java.exe` 存在；`java -version` 可运行 | 在新电脑正确设置 `JAVA_HOME` / PATH，重开终端与 IDE；IDE Project SDK、Maven runner 均选 JDK 21 |
| Maven | Wrapper 配置声明 Maven `3.9.9` / Wrapper JAR `3.3.2`；本轮没有运行 Wrapper，不宣称其运行版本已实测 | 使用仓库 Wrapper，不依赖全局 Maven |
| Node / npm | Node `v22.22.2`；npm `10.9.7`（仅版本查询） | Node 22，按 lockfile 恢复依赖 |
| Docker Desktop | 安装注册信息 `4.82.0`；CLI `29.6.1`；Compose `v5.3.0` | 支持 Compose profiles、Linux containers；不要求与旧电脑补丁号完全相同 |
| WSL / Docker 模式 | WSL `2.7.10.0`，kernel `6.18.33.2-2`；`docker-desktop` 为 WSL2、Stopped；当前 context `desktop-linux`；Linux Engine pipe 不存在 | 启动 Docker Desktop 并确认 Linux 引擎可连接；运行中的后端与 WSL integration 设置本轮未核实 |
| PostgreSQL | Windows 服务 `postgresql-x64-17` 为 Running；5432 监听进程是 `postgres` | 本机观察到的是 Windows 原生服务；小版本、Finance 实际连接目标及是否另有历史 Docker 实例未核实。推荐新电脑使用下文 Compose 的 PostgreSQL 17 |
| Finance 进程 | 查询时未发现 8080 / 5173 / 8088 的监听 | 本轮未启动 Finance；旧电脑实际 IDEA 启动入口、活动 profile、C9 根及身份值均未核实 |

### 仓库声明的技术栈和运行合同

下表来自 [POM](../../backend/pom.xml)、[package.json](../../frontend/package.json)、[Wrapper 配置](../../backend/.mvn/wrapper/maven-wrapper.properties)、[Backend Dockerfile](../../backend/Dockerfile)、[Frontend Dockerfile](../../frontend/Dockerfile) 与 [Compose](../../docker/compose.yml)，不是对旧电脑活动进程的推测。

| 层次 | 仓库事实 |
| --- | --- |
| Backend | Java 21、Spring Boot 3.3.5、MyBatis-Plus 3.5.9、Spring Security、JJWT 0.12.6 |
| Schema | PostgreSQL 17；Flyway 10.20.0；正式 Migration V1–V21 |
| Frontend | React `^19.2.7`、TypeScript `~6.0.2`、Vite `^8.1.1`；精确解析以 `package-lock.json` 为准 |
| 构建 | Maven Wrapper 3.9.9；Node 22/npm；镜像构建分别恢复 Maven 依赖和 `npm ci` |
| Compose 拓扑 | Browser → Nginx → Spring Boot → PostgreSQL 17；两个 named volumes：`postgres_data`、`import_storage` |
| Compose 端口 | 示例 `FRONTEND_PORT=8088` 映射 Nginx 80；backend 8080、postgres 5432 仅容器网络内可达，没有宿主机发布 |
| 本地源码开发 | Backend `backend/mvnw.cmd spring-boot:run` 默认 8080；frontend `npm run dev` 默认 5173，`/api` 代理到 `http://localhost:8080`，可用 `VITE_API_PROXY_TARGET` 覆盖 |
| 环境载入 | Spring Boot 不会仅因存在 `.env.local` 就自动载入；仓库 `scripts/dev-start-backend.ps1` 显式加载 `backend/.env.local` 后运行 Wrapper。IDE 启动应单独配置环境，不能假定继承脚本状态 |

### 容易遗漏的配置与已知启动问题

- 本轮 Docker daemon 不可用，只确认 CLI、安装版本和 WSL 状态；不启动或修复旧环境。新电脑必须让 `docker version` 同时显示可连接的 Server。
- [历史环境日志](../archive/logs/2026-07-06-environment.md) 曾记录系统已更新 `JAVA_HOME`、旧终端进程仍不继承的问题；这是历史记录。本轮也观察到当前进程与 User / Machine 状态不同，不能直接复制旧绝对路径。
- `backend/.env.example` 与启动脚本的 required-variable 列表没有覆盖 Import Confirm secret 和全部 C9 配置；只复制它们不足以启动。完整名称见下节。不要以 `test` profile 绕过生产存储合同。
- [Development](development.md) 尚有 V1–V17 注释、旧 Compose secret 缺口与三 Job CI 描述；当前 Migration 已到 V21、Compose 已传三项 secret、CI 已为五 Job。以实际配置、本指南和 STATUS 为准；本轮不扩大修改其他文档。
- 缺少 secret、根未预配、身份或路径不一致、权限/文件锁能力不满足，会 fail-fast / fail-closed。不能靠重建 marker、关闭 guard 或解除 quarantine 解决。
- 旧构件验收中的 TLS、镜像源等历史环境失败不代表业务代码缺陷；家庭电脑如遇下载失败，应先核实网络与工具状态，不改业务行为或弱化测试。

## 从 GitHub 获取项目与检查工具

在家庭电脑安装 Git for Windows（[官方入口](https://git-scm.com/downloads/win)），以及 Docker Desktop 的 Linux containers 环境；源码开发另装 JDK 21、Node.js 22/npm。安装完成重开 PowerShell。项目目录可任意选择，无须原 F 盘。

```powershell
git --version
git clone -b zh-cn https://github.com/qianlixunbai/Personal-Finance-OS.git
Set-Location Personal-Finance-OS
git branch --show-current
git rev-parse HEAD
git status --short
Get-Content docs/engineering/finance-home-github-handoff.md

java -version
node --version
npm --version
docker --version
docker compose version
docker version
```

`docker version` 必须能连接 Linux Server。私有仓库访问授权若有需要，只在 Git / 系统凭据管理器完成，不在聊天中提供 Token。推荐标准 Clone 保留完整历史；既有历史升级测试依赖旧提交，浅克隆不适合直接运行它们。

完整 Compose 运行时 JDK / Node 在构建镜像内，宿主机 JDK / Node 主要供后续源码开发。可在新电脑检查 Wrapper（只查版本，不跑测试）：

```powershell
Push-Location backend
.\mvnw.cmd -v
Pop-Location
```

该命令可能首次下载 Maven，结果应显示 Maven 3.9.9、Java 21。本轮未执行该命令。

## 环境变量与凭据

只参考受版本控制的安全示例 [docker/.env.example](../../docker/.env.example)、[backend/.env.example](../../backend/.env.example) 与 [application.yml](../../backend/src/main/resources/application.yml)。真实值由家庭电脑本地生成或在 IDE / 系统安全机制配置，不从学校电脑复制，不提交 Git、不打印、不截图上传。

| 名称 | 用途 / 配置位置 |
| --- | --- |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Compose 新 PostgreSQL 初始化；新开发库独立凭据 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 原生 Java 的 JDBC 连接；Compose 已将上述 PostgreSQL 名称映射到 backend，无须手动重复配置 |
| `JWT_SECRET` | JWT HMAC；UTF-8 至少 32 字节 |
| `MIGRATION_PREVIEW_SECRET` | Legacy opening preview；至少 32 字符 |
| `FINANCE_IMPORT_CONFIRM_TOKEN_SECRET` | Import Preview / Confirm token；至少 32 字符，与前两项分别独立 |
| `FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID` | 新部署 UUID；不是 secret |
| `FINANCE_IMPORT_STORAGE_RAW_ID` / `FINANCE_IMPORT_STORAGE_PLAN_ID` | 两个不同的新 Storage UUID；不是 secret |
| `FINANCE_IMPORT_STORAGE_RAW_ROOT` / `FINANCE_IMPORT_STORAGE_PLAN_ROOT` | 原生运行必填独立绝对根；Compose 已固定容器路径，无须把 Windows 路径填进去 |
| `FRONTEND_PORT` | Compose 宿主机入口；安全示例为 8088，需确认未占用 |
| `SPRING_PROFILES_ACTIVE` | Compose 固定 `prod`；不得改为 `test` 来启动真实开发账本 |
| `JAVA_HOME` / PATH | 本地 Java 工具环境，IDE SDK / runner 还需各自确认 |
| `VITE_API_PROXY_TARGET` | 本地 Vite `/api` 代理覆盖，默认后端 8080 |
| `FINANCE_IMPORT_CLEANUP_ENABLED` | 原生配置默认 true；不能以关闭 cleanup 替代合法 C9 初始化 |
| `MIGRATION_PREVIEW_TTL` / `ACCOUNT_BALANCE_LOCK_TIMEOUT` | 原生可选，默认 10m / 4s |
| `MARKET_DATA_ENABLED` / `MARKET_DATA_API_KEY` | 可选行情，默认关闭；Compose 还透传两个 `MARKET_DATA_*_REQUEST_LIMIT_PER_MINUTE` 变量，名称见示例 |
| `FX_DATA_ENABLED` / `FX_DATA_PROVIDER` / `FX_DATA_BASE_URL` / `FX_DATA_API_KEY` | 原生可选 FX；默认关闭，标准 Compose 未透传 |
| `FINANCE_AI_ENABLED` / `FINANCE_AI_PROVIDER` / `FINANCE_AI_BASE_URL` / `FINANCE_AI_API_KEY` / `FINANCE_AI_MODEL` | 可选 AI；默认关闭，标准 Compose 未透传这些变量 |
| `FINANCE_AI_CONNECT_TIMEOUT` / `FINANCE_AI_READ_TIMEOUT` / `FINANCE_AI_USER_REQUEST_LIMIT_PER_MINUTE` | 原生 AI 可选，默认 3s / 30s / 8 |

**基础账本不需要付费 AI 凭据。** 暂时保持 AI、行情和 FX 关闭。仅把 AI 变量放进 Compose `.env` 不会启用容器 AI；后续若要改部署机制，另行核验与授权。

## 全新数据库方案

本次选择 **Fresh Development Environment**，不做 Existing Finance Database Migration。用户已确认旧数据库没有需要保留的真实个人财务数据；无需迁移旧开发测试数据，也不自动删除旧数据库或卷。

家庭电脑使用新的 PostgreSQL 17 开发库。下文以新的 Compose project name 隔离两个新卷，PostgreSQL 镜像在空数据卷初始化数据库；Backend 使用仓库正式 Flyway 自动执行 V1–V21。Migration 是 Schema 唯一来源，禁止以历史 `schema.sql` 替代，禁止盲目 baseline，禁止复制旧 PostgreSQL 数据目录。

仓库的现成流程是在 backend 首次启动中执行 Flyway，再使用新 Schema 注册 Storage；没有另行声明独立的迁移 CLI，本指南不创造一条。在启动生产应用前必须确认新数据库隔离、所有配置完整和 C9 已预配；启动过程中迁移未成功不得继续业务或 cleanup 排障操作。V21 创建 `transaction_import_storage_bindings` 与 `transaction_import_file_objects`，不能人工插入 Binding / Registry。

## C9 Storage 全新初始化合同

依据 [Deployment](deployment.md)、[官方 Provisioner](../../backend/src/main/java/com/financeos/module/importing/storage/ImportStorageProvisioner.java)、[受信根实现](../../backend/src/main/java/com/financeos/module/importing/storage/TrustedImportStorageRoot.java)、[生命周期服务](../../backend/src/main/java/com/financeos/module/importing/service/TransactionImportFileLifecycleService.java) 和 [V21](../../backend/src/main/resources/db/migration/V21__add_import_file_lifecycle_registry.sql)。本轮没有读取旧根、身份值或文件内容，旧电脑活动 C9 拓扑未核实；以下为仓库支持的运行拓扑。

1. 选择单主机、持久、独占的 RAW / PLAN 根，互不相同且互不嵌套。新电脑生成一个 Deployment UUID 和两个互不相同的 Storage UUID；均为全新身份，不使用安全示例或旧电脑的 UUID。
2. 只对新的空受管根运行官方 Provisioner。Compose named volume 内固定为 `/var/lib/finance-os-import/raw`、`/var/lib/finance-os-import/plan`；Provisioner 不连接数据库，创建 `.import-binding` / `.import-control`，随后 Compose 预配任务设置 `financeos:financeos` 所有权。
3. 确认目录、父目录、marker/control 的权限及运行账号正确，再启动 backend。Linux 目录 `0700`，文件应仅服务账号可访问；其他普通进程不得替换 control 或修改受管根。
4. 正常应用验证路径与身份，向 V21 Storage Binding 表登记与比对 digest；Registry 管理文件引用与生命周期。全新库无旧 Session；首次启动且尚无上传时 RAW / PLAN 应各有一个非 quarantine 的 Binding、Registry 为零。
5. 新身份、根和新数据库一旦启用，正常重启必须保留匹配组合，不能每次重新生成 UUID，也不能每次运行 Provisioner。

digest 包含规范化绝对路径、Deployment / Storage UUID 与 RAW / PLAN kind。禁止复制旧 `.import-binding`、`.import-control`、旧 Storage Identity 或旧数据库 Binding。禁止强制预配非空根、手工伪造 Binding、修改 Registry、关闭身份校验、绕过 guard 或自动解除 quarantine。预配拒绝或部分初始化时停止，不删除标记后重试。

Windows 原生 Java 使用 Windows 绝对目录和 JDK 21 provider 的 `NOSHARE_DELETE` pin，需由部署方配置服务账号专用 ACL 并保护父目录。Linux 容器使用容器内绝对路径、持久 named volume 和 `SecureDirectoryStream`。本指南推荐 Linux named volume，避免直接绑定 Windows 共享目录引入文件锁能力差异；不混用 Windows 路径与 Linux 身份。支持范围仅为单主机，不包括网络共享、多主机或高权限停机克隆识别，也未承诺断电耐久性。

若未来改用 Windows 原生运行，先构建当前 JAR，在配置好五个 `FINANCE_IMPORT_STORAGE_*` 变量、确认两个独占空根及 ACL 后，使用现有入口（从 `backend` 目录执行）：

```powershell
.\mvnw.cmd -DskipTests package
java '-Dloader.main=com.financeos.module.importing.storage.ImportStorageProvisioner' -cp target/finance-os-backend-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher --provision
```

预配后才能用本地 Java 启动 Finance。Windows 原生 PostgreSQL 创建/账号配置和 ACL 的具体操作取决于新电脑安装；本轮未核验，不提供猜测命令。使用下面的 Compose 主流程即可避免依赖这些旧电脑设置。

## 推荐首次启动顺序：Docker Compose 全环境

以下命令供**家庭电脑首次重建时执行，本轮未执行**。从仓库根目录 PowerShell 操作，Docker Desktop 选择 Linux containers。依赖恢复由两个 Dockerfile 完成，无须复制 Maven/npm 缓存；本地源码开发另在 `frontend` 运行 `npm ci`，不改 lockfile。

### 1. 准备新配置与隔离项目

选择一个本机尚不存在的项目名称，所有后续命令在同一 PowerShell 中保持 `$financeProject` 不变：

```powershell
$financeProject = 'finance-home-dev'
docker compose ls --all
docker volume ls --filter "label=com.docker.compose.project=$financeProject"
if (Test-Path -LiteralPath docker/.env) { throw 'docker/.env 已存在，请先人工核对，禁止覆盖。' }
# 从安全示例构造新文件：本地生成凭据，不向终端打印。
$financeTemplate = Get-Content -LiteralPath docker/.env.example -Raw
$financeRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    foreach ($financeName in @('POSTGRES_PASSWORD','JWT_SECRET','MIGRATION_PREVIEW_SECRET','FINANCE_IMPORT_CONFIRM_TOKEN_SECRET')) {
        $financeBytes = New-Object byte[] 48
        $financeRng.GetBytes($financeBytes)
        $financeGenerated = [Convert]::ToBase64String($financeBytes)
        $financeTemplate = [regex]::Replace($financeTemplate, "(?m)^$financeName=.*$", "$financeName=$financeGenerated")
    }
    foreach ($financeName in @('FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID','FINANCE_IMPORT_STORAGE_RAW_ID','FINANCE_IMPORT_STORAGE_PLAN_ID')) {
        $financeTemplate = [regex]::Replace($financeTemplate, "(?m)^$financeName=.*$", "$financeName=$([guid]::NewGuid())")
    }
    Set-Content -LiteralPath docker/.env -Value $financeTemplate -Encoding ascii
} finally {
    $financeRng.Dispose()
    Remove-Variable financeTemplate,financeGenerated,financeBytes -ErrorAction SilentlyContinue
}
notepad docker/.env
```

若该 project 已有容器或卷，应在生成文件前停止首次初始化，改用未占用的新名称或先人工核验，禁止删卷。上段从安全示例一次性生成新的数据库密码、三个独立随机 secret（各 48 个随机字节的 Base64）及三个新 UUID，不使用旧机值或示例身份。新文件只保存在本机；在本地编辑器确认数据库名称/用户、`FRONTEND_PORT=8088` 及行情关闭，限制文件访问权限，不要截图或分享其中内容，也不要填入 Windows RAW / PLAN 路径。

配置不得提交或向聊天回传；无需学校电脑的任何值。可检查忽略状态及配置结构，不输出展开后的配置：

```powershell
git check-ignore docker/.env
docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml config --quiet
if ($LASTEXITCODE -ne 0) { throw '配置检查失败，停止。' }
```

`--quiet` 只证明结构/插值可解析，不验证 secret 强度或 runtime。不要运行会打印展开后凭据的普通 `config` 或完整 `docker inspect`。

### 2. 仅启动新 PostgreSQL，然后预配空 C9 卷

```powershell
docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml up -d postgres
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 启动失败。' }
docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml ps
docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
if ($LASTEXITCODE -ne 0) { throw '等待 PostgreSQL healthy 后再继续。' }

docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml --profile import-provision run --build --rm import-storage-provision
if ($LASTEXITCODE -ne 0) { throw 'C9 预配失败，停止并人工核验，禁止强制重试或删卷。' }
```

确认 PostgreSQL `healthy`、预配退出 0，并出现官方预配成功提示。这时不应有 backend writer。预配任务以 root 建立新根并设服务账号所有权，随后 backend 以非 root `financeos` 运行；不要对旧卷执行此操作。

### 3. 启动 backend，再启动 frontend

```powershell
docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml up --build -d backend
if ($LASTEXITCODE -ne 0) { throw 'Backend 启动失败。' }
docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml ps
```

等待 backend `healthy`。该启动包含正式 Flyway 初始化、Storage 验证/数据库绑定及正常 cleanup 生命周期；在此之前已完成新库/新卷隔离和预配。若出现迁移、身份、安全或一致性异常，停止相关业务操作，不继续启动写入测试。需要停下本次新 backend 时可执行：

```powershell
docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml stop backend
```

只有 backend healthy 后继续：

```powershell
docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml up --build -d frontend
if ($LASTEXITCODE -ne 0) { throw 'Frontend 启动失败。' }
docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml ps
```

镜像构建成功不等于运行成功。首次依赖下载和镜像拉取需要网络；若失败先处理环境，不修改代码或 Workflow。

## 家庭电脑最小验收

### 只读验证

以下 PostgreSQL 命令只在**本次新 Compose 数据库**执行，不访问旧环境。使用容器内既有环境名称，不把密码写进命令行。`-X` 忽略 psqlrc，`ON_ERROR_STOP` 使 SQL 错误失败；每段均为只读事务。

使用 PowerShell here-string 管道，避免手工输入 SQL：

```powershell
@'
BEGIN READ ONLY;
SHOW server_version;
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT count(*) AS failed_migrations FROM flyway_schema_history WHERE NOT success;
SELECT kind, quarantined, count(*) FROM transaction_import_storage_bindings GROUP BY kind, quarantined ORDER BY kind;
SELECT count(*) AS registry_rows FROM transaction_import_file_objects;
SELECT count(*) AS foreign_keys FROM pg_constraint WHERE connamespace='public'::regnamespace AND contype='f';
SELECT count(*) AS unique_constraints FROM pg_constraint WHERE connamespace='public'::regnamespace AND contype='u';
SELECT count(*) AS user_triggers FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND NOT t.tgisinternal;
COMMIT;
'@ | docker compose -p $financeProject --env-file docker/.env -f docker/compose.yml exec -T postgres sh -c 'psql -X --set=ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
if ($LASTEXITCODE -ne 0) { throw '数据库只读验收失败。' }
```

期望 PostgreSQL major 17，Flyway V1–V21 均成功、最后为 21、failed 为 0；在首次上传前 RAW / PLAN 各一条 `quarantined=false`、Registry 为 0。核心 FK / unique / immutable trigger 应存在，具体对象与 [Migration](../../backend/src/main/resources/db/migration/) 和 [Database](../architecture/database.md) 核对；只数数量不能证明所有约束行为正确。若不符合，停止，不手工补 Binding / Registry。

若保持示例端口 8088：

```powershell
(Invoke-RestMethod http://localhost:8088/actuator/health).status
(Invoke-RestMethod http://localhost:8088/actuator/health/readiness).status
(Invoke-WebRequest -UseBasicParsing http://localhost:8088/).StatusCode
```

期望 `UP`、`UP`、`200`；若改变 `FRONTEND_PORT`，同步改 URL。health 经 Nginx 转发到 backend，不直接访问宿主机 8080。`prod` 下 Swagger 关闭是预期行为。

### 会写数据库的最小人工验收

只在全新、隔离的开发库中使用虚构账号与金额，不用真实财务数据；首次恢复不要求重跑完整历史测试。

- [ ] 分支 / SHA 与本文身份说明一致；工具版本、Docker Linux Server、新 PostgreSQL / Flyway V21、C9 Binding 与健康检查均符合。
- [ ] 浏览器访问前端，注册新开发账户并登录，确认认证后的列表可加载。
- [ ] 创建两个 CNY 测试账户，创建虚构收入 / 支出，核对后端余额与 Dashboard。
- [ ] 按 [API](../architecture/api.md) 的独立 Transfer 接口在两账户间执行一次测试 Transfer，核对两侧余额和总额一致；不要把普通 Transaction 类型改成 TRANSFER，也不要求 UI 存在独立转账页。
- [ ] 投资账本执行最小受支持业务，核对持仓、回执与读取结果。
- [ ] 用少量虚构 CSV / XLSX 走 Import 上传、mapping、Preview、warning acknowledgement、Confirm、Receipt；按正式恢复合同同 key / 同请求 Replay，核对没有重复入账。细节见 [Import 业务](../domain/transaction-import.md)、[API](../architecture/api.md) 与[前端结项边界](../archive/review/V3.0-Transaction-Import-Frontend-Closing-Review.md)。不得靠新 key 重提未知结果。
- [ ] AI Analyst 可选且默认关闭；不为了基础账本验收配置付费 Provider。

任何迁移错误、安全异常、金额不一致、重复入账或 Storage Identity 错误都要求停止相关业务操作并保留脱敏信息。这里的验收清单均为**待在家庭电脑执行**，不是本轮 PASS。

## GitHub 可恢复性与未完成事项

GitHub 受版本控制的交接范围包括完整 Backend / Frontend 源码、Maven Wrapper（含 JAR）、`package.json` / lockfile、V1–V21、两个 Dockerfile、Compose、必要安全配置示例、架构/业务/工程文档、G4-3 Implementation Reviews、STATUS 与本文。工具、容器镜像和依赖包需在新电脑安装/下载；真实 secret 与数据库/受管卷由新环境创建，不属于 Git 内容。

恢复不依赖未追踪文件、IDE 本地配置、构建缓存、旧 Docker Volume、旧数据库、原机器路径或仓库外证据。已知非代码启动资料缺口是本地开发示例遗漏的 Import secret / C9 变量，本文已完整补齐名称、合同与官方预配入口；没有把它们藏在学校电脑的唯一入口中。

仍待完成：

- Import Frontend A–D 最终独立 Closing Re-Review 尚未正式关闭。
- 完整 Finance Freeze 尚未执行；G5 / Freeze 正式签核仍单独管理，用户须另行授权。
- 家庭电脑环境尚未验证；新 PostgreSQL / C9 尚未初始化，全新环境运行验收尚未完成。
- 原有真实数据跨机恢复未验证；本次无需迁移旧开发测试数据，没有导出或备份旧卷。
- 既有非阻塞边界仍见 STATUS（含 C8 提示、FX、C4、C3 后续项），本轮不扩大处理。
- Workspace 整合尚未开始。

Personal Finance OS 是未来 Personal AI Workspace 的 Finance 业务分支，现阶段保持独立仓库。回家先按本文确认独立 Finance 可运行，再考虑整合。Shared AI Runtime、Shared Memory、Workspace Tool Integration、Windows Assistant 合并和 Android 本地客户端都没有完成，本轮不设计或实施。

本轮仅文档静态收档与普通 Git 提交/推送，不运行新功能测试、不手动重跑 CI、不执行 Freeze Tag / Release、不清理旧数据库或卷、不迁移真实数据。收档完成后停止集中开发，下一阶段为家庭电脑全新环境重建及运行验收。
