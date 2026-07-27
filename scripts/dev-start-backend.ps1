$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $repoRoot "backend"
$envFile = Join-Path $backendDir ".env.local"

if (-not (Test-Path -LiteralPath $envFile)) {
    Write-Host "backend/.env.local not found."
    Write-Host "Please copy backend/.env.example to backend/.env.local and fill in your local configuration."
    exit 1
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()

    if ([string]::IsNullOrWhiteSpace($line)) {
        return
    }

    if ($line.StartsWith("#")) {
        return
    }

    $separatorIndex = $line.IndexOf("=")
    if ($separatorIndex -le 0) {
        Write-Warning "Ignoring invalid .env.local line. Expected KEY=VALUE format."
        return
    }

    $key = $line.Substring(0, $separatorIndex).Trim()
    $value = $line.Substring($separatorIndex + 1).Trim()

    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    Set-Item -Path "Env:$key" -Value $value
}

$requiredVariables = @("JWT_SECRET", "MIGRATION_PREVIEW_SECRET", "DB_USERNAME", "DB_PASSWORD")
$missingVariables = @()

foreach ($name in $requiredVariables) {
    $value = [Environment]::GetEnvironmentVariable($name, "Process")
    if ([string]::IsNullOrWhiteSpace($value)) {
        $missingVariables += $name
    }
}

if ($missingVariables.Count -gt 0) {
    Write-Host "Missing required values in backend/.env.local: $($missingVariables -join ', ')"
    exit 1
}

if ($env:JWT_SECRET.Length -lt 32) {
    Write-Host "JWT_SECRET must be at least 32 characters for local development."
    exit 1
}

if ($env:MIGRATION_PREVIEW_SECRET.Length -lt 32) {
    Write-Host "MIGRATION_PREVIEW_SECRET must be at least 32 characters for local development."
    exit 1
}

if ($env:JWT_SECRET -eq $env:MIGRATION_PREVIEW_SECRET) {
    Write-Host "MIGRATION_PREVIEW_SECRET must not reuse JWT_SECRET."
    exit 1
}

Push-Location $backendDir
try {
    .\mvnw.cmd spring-boot:run
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
