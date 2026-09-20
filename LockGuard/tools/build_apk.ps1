param([switch]$Online)
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot    = (Resolve-Path (Join-Path $projectRoot '..')).Path

# 本机路径不写死在脚本里：读仓库根的 local.env（已忽略，不会提交）。
# 也支持先用环境变量设好，local.env 只做补充覆盖。
$localEnv = Join-Path $repoRoot 'local.env'
if (Test-Path -LiteralPath $localEnv) {
    foreach ($line in Get-Content -LiteralPath $localEnv) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $k = $t.Substring(0, $i).Trim()
        $v = $t.Substring($i + 1).Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($k, $v, 'Process')
    }
}

if (-not $env:ANDROID_HOME) {
    throw '未设置 ANDROID_HOME。请复制仓库根的 local.env.example 为 local.env 并填入你的 Android SDK 路径。'
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

$gradleExe = if ($env:GRADLE_HOME) { Join-Path $env:GRADLE_HOME 'bin\gradle.bat' } else { 'gradle' }
if ($gradleExe -ne 'gradle' -and -not (Test-Path -LiteralPath $gradleExe)) {
    throw "找不到 Gradle：$gradleExe。请在 local.env 里修正 GRADLE_HOME，或把 gradle 放进 PATH。"
}

# JDK 17 Windows 的测试 worker 参数文件不能可靠处理中文 classpath。
# 从当前源码创建独立 ASCII 临时构建目录；不修改原项目位置。
$buildRoot = Join-Path ([IO.Path]::GetTempPath()) ('LockGuard-build-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $buildRoot 'app') -Force | Out-Null
foreach ($name in @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'local.properties')) {
    if (Test-Path -LiteralPath (Join-Path $projectRoot $name)) {
        Copy-Item -LiteralPath (Join-Path $projectRoot $name) -Destination $buildRoot
    }
}
foreach ($name in @('build.gradle.kts', 'proguard-rules.pro', 'src')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot "app\$name") -Destination (Join-Path $buildRoot 'app') -Recurse
}
Push-Location $buildRoot
try {
    $buildArgs = @(':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug', '--console=plain')
    if (-not $Online) { $buildArgs += '--offline' }
    & $gradleExe @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE; staging: $buildRoot" }
    $destination = Join-Path $projectRoot 'app\build'
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    foreach ($name in @('outputs', 'reports', 'test-results')) {
        $src = Join-Path $buildRoot "app\build\$name"
        if (Test-Path -LiteralPath $src) {
            Copy-Item -LiteralPath $src -Destination $destination -Recurse -Force
        }
    }
    Set-Content -LiteralPath (Join-Path $destination 'last-staging-path.txt') -Value $buildRoot -Encoding utf8
    Write-Output "Verified APK and reports copied to $destination"
} finally { Pop-Location }
