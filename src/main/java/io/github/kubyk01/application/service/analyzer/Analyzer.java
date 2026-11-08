package io.github.kubyk01.application.service.analyzer;

import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;

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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.kubyk01.util.LlvmUtil.isAllocation;
import static io.github.kubyk01.util.LlvmUtil.isSystemClassName;

@Slf4j
public class Analyzer {

    private Scheduler scheduler = Schedulers.single();

    public void setScheduler(Scheduler scheduler) {
        if (scheduler != null) this.scheduler = scheduler;
    }

    private final Set<String> deferredClinits = new LinkedHashSet<>();

    @Setter
    private int parallelism = Runtime.getRuntime().availableProcessors();

    @Setter
    private BytecodeToIr translator;

    @Setter
    private ReflectInfo reflectInfo;

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

        System.out.println("\n--- Running dead code elimination ---");
        DeadCodeEliminator dce = new DeadCodeEliminator(module);
        dce.setScheduler(scheduler);
        if (reflectInfo != null) {
            dce.setReflectivelyReadStaticFields(reflectInfo.staticFieldKeys(resolver::getField));
        }
        dce.eliminate();

        int resurrected = resurrectRemovedClinits(module, resolver);
        if (resurrected > 0) {
            System.out.println("Resurrected " + resurrected + " missing <clinit> function(s).");
        }
        int ensured = ensureReferencedClinitsPresent(module, resolver);
        if (ensured > 0) {
            System.out.println("Added " + ensured + " missing <clinit> function(s) for referenced classes.");
        }
        int declaredNatives = declareMissingNativeTargets(module, resolver);
        if (declaredNatives > 0) {
            System.out.println("Declared " + declaredNatives + " missing native-method symbol(s).");
        }

        if (translator != null) {
            translator.closeOverIrCalleesWithSsa();
        }

        List<Function> clinitFunctions = new ArrayList<>();
        for (Function func : module.getFunctions()) {
            if (func.getName().endsWith("__clinit____V")) clinitFunctions.add(func);
        }

        List<Function> phaseFunctions = collectPhaseFunctions(module, resolver);

        // One call graph + fact scan shared by the sort and the lazy-<clinit> pass;
        // the module does not change between them.
        InitGraph initGraph = clinitFunctions.isEmpty() && phaseFunctions.isEmpty()
            ? null : buildInitGraph(module, resolver);

        List<ClinitScheduleEntry> clinitSchedule = sortInitializers(
            clinitFunctions, phaseFunctions, resolver, module, initGraph,
            entryClass, entryMethod, entryDescriptor);

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
        applyLazyClinitForCyclicClasses(module, resolver, initGraph,
            clinitsToInstrument, clinitWrappers,
            deferredClinits);
        initGraph = null;

        AliasAnalysisResult aliasResult = new AliasAnalyzer(module, scheduler, parallelism).analyze();
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

        EscapeAnalysisResult escapeResult = new EscapeAnalyzer(
            module, aliasResult, resolver, scheduler).analyze();
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
                                System.out.println("  " + site + " -> " +
                                    escapeResult.getSiteStatus(site));
                            }
                        }
                    }
                }
            }
        }

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

    // ------------------------------------------------------------------
    //  Lazy <clinit> for cyclic groups
    // ------------------------------------------------------------------

    private void applyLazyClinitForCyclicClasses(Module module,
                                                 DependencyResolver resolver,
                                                 InitGraph g,
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

        Set<String> forcedClasses = new LinkedHashSet<>();
        if (forcedDeferredClinits != null) {
            for (String clinitName : forcedDeferredClinits) {
                String cls = clinitNameToClassName.get(clinitName);
                if (cls != null && allWithClinit.contains(cls)) forcedClasses.add(cls);
            }
        }

        Map<String, Set<String>> classDepDeltas = new ConcurrentHashMap<>();
        Map<String, Set<String>> fieldWriters   = new ConcurrentHashMap<>();
        Map<String, Set<String>> fieldReaders   = new ConcurrentHashMap<>();

        boolean[] cut = new boolean[g.live().size()];
        List<Integer> roots = new ArrayList<>();
        for (String name : clinitNames) {
            Integer idx = g.index().get(name);
            if (idx == null) continue;
            cut[idx] = true;
            if (clinitNameToClassName.containsKey(name)) roots.add(idx);
        }
        InitFacts[] facts = closeOverInitializers(g, cut, roots);
        for (int idx : roots) {
            String clinitName = g.live().get(idx).getName();
            String className = clinitNameToClassName.get(clinitName);
            InitFacts f = facts[idx];
            Set<String> delta = bitNames(f.trig(), g.classes());
            delta.remove(className);
            Set<String> nested = new HashSet<>();
            for (int n : f.nested()) nested.add(g.live().get(n).getName());
            publishClinitWalk(className,
                new ClinitWalk(delta, nested, bitNames(f.writes(), g.fields()), bitNames(f.reads(), g.fields())),
                resolver, clinitNameToClassName, classDepDeltas, fieldWriters, fieldReaders);
        }

        Map<String, Set<String>> classDeps = new LinkedHashMap<>();
        for (String cls : allWithClinit) classDeps.put(cls, new LinkedHashSet<>());

        for (Map.Entry<String, Set<String>> entry : classDepDeltas.entrySet()) {
            Set<String> deps = classDeps.get(entry.getKey());
            if (deps == null) continue;
            List<String> sorted = new ArrayList<>(entry.getValue());
            Collections.sort(sorted);
            deps.addAll(sorted);
        }

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
            + " cyclic class(es), " + lazyClasses.size() + " total lazy class(es).");

        LazyClinitInstrumenter instrumenter =
            new LazyClinitInstrumenter(module, resolver, lazyClasses, scheduler);
        int inserted = instrumenter.instrument();
        System.out.println("Lazy <clinit>: instrumented " + inserted + " trigger site(s).");

        for (String cls : lazyClasses) {
            String clinitName = classNameToClinitName.get(cls);
            if (clinitName == null) continue;
            String wrapperName = "fn___lazy_clinit_run_" + sanitizeForWrapper(cls);
            Function wrapper = module.getFunction(wrapperName);
            if (wrapper != null) clinitWrappers.put(clinitName, wrapper.getName());
        }
    }

    // ------------------------------------------------------------------
    //  Initializer reachability: one shared call graph, SCC-condensed closure
    // ------------------------------------------------------------------

    /**
     * Direct-call graph and per-function static-init facts over functions with
     * a body, indexed by position in {@code live}. Fact ids index
     * {@code classes} (trigger owners) and {@code fields} (static field keys).
     */
    private record InitGraph(List<Function> live, Map<String, Integer> index, int[][] callees,
                             List<String> classes, List<String> fields,
                             Map<String, Integer> fieldIds,
                             int[][] trig, int[][] reads, int[][] writes) {
        int classWords() { return (classes.size() + 63) >>> 6; }
        int fieldWords() { return (fields.size() + 63) >>> 6; }
    }

    /**
     * What one initializer reaches through direct calls without entering
     * another initializer. Bit arrays may be shared between initializers and
     * must not be mutated; {@code nested} holds function indices.
     */
    private record InitFacts(long[] trig, long[] reads, long[] writes, int[] nested) {}

    /** What one <clinit> reaches, by name: trigger classes (minus its own), nested <clinit>s, fields. */
    private record ClinitWalk(Set<String> delta, Set<String> nested,
                              Set<String> writes, Set<String> reads) {}

    private static final int[] NO_TARGETS = new int[0];

    private InitGraph buildInitGraph(Module module, DependencyResolver resolver) {
        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }
        int n = live.size();
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.put(live.get(i).getName(), i);

        int[][] callees = new int[n][];
        String[][] trigNames  = new String[n][];
        String[][] readNames  = new String[n][];
        String[][] writeNames = new String[n][];
        Set<String> classUniverse = ConcurrentHashMap.newKeySet();
        Set<String> fieldUniverse = ConcurrentHashMap.newKeySet();
        Map<String, int[]> directCache  = new ConcurrentHashMap<>();
        Map<String, int[]> virtualCache = new ConcurrentHashMap<>();

        forEachIndexParallel(n, i -> {
            IntArrayList calls = new IntArrayList();
            Set<String> tr = new HashSet<>();
            Set<String> rd = new HashSet<>();
            Set<String> wr = new HashSet<>();
            for (BasicBlock block : live.get(i).getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    collectDirectCallees(inst, calls, module, resolver, index, directCache, virtualCache);
                    Opcode op = inst.getOpcode();
                    if (op == Opcode.NEW || op == Opcode.GET_STATIC
                        || op == Opcode.PUT_STATIC || op == Opcode.STATIC_CALL) {
                        String owner = extractTriggerOwner(inst);
                        if (owner != null && !owner.isEmpty()) tr.add(owner);
                    }
                    if (op == Opcode.GET_STATIC || op == Opcode.PUT_STATIC) {
                        String key = extractStaticFieldKey(inst);
                        if (key != null) (op == Opcode.PUT_STATIC ? wr : rd).add(key);
                    }
                }
            }
            calls.sortUnique();
            callees[i] = calls.isEmpty() ? NO_TARGETS : calls.toArray();
            trigNames[i]  = tr.toArray(new String[0]);
            readNames[i]  = rd.toArray(new String[0]);
            writeNames[i] = wr.toArray(new String[0]);
            classUniverse.addAll(tr);
            fieldUniverse.addAll(rd);
            fieldUniverse.addAll(wr);
        });

        List<String> classes = new ArrayList<>(classUniverse);
        List<String> fields  = new ArrayList<>(fieldUniverse);
        Collections.sort(classes);
        Collections.sort(fields);
        Map<String, Integer> classIds = new HashMap<>(classes.size() * 2);
        for (int i = 0; i < classes.size(); i++) classIds.put(classes.get(i), i);
        Map<String, Integer> fieldIds = new HashMap<>(fields.size() * 2);
        for (int i = 0; i < fields.size(); i++) fieldIds.put(fields.get(i), i);

        int[][] trig = new int[n][], reads = new int[n][], writes = new int[n][];
        forEachIndexParallel(n, i -> {
            trig[i]   = toIds(trigNames[i], classIds);
            reads[i]  = toIds(readNames[i], fieldIds);
            writes[i] = toIds(writeNames[i], fieldIds);
            trigNames[i] = readNames[i] = writeNames[i] = null;
        });

        System.out.println("Pre-computing init facts: " + classes.size()
            + " trigger class(es), " + fields.size() + " static field key(s)");
        return new InitGraph(live, index, callees, classes, fields, fieldIds, trig, reads, writes);
    }

    private static int[] toIds(String[] names, Map<String, Integer> ids) {
        if (names.length == 0) return NO_TARGETS;
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) out[i] = ids.get(names[i]);
        return out;
    }

    private static Set<String> bitNames(long[] bits, List<String> names) {
        Set<String> out = new HashSet<>();
        for (int w = 0; w < bits.length; w++) {
            for (long word = bits[w]; word != 0; word &= word - 1) {
                out.add(names.get((w << 6) + Long.numberOfTrailingZeros(word)));
            }
        }
        return out;
    }

    /**
     * For every root, the union of facts over all functions reachable by
     * direct calls without entering a {@code cut} function (calls into a cut
     * function are recorded as nested instead). Same result as one DFS per
     * root, but each strongly connected component of the call graph is
     * summarized once, bottom-up, so the cost is O(edges x words) instead of
     * O(roots x reachable). Summaries are copy-on-write shared and freed once
     * their last caller component has consumed them.
     */
    private static InitFacts[] closeOverInitializers(InitGraph g, boolean[] cut, List<Integer> roots) {
        int n = g.live().size();
        int[][] callees = g.callees();
        int[] cutId = new int[n];
        IntArrayList cutNodes = new IntArrayList();
        for (int i = 0; i < n; i++) {
            if (cut[i]) { cutId[i] = cutNodes.size(); cutNodes.add(i); }
        }
        int classWords = g.classWords(), fieldWords = g.fieldWords();
        int cutWords = (cutNodes.size() + 63) >>> 6;

        // Iterative Tarjan over non-cut edges, started only from roots. A cut
        // node has no incoming non-cut edge, so each root is its own component.
        int[] idx = new int[n];
        Arrays.fill(idx, -1);
        int[] low = new int[n];
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int[] tstack = new int[n];
        int[] cstack = new int[n];
        int[] epos = new int[n];
        IntArrayList members = new IntArrayList();   // nodes, grouped by component, sinks first
        IntArrayList compStart = new IntArrayList();
        int counter = 0, comps = 0, tsp = 0;
        for (int root : roots) {
            if (idx[root] >= 0) continue;
            int csp = 0;
            idx[root] = low[root] = counter++;
            tstack[tsp++] = root;
            cstack[csp] = root; epos[csp++] = 0;
            while (csp > 0) {
                int u = cstack[csp - 1];
                int[] su = callees[u];
                if (epos[csp - 1] < su.length) {
                    int v = su[epos[csp - 1]++];
                    if (cut[v]) continue;
                    if (idx[v] < 0) {
                        idx[v] = low[v] = counter++;
                        tstack[tsp++] = v;
                        cstack[csp] = v; epos[csp++] = 0;
                    } else if (comp[v] < 0) {
                        low[u] = Math.min(low[u], idx[v]);
                    }
                    continue;
                }
                csp--;
                if (csp > 0) {
                    int p = cstack[csp - 1];
                    low[p] = Math.min(low[p], low[u]);
                }
                if (low[u] == idx[u]) {
                    compStart.add(members.size());
                    int w;
                    do {
                        w = tstack[--tsp];
                        comp[w] = comps;
                        members.add(w);
                    } while (w != u);
                    comps++;
                }
            }
        }
        compStart.add(members.size());

        int[] refs = new int[comps];
        for (int k = 0; k < members.size(); k++) {
            int u = members.get(k);
            for (int v : callees[u]) {
                if (!cut[v] && comp[v] != comp[u]) refs[comp[v]]++;
            }
        }

        long[][][] summary = new long[comps][][];
        for (int c = 0; c < comps; c++) {
            SharedBits trig = new SharedBits(classWords);
            SharedBits rd   = new SharedBits(fieldWords);
            SharedBits wr   = new SharedBits(fieldWords);
            SharedBits nest = new SharedBits(cutWords);
            int from = compStart.get(c), to = compStart.get(c + 1);
            for (int k = from; k < to; k++) {
                int u = members.get(k);
                for (int id : g.trig()[u])   trig.set(id);
                for (int id : g.reads()[u])  rd.set(id);
                for (int id : g.writes()[u]) wr.set(id);
                for (int v : callees[u]) {
                    if (cut[v]) { nest.set(cutId[v]); continue; }
                    if (comp[v] == c) continue;
                    long[][] s = summary[comp[v]];
                    trig.or(s[0]); rd.or(s[1]); wr.or(s[2]); nest.or(s[3]);
                }
            }
            summary[c] = new long[][] { trig.bits, rd.bits, wr.bits, nest.bits };
            for (int k = from; k < to; k++) {
                for (int v : callees[members.get(k)]) {
                    if (!cut[v] && comp[v] != c && --refs[comp[v]] == 0) summary[comp[v]] = null;
                }
            }
        }

        InitFacts[] out = new InitFacts[n];
        for (int root : roots) {
            long[][] s = summary[comp[root]];
            IntArrayList nested = new IntArrayList();
            if (s[3] != null) {
                for (int w = 0; w < s[3].length; w++) {
                    for (long word = s[3][w]; word != 0; word &= word - 1) {
                        nested.add(cutNodes.get((w << 6) + Long.numberOfTrailingZeros(word)));
                    }
                }
            }
            out[root] = new InitFacts(
                s[0] != null ? s[0] : new long[classWords],
                s[1] != null ? s[1] : new long[fieldWords],
                s[2] != null ? s[2] : new long[fieldWords],
                nested.toArray());
        }
        return out;
    }

    /** Copy-on-write bitset accumulator: adopts an incoming array by reference until it must diverge. */
    private static final class SharedBits {
        private final int words;
        long[] bits;
        private boolean owned;

        SharedBits(int words) { this.words = words; }

        void set(int id) {
            long mask = 1L << (id & 63);
            if (bits != null && (bits[id >>> 6] & mask) != 0) return;
            own();
            bits[id >>> 6] |= mask;
        }

        void or(long[] add) {
            if (add == null || add == bits) return;
            if (bits == null) { bits = add; return; }
            if (!owned) {
                if (subset(add, bits)) return;
                if (subset(bits, add)) { bits = add; return; }
            }
            own();
            for (int i = 0; i < words; i++) bits[i] |= add[i];
        }

        private void own() {
            if (owned) return;
            bits = bits == null ? new long[words] : bits.clone();
            owned = true;
        }

        private static boolean subset(long[] a, long[] b) {
            for (int i = 0; i < a.length; i++) {
                if ((a[i] & ~b[i]) != 0) return false;
            }
            return true;
        }
    }

    /** Folds one walk into the shared maps and adds superclass and nested-<clinit> dependencies. */
    private static void publishClinitWalk(String className, ClinitWalk walk,
                                          DependencyResolver resolver,
                                          Map<String, String> clinitNameToClassName,
                                          Map<String, Set<String>> classDepDeltas,
                                          Map<String, Set<String>> fieldWriters,
                                          Map<String, Set<String>> fieldReaders) {
        for (String fld : walk.writes()) {
            fieldWriters.computeIfAbsent(fld, k -> ConcurrentHashMap.newKeySet()).add(className);
        }
        for (String fld : walk.reads()) {
            fieldReaders.computeIfAbsent(fld, k -> ConcurrentHashMap.newKeySet()).add(className);
        }

        Set<String> delta = walk.delta(); // fresh mutable set owned by this walk
        ClassNode cn = resolver.getClassNode(className);
        Set<String> supVisited = new HashSet<>();
        while (cn != null && cn.getSuperName() != null
            && !cn.getSuperName().equals(cn.getName())
            && supVisited.add(cn.getSuperName())) {
            String sup = cn.getSuperName();
            if ("java/lang/Object".equals(sup)) break;
            delta.add(sup);
            cn = resolver.getClassNode(sup);
        }
        for (String nc : walk.nested()) {
            String ncls = clinitNameToClassName.get(nc);
            if (ncls != null && !ncls.equals(className)) delta.add(ncls);
        }

        if (!delta.isEmpty()) {
            classDepDeltas.compute(className, (k, e) -> {
                if (e == null) return delta;
                e.addAll(delta);
                return e;
            });
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

    // ------------------------------------------------------------------
    //  <clinit> resurrection / ensuring / native declaration
    // ------------------------------------------------------------------

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

    private Set<String> collectTriggeredOwners(Module module, DependencyResolver resolver) {
        Set<String> owners = new HashSet<>();

        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() == null) continue;
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op == Opcode.GET_STATIC || op == Opcode.PUT_STATIC
                        || op == Opcode.NEW) {
                        String owner = extractTriggerOwner(inst);
                        if (owner != null && !owner.isEmpty()) owners.add(owner);
                    } else if (op == Opcode.STATIC_CALL || op == Opcode.CALL) {
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
                        if (mn != null && mn.isNative()) continue;
                        String declOwner = (foundOwner[0] != null && !foundOwner[0].isEmpty())
                            ? foundOwner[0] : owner;
                        owners.add(declOwner);
                    }
                }
            }
        }

        Deque<String> worklist = new ArrayDeque<>(owners);
        while (!worklist.isEmpty()) {
            String owner = worklist.poll();
            ClassNode cn = resolver.getClassNode(owner);
            if (cn == null) continue;
            String sup = cn.getSuperName();
            if (sup == null || sup.isEmpty() || sup.equals(owner)
                || "java/lang/Object".equals(sup)) continue;
            if (owners.add(sup)) worklist.add(sup);
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
                    if (name.equals("<clinit>") && desc.equals("()V")) return translator;
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

    // ------------------------------------------------------------------
    //  <clinit> sorting
    // ------------------------------------------------------------------

    private List<String> collectBootstrapPhaseFunctions(DependencyResolver resolver) {
        List<String> phases = new ArrayList<>();
        ClassNode systemNode = resolver.getClassNode("java/lang/System");
        if (systemNode == null || systemNode.isExternal()) {
            log.warn("java.lang.System is not loaded; no bootstrap phases will be scheduled");
            return phases;
        }
        for (MethodNode mn : systemNode.getMethods()) {
            if (!mn.isStatic()) continue;
            String n = mn.getName();
            if (!n.startsWith("initPhase")) continue;
            if (n.equals("<clinit>") || n.equals("<init>")) continue;
            phases.add(LlvmRuntime.mangleMethod("java/lang/System", n, mn.getDescriptor()));
        }
        phases.sort(Comparator.naturalOrder());
        return phases;
    }

    private List<Function> collectPhaseFunctions(Module module,
                                                 DependencyResolver resolver) {
        List<String> names = collectBootstrapPhaseFunctions(resolver);
        List<Function> result = new ArrayList<>(names.size());
        for (String name : names) {
            Function f = module.getFunction(name);
            if (f != null && f.getEntryBlock() != null) result.add(f);
        }
        return result;
    }

    private List<ClinitScheduleEntry> sortInitializers(
        List<Function> clinitFunctions,
        List<Function> phaseFunctions,
        DependencyResolver resolver,
        Module module,
        InitGraph g,
        String entryClass,
        String entryMethod,
        String entryDescriptor) {

        if (clinitFunctions.isEmpty() && phaseFunctions.isEmpty()) {
            return List.of();
        }

        System.out.println("Sorting " + clinitFunctions.size()
            + " <clinit> and " + phaseFunctions.size() + " bootstrap-phase function(s)...");

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

        final List<Function> liveFunctions = g.live();
        final Map<String, Integer> funcIndex = g.index();
        final List<String> classList = g.classes();
        final List<String> fieldList = g.fields();
        final Map<String, Integer> fieldIdMap = g.fieldIds();
        final int fieldWords = g.fieldWords();

        Set<String> allInitializerNames = new HashSet<>(clinitNames);
        for (Function f : phaseFunctions) allInitializerNames.add(f.getName());

        List<Function> allInit = new ArrayList<>(clinitFunctions.size() + phaseFunctions.size());
        allInit.addAll(clinitFunctions);
        allInit.addAll(phaseFunctions);

        boolean[] cut = new boolean[liveFunctions.size()];
        for (String name : allInitializerNames) {
            Integer idx = funcIndex.get(name);
            if (idx != null) cut[idx] = true;
        }
        List<Integer> roots = new ArrayList<>(allInit.size());
        for (Function init : allInit) {
            Integer idx = funcIndex.get(init.getName());
            if (idx != null) roots.add(idx);
        }
        InitFacts[] initFacts = closeOverInitializers(g, cut, roots);

        final long[][] initTriggerBits = new long[liveFunctions.size()][];
        final long[][] initReadBits    = new long[liveFunctions.size()][];
        final long[][] initWriteBits   = new long[liveFunctions.size()][];
        final int[][]  nestedInit      = new int[liveFunctions.size()][];
        for (int idx : roots) {
            initTriggerBits[idx] = initFacts[idx].trig();
            initReadBits[idx]    = initFacts[idx].reads();
            initWriteBits[idx]   = initFacts[idx].writes();
            nestedInit[idx]      = initFacts[idx].nested();
        }
        initFacts = null;

        // Every initializer's trigger facts become direct dependency
        // edges, indexed by function position instead of by Function or
        // name hash lookups.
        final int[][] triggeredDeps = new int[liveFunctions.size()][];
        forEachParallel(allInit, init -> {
            if (phaseSet.contains(init)) return;
            Integer initIdxObj = funcIndex.get(init.getName());
            if (initIdxObj == null) return;
            int initIdx = initIdxObj;
            IntArrayList depList = new IntArrayList();

            String ownClass = clinitNameToClassName.get(init.getName());
            if (ownClass != null) {
                ClassNode cn = resolver.getClassNode(ownClass);
                Set<String> visited = new HashSet<>();
                while (cn != null && cn.getSuperName() != null
                    && !cn.getSuperName().equals(cn.getName())
                    && visited.add(cn.getSuperName())) {
                    String sup = cn.getSuperName();
                    if ("java/lang/Object".equals(sup)) break;
                    Function dep = classNameToClinit.get(sup);
                    if (dep != null) {
                        int depIdx = funcIndex.get(dep.getName());
                        if (depIdx != initIdx) depList.add(depIdx);
                    }
                    cn = resolver.getClassNode(sup);
                }
            }

            int[] nested = nestedInit[initIdx];
            if (nested != null) {
                for (int nestedIdx : nested) {
                    String nestedClass = clinitNameToClassName.get(
                        liveFunctions.get(nestedIdx).getName());
                    if (nestedClass == null) continue;
                    Function dep = classNameToClinit.get(nestedClass);
                    if (dep != null) {
                        int depIdx = funcIndex.get(dep.getName());
                        if (depIdx != initIdx) depList.add(depIdx);
                    }
                }
            }

            long[] bits = initTriggerBits[initIdx];
            if (bits != null) {
                for (int w = 0; w < bits.length; w++) {
                    long word = bits[w];
                    while (word != 0) {
                        int bit = Long.numberOfTrailingZeros(word);
                        Function dep = classNameToClinit.get(
                            classList.get((w << 6) + bit));
                        if (dep != null) {
                            int depIdx = funcIndex.get(dep.getName());
                            if (depIdx != initIdx) depList.add(depIdx);
                        }
                        word &= word - 1;
                    }
                }
            }

            depList.sortUnique();
            triggeredDeps[initIdx] = depList.toArray();
        });

        // Field writers/readers, as function indices per field id.
        IntArrayList[] fieldWriters = new IntArrayList[fieldList.size()];
        IntArrayList[] fieldReaders = new IntArrayList[fieldList.size()];
        for (Function init : allInit) {
            Integer initIdxObj = funcIndex.get(init.getName());
            if (initIdxObj == null) continue;
            int initIdx = initIdxObj;
            long[] rbits = initReadBits[initIdx];
            if (rbits != null) {
                for (int w = 0; w < rbits.length; w++) {
                    long word = rbits[w];
                    while (word != 0) {
                        int fieldId = (w << 6) + Long.numberOfTrailingZeros(word);
                        if (fieldReaders[fieldId] == null) {
                            fieldReaders[fieldId] = new IntArrayList();
                        }
                        fieldReaders[fieldId].add(initIdx);
                        word &= word - 1;
                    }
                }
            }
            long[] wbits = initWriteBits[initIdx];
            if (wbits != null) {
                for (int w = 0; w < wbits.length; w++) {
                    long word = wbits[w];
                    while (word != 0) {
                        int fieldId = (w << 6) + Long.numberOfTrailingZeros(word);
                        if (fieldWriters[fieldId] == null) {
                            fieldWriters[fieldId] = new IntArrayList();
                        }
                        fieldWriters[fieldId].add(initIdx);
                        word &= word - 1;
                    }
                }
            }
        }

        IntArrayList[] depsList = new IntArrayList[liveFunctions.size()];
        int baseEdges = 0;
        for (Function f : allInit) {
            Integer idxObj = funcIndex.get(f.getName());
            if (idxObj == null) continue;
            int idx = idxObj;
            IntArrayList d = new IntArrayList();
            int[] base = triggeredDeps[idx];
            if (base != null) d.addAll(base);
            d.sortUnique();
            depsList[idx] = d;
            baseEdges += d.size();
        }

        BitSet[] provenance = new BitSet[liveFunctions.size()];
        for (int fieldId = 0; fieldId < fieldList.size(); fieldId++) {
            IntArrayList writers = fieldWriters[fieldId];
            IntArrayList readers = fieldReaders[fieldId];
            if (writers == null || writers.isEmpty() || readers == null) continue;
            BitSet writerBits = new BitSet(liveFunctions.size());
            for (int wi = 0; wi < writers.size(); wi++) writerBits.set(writers.get(wi));
            for (int ri = 0; ri < readers.size(); ri++) {
                int reader = readers.get(ri);
                if (depsList[reader] == null) continue;
                if (provenance[reader] == null) provenance[reader] = new BitSet(liveFunctions.size());
                provenance[reader].or(writerBits);
            }
        }
        for (int reader = 0; reader < provenance.length; reader++) {
            BitSet bits = provenance[reader];
            if (bits == null) continue;
            bits.clear(reader);
            IntArrayList rd = depsList[reader];
            for (int w = bits.nextSetBit(0); w >= 0; w = bits.nextSetBit(w + 1)) rd.add(w);
            provenance[reader] = null;
        }

        int[][] deps = new int[liveFunctions.size()][];
        int finalEdges = 0;
        for (Function f : allInit) {
            Integer idxObj = funcIndex.get(f.getName());
            if (idxObj == null) continue;
            int idx = idxObj;
            IntArrayList d = depsList[idx];
            d.sortUnique();
            deps[idx] = d.toArray();
            finalEdges += deps[idx].length;
        }
        int provenanceEdges = finalEdges - baseEdges;
        if (provenanceEdges > 0) {
            System.out.println("Added " + provenanceEdges
                + " static-field provenance edge(s) to init dependency graph.");
        }

        IntArrayList[] revList = new IntArrayList[liveFunctions.size()];
        for (Function f : allInit) {
            Integer readerObj = funcIndex.get(f.getName());
            if (readerObj == null) continue;
            int reader = readerObj;
            for (int dep : deps[reader]) {
                if (revList[dep] == null) revList[dep] = new IntArrayList();
                revList[dep].add(reader);
            }
        }
        int[][] revDeps = new int[liveFunctions.size()][];
        for (int i = 0; i < revList.length; i++) {
            if (revList[i] == null) continue;
            revList[i].sortUnique();
            revDeps[i] = revList[i].toArray();
        }

        Map<String, Integer> activationDepth = computeActivationDepth(
            g, clinitNames,
            entryRoots(entryClass, entryMethod, entryDescriptor));

        Map<Function, Set<Function>> clinitDeps = new LinkedHashMap<>();
        for (Function c : clinitFunctions) {
            Set<Function> filtered = new LinkedHashSet<>();
            Integer idxObj = funcIndex.get(c.getName());
            if (idxObj != null) {
                for (int depIdx : deps[idxObj]) {
                    Function d = liveFunctions.get(depIdx);
                    if (clinitNames.contains(d.getName())) filtered.add(d);
                }
            }
            clinitDeps.put(c, filtered);
        }
        List<Function> clinitOrder = tarjanLinearize(clinitFunctions, clinitDeps, activationDepth);

        List<Function> phaseOrder = new ArrayList<>(phaseFunctions);
        phaseOrder.sort(Comparator.comparingInt(Analyzer::phaseOrdinal));

        int[] clinitIndex = new int[liveFunctions.size()];
        Arrays.fill(clinitIndex, -1);
        for (int i = 0; i < clinitOrder.size(); i++) {
            Integer idxObj = funcIndex.get(clinitOrder.get(i).getName());
            if (idxObj != null) clinitIndex[idxObj] = i;
        }

        Map<Function, Integer> afterMap  = new HashMap<>();
        Map<Function, Integer> beforeMap = new HashMap<>();
        for (Function p : phaseOrder) {
            int pIdx = funcIndex.get(p.getName());
            int after = 0;
            for (int d : transitiveClosureInt(deps, pIdx)) {
                int idx = clinitIndex[d];
                if (idx >= 0 && idx + 1 > after) after = idx + 1;
            }
            afterMap.put(p, after);

            int before = clinitOrder.size();
            for (int d : transitiveClosureInt(revDeps, pIdx)) {
                int idx = clinitIndex[d];
                if (idx >= 0 && idx < before) before = idx;
            }
            beforeMap.put(p, before);

            System.out.println("Phase placement: " + p.getName()
                + " after=" + after + ", before=" + before);
        }

        Set<String> deferred = computeDeferredClinits(
            clinitOrder, phaseOrder, afterMap,
            initReadBits, initWriteBits, funcIndex, fieldIdMap, fieldWords);
        deferred.addAll(computeJniDependentClinits(module, clinitOrder));

        if (!deferred.isEmpty()) {
            this.deferredClinits.addAll(deferred);

            System.out.println("Deferring " + deferred.size()
                + " <clinit>(s) to lazy initialization.");

            Set<Function> deferredSet = new HashSet<>();
            for (Function c : clinitOrder) {
                if (deferred.contains(c.getName())) deferredSet.add(c);
            }
            clinitOrder.removeIf(deferredSet::contains);

            int[] trimmedAfterDefer = new int[liveFunctions.size()];
            Arrays.fill(trimmedAfterDefer, -1);
            for (int i = 0; i < clinitOrder.size(); i++) {
                Integer idxObj = funcIndex.get(clinitOrder.get(i).getName());
                if (idxObj != null) trimmedAfterDefer[idxObj] = i;
            }
            for (Function p : phaseOrder) {
                int pIdx = funcIndex.get(p.getName());
                int afterTrimmed = 0;
                for (int d : transitiveClosureInt(deps, pIdx)) {
                    int idx = trimmedAfterDefer[d];
                    if (idx >= 0 && idx + 1 > afterTrimmed) afterTrimmed = idx + 1;
                }
                afterMap.put(p, afterTrimmed);

                int beforeTrimmed = clinitOrder.size();
                for (int d : transitiveClosureInt(revDeps, pIdx)) {
                    int idx = trimmedAfterDefer[d];
                    if (idx >= 0 && idx < beforeTrimmed) beforeTrimmed = idx;
                }
                beforeMap.put(p, beforeTrimmed);

                System.out.println("Phase placement (post-defer): " + p.getName()
                    + " after=" + afterTrimmed + ", before=" + beforeTrimmed);
            }
        }

        Map<Function, Integer> clinitLevels = new HashMap<>();
        Set<Function> cyclicClinits = new HashSet<>();
        computeClinitLevelsAndCyclic(clinitOrder, clinitDeps, clinitLevels, cyclicClinits);

        clinitOrder.sort(Comparator.comparingInt(f -> clinitLevels.getOrDefault(f, 0)));

        int[] trimmedIdx = new int[liveFunctions.size()];
        Arrays.fill(trimmedIdx, -1);
        for (int i = 0; i < clinitOrder.size(); i++) {
            Integer idxObj = funcIndex.get(clinitOrder.get(i).getName());
            if (idxObj != null) trimmedIdx[idxObj] = i;
        }
        for (Function p : phaseOrder) {
            int pIdx = funcIndex.get(p.getName());
            int after = 0;
            for (int d : transitiveClosureInt(deps, pIdx)) {
                int idx = trimmedIdx[d];
                if (idx >= 0 && idx + 1 > after) after = idx + 1;
            }
            afterMap.put(p, after);

            int before = clinitOrder.size();
            for (int d : transitiveClosureInt(revDeps, pIdx)) {
                int idx = trimmedIdx[d];
                if (idx >= 0 && idx < before) before = idx;
            }
            beforeMap.put(p, before);
        }

        List<ClinitScheduleEntry> result =
            new ArrayList<>(clinitOrder.size() + phaseOrder.size());
        int ci = 0;
        int prevPhaseSlot = 0;
        int groupCounter = 0;
        int currentLevel = -2;
        for (Function p : phaseOrder) {
            int after  = afterMap.get(p);
            int before = beforeMap.get(p);
            int target = Math.max(after, prevPhaseSlot);

            if (target > before) {
                System.out.println("WARNING: phase " + p.getName()
                    + " has cyclic dependency with clinits (after=" + after
                    + ", before=" + before + "); placed at " + target
                    + " (phase's own dependency edges take priority)");
            }

            while (ci < target && ci < clinitOrder.size()) {
                Function c = clinitOrder.get(ci++);
                int pg;
                if (cyclicClinits.contains(c)) {
                    pg = -1;
                    currentLevel = -2;
                } else {
                    int lvl = clinitLevels.getOrDefault(c, 0);
                    if (lvl != currentLevel) { groupCounter++; currentLevel = lvl; }
                    pg = groupCounter;
                }
                result.add(new ClinitScheduleEntry(c, false, pg));
            }
            result.add(new ClinitScheduleEntry(p, true, -1));
            currentLevel = -2;
            prevPhaseSlot = target;
        }
        while (ci < clinitOrder.size()) {
            Function c = clinitOrder.get(ci++);
            int pg;
            if (cyclicClinits.contains(c)) {
                pg = -1;
                currentLevel = -2;
            } else {
                int lvl = clinitLevels.getOrDefault(c, 0);
                if (lvl != currentLevel) { groupCounter++; currentLevel = lvl; }
                pg = groupCounter;
            }
            result.add(new ClinitScheduleEntry(c, false, pg));
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

    private static int[] transitiveClosureInt(int[][] graph, int start) {
        boolean[] visited = new boolean[graph.length];
        int[] stack = new int[graph.length];
        IntArrayList order = new IntArrayList();
        int top = 0;
        visited[start] = true;
        stack[top++] = start;
        while (top > 0) {
            int cur = stack[--top];
            order.add(cur);
            int[] succ = graph[cur];
            if (succ == null) continue;
            for (int next : succ) {
                if (!visited[next]) {
                    visited[next] = true;
                    stack[top++] = next;
                }
            }
        }
        return order.toArray();
    }

    private static final class IntArrayList {
        private int[] data = new int[4];
        private int size;

        void add(int value) {
            if (size == data.length) {
                data = Arrays.copyOf(data, size << 1);
            }
            data[size++] = value;
        }

        void addAll(int[] values) {
            for (int value : values) add(value);
        }

        int get(int index) {
            return data[index];
        }

        int size() {
            return size;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void sortUnique() {
            if (size < 2) return;
            Arrays.sort(data, 0, size);
            int write = 1;
            for (int read = 1; read < size; read++) {
                if (data[read] != data[write - 1]) {
                    data[write++] = data[read];
                }
            }
            size = write;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }


    private static Function resolveCallTarget(Module module, String callee) {
        if (callee == null || callee.indexOf('(') < 0) return null;
        Function f = module.getFunction(LlvmRuntime.mangleCallable(callee));
        if (f == null) f = module.getFunction("__jnative_" + LlvmRuntime.mangleCallable(callee));
        if (f == null) f = module.getFunction(callee);
        return f;
    }

    private Set<String> computeJniDependentClinits(Module module, List<Function> clinitOrder) {
        Map<Function, Set<Function>> callers = new HashMap<>();
        Set<Function> tainted = new HashSet<>();
        Deque<Function> work = new ArrayDeque<>();

        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() == null || isSystemClassName(f.getOwnerClass())) continue;
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op != Opcode.CALL && op != Opcode.STATIC_CALL && op != Opcode.SPECIAL_CALL
                        && op != Opcode.VIRTUAL_CALL && op != Opcode.INTERFACE_CALL) continue;
                    for (int k = 0; k < Math.min(2, inst.getOperands().size()); k++) {
                        Function target = resolveCallTarget(module, extractCalleeConstant(inst, k));
                        if (target == null) continue;
                        if (target.isJniNative() && !isSystemClassName(target.getOwnerClass())) {
                            if (tainted.add(f)) work.add(f);
                        } else {
                            callers.computeIfAbsent(target, x -> new HashSet<>()).add(f);
                        }
                    }
                }
            }
        }

        while (!work.isEmpty()) {
            Function f = work.poll();
            for (Function caller : callers.getOrDefault(f, Set.of())) {
                if (tainted.add(caller)) work.add(caller);
            }
        }

        Set<String> result = new LinkedHashSet<>();
        for (Function c : clinitOrder) {
            if (!isSystemClassName(c.getOwnerClass()) && tainted.contains(c)) result.add(c.getName());
        }
        if (!result.isEmpty()) {
            System.out.println("Deferring " + result.size()
                + " <clinit>(s) that call JNI natives to lazy initialization.");
        }
        return result;
    }

    private Set<String> computeDeferredClinits(
        List<Function> clinitOrder,
        List<Function> phaseOrder,
        Map<Function, Integer> afterMap,
        long[][] initReadBits,
        long[][] initWriteBits,
        Map<String, Integer> funcIndex,
        Map<String, Integer> fieldIdMap,
        int fieldWords) {

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

                long[] pWrites = initWriteBits[funcIndex.get(p.getName())];
                if (pWrites == null || bitsEmpty(pWrites)) continue;

                long[] pDirectReads = phaseDirectReadBits.get(p);

                int scanEnd = Math.min(after, clinitOrder.size());
                for (int i = 0; i < scanEnd; i++) {
                    Function c = clinitOrder.get(i);
                    String cName = c.getName();
                    if (deferred.contains(cName)) continue;

                    Integer cIdxObj = funcIndex.get(cName);
                    if (cIdxObj == null) continue;
                    int cIdx = cIdxObj;
                    long[] cReads  = initReadBits[cIdx];
                    long[] cWrites = initWriteBits[cIdx];
                    if (cReads == null || cWrites == null) continue;

                    boolean cReadsP  = bitsIntersect(cReads,  pWrites);
                    boolean cWritesP = (pDirectReads != null) && bitsIntersect(cWrites, pDirectReads);

                    if (cReadsP && !cWritesP) {
                        deferred.add(cName);
                        changed = true;
                    }
                }
            }

            if (changed) {
                Map<Function, Integer> trimmedIdx = new HashMap<>();
                int k = 0;
                for (Function c : clinitOrder) {
                    if (!deferred.contains(c.getName())) trimmedIdx.put(c, k++);
                }
                for (Function p : phaseOrder) {
                    Integer prev = afterMap.get(p);
                    if (prev != null && prev > trimmedIdx.size()) afterMap.put(p, trimmedIdx.size());
                }
            }
        }

        if (pass >= MAX_PASSES) {
            System.out.println("Deferral fixed point did not converge after " + MAX_PASSES
                + " passes; " + deferred.size() + " clinit(s) deferred so far.");
        }

        return deferred;
    }

    private static boolean bitsIntersect(long[] a, long[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if ((a[i] & b[i]) != 0) return true;
        }
        return false;
    }

    private static boolean bitsEmpty(long[] bits) {
        for (long bit : bits) {
            if (bit != 0) return false;
        }
        return true;
    }

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
            throw new RuntimeException("parallel initialization phase interrupted", e);
        }

        Throwable first = failure.get();
        if (first != null) {
            throw new RuntimeException("parallel initialization phase failed", first);
        }
    }

    /** Runs {@code action} over 0..n-1 in about parallelism*4 contiguous chunks. */
    private void forEachIndexParallel(int n, java.util.function.IntConsumer action) {
        if (n == 0) return;
        int chunks = Math.max(1, Math.min(n, parallelism * 4));
        List<int[]> ranges = new ArrayList<>(chunks);
        for (int c = 0; c < chunks; c++) {
            ranges.add(new int[] { (int) ((long) n * c / chunks), (int) ((long) n * (c + 1) / chunks) });
        }
        forEachParallel(ranges, r -> {
            for (int i = r[0]; i < r[1]; i++) action.accept(i);
        });
    }

    /**
     * Adds the indices of the bodies a call can reach. Resolutions are memoized
     * per callee string: the same target is called from many sites, and virtual
     * resolution walks every subclass.
     */
    private static void collectDirectCallees(Instruction inst,
                                             IntArrayList out,
                                             Module module,
                                             DependencyResolver resolver,
                                             Map<String, Integer> index,
                                             Map<String, int[]> directCache,
                                             Map<String, int[]> virtualCache) {
        Opcode op = inst.getOpcode();
        boolean virtual = op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL;
        if (!virtual && op != Opcode.STATIC_CALL && op != Opcode.CALL && op != Opcode.SPECIAL_CALL) return;
        String callee = extractCalleeConstant(inst,
            op == Opcode.STATIC_CALL || op == Opcode.CALL ? 0 : 1);
        if (callee == null) return;
        Map<String, int[]> cache = virtual ? virtualCache : directCache;
        int[] targets = cache.get(callee);
        if (targets == null) {
            targets = virtual
                ? resolveVirtualTargets(callee, module, resolver, index)
                : resolveDirectTarget(callee, module, index);
            cache.putIfAbsent(callee, targets);
        }
        out.addAll(targets);
    }

    private static int[] resolveDirectTarget(String callee, Module module, Map<String, Integer> index) {
        Function f = module.getFunction(callee);
        if (f == null) f = module.getFunction(LlvmRuntime.mangleCallable(callee));
        Integer i = f == null ? null : index.get(f.getName()); // index holds only bodies
        return i == null ? NO_TARGETS : new int[] { i };
    }

    private static int[] resolveVirtualTargets(String callee, Module module,
                                               DependencyResolver resolver,
                                               Map<String, Integer> index) {
        int dotIdx = callee.lastIndexOf('.');
        int parenIdx = callee.indexOf('(');
        if (dotIdx <= 0 || parenIdx <= dotIdx) return NO_TARGETS;
        String owner = callee.substring(0, dotIdx);
        String methodName = callee.substring(dotIdx + 1, parenIdx);
        String descriptor = callee.substring(parenIdx);

        Set<String> candidates = new HashSet<>();
        candidates.add(owner);
        candidates.addAll(resolver.getSubclasses(owner));

        IntArrayList out = new IntArrayList();
        String[] foundOwner = new String[1];
        for (String cls : candidates) {
            foundOwner[0] = null;
            MethodNode mn = resolver.findMethodInHierarchy(cls, methodName, descriptor, foundOwner);
            if (mn == null || mn.isAbstract() || mn.isNative()) continue;
            String decl = foundOwner[0] != null ? foundOwner[0] : cls;
            Integer i = index.get(LlvmRuntime.mangleMethod(decl, methodName, descriptor));
            if (i != null) out.add(i);
        }
        if (out.isEmpty()) return NO_TARGETS;
        out.sortUnique();
        return out.toArray();
    }

    private static void computeClinitLevelsAndCyclic(
        List<Function> clinitOrder,
        Map<Function, Set<Function>> clinitDeps,
        Map<Function, Integer> outLevels,
        Set<Function> outCyclic) {

        Map<Function, Integer> index   = new HashMap<>();
        Map<Function, Integer> lowlink = new HashMap<>();
        Deque<Function> stack   = new ArrayDeque<>();
        Set<Function> onStack   = new HashSet<>();
        List<List<Function>> sccs = new ArrayList<>();
        int[] counter = {0};

        for (Function c : clinitOrder) {
            if (!index.containsKey(c)) {
                strongConnect(c, clinitDeps, index, lowlink, stack, onStack, sccs, counter);
            }
        }

        Map<Function, Integer> clinitToScc = new HashMap<>();
        for (int i = 0; i < sccs.size(); i++) {
            for (Function f : sccs.get(i)) clinitToScc.put(f, i);
        }

        Map<Integer, Integer> sccLevels = new HashMap<>();
        for (int i = 0; i < sccs.size(); i++) {
            int level = 0;
            for (Function c : sccs.get(i)) {
                Set<Function> deps = clinitDeps.get(c);
                if (deps == null) continue;
                for (Function d : deps) {
                    Integer dScc = clinitToScc.get(d);
                    if (dScc == null || dScc == i) continue;
                    Integer dLevel = sccLevels.get(dScc);
                    if (dLevel != null) level = Math.max(level, dLevel + 1);
                }
            }
            sccLevels.put(i, level);
        }

        for (Function c : clinitOrder) {
            Integer scc = clinitToScc.get(c);
            outLevels.put(c, scc != null ? sccLevels.getOrDefault(scc, 0) : 0);
        }
        for (List<Function> scc : sccs) {
            if (scc.size() > 1) outCyclic.addAll(scc);
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
        for (List<Function> scc : sccs) result.addAll(orderScc(scc, deps, activationDepth));
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
        for (Function f : scc) if (!visited.contains(f)) rest.add(f);
        rest.sort(Comparator.comparing(Function::getName));
        ordered.addAll(rest);
        return ordered;
    }

    private static int compareActivation(Function a, Function b,
                                         Map<String, Integer> activationDepth) {
        int da = activationDepth.getOrDefault(a.getName(), Integer.MAX_VALUE);
        int db = activationDepth.getOrDefault(b.getName(), Integer.MAX_VALUE);
        if (da != db) return Integer.compare(da, db);
        return a.getName().compareTo(b.getName());
    }

    /** 0-1 BFS from the roots; entering a <clinit> costs one activation level. */
    private static Map<String, Integer> computeActivationDepth(
        InitGraph g,
        Set<String> clinitNames,
        Collection<String> roots) {
        int n = g.live().size();
        int[] depth = new int[n];
        Arrays.fill(depth, Integer.MAX_VALUE);
        boolean[] isClinit = new boolean[n];
        for (String name : clinitNames) {
            Integer i = g.index().get(name);
            if (i != null) isClinit[i] = true;
        }
        Map<String, Integer> result = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (String root : roots) {
            if (root == null) continue;
            result.putIfAbsent(root, 0); // roots count even without a body
            Integer i = g.index().get(root);
            if (i != null && depth[i] != 0) { depth[i] = 0; queue.addLast(i); }
        }

        while (!queue.isEmpty()) {
            int cur = queue.removeFirst();
            int curDepth = depth[cur];
            for (int callee : g.callees()[cur]) {
                int next = curDepth + (isClinit[callee] ? 1 : 0);
                if (depth[callee] <= next) continue;
                depth[callee] = next;
                if (next == curDepth) queue.addFirst(callee);
                else                  queue.addLast(callee);
            }
        }
        for (int i = 0; i < n; i++) {
            if (depth[i] != Integer.MAX_VALUE) result.put(g.live().get(i).getName(), depth[i]);
        }
        return result;
    }

    private static List<String> entryRoots(String entryClass, String entryMethod,
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
            if (elem.isReference()) return !LlvmUtil.isSystemClassName(elem.getClassName());
            return false;
        }
        if (type.isReference()) return !LlvmUtil.isSystemClassName(type.getClassName());
        return false;
    }
}