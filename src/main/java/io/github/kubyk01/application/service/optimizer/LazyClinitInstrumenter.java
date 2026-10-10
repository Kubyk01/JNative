package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.BranchTerminator;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Instruments cyclic {@code <clinit>} groups for lazy initialization.
 *
 * <p>Class-level initialization of a compiled image is normally performed
 * eagerly from {@code @main} in a flat, topologically-sorted order. That
 * flat order exists if and only if the dependency graph — superclass
 * before subclass, static-field writer before reader, trigger before user
 * — is acyclic. When the trigger graph contains a non-trivial strongly
 * connected component, no flat order can satisfy all three invariants
 * simultaneously, and the runtime would observe a {@code null} static
 * field during {@code <clinit>}.</p>
 *
 * <p>For exactly those classes (and their superclass/subclass closure),
 * this pass switches the runtime to lazy initialization:</p>
 * <ul>
 *   <li>the real {@code <clinit>} body stays in the module under its
 *       original mangled name, but is removed from the eager schedule;</li>
 *   <li>a per-class guard wrapper
 *       {@code fn___lazy_clinit_run_<C>} (see
 *       {@link #WRAPPER_NAME_PREFIX}) is synthesized. The wrapper first
 *       runs the wrapper of the nearest ancestor that declares a
 *       {@code <clinit>} (JVMS 5.5 step 5), then consults the runtime
 *       state machine {@code __jnative_clinit_enter}/{@code
 *       __jnative_clinit_exit}, and only if that returns "you are the
 *       initializing thread" does it call the real body;</li>
 *   <li>every active-use site of such a class (JLS §12.4.1:
 *       {@code new}, {@code getstatic}/{@code putstatic} on a field of
 *       the class, and {@code invokestatic} into a non-native method
 *       declared by the class) is rewritten to call the wrapper first.</li>
 * </ul>
 *
 * <h2>Interaction with the LLVM backend's inliner</h2>
 *
 * <p>The wrapper is a real, standalone {@link Function} in the module.
 * Inserting a {@code call} to it at the active-use site is not enough on
 * its own: the LLVM backend will happily inline the wrapper back into
 * every call site, and the wrapper body itself calls
 * {@code __jnative_clinit_enter}/{@code __jnative_clinit_exit}, whose
 * bodies contain {@code pthread_mutex_lock} and a linear
 * {@code strcmp} walk over the runtime's {@code clinit_table}. Without
 * a barrier the backend inlines the mutex + strcmp chain into every
 * active-use site, producing tens of thousands of copies of the same
 * guarded region per function and turning a diagnosable control-flow
 * defect into a heavyweight hot spot.</p>
 *
 * <p>The barrier is applied in two places, both mandatory:</p>
 * <ol>
 *   <li>{@code __jnative_clinit_enter} and {@code __jnative_clinit_exit}
 *       are declared {@code __attribute__((noinline))} in
 *       {@code jnative_runtime.c}. This is what keeps the mutex and the
 *       strcmp walk out of the per-call-site IR;</li>
 *   <li>{@link io.github.kubyk01.application.service.codegen.llvm.LlvmFunctionEmitter#emitFunction}
 *       must emit the wrapper as {@code define noinline ...} whenever
 *       the function name starts with {@link #WRAPPER_NAME_PREFIX}. The
 *       prefix is a stable part of this class's public contract; the
 *       emitter's check is a plain {@code startsWith} on the mangled
 *       name.</li>
 * </ol>
 *
 * <p>Together these two changes reduce the active-use instrumentation to
 * a compact {@code call fn___lazy_clinit_run_<C>} sequence, so the
 * guarded-region structure of the surrounding function remains visible
 * in the emitted IR instead of being buried under the state-machine's
 * body.</p>
 *
 * <h2>Parallelism</h2>
 *
 * <p>The active-use instrumentation pass is the single largest loop in
 * the optimizer: it walks every function of the module and rewrites
 * every active-use site. On a full JDK reachability closure that is tens
 * of thousands of call sites.</p>
 *
 * <p>The work is distributed through Reactor on the scheduler the
 * {@link io.github.kubyk01.application.service.Orchestrator} created and
 * owns, so it never lands on {@code ForkJoinPool.commonPool()} and the
 * {@code common.parallelism} system property is never consulted. Every
 * function is processed by exactly one worker for the duration of its
 * pass; the {@link BasicBlock} instruction lists of a single function
 * are never touched by two threads at once. The only shared state
 * written during the parallel phase is the {@link AtomicInteger} that
 * accumulates the insert count, which is only ever updated once per
 * function after its local count is known.</p>
 */
public class LazyClinitInstrumenter {

    /**
     * Name prefix of every lazy-{@code <clinit>} guard wrapper emitted by
     * this pass. The full wrapper name is
     * {@code fn___lazy_clinit_run_<sanitised-internal-class-name>}.
     *
     * <p>The LLVM function emitter must emit any function whose mangled
     * name begins with this prefix with the {@code noinline} attribute
     * in its definition. See the class-level javadoc for the full
     * rationale.</p>
     */
    public static final String WRAPPER_NAME_PREFIX = "fn___lazy_clinit_run_";

    private final Module module;
    private final DependencyResolver resolver;
    private final Set<String> lazyClasses;
    private final Scheduler scheduler;

    /** internal class name → wrapper function name */
    private final Map<String, String> classToWrapper = new HashMap<>();

    /** names of every wrapper this pass has created */
    private final Set<String> wrapperNames = new HashSet<>();

    /**
     * Mangled {@code <clinit>} function name → internal class name.
     *
     * <p>Populated in {@link #createWrappers()} for every lazy class
     * whose real {@code <clinit>} exists in the module. Used by
     * {@link #instrumentBlock(BasicBlock, String)} to skip self-triggers:
     * the {@code <clinit>} body of class C does not need to trigger the
     * lazy initialization of C, because C is being initialized right
     * now.</p>
     */
    private final Map<String, String> clinitNameToClass = new HashMap<>();

    /**
     * Convenience constructor: runs on {@link Schedulers#single()}.
     * Kept for tests and for any caller that does not care about
     * parallelism; the two-argument form was the previous public API
     * and remains valid.
     */
    public LazyClinitInstrumenter(Module module,
                                  DependencyResolver resolver,
                                  Set<String> lazyClasses) {
        this(module, resolver, lazyClasses, Schedulers.single());
    }

    /**
     * Full constructor. The {@code scheduler} is borrowed, not owned:
     * the orchestrator disposes it after the analysis pipeline is
     * complete. Passing {@code null} collapses to
     * {@link Schedulers#single()}.
     */
    public LazyClinitInstrumenter(Module module,
                                  DependencyResolver resolver,
                                  Set<String> lazyClasses,
                                  Scheduler scheduler) {
        this.module = module;
        this.resolver = resolver;
        this.lazyClasses = lazyClasses;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
    }

    /**
     * Runs the three-pass instrumentation.
     *
     * @return number of trigger calls inserted into the module.
     */
    public int instrument() {
        createWrappers();
        fillWrapperBodies();

        List<Function> snapshot = new ArrayList<>(module.getFunctions());

        AtomicInteger inserted = new AtomicInteger(0);

        LlvmUtil.awaitMono(Flux.fromIterable(snapshot)
            .filter(f -> f.getEntryBlock() != null)
            .filter(f -> !wrapperNames.contains(f.getName()))
            .parallel()
            .runOn(scheduler)
            .doOnNext(func -> {
                String ownerClass = clinitNameToClass.get(func.getName());
                int local = 0;
                for (BasicBlock block : func.getBlocks()) {
                    local += instrumentBlock(block, ownerClass);
                }
                if (local > 0) {
                    inserted.addAndGet(local);
                }
            })
            .sequential()
            .then()
        );

        return inserted.get();
    }

    // ------------------------------------------------------------------
    // Pass 1 — create empty wrapper shells
    //
    // Sequential by design: it writes to classToWrapper, wrapperNames
    // and clinitNameToClass, all of which are read by the later
    // passes. The pass must complete and publish its writes before the
    // parallel instrument() phase begins, which the ordering in
    // instrument() guarantees.
    // ------------------------------------------------------------------

    private void createWrappers() {
        for (String className : lazyClasses) {
            String clinitName = LlvmRuntime.mangleMethod(className, "<clinit>", "()V");
            Function realClinit = module.getFunction(clinitName);
            if (realClinit == null || realClinit.getEntryBlock() == null) {
                continue;   // no body to guard; nothing to do
            }

            // Record the clinit → class mapping so instrumentBlock can
            // skip self-triggers. This must be filled for every lazy
            // class whose real <clinit> is present, even if the wrapper
            // already exists (idempotent re-run).
            clinitNameToClass.put(clinitName, className);

            String wrapperName = wrapperNameFor(className);
            if (module.getFunction(wrapperName) != null) {
                continue;
            }

            Function wrapper = new Function(wrapperName, Type.VOID);
            wrapper.setOwnerClass(className);
            module.addFunction(wrapper);
            classToWrapper.put(className, wrapperName);
            wrapperNames.add(wrapperName);
        }
    }

    // ------------------------------------------------------------------
    // Pass 2 — fill in wrapper bodies
    //
    // Sequential by design: each wrapper's body depends on the
    // superclass wrapper being already resolvable through
    // classToWrapper, and each wrapper is a distinct Function that is
    // being mutated. The pass is O(number of lazy classes), which on
    // the observed profile is ~500 entries, not the bottleneck.
    // ------------------------------------------------------------------

    private void fillWrapperBodies() {
        for (Map.Entry<String, String> e : classToWrapper.entrySet()) {
            String className   = e.getKey();
            String wrapperName = e.getValue();
            Function wrapper   = module.getFunction(wrapperName);
            if (wrapper == null || wrapper.getEntryBlock() != null) continue;

            String clinitName   = LlvmRuntime.mangleMethod(className, "<clinit>", "()V");
            String superWrapper = findNearestSuperclassWrapper(className);

            buildWrapperBody(wrapper, className, clinitName, superWrapper);
        }
    }

    /**
     * Finds the wrapper of the nearest ancestor of {@code className} that
     * declares a {@code <clinit>}. The propagation step in
     * {@link #propagateLazy} guarantees that any such ancestor is also in
     * {@link #lazyClasses} and therefore has a wrapper.
     */
    private String findNearestSuperclassWrapper(String className) {
        ClassNode cn = resolver.getClassNode(className);
        if (cn == null) return null;

        ClassNode cur = cn;
        while (cur != null) {
            String sup = cur.getSuperName();
            if (sup == null || sup.equals(cur.getName()) || "java/lang/Object".equals(sup)) {
                break;
            }
            String wrapper = classToWrapper.get(sup);
            if (wrapper != null) return wrapper;
            cur = resolver.getClassNode(sup);
        }
        return null;
    }

    /**
     * Emits the body of the guard wrapper:
     *
     * <pre>
     *   entry:
     *     [ call superclass wrapper ]        ; JVMS 5.5 step 5
     *     %r = call i32 @__jnative_clinit_enter(i8* &lt;name&gt;)
     *     %c = icmp eq i32 %r, 0
     *     br i1 %c, label %do_init, label %exit
     *
     *   do_init:
     *     call void @&lt;real_clinit&gt;()        ; the actual body
     *     call void @__jnative_clinit_exit(i8* &lt;name&gt;)
     *     br label %exit
     *
     *   exit:
     *     ret void
     * </pre>
     *
     * <p>The wrapper's mangled name begins with
     * {@link #WRAPPER_NAME_PREFIX}, and {@code LlvmFunctionEmitter} is
     * required to emit the wrapper's definition with the {@code noinline}
     * attribute so that the state-machine calls inside it are not
     * duplicated into every active-use site. See the class-level
     * javadoc.</p>
     */
    private void buildWrapperBody(Function wrapper,
                                  String className,
                                  String realClinitName,
                                  String superWrapperName) {
        String wrapperName = wrapper.getName();

        BasicBlock entry  = new BasicBlock(wrapperName + "_entry");
        BasicBlock doInit = new BasicBlock(wrapperName + "_do_init");
        BasicBlock exit   = new BasicBlock(wrapperName + "_exit");
        wrapper.addBlock(entry);
        wrapper.addBlock(doInit);
        wrapper.addBlock(exit);
        wrapper.setEntryBlock(entry);

        /* JVMS 5.5 step 5: superclass initialization precedes this class's. */
        if (superWrapperName != null) {
            Instruction superCall = new Instruction(Opcode.STATIC_CALL);
            superCall.addOperand(new Constant(
                Type.reference(superWrapperName), superWrapperName));
            entry.addInstruction(superCall);
        }

        /* Shared String constant carrying the internal class name. */
        Constant nameConst = new Constant(Type.reference("java/lang/String"), className);

        /* %r = __jnative_clinit_enter(name) */
        Instruction enterCall = new Instruction(Opcode.STATIC_CALL);
        enterCall.addOperand(new Constant(
            Type.reference("__jnative_clinit_enter"), "__jnative_clinit_enter"));
        enterCall.addOperand(nameConst);
        Temporary rTmp = new Temporary(Type.INT);
        enterCall.setResult(rTmp);
        rTmp.setDefiningInstruction(enterCall);
        entry.addInstruction(enterCall);

        /* %c = (r == 0) — "this thread must run the body". */
        Instruction cmp = new Instruction(Opcode.EQ);
        cmp.addOperand(rTmp);
        cmp.addOperand(new Constant(Type.INT, 0));
        Temporary condTmp = new Temporary(Type.BOOLEAN);
        cmp.setResult(condTmp);
        condTmp.setDefiningInstruction(cmp);
        entry.addInstruction(cmp);

        entry.setTerminator(new CondBranchTerminator(condTmp, doInit, exit));

        /* do_init: real body, then publish completion. */
        Instruction realCall = new Instruction(Opcode.STATIC_CALL);
        realCall.addOperand(new Constant(
            Type.reference(realClinitName), realClinitName));
        doInit.addInstruction(realCall);

        Instruction exitCall = new Instruction(Opcode.STATIC_CALL);
        exitCall.addOperand(new Constant(
            Type.reference("__jnative_clinit_exit"), "__jnative_clinit_exit"));
        exitCall.addOperand(nameConst);
        doInit.addInstruction(exitCall);

        doInit.setTerminator(new BranchTerminator(exit));

        /* exit: return. */
        exit.setTerminator(new ReturnTerminator(null));
    }

    // ------------------------------------------------------------------
    // Pass 3 — active-use instrumentation
    //
    // The hot loop. Called in parallel across functions; each call is
    // guaranteed to be invoked from exactly one worker thread and to be
    // the only thing that ever mutates the block during its execution.
    //
    // ownerClass is the internal name of the class whose <clinit> this
    // block belongs to, or null when the block belongs to a non-clinit
    // function. When non-null, self-triggers are skipped: a class's own
    // <clinit> body must not call the lazy wrapper for itself.
    // ------------------------------------------------------------------

    private int instrumentBlock(BasicBlock block, String ownerClass) {
        List<Instruction> insts = block.getInstructions();
        if (insts.isEmpty()) return 0;

        List<Instruction> rewritten = new ArrayList<>(insts.size() + 4);
        int added = 0;

        for (Instruction inst : insts) {
            String triggered = triggeredClassFor(inst);
            if (triggered != null && !triggered.equals(ownerClass)) {
                String wrapper = classToWrapper.get(triggered);
                if (wrapper != null) {
                    Instruction call = new Instruction(Opcode.STATIC_CALL);
                    call.addOperand(new Constant(Type.reference(wrapper), wrapper));
                    call.setParent(block);
                    rewritten.add(call);
                    added++;
                }
            }
            rewritten.add(inst);
        }

        if (added > 0) {
            insts.clear();
            insts.addAll(rewritten);
        }
        return added;
    }

    /**
     * Returns the internal name of the class whose initialization is
     * triggered by {@code inst}, or {@code null} when the instruction is
     * not an active use (JLS §12.4.1 / JVMS 5.5).
     *
     * <p>An {@code invokestatic} targeting a {@code native} method does
     * NOT trigger initialization: the body lives in C and does not read
     * the class's Java-side static state. This mirrors the reachability
     * analysis rule in {@code Analyzer.collectClassInitTriggers}.</p>
     */
    private String triggeredClassFor(Instruction inst) {
        Opcode op = inst.getOpcode();

        if (op == Opcode.NEW) {
            if (!inst.getOperands().isEmpty()) {
                Value v = inst.getOperands().getFirst();
                if (v instanceof Constant c && c.getType().isReference()) {
                    Object val = c.getValue();
                    if (val instanceof String s) return s;
                }
            }
            return null;
        }

        if (op == Opcode.GET_STATIC || op == Opcode.PUT_STATIC) {
            if (!inst.getOperands().isEmpty()) {
                Value v = inst.getOperands().getFirst();
                if (v instanceof Constant c && c.getType().isReference()) {
                    Object val = c.getValue();
                    if (val instanceof String s) {
                        int dot = s.lastIndexOf('.');
                        return dot > 0 ? s.substring(0, dot) : s;
                    }
                }
            }
            return null;
        }

        if (op == Opcode.STATIC_CALL) {
            if (inst.getOperands().isEmpty()) return null;
            Value v = inst.getOperands().getFirst();
            if (!(v instanceof Constant c) || !c.getType().isReference()) return null;

            Object val = c.getValue();
            if (!(val instanceof String callee)) return null;

            int dotIdx   = callee.lastIndexOf('.');
            int parenIdx = callee.indexOf('(');
            if (dotIdx <= 0 || parenIdx <= dotIdx) return null;

            String owner        = callee.substring(0, dotIdx);
            String methodPart   = callee.substring(dotIdx + 1);
            int    localParen   = parenIdx - dotIdx - 1;
            String methodName   = methodPart.substring(0, localParen);
            String descriptor   = methodPart.substring(localParen);

            String[] foundOwner = new String[1];
            MethodNode mn = resolver.findMethodInHierarchy(
                owner, methodName, descriptor, foundOwner);
            if (mn != null && mn.isNative()) return null;
            return foundOwner[0] != null ? foundOwner[0] : owner;
        }

        return null;
    }

    // ------------------------------------------------------------------
    // Naming
    // ------------------------------------------------------------------

    private static String wrapperNameFor(String className) {
        return WRAPPER_NAME_PREFIX + sanitize(className);
    }

    private static String sanitize(String s) {
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
    // Static helpers used by Analyzer
    // ------------------------------------------------------------------

    /**
     * Tarjan's strongly connected components — iterative form to avoid
     * stack overflow on deep JDK hierarchy graphs.
     *
     * <p>The iterative implementation uses an explicit frame stack to
     * emulate the recursion, so a graph whose depth exceeds the JVM's
     * default call stack (which happens routinely on a full JDK
     * dependency graph) does not overflow. Tarjan's SCC algorithm is
     * inherently sequential: each node's finishing time depends on the
     * finishing times of its successors, so the pass cannot be
     * parallelized without changing the algorithm. It runs once per
     * analysis and is not the bottleneck.</p>
     *
     * @param graph node → successors
     * @return every SCC, including the trivial singletons
     */
    public static List<Set<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
        Map<String, Integer> index   = new HashMap<>();
        Map<String, Integer> lowlink = new HashMap<>();
        Deque<String>        sccStack = new ArrayDeque<>();
        Set<String>          onStack  = new HashSet<>();
        List<Set<String>>    sccs     = new ArrayList<>();
        int[] counter = {0};

        final class Frame {
            final String node;
            Iterator<String> iter;
            Frame(String node) { this.node = node; }
        }

        for (String start : graph.keySet()) {
            if (index.containsKey(start)) continue;

            Deque<Frame> dfs = new ArrayDeque<>();
            dfs.push(new Frame(start));

            while (!dfs.isEmpty()) {
                Frame frame = dfs.peek();
                String v = frame.node;

                if (frame.iter == null) {
                    index.put(v, counter[0]);
                    lowlink.put(v, counter[0]);
                    counter[0]++;
                    sccStack.push(v);
                    onStack.add(v);
                    frame.iter = graph.getOrDefault(v, Collections.emptySet()).iterator();
                }

                if (frame.iter.hasNext()) {
                    String w = frame.iter.next();
                    if (!index.containsKey(w)) {
                        dfs.push(new Frame(w));
                    } else if (onStack.contains(w)) {
                        lowlink.put(v, Math.min(lowlink.get(v), index.get(w)));
                    }
                    continue;
                }

                dfs.pop();
                if (!dfs.isEmpty()) {
                    String parent = dfs.peek().node;
                    lowlink.put(parent,
                        Math.min(lowlink.get(parent), lowlink.get(v)));
                }

                if (lowlink.get(v).equals(index.get(v))) {
                    Set<String> scc = new LinkedHashSet<>();
                    String w;
                    do {
                        w = sccStack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (!w.equals(v));
                    sccs.add(scc);
                }
            }
        }
        return sccs;
    }

    /**
     * From a set of classes that participate in a cycle, close the set
     * under both directions of the superclass relation.
     *
     * <p>Upward closure: if {@code C} is lazy, its nearest ancestor that
     * declares a {@code <clinit>} must be lazy as well. Otherwise that
     * ancestor could run eagerly, and a lazy trigger of {@code C} issued
     * from inside an eager {@code <clinit>} that precedes the ancestor
     * could observe the ancestor as not yet initialized — violating
     * JVMS 5.5 step 5.</p>
     *
     * <p>Downward closure: if {@code SC} is lazy, every transitive
     * subclass of {@code SC} that declares its own {@code <clinit>} must
     * be lazy as well. Otherwise a subclass that ran eagerly could
     * observe {@code SC} as not yet initialized.</p>
     */
    public static Set<String> propagateLazy(Set<String> cyclicClasses,
                                            Set<String> allWithClinit,
                                            DependencyResolver resolver) {
        Set<String> lazy = new HashSet<>(cyclicClasses);
        Deque<String> worklist = new ArrayDeque<>(cyclicClasses);

        while (!worklist.isEmpty()) {
            String c = worklist.poll();

            /* Upward: nearest ancestor that declares a <clinit>. */
            ClassNode cn = resolver.getClassNode(c);
            if (cn != null) {
                ClassNode cur = cn;
                while (cur != null) {
                    String sup = cur.getSuperName();
                    if (sup == null
                        || sup.equals(cur.getName())
                        || "java/lang/Object".equals(sup)) {
                        break;
                    }
                    if (allWithClinit.contains(sup)) {
                        if (lazy.add(sup)) worklist.add(sup);
                        break;
                    }
                    cur = resolver.getClassNode(sup);
                }
            }

            /* Downward: every transitive subclass that declares a <clinit>.
             * resolver.getSubclasses already walks interfaces transitively,
             * which over-approximates the relation but is safe. */
            for (String sub : resolver.getSubclasses(c)) {
                if (allWithClinit.contains(sub) && lazy.add(sub)) {
                    worklist.add(sub);
                }
            }
        }
        return lazy;
    }
}