package io.github.kubyk01.application.service.analyzer.lifetime;

import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FunctionSummary;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.ir.*;
import io.github.kubyk01.domain.ir.Module;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.getCallArguments;

@Slf4j
public class LivenessAnalyzer {

    private static final int[] EMPTY_INT = new int[0];

    private final Module module;
    private final AliasAnalysisResult aliasResult;
    private final Map<String, FunctionSummary> summaries;

    /**
     * Value-level points-to cache. Populated lazily during the
     * per-function batch. Cleared by {@link #clearCaches()} once all
     * functions have been processed; kept alive between functions so
     * that cross-function operand points-to is not recomputed from
     * scratch.
     */
    private final Map<Value, Set<AllocationSite>> pointsToCache = new ConcurrentHashMap<>();

    public LivenessAnalyzer(Module module,
                            AliasAnalysisResult aliasResult,
                            Map<String, FunctionSummary> summaries) {
        this.module = module;
        this.aliasResult = aliasResult;
        this.summaries = summaries;
    }

    public void clearCaches() {
        pointsToCache.clear();
    }

    private Set<AllocationSite> getPointsTo(Value v) {
        if (v == null) return Collections.emptySet();
        return pointsToCache.computeIfAbsent(v,
            key -> aliasResult.getPointsTo(key).getSites());
    }

    // ==================================================================
    //  Public per-function entry point
    // ==================================================================

    /**
     * Computes and returns the liveness facts for one function. The
     * caller owns the returned object and is expected to discard it
     * once {@code computeSite} has run for every site of the function
     * and {@code computeFieldOverwriteGates} has recorded every gate.
     *
     * <p>Nothing survives this call. The function's
     * {@code liveIn}/{@code liveOut} bitsets are not published anywhere
     * outside the returned {@link FunctionLiveness}, and the only state
     * that persists on the analyser is the per-{@link Value}
     * points-to cache.</p>
     */
    public FunctionLiveness computeForFunction(Function func) {
        return FunctionLiveness.compute(func, module, this);
    }

    // ==================================================================
    //  Accessors used by FunctionLiveness (package-private)
    // ==================================================================

    Map<String, FunctionSummary> summaries() { return summaries; }
    Set<AllocationSite> pointsTo(Value v)    { return getPointsTo(v); }
    Module module()                          { return module; }

    // ==================================================================
    //  Value-level last-use index (cached per function, used by computeSite)
    // ==================================================================

    public record ValueLastUse(BasicBlock block, int instructionIndex, Value value) {}

    /**
     * Returns the ordered list of syntactic occurrences of every value
     * used as an operand anywhere in {@code func}. The last element of
     * each list is the value's last syntactic use.
     *
     * <p>Intended to be called exactly once per function, from inside
     * the per-function batch. There is no cache here because the caller
     * uses the result for every site of the function anyway; caching
     * the result on the analyser would defeat the goal of discarding
     * per-function state.</p>
     */
    public Map<Value, List<ValueLastUse>> computeValueLastUses(Function func) {
        Map<Value, List<ValueLastUse>> lastUses = new HashMap<>();
        for (BasicBlock block : func.getBlocks()) {
            List<Instruction> insts = block.getInstructions();
            for (int i = 0; i < insts.size(); i++) {
                Instruction inst = insts.get(i);
                // PHI operands are evaluated on the incoming edge, not in
                // the PHI's own block. Recording them as uses here would
                // mis-attribute the last use to a block that may not
                // dominate the operand's definition.
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
        List<Value> result = new ArrayList<>(2);
        if (term instanceof ReturnTerminator rt)               { if (rt.getValue() != null) result.add(rt.getValue()); }
        else if (term instanceof ThrowTerminator tt)           { if (tt.getException() != null) result.add(tt.getException()); }
        else if (term instanceof CondBranchTerminator cbt)     { if (cbt.getCondition() != null) result.add(cbt.getCondition()); }
        else if (term instanceof LookupSwitchTerminator lst)   { if (lst.getKey() != null) result.add(lst.getKey()); }
        else if (term instanceof TableSwitchTerminator tst)    { if (tst.getKey() != null) result.add(tst.getKey()); }
        else if (term instanceof IndirectBranchTerminator ibt) { if (ibt.getTargetBlock() != null) result.add(ibt.getTargetBlock()); }
        return result;
    }

    // ==================================================================
    //  FunctionLiveness — the single-function result object
    // ==================================================================

    /**
     * Liveness facts for one function. Consumed exclusively by the
     * batch that produced it; the caller is expected not to retain the
     * object beyond the batch's lifetime.
     */
    public static final class FunctionLiveness {
        private final int siteCount;
        private final int wordCount;
        private final Map<AllocationSite, Integer> localId;
        private final AllocationSite[] localSites;
        private final Map<BasicBlock, long[]> liveIn;
        private final Map<BasicBlock, long[]> liveOut;

        private FunctionLiveness(int siteCount, int wordCount,
                                 Map<AllocationSite, Integer> localId,
                                 AllocationSite[] localSites,
                                 Map<BasicBlock, long[]> liveIn,
                                 Map<BasicBlock, long[]> liveOut) {
            this.siteCount = siteCount;
            this.wordCount = wordCount;
            this.localId = localId;
            this.localSites = localSites;
            this.liveIn = liveIn;
            this.liveOut = liveOut;
        }

        public int siteCount() { return siteCount; }
        public int wordCount() { return wordCount; }
        public AllocationSite siteById(int id) { return localSites[id]; }

        public boolean isLiveIn(BasicBlock block, AllocationSite site) {
            return test(liveIn, block, site);
        }

        public boolean isLiveOut(BasicBlock block, AllocationSite site) {
            return test(liveOut, block, site);
        }

        public boolean hasLiveOut(BasicBlock block) {
            return liveOut.containsKey(block);
        }

        private boolean test(Map<BasicBlock, long[]> store,
                             BasicBlock block, AllocationSite site) {
            if (wordCount == 0) return false;
            Integer id = localId.get(site);
            if (id == null) return false;
            long[] bits = store.get(block);
            if (bits == null) return false;
            return (bits[id >>> 6] & (1L << (id & 63))) != 0;
        }

        // --------------------------------------------------------------
        //  Construction
        // --------------------------------------------------------------

        static FunctionLiveness compute(Function func, Module module, LivenessAnalyzer analyzer) {
            List<BasicBlock> blocks = func.getBlocks();

            // ---- 1. Local site set ----------------------------------
            //
            // LinkedHashSet preserves insertion order, so the local ids
            // and the allocation of bits across words are deterministic.
            LinkedHashSet<AllocationSite> localSitesSet = new LinkedHashSet<>();
            for (BasicBlock block : blocks) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getResult() != null) {
                        addRealSites(localSitesSet, analyzer.pointsTo(inst.getResult()));
                    }
                    for (Value operand : inst.getOperands()) {
                        addRealSites(localSitesSet, analyzer.pointsTo(operand));
                    }
                    if (inst.getOpcode() == Opcode.PUT_FIELD
                        && isReferenceRhs(inst)
                        && !isOuterInstanceField(fieldNameOf(inst))) {
                        PointsToSet fieldSites = fieldSitesOf(inst, analyzer);
                        if (fieldSites != null && !fieldSites.isEmpty()) {
                            addRealSites(localSitesSet, fieldSites.getSites());
                        }
                    }
                }
            }

            if (localSitesSet.isEmpty()) {
                return new FunctionLiveness(0, 0,
                    Collections.emptyMap(),
                    new AllocationSite[0],
                    Collections.emptyMap(),
                    Collections.emptyMap());
            }

            int S_f = localSitesSet.size();
            int W_f = (S_f + 63) >>> 6;

            Map<AllocationSite, Integer> localId = new HashMap<>(S_f * 2);
            AllocationSite[] localSites = new AllocationSite[S_f];
            int next = 0;
            for (AllocationSite site : localSitesSet) {
                localId.put(site, next);
                localSites[next++] = site;
            }

            // ---- 2. use / def per block as sorted int[] -------------
            Map<BasicBlock, int[]> use = new HashMap<>(blocks.size() * 2);
            Map<BasicBlock, int[]> def = new HashMap<>(blocks.size() * 2);
            for (BasicBlock block : blocks) {
                use.put(block, collectUseIds(block, analyzer, localId));
                def.put(block, collectDefIds(block, analyzer, localId));
            }

            // ---- 3. Lazy storage + worklist ------------------------
            Map<BasicBlock, long[]> liveIn  = new HashMap<>(blocks.size() * 2);
            Map<BasicBlock, long[]> liveOut = new HashMap<>(blocks.size() * 2);
            // Every block starts implicitly at "no bits set" (absent from
            // the map). The worklist fills the map only where the live
            // set is non-empty.

            runWorklist(func, blocks, use, def, liveIn, liveOut, W_f);

            // ---- 4. Drop the use/def maps --------------------------
            // They are no longer needed; the caller will consume only
            // liveIn/liveOut through the FunctionLiveness object.
            use.clear();
            def.clear();

            return new FunctionLiveness(S_f, W_f, localId, localSites, liveIn, liveOut);
        }

        private static void runWorklist(Function func,
                                        List<BasicBlock> blocks,
                                        Map<BasicBlock, int[]> use,
                                        Map<BasicBlock, int[]> def,
                                        Map<BasicBlock, long[]> liveIn,
                                        Map<BasicBlock, long[]> liveOut,
                                        int wordCount) {
            if (wordCount == 0) return;

            // Predecessor adjacency, built once.
            Map<BasicBlock, List<BasicBlock>> preds =
                new HashMap<>(blocks.size() * 2);
            for (BasicBlock b : blocks) {
                for (BasicBlock succ : b.getSuccessors()) {
                    preds.computeIfAbsent(succ, k -> new ArrayList<>(2)).add(b);
                }
            }

            Deque<BasicBlock> worklist = new ArrayDeque<>(blocks.size());
            Set<BasicBlock> inWorklist = new HashSet<>(blocks.size() * 2);
            for (BasicBlock b : blocks) {
                worklist.add(b);
                inWorklist.add(b);
            }

            while (!worklist.isEmpty()) {
                BasicBlock b = worklist.poll();
                inWorklist.remove(b);

                // liveOut[b] = ⋃ liveIn[succ]
                long[] newLiveOut = null;
                for (BasicBlock succ : b.getSuccessors()) {
                    long[] succLiveIn = liveIn.get(succ);
                    if (succLiveIn == null) continue;
                    if (newLiveOut == null) newLiveOut = new long[wordCount];
                    for (int w = 0; w < wordCount; w++) {
                        newLiveOut[w] |= succLiveIn[w];
                    }
                }
                if (newLiveOut != null && isAllZero(newLiveOut)) newLiveOut = null;

                long[] oldLiveOut = liveOut.get(b);
                if (setsEqual(oldLiveOut, newLiveOut)) continue;
                if (newLiveOut == null) liveOut.remove(b);
                else                    liveOut.put(b, newLiveOut);

                // liveIn[b] = use[b] ∪ (liveOut[b] ∖ def[b])
                long[] newLiveIn = null;
                if (newLiveOut != null) newLiveIn = newLiveOut.clone();
                int[] bDef = def.get(b);
                if (newLiveIn != null && bDef.length > 0) {
                    for (int id : bDef) newLiveIn[id >>> 6] &= ~(1L << (id & 63));
                    if (isAllZero(newLiveIn)) newLiveIn = null;
                }
                int[] bUse = use.get(b);
                if (bUse.length > 0) {
                    if (newLiveIn == null) newLiveIn = new long[wordCount];
                    for (int id : bUse) newLiveIn[id >>> 6] |= 1L << (id & 63);
                }

                long[] oldLiveIn = liveIn.get(b);
                if (setsEqual(oldLiveIn, newLiveIn)) continue;
                if (newLiveIn == null) liveIn.remove(b);
                else                   liveIn.put(b, newLiveIn);

                List<BasicBlock> blockPreds = preds.get(b);
                if (blockPreds == null) continue;
                for (BasicBlock pred : blockPreds) {
                    if (inWorklist.add(pred)) worklist.add(pred);
                }
            }
        }

        // --------------------------------------------------------------
        //  Helpers
        // --------------------------------------------------------------

        private static void addRealSites(Set<AllocationSite> dst, Collection<AllocationSite> sites) {
            for (AllocationSite s : sites) {
                if (s != null && s != AllocationSite.UNKNOWN) dst.add(s);
            }
        }

        private static int[] collectUseIds(BasicBlock block,
                                           LivenessAnalyzer analyzer,
                                           Map<AllocationSite, Integer> localId) {
            List<Integer> ids = new ArrayList<>();
            for (Instruction inst : block.getInstructions()) {
                for (Value operand : inst.getOperands()) {
                    if (operand == null) continue;
                    for (AllocationSite site : analyzer.pointsTo(operand)) {
                        Integer id = localId.get(site);
                        if (id != null) ids.add(id);
                    }
                }
            }
            Terminator term = block.getTerminator();
            if (term != null) {
                for (Value operand : terminatorOperandsOf(term)) {
                    if (operand == null) continue;
                    for (AllocationSite site : analyzer.pointsTo(operand)) {
                        Integer id = localId.get(site);
                        if (id != null) ids.add(id);
                    }
                }
            }
            return sortDedup(ids);
        }

        private static int[] collectDefIds(BasicBlock block,
                                           LivenessAnalyzer analyzer,
                                           Map<AllocationSite, Integer> localId) {
            List<Integer> ids = new ArrayList<>();
            for (Instruction inst : block.getInstructions()) {
                Opcode op = inst.getOpcode();
                if (op == Opcode.FREE) {
                    if (!inst.getOperands().isEmpty()) {
                        for (AllocationSite site : analyzer.pointsTo(inst.getOperands().getFirst())) {
                            Integer id = localId.get(site);
                            if (id != null) ids.add(id);
                        }
                    }
                } else if (op == Opcode.CALL || op == Opcode.VIRTUAL_CALL
                    || op == Opcode.INTERFACE_CALL || op == Opcode.STATIC_CALL
                    || op == Opcode.SPECIAL_CALL) {
                    String calleeName = extractCalleeName(inst);
                    if (calleeName == null) continue;
                    FunctionSummary summary =
                        analyzer.summaries().get(canonicalCalleeKey(calleeName));
                    if (summary == null || summary.getParamsDestroyed().isEmpty()) continue;
                    List<Value> args = getCallArguments(inst);
                    for (int i = 0; i < args.size(); i++) {
                        if (!summary.getParamsDestroyed().contains(i)) continue;
                        for (AllocationSite site : analyzer.pointsTo(args.get(i))) {
                            Integer id = localId.get(site);
                            if (id != null) ids.add(id);
                        }
                    }
                }
            }
            return sortDedup(ids);
        }

        private static int[] sortDedup(List<Integer> ids) {
            if (ids.isEmpty()) return EMPTY_INT;
            int[] arr = new int[ids.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
            Arrays.sort(arr);
            int w = 0;
            for (int i = 0; i < arr.length; i++) {
                if (i == 0 || arr[i] != arr[i - 1]) arr[w++] = arr[i];
            }
            return w == arr.length ? arr : Arrays.copyOf(arr, w);
        }

        private static boolean isAllZero(long[] bits) {
            for (long w : bits) if (w != 0) return false;
            return true;
        }

        private static boolean setsEqual(long[] a, long[] b) {
            if (a == b) return true;
            if (a == null || b == null) return false;
            if (a.length != b.length) return false;
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) return false;
            }
            return true;
        }

        private static List<Value> terminatorOperandsOf(Terminator term) {
            List<Value> out = new ArrayList<>(2);
            if (term instanceof ReturnTerminator rt)               { if (rt.getValue() != null) out.add(rt.getValue()); }
            else if (term instanceof ThrowTerminator tt)           { if (tt.getException() != null) out.add(tt.getException()); }
            else if (term instanceof CondBranchTerminator cbt)     { if (cbt.getCondition() != null) out.add(cbt.getCondition()); }
            else if (term instanceof LookupSwitchTerminator lst)   { if (lst.getKey() != null) out.add(lst.getKey()); }
            else if (term instanceof TableSwitchTerminator tst)    { if (tst.getKey() != null) out.add(tst.getKey()); }
            else if (term instanceof IndirectBranchTerminator ibt) { if (ibt.getTargetBlock() != null) out.add(ibt.getTargetBlock()); }
            return out;
        }

        // --------------------------------------------------------------
        //  PUT_FIELD helpers
        // --------------------------------------------------------------

        static boolean isReferenceRhs(Instruction putField) {
            if (putField.getOperands().size() < 3) return false;
            Value rhs = putField.getOperands().get(2);
            if (rhs == null) return false;
            Type t = rhs.getType();
            return t != null && !t.isPrimitive() && !t.isVoid();
        }

        static String fieldNameOf(Instruction fieldAccess) {
            if (fieldAccess.getOperands().size() < 2) return null;
            Value fieldConst = fieldAccess.getOperands().get(1);
            if (!(fieldConst instanceof Constant c)) return null;
            String key = c.getValue().toString();
            int dot = key.lastIndexOf('.');
            return dot < 0 ? key : key.substring(dot + 1);
        }

        static boolean isOuterInstanceField(String fieldName) {
            if (fieldName == null) return false;
            if (!fieldName.startsWith("this$")) return false;
            if (fieldName.length() == "this$".length()) return false;
            for (int i = "this$".length(); i < fieldName.length(); i++) {
                char c = fieldName.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            return true;
        }

        static PointsToSet fieldSitesOf(Instruction putField, LivenessAnalyzer analyzer) {
            if (putField.getOperands().size() < 2) return null;
            Value base = putField.getOperands().get(0);
            Value fieldConst = putField.getOperands().get(1);
            if (!(fieldConst instanceof Constant c) || !c.getType().isReference()) return null;
            String fieldKey = c.getValue().toString();
            PointsToSet baseSites = analyzer.aliasResult().getPointsTo(base);
            return analyzer.aliasResult().getFieldPointsToForSites(baseSites, fieldKey);
        }
    }

    // Expose aliasResult for FunctionLiveness.fieldSitesOf without an
    // awkward parameter chain; the field is already final.
    AliasAnalysisResult aliasResult() { return aliasResult; }
}