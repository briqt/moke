#!/usr/bin/env bash
# Content-based AAR verification. Requires Bash and GNU coreutils; never invokes Go.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAR="$REPO_ROOT/app/libs/netbridge.aar"
FORMAT="moke-netbridge-aar-v1"

fail() {
  printf 'netbridge: %s\nRebuild with: bash scripts/build-netbridge.sh\n' "$*" >&2
  exit 1
}

file_hash() {
  local result
  result="$(sha256sum < "$1")" || return
  printf '%s\n' "${result%% *}"
}

# Paths and contents both participate, sorted independently of the host locale.
# Test sources do not enter the Android library. Locked build flags/versions are
# covered by the build script and go.mod, not duplicated in another rule list.
fingerprint() {
  (
    cd "$REPO_ROOT"
    printf '%s\0' netbridge/go.mod netbridge/go.sum scripts/build-netbridge.sh scripts/verify-netbridge.sh
    find netbridge -type f -name '*.go' ! -name '*_test.go' -print0
  ) | LC_ALL=C sort -z | while IFS= read -r -d '' path; do
    hash="$(file_hash "$REPO_ROOT/$path")" || exit 1
    printf '%s\0%s\0' "$path" "$hash"
  done | sha256sum | cut -d ' ' -f 1
}

check() {
  [ -s "$AAR" ] || fail 'AAR is missing or empty.'
  local resolved stamp inputs actual
  resolved="$(readlink -f -- "$AAR")" || fail 'Cannot resolve the AAR.'
  stamp="$resolved.fingerprint"
  [ -f "$stamp" ] || fail 'AAR fingerprint is missing.'
  [ "$(wc -c < "$stamp")" -le 256 ] || fail 'AAR fingerprint is invalid.'
  local -a fields
  mapfile -t fields < "$stamp"
  [ "${#fields[@]}" -eq 3 ] || fail 'AAR fingerprint is invalid.'
  [[ "${fields[0]}" == "$FORMAT" && "${fields[1]}" =~ ^[0-9a-f]{64}$ && "${fields[2]}" =~ ^[0-9a-f]{64}$ ]] || fail 'AAR fingerprint is invalid.'
  inputs="$(fingerprint)" || fail 'Cannot fingerprint the production inputs.'
  [ "$inputs" = "${fields[1]}" ] || fail 'AAR is stale: production inputs or locked build settings changed.'
  actual="$(file_hash "$resolved")" || fail 'Cannot read the AAR.'
  [ "$actual" = "${fields[2]}" ] || fail 'AAR content does not match its fingerprint (replaced or damaged).'
  [ "$(readlink -f -- "$AAR")" = "$resolved" ] || fail 'AAR changed during verification; retry.'
  printf 'netbridge: AAR is current (inputs=%s, aar=%s).\n' "$inputs" "$actual"
}

case "${1:-check}" in
  fingerprint)
    [ "$#" -eq 1 ] || fail 'Usage: verify-netbridge.sh fingerprint'
    fingerprint
    ;;
  check)
    [ "$#" -le 1 ] || fail 'Usage: verify-netbridge.sh check'
    check
    ;;
  record)
    # Only the builder calls this, on an unpublished AAR. Verify that inputs did
    # not change during compilation before creating the companion stamp.
    [ "$#" -eq 3 ] || fail 'Usage: verify-netbridge.sh record AAR INPUT_FINGERPRINT'
    [ -s "$2" ] || fail 'The build did not produce a nonempty AAR.'
    inputs="$(fingerprint)" || fail 'Cannot fingerprint the production inputs.'
    [ "$inputs" = "$3" ] || fail 'Production inputs changed during the build; output was not published.'
    aar_hash="$(file_hash "$2")" || fail 'Cannot hash the built AAR.'
    printf '%s\n%s\n%s\n' "$FORMAT" "$inputs" "$aar_hash" > "$2.fingerprint"
    ;;
  *) fail 'Usage: verify-netbridge.sh [check|fingerprint|record AAR INPUT_FINGERPRINT]' ;;
esac
