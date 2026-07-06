# 開発ログ（2026-07-06）：Java 21 環境と Maven Wrapper

## 目的

バックエンドの開発・テスト環境を統一し、マシン全体の Maven への依存をなくす。

## 問題

- 現在のターミナルプロセスから最新の `JAVA_HOME` が読めなかった。
- プロジェクトに Maven Wrapper がなく、グローバル Maven に依存していた。
- Windows PowerShell で直接 `mvn test` を実行すると、環境差異のリスクがあった。
- 現在の Codex プロセスは起動時の環境変数を引き継いでいるため、システム変数を更新しても、アプリやターミナルの再起動が必要な場合があった。

## 対応手順

1. `backend` に Maven Wrapper を追加した。
2. `mvnw`、`mvnw.cmd`、`.mvn/wrapper/` を生成した。
3. ローカルの Java インストール先を `F:\ideajava\jdk21` と確認した。
4. User / Machine の両方で `JAVA_HOME` が `F:\ideajava\jdk21` を指していることを確認した。
5. 現在の Codex プロセスで一時的な `JAVA_HOME` を設定して Maven Wrapper を検証した。
6. 以後はグローバル Maven ではなく Maven Wrapper を標準とした。

## 検証

- `java -version`: Java 21.0.10
- `F:\ideajava\jdk21\bin\java.exe`: 存在する
- `backend\mvnw.cmd test`: ビルド成功
- 単体テスト: 4 件中 4 件成功

検証コマンド:

```powershell
cd F:\IDEA\daima\finance-os\backend
$env:JAVA_HOME='F:\ideajava\jdk21'
.\mvnw.cmd test
```

検証結果:

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 結論

バックエンドは次の構成で統一する:

- Java 21
- Maven Wrapper
- PowerShell では `.\mvnw.cmd` を優先する
- グローバル Maven には依存しない

システム変数を設定済みでも現在のターミナルで `JAVA_HOME` が読めない場合は、ターミナルまたは Codex アプリを再起動する。
