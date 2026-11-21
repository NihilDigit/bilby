param(
    [switch]$NoBuild,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AppArgs
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$devLaunchDir = Join-Path $repoRoot 'desktop\build\dev-launch'
$javaExecFile = Join-Path $devLaunchDir 'java-exec.txt'
$argsFile = Join-Path $devLaunchDir 'bilby.args'

Push-Location -LiteralPath $repoRoot
try {
    if (-not $NoBuild -or -not (Test-Path -LiteralPath $javaExecFile) -or -not (Test-Path -LiteralPath $argsFile)) {
        & (Join-Path $repoRoot 'gradlew.bat') :desktop:prepareDevLaunch
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }

    $javaExec = (Get-Content -LiteralPath $javaExecFile -Raw -Encoding utf8).Trim()
    if (-not (Test-Path -LiteralPath $javaExec)) {
        throw "Java executable not found: $javaExec"
    }

    # 直接以独立 JVM 进程启动开发版窗口,保持工作目录在仓库根目录(便于读取 local.properties),不占用 Gradle Daemon。
    & $javaExec "@$argsFile" @AppArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
