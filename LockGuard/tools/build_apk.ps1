param([switch]$Online)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:GRADLE_USER_HOME = 'D:\GradleHome'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
# JDK 17 Windows 的测试 worker 参数文件不能可靠处理中文 classpath。
# 从当前源码创建独立 ASCII 临时构建目录；不修改原项目位置。
$buildRoot = Join-Path ([IO.Path]::GetTempPath()) ('LockGuard-build-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $buildRoot 'app') -Force | Out-Null
foreach ($name in @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'local.properties')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $name) -Destination $buildRoot
}
foreach ($name in @('build.gradle.kts', 'proguard-rules.pro', 'src')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot "app\$name") -Destination (Join-Path $buildRoot 'app') -Recurse
}
Push-Location $buildRoot
try {
    $buildArgs = @(':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug', '--console=plain')
    if (-not $Online) { $buildArgs += '--offline' }
    & 'D:\Gradle\gradle-8.9\bin\gradle.bat' @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE; staging: $buildRoot" }
    $destination = Join-Path $projectRoot 'app\build'
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    foreach ($name in @('outputs', 'reports', 'test-results')) {
        Copy-Item -LiteralPath (Join-Path $buildRoot "app\build\$name") -Destination $destination -Recurse -Force
    }
    Set-Content -LiteralPath (Join-Path $destination 'last-staging-path.txt') -Value $buildRoot -Encoding utf8
    Write-Output "Verified APK and reports copied to $destination"
} finally { Pop-Location }
