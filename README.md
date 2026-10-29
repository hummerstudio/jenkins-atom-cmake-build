# jenkins-atom-cmake-build

CMake build — port of **cmake（无官方插件，常见需求）** (—) as an atom.

Configure and build are two steps on purpose: the configure output (CMakeCache, compiler detection) is what
people actually debug.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
