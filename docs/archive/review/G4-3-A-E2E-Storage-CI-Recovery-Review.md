# G4-3 / Batch A：E2E Storage CI 环境恢复实施 Review

日期：2026-10-13。授权范围：仅 Batch A；未实施 Batch B、C、D，未 commit、push、tag 或 Finance Freeze。

## 1. 结论与验收边界

**G4-3 / Batch A — IMPLEMENTED / ACCEPTANCE GO CANDIDATE（静态实施候选）**。

Workflow 实施与静态复审 GO，未发现本轮 P0/P1。完整运行验收仍为 **NO-GO／待验证**：本机隔离 Linux 构建被 Maven Central TLS 握手失败阻断，Playwright 浏览器安装同时被 Debian security 源 HTTP 502 阻断。没有当前 JAR，没有执行 Provisioner 或启动后端，没有本轮 V21、健康检查或真实 E2E 通过证据。不得据此宣称 GitHub Actions 全绿。

正式远端运行验收与提交、推送均等待之后的人工授权。本轮结束后停止。

## 2. Preflight 与 HEAD

修改前独立执行并核对：

| 检查 | 实际结果 |
| --- | --- |
| `git branch --show-current` | `zh-cn` |
| `git rev-parse HEAD` | `ea06502cdf9c7b2407617633eb800221e8347f31` |
| `git rev-parse refs/remotes/origin/zh-cn` | 同上 |
| `git ls-remote origin refs/heads/zh-cn` | 同上，远端 ref 为 `refs/heads/zh-cn` |
| `git diff --name-status` | 初始为空 |
| `git diff --cached --name-status` | 初始为空 |
| `git ls-files --others --exclude-standard` | 仅 `AGENTS.md`、`docs/codex-task.md` |

两份未追踪文件是任务指定的受保护路径，不视为异常。只查看 Git 路径状态，未读取内容、hash、修改、删除或暂存；规则来自用户在聊天中给出的文本。

## 3. 本轮根因依据

通过 GitHub 只读工具重新读取 [Run 38332239334](https://github.com/qianlixunbai/Personal-Finance-OS/actions/runs/38332239334) 的 jobs 和 Playwright E2E Job `115084364814` 日志。

- `Start E2E backend` 失败；后续前端启动及三组 Playwright 步骤均 skipped。
- 该日志显示唯一 active profile 为 `e2e`，V1–V21 成功应用，schema 达到 `v21`。
- 首个业务初始化异常为 `IllegalArgumentException: Invalid UUID string:`，位置为 `TransactionImportPreviewConfiguration.importStorageBindings` 第 37 行。
- 本轮读取实际代码确认第 37 行解析 `finance.import.storage.deployment-id`；`application.yml` 将其绑定至缺省空值的 `FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID`。
- 修改前 Workflow 未提供五个 Storage 环境变量，也未执行显式 Provisioner。

该 Run 是修改前历史失败证据，虽然本轮重新查询，仍不能当成本轮修改后的执行结果。该 Run 的 Backend 为 failure，Deployment artifacts 为 skipped；本次修改没有隐藏 Backend 红灯。

## 4. 修改范围与精确 diff

仅修改 `.github/workflows/ci.yml`，另新增本 Review。Workflow 修改为 68 行新增、3 行删除；其他四个 Jobs、触发条件、permissions、concurrency、依赖和 E2E job 元数据保持原状。

精确 Workflow diff 见文末附录。没有生产 Java、SQL、测试、前端或其他配置修改。

## 5. RAW / PLAN 路径设计

`RUNNER_TEMP` 必须存在、不是 symlink，并且与 `realpath -e` 完全一致。`mktemp -d` 在其下面创建本 Job 新的独占父目录，随后创建同级 `raw`、`plan` 空目录。

- 父目录和两个根均为 `0700`；父目录 owner 必须是当前 runner UID。
- 两个根采用真实绝对路径，分别检查 symlink 和 realpath；同级固定子目录保证不同且互不嵌套。
- `umask 077` 覆盖准备与正式预配；marker/control 验证为普通文件、非 symlink、`0600` 且 owner 为当前进程 UID。
- 系统公共临时根仅承载随机私有父目录，不直接作为受信 RAW / PLAN 根；无外部上传目录或跨 Job 共享根。
- 目录只需在本 Job 及其后端重启期间保留；该 CI 生命周期不能替代正式部署的持久卷合同。

本机 Linux 实跑创建的目录为 `/workspace/runner-temp/finance-os-e2e-import.V4zBXqz6rc/raw` 与同级 `plan`。父目录、RAW 和 PLAN 均实际核验为 `700`、UID `0`。本机容器以 root 执行；Hosted Runner 的 UID 与主机安全边界仍需远端确认。

## 6. Storage Identity 的生成与稳定性

三个 UUID 分别从 Linux `/proc/sys/kernel/random/uuid` 生成一次，RAW_ID 与 PLAN_ID 显式比较并拒绝相同值。五个 Storage 变量一起写入 `GITHUB_ENV`，后续 Provisioner 和 Maven/Spring Boot 步骤继承同一组值，没有按步骤重新生成。

同一 Job 内重启复用环境文件与原目录；不在生产默认配置中写入 UUID。正式 CLI 再通过 `UUID.fromString` 和路径/身份独立性检查验证输入。

本机实际验证三个生成值均符合规范 UUID 形式、RAW_ID 与 PLAN_ID 不同、根不同且为空。环境文件已加载验证；跨后端重启的运行证据未取得。

## 7. 当前 JAR 的构件身份与构建结果

Workflow 在预配前执行 `./mvnw -B clean package -DskipTests`。`clean` 排除旧 target 构件，记录并比较构建前后的 checkout HEAD，要求当前版本 JAR 实际存在且非空，并用 `jar tf` 检查：

- `BOOT-INF/classes/com/financeos/FinanceOsApplication.class`；
- `BOOT-INF/classes/com/financeos/module/importing/storage/ImportStorageProvisioner.class`；
- `org/springframework/boot/loader/launch/PropertiesLauncher.class`；
- `org/springframework/boot/loader/launch/JarLauncher.class`。

通过后输出源码 HEAD 与 JAR SHA-256，任何命令失败都停止该步骤。原 Backend Job 的 `./mvnw -B clean test` 未改动；`-DskipTests` 仅用于 E2E 构件准备。

本机输入来自指定 HEAD 的 `backend`、`frontend` Git archive，无旧 target、node_modules 或受保护文件。Windows 初次 archive 受到 `core.autocrlf=true` 影响，将原始 LF wrapper 转成 CRLF，Linux 首次执行退出码 **126**（`/bin/sh^M: bad interpreter`）。`git ls-files --eol backend/mvnw` 确认 `i/lf w/crlf` 后，使用 `git -c core.autocrlf=false archive ... HEAD backend frontend` 重新导出；只改变外部验证输入的换行策略，未修改仓库。

Linux 源码归档 SHA-256：`330c2d1119aea2d2e7097cb891b590743cb3b43e75068916467fde5d0d99a4c1`。

纠正环境后 `clean package -DskipTests` 退出码 **1**：下载 `org.springframework.boot:spring-boot:3.3.5` 时 Maven Central 报 `Remote host terminated the handshake`。相同命令重试一次仍退出 **1**，同一错误。属于依赖下载环境失败，没有据此修改生产代码。

实际确认目标 JAR 不存在，因此未运行后续预配/启动；没有使用 C9 历史构件。**本轮 JAR hash、实际 Boot Loader 内容和成功编译均未验证。**

## 8. 正式 Provisioner 启动命令与结果

使用与当前部署文档、Spring Boot 3.3.5 打包合同相同的正式命令：

```bash
java -Dloader.main=com.financeos.module.importing.storage.ImportStorageProvisioner \
  -cp target/finance-os-backend-1.0.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --provision
```

仅在当前 JAR 构建与内容检查通过后执行。CLI 保留原先的空根要求、已有/部分绑定拒绝、`CREATE_NEW` 元数据创建和 RAW/PLAN 独立性检查；未重写入口，未使用测试 profile 自动初始化。

**本轮实际执行次数为 0。** 尚未验证首次预配成功、元数据创建、重复预配拒绝或实际 Linux Storage guard 可用。Workflow 的预配后路径/owner/权限检查已实现并通过静态审查，运行结果等待远端验收。

## 9. Spring Boot、PostgreSQL V21 与健康检查

原 `Start E2E backend` 步骤完整保留：`SPRING_PROFILES_ACTIVE=e2e`、Maven 启动、端口 `8081`、进程存活检查、原 health readiness 与失败退出逻辑均未改变。未将 Provisioner 放入应用启动，未切换 `test` profile。

本机使用独占 Linux volume 和三个新容器共享同一网络 namespace。PostgreSQL 17 Alpine 使用隔离数据库与账号 `finance_os_e2e`；所有容器均无宿主机映射端口，未连接日常 PostgreSQL。`pg_isready` 实际退出码 **0**。

由于无当前 JAR，**Spring Boot 实际启动次数为 0**；新数据库查询 `to_regclass('public.flyway_schema_history') IS NULL` 返回 `t`，证实本轮迁移未开始。V21 成功、`/actuator/health` 成功、真实绑定登记与可用性均**未验证**。第 3 节的旧 Run V21 成功证据不能代替本轮结果。

## 10. 原三组 Playwright 实际执行情况

`npm ci` 成功；本轮采用锁文件对应依赖。`npx playwright install --with-deps chromium` 首次及一次原命令重试均最终退出 **1**，内部 apt 安装退出 **100**：Debian security 源 `InRelease` HTTP 502，随之无法核验仓库签名。没有禁用签名校验或更换依赖。

仅执行原三组 npm 命令的 `-- --list` 来验证收集，没有启动浏览器或执行用例：

| 原测试组 | 收集命令退出码 | 收集数 | 实际执行数 |
| --- | --- | --- | --- |
| Investment Command | 0 | 2，1 个 spec | 0 |
| Transaction Import mock/browser | 0 | 3，1 个 spec | 0 |
| Transaction Import real backend | 0 | 1，1 个 spec | 0 |

没有本轮通过/失败用例结果；不能把合计 6 个收集用例写成 6 个通过。原 spec、断言、命令和步骤顺序均保留，未加入 skip、mock 替换真实后端或 `continue-on-error`。

真实链路仍为 Vite `5180` → Spring Boot `8081` → PostgreSQL；mock/browser 组继续使用原独立 `5181` 配置。Upload、Mapping、Preview、Warning、Confirm、Receipt、幂等恢复、用户/跨用户边界、冻结请求意图和受管文件生命周期均待实际运行确认。

## 11. Secret masking 与日志安全

保留原 Secret 名称和 `openssl rand -base64 48` 生成方式，三个值先分别执行 `::add-mask::`，然后写入 `GITHUB_ENV`。未引入真实 Provider Key、生产凭据或用户 JWT；未使用 shell xtrace 或环境 dump。

本机执行实际 Secret 生成脚本时，将 masking stdout 留在容器私有目录；逐值核验 masking 输出与环境文件内容完全一致，只输出验证成功标识，未回显值。该验证退出码 **0**。没有向 Review 或宿主机证据目录导出 Secret 环境文件或 masking 原始输出。

GitHub Runner 对后续 step 环境展示及失败日志的实际遮蔽效果，仍需远端日志验收。

## 12. 静态检查与其他 Jobs 回归范围

- PyYAML 6.0.3 成功解析修改前后 YAML，并断言顶层配置一致、五个 job ID 一致。
- Backend、Frontend、AI Browser E2E、Deployment artifacts 的解析后结构逐项完全一致。
- E2E job 的 env、services、timeout 等非 steps 元数据完全一致。
- 除 Secret 生成步骤外，每个原步骤的完整结构均一致；仅新增三个准备步骤。
- 所有 E2E `run` 脚本经 Linux `bash -n` 检查通过。
- 实际 Secret masking 值匹配及 Linux 路径/UUID 准备成功。
- `git diff --check` 通过，仅出现 Windows 自动换行提示。

未运行 actionlint；没有将 YAML 解析等同于远端 Runner 执行通过。未运行不相关的大型 Backend/Frontend 套件。

## 13. 两名 Luna Max 独立只读复审

按用户指定分别使用 `gpt-6-luna`、`max` 思考；两名均只读，不修改文件，不启动生产/测试，不读取或 hash 受保护文件。

| 复审 | 职责 | 结果 |
| --- | --- | --- |
| Luna Max 1，`storage_review` | 受信根、identity、显式预配、Linux 路径与 C9 Option A | 通过，未发现 P0/P1；提出两项现有生产边界 P2 |
| Luna Max 2，`workflow_review` | Workflow 保真、构件入口、masking、失败传播、其他 Jobs | 通过，未发现 P0/P1/P2 |

主控逐项核对实际代码和 diff。上述复审均为静态结论，没有借用历史测试数字，也不能替代 Linux 实跑。以下 P2 已由主控核实并限定至现有生产边界，本轮不修复。

## 14. P0/P1/P2 与风险

本轮 Workflow 未发现 P0/P1。运行验收被网络环境阻断，这是明确的未验证项，不能解释为已证明不存在运行时缺陷。

两项现有生产边界 P2，均不阻塞本轮静态实施候选：

1. `TrustedImportStorageRoot.validatePath` 第 255–259 行仅拒绝 group/other 写权限，不强制精确 `0700`。部署合同要求私有根；本次 CI 创建并检查 `0700`，是部署层落实合同。权限后续漂移的运行期精确约束仍属既有边界。
2. `ImportStorageProvisioner.requireEmpty` 第 35–38 行遇到尚不存在的 root 会直接返回，因此单独调用 CLI 且祖先含 symlink 时可能先创建后再被应用拒绝。本次 CI 先核验 canonical `RUNNER_TEMP`，创建实际空根并校验完整 realpath，避免该场景；不修改正式 CLI。

不自动解决生产 Storage 安全设计、历史 Flyway、C9 合同、金融业务或 Batch B/C/D 问题。若后续出现真实新的首个业务失败，应保留证据并按授权要求进入 DESIGN CHECKPOINT。

## 15. 未验证范围与环境证据

本机可用 Docker Linux 引擎，JDK 容器为 Ubuntu Jammy / Temurin 21.0.11，Node 容器为 Node 22 Debian Bookworm，DB 为 PostgreSQL 17 Alpine。这是共享网络 namespace 模拟 localhost 服务模型，**不是完整 GitHub Hosted Ubuntu Runner 等价环境**：基础发行版、root UID、Runner 环境文件处理、真实 masking 和 Actions services 管理均有差异。

仍待验收：当前 JAR 成功构建及 hash/内容核验；正式预配首次成功与拒绝重复预配；实际 Storage 绑定与 Linux guard；后端 `e2e` 启动；V21/health；重启身份稳定；原三组完整 Playwright；远端 masking；指定提交 SHA 的实际 Job conclusion。当前未出现可供分析的新业务失败。

外部证据目录：`F:\Downloads\finance-os-g4-3-a-bc41742041b44370a79de4212be1cd76`。保留源码归档、精确 diff、静态验证脚本、构建/依赖安装失败日志与测试收集日志，不计入仓库修改。

临时资源名称：`finance-os-g4-3-a-jdk`、`finance-os-g4-3-a-node`、`finance-os-g4-3-a-pg`，以及独占 volume `finance-os-g4-3-a-20261013`。结束时停止本轮容器，保留 volume 与证据，不删除未知目录或用户文件。

## 16. 受保护文件状态与下一步

结束核验：HEAD 仍为指定 SHA，staged diff 为空；tracked 修改仅 `.github/workflows/ci.yml`，新增仅本 Review。受保护路径 `AGENTS.md` 和 `docs/codex-task.md` 仍为 untracked；只核对路径状态，没有通过内容或 hash 比较声称其内容未变。

实现已形成可审阅 diff。下一步是人工技术验收，之后单独授权提交、推送与远端验收；本轮不触发远端运行，不提前关闭 G4-3 或 Finance Freeze。

## 附录：Workflow 精确 diff

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index db5f2b1..ccda317 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -136,11 +136,76 @@ jobs:
         run: npx playwright install --with-deps chromium
       - name: Generate isolated E2E secrets
         run: |
+          jwt_secret="$(openssl rand -base64 48)"
+          migration_secret="$(openssl rand -base64 48)"
+          import_secret="$(openssl rand -base64 48)"
+          echo "::add-mask::$jwt_secret"
+          echo "::add-mask::$migration_secret"
+          echo "::add-mask::$import_secret"
           {
-            echo "E2E_JWT_SECRET=$(openssl rand -base64 48)"
-            echo "E2E_MIGRATION_PREVIEW_SECRET=$(openssl rand -base64 48)"
-            echo "FINANCE_IMPORT_CONFIRM_TOKEN_SECRET=$(openssl rand -base64 48)"
+            echo "E2E_JWT_SECRET=$jwt_secret"
+            echo "E2E_MIGRATION_PREVIEW_SECRET=$migration_secret"
+            echo "FINANCE_IMPORT_CONFIRM_TOKEN_SECRET=$import_secret"
           } >> "$GITHUB_ENV"
+      - name: Prepare isolated E2E import storage
+        run: |
+          umask 077
+          test -d "$RUNNER_TEMP"
+          test ! -L "$RUNNER_TEMP"
+          test "$(realpath -e "$RUNNER_TEMP")" = "$RUNNER_TEMP"
+          storage_parent="$(mktemp -d "$RUNNER_TEMP/finance-os-e2e-import.XXXXXXXXXX")"
+          test "$(stat -c '%a:%u' "$storage_parent")" = "700:$(id -u)"
+          raw_root="$storage_parent/raw"
+          plan_root="$storage_parent/plan"
+          mkdir -m 700 "$raw_root" "$plan_root"
+          for root in "$raw_root" "$plan_root"; do
+            test ! -L "$root"
+            test "$(realpath -e "$root")" = "$root"
+          done
+          deployment_id="$(cat /proc/sys/kernel/random/uuid)"
+          raw_id="$(cat /proc/sys/kernel/random/uuid)"
+          plan_id="$(cat /proc/sys/kernel/random/uuid)"
+          test "$raw_id" != "$plan_id"
+          {
+            echo "FINANCE_IMPORT_STORAGE_RAW_ROOT=$raw_root"
+            echo "FINANCE_IMPORT_STORAGE_PLAN_ROOT=$plan_root"
+            echo "FINANCE_IMPORT_STORAGE_DEPLOYMENT_ID=$deployment_id"
+            echo "FINANCE_IMPORT_STORAGE_RAW_ID=$raw_id"
+            echo "FINANCE_IMPORT_STORAGE_PLAN_ID=$plan_id"
+          } >> "$GITHUB_ENV"
+      - name: Build current E2E backend JAR
+        working-directory: backend
+        run: |
+          chmod +x mvnw
+          source_head="$(git rev-parse HEAD)"
+          ./mvnw -B clean package -DskipTests
+          test "$(git rev-parse HEAD)" = "$source_head"
+          jar_path="$GITHUB_WORKSPACE/backend/target/finance-os-backend-1.0.0.jar"
+          test -s "$jar_path"
+          jar tf "$jar_path" > "$RUNNER_TEMP/e2e-backend-jar-entries.txt"
+          grep -Fx 'BOOT-INF/classes/com/financeos/FinanceOsApplication.class' "$RUNNER_TEMP/e2e-backend-jar-entries.txt"
+          grep -Fx 'BOOT-INF/classes/com/financeos/module/importing/storage/ImportStorageProvisioner.class' "$RUNNER_TEMP/e2e-backend-jar-entries.txt"
+          grep -Fx 'org/springframework/boot/loader/launch/PropertiesLauncher.class' "$RUNNER_TEMP/e2e-backend-jar-entries.txt"
+          grep -Fx 'org/springframework/boot/loader/launch/JarLauncher.class' "$RUNNER_TEMP/e2e-backend-jar-entries.txt"
+          echo "E2E backend JAR built from $source_head"
+          sha256sum "$jar_path"
+      - name: Provision E2E import storage
+        working-directory: backend
+        run: |
+          umask 077
+          java -Dloader.main=com.financeos.module.importing.storage.ImportStorageProvisioner \
+            -cp target/finance-os-backend-1.0.0.jar \
+            org.springframework.boot.loader.launch.PropertiesLauncher --provision
+          for root in "$FINANCE_IMPORT_STORAGE_RAW_ROOT" "$FINANCE_IMPORT_STORAGE_PLAN_ROOT"; do
+            test ! -L "$root"
+            test "$(realpath -e "$root")" = "$root"
+            test "$(stat -c '%a:%u' "$root")" = "700:$(id -u)"
+            for binding in .import-binding .import-control; do
+              test -f "$root/$binding"
+              test ! -L "$root/$binding"
+              test "$(stat -c '%a:%u' "$root/$binding")" = "600:$(id -u)"
+            done
+          done
       - name: Start E2E backend
         working-directory: backend
         run: |
```
