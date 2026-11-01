# jenkins-atom-cmake-build

CMake build — an atom for a tool the agent has, plus the install half of the Jenkins **cmakebuilder** plugin.

Configure and build are two steps on purpose: the configure output (CMakeCache, compiler detection) is what
people actually debug.

## What it does

`cmake -S <source> -B <buildDir>`, then `cmake --build <buildDir>` (with `--target` and `--parallel` when asked),
then `ctest` if `runTests`, then an install when `installDir` is set.

Arguments borrowed from the plugin, in the plugin's order: `-C <preloadScript>`, `-G <generator>`,
`-DCMAKE_INSTALL_PREFIX=<installDir>`, `-DCMAKE_BUILD_TYPE=<buildType>`, then `configureArgs`. `cleanBuild` and
`cleanInstallDir` wipe their directory before anything runs, as the plugin's checkboxes say.

The install step runs `installCommand` **with the build directory as its working directory** (the plugin's
`CmakeLauncher` does the same), or `cmake --install <buildDir>` when no command is given — that form works with
every generator, `make install` only with makefiles.

## Deliberate differences from the plugin

- The plugin runs `cmake <args> "<source>"` from inside the build directory; this atom uses `-S`/`-B` (CMake 3.13+).
  Same configuration, no `cd`.
- The plugin does not check its install result: `launchInstall` returns true when either the install directory or
  the install command is empty, and it never looks at what was written. This atom counts the files under the
  prefix and fails with `40006` when an install wrote nothing — an install that installs nothing is not a success.
- The plugin ignores a missing source directory until cmake complains, and it has already wiped the build
  directory by then. This atom checks the source (and the preload script) first and fails with their absolute path
  (`40010`, `40011`) before touching the workspace.
- `installDir`, `installCommand` and `preloadScript` are resolved against the workspace and handed to cmake as
  absolute paths; the plugin passes whatever the job typed.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```

The smoke test has two halves. A fake `cmake`/`ctest` proves the argument shape (`-DCMAKE_BUILD_TYPE`, `--target`,
`--parallel`, the ctest report path), the failing-build and missing-tool paths. A **real** cmake and C compiler,
when the machine has them, prove the install half: `-C` lands in CMakeCache, the prefix is passed, the binary
appears under `<installDir>/bin`, `cleanInstallDir` wipes a stale file, an install that writes nothing fails, a
failing install command fails, and a missing source or preload script fails before the build directory exists.
