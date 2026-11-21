package io.github.kubyk01.domain.analyzer.lifetime.strategies;

import io.github.kubyk01.application.service.analyzer.lifetime.LivenessAnalyzer;
import io.github.kubyk01.application.service.analyzer.ssa.DominatorTree;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult.CallShape;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.escapeanalysis.strategies.CallShapeEscapeStrategy;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;

public final class FreshObjectOwnershipLifetimeStrategy implements LifetimeAnalysisStrategies {

    private static final AllocationSite OWNED =
        AllocationSite.of("<owned>", -1, "java/lang/Object", Type.reference("java/lang/Object"));
    private static final AllocationSite FRAME =
        AllocationSite.of("<owned-frame>", -1, "java/lang/Object", Type.reference("java/lang/Object"));
    private static final int NONE = 0;
    private static final int HEAP = 1;
    private static final int STACK_FRAME = 2;
    private static final int MAX_REGION = 4096;

    private record Use(int block, int index, Instruction inst) {}

    private record Death(DestructionPoint point, boolean frame) {}

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public LifetimeAnalysisResult analyze(Module module, Set<AllocationSite> sites, AliasAnalysisResult aliasResult,
                                          EscapeAnalysisResult escapeResult, LivenessAnalyzer liveness,
                                          Map<Function, DominatorTree> domTrees,
                                          Map<Function, Map<BasicBlock, Set<BasicBlock>>> loops,
                                          Scheduler scheduler) {
        List<Function> live = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null && domTrees.containsKey(f)) live.add(f);
        }
        List<List<Death>> parts = LlvmUtil.awaitFlux(Flux.fromIterable(live)
            .parallel()
            .runOn(scheduler)
            .map(f -> new FunctionOwnership(f, domTrees.get(f), aliasResult, escapeResult, liveness, loops).deaths())
            .filter(l -> !l.isEmpty())
            .sequential());
        LifetimeAnalysisResult result = new LifetimeAnalysisResult();
        if (parts == null || parts.isEmpty()) return result;
        int roots = 0;
        for (List<Death> part : parts) {
            for (Death d : part) {
                result.getDestructionPoints().computeIfAbsent(d.frame() ? FRAME : OWNED, x -> new HashSet<>()).add(d.point());
            }
            roots += part.size();
        }
        result.getShallowSites().add(OWNED);
        result.getShallowSites().add(FRAME);
        result.getFrameSites().add(FRAME);
        System.out.println("Lifetime: " + roots + " free point(s) for fresh owned objects");
        return result;
    }

    private static final class FunctionOwnership {
        private final DominatorTree dom;
        private final AliasAnalysisResult alias;
        private final EscapeAnalysisResult escape;
        private final LivenessAnalyzer liveness;
        private final int n;
        private final Map<Value, List<Use>> uses = new IdentityHashMap<>();
        private final boolean[] handler;
        private final Map<Function, Map<BasicBlock, Set<BasicBlock>>> loops;

        FunctionOwnership(Function function, DominatorTree dom, AliasAnalysisResult alias,
                          EscapeAnalysisResult escape, LivenessAnalyzer liveness,
                          Map<Function, Map<BasicBlock, Set<BasicBlock>>> loops) {
            this.dom = dom;
            this.alias = alias;
            this.escape = escape;
            this.liveness = liveness;
            this.loops = loops;
            this.n = dom.size();
            this.handler = new boolean[n];
        }

        List<Death> deaths() {
            List<Instruction> roots = new ArrayList<>();
            List<Boolean> frames = new ArrayList<>();
            for (int b = 0; b < n; b++) {
                BasicBlock block = dom.blockAt(b);
                for (BasicBlock ex : block.getExceptionalSuccessors()) {
                    int h = dom.indexOf(ex);
                    if (h >= 0) handler[h] = true;
                }
                for (Instruction inst : block.getInstructions()) {
                    int kind = rootKind(inst);
                    if (kind == NONE) continue;
                    roots.add(inst);
                    frames.add(kind == STACK_FRAME);
                }
            }
            if (roots.isEmpty()) return List.of();
            indexUses();
            List<Death> out = new ArrayList<>();
            List<DestructionPoint> points = new ArrayList<>();
            boolean dbg = System.getenv("JN_OWN_DEBUG") != null && dom.blockAt(0).getFunction() != null
                && dom.blockAt(0).getFunction().getName().contains(System.getenv("JN_OWN_DEBUG"));
            dbgOn = dbg;
            for (int r = 0; r < roots.size(); r++) {
                Instruction root = roots.get(r);
                points.clear();
                placeDeaths(root, points);
                for (int k = 0; k < points.size(); k++) out.add(new Death(points.get(k), frames.get(r)));
                if (dbg) System.out.println("DBG root " + root.getOpcode() + " " + extractCalleeName(root) + " deaths=" + points.size() + " why=" + why);
            }
            return out;
        }

        private String why = "";
        private boolean dbgOn;
        private Map<Integer, List<Instruction>> loadsBySlot;

        private List<Instruction> loads(int slot) {
            if (loadsBySlot == null) {
                loadsBySlot = new java.util.HashMap<>();
                for (int b = 0; b < n; b++) {
                    for (Instruction inst : dom.blockAt(b).getInstructions()) {
                        if (inst.getOpcode() == Opcode.LOAD && inst.getLocalIndex() >= 0 && inst.getResult() != null) {
                            loadsBySlot.computeIfAbsent(inst.getLocalIndex(), k -> new ArrayList<>()).add(inst);
                        }
                    }
                }
            }
            return loadsBySlot.getOrDefault(slot, List.of());
        }

        private int rootKind(Instruction inst) {
            Value res = inst.getResult();
            if (res == null || !isObject(res.getType())) return NONE;
            Opcode op = inst.getOpcode();
            if (op == Opcode.NEW) {
                Type t = res.getType();
                String cls = t.isReference() ? t.getClassName() : null;
                if (cls == null || cls.isEmpty() || !LlvmUtil.isSystemClassName(cls)) return NONE;
                return placement(res, true);
            }
            if (op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY) {
                Set<AllocationSite> sites = alias.getPointsTo(res).getSites();
                if (sites.isEmpty()) return NONE;
                for (AllocationSite site : sites) {
                    if (escape.getSiteStatus(site) != EscapeStatus.STACK || sitePlacement(site) != HEAP) return NONE;
                }
                return HEAP;
            }
            if (!CallShapes.isCall(op)) return NONE;
            CallShape shape = escape.getCallShape(inst, extractCalleeName(inst));
            return shape != null && shape.freshReturn() && placement(res, false) == HEAP ? HEAP : NONE;
        }

        private int placement(Value v, boolean own) {
            Set<AllocationSite> sites = alias.getPointsTo(v).getSites();
            boolean frame = false;
            boolean heap = false;
            for (AllocationSite site : sites) {
                if (escape.getSiteStatus(site) != EscapeStatus.STACK) {
                    heap = true;
                    continue;
                }
                if (own && alias.getAllocationSiteToValue().get(site) != v) return NONE;
                int p = sitePlacement(site);
                if (p == NONE) return NONE;
                if (p == STACK_FRAME) frame = true;
                else heap = true;
            }
            if (frame) return heap ? NONE : STACK_FRAME;
            return HEAP;
        }

        private int sitePlacement(AllocationSite site) {
            Value v = alias.getAllocationSiteToValue().get(site);
            if (!(v instanceof Temporary t) || t.getDefiningInstruction() == null) return NONE;
            Instruction def = t.getDefiningInstruction();
            Opcode op = def.getOpcode();
            if (op == Opcode.NEW_ARRAY || op == Opcode.MULTI_NEW_ARRAY) return HEAP;
            BasicBlock block = def.getParent();
            Function fn = block == null ? null : block.getFunction();
            Map<BasicBlock, Set<BasicBlock>> fl = fn == null ? null : loops.get(fn);
            if (op != Opcode.NEW || fl == null || !usedAsCallOperand(def, fn)) return NONE;
            for (Set<BasicBlock> body : fl.values()) {
                if (body.contains(block)) return HEAP;
            }
            return STACK_FRAME;
        }

        private static boolean usedAsCallOperand(Instruction def, Function fn) {
            Value res = def.getResult();
            if (res == null) return false;
            for (int b = 0; b < fn.getBlocks().size(); b++) {
                List<Instruction> insts = fn.getBlocks().get(b).getInstructions();
                for (Instruction inst : insts) {
                    if (inst == def || !CallShapes.isCall(inst.getOpcode())) continue;
                    if (inst.getOperands().contains(res)) return true;
                }
            }
            return false;
        }

        private static boolean isObject(Type t) {
            return t != null && (t.isReference() || t.isArray()) && !t.isNull();
        }

        private void indexUses() {
            for (int b = 0; b < n; b++) {
                BasicBlock block = dom.blockAt(b);
                List<Instruction> insts = block.getInstructions();
                for (int i = 0; i < insts.size(); i++) {
                    Instruction inst = insts.get(i);
                    for (Value op : inst.getOperands()) {
                        if (op instanceof Temporary) uses.computeIfAbsent(op, k -> new ArrayList<>()).add(new Use(b, i, inst));
                    }
                }
                Terminator term = block.getTerminator();
                if (term == null) continue;
                for (Value op : liveness.terminatorOperands(term)) {
                    if (op instanceof Temporary) uses.computeIfAbsent(op, k -> new ArrayList<>()).add(new Use(b, insts.size(), null));
                }
            }
        }

        private void placeDeaths(Instruction root, List<DestructionPoint> out) {
            why = "ok";
            List<Value> aliases = new ArrayList<>();
            List<Integer> slots = new ArrayList<>();
            aliases.add(root.getResult());
            for (int a = 0; a < aliases.size(); a++) {
                Value v = aliases.get(a);
                for (Use u : uses.getOrDefault(v, List.of())) {
                    if (u.inst() == null) { why = "r1"; return; }
                    Instruction inst = u.inst();
                    switch (inst.getOpcode()) {
                        case CAST, CHECKCAST -> {
                            Value r = inst.getResult();
                            if (r == null) continue;
                            if (!aliases.contains(r)) aliases.add(r);
                            if (aliases.size() > 63) { why = "r3"; return; }
                        }
                        case INVOKEDYNAMIC -> {
                            if (!CallShapeEscapeStrategy.concatReadsOnly(inst, v)) { why = "r18"; return; }
                        }
                        case GET_FIELD, ALOAD, ARRAYLENGTH, INSTANCEOF, EQ, NE, MONITOR_ENTER, MONITOR_EXIT -> {
                            if (inst.getOperands().getFirst() != v && inst.getOpcode() != Opcode.EQ
                                && inst.getOpcode() != Opcode.NE) { why = "r4"; return; }
                        }
                        case PUT_FIELD, ASTORE -> {
                            List<Value> ops = inst.getOperands();
                            for (int k = 1; k < ops.size(); k++) if (ops.get(k) == v) { why = "r5"; return; }
                        }
                        case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                            CallShape shape = escape.getCallShape(inst, extractCalleeName(inst));
                            if (shape == null) { why = "r6"; return; }
                            int argc = CallShapes.argCount(inst);
                            for (int j = 0; j < argc; j++) {
                                if (CallShapes.arg(inst, j) != v) continue;
                                if (CallShape.has(shape.retainedArgs(), j)) { why = "r7"; return; }
                                Value r = inst.getResult();
                                if (CallShape.has(shape.returnedArgs(), j) && r != null && isObject(r.getType())) {
                                    if (!aliases.contains(r)) aliases.add(r);
                                }
                            }
                        }
                        case STORE -> {
                            int slot = inst.getLocalIndex();
                            if (slot < 0 || inst.getOperands().getFirst() != v) { why = "r9"; return; }
                            Value sr = inst.getResult();
                            if (sr != null) {
                                if (!aliases.contains(sr)) aliases.add(sr);
                            }
                            if (!slots.contains(slot)) {
                                slots.add(slot);
                                for (Instruction load : loads(slot)) {
                                    Value r = load.getResult();
                                    if (r == null) { why = "r16"; return; }
                                    if (!aliases.contains(r)) aliases.add(r);
                                }
                            }
                        }
                        default -> {
                            { why = "r9"; return; }
                        }
                    }
                }
            }

            if (aliases.size() + slots.size() > 63) { why = "r3"; return; }
            int rootBlock = dom.indexOf(root.getParent());
            if (rootBlock < 0) { why = "r10"; return; }
            int[] region = subtree(rootBlock);
            if (region == null) { why = "r11"; return; }
            int m = region.length;
            int[] local = new int[n];
            Arrays.fill(local, -1);
            for (int k = 0; k < m; k++) local[region[k]] = k;

            long[] gen = new long[m];
            long[] kill = new long[m];
            int[] lastTouch = new int[m];
            Arrays.fill(lastTouch, -1);
            for (int a = 0; a < aliases.size(); a++) {
                long bit = 1L << a;
                Value v = aliases.get(a);
                Instruction def = ((Temporary) v).getDefiningInstruction();
                if (def == null || def.getParent() == null) { why = "r12"; return; }
                int db = dom.indexOf(def.getParent());
                if (db < 0 || local[db] < 0) { why = "r13"; return; }
                int di = def.getParent().getInstructions().indexOf(def);
                if (di < 0) { why = "r14"; return; }
                int ld = local[db];
                kill[ld] |= bit;
                lastTouch[ld] = Math.max(lastTouch[ld], di);
                for (Use u : uses.getOrDefault(v, List.of())) {
                    int lu = u.block() < n ? local[u.block()] : -1;
                    if (lu < 0) { why = "r15"; return; }
                    if (!(lu == ld && u.index() > di)) gen[lu] |= bit;
                    lastTouch[lu] = Math.max(lastTouch[lu], u.index());
                }
            }

            for (int q = 0; q < slots.size(); q++) {
                long bit = 1L << (aliases.size() + q);
                int slot = slots.get(q);
                for (int k = 0; k < m; k++) {
                    List<Instruction> insts = dom.blockAt(region[k]).getInstructions();
                    boolean stored = false;
                    for (int i = 0; i < insts.size(); i++) {
                        Instruction inst = insts.get(i);
                        if (inst.getLocalIndex() != slot) continue;
                        if (inst.getOpcode() == Opcode.STORE && aliases.contains(inst.getOperands().getFirst())) {
                            if (!stored) kill[k] |= bit;
                            stored = true;
                            lastTouch[k] = Math.max(lastTouch[k], i);
                        } else if (inst.getOpcode() == Opcode.LOAD) {
                            if (!stored) gen[k] |= bit;
                            lastTouch[k] = Math.max(lastTouch[k], i);
                        }
                    }
                }
                for (Instruction load : loads(slot)) {
                    int lb = dom.indexOf(load.getParent());
                    if (lb < 0 || local[lb] < 0) { why = "r17"; return; }
                }
            }

            long[] liveIn = new long[m];
            long[] liveOut = new long[m];
            int[][] succ = new int[m][];
            for (int k = 0; k < m; k++) {
                List<BasicBlock> ss = dom.blockAt(region[k]).getSuccessors();
                int[] s = new int[ss.size()];
                int c = 0;
                for (BasicBlock sb : ss) {
                    int gi = dom.indexOf(sb);
                    int li = gi < 0 ? -1 : local[gi];
                    if (li >= 0) s[c++] = li;
                }
                succ[k] = c == s.length ? s : Arrays.copyOf(s, c);
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int k = m - 1; k >= 0; k--) {
                    long o = 0;
                    for (int s : succ[k]) o |= liveIn[s];
                    long in = gen[k] | (o & ~kill[k]);
                    if (o != liveOut[k] || in != liveIn[k]) {
                        liveOut[k] = o;
                        liveIn[k] = in;
                        changed = true;
                    }
                }
            }
            for (int k = 0; k < m; k++) {
                if (handler[region[k]] && liveIn[k] != 0) { why = "r16"; return; }
            }

            if (dbgOn) {
                StringBuilder d = new StringBuilder("DBG  aliases:");
                for (Value a : aliases) {
                    Instruction di = ((Temporary) a).getDefiningInstruction();
                    d.append(' ').append(di.getOpcode()).append('@').append(dom.indexOf(di.getParent()))
                        .append(':').append(di.getParent().getInstructions().indexOf(di))
                        .append("/uses=").append(uses.getOrDefault(a, List.of()).size());
                }
                d.append(" slots=").append(slots).append(" region=").append(m);
                for (int k = 0; k < m; k++) {
                    if (lastTouch[k] >= 0 || liveIn[k] != 0) d.append(" [b").append(region[k]).append(" t=").append(lastTouch[k])
                        .append(" in=").append(Long.toHexString(liveIn[k])).append(" out=").append(Long.toHexString(liveOut[k])).append(']');
                }
                System.out.println(d);
                List<Instruction> rb = root.getParent().getInstructions();
                for (int q = 0; q < rb.size(); q++) System.out.println("DBG    " + q + ": " + rb.get(q) + " li=" + rb.get(q).getLocalIndex());
            }
            Value objRef = root.getResult();
            List<DestructionPoint> found = new ArrayList<>();
            for (int k = 0; k < m; k++) {
                BasicBlock block = dom.blockAt(region[k]);
                List<Instruction> insts = block.getInstructions();
                boolean touched = lastTouch[k] >= 0;
                if (touched) {
                    if (liveOut[k] != 0) continue;
                    if (lastTouch[k] >= insts.size()) { why = "r17"; return; }
                    Instruction before = lastTouch[k] + 1 < insts.size() ? insts.get(lastTouch[k] + 1) : null;
                    found.add(new DestructionPoint(block, before, objRef));
                    continue;
                }
                if (region[k] == rootBlock || handler[region[k]]) continue;
                if (liveIn[k] != 0) {
                    if (liveOut[k] == 0) found.add(new DestructionPoint(block, firstNonPhi(insts), objRef));
                    continue;
                }
                List<BasicBlock> preds = block.getPredecessors();
                if (preds.size() != 1) continue;
                int pg = dom.indexOf(preds.getFirst());
                int pl = pg < 0 ? -1 : local[pg];
                if (pl >= 0 && liveOut[pl] != 0) found.add(new DestructionPoint(block, firstNonPhi(insts), objRef));
            }
            out.addAll(found);
        }

        private static Instruction firstNonPhi(List<Instruction> insts) {
            for (Instruction inst : insts) {
                if (inst.getOpcode() != Opcode.PHI) return inst;
            }
            return null;
        }

        private int[] subtree(int rootBlock) {
            int[] out = new int[16];
            int size = 0;
            int[] stack = new int[16];
            int top = 0;
            stack[top++] = rootBlock;
            while (top > 0) {
                int b = stack[--top];
                if (size == out.length) out = Arrays.copyOf(out, size << 1);
                out[size++] = b;
                if (size > MAX_REGION) return null;
                int c = dom.childCount(b);
                for (int k = 0; k < c; k++) {
                    if (top == stack.length) stack = Arrays.copyOf(stack, top << 1);
                    stack[top++] = dom.childAt(b, k);
                }
            }
            Arrays.sort(out, 0, size);
            return Arrays.copyOf(out, size);
        }
    }

    private static final class CallShapes {
        static boolean isCall(Opcode op) {
            return op == Opcode.CALL || op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL
                || op == Opcode.STATIC_CALL || op == Opcode.SPECIAL_CALL;
        }

        static int argCount(Instruction inst) {
            int n = inst.getOperands().size();
            return switch (inst.getOpcode()) {
                case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL -> n == 0 ? 0 : (n >= 2 ? n - 1 : 1);
                default -> Math.max(0, n - 1);
            };
        }

        static Value arg(Instruction inst, int j) {
            List<Value> ops = inst.getOperands();
            return switch (inst.getOpcode()) {
                case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL -> j == 0 ? ops.getFirst() : ops.get(j + 1);
                default -> ops.get(j + 1);
            };
        }
    }
}
