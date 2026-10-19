#!/usr/bin/env bash
# Isolated regression fixtures: no real Go sources, compiler or Android tools are changed.
# An optional builder path permits replaying these assertions against an older builder.
set -euo pipefail
SOURCE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILDER="${1:-$SOURCE_ROOT/scripts/build-netbridge.sh}"
FIXTURE="$(mktemp -d)"
trap 'rm -rf -- "$FIXTURE"' EXIT
mkdir -p "$FIXTURE"/{scripts,netbridge,app/libs,tools,ndk}
cp "$BUILDER" "$FIXTURE/scripts/build-netbridge.sh"
cp "$SOURCE_ROOT/scripts/verify-netbridge.sh" "$FIXTURE/scripts/verify-netbridge.sh"
printf 'module fixture\n\ngo 1.27.0\ntoolchain go1.27.2\n' > "$FIXTURE/netbridge/go.mod"
printf 'fixture module sum\n' > "$FIXTURE/netbridge/go.sum"
printf 'package fixture\n// production one\n' > "$FIXTURE/netbridge/bridge.go"
printf 'package fixture\n// test one\n' > "$FIXTURE/netbridge/bridge_test.go"
printf 'Pkg.Revision = 30.0.16248370\n' > "$FIXTURE/ndk/source.properties"

cat > "$FIXTURE/tools/go" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [ "$*" = 'env GOVERSION' ]; then printf 'go1.27.2\n'; exit 0; fi
[ "$1" = install ] || exit 99
mkdir -p "$GOBIN"
cp "$TEST_TOOLS/gomobile" "$GOBIN/gomobile"
STUB
cat > "$FIXTURE/tools/gomobile" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
out=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = -o ]; then out="$2"; shift; fi
  shift
done
[ -n "$out" ]
printf 'bind\n' >> "$TEST_BIND_LOG"
printf 'fixture AAR bytes\n' > "$out"
case "${TEST_BIND_MODE:-success}" in
  fail) printf 'partial build\n' > "$out"; exit 17 ;;
  interrupt)
    printf 'partial build\n' > "$out"
    : > "$TEST_BIND_READY"
    until [ -e "$TEST_BIND_CONTINUE" ]; do sleep 0.01; done
    exit 143 ;;
  source-change) printf '// changed during bind\n' >> bridge.go ;;
esac
STUB
chmod +x "$FIXTURE/tools/go" "$FIXTURE/tools/gomobile"
export PATH="$FIXTURE/tools:$PATH" ANDROID_NDK_HOME="$FIXTURE/ndk"
export TEST_TOOLS="$FIXTURE/tools" TEST_BIND_LOG="$FIXTURE/binds.log"
export TEST_BIND_READY="$FIXTURE/ready" TEST_BIND_CONTINUE="$FIXTURE/continue"
CHECK="$FIXTURE/scripts/verify-netbridge.sh"
BUILD="$FIXTURE/scripts/build-netbridge.sh"
AAR="$FIXTURE/app/libs/netbridge.aar"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
reject() {
  if bash "$CHECK" check > "$FIXTURE/check.log" 2>&1; then fail "$1 was accepted"; fi
  grep -q 'Rebuild with: bash scripts/build-netbridge.sh' "$FIXTURE/check.log" || fail 'missing rebuild guidance'
  printf 'PASS: rejected %s\n' "$1"
  while IFS= read -r line; do printf '  %s\n' "$line"; done < "$FIXTURE/check.log"
}
current() { bash "$CHECK" check; }

reject 'missing AAR'
# A known-good existing artifact, including a stamp from the same algorithm.
printf 'previous valid AAR\n' > "$AAR"
bash "$CHECK" record "$AAR" "$(bash "$CHECK" fingerprint)"
current
cp "$AAR" "$FIXTURE/previous.aar"

# No change and test-only changes reuse the artifact even if a bind would fail.
if ! TEST_BIND_MODE=fail bash "$BUILD"; then
  cmp -s "$AAR" "$FIXTURE/previous.aar" || fail 'failed build replaced or deleted the previous AAR'
  fail 'unchanged inputs attempted a bind'
fi
printf '// test changed\n' >> "$FIXTURE/netbridge/bridge_test.go"
TEST_BIND_MODE=fail bash "$BUILD"
[ ! -e "$TEST_BIND_LOG" ] || fail 'unchanged/test-only inputs invoked gomobile'
printf 'PASS: no-change and test-only reuse\n'

mv "$AAR.fingerprint" "$FIXTURE/stamp.saved"
reject 'missing stamp'
mv "$FIXTURE/stamp.saved" "$AAR.fingerprint"
printf 'replacement AAR\n' > "$AAR"
reject 'replaced AAR'
cp "$FIXTURE/previous.aar" "$AAR"
current

# Fingerprints follow contents, not timestamps, for every non-Go build input too.
for path in netbridge/go.mod netbridge/go.sum scripts/build-netbridge.sh scripts/verify-netbridge.sh; do
  cp -p "$FIXTURE/$path" "$FIXTURE/input.saved"
  printf '\n# changed\n' >> "$FIXTURE/$path"
  touch -r "$FIXTURE/input.saved" "$FIXTURE/$path"
  reject "$path content with preserved mtime"
  cp -p "$FIXTURE/input.saved" "$FIXTURE/$path"
done
cp -p "$FIXTURE/netbridge/bridge.go" "$FIXTURE/input.saved"
printf 'package fixture\n// production two\n' > "$FIXTURE/netbridge/bridge.go"
touch -r "$FIXTURE/input.saved" "$FIXTURE/netbridge/bridge.go"
reject 'Go source content with preserved mtime'

if TEST_BIND_MODE=fail bash "$BUILD" > "$FIXTURE/build.log" 2>&1; then fail 'failed bind succeeded'; fi
cmp -s "$AAR" "$FIXTURE/previous.aar" || fail 'failed build replaced or deleted the previous AAR'
printf 'PASS: failed build preserves previous AAR\n'

TEST_BIND_MODE=interrupt bash "$BUILD" > "$FIXTURE/build.log" 2>&1 &
pid=$!
for ((i=0; i<200; i++)); do
  [ -e "$TEST_BIND_READY" ] && break
  sleep 0.01
done
[ -e "$TEST_BIND_READY" ] || fail 'interrupted build never reached its partial output'
kill -TERM "$pid"
: > "$TEST_BIND_CONTINUE"
if wait "$pid"; then fail 'interrupted build succeeded'; fi
cmp -s "$AAR" "$FIXTURE/previous.aar" || fail 'interrupted build replaced or deleted the previous AAR'
printf 'PASS: interrupted build preserves previous AAR\n'

if TEST_BIND_MODE=source-change bash "$BUILD" > "$FIXTURE/build.log" 2>&1; then fail 'input changes during bind were published'; fi
cmp -s "$AAR" "$FIXTURE/previous.aar" || fail 'racing source edit replaced the previous AAR'
printf 'PASS: source change during bind prevents publication\n'

bash "$BUILD"
[ -L "$AAR" ] || fail 'new generation was not atomically linked'
current
before="$(wc -l < "$TEST_BIND_LOG")"
TEST_BIND_MODE=fail bash "$BUILD"
[ "$(wc -l < "$TEST_BIND_LOG")" = "$before" ] || fail 'verified generation was rebuilt'
printf 'PASS: rebuilt AAR/stamp generation is reusable\n'

# Each published link resolves to an adjacent, complete stamp. Partial output
# generations were discarded by EXIT/TERM cleanup and can never be selected.
resolved="$(readlink -f "$AAR")"
[ -s "$resolved.fingerprint" ] || fail 'published generation lacks its stamp'
[ "$(find "$FIXTURE/app/libs/netbridge-artifacts" -name '*.aar' | wc -l)" -eq 1 ] || fail 'partial build generation leaked'
printf 'PASS: all isolated freshness and publication regressions\n'
