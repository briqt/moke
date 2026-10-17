#!/usr/bin/env bash
# 构建 netbridge（Go：tailnet 节点、代理与 mosh 中继）的 Android AAR → app/libs/netbridge.aar（不入库）。
#
# 版本全部锁定：Go 取 netbridge/go.mod 的 toolchain 行（本机版本不符即失败，不自动下载），
# gomobile/gobind 取 go.mod 的 tool 依赖；-trimpath 与空 buildid 使产物可复现。
# 依赖：上述 Go、Android NDK r30、Android SDK（ANDROID_HOME）。
# 用法：ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-netbridge.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$REPO_ROOT/netbridge"
OUT="$REPO_ROOT/app/libs/netbridge.aar"
WORK="$REPO_ROOT/build/netbridge"
NDK_VERSION="30.0.16248370"

want="$(sed -n 's/^toolchain //p' "$MODULE/go.mod")"
have="$(GOTOOLCHAIN=local go env GOVERSION 2>/dev/null || true)"
[ -n "$want" ] && [ "$have" = "$want" ] || { echo "需要 $want，当前为 ${have:-未安装}"; exit 1; }

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
[ -n "$NDK" ] || NDK="${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/$NDK_VERSION"
[ -d "$NDK" ] || { echo "找不到 NDK $NDK_VERSION，请设置 ANDROID_NDK_HOME"; exit 1; }
grep -q "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties" || { echo "NDK 版本不是 $NDK_VERSION：$NDK"; exit 1; }

export GOTOOLCHAIN=local GOFLAGS="-trimpath -buildvcs=false" CGO_ENABLED=1 ANDROID_NDK_HOME="$NDK"
mkdir -p "$WORK/bin" "$(dirname "$OUT")"
(cd "$MODULE" && GOBIN="$WORK/bin" go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind)

echo "==> gomobile bind（$want，NDK $NDK_VERSION，arm64，API 30）"
rm -f "$OUT"
(cd "$MODULE" && PATH="$WORK/bin:$PATH" gomobile bind \
  -target=android/arm64 -androidapi=30 -javapkg=com.briqt.moke.go \
  -ldflags="-s -w -buildid=" -o "$OUT" .)
rm -f "${OUT%.aar}-sources.jar"
echo "==> 完成：$OUT（$(du -h "$OUT" | cut -f1)）"
