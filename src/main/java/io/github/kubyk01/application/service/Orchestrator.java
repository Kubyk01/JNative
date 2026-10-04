package io.github.kubyk01.application.service;

import io.github.kubyk01.application.service.analyzer.Analyzer;
import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityAnalysis;
import io.github.kubyk01.application.service.analyzer.ssa.BytecodeToIr;
import io.github.kubyk01.application.service.analyzer.ssa.OutOfSsaPass;
import io.github.kubyk01.application.service.analyzer.ssa.SSATransformer;
import io.github.kubyk01.application.service.codegen.ResourceEmbedder;
import io.github.kubyk01.application.service.codegen.llvm.LlvmGenerator;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver;
import io.github.kubyk01.application.service.optimizer.DestructorInserter;
import io.github.kubyk01.application.service.optimizer.LocalSlotWidthValidator;
import io.github.kubyk01.application.service.optimizer.Optimizer;
import io.github.kubyk01.domain.analyzer.AnalyzerResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
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
import java.util.Comparator;
import java.util.HashSet;
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
                        int cores) {

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
                effectiveCores, scheduler);
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

        BytecodeToIr translator = new BytecodeToIr(resolver, analysis);
        Module module = translator.translate();

        SSATransformer ssaTransformer = new SSATransformer();
        for (Function func : module.getFunctions()) {
            ssaTransformer.transform(func);
        }

        // --- 7. Analysis pipeline -----------------------------------------
        Analyzer analyzer = new Analyzer();
        analyzer.setScheduler(scheduler);
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
        // lookup table the native override indexes. Collection fails loudly
        // rather than producing an empty table.
        List<Map.Entry<String, byte[]>> embedded = ResourceEmbedder.collectIcuResources();
        System.out.println("Embedding " + embedded.size() + " built-in resource(s).");

        LlvmGenerator llvmGen = new LlvmGenerator(module, resolver, aliasResult,
            entryClass, entryMethod, entryDescriptor, analysis.getReflectInfo(),
            polymorphicResolver);
        llvmGen.setClinitFunctions(analysisResult.clinitFunctions());
        llvmGen.setClinitWrappers(analysisResult.clinitWrappers());
        llvmGen.setEmbeddedResources(embedded);
        llvmGen.setCores(effectiveCores);

        String llvmIR = llvmGen.generate();
        Path llPath = Paths.get("output.ll");
        try {
            Files.write(llPath, llvmIR.getBytes());
            System.out.println("LLVM IR written to output.ll");
        } catch (IOException e) {
            log.error("Failed to write LLVM IR", e);
        }

        // --- 11. Native compilation ---------------------------------------
        if (!noCompile) {
            Path exePath = outputFile != null ? Paths.get(outputFile) : Paths.get("a.out");
            try {
                compiler.compileAndLink(llPath, exePath, usedSystemClasses, module,
                    resolver, effectiveCores);
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