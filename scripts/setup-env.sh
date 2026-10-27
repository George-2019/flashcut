#!/usr/bin/env bash
# ARM64 Linux 上从零搭建 Android 构建环境（可重复执行，断点续传）
# 关键经验：Google 官方源慢且不可靠，全部改走国内镜像，速度从 50KB/s 提升到 15MB/s。
set -euo pipefail

TOOLS=/root/android/tools
SDK=/root/android/sdk
mkdir -p "$TOOLS/dl" "$SDK"

echo "== 1. JDK 17 (Temurin arm64, 清华镜像) =="
if [ ! -x "$TOOLS/jdk-17.0.20.1+1/bin/java" ]; then
  cd "$TOOLS"
  for i in $(seq 1 40); do
    curl -sL -C - --max-time 120 -o jdk17.tar.gz \
      "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/linux/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.20.1_1.tar.gz" || true
    size=$(stat -c %s jdk17.tar.gz || echo 0)
    [ "$size" -ge 191962709 ] && break
  done
  python3 -c "import tarfile;tarfile.open('jdk17.tar.gz').extractall('$TOOLS')"
fi

echo "== 2. Gradle 8.13 (腾讯镜像) =="
if [ ! -x "$TOOLS/gradle-8.13/bin/gradle" ]; then
  cd "$TOOLS"
  curl -sL -o gradle.zip "https://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip"
  python3 -c "import zipfile;zipfile.ZipFile('gradle.zip').extractall('$TOOLS')"
  chmod +x gradle-8.13/bin/gradle
fi

echo "== 3. Android cmdline-tools 11.0 (腾讯镜像) =="
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  cd "$TOOLS/dl"
  curl -sL -o cmdline.zip "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip"
  python3 -c "import zipfile;zipfile.ZipFile('cmdline.zip').extractall('/tmp/sdkextract')"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv /tmp/sdkextract/cmdline-tools "$SDK/cmdline-tools/latest"
  chmod +x "$SDK"/cmdline-tools/latest/bin/*
fi

echo "== 4. platform 35 + build-tools (腾讯镜像直接解压，绕过 x86-only 的 sdkmanager 校验) =="
cd "$TOOLS/dl"
for f in platform-35_r02.zip build-tools_r35_linux.zip platform-tools_r37.0.1-linux.zip; do
  [ -f "$f" ] || curl -sL -o "$f" "https://mirrors.cloud.tencent.com/AndroidSDK/$f"
done
python3 -c "import zipfile;[zipfile.ZipFile(f).extractall('/tmp/sdkextract') for f in ['platform-35_r02.zip','build-tools_r35_linux.zip','platform-tools_r37.0.1-linux.zip']]"
mkdir -p "$SDK/platforms" "$SDK/build-tools"
rm -rf "$SDK/platforms/android-35" "$SDK/build-tools/35.0.0" "$SDK/platform-tools"
mv /tmp/sdkextract/android-35 "$SDK/platforms/android-35"
mv /tmp/sdkextract/android-15 "$SDK/build-tools/35.0.0"
mv /tmp/sdkextract/platform-tools "$SDK/platform-tools"
chmod -R +x "$SDK/build-tools/35.0.0" "$SDK/platform-tools"

cat > "$TOOLS/env.sh" <<'EOS'
export JAVA_HOME=/root/android/tools/jdk-17.0.20.1+1
export ANDROID_HOME=/root/android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:/root/android/tools/gradle-8.13/bin:$PATH
EOS

echo
echo "环境就绪。下一步："
echo "  source $TOOLS/env.sh"

