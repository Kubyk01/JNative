package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FieldCell;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.analyzer.lifetime.RefCountPlan;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import org.objectweb.asm.Opcodes;

import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.kubyk01.util.LlvmUtil.extractFieldName;

final class RefCountPlanner {

    static final String WEAK_OWNER = "java/lang/ref/Reference";
    static final String WEAK_FIELD = "referent";

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult alias;
    private final LifetimeAnalysisResult lifetime;
    private final RefCountPlan plan = new RefCountPlan();
    private final BitSet counted = new BitSet();
    private final Set<Function> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Instruction> weakStores = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> relatedTypes = new HashSet<>();

    RefCountPlanner(Module module, DependencyResolver resolver, AliasAnalysisResult alias, LifetimeAnalysisResult lifetime) {
        this.module = module;
        this.resolver = resolver;
        this.alias = alias;
        this.lifetime = lifetime;
    }

    RefCountPlan plan() {
        return plan;
    }

    boolean isPinned(Function f) {
        return pinned.contains(f);
    }

    boolean isWeakStore(Instruction inst) {
        return weakStores.contains(inst);
    }

    boolean isCountedType(Type t) {
        return t != null && t.isReference() && !t.isArray() && t.getClassName() != null && plan.isCounted(t.getClassName());
    }

    void compute() {
        long started = System.nanoTime();
        int n = AllocationSite.count();
        BitSet freed = freedSites();
        BitSet stored = heapStoredSites(n);
        Set<String> leaking = new HashSet<>();
        for (int id = 0; id < n; id++) {
            if (!stored.get(id) || freed.get(id)) continue;
            String cls = countableClass(AllocationSite.byId(id).getType());
            if (cls != null) leaking.add(cls);
        }
        plan.getCountedClasses().addAll(leaking);
        for (String cls : leaking) {
            String declarer = cls;
            Set<String> seen = new HashSet<>();
            for (String c = superOf(cls); c != null && seen.add(c); c = superOf(c)) {
                if (leaking.contains(c)) declarer = c;
            }
            plan.getCountDeclarers().add(declarer);
        }
        for (int id = 0; id < n; id++) {
            Type t = AllocationSite.byId(id).getType();
            if (t != null && t.isReference() && !t.isArray() && t.getClassName() != null && leaking.contains(t.getClassName())) {
                counted.set(id);
            }
        }
        if (counted.isEmpty()) {
            module.setRefCountPlan(plan);
            System.out.println("RefCount: no counted classes");
            return;
        }
        collectHolders(n);
        collectArrayTypes();
        int stores = markStores();
        module.setRefCountPlan(plan);
        System.out.println("RefCount: " + leaking.size() + " counted class(es), " + counted.cardinality() + " site(s), "
            + stores + " counted store(s), " + weakStores.size() + " weak store(s), " + pinned.size() + " function(s) with hold slots, "
            + plan.getCountedFields().size() + " holder class(es), " + plan.getCountedArrayTypes().size() + " holder array type(s) in "
            + (System.nanoTime() - started) / 1_000_000 + " ms");
    }

    private BitSet freedSites() {
        BitSet out = new BitSet();
        int n = AllocationSite.count();
        for (Map.Entry<AllocationSite, Set<DestructionPoint>> e : lifetime.getDestructionPoints().entrySet()) {
            int id = e.getKey().getId();
            if (id >= 0 && id < n && AllocationSite.byId(id) == e.getKey()) {
                out.set(id);
                continue;
            }
            for (DestructionPoint dp : e.getValue()) {
                PointsToSet pts = alias.getPointsTo(dp.getObjectRef());
                for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) out.set(pts.id(c));
            }
        }
        return out;
    }

    private BitSet heapStoredSites(int n) {
        PointsToGraph graph = alias.getGraph();
        BitSet out = new BitSet();
        for (int src = 0; src < n; src++) {
            for (FieldCell cell : graph.fieldCells(src)) addAll(out, cell.get());
        }
        for (PointsToSet pts : graph.getStaticFieldPointsToMap().values()) addAll(out, pts);
        out.clear(AllocationSite.UNKNOWN.getId());
        return out;
    }

    private static void addAll(BitSet out, PointsToSet pts) {
        for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) out.set(pts.id(c));
    }

    private String countableClass(Type t) {
        if (t == null || !t.isReference() || t.isArray() || t.isUnknown() || t.isNull()) return null;
        String cls = t.getClassName();
        if (cls == null || cls.isEmpty() || cls.charAt(0) == '[' || cls.startsWith("__Lambda_")) return null;
        if (LlvmUtil.isSystemClassName(cls)) return null;
        ClassNode node = resolver.getClassNode(cls);
        if (node == null || node.isExternal() || node.isInterface() || (node.getAccess() & Opcodes.ACC_ABSTRACT) != 0) return null;
        for (String c = superOf(cls); c != null; c = superOf(c)) {
            ClassNode sup = resolver.getClassNode(c);
            if (sup == null || sup.isExternal()) return null;
        }
        return cls;
    }

    private String superOf(String cls) {
        ClassNode node = resolver.getClassNode(cls);
        if (node == null) return null;
        String sup = node.getSuperName();
        return sup == null || sup.equals("java/lang/Object") || sup.equals(cls) ? null : sup;
    }

    private boolean maybeCountedType(Type t) {
        if (t == null || t.isUnknown()) return true;
        if (t.isNull() || t.isArray() || !t.isReference()) return false;
        String cls = t.getClassName();
        if (cls == null || cls.startsWith("[")) return cls == null;
        return relatedTypes.contains(cls) || resolver.getClassNode(cls) == null;
    }

    private boolean maybeCounted(Value v) {
        if (v == null) return false;
        PointsToSet pts = alias.getPointsTo(v);
        if (holdsCounted(pts)) return true;
        return (pts.isEmpty() || pts.containsUnknown()) && maybeCountedType(v.getType());
    }

    private boolean maybeCountedContents(PointsToSet pts, Type declared) {
        if (holdsCounted(pts)) return true;
        return (pts == null || pts.isEmpty() || pts.containsUnknown()) && maybeCountedType(declared);
    }

    private Type fieldType(String raw) {
        if (raw == null) return null;
        int dot = raw.lastIndexOf('.');
        if (dot <= 0) return null;
        FieldNode f = resolver.getField(raw.substring(0, dot), raw.substring(dot + 1));
        return f == null ? null : f.getType();
    }

    private boolean holdsCounted(PointsToSet pts) {
        if (pts == null) return false;
        for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) {
            if (counted.get(pts.id(c))) return true;
        }
        return false;
    }

    private void collectArrayTypes() {
        Set<String> seen = new HashSet<>();
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>(plan.getCountedClasses());
        work.add("java/lang/Object");
        while (!work.isEmpty()) {
            String c = work.poll();
            if (c == null || !seen.add(c)) continue;
            relatedTypes.add(c);
            plan.getCountedArrayTypes().add("[L" + c + ";");
            ClassNode node = resolver.getClassNode(c);
            if (node == null) continue;
            if (node.getSuperName() != null) work.add(node.getSuperName());
            if (node.getInterfaces() != null) work.addAll(node.getInterfaces());
        }
    }

    private void collectHolders(int n) {
        PointsToGraph graph = alias.getGraph();
        for (int src = 0; src < n; src++) {
            Type t = AllocationSite.byId(src).getType();
            if (t == null || t.isUnknown() || t.isNull()) continue;
            boolean arrayClass = t.isReference() && t.getClassName() != null && t.getClassName().startsWith("[");
            for (FieldCell cell : graph.fieldCells(src)) {
                if (!holdsCounted(cell.get())) continue;
                if (t.isArray() || arrayClass) {
                    if (PointsToGraph.ARRAY_ELEMENT_FIELD.equals(cell.field())) {
                        String desc = arrayClass ? t.getClassName() : arrayDescriptor(t);
                        if (desc != null && desc.length() > 1 && (desc.charAt(1) == 'L' || desc.charAt(1) == '[')) {
                            plan.getCountedArrayTypes().add(desc);
                        }
                    }
                    continue;
                }
                if (!t.isReference() || t.getClassName() == null || t.getClassName().startsWith("__Lambda_")) continue;
                String key = instanceReferenceField(cell.field());
                if (key != null && declaredIn(t.getClassName(), key.substring(0, key.lastIndexOf('.')))) {
                    plan.getCountedFields().computeIfAbsent(t.getClassName(), k -> new HashSet<>()).add(key);
                }
            }
        }
    }

    private boolean declaredIn(String cls, String owner) {
        Set<String> seen = new HashSet<>();
        for (String c = cls; c != null && seen.add(c); ) {
            if (c.equals(owner)) return true;
            ClassNode node = resolver.getClassNode(c);
            c = node == null ? null : node.getSuperName();
        }
        return false;
    }

    private String instanceReferenceField(String raw) {
        if (raw == null) return null;
        int dot = raw.lastIndexOf('.');
        if (dot <= 0) return null;
        FieldNode f = resolver.getField(raw.substring(0, dot), raw.substring(dot + 1));
        if (f == null || f.getOwner() == null || (f.getAccess() & Opcodes.ACC_STATIC) != 0) return null;
        String d = f.getDescriptor();
        if (d == null || d.charAt(0) != 'L') return null;
        if (WEAK_OWNER.equals(f.getOwner()) && WEAK_FIELD.equals(f.getName())) return null;
        return f.getOwner() + "." + f.getName();
    }

    static String arrayDescriptor(Type t) {
        if (t == null || !t.isArray()) return null;
        Type e = t.getElementType();
        if (e == null) return null;
        if (e.isArray()) {
            String inner = arrayDescriptor(e);
            return inner == null ? null : "[" + inner;
        }
        if (e.isReference() && e.getClassName() != null) return "[L" + e.getClassName() + ";";
        return null;
    }

    private int markStores() {
        int marked = 0;
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() == null) continue;
            int next = 0;
            Map<Integer, Integer> localHolds = new java.util.HashMap<>();
            for (BasicBlock block : f.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    Value res = inst.getResult();
                    if (inst.getOpcode() == Opcode.STORE) {
                        Value stored = inst.getOperands().isEmpty() ? null : inst.getOperands().getFirst();
                        if (inst.getLocalIndex() >= 0 && isReference(stored) && maybeCounted(stored)) {
                            Integer k = localHolds.get(inst.getLocalIndex());
                            if (k == null) {
                                k = next++;
                                localHolds.put(inst.getLocalIndex(), k);
                            }
                            inst.setRcHold(k);
                        }
                    } else if (isReference(res) && maybeCounted(res)) {
                        inst.setRcHold(next++);
                    }
                    if (markStore(inst)) marked++;
                }
            }
            f.setRcHolds(next);
            if (next > 0) pinned.add(f);
        }
        return marked;
    }

    private boolean markStore(Instruction inst) {
        List<Value> ops = inst.getOperands();
        switch (inst.getOpcode()) {
            case PUT_FIELD -> {
                if (ops.size() < 3 || !isReference(ops.get(2))) return false;
                Value field = ops.get(1);
                if (field instanceof Constant c && c.getType() == Type.INT) {
                    if (!maybeCounted(ops.get(2))) return false;
                    inst.setRcStore(true);
                    return true;
                }
                String raw = extractFieldName(inst);
                if (isWeakReferent(raw)) {
                    weakStores.add(inst);
                    return false;
                }
                PointsToSet old = alias.getFieldPointsToForSites(alias.getPointsTo(ops.getFirst()), raw);
                if (!maybeCounted(ops.get(2)) && !maybeCountedContents(old, fieldType(raw))) return false;
                inst.setRcStore(true);
                return true;
            }
            case ASTORE -> {
                if (ops.size() < 3 || !isReference(ops.get(2))) return false;
                PointsToSet old = alias.getFieldPointsToForSites(alias.getPointsTo(ops.getFirst()), PointsToGraph.ARRAY_ELEMENT_FIELD);
                Type arr = ops.getFirst().getType();
                Type element = arr != null && arr.isArray() ? arr.getElementType() : null;
                if (!maybeCounted(ops.get(2)) && !maybeCountedContents(old, element)) return false;
                inst.setRcStore(true);
                return true;
            }
            case PUT_STATIC -> {
                if (ops.size() < 2 || !isReference(ops.get(1))) return false;
                PointsToSet old = alias.getStaticFieldPointsTo(extractFieldName(inst));
                if (!maybeCounted(ops.get(1)) && !maybeCountedContents(old, fieldType(extractFieldName(inst)))) return false;
                inst.setRcStore(true);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean isWeakReferent(String raw) {
        if (raw == null) return false;
        int dot = raw.lastIndexOf('.');
        if (dot <= 0 || !WEAK_FIELD.equals(raw.substring(dot + 1))) return false;
        FieldNode f = resolver.getField(raw.substring(0, dot), raw.substring(dot + 1));
        return f != null && WEAK_OWNER.equals(f.getOwner());
    }

    private static boolean isReference(Value v) {
        if (v == null || v.getType() == null) return false;
        Type t = v.getType();
        return t.isReference() || t.isArray() || t.isNull() || t.isUnknown();
    }
}
