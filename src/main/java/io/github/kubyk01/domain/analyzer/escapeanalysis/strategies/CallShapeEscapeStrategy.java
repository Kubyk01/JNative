package io.github.kubyk01.domain.analyzer.escapeanalysis.strategies;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult.CallShape;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeSummary;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ResolvedCall;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import io.github.kubyk01.util.ScratchIdentityMap;
import org.objectweb.asm.Opcodes;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayDeque;
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
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;

public final class CallShapeEscapeStrategy implements EscapeAnalysisStrategies {

    private static final int[] NONE = new int[0];
    private static final Target UNKNOWN = new Target(NONE, true);
    private static final Target PURE_NATIVE = new Target(NONE, false);
    private static final Target FRESH_NATIVE = new Target(NONE, false);
    private static final Set<String> FRESH_NATIVES = Set.of(
        "java/lang/reflect/Array.newArray(Ljava/lang/Class;I)Ljava/lang/Object;",
        "java/lang/reflect/Array.multiNewArray(Ljava/lang/Class;[I)Ljava/lang/Object;",
        "java/lang/Object.clone()Ljava/lang/Object;");
    private static final Set<String> PURE_NATIVES = Set.of(
        "java/lang/System.arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V",
        "java/lang/Object.hashCode()I",
        "java/lang/Object.getClass()Ljava/lang/Class;",
        "java/lang/Class.isArray()Z",
        "java/lang/Class.isPrimitive()Z",
        "java/lang/Class.isInterface()Z",
        "java/lang/Class.isHidden()Z",
        "java/lang/Class.getModifiers()I",
        "java/lang/Class.isInstance(Ljava/lang/Object;)Z",
        "java/lang/Class.isAssignableFrom(Ljava/lang/Class;)Z",
        "java/lang/System.identityHashCode(Ljava/lang/Object;)I");

    private record Target(int[] functions, boolean unknown) {}

    private record Site(Instruction inst, Target target, boolean exact) {}

    private static final class Scratch {
        final ScratchIdentityMap<Value, Long> bits = new ScratchIdentityMap<>();
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

    static boolean isStringLiteral(Value v) {
        if (!(v instanceof Constant c) || !(c.getValue() instanceof String)) return false;
        Type t = c.getType();
        return t != null && t.isReference() && "java/lang/String".equals(t.getClassName());
    }

    public static boolean concatReadsOnly(Instruction inst, Value v) {
        if (inst.getOpcode() != Opcode.INVOKEDYNAMIC || v == null) return false;
        if (!(inst.getInvokedynamicData() instanceof InvokeDynamicInfo info)) return false;
        ResolvedCall call = info.resolvedCall();
        if (call == null || call.getType() != ResolvedCall.Type.CONCAT) return false;
        Type t = v.getType();
        return t != null && t.isReference() && "java/lang/String".equals(t.getClassName());
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
            int refined = refineByReturnClasses();
            if (refined > 0) {
                for (int i = 0; i < n; i++) {
                    retained.set(i, 0L);
                    returned.set(i, 0L);
                    fresh.set(i, 1L);
                }
                buildCallers(n);
                dirty = all;
                while (dirty.length > 0) dirty = callersOf(parallel(dirty, this::updateMasks));
                dirty = all;
                while (dirty.length > 0) dirty = callersOf(parallel(dirty, this::updateFresh));
            }
            System.out.println("Escape: " + refined + " dispatch call(s) narrowed by allocated return classes");

            EscapeAnalysisResult result = new EscapeAnalysisResult();
            String dbgFilter = System.getenv("JN_OWN_DEBUG");
            if (dbgFilter != null) {
                for (int i = 0; i < n; i++) {
                    String nm = bodies.get(i).getName();
                    if (!nm.contains(dbgFilter)) continue;
                    System.out.println("DBG fn " + nm + " retained=" + Long.toHexString(retained.get(i))
                        + " returned=" + Long.toHexString(returned.get(i)) + " fresh=" + fresh.get(i));
                    for (Site s : sites.get(i)) {
                        CallShape sh = shapeOf(s.target());
                        Value r0 = s.inst().getOperands().isEmpty() ? null : s.inst().getOperands().getFirst();
                        Object rp = r0 == null ? null : r0.getPointsTo();
                        String rdesc = rp instanceof PointsToSet ps
                            ? ("pts size=" + ps.size() + " unknown=" + ps.containsUnknown() + " first="
                                + (ps.isEmpty() ? "-" : AllocationSite.byId(ps.id(ps.cursor())).getType()))
                            : "pts none";
                        System.out.println("DBG     receiver " + rdesc);
                        System.out.println("DBG   call " + extractCalleeName(s.inst()) + " unknown=" + s.target().unknown()
                            + " targets=" + s.target().functions().length + " exact=" + s.exact()
                            + " retained=" + Long.toHexString(sh.retainedArgs()) + " returned=" + Long.toHexString(sh.returnedArgs())
                            + " fresh=" + sh.freshReturn());
                    }
                }
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
                    if (isArrayClone(inst)) {
                        out.add(new Site(inst, FRESH_NATIVE, true));
                        continue;
                    }
                    Target narrow = isDispatch(inst.getOpcode()) ? narrowed(inst, t) : t;
                    out.add(new Site(inst, narrow, narrow != t));
                }
            }
            return out.toArray(new Site[0]);
        }

        private static boolean isArrayClone(Instruction inst) {
            if (!isDispatch(inst.getOpcode()) || inst.getOperands().isEmpty()) return false;
            String callee = extractCalleeName(inst);
            if (callee == null || !callee.endsWith(".clone()Ljava/lang/Object;")) return false;
            if (callee.startsWith("[")) return true;
            if (!callee.equals("java/lang/Object.clone()Ljava/lang/Object;")) return false;
            Value recv = inst.getOperands().getFirst();
            return recv != null && recv.getType() != null && recv.getType().isArray();
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
            if (gi != null) return new Target(new int[]{gi}, false);
            Target nt = nativeTarget(callee);
            return nt != null ? nt : UNKNOWN;
        }

        private Target nativeTarget(String callee) {
            if (FRESH_NATIVES.contains(callee)) return FRESH_NATIVE;
            if (PURE_NATIVES.contains(callee)) return PURE_NATIVE;
            if (callee.startsWith("__jnative_fn_java_lang_System_arraycopy__")) return PURE_NATIVE;
            int paren = callee.indexOf('(');
            int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
            if (dot <= 0) return null;
            String owner = callee.substring(0, dot);
            String name = callee.substring(dot + 1, paren);
            String descriptor = callee.substring(paren);
            if (owner.startsWith("[") && name.equals("clone") && descriptor.equals("()Ljava/lang/Object;")) return FRESH_NATIVE;
            if (descriptor.indexOf('L') >= 0 || descriptor.indexOf('[') >= 0) return null;
            String[] holder = new String[1];
            MethodNode m = resolver.findMethodInHierarchy(owner, name, descriptor, holder);
            return m != null && m.isNative() && m.isStatic() ? PURE_NATIVE : null;
        }

        private Target dispatch(String callee) {
            int paren = callee.indexOf('(');
            int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
            if (dot <= 0) return UNKNOWN;
            String owner = callee.substring(0, dot);
            String name = callee.substring(dot + 1, paren);
            String descriptor = callee.substring(paren);
            if (owner.startsWith("[")) {
                Target nt = nativeTarget(callee);
                return nt != null ? nt : UNKNOWN;
            }
            Set<String> types = new LinkedHashSet<>(resolver.getSubclasses(owner));
            types.add(owner);
            Set<Integer> found = new LinkedHashSet<>();
            boolean sawPure = false;
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
                if (gi == null) {
                    if (nativeTarget(holder[0] + "." + name + descriptor) == PURE_NATIVE) {
                        sawPure = true;
                        continue;
                    }
                    return UNKNOWN;
                }
                found.add(gi);
            }
            if (found.isEmpty()) return sawPure ? PURE_NATIVE : UNKNOWN;
            int[] fs = new int[found.size()];
            int k = 0;
            for (int g : found) fs[k++] = g;
            return new Target(fs, false);
        }

        private Target narrowed(Instruction inst, Target cha) {
            if (!cha.unknown() && cha.functions().length <= 1) return cha;
            List<Value> ops = inst.getOperands();
            if (ops.isEmpty()) return cha;
            Value recv = ops.getFirst();
            if (recv == null || !(recv.getPointsTo() instanceof PointsToSet pts)) return cha;
            if (pts.isEmpty() || pts.containsUnknown()) return cha;
            Set<String> classes = new LinkedHashSet<>();
            for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) {
                Type t = AllocationSite.byId(pts.id(c)).getType();
                String cls = t == null ? null : t.isArray() ? "java/lang/Object" : t.isReference() ? t.getClassName() : null;
                if (cls == null || cls.isEmpty()) return cha;
                classes.add(cls);
            }
            return narrowTo(inst, cha, classes);
        }

        private Target narrowTo(Instruction inst, Target cha, Set<String> classes) {
            String callee = extractCalleeName(inst);
            if (callee == null) return cha;
            int paren = callee.indexOf('(');
            int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
            if (dot <= 0) return cha;
            String name = callee.substring(dot + 1, paren);
            String descriptor = callee.substring(paren);
            int[] allowed = cha.functions().clone();
            Arrays.sort(allowed);
            int[] found = new int[Math.max(allowed.length, 4)];
            int k = 0;
            for (String cls : classes) {
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

        private int refineByReturnClasses() {
            Map<Integer, Set<String>> memo = new HashMap<>();
            Set<Integer> failed = new HashSet<>();
            int refined = 0;
            for (int i = 0; i < bodies.size(); i++) {
                Site[] ss = sites.get(i);
                Map<Instruction, Target> local = null;
                for (int k = 0; k < ss.length; k++) {
                    Site s = ss[k];
                    if (s.exact() || !isDispatch(s.inst().getOpcode())) continue;
                    if (!s.target().unknown() && s.target().functions().length <= 1) continue;
                    List<Value> ops = s.inst().getOperands();
                    if (ops.isEmpty()) continue;
                    if (local == null) {
                        local = new IdentityHashMap<>();
                        for (int q = 0; q < ss.length; q++) local.put(ss[q].inst(), ss[q].target());
                    }
                    ArrayDeque<Value> work = new ArrayDeque<>();
                    work.push(ops.getFirst());
                    Set<String> classes = allocatedClasses(work, local, memo, failed);
                    if (classes == null || classes.isEmpty()) continue;
                    Target t = narrowTo(s.inst(), s.target(), classes);
                    if (t == s.target()) continue;
                    ss[k] = new Site(s.inst(), t, true);
                    local.put(s.inst(), t);
                    refined++;
                }
            }
            return refined;
        }

        private Map<String, List<Instruction>> fieldStores;
        private final Map<String, Set<String>> fieldClassMemo = new HashMap<>();
        private final Set<String> fieldClassBusy = new HashSet<>();
        private final Set<String> fieldClassFailed = new HashSet<>();

        private String canonicalField(String raw) {
            if (raw == null) return null;
            int dot = raw.lastIndexOf('.');
            if (dot <= 0) return null;
            FieldNode fn = resolver.getField(raw.substring(0, dot), raw.substring(dot + 1));
            if (fn == null || fn.getOwner() == null) return null;
            if ((fn.getAccess() & Opcodes.ACC_FINAL) == 0 || (fn.getAccess() & Opcodes.ACC_STATIC) != 0) return null;
            if (serializable(fn.getOwner())) return null;
            return fn.getOwner() + "." + fn.getName();
        }

        private boolean serializable(String cls) {
            ArrayDeque<String> work = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            work.add(cls);
            while (!work.isEmpty()) {
                String c = work.poll();
                if (c == null || !seen.add(c)) continue;
                if (c.equals("java/io/Serializable")) return true;
                ClassNode node = resolver.getClassNode(c);
                if (node == null) return true;
                if (node.getSuperName() != null) work.add(node.getSuperName());
                if (node.getInterfaces() != null) work.addAll(node.getInterfaces());
            }
            return false;
        }

        private Set<String> fieldClasses(String raw, Map<Integer, Set<String>> memo, Set<Integer> failed) {
            String key = canonicalField(raw);
            if (key == null) return null;
            Set<String> known = fieldClassMemo.get(key);
            if (known != null) return known;
            if (fieldClassFailed.contains(key) || !fieldClassBusy.add(key)) return null;
            if (fieldStores == null) {
                fieldStores = new HashMap<>();
                for (int b = 0; b < bodies.size(); b++) {
                    Function f = bodies.get(b);
                    for (int bi = 0; bi < f.getBlocks().size(); bi++) {
                        List<Instruction> insts = f.getBlocks().get(bi).getInstructions();
                        for (int ii = 0; ii < insts.size(); ii++) {
                            Instruction inst = insts.get(ii);
                            if (inst.getOpcode() != Opcode.PUT_FIELD || inst.getOperands().size() < 3) continue;
                            String k = canonicalField(extractFieldName(inst));
                            if (k != null) fieldStores.computeIfAbsent(k, x -> new ArrayList<>()).add(inst);
                        }
                    }
                }
            }
            Set<String> out = new LinkedHashSet<>();
            List<Instruction> stores = fieldStores.getOrDefault(key, List.of());
            for (int i = 0; i < stores.size() && out != null; i++) {
                Instruction put = stores.get(i);
                Integer g = index.get(put.getParent().getFunction());
                if (g == null) {
                    out = null;
                    break;
                }
                Map<Instruction, Target> local = new IdentityHashMap<>();
                Site[] ss = sites.get(g);
                for (int q = 0; q < ss.length; q++) local.put(ss[q].inst(), ss[q].target());
                ArrayDeque<Value> work = new ArrayDeque<>();
                work.push(put.getOperands().get(2));
                Set<String> c = allocatedClasses(work, local, memo, failed);
                if (c == null) out = null;
                else out.addAll(c);
            }
            fieldClassBusy.remove(key);
            if (out == null || out.isEmpty()) {
                fieldClassFailed.add(key);
                return null;
            }
            fieldClassMemo.put(key, out);
            return out;
        }

        private Set<String> returnClasses(int g, Map<Integer, Set<String>> memo, Set<Integer> failed) {
            Set<String> known = memo.get(g);
            if (known != null) return known;
            if (!failed.add(g)) return null;
            Function f = bodies.get(g);
            Map<Instruction, Target> local = new IdentityHashMap<>();
            Site[] ss = sites.get(g);
            for (int q = 0; q < ss.length; q++) local.put(ss[q].inst(), ss[q].target());
            ArrayDeque<Value> work = new ArrayDeque<>();
            for (int b = 0; b < f.getBlocks().size(); b++) {
                Terminator term = f.getBlocks().get(b).getTerminator();
                if (term instanceof ReturnTerminator rt && rt.getValue() != null) work.push(rt.getValue());
            }
            Set<String> out = allocatedClasses(work, local, memo, failed);
            if (out == null || out.isEmpty()) return null;
            failed.remove(g);
            memo.put(g, out);
            return out;
        }

        private Set<String> allocatedClasses(ArrayDeque<Value> work, Map<Instruction, Target> local,
                                             Map<Integer, Set<String>> memo, Set<Integer> failed) {
            Set<String> out = new LinkedHashSet<>();
            Set<Value> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            while (!work.isEmpty()) {
                Value v = work.pop();
                if (isNullConstant(v) || !seen.add(v)) continue;
                if (isStringLiteral(v)) {
                    out.add("java/lang/String");
                    continue;
                }
                if (!(v instanceof Temporary t) || t.getDefiningInstruction() == null) return null;
                Instruction def = t.getDefiningInstruction();
                List<Value> ops = def.getOperands();
                switch (def.getOpcode()) {
                    case NEW -> {
                        if (ops.isEmpty() || !(ops.getFirst() instanceof Constant c)
                            || c.getValue() == null || !c.getType().isReference()) return null;
                        String cls = c.getValue().toString();
                        if (cls.isEmpty() || cls.charAt(0) == '[') return null;
                        out.add(cls);
                    }
                    case NEW_ARRAY, MULTI_NEW_ARRAY -> out.add("java/lang/Object");
                    case GET_FIELD -> {
                        Set<String> fc = fieldClasses(extractFieldName(def), memo, failed);
                        if (fc == null) return null;
                        out.addAll(fc);
                    }
                    case CAST, CHECKCAST, STORE -> {
                        if (ops.isEmpty()) return null;
                        work.push(ops.getFirst());
                    }
                    case PHI, SELECT -> {
                        for (int k = 0; k < ops.size(); k++) {
                            Value op = ops.get(k);
                            if (isReference(op)) work.push(op);
                        }
                    }
                    case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                        Target target = local.get(def);
                        if (target == null || target.unknown() || target.functions().length == 0) return null;
                        for (int g : target.functions()) {
                            Set<String> rc = returnClasses(g, memo, failed);
                            if (rc == null) return null;
                            out.addAll(rc);
                        }
                    }
                    default -> {
                        return null;
                    }
                }
            }
            return out;
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
            if (t == FRESH_NATIVE) return true;
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
                if (p.getOrdinal() >= 0 && isReference(p)) bits.put(p, CallShape.bit(p.getOrdinal()));
            }
            Map<Integer, Long> slots = scratch.slots;
            slots.clear();
            for (int pIdx = 0; pIdx < f.getParameters().size(); pIdx++) {
                Parameter p = f.getParameters().get(pIdx);
                if (p.getOrdinal() >= 0 && isReference(p)) slots.merge(p.getIndex(), CallShape.bit(p.getOrdinal()), (a, b) -> a | b);
            }
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
                        case INVOKEDYNAMIC -> {
                            for (Value v : ops) if (!concatReadsOnly(inst, v)) keep |= get(bits, v);
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
            Map<Integer, List<Instruction>> stores = new HashMap<>();
            Map<Integer, List<Instruction>> loads = new HashMap<>();
            Map<Value, List<Instruction>> users = new IdentityHashMap<>();
            Set<Integer> paramSlots = new HashSet<>();
            for (int pIdx = 0; pIdx < f.getParameters().size(); pIdx++) {
                paramSlots.add(f.getParameters().get(pIdx).getIndex());
            }
            List<Value> returns = new ArrayList<>();
            List<Value> thrown = new ArrayList<>();
            for (int blockIdx = 0; blockIdx < f.getBlocks().size(); blockIdx++) {
                BasicBlock block = f.getBlocks().get(blockIdx);
                for (int instIdx = 0; instIdx < block.getInstructions().size(); instIdx++) {
                    Instruction inst = block.getInstructions().get(instIdx);
                    List<Value> ops = inst.getOperands();
                    int slot = inst.getLocalIndex();
                    if (inst.getOpcode() == Opcode.STORE && slot >= 0 && !ops.isEmpty() && isReference(ops.getFirst())) {
                        stores.computeIfAbsent(slot, k -> new ArrayList<>()).add(inst);
                    } else if (inst.getOpcode() == Opcode.LOAD && slot >= 0 && inst.getResult() != null) {
                        loads.computeIfAbsent(slot, k -> new ArrayList<>()).add(inst);
                    }
                    for (int k = 0; k < ops.size(); k++) {
                        Value v = ops.get(k);
                        if (v != null && !(v instanceof Constant)) users.computeIfAbsent(v, x -> new ArrayList<>()).add(inst);
                    }
                }
                Terminator term = block.getTerminator();
                if (term instanceof ReturnTerminator rt) {
                    Value v = rt.getValue();
                    if (v != null && !isNullConstant(v)) returns.add(v);
                } else if (term instanceof ThrowTerminator tt && tt.getException() != null) {
                    thrown.add(tt.getException());
                }
            }

            Set<Value> origin = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Integer> loadedSlots = new HashSet<>();
            ArrayDeque<Value> work = new ArrayDeque<>(returns);
            while (!work.isEmpty()) {
                Value v = work.pop();
                if (isNullConstant(v) || !origin.add(v)) continue;
                if (isStringLiteral(v)) continue;
                if (!(v instanceof Temporary t) || t.getDefiningInstruction() == null) {
                    return false;
                }
                Instruction def = t.getDefiningInstruction();
                List<Value> ops = def.getOperands();
                switch (def.getOpcode()) {
                    case NEW, NEW_ARRAY, MULTI_NEW_ARRAY -> { }
                    case CAST, CHECKCAST, STORE -> work.push(ops.getFirst());
                    case PHI, SELECT -> {
                        for (int k = 0; k < ops.size(); k++) {
                            Value op = ops.get(k);
                            if (isReference(op)) work.push(op);
                        }
                    }
                    case LOAD -> {
                        int slot = def.getLocalIndex();
                        List<Instruction> st = stores.get(slot);
                        if (slot < 0 || paramSlots.contains(slot) || st == null) {
                            return false;
                        }
                        if (loadedSlots.add(slot)) {
                            for (int k = 0; k < st.size(); k++) work.push(st.get(k).getOperands().getFirst());
                        }
                    }
                    case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                        Target target = targets.get(def);
                        if (!freshTarget(target)) {
                            return false;
                        }
                        int argc = argCount(def);
                        for (int j = 0; j < argc; j++) {
                            if (returnsArg(target, j)) work.push(arg(def, j));
                        }
                    }
                    default -> {
                        return false;
                    }
                }
            }

            Set<Value> alias = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            work = new ArrayDeque<>(origin);
            while (!work.isEmpty()) {
                Value v = work.pop();
                if (isNullConstant(v) || !alias.add(v)) continue;
                List<Instruction> us = users.get(v);
                if (us == null) continue;
                for (int u = 0; u < us.size(); u++) {
                    Instruction inst = us.get(u);
                    Value res = inst.getResult();
                    switch (inst.getOpcode()) {
                        case CAST, CHECKCAST, PHI, SELECT -> {
                            if (res != null) work.push(res);
                        }
                        case STORE -> {
                            if (res != null) work.push(res);
                            List<Instruction> ld = loads.get(inst.getLocalIndex());
                            if (ld != null && inst.getOperands().getFirst() == v) {
                                for (int k = 0; k < ld.size(); k++) work.push(ld.get(k).getResult());
                            }
                        }
                        case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                            Target target = targets.get(inst);
                            int argc = argCount(inst);
                            for (int j = 0; j < argc; j++) {
                                if (arg(inst, j) == v && returnsArg(target, j) && isReference(res)) work.push(res);
                            }
                        }
                        default -> { }
                    }
                }
            }

            for (Value v : alias) {
                List<Instruction> us = users.get(v);
                if (us == null) continue;
                for (int u = 0; u < us.size(); u++) {
                    Instruction inst = us.get(u);
                    List<Value> ops = inst.getOperands();
                    for (int k = 0; k < ops.size(); k++) {
                        if (ops.get(k) != v || keepsFresh(inst, k, v, targets)) continue;
                        return false;
                    }
                }
            }
            for (int k = 0; k < thrown.size(); k++) {
                if (alias.contains(thrown.get(k))) return false;
            }
            return true;
        }

        private boolean keepsFresh(Instruction inst, int k, Value v, ScratchIdentityMap<Instruction, Target> targets) {
            return switch (inst.getOpcode()) {
                case GET_FIELD, ALOAD, ARRAYLENGTH, INSTANCEOF, EQ, NE, MONITOR_ENTER, MONITOR_EXIT,
                     CAST, CHECKCAST, PHI, SELECT, STORE -> true;
                case PUT_FIELD, ASTORE -> k == 0;
                case INVOKEDYNAMIC -> concatReadsOnly(inst, v);
                case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                    Target target = targets.get(inst);
                    int argc = argCount(inst);
                    for (int j = 0; j < argc; j++) {
                        if (arg(inst, j) == v && retains(target, j)) yield false;
                    }
                    yield true;
                }
                default -> false;
            };
        }
    }
}
