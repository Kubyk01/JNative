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
import java.util.LinkedHashMap;
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
 * <h2>Self-contained executables</h2>
 *
 * <p>The produced binary does <b>not</b> depend on an installed JDK, on
 * {@code JAVA_HOME}, on {@code LD_LIBRARY_PATH}, or on the presence of
 * any external {@code .so} at run time. Every native library the compiled
 * image can call into — the JDK's own {@code libawt.so},
 * {@code libjava.so}, {@code libnio.so}, {@code libnetty_transport_*.so}
 * and so on — is read as bytes, embedded into the executable, written to
 * a private directory at program start, {@code dlopen}'d with
 * {@code RTLD_GLOBAL}, and (for libraries that ship a {@code JNI_OnLoad})
 * initialised before any Java code runs.</p>
 *
 * <p>The link line therefore contains no {@code -Wl,--no-as-needed} for
 * JDK libraries. The previous approach — passing
 * {@code $JAVA_HOME/lib/libawt.so} to the linker — recorded only the
 * library's SONAME ({@code "libawt.so"}) in {@code DT_NEEDED}, forcing
 * the dynamic loader to find that file again at run time through
 * {@code RUNPATH}/{@code LD_LIBRARY_PATH}, which fails on any machine
 * that does not carry the exact JDK the binary was built against.</p>
 *
 * <h2>Transitive closure of JDK libraries</h2>
 *
 * <p>Selecting JDK libraries only by the JNI symbols the module
 * references is not sufficient for a truly self-contained image. A
 * library that exports a needed symbol is itself a dynamic object: its
 * ELF {@code .dynamic} section names its own dependencies by SONAME
 * ({@code libjvm.so}, {@code libjava.so}, {@code libawt_headless.so},
 * {@code libfreetype.so}, …), and the loader refuses to {@code dlopen}
 * it unless every one of those SONAMEs can be satisfied. The embed step
 * therefore walks the {@code DT_NEEDED} graph from the initial
 * symbol-filtered set until it reaches a fixed point, and embeds every
 * transitively required library that lives under {@code $JAVA_HOME}.
 * Dependencies on system libraries ({@code libc}, {@code libm},
 * {@code libX11}, {@code libz}, …) are deliberately not embedded: the
 * target machine has its own copies, and shadowing them with the build
 * host's binaries would break on any system whose ABI differs.</p>
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
 * passes an out-of-range value still gets a valid clang invocation.</p>
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
     * Prefix used for the extraction directory that holds the native
     * library bytes during the build. Kept separate from the main build
     * root so that its lifetime can be managed independently: the main
     * build root is deleted when {@code compileAndLink} returns, while
     * the extracted natives must survive at least until the LLVM
     * generator has read them.
     */
    private static final String NATIVE_BYTES_DIR_NAME = "jnative-native-bytes-";

    /**
     * Prefix used for the extraction directory that holds native library
     * files which are candidates for linking (input archive and tool
     * classpath).
     */
    private static final String NATIVE_LINK_DIR_NAME = "jnative-natives-";

    /**
     * Resource-key prefixes under which the two kinds of native library
     * are registered in the executable's built-in resource table.
     *
     * <p>Both strings are opaque to the C side: they are simply keys
     * looked up in {@code @jnative_builtin_resources}. Choosing distinct,
     * non-colliding prefixes keeps the two categories visually separable
     * in a diagnostic dump of that table.</p>
     */
    private static final String KEY_PREFIX_JDK_LIBS = "jnative-jdk-libs/";
    private static final String KEY_PREFIX_BUNDLED_NATIVES = "jnative-natives/";

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

    // =========================================================================
    //  compileAndLink
    // =========================================================================

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
        // which linker libraries are appended to the link command.
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

            Path cDir = tempDir.resolve("c");
            Files.createDirectories(cDir);

            Path runtimeObj = cDir.resolve("jnative_runtime.o");
            tasks.add(new CompileTask(runtimeSource, runtimeObj, false));

            Path jniBridgeObj = cDir.resolve("jnative_jni_bridge.o");
            tasks.add(new CompileTask(jniBridgeSource, jniBridgeObj, false));

            for (Path src : extraSources) {
                Path rel = tempDir.relativize(src);
                Path objWithPath = cDir.resolve(
                    rel.toString().replaceAll("\\.c$", ".o"));
                Files.createDirectories(objWithPath.getParent());
                tasks.add(new CompileTask(src, objWithPath, false));
            }

            List<Path> llObjPaths = new ArrayList<>(llPaths.size());
            int irIndex = 0;
            for (Path ll : llPaths) {
                String base = sanitizeForObjName(ll.getFileName().toString());
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
            // 4. Link with ThinLTO.
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
            // 5. Discover and append the native libraries the input archive
            //    itself contributes (Spring Boot fat jar, shaded jar, etc.).
            //
            //    These are the ONLY libraries that end up on the link line.
            //    JDK platform libraries are no longer linked here: they are
            //    embedded into the executable and dlopen'd at run time. See
            //    embedNativeLibraries() for the rationale.
            //
            //    A library placed on the link line by absolute path records
            //    only its SONAME in DT_NEEDED, which is exactly the
            //    portability bug this fix removes. Placing an application
            //    library on the link line is safe in that respect because
            //    the library is copied next to the executable by the
            //    extractor and found through $ORIGIN.
            // ------------------------------------------------------------------
            List<Path> bundledNativeLibs = collectNativeLibraries(module, resolver, target);
            for (Path lib : bundledNativeLibs) {
                linkCmd.add("-Wl,--no-as-needed");
                linkCmd.add(lib.toString());
            }

            // $ORIGIN is the directory containing the produced executable;
            // the user can either copy the application .so files next to
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

    // =========================================================================
    //  Compilation helpers
    // =========================================================================

    /**
     * Normalizes a user-supplied optimization level into the range
     * clang accepts on its command line ({@code [0, 3]}).
     */
    private static int clampOptLevel(int level) {
        if (level < MIN_OPT_LEVEL) return MIN_OPT_LEVEL;
        return Math.min(level, MAX_OPT_LEVEL);
    }

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
    //  Single-unit compilation
    // =========================================================================

    private void compileOne(String compiler, CompileTask task, Path includeDir,
                            int optimizationLevel, TargetPlatform target)
        throws IOException, InterruptedException {

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
        command.add("-flto=thin");
        command.add("-g");
        command.add("-fno-omit-frame-pointer");

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
     * differ for every cross-compile.</p>
     *
     * <p>The set of libraries is deliberately minimal — only the ones
     * the runtime's C sources actually reference.</p>
     *
     * <ul>
     *   <li><b>Windows</b> ({@code *-windows-*}): no {@code -rdynamic},
     *       no {@code -lpthread}, no {@code -ldl}; only {@code -lm}.</li>
     *   <li><b>Darwin/macOS</b> ({@code *-apple-*}): the equivalent of
     *       {@code -rdynamic} is {@code -Wl,-export_dynamic};
     *       {@code -lpthread} is a system stub; no {@code -ldl}.</li>
     *   <li><b>BSD</b> ({@code *-freebsd}, {@code *-netbsd},
     *       {@code *-openbsd}): {@code -rdynamic -lpthread -lm}.</li>
     *   <li><b>Linux and other ELF Unix</b>: the full set —
     *       {@code -rdynamic -lpthread -ldl -lm}.</li>
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

        linkCmd.add("-rdynamic");
        linkCmd.add("-lpthread");
        linkCmd.add("-ldl");
        linkCmd.add("-lm");
    }

    // =========================================================================
    //  Native library discovery — LINK-TIME candidates
    // =========================================================================

    /**
     * Discovers native libraries bundled inside the analysed application
     * itself (the input archive and the tool's own classpath) that export
     * at least one {@code Java_*} symbol the compiled module needs, and
     * returns them for the LINK LINE.
     *
     * <h2>What is deliberately not scanned here</h2>
     *
     * <p>JDK platform libraries — anything under {@code $JAVA_HOME/lib}
     * or {@code $JAVA_HOME/lib/server} — are NOT candidates for the link
     * line. Linking them by absolute path records only their SONAME in
     * {@code DT_NEEDED}, forcing the dynamic loader to find the same file
     * again at run time through {@code RUNPATH}/{@code LD_LIBRARY_PATH}.
     * That is the exact portability defect this fix removes.</p>
     *
     * <p>Those libraries are instead read as bytes, embedded into the
     * executable, extracted to a private directory at program start, and
     * {@code dlopen}'d with {@code RTLD_GLOBAL} before any Java code
     * runs. See {@link #embedNativeLibraries(Module, DependencyResolver,
     * TargetPlatform)} for the implementation and the resource-key
     * convention.</p>
     *
     * <p>Application libraries bundled inside the input archive (a Spring
     * Boot fat jar's {@code BOOT-INF/lib/*.jar} contents, a shaded jar's
     * {@code native/} directories, and so on) stay on the link line
     * because they are copied to a directory next to the executable at
     * build time and found through {@code $ORIGIN}. They are also
     * embedded as a fallback so that a build without {@code $ORIGIN}
     * still runs.</p>
     */
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

        // 2. Candidate directories and archives (application side only).
        List<Path> candidates = new ArrayList<>();

        Path extractDir = Files.createTempDirectory(NATIVE_LINK_DIR_NAME);
        extractDir.toFile().deleteOnExit();

        Path inputArchive = resolver.getInputArchive();
        if (inputArchive != null) {
            scanInputArchiveForNatives(inputArchive, targetPlatform,
                extractDir, candidates);
        }

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
            log.info("Application native library {} provides {} required JNI symbol(s)",
                lib, overlap.size());
        }

        if (!stillWanted.isEmpty()) {
            log.warn("{} JNI symbol(s) still unresolved after scanning {} application "
                    + "library(ies). First 5: {}",
                stillWanted.size(), candidates.size(),
                stillWanted.stream().limit(5).collect(Collectors.joining(", ")));
        }
        return result;
    }

    // =========================================================================
    //  Native library discovery — EMBED-TIME candidates
    // =========================================================================

    /**
     * Discovers every native library the produced executable must be
     * able to load at run time, reads its bytes, and returns them
     * together with the resource keys under which they must be
     * registered.
     *
     * <h2>Two categories</h2>
     *
     * <ol>
     *   <li><b>JDK platform libraries.</b> Starting from the set of
     *       libraries that directly export a JNI symbol the module
     *       needs, the algorithm walks the ELF {@code DT_NEEDED} graph
     *       until it reaches a fixed point and embeds every JDK
     *       library that appears anywhere in the transitive closure.
     *       The initial symbol filter is what keeps the embedded set
     *       small: a full {@code $JAVA_HOME/lib} contains a few
     *       hundred shared objects, most of them irrelevant to a given
     *       image, and only the ones that ultimately satisfy an
     *       unresolved symbol are embedded. The transitive closure on
     *       top of that initial filter is what makes the resulting
     *       image self-contained: a library that exports a needed
     *       symbol is useless on a machine without a JDK unless every
     *       library it names in {@code DT_NEEDED} travels with it.</li>
     *
     *   <li><b>Application native libraries.</b> Every native library
     *       bundled inside the input archive, plus every native library
     *       on the tool's own classpath. These are embedded
     *       <b>unconditionally</b>, without symbol filtering, because a
     *       {@code RegisterNatives}-style library (Netty's native
     *       transports among them) exports no {@code Java_*} symbols at
     *       all — it exposes a {@code JNI_OnLoad} entry point and
     *       installs its entire native surface through
     *       {@code RegisterNatives} at load time. Filtering them by
     *       exported {@code Java_*} symbols would drop exactly the
     *       libraries this runtime needs.</li>
     * </ol>
     *
     * <h2>Which dependencies are NOT embedded</h2>
     *
     * <p>The transitive closure step only follows {@code DT_NEEDED}
     * entries whose SONAME matches a file that actually lives in one of
     * the JDK library directories. Dependencies on system libraries —
     * {@code libc.so.6}, {@code libm.so.6}, {@code libpthread.so.0},
     * {@code libX11.so.6}, {@code libz.so.1} and so on — are not
     * embedded, for two reasons:</p>
     *
     * <ul>
     *   <li>The target machine has its own copies. The whole point of
     *       not depending on a JDK is to run on systems that already
     *       have a working libc, X11 stack, and zlib; shadowing those
     *       with the build host's binaries would break on any system
     *       whose ABI differs.</li>
     *
     *   <li>Embedding them would inflate the executable by hundreds of
     *       megabytes on a typical Linux distribution — the transitive
     *       closure of {@code libawt.so} alone reaches
     *       {@code libcups.so}, {@code libgssapi_krb5.so}, the entire
     *       kerberos stack, and eventually half of {@code /usr/lib}.</li>
     * </ul>
     *
     * <p>This is the same policy the reference JDK's own
     * {@code jlink} applies to system libraries: the image carries the
     * modules, and the operating system carries the C library. It is
     * also the only policy under which a portable executable exists:
     * a fully static closure of every JDK library would not even link
     * on a modern glibc distribution, because glibc itself refuses to
     * be statically linked into a dynamic executable.</p>
     *
     * <h2>How the executable consumes the result</h2>
     *
     * <p>The returned {@link EmbeddedNativeLibraries} record carries two
     * parallel views of the same set of libraries. {@code resources} is
     * passed to {@code LlvmGenerator.setEmbeddedResources}, which emits
     * each library as a byte array into the
     * {@code @jnative_builtin_resources} table. {@code keys} is passed to
     * {@code LlvmGenerator.setRuntimeJniLibraryKeys}, which makes
     * {@code @main} emit a call to {@code __jnative_jni_load_all} with
     * that key list as its argument.</p>
     *
     * <p>At program start, before any Java code runs,
     * {@code __jnative_jni_load_all} creates a private directory,
     * materialises every embedded library into it under its original
     * basename, and {@code dlopen}'s each one with
     * {@code RTLD_NOW | RTLD_GLOBAL}. Libraries are loaded in multiple
     * passes until no further progress is made, so that a library whose
     * {@code DT_NEEDED} names a dependency that appears later in the
     * key list is retried once that dependency is in the loaded set.
     * Each library's {@code JNI_OnLoad} is invoked immediately after its
     * own {@code dlopen} succeeds.</p>
     *
     * <p>The order of the keys is preserved: JDK libraries first, then
     * application natives. That order matters when an application
     * library depends on a JDK library's exported symbols — for example
     * Netty's transport depends on symbols from {@code libnio.so},
     * which is materialised and available in the private directory
     * before the transport's own {@code JNI_OnLoad} runs.</p>
     */
    @Override
    public EmbeddedNativeLibraries embedNativeLibraries(
        Module module,
        DependencyResolver resolver,
        TargetPlatform targetPlatform)
        throws IOException {

        // ------------------------------------------------------------------
        // 0. Symbols the module actually needs. Used only to decide which
        //    JDK libraries to seed the closure from; the second category
        //    (application natives) is embedded unconditionally.
        // ------------------------------------------------------------------
        Set<String> stillWanted = new HashSet<>();
        for (Function f : module.getFunctions()) {
            if (f.isJniNative()) stillWanted.add(f.getName());
        }

        Path extractDir = Files.createTempDirectory(NATIVE_BYTES_DIR_NAME);
        extractDir.toFile().deleteOnExit();

        List<String> keys = new ArrayList<>();
        List<Map.Entry<String, byte[]>> resources = new ArrayList<>();

        // ------------------------------------------------------------------
        // 1. JDK platform libraries — symbol-filtered seed, then
        //    transitive DT_NEEDED closure.
        //
        //    Both <jdk>/lib and <jdk>/lib/server are scanned. libjvm.so
        //    lives in the second, and several of the libraries in the
        //    first transitively need it.
        // ------------------------------------------------------------------
        Set<Path> jdkLibsToEmbed = computeJdkLibraryClosure(stillWanted, targetPlatform);

        for (Path lib : jdkLibsToEmbed) {
            try {
                byte[] bytes = Files.readAllBytes(lib);
                String base  = lib.getFileName().toString();
                String key   = KEY_PREFIX_JDK_LIBS + base;
                keys.add(key);
                resources.add(new AbstractMap.SimpleImmutableEntry<>(key, bytes));
                log.info("Embedded JDK library {} (transitive closure)", lib);
            } catch (IOException e) {
                log.warn("Failed to read JDK library {}: {}", lib, e.getMessage());
            }
        }

        if (!stillWanted.isEmpty()) {
            log.warn("{} JNI symbol(s) still unresolved after scanning all "
                    + "JDK library directories. First 5: {}",
                stillWanted.size(),
                stillWanted.stream().limit(5).collect(Collectors.joining(", ")));
        }

        // ------------------------------------------------------------------
        // 2. Application native libraries — embedded unconditionally.
        //
        //    These include RegisterNatives-style libraries that export no
        //    Java_* symbols and therefore could not pass the JDK filter
        //    above. They are the libraries whose JNI_OnLoad populates the
        //    dispatch table used by __jnative_jni_resolve.
        // ------------------------------------------------------------------
        List<Path> candidates = new ArrayList<>();

        Path inputArchive = resolver.getInputArchive();
        if (inputArchive != null) {
            scanInputArchiveForNatives(inputArchive, targetPlatform,
                extractDir, candidates);
        }
        scanClasspathJarsForNatives(targetPlatform, extractDir, candidates);

        for (Path lib : candidates) {
            try {
                byte[] bytes = Files.readAllBytes(lib);
                String base = lib.getFileName().toString();
                String key = KEY_PREFIX_BUNDLED_NATIVES + base;
                keys.add(key);
                resources.add(new AbstractMap.SimpleImmutableEntry<>(key, bytes));
                log.info("Embedded application native library {}", lib);
            } catch (IOException e) {
                log.warn("Failed to read application native library {}: {}",
                    lib, e.getMessage());
            }
        }

        if (keys.isEmpty()) {
            return EmbeddedNativeLibraries.empty();
        }

        return new EmbeddedNativeLibraries(
            Collections.unmodifiableList(keys),
            Collections.unmodifiableList(resources));
    }

    // =========================================================================
    //  JDK library closure
    // =========================================================================

    /**
     * JDK shared objects that cannot work outside a live HotSpot JVM
     * process and therefore must NOT be embedded into the executable.
     *
     * <p>Each of these libraries either IS the JVM (libjvm.so), links
     * against it and depends on its thread/monitor/class-loader
     * internals (libjava.so), or is itself a JVM-aware front-end whose
     * {@code JNI_OnLoad} calls {@code (*env)->CallStaticBooleanMethod}
     * and dozens of other JNI functions that this runtime's stub JNIEnv
     * leaves NULL (libawt*, libfontmanager, liblcms, libmanagement,
     * libnet, libnio, libprefs). Loading them produces one of three
     * outcomes, none of them useful:</p>
     *
     * <ul>
     *   <li>{@code JNI_OnLoad} crashes on the first NULL JNIEnv slot;
     *       the crash guard catches the fault and marks the library
     *       unusable, but the library is still in memory and its
     *       exports are still visible to {@code dlsym}. A later call
     *       to one of its natives — outside the guard — crashes for
     *       real.</li>
     *   <li>{@code JNI_OnLoad} succeeds but {@code RegisterNatives}
     *       never runs, so the library's natives are still unreachable
     *       through the RegisterNatives table. The trampoline falls
     *       back to {@code dlsym}, finds the broken function pointer,
     *       and crashes when the function is called.</li>
     *   <li>{@code dlopen} itself fails on an unsatisfied DT_NEEDED
     *       that could not be embedded (system library such as
     *       {@code libX11.so}), and the whole chain is unusable.</li>
     * </ul>
     *
     * <p>The correct replacement for each of these libraries is a
     * JNative C file that supplies the natives the reachable set
     * actually invokes. The tree already contains such files for
     * {@code java.lang.*}, {@code java.io.*}, {@code java.nio.*},
     * {@code sun.nio.ch.*}, {@code sun.java2d.loops.*}, and dozens of
     * other classes; the ones still missing appear in the build log as
     * "N JNI symbol(s) still unresolved" and must be added as they are
     * reached.</p>
     */
    private static final Set<String> NON_PORTABLE_JDK_LIBS = Set.of(
        "libjvm.so",          // HotSpot VM itself
        "libjava.so",         // HotSpot's shared libjava
        "libawt.so",          // AWT, HotSpot- and X11-coupled
        "libawt_headless.so", // ditto
        "libawt_xawt.so",     // ditto
        "libfontmanager.so",  // HotSpot- and fontconfig-coupled
        "liblcms.so",         // part of the fontmanager chain
        "libmanagement.so",   // HotSpot-coupled
        "libnet.so",          // HotSpot-coupled
        "libnio.so",          // HotSpot-coupled
        "libprefs.so",        // HotSpot-coupled
        "libverify.so",       // HotSpot-coupled
        "libzip.so",          // HotSpot-coupled
        "libjimage.so",       // HotSpot-coupled
        "libjli.so"           // JVM launcher, cannot be embedded
    );

    /**
     * Computes the set of JDK shared objects that must be embedded into
     * the executable for the module's JNI references to resolve at run
     * time on a machine without a JDK.
     *
     * <h2>Algorithm</h2>
     *
     * <ol>
     *   <li><b>Seed.</b> Scan every directory returned by
     *       {@link #jdkNativeLibraryDirs(TargetPlatform)}, read each
     *       library's exported dynamic symbol set with
     *       {@link ElfSymbolReader#read(Path)}, and keep every library
     *       whose exports intersect the set of JNI symbols the module
     *       references. Symbols that remain unsatisfied after this pass
     *       are left in {@code stillWanted} so the caller can log them;
     *       they will normally be provided by an application library
     *       embedded in the second pass of {@code embedNativeLibraries}.</li>
     *
     *   <li><b>Transitive closure.</b> For every library in the set,
     *       read its {@code DT_NEEDED} list with
     *       {@link ElfSymbolReader#readNeededLibraries(Path)}. For each
     *       required SONAME, look it up in the same universe of JDK
     *       directories; a hit that is not yet in the set is added and
     *       the loop repeats. The iteration terminates because the set
     *       grows monotonically and the universe of JDK libraries is
     *       finite.</li>
     * </ol>
     *
     * <h2>Why the closure is required</h2>
     *
     * <p>The concrete failure the closure eliminates is the same one
     * that motivates the whole embed step: an executable that embeds
     * {@code libawt.so} because that library exports
     * {@code Java_sun_awt_X11_XlibWrapper_DisplayHeight} (or one of the
     * hundreds of other X11 JNI symbols the AWT pipeline references)
     * still fails to start on a machine without a JDK, because
     * {@code libawt.so}'s own {@code DT_NEEDED} list names
     * {@code libawt_headless.so}, {@code libjvm.so},
     * {@code libjava.so}, and (transitively through those)
     * {@code libfontmanager.so}, {@code libfreetype.so},
     * {@code liblcms.so}, {@code libnio.so}, {@code libnet.so},
     * {@code libzip.so}. The loader refuses to {@code dlopen} a library
     * whose {@code DT_NEEDED} cannot be satisfied by the already-loaded
     * set plus the ordinary path search, and with the previous
     * single-pass approach the missing dependencies never made it into
     * the image.</p>
     *
     * <h2>What the closure does not do</h2>
     *
     * <p>It does not follow SONAMEs into system library directories.
     * A {@code DT_NEEDED} entry of {@code libc.so.6} or
     * {@code libX11.so.6} matches no file in any of the JDK directories
     * and is therefore silently skipped, leaving the dependency to be
     * resolved against the target host's own copy. See the javadoc of
     * {@link #embedNativeLibraries} for the full rationale.</p>
     *
     * @param stillWanted the set of JNI symbols the module references;
     *                    modified in place: symbols satisfied by an
     *                    embedded library are removed, so the caller can
     *                    log whatever remains
     * @param targetPlatform used to locate the JDK library directories
     * @return the set of JDK shared objects to embed, in a deterministic
     *         order
     */
    private Set<Path> computeJdkLibraryClosure(Set<String> stillWanted,
                                               TargetPlatform targetPlatform) {

        // ----------------------------------------------------------------
        // 0. Snapshot every JDK shared object once. The result is a
        //    sorted list so that two builds against the same JDK produce
        //    the same embedded set in the same order.
        // ----------------------------------------------------------------
        List<Path> allJdkLibs = new ArrayList<>();
        for (Path libDir : jdkNativeLibraryDirs(targetPlatform)) {
            if (!Files.isDirectory(libDir)) continue;
            try (var s = Files.walk(libDir, 1)) {
                s.filter(Files::isRegularFile)
                    .filter(p -> isDynamicLibrary(p, targetPlatform))
                    // Skip JDK libraries that cannot work outside a live
                    // HotSpot JVM. See NON_PORTABLE_JDK_LIBS.
                    .filter(p -> !NON_PORTABLE_JDK_LIBS.contains(
                        p.getFileName().toString()))
                    .sorted()
                    .forEach(allJdkLibs::add);
            } catch (IOException e) {
                log.debug("Failed to scan JDK library directory {}: {}",
                    libDir, e.getMessage());
            }
        }
        if (allJdkLibs.isEmpty()) {
            return new LinkedHashSet<>();
        }

        // ----------------------------------------------------------------
        // 1. Seed the closure with the symbol-filtered set.
        // ----------------------------------------------------------------
        Set<Path> closure = new LinkedHashSet<>();
        if (!stillWanted.isEmpty()) {
            for (Path lib : allJdkLibs) {
                if (stillWanted.isEmpty()) break;

                Set<String> exported;
                try {
                    exported = ElfSymbolReader.read(lib);
                } catch (IOException e) {
                    log.debug("Failed to read symbols from {}: {}",
                        lib, e.getMessage());
                    continue;
                }
                if (exported.isEmpty()) continue;

                Set<String> overlap = new HashSet<>(exported);
                overlap.retainAll(stillWanted);
                if (overlap.isEmpty()) continue;

                closure.add(lib);
                stillWanted.removeAll(overlap);
            }
        }

        if (closure.isEmpty()) {
            return closure;
        }

        // ----------------------------------------------------------------
        // 2. Close the set under DT_NEEDED. On each pass, walk every
        //    library already in the closure, read its dependency list,
        //    and add any dependency whose SONAME names a file present
        //    in the JDK library directories. The loop repeats until a
        //    full pass adds nothing, which is guaranteed to happen
        //    because the closure grows monotonically and its universe
        //    is finite.
        //
        //    The SONAME -> Path index is built once; without it, every
        //    dependency lookup on every pass would be a linear scan of
        //    allJdkLibs, and on a full JDK 21 the closure reaches a
        //    few dozen libraries, each with a dozen dependencies, so
        //    the quadratic form is not catastrophic but is also not
        //    free. The index makes each lookup a single hash-map hit.
        // ----------------------------------------------------------------
        Map<String, Path> bySoname = new LinkedHashMap<>();
        for (Path lib : allJdkLibs) {
            bySoname.putIfAbsent(lib.getFileName().toString(), lib);
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Path lib : new ArrayList<>(closure)) {
                Set<String> needed;
                try {
                    needed = ElfSymbolReader.readNeededLibraries(lib);
                } catch (IOException e) {
                    log.debug("Failed to read DT_NEEDED of {}: {}",
                        lib, e.getMessage());
                    continue;
                }
                for (String soname : needed) {
                    Path dep = bySoname.get(soname);
                    if (dep != null && closure.add(dep)) {
                        changed = true;
                        log.debug("JDK closure: {} -> {} (DT_NEEDED)",
                            lib.getFileName(), soname);
                    }
                }
            }
        }

        return closure;
    }

    // =========================================================================
    //  Recursive scanning of the input archive
    // =========================================================================

    /**
     * Maximum recursion depth when parsing jar-in-jar. A Spring Boot fat
     * jar has exactly one level of nesting; a margin is left for wrapper
     * formats. The limit exists so a malicious or corrupt archive cannot
     * make the scanner recurse without bound.
     */
    private static final int MAX_NESTING_DEPTH = 4;

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
    }

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
                            // Best-effort cleanup.
                        }
                    }
                }
            }
        }
    }

    private static String dynamicLibrarySuffix(TargetPlatform t) {
        String triple = t.triple().toLowerCase(Locale.ROOT);
        if (triple.contains("windows")) return ".dll";
        if (triple.contains("darwin") || triple.contains("apple")) return ".dylib";
        return ".so";
    }

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

    // =========================================================================
    //  JDK library directories
    // =========================================================================

    private static boolean isDynamicLibrary(Path p, TargetPlatform t) {
        String n = p.getFileName().toString();
        String triple = t.triple().toLowerCase(Locale.ROOT);
        if (triple.contains("windows")) return n.endsWith(".dll");
        if (triple.contains("darwin") || triple.contains("apple")) {
            return n.endsWith(".dylib");
        }
        return n.endsWith(".so");
    }

    /**
     * Returns every directory under {@code $JAVA_HOME} that may contain
     * JDK native libraries:
     *
     * <ul>
     *   <li>{@code <jdk>/lib} — {@code libawt.so}, {@code libjava.so},
     *       {@code libnio.so}, {@code libnetty_transport_*.so} (when a
     *       user has installed one), etc.</li>
     *   <li>{@code <jdk>/lib/server} — {@code libjvm.so}. This library
     *       is a transitive dependency of several of the first
     *       directory's libraries, so a machine without it on
     *       {@code LD_LIBRARY_PATH} will fail to load them unless the
     *       executable is prepared to supply it directly.</li>
     *   <li>{@code <jdk>/bin} — on Windows the JDK ships its native
     *       libraries next to the executables.</li>
     * </ul>
     *
     * <p>On a JDK whose layout differs from this canonical one, the
     * directories are simply absent from the returned list and the
     * scanner silently skips them; the diagnostic warning printed by
     * {@link #embedNativeLibraries} names the unresolved symbols, so the
     * cause is always visible in the build log.</p>
     */
    private static List<Path> jdkNativeLibraryDirs(TargetPlatform t) {
        String home = System.getProperty("java.home");
        if (home == null) return List.of();

        Path base = Paths.get(home);
        String triple = t.triple().toLowerCase(Locale.ROOT);

        if (triple.contains("windows")) {
            return List.of(base.resolve("bin"));
        }

        List<Path> dirs = new ArrayList<>(2);
        dirs.add(base.resolve("lib"));
        dirs.add(base.resolve("lib").resolve("server"));
        return dirs;
    }

    // =========================================================================
    //  Symbol export reading
    // =========================================================================

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
}