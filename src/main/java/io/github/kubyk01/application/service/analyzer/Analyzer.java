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
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.Instruction;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.BasicBlock;
import io.github.kubyk01.domain.analyzer.ir.Opcode;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.port.primary.AnalyzerPort;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class Analyzer implements AnalyzerPort {

    @Override
    public void analyze(Path path, String entryClass, String entryMethod, String entryDescriptor,
                        boolean showClasses, boolean showAlias, boolean showEscape, boolean showLifetime, boolean showDestructor) {
        DependencyResolver resolver = new DependencyResolver();
        try {
            resolver.scan(path);
        } catch (IOException e) {
            log.error("Failed to scan path: {}", path, e);
            return;
        }

        System.out.println("Parsed classes: " + resolver.getAllClasses().size());

        ReachabilityAnalysis analysis = new ReachabilityAnalysis(resolver);
        analysis.applyMetadata(resolver.getMetadata());
        analysis.analyzeFromEntry(entryClass, entryMethod, entryDescriptor);

        Set<String> allClasses = analysis.getReachableClasses();
        Set<MethodReference> allMethods = analysis.getReachableMethods();

        // Filter to output only user classes and methods
        Set<String> userClasses = allClasses.stream()
            .filter(c -> !isSystemClassName(c))
            .collect(Collectors.toSet());

        Set<MethodReference> userMethods = allMethods.stream()
            .filter(m -> !isSystemClass(m.getOwner() + "." + m.getName()))
            .collect(Collectors.toSet());

        if (showClasses) {
            System.out.println("\nUser classes (" + userClasses.size() + "):");
            userClasses.stream().sorted().forEach(c -> System.out.println("  " + c));

            System.out.println("\nUser methods (" + userMethods.size() + "):");
            userMethods.stream()
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

        // --- Alias Analysis (always runs, output optional) ---
        AliasAnalyzer aliasAnalyzer = new AliasAnalyzer(module);
        AliasAnalysisResult aliasResult = aliasAnalyzer.analyze();

        if (showAlias) {
            System.out.println("\n--- Alias Analysis ---");
            System.out.println("Alias analysis complete.");
            for (Function func : module.getFunctions()) {
                if (!isUserFunction(func)) continue;
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
        EscapeAnalyzer escapeAnalyzer = new EscapeAnalyzer(module, aliasResult);
        EscapeAnalysisResult escapeResult = escapeAnalyzer.analyze();

        if (showEscape) {
            System.out.println("\n--- Escape Analysis ---");
            System.out.println("Escape analysis complete.");
            for (Function func : module.getFunctions()) {
                if (!isUserFunction(func)) continue;
                for (BasicBlock block : func.getBlocks()) {
                    for (Instruction inst : block.getInstructions()) {
                        if (isAllocation(inst.getOpcode()) && inst.getResult() != null) {
                            PointsToSet pts = aliasResult.getPointsTo(inst.getResult());
                            for (AllocationSite site : pts.getSites()) {
                                if (!isUserAllocationSite(site)) continue;
                                EscapeStatus status = escapeResult.getSiteStatus(site);
                                System.out.println("  " + site + " -> " + status);
                            }
                        }
                    }
                }
            }
        }

        // --- Lifetime Analysis (always runs, output optional) ---
        LifetimeAnalyzer lifetimeAnalyzer = new LifetimeAnalyzer(module, aliasResult, escapeResult);
        LifetimeAnalysisResult lifetimeResult = lifetimeAnalyzer.analyze(aliasResult.getAllocationSiteToValue());

        if (showLifetime) {
            System.out.println("\n--- Lifetime Analysis ---");
            System.out.println("Destruction points (user objects only):");
            for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry : lifetimeResult.getDestructionPoints().entrySet()) {
                AllocationSite site = entry.getKey();
                if (!isUserAllocationSite(site)) continue;
                System.out.println("  " + site + " -> " + entry.getValue());
            }
            if (!lifetimeResult.getUnresolved().isEmpty()) {
                System.out.print("  Unresolved (cyclic or uncertain): ");
                boolean first = true;
                for (AllocationSite site : lifetimeResult.getUnresolved()) {
                    if (isUserAllocationSite(site)) {
                        if (!first) System.out.print(", ");
                        System.out.print(site);
                        first = false;
                    }
                }
                System.out.println();
            }
        }

        // --- Destructor Insertion (always runs, output optional) ---
        DestructorInserter inserter = new DestructorInserter(module, resolver, lifetimeResult,
            aliasResult.getAllocationSiteToValue(), aliasResult);
        inserter.insert();

        // --- Optimization ---
        boolean optimize = true; // can be moved to parameters
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
        LlvmGenerator llvmGen = new LlvmGenerator(module, resolver, aliasResult, escapeResult,
                entryClass, entryMethod, entryDescriptor);
        String llvmIR = llvmGen.generate();
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("output.ll"), llvmIR.getBytes());
            System.out.println("LLVM IR written to output.ll");
        } catch (IOException e) {
            log.error("Failed to write LLVM IR", e);
        }

        if (showDestructor) {
            System.out.println("\n--- Destructor Insertion ---");
            System.out.println("--- After destructor insertion (user functions only) ---");
            for (Function func : module.getFunctions()) {
                if (isUserFunction(func)) {
                    System.out.println(func);
                }
            }
        }
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
        String type = site.getType();
        if (type == null || type.isEmpty()) {
            return false;
        }

        if (type.startsWith("array") || type.startsWith("multiarray") ||
            type.equals("unknown") || type.equals("<unknown>")) {
            return false;
        }
        return !isSystemClassName(type);
    }
}