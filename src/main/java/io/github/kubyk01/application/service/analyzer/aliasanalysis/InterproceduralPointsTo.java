package io.github.kubyk01.application.service.analyzer.aliasanalysis;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FieldCell;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.aliasanalysis.SiteSetBuilder;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.isAllocation;
import static io.github.kubyk01.util.LlvmUtil.isLazyClinitWrapper;

@Slf4j
public class InterproceduralPointsTo {

    private static final long MAX_EVALUATIONS = 50_000_000L;
    private static final long PROGRESS_LOG_INTERVAL = 65_536L;

    private static final byte K_LOAD = 1;
    private static final byte K_STORE = 2;
    private static final byte K_PHI = 3;
    private static final byte K_GET_FIELD = 4;
    private static final byte K_PUT_FIELD = 5;
    private static final byte K_GET_STATIC = 6;
    private static final byte K_PUT_STATIC = 7;
    private static final byte K_CALL = 8;

    private static final byte F_NULL_SUMMARY_DONE = 1;
    private static final byte F_RETURNED_DONE = 2;
    private static final byte F_STATIC_WRITES_DONE = 4;
    private static final byte F_UNKNOWN_FALLBACK = 8;

    private static final int IDLE = 0;
    private static final int QUEUED = 1;
    private static final int RUNNING = 2;
    private static final int DIRTY = 3;

    private static final Parameter[] NO_PARAMS = new Parameter[0];
    private static final FieldCell[] NO_CELLS = new FieldCell[0];
    private static final int[] NO_INTS = new int[0];

    private final Module module;
    private final Map<String, FunctionSummary> summaries;
    private final Scheduler scheduler;
    private final int parallelism;
    private final PointsToGraph graph = new PointsToGraph();
    private final PointsToSet unknownSet = AllocationSite.UNKNOWN.singleton();
    private final int unknownId = AllocationSite.UNKNOWN.getId();

    private final ConcurrentHashMap<String, CallInfo> callInfos = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<FnState> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong enqueues = new AtomicLong();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong nextProgress = new AtomicLong(PROGRESS_LOG_INTERVAL);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean aborted;

    private Map<Function, FnState> stateOf;
    private FnState[] states;

    public InterproceduralPointsTo(Module module,
                                   Map<String, FunctionSummary> summaries,
                                   Scheduler scheduler) {
        this(module, summaries, scheduler, Runtime.getRuntime().availableProcessors());
    }

    public InterproceduralPointsTo(Module module,
                                   Map<String, FunctionSummary> summaries,
                                   Scheduler scheduler,
                                   int parallelism) {
        this.module = module;
        this.summaries = summaries;
        this.scheduler = (scheduler != null) ? scheduler : Schedulers.single();
        this.parallelism = (scheduler != null) ? Math.max(1, parallelism) : 1;
    }

    private static final class CallInfo {
        final FunctionSummary summary;
        final Parameter[] params;
        final FnState callee;
        final int[] paramsReturned;
        final PointsToSet returned;
        final FieldCell[] staticWrites;
        final boolean returnsObject;

        CallInfo(FunctionSummary summary, Parameter[] params, FnState callee, int[] paramsReturned,
                 PointsToSet returned, FieldCell[] staticWrites, boolean returnsObject) {
            this.summary = summary;
            this.params = params;
            this.callee = callee;
            this.paramsReturned = paramsReturned;
            this.returned = returned;
            this.staticWrites = staticWrites;
            this.returnsObject = returnsObject;
        }

        boolean returnsParam(int index) {
            return Arrays.binarySearch(paramsReturned, index) >= 0;
        }
    }

    private static final class FnState {
        final Function fn;
        final int id;
        final AtomicInteger state = new AtomicInteger(IDLE);
        Parameter[] params;
        int[] paramSlots;
        PointsToSet[] slots;
        Instruction[] ins;
        byte[] kind;
        int[] aux;
        int[] loadOrdinal;
        Object[] info;
        int[] inOff;
        byte[] flags;
        PointsToSet[] last;
        long[] loadEpoch;
        boolean iterate;

        FnState(Function fn, int id) {
            this.fn = fn;
            this.id = id;
        }
    }

    private static final class IntSeq {
        int[] a = new int[16];
        int n;

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n << 1);
            a[n++] = v;
        }

        int[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    public PointsToGraph analyze() {
        collectAllocationSites();

        List<Function> live = new ArrayList<>();
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() != null) live.add(func);
        }
        states = new FnState[live.size()];
        stateOf = new IdentityHashMap<>(live.size() * 2);
        for (int i = 0; i < states.length; i++) {
            states[i] = new FnState(live.get(i), i);
            stateOf.put(live.get(i), states[i]);
        }

        LlvmUtil.awaitMono(Flux.fromArray(states)
            .parallel(parallelism)
            .runOn(scheduler)
            .doOnNext(this::compile)
            .sequential()
            .then());

        for (FnState f : states) enqueue(f);

        int rounds = 0;
        while (true) {
            runWorkers();
            Throwable t = failure.get();
            if (t != null) throw new RuntimeException("points-to analysis failed", t);
            if (aborted) break;
            long fired = fireDeferredRules();
            rounds++;
            if (fired == 0) break;
        }

        if (aborted) {
            System.err.println("PointsTo: WARNING: exceeded " + MAX_EVALUATIONS
                + " evaluations; " + pending.get() + " functions still pending. "
                + "Stopping (results may be imprecise).");
        }
        System.out.println("PointsTo: converged after " + evaluations.get()
            + " function evaluations, " + enqueues.get() + " enqueues, "
            + rounds + " round(s), " + AllocationSite.count() + " allocation sites");

        graph.releaseSolverState();
        states = null;
        stateOf = null;
        callInfos.clear();
        queue.clear();
        return graph;
    }

    private void collectAllocationSites() {
        for (Function func : module.getFunctions()) {
            if (func.getEntryBlock() == null) continue;
            int idx = 0;
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (isAllocation(inst.getOpcode())) {
                        AllocationSite site = AllocationSite.fromInstruction(
                            inst, func.getName(), idx);
                        graph.putAllocationSite(site, inst.getResult());
                        if (inst.getResult() != null) graph.add(inst.getResult(), site);
                    }
                    idx++;
                }
            }
        }
    }

    private CallInfo callInfo(String calleeKey) {
        CallInfo ci = callInfos.get(calleeKey);
        if (ci != null) return ci;
        return callInfos.computeIfAbsent(calleeKey, this::createCallInfo);
    }

    private CallInfo createCallInfo(String calleeKey) {
        FunctionSummary summary = summaries.get(calleeKey);
        if (summary == null) {
            return new CallInfo(null, NO_PARAMS, null, NO_INTS, PointsToSet.EMPTY, NO_CELLS, false);
        }
        Function calleeFunc = module.getFunction(calleeKey);
        Parameter[] params = NO_PARAMS;
        FnState callee = null;
        if (calleeFunc != null && calleeFunc.getName().equals(calleeKey)) {
            params = calleeFunc.getParameters().toArray(NO_PARAMS);
            callee = stateOf.get(calleeFunc);
        }
        int[] returnedParams = summary.getParamsReturned().stream()
            .mapToInt(Integer::intValue).filter(i -> i >= 0).sorted().distinct().toArray();
        PointsToSet returned = PointsToSet.copyOf(summary.getReturnedAllocations());
        FieldCell[] staticWrites = summary.getStaticFieldWrites().isEmpty()
            ? NO_CELLS
            : summary.getStaticFieldWrites().stream().sorted().map(graph::staticCell).toArray(FieldCell[]::new);
        return new CallInfo(summary, params, callee, returnedParams, returned, staticWrites, summary.isReturnsObject());
    }

    private static boolean isVirtualLike(Opcode op) {
        return op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL || op == Opcode.SPECIAL_CALL;
    }

    private static int argCount(Instruction inst, boolean virtualLike) {
        int n = inst.getOperands().size();
        if (virtualLike) return n == 0 ? 0 : (n >= 2 ? n - 1 : 1);
        return Math.max(0, n - 1);
    }

    private static Value arg(Instruction inst, int index, boolean virtualLike) {
        List<Value> ops = inst.getOperands();
        if (virtualLike) return index == 0 ? ops.getFirst() : ops.get(index + 1);
        return ops.get(index + 1);
    }

    private void compile(FnState f) {
        Function fn = f.fn;
        List<Parameter> ps = fn.getParameters();
        f.params = ps.toArray(NO_PARAMS);
        f.paramSlots = new int[f.params.length];
        int maxSlot = -1;
        for (int i = 0; i < f.params.length; i++) {
            f.paramSlots[i] = f.params[i].getIndex();
            maxSlot = Math.max(maxSlot, f.paramSlots[i]);
        }

        List<Instruction> rel = new ArrayList<>();
        IntSeq kinds = new IntSeq();
        IntSeq auxs = new IntSeq();
        IntSeq inputs = new IntSeq();
        List<Object> infos = new ArrayList<>();
        int loads = 0;

        for (BasicBlock block : fn.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Opcode op = inst.getOpcode();
                List<Value> ops = inst.getOperands();
                Value res = inst.getResult();
                switch (op) {
                    case LOAD -> {
                        int slot = inst.getLocalIndex();
                        if (res == null || slot < 0) continue;
                        maxSlot = Math.max(maxSlot, slot);
                        rel.add(inst); kinds.add(K_LOAD); auxs.add(slot); inputs.add(1); infos.add(null);
                    }
                    case STORE -> {
                        if (ops.isEmpty() || ops.getFirst() == null) continue;
                        int slot = inst.getLocalIndex();
                        maxSlot = Math.max(maxSlot, slot);
                        rel.add(inst); kinds.add(K_STORE); auxs.add(slot); inputs.add(1); infos.add(null);
                    }
                    case PHI -> {
                        if (res == null) continue;
                        rel.add(inst); kinds.add(K_PHI); auxs.add(0); inputs.add(ops.size()); infos.add(null);
                    }
                    case GET_FIELD, ALOAD -> {
                        if (ops.size() < 2 || res == null) continue;
                        String field = op == Opcode.ALOAD ? PointsToGraph.ARRAY_ELEMENT_FIELD : extractFieldName(inst);
                        rel.add(inst); kinds.add(K_GET_FIELD); auxs.add(graph.fieldId(field)); inputs.add(1); infos.add(field);
                        loads++;
                    }
                    case PUT_FIELD, ASTORE -> {
                        if (ops.size() < 3) continue;
                        String field = op == Opcode.ASTORE ? PointsToGraph.ARRAY_ELEMENT_FIELD : extractFieldName(inst);
                        rel.add(inst); kinds.add(K_PUT_FIELD); auxs.add(graph.fieldId(field)); inputs.add(2); infos.add(field);
                    }
                    case GET_STATIC -> {
                        if (res == null) continue;
                        FieldCell cell = graph.staticCell(extractFieldName(inst));
                        cell.addReader(f.id, states.length);
                        rel.add(inst); kinds.add(K_GET_STATIC); auxs.add(0); inputs.add(1); infos.add(cell);
                    }
                    case PUT_STATIC -> {
                        if (ops.size() < 2) continue;
                        FieldCell cell = graph.staticCell(extractFieldName(inst));
                        rel.add(inst); kinds.add(K_PUT_STATIC); auxs.add(0); inputs.add(1); infos.add(cell);
                    }
                    case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                        String calleeName = extractCalleeName(inst);
                        if (calleeName == null) continue;
                        String calleeKey = canonicalCalleeKey(calleeName);
                        if (isLazyClinitWrapper(calleeKey)) continue;
                        CallInfo ci = callInfo(calleeKey);
                        if (ci.summary == null && res == null) continue;
                        boolean virtualLike = isVirtualLike(op);
                        int argc = ci.summary == null ? 0 : argCount(inst, virtualLike);
                        rel.add(inst); kinds.add(K_CALL); auxs.add(virtualLike ? 1 : 0); inputs.add(argc); infos.add(ci);
                    }
                    default -> { }
                }
            }
        }

        int n = rel.size();
        f.ins = rel.toArray(new Instruction[0]);
        f.kind = new byte[n];
        int[] kindInts = kinds.toArray();
        for (int i = 0; i < n; i++) f.kind[i] = (byte) kindInts[i];
        f.aux = auxs.toArray();
        f.info = infos.toArray();
        f.flags = new byte[n];
        f.inOff = new int[n];
        f.loadOrdinal = new int[n];
        int[] inCounts = inputs.toArray();
        int total = 0;
        int loadIndex = 0;
        for (int i = 0; i < n; i++) {
            f.inOff[i] = total;
            total += inCounts[i];
            if (f.kind[i] == K_GET_FIELD) f.loadOrdinal[i] = loadIndex++;
        }
        f.last = new PointsToSet[total];
        f.loadEpoch = new long[loads];
        f.slots = new PointsToSet[maxSlot + 1];
        Arrays.fill(f.slots, PointsToSet.EMPTY);
        f.iterate = needsIteration(f, maxSlot);
    }

    private boolean needsIteration(FnState f, int maxSlot) {
        int n = f.ins.length;
        IdentityHashMap<Value, Integer> defPos = new IdentityHashMap<>();
        int[] lastStore = new int[maxSlot + 1];
        Arrays.fill(lastStore, -1);
        for (int i = 0; i < n; i++) {
            Instruction inst = f.ins[i];
            byte k = f.kind[i];
            if (k != K_PUT_FIELD && k != K_PUT_STATIC && inst.getResult() != null) defPos.put(inst.getResult(), i);
            if (k == K_STORE && f.aux[i] >= 0) lastStore[f.aux[i]] = i;
        }
        for (int i = 0; i < n; i++) {
            Instruction inst = f.ins[i];
            List<Value> ops = inst.getOperands();
            switch (f.kind[i]) {
                case K_LOAD -> {
                    if (lastStore[f.aux[i]] >= i) return true;
                }
                case K_STORE, K_GET_FIELD -> {
                    if (definedAtOrAfter(defPos, ops.getFirst(), i)) return true;
                }
                case K_PHI -> {
                    for (Value v : ops) if (definedAtOrAfter(defPos, v, i)) return true;
                }
                case K_PUT_FIELD -> {
                    if (definedAtOrAfter(defPos, ops.get(0), i) || definedAtOrAfter(defPos, ops.get(2), i)) return true;
                }
                case K_PUT_STATIC -> {
                    if (definedAtOrAfter(defPos, ops.get(1), i)) return true;
                }
                case K_CALL -> {
                    CallInfo ci = (CallInfo) f.info[i];
                    if (ci.summary == null) continue;
                    boolean vl = f.aux[i] == 1;
                    int argc = argCount(inst, vl);
                    for (int j = 0; j < argc; j++) {
                        if (definedAtOrAfter(defPos, arg(inst, j, vl), i)) return true;
                    }
                }
                default -> { }
            }
        }
        return false;
    }

    private static boolean definedAtOrAfter(IdentityHashMap<Value, Integer> defPos, Value v, int pos) {
        if (v == null) return false;
        Integer p = defPos.get(v);
        return p != null && p >= pos;
    }

    private void enqueue(FnState f) {
        while (true) {
            int s = f.state.get();
            if (s == IDLE) {
                if (f.state.compareAndSet(IDLE, QUEUED)) {
                    pending.incrementAndGet();
                    enqueues.incrementAndGet();
                    queue.add(f);
                    return;
                }
            } else if (s == RUNNING) {
                if (f.state.compareAndSet(RUNNING, DIRTY)) return;
            } else {
                return;
            }
        }
    }

    private void enqueueById(int id) {
        enqueue(states[id]);
    }

    private void runWorkers() {
        if (pending.get() == 0) return;
        LlvmUtil.awaitMono(Flux.range(0, parallelism)
            .parallel(parallelism)
            .runOn(scheduler, 1)
            .doOnNext(i -> workerLoop())
            .sequential()
            .then());
    }

    private void workerLoop() {
        int idle = 0;
        while (!aborted) {
            FnState f = queue.poll();
            if (f == null) {
                if (pending.get() == 0) return;
                if (idle < 64) Thread.onSpinWait();
                else if (idle < 128) Thread.yield();
                else LockSupport.parkNanos(200_000L);
                idle++;
                continue;
            }
            idle = 0;
            process(f);
        }
    }

    private void process(FnState f) {
        f.state.set(RUNNING);
        try {
            while (true) {
                evaluate(f);
                long n = evaluations.incrementAndGet();
                reportProgress(n);
                if (n >= MAX_EVALUATIONS) aborted = true;
                if (aborted) {
                    f.state.set(IDLE);
                    break;
                }
                if (f.state.compareAndSet(RUNNING, IDLE)) break;
                f.state.set(RUNNING);
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
            aborted = true;
            f.state.set(IDLE);
        } finally {
            pending.decrementAndGet();
        }
    }

    private void reportProgress(long n) {
        long target = nextProgress.get();
        if (n >= target && nextProgress.compareAndSet(target, target + PROGRESS_LOG_INTERVAL)) {
            System.out.println("PointsTo: " + n + " evaluations, " + enqueues.get()
                + " enqueues, " + pending.get() + " pending");
        }
    }

    private void evaluate(FnState f) {
        while (runPass(f) && f.iterate) {
            if (aborted) return;
        }
    }

    private boolean runPass(FnState f) {
        boolean local = false;
        PointsToSet[] slots = f.slots;
        for (int p = 0; p < f.params.length; p++) {
            int slot = f.paramSlots[p];
            if (slot < 0) continue;
            PointsToSet pp = graph.get(f.params[p]);
            if (pp.isEmpty()) continue;
            PointsToSet cur = slots[slot];
            PointsToSet u = PointsToSet.union(cur, pp);
            if (u != cur) {
                slots[slot] = u;
                local = true;
            }
        }

        Instruction[] ins = f.ins;
        PointsToSet[] last = f.last;
        for (int i = 0; i < ins.length; i++) {
            Instruction inst = ins[i];
            int o = f.inOff[i];
            switch (f.kind[i]) {
                case K_LOAD -> {
                    PointsToSet src = slots[f.aux[i]];
                    if (src == last[o]) continue;
                    last[o] = src;
                    if (!src.isEmpty() && graph.merge(inst.getResult(), src)) local = true;
                }
                case K_STORE -> {
                    PointsToSet sp = graph.get(inst.getOperands().getFirst());
                    if (sp == last[o]) continue;
                    last[o] = sp;
                    if (sp.isEmpty()) continue;
                    Value res = inst.getResult();
                    if (res != null && graph.merge(res, sp)) local = true;
                    int slot = f.aux[i];
                    if (slot >= 0) {
                        PointsToSet cur = slots[slot];
                        PointsToSet u = PointsToSet.union(cur, sp);
                        if (u != cur) {
                            slots[slot] = u;
                            local = true;
                        }
                    }
                }
                case K_PHI -> {
                    if (evalPhi(inst, last, o)) local = true;
                }
                case K_GET_FIELD -> {
                    if (evalGetField(f, i, inst, last, o)) local = true;
                }
                case K_PUT_FIELD -> evalPutField(f, i, inst, last, o);
                case K_GET_STATIC -> {
                    PointsToSet s = ((FieldCell) f.info[i]).get();
                    if (s == last[o]) continue;
                    last[o] = s;
                    if (!s.isEmpty() && graph.merge(inst.getResult(), s)) local = true;
                }
                case K_PUT_STATIC -> {
                    PointsToSet rhs = graph.get(inst.getOperands().get(1));
                    if (rhs == last[o]) continue;
                    last[o] = rhs;
                    FieldCell cell = (FieldCell) f.info[i];
                    if (cell.merge(rhs)) cell.forEachReader(this::enqueueById);
                }
                case K_CALL -> {
                    if (evalCall(f, i, inst, last, o)) local = true;
                }
                default -> { }
            }
        }
        return local;
    }

    private boolean evalPhi(Instruction inst, PointsToSet[] last, int o) {
        List<Value> ops = inst.getOperands();
        Value res = inst.getResult();
        PointsToSet cur = graph.get(res);
        PointsToSet single = null;
        SiteSetBuilder builder = null;
        try {
            for (int j = 0, n = ops.size(); j < n; j++) {
                Value v = ops.get(j);
                if (v == null) continue;
                PointsToSet s = graph.get(v);
                if (s == last[o + j]) continue;
                last[o + j] = s;
                if (s.isEmpty() || s == cur || s == single) continue;
                if (single == null) {
                    single = s;
                } else {
                    if (builder == null) {
                        builder = SiteSetBuilder.acquire();
                        builder.addAll(cur);
                        builder.addAll(single);
                    }
                    builder.addAll(s);
                }
            }
            if (builder != null) return graph.merge(res, builder.buildOr(cur));
            return single != null && graph.merge(res, single);
        } finally {
            if (builder != null) builder.release();
        }
    }

    private boolean evalGetField(FnState f, int i, Instruction inst, PointsToSet[] last, int o) {
        PointsToSet base = graph.get(inst.getOperands().getFirst());
        PointsToSet prev = last[o];
        int fieldId = f.aux[i];
        int lo = f.loadOrdinal[i];
        long e0 = epoch.get();
        boolean baseChanged = base != prev;
        if (baseChanged) {
            registerReaders(f, base, prev, fieldId, (String) f.info[i]);
            last[o] = base;
        }
        if (base.isEmpty()) return false;
        if (!baseChanged && !anyCellNewer(base, fieldId, f.loadEpoch[lo])) return false;
        f.loadEpoch[lo] = e0;

        Value res = inst.getResult();
        PointsToSet cur = graph.get(res);
        PointsToSet single = null;
        SiteSetBuilder builder = null;
        try {
            for (int id : base.rawIds()) {
                FieldCell c = graph.findFieldCell(id, fieldId);
                if (c == null) continue;
                PointsToSet s = c.get();
                if (s.isEmpty() || s == cur || s == single) continue;
                if (single == null) {
                    single = s;
                } else {
                    if (builder == null) {
                        builder = SiteSetBuilder.acquire();
                        builder.addAll(cur);
                        builder.addAll(single);
                    }
                    builder.addAll(s);
                }
            }
            if (builder != null) return graph.merge(res, builder.buildOr(cur));
            return single != null && graph.merge(res, single);
        } finally {
            if (builder != null) builder.release();
        }
    }

    private void registerReaders(FnState f, PointsToSet base, PointsToSet prev, int fieldId, String field) {
        int[] now = base.rawIds();
        int capacity = states.length;
        if (prev == null || prev.isEmpty()) {
            for (int id : now) graph.fieldCell(id, fieldId, field).addReader(f.id, capacity);
            return;
        }
        int[] old = prev.rawIds();
        int j = 0;
        for (int id : now) {
            while (j < old.length && old[j] < id) j++;
            if (j < old.length && old[j] == id) continue;
            graph.fieldCell(id, fieldId, field).addReader(f.id, capacity);
        }
    }

    private boolean anyCellNewer(PointsToSet base, int fieldId, long since) {
        for (int id : base.rawIds()) {
            FieldCell c = graph.findFieldCell(id, fieldId);
            if (c != null && c.version() > since) return true;
        }
        return false;
    }

    private void evalPutField(FnState f, int i, Instruction inst, PointsToSet[] last, int o) {
        List<Value> ops = inst.getOperands();
        PointsToSet rhs = graph.get(ops.get(2));
        if (rhs.isEmpty()) return;
        PointsToSet base = graph.get(ops.getFirst());
        PointsToSet lastBase = last[o];
        PointsToSet lastRhs = last[o + 1];
        if (base == lastBase && rhs == lastRhs) return;
        int fieldId = f.aux[i];
        String field = (String) f.info[i];
        if (rhs == lastRhs) {
            int[] old = lastBase == null ? NO_INTS : lastBase.rawIds();
            int j = 0;
            for (int id : base.rawIds()) {
                while (j < old.length && old[j] < id) j++;
                if (j < old.length && old[j] == id) continue;
                writeField(id, fieldId, field, rhs);
            }
        } else {
            for (int id : base.rawIds()) writeField(id, fieldId, field, rhs);
            if ((f.flags[i] & F_UNKNOWN_FALLBACK) != 0) writeField(unknownId, fieldId, field, rhs);
        }
        last[o] = base;
        last[o + 1] = rhs;
    }

    private void writeField(int siteId, int fieldId, String field, PointsToSet rhs) {
        FieldCell cell = graph.fieldCell(siteId, fieldId, field);
        if (cell.merge(rhs)) {
            cell.advanceVersion(epoch.incrementAndGet());
            cell.forEachReader(this::enqueueById);
        }
    }

    private boolean evalCall(FnState f, int i, Instruction inst, PointsToSet[] last, int o) {
        CallInfo ci = (CallInfo) f.info[i];
        Value res = inst.getResult();
        byte flags = f.flags[i];
        boolean local = false;
        if (ci.summary == null) {
            if ((flags & F_NULL_SUMMARY_DONE) == 0) {
                f.flags[i] = (byte) (flags | F_NULL_SUMMARY_DONE);
                local = graph.merge(res, unknownSet);
            }
            return local;
        }
        if ((flags & F_STATIC_WRITES_DONE) == 0) {
            flags |= F_STATIC_WRITES_DONE;
            for (FieldCell cell : ci.staticWrites) {
                if (!cell.get().containsUnknown() && cell.merge(unknownSet)) cell.forEachReader(this::enqueueById);
            }
        }
        if (res != null && (flags & F_RETURNED_DONE) == 0) {
            flags |= F_RETURNED_DONE;
            if (!ci.returned.isEmpty() && graph.merge(res, ci.returned)) local = true;
        }
        f.flags[i] = flags;

        boolean virtualLike = f.aux[i] == 1;
        int argc = argCount(inst, virtualLike);
        Parameter[] params = ci.params;
        for (int j = 0; j < argc; j++) {
            Value a = arg(inst, j, virtualLike);
            if (a == null) continue;
            PointsToSet s = graph.get(a);
            if (s == last[o + j]) continue;
            last[o + j] = s;
            if (s.isEmpty()) continue;
            if (j < params.length && graph.merge(params[j], s) && ci.callee != null) enqueue(ci.callee);
            if (res != null && ci.returnsParam(j) && graph.merge(res, s)) local = true;
        }
        return local;
    }

    private long fireDeferredRules() {
        AtomicLong fired = new AtomicLong();
        LlvmUtil.awaitMono(Flux.fromArray(states)
            .parallel(parallelism)
            .runOn(scheduler)
            .doOnNext(f -> {
                if (fireDeferredRules(f)) {
                    fired.incrementAndGet();
                    enqueue(f);
                }
            })
            .sequential()
            .then());
        return fired.get();
    }

    private boolean fireDeferredRules(FnState f) {
        boolean any = false;
        for (int i = 0; i < f.ins.length; i++) {
            if ((f.flags[i] & F_UNKNOWN_FALLBACK) != 0) continue;
            Instruction inst = f.ins[i];
            byte k = f.kind[i];
            if (k == K_PUT_FIELD) {
                List<Value> ops = inst.getOperands();
                if (graph.get(ops.get(2)).isEmpty() || !graph.get(ops.getFirst()).isEmpty()) continue;
                f.flags[i] |= F_UNKNOWN_FALLBACK;
                f.last[f.inOff[i]] = null;
                f.last[f.inOff[i] + 1] = null;
                any = true;
            } else if (k == K_CALL) {
                CallInfo ci = (CallInfo) f.info[i];
                Value res = inst.getResult();
                if (ci.summary == null || res == null || !ci.returnsObject || !ci.returned.isEmpty()) continue;
                boolean virtualLike = f.aux[i] == 1;
                int argc = argCount(inst, virtualLike);
                boolean produced = false;
                for (int idx : ci.paramsReturned) {
                    if (idx >= argc) break;
                    Value a = arg(inst, idx, virtualLike);
                    if (a != null && !graph.get(a).isEmpty()) {
                        produced = true;
                        break;
                    }
                }
                if (produced) continue;
                f.flags[i] |= F_UNKNOWN_FALLBACK;
                if (graph.merge(res, unknownSet)) any = true;
            }
        }
        return any;
    }
}
