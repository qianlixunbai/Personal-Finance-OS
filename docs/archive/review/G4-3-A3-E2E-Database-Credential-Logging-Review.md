# G4-3 / Batch A3：CI 测试数据库凭据与日志边界修复 Review

日期：2026-10-13。仅批准最小实施、静态验收、隔离验证与两名 Luna Max 只读复审；本轮未 commit、push、tag 或自动重跑 Actions。

## 1. 结论与正式状态

**G4-3 / Batch A3 — IMPLEMENTED / REMOTE ACCEPTANCE CANDIDATE**。

实现、静态检查与两名独立只读复审通过；隔离环境的 13 个验证场景均符合预期，未发现新的 P0/P1/P2。适合后续人工技术验收，再单独授权提交和远端验收。

**Batch A 仍为 REMOTE ACCEPTANCE HOLD**。本轮没有新 SHA 或新 GitHub Run，不能宣布新 Runner 日志已通过、完整 E2E 已通过或 Batch A CLOSED — GO。C1–C11 CLOSED — GO（11/11）、G4-3 整体 OPEN、Finance Final Stabilization；Backend CI、Batch B/C/D 和 Finance Freeze 保持独立 OPEN。

## 2. Preflight 与初始 HEAD

| 检查 | 本轮实际结果 |
| --- | --- |
| 分支 | `zh-cn` |
| 初始 / 最终 HEAD | `fc44f232737ad23ab6e912714d448758a51a0ed0` |
| 本地 `origin/zh-cn` | 同一 SHA |
| 独立 `git ls-remote origin refs/heads/zh-cn` | 同一 SHA |
| 初始 tracked / staged diff | 均为空 |
| 初始 untracked | 仅 `AGENTS.md`、`docs/codex-task.md` |

受保护路径仅核对 Git 路径状态，未读取内容、hash、修改、删除、暂存或提交。没有破坏性 Git 操作；规则取自聊天中的用户指令，没有打开仓库内受保护指令文件。

## 3. 已确认根因与方案 A/B

[Parent Run 38374938817](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38374938817) 的真实 E2E、V21 和六项 Playwright 已在上一轮核验。本轮直接采用已确认根因：固定数据库测试密码分别位于 Job env 与 PostgreSQL service env；服务初始化日志先于普通步骤，随后 `add-mask` 无法追溯遮蔽已输出内容。密码属于 CI fixture，没有将其解释为生产凭据。本轮没有重新调查 Storage UUID 或 V21。

| 方案 | 当前约束下的评估 | 决定 |
| --- | --- | --- |
| A：保留原 service 启动合同 | service env 的可用 context 不含 `steps`，不能引用当前 Job 稍后生成的密码；普通步骤不能在已有服务初始化日志之前注册 mask。额外仓库 Secret 不在授权范围内，也不能作为现有 PR 语义的新增前提。未找到符合这些约束的服务启动前 mask 路径 | 不采用 |
| B：当前 E2E Job 显式启动容器 | 先在普通步骤生成密码、注册 mask，再启动数据库；后端与 SQL 沿用同一 `GITHUB_ENV` 密码。无需新增仓库 Secret、Job、框架或镜像依赖 | 采用 |

上述 A 的结论限定于当前最小范围，没有声称所有自定义 service entrypoint 都不可能实现。[GitHub context 表](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability)列明 service env 的 context 限制；[masking 文档](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#masking-a-value-in-a-log)要求在输出或其他 workflow command 使用值之前注册 mask。

## 4. 精确改动与真实 E2E 保真

现有文件仅修改 [`.github/workflows/ci.yml`](../../../.github/workflows/ci.yml)，实际 diff 为 **+64 / -15**；新增文档仅本 Review。删除固定 Job 密码与旧 `services.postgres`，现有 Secret 步骤新增四行；新增数据库启动、失败诊断和清理三个步骤。完整 hunk 见附录，两个旧 fixture 密码删除值作明确脱敏。

使用已有 `js-yaml 4.1.1` 解析修改前后结构；还原授权的数据库 env/service、Secret 增补和三个新增步骤后，整个 Workflow 与 HEAD 深比较一致。五个 Jobs、触发条件、permissions、concurrency、Deployment 依赖、Frontend/Backend 测试命令和失败传播均保留。

所有原 E2E 步骤结构保持原样，只有 Secret 生成增加数据库密码：当前 JAR `clean package -DskipTests`、正式 Provisioner、五个 Storage 变量及权限/绑定检查、Spring `e2e` profile、原 `/actuator/health`、A2 的只读 V21 与零失败迁移验证、原 Investment 2 项 / Import mock 3 项 / Import real 1 项、独立 AI Browser E2E 58 项均保留。这些数量属于既定回归边界，未冒充本轮执行数。

没有修改应用配置、Java、Schema、Flyway SQL、Backend Test、Playwright Spec、C9 生产合同、业务代码或历史 Review。

## 5. 密码生成、初始化与认证

现有 Secret 步骤首先启用严格 Shell 模式，使用 `openssl rand -hex 32` 生成本 Job 独立的 256 位数据库测试密码。密码不是 Job 顶层常量，服务初始化阶段也不再存在密码 env 配置。

四个值都先通过 `::add-mask::` 注册，再写入现有 `GITHUB_ENV`。数据库密码只在 Runner 临时环境通道使用，不写入源码、artifact、Review 或新持久凭据文件。JWT / Migration / Import 原生成与注册方式保留。

Docker 启动时，`POSTGRES_PASSWORD="$E2E_DB_PASSWORD"` 只设置该进程环境，CLI 参数仅包含 `--env POSTGRES_PASSWORD`，不包含展开后的密码值。使用原 `postgres:17-alpine`；数据库名和账号仍由既有 E2E 合同确定。

`POSTGRES_HOST_AUTH_METHOD=scram-sha-256` 且 initdb 显式指定 host / local SCRAM，未配置 `trust` 或关闭认证。官方[PostgreSQL 镜像入口](https://github.com/docker-library/postgres/blob/master/docker-entrypoint.sh)会在初始化数据库前设置 `PGPASSWORD`，因此 local SCRAM 初始化具有现有官方支持路径。

## 6. 生命周期、网络与诊断取舍

启动从 Actions 的步骤前初始化移到 Secret 注册之后、Storage/JAR/后端启动之前；原 service 的自动销毁职责改为本 Job 的显式清理。

每次启动生成 owner UUID，以其组成唯一容器名并设置 owner label；创建前把名称和 owner 写入 `GITHUB_ENV`。即使 `docker run` 在创建之后启动失败，后续诊断与清理仍能找到本轮资源。

数据库仅 publish `127.0.0.1:5432:5432`；容器内部仍为 PostgreSQL 5432。数据放在专属 tmpfs，不使用外部持久数据库、用户卷或生产凭据。容器 bridge 网络和宿主端口由 Docker 管理；没有新增独立网络或基础设施。

就绪流程最多 60 次、每次间隔 2 秒：要求容器 Running，使用容器内 TCP `pg_isready` 避免把初始化阶段的 Unix socket 临时 server 当成最终就绪，再由 Runner `psql -X --no-password` 真正登录并要求 `current_database()` 等于 `finance_os_e2e`。连接超时为 5 秒，SQL 启用 `ON_ERROR_STOP=1`。任何工具、容器、连接或身份检查错误都会失败，不以 pg_isready 单独证明认证。

Spring Boot 的固定 localhost URL 与同一组 E2E 账号变量保持不变；后续 A2 查询仍连接该数据库。

失败时新增诊断步骤只输出本轮 PostgreSQL 的最多 100 条日志。诊断和清理都先按完整容器名找到 ID，再核对 owner label；不 dump Docker env 或配置。owner 不匹配时失败且不触碰资源。

原应用停止步骤完整保留；之后独立 `if: always()` 步骤先正常 stop，再 rm 本轮容器及其自有匿名卷。未启动或容器已不存在时安全返回，Docker 命令错误不被忽略。强制中止 Runner 时平台是否执行完 cleanup 没有实测；不能保证进程被强杀后的步骤仍执行。

## 7. 静态与隔离运行证据

| 静态检查 | 本轮结果 |
| --- | --- |
| YAML 解析、五 Jobs / 顶层配置 / 原步骤对照 | 通过 |
| 全部 17 个 E2E run 脚本 `bash -n` | 通过 |
| 原镜像、SCRAM 参数、回环 publish、mask 时序 | 通过 |
| 无新增 skip、continue-on-error、失败兜底 | 通过；原应用停止中的既有 kill 兜底原样保留 |
| 凭据引用、创建/就绪/SQL/诊断/cleanup 日志边界 | 静态检查通过 |
| `git diff --check` | 通过，仅有 Windows LF/CRLF 提示 |

本机 Docker Desktop 29.6.1 可用，Windows 没有 PATH 中的 psql。使用已缓存的 PostgreSQL 客户端容器及 Docker Desktop 已有的真实 Linux CLI，通过 host network 执行脚本；没有下载安装依赖或修改系统 WSL 设置。

首次按原端口执行时，Docker 拒绝绑定 5432；只读 `netstat` 确认本机存在 `0.0.0.0:5432` 监听。启动在执行任何数据库登录之前失败，没有连接该监听服务。本轮未停止或修改占用端口的用户进程。只在外部验证副本把宿主端口与客户端参数改为 15432，容器内部仍为 5432；反向替换断言证明除此之外脚本不变，仓库 Workflow 仍为 5432。

工具准备曾遇到 Docker Desktop 的 WSL wrapper/stub 不能充当真实 Linux CLI，随后复用已有 cli-tools 中的真实二进制；外部脚本写出时曾被 Windows 转成 CRLF，随后只修正外部副本为 LF。均属验证环境问题，没有据此修改 Workflow。

| 隔离执行场景 | 实际结果 |
| --- | --- |
| 动态密码格式、四项 mask 控制命令与 GITHUB_ENV 值一致 | 通过，不导出值 |
| 数据库启动、镜像、回环端口、tmpfs、Docker 密码转发 | 通过，外部端口 15432 |
| `127.0.0.1` 真正密码登录与身份 | 通过 |
| `localhost` 真正密码登录与身份 | 通过 |
| Java 21 + 项目现有 JDBC 42.7.4 的 localhost 登录 | 通过；不是完整 Spring Boot 启动 |
| 存储密码为 SCRAM 的布尔检查、HBA 仅 SCRAM | 通过，不导出密码 hash |
| 错误密码拒绝 | 预期失败，退出 2 |
| 新空库 history 尚不存在；原 A2 步骤拒绝缺表 | 预期失败，退出 1；未伪造 V21 |
| owner 不匹配时拒绝清理，原容器继续运行 | 预期失败，退出 1 |
| 仅与本轮主容器制造端口碰撞、失败诊断及清理 | 启动预期退出 125，诊断/清理成功，主容器未受影响 |
| 实际 PostgreSQL 诊断日志扫描四项原 Secret | 无原值 |
| 原 cleanup 正常 stop / rm | 通过 |
| cleanup 遇已不存在容器或尚未启动 | 安全返回 |

合计 **13/13 场景符合预期**。临时密码只在 helper 的环境及临时 `GITHUB_ENV` 中存在；生成时的 mask 控制命令仅在工具内存中核对，不作为普通日志导出。普通创建、就绪、SQL、失败诊断与 cleanup 输出逐项检查四项本轮 Secret 原值，没有泄露。所有本轮创建的容器均按明确身份清理，临时 Secret 环境文件未导出。

这是 Docker Desktop 下有端口适配的隔离证据，不是真实 GitHub Hosted Runner。客户端为 PostgreSQL 17，实际数据库镜像运行 PostgreSQL 17.11；Java 探针为 Temurin 21。没有执行当前 JAR、Provisioner、Boot Health、真实迁移或 Playwright，本轮没有 Maven TLS / 外部下载故障证据。正式端口完整链路未验证，不能借用 Parent 的历史结果填充本轮 PASS。

## 8. 安全与日志验收边界

容器创建参数只含密码变量名；Runner 在生成步骤前没有数据库密码 env，后续自动 env 展示发生于 mask 注册之后。就绪/SQL 使用密码环境，不输出值；失败日志不导出 Docker env；cleanup 只记录已核对的资源操作。

上述时序和本地普通输出已验证，**新 GitHub Runner 的自动 env 展示、动态 mask、生效范围和失败日志仍未实际验证**。本地 echo 出 mask 控制命令不等于运行了 GitHub masker；未制造“模拟 Runner PASS”。后续必须逐段检查创建、自动环境、就绪、V21、失败诊断与清理日志。

旧固定 fixture 密码已在历史 Run / commit 出现，此修改不能追溯清除历史，本次候选只评价新配置；报告与 diff 导出不复述旧密码删除值。

## 9. 两名 Luna Max 复审、风险与停止状态

| 独立只读复审 | 配置与职责 | 最终结果 |
| --- | --- | --- |
| `a3_database_review` | `gpt-6-luna` / `max`；启动、认证、网络、身份、密码及资源生命周期，补读隔离证据 | 候选 GO，无阻断 finding |
| `a3_workflow_review` | `gpt-6-luna` / `max`；Workflow 保真、日志时序、最小 diff、失败传播，补读隔离证据 | 静态实施候选 GO，无阻断 finding |

两名均只读；初审提出 localhost / IPv4 解析疑虑。主控通过 Java 21 和既有 JDBC 驱动实际 localhost 登录独立验证，随后两名复审员复核证据并消歧，没有为此修改应用配置。最终未发现新的 P0/P1/P2。

未验证范围：新 GitHub Runner 5432、真实自动 masking、当前 JAR / Provisioner / Storage / e2e Boot Health / V21 / 六项 Playwright 的完整新配置链路、强制取消后的清理、actionlint、Backend 历史问题、Batch B/C/D、其他独立风险和 Finance Freeze。

结束时 HEAD / 分支未变，staged 为空，tracked 修改仅 Workflow，新增仅本 Review；受保护路径仍为原有 untracked 状态，仅检查 Git 路径。没有修改历史 Review。

外部证据目录：`F:\Downloads\finance-os-g4-3-a3-20261013-c7809098b484407f97fb7c983fa75325`，含静态验证、明确标注端口适配的脚本、13 场景结果及经过 Secret 原值检查的普通输出。未提交日志、构件或凭据。

停止等待人工验收。后续单独授权提交后，必须在新 SHA 证明无明文数据库密码、真实认证、JAR / Provisioner / Storage / Health、V21 与零失败迁移、原六项浏览器 E2E 全通过且无新增 P0/P1，才可正式关闭 Batch A。

## 附录：Workflow hunk diff

以下与实际 `git diff -- .github/workflows/ci.yml` 对应；两条已删除旧 fixture 密码的值以 `[REDACTED-LEGACY-FIXTURE]` 替代，空 context 行去掉 Markdown 尾空白，其余内容和 hunk 原样保留。字节级原 diff 由本地 Git 核验，不将密码再写入报告。

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index 96be5fe..03f5fc7 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -98,21 +98,6 @@ jobs:
       SPRING_PROFILES_ACTIVE: e2e
       E2E_BACKEND_PORT: 8081
       E2E_DB_USERNAME: finance_os_e2e
-      E2E_DB_PASSWORD: [REDACTED-LEGACY-FIXTURE]
-    services:
-      postgres:
-        image: postgres:17-alpine
-        env:
-          POSTGRES_DB: finance_os_e2e
-          POSTGRES_USER: finance_os_e2e
-          POSTGRES_PASSWORD: [REDACTED-LEGACY-FIXTURE]
-        ports:
-          - 5432:5432
-        options: >-
-          --health-cmd "pg_isready -U finance_os_e2e -d finance_os_e2e"
-          --health-interval 5s
-          --health-timeout 5s
-          --health-retries 10
     steps:
       - uses: actions/checkout@v6
       - name: Set up JDK 21
@@ -136,17 +121,56 @@ jobs:
         run: npx playwright install --with-deps chromium
       - name: Generate isolated E2E secrets
         run: |
+          set -euo pipefail
+          db_password="$(openssl rand -hex 32)"
           jwt_secret="$(openssl rand -base64 48)"
           migration_secret="$(openssl rand -base64 48)"
           import_secret="$(openssl rand -base64 48)"
+          echo "::add-mask::$db_password"
           echo "::add-mask::$jwt_secret"
           echo "::add-mask::$migration_secret"
           echo "::add-mask::$import_secret"
           {
+            echo "E2E_DB_PASSWORD=$db_password"
             echo "E2E_JWT_SECRET=$jwt_secret"
             echo "E2E_MIGRATION_PREVIEW_SECRET=$migration_secret"
             echo "FINANCE_IMPORT_CONFIRM_TOKEN_SECRET=$import_secret"
           } >> "$GITHUB_ENV"
+      - name: Start isolated E2E PostgreSQL
+        shell: bash
+        run: |
+          set -euo pipefail
+          command -v psql > /dev/null
+          db_owner="$(cat /proc/sys/kernel/random/uuid)"
+          db_container="finance-os-e2e-pg-$db_owner"
+          {
+            echo "E2E_DB_CONTAINER=$db_container"
+            echo "E2E_DB_CONTAINER_OWNER=$db_owner"
+          } >> "$GITHUB_ENV"
+          POSTGRES_PASSWORD="$E2E_DB_PASSWORD" docker run --detach --name "$db_container" \
+            --label "finance-os.e2e-owner=$db_owner" \
+            --publish 127.0.0.1:5432:5432 \
+            --tmpfs /var/lib/postgresql/data \
+            --env POSTGRES_DB=finance_os_e2e --env "POSTGRES_USER=$E2E_DB_USERNAME" \
+            --env POSTGRES_PASSWORD --env POSTGRES_HOST_AUTH_METHOD=scram-sha-256 \
+            --env 'POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256 --auth-local=scram-sha-256' \
+            postgres:17-alpine
+          for attempt in {1..60}; do
+            test "$(docker inspect --format '{{.State.Running}}' "$db_container")" = 'true'
+            if docker exec "$db_container" pg_isready --host=127.0.0.1 \
+              --username="$E2E_DB_USERNAME" --dbname=finance_os_e2e > /dev/null 2>&1; then
+              database_name="$(PGPASSWORD="$E2E_DB_PASSWORD" PGCONNECT_TIMEOUT=5 \
+                psql -X --no-password --set=ON_ERROR_STOP=1 --no-align --tuples-only \
+                --host=127.0.0.1 --port=5432 --dbname=finance_os_e2e \
+                --username="$E2E_DB_USERNAME" --command='SELECT current_database();')"
+              test "$database_name" = 'finance_os_e2e'
+              echo 'Isolated E2E PostgreSQL ready; authenticated database identity verified.'
+              exit 0
+            fi
+            sleep 2
+          done
+          echo '::error::Isolated E2E PostgreSQL did not become ready.'
+          exit 1
       - name: Prepare isolated E2E import storage
         run: |
           umask 077
@@ -288,6 +312,18 @@ jobs:
           if [ -f "$GITHUB_WORKSPACE/backend-e2e.log" ]; then cat "$GITHUB_WORKSPACE/backend-e2e.log"; fi
           echo '--- frontend-e2e.log ---'
           if [ -f "$GITHUB_WORKSPACE/frontend-e2e.log" ]; then cat "$GITHUB_WORKSPACE/frontend-e2e.log"; fi
+      - name: Print E2E PostgreSQL failure logs
+        if: failure()
+        shell: bash
+        run: |
+          set -euo pipefail
+          if [ -n "${E2E_DB_CONTAINER:-}" ]; then
+            db_id="$(docker container ls --all --filter "name=^/$E2E_DB_CONTAINER$" --format '{{.ID}}')"
+            if [ -n "$db_id" ]; then
+              test "$(docker inspect --format '{{ index .Config.Labels "finance-os.e2e-owner" }}' "$db_id")" = "$E2E_DB_CONTAINER_OWNER"
+              docker logs --tail 100 "$db_id"
+            fi
+          fi
       - name: Stop E2E services
         if: always()
         run: |
@@ -307,6 +343,19 @@ jobs:
           }
           stop_process_group "$GITHUB_WORKSPACE/frontend-e2e.pid"
           stop_process_group "$GITHUB_WORKSPACE/backend-e2e.pid"
+      - name: Stop E2E PostgreSQL
+        if: always()
+        shell: bash
+        run: |
+          set -euo pipefail
+          if [ -n "${E2E_DB_CONTAINER:-}" ]; then
+            db_id="$(docker container ls --all --filter "name=^/$E2E_DB_CONTAINER$" --format '{{.ID}}')"
+            if [ -n "$db_id" ]; then
+              test "$(docker inspect --format '{{ index .Config.Labels "finance-os.e2e-owner" }}' "$db_id")" = "$E2E_DB_CONTAINER_OWNER"
+              docker stop --time 10 "$db_id" > /dev/null
+              docker rm --volumes "$db_id" > /dev/null
+            fi
+          fi

   deployment:
     name: Deployment artifacts
```
