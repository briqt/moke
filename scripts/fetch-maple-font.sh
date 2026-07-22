#!/usr/bin/env bash
# 为 `maple` 发行变体准备内置字体：从 Maple Mono 官方 Release 下载整包、校验、提取 Regular 单字重，
# 放到 app/src/maple/res/font/maple_mono.ttf（该文件不入库，见 .gitignore；OFL 许可，可再分发）。
# standard 变体不需要它。CI 在构建 maple 变体前执行本脚本。
#
# 用法：./scripts/fetch-maple-font.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$REPO_ROOT/app/src/maple/res/font/maple_mono.ttf"
WORK="$REPO_ROOT/build/maple-font"

ZIP_URL="https://github.com/subframe7536/maple-font/releases/download/v7.9/MapleMonoNormalNL-NF-CN-unhinted.zip"
ZIP_SHA256="1bd6b4be3062e6ef2b4aaa44e044d05efa4501afd3c42367842154bdb0367d0b"
TTF_ENTRY="MapleMonoNormalNL-NF-CN-Regular.ttf"
TTF_SHA256="5ffe9abedc1448551d2841826eef01ecb86e2167f22686e1dae6460bc67680ad"

sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }

# 已就位且校验通过则跳过（幂等）。
if [ -f "$DEST" ] && [ "$(sha256_of "$DEST")" = "$TTF_SHA256" ]; then
  echo "==> maple 字体已就位：$DEST"; exit 0
fi

mkdir -p "$WORK" "$(dirname "$DEST")"
ZIP="$WORK/maple.zip"
if [ ! -f "$ZIP" ] || [ "$(sha256_of "$ZIP")" != "$ZIP_SHA256" ]; then
  echo "==> 下载 Maple Mono NF-CN（约 151MB）"
  curl -fsSL "$ZIP_URL" -o "$ZIP"
fi
[ "$(sha256_of "$ZIP")" = "$ZIP_SHA256" ] || { echo "❌ zip sha256 不匹配"; exit 1; }

echo "==> 提取 $TTF_ENTRY"
unzip -o "$ZIP" "$TTF_ENTRY" -d "$WORK" >/dev/null
cp -f "$WORK/$TTF_ENTRY" "$DEST"
[ "$(sha256_of "$DEST")" = "$TTF_SHA256" ] || { echo "❌ ttf sha256 不匹配"; exit 1; }
echo "==> 完成：$DEST（$(sha256_of "$DEST" | cut -c1-12)…）"
