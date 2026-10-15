package io.github.kubyk01.application.service.analyzer;

import io.github.kubyk01.application.service.analyzer.aliasanalysis.AliasAnalyzer;
import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.escapeanalysis.EscapeAnalyzer;
import io.github.kubyk01.application.service.analyzer.lifetime.LifetimeAnalyzer;
import io.github.kubyk01.application.service.analyzer.ssa.BytecodeToIr;
import io.github.kubyk01.application.service.analyzer.ssa.MethodTranslator;
import io.github.kubyk01.application.service.analyzer.ssa.SSATransformer;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.application.service.optimizer.DeadCodeEliminator;
import io.github.kubyk01.application.service.optimizer.LazyClinitInstrumenter;
import io.github.kubyk01.domain.analyzer.AnalyzerResult;
import io.github.kubyk01.domain.analyzer.ClinitScheduleEntry;
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
import io.github.kubyk01.util.LlvmUtil;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.kubyk01.util.LlvmUtil.isAllocation;

/**
 * Orchestrates the analysis pipeline for a compiled module.
 *
 * <h2>Parallelism</h2>
 *
 * <p>Every parallel phase in this class — the call-graph construction,
 * the scope/nested-clinit construction, the triggered-class collection,
 * the field writer/reader collection, and the lazy-clinit
 * instrumentation — runs on the {@link Scheduler} that
 * {@link io.github.kubyk01.application.service.Orchestrator} creates
 * once and hands in through {@link #setScheduler(Scheduler)}. The class
 * never calls {@code parallelStream()} and therefore never touches
 * {@code ForkJoinPool.commonPool()}; the {@code common.parallelism}
 * system property is irrelevant to it.</p>
 *
 * <p>The scheduler is not owned by this class. The orchestrator disposes
 * it after the analysis pipeline has finished; the default
 * {@link Schedulers#single()} that is used when no scheduler has been
 * provided keeps the class usable from unit tests that never touch the
 * reactor runtime at all.</p>
 *
 * <h2>Removed pre-analysis pass</h2>
 *
 * <p>Earlier revisions of this class ran a separate {@link AliasAnalyzer}
 * pass over a synthetic {@link Module} containing only the {@code <clinit>}
 * bodies, immediately before the real alias analysis. That pre-pass was
 * dead code: its result was thrown away, and the same functions were
 * re-analyzed a few lines later as part of the full module. It doubled
 * the cost of the two fixed-point passes inside {@link AliasAnalyzer}
 * (the summary builder and the points-to propagation) for every class
 * initializer in the image, and on a full JDK reachability closure it
 * was the dominant contributor to wall-clock time. It has been removed;
 * the single remaining pass over the full module already covers every
 * {@code <clinit>} body, because the sort that precedes it only reorders
 * the functions, it does not remove them.</p>
 */
@Slf4j
public class Analyzer {

    /**
     * Scheduler for every parallel phase in this class and in the
     * {@link AliasAnalyzer} it constructs. Set by
     * {@link #setScheduler(Scheduler)} from
     * {@code Orchestrator}. Defaults to {@link Schedulers#single()} so
     * the class is usable from tests that do not care about parallelism.
     */
    private Scheduler scheduler = Schedulers.single();

    public void setScheduler(Scheduler scheduler) {
        if (scheduler != null) {
            this.scheduler = scheduler;
        }
    }

    /**
     * Mangled names of {@code <clinit>} functions the scheduler has deferred
     * to lazy initialization because their eager position conflicts with a
     * bootstrap phase. Populated by {@link #sortInitializers}; read by
     * {@link #analyze} and passed through to the lazy-clinit instrumenter.
     */
    private final Set<String> deferredClinits = new LinkedHashSet<>();

    /**
     * Optional IR translator that produced the module being analyzed.
     *
     * <p>When non-null, {@link #analyze} uses it to close over the IR
     * callees of every function it resurrects — most importantly the
     * {@code <clinit>} bodies added by
     * {@link #resurrectRemovedClinits} and
     * {@link #ensureReferencedClinitsPresent}. Those bodies call their
     * class's constructor and the synthetic {@code $values()} method,
     * and those callees may not be in the module yet; without closing
     * over them the LLVM emitter turns every such call into a runtime
     * {@code __jnative_unresolved_slot} trap.</p>
     *
     * <p>The orchestrator always sets this before invoking
     * {@link #analyze}. The default of {@code null} keeps the class
     * usable from unit tests that construct a module by hand and do not
     * care about the closure.</p>
     */
    @Setter
    private BytecodeToIr translator;

    public AnalyzerResult analyze(Module module,
                                  DependencyResolver resolver,
                                  String entryClass,
                                  String entryMethod,
                                  String entryDescriptor,
                                  boolean includeSystem,
                                  String debugName,
                                  boolean showAlias,
                                  boolean showEscape,
                                  boolean showLifetime) {

        // --- 1. Dead code elimination -------------------------------------
        System.out.println("\n--- Running dead code elimination ---");
        DeadCodeEliminator dce = new DeadCodeEliminator(module);
        dce.eliminate();

        int resurrected = resurrectRemovedClinits(module, resolver);
        if (resurrected > 0) {
            System.out.println("Resurrected " + resurrected
                + " missing <clinit> function(s) into the module.");
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

        // ------------------------------------------------------------------
        // Close over the IR callees of every function resurrected above.
        //
        // resurrectRemovedClinits and ensureReferencedClinitsPresent add
        // <clinit> bodies directly to the module via retranslateClinit,
        // which builds only the one function it is asked for and does not
        // walk its outgoing call graph. The bodies it produces therefore
        // reference their class's constructor <init>(...)V and the
        // synthetic $values() method by mangled name, without either
        // method having been translated into the module.
        //
        // The orchestrator's initial translate() pass would normally have
        // caught those missing callees through closeOverIrCallees(), but
        // that pass has already completed by the time this code runs: it
        // executes inside BytecodeToIr.translate(), long before Analyzer
        // is constructed. Without a second closure pass here, every call
        // site inside a resurrected <clinit> reaches the LLVM emitter
        // with a callee that has no body, and the emitter converts each
        // one into a call to __jnative_unresolved_slot followed by
        // unreachable.
        //
        // The concrete failure this closes is documented in the fun.txt
        // report: a resurrected
        // java/net/Authenticator$RequestorType.<clinit> called both
        // RequestorType.<init>(Ljava/lang/String;I)V and
        // RequestorType.$values(), neither of which was in the module,
        // and the resulting trap fired before main() had produced any
        // observable output.
        //
        // The closure is applied unconditionally rather than only when
        // resurrected + ensured > 0: it is a no-op when there is nothing
        // to translate, and gating it on a counter would silently miss a
        // future caller that adds functions to the module by another
        // route.
        // ------------------------------------------------------------------
        if (translator != null) {
            translator.closeOverIrCalleesWithSsa();
        }

        // --- 2. Collect + sort <clinit> functions -------------------------
        List<Function> clinitFunctions = new ArrayList<>();
        for (Function func : module.getFunctions()) {
            if (func.getName().endsWith("__clinit____V")) {
                clinitFunctions.add(func);
            }
        }

        // Collect the bootstrap phases System.initPhase1/2/3 as ordinary IR
        // functions and include them in the same dependency graph as the
        // <clinit>s. The topological sort itself places them in the correct
        // positions: any phase that (transitively) writes a static field is
        // placed before every initializer that reads that field; any phase
        // that (transitively) triggers a class is placed after its <clinit>.
        List<Function> phaseFunctions = collectPhaseFunctions(module, resolver);

        List<ClinitScheduleEntry> clinitSchedule = sortInitializers(
            clinitFunctions, phaseFunctions, resolver, module,
            entryClass, entryMethod, entryDescriptor);

        // The list of only the real <clinit>s (without the phases) is needed
        // by applyLazyClinitForCyclicClasses, because lazy instrumentation
        // makes sense only for class initializers, not for System methods.
        //
        // The eager schedule contains only the clinits that survived the
        // deferral step. Every deferred clinit still needs a wrapper so
        // that lazy triggering works; it is added to the instrumentation
        // pass explicitly.
        List<Function> clinitsToInstrument = new ArrayList<>();
        Set<String> scheduledNames = new HashSet<>();
        for (ClinitScheduleEntry e : clinitSchedule) {
            if (e.bootstrapPhase()) continue;
            Function f = e.function();
            clinitsToInstrument.add(f);
            scheduledNames.add(f.getName());
        }
        for (Function f : clinitFunctions) {
            if (deferredClinits.contains(f.getName())
                && !scheduledNames.contains(f.getName())) {
                clinitsToInstrument.add(f);
            }
        }

        Map<String, String> clinitWrappers = new HashMap<>();
        applyLazyClinitForCyclicClasses(module, resolver,
                                        clinitsToInstrument, clinitWrappers,
                                        deferredClinits);

        // --- 3. Alias analysis --------------------------------------------
        //
        // There used to be a separate AliasAnalyzer pass over a synthetic
        // Module containing only the <clinit> functions, run just before
        // this one. Its result was discarded — the AliasAnalysisResult
        // returned below is produced by the call on the full module, which
        // already contains every <clinit> body. That pre-pass therefore
        // doubled the cost of both fixed-points (SummaryBuilder and
        // InterproceduralPointsTo) for every class initializer in the
        // image, and it was the reason the "Processing N static
        // initializers" step dominated the wall clock on a full JDK
        // reachability closure. It is gone.
        AliasAnalyzer aliasAnalyzer = new AliasAnalyzer(module, scheduler);
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

        // --- 4. Escape analysis -------------------------------------------
        EscapeAnalyzer escapeAnalyzer =
            new EscapeAnalyzer(module, aliasResult, resolver, scheduler);
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

        // --- 5. Lifetime analysis -----------------------------------------
        Map<String, FunctionSummary> summaries = aliasResult.getFunctionSummaries();
        LifetimeAnalyzer lifetimeAnalyzer =
            new LifetimeAnalyzer(module, aliasResult, escapeResult, summaries);
        lifetimeAnalyzer.setScheduler(scheduler);
        LifetimeAnalysisResult lifetimeResult =
            lifetimeAnalyzer.analyze(aliasResult.getAllocationSiteToValue());

        if (showLifetime) {
            System.out.println("\n--- Lifetime Analysis ---");
            System.out.println(includeSystem
                ? "Destruction points:"
                : "Destruction points (user objects only):");
            for (Map.Entry<AllocationSite, Set<DestructionPoint>> entry
                : lifetimeResult.getDestructionPoints().entrySet()) {
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

        return new AnalyzerResult(
            clinitSchedule, clinitWrappers,
            Collections.unmodifiableSet(new LinkedHashSet<>(deferredClinits)),
            aliasResult, escapeResult, lifetimeResult);
    }

    // =====================================================================
    //  Lazy <clinit> instrumentation for cyclic initialization groups
    // =====================================================================

    private void applyLazyClinitForCyclicClasses(Module module,
                                                 DependencyResolver resolver,
                                                 List<Function> clinitFunctions,
                                                 Map<String, String> clinitWrappers,
                                                 Set<String> forcedDeferredClinits) {
        if (clinitFunctions.isEmpty()) return;

        Map<String, String> clinitNameToClassName = new HashMap<>();
        Map<String, String> classNameToClinitName = new HashMap<>();
        Set<String> clinitNames = new HashSet<>();
        for (Function f : clinitFunctions) clinitNames.add(f.getName());

        for (ClassNode cn : resolver.getClassMap().values()) {
            String clinitName = LlvmRuntime.mangleMethod(cn.getName(), "<clinit>", "()V");
            Function clinit = module.getFunction(clinitName);
            if (clinit == null || clinit.getEntryBlock() == null) continue;
            if (!clinitNames.contains(clinitName)) continue;
            clinitNameToClassName.put(clinitName, cn.getName());
            classNameToClinitName.put(cn.getName(), clinitName);
        }
        if (clinitNameToClassName.isEmpty()) return;

        Set<String> allWithClinit = new HashSet<>(classNameToClinitName.keySet());

        // Force-include any class that the scheduler deferred. A deferred
        // class is not in the cyclic classDeps graph computed below — the
        // cycle it participates in goes through the bootstrap phases,
        // which are not represented in classDeps at all — so without this
        // merge it would never receive a wrapper and its active-use sites
        // would never be instrumented. Then the deferred <clinit> would
        // simply never run, and the first read of a field it initializes
        // would observe null.
        //
        // We add the class to the cyclicClasses set below and let the
        // ordinary propagation and instrumentation passes do the rest.
        Set<String> forcedClasses = new LinkedHashSet<>();
        if (forcedDeferredClinits != null) {
            for (String clinitName : forcedDeferredClinits) {
                String cls = clinitNameToClassName.get(clinitName);
                if (cls != null && allWithClinit.contains(cls)) {
                    forcedClasses.add(cls);
                }
            }
        }

        // ---- Parallel call-graph construction (Reactor).
        //
        // The call graph is the only persistent structure this method
        // keeps alive. It maps every live function to the set of
        // functions it calls directly, and it is consumed twice: once
        // by the BFS in the next block and once more by the
        // activation-depth computation at the end. Its memory cost is
        // proportional to the number of direct call edges in the
        // module — on a full JDK closure a few hundred thousand
        // entries, a few tens of megabytes — which is the only large
        // data structure that survives the phase.
        Map<String, Set<String>> callGraph = new ConcurrentHashMap<>();
        List<Function> functionsSnapshot = new ArrayList<>(module.getFunctions());
        LlvmUtil.awaitMono(Flux.fromIterable(functionsSnapshot)
            .filter(f -> f.getEntryBlock() != null)
            .parallel()
            .runOn(scheduler)
            .doOnNext(func -> {
                Set<String> callees = new HashSet<>();
                for (BasicBlock block : func.getBlocks()) {
                    for (Instruction inst : block.getInstructions()) {
                        collectDirectCallees(inst, callees, module, resolver);
                    }
                }
                callGraph.put(func.getName(), callees);
            })
            .sequential()
            .then()
        );

        // ---- Single-pass scope walk for every <clinit> body.
        //
        // This block replaces three separate parallel passes that used
        // to run one after the other:
        //
        //   1. a pass that built a persistent Map<String, Set<String>>
        //      holding the entire reachable call graph of every
        //      <clinit> (the "clinitScope" map),
        //
        //   2. a pass that walked every such scope to build the
        //      per-class dependency deltas,
        //
        //   3. a pass that walked every such scope again to build the
        //      field-writer / field-reader maps.
        //
        // The old arrangement was O(#clinit x (V + E)) in memory, not
        // just in time. For each of the ~500 to ~2500 <clinit>
        // functions it retained a full LinkedHashSet of every
        // function reachable without crossing another <clinit>. On
        // the failing run that was several GB of live strings, all
        // strongly referenced by the clinitScope map for the entire
        // duration of the phase, none of it reachable by the garbage
        // collector. G1 filled the 4-GB heap to 99.9 %, the
        // concurrent cycle stopped making progress, and the JVM
        // eventually aborted with
        //
        //     OutOfMemoryError: Java heap space: failed reallocation
        //     of scalar replaced objects
        //
        // raised from C2's deoptimization path while the compiler was
        // itself running out of heap.
        //
        // The rework below performs the same BFS once per clinit, but
        // accumulates every derived fact DURING the walk and discards
        // the scope set as soon as the walk returns. The three
        // outputs the method actually needs — the set of directly
        // nested <clinit> bodies, the per-class dependency delta, and
        // the field writer/reader index — are all small relative to
        // the scope. The scope is the only thing that grows with the
        // reachable subgraph size, and it is exactly what the rework
        // eliminates.
        //
        // Each BFS is still bounded by one clinit's own reachability,
        // which is what makes the transient memory profile linear
        // rather than quadratic in the number of clinit bodies. The
        // instruction scan that used to run three times per scope
        // member (once per consuming pass) now runs exactly once,
        // folded into the BFS itself.
        Map<String, Set<String>> nestedClinits  = new ConcurrentHashMap<>();
        Map<String, Set<String>> classDepDeltas = new ConcurrentHashMap<>();
        Map<String, Set<String>> fieldWriters   = new ConcurrentHashMap<>();
        Map<String, Set<String>> fieldReaders   = new ConcurrentHashMap<>();

        LlvmUtil.awaitMono(Flux.fromIterable(clinitFunctions)
            .parallel()
            .runOn(scheduler)
            .doOnNext(clinit -> {
                String className = clinitNameToClassName.get(clinit.getName());
                if (className == null) return;

                // Transient visited set for the BFS. Bounded by this
                // clinit's own reachable call graph; the moment the
                // walk returns, the only reference to it dies with
                // this stack frame and the whole set becomes garbage.
                Set<String> visited = new HashSet<>();

                // Retained outputs. `nested` is small: one entry per
                // directly-triggered <clinit>. `delta` is small: one
                // entry per class this clinit's scope touches, which
                // on a large module is a few hundred at most.
                Set<String> nested = new LinkedHashSet<>();
                Set<String> delta  = new HashSet<>();

                Deque<String> worklist = new ArrayDeque<>();
                visited.add(clinit.getName());
                worklist.push(clinit.getName());

                while (!worklist.isEmpty()) {
                    String cur = worklist.pop();

                    // (a) Instruction scan. Every function the BFS
                    // reaches is scanned exactly once, here, while its
                    // name is on the stack. The previous revision
                    // walked each scope three separate times, and
                    // every one of those walks paid the same
                    // per-instruction cost; folding the scan into the
                    // BFS removes that threefold redundancy as a
                    // side-effect of the memory fix.
                    Function func = module.getFunction(cur);
                    if (func != null && func.getEntryBlock() != null) {
                        for (BasicBlock block : func.getBlocks()) {
                            for (Instruction inst : block.getInstructions()) {
                                Opcode op = inst.getOpcode();

                                if (op == Opcode.NEW
                                    || op == Opcode.GET_STATIC
                                    || op == Opcode.PUT_STATIC
                                    || op == Opcode.STATIC_CALL) {
                                    String owner = extractTriggerOwner(inst);
                                    if (owner != null
                                        && !owner.isEmpty()
                                        && !owner.equals(className)) {
                                        delta.add(owner);
                                    }
                                }

                                if (op == Opcode.GET_STATIC
                                    || op == Opcode.PUT_STATIC) {
                                    String field = extractStaticFieldKey(inst);
                                    if (field != null) {
                                        if (op == Opcode.PUT_STATIC) {
                                            fieldWriters
                                                .computeIfAbsent(field, k ->
                                                    ConcurrentHashMap.newKeySet())
                                                .add(className);
                                        } else {
                                            fieldReaders
                                                .computeIfAbsent(field, k ->
                                                    ConcurrentHashMap.newKeySet())
                                                .add(className);
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // (b) Call-graph expansion. A callee that is
                    // itself a <clinit> body is recorded in `nested`
                    // and not descended into: this clinit's scope
                    // deliberately stops at another class's
                    // initializer. A callee that is an ordinary
                    // function is pushed onto the worklist exactly
                    // once, guarded by `visited`.
                    Set<String> callees = callGraph.get(cur);
                    if (callees == null) continue;
                    for (String callee : callees) {
                        if (clinitNames.contains(callee)) {
                            nested.add(callee);
                            continue;
                        }
                        if (visited.add(callee)) {
                            worklist.push(callee);
                        }
                    }
                }

                // (c) Superclass chain. The delta also carries every
                // ancestor that declares a <clinit>, because JVMS
                // §5.5 requires a superclass initializer to run
                // before its subclass. This walk is independent of
                // the call graph; it is done here so that all of this
                // clinit's dependency edges are collected by exactly
                // one task.
                ClassNode cn = resolver.getClassNode(className);
                Set<String> supVisited = new HashSet<>();
                while (cn != null
                       && cn.getSuperName() != null
                       && !cn.getSuperName().equals(cn.getName())
                       && supVisited.add(cn.getSuperName())) {
                    String sup = cn.getSuperName();
                    if ("java/lang/Object".equals(sup)) break;
                    delta.add(sup);
                    cn = resolver.getClassNode(sup);
                }

                // (d) Nested clinit classes contribute to the delta
                // exactly as the superclass chain does. `nested` is
                // the set of clinit function names collected in step
                // (b); the corresponding class names are looked up
                // through the same reverse index the rest of the
                // method uses.
                for (String nestedClinit : nested) {
                    String nestedClass =
                        clinitNameToClassName.get(nestedClinit);
                    if (nestedClass != null
                        && !nestedClass.equals(className)) {
                        delta.add(nestedClass);
                    }
                }

                nestedClinits.put(clinit.getName(), nested);

                if (!delta.isEmpty()) {
                    classDepDeltas.compute(className, (k, existing) -> {
                        if (existing == null) return delta;
                        existing.addAll(delta);
                        return existing;
                    });
                }
            })
            .sequential()
            .then()
        );

        // ---- Class-dependency graph, assembled on the caller thread.
        //
        // The deltas collected by the parallel walk above are merged
        // here, single-threaded, so that the LinkedHashSet inside
        // each classDeps entry keeps a deterministic iteration order.
        // Sorting each incoming delta before inserting it is what
        // guarantees that two runs of the same build produce
        // byte-identical emitted IR; the parallel producers cannot
        // offer that guarantee on their own, because two threads can
        // observe the same call graph in different orders.
        Map<String, Set<String>> classDeps = new LinkedHashMap<>();
        for (String cls : allWithClinit) {
            classDeps.put(cls, new LinkedHashSet<>());
        }

        for (Map.Entry<String, Set<String>> entry : classDepDeltas.entrySet()) {
            Set<String> deps = classDeps.get(entry.getKey());
            if (deps == null) continue;
            List<String> sorted = new ArrayList<>(entry.getValue());
            Collections.sort(sorted);
            deps.addAll(sorted);
        }

        // ---- Field-provenance edges.
        //
        // A class that reads a static field written by another class
        // must be initialized after that writer's <clinit> has run.
        // The fieldWriters / fieldReaders maps built in the
        // single-pass walk above index both sides of that relation
        // by field key; the loop below materialises the edges into
        // classDeps.
        for (Map.Entry<String, Set<String>> entry : fieldReaders.entrySet()) {
            Set<String> writers = fieldWriters.get(entry.getKey());
            if (writers == null || writers.isEmpty()) continue;
            for (String reader : entry.getValue()) {
                if (writers.contains(reader)) continue;
                Set<String> readerDeps = classDeps.get(reader);
                if (readerDeps == null) continue;
                for (String writer : writers) {
                    if (!reader.equals(writer)) readerDeps.add(writer);
                }
            }
        }

        List<Set<String>> sccs = LazyClinitInstrumenter.stronglyConnectedComponents(classDeps);
        Set<String> cyclicClasses = new LinkedHashSet<>();
        for (Set<String> scc : sccs) {
            if (scc.size() > 1) {
                cyclicClasses.addAll(scc);
            } else {
                String c = scc.iterator().next();
                Set<String> deps = classDeps.get(c);
                if (deps != null && deps.contains(c)) cyclicClasses.add(c);
            }
        }
        cyclicClasses.addAll(forcedClasses);
        if (cyclicClasses.isEmpty()) return;

        Set<String> lazyClasses = LazyClinitInstrumenter.propagateLazy(
            cyclicClasses, allWithClinit, resolver);

        System.out.println("Lazy <clinit>: " + cyclicClasses.size()
            + " cyclic class(es), " + lazyClasses.size()
            + " total lazy class(es) after superclass/subclass closure.");

        LazyClinitInstrumenter instrumenter =
            new LazyClinitInstrumenter(module, resolver, lazyClasses, scheduler);
        int inserted = instrumenter.instrument();
        System.out.println("Lazy <clinit>: instrumented " + inserted
            + " trigger site(s).");

        for (String cls : lazyClasses) {
            String clinitName = classNameToClinitName.get(cls);
            if (clinitName == null) continue;
            String wrapperName = "fn___lazy_clinit_run_" + sanitizeForWrapper(cls);
            Function wrapper = module.getFunction(wrapperName);
            if (wrapper != null) {
                clinitWrappers.put(clinitName, wrapper.getName());
            }
        }
    }

    private static String sanitizeForWrapper(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    // =====================================================================
    //  <clinit> resurrection / ensuring / native declaration
    // =====================================================================

    private int resurrectRemovedClinits(Module module, DependencyResolver resolver) {
        int total = 0;
        boolean anyAdded = true;
        int guard = 0;
        final int maxPasses = 8;

        while (anyAdded && guard++ < maxPasses) {
            anyAdded = false;
            Set<String> neededOwners = collectTriggeredOwners(module, resolver);

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
                    if (mn.getName().equals("<clinit>")
                        && mn.getDescriptor().equals("()V")) {
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
                    // Distinguish "was in the module before DCE and got removed"
                    // from "was never added at all". The previous single message
                    // ("removed by DCE") was misleading: for the NativeImageBuffer
                    // case the function had never been translated in the first place.
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
            Set<String> referenced = collectTriggeredOwners(module, resolver);
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
                    if (mn.getName().equals("<clinit>")
                        && mn.getDescriptor().equals("()V")) {
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

    /**
     * Collects the internal names of every class whose static initialization
     * must be scheduled into the compiled image.
     *
     * <p>The result is the closure of two relations:</p>
     *
     * <ol>
     *   <li>Every class referenced explicitly from the compiled bytecode:
     *       by a {@code getstatic}/{@code putstatic}, a {@code new}, an
     *       {@code invokestatic} (the trigger owners), or by the owner of a
     *       direct call ({@code CALL}). A call whose target is a
     *       {@code native} method is excluded, because such an invocation
     *       does not trigger class initialization. This is what the previous
     *       revision computed, and it is what the ordinary reachability walk
     *       already discovers.</li>
     *
     *   <li>The transitive superclass closure of the first set. JVMS §5.5
     *       guarantees that a class's superclass is initialized before the
     *       class itself, but that rule is enforced by the VM, not by the
     *       bytecode: no compiled program ever contains an explicit
     *       "initialize the superclass of this class" instruction. A class
     *       that participates only as a superclass — it is never
     *       instantiated, has no static field read or written from the
     *       image, and has no static method called — would therefore be
     *       missing from the first set, and its {@code <clinit>} would
     *       never be resurrected into the module.</li>
     * </ol>
     *
     * <p>The concrete failure this closure fixes is
     * {@code java.security.SecureClassLoader}: it is the direct superclass
     * of {@code jdk.internal.loader.BuiltinClassLoader}, it is abstract,
     * and nothing in the compiled image references it directly. Its
     * {@code <clinit>} body is
     * <pre>
     *     static { ClassLoader.registerAsParallelCapable(); }
     * </pre>
     * and {@code ClassLoader.registerAsParallelCapable} consults
     * {@code ParallelLoaders.loaderTypes}, whose contents are determined by
     * the order in which the superclass chain initializes:</p>
     *
     * <pre>
     *     ClassLoader.<clinit>         adds ClassLoader
     *     SecureClassLoader.<clinit>   adds SecureClassLoader
     *     BuiltinClassLoader.<clinit>  adds BuiltinClassLoader
     * </pre>
     *
     * <p>With {@code SecureClassLoader.<clinit>} missing from the module,
     * the middle entry is never inserted, and {@code BuiltinClassLoader}'s
     * own registration fails with
     * {@code java.lang.InternalError: Unable to register as parallel capable}.</p>
     *
     * <p>The closure walks superclasses only, not interfaces. Interfaces are
     * excluded on purpose: JVMS §5.5 does not initialize an interface when a
     * class that implements it is initialized, and the JDK's own
     * {@code ParallelLoaders.loaderTypes} chain is a superclass chain, not a
     * superinterface chain.</p>
     */
    private Set<String> collectTriggeredOwners(Module module, DependencyResolver resolver) {
        Set<String> owners = new HashSet<>();

        // ---- 1. Explicit references from bytecode. ----
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() == null) continue;
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op == Opcode.GET_STATIC || op == Opcode.PUT_STATIC
                        || op == Opcode.NEW) {
                        String owner = extractTriggerOwner(inst);
                        if (owner != null && !owner.isEmpty()) {
                            owners.add(owner);
                        }
                    } else if (op == Opcode.STATIC_CALL || op == Opcode.CALL) {
                        // An invokestatic to a *native* method does NOT
                        // trigger class initialization (JVMS §5.5): the body
                        // lives in C and reads no Java-side static state.
                        // Excluding native targets here is what keeps this
                        // pass consistent with
                        // MethodBytecodeVisitor.visitMethodInsn and with
                        // LazyClinitInstrumenter.triggeredClassFor.
                        //
                        // The earlier revision added the owner of every
                        // STATIC_CALL unconditionally. That resurrected
                        // <clinit> functions for classes whose only entry
                        // point was a native static method — most notably
                        // jdk.internal.jimage.NativeImageBuffer — and the
                        // resurrected bodies were missing their transitive
                        // closure because retranslateClinit() does not run
                        // the reachability walk. The result was a vtable
                        // slot populated with an unresolved thunk and a
                        // runtime __jnative_unresolved_slot trap.
                        String callee = extractCalleeConstant(inst, 0);
                        if (callee == null) continue;

                        int dotIdx = callee.lastIndexOf('.');
                        int parenIdx = callee.indexOf('(');
                        if (dotIdx <= 0 || parenIdx <= dotIdx) continue;

                        String owner = callee.substring(0, dotIdx);
                        String methodPart = callee.substring(dotIdx + 1);
                        int localParenIdx = parenIdx - dotIdx - 1;
                        String methodName = methodPart.substring(0, localParenIdx);
                        String descriptor = methodPart.substring(localParenIdx);

                        String[] foundOwner = new String[1];
                        MethodNode mn = resolver.findMethodInHierarchy(
                            owner, methodName, descriptor, foundOwner);
                        if (mn != null && mn.isNative()) {
                            continue;
                        }
                        String declOwner = (foundOwner[0] != null
                            && !foundOwner[0].isEmpty())
                            ? foundOwner[0]
                            : owner;
                        owners.add(declOwner);
                    }
                }
            }
        }

        // ---- 2. Transitive superclass closure. ----
        //
        // See the javadoc above for the full rationale. The walk is
        // monotone (owners is a growing set, each class is enqueued at
        // most once) and terminates at java/lang/Object or at the first
        // missing ClassNode. External classes and interfaces are not
        // added; the callers of this method (resurrectRemovedClinits and
        // ensureReferencedClinitsPresent) skip both categories again when
        // they iterate the result, so no special handling is required
        // here.
        Deque<String> worklist = new ArrayDeque<>(owners);
        while (!worklist.isEmpty()) {
            String owner = worklist.poll();
            ClassNode cn = resolver.getClassNode(owner);
            if (cn == null) continue;

            String sup = cn.getSuperName();
            if (sup == null
                || sup.isEmpty()
                || sup.equals(owner)
                || "java/lang/Object".equals(sup)) {
                continue;
            }
            if (owners.add(sup)) {
                worklist.add(sup);
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
                func.setOwnerClass(actualOwner);
                int slot = 0;
                for (int i = 0; i < paramTypes.size(); i++) {
                    Type t = paramTypes.get(i);
                    func.addParameter(new Parameter(t, slot, i));
                    slot += (t == Type.LONG || t == Type.DOUBLE) ? 2 : 1;
                }
                module.addFunction(func);
                added++;
                changed = true;
            }
        }
        return added;
    }

    // =====================================================================
    //  <clinit> sorting
    // =====================================================================

    /**
     * Collects the mangled names of all static bootstrap phases declared in
     * java.lang.System: initPhase1, initPhase2, initPhase3.
     *
     * <p>Discovery is driven by the "initPhase" prefix among the static
     * methods of System rather than by a hard-coded list. A JDK that renames
     * or adds a phase is picked up automatically.</p>
     *
     * <p>The returned names match what {@link LlvmRuntime#mangleMethod}
     * produces, so the caller can look them up in
     * {@link Module#getFunction(String)} without further conversion. The list
     * is sorted so that initPhase1 runs before initPhase2, and initPhase2
     * before initPhase3, independently of the method traversal order.</p>
     */
    private List<String> collectBootstrapPhaseFunctions(DependencyResolver resolver) {
        List<String> phases = new ArrayList<>();
        ClassNode systemNode = resolver.getClassNode("java/lang/System");
        if (systemNode == null || systemNode.isExternal()) {
            log.warn("java.lang.System is not loaded; no bootstrap phases "
                + "will be scheduled and System.out/err/in may stay unset");
            return phases;
        }
        for (MethodNode mn : systemNode.getMethods()) {
            if (!mn.isStatic()) continue;
            String n = mn.getName();
            if (!n.startsWith("initPhase")) continue;
            if (n.equals("<clinit>") || n.equals("<init>")) continue;
            phases.add(LlvmRuntime.mangleMethod(
                "java/lang/System", n, mn.getDescriptor()));
        }
        phases.sort(Comparator.naturalOrder());
        return phases;
    }

    /**
     * Resolves the mangled names of the bootstrap phases returned by
     * {@link #collectBootstrapPhaseFunctions} into the real IR functions of
     * the module.
     *
     * <p>Phases whose bodies are absent from the module (the System class was
     * not loaded, no IR was generated) are silently skipped: this is equivalent
     * to the phase doing nothing and preserves the previous generator
     * behaviour, which wrote a warning in that case.</p>
     */
    private List<Function> collectPhaseFunctions(Module module,
                                                 DependencyResolver resolver) {
        List<String> names = collectBootstrapPhaseFunctions(resolver);
        List<Function> result = new ArrayList<>(names.size());
        for (String name : names) {
            Function f = module.getFunction(name);
            if (f != null && f.getEntryBlock() != null) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Builds the initialization schedule in two stages: first the
     * {@code <clinit>} functions are linearized by their own Tarjan pass over
     * their own subgraph, then the bootstrap phases
     * {@code System.initPhase1/2/3} are inserted into that finished order.
     *
     * <h2>Why the phases stay out of the SCC decomposition</h2>
     *
     * <p>The phase order is fixed by the JDK contract:
     * {@code initPhase1 -> initPhase2 -> initPhase3}. It must not be broken.
     * If the phases are placed in the same graph as the {@code <clinit>}s,
     * they land in one huge SCC and the linearizer breaks edges inside the
     * cycle arbitrarily — including the hard {@code phase2 -> phase1} edge.
     * After that {@code initPhase2} runs before {@code initPhase1} and
     * {@code VM.initLevel()} is still 0 when phase 2 starts.</p>
     *
     * <p>The position of each phase is computed on the finished
     * {@code <clinit>} order:</p>
     * <ul>
     *   <li>{@code after(P)} = 1 + the largest index of a {@code <clinit>}
     *       whose static fields {@code P} reads (transitively);</li>
     *   <li>{@code before(P)} = the smallest index of a {@code <clinit>}
     *       that reads (transitively) a static field written by {@code P}.</li>
     * </ul>
     *
     * <p>If {@code after(P) <= before(P)} the phase is placed at position
     * {@code after(P)} — right after its dependencies and before all of its
     * readers. If {@code after(P) > before(P)} (a phase and a clinit depend
     * on each other through a field), priority is given to {@code after(P)}:
     * the phase is placed AFTER all of its dependencies even if that means
     * some of its readers run before it. The choice is deliberate — see the
     * block comment at the merge site for the full reasoning — and is the
     * opposite of the previous revision's. A warning is printed for every
     * such break.</p>
     *
     * <h2>Why trigger edges from phases are dropped</h2>
     *
     * <p>The scope of phase 1 covers nearly all of {@code java.base} startup,
     * so "phase triggers class X" degenerates into "the phase depends on
     * almost every {@code <clinit>}". In a linear schedule this is
     * meaningless: a triggered initialization of X completes before the
     * trigger instruction inside the phase is reached, and everything the
     * phase actually reads from X is already covered by field-provenance
     * edges (X — through the phase scope — lands in the fieldReaders of the
     * corresponding field). Dropping trigger edges from phases keeps exactly
     * the dependencies that really affect correctness and stops the phases
     * from being pushed to the end of the schedule.</p>
     */
    private List<ClinitScheduleEntry> sortInitializers(
            List<Function> clinitFunctions,
            List<Function> phaseFunctions,
            DependencyResolver resolver,
            Module module,
            String entryClass,
            String entryMethod,
            String entryDescriptor) {

        if (clinitFunctions.isEmpty() && phaseFunctions.isEmpty()) {
            return List.of();
        }

        System.out.println("Sorting " + clinitFunctions.size()
            + " <clinit> and " + phaseFunctions.size()
            + " bootstrap-phase function(s) by initialization dependency...");

        Set<Function> phaseSet = new HashSet<>(phaseFunctions);

        Set<String> clinitNames = new HashSet<>();
        for (Function f : clinitFunctions) clinitNames.add(f.getName());

        Map<String, Function> classNameToClinit = new HashMap<>();
        Map<String, String>   clinitNameToClassName = new HashMap<>();
        for (ClassNode cn : resolver.getClassMap().values()) {
            String clinitName = LlvmRuntime.mangleMethod(cn.getName(), "<clinit>", "()V");
            if (!clinitNames.contains(clinitName)) continue;
            Function clinit = module.getFunction(clinitName);
            if (clinit == null) continue;
            classNameToClinit.put(cn.getName(), clinit);
            clinitNameToClassName.put(clinitName, cn.getName());
        }

        // ---- Parallel call-graph construction ----
        Map<String, Set<String>> callGraph = new ConcurrentHashMap<>();
        List<Function> functionsSnapshot = new ArrayList<>(module.getFunctions());
        forEachParallel(functionsSnapshot, func -> {
            if (func.getEntryBlock() == null) return;
            Set<String> callees = new HashSet<>();
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    collectDirectCallees(inst, callees, module, resolver);
                }
            }
            callGraph.put(func.getName(), callees);
        });

        // =================================================================
        //  Pre-computation pass 1: per-function direct facts.
        //
        //  For every live function we pre-collect:
        //    - the set of trigger-owner classes it observes directly
        //      (NEW / GET_STATIC / PUT_STATIC / STATIC_CALL owners);
        //    - the set of static fields it reads directly;
        //    - the set of static fields it writes directly.
        //
        //  These are stored as int[] arrays of IDs into two global
        //  universes (trigger classes and static field keys).  Downstream
        //  passes then only ever need OR / AND bit operations over long[]
        //  bitsets instead of re-scanning the instructions of every
        //  function in every init's scope.
        // =================================================================

        List<Function> liveFunctions = new ArrayList<>(functionsSnapshot.size());
        for (Function f : functionsSnapshot) {
            if (f.getEntryBlock() != null) liveFunctions.add(f);
        }

        Map<String, Integer> funcIndex = new HashMap<>(liveFunctions.size() * 2);
        for (int i = 0; i < liveFunctions.size(); i++) {
            funcIndex.put(liveFunctions.get(i).getName(), i);
        }

        // Serial deterministic pre-scan of the two universes.
        TreeSet<String> triggerClassUniverse = new TreeSet<>();
        TreeSet<String> fieldKeyUniverse     = new TreeSet<>();
        for (Function f : liveFunctions) {
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op == Opcode.NEW || op == Opcode.GET_STATIC
                        || op == Opcode.PUT_STATIC || op == Opcode.STATIC_CALL) {
                        String owner = extractTriggerOwner(inst);
                        if (owner != null && !owner.isEmpty()) {
                            triggerClassUniverse.add(owner);
                        }
                    }
                    if (op == Opcode.GET_STATIC || op == Opcode.PUT_STATIC) {
                        String key = extractStaticFieldKey(inst);
                        if (key != null) fieldKeyUniverse.add(key);
                    }
                }
            }
        }

        final List<String> classList = new ArrayList<>(triggerClassUniverse);
        final List<String> fieldList = new ArrayList<>(fieldKeyUniverse);

        final Map<String, Integer> classIdMap = new HashMap<>(classList.size() * 2);
        for (int i = 0; i < classList.size(); i++) classIdMap.put(classList.get(i), i);
        final Map<String, Integer> fieldIdMap = new HashMap<>(fieldList.size() * 2);
        for (int i = 0; i < fieldList.size(); i++) fieldIdMap.put(fieldList.get(i), i);

        final int classWords = (classList.size() + 63) >>> 6;
        final int fieldWords = (fieldList.size() + 63) >>> 6;

        System.out.println("Pre-computing per-function initialization facts: "
            + classList.size() + " distinct trigger class(es), "
            + fieldList.size() + " distinct static field key(s)");

        final int[][] funcTriggerIds = new int[liveFunctions.size()][];
        final int[][] funcReadIds    = new int[liveFunctions.size()][];
        final int[][] funcWriteIds   = new int[liveFunctions.size()][];

        forEachParallel(liveFunctions, func -> {
            int idx = funcIndex.get(func.getName());
            ArrayList<Integer> trig = new ArrayList<>();
            ArrayList<Integer> rd   = new ArrayList<>();
            ArrayList<Integer> wr   = new ArrayList<>();
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op == Opcode.NEW || op == Opcode.GET_STATIC
                        || op == Opcode.PUT_STATIC || op == Opcode.STATIC_CALL) {
                        String owner = extractTriggerOwner(inst);
                        if (owner != null && !owner.isEmpty()) {
                            Integer id = classIdMap.get(owner);
                            if (id != null) trig.add(id);
                        }
                    }
                    if (op == Opcode.GET_STATIC || op == Opcode.PUT_STATIC) {
                        String key = extractStaticFieldKey(inst);
                        if (key != null) {
                            Integer id = fieldIdMap.get(key);
                            if (id != null) {
                                if (op == Opcode.PUT_STATIC) wr.add(id);
                                else                        rd.add(id);
                            }
                        }
                    }
                }
            }
            funcTriggerIds[idx] = toIntArray(trig);
            funcReadIds[idx]    = toIntArray(rd);
            funcWriteIds[idx]   = toIntArray(wr);
        });

        // =================================================================
        //  Pre-computation pass 2: init-scope BFS with fact accumulation.
        //
        //  For each init (clinit or bootstrap phase) we BFS through the
        //  call graph, never entering another init's body, and — instead
        //  of building a huge Set<String> scope — we accumulate three
        //  bitsets by OR-ing the small per-function id arrays of every
        //  visited function.
        //
        //  This replaces the previous three independent passes
        //  (initScope, triggeredClasses, fieldWriters/fieldReaders), each
        //  of which walked the whole scope and all of its instructions.
        // =================================================================

        Set<String> allInitializerNames = new HashSet<>(clinitNames);
        for (Function f : phaseFunctions) allInitializerNames.add(f.getName());

        List<Function> allInit = new ArrayList<>(
            clinitFunctions.size() + phaseFunctions.size());
        allInit.addAll(clinitFunctions);
        allInit.addAll(phaseFunctions);

        final Map<String, long[]> initTriggerBits = new ConcurrentHashMap<>();
        final Map<String, long[]> initReadBits    = new ConcurrentHashMap<>();
        final Map<String, long[]> initWriteBits   = new ConcurrentHashMap<>();
        final Map<String, Set<String>> nestedInit = new ConcurrentHashMap<>();
        final Set<String> allInitializerNamesF = allInitializerNames;
        final Map<String, Set<String>> callGraphF = callGraph;

        forEachParallel(allInit, init -> {
            String initName = init.getName();
            long[] trig = new long[classWords];
            long[] rd   = new long[fieldWords];
            long[] wr   = new long[fieldWords];
            Set<String> nested   = new LinkedHashSet<>();
            Set<String> visited  = new HashSet<>();
            Deque<String> worklist = new ArrayDeque<>();

            visited.add(initName);
            worklist.push(initName);
            orInFunctionFacts(initName, funcIndex,
                              funcTriggerIds, funcReadIds, funcWriteIds,
                              trig, rd, wr);

            while (!worklist.isEmpty()) {
                String cur = worklist.pop();
                Set<String> callees = callGraphF.get(cur);
                if (callees == null) continue;
                for (String callee : callees) {
                    if (allInitializerNamesF.contains(callee)) {
                        nested.add(callee);
                        continue;
                    }
                    if (visited.add(callee)) {
                        worklist.push(callee);
                        orInFunctionFacts(callee, funcIndex,
                                          funcTriggerIds, funcReadIds, funcWriteIds,
                                          trig, rd, wr);
                    }
                }
            }

            initTriggerBits.put(initName, trig);
            initReadBits.put(initName, rd);
            initWriteBits.put(initName, wr);
            nestedInit.put(initName, nested);
        });

        // ---- Parallel triggered-class collection ----
        Map<Function, Set<String>> triggeredClasses = new ConcurrentHashMap<>();
        forEachParallel(allInit, init -> {
            Set<String> triggered = new LinkedHashSet<>();

            String ownClass = clinitNameToClassName.get(init.getName());
            if (ownClass != null) {
                ClassNode cn = resolver.getClassNode(ownClass);
                Set<String> visited = new HashSet<>();
                while (cn != null && cn.getSuperName() != null
                    && !cn.getSuperName().equals(cn.getName())
                    && visited.add(cn.getSuperName())) {
                    String sup = cn.getSuperName();
                    if ("java/lang/Object".equals(sup)) break;
                    triggered.add(sup);
                    cn = resolver.getClassNode(sup);
                }
            }

            Set<String> nested = nestedInit.get(init.getName());
            if (nested != null) {
                for (String nestedName : nested) {
                    String nestedClass = clinitNameToClassName.get(nestedName);
                    if (nestedClass != null) triggered.add(nestedClass);
                }
            }

            long[] bits = initTriggerBits.get(init.getName());
            if (bits != null) {
                for (int w = 0; w < bits.length; w++) {
                    long word = bits[w];
                    while (word != 0) {
                        int bit = Long.numberOfTrailingZeros(word);
                        triggered.add(classList.get((w << 6) + bit));
                        word &= word - 1;
                    }
                }
            }

            triggeredClasses.put(init, triggered);
        });

        // ---- Deps assembly ----
        Map<Function, Set<Function>> deps = new LinkedHashMap<>();
        for (Function f : allInit) deps.put(f, new LinkedHashSet<>());

        for (Map.Entry<Function, Set<String>> entry : triggeredClasses.entrySet()) {
            Function f = entry.getKey();
            // Trigger edges from phases are dropped (see javadoc).
            if (phaseSet.contains(f)) continue;
            for (String triggered : entry.getValue()) {
                Function dep = classNameToClinit.get(triggered);
                if (dep != null && dep != f) {
                    deps.get(f).add(dep);
                }
            }
        }

        // ---- Field writer/reader maps, built from bitsets ----
        Map<String, Set<Function>> fieldWriters = new ConcurrentHashMap<>();
        Map<String, Set<Function>> fieldReaders = new ConcurrentHashMap<>();

        forEachParallel(allInit, init -> {
            long[] rbits = initReadBits.get(init.getName());
            if (rbits != null) {
                for (int w = 0; w < rbits.length; w++) {
                    long word = rbits[w];
                    while (word != 0) {
                        int bit = Long.numberOfTrailingZeros(word);
                        String field = fieldList.get((w << 6) + bit);
                        fieldReaders
                            .computeIfAbsent(field, k -> ConcurrentHashMap.newKeySet())
                            .add(init);
                        word &= word - 1;
                    }
                }
            }
            long[] wbits = initWriteBits.get(init.getName());
            if (wbits != null) {
                for (int w = 0; w < wbits.length; w++) {
                    long word = wbits[w];
                    while (word != 0) {
                        int bit = Long.numberOfTrailingZeros(word);
                        String field = fieldList.get((w << 6) + bit);
                        fieldWriters
                            .computeIfAbsent(field, k -> ConcurrentHashMap.newKeySet())
                            .add(init);
                        word &= word - 1;
                    }
                }
            }
        });

        // ---- Field-provenance edges ----
        int provenanceEdges = 0;
        for (Map.Entry<String, Set<Function>> entry : fieldReaders.entrySet()) {
            String field = entry.getKey();
            Set<Function> writers = fieldWriters.get(field);
            if (writers == null || writers.isEmpty()) continue;
            for (Function reader : entry.getValue()) {
                if (writers.contains(reader)) continue;
                Set<Function> rd = deps.get(reader);
                if (rd == null) continue;
                for (Function writer : writers) {
                    if (reader == writer) continue;
                    if (rd.add(writer)) provenanceEdges++;
                }
            }
        }
        if (provenanceEdges > 0) {
            System.out.println("Added " + provenanceEdges
                + " static-field provenance edge(s) to initialization dependency graph.");
        }

        // ---- Activation depth (over <clinit> only) ----
        Map<String, Integer> activationDepth = computeActivationDepth(
            callGraph, clinitNames,
            entryRoots(entryClass, entryMethod, entryDescriptor));

        // ---- Linearize <clinit> ONLY ----
        //
        // The phases are excluded from the Tarjan graph. To do that, deps is
        // filtered down to clinit-to-clinit edges: the successor set of every
        // clinit function keeps only the nodes whose name belongs to
        // clinitNames. Tarjan then physically cannot place a phase in any SCC.
        Map<Function, Set<Function>> clinitDeps = new LinkedHashMap<>();
        for (Function c : clinitFunctions) {
            Set<Function> filtered = new LinkedHashSet<>();
            Set<Function> succ = deps.get(c);
            if (succ != null) {
                for (Function d : succ) {
                    if (clinitNames.contains(d.getName())) {
                        filtered.add(d);
                    }
                }
            }
            clinitDeps.put(c, filtered);
        }
        List<Function> clinitOrder = tarjanLinearize(
            clinitFunctions, clinitDeps, activationDepth);

        // ---- Phase placement ----
        List<Function> phaseOrder = new ArrayList<>(phaseFunctions);
        phaseOrder.sort(Comparator.comparingInt(Analyzer::phaseOrdinal));

        // Reverse graph for computing before(P)
        Map<Function, Set<Function>> revDeps = new HashMap<>();
        for (Map.Entry<Function, Set<Function>> e : deps.entrySet()) {
            for (Function d : e.getValue()) {
                revDeps.computeIfAbsent(d, k -> new HashSet<>()).add(e.getKey());
            }
        }

        Map<Function, Integer> clinitIndex = new HashMap<>();
        for (int i = 0; i < clinitOrder.size(); i++) {
            clinitIndex.put(clinitOrder.get(i), i);
        }

        Map<Function, Integer> afterMap  = new HashMap<>();
        Map<Function, Integer> beforeMap = new HashMap<>();
        for (Function p : phaseOrder) {
            Set<Function> depsOfP = transitiveClosure(deps, p);
            int after = 0;
            for (Function d : depsOfP) {
                Integer idx = clinitIndex.get(d);
                if (idx != null && idx + 1 > after) after = idx + 1;
            }
            afterMap.put(p, after);

            Set<Function> dependentsOfP = transitiveClosure(revDeps, p);
            int before = clinitOrder.size();
            for (Function d : dependentsOfP) {
                Integer idx = clinitIndex.get(d);
                if (idx != null && idx < before) before = idx;
            }
            beforeMap.put(p, before);

            System.out.println("Phase placement: " + p.getName()
                + " after=" + after + ", before=" + before);
        }

        // ---- Defer the family-B side of the phase/clinit cycle ----
        //
        // Any initializer that reads a static field written by a phase,
        // but does not itself write a field any phase reads, must run
        // after that phase. In a linear schedule whose phase positions
        // are dictated by after(P) — which is what the schedule needs
        // to satisfy the phase's own dependencies — such an initializer
        // would run before the phase and observe a null field. Rather
        // than move the phase (which reintroduces the StringConcatHelper
        // failure) this code removes the conflicting initializer from
        // the eager schedule entirely. Its wrapper fires on first active
        // use, which is by construction after the phase that provoked
        // that use. See computeDeferredClinits() for the full rationale.
        Set<String> deferred = computeDeferredClinits(
            clinitOrder, phaseOrder, afterMap,
            initReadBits, initWriteBits,
            fieldIdMap, fieldWords);

        if (!deferred.isEmpty()) {
            this.deferredClinits.addAll(deferred);

            System.out.println("Deferring " + deferred.size()
                + " <clinit>(s) to lazy initialization: they read a field "
                + "written by a bootstrap phase but do not contribute to any "
                + "phase's own dependencies, so eager scheduling would place "
                + "them before the phase and observe a null field.");

            Set<Function> deferredSet = new HashSet<>();
            for (Function c : clinitOrder) {
                if (deferred.contains(c.getName())) deferredSet.add(c);
            }
            clinitOrder.removeIf(deferredSet::contains);

            // Recompute after/before positions against the trimmed list.
            Map<Function, Integer> trimmedIdx = new HashMap<>();
            for (int i = 0; i < clinitOrder.size(); i++) {
                trimmedIdx.put(clinitOrder.get(i), i);
            }
            for (Function p : phaseOrder) {
                Set<Function> depsOfP = transitiveClosure(deps, p);
                int afterTrimmed = 0;
                for (Function d : depsOfP) {
                    Integer idx = trimmedIdx.get(d);
                    if (idx != null && idx + 1 > afterTrimmed) {
                        afterTrimmed = idx + 1;
                    }
                }
                afterMap.put(p, afterTrimmed);

                Set<Function> dependentsOfP = transitiveClosure(revDeps, p);
                int beforeTrimmed = clinitOrder.size();
                for (Function d : dependentsOfP) {
                    Integer idx = trimmedIdx.get(d);
                    if (idx != null && idx < beforeTrimmed) {
                        beforeTrimmed = idx;
                    }
                }
                beforeMap.put(p, beforeTrimmed);

                System.out.println("Phase placement (post-defer): " + p.getName()
                    + " after=" + afterTrimmed + ", before=" + beforeTrimmed);
            }
        }

        // ---- Merge: clinits + phases with a hard phase order ----
        List<ClinitScheduleEntry> result =
            new ArrayList<>(clinitOrder.size() + phaseOrder.size());
        int ci = 0;
        int prevPhaseSlot = 0;
        for (Function p : phaseOrder) {
            int after  = afterMap.get(p);
            int before = beforeMap.get(p);
            int target = Math.max(after, prevPhaseSlot);

            // Cycle between the phase and the clinit set.  Exactly one of the
            // two edge families has to run in reverse in the emitted schedule;
            // the one chosen here is "clinit -> phase" (the phase's readers),
            // not "phase -> clinit" (the phase's own dependencies).
            //
            // Rationale.  A phase is a straight-line entry point that
            // unconditionally walks into the code of every class in its
            // transitive dependency closure.  Placing it before those
            // initializers makes it read null from a static field that its
            // own body dereferences on the next instruction — the exact
            // failure this build reported:
            //
            //   java.lang.NullPointerException: Cannot invoke
            //     java.lang.StringConcatHelper.newArray(JB) because receiver
            //     of ...Unsafe.allocateUninitializedArray(...) is null
            //       at StringConcatHelper.newArray
            //       at StringConcatHelper.simpleConcat
            //       at String.concat
            //       at SystemProps.fillI18nProps
            //       at SystemProps.initProperties
            //       at System.initPhase1
            //
            // The only writer of StringConcatHelper.UNSAFE is
            // StringConcatHelper.<clinit>; under the previous branch that
            // clinit was scheduled at position 1121 while initPhase1 was
            // placed at position 24, so UNSAFE was null on entry.
            //
            // The reader edges broken in their place belong to initializers
            // that read a field written by the phase.  Not every such reader
            // is actually harmed: one that reads a phase-produced field only
            // on a path the program does not take is unaffected, and one that
            // reads it unconditionally but tolerates the default (System.props,
            // for example, already holds the bootstrap Properties installed
            // by @main before the schedule runs) is unaffected as well.  The
            // warning below names the phase and both boundary indices so that
            // a reader that genuinely cannot tolerate an uninitialized field
            // is visible at build time and can be examined against its own
            // bytecode.
            if (target > before) {
                System.out.println("WARNING: phase " + p.getName()
                    + " has cyclic dependency with clinits "
                    + "(after=" + after + ", before=" + before
                    + "); placed at " + target
                    + " (phase's own dependency edges take priority; "
                    + "reader initializers that would have run between "
                    + "position " + before + " and " + target
                    + " now run before the phase)");
            }

            while (ci < target && ci < clinitOrder.size()) {
                result.add(new ClinitScheduleEntry(clinitOrder.get(ci++), false));
            }
            result.add(new ClinitScheduleEntry(p, true));
            prevPhaseSlot = target;
        }
        while (ci < clinitOrder.size()) {
            result.add(new ClinitScheduleEntry(clinitOrder.get(ci++), false));
        }

        int cycles = countBrokenCycleEdges(clinitOrder, clinitDeps);
        if (cycles > 0) {
            System.out.println("Clinit cycles: " + cycles
                + " edge(s) inside a <clinit> cycle run in reverse order "
                + "(nested-init semantics of JVMS 5.5).");
        }
        System.out.println("Initialization sort complete (" + result.size()
            + " entries: " + clinitOrder.size() + " clinits, "
            + phaseFunctions.size() + " phase(s)).");
        return result;
    }

    /**
     * Extracts the bootstrap-phase number from a mangled method name:
     * {@code fn_java_lang_System_initPhase2__ZZ_I} -> 2.
     * Returns {@link Integer#MAX_VALUE} for non-phase names.
     */
    private static int phaseOrdinal(Function f) {
        String name = f.getName();
        int idx = name.indexOf("initPhase");
        if (idx < 0) return Integer.MAX_VALUE;
        int start = idx + "initPhase".length();
        int end = start;
        while (end < name.length() && Character.isDigit(name.charAt(end))) end++;
        if (end == start) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(name.substring(start, end));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Transitive closure from {@code start} over {@code graph}.
     * The returned set contains {@code start}.
     */
    private static Set<Function> transitiveClosure(
            Map<Function, Set<Function>> graph, Function start) {
        Set<Function> visited = new HashSet<>();
        Deque<Function> worklist = new ArrayDeque<>();
        worklist.add(start);
        while (!worklist.isEmpty()) {
            Function cur = worklist.poll();
            if (!visited.add(cur)) continue;
            Set<Function> succ = graph.get(cur);
            if (succ == null) continue;
            worklist.addAll(succ);
        }
        return visited;
    }

    /**
     * Determines which <clinit> functions must be deferred to lazy
     * initialization because eager scheduling cannot satisfy them together
     * with the bootstrap phases.
     *
     * <p>This method is a bitset-driven version of the original algorithm.
     * Its correctness is identical to the previous implementation — the
     * family-A / family-B split, the fixed point over the surviving
     * schedule, and the {@code afterMap} clamping are unchanged — but the
     * inner test is now two {@code long[] & long[]} operations instead of
     * a nested walk over every instruction of every function in the
     * candidate clinit's scope.</p>
     *
     * <p>The relevant sets are precomputed once, during the init-scope BFS
     * in {@link #sortInitializers}:</p>
     *
     * <ul>
     *   <li>{@code pWrites} = {@code initWriteBits.get(p.getName())} —
     *       every static field written anywhere in the phase's transitive
     *       scope;</li>
     *   <li>{@code cReads} = {@code initReadBits.get(c.getName())} —
     *       every static field read anywhere in the clinit's transitive
     *       scope;</li>
     *   <li>{@code cWrites} = {@code initWriteBits.get(c.getName())} —
     *       every static field written anywhere in the clinit's scope;</li>
     *   <li>{@code pDirectReads} — every static field read by the phase's
     *       <em>own body only</em>. This is the only piece of information
     *       that the init-scope BFS cannot supply, because it deliberately
     *       collapses the phase's own body and its transitive scope into
     *       one union. It is recomputed here directly from the phase's
     *       instruction stream — a single pass over one function, once per
     *       call.</li>
     * </ul>
     *
     * <p>The three tests in the loop then become:</p>
     *
     * <pre>
     *     cReadsP  == (cReads  &amp; pWrites)      != 0
     *     cWritesP == (cWrites &amp; pDirectReads) != 0
     * </pre>
     *
     * <p>which is exactly the original semantics, evaluated on the same
     * precomputed universes.</p>
     */
    private Set<String> computeDeferredClinits(
            List<Function> clinitOrder,
            List<Function> phaseOrder,
            Map<Function, Integer> afterMap,
            Map<String, long[]> initReadBits,
            Map<String, long[]> initWriteBits,
            Map<String, Integer> fieldIdMap,
            int fieldWords) {

        // Per-phase direct reads (phase body only). This is the only
        // piece of information that the scope BFS could not have provided
        // — it aggregated scope-level unions, and pDirectReads is the
        // phase's own body reads, which is a strictly smaller set.
        Map<Function, long[]> phaseDirectReadBits = new HashMap<>();
        for (Function p : phaseOrder) {
            long[] bits = new long[fieldWords];
            for (BasicBlock b : p.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    if (inst.getOpcode() != Opcode.GET_STATIC) continue;
                    String key = extractStaticFieldKey(inst);
                    if (key == null) continue;
                    Integer id = fieldIdMap.get(key);
                    if (id != null) bits[id >>> 6] |= 1L << (id & 63);
                }
            }
            phaseDirectReadBits.put(p, bits);
        }

        Set<String> deferred = new LinkedHashSet<>();

        final int MAX_PASSES = 8;
        boolean changed = true;
        int pass = 0;

        while (changed && pass++ < MAX_PASSES) {
            changed = false;

            for (Function p : phaseOrder) {
                int after = afterMap.getOrDefault(p, 0);
                if (after <= 0) continue;

                long[] pWrites = initWriteBits.get(p.getName());
                if (pWrites == null || bitsEmpty(pWrites)) continue;

                long[] pDirectReads = phaseDirectReadBits.get(p);

                int scanEnd = Math.min(after, clinitOrder.size());
                for (int i = 0; i < scanEnd; i++) {
                    Function c = clinitOrder.get(i);
                    String cName = c.getName();
                    if (deferred.contains(cName)) continue;

                    long[] cReads  = initReadBits.get(cName);
                    long[] cWrites = initWriteBits.get(cName);
                    if (cReads == null || cWrites == null) continue;

                    boolean cReadsP  = bitsIntersect(cReads,  pWrites);
                    boolean cWritesP = (pDirectReads != null)
                        && bitsIntersect(cWrites, pDirectReads);

                    if (cReadsP && !cWritesP) {
                        deferred.add(cName);
                        changed = true;
                    }
                }
            }

            // Each pass may unblock new candidates because defers shift
            // the index of later clinits. Clamp `after` against the
            // trimmed schedule before the next pass — same conservative
            // approximation as the previous revision (a smaller `after`
            // makes strictly more candidates eligible, which is the safe
            // direction).
            if (changed) {
                Map<Function, Integer> trimmedIdx = new HashMap<>();
                int k = 0;
                for (Function c : clinitOrder) {
                    if (!deferred.contains(c.getName())) {
                        trimmedIdx.put(c, k++);
                    }
                }
                for (Function p : phaseOrder) {
                    Integer prev = afterMap.get(p);
                    if (prev != null && prev > trimmedIdx.size()) {
                        afterMap.put(p, trimmedIdx.size());
                    }
                }
            }
        }

        if (pass >= MAX_PASSES) {
            System.out.println("Deferral fixed point did not converge after "
                + MAX_PASSES + " passes; " + deferred.size()
                + " clinit(s) deferred so far.");
        }

        return deferred;
    }

    /**
     * ORs the pre-computed fact arrays of one function into three
     * accumulator bitsets.
     *
     * <p>The three {@code int[]} arrays are indexed by function index
     * (see {@code funcIndex}); every entry is an ID into the corresponding
     * global universe. The bitsets are updated in place.</p>
     *
     * <p>Called from the init-scope BFS for every function newly visited
     * by a given init. Because the per-function arrays are typically only
     * a handful of elements long, this is a few dozen {@code OR}
     * operations at most, versus the tens of thousands of instruction
     * scans that the previous implementation performed per init.</p>
     */
    private static void orInFunctionFacts(String funcName,
                                          Map<String, Integer> funcIndex,
                                          int[][] funcTriggers,
                                          int[][] funcReads,
                                          int[][] funcWrites,
                                          long[] trigBits,
                                          long[] readBits,
                                          long[] writeBits) {
        Integer idxObj = funcIndex.get(funcName);
        if (idxObj == null) return;
        int idx = idxObj;

        int[] trig = funcTriggers[idx];
        for (int id : trig) {
            trigBits[id >>> 6] |= 1L << (id & 63);
        }
        int[] rd = funcReads[idx];
        for (int id : rd) {
            readBits[id >>> 6] |= 1L << (id & 63);
        }
        int[] wr = funcWrites[idx];
        for (int id : wr) {
            writeBits[id >>> 6] |= 1L << (id & 63);
        }
    }

    /**
     * Converts a {@code List<Integer>} to an {@code int[]}. Used once per
     * function during the pre-computation pass; the JIT folds the manual
     * loop well enough that there is no reason to pull in a stream.
     */
    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    /**
     * Word-wise intersection test with early exit on the first
     * overlapping bit. Both arrays are expected to be the same length;
     * the {@code Math.min} guard is purely defensive.
     */
    private static boolean bitsIntersect(long[] a, long[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if ((a[i] & b[i]) != 0) return true;
        }
        return false;
    }

    /**
     * Returns true iff every word of {@code bits} is zero, i.e. the
     * represented set is empty. Used as a fast-path skip for phases whose
     * transitive scope writes nothing at all.
     */
    private static boolean bitsEmpty(long[] bits) {
        for (long bit : bits) {
            if (bit != 0) return false;
        }
        return true;
    }

    /**
     * Runs {@code action} for every element of {@code items} in parallel
     * through a dedicated {@link Scheduler.Worker} and waits for all tasks to
     * finish.
     *
     * <p>It does not use {@code Mono/Flux.block()}: the Reactor worker runs
     * the tasks on the pool owned by the given {@link Scheduler} and
     * completion is synchronized with a {@link CountDownLatch}. The first
     * exception caught by any task is rethrown to the caller after all tasks
     * have run — the same behaviour as the previous sequence
     * {@code Flux.parallel().runOn(scheduler).doOnNext(...).sequential().then().block()},
     * but without the reactive wrapper.</p>
     *
     * <p>The order of side effects of {@code action} between different
     * elements is undefined — as it was in the Reactor parallel branch. Every
     * call to {@code action.accept(item)} happens exactly once.</p>
     */
    private <T> void forEachParallel(List<T> items,
                                     java.util.function.Consumer<T> action) {
        if (items.isEmpty()) return;

        CountDownLatch latch = new CountDownLatch(items.size());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (T item : items) {
            scheduler.schedule(() -> {
                try {
                    action.accept(item);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                "parallel initialization phase interrupted", e);
        }

        Throwable first = failure.get();
        if (first != null) {
            throw new RuntimeException(
                "parallel initialization phase failed", first);
        }
    }

    private static void collectDirectCallees(Instruction inst,
                                             Set<String> out,
                                             Module module,
                                             DependencyResolver resolver) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case STATIC_CALL:
            case CALL: {
                String callee = extractCalleeConstant(inst, 0);
                if (callee == null) break;
                Function calleeFunc = module.getFunction(callee);
                if (calleeFunc == null) {
                    calleeFunc = module.getFunction(LlvmRuntime.mangleCallable(callee));
                }
                if (calleeFunc != null && calleeFunc.getEntryBlock() != null) {
                    out.add(calleeFunc.getName());
                }
                break;
            }
            case SPECIAL_CALL: {
                String callee = extractCalleeConstant(inst, 1);
                if (callee == null) break;
                Function calleeFunc = module.getFunction(callee);
                if (calleeFunc == null) {
                    calleeFunc = module.getFunction(LlvmRuntime.mangleCallable(callee));
                }
                if (calleeFunc != null && calleeFunc.getEntryBlock() != null) {
                    out.add(calleeFunc.getName());
                }
                break;
            }
            case VIRTUAL_CALL:
            case INTERFACE_CALL: {
                String callee = extractCalleeConstant(inst, 1);
                if (callee == null) break;
                int dotIdx = callee.lastIndexOf('.');
                int parenIdx = callee.indexOf('(');
                if (dotIdx <= 0 || parenIdx <= dotIdx) break;
                String owner = callee.substring(0, dotIdx);
                String methodPart = callee.substring(dotIdx + 1);
                int localParenIdx = parenIdx - dotIdx - 1;
                String methodName = methodPart.substring(0, localParenIdx);
                String descriptor = methodPart.substring(localParenIdx);

                Set<String> candidates = new HashSet<>();
                candidates.add(owner);
                candidates.addAll(resolver.getSubclasses(owner));

                for (String cls : candidates) {
                    String[] foundOwner = new String[1];
                    MethodNode mn = resolver.findMethodInHierarchy(
                        cls, methodName, descriptor, foundOwner);
                    if (mn == null || mn.isAbstract() || mn.isNative()) continue;
                    String decl = foundOwner[0] != null ? foundOwner[0] : cls;
                    String mangled = LlvmRuntime.mangleMethod(decl, methodName, descriptor);
                    Function f = module.getFunction(mangled);
                    if (f != null && f.getEntryBlock() != null) {
                        out.add(mangled);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private static void strongConnect(Function v,
                                      Map<Function, Set<Function>> deps,
                                      Map<Function, Integer> index,
                                      Map<Function, Integer> lowlink,
                                      Deque<Function> stack,
                                      Set<Function> onStack,
                                      List<List<Function>> sccs,
                                      int[] counter) {
        int idx = counter[0]++;
        index.put(v, idx);
        lowlink.put(v, idx);
        stack.push(v);
        onStack.add(v);

        Set<Function> successors = deps.get(v);
        if (successors != null) {
            for (Function w : successors) {
                if (!index.containsKey(w)) {
                    strongConnect(w, deps, index, lowlink, stack, onStack, sccs, counter);
                    lowlink.put(v, Math.min(lowlink.get(v), lowlink.get(w)));
                } else if (onStack.contains(w)) {
                    lowlink.put(v, Math.min(lowlink.get(v), index.get(w)));
                }
            }
        }

        if (lowlink.get(v).equals(index.get(v))) {
            List<Function> scc = new ArrayList<>();
            Function w;
            do {
                w = stack.pop();
                onStack.remove(w);
                scc.add(w);
            } while (w != v);
            sccs.add(scc);
        }
    }

    private static List<Function> tarjanLinearize(List<Function> nodes,
                                                  Map<Function, Set<Function>> deps,
                                                  Map<String, Integer> activationDepth) {
        Map<Function, Integer> index   = new HashMap<>();
        Map<Function, Integer> lowlink = new HashMap<>();
        Deque<Function> stack = new ArrayDeque<>();
        Set<Function> onStack = new HashSet<>();
        List<List<Function>> sccs = new ArrayList<>();
        int[] counter = {0};

        for (Function node : nodes) {
            if (!index.containsKey(node)) {
                strongConnect(node, deps, index, lowlink, stack, onStack, sccs, counter);
            }
        }

        List<Function> result = new ArrayList<>(nodes.size());
        for (List<Function> scc : sccs) {
            result.addAll(orderScc(scc, deps, activationDepth));
        }
        return result;
    }

    private static List<Function> orderScc(List<Function> scc,
                                           Map<Function, Set<Function>> deps,
                                           Map<String, Integer> activationDepth) {
        if (scc.size() == 1) return scc;

        Set<Function> members = new HashSet<>(scc);
        Function entry = scc.getFirst();
        for (Function candidate : scc) {
            if (compareActivation(candidate, entry, activationDepth) < 0) entry = candidate;
        }

        List<Function> ordered = new ArrayList<>(scc.size());
        Set<Function> visited = new HashSet<>();
        Deque<Function> stack = new ArrayDeque<>();
        stack.push(entry);
        visited.add(entry);

        while (!stack.isEmpty()) {
            Function v = stack.peek();
            boolean descended = false;
            Set<Function> successors = deps.get(v);
            if (successors != null) {
                for (Function w : successors) {
                    if (!members.contains(w) || !visited.add(w)) continue;
                    stack.push(w);
                    descended = true;
                    break;
                }
            }
            if (descended) continue;
            stack.pop();
            ordered.add(v);
        }

        List<Function> rest = new ArrayList<>();
        for (Function f : scc) {
            if (!visited.contains(f)) rest.add(f);
        }
        rest.sort(Comparator.comparing(Function::getName));
        ordered.addAll(rest);
        return ordered;
    }

    private static int compareActivation(Function a,
                                         Function b,
                                         Map<String, Integer> activationDepth) {
        int da = activationDepth.getOrDefault(a.getName(), Integer.MAX_VALUE);
        int db = activationDepth.getOrDefault(b.getName(), Integer.MAX_VALUE);
        if (da != db) return Integer.compare(da, db);
        return a.getName().compareTo(b.getName());
    }

    private static Map<String, Integer> computeActivationDepth(
        Map<String, Set<String>> callGraph,
        Set<String> clinitNames,
        Collection<String> roots) {
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String root : roots) {
            if (root == null) continue;
            if (depth.putIfAbsent(root, 0) == null) queue.addLast(root);
        }

        while (!queue.isEmpty()) {
            String cur = queue.removeFirst();
            int curDepth = depth.get(cur);
            Set<String> callees = callGraph.get(cur);
            if (callees == null) continue;
            for (String callee : callees) {
                int next = curDepth + (clinitNames.contains(callee) ? 1 : 0);
                Integer previous = depth.get(callee);
                if (previous != null && previous <= next) continue;
                depth.put(callee, next);
                if (next == curDepth) queue.addFirst(callee);
                else queue.addLast(callee);
            }
        }
        return depth;
    }

    private static List<String> entryRoots(String entryClass,
                                           String entryMethod,
                                           String entryDescriptor) {
        List<String> roots = new ArrayList<>();
        roots.add(LlvmRuntime.mangleMethod(entryClass, entryMethod, entryDescriptor));
        roots.add(LlvmRuntime.mangleMethod("java/lang/String", "<clinit>", "()V"));
        roots.add(LlvmRuntime.mangleMethod("java/lang/System", "<clinit>", "()V"));
        roots.add(LlvmRuntime.mangleMethod("java/jdk/internal/misc/Unsafe", "<clinit>", "()V"));
        roots.add(LlvmRuntime.mangleMethod("java/util/concurrent/ConcurrentHashMap", "<clinit>", "()V"));
        roots.add(LlvmRuntime.mangleMethod("java/util/Properties", "<clinit>", "()V"));
        roots.add(LlvmRuntime.mangleMethod("jdk/internal/util/ArraysSupport", "<clinit>", "()V"));
        return roots;
    }

    private static int countBrokenCycleEdges(List<Function> sorted,
                                             Map<Function, Set<Function>> deps) {
        int n = sorted.size();
        Map<Function, Integer> position = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) position.put(sorted.get(i), i);

        int broken = 0;
        for (Function f : sorted) {
            Set<Function> successors = deps.get(f);
            if (successors == null || successors.isEmpty()) continue;
            Integer fPos = position.get(f);
            if (fPos == null) continue;
            int fPosInt = fPos;
            for (Function dep : successors) {
                Integer depPos = position.get(dep);
                if (depPos != null && depPos > fPosInt) broken++;
            }
        }
        return broken;
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

    private static String extractStaticFieldKey(Instruction inst) {
        Opcode op = inst.getOpcode();
        if (op != Opcode.PUT_STATIC && op != Opcode.GET_STATIC) return null;
        if (inst.getOperands().isEmpty()) return null;
        Value v = inst.getOperands().getFirst();
        if (!(v instanceof Constant c)) return null;
        if (!c.getType().isReference()) return null;
        Object raw = c.getValue();
        if (raw == null) return null;
        String name = raw.toString();
        if (name.isEmpty() || "unknown".equals(name)) return null;
        return name;
    }

    // =====================================================================
    //  Debug / user-vs-system filtering helpers
    // =====================================================================

    private static boolean matchesDebug(String debugName, Function func) {
        if (debugName == null) return true;
        return func != null && func.getName() != null && func.getName().contains(debugName);
    }

    private static boolean matchesDebug(String debugName, AllocationSite site) {
        if (debugName == null) return true;
        return site != null && site.getMethodName() != null
            && site.getMethodName().contains(debugName);
    }

    private static boolean isUserFunction(Function func) {
        String name = func.getName();
        if (name.startsWith("__destruct_")) {
            String className = name.substring("__destruct_".length()).replace('_', '/');
            return !LlvmUtil.isSystemClassName(className);
        }
        int dotIdx = name.indexOf('.');
        String className = dotIdx > 0 ? name.substring(0, dotIdx) : name;
        return !LlvmUtil.isSystemClassName(className);
    }

    private static boolean isUserAllocationSite(AllocationSite site) {
        Type type = site.getType();
        if (type == null || type.isUnknown()) return false;
        if (type.isArray()) {
            Type elem = type.getElementType();
            if (elem.isPrimitive()) return false;
            if (elem.isReference()) {
                return !LlvmUtil.isSystemClassName(elem.getClassName());
            }
            return false;
        }
        if (type.isReference()) {
            return !LlvmUtil.isSystemClassName(type.getClassName());
        }
        return false;
    }
}