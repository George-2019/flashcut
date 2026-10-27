#!/usr/bin/env bash
# 在本机（arm64 Android / Termux 环境）构建 FlashCut debug APK
# 依赖：scripts/setup-env.sh 已执行过，且已装好 Termux 版 arm64 aapt2
set -euo pipefail

export JAVA_HOME=/root/android/tools/jdk-17.0.20.1+1
export ANDROID_HOME=/root/android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/root/android/tools/gradle-8.13/bin:$PATH

# aapt2 是 Termux 编译版，运行时需要从 build-tools/lib64 找依赖库
export LD_LIBRARY_PATH=$ANDROID_HOME/build-tools/35.0.0/lib64

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "== 检查 aapt2 =="
$ANDROID_HOME/build-tools/35.0.0/aapt2 version 2>&1 | tail -1

echo "== 构建 debug APK =="
gradle :app:assembleDebug --no-daemon --console=plain

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
echo
echo "产物: $APK"
ls -lh "$APK"
cp -f "$APK" "$ROOT/FlashCut-v0.1-debug.apk"
echo "已复制到: $ROOT/FlashCut-v0.1-debug.apk"
