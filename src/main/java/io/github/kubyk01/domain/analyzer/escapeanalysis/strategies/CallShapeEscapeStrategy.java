package io.github.kubyk01.domain.analyzer.escapeanalysis.strategies;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult.CallShape;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import io.github.kubyk01.util.ScratchIdentityMap;
import org.objectweb.asm.Opcodes;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;

public final class CallShapeEscapeStrategy implements EscapeAnalysisStrategies {

    private static final int[] NONE = new int[0];
    private static final Target UNKNOWN = new Target(NONE, true);

    private record Target(int[] functions, boolean unknown) {}

    private record Site(Instruction inst, Target target, boolean exact) {}

    private static final class Scratch {
        final ScratchIdentityMap<Value, Long> bits = new ScratchIdentityMap<>();
        final ScratchIdentityMap<Value, Boolean> derived = new ScratchIdentityMap<>();
        final ScratchIdentityMap<Instruction, Target> targets = new ScratchIdentityMap<>();
        final Map<Integer, Long> slots = new HashMap<>();
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public EscapeAnalysisResult analyze(Module module, AliasAnalysisResult aliasResult,
                                        Map<String, EscapeSummary> summaries,
                                        DependencyResolver resolver, Scheduler scheduler) {
        return new Solver(module, resolver, scheduler).solve();
    }

    static boolean isCall(Opcode op) {
        return op == Opcode.CALL || op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL
            || op == Opcode.STATIC_CALL || op == Opcode.SPECIAL_CALL;
    }

    static boolean isDispatch(Opcode op) {
        return op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL;
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

    static boolean isReference(Value v) {
        if (v == null || v.getType() == null) return false;
        Type t = v.getType();
        return t.isReference() || t.isArray() || t.isNull() || t.isUnknown();
    }

    static boolean isNullConstant(Value v) {
        return v instanceof Constant c && c.getValue() == null;
    }

    private static final class Solver {
        private final Module module;
        private final DependencyResolver resolver;
        private final Scheduler scheduler;
        private final List<Function> bodies = new ArrayList<>();
        private final Map<Function, Integer> index = new IdentityHashMap<>();
        private final Map<String, Target> directTargets = new ConcurrentHashMap<>();
        private final Map<String, Target> dispatchTargets = new ConcurrentHashMap<>();
        private final Map<String, Integer> implCache = new ConcurrentHashMap<>();
        private AtomicReferenceArray<Site[]> sites;
        private AtomicLongArray retained;
        private AtomicLongArray returned;
        private AtomicLongArray fresh;
        private int[][] callers;

        Solver(Module module, DependencyResolver resolver, Scheduler scheduler) {
            this.module = module;
            this.resolver = resolver;
            this.scheduler = scheduler;
        }

        EscapeAnalysisResult solve() {
            List<? extends Function> jnLoop1 = module.getFunctions();
            for (int jnIdx1 = 0; jnIdx1 < jnLoop1.size(); jnIdx1++) {
                Function f = jnLoop1.get(jnIdx1);
                if (f.getEntryBlock() == null || index.containsKey(f)) continue;
                index.put(f, bodies.size());
                bodies.add(f);
            }
            int n = bodies.size();
            sites = new AtomicReferenceArray<>(n);
            retained = new AtomicLongArray(n);
            returned = new AtomicLongArray(n);
            fresh = new AtomicLongArray(n);
            for (int i = 0; i < n; i++) fresh.set(i, 1L);

            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i;
            parallel(all, i -> {
                sites.set(i, collectSites(bodies.get(i)));
                return false;
            });
            buildCallers(n);

            int[] dirty = all;
            while (dirty.length > 0) dirty = callersOf(parallel(dirty, this::updateMasks));
            dirty = all;
            while (dirty.length > 0) dirty = callersOf(parallel(dirty, this::updateFresh));

            EscapeAnalysisResult result = new EscapeAnalysisResult();
            if (System.getenv("JN_OWN_DEBUG") != null) {
                for (int i = 0; i < n; i++) {
                    String nm = bodies.get(i).getName();
                    if (nm.contains("Own_") || nm.equals("fn_java_lang_Object__init____V")) {
                        System.out.println("DBG fn " + nm + " retained=" + Long.toHexString(retained.get(i))
                            + " returned=" + Long.toHexString(returned.get(i)) + " fresh=" + fresh.get(i));
                    }
                }
                directTargets.forEach((c, t) -> { if (c.startsWith("Own") || c.startsWith("java/lang/Object.<init>")) System.out.println("DBG direct " + c + " unknown=" + t.unknown() + " fns=" + t.functions().length); });
            }
            directTargets.forEach((callee, t) -> result.putCallShape(false, callee, shapeOf(t)));
            dispatchTargets.forEach((callee, t) -> result.putCallShape(true, callee, shapeOf(t)));
            int exact = 0;
            for (int i = 0; i < n; i++) {
                for (Site s : sites.get(i)) {
                    if (!s.exact()) continue;
                    result.putSiteShape(s.inst(), shapeOf(s.target()));
                    exact++;
                }
            }
            System.out.println("Escape: " + exact + " dispatch call(s) narrowed by receiver points-to");
            int freshCount = 0;
            for (int i = 0; i < n; i++) if (fresh.get(i) != 0 && returnsReference(bodies.get(i))) freshCount++;
            System.out.println("Escape: call shapes for " + (directTargets.size() + dispatchTargets.size())
                + " callee(s), " + freshCount + " fresh-returning function(s)");
            return result;
        }

        private interface Step {
            boolean run(int i);
        }

        private int[] parallel(int[] work, Step step) {
            List<Integer> changed = LlvmUtil.awaitFlux(Flux.range(0, work.length)
                .parallel()
                .runOn(scheduler)
                .filter(k -> step.run(work[k]))
                .map(k -> work[k])
                .sequential());
            if (changed == null) return NONE;
            int[] out = new int[changed.size()];
            for (int k = 0; k < out.length; k++) out[k] = changed.get(k);
            return out;
        }

        private void buildCallers(int n) {
            int[] counts = new int[n];
            for (int i = 0; i < n; i++) {
                for (Site s : sites.get(i)) for (int g : s.target().functions()) counts[g]++;
            }
            callers = new int[n][];
            for (int g = 0; g < n; g++) callers[g] = counts[g] == 0 ? NONE : new int[counts[g]];
            int[] fill = new int[n];
            for (int i = 0; i < n; i++) {
                for (Site s : sites.get(i)) for (int g : s.target().functions()) callers[g][fill[g]++] = i;
            }
        }

        private int[] callersOf(int[] changed) {
            Set<Integer> next = new LinkedHashSet<>();
            for (int g : changed) for (int c : callers[g]) next.add(c);
            int[] out = new int[next.size()];
            int k = 0;
            for (int c : next) out[k++] = c;
            return out;
        }

        private Site[] collectSites(Function f) {
            List<Site> out = new ArrayList<>();
            for (int blockIdx1 = 0; blockIdx1 < f.getBlocks().size(); blockIdx1++) { BasicBlock block = f.getBlocks().get(blockIdx1);
                for (int instIdx2 = 0; instIdx2 < block.getInstructions().size(); instIdx2++) { Instruction inst = block.getInstructions().get(instIdx2);
                    if (!isCall(inst.getOpcode())) continue;
                    Target t = targetOf(inst);
                    Target narrow = isDispatch(inst.getOpcode()) ? narrowed(inst, t) : t;
                    out.add(new Site(inst, narrow, narrow != t));
                }
            }
            return out.toArray(new Site[0]);
        }

        private Target targetOf(Instruction inst) {
            String callee = extractCalleeName(inst);
            if (callee == null) return UNKNOWN;
            if (isDispatch(inst.getOpcode())) {
                return dispatchTargets.computeIfAbsent(callee, this::dispatch);
            }
            return directTargets.computeIfAbsent(callee, this::direct);
        }

        private Target direct(String callee) {
            Function g = module.getFunction(canonicalCalleeKey(callee));
            Integer gi = g == null ? null : index.get(g);
            return gi == null ? UNKNOWN : new Target(new int[]{gi}, false);
        }

        private Target dispatch(String callee) {
            int paren = callee.indexOf('(');
            int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
            if (dot <= 0) return UNKNOWN;
            String owner = callee.substring(0, dot);
            String name = callee.substring(dot + 1, paren);
            String descriptor = callee.substring(paren);
            Set<String> types = new LinkedHashSet<>(resolver.getSubclasses(owner));
            types.add(owner);
            Set<Integer> found = new LinkedHashSet<>();
            for (String type : types) {
                ClassNode node = resolver.getClassNode(type);
                if (node == null || node.isInterface() || (node.getAccess() & Opcodes.ACC_ABSTRACT) != 0) continue;
                String[] holder = new String[1];
                MethodNode impl = resolver.findMethodInHierarchy(type, name, descriptor, holder);
                if (impl == null || holder[0] == null) return UNKNOWN;
                if (impl.isAbstract()) continue;
                Function g = module.getFunction(canonicalCalleeKey(holder[0] + "." + name + descriptor));
                if (g == null) continue;
                Integer gi = index.get(g);
                if (gi == null) return UNKNOWN;
                found.add(gi);
            }
            if (found.isEmpty()) return UNKNOWN;
            int[] fs = new int[found.size()];
            int k = 0;
            for (int g : found) fs[k++] = g;
            return new Target(fs, false);
        }

        private Target narrowed(Instruction inst, Target cha) {
            if (!cha.unknown() && cha.functions().length <= 1) return cha;
            String callee = extractCalleeName(inst);
            List<Value> ops = inst.getOperands();
            if (callee == null || ops.isEmpty()) return cha;
            Value recv = ops.getFirst();
            if (recv == null || !(recv.getPointsTo() instanceof PointsToSet pts)) return cha;
            if (pts.isEmpty() || pts.containsUnknown()) return cha;
            int paren = callee.indexOf('(');
            int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
            if (dot <= 0) return cha;
            String name = callee.substring(dot + 1, paren);
            String descriptor = callee.substring(paren);
            int[] allowed = cha.functions().clone();
            Arrays.sort(allowed);
            int[] found = new int[Math.max(allowed.length, 4)];
            int k = 0;
            Set<String> seen = new HashSet<>();
            for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) {
                Type t = AllocationSite.byId(pts.id(c)).getType();
                String cls = t == null ? null : t.isArray() ? "java/lang/Object" : t.isReference() ? t.getClassName() : null;
                if (cls == null || cls.isEmpty()) return cha;
                if (!seen.add(cls)) continue;
                int g = implIndex(cls, name, descriptor);
                if (g < 0 || (!cha.unknown() && Arrays.binarySearch(allowed, g) < 0)) return cha;
                boolean dup = false;
                for (int q = 0; q < k; q++) {
                    if (found[q] == g) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;
                if (k == found.length) found = Arrays.copyOf(found, k << 1);
                found[k++] = g;
            }
            if (k == 0 || (!cha.unknown() && k >= allowed.length)) return cha;
            return new Target(Arrays.copyOf(found, k), false);
        }

        private int implIndex(String cls, String name, String descriptor) {
            String key = cls + '.' + name + descriptor;
            Integer cached = implCache.get(key);
            if (cached != null) return cached;
            int r = -1;
            ClassNode node = resolver.getClassNode(cls);
            if (node != null && !node.isInterface() && (node.getAccess() & Opcodes.ACC_ABSTRACT) == 0) {
                String[] holder = new String[1];
                MethodNode impl = resolver.findMethodInHierarchy(cls, name, descriptor, holder);
                if (impl != null && holder[0] != null && !impl.isAbstract()) {
                    Function g = module.getFunction(canonicalCalleeKey(holder[0] + "." + name + descriptor));
                    Integer gi = g == null ? null : index.get(g);
                    if (gi != null) r = gi;
                }
            }
            implCache.putIfAbsent(key, r);
            return r;
        }

        private boolean retains(Target t, int j) {
            if (t.unknown()) return true;
            for (int g : t.functions()) if (CallShape.has(retained.get(g), j)) return true;
            return false;
        }

        private boolean returnsArg(Target t, int j) {
            if (t.unknown()) return true;
            for (int g : t.functions()) if (CallShape.has(returned.get(g), j)) return true;
            return false;
        }

        private boolean freshTarget(Target t) {
            if (t.unknown() || t.functions().length == 0) return false;
            for (int g : t.functions()) if (fresh.get(g) == 0) return false;
            return true;
        }

        private CallShape shapeOf(Target t) {
            if (t.unknown()) return new CallShape(-1L, -1L, false);
            long r = 0;
            long ret = 0;
            for (int g : t.functions()) {
                r |= retained.get(g);
                ret |= returned.get(g);
            }
            return new CallShape(r, ret, freshTarget(t));
        }

        private boolean updateMasks(int i) {
            Function f = bodies.get(i);
            Scratch scratch = SCRATCH.get();
            ScratchIdentityMap<Value, Long> bits = scratch.bits;
            bits.clear();
            for (int pIdx3 = 0; pIdx3 < f.getParameters().size(); pIdx3++) { Parameter p = f.getParameters().get(pIdx3);
                if (p.getOrdinal() >= 0) bits.put(p, CallShape.bit(p.getOrdinal()));
            }
            Map<Integer, Long> slots = scratch.slots;
            slots.clear();
            ScratchIdentityMap<Instruction, Target> targets = scratch.targets;
            targets.clear();
            for (Site s : sites.get(i)) targets.put(s.inst(), s.target());

            boolean changed = true;
            while (changed) {
                changed = false;
                for (int blockIdx4 = 0; blockIdx4 < f.getBlocks().size(); blockIdx4++) { BasicBlock block = f.getBlocks().get(blockIdx4);
                    for (int instIdx5 = 0; instIdx5 < block.getInstructions().size(); instIdx5++) { Instruction inst = block.getInstructions().get(instIdx5);
                        Value res = inst.getResult();
                        long add = 0;
                        switch (inst.getOpcode()) {
                            case CAST, CHECKCAST, PHI, SELECT -> {
                                for (Value v : inst.getOperands()) add |= get(bits, v);
                            }
                            case STORE -> {
                                if (!inst.getOperands().isEmpty()) {
                                    long b = get(bits, inst.getOperands().getFirst());
                                    add = b;
                                    if (b != 0) {
                                        long old = slots.getOrDefault(inst.getLocalIndex(), 0L);
                                        if ((old | b) != old) {
                                            slots.put(inst.getLocalIndex(), old | b);
                                            changed = true;
                                        }
                                    }
                                }
                            }
                            case LOAD -> add = slots.getOrDefault(inst.getLocalIndex(), 0L);
                            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                                Target t = targets.get(inst);
                                int argc = argCount(inst);
                                for (int j = 0; j < argc; j++) {
                                    if (returnsArg(t, j)) add |= get(bits, arg(inst, j));
                                }
                            }
                            default -> { }
                        }
                        if (res != null && add != 0) {
                            long old = get(bits, res);
                            if ((old | add) != old) {
                                bits.put(res, old | add);
                                changed = true;
                            }
                        }
                    }
                }
            }

            long ret = 0;
            long keep = 0;
            for (int blockIdx6 = 0; blockIdx6 < f.getBlocks().size(); blockIdx6++) { BasicBlock block = f.getBlocks().get(blockIdx6);
                for (int instIdx7 = 0; instIdx7 < block.getInstructions().size(); instIdx7++) { Instruction inst = block.getInstructions().get(instIdx7);
                    List<Value> ops = inst.getOperands();
                    switch (inst.getOpcode()) {
                        case CAST, CHECKCAST, PHI, SELECT, STORE, LOAD, GET_FIELD, GET_STATIC, ARRAYLENGTH,
                             INSTANCEOF, EQ, NE, LT, LE, GT, GE, MONITOR_ENTER, MONITOR_EXIT, NOP,
                             NEW, NEW_ARRAY, MULTI_NEW_ARRAY, ALOAD -> { }
                        case PUT_FIELD, ASTORE -> {
                            if (ops.size() > 2) keep |= get(bits, ops.get(2));
                        }
                        case PUT_STATIC -> {
                            for (Value v : ops) keep |= get(bits, v);
                        }
                        case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                            Target t = targets.get(inst);
                            int argc = argCount(inst);
                            for (int j = 0; j < argc; j++) {
                                if (retains(t, j)) keep |= get(bits, arg(inst, j));
                            }
                        }
                        default -> {
                            for (Value v : ops) keep |= get(bits, v);
                        }
                    }
                }
                Terminator term = block.getTerminator();
                if (term instanceof ReturnTerminator rt) ret |= get(bits, rt.getValue());
                else if (term instanceof ThrowTerminator tt) keep |= get(bits, tt.getException());
            }
            boolean grew = false;
            long oldKeep = retained.get(i);
            if ((oldKeep | keep) != oldKeep) {
                retained.set(i, oldKeep | keep);
                grew = true;
            }
            long oldRet = returned.get(i);
            if ((oldRet | ret) != oldRet) {
                returned.set(i, oldRet | ret);
                grew = true;
            }
            return grew;
        }

        private static long get(ScratchIdentityMap<Value, Long> bits, Value v) {
            if (v == null) return 0;
            Long b = bits.get(v);
            return b == null ? 0 : b;
        }

        private static boolean loadsSlot(Function f, int slot) {
            for (int blockIdx8 = 0; blockIdx8 < f.getBlocks().size(); blockIdx8++) { BasicBlock block = f.getBlocks().get(blockIdx8);
                for (int instIdx9 = 0; instIdx9 < block.getInstructions().size(); instIdx9++) { Instruction inst = block.getInstructions().get(instIdx9);
                    if (inst.getOpcode() == Opcode.LOAD && inst.getLocalIndex() == slot) return true;
                }
            }
            return false;
        }

        private static boolean returnsReference(Function f) {
            Type t = f.getReturnType();
            return t != null && (t.isReference() || t.isArray());
        }

        private boolean updateFresh(int i) {
            if (fresh.get(i) == 0) return false;
            Function f = bodies.get(i);
            if (!returnsReference(f) || !computeFresh(i, f)) {
                fresh.set(i, 0);
                return true;
            }
            return false;
        }

        private boolean computeFresh(int i, Function f) {
            Scratch scratch = SCRATCH.get();
            ScratchIdentityMap<Instruction, Target> targets = scratch.targets;
            targets.clear();
            for (Site s : sites.get(i)) targets.put(s.inst(), s.target());
            ScratchIdentityMap<Value, Boolean> derived = scratch.derived;
            derived.clear();
            for (int blockIdx10 = 0; blockIdx10 < f.getBlocks().size(); blockIdx10++) { BasicBlock block = f.getBlocks().get(blockIdx10);
                for (int instIdx11 = 0; instIdx11 < block.getInstructions().size(); instIdx11++) { Instruction inst = block.getInstructions().get(instIdx11);
                    Value res = inst.getResult();
                    if (res == null) continue;
                    switch (inst.getOpcode()) {
                        case NEW, NEW_ARRAY, MULTI_NEW_ARRAY, CAST, CHECKCAST, PHI, SELECT -> derived.put(res, Boolean.TRUE);
                        case STORE -> {
                            if (!loadsSlot(f, inst.getLocalIndex())) derived.put(res, Boolean.TRUE);
                        }
                        case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                            if (freshTarget(targets.get(inst))) derived.put(res, Boolean.TRUE);
                        }
                        default -> { }
                    }
                }
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int blockIdx12 = 0; blockIdx12 < f.getBlocks().size(); blockIdx12++) { BasicBlock block = f.getBlocks().get(blockIdx12);
                    for (int instIdx13 = 0; instIdx13 < block.getInstructions().size(); instIdx13++) { Instruction inst = block.getInstructions().get(instIdx13);
                        Value res = inst.getResult();
                        if (!isDerived(derived, res)) continue;
                        Opcode op = inst.getOpcode();
                        boolean ok = switch (op) {
                            case CAST, CHECKCAST, STORE -> isDerived(derived, inst.getOperands().getFirst());
                            case PHI, SELECT -> {
                                boolean all = true;
                                for (int vIdx14 = 0; vIdx14 < inst.getOperands().size(); vIdx14++) { Value v = inst.getOperands().get(vIdx14);
                                    if (!isReference(v) || isNullConstant(v)) continue;
                                    if (!isDerived(derived, v)) {
                                        all = false;
                                        break;
                                    }
                                }
                                yield all;
                            }
                            default -> true;
                        };
                        if (!ok) {
                            derived.put(res, Boolean.FALSE);
                            changed = true;
                        }
                    }
                }
            }

            for (int blockIdx15 = 0; blockIdx15 < f.getBlocks().size(); blockIdx15++) { BasicBlock block = f.getBlocks().get(blockIdx15);
                for (int instIdx16 = 0; instIdx16 < block.getInstructions().size(); instIdx16++) { Instruction inst = block.getInstructions().get(instIdx16);
                    List<Value> ops = inst.getOperands();
                    Opcode op = inst.getOpcode();
                    for (int k = 0; k < ops.size(); k++) {
                        Value v = ops.get(k);
                        if (!isDerived(derived, v)) continue;
                        if (!usageKeepsFresh(inst, op, k, v, targets, derived)) return false;
                    }
                }
                Terminator term = block.getTerminator();
                if (term instanceof ReturnTerminator rt) {
                    Value v = rt.getValue();
                    if (v != null && !isNullConstant(v) && !isDerived(derived, v)) return false;
                } else if (term instanceof ThrowTerminator tt) {
                    if (isDerived(derived, tt.getException())) return false;
                }
            }
            return true;
        }

        private static boolean isDerived(ScratchIdentityMap<Value, Boolean> derived, Value v) {
            return v != null && derived.get(v) == Boolean.TRUE;
        }

        private boolean usageKeepsFresh(Instruction inst, Opcode op, int k, Value v,
                                        ScratchIdentityMap<Instruction, Target> targets,
                                        ScratchIdentityMap<Value, Boolean> derived) {
            return switch (op) {
                case GET_FIELD, ALOAD, ARRAYLENGTH, INSTANCEOF, EQ, NE, MONITOR_ENTER, MONITOR_EXIT -> true;
                case PUT_FIELD, ASTORE -> k == 0;
                case CAST, CHECKCAST, PHI, SELECT, STORE -> inst.getResult() != null && isDerived(derived, inst.getResult());
                case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                    Target t = targets.get(inst);
                    int argc = argCount(inst);
                    for (int j = 0; j < argc; j++) {
                        if (arg(inst, j) != v) continue;
                        if (retains(t, j)) yield false;
                        Value res = inst.getResult();
                        if (returnsArg(t, j) && isReference(res) && !isDerived(derived, res)) yield false;
                    }
                    yield true;
                }
                default -> false;
            };
        }
    }
}
