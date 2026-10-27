#!/usr/bin/env bash
# FlashCut 构建环境一键配置（ARM64 Linux / Termux / 容器内均可参考）
# 用法: source scripts/env-arm64.sh
export JAVA_HOME=/root/android/tools/jdk-17.0.20.1+1
export ANDROID_HOME=/root/android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:/root/android/tools/gradle-8.13/bin:$PATH
echo "JAVA: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
echo "SDK : $ANDROID_HOME ($(ls $ANDROID_HOME/platforms 2>/dev/null | tr '\n' ' '))"
echo "GRADLE: $(gradle --version 2>/dev/null | grep '^Gradle' | head -1)"
