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
cat >"$work/bin/ctest" <<'SH'
#!/usr/bin/env bash
printf 'ctest %s\n' "$*" >> "$SMOKE_ARGV"
testdir=""; junit=""
while [ $# -gt 0 ]; do
  case "$1" in
    --test-dir) testdir="$2"; shift 2 ;;
    --output-junit) junit="$2"; shift 2 ;;
    *) shift ;;
  esac
done
if [ -n "$junit" ]; then
  mkdir -p "$(dirname "$testdir/$junit")"
  printf '<testsuites><testsuite name="ct"><testcase name="one"/></testsuite></testsuites>\n' >"$testdir/$junit"
fi
if [ -n "${SMOKE_TESTFAIL:-}" ]; then
  echo "33% tests passed, 2 tests failed out of 3"; exit 8
fi
echo "100% tests passed, 0 tests failed out of 3"
SH
chmod +x "$work/bin/ctest"
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

# ctest runs when asked, the summary is parsed, and the report lands where ctest writes it
dir="$work/tests"; mkdir -p "$dir"
printf '{"runTests": "true", "testArgs": "--output-junit reports/ctest.xml"}' >"$dir/input.json"
PATH="$work/bin:$PATH" run "$dir" >/dev/null 2>&1 || fail "run with tests failed: $(cat "$dir/output.json")"
[ "$(output "$dir" testsPassed)" = "3" ] || fail "testsPassed: $(cat "$dir/output.json")"
[ "$(output "$dir" testsFailed)" = "0" ] || fail "testsFailed: $(cat "$dir/output.json")"
[ -f "$dir/build/reports/ctest.xml" ] || fail "no junit report where ctest writes it"
grep -q "reports/ctest.xml" "$work/argv.txt" || fail "test args not passed"

# a failing test fails the step unless told otherwise
dir="$work/testfail"; mkdir -p "$dir"
printf '{"runTests": "true"}' >"$dir/input.json"
PATH="$work/bin:$PATH" SMOKE_TESTFAIL=1 run "$dir" >/dev/null 2>&1 && fail "a failing test must fail the atom"
grep -q "2 failing test" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

echo "smoke ok: configure args, target, parallel, ctest summary, failing test, failing build, missing cmake"

# --- the install half, against a real cmake and a real compiler ---
real_cmake="$(command -v cmake || true)"
real_cc="$(command -v cc || command -v gcc || true)"
if [ -n "$real_cmake" ] && [ -n "$real_cc" ]; then
  dir="$work/real"; mkdir -p "$dir/src"
  printf 'cmake_minimum_required(VERSION 3.13)\nproject(hello C)\nadd_executable(hello main.c)\ninstall(TARGETS hello DESTINATION bin)\n' >"$dir/src/CMakeLists.txt"
  printf 'int main(void){ return 0; }\n' >"$dir/src/main.c"
  printf 'set(ATOM_SMOKE_MARK "seen" CACHE STRING "preload")\n' >"$dir/preload.cmake"
  mkdir -p "$dir/stage"; printf 'stale\n' >"$dir/stage/stale.txt"
  printf '%s' '{"source":"src","buildDir":"build","buildType":"Debug","preloadScript":"preload.cmake","installDir":"stage","cleanInstallDir":"true"}' >"$dir/input.json"
  run "$dir" >"$dir/log.txt" 2>&1 || fail "real cmake run failed: $(tail -5 "$dir/log.txt")"
  [ "$(status "$dir")" = "success" ] || fail "real cmake: not a success: $(cat "$dir/output.json")"
  [ -x "$dir/stage/bin/hello" ] || fail "real cmake: nothing installed into the prefix"
  grep -q 'ATOM_SMOKE_MARK:STRING=seen' "$dir/build/CMakeCache.txt" || fail "real cmake: the preload script never ran"
  grep -Eq '^CMAKE_INSTALL_PREFIX:PATH=.*/stage$' "$dir/build/CMakeCache.txt" || fail "real cmake: install prefix not passed"
  [ ! -e "$dir/stage/stale.txt" ] || fail "real cmake: cleanInstallDir did not wipe the prefix"
  [ "$(output "$dir" installedFiles)" -ge 1 ] || fail "real cmake: installedFiles: $(cat "$dir/output.json")"
  # a project with no install rule installs nothing: that is a failure, not a quiet success
  dir="$work/noinstall"; mkdir -p "$dir/src"
  printf 'cmake_minimum_required(VERSION 3.13)\nproject(bare C)\n' >"$dir/src/CMakeLists.txt"
  printf '%s' '{"source":"src","buildDir":"build","installDir":"stage"}' >"$dir/input.json"
  run "$dir" >/dev/null 2>&1 && fail "an install that wrote nothing must fail the atom"
  grep -q "wrote no file" "$dir/output.json" || fail "no-install message: $(cat "$dir/output.json")"
  # an install command of your own runs in the build directory and its exit code decides
  dir="$work/installfail"; mkdir -p "$dir/src"
  printf 'cmake_minimum_required(VERSION 3.13)\nproject(hello C)\nadd_executable(hello main.c)\n' >"$dir/src/CMakeLists.txt"
  printf 'int main(void){ return 0; }\n' >"$dir/src/main.c"
  printf '%s' '{"source":"src","buildDir":"build","installDir":"stage","installCommand":"false"}' >"$dir/input.json"
  run "$dir" >/dev/null 2>&1 && fail "a failing install command must fail the atom"
  grep -q "cmake install failed" "$dir/output.json" || fail "install failure message: $(cat "$dir/output.json")"
  # a source directory or a preload script that is not there fails before anything is built
  dir="$work/nosrc"; mkdir -p "$dir"
  printf '%s' '{"source":"missing","buildDir":"build"}' >"$dir/input.json"
  run "$dir" >/dev/null 2>&1 && fail "a missing source directory must fail the atom"
  grep -q "source directory does not exist" "$dir/output.json" || fail "source message: $(cat "$dir/output.json")"
  [ ! -d "$dir/build" ] || fail "the build directory was created for a source that does not exist"
  dir="$work/nopreload"; mkdir -p "$dir/src"
  printf 'cmake_minimum_required(VERSION 3.13)\nproject(bare C)\n' >"$dir/src/CMakeLists.txt"
  printf '%s' '{"source":"src","preloadScript":"nope.cmake"}' >"$dir/input.json"
  run "$dir" >/dev/null 2>&1 && fail "a missing preload script must fail the atom"
  grep -q "preload script does not exist" "$dir/output.json" || fail "preload message: $(cat "$dir/output.json")"
  echo "real cmake: install prefix, -C preload, clean install dir, empty install, failing install, missing source/preload"
else
  echo "real cmake: skipped (no cmake or no C compiler on this machine)"
fi

echo "smoke ok: configure args, target, parallel, ctest summary, failing test, failing build, missing cmake, install"
