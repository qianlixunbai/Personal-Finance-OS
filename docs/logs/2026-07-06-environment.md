# Development Log (2026-07-06): Java 21 Environment and Maven Wrapper

## Goal

Standardize the backend development and test environment and remove the dependency on the machine-wide Maven installation.

## Issue

- The current terminal process could not read the latest `JAVA_HOME`.
- The project did not have Maven Wrapper, so the environment depended on the global Maven installation.
- Running `mvn test` directly in Windows PowerShell created environment drift risk.
- The current Codex process inherited startup-time environment variables, so even after the system variables were updated, a restart of the app or terminal might still be required before they became visible.

## Process

1. Added Maven Wrapper under `backend`.
2. Generated `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/`.
3. Confirmed the local Java installation path as `F:\ideajava\jdk21`.
4. Confirmed that both User and Machine level `JAVA_HOME` point to `F:\ideajava\jdk21`.
5. Used a temporary `JAVA_HOME` in the current Codex process to verify Maven Wrapper.
6. Standardized on Maven Wrapper instead of the global Maven installation.

## Validation

- `java -version`: Java 21.0.10.
- `F:\ideajava\jdk21\bin\java.exe`: exists.
- `backend\mvnw.cmd test`: build succeeded.
- Unit tests: 4 out of 4 passed.

Validation command:

```powershell
cd F:\IDEA\daima\finance-os\backend
$env:JAVA_HOME='F:\ideajava\jdk21'
.\mvnw.cmd test
```

Validation result:

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Conclusion

The backend now standardizes on:

- Java 21
- Maven Wrapper
- `.\mvnw.cmd` as the preferred PowerShell command
- No dependency on global Maven

If system variables are already configured but the current terminal still cannot read `JAVA_HOME`, restart the terminal or the Codex app.
