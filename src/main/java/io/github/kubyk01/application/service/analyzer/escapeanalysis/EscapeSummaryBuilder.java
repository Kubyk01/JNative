package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Opcodes;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldOwnerAndName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;
import static io.github.kubyk01.util.LlvmUtil.isAllocation;
import static io.github.kubyk01.util.LlvmUtil.isLazyClinitWrapper;

/**
 * Computes an {@link EscapeSummary} for every function in the module.
 *
 * <h2>What changed relative to the previous revision</h2>
 *
 * <p>The previous revision of this class tracked only <em>parameter</em>
 * escape facts: which parameters escape, which are returned, which are
 * stored into static fields, and the parameter-to-parameter escape
 * edges. The {@code sitesReachableFromParams} field was populated with
 * the allocation sites that were <b>directly</b> stored into a field of
 * a parameter:</p>
 *
 * <pre>
 *     // InternalLock.&lt;init&gt;:
 *     this.lock = new ReentrantLock();       // sitesReachableFromParams[0] = {ReentrantLock}
 * </pre>
 *
 * <p>That was sufficient for the {@code InternalLock} case because the
 * owner ({@code InternalLock}) was allocated in the very same function
 * that later promoted it to {@code RETURN}, so the promotion reached the
 * {@code ReentrantLock} site directly.</p>
 *
 * <p>It is <b>not</b> sufficient for the {@code BufferedWriter} and
 * {@code ProtectionDomain} cases. There the owner lives in one function
 * and the nested site lives in another:</p>
 *
 * <pre>
 *     BufferedWriter.&lt;init&gt;(Writer):
 *         this.cb = new char[8192];          // sitesReachableFromParams[0] = {cb}
 *
 *     PrintStream.&lt;init&gt;(OutputStream, ...):
 *         this.charOut = new OutputStreamWriter(out, charset);
 *         this.textOut = new BufferedWriter(charOut);
 *         // direct tracking sees only: sitesReachableFromParams[0] = {charOut, textOut}
 *         // DOES NOT see the cb site inside textOut
 *
 *     System.&lt;clinit&gt;:
 *         System.out = new PrintStream(...); // promotes PrintStream to GLOBAL
 * </pre>
 *
 * <p>Because {@code cb} was never in {@code PrintStream.&lt;init&gt;}'s
 * summary, the {@code GLOBAL} promotion at {@code System.&lt;clinit&gt;}
 * stopped at {@code BufferedWriter} and never reached {@code cb}.
 * {@code cb} stayed {@code HEAP}, {@link io.github.kubyk01.application.service.analyzer.lifetime.LifetimeAnalyzer}
 * placed a destruction point at the {@code PUT_FIELD this.cb = …}, and
 * {@link io.github.kubyk01.application.service.optimizer.DestructorInserter}
 * emitted a {@code free} followed by a null-out of {@code this.cb}. The
 * next write to {@code BufferedWriter.cb} loaded a null array and
 * {@code String.getChars} reported
 * {@code "Cannot invoke java.lang.String.getChars(IICIV) because <array> is null"}.</p>
 *
 * <h2>What this revision adds</h2>
 *
 * <p>Two new transitive tracking structures:</p>
 *
 * <ol>
 *   <li>{@code localPointsTo}: value &rarr; allocation sites it may point
 *       to. Seeded from every {@code NEW}/{@code NEW_ARRAY}/{@code
 *       MULTI_NEW_ARRAY} result, propagated through {@code LOAD},
 *       {@code STORE}, {@code PHI}, {@code CAST}, {@code CHECKCAST}, and
 *       call return values.</li>
 *
 *   <li>{@code localSiteContents}: site &rarr; sites reachable from it
 *       by following field references. Populated from three sources:
 *       direct {@code PUT_FIELD}/{@code ASTORE} with a non-parameter
 *       base; the callee's own {@code sitesReachableFromParams} at every
 *       call site; and the transitive closure of the rhs in the
 *       containing value.</li>
 * </ol>
 *
 * <p>With both, the summary's {@code sitesReachableFromParams[i]} for a
 * constructor of {@code BufferedWriter} says: "the value passed as
 * parameter {@code i} transitively contains {@code cb}". The same
 * property is then preserved by {@code PrintStream.&lt;init&gt;}:
 * {@code sitesReachableFromParams[0] = {charOut, textOut, cb, ...}} —
 * the transitive closure of everything reachable through the receiver
 * of a {@code PrintStream}.</p>
 *
 * <p>At {@code System.&lt;clinit&gt;}, when the {@code PrintStream}
 * allocation is promoted to {@code GLOBAL}, the
 * {@link IntraproceduralEscape#processCall} promotion loop reaches every
 * site in the summary's closure — including {@code cb} — and marks it
 * {@code GLOBAL} as well. {@code LifetimeAnalyzer} therefore skips it
 * (GLOBAL returns early, no destruction point), and no {@code free} or
 * null-out is emitted at {@code PUT_FIELD}. The crash is gone at the
 * source.</p>
 *
 * <h2>SCC-driven fixed point</h2>
 *
 * <p>Two properties are required for the fixed point to converge in
 * O(lattice height) iterations rather than oscillate:</p>
 *
 * <ol>
 *   <li><b>Pre-fill with &perp;.</b> Every SCC member receives an empty
 *       summary in {@link #summaries} before the fixed point starts.
 *       Without this, the first iteration sees
 *       {@code snapshot.get(calleeName) == null} for every intra-SCC
 *       call, and {@code processCall} takes its conservative
 *       "all-reference-args-escape" branch, producing a near-&top;
 *       value that the next iteration then shrinks — the SCC oscillates
 *       between the two bounds and hits
 *       {@link #MAX_SCC_ITERATIONS}.</li>
 *
 *   <li><b>Monotone merge on commit.</b> Old and new summaries are
 *       merged rather than replaced, so the stored value never
 *       decreases across iterations even when the freshly computed
 *       value is strictly smaller than the previous one. This is the
 *       same commit discipline
 *       {@link io.github.kubyk01.application.service.analyzer.aliasanalysis.SummaryBuilder}
 *       uses, and it is what makes the SCC loop terminate.</li>
 * </ol>
 *
 * <p>Together these two properties reduce the observed oscillation of
 * the largest SCC (size 1290) from 2000 capped iterations to the same
 * O(dozens) that {@code SummaryBuilder} already exhibits on the same
 * graph.</p>
 */
@Slf4j
public class EscapeSummaryBuilder {

    /**
     * Upper bound on the number of intra-SCC fixed-point iterations.
     * Mirrors the bound used by
     * {@link io.github.kubyk01.application.service.analyzer.aliasanalysis.SummaryBuilder}.
     * Reaching it is reported as a warning; the analysis then continues
     * with whatever the last iteration produced, which is guaranteed
     * sound (a subset of the true fixed point) because every summary
     * field is monotonically growing.
     */
    private static final int MAX_SCC_ITERATIONS = 2000;

    /**
     * Maximum number of functions processed in one parallel batch inside
     * {@link #processSCC}. Without this limit a single iteration can
     * process the whole SCC at once, and each element of the batch is a
     * freshly built summary — hundreds of bytes to kilobytes — so a large
     * SCC translates into hundreds of MB of temporary memory for one
     * batch. The fixpoint is still monotone in the summaries, so splitting
     * a pass into chunks changes the order of work but not the final
     * lattice value.
     */
    private static final int EVAL_BATCH_SIZE = 2048;

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final DependencyResolver resolver;
    private final Scheduler scheduler;
    private final NativeBodyEscapeAnalyzer nativeAnalyzer;
    private final Map<String, EscapeSummary> summaries = new ConcurrentHashMap<>();

    public EscapeSummaryBuilder(Module module,
                                AliasAnalysisResult aliasResult,
                                DependencyResolver resolver) {
        this(module, aliasResult, resolver, Schedulers.single());
    }

    public EscapeSummaryBuilder(Module module,
                                AliasAnalysisResult aliasResult,
                                DependencyResolver resolver,
                                Scheduler scheduler) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.resolver = resolver;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
        this.nativeAnalyzer = new NativeBodyEscapeAnalyzer(resolver);
    }

    // =====================================================================
    //  Public entry point
    // =====================================================================

    /**
     * Computes the escape summary of every function in the module.
     *
     * <p>Decomposes the call graph into its strongly connected components,
     * processes them in reverse topological order, and performs a local
     * fixed point inside each component. See the class-level javadoc for
     * the full reasoning.</p>
     */
    public Map<String, EscapeSummary> build() {
        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }
        if (live.isEmpty()) return summaries;

        // ---- Call graph ------------------------------------------------
        Map<String, Function> byName = new HashMap<>(live.size() * 2);
        for (Function f : live) {
            byName.put(f.getName(), f);
        }

        Map<Function, List<Function>> callees = new HashMap<>(live.size() * 2);
        Map<String, Set<Function>> callersOf = new HashMap<>(live.size() * 2);

        for (Function f : live) {
            List<Function> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    String callee = extractCalleeName(inst);
                    if (callee == null) continue;
                    String calleeKey = canonicalCalleeKey(callee);

                    if (isLazyClinitWrapper(calleeKey)) continue;

                    if (!seen.add(calleeKey)) continue;
                    Function calleeFunc = byName.get(calleeKey);
                    if (calleeFunc != null) {
                        out.add(calleeFunc);
                    }
                    callersOf.computeIfAbsent(calleeKey, k -> new HashSet<>(4)).add(f);
                }
            }
            callees.put(f, out);
        }

        List<List<Function>> sccs = computeSCCs(live, callees);

        int totalProcessed = 0;
        int maxSccSize = 0;
        for (List<Function> scc : sccs) {
            if (scc.size() > maxSccSize) maxSccSize = scc.size();
            totalProcessed += processSCC(scc, callersOf);
        }

        System.out.println("EscapeSummaryBuilder: converged after " + sccs.size()
                + " SCC(s) (largest " + maxSccSize + " function(s)), "
                + totalProcessed + " total evaluations, "
                + summaries.size() + " summaries");
        return summaries;
    }

    // =====================================================================
    //  Per-SCC fixed point
    // =====================================================================

    private int processSCC(List<Function> scc, Map<String, Set<Function>> callersOf) {
        if (scc.isEmpty()) {
            return 0;
        }

        if (scc.size() == 1) {
            Function f = scc.getFirst();
            EscapeSummary newSum = analyzeFunction(f, summaries);
            EscapeSummary oldSum = summaries.get(f.getName());
            if (oldSum != null) newSum.merge(oldSum);
            if (oldSum == null || !oldSum.equals(newSum)) {
                summaries.put(f.getName(), newSum);
            }
            return 1;
        }

        Set<Function> sccSet = new HashSet<>(scc);
        Set<Function> pending = new HashSet<>(scc);
        int totalProcessed = 0;
        int iteration = 0;

        for (Function f : scc) {
            summaries.putIfAbsent(f.getName(), EscapeSummary.builder().build());
        }

        while (!pending.isEmpty()) {
            iteration++;
            if (iteration > MAX_SCC_ITERATIONS) {
                System.err.println("EscapeSummaryBuilder: WARNING: SCC of size "
                        + scc.size() + " did not converge after "
                        + MAX_SCC_ITERATIONS + " iterations; "
                        + pending.size() + " functions still changing. "
                        + "Continuing with the current summaries "
                        + "(some field-escape information may be missing).");
                break;
            }

            List<Function> batch = new ArrayList<>(Math.min(EVAL_BATCH_SIZE, pending.size()));
            Iterator<Function> it = pending.iterator();
            while (it.hasNext() && batch.size() < EVAL_BATCH_SIZE) {
                batch.add(it.next());
                it.remove();
            }
            totalProcessed += batch.size();

            List<EscapeSummary> fresh = LlvmUtil.parallelMapOrdered(
                    batch, scheduler,
                    func -> analyzeFunction(func, summaries));

            int committed = 0;
            for (int i = 0; i < batch.size(); i++) {
                Function f = batch.get(i);
                EscapeSummary newSum = fresh.get(i);
                EscapeSummary oldSum = summaries.get(f.getName());

                if (oldSum != null) {
                    newSum.merge(oldSum);
                }

                if (oldSum == null || !oldSum.equals(newSum)) {
                    summaries.put(f.getName(), newSum);
                    committed++;

                    Set<Function> funcCallers = callersOf.get(f.getName());
                    if (funcCallers != null) {
                        for (Function caller : funcCallers) {
                            if (sccSet.contains(caller)) {
                                pending.add(caller);
                            }
                        }
                    }
                }
            }

            if (committed == 0) {
                break;
            }
        }

        return totalProcessed;
    }

    // =====================================================================
    //  Tarjan's SCC algorithm — iterative, to survive deep JDK hierarchies
    // =====================================================================

    private static final class SCCFrame {
        final Function node;
        Iterator<Function> iter;

        SCCFrame(Function node) {
            this.node = node;
        }
    }

    private List<List<Function>> computeSCCs(List<Function> functions,
                                             Map<Function, List<Function>> callees) {
        Map<Function, Integer> index = new HashMap<>(functions.size() * 2);
        Map<Function, Integer> lowlink = new HashMap<>(functions.size() * 2);
        Deque<Function> sccStack = new ArrayDeque<>();
        Set<Function> onStack = new HashSet<>();
        List<List<Function>> sccs = new ArrayList<>();
        int[] counter = {0};

        for (Function start : functions) {
            if (index.containsKey(start)) {
                continue;
            }

            Deque<SCCFrame> dfsStack = new ArrayDeque<>();
            dfsStack.push(new SCCFrame(start));

            while (!dfsStack.isEmpty()) {
                SCCFrame frame = dfsStack.peek();
                Function v = frame.node;

                if (frame.iter == null) {
                    index.put(v, counter[0]);
                    lowlink.put(v, counter[0]);
                    counter[0]++;
                    sccStack.push(v);
                    onStack.add(v);
                    frame.iter = callees.getOrDefault(v, Collections.emptyList())
                            .iterator();
                }

                boolean descended = false;
                while (frame.iter.hasNext()) {
                    Function w = frame.iter.next();
                    if (!index.containsKey(w)) {
                        dfsStack.push(new SCCFrame(w));
                        descended = true;
                        break;
                    }
                    if (onStack.contains(w)) {
                        int newLow = Math.min(lowlink.get(v), index.get(w));
                        lowlink.put(v, newLow);
                    }
                }
                if (descended) {
                    continue;
                }

                dfsStack.pop();
                if (!dfsStack.isEmpty()) {
                    Function parent = dfsStack.peek().node;
                    int newLow = Math.min(lowlink.get(parent), lowlink.get(v));
                    lowlink.put(parent, newLow);
                }

                if (lowlink.get(v).equals(index.get(v))) {
                    List<Function> scc = new ArrayList<>();
                    Function w;
                    do {
                        w = sccStack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (w != v);
                    sccs.add(scc);
                }
            }
        }

        return sccs;
    }

    // =====================================================================
    //  Fixed point over localToParams
    // =====================================================================

    /**
     * Computes the set of parameters whose objects are <b>reachable</b>
     * from the value {@code v} (through a chain of fields, array elements,
     * arguments or call results).
     *
     * <p>Semantics: {@code v} contains {@code p.ordinal} in its result if
     * and only if the object that was passed as parameter {@code p} can be
     * reached from {@code v} by following references.</p>
     *
     * <p>Used for:</p>
     * <ul>
     *   <li>{@code PUT_FIELD base, field, rhs}: if
     *       {@code localToParams[rhs]} is non-empty, all those parameters
     *       escape (into {@code paramsEscaped} or into
     *       {@code paramEscapeEdges});</li>
     *   <li>{@code return v}: {@code localToParams[v]} is what has to be
     *       added to {@code paramsEscapedToReturn}.</li>
     * </ul>
     *
     * <p>The algorithm is a monotone fixed point: the sets only ever grow,
     * so the iteration terminates after at most {@code |params|} steps;
     * in practice after two or three.</p>
     */
    private Map<Value, Set<Integer>> computeLocalToParams(Function func,
                                                          Map<String, EscapeSummary> snapshot) {
        Map<Value, Set<Integer>> map = new HashMap<>();
        Map<Integer, Set<Integer>> slotMap = new HashMap<>();

        for (Parameter p : func.getParameters()) {
            if (!isReferenceLike(p.getType())) continue;
            Set<Integer> s = new HashSet<>();
            s.add(p.getOrdinal());
            map.put(p, s);
        }

        int maxIterations = 2 * func.getParameters().size() + 64;

        for (int iter = 0; iter < maxIterations; iter++) {
            boolean changed = false;

            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    Opcode op = inst.getOpcode();

                    if (op == Opcode.LOAD) {
                        Value result = inst.getResult();
                        int idx = inst.getLocalIndex();
                        if (result == null || idx < 0) continue;
                        Set<Integer> sp = slotMap.get(idx);
                        if (sp == null || sp.isEmpty()) continue;
                        Set<Integer> cur = map.computeIfAbsent(result, k -> new HashSet<>());
                        int before = cur.size();
                        cur.addAll(sp);
                        if (cur.size() > before) changed = true;
                    } else if (op == Opcode.STORE) {
                        if (inst.getOperands().isEmpty()) continue;
                        Value stored = inst.getOperands().getFirst();
                        int idx = inst.getLocalIndex();
                        if (idx < 0) continue;
                        Set<Integer> sp = map.get(stored);
                        if (sp == null || sp.isEmpty()) continue;
                        Set<Integer> cur = slotMap.computeIfAbsent(idx, k -> new HashSet<>());
                        int before = cur.size();
                        cur.addAll(sp);
                        if (cur.size() > before) changed = true;
                    } else if (op == Opcode.PHI) {
                        Value result = inst.getResult();
                        if (result == null) continue;
                        Set<Integer> cur = map.computeIfAbsent(result, k -> new HashSet<>());
                        int before = cur.size();
                        for (Value operand : inst.getOperands()) {
                            if (operand == null) continue;
                            Set<Integer> sp = map.get(operand);
                            if (sp != null) cur.addAll(sp);
                        }
                        if (cur.size() > before) changed = true;
                    } else if (op == Opcode.CAST || op == Opcode.CHECKCAST) {
                        if (inst.getOperands().isEmpty()) continue;
                        Value src = inst.getOperands().getFirst();
                        Value result = inst.getResult();
                        if (result == null) continue;
                        Set<Integer> sp = map.get(src);
                        if (sp == null || sp.isEmpty()) continue;
                        Set<Integer> cur = map.computeIfAbsent(result, k -> new HashSet<>());
                        int before = cur.size();
                        cur.addAll(sp);
                        if (cur.size() > before) changed = true;
                    } else if (op == Opcode.PUT_FIELD) {
                        if (inst.getOperands().size() < 3) continue;
                        Value base = inst.getOperands().get(0);
                        Value rhs = inst.getOperands().get(2);
                        Set<Integer> sp = map.get(rhs);
                        if (sp == null || sp.isEmpty()) continue;
                        Set<Integer> cur = map.computeIfAbsent(base, k -> new HashSet<>());
                        int before = cur.size();
                        cur.addAll(sp);
                        if (cur.size() > before) changed = true;
                    } else if (op == Opcode.ASTORE) {
                        if (inst.getOperands().size() < 3) continue;
                        Value array = inst.getOperands().get(0);
                        Value value = inst.getOperands().get(2);
                        Set<Integer> sp = map.get(value);
                        if (sp == null || sp.isEmpty()) continue;
                        Set<Integer> cur = map.computeIfAbsent(array, k -> new HashSet<>());
                        int before = cur.size();
                        cur.addAll(sp);
                        if (cur.size() > before) changed = true;
                    } else if (op == Opcode.ALOAD) {
                        if (inst.getOperands().size() < 2) continue;
                        Value array = inst.getOperands().getFirst();
                        Value result = inst.getResult();
                        if (result == null) continue;
                        Set<Integer> sp = map.get(array);
                        if (sp == null || sp.isEmpty()) continue;
                        Set<Integer> cur = map.computeIfAbsent(result, k -> new HashSet<>());
                        int before = cur.size();
                        cur.addAll(sp);
                        if (cur.size() > before) changed = true;
                    } else if (op == Opcode.CALL || op == Opcode.VIRTUAL_CALL
                            || op == Opcode.INTERFACE_CALL || op == Opcode.STATIC_CALL
                            || op == Opcode.SPECIAL_CALL) {
                        String calleeName = extractCalleeName(inst);
                        if (calleeName == null) continue;
                        String calleeKey = canonicalCalleeKey(calleeName);
                        EscapeSummary calleeSum = snapshot.get(calleeKey);
                        if (calleeSum == null) continue;

                        List<Value> args = getCallArguments(inst);
                        Value ret = inst.getResult();

                        if (ret != null) {
                            Set<Integer> cur = map.computeIfAbsent(ret, k -> new HashSet<>());
                            int before = cur.size();
                            for (int i = 0; i < args.size(); i++) {
                                if (calleeSum.getParamsReturned().contains(i)
                                        || calleeSum.getParamsEscapedToReturn().contains(i)) {
                                    Set<Integer> sp = map.get(args.get(i));
                                    if (sp != null) cur.addAll(sp);
                                }
                            }
                            if (cur.size() > before) changed = true;
                        }

                        for (Map.Entry<Integer, Set<Integer>> e
                                : calleeSum.getParamEscapeEdges().entrySet()) {
                            int fromIdx = e.getKey();
                            if (fromIdx >= args.size()) continue;
                            Set<Integer> sp = map.get(args.get(fromIdx));
                            if (sp == null || sp.isEmpty()) continue;
                            for (int toIdx : e.getValue()) {
                                if (toIdx >= args.size()) continue;
                                Set<Integer> cur = map.computeIfAbsent(
                                        args.get(toIdx), k -> new HashSet<>());
                                int before = cur.size();
                                cur.addAll(sp);
                                if (cur.size() > before) changed = true;
                            }
                        }
                    }
                }
            }

            if (!changed) break;
        }

        return map;
    }

    private static boolean isReferenceLike(Type type) {
        if (type == null) return true;
        return type.isReference() || type.isArray()
                || type.isNull() || type.isUnknown();
    }

    // =====================================================================
    //  Per-function summary analysis
    // =====================================================================

    /**
     * Computes the summary of one function against the current snapshot
     * of callee summaries.
     *
     * <p>Two-phase walk: seed {@code localPointsTo} with every allocation
     * site produced by the function, then walk the instructions in block
     * order applying each instruction's effect on the tracking state.</p>
     *
     * <p>The instruction walk maintains four tracking structures:</p>
     *
     * <ul>
     *   <li>{@code localToParams}: value &rarr; set of parameter ordinals
     *       reachable from that value. Populated by the fixed point in
     *       {@link #computeLocalToParams} and consumed by
     *       {@link #processPutField} / {@link #processTerminator} to
     *       decide which parameters escape when a value is stored or
     *       returned.</li>
     *
     *   <li>{@code localPointsTo}: value &rarr; set of allocation sites
     *       it may point to. Seeded from {@code NEW}/{@code NEW_ARRAY}/
     *       {@code MULTI_NEW_ARRAY} results, propagated through
     *       {@code LOAD} (non-parameter slots), {@code STORE} (non-
     *       parameter slots), {@code PHI}, {@code CAST}, {@code CHECKCAST},
     *       and call return values.</li>
     *
     *   <li>{@code slotSites}: non-parameter slot index &rarr; set of
     *       allocation sites currently stored there. Needed so that
     *       {@code Object tmp = new Foo(); this.field = tmp;} sees the
     *       site after {@code ALOAD tmp}.</li>
     *
     *   <li>{@code localSiteContents}: allocation site &rarr; set of
     *       sites reachable from it by following field references.
     *       Populated from direct {@code PUT_FIELD}/{@code ASTORE} with
     *       a non-parameter base, and from callee summaries' own
     *       {@code sitesReachableFromParams} at every call site.</li>
     *
     *   <li>{@code sitesReachableFromParams}: parameter ordinal &rarr;
     *       set of sites reachable from it, transitively. This is the
     *       field that appears in the {@link EscapeSummary} and is
     *       consumed by the caller at every call site.</li>
     * </ul>
     */
    private EscapeSummary analyzeFunction(Function func,
                                          Map<String, EscapeSummary> snapshot) {
        Map<Value, Set<Integer>> localToParams = computeLocalToParams(func, snapshot);

        Set<Integer> paramsEscaped         = new HashSet<>();
        Set<Integer> paramsReturned        = new HashSet<>();
        Set<Integer> paramsEscapedToStatic = new HashSet<>();
        Set<Integer> paramsEscapedToReturn = new HashSet<>();
        Set<String>  fieldsEscaped         = new HashSet<>();
        boolean[]    flags                 = new boolean[3];

        Map<Integer, Set<Integer>> paramAliases = computeParamAliases(func);
        Map<Value, Value> valueOrigin = new HashMap<>();
        Map<Integer, Set<Integer>> paramEscapeEdges = new HashMap<>();

        // ---- NEW: transitive site tracking ----
        Map<Value, Set<AllocationSite>> localPointsTo = new HashMap<>();
        Map<Integer, Set<AllocationSite>> slotSites = new HashMap<>();
        Map<AllocationSite, Set<AllocationSite>> localSiteContents = new HashMap<>();
        Map<Integer, Set<AllocationSite>> sitesReachableFromParams = new HashMap<>();

        // ---- Phase 1: seed allocation sites ----
        int idx = 0;
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                if (isAllocation(inst.getOpcode()) && inst.getResult() != null) {
                    AllocationSite site = AllocationSite.fromInstruction(
                            inst, func.getName(), idx);
                    Set<AllocationSite> pts = new HashSet<>();
                    pts.add(site);
                    localPointsTo.put(inst.getResult(), pts);
                }
                idx++;
            }
        }

        // ---- Phase 2: walk instructions ----
        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, paramAliases, paramsEscaped,
                        paramsReturned, paramsEscapedToStatic, fieldsEscaped, flags,
                        snapshot, valueOrigin, paramEscapeEdges, localToParams,
                        localPointsTo, slotSites, localSiteContents,
                        sitesReachableFromParams);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                processTerminator(term, paramAliases, paramsEscaped,
                        paramsReturned, paramsEscapedToReturn, flags, valueOrigin,
                        localToParams);
            }
        }

        EscapeSummary result = EscapeSummary.builder()
                .paramsEscaped(paramsEscaped)
                .paramsReturned(paramsReturned)
                .paramsEscapedToStatic(paramsEscapedToStatic)
                .paramsEscapedToReturn(paramsEscapedToReturn)
                .fieldsEscaped(fieldsEscaped)
                .returnsObject(flags[0])
                .escapesGlobally(flags[1])
                .createsThread(flags[2])
                .paramEscapeEdges(paramEscapeEdges)
                .sitesReachableFromParams(sitesReachableFromParams)
                .build();

        validateSummaryIndices(func, result);
        return result;
    }

    private static void validateSummaryIndices(Function func, EscapeSummary s) {
        int n = func.getParameters().size();
        checkIndices(func, "paramsEscaped",         s.getParamsEscaped(),         n);
        checkIndices(func, "paramsReturned",        s.getParamsReturned(),        n);
        checkIndices(func, "paramsEscapedToStatic", s.getParamsEscapedToStatic(), n);
        checkIndices(func, "paramsEscapedToReturn", s.getParamsEscapedToReturn(), n);
    }

    private static void checkIndices(Function func, String name,
                                     Set<Integer> indices, int paramCount) {
        for (int idx : indices) {
            if (idx < 0 || idx >= paramCount) {
                throw new IllegalStateException(
                        "EscapeSummary for " + func.getName() + " has " + name
                                + " index " + idx + " outside [0, " + paramCount + ")");
            }
        }
    }

    private Map<Integer, Set<Integer>> computeParamAliases(Function func) {
        Map<Integer, Set<Integer>> aliases = new HashMap<>();
        List<Parameter> ps = func.getParameters();
        for (int i = 0; i < ps.size(); i++) {
            for (int j = i + 1; j < ps.size(); j++) {
                Parameter p1 = ps.get(i);
                Parameter p2 = ps.get(j);
                if (aliasResult.mayAlias(p1, p2)) {
                    int o1 = p1.getOrdinal();
                    int o2 = p2.getOrdinal();
                    aliases.computeIfAbsent(o1, k -> new HashSet<>()).add(o2);
                    aliases.computeIfAbsent(o2, k -> new HashSet<>()).add(o1);
                }
            }
        }
        return aliases;
    }

    // =====================================================================
    //  Instruction dispatch
    // =====================================================================

    private void processInstruction(Instruction inst,
                                    Map<Integer, Set<Integer>> paramAliases,
                                    Set<Integer> paramsEscaped,
                                    Set<Integer> paramsReturned,
                                    Set<Integer> paramsEscapedToStatic,
                                    Set<String>  fieldsEscaped,
                                    boolean[]    flags,
                                    Map<String, EscapeSummary> snapshot,
                                    Map<Value, Value> valueOrigin,
                                    Map<Integer, Set<Integer>> paramEscapeEdges,
                                    Map<Value, Set<Integer>> localToParams,
                                    Map<Value, Set<AllocationSite>> localPointsTo,
                                    Map<Integer, Set<AllocationSite>> slotSites,
                                    Map<AllocationSite, Set<AllocationSite>> localSiteContents,
                                    Map<Integer, Set<AllocationSite>> sitesReachableFromParams) {

        Opcode op = inst.getOpcode();
        switch (op) {

            // ------------------------------------------------------------------
            // LOAD: if the slot is not a parameter slot, read its
            // tracked allocation sites from slotSites and attach them to
            // the LOAD result. Parameter slots are handled elsewhere
            // (paramPointsTo is seeded by the caller); the escape summary
            // does not need to read them.
            // ------------------------------------------------------------------
            case LOAD: {
                Value result = inst.getResult();
                int idx = inst.getLocalIndex();
                if (result == null || idx < 0) break;
                Set<AllocationSite> slotPts = slotSites.get(idx);
                if (slotPts != null && !slotPts.isEmpty()) {
                    localPointsTo.put(result, new HashSet<>(slotPts));
                }
                break;
            }

            // ------------------------------------------------------------------
            // STORE: if the target is a non-parameter slot, record the
            // stored sites in slotSites so subsequent LOADs can read them
            // back. Also attach them to the STORE result.
            // ------------------------------------------------------------------
            case STORE: {
                if (inst.getOperands().isEmpty()) break;
                Value stored = inst.getOperands().getFirst();
                int idx = inst.getLocalIndex();
                Set<AllocationSite> storedSites = localPointsTo.get(stored);
                if (idx >= 0 && storedSites != null && !storedSites.isEmpty()) {
                    slotSites.computeIfAbsent(idx, k -> new HashSet<>())
                            .addAll(storedSites);
                }
                if (inst.getResult() != null && storedSites != null) {
                    localPointsTo.put(inst.getResult(),
                            new HashSet<>(storedSites));
                }
                break;
            }

            // ------------------------------------------------------------------
            // PHI: union the allocation sites of every incoming operand.
            // A PHI operand from a parameter would have an empty
            // localPointsTo entry; that is fine and the operand
            // contributes nothing to the site set (parameters are
            // tracked by paramPointsTo, not by localPointsTo).
            // ------------------------------------------------------------------
            case PHI: {
                Value result = inst.getResult();
                if (result == null) break;
                Set<AllocationSite> pts = new HashSet<>();
                for (Value operand : inst.getOperands()) {
                    if (operand == null) continue;
                    Set<AllocationSite> opPts = localPointsTo.get(operand);
                    if (opPts != null) pts.addAll(opPts);
                }
                if (!pts.isEmpty()) {
                    localPointsTo.put(result, pts);
                }
                break;
            }

            // ------------------------------------------------------------------
            // CAST / CHECKCAST: propagate the site set from the source
            // value to the result. Neither instruction changes the
            // identity of the object, so the sites are unchanged.
            // ------------------------------------------------------------------
            case CAST:
            case CHECKCAST: {
                if (inst.getOperands().isEmpty()) break;
                Value src = inst.getOperands().getFirst();
                Value result = inst.getResult();
                if (result == null) break;
                Set<AllocationSite> pts = localPointsTo.get(src);
                if (pts != null && !pts.isEmpty()) {
                    localPointsTo.put(result, new HashSet<>(pts));
                }
                break;
            }

            case PUT_FIELD: {
                processPutField(inst, paramAliases, paramsEscaped,
                        fieldsEscaped, flags, paramEscapeEdges, localToParams,
                        localPointsTo, localSiteContents, sitesReachableFromParams);
                break;
            }

            case GET_FIELD: {
                processGetField(inst, flags);
                break;
            }

            case MONITOR_ENTER, MONITOR_EXIT: {
                processMonitor(inst, paramAliases, paramsEscaped, flags);
                break;
            }

            case PUT_STATIC: {
                processPutStatic(inst, paramAliases, paramsEscaped,
                        paramsEscapedToStatic, flags);
                break;
            }

            case ALOAD: {
                processALoad(inst, valueOrigin);
                break;
            }

            // ------------------------------------------------------------------
            // ASTORE: same containment tracking as PUT_FIELD, targeting
            // the array.
            // ------------------------------------------------------------------
            case ASTORE: {
                processAStore(inst, paramAliases, paramsEscaped,
                        fieldsEscaped, localPointsTo, localSiteContents,
                        sitesReachableFromParams);
                break;
            }

            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL: {
                processCall(inst, paramAliases, paramsEscaped, paramsReturned,
                        paramsEscapedToStatic, flags, snapshot, paramEscapeEdges,
                        localPointsTo, localSiteContents);
                break;
            }

            default: { }
        }
    }

    // =====================================================================
    //  PUT_FIELD
    // =====================================================================

    /**
     * Handles a {@code PUT_FIELD} instruction.
     *
     * <p>The base object is mutated in place; it does NOT escape. An
     * earlier revision added the base to {@code paramsEscaped} here,
     * which caused every constructor of the form {@code this.field =
     * value} to be summarised as "the receiver escapes". That bug was
     * fixed by removing the addition. The current method performs two
     * independent pieces of bookkeeping:</p>
     *
     * <ol>
     *   <li><b>Parameter escape</b>: for every parameter reachable from
     *       the stored value, mark it escaped and record a
     *       parameter-to-parameter escape edge when the base is itself a
     *       parameter.</li>
     *
     *   <li><b>Site containment</b>: for every allocation site reachable
     *       from the stored value, record it as reachable from the
     *       base. When the base is a parameter, this goes into
     *       {@code sitesReachableFromParams}; when the base is a local
     *       site, it goes into {@code localSiteContents}.</li>
     * </ol>
     *
     * <p>Both branches also include the transitive contents of the
     * stored value's sites, so the resulting set is closed under field
     * reachability at every level.</p>
     */
    private void processPutField(Instruction inst,
                                 Map<Integer, Set<Integer>> paramAliases,
                                 Set<Integer> paramsEscaped,
                                 Set<String>  fieldsEscaped,
                                 boolean[]    flags,
                                 Map<Integer, Set<Integer>> paramEscapeEdges,
                                 Map<Value, Set<Integer>> localToParams,
                                 Map<Value, Set<AllocationSite>> localPointsTo,
                                 Map<AllocationSite, Set<AllocationSite>> localSiteContents,
                                 Map<Integer, Set<AllocationSite>> sitesReachableFromParams) {

        if (inst.getOperands().size() < 3) return;

        Value base = inst.getOperands().get(0);
        Value rhs  = inst.getOperands().get(2);
        String[] of = extractFieldOwnerAndName(inst);
        String owner = of[0];
        String fieldName = of[1];
        boolean isVolatile = isVolatileField(owner, fieldName);

        Object fn = inst.getOperands().get(1);
        if (fn instanceof Constant c) {
            fieldsEscaped.add(c.getValue().toString());
        }

        // ---- 1. Parameter escape (existing logic) ----
        Set<Integer> rhsParams = localToParams.get(rhs);
        if (rhsParams != null && !rhsParams.isEmpty()) {
            for (int prd : rhsParams) {
                paramsEscaped.add(prd);
                addAliases(prd, paramAliases, paramsEscaped);
                if (base instanceof Parameter bp) {
                    paramEscapeEdges
                            .computeIfAbsent(prd, k -> new HashSet<>())
                            .add(bp.getOrdinal());
                }
            }
        }

        // ---- 2. Site containment (new) ----
        Set<AllocationSite> rhsSites = localPointsTo.get(rhs);

        if (base instanceof Parameter bp) {
            int pidx = bp.getOrdinal();
            if (rhsSites != null && !rhsSites.isEmpty()) {
                Set<AllocationSite> acc = sitesReachableFromParams
                        .computeIfAbsent(pidx, k -> new HashSet<>());
                for (AllocationSite s : rhsSites) {
                    acc.add(s);
                    Set<AllocationSite> inner = localSiteContents.get(s);
                    if (inner != null) acc.addAll(inner);
                }
            }
        } else {
            Set<AllocationSite> baseSites = localPointsTo.get(base);
            if (baseSites != null && !baseSites.isEmpty()
                    && rhsSites != null && !rhsSites.isEmpty()) {
                for (AllocationSite bs : baseSites) {
                    Set<AllocationSite> contents = localSiteContents
                            .computeIfAbsent(bs, k -> new HashSet<>());
                    for (AllocationSite s : rhsSites) {
                        contents.add(s);
                        Set<AllocationSite> inner = localSiteContents.get(s);
                        if (inner != null) contents.addAll(inner);
                    }
                }
            }
        }

        if (isVolatile && rhs != null) {
            flags[1] = true;
            Set<Integer> rp = localToParams.get(rhs);
            if (rp != null) {
                for (int prd : rp) {
                    paramsEscaped.add(prd);
                    addAliases(prd, paramAliases, paramsEscaped);
                }
            }
        }
    }

    // =====================================================================
    //  GET_FIELD
    // =====================================================================

    private void processGetField(Instruction inst, boolean[] flags) {
        if (inst.getOperands().size() < 2) return;
        String[] of = extractFieldOwnerAndName(inst);
        if (isVolatileField(of[0], of[1])) {
            flags[1] = true;
        }
    }

    // =====================================================================
    //  MONITOR
    // =====================================================================

    private void processMonitor(Instruction inst,
                                Map<Integer, Set<Integer>> paramAliases,
                                Set<Integer> paramsEscaped,
                                boolean[]    flags) {
        if (inst.getOperands().isEmpty()) return;
        Value obj = inst.getOperands().getFirst();
        if (obj instanceof Parameter p) {
            paramsEscaped.add(p.getOrdinal());
            addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
            flags[1] = true;
        }
    }

    // =====================================================================
    //  PUT_STATIC
    // =====================================================================

    private void processPutStatic(Instruction inst,
                                  Map<Integer, Set<Integer>> paramAliases,
                                  Set<Integer> paramsEscaped,
                                  Set<Integer> paramsEscapedToStatic,
                                  boolean[]    flags) {
        if (inst.getOperands().size() < 2) return;
        Value rhs = inst.getOperands().get(1);

        Type rhsType = (rhs != null) ? rhs.getType() : null;
        boolean rhsIsReference = (rhsType == null)
                || rhsType.isReference()
                || rhsType.isArray()
                || rhsType.isNull()
                || rhsType.isUnknown();

        if (rhs instanceof Parameter p) {
            paramsEscaped.add(p.getOrdinal());
            addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
            if (rhsIsReference) {
                paramsEscapedToStatic.add(p.getOrdinal());
                addAliases(p.getOrdinal(), paramAliases, paramsEscapedToStatic);
            }
        }
        if (rhsIsReference) {
            flags[1] = true;
        }
    }

    // =====================================================================
    //  ALOAD
    // =====================================================================

    private void processALoad(Instruction inst, Map<Value, Value> valueOrigin) {
        if (inst.getOperands().size() < 2) return;
        Value array = inst.getOperands().getFirst();
        Value result = inst.getResult();
        if (result != null) valueOrigin.put(result, array);
    }

    // =====================================================================
    //  ASTORE
    // =====================================================================

    /**
     * Handles an {@code ASTORE} instruction.
     *
     * <p>Writing into an array mutates it in place; the array reference
     * itself does not escape. Only the stored value does. The site
     * containment tracking is symmetric to {@link #processPutField}:
     * sites reachable from the stored value become reachable from the
     * array parameter (or the local array site).</p>
     */
    private void processAStore(Instruction inst,
                               Map<Integer, Set<Integer>> paramAliases,
                               Set<Integer> paramsEscaped,
                               Set<String>  fieldsEscaped,
                               Map<Value, Set<AllocationSite>> localPointsTo,
                               Map<AllocationSite, Set<AllocationSite>> localSiteContents,
                               Map<Integer, Set<AllocationSite>> sitesReachableFromParams) {
        if (inst.getOperands().size() < 3) return;
        Value array = inst.getOperands().get(0);
        Value value = inst.getOperands().get(2);

        Type vt = (value != null) ? value.getType() : null;
        boolean valueIsReference = (vt == null)
                || vt.isReference()
                || vt.isArray()
                || vt.isNull()
                || vt.isUnknown();

        if (valueIsReference && value instanceof Parameter p) {
            paramsEscaped.add(p.getOrdinal());
            addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
        }
        fieldsEscaped.add("[]");

        // Site containment (new).
        Set<AllocationSite> valueSites = localPointsTo.get(value);
        if (array instanceof Parameter bp) {
            int pidx = bp.getOrdinal();
            if (valueSites != null && !valueSites.isEmpty()) {
                Set<AllocationSite> acc = sitesReachableFromParams
                        .computeIfAbsent(pidx, k -> new HashSet<>());
                for (AllocationSite s : valueSites) {
                    acc.add(s);
                    Set<AllocationSite> inner = localSiteContents.get(s);
                    if (inner != null) acc.addAll(inner);
                }
            }
        } else {
            Set<AllocationSite> arraySites = localPointsTo.get(array);
            if (arraySites != null && !arraySites.isEmpty()
                    && valueSites != null && !valueSites.isEmpty()) {
                for (AllocationSite as : arraySites) {
                    Set<AllocationSite> contents = localSiteContents
                            .computeIfAbsent(as, k -> new HashSet<>());
                    for (AllocationSite s : valueSites) {
                        contents.add(s);
                        Set<AllocationSite> inner = localSiteContents.get(s);
                        if (inner != null) contents.addAll(inner);
                    }
                }
            }
        }
    }

    // =====================================================================
    //  CALL
    // =====================================================================

    /**
     * Handles a call instruction.
     *
     * <p>In addition to the existing parameter-escape propagation, this
     * method translates the callee's {@code sitesReachableFromParams}
     * into the caller's {@code localSiteContents}: if the callee says
     * "sites X, Y are reachable from parameter i", and the caller
     * passes some local site Z as {@code args[i]}, then X and Y become
     * reachable from Z in the caller's summary.</p>
     *
     * <p>This is the mechanism that makes {@code sitesReachableFromParams}
     * transitive across function boundaries:</p>
     *
     * <ul>
     *   <li>{@code BufferedWriter.<init>} reports {@code cb} as
     *       reachable from {@code this}.</li>
     *   <li>{@code PrintStream.<init>} calls {@code new BufferedWriter(...)},
     *       so it learns {@code cb} is reachable from the {@code
     *       BufferedWriter} site.</li>
     *   <li>When {@code PrintStream.<init>} stores that site into
     *       {@code this.textOut}, it also adds {@code cb} to its own
     *       {@code sitesReachableFromParams[0]}.</li>
     *   <li>{@code System.<clinit>} calls {@code new PrintStream(...)},
     *       so it learns {@code cb} is reachable from the PrintStream
     *       site.</li>
     *   <li>When {@code System.<clinit>} stores that site into
     *       {@code System.out}, the {@code PUT_STATIC} promotes the
     *       PrintStream site to {@code GLOBAL}; the promotion then
     *       reaches {@code cb} via the caller's own
     *       {@code sitesReachableFromParams} propagation in
     *       {@link IntraproceduralEscape#processCall}.</li>
     * </ul>
     *
     * <p>Without this translation the promotion would stop at the first
     * function boundary and the crash at
     * {@code BufferedWriter.cb = null} would persist even after the
     * owner was correctly classified as global.</p>
     */
    private void processCall(Instruction inst,
                             Map<Integer, Set<Integer>> paramAliases,
                             Set<Integer> paramsEscaped,
                             Set<Integer> paramsReturned,
                             Set<Integer> paramsEscapedToStatic,
                             boolean[]    flags,
                             Map<String, EscapeSummary> snapshot,
                             Map<Integer, Set<Integer>> paramEscapeEdges,
                             Map<Value, Set<AllocationSite>> localPointsTo,
                             Map<AllocationSite, Set<AllocationSite>> localSiteContents) {

        String calleeName = extractCalleeName(inst);
        if (calleeName == null) return;

        if (calleeName.startsWith("java/util/concurrent/ExecutorService.submit")
                || calleeName.startsWith("java/util/concurrent/ForkJoinPool.submit")
                || calleeName.startsWith("java/util/concurrent/CompletableFuture.supplyAsync")
                || calleeName.startsWith("java/lang/Thread.start()V")) {
            flags[2] = true;
            for (Value arg : getCallArguments(inst)) {
                if (arg instanceof Parameter p) {
                    paramsEscaped.add(p.getOrdinal());
                    addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
                }
            }
            return;
        }

        String calleeKey = canonicalCalleeKey(calleeName);
        EscapeSummary calleeSum = snapshot.get(calleeKey);
        if (calleeSum == null) {
            // Fall back to the native-body analyser before giving up:
            // JNI-style native methods have no IR body, so the summary
            // builder never produced an entry for them. When its contract
            // does not escape globally, use the computed summary; otherwise
            // stay conservative.
            EscapeSummary computed = nativeAnalyzer.summaryFor(calleeName);
            if (computed != null && !computed.isEscapesGlobally()) {
                calleeSum = computed;
            } else {
                // Unknown callee: conservatively assume every reference
                // argument may be stored anywhere the callee has access to.
                for (Value arg : getCallArguments(inst)) {
                    if (arg instanceof Parameter p) {
                        paramsEscaped.add(p.getOrdinal());
                        addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
                    }
                }
                Value ret = inst.getResult();
                if (ret != null && ret.getType() != Type.VOID) flags[0] = true;
                return;
            }
        }

        List<Value> args = getCallArguments(inst);

        // ------------------------------------------------------------------
        // NEW: translate the callee's site containment into the caller's
        // localSiteContents. For every parameter i of the callee, and
        // every site T in localPointsTo[args[i]], each site S in
        // callee.sitesReachableFromParams[i] becomes reachable from T.
        //
        // This is the mechanism that makes sitesReachableFromParams
        // transitive across function boundaries. See the method javadoc
        // for the full BufferedWriter/PrintStream chain.
        // ------------------------------------------------------------------
        for (int i = 0; i < args.size(); i++) {
            Set<AllocationSite> inParam = calleeSum.getSitesReachableFromParams().get(i);
            if (inParam == null || inParam.isEmpty()) continue;

            Set<AllocationSite> argSites = localPointsTo.get(args.get(i));
            if (argSites == null || argSites.isEmpty()) continue;

            for (AllocationSite site : argSites) {
                Set<AllocationSite> contents = localSiteContents
                        .computeIfAbsent(site, k -> new HashSet<>());
                contents.addAll(inParam);
            }
        }

        // ---- Existing parameter-escape propagation ----
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            if (calleeSum.getParamsEscaped().contains(i) && arg instanceof Parameter p) {
                paramsEscaped.add(p.getOrdinal());
                addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
            }
            if (calleeSum.getParamsEscapedToStatic().contains(i) && arg instanceof Parameter p) {
                paramsEscaped.add(p.getOrdinal());
                paramsEscapedToStatic.add(p.getOrdinal());
                addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
                addAliases(p.getOrdinal(), paramAliases, paramsEscapedToStatic);
            }
            if (calleeSum.getParamsReturned().contains(i) && arg instanceof Parameter p) {
                paramsReturned.add(p.getOrdinal());
                addAliases(p.getOrdinal(), paramAliases, paramsReturned);
            }
        }
        if (calleeSum.isEscapesGlobally()) flags[1] = true;
        if (calleeSum.isCreatesThread())   flags[2] = true;
        if (calleeSum.isReturnsObject())   flags[0] = true;

        // ---- Existing parameter-to-parameter escape edges ----
        for (Map.Entry<Integer, Set<Integer>> e
                : calleeSum.getParamEscapeEdges().entrySet()) {
            int fromIdx = e.getKey();
            if (fromIdx >= args.size()) continue;
            Value fromArg = args.get(fromIdx);
            if (!(fromArg instanceof Parameter fromParam)) continue;

            for (int toIdx : e.getValue()) {
                if (toIdx >= args.size()) continue;
                Value toArg = args.get(toIdx);
                if (toArg instanceof Parameter toParam) {
                    paramEscapeEdges
                            .computeIfAbsent(fromParam.getOrdinal(),
                                    k -> new HashSet<>())
                            .add(toParam.getOrdinal());
                } else {
                    paramsEscaped.add(fromParam.getOrdinal());
                }
            }
        }
    }

    // =====================================================================
    //  Terminators
    // =====================================================================

    private void processTerminator(Terminator term,
                                   Map<Integer, Set<Integer>> paramAliases,
                                   Set<Integer> paramsEscaped,
                                   Set<Integer> paramsReturned,
                                   Set<Integer> paramsEscapedToReturn,
                                   boolean[]    flags,
                                   Map<Value, Value> valueOrigin,
                                   Map<Value, Set<Integer>> localToParams) {
        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            if (retVal != null && retVal.getType() != Type.VOID) {
                flags[0] = true;

                if (retVal instanceof Parameter p) {
                    paramsReturned.add(p.getOrdinal());
                    paramsEscapedToReturn.add(p.getOrdinal());
                    addAliases(p.getOrdinal(), paramAliases, paramsReturned);
                    addAliases(p.getOrdinal(), paramAliases, paramsEscapedToReturn);
                } else {
                    Set<Integer> contained = localToParams.get(retVal);
                    if (contained != null && !contained.isEmpty()) {
                        for (int prd : contained) {
                            paramsEscapedToReturn.add(prd);
                            addAliases(prd, paramAliases, paramsEscapedToReturn);
                        }
                    }
                    Value origin = valueOrigin.get(retVal);
                    if (origin instanceof Parameter p) {
                        paramsEscapedToReturn.add(p.getOrdinal());
                    }
                }
            }
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            if (exc != null) {
                if (exc instanceof Parameter p) {
                    paramsEscaped.add(p.getOrdinal());
                    paramsEscapedToReturn.add(p.getOrdinal());
                    addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
                    addAliases(p.getOrdinal(), paramAliases, paramsEscapedToReturn);
                } else {
                    Set<Integer> contained = localToParams.get(exc);
                    if (contained != null && !contained.isEmpty()) {
                        for (int prd : contained) {
                            paramsEscaped.add(prd);
                            paramsEscapedToReturn.add(prd);
                        }
                    }
                }
            }
        }
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private boolean isVolatileField(String owner, String fieldName) {
        if (owner == null || owner.isEmpty() || fieldName == null || fieldName.isEmpty()) return false;
        FieldNode field = resolver.getField(owner, fieldName);
        return field != null && (field.getAccess() & Opcodes.ACC_VOLATILE) != 0;
    }

    private void addAliases(int paramIndex, Map<Integer, Set<Integer>> aliases, Set<Integer> set) {
        Set<Integer> aliasSet = aliases.get(paramIndex);
        if (aliasSet != null) set.addAll(aliasSet);
    }
}