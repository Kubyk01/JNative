package io.github.kubyk01.application.service;

import io.github.kubyk01.application.service.analyzer.Analyzer;
import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityAnalysis;
import io.github.kubyk01.application.service.analyzer.ssa.BytecodeToIr;
import io.github.kubyk01.application.service.analyzer.ssa.OutOfSsaPass;
import io.github.kubyk01.application.service.analyzer.ssa.SSATransformer;
import io.github.kubyk01.application.service.codegen.NativeOverride;
import io.github.kubyk01.application.service.codegen.NativeOverrideScanner;
import io.github.kubyk01.application.service.codegen.ResourceEmbedder;
import io.github.kubyk01.application.service.codegen.llvm.LlvmGenerator;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver;
import io.github.kubyk01.application.service.optimizer.DestructorInserter;
import io.github.kubyk01.application.service.optimizer.LocalSlotWidthValidator;
import io.github.kubyk01.application.service.optimizer.Optimizer;
import io.github.kubyk01.domain.analyzer.AnalyzerResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.port.primary.OrchestratorPort;
import io.github.kubyk01.port.secondary.CompilerPort;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
public class Orchestrator implements OrchestratorPort {

    private final CompilerPort compiler;

    public Orchestrator(CompilerPort compiler) {
        this.compiler = compiler;
    }

    @Override
    public void analyze(Path path,
                        String entryClass,
                        String entryMethod,
                        String entryDescriptor,
                        boolean showClasses,
                        boolean showAlias,
                        boolean showEscape,
                        boolean showLifetime,
                        boolean showDestructor,
                        String outputFile,
                        boolean noCompile,
                        boolean includeSystem,
                        String debugName,
                        boolean showClassesGraph,
                        int cores,
                        int optimizationLevel) {

        final int effectiveCores = (cores <= 0)
                ? Runtime.getRuntime().availableProcessors()
                : cores;

        System.out.println("Using " + effectiveCores + " core(s)"
                + (cores <= 0
                ? " (auto-detected via Runtime.availableProcessors())"
                : " (user-specified via --cores)")
                + " for parallel work");

        // Single, explicit, lifecycle-managed Reactor scheduler replaces
        // ForkJoinPool.commonPool(). All parallel work in the analysis
        // pipeline runs on this scheduler; nothing touches the common pool.
        Scheduler scheduler = Schedulers.newParallel("jnative-analysis", effectiveCores);

        try {
            runAnalysis(path, entryClass, entryMethod, entryDescriptor,
                    showClasses, showAlias, showEscape, showLifetime, showDestructor,
                    outputFile, noCompile, includeSystem, debugName, showClassesGraph,
                    effectiveCores, optimizationLevel, scheduler);
        } finally {
            scheduler.dispose();
        }
    }

    private void runAnalysis(Path path,
                             String entryClass,
                             String entryMethod,
                             String entryDescriptor,
                             boolean showClasses,
                             boolean showAlias,
                             boolean showEscape,
                             boolean showLifetime,
                             boolean showDestructor,
                             String outputFile,
                             boolean noCompile,
                             boolean includeSystem,
                             String debugName,
                             boolean showClassesGraph,
                             int effectiveCores,
                             int optimizationLevel,
                             Scheduler scheduler) {

        // --- 1. Dependency resolution -------------------------------------
        DependencyResolver resolver = new DependencyResolver();
        try {
            resolver.scan(path);
        } catch (IOException e) {
            log.error("Failed to scan path: {}", path, e);
            System.out.println("Crash!!" + e.getMessage());
            return;
        }
        System.out.println("Parsed classes: " + resolver.getAllClasses().size());

        // --- 2. Reachability analysis -------------------------------------
        ReachabilityAnalysis analysis = new ReachabilityAnalysis(resolver);
        analysis.applyMetadata(resolver.getMetadata());
        analysis.analyzeFromEntry(entryClass, entryMethod, entryDescriptor);

        // The native Class.getResourceAsStream override builds a
        // ByteArrayInputStream from C, so the ordinary bytecode closure never
        // sees those methods. Force them in and re-run the fixed point before
        // the reachable sets are snapshotted below, otherwise the class and
        // method listings would be missing them and the vtable slots would
        // hold unresolved thunks.
        forceResourceStreamMethods(analysis);
        forcePerfMethods(analysis);

        // ------------------------------------------------------------------
        // Superclass-<clinit> closure.
        //
        // Every class whose <clinit> must run before another class's — the
        // transitive superclass chain of the reachable set — is forced into
        // the reachable set here, before translation. This is the only way
        // to guarantee that LlvmGenerator.generateMain()'s eager schedule
        // covers the full JVMS §5.5 step-5 chain without an ad-hoc
        // resurrection pass after the module has been built.
        //
        // The concrete failure this closes is documented on
        // ReachabilityAnalysis.ensureSuperclassClinitsReachable().
        // ------------------------------------------------------------------
        analysis.ensureSuperclassClinitsReachable();

        // ------------------------------------------------------------------
        // Descriptor-type closure.
        //
        // Pull in every class referenced only from a parameter type,
        // return type, or field type of a class already in the class map.
        // Without this pass a class that is referenced only through such
        // a descriptor — the canonical case being a provider
        // implementation whose name is read from a String field at run
        // time and handed to Class.forName — is present in the reachable
        // set as a name but absent from the class map, and therefore has
        // no vtable, no struct type, and no reflection record. At run
        // time Class.forName0 then fails to find it, and any Java-level
        // catch (ClassNotFoundException) or NoSuchAlgorithmException on
        // the caller's frame receives NULL rather than an exception
        // object.
        //
        // The concrete failure this closes is documented on
        // ReachabilityAnalysis.expandClassMapToDescriptorClosure().
        // ------------------------------------------------------------------
        analysis.expandClassMapToDescriptorClosure();

        // ------------------------------------------------------------------
        // Interface-implementation closure.
        //
        // The reachability walk reaches a class only through NEW, GETSTATIC /
        // PUTSTATIC, INVOKESTATIC, or a resolved virtual/interface dispatch.
        // A class that participates in the image only as the runtime type of
        // an interface-typed receiver is reached, but its concrete
        // implementations of the interface's methods may not be — because
        // resolveVirtualDispatchFixedPoint only resolves slots whose dispatch
        // site was actually observed, and by the time that site is resolved
        // the concrete class may not yet be in instantiatedClasses.
        //
        // Codegen emits an itable entry for every method of every interface
        // that every non-external class implements, so any implementation
        // that is missing from reachableMethods becomes a NULL function
        // pointer in the itable, and the first caller that dispatches
        // through it traps with __jnative_unresolved_slot.
        //
        // The concrete failure this closes: JceSecurity.<clinit> ->
        // JceSecurity.setupJurisdictionPolicies -> for (Path p : stream)
        // -> DirectoryStream.iterator(), where stream's runtime type is
        // sun/nio/fs/UnixSecureDirectoryStream. The implementation of
        // iterator() is inherited from sun/nio/fs/UnixDirectoryStream, and
        // that method had never been added to the reachable set.
        //
        // The pass must run *before* the snapshots of allClasses/allMethods
        // below, so that the newly-added methods are picked up by
        // BytecodeToIr.translate() and receive the ordinary SSA / analysis
        // pipeline.
        // ------------------------------------------------------------------
        analysis.ensureInterfaceImplementationsReachable();

        Set<String> allClasses = analysis.getReachableClasses();
        Set<MethodReference> allMethods = analysis.getReachableMethods();

        Set<String> usedSystemClasses = new HashSet<>();
        for (String cls : allClasses) {
            if (LlvmUtil.isSystemClassName(cls) && compiler.hasNativeSupport(cls)) {
                usedSystemClasses.add(cls);
            }
        }

        // --- 3. Polymorphic-resolver warm-up ------------------------------
        Set<String> polymorphicClasses = new HashSet<>();
        for (String cls : allClasses) {
            ClassNode node = resolver.getClassNode(cls);
            if (node != null && node.getPolymorphicMethodNames() != null
                    && !node.getPolymorphicMethodNames().isEmpty()) {
                polymorphicClasses.add(cls);
            }
        }
        Set<String> toLoad = new HashSet<>(polymorphicClasses);
        toLoad.retainAll(usedSystemClasses);
        PolymorphicResolver polymorphicResolver = new PolymorphicResolver();
        polymorphicResolver.loadAll(toLoad);

        // --- 4. Filtering for user-visible listings -----------------------
        Set<String> classesToShow;
        Set<MethodReference> methodsToShow;
        if (includeSystem) {
            classesToShow = allClasses;
            methodsToShow = allMethods;
        } else {
            classesToShow = allClasses.stream()
                    .filter(c -> !LlvmUtil.isSystemClassName(c))
                    .collect(Collectors.toSet());
            methodsToShow = allMethods.stream()
                    .filter(m -> !LlvmUtil.isSystemClassName(
                            m.getOwner() + "." + m.getName()))
                    .collect(Collectors.toSet());
        }

        // --- 5. Optional prints -------------------------------------------
        if (showClassesGraph) {
            printCallGraph(analysis, entryClass, entryMethod, entryDescriptor,
                    includeSystem, debugName);
        }

        if (showClasses) {
            Set<String> filteredClasses = classesToShow.stream()
                    .filter(c -> matchesDebug(debugName, c))
                    .collect(Collectors.toSet());
            Set<MethodReference> filteredMethods = methodsToShow.stream()
                    .filter(m -> matchesDebug(debugName, m.getOwner())
                            || matchesDebug(debugName, m.getName()))
                    .collect(Collectors.toSet());

            System.out.println("\nClasses (" + filteredClasses.size() + "):");
            filteredClasses.stream().sorted().forEach(c -> System.out.println("  " + c));

            System.out.println("\nMethods (" + filteredMethods.size() + "):");
            filteredMethods.stream()
                    .sorted(Comparator.comparing(MethodReference::getOwner)
                            .thenComparing(MethodReference::getName))
                    .forEach(m -> System.out.println("  " + m));
        }

        // --- 6. IR translation + SSA --------------------------------------
        System.out.println("\n--- Translating to IR and applying SSA ---");
        System.out.println("Total methods to translate: " + allMethods.size());

        // ------------------------------------------------------------------
        // Native overrides.
        //
        // Scan every reachable class's C source file (under jnative/) for
        // functions named __jnative_override_<Class>_<method>. Each one
        // replaces the Java body of the corresponding method: the Java
        // bytecode is not translated into IR at all, and every call site
        // — direct, virtual, or reflective — is aliased onto the C symbol.
        //
        // The C resource path is derived with exactly the same rule
        // Compiler.getNativeResourcePath uses: strip a leading "java/"
        // from the class name and prepend "jnative/". So for
        // java.security.SecureRandom the path is
        // "jnative/security/SecureRandom.c".
        // ------------------------------------------------------------------
        List<NativeOverride> nativeOverrides = collectNativeOverrides(analysis);

        System.out.println("Native overrides discovered: " + nativeOverrides.size());
        for (NativeOverride o : nativeOverrides) {
            System.out.println("  " + o.getClassName() + "." + o.getMethodName()
                    + (o.getDescriptor() != null ? o.getDescriptor() : "(all overloads)")
                    + " -> " + o.getCFunctionName());
        }

        BytecodeToIr translator = new BytecodeToIr(resolver, analysis, nativeOverrides);
        Module module = translator.translate();

        SSATransformer ssaTransformer = new SSATransformer();
        for (Function func : module.getFunctions()) {
            ssaTransformer.transform(func);
        }

        // --- 7. Analysis pipeline -----------------------------------------
        Analyzer analyzer = new Analyzer();
        analyzer.setScheduler(scheduler);
        analyzer.setTranslator(translator);
        AnalyzerResult analysisResult = analyzer.analyze(
                module, resolver,
                entryClass, entryMethod, entryDescriptor,
                includeSystem, debugName,
                showAlias, showEscape, showLifetime);

        AliasAnalysisResult aliasResult = analysisResult.aliasResult();
        EscapeAnalysisResult escapeResult = analysisResult.escapeResult();
        LifetimeAnalysisResult lifetimeResult = analysisResult.lifetimeResult();

        // --- 8. Destructor insertion + optimization -----------------------
        DestructorInserter inserter = new DestructorInserter(module, resolver, lifetimeResult,
                aliasResult.getAllocationSiteToValue(), aliasResult);
        inserter.insert();

        System.out.println("\n--- Running Optimizations ---");
        Optimizer optimizer = new Optimizer(module, aliasResult, escapeResult,
                lifetimeResult, aliasResult.getAllocationSiteToValue());
        optimizer.setEnableScalarReplacement(true);
        optimizer.setEnableDestructorSimplification(true);
        optimizer.setEnableDestructorInlining(true);
        optimizer.setEnableDeadDestructorElimination(true);
        optimizer.optimize();

        // --- 9. Post-analysis IR passes -----------------------------------
        analysis.registerClassLiterals(module);
        OutOfSsaPass.transform(module);
        LocalSlotWidthValidator.validate(module);

        // --- 10. LLVM IR generation ---------------------------------------
        System.out.println("\n--- Generating LLVM IR ---");

        // Resources the JDK reads through Class.getResourceAsStream. They
        // live in the java.base module image and never reach the image on
        // their own, so their bytes are collected here and emitted as a
        // lookup table the native override indexes. The whole module is
        // embedded (minus .class files): a narrow set such as the ICU data
        // alone silently returns null for everything else, and the first
        // client outside it — MimeTable.load() — turns that null into
        // InternalError: default mime table not found. An empty result is
        // reported loudly rather than producing an empty table.
        // Resources the JDK reads at run time from outside its own
        // module image. Two sources contribute:
        //
        //   * java.base's own resources (ICU data, META-INF/services,
        //     content-types.properties, ...) — every non-.class entry of
        //     the module, minus the module's classes which are already in
        //     the IR;
        //
        //   * the runtime data files that live under $JAVA_HOME/lib/
        //     rather than under $JAVA_HOME/lib/modules — today that is
        //     exactly lib/tzdb.dat, consumed by
        //     sun.util.calendar.ZoneInfoFile.<clinit>.
        //
        // The two lists are merged before the emitter sees them so a
        // single flat lookup table (jnative_builtin_resources) covers
        // both kinds. The C-side dispatch is by resource key, not by
        // kind, so no consumer needs to know which source an entry came
        // from.
        List<Map.Entry<String, byte[]>> embedded = new ArrayList<>();
        embedded.addAll(ResourceEmbedder.collectJavaBaseResources());
        embedded.addAll(ResourceEmbedder.collectJdkRuntimeData());
        System.out.println("Embedding " + embedded.size()
            + " built-in resource(s) from java.base and JDK runtime data.");

        LlvmGenerator llvmGen = new LlvmGenerator(module, resolver, aliasResult,
                entryClass, entryMethod, entryDescriptor, analysis.getReflectInfo(),
                polymorphicResolver);
        llvmGen.setClinitSchedule(analysisResult.clinitSchedule());
        llvmGen.setClinitWrappers(analysisResult.clinitWrappers());
        llvmGen.setEmbeddedResources(embedded);
        llvmGen.setCores(effectiveCores);

        Path genDir = Paths.get("target", "generated-jnative");
        try {
            llvmGen.generate(genDir);
        } catch (IOException e) {
            log.error("Failed to write generated IR", e);
            return;
        }

        List<Path> llPaths = new ArrayList<>();
        try (var stream = Files.walk(genDir)) {
            stream.filter(p -> p.toString().endsWith(".ll"))
                  .sorted()
                  .forEach(llPaths::add);
        } catch (IOException e) {
            log.error("Failed to enumerate generated IR files under {}", genDir, e);
            return;
        }
        System.out.println("Generated " + llPaths.size() + " LLVM IR file(s) in " + genDir);

        // --- 11. Native compilation ---------------------------------------
        if (!noCompile) {
            Path exePath = outputFile != null ? Paths.get(outputFile) : Paths.get("a.out");
            try {
                compiler.compileAndLink(llPaths, exePath, usedSystemClasses, module,
                        resolver, effectiveCores, optimizationLevel);
                System.out.println("Native executable built successfully: "
                        + exePath.toAbsolutePath());
            } catch (IOException | InterruptedException e) {
                log.error("Failed to build native executable", e);
                System.err.println("Build failed: " + e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        } else {
            System.out.println("Skipping native compilation (--no-compile specified)");
        }
    }

    // =====================================================================
    //  Native override discovery
    // =====================================================================

    /**
     * Scans the C runtime source of every reachable class for
     * {@code __jnative_override_*} functions and returns them as
     * {@link NativeOverride} records.
     *
     * <p>Two properties of this method matter to its correctness:</p>
     *
     * <ul>
     *   <li>The C resource path is derived with the same rule
     *       {@link io.github.kubyk01.application.service.compiler.Compiler#hasNativeSupport}
     *       uses: a leading {@code "java/"} is stripped and the path is
     *       rebased under {@code "jnative/"}. For
     *       {@code java.security.SecureRandom} the resulting path is
     *       {@code "jnative/security/SecureRandom.c"}, which is where
     *       the file actually lives. Using
     *       {@code ParserC.getResourcePathForClass} here produces
     *       {@code "jnative/java/security/SecureRandom.c"}, which does
     *       not exist, the scan silently returns an empty list, and the
     *       override is never applied.</li>
     *
     *   <li>The scan runs over every reachable class, not only those
     *       that {@code Compiler.hasNativeSupport} reports — a class
     *       may have an override file without having a
     *       {@code __jnative_fn_*} file, and vice versa. Attempting to
     *       read a file that does not exist is a cheap miss:
     *       {@code NativeOverrideScanner.scan} returns an empty list.</li>
     * </ul>
     *
     * <p>Duplicates are collapsed by
     * {@code (className, methodName, descriptor)}. The last entry wins,
     * which lets a later class in iteration order override an earlier
     * one — an unusual case, but one that has a well-defined outcome
     * rather than an exception.</p>
     */
    private List<NativeOverride> collectNativeOverrides(ReachabilityAnalysis analysis) {
        List<NativeOverride> discovered = new ArrayList<>();

        for (String cls : analysis.getReachableClasses()) {
            String cResourcePath = cResourcePathForClass(cls);
            List<NativeOverride> found = NativeOverrideScanner.scan(cResourcePath);
            if (!found.isEmpty()) {
                discovered.addAll(found);
                System.out.println("Native overrides in " + cResourcePath
                        + ": " + found.size());
            }
        }

        Map<String, NativeOverride> byKey = new LinkedHashMap<>();
        for (NativeOverride o : discovered) {
            String key = o.getClassName() + "." + o.getMethodName()
                    + "#" + o.getDescriptor();
            byKey.put(key, o);
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Maps an internal class name to the classpath-relative path of its
     * C runtime source.
     *
     * <p>This is byte-for-byte the same mapping
     * {@link io.github.kubyk01.application.service.compiler.Compiler#getNativeResourcePath}
     * performs, and it must stay that way: the override scanner and the
     * native compiler must find the same files.</p>
     */
    private static String cResourcePathForClass(String internalName) {
        if (internalName.startsWith("java/")) {
            return "jnative/" + internalName.substring("java/".length()) + ".c";
        }
        return "jnative/" + internalName + ".c";
    }

    // =====================================================================
    //  Forced reachability
    // =====================================================================

    /**
     * Marks the {@code ByteArrayInputStream} methods that the native
     * {@code Class.getResourceAsStream} override depends on as reachable.
     *
     * <p>Nothing in the bytecode of any reachable method calls them: the
     * override constructs the stream from C
     * ({@code __jnative_make_byte_array_input_stream} in
     * {@code jnative_runtime.c}), so the worklist never learns about them and
     * their vtable slots would be filled with unresolved thunks. The set below
     * is the JDK code {@code ICUBinary.getRequiredData} drives on the returned
     * stream: {@code available}, the two {@code read} overloads, and
     * {@code close} from the try-with-resources.
     */
    private void forceResourceStreamMethods(ReachabilityAnalysis analysis) {
        String owner = "java/io/ByteArrayInputStream";
        analysis.addExtraReachableMethod(
                new MethodReference(owner, "<init>", "([B)V"));
        analysis.addExtraReachableMethod(
                new MethodReference(owner, "available", "()I"));
        analysis.addExtraReachableMethod(
                new MethodReference(owner, "read", "([BII)I"));
        analysis.addExtraReachableMethod(
                new MethodReference(owner, "read", "()I"));
        analysis.addExtraReachableMethod(
                new MethodReference(owner, "skip", "(J)J"));
        analysis.addExtraReachableMethod(
                new MethodReference(owner, "mark", "(I)V"));
        analysis.addExtraReachableMethod(
                new MethodReference(owner, "reset", "()V"));
        analysis.addExtraReachableMethod(
                new MethodReference(owner, "close", "()V"));
    }

    /**
     * Forces the {@code java.nio} methods required by the C implementation
     * of {@code jdk.internal.perf.Perf.create*} into the reachable set.
     *
     * <h2>Why this is needed</h2>
     *
     * <p>{@code Perf.createLong/createByteArray/createString} are native
     * methods. Their C implementation (see
     * {@code jnative/jdk/internal/perf/Perf.c}) returns a real
     * {@link java.nio.ByteBuffer}, but cannot construct it directly: the
     * layout of {@code java.nio.Buffer} and {@code java.nio.HeapByteBuffer}
     * changes between JDK releases, and {@code HeapByteBuffer} itself is
     * package-private and may have no entry in
     * {@code reflect_all_classes[]}. The C side therefore calls
     * {@code java.nio.ByteBuffer.allocate(int)} by mangled name through
     * {@code dlsym} — the same technique already used in
     * {@code MethodHandleNatives.c} and {@code FileInputStream.c}.</p>
     *
     * <p>For that technique to work, three methods must be <em>translated
     * into IR and linked into the executable</em>:</p>
     *
     * <ol>
     *   <li><b>{@code java.nio.ByteBuffer.allocate(int)}</b>. The body of
     *       this method — {@code new HeapByteBuffer(cap, cap)} — does two
     *       things at once: it emits the symbol
     *       {@code fn_java_nio_ByteBuffer_allocate__I_Ljava_nio_ByteBuffer_}
     *       that {@code Perf.c} refers to, and it instantiates
     *       {@code HeapByteBuffer}, which gives the reachability analysis a
     *       concrete candidate for the abstract virtual call
     *       {@code ByteBuffer.asLongBuffer()} from
     *       {@code PerfCounter.<init>}. Without that second part the vtable
     *       slot {@code asLongBuffer} would remain unresolved and would fail
     *       on {@code __jnative_unresolved_slot}.</li>
     *
     *   <li><b>{@code java.nio.HeapByteBuffer.putLong(int, long)}</b>.
     *       Needed so that {@code Perf.createLong} can write a non-zero
     *       initial counter value. All JDK call-sites pass {@code 0L}, but
     *       the public contract of {@code jdk.internal.perf.Perf} also
     *       allows initialisation, and we do not silently substitute for it:
     *       if the symbol is present, the initial value is written with the
     *       index-based {@code putLong} (position is not advanced, so the
     *       subsequent {@code asLongBuffer()} gets a buffer with position
     *       0).</li>
     *
     *   <li><b>{@code java.nio.ByteBuffer.put(byte[])}</b>. Needed for
     *       {@code Perf.createByteArray(name, variability, units, byte[]
     *       value, int maxLength)}. The method is concrete (implemented
     *       directly in {@code ByteBuffer}, not abstract) — its body is
     *       emitted automatically as soon as it enters the reachable set.
     *       Inside it delegates to the virtual {@code put(byte[], int, int)},
     *       whose concrete implementation lives in {@code HeapByteBuffer}
     *       precisely because {@code allocate} made that class
     *       instantiable.</li>
     * </ol>
     *
     * <p>All three calls go through
     * {@link ReachabilityAnalysis#addExtraReachableMethod}, which runs the
     * worklist to fixed point after each addition and re-resolves the
     * virtual dispatch sites. The order of the calls does not matter: after
     * {@code ByteBuffer.allocate} is added, the class {@code HeapByteBuffer}
     * is loaded and known to the analyser, so the subsequent addition of
     * {@code HeapByteBuffer.putLong} finds its methods without further
     * hints.</p>
     *
     * <p><b>Root failure this pass closes:</b></p>
     *
     * <pre>
     *   jdk.internal.perf.PerfCounter.&lt;init&gt;:
     *       ByteBuffer bb = perf.createLong(name, type, U_None, 0L);
     *       bb.order(ByteOrder.nativeOrder());       // NPE when bb == null
     *       this.lb = bb.asLongBuffer();             // NPE when bb == null
     * </pre>
     *
     * <p>Previously {@code Perf.create*} in the C runtime returned
     * {@code NULL}, which produced an NPE on the very first initialization
     * of {@code PerfCounter$CoreCounters.<clinit>} — and that class is
     * initialized in the Stage 4 eager loop and is also really used from
     * {@code ClassLoader.findClass}, {@code ZipFile.open} and
     * {@code ModuleBootstrap.boot}. Merely excluding it from Stage 4 did
     * not help: the class was still initialized on the first real access
     * and failed the same way.</p>
     */
    private void forcePerfMethods(ReachabilityAnalysis analysis) {
        analysis.addExtraReachableMethod(new MethodReference(
            "java/nio/ByteBuffer", "allocate",
            "(I)Ljava/nio/ByteBuffer;"));

        analysis.addExtraReachableMethod(new MethodReference(
            "java/nio/ByteBuffer", "put",
            "([B)Ljava/nio/ByteBuffer;"));

        analysis.addExtraReachableMethod(new MethodReference(
            "java/nio/HeapByteBuffer", "putLong",
            "(IJ)Ljava/nio/ByteBuffer;"));
    }


    /**
     * Makes every declared {@code <init>} of {@code className} reachable
     * and registers it in the reflection table.
     *
     * <p>The instantiation of secure-random providers happens
     * reflectively — see {@code java.security.Provider$Service
     * .newInstanceUtil} and {@code Class.getConstructor(Class[])}.
     * Fix #1 in {@code MethodBytecodeVisitor} makes that lookup visible
     * to the reachability walk, so under normal circumstances these
     * constructors would already be registered by the time this method
     * runs. The two operations below are a deliberate belt-and-braces:
     * they cover the corner case where the reflective call site is not
     * reached (for example because a JDK optimisation short-circuits a
     * path this build's reachability walk did not anticipate), and they
     * cost nothing when Fix #1 has already done the work — both stores
     * below use set semantics, so re-registering an already-present
     * constructor is a no-op.</p>
     *
     * <p>These two operations are independent and both required:</p>
     *
     * <ul>
     *   <li>{@link ReachabilityAnalysis#getReflectInfo()}
     *       {@code .addConstructor(...)} is what makes the constructor
     *       appear in {@code @refctors_<class>}. The C side's
     *       {@code Class.getDeclaredConstructors0} walks that array to
     *       build a {@code Constructor[]} for the Java layer.</li>
     *
     *   <li>{@link ReachabilityAnalysis#addExtraReachableMethod} is what
     *       pulls the constructor's bytecode into the module as an IR
     *       {@code Function}. Without it, {@code @refctors_<class>} would
     *       contain entries but their adaptor slot would be
     *       {@code i8* null} — see {@code generateReflectionData} — and
     *       {@code Constructor.newInstance()} would dereference a null
     *       target at run time.</li>
     * </ul>
     */
    private void registerConstructorForReflection(ReachabilityAnalysis analysis,
                                                  DependencyResolver resolver,
                                                  String className) {
        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isExternal() || resolver.getClassBytes(className) == null) {
            resolver.forceLoadSystemClass(className);
            cn = resolver.getClassNode(className);
        }
        if (cn == null || cn.isExternal()) {
            log.debug("registerConstructorForReflection: {} is not available "
                    + "in the class map after a forced load; skipping", className);
            return;
        }

        for (MethodNode mn : cn.getMethods()) {
            if (!"<init>".equals(mn.getName())) continue;
            if (mn.getDescriptor() == null || mn.getDescriptor().isEmpty()) continue;

            MethodReference ref = new MethodReference(
                    className, mn.getName(), mn.getDescriptor());

            analysis.getReflectInfo().addConstructor(className, ref);
            analysis.addExtraReachableMethod(ref);
        }
    }

    // =====================================================================
    //  Printing / filtering helpers (unchanged)
    // =====================================================================

    private void printCallGraph(ReachabilityAnalysis analysis, String entryClass,
                                String entryMethod, String entryDescriptor,
                                boolean includeSystem, String debugName) {
        MethodReference entry = new MethodReference(
                entryClass.replace('.', '/'), entryMethod, entryDescriptor);
        Map<MethodReference, Set<MethodReference>> graph = analysis.getCallGraph();
        Set<MethodReference> visited = new HashSet<>();
        Predicate<MethodReference> filter = mr -> {
            if (!includeSystem && LlvmUtil.isSystemClassName(mr.getOwner())) return false;
            return debugName == null || mr.toString().contains(debugName);
        };
        System.out.println("\nCall graph from entry:");
        printMethodTree(entry, graph, filter, visited, 0);
    }

    private void printMethodTree(MethodReference method,
                                 Map<MethodReference, Set<MethodReference>> graph,
                                 Predicate<MethodReference> filter,
                                 Set<MethodReference> visited,
                                 int depth) {
        if (!filter.test(method)) return;
        String indent = "  ".repeat(depth);
        System.out.println(indent + method);
        if (!visited.add(method)) {
            System.out.println(indent + "  (already visited)");
            return;
        }
        Set<MethodReference> callees = graph.get(method);
        if (callees != null) {
            for (MethodReference callee : callees) {
                if (filter.test(callee)) {
                    printMethodTree(callee, graph, filter, visited, depth + 1);
                }
            }
        }
    }

    private static boolean matchesDebug(String debugName, String name) {
        if (debugName == null) return true;
        return name != null && name.contains(debugName);
    }
}