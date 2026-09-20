#!/usr/bin/env bash
# 在无 Android Studio 的机器上构建 LockGuard 的 debug APK。
#
# 用法：
#   1) 复制仓库根的 local.env.example 为 local.env，按你的机器改路径（local.env 已被忽略，不会提交）
#   2) bash LockGuard/tools/build_apk.sh
#
# 依赖：JDK 17、Android SDK（含 platform-tools 与 platforms;android-35）、Gradle 8.x
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$PROJECT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# 本机路径不写死在脚本里：优先读仓库根的 local.env（已忽略），其次用已有环境变量。
if [ -f "$REPO_ROOT/local.env" ]; then
  # local.env 是 KEY=VALUE 配置，不是 shell 脚本。保留 Windows 反斜杠和路径中的空格。
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    case "$key" in
      ANDROID_HOME|GRADLE_HOME|GRADLE_USER_HOME|JAVA_HOME)
        value="${value#\"}"; value="${value%\"}"
        value="${value#\'}"; value="${value%\'}"
        export "$key=$value"
        ;;
    esac
  done < "$REPO_ROOT/local.env"
fi

: "${ANDROID_HOME:?未设置 ANDROID_HOME。请复制 local.env.example 为 local.env 并填入你的 Android SDK 路径}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# GRADLE_USER_HOME 可选；不设则用 Gradle 默认位置。
if [ -n "${GRADLE_USER_HOME:-}" ]; then
  export GRADLE_USER_HOME
fi

# 注意（Windows 上的坑）：调 gradle.bat 这类批处理时，路径必须写成 Windows 风格 D:\xxx。
# 若写成 /d/xxx，cmd 会把它当成当前盘下的相对路径，缓存会落到 C:\d\xxx。
# GRADLE_HOME 用 Windows 风格写在 local.env 里，这里用 cygpath 转成 POSIX 路径进 PATH。
if [ -n "${GRADLE_HOME:-}" ]; then
  if command -v cygpath >/dev/null 2>&1; then
    export PATH="$(cygpath -u "$GRADLE_HOME")/bin:$PATH"
  else
    export PATH="$GRADLE_HOME/bin:$PATH"
  fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
  if command -v cygpath >/dev/null 2>&1; then
    export PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"
  else
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

command -v gradle >/dev/null 2>&1 || {
  echo "找不到 gradle。请安装 Gradle 8.x，或在 local.env 里设置 GRADLE_HOME=<gradle解压目录>" >&2
  exit 1
}

echo "=== 环境 ==="
echo "JAVA_HOME        = ${JAVA_HOME:-（未设置，用系统默认 java）}"
echo "ANDROID_HOME     = $ANDROID_HOME"
echo "GRADLE_USER_HOME = ${GRADLE_USER_HOME:-（默认）}"
echo "GRADLE_HOME      = ${GRADLE_HOME:-（用 PATH 上的 gradle）}"
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
