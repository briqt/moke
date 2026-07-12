#!/usr/bin/env bash
# 构建 moke 的 mosh native 产物（可复现）：
#   - libtermux.so      : forkpty JNI（源 terminal-emulator/src/main/jni/termux.c，Apache-2.0）
#   - libmosh-client.so : mosh-client 可执行文件（mosh 1.4.0 前端 + rjyo/mosh-android 预编译静态库，GPLv3）
# 产物输出到 app/src/main/jniLibs/<abi>/（该目录下的 *.so 不入库，见 .gitignore）。
#
# 依赖：Android NDK r29（或兼容）、curl、tar、git。
# 用法：ANDROID_NDK=/path/to/ndk ./scripts/build-mosh-native.sh [abi ...]
#   abi 默认 arm64-v8a，可传 armeabi-v7a / x86_64。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABIS=("$@"); [ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a)
API=24
MOSH_TAG="mosh-1.4.0"
RJYO_URL="https://github.com/rjyo/mosh-android/releases/download/v1.0.0/mosh-android-libs-v1.0.0.tar.gz"

# 定位 NDK
NDK="${ANDROID_NDK:-}"
if [ -z "$NDK" ]; then
  NDK="$(ls -d "$HOME"/android-sdk/ndk/29.* 2>/dev/null | head -1 || true)"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "找不到 NDK，请设置 ANDROID_NDK"; exit 1; }
HOSTTAG="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"
TC="$NDK/toolchains/llvm/prebuilt/$HOSTTAG"

WORK="$REPO_ROOT/build/mosh-native"
RJYO="$WORK/rjyo/android-libs"
MOSH="$WORK/mosh-src"
mkdir -p "$WORK"

# 1) rjyo 预编译静态库 + 头文件
if [ ! -d "$RJYO" ]; then
  echo "==> 下载 rjyo/mosh-android 预编译库"
  curl -fsSL "$RJYO_URL" -o "$WORK/rjyo.tar.gz"
  mkdir -p "$WORK/rjyo"; tar xzf "$WORK/rjyo.tar.gz" -C "$WORK/rjyo"
fi
# 2) mosh 源码（前端）
if [ ! -d "$MOSH" ]; then
  echo "==> 克隆 mosh $MOSH_TAG"
  git clone --depth 1 --branch "$MOSH_TAG" https://github.com/mobile-shell/mosh.git "$MOSH"
fi

# 3) 用 rjyo 打过 Android 补丁的头覆盖到 mosh 源码树对应位置 + 补 config/version/pb.h
INC="$RJYO/include"
for h in "$INC"/*.h; do
  b="$(basename "$h")"; dst="$(find "$MOSH/src" -name "$b" 2>/dev/null | head -1)"
  [ -n "$dst" ] && cp -f "$h" "$dst" || true
done
cp -f "$INC/config.h"  "$MOSH/src/include/config.h" 2>/dev/null || true
cp -f "$INC/version.h" "$MOSH/src/frontend/version.h" 2>/dev/null || true
cp -f "$INC"/hostinput.pb.h "$INC"/transportinstruction.pb.h "$INC"/userinput.pb.h "$MOSH/src/protobufs/" 2>/dev/null || true
printf '#pragma once\n#include <string>\n#include <vector>\n#include <list>\n#include <map>\n#include <deque>\nusing namespace std;\n' > "$WORK/prelude.h"

for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a) TR=aarch64-linux-android ;;
    armeabi-v7a) TR=armv7a-linux-androideabi ;;
    x86_64) TR=x86_64-linux-android ;;
    *) echo "未知 ABI $ABI"; exit 1 ;;
  esac
  CC="$TC/bin/${TR}${API}-clang"; CXX="$TC/bin/${TR}${API}-clang++"
  OUT="$REPO_ROOT/app/src/main/jniLibs/$ABI"; mkdir -p "$OUT"
  LIBDIR="$RJYO/static/$ABI"; FE="$MOSH/src/frontend"

  echo "==> [$ABI] libtermux.so"
  "$CC" -shared -fPIC -O2 "$REPO_ROOT/terminal-emulator/src/main/jni/termux.c" -o "$OUT/libtermux.so" -llog

  echo "==> [$ABI] libmosh-client.so"
  "$CXX" -std=c++17 -O2 -fPIE -pie -fexceptions -frtti -DHAVE_CONFIG_H -Wno-deprecated-declarations \
    -include "$WORK/prelude.h" \
    -I"$INC" -I"$INC/ncursesw" -I"$MOSH" \
    -I"$FE" -I"$MOSH/src/terminal" -I"$MOSH/src/network" -I"$MOSH/src/crypto" \
    -I"$MOSH/src/statesync" -I"$MOSH/src/util" -I"$MOSH/src/protobufs" -I"$MOSH/src/include" \
    "$FE/mosh-client.cc" "$FE/stmclient.cc" "$FE/terminaloverlay.cc" \
    -o "$OUT/libmosh-client.so" \
    -Wl,--start-group \
      "$LIBDIR/libmoshnetwork.a" "$LIBDIR/libmoshstatesync.a" "$LIBDIR/libmoshterminal.a" \
      "$LIBDIR/libmoshcrypto.a" "$LIBDIR/libmoshutil.a" "$LIBDIR/libmoshprotos.a" "$LIBDIR/libprotobuf.a" \
      $(ls "$LIBDIR"/libabsl_*.a 2>/dev/null) $(ls "$LIBDIR"/libutf8_*.a 2>/dev/null) \
      "$LIBDIR/libssl.a" "$LIBDIR/libcrypto.a" "$LIBDIR/libncursesw.a" \
    -Wl,--end-group -static-libstdc++ -llog -lz -lm
  "$TC/bin/llvm-strip" "$OUT/libmosh-client.so"
  echo "==> [$ABI] 完成: $(ls -lh "$OUT"/*.so | awk '{print $9, $5}')"
done
echo "全部完成。"
