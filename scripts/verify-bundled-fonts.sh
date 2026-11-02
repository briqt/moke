#!/usr/bin/env bash
# Verify source font inventory, or APK font bytes regardless of shortened resource paths.
# Usage: bash scripts/verify-bundled-fonts.sh check|apk <apk-path>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FONT_DIR="$ROOT/app/src/main/res/font"
FONTS=(jetbrains_mono.ttf noto_sans_symbols2.ttf symbols_nerd_font.ttf)
fail() { printf 'bundled-fonts: %s\n' "$*" >&2; exit 1; }

mode="${1:-check}"
[[ "$mode" == check && $# -le 1 || "$mode" == apk && $# -eq 2 ]] \
    || { printf 'Usage: %s check|apk <apk-path>\n' "$0" >&2; exit 2; }

for name in "${FONTS[@]}"; do
    [[ -s "$FONT_DIR/$name" && -f "$FONT_DIR/$name" ]] || fail "missing source font: $name"
done
shopt -s nullglob dotglob
sources=("$FONT_DIR"/*)
[[ ${#sources[@]} -eq ${#FONTS[@]} ]] || fail 'unexpected source font inventory'
for file in "${sources[@]}"; do
    case " ${FONTS[*]} " in
        *" ${file##*/} "*) ;;
        *) fail "unexpected source resource: ${file##*/}" ;;
    esac
done
if [[ "$mode" == check ]]; then
    printf 'bundled-fonts: source inventory is current.\n'
    exit 0
fi

apk="$2"
[[ -f "$apk" ]] || fail "APK not found: $apk"
entries="$(unzip -Z1 "$apk")" || fail 'cannot read APK'
fonts=()
while IFS= read -r entry; do
    case "$entry" in
        *.ttf|*.otf) fonts+=("$entry") ;;
    esac
done <<< "$entries"
[[ ${#fonts[@]} -eq ${#FONTS[@]} ]] || fail 'unexpected APK font inventory'

declare -A expected=() seen=()
for name in "${FONTS[@]}"; do
    hash="$(sha256sum "$FONT_DIR/$name")"
    expected["${hash%% *}"]="$name"
done
for entry in "${fonts[@]}"; do
    hash="$(unzip -p "$apk" "$entry" | sha256sum)" || fail "cannot read font: $entry"
    name="${expected[${hash%% *}]-}"
    [[ -n "$name" ]] || fail "unexpected font content: $entry"
    [[ -z "${seen[$name]-}" ]] || fail "duplicate font: $name"
    seen["$name"]=1
done
printf 'bundled-fonts: APK font inventory and contents match.\n'
