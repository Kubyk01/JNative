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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <h2>Parallelism</h2>
 *
 * <p>Two parallel phases are involved:</p>
 *
 * <ol>
 *   <li>Every C source in the build — {@code jnative_runtime.c} and each
 *       per-class native file — is compiled into an LLVM bitcode object
 *       in parallel by an independent compiler process. The number of
 *       concurrent compiler processes is bounded by the caller-supplied
 *       {@code cores} value.</li>
 *
 *   <li>The heavy {@code -O3} optimization runs at link time via
 *       ThinLTO. Because the whole module is a single LLVM translation
 *       unit, {@code clang -O3 file.ll} cannot parallelize on its own;
 *       {@code -flto=thin -Wl,--thinlto-jobs=N} hands the optimization
 *       to the ThinLTO backend, which splits the module into partitions
 *       and optimizes them across {@code N} worker threads.</li>
 * </ol>
 *
 * <p>The generated {@code .ll} is emitted to bitcode with {@code -O2}
 * (fast, single-threaded); the expensive passes run only during the
 * link step, where they are parallelized. This two-stage arrangement is
 * what turns a single-threaded {@code clang -O3} invocation into a
 * fully parallel build.</p>
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

    @Override
    public void compileAndLink(Path llPath,
                               Path exePath,
                               Set<String> usedClasses,
                               Module module,
                               DependencyResolver resolver,
                               int cores)
        throws IOException, InterruptedException {

        // The caller (Orchestrator) is the single source of truth for the
        // core count. A non-positive value here means the caller did not
        // resolve one, which is a programming error; default to a single
        // thread rather than calling availableProcessors() again and
        // reintroducing a second decision point.
        final int effectiveCores = (cores <= 0) ? 1 : cores;

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
            // 3. Build the (source → object) work list. Every object is
            //    bitcode, ready for the ThinLTO link step.
            // ------------------------------------------------------------------
            Map<Path, Path> srcToObj = new LinkedHashMap<>();
            srcToObj.put(runtimeSource, tempDir.resolve("jnative_runtime.o"));
            for (Path src : extraSources) {
                Path obj = src.getParent().resolve(
                    src.getFileName().toString().replaceAll("\\.c$", ".o"));
                srcToObj.put(src, obj);
            }

            int toCompile = srcToObj.size();
            int threadCount = Math.min(effectiveCores, toCompile);
            System.out.println("Compiling " + toCompile + " C source(s) using "
                + threadCount + " thread(s) (ThinLTO bitcode)...");

            compileAllSources(compiler, srcToObj, tempDir, threadCount);

            // ------------------------------------------------------------------
            // 4. Compile the generated LLVM IR to bitcode. -O2 is a cheap
            //    single-threaded pass that only normalizes the IR; the
            //    expensive -O3 runs at link time under ThinLTO.
            // ------------------------------------------------------------------
            Path objPath = tempDir.resolve(exePath.getFileName().toString() + ".o");
            List<String> irCommand = new ArrayList<>();
            irCommand.add(compiler);
            irCommand.add("-c");
            irCommand.add("-O2");
            irCommand.add("-flto=thin");
            irCommand.add("-g");
            irCommand.add("-fno-omit-frame-pointer");
            irCommand.add("-I" + tempDir);
            irCommand.add(llPath.toString());
            irCommand.add("-o");
            irCommand.add(objPath.toString());

            ProcessBuilder irBuilder = new ProcessBuilder(irCommand);
            irBuilder.inheritIO();
            int exit = irBuilder.start().waitFor();
            if (exit != 0) {
                throw new RuntimeException(
                    "Compilation of generated LLVM IR to bitcode failed with exit code " + exit);
            }

            // ------------------------------------------------------------------
            // 5. Link with ThinLTO. The link driver reads every bitcode
            //    object, hands the merged module to the ThinLTO backend,
            //    and the backend splits it into partitions and runs -O3
            //    over them across --thinlto-jobs worker threads.
            // ------------------------------------------------------------------
            List<String> linkCmd = new ArrayList<>();
            linkCmd.add(compiler);
            linkCmd.add("-flto=thin");
            linkCmd.add("-fuse-ld=lld");
            linkCmd.add("-O3");
            linkCmd.add("-Wl,--thinlto-jobs=" + effectiveCores);
            linkCmd.add(objPath.toString());
            for (Path obj : srcToObj.values()) {
                linkCmd.add(obj.toString());
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
            exit = linkBuilder.start().waitFor();
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

    // =========================================================================
    //  Parallel compilation
    // =========================================================================

    /**
     * Compiles every (source → object) pair using a fixed-size thread pool.
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
    private void compileAllSources(String compiler,
                                   Map<Path, Path> srcToObj,
                                   Path includeDir,
                                   int threadCount)
        throws IOException, InterruptedException {

        List<Map.Entry<Path, Path>> tasks = new ArrayList<>(srcToObj.entrySet());

        if (threadCount <= 1 || tasks.size() <= 1) {
            for (Map.Entry<Path, Path> e : tasks) {
                compileCSource(compiler, e.getKey(), e.getValue(), includeDir);
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
                    Map.Entry<Path, Path> e = tasks.get(idx);
                    try {
                        compileCSource(compiler, e.getKey(), e.getValue(), includeDir);
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
                "C compilation failed for " + failures.size()
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
    //  Compilation of a single C source
    // =========================================================================

    private void compileCSource(String compiler, Path src, Path obj, Path includeDir)
        throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.add("-c");
        command.add("-O2");
        // Emit LLVM bitcode for ThinLTO. Every translation unit (including
        // the runtime itself) participates in the same link-time
        // optimization pipeline.
        command.add("-flto=thin");
        command.add("-g");
        command.add("-fno-omit-frame-pointer");
        command.add("-I" + includeDir.toString());
        command.add(src.toString());
        command.add("-o");
        command.add(obj.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        int exit = pb.start().waitFor();
        if (exit != 0) {
            throw new RuntimeException(
                "Compilation of " + src + " failed with exit code " + exit);
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