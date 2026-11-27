package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.FieldCell;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToGraph;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.analyzer.lifetime.RefCountPlan;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import org.objectweb.asm.Opcodes;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.kubyk01.application.service.analyzer.ssa.TypeResolver.primitiveDescriptor;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;
import static io.github.kubyk01.util.LlvmUtil.lambdaCaptures;

final class RefCountPlanner {

    static final String WEAK_OWNER = "java/lang/ref/Reference";
    static final String WEAK_FIELD = "referent";

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult alias;
    private final LifetimeAnalysisResult lifetime;
    private final EscapeAnalysisResult escape;
    private final Scheduler scheduler;
    private final RefCountPlan plan = new RefCountPlan();
    private final BitSet counted = new BitSet();
    private final BitSet erasedArrayOwners = new BitSet();
    private boolean managesArrays;
    private final BitSet captured = new BitSet();
    private final BitSet weakTargets = new BitSet();
    private final Set<Function> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
    private int weakStores;
    private final Set<String> relatedTypes = new HashSet<>();
    private final BitSet birthDrop = new BitSet();
    private boolean countsClosures;

    RefCountPlanner(Module module, DependencyResolver resolver, AliasAnalysisResult alias, LifetimeAnalysisResult lifetime,
                    EscapeAnalysisResult escape, Scheduler scheduler) {
        this.module = module;
        this.scheduler = scheduler;
        this.resolver = resolver;
        this.alias = alias;
        this.lifetime = lifetime;
        this.escape = escape;
    }

    RefCountPlan plan() {
        return plan;
    }

    boolean isPinned(Function f) {
        return pinned.contains(f);
    }

    boolean isCountedType(Type t) {
        return t != null && t.isReference() && !t.isArray() && t.getClassName() != null && plan.isCounted(t.getClassName());
    }

    boolean isCapturedSite(AllocationSite site) {
        return captured.get(site.getId());
    }

    void compute() {
        long started = System.nanoTime();
        int n = AllocationSite.count();
        BitSet freed = freedSites();
        BitSet stored = heapStoredSites(n);
        collectCapturedSites();
        // A closure owns a strong reference even when the lifetime pass found
        // a destruction point for another reference to the same allocation.
        stored.or(captured);
        erasedArrayOwners.or(captured);
        freed.andNot(captured);
        Set<String> leaking = new HashSet<>();
        List<LinkedHashSet<String>> leakParts = LlvmUtil.parallelMapOrdered(siteRanges(n), scheduler, r -> {
            LinkedHashSet<String> part = new LinkedHashSet<>();
            for (int id = r[0]; id < r[1]; id++) {
                String cls = countableClass(AllocationSite.byId(id).getType());
                if (cls == null) continue;
                if (stored.get(id) && !freed.get(id)) part.add(cls);
            }
            return part;
        });
        for (LinkedHashSet<String> part : leakParts) leaking.addAll(part);
        plan.getCountedClasses().addAll(leaking);
        plan.getCountDeclarers().addAll(leaking);
        List<CountedSites> countedParts = LlvmUtil.parallelMapOrdered(siteRanges(n), scheduler, r -> {
            BitSet part = new BitSet();
            List<Instruction> arrays = new ArrayList<>();
            boolean arraysManaged = false;
            for (int id = r[0]; id < r[1]; id++) {
                AllocationSite site = AllocationSite.byId(id);
                Type t = site.getType();
                if (t != null && t.isReference() && !t.isArray() && t.getClassName() != null && leaking.contains(t.getClassName())) {
                    part.set(id);
                }
                // A primitive array can have several owners once it flows through
                // erased storage. Give that allocation the same lifetime as an object.
                if (erasedArrayOwners.get(id) && alias.getAllocationSiteToValue().get(site) instanceof Temporary value
                        && value.getType() != null && value.getType().isArray()
                        && value.getType().getElementType().isPrimitive()) {
                    Instruction allocation = value.getDefiningInstruction();
                    if (allocation != null && allocation.getOpcode() == Opcode.NEW_ARRAY
                            && allocation.getOperands().size() == 2) {
                        arrays.add(allocation);
                        part.set(id);
                        arraysManaged = true;
                    }
                }
            }
            return new CountedSites(part, arrays, arraysManaged);
        });
        for (CountedSites part : countedParts) {
            counted.or(part.bits());
            for (Instruction allocation : part.arrays()) allocation.setRcManagedArray(true);
            if (part.managesArrays()) managesArrays = true;
        }
        if (counted.isEmpty()) {
            module.setRefCountPlan(plan);
            return;
        }
        countsClosures = !plan.getCountedClasses().isEmpty();
        if (countsClosures) markClosureSites(n);
        markBirthDrops(n, freed);
        for (AllocationSite site : lifetime.getCyclicSites()) {
            int id = site.getId();
            Type t = site.getType();
            if (id >= 0 && id < n && counted.get(id) && t != null && t.isReference() && !t.isArray()
                    && t.getClassName() != null && plan.isCounted(t.getClassName())) {
                plan.getCyclicClasses().add(t.getClassName());
            }
        }
        collectArrayTypes();
        collectHolders(n);
        int stores = markStores();
        module.setRefCountPlan(plan);
        System.out.println("RefCount: " + leaking.size() + " counted class(es), " + counted.cardinality() + " site(s), "
            + stores + " counted store(s), " + weakStores + " weak store(s), " + pinned.size() + " function(s) with hold slots, "
            + plan.getCountedFields().size() + " holder class(es), " + plan.getCountedArrayTypes().size() + " holder array type(s), "
            + plan.getCyclicClasses().size() + " cyclic class(es) in "
            + (System.nanoTime() - started) / 1_000_000 + " ms");
    }

    private void markClosureSites(int n) {
        List<BitSet> parts = LlvmUtil.parallelMapOrdered(siteRanges(n), scheduler, r -> {
            BitSet part = new BitSet();
            for (int id = r[0]; id < r[1]; id++) {
                if (AllocationSite.byId(id).getType() instanceof Type.ReferenceType ref && ref.isClosure()) part.set(id);
            }
            return part;
        });
        for (BitSet part : parts) counted.or(part);
    }

    private void markBirthDrops(int n, BitSet freed) {
        List<BitSet> parts = LlvmUtil.parallelMapOrdered(siteRanges(n), scheduler, r -> {
            BitSet part = new BitSet();
            for (int id = r[0]; id < r[1]; id++) {
                if (!counted.get(id) || freed.get(id)) continue;
                AllocationSite site = AllocationSite.byId(id);
                Type t = site.getType();
                if (t == null || !t.isReference() || t.isArray() || t.getClassName() == null
                        || !plan.isCounted(t.getClassName())) continue;
                EscapeStatus status = escape.getSiteStatus(site);
                if (status == EscapeStatus.STACK || status == EscapeStatus.HEAP || status == EscapeStatus.RETURN) {
                    part.set(id);
                }
            }
            return part;
        });
        for (BitSet part : parts) birthDrop.or(part);
    }

    private boolean dropsAtBirth(Value v) {
        PointsToSet pts = alias.getPointsTo(v);
        if (pts == null || pts.isEmpty() || pts.containsUnknown()) return false;
        for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) {
            if (!birthDrop.get(pts.id(c))) return false;
        }
        return true;
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
        List<BitSet[]> parts = LlvmUtil.parallelMapOrdered(siteRanges(n), scheduler, r -> {
            BitSet stored = new BitSet();
            BitSet erased = new BitSet();
            BitSet weak = new BitSet();
            for (int src = r[0]; src < r[1]; src++) {
                for (FieldCell cell : graph.fieldCells(src)) {
                    addAll(stored, cell.get());
                    if (PointsToGraph.ARRAY_ELEMENT_FIELD.equals(cell.field()) || canHoldErasedArray(fieldType(cell.field()))) {
                        addAll(erased, cell.get());
                    }
                    if (isWeakReferent(cell.field())) addAll(weak, cell.get());
                }
            }
            return new BitSet[]{stored, erased, weak};
        });
        for (BitSet[] part : parts) {
            out.or(part[0]);
            erasedArrayOwners.or(part[1]);
            weakTargets.or(part[2]);
        }
        graph.getStaticFieldPointsToMap().forEach((field, pts) -> {
            addAll(out, pts);
            if (canHoldErasedArray(fieldType(field))) addAll(erasedArrayOwners, pts);
        });
        out.clear(AllocationSite.UNKNOWN.getId());
        return out;
    }

    private void collectCapturedSites() {
        List<BitSet> parts = LlvmUtil.parallelMapOrdered(new ArrayList<>(module.getFunctions()), scheduler, function -> {
            BitSet part = new BitSet();
            for (BasicBlock block : function.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() != Opcode.INVOKEDYNAMIC) continue;
                    for (Value value : lambdaCaptures(inst)) {
                        if (isReference(value)) addAll(part, alias.getPointsTo(value));
                    }
                }
            }
            return part;
        });
        for (BitSet part : parts) captured.or(part);
        captured.clear(AllocationSite.UNKNOWN.getId());
    }

    private static List<int[]> siteRanges(int n) {
        int chunk = Math.max(1, (n + 255) / 256);
        List<int[]> out = new ArrayList<>();
        for (int lo = 0; lo < n; lo += chunk) out.add(new int[]{lo, Math.min(n, lo + chunk)});
        return out;
    }

    private static void addAll(BitSet out, PointsToSet pts) {
        for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) out.set(pts.id(c));
    }

    private String countableClass(Type t) {
        if (t == null || !t.isReference() || t.isArray() || t.isUnknown() || t.isNull()) return null;
        String cls = t.getClassName();
        if (cls == null || cls.isEmpty() || cls.charAt(0) == '[' || cls.startsWith("__Lambda_")) return null;
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
        if (t.isArray()) return managesArrays && t.getElementType().isPrimitive();
        if (t.isNull() || !t.isReference()) return false;
        String cls = t.getClassName();
        if (cls == null) return true;
        if (cls.startsWith("[")) return maybeCountedType(Type.array(cls));
        if (managesArrays && canHoldErasedArray(t)) return true;
        return relatedTypes.contains(cls) || resolver.getClassNode(cls) == null;
    }

    private static boolean canHoldErasedArray(Type type) {
        if (type == null || type.isUnknown()) return true;
        if (!type.isReference()) return false;
        return switch (type.getClassName()) {
            case "java/lang/Object", "java/lang/Cloneable", "java/io/Serializable" -> true;
            case null, default -> false;
        };
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
        if (managesArrays) {
            work.add("java/lang/Cloneable");
            work.add("java/io/Serializable");
        }
        if (countsClosures) {
            for (ClassNode node : resolver.getClassMap().values()) {
                if (node.isInterface()) work.add(node.getName());
            }
        }
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

    private record CountedSites(BitSet bits, List<Instruction> arrays, boolean managesArrays) {}

    private record Holders(List<String> arrayTypes, List<String[]> fields) {}

    private void collectHolders(int n) {
        PointsToGraph graph = alias.getGraph();
        List<Holders> parts = LlvmUtil.parallelMapOrdered(siteRanges(n), scheduler, r -> {
            Holders part = new Holders(new ArrayList<>(), new ArrayList<>());
            for (int src = r[0]; src < r[1]; src++) collectHolders(graph, src, part);
            return part;
        });
        for (Holders part : parts) {
            plan.getCountedArrayTypes().addAll(part.arrayTypes());
            for (String[] field : part.fields()) {
                plan.getCountedFields().computeIfAbsent(field[0], k -> new HashSet<>()).add(field[1]);
            }
        }
        if (countsClosures) collectDeclaredHolders();
    }

    private void collectDeclaredHolders() {
        for (String cls : plan.getCountedClasses()) {
            Set<String> seen = new HashSet<>();
            for (String c = cls; c != null && seen.add(c); ) {
                ClassNode node = resolver.getClassNode(c);
                if (node == null || node.isExternal()) break;
                if (node.getFields() != null) {
                    for (FieldNode f : node.getFields()) {
                        if ((f.getAccess() & Opcodes.ACC_STATIC) != 0 || !maybeCountedType(f.getType())) continue;
                        String key = instanceReferenceField(c + "." + f.getName());
                        if (key != null) plan.getCountedFields().computeIfAbsent(cls, k -> new HashSet<>()).add(key);
                    }
                }
                c = node.getSuperName();
            }
        }
    }

    private void collectHolders(PointsToGraph graph, int src, Holders part) {
        Type t = AllocationSite.byId(src).getType();
        if (t == null || t.isUnknown() || t.isNull()) return;
        boolean arrayClass = t.isReference() && t.getClassName() != null && t.getClassName().startsWith("[");
        for (FieldCell cell : graph.fieldCells(src)) {
            if (!holdsCounted(cell.get()) && !(managesArrays && maybeCountedType(fieldType(cell.field())))) continue;
            if (t.isArray() || arrayClass) {
                if (PointsToGraph.ARRAY_ELEMENT_FIELD.equals(cell.field())) {
                    Value allocation = alias.getAllocationSiteToValue().get(AllocationSite.byId(src));
                    Type valueType = allocation == null ? null : allocation.getType();
                    Type arrayType = valueType != null && valueType.isArray() ? valueType : t;
                    String desc = arrayClass ? t.getClassName() : arrayDescriptor(arrayType);
                    if (desc != null && desc.length() > 1 && (desc.charAt(1) == 'L' || desc.charAt(1) == '[')) {
                        part.arrayTypes().add(desc);
                    }
                }
                continue;
            }
            if (!t.isReference() || t.getClassName() == null || t.getClassName().startsWith("__Lambda_")) continue;
            String key = instanceReferenceField(cell.field());
            if (key != null && declaredIn(t.getClassName(), key.substring(0, key.lastIndexOf('.')))) {
                part.fields().add(new String[]{t.getClassName(), key});
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
        if (d == null || (d.charAt(0) != 'L' && d.charAt(0) != '[')) return null;
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
        if (e.isPrimitive() && !e.isVoid()) return "[" + primitiveDescriptor(e);
        return null;
    }

    private int markStores() {
        List<Function> functions = new ArrayList<>();
        for (Function f : module.getFunctions()) {
            if (f.getEntryBlock() != null) functions.add(f);
        }
        List<int[]> counts = LlvmUtil.parallelMapOrdered(functions, scheduler, this::markFunctionStores);
        int marked = 0;
        for (int i = 0; i < functions.size(); i++) {
            int[] c = counts.get(i);
            marked += c[0];
            weakStores += c[1];
            if (c[2] > 0) pinned.add(functions.get(i));
        }
        return marked;
    }

    private int[] markFunctionStores(Function f) {
        int marked = 0;
        int[] weak = new int[1];
        int next = 0;
        Map<Integer, Integer> localHolds = new java.util.HashMap<>();
        for (BasicBlock block : f.getBlocks()) {
            for (Instruction inst : block.getInstructions()) {
                Value res = inst.getResult();
                if (inst.getOpcode() == Opcode.GET_FIELD && isWeakReferent(extractFieldName(inst))) {
                    inst.setRcWeak(true);
                }
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
                } else if (isReference(res) && (inst.isRcManagedArray() || inst.isRcWeak() || maybeCounted(res))) {
                    inst.setRcHold(next++);
                }
                if (inst.getOpcode() == Opcode.NEW && isCountedType(res == null ? null : res.getType())) {
                    if (mayBeWeakTarget(res)) {
                        inst.setRcWeak(true);
                    } else if (dropsAtBirth(res)) {
                        inst.setRcBirthDrop(true);
                    }
                }
                if (countsClosures && res != null && LlvmUtil.isLambdaCreation(inst)) {
                    inst.setRcBirthDrop(true);
                }
                if (markStore(inst, weak)) marked++;
            }
        }
        f.setRcHolds(next);
        return new int[]{marked, weak[0], next};
    }

    private boolean markStore(Instruction inst, int[] weak) {
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
                    inst.setRcWeak(true);
                    weak[0]++;
                    return false;
                }
                PointsToSet old = alias.getFieldPointsToForSites(alias.getPointsTo(ops.getFirst()), raw);
                Type type = fieldType(raw);
                if (!maybeCounted(ops.get(2)) && !maybeCountedContents(old, type)
                        && !(managesArrays && maybeCountedType(type))) return false;
                inst.setRcStore(true);
                return true;
            }
            case ASTORE -> {
                if (ops.size() < 3 || !isReference(ops.get(2))) return false;
                PointsToSet old = alias.getFieldPointsToForSites(alias.getPointsTo(ops.getFirst()), PointsToGraph.ARRAY_ELEMENT_FIELD);
                Type arr = ops.getFirst().getType();
                Type element = arr != null && arr.isArray() ? arr.getElementType() : null;
                if (!maybeCounted(ops.get(2)) && !maybeCountedContents(old, element)
                        && !(managesArrays && maybeCountedType(element))) return false;
                inst.setRcStore(true);
                return true;
            }
            case PUT_STATIC -> {
                if (ops.size() < 2 || !isReference(ops.get(1))) return false;
                PointsToSet old = alias.getStaticFieldPointsTo(extractFieldName(inst));
                Type type = fieldType(extractFieldName(inst));
                if (!maybeCounted(ops.get(1)) && !maybeCountedContents(old, type)
                        && !(managesArrays && maybeCountedType(type))) return false;
                inst.setRcStore(true);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean mayBeWeakTarget(Value value) {
        PointsToSet sites = alias.getPointsTo(value);
        if (weakTargets.get(AllocationSite.UNKNOWN.getId())) return true;
        for (long c = sites.cursor(); sites.has(c); c = sites.next(c)) {
            if (weakTargets.get(sites.id(c))) return true;
        }
        return false;
    }

    private boolean isWeakReferent(String raw) {
        if (raw == null || !raw.endsWith("." + WEAK_FIELD)) return false;
        int dot = raw.lastIndexOf('.');
        FieldNode f = resolver.getField(raw.substring(0, dot), raw.substring(dot + 1));
        return f != null && WEAK_OWNER.equals(f.getOwner());
    }

    private static boolean isReference(Value v) {
        if (v == null || v.getType() == null) return false;
        Type t = v.getType();
        return t.isReference() || t.isArray() || t.isNull() || t.isUnknown();
    }
}
