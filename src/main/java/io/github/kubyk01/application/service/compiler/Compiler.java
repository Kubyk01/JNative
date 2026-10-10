package io.github.kubyk01.application.service.compiler;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.port.secondary.CompilerPort;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
                               int optimizationLevel)
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
                + effectiveOptLevel + ")...");

            compileAll(compiler, tasks, tempDir, threadCount, effectiveOptLevel);

            // ------------------------------------------------------------------
            // 4. Link with ThinLTO. The link driver reads every bitcode
            //    object, hands the merged module to the ThinLTO backend,
            //    and the backend splits it into partitions and runs the
            //    requested optimization level over them across
            //    --thinlto-jobs worker threads.
            // ------------------------------------------------------------------
            List<String> linkCmd = new ArrayList<>();
            linkCmd.add(compiler);
            linkCmd.add("-flto=thin");
            linkCmd.add("-fuse-ld=lld");
            linkCmd.add("-O" + effectiveOptLevel);
            linkCmd.add("-Wl,--thinlto-jobs=" + effectiveCores);
            for (CompileTask task : tasks) {
                linkCmd.add(task.obj.toString());
            }
            linkCmd.add("-o");
            linkCmd.add(exePath.toString());

            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("linux")) {
                linkCmd.add("-rdynamic");
                linkCmd.add("-lpthread");
                linkCmd.add("-ldl");
                linkCmd.add("-lm");
            } else if (os.contains("mac") || os.contains("darwin")) {
                linkCmd.add("-Wl,-export_dynamic");
                linkCmd.add("-lpthread");
                linkCmd.add("-lm");
            } else if (os.contains("win")) {
                linkCmd.add("-lpthread");
                linkCmd.add("-lm");
            } else {
                linkCmd.add("-rdynamic");
                linkCmd.add("-lpthread");
                linkCmd.add("-ldl");
                linkCmd.add("-lm");
            }

            ProcessBuilder linkBuilder = new ProcessBuilder(linkCmd);
            linkBuilder.inheritIO();
            int exit = linkBuilder.start().waitFor();
            if (exit != 0) {
                throw new RuntimeException("Linking failed with exit code " + exit);
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
                            int optimizationLevel)
        throws IOException, InterruptedException {

        if (threadCount <= 1 || tasks.size() <= 1) {
            for (CompileTask task : tasks) {
                compileOne(compiler, task, includeDir, optimizationLevel);
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
                        compileOne(compiler, task, includeDir, optimizationLevel);
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
     */
    private void compileOne(String compiler, CompileTask task, Path includeDir,
                            int optimizationLevel)
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
                "Compilation of " + task.src + " failed with exit code " + exit);
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

    private Set<String> collectReferencedNativeClasses(Module module,
                                                       DependencyResolver resolver) {
        Set<String> result = new HashSet<>();

        for (String cls : new ArrayList<>(resolver.getClassMap().keySet())) {
            if (!LlvmUtil.isSystemClassName(cls)) {
                continue;
            }
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
}