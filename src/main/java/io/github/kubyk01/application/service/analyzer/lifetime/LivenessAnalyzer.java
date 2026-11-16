package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.util.LlvmUtil;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;

@RequiredArgsConstructor
public class LivenessAnalyzer {

    private static final int PROGRESS_EVERY = 20_000;

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, FunctionSummary> summaries;

    private Scheduler scheduler = Schedulers.single();

    public void setScheduler(Scheduler scheduler) {
        if (scheduler != null) this.scheduler = scheduler;
    }

    /** Global site ids that take part in the analysis (the collected sites). */
    private BitSet tracked = new BitSet();

    private Map<BasicBlock, LiveSiteSet> liveIn;
    private Map<BasicBlock, LiveSiteSet> liveOut;

    /** Per-thread scratch indexed by global site id, used to build a function's site table. */
    private static final ThreadLocal<BitSet> SCRATCH = ThreadLocal.withInitial(BitSet::new);

    private PointsToSet getPointsTo(Value v) {
        return v == null ? PointsToSet.EMPTY : aliasResult.getPointsTo(v);
    }

    public void computeLiveSets(Collection<AllocationSite> allSites) {
        BitSet t = new BitSet(AllocationSite.count());
        for (AllocationSite site : allSites) t.set(site.getId());
        tracked = t;

        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) live.add(f);
        }
        liveIn = new ConcurrentHashMap<>();
        liveOut = new ConcurrentHashMap<>();
        if (live.isEmpty()) return;

        int total = live.size();
        AtomicInteger done = new AtomicInteger();
        LlvmUtil.awaitMono(Flux.fromIterable(live)
            .parallel()
            .runOn(scheduler)
            .doOnNext(func -> {
                solveFunction(func);
                int n = done.incrementAndGet();
                if (n % PROGRESS_EVERY == 0) {
                    System.out.println("Liveness: " + n + "/" + total + " functions");
                }
            })
            .sequential()
            .then()
        );
        System.out.println("Liveness: solved " + total + " functions");
    }

    private void solveFunction(Function func) {
        List<BasicBlock> blocks = func.getBlocks();
        int n = blocks.size();
        if (n == 0) return;

        int[] localToGlobal = buildSiteTable(blocks);

        Map<BasicBlock, Integer> blockIndex = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) blockIndex.put(blocks.get(i), i);

        BitSet[] use = new BitSet[n];
        BitSet[] def = new BitSet[n];
        for (int i = 0; i < n; i++) {
            BasicBlock block = blocks.get(i);
            BitSet u = new BitSet();
            BitSet d = new BitSet();
            for (Instruction inst : block.getInstructions()) {
                collectUses(inst, u, localToGlobal);
                collectDefs(inst, d, localToGlobal);
            }
            Terminator term = block.getTerminator();
            if (term != null) collectTerminatorUses(term, u, localToGlobal);
            use[i] = u;
            def[i] = d;
        }

        int[][] succs = new int[n][];
        int[] predCount = new int[n];
        for (int i = 0; i < n; i++) {
            List<BasicBlock> ss = blocks.get(i).getSuccessors();
            int[] idx = new int[ss.size()];
            int k = 0;
            for (BasicBlock succ : ss) {
                Integer si = blockIndex.get(succ);
                if (si != null) {
                    idx[k++] = si;
                    predCount[si]++;
                }
            }
            succs[i] = k == idx.length ? idx : Arrays.copyOf(idx, k);
        }
        int[][] preds = new int[n][];
        for (int i = 0; i < n; i++) preds[i] = new int[predCount[i]];
        int[] fill = new int[n];
        for (int i = 0; i < n; i++) {
            for (int si : succs[i]) preds[si][fill[si]++] = i;
        }

        BitSet[] in = new BitSet[n];
        BitSet[] out = new BitSet[n];
        for (int i = 0; i < n; i++) {
            in[i] = new BitSet();
            out[i] = new BitSet();
        }

        // Backward problem: worklist seeded in reverse block order, a block is
        // re-queued when the live-in of one of its successors grows. Same least
        // fixpoint as round-robin iteration, without O(n^2) passes.
        int[] queue = new int[n];
        boolean[] queued = new boolean[n];
        int head = 0, size = n;
        for (int i = 0; i < n; i++) {
            queue[i] = n - 1 - i;
            queued[n - 1 - i] = true;
        }

        BitSet scratchOut = new BitSet();
        BitSet scratchIn = new BitSet();
        while (size > 0) {
            int i = queue[head];
            head = (head + 1) % n;
            size--;
            queued[i] = false;

            scratchOut.clear();
            for (int si : succs[i]) scratchOut.or(in[si]);
            if (!scratchOut.equals(out[i])) {
                BitSet old = out[i];
                out[i] = scratchOut;
                scratchOut = old;
            }

            scratchIn.clear();
            scratchIn.or(out[i]);
            scratchIn.andNot(def[i]);
            scratchIn.or(use[i]);
            if (!scratchIn.equals(in[i])) {
                BitSet old = in[i];
                in[i] = scratchIn;
                scratchIn = old;
                for (int p : preds[i]) {
                    if (!queued[p]) {
                        queued[p] = true;
                        queue[(head + size) % n] = p;
                        size++;
                    }
                }
            }
        }

        for (int i = 0; i < n; i++) {
            BasicBlock block = blocks.get(i);
            liveIn.put(block, new LiveSiteSet(localToGlobal, in[i]));
            liveOut.put(block, new LiveSiteSet(localToGlobal, out[i]));
        }
    }

    /**
     * Sorted, de-duplicated global ids of every tracked site that any operand
     * or terminator value of the function may point to. Every site that can
     * enter a use or def set of the function is in this table.
     */
    private int[] buildSiteTable(List<BasicBlock> blocks) {
        BitSet seen = SCRATCH.get();
        seen.clear();
        for (BasicBlock block : blocks) {
            for (Instruction inst : block.getInstructions()) {
                for (Value operand : inst.getOperands()) {
                    if (operand != null) markTracked(getPointsTo(operand), seen);
                }
            }
            Terminator term = block.getTerminator();
            if (term != null) markTracked(getPointsTo(terminatorUse(term)), seen);
        }
        int[] table = new int[seen.cardinality()];
        int k = 0;
        for (int id = seen.nextSetBit(0); id >= 0; id = seen.nextSetBit(id + 1)) table[k++] = id;
        seen.clear();
        return table;
    }

    private void markTracked(PointsToSet pts, BitSet seen) {
        for (int id : pts.rawIds()) {
            if (tracked.get(id)) seen.set(id);
        }
    }

    private void setSites(PointsToSet pts, BitSet out, int[] localToGlobal) {
        for (int id : pts.rawIds()) {
            int local = Arrays.binarySearch(localToGlobal, id);
            if (local >= 0) out.set(local);
        }
    }

    private void collectUses(Instruction inst, BitSet out, int[] localToGlobal) {
        for (Value operand : inst.getOperands()) {
            if (operand != null) setSites(getPointsTo(operand), out, localToGlobal);
        }
    }

    private void collectDefs(Instruction inst, BitSet out, int[] localToGlobal) {
        Opcode op = inst.getOpcode();
        if (op == Opcode.FREE) {
            if (!inst.getOperands().isEmpty()) {
                setSites(getPointsTo(inst.getOperands().getFirst()), out, localToGlobal);
            }
        } else if (op == Opcode.CALL || op == Opcode.VIRTUAL_CALL
            || op == Opcode.INTERFACE_CALL || op == Opcode.STATIC_CALL
            || op == Opcode.SPECIAL_CALL) {
            String calleeName = extractCalleeName(inst);
            if (calleeName == null) return;
            FunctionSummary summary = summaries.get(canonicalCalleeKey(calleeName));
            if (summary == null) return;
            List<Value> args = getCallArguments(inst);
            for (int i = 0; i < args.size(); i++) {
                if (summary.getParamsDestroyed().contains(i)) {
                    setSites(getPointsTo(args.get(i)), out, localToGlobal);
                }
            }
        }
    }

    private void collectTerminatorUses(Terminator term, BitSet out, int[] localToGlobal) {
        Value used = terminatorUse(term);
        if (used != null) setSites(getPointsTo(used), out, localToGlobal);
    }

    /** The value a terminator consumes for liveness (indirect branches are not counted). */
    private static Value terminatorUse(Terminator term) {
        if (term instanceof ReturnTerminator rt) return rt.getValue();
        if (term instanceof ThrowTerminator tt) return tt.getException();
        if (term instanceof CondBranchTerminator cbt) return cbt.getCondition();
        if (term instanceof LookupSwitchTerminator lst) return lst.getKey();
        if (term instanceof TableSwitchTerminator tst) return tst.getKey();
        return null;
    }

    public Set<AllocationSite> getLiveIn(BasicBlock block) {
        Set<AllocationSite> s = liveIn != null ? liveIn.get(block) : null;
        return s != null ? s : Collections.emptySet();
    }

    public Set<AllocationSite> getLiveOut(BasicBlock block) {
        Set<AllocationSite> s = liveOut != null ? liveOut.get(block) : null;
        return s != null ? s : Collections.emptySet();
    }

    /**
     * Live-out set of every analysed block. The values are read-only views over
     * the solver's bits, so publishing the map allocates nothing per site.
     */
    public Map<BasicBlock, Set<AllocationSite>> getLiveOutMap() {
        if (liveOut == null) return null;
        return Collections.unmodifiableMap(liveOut);
    }

    public record ValueLastUse(BasicBlock block, int instructionIndex, Value value) {}

    /** Uses of every value of {@code func}, in block/instruction order. Not cached: callers compute it once per function. */
    public Map<Value, List<ValueLastUse>> computeValueLastUses(Function func) {
        Map<Value, List<ValueLastUse>> lastUses = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            List<Instruction> insts = block.getInstructions();
            for (int i = 0; i < insts.size(); i++) {
                Instruction inst = insts.get(i);
                if (inst.getOpcode() == Opcode.PHI) continue;
                for (Value operand : inst.getOperands()) {
                    if (operand == null) continue;
                    lastUses.computeIfAbsent(operand, k -> new ArrayList<>())
                        .add(new ValueLastUse(block, i, operand));
                }
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                for (Value operand : terminatorOperands(term)) {
                    if (operand == null) continue;
                    lastUses.computeIfAbsent(operand, k -> new ArrayList<>())
                        .add(new ValueLastUse(block, insts.size(), operand));
                }
            }
        }
        return lastUses;
    }

    public List<Value> terminatorOperands(Terminator term) {
        List<Value> result = new ArrayList<>();
        if (term instanceof ReturnTerminator rt) { if (rt.getValue() != null) result.add(rt.getValue()); }
        else if (term instanceof ThrowTerminator tt) { if (tt.getException() != null) result.add(tt.getException()); }
        else if (term instanceof CondBranchTerminator cbt) { if (cbt.getCondition() != null) result.add(cbt.getCondition()); }
        else if (term instanceof LookupSwitchTerminator lst) { if (lst.getKey() != null) result.add(lst.getKey()); }
        else if (term instanceof TableSwitchTerminator tst) { if (tst.getKey() != null) result.add(tst.getKey()); }
        else if (term instanceof IndirectBranchTerminator ibt) { if (ibt.getTargetBlock() != null) result.add(ibt.getTargetBlock()); }
        return result;
    }

    /**
     * Read-only set of sites backed by a function-local BitSet. {@code contains}
     * is a binary search in the function's site table plus one bit test.
     */
    static final class LiveSiteSet extends AbstractSet<AllocationSite> {
        private final int[] localToGlobal;
        private final BitSet bits;
        private final int size;

        LiveSiteSet(int[] localToGlobal, BitSet bits) {
            this.localToGlobal = localToGlobal;
            this.bits = bits;
            this.size = bits.cardinality();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof AllocationSite site)) return false;
            int local = Arrays.binarySearch(localToGlobal, site.getId());
            return local >= 0 && bits.get(local);
        }

        @Override
        public int size() { return size; }

        @Override
        public boolean isEmpty() { return size == 0; }

        @Override
        public Iterator<AllocationSite> iterator() {
            return new Iterator<>() {
                private int next = bits.nextSetBit(0);

                @Override
                public boolean hasNext() { return next >= 0; }

                @Override
                public AllocationSite next() {
                    if (next < 0) throw new NoSuchElementException();
                    AllocationSite s = AllocationSite.byId(localToGlobal[next]);
                    next = bits.nextSetBit(next + 1);
                    return s;
                }
            };
        }
    }
}
