# 把 MSI 的 RemoveExistingProducts 挪进安装事务。
#
# jpackage 生成的 MSI 把它排在 InstallInitialize 之前，卸载旧版单独提交：新版随后装失败时，
# 旧版已经没了，应用内更新拉起的只是一个空目录。排在 InstallInitialize 之后，卸载与安装同属
# 一个事务，任一步失败都整体回滚，旧版原样留下。这正是 WiX MajorUpgrade 的
# afterInstallInitialize 排法；卸载仍在装新文件之前，组件规则上与原来没有区别。
param([Parameter(Mandatory = $true)][string]$Msi)
$ErrorActionPreference = 'Stop'

$installer = New-Object -ComObject WindowsInstaller.Installer
# 1 = msiOpenDatabaseModeTransact：改动在 Commit 之前不落盘
$db = $installer.OpenDatabase($Msi, 1)

function Invoke-Query([string]$sql) {
    $view = $db.OpenView($sql)
    # 不丢弃的话 Execute 与 Close 的返回值也混进函数输出，拿到的是数组而不是那条记录
    [void]$view.Execute()
    $record = $view.Fetch()
    [void]$view.Close()
    return $record
}
function Invoke-Update([string]$sql) {
    $view = $db.OpenView($sql)
    [void]$view.Execute()
    [void]$view.Close()
}
function Get-Sequence([string]$action) {
    $record = Invoke-Query "SELECT ``Sequence`` FROM ``InstallExecuteSequence`` WHERE ``Action`` = '$action'"
    if ($null -eq $record) { throw "InstallExecuteSequence 里没有 $action" }
    return [int]$record.GetType().InvokeMember('IntegerData', 'GetProperty', $null, $record, @(1))
}

$target = (Get-Sequence 'InstallInitialize') + 1
if ((Get-Sequence 'RemoveExistingProducts') -eq $target) {
    Write-Output "RemoveExistingProducts already at $target"
    exit 0
}
$occupied = Invoke-Query "SELECT ``Action`` FROM ``InstallExecuteSequence`` WHERE ``Sequence`` = $target"
if ($null -ne $occupied) { throw "序号 $target 已被占用，jpackage 的模板可能变了" }

Invoke-Update "UPDATE ``InstallExecuteSequence`` SET ``Sequence`` = $target WHERE ``Action`` = 'RemoveExistingProducts'"
$db.Commit()
Write-Output "RemoveExistingProducts -> $target"
