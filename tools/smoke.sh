#!/usr/bin/env bash
# A fake cmake proves the contract: -S/-B/-D, the target, the parallel flag, and the failure path.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"; mkdir -p "$work/bin"
cat >"$work/bin/cmake" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SMOKE_ARGV"
case "$1" in
  -S)
    # configure: create the build dir the way cmake would
    while [ $# -gt 0 ]; do [ "$1" = "-B" ] && mkdir -p "$2"; shift; done
    echo "-- Configuring done"
    exit 0 ;;
  --build)
    echo "-- Build files have been written"
    echo "FAILBUILD" | grep -q . && [ -n "${SMOKE_FAIL:-}" ] && { echo "error: build failed"; exit 2; }
    printf '#!/bin/sh\necho hi\n' > "$2/app" && chmod +x "$2/app"
    echo "-- Build finished"
    exit 0 ;;
esac
exit 0
SH
chmod +x "$work/bin/cmake"
export SMOKE_ARGV="$work/argv.txt"

dir="$work/build"; mkdir -p "$dir"
printf '{"buildType": "Debug", "target": "app", "jobs": "4", "clean": "true"}' >"$dir/input.json"
PATH="$work/bin:$PATH" run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" buildType)" = "Debug" ] || fail "build type: $(cat "$dir/output.json")"
grep -q -- "-DCMAKE_BUILD_TYPE=Debug" "$work/argv.txt" || fail "build type not passed"
grep -q -- "--target app" "$work/argv.txt" || fail "target not passed"
grep -q -- "--parallel 4" "$work/argv.txt" || fail "parallel not passed"
[ -x "$dir/build/app" ] || fail "the fake build produced no binary"

# a failing build is a third-party error with the tail of the output
dir="$work/fail"; mkdir -p "$dir"
printf '{}' >"$dir/input.json"
PATH="$work/bin:$PATH" SMOKE_FAIL=1 run "$dir" >/dev/null 2>&1 && fail "a failing build must fail the atom"
grep -q "cmake build failed" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

# no cmake on the agent is an environment error, not a user error
dir="$work/nocmake"; mkdir -p "$dir"
mkdir -p "$work/empty"; printf '{}' >"$dir/input.json"
PATH="$work/empty:/usr/bin:/bin" run "$dir" >/dev/null 2>&1 && fail "missing cmake must fail"
grep -q "cmake is not on the PATH" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"
grep -q '"errorType":5' "$dir/output.json" || fail "ENVIRONMENT(5) expected: $(cat "$dir/output.json")"

echo "smoke ok: configure args, target, parallel, failing build, missing cmake"
