# G4-3 Batch A2 Flyway Evidence Review

日期：2026-10-13。范围：仅补充真实 E2E 数据库 Flyway V21 的成功路径验证步骤。本轮未 commit、push、tag，未触发新远端运行。

## 结论

**G4-3 / Batch A2 — IMPLEMENTED / REMOTE ACCEPTANCE CANDIDATE**。

最小 Workflow 修改、静态检查、隔离查询验证和两名 Luna Max 独立只读复审均通过，未发现本轮 P0/P1/P2。真实 GitHub E2E Job 的新增步骤尚未执行，直接 V21 运行证据仍待新 SHA 验收；**Batch A 继续 REMOTE ACCEPTANCE HOLD**，不能宣布 CLOSED — GO。

正式状态采用本轮人工决定：C1–C11 CLOSED — GO（11/11），Finance 为 Final Stabilization。没有实施 Storage 预配整改、Backend 历史测试修复、Batch B/C/D 或 Finance Freeze；历史 Review 未修改。

## Preflight 与受保护路径

| 检查 | 本轮实际结果 |
| --- | --- |
| 当前分支 | `zh-cn` |
| 本地 HEAD | `8da0bbd58ac2178684b417fa7da218790e00a3ea` |
| 本地 `origin/zh-cn` | 同一 SHA |
| 独立 `git ls-remote origin refs/heads/zh-cn` | 同一 SHA |
| 初始 tracked / staged diff | 均为空 |
| 初始未追踪路径 | 仅 `AGENTS.md`、`docs/codex-task.md` |

两个路径属于已声明的受保护文件，仅检查 Git 路径状态，未读取内容、hash、修改、删除、暂存或提交。结束时仍为未追踪；不以内容比较声称内容未变。

## 精确修改与原链路保真

现有文件仅修改 [`.github/workflows/ci.yml`](../../../.github/workflows/ci.yml)：新增 27 行 `Verify E2E Flyway V21`，位于第 229–255 行，紧接 `Start E2E backend` 成功之后、`Start E2E frontend` 之前。新增文档仅本 Review；精确 Workflow diff 见附录。

使用已有全局 Vue CLI 的 `js-yaml 4.1.1` 解析修改前后 YAML，没有安装新依赖。验证脚本删除新增步骤后，对整个 Workflow 进行结构深比较，并对删除新增文本块后的原文进行比较，两者均完全一致。

因此五个 Jobs、原触发条件、permissions、concurrency、依赖及所有原步骤保留，包括当前 JAR 的 `clean package -DskipTests`、五个 Storage 环境变量、正式显式 Provisioner、目录权限和身份检查、`e2e` profile、原 health readiness、三组 Playwright 命令、独立 AI Browser E2E 与 Secret masking。

未修改生产 Java、Flyway SQL、应用配置、Playwright Spec、Backend Test、安全合同或 C1–C11 历史 Review。

## SQL、数据库身份与失败传播

连接显式固定为 `127.0.0.1:5432/finance_os_e2e`，用户名来自现有 `E2E_DB_USERNAME`，密码通过 `PGPASSWORD` 使用现有 `E2E_DB_PASSWORD`，不拼入 URI 或命令参数。

当前 E2E Job 自己的 PostgreSQL service 使用同名数据库并映射 `5432:5432`；后端继承 `SPRING_PROFILES_ACTIVE=e2e`。[`application-e2e.yml`](../../../backend/src/main/resources/application-e2e.yml) 固定 URL 为 `jdbc:postgresql://localhost:5432/finance_os_e2e`，使用同一组 E2E 账号变量。新增步骤还要求 `SELECT current_database()` 返回 `finance_os_e2e`。这是同一 Job 的数据库连接合同，没有使用 Backend Job 的 Testcontainers 数据库。

三个查询均为只读 SELECT：核对数据库名；统计 `public.flyway_schema_history` 中所有 `success = FALSE` 记录，要求为 `0`；按 `installed_rank DESC` 获取最新的成功、版本非 NULL 的迁移，要求精确等于 `21`。没有按字符串最大版本排序，也没有默认填充版本。

步骤先用 `command -v psql` 检查客户端，不存在则显式失败；`-X` 禁用用户 psqlrc，`--no-password` 禁止交互提示，`PGCONNECT_TIMEOUT=10` 限制连接等待，`PGOPTIONS` 设置默认只读事务。`ON_ERROR_STOP=1` 与 `set -euo pipefail` 保证 SQL/连接/命令错误及任一检查失败传播为步骤失败。没有 `|| true`、`continue-on-error`、成功兜底或忽略退出码；表不存在、空查询及非 V21 都不能通过。

本轮从旧 E2E Job 的 setup 日志确认其镜像为 `ubuntu-24.04`，清单链接为 [ubuntu24/20261004.327](https://github.com/actions/runner-images/blob/ubuntu24/20261004.327/images/ubuntu/Ubuntu2404-Readme.md)。该清单列有 PostgreSQL 16.15，对应[安装脚本](https://github.com/actions/runner-images/blob/ubuntu24/20261004.327/images/ubuntu/scripts/build/install-postgresql.sh)安装 PostgreSQL。此为客户端可用性的镜像依据；新 Runner 的实际 `command -v psql` 结果尚未验证。未添加 apt 或其他依赖安装流程。

## 静态与隔离执行证据

| 验证 | 本轮结果 |
| --- | --- |
| 修改前后 Workflow YAML 解析 | 通过 |
| 整个 Workflow 原结构及原文本对照 | 通过，五个 Jobs 保留 |
| E2E 全部 14 个 run 脚本 `bash -n` | 通过，含新增步骤 |
| 查询、失败传播、位置和数据库配置静态断言 | 通过 |
| Secret / 日志静态检查 | 通过 |
| `git diff --check` | 通过，仅有 Windows LF/CRLF 转换提示 |

本轮将未经改写的新增步骤脚本复制到新建 PostgreSQL 17.10 容器执行。容器使用已缓存的 `postgres:17`，`network=none`，无宿主机端口、无宿主 bind mount，数据位于新 tmpfs；没有连接日常数据库或真实用户数据。使用合成 history 行验证以下 12 个场景，全部符合预期：

| 场景 | 实际退出码 |
| --- | --- |
| 最新成功版本 21，无失败记录 | 0 |
| 较早版本 100、较新版本 21、之后 NULL 版本；验证 rank 排序及 NULL 排除 | 0 |
| 最新成功版本 20 | 1 |
| 最新成功版本 22 | 1 |
| 空 history | 1 |
| 仅成功但版本为 NULL 的记录 | 1 |
| V21 后存在失败迁移 | 1 |
| V21 前存在失败迁移 | 1 |
| history 表不存在 | 1 |
| 密码错误 | 2 |
| psql 不存在 | 1 |
| 目标数据库不存在，连接失败 | 2 |

另实际查询确认只读事务为 `on`；独立合成 INSERT 验证被只读事务拒绝，退出码为 1。该 INSERT 仅属于外部隔离验证脚本，未进入 Workflow。

首轮验证容器的 loopback 为 `trust`，错误密码用例因此意外成功；只调整新验证容器的 `initdb --auth-host=scram-sha-256` 后重新执行。验证脚本最初错误预期只读 INSERT 的 `psql -c` 退出码必须为 3，实际为 1；修正为“非零且明确只读事务错误”，完整矩阵随后通过。没有因这些 fixture / 验证脚本问题修改 Workflow 或生产行为。

上述是**查询脚本的隔离运行证据**，没有启动 Spring Boot 或实际应用 Flyway migrations，不能当作真实 E2E 数据库 V21 证据，也不能替代远端验收。

外部证据目录：`F:\Downloads\finance-os-g4-3-a2-20261013-feae2fa8dd2447b59e27358cb2b51805`。保留 `validate.cjs`、`static-results.json`、原步骤 Shell、`workflow.diff`、`isolated_check.py`、`isolated-results.json` 及历史 Run / Job 元数据。所有本轮新建容器均已停止，未删除用户文件或既有资源。

## 敏感日志边界与历史证据

新增步骤不输出凭据、JWT、Secret、环境 dump 或用户业务数据；成功 stdout 仅在全部检查后输出 `E2E Flyway verified: V21; no failed migrations.`。失败允许 psql 输出数据库连接或元数据查询错误。隔离执行结果已检查不含该容器密码；原 Secret masking 保持原文。新 GitHub Runner 的日志展示与 masking 行为尚未实跑。

本轮通过 GitHub API 只读复核[历史 Run 38343492456](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38343492456)：SHA 为指定 HEAD，完整 Workflow 为 failure；Playwright E2E Job `115119518247` 为 success，JAR 构建、Provisioner、后端 readiness、前端和三组 Playwright 步骤均 success；AI Browser E2E 与 Frontend success，Backend failure，Deployment skipped。

人工提供的旧 Run 用例数为 Investment 2/2、Import mock/browser 3/3、Import real backend 1/1、AI Browser E2E 58/58。本轮未重新统计或执行这些用例；上述历史结果不能当成新增验证步骤的运行结果。

## 两名 Luna Max 独立只读复审

| 审查 | 实际配置与范围 | 结果 |
| --- | --- | --- |
| Luna Max 1，`flyway_review` | `gpt-6-luna` / `max`；数据库身份、V21 查询、只读及失败传播，补读本轮隔离证据 | GO；未发现 P0/P1/P2 |
| Luna Max 2，`workflow_review` | `gpt-6-luna` / `max`；精确 diff、原五个 Jobs 保真、敏感日志边界 | GO；未发现 P0/P1/P2 |

两名均只读，未连接数据库或执行真实 GitHub Actions；均仅通过 Git 检查受保护路径状态。主控已核对其结论对应实际代码、diff、结构断言和隔离结果，未依据未经验证的问题扩大修改。

## 风险、未验证范围与下一步

本轮未发现新的 P0/P1/P2。未运行 actionlint、C9 全量测试、Maven 构建、完整 Playwright 或真实 Actions；隔离客户端为 PostgreSQL 17，与旧 Runner 清单中的客户端版本及 Ubuntu 环境不同。新 Runner 客户端可用性、真实数据库最新成功 V21、无失败迁移、实际 masking 和新 SHA 的完整链路均仍待验证。Backend 历史红灯保持独立 OPEN。

结束状态：HEAD / 分支未变，staged diff 为空；tracked 修改仅 Workflow，新增仅本 Review，两个受保护未追踪路径保留。

停止等待人工验收。本轮不提交或推送。后续单独授权后，必须在新 SHA 真实验证当前 JAR、Provisioner、e2e health、本步骤的 V21 与零失败记录、原三组 Playwright 共六项、无新增 P0/P1，并核对提交与远端 SHA；全部满足才可宣布 **G4-3 / Batch A — CLOSED — GO**。

## 附录：Workflow 精确 diff

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index ccda317..96be5fe 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -226,6 +226,33 @@ jobs:
           done
           cat "$GITHUB_WORKSPACE/backend-e2e.log"
           exit 1
+      - name: Verify E2E Flyway V21
+        shell: bash
+        run: |
+          set -euo pipefail
+          if ! command -v psql > /dev/null; then
+            echo '::error::PostgreSQL client psql is unavailable.'
+            exit 1
+          fi
+          export PGPASSWORD="$E2E_DB_PASSWORD"
+          export PGCONNECT_TIMEOUT=10
+          export PGOPTIONS='-c default_transaction_read_only=on'
+          psql_command=(psql -X --no-password --set=ON_ERROR_STOP=1 --no-align --tuples-only
+            --host=127.0.0.1 --port=5432 --dbname=finance_os_e2e --username="$E2E_DB_USERNAME")
+          database_name="$("${psql_command[@]}" --command='SELECT current_database();')"
+          test "$database_name" = 'finance_os_e2e'
+          failed_migrations="$("${psql_command[@]}" --command='
+            SELECT COUNT(*) FROM public.flyway_schema_history WHERE success = FALSE;')"
+          test "$failed_migrations" = '0'
+          latest_version="$("${psql_command[@]}" --command='
+            SELECT version
+            FROM public.flyway_schema_history
+            WHERE success = TRUE
+              AND version IS NOT NULL
+            ORDER BY installed_rank DESC
+            LIMIT 1;')"
+          test "$latest_version" = '21'
+          echo "E2E Flyway verified: V$latest_version; no failed migrations."
       - name: Start E2E frontend
         working-directory: frontend
         run: |
```
