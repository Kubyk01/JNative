package io.github.kubyk01.application.service.compiler;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.TargetPlatform;
import io.github.kubyk01.port.secondary.CompilerPort;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

   // Todo remove hard-coded ways
    private static final String[] MINGW_ROOT_CANDIDATES_POSIX = {
            "/usr/x86_64-w64-mingw32",
            "/usr/x86_64-w64-mingw32/sys-root/mingw",
            "/usr/aarch64-w64-mingw32",
            "/usr/aarch64-w64-mingw32/sys-root/mingw",
            "/usr/i686-w64-mingw32",
            "/usr/i686-w64-mingw32/sys-root/mingw",
            "/mingw64",
            "/mingw32",
    };

    private static final String[] MINGW_ROOT_CANDIDATES_WINDOWS = {
            "C:\\msys64\\mingw64",
            "C:\\msys64\\ucrt64",
            "C:\\msys64\\clang64",
            "C:\\msys64\\mingw32",
            "C:\\msys2\\mingw64",
            "C:\\msys2\\ucrt64",
            "C:\\msys2\\clang64",
            "C:\\mingw64",
            "C:\\mingw32",
            "C:\\tools\\mingw64",
            "C:\\tools\\msys64\\mingw64",
            "C:\\tools\\msys64\\ucrt64",
            "C:\\ProgramData\\chocolatey\\lib\\mingw\\tools\\install\\mingw64",
            "C:\\ProgramData\\chocolatey\\lib\\mingw\\tools\\install\\mingw32",
            "C:\\llvm-mingw",
            "C:\\Program Files\\llvm-mingw",
            "C:\\Program Files (x86)\\llvm-mingw",
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

            MsvcToolchain msvcToolchain = null;
            String mingwSysroot = null;

            if (target.isWindows()) {
                boolean isMsvc = target.triple().toLowerCase(Locale.ROOT)
                        .contains("-msvc");
                if (isMsvc) {
                    msvcToolchain = checkWindowsMsvcToolchain(target);
                } else {
                    mingwSysroot = resolveWindowsSysroot(target);
                    System.out.println("MinGW-w64 sysroot: " + mingwSysroot);
                    checkWindowsToolchain(target, compiler, mingwSysroot);
                }
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
                    target, mingwSysroot, msvcToolchain);

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
            Path defFile = null;
            if (target.isWindows()) {
                defFile = generateWindowsExportDef(module, llPaths, tempDir);
            }

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
                if (!isAvailable("lld-link")) {
                    throw new IllegalStateException(
                            "ThinLTO for target '" + target.triple() + "' requires the "
                                    + "LLVM linker 'lld-link', but it was not found on PATH. "
                                    + "MSVC link.exe cannot consume LLVM bitcode objects. "
                                    + "Install LLVM with lld (e.g. `winget install LLVM.LLVM`) "
                                    + "and make sure its bin directory is on PATH.");
                }
                linkCmd.add("-fuse-ld=lld");
                linkCmd.add("-flto-jobs=" + effectiveCores);
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

                // The MinGW-w64 GCC runtime library discovery and the
                // libgcc_eh.a fallback symlink are ONLY relevant when
                // cross-compiling to a Windows GNU target. On a native
                // Linux/macOS build, the host toolchain already resolves
                // -lgcc and -lgcc_eh through its own search paths; running
                // findGccRuntimeLibDirs() unconditionally here makes it pick
                // up the mingw-w64 cross-compiler's libgcc.a (if the
                // cross-toolchain happens to be installed), add its directory
                // as -L, and the native ld.lld then tries to link MinGW PE
                // .o members as if they were ELF relocatables — the "archive
                // member 'X.o' is neither ET_REL nor LLVM bitcode" warning
                // storm.
                if (target.isWindows()) {
                    List<Path> gccRuntimeDirs = findGccRuntimeLibDirs(target);
                    for (Path dir : gccRuntimeDirs) {
                        linkCmd.add("-L" + dir);
                    }
                    Path ehFallbackDir = ensureLibgccEh(target, gccRuntimeDirs, tempDir);
                    if (ehFallbackDir != null) {
                        linkCmd.add("-L" + ehFallbackDir);
                    }
                }

                linkCmd.add("-fuse-ld=lld");
                linkCmd.add("-Wl,--thinlto-jobs=" + effectiveCores);
            }

            appendWindowsSysrootToLinker(linkCmd, mingwSysroot, target);

            List<String> objectLines = new ArrayList<>(tasks.size());
            for (CompileTask task : tasks) {
                String objPath = task.obj.toAbsolutePath().toString().replace('\\', '/');
                objectLines.add("\"" + objPath + "\"");
            }
            Path objectsRsp = tempDir.resolve("link_objects.rsp");
            Files.write(objectsRsp, objectLines, StandardCharsets.UTF_8);
            linkCmd.add("@" + objectsRsp.toAbsolutePath());
            linkCmd.add("-o");
            linkCmd.add(exePath.toString());

            appendLinkerLibraries(linkCmd, target, defFile);

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
        if (!target.isWindows()) {
            return List.of();
        }

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
            boolean isMsvc = target.triple().toLowerCase(Locale.ROOT)
                    .contains("-msvc");

            if (isMsvc) {
                if (isAvailable("clang")) return "clang";
                throw new IllegalStateException(
                        "MSVC target requires clang on PATH. "
                                + "Install LLVM for Windows (https://releases.llvm.org/) "
                                + "or use --target=x86_64-w64-windows-gnu with MinGW-w64.");
            }

            String wrapperName = "x86_64-w64-mingw32-clang";
            if (isAvailable(wrapperName)) {
                return wrapperName;
            }

            String sysroot = findMinGWSysroot();
            if (sysroot != null) {
                Path wrapper = Paths.get(sysroot, "bin", wrapperName + ".exe");
                if (!Files.exists(wrapper)) {
                    wrapper = Paths.get(sysroot, "bin", wrapperName);
                }
                if (Files.isRegularFile(wrapper)) {
                    return wrapper.toString();
                }
            }

            if (isAvailable("clang")) {
                return "clang";
            }
            throw new IllegalStateException(
                    "GNU Windows target requires clang or x86_64-w64-mingw32-clang "
                            + "on PATH. Install LLVM for Windows or MSYS2 with mingw-w64.");
        }

        if (isAvailable("clang")) return "clang";
        if (isAvailable("gcc")) return "gcc";
        throw new IllegalStateException(
                "Neither clang nor gcc is installed. At least one is required.");
    }

    private static MsvcToolchain checkWindowsMsvcToolchain(TargetPlatform target) {
        String vswhere = findVswhere();
        if (vswhere == null) {
            throw new IllegalStateException(
                    "Visual Studio Installer not found.\n"
                            + "Expected vswhere.exe at:\n"
                            + "  %ProgramFiles(x86)%\\Microsoft Visual Studio\\Installer\\vswhere.exe\n\n"
                            + "Install one of:\n"
                            + "  - Visual Studio Build Tools (no IDE, recommended):\n"
                            + "      winget install Microsoft.VisualStudio.2022.BuildTools --override "
                            + "\"--passive --wait --add Microsoft.VisualStudio.Workload.VCTools "
                            + "--includeRecommended\"\n"
                            + "  - Full Visual Studio with \"Desktop development with C++\" workload.\n\n"
                            + "Or switch to a GNU toolchain that bundles everything:\n"
                            + "  --target=x86_64-w64-windows-gnu   (llvm-mingw, no MSVC needed)\n"
                            + "  https://github.com/mstorsjo/llvm-mingw/releases");
        }

        String installationPath = runCapture(vswhere,
                "-latest", "-products", "*",
                "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "-property", "installationPath");

        if (installationPath == null || installationPath.isBlank()) {
            throw new IllegalStateException(
                    "Visual Studio Build Tools are installed, but the C++ "
                            + "workload (Microsoft.VisualStudio.Component.VC.Tools.x86.x64) "
                            + "is missing.\n\n"
                            + "Open Visual Studio Installer and add:\n"
                            + "  Workload: \"Desktop development with C++\"\n"
                            + "  Components (in the right panel):\n"
                            + "    - MSVC v143 - VS 2022 C++ x64/x86 build tools\n"
                            + "    - Windows 10/11 SDK\n\n"
                            + "Or re-run the installer with the C++ workload:\n"
                            + "  winget install Microsoft.VisualStudio.2022.BuildTools --override "
                            + "\"--passive --wait --add Microsoft.VisualStudio.Workload.VCTools "
                            + "--includeRecommended\"\n\n"
                            + "Alternatively, use a self-contained GNU toolchain:\n"
                            + "  --target=x86_64-w64-windows-gnu\n"
                            + "  https://github.com/mstorsjo/llvm-mingw/releases");
        }

        String vcToolsRoot = installationPath.trim();
        File msvcDir = new File(vcToolsRoot, "VC\\Tools\\MSVC");
        if (!msvcDir.isDirectory()) {
            throw new IllegalStateException(
                    "MSVC C++ toolchain directory not found under:\n  "
                            + msvcDir + "\n\n"
                            + "The C++ workload was probably installed partially. "
                            + "Open Visual Studio Installer and add the "
                            + "\"MSVC v143 - VS 2022 C++ x64/x86 build tools\" component.");
        }

        File[] versions = msvcDir.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) {
            throw new IllegalStateException(
                    "No MSVC versions found under:\n  " + msvcDir + "\n\n"
                            + "Reinstall the C++ workload via Visual Studio Installer.");
        }
        Arrays.sort(versions, Comparator.comparing(File::getName).reversed());
        String vcToolsInclude = new File(versions[0], "include").getAbsolutePath();
        if (!new File(vcToolsInclude, "vcruntime.h").exists()) {
            throw new IllegalStateException(
                    "MSVC toolchain at " + versions[0] + " has no vcruntime.h.\n"
                            + "The installation is corrupted. Reinstall the C++ workload.");
        }

        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFilesX86 == null) programFilesX86 = "C:\\Program Files (x86)";
        File sdkIncludeRoot = new File(programFilesX86,
                "Windows Kits\\10\\Include");
        if (!sdkIncludeRoot.isDirectory()) {
            throw new IllegalStateException(
                    "Windows SDK not found at:\n  " + sdkIncludeRoot + "\n\n"
                            + "Open Visual Studio Installer and install:\n"
                            + "  Windows 10 SDK (or Windows 11 SDK)\n"
                            + "under the \"Desktop development with C++\" workload.\n\n"
                            + "Alternatively, use a self-contained GNU toolchain:\n"
                            + "  --target=x86_64-w64-windows-gnu\n"
                            + "  https://github.com/mstorsjo/llvm-mingw/releases");
        }

        File[] sdkVersions = sdkIncludeRoot.listFiles(File::isDirectory);
        if (sdkVersions == null || sdkVersions.length == 0) {
            throw new IllegalStateException(
                    "Windows SDK directory exists but contains no versions:\n  "
                            + sdkIncludeRoot + "\n\n"
                            + "Reinstall the Windows SDK via Visual Studio Installer.");
        }
        Arrays.sort(sdkVersions, Comparator.comparing(File::getName).reversed());
        File sdkVersionDir = sdkVersions[0];

        File sdkRootDir = sdkIncludeRoot.getParentFile();
        if (sdkRootDir == null
                || !sdkRootDir.isDirectory()
                || !new File(sdkRootDir, "Include").isDirectory()
                || !new File(sdkRootDir, "Lib").isDirectory()) {
            throw new IllegalStateException(
                    "Cannot determine the Windows SDK root directory from:\n  "
                            + sdkIncludeRoot + "\n\n"
                            + "Expected layout:\n"
                            + "  <SDK root>\\Include\\<version>\\ucrt\\stdlib.h\n"
                            + "  <SDK root>\\Lib\\<version>\\ucrt\\x64\\ucrt.lib\n\n"
                            + "Reinstall the Windows SDK via Visual Studio Installer.");
        }
        if (!new File(sdkVersionDir, "ucrt\\stdlib.h").exists()) {
            throw new IllegalStateException(
                    "Windows SDK version directory " + sdkVersionDir
                            + " has no ucrt\\stdlib.h.\n"
                            + "The installation is corrupted or incomplete. "
                            + "Reinstall the Windows 10/11 SDK.");
        }

        System.out.println("MSVC toolchain:");
        System.out.println("  VC tools : " + versions[0].getName()
                + " (" + vcToolsInclude + ")");
        System.out.println("  Win SDK  : " + sdkVersionDir.getName()
                + " (root: " + sdkRootDir.getAbsolutePath() + ")");

        return new MsvcToolchain(versions[0].getAbsolutePath(),
                sdkRootDir.getAbsolutePath());
    }

    private record MsvcToolchain(String vcToolsRoot, String windowsSdkRoot) {}

    private static String findVswhere() {
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFilesX86 == null) programFilesX86 = "C:\\Program Files (x86)";
        File vswhere = new File(programFilesX86,
                "Microsoft Visual Studio\\Installer\\vswhere.exe");
        return vswhere.isFile() ? vswhere.getAbsolutePath() : null;
    }

    private static String runCapture(String executable, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.addAll(Arrays.asList(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output;
            try (var reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(),
                            StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n")).trim();
            }
            p.waitFor();
            return output.isEmpty() ? null : output;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void checkWindowsToolchain(TargetPlatform target,
                                              String compiler,
                                              String mingwSysroot) {
        if (!target.isWindows()) {
            return;
        }

        String testSource = "#include <stdlib.h>\nint main(void){return 0;}\n";

        List<String> command = new ArrayList<>();
        command.add(compiler);

        boolean isMsvc = target.triple().toLowerCase(Locale.ROOT)
                .contains("-msvc");

        if (!isMinGwWrapper(compiler)) {
            command.add("--target=" + target.triple());
        }

        if (!isMsvc && mingwSysroot != null) {
            command.add("--sysroot=" + mingwSysroot);
            command.add("--gcc-toolchain=" + mingwSysroot);
        }

        command.add("-fsyntax-only");
        command.add("-x");
        command.add("c");
        command.add("-");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            try (var stdin = process.getOutputStream()) {
                stdin.write(testSource.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            process.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return;
            }

            String lower = output.toLowerCase(Locale.ROOT);
            if (lower.contains("stdlib.h") && lower.contains("not found")) {
                String advice = isMsvc
                        ? msvcToolchainAdvice()
                        : gnuToolchainAdvice();
                throw new IllegalStateException(
                        "C toolchain for target '" + target.triple()
                                + "' cannot find standard C headers.\n"
                                + "The compiler '" + compiler + "' reported:\n"
                                + "  fatal error: 'stdlib.h' file not found\n\n"
                                + advice
                                + "\n\nAlternatively, switch to a different "
                                + "toolchain, e.g.:\n"
                                + "  --target=x86_64-w64-windows-gnu   (MinGW-w64)\n"
                                + "  --target=x86_64-pc-windows-msvc   (MSVC SDK)\n"
                                + "\nFull compiler output:\n" + output);
            }

            throw new IllegalStateException(
                    "C toolchain check failed for target '" + target.triple()
                            + "' with exit code " + exitCode + ":\n" + output);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(
                    "Failed to run C toolchain check for target '"
                            + target.triple() + "': " + e.getMessage(), e);
        }
    }

    private static String msvcToolchainAdvice() {
        return """
                The MSVC target requires the Windows SDK and Visual C++ Build Tools.
                Install one of:

                  1. Visual Studio Build Tools (recommended, no full IDE needed):
                     winget install Microsoft.VisualStudio.2022.BuildTools --override "--passive --wait --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"

                  2. Full Visual Studio with "Desktop development with C++" workload.

                After installation, clang will locate the SDK automatically
                (via vswhere.exe / environment / registry). No extra flags needed.
                """;
    }

    private static String gnuToolchainAdvice() {
        return """
                The GNU target requires MinGW-w64 (headers + runtime).
                Install one of:

                  1. MSYS2 (recommended):
                     pacman -S mingw-w64-x86_64-gcc mingw-w64-x86_64-clang
                     Then add C:\\msys64\\mingw64\\bin to PATH,
                     or set MINGW_SYSROOT=C:\\msys64\\mingw64.

                  2. llvm-mingw (self-contained clang + MinGW):
                     https://github.com/mstorsjo/llvm-mingw/releases
                     Unpack, add its bin/ to PATH, set MINGW_SYSROOT to its root.

                  3. Chocolatey:
                     choco install mingw

                If MinGW is already installed, point MINGW_SYSROOT at its root
                (the directory containing include\\stdlib.h).
                """;
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

    private static String resolveWindowsSysroot(TargetPlatform target) {
        if (!target.isWindows()) return null;

        boolean isMsvc = target.triple().toLowerCase(Locale.ROOT).contains("-msvc");
        if (isMsvc) {
            return null;
        }

        String sysroot = findMinGWSysroot();
        if (sysroot == null) {
            throw new IllegalStateException(
                    "MinGW-w64 installation not found for GNU target '"
                            + target.triple() + "'.\n"
                            + "Install one of:\n"
                            + "  - MSYS2 (https://www.msys2.org/) with package\n"
                            + "      mingw-w64-x86_64-gcc   (64-bit)\n"
                            + "      mingw-w64-i686-gcc     (32-bit)\n"
                            + "    default install path: C:\\msys64\\mingw64\n"
                            + "  - llvm-mingw (https://github.com/mstorsjo/llvm-mingw)\n"
                            + "  - Chocolatey: choco install mingw\n"
                            + "  - Or set the MINGW_SYSROOT environment variable to\n"
                            + "    the root of an existing MinGW-w64 installation\n"
                            + "    (the directory that contains include\\stdlib.h).\n"
                            + "Searched:\n"
                            + "  env MINGW_SYSROOT, MSYSTEM_PREFIX, MINGW_PREFIX\n"
                            + "  `where gcc` / `where x86_64-w64-mingw32-gcc`\n"
                            + "  gcc --print-sysroot\n"
                            + String.join("\n  ", MINGW_ROOT_CANDIDATES_WINDOWS));
        }
        return sysroot;
    }

    private static String findMinGWSysroot() {
        String[] envVars = { "MINGW_SYSROOT", "MSYSTEM_PREFIX", "MINGW_PREFIX" };
        for (String env : envVars) {
            String val = System.getenv(env);
            if (val != null && !val.isBlank()) {
                String trimmed = val.trim();
                if (!trimmed.equalsIgnoreCase("NONE")
                        && !trimmed.equalsIgnoreCase("SKIP")
                        && hasStdlibHeader(trimmed)) {
                    return trimmed;
                }
            }
        }

        boolean isWindowsHost = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");

        String[] gccNames = isWindowsHost
                ? new String[]{ "x86_64-w64-mingw32-gcc.exe",
                                "x86_64-w64-mingw32-gcc",
                                "gcc.exe", "gcc" }
                : new String[]{ "x86_64-w64-mingw32-gcc", "gcc" };

        for (String gccName : gccNames) {
            String gccPath = findExecutable(gccName);
            if (gccPath == null) continue;

            String printed = queryGccSysroot(gccPath);
            if (printed != null && hasStdlibHeader(printed)) {
                return printed;
            }

            File gccFile = new File(gccPath);
            File binDir = gccFile.getParentFile();
            if (binDir != null) {
                File rootDir = binDir.getParentFile();
                if (rootDir != null && hasStdlibHeader(rootDir.getAbsolutePath())) {
                    return rootDir.getAbsolutePath();
                }
                if (hasStdlibHeader(binDir.getAbsolutePath())) {
                    return binDir.getAbsolutePath();
                }
            }
        }

        String[] candidates = isWindowsHost
                ? MINGW_ROOT_CANDIDATES_WINDOWS
                : MINGW_ROOT_CANDIDATES_POSIX;
        for (String candidate : candidates) {
            if (hasStdlibHeader(candidate)) {
                return candidate;
            }
        }

        if (isWindowsHost) {
            for (String pfVar : new String[]{ "ProgramFiles(x86)", "ProgramFiles" }) {
                String pf = System.getenv(pfVar);
                if (pf == null) continue;
                Path mingwW64Root = Paths.get(pf, "mingw-w64");
                if (!Files.isDirectory(mingwW64Root)) continue;
                try (var stream = Files.list(mingwW64Root)) {
                    for (Path sub : stream.toList()) {
                        if (!Files.isDirectory(sub)) continue;
                        for (String triple : new String[]{
                                "mingw64", "mingw32", "x86_64-w64-mingw32" }) {
                            Path candidate = sub.resolve(triple);
                            if (hasStdlibHeader(candidate.toString())) {
                                return candidate.toString();
                            }
                        }
                    }
                } catch (IOException ignored) { }
            }
        }

        return null;
    }

    private static boolean hasStdlibHeader(String root) {
        if (root == null || root.isEmpty()) return false;
        Path p = Paths.get(root);
        if (Files.exists(p.resolve("include").resolve("stdlib.h"))) return true;
        if (Files.exists(p.resolve("sys-root").resolve("mingw")
                .resolve("include").resolve("stdlib.h"))) return true;
        return false;
    }

    private static String findExecutable(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        boolean isWindows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        String[] exts = isWindows
                ? new String[]{ "", ".exe", ".bat", ".cmd" }
                : new String[]{ "" };
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            for (String ext : exts) {
                File f = new File(dir, name.endsWith(ext) ? name : name + ext);
                if (f.isFile() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String queryGccSysroot(String gccPath) {
        try {
            Process p = new ProcessBuilder(gccPath, "--print-sysroot")
                    .redirectErrorStream(false).start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                output = reader.readLine();
            }
            p.waitFor();
            if (output == null || output.isBlank()) return null;
            return output.trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void appendWindowsSysrootToCompiler(List<String> command,
                                                       String sysroot,
                                                       boolean isIr,
                                                       TargetPlatform target) {
        if (sysroot == null) return;

        boolean isMsvc = target.triple().toLowerCase(Locale.ROOT).contains("-msvc");
        if (isMsvc) return;

        command.add("--sysroot=" + sysroot);
        command.add("--gcc-toolchain=" + sysroot);

        if (isIr) return;

        addIfDirectory(command, "-I", sysroot, "include");
        addIfDirectory(command, "-I", sysroot, "x86_64-w64-mingw32", "include");
        addIfDirectory(command, "-I", sysroot, "i686-w64-mingw32", "include");
        addIfDirectory(command, "-I", sysroot, "aarch64-w64-mingw32", "include");
        addIfDirectory(command, "-I", sysroot, "sys-root", "mingw", "include");
    }

    private static void addIfDirectory(List<String> command,
                                       String flag,
                                       String root,
                                       String... relativePath) {
        Path p = Paths.get(root, relativePath);
        if (Files.isDirectory(p)) {
            command.add(flag + p);
        }
    }

    private static void appendWindowsSysrootToLinker(List<String> linkCmd,
                                                     String sysroot,
                                                     TargetPlatform target) {
        if (sysroot == null) return;
        boolean isMsvc = target.triple().toLowerCase(Locale.ROOT).contains("-msvc");
        if (isMsvc) return;

        linkCmd.add("--sysroot=" + sysroot);
        linkCmd.add("--gcc-toolchain=" + sysroot);
        addLibIfDir(linkCmd, sysroot, "lib");
        addLibIfDir(linkCmd, sysroot, "x86_64-w64-mingw32", "lib");
        addLibIfDir(linkCmd, sysroot, "i686-w64-mingw32", "lib");
        addLibIfDir(linkCmd, sysroot, "aarch64-w64-mingw32", "lib");
        addLibIfDir(linkCmd, sysroot, "sys-root", "mingw", "lib");
    }

    private static void addLibIfDir(List<String> cmd, String root, String... rel) {
        Path p = Paths.get(root, rel);
        if (Files.isDirectory(p)) cmd.add("-L" + p);
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
                            String mingwSysroot, MsvcToolchain msvcToolchain)
            throws IOException, InterruptedException {

        if (threadCount <= 1 || tasks.size() <= 1) {
            for (CompileTask task : tasks) {
                compileOne(compiler, task, includeDir, optimizationLevel,
                        target, mingwSysroot, msvcToolchain);
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
                                optimizationLevel, target, mingwSysroot,
                                msvcToolchain);
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
                            String mingwSysroot, MsvcToolchain msvcToolchain)
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

        if (task.isIr) {
            command.add("-Wno-override-module");
        } else {
            command.add("-I" + includeDir.toString());
            Path portability = includeDir.resolve("jnative_portability");
            if (Files.isDirectory(portability)) {
                command.add("-I" + portability);
            }
        }

        if (target.isWindows()) {
            boolean isMsvc = target.triple().toLowerCase(Locale.ROOT)
                    .contains("-msvc");

            if (isMsvc) {
                if (msvcToolchain != null) {
                    command.add("-Xmicrosoft-visualc-tools-root");
                    command.add(msvcToolchain.vcToolsRoot());
                    command.add("-Xmicrosoft-windows-sdk-root");
                    command.add(msvcToolchain.windowsSdkRoot());
                }
                if (!isMinGwWrapper(compiler)) {
                    command.add("--target=" + target.triple());
                }
                command.add("-D_WIN32_WINNT=0x0A00");
                command.add("-DWINVER=0x0A00");
                command.add("-D_WINSOCK_DEPRECATED_NO_WARNINGS=1");
                command.add("-D_CRT_SECURE_NO_WARNINGS=1");
                command.add("-D_CRT_NONSTDC_NO_DEPRECATE=1");
                command.add("-D__USE_MINGW_ANSI_STDIO=1");
            } else {
                appendWindowsSysrootToCompiler(command, mingwSysroot, task.isIr, target);
                if (!isMinGwWrapper(compiler)) {
                    command.add("--target=" + target.triple());
                }
                command.add("-D_WIN32_WINNT=0x0A00");
                command.add("-DWINVER=0x0A00");
                command.add("-D_WINSOCK_DEPRECATED_NO_WARNINGS=1");
                command.add("-D_CRT_SECURE_NO_WARNINGS=1");
                command.add("-D__USE_MINGW_ANSI_STDIO=1");
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
        Set<String> seenSymbols = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(usedClasses);
        while (!pending.isEmpty()) {
            String cls = pending.poll();
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
            Matcher m = NATIVE_SYMBOL_REFERENCE.matcher(Files.readString(targetPath));
            while (m.find()) {
                if (!seenSymbols.add(m.group(1))) continue;
                String owner = findNativeOwnerOfSymbol(m.group(1));
                if (owner != null) pending.add(owner);
            }
        }
        return extracted;
    }

    private static final Pattern NATIVE_SYMBOL_REFERENCE =
            Pattern.compile("__jnative_fn_([A-Za-z0-9_]+)");

    private String findNativeOwnerOfSymbol(String mangled) {
        int sep = mangled.indexOf("__");
        String head = sep < 0 ? mangled : mangled.substring(0, sep);
        String[] parts = head.split("_");
        for (int k = parts.length; k >= 1; k--) {
            String candidate = String.join("/", Arrays.copyOfRange(parts, 0, k));
            if (findNativeResourcePath(candidate) != null) return candidate;
        }
        return null;
    }

    // =====================================================================
    //  Windows export table
    // =====================================================================

    private static Path generateWindowsExportDef(Module module,
                                                 List<Path> llPaths,
                                                 Path tempDir) throws IOException {
        Set<String> exports = new TreeSet<>();

        // ------------------------------------------------------------------
        // 1. Defined functions in the module. A Function with an entry block
        //    becomes `define void @fn_...` in _module.ll; one without it is a
        //    bare `declare` and has no body to export.
        // ------------------------------------------------------------------
        Set<String> definedFunctions = new HashSet<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() == null) continue;
            definedFunctions.add(f.getName());
        }

        // ------------------------------------------------------------------
        // 2. Defined globals from _module.ll.
        //
        //    `@name = global ...` and `@name = constant ...` are global
        //    definitions. Function definitions start with `define`, function
        //    declarations start with `declare`, and operand-only `@` mentions
        //    start with an instruction mnemonic — none of those begin with `@`,
        //    so the `startsWith("@")` filter is exact.
        // ------------------------------------------------------------------
        Path moduleLl = null;
        for (Path ll : llPaths) {
            if ("_module.ll".equals(ll.getFileName().toString())) {
                moduleLl = ll;
                break;
            }
        }
        if (moduleLl == null) {
            throw new IllegalStateException(
                "Cannot generate Windows export def: _module.ll not found in "
                    + llPaths);
        }
        Set<String> definedGlobals = new HashSet<>();
        for (String line : Files.readAllLines(moduleLl)) {
            if (!line.startsWith("@")) continue;
            int eq = line.indexOf(" = ");
            if (eq <= 0) continue;
            String lhs = line.substring(1, eq).trim();
            String rhs = line.substring(eq + 3).trim();
            if (rhs.startsWith("external ") || rhs.startsWith("declare ")) continue;
            definedGlobals.add(lhs);
        }

        // ------------------------------------------------------------------
        // 3. Globals the C runtime dlsyms.
        //
        //    jnative_runtime.c and the per-class native support files all
        //    resolve these through GetProcAddress / dlsym(RTLD_DEFAULT, ...),
        //    which on Windows can only see the export table. The four prefixes
        //    below are the only global families the runtime looks up by name.
        // ------------------------------------------------------------------
        for (String g : definedGlobals) {
            if (g.startsWith("gv_")
                || g.startsWith("vtable_")
                || g.startsWith("__type_info_")) {
                exports.add(g);
            }
        }

        // ------------------------------------------------------------------
        // 4. Functions the C runtime dlsyms by pattern.
        //
        //    __destruct_<Type> / __destruct_array_<Desc>
        //        resolved by jnative_resolve_destructor() in jnative_runtime.c.
        //
        //    fn_<Class>__clinit____V
        //        built at runtime by ObjectStreamClass.hasStaticInitializer()
        //        and looked up through dlsym.
        // ------------------------------------------------------------------
        for (String f : definedFunctions) {
            if (f.startsWith("__destruct_")) {
                exports.add(f);
            } else if (f.startsWith("fn_") && f.endsWith("__clinit____V")) {
                exports.add(f);
            }
        }

        // ------------------------------------------------------------------
        // 5. Functions the C runtime dlsyms by literal.
        //
        //    Anywhere a C source writes a `"fn_..."` or `"__jnative_fn_..."`
        //    string literal, that literal is a dlsym target. The scan is data-
        //    driven: when a future C file adds a new lookup, it is picked up
        //    automatically without touching this Java method.
        //
        //    The literal is only added to the .def when it is also a defined
        //    function in the module. That filter is what makes JDK-version
        //    differences safe: MethodHandleNatives.c still mentions
        //    "fn_java_lang_reflect_Method_getType__..." in its source, but on
        //    JDK 21 that method does not exist, so the literal is skipped
        //    rather than handed to ld.lld as an undefined-symbol requirement.
        // ------------------------------------------------------------------
        Set<String> fnLiteralsInCSources = new HashSet<>();
        try (var stream = Files.walk(tempDir)) {
            stream.filter(p -> p.toString().endsWith(".c"))
                  .forEach(p -> scanFnLiterals(p, fnLiteralsInCSources));
        }
        for (String literal : fnLiteralsInCSources) {
            if (definedFunctions.contains(literal)) {
                exports.add(literal);
            }
        }

        // ------------------------------------------------------------------
        // 6. Write the .def file.
        // ------------------------------------------------------------------
        Path defFile = tempDir.resolve("exports.def");
        StringBuilder sb = new StringBuilder("EXPORTS\n");
        for (String sym : exports) {
            sb.append("    ").append(sym).append("\n");
        }
        Files.writeString(defFile, sb.toString());

        System.out.println("Windows export table: " + exports.size() + " symbol(s)");
        return defFile;
    }

    /**
     * Scans one C source file for {@code "fn_..."} and
     * {@code "__jnative_fn_..."} string literals and records every match in
     * {@code out}.
     *
     * <p>The scan runs on the comment-stripped text, so a symbol mentioned
     * only in a comment is not picked up. String literals themselves are
     * preserved verbatim, so a symbol referenced from a {@code #define}
     * (as {@code Perf.c} does) is found even when the {@code #define} and
     * its {@code dlsym} call site are on separate physical lines.</p>
     *
     * <p>False positives — a string that happens to look like a mangled
     * symbol but is not actually passed to {@code dlsym} — are filtered out
     * by the {@code definedFunctions.contains(literal)} check at the call
     * site. So a string that ends up in {@code out} but is not a real
     * function simply does not make it into the {@code .def} file.</p>
     */
    private static void scanFnLiterals(Path cFile, Set<String> out) {
        String content;
        try {
            content = Files.readString(cFile);
        } catch (IOException e) {
            return;
        }
        content = stripCComments(content);

        final int n = content.length();
        int i = 0;
        while ((i = content.indexOf('"', i)) >= 0) {
            int after = i + 1;
            String prefix = null;
            if (content.startsWith("fn_", after)) {
                prefix = "fn_";
            } else if (content.startsWith("__jnative_fn_", after)) {
                prefix = "__jnative_fn_";
            }
            if (prefix == null) {
                i = after;
                continue;
            }
            int j = after;
            while (j < n) {
                char c = content.charAt(j);
                if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_') {
                    j++;
                } else {
                    break;
                }
            }
            if (j > after && j < n && content.charAt(j) == '"') {
                out.add(content.substring(after, j));
            }
            i = j > after ? j : after;
        }
    }

    /**
     * Removes C and C++ comments from {@code src} while leaving every string
     * and character literal intact. A string literal that happens to contain
     * {@code //} or {@code /*} must not be treated as a comment, which is why
     * the scan enters string and character literals before it looks at the
     * first two characters of the current position.
     */
    private static String stripCComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        final int n = src.length();
        int i = 0;
        while (i < n) {
            char quote = src.charAt(i);
            if (quote == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (quote == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            if (quote == '"' || quote == '\'') {
                out.append(src.charAt(i++));
                while (i < n) {
                    char ch = src.charAt(i);
                    out.append(ch);
                    i++;
                    if (ch == '\\' && i < n) {
                        out.append(src.charAt(i++));
                    } else if (ch == quote) {
                        break;
                    }
                }
                continue;
            }
            out.append(quote);
            i++;
        }
        return out.toString();
    }

    // =====================================================================
    //  Linker flags
    // =====================================================================

    private static void appendLinkerLibraries(List<String> linkCmd,
                                              TargetPlatform target,
                                              Path defFile) {
        if (target.isWindows()
                && target.triple().toLowerCase(Locale.ROOT).contains("-msvc")) {
            linkCmd.add("-Wl,/subsystem:console");
            if (defFile != null) {
                linkCmd.add("-Wl,/def:" + defFile.toAbsolutePath());
            }
            linkCmd.add("-Wl,/opt:ref");
            linkCmd.add("-ldbghelp");
            linkCmd.add("-lws2_32");
            linkCmd.add("-liphlpapi");
            linkCmd.add("-lpsapi");
            linkCmd.add("-ladvapi32");
            return;
        }

        if (target.isWindows()) {
            linkCmd.add("-mconsole");
            linkCmd.add("-Wl,--subsystem,console");
            linkCmd.add("-static-libgcc");
            if (defFile != null) {
                linkCmd.add(defFile.toString());
            }
            linkCmd.add("-Wl,--gc-sections");
            linkCmd.add("-lm");
            linkCmd.add("-Wl,-Bstatic");
            linkCmd.add("-lwinpthread");
            linkCmd.add("-Wl,-Bdynamic");
            linkCmd.add("-ldbghelp");
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
