package io.github.kubyk01.application.service.compiler;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.TargetPlatform;
import io.github.kubyk01.port.secondary.CompilerPort;
import lombok.extern.slf4j.Slf4j;

import io.github.kubyk01.domain.ir.Function;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Default {@link CompilerPort} implementation.
 *
 * <p>Extracts the runtime C source, the shared runtime header, and the
 * per-class native-support C sources from the classpath, compiles
 * everything with {@code clang} (falling back to {@code gcc}), and links
 * the final executable.</p>
 *
 * <h2>Optimization level</h2>
 *
 * <p>The level passed to clang as {@code -O<level>} is user-supplied
 * through the CLI's {@code -O} option (see
 * {@link io.github.kubyk01.adapter.driving.CLI}). The same level is used
 * for both stages of the build:</p>
 *
 * <ol>
 *   <li>the single-threaded IR-to-bitcode compilation step, and</li>
 *   <li>the ThinLTO link step, where the optimizations are distributed
 *       across {@code --thinlto-jobs} worker threads.</li>
 * </ol>
 *
 * <p>The default of {@code 2} matches the previous hard-coded value.
 * The value is clamped to {@code [0, 3]} before use, so a caller that
 * passes an out-of-range value (or the interface's implicit default of
 * {@code 0} when no explicit choice was made) still gets a valid clang
 * invocation.</p>
 *
 * <h2>Parallelism</h2>
 *
 * <p>Two parallel phases are involved:</p>
 *
 * <ol>
 *   <li>Every translation unit in the build — {@code jnative_runtime.c},
 *       each per-class native file, and each per-class LLVM IR module
 *       produced by {@code LlvmGenerator} — is compiled into an LLVM
 *       bitcode object in parallel by an independent compiler process.
 *       The number of concurrent compiler processes is bounded by the
 *       caller-supplied {@code cores} value.</li>
 *
 *   <li>The heavy link-time optimization runs via ThinLTO. Because every
 *       object is ThinLTO bitcode over the same merged IR,
 *       {@code clang -flto=thin -Wl,--thinlto-jobs=N} hands the
 *       optimization to the ThinLTO backend, which splits the module into
 *       partitions and optimizes them across {@code N} worker threads.</li>
 * </ol>
 *
 * <p>The generated {@code .ll} modules are emitted to bitcode with the
 * user-supplied {@code -O<level>}; the expensive passes run only during
 * the link step, where they are parallelized. This arrangement is what
 * turns a single-threaded {@code clang} invocation into a fully parallel
 * build.</p>
 */
@Slf4j
public class Compiler implements CompilerPort {

    private static final String RUNTIME_SOURCE_RESOURCE_PATH = "jnative_runtime.c";
    private static final String RUNTIME_HEADER_RESOURCE_PATH = "jnative_runtime.h";
    private static final String JNI_BRIDGE_RESOURCE_PATH = "jnative_jni_bridge.c";
    private static final String NATIVE_BASE_PATH = "jnative/";

    /**
     * Directory-name prefix used by {@link Files#createTempDirectory} for
     * the build root. Kept as a constant so that a leftover directory from
     * an aborted run is immediately recognisable.
     */
    private static final String BUILD_ROOT_NAME = "jnative_build_";

    /**
     * Lower bound of the optimization level that clang accepts on its
     * command line. {@code -O0} disables optimization entirely.
     */
    private static final int MIN_OPT_LEVEL = 0;

    /**
     * Upper bound of the optimization level that clang accepts on its
     * command line. {@code -O3} is the highest level clang recognizes;
     * passing {@code -O4} or higher is silently treated as {@code -O3}
     * by the driver, but emitting the wrong literal on the command line
     * is still a build-log defect, so the clamp normalizes it here.
     */
    private static final int MAX_OPT_LEVEL = 3;

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

        // The caller (Orchestrator) is the single source of truth for the
        // core count. A non-positive value here means the caller did not
        // resolve one, which is a programming error; default to a single
        // thread rather than calling availableProcessors() again and
        // reintroducing a second decision point.
        final int effectiveCores = (cores <= 0) ? 1 : cores;

        // Clamp the optimization level into the range clang accepts. The
        // Orchestrator already does this, but the port is also used
        // directly from tests and from any future embedder, so the guard
        // is repeated here at the single point where the value actually
        // becomes an argument to clang.
        final int effectiveOptLevel = clampOptLevel(optimizationLevel);

        // The target platform is required: it determines the --target=
        // flag passed to clang for both compilation and linking, and
        // which linker libraries are appended to the link command. A
        // null argument is a programming error; fall back to the
        // detected host platform rather than failing, so a caller that
        // has not been updated still produces a working host build.
        final TargetPlatform target = (targetPlatform != null)
            ? targetPlatform
            : TargetPlatform.detectFromBuildHost();

        Path tempDir = Files.createTempDirectory(BUILD_ROOT_NAME);
        tempDir.toFile().deleteOnExit();

        try {
            String compiler = detectCompiler();

            // ------------------------------------------------------------------
            // 1. Extract the runtime C source and the shared header.
            // ------------------------------------------------------------------
            Path runtimeSource = extractResource(
                RUNTIME_SOURCE_RESOURCE_PATH,
                tempDir.resolve(RUNTIME_SOURCE_RESOURCE_PATH));
            extractResource(
                RUNTIME_HEADER_RESOURCE_PATH,
                tempDir.resolve(RUNTIME_HEADER_RESOURCE_PATH));

            // The JNI bridge supplies the thread-local JNIEnv* that every
            // routed JNI native takes as its first argument.
            Path jniBridgeSource = extractResource(
                JNI_BRIDGE_RESOURCE_PATH,
                tempDir.resolve(JNI_BRIDGE_RESOURCE_PATH));

            // ------------------------------------------------------------------
            // 2. Extract the per-class native support files.
            // ------------------------------------------------------------------
            Set<String> classesToCompile = new HashSet<>(usedClasses);
            classesToCompile.addAll(collectReferencedNativeClasses(module, resolver));

            List<Path> extraSources = extractSystemNativeSources(classesToCompile, tempDir);

            // ------------------------------------------------------------------
            // 3. Build the (source → object) work list. Every compiled unit —
            //    the runtime C file, each per-class native C file, and each
            //    generated .ll module — becomes an LLVM bitcode object that
            //    participates in the same single ThinLTO link step.
            // ------------------------------------------------------------------
            List<CompileTask> tasks = new ArrayList<>();

            // ------------------------------------------------------------------
            // 3a. C sources — runtime + per-class natives. They live under
            //     c/ so the object naming is predictable and never collides
            //     with the IR objects under ir/.
            // ------------------------------------------------------------------
            Path cDir = tempDir.resolve("c");
            Files.createDirectories(cDir);

            Path runtimeObj = cDir.resolve("jnative_runtime.o");
            tasks.add(new CompileTask(runtimeSource, runtimeObj, false));

            Path jniBridgeObj = cDir.resolve("jnative_jni_bridge.o");
            tasks.add(new CompileTask(jniBridgeSource, jniBridgeObj, false));

            for (Path src : extraSources) {
                // Duplicate basenames (different packages, same class name)
                // must not overwrite each other — mirror the source's path
                // under c/.
                Path rel = tempDir.relativize(src);
                Path objWithPath = cDir.resolve(
                    rel.toString().replaceAll("\\.c$", ".o"));
                Files.createDirectories(objWithPath.getParent());
                tasks.add(new CompileTask(src, objWithPath, false));
            }

            // ------------------------------------------------------------------
            // 3b. LLVM IR files (one per class + _module.ll). Object names are
            //     sanitized ($, ':', whitespace) and made unique with an index.
            // ------------------------------------------------------------------
            List<Path> llObjPaths = new ArrayList<>(llPaths.size());
            int irIndex = 0;
            for (Path ll : llPaths) {
                String base = sanitizeForObjName(ll.getFileName().toString());
                // The index guarantees uniqueness even if two classes collide
                // after sanitizing (e.g. Foo$Bar and Foo_Bar).
                Path obj = tempDir.resolve("ir")
                    .resolve(String.format("%05d_%s.o", irIndex++, base));
                tasks.add(new CompileTask(ll, obj, true));
                llObjPaths.add(obj);
            }

            int toCompile = tasks.size();
            int threadCount = Math.min(effectiveCores, toCompile);
            System.out.println("Compiling " + toCompile + " translation unit(s) using "
                + threadCount + " thread(s) (ThinLTO bitcode, -O"
                + effectiveOptLevel + ", target " + target.triple() + ")...");

            compileAll(compiler, tasks, tempDir, threadCount,
                       effectiveOptLevel, target);

            // ------------------------------------------------------------------
            // 4. Link with ThinLTO. The link driver reads every bitcode
            //    object, hands the merged module to the ThinLTO backend,
            //    and the backend splits it into partitions and runs the
            //    requested optimization level over them across
            //    --thinlto-jobs worker threads.
            //
            //    --target=<triple> is passed to the clang driver, not to
            //    the linker directly: clang uses it to select the
            //    correct linker flavour (-fuse-ld=lld resolves to
            //    ld.lld / lld-link / ld64.lld based on the target),
            //    the correct C runtime for the final link, and the
            //    correct search paths for the target's sysroot.
            // ------------------------------------------------------------------
            List<String> linkCmd = new ArrayList<>();
            linkCmd.add(compiler);
            linkCmd.add("--target=" + target.triple());
            linkCmd.add("-flto=thin");
            linkCmd.add("-fuse-ld=lld");
            linkCmd.add("-O" + effectiveOptLevel);
            linkCmd.add("-Wl,--thinlto-jobs=" + effectiveCores);
            for (CompileTask task : tasks) {
                linkCmd.add(task.obj.toString());
            }
            linkCmd.add("-o");
            linkCmd.add(exePath.toString());

            appendLinkerLibraries(linkCmd, target);

            // ------------------------------------------------------------------
            // 5. Discover and append platform native libraries. Only the
            //    shared objects that actually export a JNI symbol the
            //    module needs are linked. The .so files are not turned
            //    into bitcode: they are added to the link command as
            //    shared libraries.
            // ------------------------------------------------------------------
            List<Path> nativeLibs = collectNativeLibraries(module, resolver, target);
            for (Path lib : nativeLibs) {
                linkCmd.add("-Wl,--no-as-needed");
                linkCmd.add(lib.toString());
            }
            // Export the runtime's rpath so the .so files are found at run
            // time. $ORIGIN is the directory containing the produced
            // executable; the user can either copy the .so files next to
            // the executable or set LD_LIBRARY_PATH.
            linkCmd.add("-Wl,-rpath,$ORIGIN");
            linkCmd.add("-Wl,-rpath," + new File(".").getAbsolutePath());

            System.out.println("Linking for target " + target.triple() + "...");
            ProcessBuilder linkBuilder = new ProcessBuilder(linkCmd);
            linkBuilder.inheritIO();
            int exit = linkBuilder.start().waitFor();
            if (exit != 0) {
                throw new RuntimeException("Linking failed with exit code " + exit
                    + " (target: " + target.triple() + ")");
            }

        } finally {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // Best-effort cleanup.
                    }
                });
        }
    }

    /**
     * Normalizes a user-supplied optimization level into the range
     * clang accepts on its command line ({@code [0, 3]}).
     *
     * <p>The clamp is deliberately silent: passing an out-of-range
     * value to the CLI is not an error condition worth aborting the
     * build for, because the resulting behaviour is well defined
     * (anything below {@code 0} becomes {@code -O0}, anything above
     * {@code 3} becomes {@code -O3}). The compiler driver handles the
     * same inputs the same way.</p>
     */
    private static int clampOptLevel(int level) {
        if (level < MIN_OPT_LEVEL) return MIN_OPT_LEVEL;
        return Math.min(level, MAX_OPT_LEVEL);
    }

    // =========================================================================
    //  Parallel compilation
    // =========================================================================

    /**
     * One compilation unit in the build: either a C source of the runtime /
     * per-class native files, or a generated LLVM IR module.
     */
    private static final class CompileTask {

        private final Path src;
        private final Path obj;
        private final boolean isIr;

        CompileTask(Path src, Path obj, boolean isIr) {
            this.src = src;
            this.obj = obj;
            this.isIr = isIr;
        }
    }

    /**
     * Returns a filesystem-safe form of a Java class-file basename for use
     * as the base of an object-file name.
     *
     * <p>Replaces every character outside {@code [A-Za-z0-9._-]} with
     * {@code '_'}. This is enough to cover {@code $} in nested-class names,
     * the {@code :} that appears in some Windows-style internal names, and
     * any stray whitespace that the class-file format technically
     * permits.</p>
     */
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

    /**
     * Compiles every task using a fixed-size thread pool.
     *
     * <p>Work is distributed through a shared atomic counter rather than a
     * pre-partitioned queue: a slow translation unit (say, a per-class
     * native file that pulls in heavy system headers) does not leave a
     * worker idle while other sources sit unassigned. Each worker grabs
     * the next free index, compiles it, and moves on.</p>
     *
     * <p>When a compilation fails, the failure is propagated as soon as
     * {@link Future#get()} is called on that worker's future. The
     * remaining futures are still drained, so that no compiler process is
     * left running after the method returns, and the original exception
     * is rethrown wrapped in a {@link RuntimeException}.</p>
     */
    private void compileAll(String compiler,
                            List<CompileTask> tasks,
                            Path includeDir,
                            int threadCount,
                            int optimizationLevel,
                            TargetPlatform target)
        throws IOException, InterruptedException {

        if (threadCount <= 1 || tasks.size() <= 1) {
            for (CompileTask task : tasks) {
                compileOne(compiler, task, includeDir, optimizationLevel, target);
            }
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "jnative-cc");
            t.setDaemon(true);
            return t;
        });

        final AtomicInteger nextTask = new AtomicInteger(0);
        final List<Throwable> failures =
            java.util.Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                int idx;
                while ((idx = nextTask.getAndIncrement()) < tasks.size()) {
                    CompileTask task = tasks.get(idx);
                    try {
                        compileOne(compiler, task, includeDir,
                                   optimizationLevel, target);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }
            }));
        }

        pool.shutdown();

        try {
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ee) {
                    failures.add(ee.getCause() != null ? ee.getCause() : ee);
                }
            }
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
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

    // =========================================================================
    //  Compiler detection
    // =========================================================================

    private String detectCompiler() {
        if (isAvailable("clang")) {
            return "clang";
        }
        if (isAvailable("gcc")) {
            return "gcc";
        }
        throw new IllegalStateException(
            "Neither clang nor gcc is installed. At least one is required "
                + "to compile the generated LLVM IR and the runtime C sources.");
    }

    private boolean isAvailable(String compiler) {
        try {
            Process p = new ProcessBuilder(compiler, "--version")
                .redirectErrorStream(true)
                .start();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // =========================================================================
    //  Compilation of a single unit
    // =========================================================================

    /**
     * Compiles one task into an LLVM bitcode object. C sources are passed
     * with {@code -x c}; generated LLVM IR modules with {@code -x ir}.
     * Every object participates in the same ThinLTO link step.
     *
     * <p>The {@code --target=<triple>} flag is passed to the clang
     * driver for every translation unit — C and IR alike. For a C
     * source it selects the target ABI and the corresponding header
     * search paths (which must be present in the build host's sysroot
     * for a true cross-compile). For an IR module it tells clang which
     * target's validation rules and machine-code backend to apply when
     * it lowers the module into ThinLTO bitcode.</p>
     *
     * <p>Without this flag clang would silently target its own host
     * even when the IR header declares a different triple, producing
     * either a warning-less mis-compile or a delayed error at link
     * time. Passing it explicitly makes the target an intentional,
     * visible property of the build.</p>
     */
    private void compileOne(String compiler, CompileTask task, Path includeDir,
                            int optimizationLevel, TargetPlatform target)
        throws IOException, InterruptedException {

        // clang does not create intermediate directories for -o. The object
        // path of a per-class IR file mirrors the class's package path, so
        // the directory must be created explicitly — otherwise the build
        // fails with "No such file or directory".
        Path parent = task.obj.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.add("--target=" + target.triple());
        command.add("-c");
        command.add("-x");
        command.add(task.isIr ? "ir" : "c");
        command.add("-O" + optimizationLevel);
        // Emit LLVM bitcode for ThinLTO. Every translation unit (including
        // the runtime itself) participates in the same link-time
        // optimization pipeline.
        command.add("-flto=thin");
        command.add("-g");
        command.add("-fno-omit-frame-pointer");

        // -I only makes sense for C sources. For the IR frontend clang
        // prints "argument unused during compilation" for every file, which
        // turns the log of 3000+ files into noise.
        if (!task.isIr) {
            command.add("-I" + includeDir.toString());
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

    // =========================================================================
    //  Resource extraction
    // =========================================================================

    private String getNativeResourcePath(String className) {
        if (className.startsWith("java/")) {
            return NATIVE_BASE_PATH + className.substring("java/".length()) + ".c";
        }
        return NATIVE_BASE_PATH + className + ".c";
    }

    @Override
    public boolean hasNativeSupport(String className) {
        String path = getNativeResourcePath(className);
        try (InputStream is = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(path)) {
            return is != null;
        } catch (IOException e) {
            return false;
        }
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

        for (String cls : usedClasses) {
            if (!hasNativeSupport(cls)) {
                continue;
            }

            String resourcePath = getNativeResourcePath(cls);
            Path targetPath = tempDir.resolve(
                cls.replace('/', java.io.File.separatorChar) + ".c");

            try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
                if (in == null) {
                    log.warn("Native resource {} disappeared between check and extraction",
                        resourcePath);
                    continue;
                }
                if (targetPath.getParent() != null) {
                    Files.createDirectories(targetPath.getParent());
                }
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                extracted.add(targetPath);
            }
        }

        return extracted;
    }

    // =========================================================================
    //  Native-class discovery
    // =========================================================================


    /**
     * Appends the linker libraries and export flags appropriate for the
     * target platform to {@code linkCmd}.
     *
     * <p>The selection is driven by the target triple, not by the host
     * {@code os.name} property. The two agree for a native build and
     * differ for every cross-compile; keying on the host would make a
     * Linux-to-FreeBSD cross-compile request {@code -ldl} from the
     * FreeBSD sysroot, where that library does not exist under that
     * name.</p>
     *
     * <p>The set of libraries is deliberately minimal — only the ones
     * the runtime's C sources actually reference. Adding a library that
     * the target does not ship is a link-time error, not a silent
     * fallback, so an under-specified list is easier to diagnose than
     * an over-specified one.</p>
     *
     * <ul>
     *   <li><b>Windows</b> ({@code *-windows-*}): no {@code -rdynamic}
     *       (the MSVC linker has no equivalent and interprets it as an
     *       unknown flag), no {@code -lpthread} (Win32 threads are in
     *       the CRT), no {@code -ldl} (no dynamic loader library). The
     *       MinGW target still needs {@code -lm} because its C library
     *       splits the math functions out of libc.</li>
     *
     *   <li><b>Darwin/macOS</b> ({@code *-apple-*}): the equivalent of
     *       {@code -rdynamic} is {@code -Wl,-export_dynamic};
     *       {@code -lpthread} is a system stub; there is no
     *       {@code -ldl} (dlopen lives in libSystem).</li>
     *
     *   <li><b>BSD</b> ({@code *-freebsd}, {@code *-netbsd},
     *       {@code *-openbsd}): {@code -rdynamic} is accepted;
     *       {@code -lpthread} is a stub in libc on newer versions but
     *       linking it explicitly is harmless; {@code -ldl} does not
     *       exist on the BSDs and is omitted.</li>
     *
     *   <li><b>Linux and other ELF Unix</b>: the full set —
     *       {@code -rdynamic -lpthread -ldl -lm} — which is what every
     *       previous revision of this class has appended
     *       unconditionally.</li>
     * </ul>
     */
    private static void appendLinkerLibraries(List<String> linkCmd,
                                              TargetPlatform target) {
        String triple = target.triple().toLowerCase(Locale.ROOT);

        if (triple.contains("windows")) {
            linkCmd.add("-lm");
            return;
        }
        if (triple.contains("darwin")
            || triple.contains("apple")
            || triple.contains("mac")) {
            linkCmd.add("-Wl,-export_dynamic");
            linkCmd.add("-lpthread");
            linkCmd.add("-lm");
            return;
        }
        if (triple.contains("freebsd")
            || triple.contains("netbsd")
            || triple.contains("openbsd")) {
            linkCmd.add("-rdynamic");
            linkCmd.add("-lpthread");
            linkCmd.add("-lm");
            return;
        }

        // Linux and every other ELF-based Unix target.
        linkCmd.add("-rdynamic");
        linkCmd.add("-lpthread");
        linkCmd.add("-ldl");
        linkCmd.add("-lm");
    }

    // =========================================================================
    //  Platform native library discovery
    // =========================================================================

    @Override
    public List<Path> collectNativeLibraries(Module module,
                                             DependencyResolver resolver,
                                             TargetPlatform targetPlatform)
        throws IOException {

        // 1. The set of JNI symbols the module actually calls.
        Set<String> wanted = new HashSet<>();
        for (Function f : module.getFunctions()) {
            if (f.isJniNative()) wanted.add(f.getName());
        }
        if (wanted.isEmpty()) return List.of();

        // 2. Candidate directories and archives.
        List<Path> candidates = new ArrayList<>();

        // 2a. JDK platform libraries.
        Path libDir = platformLibraryDir(targetPlatform);
        if (libDir != null && Files.isDirectory(libDir)) {
            try (var s = Files.walk(libDir, 2)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> isDynamicLibrary(p, targetPlatform))
                 .forEach(candidates::add);
            }
        }

        // 2b. Native libraries bundled inside the analysed input archive,
        //     including recursion into nested jars (Spring Boot fat jar
        //     layout).
        //
        //     The extraction directory must outlive the whole build, so it is
        //     created separately from the main build directory and its removal
        //     is deferred to JVM exit (deleteOnExit). This matters: even after
        //     compileAndLink() returns the extracted .so files stay valid and
        //     can be reused by later runs.
        Path extractDir = Files.createTempDirectory("jnative-natives-");
        extractDir.toFile().deleteOnExit();

        Path inputArchive = resolver.getInputArchive();
        if (inputArchive != null) {
            scanInputArchiveForNatives(inputArchive, targetPlatform,
                                       extractDir, candidates);
        }

        // 2c. Native libraries bundled inside the jar files on the tool's own
        //     classpath. Usually these are extra plugins rather than the
        //     application's dependencies, but scanning costs almost nothing
        //     and covers the case where the build system explicitly put a
        //     .so-jar on the tool classpath.
        scanClasspathJarsForNatives(targetPlatform, extractDir, candidates);

        // 3. Filter by exported symbols.
        Set<String> stillWanted = new HashSet<>(wanted);
        List<Path> result = new ArrayList<>();
        Collections.sort(candidates);

        for (Path lib : candidates) {
            if (stillWanted.isEmpty()) break;
            Set<String> exported = readExportedSymbols(lib);
            Set<String> overlap = new HashSet<>(exported);
            overlap.retainAll(stillWanted);
            if (overlap.isEmpty()) continue;
            result.add(lib);
            stillWanted.removeAll(overlap);
            log.info("Native library {} provides {} required JNI symbol(s)",
                     lib, overlap.size());
        }

        if (!stillWanted.isEmpty()) {
            log.warn("{} JNI symbol(s) still unresolved after scanning {} candidate "
                    + "libraries. First 5: {}", stillWanted.size(), candidates.size(),
                    stillWanted.stream().limit(5).collect(Collectors.joining(", ")));
        }
        return result;
    }

    // =========================================================================
    //  Recursive scanning of the input archive
    // =========================================================================

    /**
     * Maximum recursion depth when parsing jar-in-jar.
     *
     * <p>A Spring Boot fat jar has exactly one level of nesting: the outer
     * archive contains {@code BOOT-INF/lib/*.jar} entries. A margin of 3
     * levels is left for wrapper formats that put the executable jar one
     * level deeper. The limit exists precisely so that a malicious or corrupt
     * archive cannot make the scanner recurse without bound and exhaust the
     * Java stack.</p>
     */
    private static final int MAX_NESTING_DEPTH = 4;

    /**
     * Scans the input archive for native libraries and adds each extracted
     * file to {@code out}.
     *
     * <p>The input may be a jar, a Spring Boot fat jar, a directory tree of
     * jars, or a single .class. Each case gets its own explicit branch so the
     * recursion rules stay unambiguous:</p>
     *
     * <ul>
     *   <li><b>Directory</b> — every {@code .jar} found beneath it is scanned
     *       by {@link #scanOneJarForNatives}.</li>
     *   <li><b>Jar</b> — scanned by {@link #scanOneJarForNatives}, which
     *       extracts dynamic libraries and recurses into nested jars.</li>
     *   <li><b>Single .class</b> — nothing to do; a .class cannot contain a
     *       native library.</li>
     * </ul>
     */
    private void scanInputArchiveForNatives(Path inputArchive,
                                            TargetPlatform target,
                                            Path extractDir,
                                            List<Path> out) throws IOException {
        if (Files.isDirectory(inputArchive)) {
            try (var stream = Files.walk(inputArchive)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> p.getFileName().toString()
                                    .toLowerCase(Locale.ROOT)
                                    .endsWith(".jar"))
                      .forEach(jar -> {
                          try {
                              scanOneJarForNatives(jar, target,
                                                   extractDir, out, 0);
                          } catch (IOException e) {
                              log.debug("Skipping JAR {}: {}",
                                        jar, e.getMessage());
                          }
                      });
            }
            return;
        }

        if (!Files.isRegularFile(inputArchive)) {
            return;
        }

        String name = inputArchive.getFileName().toString()
                                 .toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar")) {
            scanOneJarForNatives(inputArchive, target, extractDir, out, 0);
        }
        // A .class input cannot contain a native library.
    }

    /**
     * Scans a single jar (or nested jar) for native libraries and recurses
     * into every nested jar.
     *
     * <p>Scanning is two-pass. In the first pass the outer jar's entry list is
     * walked in full: each dynamic library is extracted to disk immediately,
     * and each nested {@code .jar} entry is remembered for the second pass.
     * The two-pass structure is mandatory because opening a nested jar consumes
     * the outer jar entry's input stream (the entry stream must be drained
     * before the next entry can be read), and interleaving the two kinds of
     * processing would make the traversal order non-deterministic.</p>
     *
     * <p>In the second pass each remembered nested jar is materialised to a
     * temp file, recursively scanned by this same method, and deleted.
     * Materialisation is preferred over in-memory scanning because a Spring
     * Boot fat jar normally stores nested jars in STORED (uncompressed) mode,
     * and {@link java.util.zip.ZipInputStream} does not expose the offset that
     * would let the nested archive be opened without copying. The temp file is
     * deleted in a finally block, so a mid-scan failure leaves nothing
     * behind.</p>
     *
     * <p>Nesting depth is bounded by {@link #MAX_NESTING_DEPTH}; a deeper
     * structure is scanned to that depth and the remainder is skipped with a
     * debug message. A Spring Boot fat jar uses exactly one level of nesting,
     * so the limit is never reached in practice.</p>
     */
    private void scanOneJarForNatives(Path jarPath,
                                      TargetPlatform target,
                                      Path extractDir,
                                      List<Path> out,
                                      int depth) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            log.debug("Jar nesting depth {} exceeded for {}; "
                    + "not recursing further", depth, jarPath);
            return;
        }

        String suffix = dynamicLibrarySuffix(target);

        try (JarFile jf = new JarFile(jarPath.toFile())) {
            List<JarEntry> nestedJars = new ArrayList<>();
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String en = e.getName();

                if (en.endsWith(suffix)
                    && (en.contains("native/") || en.contains("natives/"))) {
                    String base = en.substring(en.lastIndexOf('/') + 1);
                    Path dest = uniqueExtractPath(extractDir, base);
                    try (InputStream is = jf.getInputStream(e)) {
                        Files.copy(is, dest,
                                   StandardCopyOption.REPLACE_EXISTING);
                        out.add(dest);
                        log.info("Extracted native library {} from {}!/{}",
                                 base, jarPath.getFileName(), en);
                    }
                    continue;
                }

                if (en.endsWith(".jar")) {
                    nestedJars.add(e);
                }
            }

            for (JarEntry nested : nestedJars) {
                Path nestedJarPath = null;
                try {
                    Path tmp = Files.createTempFile(extractDir,
                                                    "nested-", ".jar");
                    try (InputStream is = jf.getInputStream(nested)) {
                        Files.copy(is, tmp,
                                   StandardCopyOption.REPLACE_EXISTING);
                    }
                    nestedJarPath = tmp;
                    scanOneJarForNatives(tmp, target, extractDir, out,
                                         depth + 1);
                } catch (IOException e) {
                    log.debug("Failed to scan nested jar {}!/{}: {}",
                              jarPath.getFileName(), nested.getName(),
                              e.getMessage());
                } finally {
                    if (nestedJarPath != null) {
                        try {
                            Files.deleteIfExists(nestedJarPath);
                        } catch (IOException ignored) {
                            // Best-effort cleanup. A leftover file will be
                            // deleted on JVM exit via the parent extractDir's
                            // deleteOnExit.
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the dynamic-library filename suffix for the given target
     * platform: {@code .dll} on Windows, {@code .dylib} on macOS, {@code .so}
     * everywhere else.
     */
    private static String dynamicLibrarySuffix(TargetPlatform t) {
        String triple = t.triple().toLowerCase(Locale.ROOT);
        if (triple.contains("windows")) return ".dll";
        if (triple.contains("darwin") || triple.contains("apple")) return ".dylib";
        return ".so";
    }

    /**
     * Returns a path inside {@code extractDir} with the name {@code base},
     * decorated with a numeric suffix if necessary to avoid collisions.
     * Different jar files may carry native libraries with the same base name
     * (for example two versions of the same dependency inside a fat jar), and
     * the link command must be able to reference each one by a distinct path.
     */
    private static Path uniqueExtractPath(Path extractDir, String base) {
        Path candidate = extractDir.resolve(base);
        if (!Files.exists(candidate)) return candidate;

        int dot = base.lastIndexOf('.');
        String stem = (dot > 0) ? base.substring(0, dot) : base;
        String ext  = (dot > 0) ? base.substring(dot) : "";

        int i = 1;
        while (Files.exists(candidate)) {
            candidate = extractDir.resolve(stem + "_" + i + ext);
            i++;
        }
        return candidate;
    }

    /**
     * Walks the JVM classpath, opens every jar, and recursively scans it via
     * {@link #scanOneJarForNatives}, extracting native libraries into
     * {@code extractDir}.
     *
     * <p>This method used to do a flat scan of the top-level jar. It now
     * delegates to the recursive implementation so that the same jar-in-jar
     * handling applies both to the application and to the tool's classpath.
     * Behaviour for jars without nested archives is unchanged.</p>
     */
    private void scanClasspathJarsForNatives(TargetPlatform target,
                                             Path extractDir,
                                             List<Path> out) {
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
            try {
                scanOneJarForNatives(jar, target, extractDir, out, 0);
            } catch (IOException e) {
                log.debug("Skipping JAR {}: {}", jar, e.getMessage());
            }
        }
    }

    private static boolean isDynamicLibrary(Path p, TargetPlatform t) {
        String n = p.getFileName().toString();
        String triple = t.triple().toLowerCase(Locale.ROOT);
        if (triple.contains("windows")) return n.endsWith(".dll");
        if (triple.contains("darwin") || triple.contains("apple")) {
            return n.endsWith(".dylib");
        }
        return n.endsWith(".so");
    }

    private static Path platformLibraryDir(TargetPlatform t) {
        String home = System.getProperty("java.home");
        if (home == null) return null;
        Path base = Paths.get(home);
        String triple = t.triple().toLowerCase(Locale.ROOT);
        if (triple.contains("windows")) return base.resolve("bin");
        return base.resolve("lib");
    }

    /**
     * Read the dynamic-symbol export list of {@code lib} by parsing the
     * ELF export table directly. Avoids spawning {@code nm} for every
     * file, which on JDK 21 means hundreds of subprocesses.
     *
     * <p>Falls back to {@code nm -D --defined-only} only if the parse
     * fails, so a system without a working parser still functions.</p>
     */
    private static Set<String> readExportedSymbols(Path lib) {
        try {
            return ElfSymbolReader.read(lib);
        } catch (Throwable t) {
            return readExportedSymbolsViaNm(lib);
        }
    }

    private static Set<String> readExportedSymbolsViaNm(Path lib) {
        try {
            Process p = new ProcessBuilder("nm", "-D", "--defined-only",
                lib.toString())
                .redirectErrorStream(true)
                .start();
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

    private Set<String> collectReferencedNativeClasses(Module module,
                                                       DependencyResolver resolver) {
        Set<String> result = new HashSet<>();

        for (String cls : new ArrayList<>(resolver.getClassMap().keySet())) {
            if (!hasNativeSupport(cls)) {
                continue;
            }
            ClassNode cn = resolver.getClassNode(cls);
            if (cn == null || cn.isExternal()) {
                continue;
            }
            for (MethodNode mn : cn.getMethods()) {
                if (!mn.isNative()) {
                    continue;
                }
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

    /**
     * Discovers every native library the input archive contributes and
     * returns both the resource keys under which the runtime will load
     * them and the (key, bytes) pairs the LLVM emitter must embed.
     *
     * <p>The method is idempotent and free of hidden state: it opens
     * the input archive, extracts every {@code .so} / {@code .dylib} /
     * {@code .dll} whose path contains {@code native/} or
     * {@code natives/}, and returns the result. No field of this
     * compiler instance is written; two invocations with the same
     * arguments produce two equivalent records.</p>
     *
     * <p>Extraction uses the same recursive scan as
     * {@link #collectNativeLibraries}, so a Netty {@code .so} inside a
     * nested {@code BOOT-INF/lib/*.jar} of a Spring Boot fat jar is
     * found by both code paths through exactly one implementation of
     * the traversal.</p>
     */
    @Override
    public EmbeddedNativeLibraries embedNativeLibraries(
            Module module,
            DependencyResolver resolver,
            TargetPlatform targetPlatform)
        throws IOException {

        Path extractDir = Files.createTempDirectory("jnative-native-bytes-");
        extractDir.toFile().deleteOnExit();

        List<Path> candidates = new ArrayList<>();
        Path inputArchive = resolver.getInputArchive();
        if (inputArchive != null) {
            scanInputArchiveForNatives(inputArchive, targetPlatform,
                                       extractDir, candidates);
        }

        List<String> keys = new ArrayList<>(candidates.size());
        List<Map.Entry<String, byte[]>> resources =
            new ArrayList<>(candidates.size());

        for (Path lib : candidates) {
            try {
                byte[] bytes = Files.readAllBytes(lib);
                String base = lib.getFileName().toString();
                String key = "jnative-natives/" + base;
                keys.add(key);
                resources.add(new AbstractMap.SimpleImmutableEntry<>(key, bytes));
            } catch (IOException e) {
                log.warn("Failed to read native library {}: {}",
                         lib, e.getMessage());
            }
        }

        return new EmbeddedNativeLibraries(
            Collections.unmodifiableList(keys),
            Collections.unmodifiableList(resources));
    }
}