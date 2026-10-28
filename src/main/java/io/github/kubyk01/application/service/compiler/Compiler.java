package io.github.kubyk01.application.service.compiler;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.TargetPlatform;
import io.github.kubyk01.port.secondary.CompilerPort;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

@Slf4j
public class Compiler implements CompilerPort {

    private static final String RUNTIME_SOURCE_RESOURCE_PATH = "jnative_runtime.c";
    private static final String RUNTIME_HEADER_RESOURCE_PATH = "jnative_runtime.h";
    private static final String JNI_BRIDGE_RESOURCE_PATH = "jnative_jni_bridge.c";

    private static final String PORTABILITY_DIR = "jnative_portability/";
    private static final String PLATFORM_HEADER_RESOURCE_PATH = PORTABILITY_DIR + "jnative_platform.h";
    private static final String PLATFORM_SOURCE_RESOURCE_PATH = PORTABILITY_DIR + "jnative_platform.c";
    private static final String DLFCN_HEADER_RESOURCE_PATH = PORTABILITY_DIR + "jnative_dlfcn_win32.h";
    private static final String PORTABILITY_HEADER_RESOURCE_PATH =
            PORTABILITY_DIR + "jnative_portability.h";
    private static final String PORTABILITY_POSIX_SOURCE_RESOURCE_PATH =
            PORTABILITY_DIR + "jnative_portability_posix.c";
    private static final String PORTABILITY_WINDOWS_SOURCE_RESOURCE_PATH =
            PORTABILITY_DIR + "jnative_portability_windows.c";
    private static final String NATIVE_BASE_PATH = "jnative/";
    private static final String BUILD_ROOT_NAME = "jnative_build_";

    private static final int MIN_OPT_LEVEL = 0;
    private static final int MAX_OPT_LEVEL = 3;

    private static final String MINGW_SYSROOT_ENV = "MINGW_SYSROOT";

    private static final String[] MINGW_ROOT_CANDIDATES = {
            "/usr/x86_64-w64-mingw32",
            "/usr/x86_64-w64-mingw32/sys-root/mingw",
            "/usr/aarch64-w64-mingw32",
            "/usr/aarch64-w64-mingw32/sys-root/mingw",
            "/mingw64",
            "C:\\msys64\\mingw64",
            "C:\\mingw64",
    };

    private static final String[] GCC_RUNTIME_SEARCH_ROOTS = {
            "/usr/lib/gcc/x86_64-w64-mingw32",
            "/usr/lib/gcc/aarch64-w64-mingw32",
            "/usr/lib/gcc/i686-w64-mingw32",
            "/usr/x86_64-w64-mingw32/sys-root/mingw/lib/gcc/x86_64-w64-mingw32",
            "/usr/aarch64-w64-mingw32/sys-root/mingw/lib/gcc/aarch64-w64-mingw32",
            "/usr/i686-w64-mingw32/sys-root/mingw/lib/gcc/i686-w64-mingw32",
    };

    @Override
    public void compileAndLink(List<Path> llPaths,
                               Path exePath,
                               Set<String> usedClasses,
                               Module module,
                               DependencyResolver resolver,
                               int cores,
                               int optimizationLevel,
                               TargetPlatform targetPlatform)
            throws IOException, InterruptedException {

        final int effectiveCores = (cores <= 0) ? 1 : cores;
        final int effectiveOptLevel = clampOptLevel(optimizationLevel);
        final TargetPlatform target = (targetPlatform != null)
                ? targetPlatform
                : TargetPlatform.detectFromBuildHost();

        Path tempDir = Files.createTempDirectory(BUILD_ROOT_NAME);
        tempDir.toFile().deleteOnExit();

        try {
            String compiler = detectCompiler(target);

            String mingwSysroot = target.isWindows()
                    ? resolveWindowsSysroot(compiler)
                    : null;
            if (target.isWindows() && mingwSysroot == null) {
                System.err.println(
                        "WARNING: cross-compiling for Windows target '" + target.triple()
                                + "' without an explicit MinGW-w64 sysroot. If the build "
                                + "fails with \"'stdlib.h' file not found\", either install "
                                + "MinGW-w64 (e.g. `sudo apt install mingw-w64` on Debian/"
                                + "Ubuntu, or `dnf install mingw64-gcc` on Fedora) or set "
                                + "the " + MINGW_SYSROOT_ENV + " environment variable to the "
                                + "root of an existing MinGW-w64 tree. If you are using "
                                + "llvm-mingw, set " + MINGW_SYSROOT_ENV + "=NONE.");
            } else if (mingwSysroot != null) {
                System.out.println("MinGW-w64 sysroot: " + mingwSysroot);
            }

            // ------------------------------------------------------------------
            // 1. Extract runtime, headers, JNI bridge.
            // ------------------------------------------------------------------
            Path runtimeSource = extractResource(
                    RUNTIME_SOURCE_RESOURCE_PATH,
                    tempDir.resolve(RUNTIME_SOURCE_RESOURCE_PATH));
            extractResource(
                    RUNTIME_HEADER_RESOURCE_PATH,
                    tempDir.resolve(RUNTIME_HEADER_RESOURCE_PATH));
            Path jniBridgeSource = extractResource(
                    JNI_BRIDGE_RESOURCE_PATH,
                    tempDir.resolve(JNI_BRIDGE_RESOURCE_PATH));

            Path portabilityDir = tempDir.resolve("jnative_portability");
            Files.createDirectories(portabilityDir);

            extractResource(
                    PLATFORM_HEADER_RESOURCE_PATH,
                    portabilityDir.resolve("jnative_platform.h"));
            extractResource(
                    DLFCN_HEADER_RESOURCE_PATH,
                    portabilityDir.resolve("jnative_dlfcn_win32.h"));
            Path platformSource = extractResource(
                    PLATFORM_SOURCE_RESOURCE_PATH,
                    portabilityDir.resolve("jnative_platform.c"));
            extractResource(
                    PORTABILITY_HEADER_RESOURCE_PATH,
                    portabilityDir.resolve("jnative_portability.h"));
            Path portabilitySource;
            if (target.isWindows()) {
                portabilitySource = extractResource(
                        PORTABILITY_WINDOWS_SOURCE_RESOURCE_PATH,
                        portabilityDir.resolve("jnative_portability_windows.c"));
            } else {
                portabilitySource = extractResource(
                        PORTABILITY_POSIX_SOURCE_RESOURCE_PATH,
                        portabilityDir.resolve("jnative_portability_posix.c"));
            }

            // ------------------------------------------------------------------
            // 2. Extract per-class native sources.
            // ------------------------------------------------------------------
            Set<String> classesToCompile = new HashSet<>(usedClasses);
            classesToCompile.addAll(collectReferencedNativeClasses(module, resolver));
            List<Path> extraSources = extractSystemNativeSources(classesToCompile, tempDir);

            // ------------------------------------------------------------------
            // 3. Build the compile-task list.
            // ------------------------------------------------------------------
            List<CompileTask> tasks = new ArrayList<>();

            Path cDir = tempDir.resolve("c");
            Files.createDirectories(cDir);

            tasks.add(new CompileTask(runtimeSource, cDir.resolve("jnative_runtime.o"), false));
            tasks.add(new CompileTask(platformSource, cDir.resolve("jnative_platform.o"), false));
            tasks.add(new CompileTask(portabilitySource, cDir.resolve("jnative_portability.o"), false));
            tasks.add(new CompileTask(jniBridgeSource, cDir.resolve("jnative_jni_bridge.o"), false));

            for (Path src : extraSources) {
                Path rel = tempDir.relativize(src);
                Path objWithPath = cDir.resolve(
                        rel.toString().replaceAll("\\.c$", ".o"));
                Files.createDirectories(objWithPath.getParent());
                tasks.add(new CompileTask(src, objWithPath, false));
            }

            int irIndex = 0;
            for (Path ll : llPaths) {
                String base = sanitizeForObjName(ll.getFileName().toString());
                Path obj = tempDir.resolve("ir")
                        .resolve(String.format("%05d_%s.o", irIndex++, base));
                tasks.add(new CompileTask(ll, obj, true));
            }

            // ------------------------------------------------------------------
            // 4. Compile.
            // ------------------------------------------------------------------
            int threadCount = Math.min(effectiveCores, tasks.size());
            System.out.println("Compiling " + tasks.size() + " translation unit(s) using "
                    + threadCount + " thread(s) (target " + target.triple() + ")...");

            compileAll(compiler, tasks, tempDir, threadCount, effectiveOptLevel,
                    target, mingwSysroot);

            // ------------------------------------------------------------------
            // 5. Link.
            //
            // ThinLTO is an LLVM-only feature: its parallel-backend option
            // --thinlto-jobs is understood only by ld.lld. GNU ld (BFD and
            // gold) does not implement ThinLTO at all and rejects both
            // --threads and --thinlto-jobs. The linker must therefore be
            // chosen by LINKER FLAVOUR, not by target OS:
            //
            //   MSVC -> lld-link (or link.exe), accepts --threads
            //   GNU  -> ld.lld, accepts --thinlto-jobs
            //
            // x86_64-w64-windows-gnu is a Windows target built with the GNU
            // toolchain, so it falls into the GNU branch. Forcing the LLVM
            // linker here is the only portable fix.
            //
            // ld.lld, unlike the GCC driver, does not automatically add the
            // MinGW-w64 GCC runtime library directory to its search path.
            // The -lgcc / -lgcc_eh flags that Clang's driver passes for a
            // MinGW target therefore fail to resolve. The fix is to add
            // -L<gcc-runtime-dir> explicitly for every directory that
            // contains libgcc.a. See findGccRuntimeLibDirs below.
            // ------------------------------------------------------------------
            List<String> linkCmd = new ArrayList<>();
            linkCmd.add(compiler);

            if (!isMinGwWrapper(compiler)) {
                linkCmd.add("--target=" + target.triple());
            }

            final boolean msvcLinker = target.triple()
                    .toLowerCase(Locale.ROOT).contains("-msvc");

            linkCmd.add("-flto=thin");
            linkCmd.add("-O" + effectiveOptLevel);

            if (msvcLinker) {
                linkCmd.add("-Wl,--threads=" + effectiveCores);
            } else {
                if (!isAvailable("ld.lld")) {
                    throw new IllegalStateException(
                            "ThinLTO for target '" + target.triple() + "' requires the "
                                    + "LLVM linker 'ld.lld', but it was not found on PATH. "
                                    + "GNU ld does not support ThinLTO options "
                                    + "(both '--threads' and '--thinlto-jobs' are rejected). "
                                    + "Install the 'lld' package for your distribution "
                                    + "(e.g. `sudo apt install lld` on Debian/Ubuntu, "
                                    + "`sudo dnf install lld` on Fedora, "
                                    + "`sudo pacman -S lld` on Arch) and try again.");
                }

                List<Path> gccRuntimeDirs = findGccRuntimeLibDirs(target);
                for (Path dir : gccRuntimeDirs) {
                    linkCmd.add("-L" + dir);
                }
                Path ehFallbackDir = ensureLibgccEh(target, gccRuntimeDirs, tempDir);
                if (ehFallbackDir != null) {
                    linkCmd.add("-L" + ehFallbackDir);
                }

                linkCmd.add("-fuse-ld=lld");
                linkCmd.add("-Wl,--thinlto-jobs=" + effectiveCores);
            }

            appendWindowsSysrootToLinker(linkCmd, mingwSysroot);

            for (CompileTask task : tasks) {
                linkCmd.add(task.obj.toString());
            }
            linkCmd.add("-o");
            linkCmd.add(exePath.toString());

            appendLinkerLibraries(linkCmd, target);

            // ------------------------------------------------------------------
            // 6. Native library discovery and bundling.
            // ------------------------------------------------------------------
            List<Path> nativeLibs = collectNativeLibraries(module, resolver, target);
            Set<Path> nativeLibDirs = new LinkedHashSet<>();
            for (Path lib : nativeLibs) {
                if (target.isWindows()) {
                    linkCmd.add(lib.toString());
                } else {
                    linkCmd.add("-Wl,--no-as-needed");
                    linkCmd.add(lib.toString());
                }
                Path parent = lib.toAbsolutePath().getParent();
                if (parent != null) nativeLibDirs.add(parent);
            }

            if (!target.isWindows()) {
                linkCmd.add("-Wl,--disable-new-dtags");
                linkCmd.add("-Wl,-rpath,$ORIGIN");
                linkCmd.add("-Wl,-rpath," + new File(".").getAbsolutePath());
                for (Path dir : nativeLibDirs) {
                    linkCmd.add("-Wl,-rpath," + dir);
                }
            }

            System.out.println("Linking for target " + target.triple() + "...");
            ProcessBuilder linkBuilder = new ProcessBuilder(linkCmd);
            linkBuilder.inheritIO();
            int exit = linkBuilder.start().waitFor();
            if (exit != 0) {
                throw new RuntimeException("Linking failed with exit code " + exit
                        + " (target: " + target.triple() + ")");
            }

            bundleNativeLibraries(exePath, nativeLibs, nativeLibDirs, target);

        } finally {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
        }
    }

    // =====================================================================
    //  GCC runtime library discovery
    // =====================================================================

    private static List<Path> findGccRuntimeLibDirs(TargetPlatform target) {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();

        String arch = target.triple().split("-")[0];
        String gccName = arch + "-w64-mingw32-gcc";
        Path gccPath = queryGccForLibgccPath(gccName);
        if (gccPath != null) {
            dirs.add(gccPath);
        }

        String triplePrefix = arch + "-w64-mingw32";
        for (String root : GCC_RUNTIME_SEARCH_ROOTS) {
            Path rootPath = Paths.get(root);
            if (!Files.isDirectory(rootPath)) continue;

            try (var stream = Files.list(rootPath)) {
                stream.filter(Files::isDirectory)
                        .filter(d -> d.getFileName().toString()
                                .startsWith(triplePrefix))
                        .filter(d -> Files.exists(d.resolve("libgcc.a")))
                        .forEach(dirs::add);
            } catch (IOException ignored) { }
        }

        if (dirs.isEmpty()) {
            log.warn("Could not locate a MinGW-w64 GCC runtime library "
                            + "directory containing libgcc.a for target '{}'. "
                            + "If the link step later fails with "
                            + "\"lld: error: unable to find library -lgcc\", install "
                            + "the MinGW-w64 GCC cross-compiler "
                            + "(e.g. `sudo apt install gcc-mingw-w64-x86-64` on "
                            + "Debian/Ubuntu, `sudo dnf install mingw64-gcc` on Fedora).",
                    target.triple());
        } else {
            log.debug("GCC runtime library director(ies) for target {}: {}",
                    target.triple(), dirs);
        }

        return new ArrayList<>(dirs);
    }

    private static Path queryGccForLibgccPath(String gccName) {
        try {
            Process p = new ProcessBuilder(gccName, "-print-libgcc-file-name")
                    .redirectErrorStream(true).start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                output = reader.readLine();
            }
            p.waitFor();
            if (output == null || output.isBlank()) return null;
            Path libgcc = Paths.get(output.trim());
            if (!Files.exists(libgcc)) return null;
            return libgcc.getParent();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Path ensureLibgccEh(TargetPlatform target,
                                       List<Path> gccRuntimeDirs,
                                       Path tempDir) {
        Path firstLibgcc = null;
        for (Path dir : gccRuntimeDirs) {
            Path eh = dir.resolve("libgcc_eh.a");
            if (Files.isRegularFile(eh)) {
                return null;
            }
            if (firstLibgcc == null) {
                Path lg = dir.resolve("libgcc.a");
                if (Files.isRegularFile(lg)) firstLibgcc = lg;
            }
        }

        if (firstLibgcc == null) {
            return null;
        }

        Path shimDir = tempDir.resolve("libgcc-eh-shim");
        Path shim = shimDir.resolve("libgcc_eh.a");
        try {
            Files.createDirectories(shimDir);
            Files.deleteIfExists(shim);
            Files.createSymbolicLink(shim, firstLibgcc);
            log.info("Created libgcc_eh.a fallback symlink at {}", shim);
            return shimDir;
        } catch (IOException e) {
            try {
                Files.copy(firstLibgcc, shim);
                log.info("Copied libgcc.a to {} as a libgcc_eh.a fallback", shim);
                return shimDir;
            } catch (IOException copyError) {
                log.warn("Could not create libgcc_eh.a fallback at {}: {}",
                        shim, copyError.getMessage());
                return null;
            }
        }
    }

    // =====================================================================
    //  Compiler detection
    // =====================================================================

    private String detectCompiler(TargetPlatform target) {
        if (target.isWindows()) {
            String mingwClang = "x86_64-w64-mingw32-clang";
            if (isAvailable(mingwClang)) return mingwClang;

            if (isAvailable("clang")) {
                return "clang";
            }

            String mingwGcc = "x86_64-w64-mingw32-gcc";
            if (isAvailable(mingwGcc)) {
                throw new IllegalStateException(
                        "Windows target requires clang (for .ll files), but only "
                                + "GCC was found. Install clang or the llvm-mingw toolchain.");
            }

            throw new IllegalStateException(
                    "No compiler for Windows target. Install clang or llvm-mingw.");
        }

        if (isAvailable("clang")) return "clang";
        if (isAvailable("gcc")) return "gcc";
        throw new IllegalStateException(
                "Neither clang nor gcc is installed. At least one is required.");
    }

    private static boolean isMinGwWrapper(String compiler) {
        return compiler.startsWith("x86_64-w64-mingw32-")
                || compiler.startsWith("aarch64-w64-mingw32-")
                || compiler.startsWith("i686-w64-mingw32-");
    }

    private boolean isAvailable(String compiler) {
        try {
            Process p = new ProcessBuilder(compiler, "--version")
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // =====================================================================
    //  MinGW-w64 sysroot resolution
    // =====================================================================

    private static String resolveWindowsSysroot(String compiler) {
        String explicit = System.getenv(MINGW_SYSROOT_ENV);
        if (explicit != null && !explicit.isBlank()) {
            String trimmed = explicit.trim();
            if (trimmed.equalsIgnoreCase("NONE")
                    || trimmed.equalsIgnoreCase("SKIP")) {
                return null;
            }
            Path dir = Paths.get(trimmed);
            if (!Files.isDirectory(dir)) {
                throw new IllegalStateException(
                        "Environment variable " + MINGW_SYSROOT_ENV + " points to a "
                                + "directory that does not exist: " + trimmed + ". "
                                + "Set it to a MinGW-w64 installation root (a directory "
                                + "containing include/stdlib.h), or to NONE to disable "
                                + "sysroot injection.");
            }
            if (!Files.exists(dir.resolve("include").resolve("stdlib.h"))) {
                throw new IllegalStateException(
                        "Environment variable " + MINGW_SYSROOT_ENV + " points to '"
                                + trimmed + "', but that directory does not contain "
                                + "include/stdlib.h. Set it to the root of a MinGW-w64 "
                                + "tree, or to NONE to disable sysroot injection.");
            }
            return trimmed;
        }

        if (isMinGwWrapper(compiler)) {
            return null;
        }

        return findMinGWSysroot();
    }

    private static String findMinGWSysroot() {
        for (String candidate : MINGW_ROOT_CANDIDATES) {
            Path include = Paths.get(candidate, "include", "stdlib.h");
            if (Files.exists(include)) {
                return candidate;
            }
        }
        return null;
    }

    private static void appendWindowsSysrootToCompiler(List<String> command,
                                                       String sysroot,
                                                       boolean isIr) {
        if (sysroot == null) return;

        command.add("--sysroot=" + sysroot);

        if (isIr) return;

        Path debianInclude = Paths.get(sysroot, "include");
        if (Files.isDirectory(debianInclude)) {
            command.add("-I" + debianInclude);
        }

        Path archInclude = Paths.get(sysroot, "x86_64-w64-mingw32", "include");
        if (Files.isDirectory(archInclude)) {
            command.add("-I" + archInclude);
        }

        Path fedoraInclude = Paths.get(sysroot, "sys-root", "mingw", "include");
        if (Files.isDirectory(fedoraInclude)) {
            command.add("-I" + fedoraInclude);
        }
    }

    private static void appendWindowsSysrootToLinker(List<String> linkCmd,
                                                     String sysroot) {
        if (sysroot == null) return;
        linkCmd.add("--sysroot=" + sysroot);
        linkCmd.add("-L" + sysroot + "/lib");
        linkCmd.add("-L" + sysroot + "/x86_64-w64-mingw32/lib");
        linkCmd.add("-L" + sysroot + "/sys-root/mingw/lib");
    }

    // =====================================================================
    //  Optimization level
    // =====================================================================

    private static int clampOptLevel(int level) {
        return Math.max(MIN_OPT_LEVEL, Math.min(level, MAX_OPT_LEVEL));
    }

    // =====================================================================
    //  Per-unit compile
    // =====================================================================

    private static final class CompileTask {
        private final Path src;
        private final Path obj;
        private final boolean isIr;
        CompileTask(Path src, Path obj, boolean isIr) {
            this.src = src; this.obj = obj; this.isIr = isIr;
        }
    }

    private void compileAll(String compiler, List<CompileTask> tasks,
                            Path includeDir, int threadCount,
                            int optimizationLevel, TargetPlatform target,
                            String mingwSysroot)
            throws IOException, InterruptedException {

        if (threadCount <= 1 || tasks.size() <= 1) {
            for (CompileTask task : tasks) {
                compileOne(compiler, task, includeDir, optimizationLevel,
                        target, mingwSysroot);
            }
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "jnative-cc");
            t.setDaemon(true);
            return t;
        });

        final AtomicInteger nextTask = new AtomicInteger(0);
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                int idx;
                while ((idx = nextTask.getAndIncrement()) < tasks.size()) {
                    try {
                        compileOne(compiler, tasks.get(idx), includeDir,
                                optimizationLevel, target, mingwSysroot);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }
            }));
        }

        pool.shutdown();
        try {
            for (Future<?> f : futures) {
                try { f.get(); }
                catch (ExecutionException ee) {
                    failures.add(ee.getCause() != null ? ee.getCause() : ee);
                }
            }
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw ie;
        }

        if (!failures.isEmpty()) {
            Throwable first = failures.getFirst();
            throw new RuntimeException(
                    "Compilation failed for " + failures.size()
                            + " translation unit(s); first failure: " + first.getMessage(),
                    first);
        }
    }

    private void compileOne(String compiler, CompileTask task, Path includeDir,
                            int optimizationLevel, TargetPlatform target,
                            String mingwSysroot)
            throws IOException, InterruptedException {

        Path parent = task.obj.getParent();
        if (parent != null) Files.createDirectories(parent);

        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.add("-c");
        command.add("-x");
        command.add(task.isIr ? "ir" : "c");
        command.add("-O" + optimizationLevel);
        command.add(optimizationLevel == 0 ? "-fno-lto" : "-flto=thin");
        command.add("-g");
        command.add("-fno-omit-frame-pointer");

        if (!task.isIr) {
            command.add("-I" + includeDir.toString());
            Path portability = includeDir.resolve("jnative_portability");
            if (Files.isDirectory(portability)) {
                command.add("-I" + portability);
            }
        }

        if (target.isWindows()) {
            appendWindowsSysrootToCompiler(command, mingwSysroot, task.isIr);

            command.add("-D_WIN32_WINNT=0x0A00");
            command.add("-DWINVER=0x0A00");
            command.add("-D_WINSOCK_DEPRECATED_NO_WARNINGS=1");
            command.add("-D_CRT_SECURE_NO_WARNINGS=1");
            command.add("-D__USE_MINGW_ANSI_STDIO=1");
            if (!isMinGwWrapper(compiler)) {
                command.add("--target=" + target.triple());
            }
        }

        command.add(task.src.toString());
        command.add("-o");
        command.add(task.obj.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        int exit = pb.start().waitFor();
        if (exit != 0) {
            throw new RuntimeException(
                    "Compilation of " + task.src + " failed with exit code " + exit
                            + " (target: " + target.triple() + ")");
        }
    }

    // =====================================================================
    //  Resource extraction
    // =====================================================================

    private String findNativeResourcePath(String className) {
        String base = className.startsWith("java/")
                ? NATIVE_BASE_PATH + className.substring("java/".length())
                : NATIVE_BASE_PATH + className;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String suffix : new String[]{".c", "Support.c"}) {
            String path = base + suffix;
            try (InputStream is = cl.getResourceAsStream(path)) {
                if (is != null) return path;
            } catch (IOException ignored) { }
        }
        return null;
    }

    @Override
    public boolean hasNativeSupport(String className) {
        return findNativeResourcePath(className) != null;
    }

    private Path extractResource(String resourcePath, Path destination) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException(
                        "Required resource not found on classpath: " + resourcePath);
            }
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }

    private List<Path> extractSystemNativeSources(Set<String> usedClasses, Path tempDir)
            throws IOException {
        List<Path> extracted = new ArrayList<>();
        Set<String> seenResources = new HashSet<>();
        for (String cls : usedClasses) {
            String resourcePath = findNativeResourcePath(cls);
            if (resourcePath == null || !seenResources.add(resourcePath)) continue;
            Path targetPath = tempDir.resolve(
                    cls.replace('/', File.separatorChar) + ".c");
            try (InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resourcePath)) {
                if (in == null) continue;
                if (targetPath.getParent() != null) {
                    Files.createDirectories(targetPath.getParent());
                }
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                extracted.add(targetPath);
            }
        }
        return extracted;
    }

    // =====================================================================
    //  Linker flags
    // =====================================================================

    private static void appendLinkerLibraries(List<String> linkCmd,
                                              TargetPlatform target) {
        if (target.isWindows()) {
            linkCmd.add("-mconsole");
            linkCmd.add("-Wl,--subsystem,console");
            linkCmd.add("-static-libgcc");
            linkCmd.add("-Wl,--gc-sections");
            linkCmd.add("-lm");

            // ---- Threading -------------------------------------------------
            //
            // Every pthread_* and sched_yield symbol that the runtime and its
            // per-class native files reference lives in libwinpthread:
            //
            //   jnative_runtime.c   : pthread_mutex_lock/unlock/destroy,
            //                         pthread_mutexattr_*,
            //                         pthread_cond_wait/init/broadcast/signal,
            //                         pthread_create/join, pthread_self/equal
            //   java/lang/Thread.c  : pthread_create/join, pthread_self/equal,
            //                         pthread_cond_*, jn_recmutex_init
            //   java/lang/Object.c  : pthread_cond_timedwait64
            //   Unsafe.c            : pthread_mutex_lock, pthread_cond_timedwait64
            //
            // MinGW-w64 ships libwinpthread under both names; -lpthread is the
            // conventional alias and resolves libpthread.a / libpthread.dll.a.
            // Without this flag every one of those symbols remains undefined.
            linkCmd.add("-lpthread");

            // ---- DbgHelp ---------------------------------------------------
            //
            // jnative_portability_windows.c and the Windows branch of
            // Reflection.c call SymInitialize / SymFromAddr / SymSetOptions
            // for stack-unwinding diagnostics. The import library that
            // resolves those __declspec(dllimport) symbols is libdbghelp.
            linkCmd.add("-ldbghelp");

            // ---- Windows SDK ----------------------------------------------
            linkCmd.add("-lws2_32");
            linkCmd.add("-liphlpapi");
            linkCmd.add("-lpsapi");
            linkCmd.add("-ladvapi32");
            return;
        }

        if (target.isDarwin()) {
            linkCmd.add("-Wl,-export_dynamic");
            linkCmd.add("-lpthread");
            linkCmd.add("-lm");
            return;
        }

        linkCmd.add("-rdynamic");
        linkCmd.add("-lpthread");
        linkCmd.add("-ldl");
        linkCmd.add("-lm");
    }

    // =====================================================================
    //  Native library discovery
    // =====================================================================

    @Override
    public List<Path> collectNativeLibraries(Module module,
                                             DependencyResolver resolver,
                                             TargetPlatform target)
            throws IOException {

        Set<String> wanted = new HashSet<>();
        for (io.github.kubyk01.domain.ir.Function f : module.getFunctions()) {
            if (f.isJniNative()) wanted.add(f.getName());
        }
        if (wanted.isEmpty()) return List.of();

        List<Path> candidates = new ArrayList<>();

        Path extractDir = Files.createTempDirectory("jnative-natives-");
        extractDir.toFile().deleteOnExit();
        scanClasspathJarsForNatives(target, extractDir, candidates);

        Set<String> stillWanted = new HashSet<>(wanted);
        List<Path> result = new ArrayList<>();
        Collections.sort(candidates);

        for (Path lib : candidates) {
            if (stillWanted.isEmpty()) break;
            Set<String> exported = readExportedSymbols(lib, target);
            Set<String> overlap = new HashSet<>(exported);
            overlap.retainAll(stillWanted);
            if (overlap.isEmpty()) continue;
            result.add(lib);
            stillWanted.removeAll(overlap);
            log.info("Native library {} provides {} required JNI symbol(s)",
                    lib, overlap.size());
        }

        if (!stillWanted.isEmpty()) {
            log.warn("{} JNI symbol(s) have neither a JNative C implementation "
                            + "under jnative/** nor a matching platform library under "
                            + "META-INF/native/. The link step will fail. Missing "
                            + "symbols: {}",
                    stillWanted.size(),
                    stillWanted.stream().limit(5).collect(Collectors.joining(", ")));
        }
        return result;
    }

    private Set<String> readExportedSymbols(Path lib, TargetPlatform target)
            throws IOException {
        if (target.isWindows()) {
            return PeSymbolReader.read(lib);
        }
        try {
            return ElfSymbolReader.read(lib);
        } catch (Throwable t) {
            return readExportedSymbolsViaNm(lib);
        }
    }

    private Set<String> readExportedSymbolsViaNm(Path lib) {
        try {
            Process p = new ProcessBuilder("nm", "-D", "--defined-only", lib.toString())
                    .redirectErrorStream(true).start();
            Set<String> syms = new HashSet<>();
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int sp = line.lastIndexOf(' ');
                    if (sp < 0 || sp + 1 >= line.length()) continue;
                    String name = line.substring(sp + 1).trim();
                    if (!name.isEmpty()) syms.add(name);
                }
            }
            p.waitFor();
            return syms;
        } catch (Exception e) {
            return Set.of();
        }
    }

    private void scanClasspathJarsForNatives(TargetPlatform target,
                                             Path extractDir,
                                             List<Path> out) {
        String suffix = target.nativeLibrarySuffix();

        String cp = System.getProperty("java.class.path", "");
        Set<Path> jars = new LinkedHashSet<>();
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            Path p = Paths.get(entry);
            if (Files.isRegularFile(p) && entry.endsWith(".jar")) {
                jars.add(p);
            }
        }

        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String n = e.getName();
                    if (!n.endsWith(suffix)) continue;
                    if (!n.contains("native/") && !n.contains("natives/")) continue;
                    String base = n.substring(n.lastIndexOf('/') + 1);
                    Path dest = extractDir.resolve(base);
                    if (Files.exists(dest)) continue;
                    try (InputStream is = jf.getInputStream(e)) {
                        Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                        out.add(dest);
                        log.info("Extracted native library {} from {}", base, jar);
                    }
                }
            } catch (IOException e) {
                log.debug("Skipping JAR {}: {}", jar, e.getMessage());
            }
        }
    }

    // =====================================================================
    //  Native library bundling
    // =====================================================================

    private void bundleNativeLibraries(Path exePath, List<Path> nativeLibs,
                                       Set<Path> nativeLibDirs,
                                       TargetPlatform target) throws IOException {

        if (nativeLibs.isEmpty()) return;

        Path exeDir = exePath.toAbsolutePath().getParent();
        Set<Path> copied = new LinkedHashSet<>();

        for (Path lib : nativeLibs) {
            Path dest = exeDir.resolve(lib.getFileName().toString());
            if (Files.exists(dest)) continue;
            Files.copy(lib, dest, StandardCopyOption.REPLACE_EXISTING);
            copied.add(dest);
        }

        Deque<Path> toVisit = new ArrayDeque<>(copied);
        while (!toVisit.isEmpty()) {
            Path lib = toVisit.poll();

            Set<String> needed = target.isWindows()
                    ? PeSymbolReader.readImportedLibraries(lib)
                    : ElfSymbolReader.readNeededLibraries(lib);

            for (String soname : needed) {
                if (soname.startsWith("/") || soname.contains("\\")) continue;

                Path source = null;
                for (Path dir : nativeLibDirs) {
                    Path candidate = dir.resolve(soname);
                    if (Files.isRegularFile(candidate)) {
                        source = candidate;
                        break;
                    }
                    if (target.isWindows()) {
                        try (var stream = Files.list(dir)) {
                            source = stream
                                    .filter(p -> p.getFileName().toString()
                                            .equalsIgnoreCase(soname))
                                    .findFirst().orElse(null);
                        }
                        if (source != null) break;
                    }
                }
                if (source == null) continue;

                Path dest = exeDir.resolve(source.getFileName().toString());
                if (Files.exists(dest)) continue;
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                toVisit.add(dest);
            }
        }

        System.out.println("Bundled " + copied.size()
                + " native library/libraries next to " + exePath);
    }

    // =====================================================================
    //  Referenced native classes
    // =====================================================================

    private Set<String> collectReferencedNativeClasses(Module module,
                                                       DependencyResolver resolver) {
        Set<String> result = new HashSet<>();
        for (String cls : new ArrayList<>(resolver.getClassMap().keySet())) {
            if (!hasNativeSupport(cls)) continue;
            ClassNode cn = resolver.getClassNode(cls);
            if (cn == null || cn.isExternal()) continue;
            for (MethodNode mn : cn.getMethods()) {
                if (!mn.isNative()) continue;
                String nativeName = "__jnative_" + LlvmRuntime.mangleMethod(
                        cls, mn.getName(), mn.getDescriptor());
                if (module.getFunction(nativeName) != null) {
                    result.add(cls);
                    break;
                }
            }
        }
        return result;
    }

    private static String sanitizeForObjName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}