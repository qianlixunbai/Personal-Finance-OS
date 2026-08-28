# 开发日志（2026-07-06）：Java 21 环境与 Maven Wrapper

## 目标

统一后端开发与测试环境，解决 Maven 构建依赖本机全局环境的问题。

## 问题

- 当前终端进程无法读取最新的 `JAVA_HOME`。
- 项目原先缺少 Maven Wrapper，新环境需要依赖全局 Maven。
- Windows PowerShell 下直接执行 `mvn test` 存在环境不一致风险。
- Codex 当前进程继承的是启动时环境变量，即使系统变量已更新，也可能需要重启应用或终端后才能读取。

## 处理过程

1. 在 `backend` 目录添加 Maven Wrapper。
2. 生成 `mvnw`、`mvnw.cmd` 与 `.mvn/wrapper/`。
3. 确认本机 Java 安装路径为 `F:\ideajava\jdk21`。
4. 确认 User 与 Machine 级别 `JAVA_HOME` 均指向 `F:\ideajava\jdk21`。
5. 在当前 Codex 进程中使用临时 `JAVA_HOME` 验证 Maven Wrapper。
6. 后续统一使用 Maven Wrapper，而不是依赖全局 Maven。

## 验证

- `java -version`：Java 21.0.10。
- `F:\ideajava\jdk21\bin\java.exe`：文件存在。
- `backend\mvnw.cmd test`：构建成功。
- 单元测试结果：4 个测试全部通过。

验证命令：

```powershell
cd F:\IDEA\daima\finance-os\backend
$env:JAVA_HOME='F:\ideajava\jdk21'
.\mvnw.cmd test
```

验证结果：

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 结论

项目后端统一采用：

- Java 21
- Maven Wrapper
- Windows PowerShell 下优先使用 `.\mvnw.cmd`
- 不依赖全局 Maven

如果已设置系统变量但当前终端仍无法读取 `JAVA_HOME`，需要重启终端或 Codex 应用。
