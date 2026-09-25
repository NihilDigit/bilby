# Bilby desktop updater, started detached by DesktopAppUpdater right before the app exits.
# Waits for the app to exit, applies the staged update, then starts the app again.
#   patch: copy staged files over the install dir. Every file is copied next to its target
#          first, then swapped in by rename; the replaced files are kept until all swaps
#          succeed, so a failure rolls back to the old image instead of a half-updated one.
#   msi:   msiexec /i. The MSI's own upgrade (remove then install) needs the app closed.
# Kept ASCII-only: Windows PowerShell 5.1 reads a script without BOM in the ANSI code page.
param(
    [Parameter(Mandatory = $true)] [int] $ProcessId,
    [Parameter(Mandatory = $true)] [string] $InstallDir,
    [Parameter(Mandatory = $true)] [ValidateSet('patch', 'msi')] [string] $Mode,
    [Parameter(Mandatory = $true)] [string] $Source,
    [Parameter(Mandatory = $true)] [string] $Executable,
    [Parameter(Mandatory = $true)] [string] $LogFile
)

$ErrorActionPreference = 'Stop'

function Write-Log([string] $message) {
    $line = '{0:yyyy-MM-dd HH:mm:ss.fff} {1}' -f (Get-Date), $message
    Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
}

function Wait-AppExit {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $true }
    return $process.WaitForExit(120000)
}

function Install-Patch {
    $sourceRoot = (Resolve-Path -LiteralPath $Source).Path.TrimEnd('\')
    $files = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File)
    if ($files.Count -eq 0) { throw "no staged files in $sourceRoot" }
    $entries = foreach ($file in $files) {
        $relative = $file.FullName.Substring($sourceRoot.Length + 1)
        [pscustomobject]@{
            Source = $file
            Target = Join-Path $InstallDir $relative
        }
    }

    $replaced = New-Object System.Collections.Generic.List[string]
    $placed = New-Object System.Collections.Generic.List[string]
    try {
        foreach ($entry in $entries) {
            $target = $entry.Target
            foreach ($stale in @("$target.new", "$target.old")) {
                if (Test-Path -LiteralPath $stale) { Remove-Item -LiteralPath $stale -Force }
            }
            $parent = Split-Path -Parent $target
            if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
            Copy-Item -LiteralPath $entry.Source.FullName -Destination "$target.new"
            # Keep the staged mtime: the manifest records it and later patches compare against it.
            (Get-Item -LiteralPath "$target.new").LastWriteTimeUtc = $entry.Source.LastWriteTimeUtc
        }
        foreach ($entry in $entries) {
            $target = $entry.Target
            if (Test-Path -LiteralPath $target) {
                Move-Item -LiteralPath $target -Destination "$target.old"
                $replaced.Add($target)
            }
            Move-Item -LiteralPath "$target.new" -Destination $target
            $placed.Add($target)
            Write-Log "replaced $target"
        }
    } catch {
        Write-Log "patch failed, rolling back: $_"
        foreach ($target in $placed) { Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue }
        foreach ($target in $replaced) { Move-Item -LiteralPath "$target.old" -Destination $target -ErrorAction SilentlyContinue }
        foreach ($entry in $entries) { Remove-Item -LiteralPath "$($entry.Target).new" -Force -ErrorAction SilentlyContinue }
        throw
    }
    foreach ($target in $replaced) { Remove-Item -LiteralPath "$target.old" -Force -ErrorAction SilentlyContinue }
}

function Install-Msi {
    $msiLog = Join-Path (Split-Path -Parent $LogFile) 'msiexec.log'
    $arguments = @('/i', "`"$Source`"", '/passive', '/norestart', '/l*v', "`"$msiLog`"")
    $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $arguments -Wait -PassThru
    # 3010: success, reboot required.
    if ($process.ExitCode -ne 0 -and $process.ExitCode -ne 3010) {
        throw "msiexec exited with $($process.ExitCode), see $msiLog"
    }
}

Write-Log "update started: mode=$Mode pid=$ProcessId dir=$InstallDir"
if (-not (Wait-AppExit)) {
    Write-Log 'app did not exit within 120 s, update aborted'
    exit 1
}
Write-Log 'app exited'

$exitCode = 0
try {
    if ($Mode -eq 'patch') { Install-Patch } else { Install-Msi }
    Write-Log 'update applied'
} catch {
    Write-Log "update failed: $_"
    $exitCode = 1
    # Read by DesktopAppUpdater on the next launch, which then says the update did not finish.
    Set-Content -LiteralPath (Join-Path (Split-Path -Parent $LogFile) 'failed') -Value "$_" -Encoding UTF8
}

# Start the app either way: after a failure the old version is still intact. For the msi mode
# that holds because the MSI removes the old version inside its install transaction (see
# desktop/package/windows/transactional-upgrade.ps1).
$exe = Join-Path $InstallDir $Executable
try {
    Start-Process -FilePath $exe -WorkingDirectory $InstallDir
    Write-Log "started $exe"
} catch {
    Write-Log "could not start $exe : $_"
    $exitCode = 1
}
exit $exitCode
