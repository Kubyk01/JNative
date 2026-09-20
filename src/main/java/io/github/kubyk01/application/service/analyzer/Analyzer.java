package io.github.kubyk01.application.service.analyzer;

import io.github.kubyk01.application.service.analyzer.aliasanalysis.AliasAnalyzer;
import io.github.kubyk01.application.service.analyzer.escapeanalysis.EscapeAnalyzer;
import io.github.kubyk01.application.service.analyzer.lifetime.LifetimeAnalyzer;
import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityAnalysis;
import io.github.kubyk01.application.service.analyzer.ssa.BytecodeToIr;
import io.github.kubyk01.application.service.analyzer.ssa.MethodTranslator;
import io.github.kubyk01.application.service.analyzer.ssa.OutOfSsaPass;
import io.github.kubyk01.application.service.analyzer.ssa.SSATransformer;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmGenerator;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver;
import io.github.kubyk01.application.service.optimizer.DeadCodeEliminator;
import io.github.kubyk01.application.service.optimizer.Optimizer;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.IrBuilder;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.port.primary.AnalyzerPort;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.github.kubyk01.util.LlvmUtil.isAllocation;

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

        Set<String> usedSystemClasses = new HashSet<>();
        for (String cls : allClasses) {
            if (isSystemClassName(cls) && hasNativeSupport(cls)) {
                usedSystemClasses.add(cls);
            }
        }

        Set<String> polymorphicClasses = new HashSet<>();
        for (String cls : allClasses) {
            ClassNode node = resolver.getClassNode(cls);
            if (node != null && node.getPolymorphicMethodNames() != null && !node.getPolymorphicMethodNames().isEmpty()) {
                polymorphicClasses.add(cls);
            }
        }
        Set<String> toLoad = new HashSet<>(polymorphicClasses);
        toLoad.retainAll(usedSystemClasses);
        PolymorphicResolver polymorphicResolver = new PolymorphicResolver();
        polymorphicResolver.loadAll(toLoad);
        if (toLoad.isEmpty()) {
            log.debug("No polymorphic native methods found; skipping native registry load.");
        } else {
            log.debug("Loaded native methods for polymorphic classes: {}", toLoad);
        }

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

        System.out.println("\n--- Translating to IR and applying SSA ---");
        System.out.println("Total methods to translate: " + allMethods.size());

        BytecodeToIr translator = new BytecodeToIr(resolver, analysis);
        Module module = translator.translate();

        SSATransformer ssaTransformer = new SSATransformer();
        for (Function func : module.getFunctions()) {
            ssaTransformer.transform(func);
        }

        Set<String> clinitNamesBeforeDce = new HashSet<>();
        for (Function f : module.getFunctions()) {
            if (f.getName().endsWith("__clinit____V")) {
                clinitNamesBeforeDce.add(f.getName());
            }
        }

        System.out.println("\n--- Running dead code elimination ---");
        DeadCodeEliminator dce = new DeadCodeEliminator(module);
        dce.eliminate();

        int resurrected = resurrectRemovedClinits(module, resolver);
        if (resurrected > 0) {
            System.out.println("Resurrected " + resurrected
                + " <clinit> function(s) removed by DCE.");
        }

        int ensured = ensureReferencedClinitsPresent(module, resolver);
        if (ensured > 0) {
            System.out.println("Added " + ensured
                + " missing <clinit> function(s) for referenced classes.");
        }

        int declaredNatives = declareMissingNativeTargets(module, resolver);
        if (declaredNatives > 0) {
            System.out.println("Declared " + declaredNatives
                + " missing native-method symbol(s).");
        }

        Set<String> clinitNamesAfterDce = new HashSet<>();
        for (Function f : module.getFunctions()) {
            if (f.getName().endsWith("__clinit____V")) {
                clinitNamesAfterDce.add(f.getName());
            }
        }
        Set<String> stillMissing = new HashSet<>(clinitNamesBeforeDce);
        stillMissing.removeAll(clinitNamesAfterDce);
        if (!stillMissing.isEmpty()) {
            log.debug("Empty <clinit> function(s) dropped by DCE (no remaining references): {}",
                stillMissing);
        }

        List<Function> clinitFunctions = new ArrayList<>();
        for (Function func : module.getFunctions()) {
            if (func.getName().endsWith("__clinit____V")) {
                clinitFunctions.add(func);
            }
        }
        clinitFunctions = sortClinitFunctions(clinitFunctions, resolver, module);

        if (!clinitFunctions.isEmpty()) {
            System.out.println("Processing " + clinitFunctions.size()
                + " static initializers (<clinit>) first...");

            Module clinitModule = new Module();
            for (Function func : clinitFunctions) {
                clinitModule.addFunction(func);
            }

            AliasAnalyzer clinitAlias = new AliasAnalyzer(clinitModule);
            clinitAlias.analyze();
        }

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
                                System.out.println("  " + func.getName() + " : "
                                    + inst.getResult() + " -> " + pts);
                            }
                        }
                    }
                }
            }
        }

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

        DestructorInserter inserter = new DestructorInserter(module, resolver, lifetimeResult,
            aliasResult.getAllocationSiteToValue(), aliasResult);
        inserter.insert();

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

        analysis.registerClassLiterals(module);
        OutOfSsaPass.transform(module);

        System.out.println("\n--- Generating LLVM IR ---");
        LlvmGenerator llvmGen = new LlvmGenerator(module, resolver, aliasResult,
            entryClass, entryMethod, entryDescriptor, analysis.getReflectInfo(),
            polymorphicResolver);
        llvmGen.setClinitFunctions(clinitFunctions);
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

        if (!noCompile) {
            Path exePath = outputFile != null ? Paths.get(outputFile) : Paths.get("a.out");
            try {
                Set<String> classesToCompile = new HashSet<>(usedSystemClasses);
                classesToCompile.addAll(collectReferencedNativeClasses(module, resolver));
                log.debug("System classes scheduled for compilation: {}", classesToCompile);

                compileAndLink(llPath, exePath, classesToCompile);
                System.out.println("Native executable built successfully: " + exePath.toAbsolutePath());
            } catch (IOException | InterruptedException e) {
                log.error("Failed to build native executable", e);
                System.err.println("Build failed: " + e.getMessage());
            }
        } else {
            System.out.println("Skipping native compilation (--no-compile specified)");
        }
    }

    private int resurrectRemovedClinits(Module module, DependencyResolver resolver) {
        int total = 0;
        boolean anyAdded = true;
        int guard = 0;
        final int maxPasses = 8;

        while (anyAdded && guard++ < maxPasses) {
            anyAdded = false;

            Set<String> neededOwners = collectTriggeredOwners(module);

            for (String owner : neededOwners) {
                ClassNode cn = resolver.getClassNode(owner);
                if (cn == null) continue;

                if (!cn.isExternal() && resolver.getClassBytes(owner) == null) {
                    resolver.reloadSystemClass(owner);
                    cn = resolver.getClassNode(owner);
                    if (cn == null) continue;
                }

                if (cn.isExternal() || cn.isInterface()) continue;

                boolean declaresClinit = false;
                for (MethodNode mn : cn.getMethods()) {
                    if (mn.getName().equals("<clinit>") && mn.getDescriptor().equals("()V")) {
                        declaresClinit = true;
                        break;
                    }
                }
                if (!declaresClinit) continue;

                String clinitName = LlvmRuntime.mangleMethod(owner, "<clinit>", "()V");
                if (module.getFunction(clinitName) != null) continue;

                if (resolver.getClassBytes(owner) == null) continue;

                Function resurrected = retranslateClinit(owner, module, resolver);
                if (resurrected != null) {
                    total++;
                    anyAdded = true;
                    log.debug("Resurrected <clinit> of {}", owner);
                }
            }
        }
        return total;
    }

    private int ensureReferencedClinitsPresent(Module module, DependencyResolver resolver) {
        int added = 0;
        boolean changed = true;
        int guard = 0;
        final int maxPasses = 8;

        while (changed && guard++ < maxPasses) {
            changed = false;
            Set<String> referenced = collectTriggeredOwners(module);
            for (String owner : referenced) {
                ClassNode cn = resolver.getClassNode(owner);
                if (cn == null || cn.isExternal() || cn.isInterface()) continue;

                if (resolver.getClassBytes(owner) == null) {
                    resolver.reloadSystemClass(owner);
                    cn = resolver.getClassNode(owner);
                    if (cn == null || cn.isExternal() || cn.isInterface()) continue;
                }

                boolean declaresClinit = false;
                for (MethodNode mn : cn.getMethods()) {
                    if (mn.getName().equals("<clinit>") && mn.getDescriptor().equals("()V")) {
                        declaresClinit = true;
                        break;
                    }
                }
                if (!declaresClinit) continue;

                String clinitName = LlvmRuntime.mangleMethod(owner, "<clinit>", "()V");
                if (module.getFunction(clinitName) != null) continue;
                if (resolver.getClassBytes(owner) == null) continue;

                Function resurrected = retranslateClinit(owner, module, resolver);
                if (resurrected != null) {
                    added++;
                    changed = true;
                    log.debug("Ensured <clinit> of {}", owner);
                }
            }
        }
        return added;
    }

    private Set<String> collectTriggeredOwners(Module module) {
        Set<String> owners = new HashSet<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() == null) continue;
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op == Opcode.GET_STATIC || op == Opcode.PUT_STATIC
                        || op == Opcode.NEW || op == Opcode.STATIC_CALL) {
                        String owner = extractTriggerOwner(inst);
                        if (owner != null && !owner.isEmpty()) {
                            owners.add(owner);
                        }
                    } else if (op == Opcode.CALL) {
                        String callee = extractCalleeConstant(inst, 0);
                        if (callee != null) {
                            int dot = callee.lastIndexOf('.');
                            int paren = callee.indexOf('(');
                            if (dot > 0 && paren > dot) {
                                owners.add(callee.substring(0, dot));
                            }
                        }
                    }
                }
            }
        }
        return owners;
    }

    private Function retranslateClinit(String className, Module module, DependencyResolver resolver) {
        byte[] bytes = resolver.getClassBytes(className);
        if (bytes == null) return null;

        MethodReference ref = new MethodReference(className, "<clinit>", "()V");
        IrBuilder builder = new IrBuilder(module);
        MethodTranslator translator = new MethodTranslator(ref, true, builder, resolver);

        try {
            ClassReader reader = new ClassReader(bytes);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    if (name.equals("<clinit>") && desc.equals("()V")) {
                        return translator;
                    }
                    return null;
                }
            }, ClassReader.SKIP_DEBUG);
        } catch (Exception e) {
            log.warn("Failed to re-translate <clinit> of {}: {}", className, e.getMessage());
            return null;
        }

        Function func = translator.getCurrentFunction();
        if (func == null) return null;

        try {
            new SSATransformer().transform(func);
        } catch (Exception e) {
            log.warn("SSA transform failed for resurrected <clinit> of {}: {}",
                className, e.getMessage());
        }
        return func;
    }

    private int declareMissingNativeTargets(Module module, DependencyResolver resolver) {
        int added = 0;
        boolean changed = true;
        int guard = 0;
        final int maxPasses = 8;

        while (changed && guard++ < maxPasses) {
            changed = false;

            Set<String> callees = new HashSet<>();
            for (Function f : new ArrayList<>(module.getFunctions())) {
                if (f.getEntryBlock() == null) continue;
                for (BasicBlock b : f.getBlocks()) {
                    for (Instruction inst : b.getInstructions()) {
                        Opcode op = inst.getOpcode();
                        if (op != Opcode.STATIC_CALL && op != Opcode.CALL) continue;
                        String callee = extractCalleeConstant(inst, 0);
                        if (callee != null) callees.add(callee);
                    }
                }
            }

            for (String calleeName : callees) {
                int dotIdx = calleeName.lastIndexOf('.');
                int parenIdx = calleeName.indexOf('(');
                if (dotIdx <= 0 || parenIdx <= dotIdx) continue;

                String owner = calleeName.substring(0, dotIdx);
                String methodPart = calleeName.substring(dotIdx + 1);
                int localParenIdx = parenIdx - dotIdx - 1;
                String methodName = methodPart.substring(0, localParenIdx);
                String descriptor = methodPart.substring(localParenIdx);

                String[] foundOwner = new String[1];
                MethodNode mn = resolver.findMethodInHierarchy(
                    owner, methodName, descriptor, foundOwner);
                if (mn == null || !mn.isNative()) continue;

                String actualOwner = foundOwner[0] != null ? foundOwner[0] : owner;
                String nativeName = "__jnative_"
                    + LlvmRuntime.mangleMethod(actualOwner, methodName, descriptor);
                if (module.getFunction(nativeName) != null) continue;

                Type retType = TypeResolver.descToReturnType(descriptor);
                List<Type> paramTypes = TypeResolver.descToParamTypes(descriptor);
                Function func = new Function(nativeName, retType);
                for (int i = 0; i < paramTypes.size(); i++) {
                    func.addParameter(new Parameter(paramTypes.get(i), i));
                }
                module.addFunction(func);
                added++;
                changed = true;
            }
        }
        return added;
    }

    private List<Function> sortClinitFunctions(List<Function> clinitFunctions,
                                               DependencyResolver resolver,
                                               Module module) {
        if (clinitFunctions.isEmpty()) return clinitFunctions;

        Map<String, Function> nameToFunc = new HashMap<>();
        for (Function f : module.getFunctions()) {
            nameToFunc.put(f.getName(), f);
        }

        Map<String, Set<String>> directReads = new HashMap<>();
        Map<String, Set<String>> directWrites = new HashMap<>();
        Map<String, Set<String>> directCallees = new HashMap<>();

        for (Function f : module.getFunctions()) {
            Set<String> reads = new HashSet<>();
            Set<String> writes = new HashSet<>();
            Set<String> callees = new HashSet<>();
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op == Opcode.GET_STATIC) {
                        String n = extractFieldNameConst(inst, 0);
                        if (n != null) reads.add(n);
                    } else if (op == Opcode.PUT_STATIC) {
                        String n = extractFieldNameConst(inst, 0);
                        if (n != null) writes.add(n);
                    } else if (op == Opcode.STATIC_CALL || op == Opcode.CALL) {
                        String callee = extractCalleeConstant(inst, 0);
                        if (callee != null) {
                            String mangled = nameToFunc.containsKey(callee)
                                ? callee
                                : LlvmRuntime.mangleCallable(callee);
                            if (nameToFunc.containsKey(mangled)) {
                                callees.add(mangled);
                            }
                        }
                    }
                }
            }
            directReads.put(f.getName(), reads);
            directWrites.put(f.getName(), writes);
            directCallees.put(f.getName(), callees);
        }

        Map<String, Set<String>> transReads = new HashMap<>();
        Map<String, Set<String>> transWrites = new HashMap<>();
        for (String name : directReads.keySet()) {
            transReads.put(name, new HashSet<>(directReads.get(name)));
            transWrites.put(name, new HashSet<>(directWrites.get(name)));
        }

        boolean changed = true;
        int guard = 0;
        final int maxPasses = Math.max(32, module.getFunctions().size() / 4 + 8);

        while (changed && guard++ < maxPasses) {
            changed = false;
            for (String name : directReads.keySet()) {
                Set<String> r = transReads.get(name);
                Set<String> w = transWrites.get(name);
                int rOld = r.size();
                int wOld = w.size();
                for (String callee : directCallees.get(name)) {
                    Set<String> cr = transReads.get(callee);
                    Set<String> cw = transWrites.get(callee);
                    if (cr != null) r.addAll(cr);
                    if (cw != null) w.addAll(cw);
                }
                if (r.size() != rOld || w.size() != wOld) changed = true;
            }
        }

        Map<String, Set<Function>> writersOfGlobal = new HashMap<>();
        for (Function clinit : clinitFunctions) {
            Set<String> w = transWrites.get(clinit.getName());
            if (w == null) continue;
            for (String g : w) {
                writersOfGlobal.computeIfAbsent(g, k -> new LinkedHashSet<>()).add(clinit);
            }
        }

        Map<Function, Set<Function>> deps = new LinkedHashMap<>();
        for (Function clinit : clinitFunctions) {
            deps.put(clinit, new LinkedHashSet<>());
        }

        for (Function clinit : clinitFunctions) {
            Set<String> r = transReads.get(clinit.getName());
            if (r == null) continue;
            for (String g : r) {
                Set<Function> writers = writersOfGlobal.get(g);
                if (writers == null) continue;
                for (Function w : writers) {
                    if (w != clinit) {
                        deps.get(clinit).add(w);
                    }
                }
            }
        }

        Map<String, Function> classNameToClinit = new HashMap<>();
        for (ClassNode cn : resolver.getClassMap().values()) {
            String clinitName = LlvmRuntime.mangleMethod(cn.getName(), "<clinit>", "()V");
            Function f = nameToFunc.get(clinitName);
            if (f != null) {
                classNameToClinit.put(cn.getName(), f);
            }
        }

        for (Function func : clinitFunctions) {
            for (BasicBlock b : func.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op != Opcode.NEW && op != Opcode.GET_STATIC
                        && op != Opcode.PUT_STATIC && op != Opcode.STATIC_CALL) {
                        continue;
                    }
                    String owner = extractTriggerOwner(inst);
                    if (owner == null) continue;
                    Function dep = classNameToClinit.get(owner);
                    if (dep != null && dep != func) {
                        deps.get(func).add(dep);
                    }
                }
            }
        }

        List<Function> sorted = new ArrayList<>(clinitFunctions.size());
        Set<Function> visited = new HashSet<>();
        Set<Function> onStack = new HashSet<>();
        for (Function f : clinitFunctions) {
            dfsClinit(f, deps, visited, onStack, sorted);
        }

        if (sorted.size() != clinitFunctions.size()) {
            Set<Function> included = new HashSet<>(sorted);
            for (Function f : clinitFunctions) {
                if (!included.contains(f)) {
                    sorted.add(f);
                }
            }
        }

        return sorted;
    }

    private void dfsClinit(Function f,
                           Map<Function, Set<Function>> deps,
                           Set<Function> visited,
                           Set<Function> onStack,
                           List<Function> out) {
        if (visited.contains(f)) return;
        if (!onStack.add(f)) return;
        for (Function dep : deps.getOrDefault(f, Collections.emptySet())) {
            dfsClinit(dep, deps, visited, onStack, out);
        }
        onStack.remove(f);
        visited.add(f);
        out.add(f);
    }

    private static String extractFieldNameConst(Instruction inst, int idx) {
        if (inst.getOperands().size() <= idx) return null;
        Value v = inst.getOperands().get(idx);
        if (v instanceof Constant c && c.getType().isReference()) {
            return c.getValue().toString();
        }
        return null;
    }

    private static String extractCalleeConstant(Instruction inst, int operandIdx) {
        if (inst.getOperands().size() <= operandIdx) return null;
        Value v = inst.getOperands().get(operandIdx);
        if (v instanceof Constant c && c.getType().isReference()) {
            return c.getValue().toString();
        }
        return null;
    }

    private static String extractTriggerOwner(Instruction inst) {
        Opcode op = inst.getOpcode();
        if (op == Opcode.NEW) {
            if (!inst.getOperands().isEmpty()) {
                Value v = inst.getOperands().getFirst();
                if (v instanceof Constant c && c.getType().isReference()) {
                    return c.getValue().toString();
                }
            }
            return null;
        }
        if (op == Opcode.GET_STATIC || op == Opcode.PUT_STATIC || op == Opcode.STATIC_CALL) {
            if (!inst.getOperands().isEmpty()) {
                Value v = inst.getOperands().getFirst();
                if (v instanceof Constant c && c.getType().isReference()) {
                    return extractOwnerClassName(c.getValue().toString());
                }
            }
            return null;
        }
        return null;
    }

    private static String extractOwnerClassName(String ref) {
        int dot = ref.lastIndexOf('.');
        return dot <= 0 ? ref : ref.substring(0, dot);
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

            Path runtimeC = extractRuntimeSource(tempDir);
            Path runtimeObj = tempDir.resolve("jnative_runtime.o");
            compileCSource(compiler, runtimeC, runtimeObj);

            List<Path> extraSources = extractSystemNativeSources(usedSystemClasses, tempDir);
            List<Path> extraObjs = new ArrayList<>();
            for (Path src : extraSources) {
                Path obj = src.getParent().resolve(src.getFileName().toString().replaceAll("\\.c$", ".o"));
                compileCSource(compiler, src, obj);
                extraObjs.add(obj);
            }

            Path objPath = tempDir.resolve(exePath.getFileName().toString() + ".o");
            ProcessBuilder pb = new ProcessBuilder(
                compiler, "-c", "-O3",
                "-g", "-fno-omit-frame-pointer",
                llPath.toString(), "-o", objPath.toString());
            pb.inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) throw new RuntimeException("Compilation failed with exit code " + exit);

            // ------------------------------------------------------------------
            // Link command.
            //
            // Order matters with GNU ld: archive members are pulled in only
            // when they satisfy a currently-undefined symbol, and the scan is
            // strictly left-to-right. Every object file that references a
            // library must therefore precede that library on the command line.
            //
            //   -lm       : libm. Required on x86-64 because LLVM lowers
            //               `frem {float,double,x86_fp80}` to `fmodf` /
            //               `fmod` / `fmodl`, and `frem` appears in the
            //               generated IR for every Java `%` on a float or
            //               double (see CompactNumberFormat.evalLOperand).
            //               Also covers `sin`, `cos`, `pow`, `sqrt` if the
            //               emitter ever starts producing those intrinsics.
            //   -lpthread : pthread_mutex_* and pthread_cond_*, used by the
            //               monitor table and the per-thread park table.
            //   -ldl      : dlopen(NULL) / dlsym / dlclose, used by every
            //               native that resolves a symbol from the process
            //               image at runtime (Class.c, Unsafe.c,
            //               NativeMethodAccessorImpl.c, SystemProps.c, …).
            //   -rdynamic : emits all global symbols into the dynamic symbol
            //               table so that dlopen(NULL)+dlsym can find the
            //               mangled `fn_*` and `__type_info_*` globals the
            //               LLVM module defines. Without it those helpers
            //               return NULL and every reflective or offset-based
            //               lookup silently fails.
            //
            // macOS does not have a separate libm — its math symbols live in
            // libSystem, which is linked implicitly — but `-lm` is accepted
            // and is a no-op, so it can be passed unconditionally without a
            // platform branch. MinGW provides an import library as libm.a,
            // so the same holds there.
            // ------------------------------------------------------------------
            List<String> linkCmd = new ArrayList<>();
            linkCmd.add(compiler);
            linkCmd.add(objPath.toString());
            linkCmd.add(runtimeObj.toString());
            for (Path obj : extraObjs) linkCmd.add(obj.toString());
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
            pb = new ProcessBuilder(linkCmd);
            pb.inheritIO();
            exit = pb.start().waitFor();
            if (exit != 0) throw new RuntimeException("Linking failed with exit code " + exit);

        } finally {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    private void compileCSource(String compiler, Path src, Path obj) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            compiler, "-c", "-O2",
            "-g", "-fno-omit-frame-pointer",
            src.toString(), "-o", obj.toString());
        pb.inheritIO();
        int exit = pb.start().waitFor();
        if (exit != 0) throw new RuntimeException("Compilation of " + src + " failed with exit code " + exit);
    }

    private String getNativeResourcePath(String className) {
        if (className.startsWith("java/")) {
            return NATIVE_BASE_PATH + className.substring("java/".length()) + ".c";
        }
        return NATIVE_BASE_PATH + className + ".c";
    }

    private boolean hasNativeSupport(String className) {
        String path = getNativeResourcePath(className);
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
                Path targetPath = tempDir.resolve(cls.replace('/', java.io.File.separatorChar) + ".c");
                Files.createDirectories(targetPath.getParent());
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                extracted.add(targetPath);
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

    private Set<String> collectReferencedNativeClasses(Module module,
                                                       DependencyResolver resolver) {
        Set<String> result = new HashSet<>();
        for (String cls : new ArrayList<>(resolver.getClassMap().keySet())) {
            if (!isSystemClassName(cls)) continue;
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