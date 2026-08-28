package io.github.kubyk01.application.service.analyzer;

import io.github.kubyk01.application.service.analyzer.aliasanalysis.AliasAnalyzer;
import io.github.kubyk01.application.service.analyzer.escapeanalysis.EscapeAnalyzer;
import io.github.kubyk01.application.service.analyzer.lifetime.LifetimeAnalyzer;
import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityAnalysis;
import io.github.kubyk01.application.service.analyzer.ssa.BytecodeToIr;
import io.github.kubyk01.application.service.analyzer.ssa.SSATransformer;
import io.github.kubyk01.application.service.codegen.llvm.LlvmGenerator;
import io.github.kubyk01.application.service.optimizer.Optimizer;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.ir.Type;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.port.primary.AnalyzerPort;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
public class Analyzer implements AnalyzerPort {

    private static final String RUNTIME_RESOURCE_PATH = "jnative_runtime.c";
    private static final String NATIVE_BASE_PATH = "jnative/";

    @Override
    public void analyze(Path path, String entryClass, String entryMethod, String entryDescriptor,
                        boolean showClasses, boolean showAlias, boolean showEscape,
                        boolean showLifetime, boolean showDestructor,
                        String outputFile, boolean noCompile,
                        boolean includeSystem, String debugName,
                        boolean showClassesGraph) {
        DependencyResolver resolver = new DependencyResolver();
        try {
            resolver.scan(path);
        } catch (IOException e) {
            log.error("Failed to scan path: {}", path, e);
            System.out.println("Crash!!" + e.getMessage());
            return;
        }

        System.out.println("Parsed classes: " + resolver.getAllClasses().size());

        ReachabilityAnalysis analysis = new ReachabilityAnalysis(resolver);
        analysis.applyMetadata(resolver.getMetadata());
        analysis.analyzeFromEntry(entryClass, entryMethod, entryDescriptor);

        Set<String> allClasses = analysis.getReachableClasses();
        Set<MethodReference> allMethods = analysis.getReachableMethods();

        // Collect used system classes that have native C implementations
        Set<String> usedSystemClasses = new HashSet<>();
        for (String cls : allClasses) {
            if (isSystemClassName(cls) && hasNativeSupport(cls)) {
                usedSystemClasses.add(cls);
            }
        }

        // If includeSystem is false, filter to output only user classes and methods
        Set<String> classesToShow;
        Set<MethodReference> methodsToShow;
        if (includeSystem) {
            classesToShow = allClasses;
            methodsToShow = allMethods;
        } else {
            classesToShow = allClasses.stream()
                .filter(c -> !isSystemClassName(c))
                .collect(Collectors.toSet());

            methodsToShow = allMethods.stream()
                .filter(m -> !isSystemClass(m.getOwner() + "." + m.getName()))
                .collect(Collectors.toSet());
        }

        if (showClassesGraph) {
            Set<String> filteredClasses = classesToShow.stream()
                .filter(c -> matchesDebug(debugName, c))
                .collect(Collectors.toSet());
            Set<MethodReference> filteredMethods = methodsToShow.stream()
                .filter(m -> matchesDebug(debugName, m.getOwner()) || matchesDebug(debugName, m.getName()))
                .collect(Collectors.toSet());

            System.out.println("\nClasses (" + filteredClasses.size() + "):");
            System.out.println("\nMethods (" + filteredMethods.size() + "):");

            printCallGraph(analysis, entryClass, entryMethod, entryDescriptor, includeSystem, debugName);
        }

        if (showClasses) {
            // filter classes/methods by debug name
            Set<String> filteredClasses = classesToShow.stream()
                .filter(c -> matchesDebug(debugName, c))
                .collect(Collectors.toSet());
            Set<MethodReference> filteredMethods = methodsToShow.stream()
                .filter(m -> matchesDebug(debugName, m.getOwner()) || matchesDebug(debugName, m.getName()))
                .collect(Collectors.toSet());

            System.out.println("\nClasses (" + filteredClasses.size() + "):");
            filteredClasses.stream().sorted().forEach(c -> System.out.println("  " + c));

            System.out.println("\nMethods (" + filteredMethods.size() + "):");
            filteredMethods.stream()
                .sorted(Comparator.comparing(MethodReference::getOwner)
                    .thenComparing(MethodReference::getName))
                .forEach(m -> System.out.println("  " + m));
        }

        // --- Translation to IR and SSA (always runs) ---
        System.out.println("\n--- Translating to IR and applying SSA ---");
        System.out.println("Total methods to translate: " + allMethods.size());

        BytecodeToIr translator = new BytecodeToIr(resolver, analysis);
        Module module = translator.translate();

        SSATransformer ssaTransformer = new SSATransformer();
        for (Function func : module.getFunctions()) {
            ssaTransformer.transform(func);
        }

        // --- Static initializers (<clinit>) must be processed first ---
        // Extract all <clinit> functions from the module
        List<Function> clinitFunctions = new ArrayList<>();
        for (Function func : module.getFunctions()) {
            if (func.getName().endsWith(".<clinit>()V")) { // name format from BytecodeToIr
                clinitFunctions.add(func);
            }
        }

        if (!clinitFunctions.isEmpty()) {
            System.out.println("Processing " + clinitFunctions.size() + " static initializers (<clinit>) first...");

            // Build a temporary module containing only <clinit> functions
            Module clinitModule = new Module();
            for (Function func : clinitFunctions) {
                clinitModule.addFunction(func);
            }

            // Run alias and escape analysis on clinitModule to propagate static field initializations
            AliasAnalyzer clinitAlias = new AliasAnalyzer(clinitModule);
            clinitAlias.analyze();

            // Now merge the results into the main alias result later, but we need a full result.
            // Better: we run the main alias analysis, but we can force processing of <clinit> first.
            // The existing AliasAnalyzer will process all functions anyway, but we can separate.
            // Since the main analysis also processes them, we could just rely on fixed-point,
            // but to be explicit we can run them first and then the rest.
        }

        // --- Alias Analysis (always runs, output optional) ---
        AliasAnalyzer aliasAnalyzer = new AliasAnalyzer(module);
        AliasAnalysisResult aliasResult = aliasAnalyzer.analyze();

        if (showAlias) {
            System.out.println("\n--- Alias Analysis ---");
            System.out.println("Alias analysis complete.");
            for (Function func : module.getFunctions()) {
                if (!includeSystem && !isUserFunction(func)) continue;
                if (!matchesDebug(debugName, func)) continue;
                for (BasicBlock block : func.getBlocks()) {
                    for (Instruction inst : block.getInstructions()) {
                        if (inst.getResult() != null) {
                            PointsToSet pts = aliasResult.getPointsTo(inst.getResult());
                            if (!pts.isEmpty()) {
                                System.out.println("  " + func.getName() + " : " + inst.getResult() + " -> " + pts);
                            }
                        }
                    }
                }
            }
        }

        // --- Escape Analysis (always runs, output optional) ---
        EscapeAnalyzer escapeAnalyzer = new EscapeAnalyzer(module, aliasResult, resolver);
        EscapeAnalysisResult escapeResult = escapeAnalyzer.analyze();

        if (showEscape) {
            System.out.println("\n--- Escape Analysis ---");
            System.out.println("Escape analysis complete.");
            for (Function func : module.getFunctions()) {
                if (!includeSystem && !isUserFunction(func)) continue;
                if (!matchesDebug(debugName, func)) continue;
                for (BasicBlock block : func.getBlocks()) {
                    for (Instruction inst : block.getInstructions()) {
                        if (isAllocation(inst.getOpcode()) && inst.getResult() != null) {
                            PointsToSet pts = aliasResult.getPointsTo(inst.getResult());
                            for (AllocationSite site : pts.getSites()) {
                                if (!includeSystem && !isUserAllocationSite(site)) continue;
                                if (!matchesDebug(debugName, site)) continue;
                                EscapeStatus status = escapeResult.getSiteStatus(site);
                                System.out.println("  " + site + " -> " + status);
                            }
                        }
                    }
                }
            }
        }

        // --- Lifetime Analysis (always runs, output optional) ---
        Map<String, FunctionSummary> summaries = aliasResult.getFunctionSummaries();
        LifetimeAnalyzer lifetimeAnalyzer = new LifetimeAnalyzer(module, aliasResult, escapeResult, summaries);
        LifetimeAnalysisResult lifetimeResult = lifetimeAnalyzer.analyze(aliasResult.getAllocationSiteToValue());

        if (showLifetime) {
            System.out.println("\n--- Lifetime Analysis ---");
            System.out.println(includeSystem
                ? "Destruction points:"
                : "Destruction points (user objects only):");
            for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry : lifetimeResult.getDestructionPoints().entrySet()) {
                AllocationSite site = entry.getKey();
                if (!includeSystem && !isUserAllocationSite(site)) continue;
                if (!matchesDebug(debugName, site)) continue;
                System.out.println("  " + site + " -> " + entry.getValue());
            }
            if (!lifetimeResult.getUnresolved().isEmpty()) {
                System.out.print("  Unresolved (cyclic or uncertain): ");
                boolean first = true;
                for (AllocationSite site : lifetimeResult.getUnresolved()) {
                    if (!includeSystem && !isUserAllocationSite(site)) continue;
                    if (!matchesDebug(debugName, site)) continue;
                    if (!first) System.out.print(", ");
                    System.out.print(site);
                    first = false;
                }
                System.out.println();
            }
        }

        // --- Destructor Insertion (always runs, output optional) ---
        DestructorInserter inserter = new DestructorInserter(module, resolver, lifetimeResult,
            aliasResult.getAllocationSiteToValue(), aliasResult);
        inserter.insert();

        // --- Optimization ---
        boolean optimize = true;
        if (optimize) {
            System.out.println("\n--- Running Optimizations ---");
            Optimizer optimizer = new Optimizer(module, aliasResult, escapeResult, lifetimeResult,
                aliasResult.getAllocationSiteToValue());
            optimizer.setEnableScalarReplacement(true);
            optimizer.setEnableDestructorSimplification(true);
            optimizer.setEnableDestructorInlining(true);
            optimizer.setEnableDeadDestructorElimination(true);
            optimizer.optimize();
        }

        // --- LLVM IR Generation ---
        System.out.println("\n--- Generating LLVM IR ---");
        LlvmGenerator llvmGen = new LlvmGenerator(module, resolver, aliasResult,
            entryClass, entryMethod, entryDescriptor, analysis.getReflectInfo());
        String llvmIR = llvmGen.generate();
        Path llPath = Paths.get("output.ll");
        try {
            Files.write(llPath, llvmIR.getBytes());
            System.out.println("LLVM IR written to output.ll");
        } catch (IOException e) {
            log.error("Failed to write LLVM IR", e);
        }

        if (showDestructor) {
            System.out.println("\n--- Destructor Insertion ---");
            System.out.println(includeSystem
                ? "--- After destructor insertion (all functions) ---"
                : "--- After destructor insertion (user functions only) ---");
            for (Function func : module.getFunctions()) {
                if (!includeSystem && !isUserFunction(func)) continue;
                if (!matchesDebug(debugName, func)) continue;
                System.out.println(func);
            }
        }

        // --- Compile and link to native executable ---
        if (!noCompile) {
            Path exePath = outputFile != null ? Paths.get(outputFile) : Paths.get("a.out");
            try {
                compileAndLink(llPath, exePath, usedSystemClasses);
                System.out.println("Native executable built successfully: " + exePath.toAbsolutePath());
            } catch (IOException | InterruptedException e) {
                log.error("Failed to build native executable", e);
                System.err.println("Build failed: " + e.getMessage());
            }
        } else {
            System.out.println("Skipping native compilation (--no-compile specified)");
        }
    }

    private void printCallGraph(ReachabilityAnalysis analysis, String entryClass,
                                String entryMethod, String entryDescriptor,
                                boolean includeSystem, String debugName) {
        MethodReference entry = new MethodReference(entryClass.replace('.', '/'), entryMethod, entryDescriptor);
        Map<MethodReference, Set<MethodReference>> graph = analysis.getCallGraph();
        Set<MethodReference> visited = new HashSet<>();
        Predicate<MethodReference> filter = mr -> {
            if (!includeSystem && isSystemClassName(mr.getOwner())) return false;
            return debugName == null || mr.toString().contains(debugName);
        };
        System.out.println("\nCall graph from entry:");
        printMethodTree(entry, graph, filter, visited, 0);
    }

    private void printMethodTree(MethodReference method, Map<MethodReference, Set<MethodReference>> graph,
                                 Predicate<MethodReference> filter, Set<MethodReference> visited, int depth) {
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

    private void compileAndLink(Path llPath, Path exePath, Set<String> usedSystemClasses)
            throws IOException, InterruptedException {
        // Create a temporary directory for all intermediate files
        Path tempDir = Files.createTempDirectory("jnative_build_");
        tempDir.toFile().deleteOnExit();

        try {
            String compiler = "clang";
            try {
                Process p = new ProcessBuilder(compiler, "--version").start();
                if (p.waitFor() != 0) compiler = "gcc";
            } catch (IOException e) {
                compiler = "gcc";
            }

            // 1. Compile the main runtime
            Path runtimeC = extractRuntimeSource(tempDir);
            Path runtimeObj = tempDir.resolve("jnative_runtime.o");
            compileCSource(compiler, runtimeC, runtimeObj);

            // 2. Compile additional C files for system classes
            List<Path> extraSources = extractSystemNativeSources(usedSystemClasses, tempDir);
            List<Path> extraObjs = new ArrayList<>();
            for (Path src : extraSources) {
                String objName = src.getFileName().toString().replaceAll("\\.c$", ".o");
                Path obj = tempDir.resolve(objName);
                compileCSource(compiler, src, obj);
                extraObjs.add(obj);
            }

            // 3. Compile LLVM IR into an object file
            Path objPath = tempDir.resolve(exePath.getFileName().toString() + ".o");
            ProcessBuilder pb = new ProcessBuilder(compiler, "-c", "-O2", llPath.toString(), "-o", objPath.toString());
            pb.inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) throw new RuntimeException("Compilation failed with exit code " + exit);

            // 4. Link all object files
            List<String> linkCmd = new ArrayList<>();
            linkCmd.add(compiler);
            linkCmd.add(objPath.toString());
            linkCmd.add(runtimeObj.toString());
            for (Path obj : extraObjs) linkCmd.add(obj.toString());
            linkCmd.add("-o");
            linkCmd.add(exePath.toString());
            linkCmd.add("-lpthread");
            pb = new ProcessBuilder(linkCmd);
            pb.inheritIO();
            exit = pb.start().waitFor();
            if (exit != 0) throw new RuntimeException("Linking failed with exit code " + exit);

        } finally {
            // Recursively delete the entire temporary directory (including all files)
            Files.walk(tempDir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    private void compileCSource(String compiler, Path src, Path obj) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(compiler, "-c", "-O2", src.toString(), "-o", obj.toString());
        pb.inheritIO();
        int exit = pb.start().waitFor();
        if (exit != 0) throw new RuntimeException("Compilation of " + src + " failed with exit code " + exit);
    }

    /**
     * Returns the resource path of the native C implementation for the given class,
     * or null if there is no mapping for its package.
     */
    private String getNativeResourcePath(String className) {
        if (!className.startsWith("java/")) {
            return null;
        }
        String subPath = className.substring("java/".length());
        return NATIVE_BASE_PATH + subPath + ".c";
    }

    private boolean hasNativeSupport(String className) {
        String path = getNativeResourcePath(className);
        if (path == null) return false;
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            return is != null;
        } catch (IOException e) {
            return false;
        }
    }

    private List<Path> extractSystemNativeSources(Set<String> usedClasses, Path tempDir) throws IOException {
        List<Path> extracted = new ArrayList<>();
        for (String cls : usedClasses) {
            if (!hasNativeSupport(cls)) continue;
            String resourcePath = getNativeResourcePath(cls);
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) continue;
                String fileName = cls.replace('/', '_') + ".c";
                Path tempFile = tempDir.resolve("jnative_" + fileName);
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                extracted.add(tempFile);
            }
        }
        return extracted;
    }

    private Path extractRuntimeSource(Path tempDir) throws IOException {
        Path runtimeC = tempDir.resolve("jnative_runtime.c");
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RUNTIME_RESOURCE_PATH)) {
            if (in == null) {
                throw new IOException("jnative_runtime.c not found in resources (" + RUNTIME_RESOURCE_PATH + ")");
            }
            Files.copy(in, runtimeC, StandardCopyOption.REPLACE_EXISTING);
        }
        return runtimeC;
    }

    private static boolean matchesDebug(String debugName, String name) {
        if (debugName == null) return true;
        return name != null && name.contains(debugName);
    }

    private static boolean matchesDebug(String debugName, Function func) {
        if (debugName == null) return true;
        return func != null && func.getName() != null && func.getName().contains(debugName);
    }

    private static boolean matchesDebug(String debugName, AllocationSite site) {
        if (debugName == null) return true;
        return site != null && site.getMethodName() != null && site.getMethodName().contains(debugName);
    }

    private static boolean isAllocation(Opcode op) {
        return op == Opcode.NEW || op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY;
    }

    private static boolean isSystemClassName(String className) {
        String dot = className.replace('/', '.');
        return dot.startsWith("java.") ||
            dot.startsWith("javax.") ||
            dot.startsWith("sun.") ||
            dot.startsWith("jdk.") ||
            dot.startsWith("org.objectweb.asm.") ||
            dot.startsWith("picocli.") ||
            dot.startsWith("reactor.") ||
            dot.startsWith("org.slf4j.") ||
            dot.startsWith("org.reactivestreams.") ||
            dot.startsWith("io.micrometer.") ||
            dot.startsWith("org.junit.") ||
            dot.startsWith("com.fasterxml.");
    }

    private static boolean isSystemClass(String functionName) {
        String[] parts = functionName.split("\\.");
        if (parts.length < 1) return false;
        String className = parts[0].replace('/', '.');
        return isSystemClassName(className);
    }

    private static boolean isUserFunction(Function func) {
        String name = func.getName();
        if (name.startsWith("__destruct_")) {
            String className = name.substring("__destruct_".length()).replace('_', '/');
            return !isSystemClassName(className);
        }
        int dotIdx = name.indexOf('.');
        String className = dotIdx > 0 ? name.substring(0, dotIdx) : name;
        return !isSystemClassName(className);
    }

    private static boolean isUserAllocationSite(AllocationSite site) {
        Type type = site.getType();
        if (type == null || type.isUnknown()) {
            return false;
        }
        if (type.isArray()) {
            Type elem = type.getElementType();
            if (elem.isPrimitive()) return false;
            if (elem.isReference()) {
                return !isSystemClassName(elem.getClassName());
            }
            return false;
        }
        if (type.isReference()) {
            return !isSystemClassName(type.getClassName());
        }
        return false;
    }
}