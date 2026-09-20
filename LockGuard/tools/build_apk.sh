#!/usr/bin/env bash
# 在无 Android Studio 的机器上构建 LockGuard 的 debug APK。
#
# 一次性准备（无需 root，不写注册表）：
#   D:\AndroidSDK            命令行工具 + SDK 组件
#   D:\Gradle\gradle-8.9     Gradle 发行版
#   D:\GradleHome            GRADLE_USER_HOME，依赖缓存放这里，不占 C 盘
#
# 用法： bash tools/build_apk.sh
set -euo pipefail

# 注意：gradle.bat 是 Windows 批处理，必须传 Windows 风格路径（D:\xxx）。
# 若写成 /d/xxx，cmd 会把它当成当前盘下的相对路径，缓存会跑到 C:\d\xxx —— 这是踩过的坑。
export ANDROID_HOME="D:\\AndroidSDK"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="D:\\GradleHome"
export JAVA_HOME="C:\\Program Files\\Java\\jdk-17.0.2"
export PATH="/d/Gradle/gradle-8.9/bin:$JAVA_HOME/bin:$PATH"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

echo "=== 环境 ==="
echo "JAVA_HOME        = $JAVA_HOME"
echo "ANDROID_HOME     = $ANDROID_HOME"
echo "GRADLE_USER_HOME = $GRADLE_USER_HOME"
java -version 2>&1 | head -1
gradle --version 2>/dev/null | grep -E "^Gradle" || true
echo

echo "=== 清理旧产物 ==="
rm -rf app/build/outputs/apk

echo "=== 构建 debug APK（首次会下载约 1.5–2.5 GB 依赖，之后可离线）==="
gradle assembleDebug

echo
echo "=== 产物 ==="
find app/build/outputs/apk -name "*.apk" -exec ls -lh {} \;

echo
echo "=== 释放内存：停掉 Gradle daemon ==="
gradle --stop
