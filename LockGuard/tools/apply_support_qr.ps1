param([switch]$Restore)
$ErrorActionPreference = 'Stop'

# 把本机私有的收款码应用到 APK 资源里，但**不提交**到仓库。
#
# 仓库里 tracking 的是占位二维码（support_wechat.png / support_alipay.png）。
# 你本地的真码放在 .tools/qr/real_wechat.png 与 real_alipay.png（该目录已被忽略）。
#
#   -Apply    用真码覆盖占位图，并对这两个文件设置 git skip-worktree（之后 git 忽略本地改动）
#   -Restore  恢复成仓库里的占位图，并取消 skip-worktree
#
# 注意：skip-worktree 只影响本仓库的 git 行为，不是加密。别把 .tools/ 手动上传。

$repoRoot    = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$resDir      = Join-Path $repoRoot 'LockGuard\app\src\main\res\drawable-nodpi'
$srcDir      = Join-Path $repoRoot '.tools\qr'
$targets = @{
    'support_wechat.png' = Join-Path $srcDir 'real_wechat.png'
    'support_alipay.png' = Join-Path $srcDir 'real_alipay.png'
}

Push-Location $repoRoot
try {
    if ($Restore) {
        foreach ($name in $targets.Keys) {
            git update-index --no-skip-worktree -- "LockGuard/app/src/main/res/drawable-nodpi/$name" 2>$null
            git checkout -- "LockGuard/app/src/main/res/drawable-nodpi/$name"
            Write-Output "已恢复占位图: $name"
        }
        return
    }

    $missing = $targets.Values | Where-Object { -not (Test-Path -LiteralPath $_) }
    if ($missing) {
        throw ("缺少本机收款码原件，请先放入：`n  " + ($missing -join "`n  "))
    }
    foreach ($name in $targets.Keys) {
        Copy-Item -LiteralPath $targets[$name] -Destination (Join-Path $resDir $name) -Force
        git update-index --skip-worktree -- "LockGuard/app/src/main/res/drawable-nodpi/$name"
        Write-Output "已应用真码并锁定: $name"
    }
    Write-Output ''
    Write-Output '现在构建出的 APK 会带你的收款码。构建完想恢复占位图执行：'
    Write-Output '  powershell -File LockGuard/tools/apply_support_qr.ps1 -Restore'
} finally { Pop-Location }
