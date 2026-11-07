package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cmake atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made. Two halves, as before: a fake {@code cmake}/{@code
 * ctest} proves the argument shape and the failure paths without a toolchain, and a real cmake plus a real C compiler
 * prove the install half against a project that actually builds.
 */
class CmakeBuildIT {

    /** Records its argv, then does what cmake would do: create the build dir, produce a binary, fail on demand. */
    private static final String CMAKE = """
            #!/bin/sh
            printf '%s\\n' "$*" >> "$SMOKE_ARGV"
            case "$1" in
              -S)
                while [ $# -gt 0 ]; do [ "$1" = "-B" ] && mkdir -p "$2"; shift; done
                echo "-- Configuring done"
                exit 0 ;;
              --build)
                if [ -n "$SMOKE_FAIL" ]; then
                  echo "error: build failed"
                  exit 2
                fi
                printf '#!/bin/sh\\necho hi\\n' > "$2/app" && chmod +x "$2/app"
                echo "-- Build files have been written"
                echo "-- Build finished"
                exit 0 ;;
            esac
            exit 0
            """;

    /** Writes the JUnit report where ctest would, and prints the summary the atom parses. */
    private static final String CTEST = """
            #!/bin/sh
            printf 'ctest %s\\n' "$*" >> "$SMOKE_ARGV"
            testdir=""; junit=""
            while [ $# -gt 0 ]; do
              case "$1" in
                --test-dir) testdir="$2"; shift 2 ;;
                --output-junit) junit="$2"; shift 2 ;;
                --output-junit=*) junit="${1#--output-junit=}"; shift ;;
                *) shift ;;
              esac
            done
            if [ -n "$junit" ]; then
              mkdir -p "$(dirname "$testdir/$junit")"
              printf '<testsuites><testsuite name="ct"><testcase name="one"/></testsuite></testsuites>\\n' > "$testdir/$junit"
            fi
            if [ -n "$SMOKE_TESTFAIL" ]; then
              echo "33% tests passed, 2 tests failed out of 3"
              exit 8
            fi
            echo "100% tests passed, 0 tests failed out of 3"
            """;

    private Atom atom;
    private Path fakeBin;
    private Path argv;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        fakeBin = Files.createDirectories(Path.of(System.getProperty("atom.scratch")).resolve("fake-cmake/bin"));
        script(fakeBin.resolve("cmake"), CMAKE);
        script(fakeBin.resolve("ctest"), CTEST);
        argv = fakeBin.resolve("argv.txt");
        Files.deleteIfExists(argv);
    }

    @Test
    @DisplayName("build type, target and the parallel flag reach cmake, and the build produced a binary")
    void passesTheArgumentsThrough() throws Exception {
        AtomResult run = fake()
                .input("{\"buildType\": \"Debug\", \"target\": \"app\", \"jobs\": \"4\", \"clean\": \"true\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("Debug", run.field("buildType"));
        String calls = Files.readString(argv, StandardCharsets.UTF_8);
        assertTrue(calls.contains("-DCMAKE_BUILD_TYPE=Debug"), "build type not passed:\n" + calls);
        assertTrue(calls.contains("--target app"), "target not passed:\n" + calls);
        assertTrue(calls.contains("--parallel 4"), "parallel not passed:\n" + calls);
        assertTrue(Files.isExecutable(run.work().resolve("build/app")), "the fake build produced no binary");
    }

    @Test
    @DisplayName("a failing build is a third-party error with the tail of the output")
    void failsWhenTheBuildFails() throws Exception {
        AtomResult run = fake().env("SMOKE_FAIL", "1").input("{}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("cmake build failed"), "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("no cmake on the agent is an environment error, not a user error")
    void failsWithoutCmake() throws Exception {
        Path empty = Files.createDirectories(Path.of(System.getProperty("atom.scratch")).resolve("empty-bin"));
        AtomResult run = atom.input("{}").env("PATH", empty + ":/usr/bin:/bin").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("cmake is not on the PATH"),
                "message: " + run.file("output.json"));
        assertTrue(run.file("output.json").contains("\"errorType\":5"),
                "ENVIRONMENT(5) expected: " + run.file("output.json"));
    }

    @Test
    @DisplayName("ctest runs when asked, its summary is parsed, and the report lands where ctest writes it")
    void runsTheTestsAndReadsTheSummary() throws Exception {
        AtomResult run = fake()
                .input("{\"runTests\": \"true\", \"testArgs\": \"--output-junit reports/ctest.xml\"}")
                .run();

        run.assertOk();
        assertEquals("3", run.field("testsPassed"), "testsPassed: " + run.file("output.json"));
        assertEquals("0", run.field("testsFailed"));
        assertTrue(run.exists("build/reports/ctest.xml"), "no junit report where ctest writes it");
        assertTrue(Files.readString(argv, StandardCharsets.UTF_8).contains("reports/ctest.xml"),
                "test args not passed");
    }

    @Test
    @DisplayName("the declared outputJunit input actually reaches ctest")
    void passesTheDeclaredJunitPath() throws Exception {
        AtomResult run = fake()
                .input("{\"runTests\": \"true\", \"outputJunit\": \"reports/declared.xml\"}")
                .run();

        run.assertOk();
        assertTrue(run.exists("build/reports/declared.xml"), "outputJunit was declared but no report was written");
        assertTrue(Files.readString(argv, StandardCharsets.UTF_8).contains("--output-junit=reports/declared.xml"),
                "outputJunit never reached ctest");
    }

    @Test
    @DisplayName("a failing test fails the step unless told otherwise")
    void failsWhenATestFails() throws Exception {
        AtomResult run = fake().env("SMOKE_TESTFAIL", "1").input("{\"runTests\": \"true\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("2 failing test"), "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a real cmake installs into the prefix, honours -C preload and wipes the install dir first")
    void installsWithRealCmake() throws Exception {
        assumeRealToolchain();
        AtomResult run = atom.file("src/CMakeLists.txt", """
                        cmake_minimum_required(VERSION 3.13)
                        project(hello C)
                        add_executable(hello main.c)
                        install(TARGETS hello DESTINATION bin)
                        """)
                .file("src/main.c", "int main(void){ return 0; }\n")
                .file("preload.cmake", "set(ATOM_SMOKE_MARK \"seen\" CACHE STRING \"preload\")\n")
                .file("stage/stale.txt", "stale\n")
                .input("{\"source\":\"src\",\"buildDir\":\"build\",\"buildType\":\"Debug\","
                        + "\"preloadScript\":\"preload.cmake\",\"installDir\":\"stage\",\"cleanInstallDir\":\"true\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertTrue(Files.isExecutable(run.work().resolve("stage/bin/hello")), "nothing installed into the prefix");

        Path cache = run.work().resolve("build/CMakeCache.txt");
        assertTrue(Files.isRegularFile(cache), "no CMakeCache.txt");
        String text = Files.readString(cache, StandardCharsets.UTF_8);
        assertTrue(text.contains("ATOM_SMOKE_MARK:STRING=seen"), "the preload script never ran:\n" + text);
        assertTrue(text.lines().anyMatch(line -> line.matches("^CMAKE_INSTALL_PREFIX:PATH=.*/stage$")),
                "install prefix not passed:\n" + text);
        assertFalse(run.exists("stage/stale.txt"), "cleanInstallDir did not wipe the prefix");
        assertTrue(Integer.parseInt(run.field("installedFiles")) >= 1,
                "installedFiles: " + run.field("installedFiles"));
    }

    @Test
    @DisplayName("a project with no install rule installs nothing, which is a failure and not a quiet success")
    void failsWhenNothingIsInstalled() throws Exception {
        assumeRealToolchain();
        AtomResult run = atom.file("src/CMakeLists.txt", "cmake_minimum_required(VERSION 3.13)\nproject(bare C)\n")
                .input("{\"source\":\"src\",\"buildDir\":\"build\",\"installDir\":\"stage\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("wrote no file"),
                "no-install message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("an install command of your own runs in the build directory and its exit code decides")
    void failsWhenTheInstallCommandFails() throws Exception {
        assumeRealToolchain();
        AtomResult run = atom.file("src/CMakeLists.txt", """
                        cmake_minimum_required(VERSION 3.13)
                        project(hello C)
                        add_executable(hello main.c)
                        """)
                .file("src/main.c", "int main(void){ return 0; }\n")
                .input("{\"source\":\"src\",\"buildDir\":\"build\",\"installDir\":\"stage\","
                        + "\"installCommand\":\"false\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("cmake install failed"),
                "install failure message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a missing source directory fails before anything is built")
    void failsOnAMissingSource() throws Exception {
        assumeRealToolchain();
        AtomResult run = atom.input("{\"source\":\"missing\",\"buildDir\":\"build\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("source directory does not exist"),
                "source message: " + run.file("output.json"));
        assertFalse(run.exists("build"), "the build directory was created for a source that does not exist");
    }

    @Test
    @DisplayName("a missing preload script is refused before the configure step")
    void failsOnAMissingPreload() throws Exception {
        assumeRealToolchain();
        AtomResult run = atom.file("src/CMakeLists.txt", "cmake_minimum_required(VERSION 3.13)\nproject(bare C)\n")
                .input("{\"source\":\"src\",\"preloadScript\":\"nope.cmake\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("preload script does not exist"),
                "preload message: " + run.file("output.json"));
    }

    /** The fake half: the agent's PATH starts with the fake cmake, as the smoke script arranged it. */
    private Atom fake() {
        return atom.env("PATH", fakeBin + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"))
                .env("SMOKE_ARGV", argv.toString());
    }

    /** The real half needs a cmake and a C compiler; the smoke script skipped it when either was missing. */
    private static void assumeRealToolchain() {
        Assumptions.assumeTrue(executableOnPath("cmake") != null, "no real cmake on this machine");
        Assumptions.assumeTrue(executableOnPath("cc") != null || executableOnPath("gcc") != null,
                "no C compiler on this machine");
    }

    private static Path executableOnPath(String name) {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void script(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        path.toFile().setExecutable(true);
        assertTrue(Files.isExecutable(path), "could not make " + path + " executable");
    }
}
