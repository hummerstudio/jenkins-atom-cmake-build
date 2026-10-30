package com.jenkinschina.atom.cmakebuild;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * cmake as an atom: configure into a build directory, then build a target.
 *
 * <p>Same shape as the maven and gradle atoms: a tool the agent has, wrapped so a job does not have to spell out the
 * command line. The configure step is separate because its output (CMakeCache, compiler detection) is what people
 * actually debug.
 */
public class CmakeBuildAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String source = context.getParam("source", ".");
        String buildDir = context.getParam("buildDir", "build");
        String generator = context.getParam("generator", "");
        String buildType = context.getParam("buildType", "Release");
        String target = context.getParam("target", "");
        String configureArgs = context.getParam("configureArgs", "");
        String buildArgs = context.getParam("buildArgs", "");
        int jobs = context.getIntParam("jobs", 0);
        boolean runTests = context.getBooleanParam("runTests", false);
        String testArgs = context.getParam("testArgs", "");
        boolean failOnTestFailure = context.getBooleanParam("failOnTestFailure", true);
        boolean clean = context.getBooleanParam("clean", false);

        String cmake = resolve("cmake");
        if (cmake == null) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40001,
                    "cmake is not on the PATH of this agent; install it or set the source/toolchain on the node");
        }

        Path buildPath = workspace.resolve(buildDir);
        if (clean) {
            deleteTree(buildPath);
        }
        Files.createDirectories(buildPath);

        List<String> configure = new ArrayList<>(List.of(cmake, "-S", workspace.resolve(source).toString(),
                "-B", buildPath.toString(), "-DCMAKE_BUILD_TYPE=" + buildType));
        if (!generator.isBlank()) {
            configure.add("-G");
            configure.add(generator);
        }
        configure.addAll(split(configureArgs));

        context.log("[cmake] " + String.join(" ", configure));
        Result configured = exec(workspace, context, configure);
        if (!configured.ok()) {
            return AtomResult.error(ErrorType.THIRD_PART, 40002,
                    "cmake configure failed (" + configured.code() + "): " + configured.tail());
        }

        List<String> build = new ArrayList<>(List.of(cmake, "--build", buildPath.toString()));
        if (!target.isBlank()) {
            build.add("--target");
            build.add(target);
        }
        if (jobs > 0) {
            build.add("--parallel");
            build.add(String.valueOf(jobs));
        }
        build.addAll(split(buildArgs));

        context.log("[cmake] " + String.join(" ", build));
        Result built = exec(workspace, context, build);
        if (!built.ok()) {
            return AtomResult.error(ErrorType.THIRD_PART, 40003,
                    "cmake build failed (" + built.code() + "): " + built.tail());
        }

        int testsPassed = 0;
        int testsFailed = 0;
        if (runTests) {
            // ctest lives next to cmake: same prefix, one level up from bin/
            String ctest = cmake.replaceAll("/cmake$", "/ctest");
            // a report path usually points into a directory that does not exist yet; ctest will not create it
            for (String arg : split(testArgs)) {
                if (arg.startsWith("--output-junit") || arg.startsWith("--output-junit=")) {
                    String junitPath = arg.contains("=") ? arg.substring(arg.indexOf('=') + 1)
                            : context.getParam("outputJunit", "");
                    if (!junitPath.isBlank()) {
                        Path parent = workspace.resolve(junitPath).normalize().getParent();
                        if (parent != null && parent.startsWith(workspace)) {
                            Files.createDirectories(parent);
                        }
                    }
                }
            }
            List<String> test = new ArrayList<>(List.of(ctest, "--test-dir", buildPath.toString(),
                    "--output-on-failure"));
            test.addAll(split(testArgs));
            context.log("[cmake] " + String.join(" ", test));
            Result tested = exec(workspace, context, test);
            // ctest says "100% tests passed, 0 tests failed out of 1": the total is the reliable number
            int total = count(tested.output(), "out of (\\d+)");
            testsFailed = count(tested.output(), "(\\d+) tests? failed");
            testsPassed = Math.max(0, total - testsFailed);
            context.log("[cmake] " + testsPassed + " passed, " + testsFailed + " failed");
            if (!tested.ok() && failOnTestFailure) {
                return AtomResult.error(ErrorType.THIRD_PART, 40004,
                        "ctest reported " + testsFailed + " failing test(s): " + tested.tail());
            }
        }

        long artifacts = 0;
        try (var walk = Files.walk(buildPath, 6)) {
            artifacts = walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".so") || name.endsWith(".dylib") || name.endsWith(".a")
                                || name.endsWith(".dll") || name.endsWith(".exe") || !name.contains(".");
                    })
                    .filter(Files::isExecutable)
                    .count();
        } catch (Exception ignored) {
            // counting is informational
        }

        return AtomResult.success()
                .data("testsPassed", String.valueOf(testsPassed))
                .data("testsFailed", String.valueOf(testsFailed))
                .data("buildDir", buildPath.toString())
                .data("buildType", buildType)
                .data("binaries", String.valueOf(artifacts))
                .artifact("build", buildDir);
    }

    private static int count(String text, String pattern) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(text);
        int total = 0;
        while (matcher.find()) {
            total += Integer.parseInt(matcher.group(1));
        }
        return total;
    }

    private static void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // leave it: a locked file is not worth failing the build over here
                }
            });
        }
    }

    private static String resolve(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static Result exec(Path workspace, AtomContext context, List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder all = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    context.log("[cmake] " + line);
                    all.append(line).append('\n');
                    if (all.length() > 200_000) {
                        all.delete(0, 100_000);
                    }
                }
            }
            return new Result(process.waitFor(), all.toString());
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e));
        }
    }

    private static List<String> split(String spec) {
        List<String> parts = new ArrayList<>();
        if (spec == null) {
            return parts;
        }
        for (String part : spec.split("\\s+")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private record Result(int code, String output) {
        boolean ok() {
            return code == 0;
        }

        String tail() {
            String[] lines = output.strip().split("\n");
            StringBuilder out = new StringBuilder();
            for (int i = Math.max(0, lines.length - 8); i < lines.length; i++) {
                out.append(lines[i]).append('\n');
            }
            return out.toString().strip();
        }
    }
}
