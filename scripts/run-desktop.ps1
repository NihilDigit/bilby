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

if (-not $NoBuild -or -not (Test-Path $javaExecFile) -or -not (Test-Path $argsFile)) {
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot 'gradlew.bat') :desktop:prepareDevLaunch
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }
}

$javaExec = (Get-Content -Path $javaExecFile -Raw).Trim()
if (-not (Test-Path $javaExec)) {
    throw "Java executable not found: $javaExec"
}

# 直接以独立 JVM 进程启动开发版窗口,不占用 Gradle Daemon。
& $javaExec "@$argsFile" @AppArgs
exit $LASTEXITCODE
