param([string]$Serial, [switch]$Live)
$ErrorActionPreference = 'Stop'
$adbPath = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' } else { 'adb' }
if ($adbPath -ne 'adb' -and -not (Test-Path -LiteralPath $adbPath)) { throw '找不到 adb。请设置 ANDROID_HOME，或把 platform-tools 加入 PATH。' }
$deviceLines = & $adbPath devices
if ($LASTEXITCODE -ne 0) { throw 'adb devices 失败' }
$available = @($deviceLines | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $Serial) {
    if ($available.Count -ne 1) { throw '请通过 USB 连接并授权一台手机；多台设备时使用 -Serial 指定。' }
    $Serial = $available[0]
}
if ($Serial -notin $available) { throw '指定设备未连接或未授权，请查看 adb devices。' }
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$capturePath = Join-Path $workspace ('diagnostics\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $capturePath -Force | Out-Null
function Save-AdbOutput([string]$Name, [string[]]$Arguments) {
    $result = & $adbPath -s $Serial @Arguments 2>&1
    $result | Set-Content -LiteralPath (Join-Path $capturePath $Name) -Encoding utf8
    if ($LASTEXITCODE -ne 0) { Write-Warning "$Name 采集失败，已保留错误信息" }
}
Save-AdbOutput 'app-log.txt' @('logcat', '-d', '-v', 'threadtime', '-s', 'LockGuard:I', '*:S')
Save-AdbOutput 'app-package.txt' @('shell', 'dumpsys', 'package', 'com.buddy.lockguard')
Save-AdbOutput 'app-service.txt' @('shell', 'dumpsys', 'activity', 'services', 'com.buddy.lockguard')
Save-AdbOutput 'app-battery.txt' @('shell', 'dumpsys', 'batterystats', 'com.buddy.lockguard')
Save-AdbOutput 'battery-now.txt' @('shell', 'dumpsys', 'battery')
Write-Output "诊断文件已保存到 $capturePath"
if ($Live) {
    Write-Output '显示本应用实时日志；按 Ctrl+C 停止。'
    & $adbPath -s $Serial logcat -v threadtime -s 'LockGuard:I' '*:S'
}
