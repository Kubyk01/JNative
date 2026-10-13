package io.github.kubyk01.application.service.analyzer.escapeanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
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
import static io.github.kubyk01.util.LlvmUtil.isLazyClinitWrapper;

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

    /**
     * Computes the escape summary of every function in the module.
     *
     * <h2>Why SCCs instead of a single global worklist</h2>
     *
     * <p>The previous revision used one global worklist over every
     * function in the module. Whenever a function's summary changed,
     * <em>every</em> caller of that function was re-enqueued,
     * regardless of whether the caller belonged to the same
     * dependency cycle. On a full JDK reachability closure this makes
     * the analysis converge at the speed of the slowest chain in the
     * whole graph: the worklist never empties because each iteration
     * re-enqueues a ten-thousand-strong frontier of functions whose
     * summaries are individually changing by a tiny amount, and the
     * convergence is bounded only by the product of the number of
     * functions and the size of their summaries. Thirty-three million
     * function evaluations with ten thousand still queued was the
     * observed behaviour.</p>
     *
     * <p>The fix is the standard one for a monotone interprocedural
     * analysis: decompose the call graph into its strongly connected
     * components, process those components in reverse topological
     * order, and perform the fixed point <em>inside each component
     * separately</em>. Because the component graph is a DAG, every
     * callee living outside the current SCC has already been
     * processed to its own fixed point and will never change again
     * while the current SCC is being solved. Only callers that belong
     * to the <em>same</em> SCC need to be re-enqueued when a summary
     * changes, and those are the only ones the intra-SCC loop
     * enqueues.</p>
     *
     * <p>The order of the SCC list produced by {@link #computeSCCs} is
     * the reverse topological order of the condensation DAG — sink
     * SCCs (callees with no outgoing edges to unprocessed nodes) come
     * first, and each SCC is emitted only after every SCC reachable
     * from it has already been emitted. That is exactly the bottom-up
     * order a caller→callee propagation requires.</p>
     */
    public Map<String, EscapeSummary> build() {
        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }
        if (live.isEmpty()) return summaries;

        // ---- Call graph --------------------------------------------------
        //
        // byName    : mangled function name -> Function, for callee lookup
        // callees   : Function -> distinct callee Functions that live in
        //             the module (external callees are handled
        //             conservatively by analyzeFunction and do not
        //             participate in propagation)
        // callersOf : mangled callee name -> set of caller Functions, used
        //             to re-enqueue intra-SCC dependents when a summary
        //             changes
        //
        // The callee lists are pre-deduplicated so that a function with a
        // hundred calls to the same target does not inflate the SCC walk.
        //
        // Lazy-<clinit> wrappers are excluded from this graph entirely:
        // their caller edges are skipped so that a wrapper's summary change
        // does not cascade into its tens of thousands of trigger sites.
        // See LlvmUtil.isLazyClinitWrapper.
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

                    // Lazy-<clinit> wrapper: see the comment on
                    // LlvmUtil.isLazyClinitWrapper.
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

        // ---- SCCs in reverse topological order --------------------------
        List<List<Function>> sccs = computeSCCs(live, callees);

        // ---- Process SCCs bottom-up ------------------------------------
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

    /**
     * Runs the local fixed point for one SCC. All callees outside the SCC
     * already have their final summaries in {@link #summaries} (guaranteed
     * by the Tarjan ordering), so the only functions that can invalidate a
     * summary here are the SCC's own members, and only they are re-enqueued.
     *
     * <h2>Why this loop is monotone</h2>
     *
     * <p>Two properties are required for the fixpoint to converge in
     * O(lattice height) iterations rather than oscillate:</p>
     *
     * <ol>
     *   <li><b>Pre-fill with ⊥.</b> Every SCC member receives an empty
     *       summary in {@link #summaries} before the fixed point starts.
     *       Without this, the first iteration sees
     *       {@code snapshot.get(calleeName) == null} for every intra-SCC
     *       call, and {@code processCall} takes its conservative
     *       "all-reference-args-escape" branch, producing a near-⊤ result.
     *       On the second iteration the callee already has a concrete
     *       (strictly smaller) summary, the caller shrinks, its callers
     *       shrink, the SCC re-enqueues itself, and the value climbs back
     *       up. The SCC then oscillates forever between the two bounds and
     *       hits {@link #MAX_SCC_ITERATIONS}.</li>
     *
     *   <li><b>Monotone merge on commit.</b> Old and new summaries are
     *       merged rather than replaced, so the stored value never
     *       decreases across iterations even when the freshly computed
     *       value is strictly smaller than the previous one. This is the
     *       same commit discipline {@code SummaryBuilder} uses, and it is
     *       what makes the SCC loop terminate.</li>
     * </ol>
     *
     * <p>Together these two properties reduce the observed oscillation of
     * the largest SCC (size 1290) from 2000 capped iterations to the same
     * O(dozens) that {@code SummaryBuilder} already exhibits on the same
     * graph.</p>
     *
     * @return the number of {@link #analyzeFunction} invocations performed.
     */
    private int processSCC(List<Function> scc, Map<String, Set<Function>> callersOf) {
        if (scc.isEmpty()) {
            return 0;
        }

        // Fast path: a singleton with no self-edge can be committed on
        // the first pass. Every one of the ~24000 functions in a typical
        // JDK module falls into this case. The merge below is not strictly
        // necessary when there is no self-loop (the freshly computed value
        // is already the final one), but performing it keeps the commit
        // discipline identical across the two paths.
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


        // ---- Pre-fill every SCC member with ⊥ (the empty summary). ----
        //
        // Without this, the first iteration sees the summaries of intra-SCC
        // callees as absent and processCall takes its conservative branch,
        // producing a near-⊤ value that the next iteration then shrinks —
        // the SCC oscillates between the two bounds and hits the cap.
        for (Function f : scc) {
            summaries.putIfAbsent(f.getName(), EscapeSummary.builder().build());
        }

        while (!pending.isEmpty()) {
            iteration++;
            if (iteration > MAX_SCC_ITERATIONS) {
                // Emit to stderr AND stdout: the build redirects only stdout,
                // so a capped (non-converged) run must also be visible in
                // res.txt rather than looking like a clean "converged".
                System.err.println("EscapeSummaryBuilder: WARNING: SCC of size "
                        + scc.size() + " did not converge after "
                        + MAX_SCC_ITERATIONS + " iterations; "
                        + pending.size() + " functions still changing. "
                        + "Continuing with the current summaries "
                        + "(some field-escape information may be missing).");
                break;
            }

            List<Function> batch = new ArrayList<>(pending);
            pending.clear();
            totalProcessed += batch.size();

            // The `summaries` map is a ConcurrentHashMap, and it is only
            // written on the main thread after this parallel phase
            // completes, so during the phase itself the map is
            // effectively read-only. Using it directly — rather than a
            // per-batch copy — gives exactly the same consistency
            // (every worker of one batch sees the same snapshot) with no
            // O(|summaries|) copy per batch.
            List<EscapeSummary> fresh = LlvmUtil.parallelMapOrdered(
                    batch, scheduler,
                    func -> analyzeFunction(func, summaries));
            // parallelMapOrdered already validates the result size.

            // Sequential commit: the only place summaries are written.
            // Old and new summaries are merged rather than replaced, so the
            // stored value never decreases across iterations even when the
            // freshly computed value is strictly smaller than the previous
            // one. Re-enqueue only callers that belong to this SCC; callers
            // in other SCCs will observe the final summary when their own SCC
            // is processed later, so re-enqueuing them here would be wasted
            // work and could not terminate until every SCC had been visited.
            int committed = 0;
            for (int i = 0; i < batch.size(); i++) {
                Function f = batch.get(i);
                EscapeSummary newSum = fresh.get(i);
                EscapeSummary oldSum = summaries.get(f.getName());

                // newSum is a fresh object built by analyzeFunction
                // specifically for this commit. It is not shared between
                // threads and is safe to mutate in place.
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

            // No summary changed in this pass: the SCC is at its fixed
            // point.
            if (committed == 0) {
                break;
            }
        }

        return totalProcessed;
    }

    // =====================================================================
    //  Tarjan's SCC algorithm — iterative, to survive deep JDK hierarchies
    // =====================================================================

    /**
     * One level of the explicit DFS stack used in place of the call
     * stack. The iterator field holds the position within the current
     * node's successor list so that a resumed frame picks up exactly
     * where it left off.
     */
    private static final class SCCFrame {
        final Function node;
        Iterator<Function> iter;

        SCCFrame(Function node) {
            this.node = node;
        }
    }

    /**
     * Tarjan's strongly-connected-components algorithm in its iterative
     * form. The recursion is emulated with an explicit {@link Deque} of
     * {@link SCCFrame}s so that call graphs deeper than the JVM's default
     * call stack do not overflow — a full JDK dependency graph routinely
     * exceeds 10&nbsp;000 frames of depth.
     *
     * <p>The output order is the reverse topological order of the
     * condensation DAG: sink SCCs (no outgoing edges to unvisited nodes)
     * are emitted first, and each SCC is emitted only after every SCC
     * reachable from it has already been emitted. With caller→callee
     * edges this is the order the fixed point needs.</p>
     */
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
                    // First visit: assign the pre-order index and lowlink,
                    // push onto the SCC stack, and start walking successors.
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

                // All successors visited: finish v. Propagate its lowlink
                // up to the parent frame, then check whether v is the root
                // of a new SCC.
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
    //  Per-function summary analysis (semantics unchanged)
    // =====================================================================

    private EscapeSummary analyzeFunction(Function func,
                                          Map<String, EscapeSummary> snapshot) {
        Set<Integer> paramsEscaped  = new HashSet<>();
        Set<Integer> paramsReturned = new HashSet<>();
        Set<String>  fieldsEscaped  = new HashSet<>();
        boolean[]    flags          = new boolean[3];   // [0]=returnsObject, [1]=escapesGlobally, [2]=createsThread

        Map<Integer, Set<Integer>> paramAliases = computeParamAliases(func);
        Map<Value, Value> valueOrigin = new HashMap<>();
        Map<Integer, Set<Integer>> paramEscapeEdges = new HashMap<>();

        for (BasicBlock block : func.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                processInstruction(inst, paramAliases, paramsEscaped,
                        paramsReturned, fieldsEscaped, flags, snapshot, valueOrigin,
                        paramEscapeEdges);
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                processTerminator(term, paramAliases, paramsEscaped,
                        paramsReturned, flags, valueOrigin);
            }
        }

        EscapeSummary result = EscapeSummary.builder()
                .paramsEscaped(paramsEscaped)
                .paramsReturned(paramsReturned)
                .fieldsEscaped(fieldsEscaped)
                .returnsObject(flags[0])
                .escapesGlobally(flags[1])
                .createsThread(flags[2])
                .paramEscapeEdges(paramEscapeEdges)
                .build();

        validateSummaryIndices(func, result);
        return result;
    }

    /**
     * Catches any error where a summary refers to a parameter index outside
     * the declared parameter list. This is exactly the error that occurs when
     * slot and ordinal are confused: a function with one parameter
     * (Object.toString) cannot legally have paramsEscaped = {1,2,3}, but a
     * slot-based summary produced by another function can.
     */
    private static void validateSummaryIndices(Function func, EscapeSummary s) {
        int n = func.getParameters().size();
        checkIndices(func, "paramsEscaped",  s.getParamsEscaped(),  n);
        checkIndices(func, "paramsReturned", s.getParamsReturned(), n);
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

    private void processInstruction(Instruction inst,
                                    Map<Integer, Set<Integer>> paramAliases,
                                    Set<Integer> paramsEscaped,
                                    Set<Integer> paramsReturned,
                                    Set<String>  fieldsEscaped,
                                    boolean[]    flags,
                                    Map<String, EscapeSummary> snapshot,
                                    Map<Value, Value> valueOrigin,
                                    Map<Integer, Set<Integer>> paramEscapeEdges) {
        Opcode op = inst.getOpcode();
        switch (op) {
            case PUT_FIELD -> processPutField(inst, paramAliases, paramsEscaped, fieldsEscaped, flags,
                    paramEscapeEdges);
            case GET_FIELD -> processGetField(inst, flags);
            case MONITOR_ENTER, MONITOR_EXIT -> processMonitor(inst, paramAliases, paramsEscaped, flags);
            case PUT_STATIC -> processPutStatic(inst, paramAliases, paramsEscaped, flags);
            case ALOAD -> processALoad(inst, valueOrigin);
            case ASTORE -> processAStore(inst, paramAliases, paramsEscaped, fieldsEscaped);
            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL ->
                    processCall(inst, paramAliases, paramsEscaped, paramsReturned,
                            flags, snapshot, paramEscapeEdges);
            default -> { }
        }
    }

    private void processPutField(Instruction inst,
                                 Map<Integer, Set<Integer>> paramAliases,
                                 Set<Integer> paramsEscaped,
                                 Set<String>  fieldsEscaped,
                                 boolean[]    flags,
                                 Map<Integer, Set<Integer>> paramEscapeEdges) {
        if (inst.getOperands().size() < 3) return;

        Value rhs  = inst.getOperands().get(2);
        String[] of = extractFieldOwnerAndName(inst);
        String owner = of[0];
        String fieldName = of[1];
        boolean isVolatile = isVolatileField(owner, fieldName);

        // ------------------------------------------------------------------
        // The base object is mutated in place. It does NOT escape.
        //
        // An earlier revision added the base parameter to paramsEscaped
        // here, which caused every constructor of the form
        //     this.field = value;
        // to be summarised as "the receiver escapes". The caller
        // consequently upgraded every freshly-allocated receiver to HEAP,
        // making all constructor-based allocations un-stack-allocatable.
        // See Test2.<init>(I)V in the trace: alloc@...:19(Test2) -> HEAP
        // was produced solely by this bug.
        // ------------------------------------------------------------------

        Object fn = inst.getOperands().get(1);
        if (fn instanceof Constant c) {
            fieldsEscaped.add(c.getValue().toString());
        }

        // --- FIX: type-dependent check ---
        // A primitive value stored into a field stays a primitive and
        // cannot make any allocation site reachable from another object
        // or another thread.
        Type rhsType = (rhs != null) ? rhs.getType() : null;
        boolean rhsIsReference = (rhsType == null)
                || rhsType.isReference()
                || rhsType.isArray()
                || rhsType.isNull()
                || rhsType.isUnknown();

        // The stored value becomes reachable from the base object. Since
        // the summary is by construction an over-approximation of what
        // this function may do to its parameters, and since the summary
        // builder does not perform per-function local escape analysis,
        // any reference parameter written into any field is reported as
        // escaping. (The IntraproceduralEscape pass handles the more
        // precise case where the base is a local whose escape status is
        // known within the function.)
        if (rhsIsReference && rhs instanceof Parameter pr) {
            paramsEscaped.add(pr.getOrdinal());
            addAliases(pr.getOrdinal(), paramAliases, paramsEscaped);

            // Record the param-to-param edge when the base of the
            // write is itself a parameter. This is the only way the
            // caller can learn that the stored value's escape status
            // is at least as high as the base's. See the javadoc on
            // EscapeSummary.paramEscapeEdges for the full rationale.
            if (inst.getOperands().getFirst() instanceof Parameter bp) {
                paramEscapeEdges
                    .computeIfAbsent(pr.getOrdinal(), k -> new HashSet<>())
                    .add(bp.getOrdinal());
            }
        }

        if (isVolatile && rhsIsReference) {
            flags[1] = true;
            if (rhs instanceof Parameter pr) {
                paramsEscaped.add(pr.getOrdinal());
                addAliases(pr.getOrdinal(), paramAliases, paramsEscaped);
            }
        }
    }

    private void processGetField(Instruction inst, boolean[] flags) {
        if (inst.getOperands().size() < 2) return;
        String[] of = extractFieldOwnerAndName(inst);
        if (isVolatileField(of[0], of[1])) {
            // A volatile read exposes the reader to values produced by
            // other threads. The base itself does not escape; only the
            // global-visibility flag is set.
            flags[1] = true;
        }
    }

    private void processMonitor(Instruction inst,
                                Map<Integer, Set<Integer>> paramAliases,
                                Set<Integer> paramsEscaped,
                                boolean[]    flags) {
        if (inst.getOperands().isEmpty()) return;
        Value obj = inst.getOperands().getFirst();
        // Synchronising on an object exposes it to any other thread that
        // synchronises on the same object.
        if (obj instanceof Parameter p) {
            paramsEscaped.add(p.getOrdinal());
            addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
            flags[1] = true;
        }
    }

    private void processPutStatic(Instruction inst,
                                  Map<Integer, Set<Integer>> paramAliases,
                                  Set<Integer> paramsEscaped,
                                  boolean[]    flags) {
        if (inst.getOperands().size() < 2) return;
        Value rhs = inst.getOperands().get(1);

        // --- FIX: type-dependent check ---
        // Only storing a reference value into a static field can make an
        // object globally reachable. A primitive store (including the
        // synthetic `static final boolean $assertionsDisabled` that javac
        // generates for assert) makes no object globally reachable and
        // must not taint the caller's summary with escapesGlobally.
        //
        // Before this fix, one boolean store anywhere in the transitive
        // closure of Object.toString() flipped the whole chain to
        // escapesGlobally: IntegerCache.<clinit> -> Integer.valueOf ->
        // ... -> Object.toString. IntraproceduralEscape then promoted the
        // receiver `new Test2(...)` to GLOBAL.
        Type rhsType = (rhs != null) ? rhs.getType() : null;
        boolean rhsIsReference = (rhsType == null)
                || rhsType.isReference()
                || rhsType.isArray()
                || rhsType.isNull()
                || rhsType.isUnknown();

        if (rhs instanceof Parameter p) {
            paramsEscaped.add(p.getOrdinal());
            addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
        }
        if (rhsIsReference) {
            flags[1] = true;
        }
    }

    private void processALoad(Instruction inst, Map<Value, Value> valueOrigin) {
        if (inst.getOperands().size() < 2) return;
        Value array = inst.getOperands().getFirst();
        Value result = inst.getResult();
        if (result != null) valueOrigin.put(result, array);
    }

    private void processAStore(Instruction inst,
                               Map<Integer, Set<Integer>> paramAliases,
                               Set<Integer> paramsEscaped,
                               Set<String>  fieldsEscaped) {
        if (inst.getOperands().size() < 3) return;
        Value value = inst.getOperands().get(2);

        Type vt = (value != null) ? value.getType() : null;
        boolean valueIsReference = (vt == null)
                || vt.isReference()
                || vt.isArray()
                || vt.isNull()
                || vt.isUnknown();

        // Writing into an array mutates it in place; the array reference
        // itself does not escape. Only the stored value does.
        if (valueIsReference && value instanceof Parameter p) {
            paramsEscaped.add(p.getOrdinal());
            addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
        }
        fieldsEscaped.add("[]");
    }

    private void processCall(Instruction inst,
                             Map<Integer, Set<Integer>> paramAliases,
                             Set<Integer> paramsEscaped,
                             Set<Integer> paramsReturned,
                             boolean[]    flags,
                             Map<String, EscapeSummary> snapshot,
                             Map<Integer, Set<Integer>> paramEscapeEdges) {
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
        for (int i = 0; i < args.size(); i++) {
            Value arg = args.get(i);
            if (calleeSum.getParamsEscaped().contains(i) && arg instanceof Parameter p) {
                paramsEscaped.add(p.getOrdinal());
                addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
            }
            if (calleeSum.getParamsReturned().contains(i) && arg instanceof Parameter p) {
                paramsReturned.add(p.getOrdinal());
                addAliases(p.getOrdinal(), paramAliases, paramsReturned);
            }
        }
        if (calleeSum.isEscapesGlobally()) flags[1] = true;
        if (calleeSum.isCreatesThread())   flags[2] = true;
        if (calleeSum.isReturnsObject())   flags[0] = true;

        // Propagate parameter-to-parameter escape edges from the callee.
        //
        // For each callee edge `i -> j`, the callee's parameter i escapes
        // into the callee's parameter j. At the call site that means the
        // caller's arg[i] escapes into the caller's arg[j]. When both
        // args are parameters of the current function, record the same
        // edge on the current summary. When only arg[i] is a parameter
        // (arg[j] is a local/temporary), the edge cannot be expressed in
        // the summary; fall back to marking arg[i] as escaped to heap,
        // which is the conservative answer.
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

    private void processTerminator(Terminator term,
                                   Map<Integer, Set<Integer>> paramAliases,
                                   Set<Integer> paramsEscaped,
                                   Set<Integer> paramsReturned,
                                   boolean[]    flags,
                                   Map<Value, Value> valueOrigin) {
        if (term instanceof ReturnTerminator rt) {
            Value retVal = rt.getValue();
            if (retVal != null && retVal.getType() != Type.VOID) {
                flags[0] = true;
                if (retVal instanceof Parameter p) {
                    paramsEscaped.add(p.getOrdinal());
                    paramsReturned.add(p.getOrdinal());
                    addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
                    addAliases(p.getOrdinal(), paramAliases, paramsReturned);
                } else {
                    Value origin = valueOrigin.get(retVal);
                    if (origin instanceof Parameter p) {
                        paramsReturned.add(p.getOrdinal());
                        addAliases(p.getOrdinal(), paramAliases, paramsReturned);
                    }
                }
            }
        } else if (term instanceof ThrowTerminator tt) {
            Value exc = tt.getException();
            if (exc != null) {
                if (exc instanceof Parameter p) {
                    paramsEscaped.add(p.getOrdinal());
                    paramsReturned.add(p.getOrdinal());
                    addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
                    addAliases(p.getOrdinal(), paramAliases, paramsReturned);
                } else {
                    Value origin = valueOrigin.get(exc);
                    if (origin instanceof Parameter p) {
                        paramsEscaped.add(p.getOrdinal());
                        paramsReturned.add(p.getOrdinal());
                        addAliases(p.getOrdinal(), paramAliases, paramsEscaped);
                        addAliases(p.getOrdinal(), paramAliases, paramsReturned);
                    }
                }
            }
        }
    }

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