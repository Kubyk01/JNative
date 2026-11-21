package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.VarHandleTargetResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult.CallShape;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import org.objectweb.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.kubyk01.util.LlvmUtil.canonicalCalleeKey;
import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;
import static io.github.kubyk01.util.LlvmUtil.extractFieldName;

final class AtomicSlotArrays {

    private static final String ARA = "java/util/concurrent/atomic/AtomicReferenceArray";
    private static final String AR = "java/util/concurrent/atomic/AtomicReference";
    private static final String VH = "java/lang/invoke/VarHandle";
    private static final String ARFU = "java/util/concurrent/atomic/AtomicReferenceFieldUpdater";
    private static final String UNKNOWN_FAMILY = "?";

    enum Kind { READ, CAS_BOOL, CAS_WITNESS, GET_AND_SET, SET, UNKNOWN_WRITE }

    record Op(Instruction inst, Kind kind, String field, Value receiver, Value expected, Value newValue) {}

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult alias;
    private final EscapeAnalysisResult escape;
    private final VarHandleTargetResolver handles;

    private final List<Op> ops = new ArrayList<>();
    private final Set<String> fieldFamilies = new HashSet<>();
    private final Map<String, String> invalid = new HashMap<>();
    private final Map<String, Boolean> allArrays = new HashMap<>();
    private final Map<Function, List<Instruction>> callSitesOf = new IdentityHashMap<>();
    private final Map<Instruction, List<Function>> targets = new IdentityHashMap<>();
    private final Map<Function, Index> indexes = new IdentityHashMap<>();
    private final Set<String> returnPropagated = new HashSet<>();

    private final Set<Instruction> pinBefore = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Function> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Instruction, Instruction> retireAfter = new IdentityHashMap<>();
    private final Map<Instruction, Value> discardAfter = new IdentityHashMap<>();
    private final Map<Instruction, String> pendingBorrows = new IdentityHashMap<>();

    private static final class Index {
        final Map<Value, List<Instruction>> users = new IdentityHashMap<>();
        final Map<Integer, List<Instruction>> loads = new HashMap<>();
        final Map<Integer, List<Instruction>> stores = new HashMap<>();
        final Map<Value, List<BasicBlock>> returnedIn = new IdentityHashMap<>();
        final Set<Value> thrown = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    AtomicSlotArrays(Module module, DependencyResolver resolver, AliasAnalysisResult alias, EscapeAnalysisResult escape) {
        this.module = module;
        this.resolver = resolver;
        this.alias = alias;
        this.escape = escape;
        this.handles = new VarHandleTargetResolver(resolver);
    }

    boolean pinsBefore(Instruction inst) {
        return pinBefore.contains(inst);
    }

    boolean unpinsAtExit(Function f) {
        return pinned.contains(f);
    }

    Op retireOp(Instruction inst) {
        return retireAfter.containsKey(inst) ? opOf(inst) : null;
    }

    Value discardValue(Instruction inst) {
        return discardAfter.get(inst);
    }

    void compute() {
        long started = System.nanoTime();
        scan();
        for (Instruction w : staticWrites) {
            String where = "unresolved static field write " + extractCalleeName(w) + " in " + w.getParent().getFunction().getName();
            for (String fam : staticFamilies) invalid.putIfAbsent("field " + fam, where);
        }
        for (int i = 0; i < ops.size(); i++) checkWrite(ops.get(i));
        for (Op op : ops) {
            Value borrowed = borrowedValue(op);
            if (borrowed == null) continue;
            String fam = familyOfRead(op, borrowed);
            if (fam == null) continue;
            pendingBorrows.put(op.inst(), fam);
            String why = checkBorrow(op.inst().getParent().getFunction(), borrowed, fam);
            if (why != null) invalid.putIfAbsent(fam, why);
        }
        for (Map.Entry<Instruction, String> e : pendingBorrows.entrySet()) {
            String fam = e.getValue();
            if (fam.equals(UNKNOWN_FAMILY) || invalid.containsKey(fam)) continue;
            pinBefore.add(e.getKey());
            pinned.add(e.getKey().getParent().getFunction());
        }
        Map<Instruction, List<Op>> byInst = new IdentityHashMap<>();
        for (Op op : ops) byInst.computeIfAbsent(op.inst(), k -> new ArrayList<>()).add(op);
        int retires = 0;
        int discards = 0;
        for (Map.Entry<Instruction, List<Op>> e : byInst.entrySet()) {
            List<Op> group = e.getValue();
            boolean allRetirable = true;
            boolean allValid = true;
            for (Op op : group) {
                String fam = familyOfWrite(op);
                if (fam == null || fam.equals(UNKNOWN_FAMILY) || invalid.containsKey(fam)) {
                    allRetirable = false;
                    allValid = false;
                    break;
                }
                if (!retirable(op, fam)) allRetirable = false;
            }
            Op first = group.getFirst();
            if (allRetirable) {
                retireAfter.put(e.getKey(), e.getKey());
                pinBefore.add(e.getKey());
                pinned.add(e.getKey().getParent().getFunction());
                retires++;
            }
            if (allValid && first.kind() == Kind.CAS_BOOL && e.getKey().getResult() != null && first.newValue() != null
                && definiteArray(first.newValue()) && unusedAfter(first, first.newValue())) {
                discardAfter.put(e.getKey(), first.newValue());
                discards++;
            }
        }
        Set<String> families = new HashSet<>(fieldFamilies);
        for (int i = 0; i < ops.size(); i++) {
            String fam = familyOfWrite(ops.get(i));
            if (fam != null && !fam.equals(UNKNOWN_FAMILY)) families.add(fam);
        }
        int valid = 0;
        for (String fam : families) if (!invalid.containsKey(fam)) valid++;
        System.out.println("AtomicSlotArrays: " + valid + " of " + families.size() + " atomic slot familie(s) reclaimable, "
            + retires + " retire point(s), " + discards + " discard point(s), " + pinned.size() + " pinned function(s) in "
            + (System.nanoTime() - started) / 1_000_000 + " ms");
        for (String fam : families) {
            if (invalid.containsKey(fam)) System.out.println("AtomicSlotArrays: " + fam + " kept: " + invalid.get(fam));
        }
        indexes.clear();
    }

    private void scan() {
        List<? extends Function> fns = module.getFunctions();
        List<Instruction> plain = new ArrayList<>();
        List<Instruction> calls = new ArrayList<>();
        for (Function f : fns) {
            if (f.getEntryBlock() == null || ContainerArrayBorrows.isDestructor(f)) continue;
            boolean intrinsicOwner = isAtomicHolder(f);
            for (BasicBlock block : f.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    Opcode op = inst.getOpcode();
                    if (op == Opcode.GET_FIELD || op == Opcode.PUT_FIELD) {
                        plain.add(inst);
                        continue;
                    }
                    if (!isCall(op)) continue;
                    List<Function> ts = resolveTargets(inst);
                    targets.put(inst, ts);
                    for (int t = 0; t < ts.size(); t++) callSitesOf.computeIfAbsent(ts.get(t), k -> new ArrayList<>()).add(inst);
                    if (!intrinsicOwner) calls.add(inst);
                }
            }
        }
        for (Instruction inst : calls) noteUpdaterCreation(inst);
        for (Instruction inst : calls) classify(inst);
        for (Instruction inst : plain) {
            String key = extractFieldName(inst);
            if (!fieldFamilies.contains(key)) continue;
            List<Value> o = inst.getOperands();
            if (inst.getOpcode() == Opcode.GET_FIELD) {
                if (inst.getResult() != null) ops.add(new Op(inst, Kind.READ, key, o.getFirst(), null, null));
            } else if (o.size() > 2) {
                ops.add(new Op(inst, Kind.SET, key, o.getFirst(), null, o.get(2)));
            }
        }
    }

    private boolean isAtomicHolder(Function f) {
        String n = f.getOwnerClass();
        return ARA.equals(n) || AR.equals(n) || "jdk/internal/misc/Unsafe".equals(n) || "sun/misc/Unsafe".equals(n)
            || (n != null && n.startsWith(ARFU))
            || VH.equals(n) || (n != null && n.startsWith("java/lang/invoke/VarHandle"));
    }

    private void classify(Instruction inst) {
        String callee = extractCalleeName(inst);
        if (callee == null) return;
        int paren = callee.indexOf('(');
        int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
        if (dot <= 0) return;
        String owner = callee.substring(0, dot);
        String name = callee.substring(dot + 1, paren);
        int argc = argCount(inst);
        if (owner.equals(ARA) || owner.equals(AR)) {
            int base = owner.equals(ARA) ? 2 : 1;
            Value recv = arg(inst, 0);
            Kind k = kindOf(name);
            if (k == null) {
                if (name.equals("<init>") && owner.equals(ARA) && callee.contains("([Ljava/lang/Object;)")) {
                    ops.add(new Op(inst, Kind.UNKNOWN_WRITE, null, recv, null, null));
                } else if (name.contains("Update") || name.contains("Accumulate") || name.startsWith("update") || name.startsWith("accumulate")) {
                    ops.add(new Op(inst, Kind.UNKNOWN_WRITE, null, recv, null, null));
                } else if (name.equals("<init>") && owner.equals(AR) && argc == 2) {
                    ops.add(new Op(inst, Kind.SET, null, recv, null, arg(inst, 1)));
                }
                return;
            }
            addValueOp(inst, k, null, recv, base, argc);
            return;
        }
        if (owner.startsWith(ARFU)) {
            Kind k = kindOf(name);
            boolean other = k == null && !name.equals("<init>") && (name.contains("Update") || name.contains("Accumulate")
                || name.startsWith("update") || name.startsWith("accumulate"));
            if (k == null && !other) return;
            List<VarHandleTargetResolver.Target> ts = argc > 0 ? handleTargets(arg(inst, 0), inst.getParent().getFunction()) : null;
            Value obj = argc > 1 ? arg(inst, 1) : null;
            boolean resolved = ts != null && !other;
            for (int i = 0; resolved && i < ts.size(); i++) {
                if (ts.get(i).kind() != VarHandleTargetResolver.Kind.INSTANCE_FIELD) resolved = false;
            }
            if (!resolved) {
                if (k != Kind.READ) {
                    ops.add(new Op(inst, Kind.UNKNOWN_WRITE, null, obj, null, argc > 2 ? arg(inst, argc - 1) : null));
                    updaterWrites.add(inst);
                }
                return;
            }
            for (VarHandleTargetResolver.Target t : ts) {
                String key = t.owner() + "." + t.fieldName();
                fieldFamilies.add(key);
                addValueOp(inst, k, key, obj, 2, argc);
            }
            return;
        }
        if ((owner.equals("jdk/internal/misc/Unsafe") || owner.equals("sun/misc/Unsafe")) && name.contains("Reference")) {
            Kind k = unsafeKind(name);
            if (k == null) return;
            if (k != Kind.READ && !(argc > 1 && isReference(arg(inst, argc - 1)))) return;
            if (argc > 2 && staticOffset(arg(inst, 2))) {
                if (k != Kind.READ) staticWrites.add(inst);
                return;
            }
            List<VarHandleTargetResolver.Target> ts = argc > 2 ? handleTargets(arg(inst, 2), inst.getParent().getFunction()) : null;
            boolean resolved = ts != null;
            for (int i = 0; resolved && i < ts.size(); i++) {
                if (ts.get(i).kind() != VarHandleTargetResolver.Kind.INSTANCE_FIELD) resolved = false;
            }
            if (!resolved) {
                if (k != Kind.READ) ops.add(new Op(inst, Kind.UNKNOWN_WRITE, null, arg(inst, 1), null, arg(inst, argc - 1)));
                return;
            }
            for (VarHandleTargetResolver.Target t : ts) {
                if (t.valueType() != null && t.valueType().isPrimitive()) continue;
                String key = t.owner() + "." + t.fieldName();
                fieldFamilies.add(key);
                addValueOp(inst, k, key, arg(inst, 1), 3, argc);
            }
            return;
        }
        if (owner.equals(VH) && inst.isPolymorphicSignature()) {
            Kind k = kindOf(name);
            if (k == null) return;
            VarHandleTargetResolver.Target single = handles.resolveCallSite(inst);
            List<VarHandleTargetResolver.Target> ts = single != null ? List.of(single)
                : handleTargets(inst.getOperands().getFirst(), inst.getParent().getFunction());
            if (ts == null) {
                Value written = argc > 1 ? arg(inst, argc - 1) : null;
                if (k != Kind.READ && isReference(written)) ops.add(new Op(inst, Kind.UNKNOWN_WRITE, null, arg(inst, 1), null,
                    arg(inst, argc - 1)));
                return;
            }
            for (VarHandleTargetResolver.Target t : ts) classifyHandle(inst, k, t, argc);
        }
    }

    private void classifyHandle(Instruction inst, Kind k, VarHandleTargetResolver.Target t, int argc) {
        if (t.valueType() != null && t.valueType().isPrimitive()) return;
        String key = t.owner() + "." + t.fieldName();
        switch (t.kind()) {
            case INSTANCE_FIELD -> {
                fieldFamilies.add(key);
                addValueOp(inst, k, key, arg(inst, 1), 2, argc);
            }
            case STATIC_FIELD -> {
                fieldFamilies.add(key);
                staticFamilies.add(key);
                addValueOp(inst, k, key, null, 1, argc);
            }
            case ARRAY_ELEMENT -> addValueOp(inst, k, null, arg(inst, 1), 3, argc);
        }
    }

    private List<VarHandleTargetResolver.Target> handleTargets(Value handle, Function f) {
        Map<String, VarHandleTargetResolver.Target> out = new HashMap<>();
        return collectHandleTargets(handle, f, out, new HashSet<>(), 0) ? new ArrayList<>(out.values()) : null;
    }

    private boolean collectHandleTargets(Value handle, Function f, Map<String, VarHandleTargetResolver.Target> out,
                                         Set<String> visiting, int depth) {
        if (depth > 16) return false;
        Set<Value> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Value> work = new ArrayDeque<>();
        work.push(handle);
        while (!work.isEmpty()) {
            Value v = work.pop();
            if (v == null || !seen.add(v)) continue;
            if (isNullConstant(v)) continue;
            VarHandleTargetResolver.Target direct = handles.resolveHandleValue(v);
            if (direct != null) {
                out.put(direct.kind() + ":" + direct.owner() + "." + direct.fieldName() + ":" + direct.valueType(), direct);
                continue;
            }
            int ordinal = -1;
            if (v instanceof Parameter p) {
                ordinal = p.getOrdinal();
            } else if (v instanceof Temporary t && t.getDefiningInstruction() != null) {
                Instruction def = t.getDefiningInstruction();
                List<Value> o = def.getOperands();
                switch (def.getOpcode()) {
                    case CAST, CHECKCAST, STORE -> {
                        work.push(o.getFirst());
                        continue;
                    }
                    case PHI, SELECT -> {
                        for (Value x : o) if (isReference(x)) work.push(x);
                        continue;
                    }
                    case LOAD -> {
                        int slot = def.getLocalIndex();
                        for (Parameter p : f.getParameters()) {
                            if (p.getIndex() == slot) work.push(p);
                        }
                        for (Instruction st : index(f).stores.getOrDefault(slot, List.of())) work.push(st.getOperands().getFirst());
                        if (!isParamSlot(f, slot) && index(f).stores.getOrDefault(slot, List.of()).isEmpty()) return false;
                        continue;
                    }
                    default -> {
                        return false;
                    }
                }
            } else {
                return false;
            }
            if (ordinal < 0) return false;
            String key = f.getName() + "#" + ordinal;
            if (!visiting.add(key)) continue;
            List<Instruction> sites = callSitesOf.getOrDefault(f, List.of());
            if (sites.isEmpty()) return false;
            for (Instruction call : sites) {
                if (ordinal >= argCount(call)) return false;
                if (!collectHandleTargets(arg(call, ordinal), call.getParent().getFunction(), out, visiting, depth + 1)) return false;
            }
        }
        return true;
    }

    private static final String NEW_UPDATER = ARFU + ".newUpdater(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;)"
        + "Ljava/util/concurrent/atomic/AtomicReferenceFieldUpdater;";
    private final Set<Instruction> updaterWrites = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> updaterUniverse = new HashSet<>();
    private boolean updaterUniverseUnknown;

    private void noteUpdaterCreation(Instruction inst) {
        if (!NEW_UPDATER.equals(extractCalleeName(inst))) return;
        Object cls = constantOf(arg(inst, 0));
        Object name = constantOf(arg(inst, 2));
        if (!(cls instanceof String c) || !(name instanceof String n)) {
            updaterUniverseUnknown = true;
            return;
        }
        io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode fn = resolver.getField(c, n);
        if (fn == null || fn.getOwner() == null) {
            updaterUniverseUnknown = true;
            return;
        }
        updaterUniverse.add(fn.getOwner() + "." + fn.getName());
    }

    private Object constantOf(Value v) {
        Set<Value> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (v != null && seen.add(v)) {
            if (v instanceof Constant c) return c.getValue();
            if (!(v instanceof Temporary t) || t.getDefiningInstruction() == null) return null;
            Instruction def = t.getDefiningInstruction();
            switch (def.getOpcode()) {
                case CAST, CHECKCAST, STORE -> v = def.getOperands().getFirst();
                case LOAD -> {
                    Function f = def.getParent().getFunction();
                    if (isParamSlot(f, def.getLocalIndex())) return null;
                    List<Instruction> st = index(f).stores.getOrDefault(def.getLocalIndex(), List.of());
                    if (st.size() != 1) return null;
                    v = st.getFirst().getOperands().getFirst();
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean updaterMayWrite(String fieldKey) {
        return updaterUniverseUnknown || updaterUniverse.contains(fieldKey);
    }

    private void addValueOp(Instruction inst, Kind k, String field, Value recv, int firstValue, int argc) {
        switch (k) {
            case READ -> ops.add(new Op(inst, k, field, recv, null, null));
            case SET, GET_AND_SET -> {
                if (argc > firstValue) ops.add(new Op(inst, k, field, recv, null, arg(inst, firstValue)));
            }
            case CAS_BOOL, CAS_WITNESS -> {
                if (argc > firstValue + 1) ops.add(new Op(inst, k, field, recv, arg(inst, firstValue), arg(inst, firstValue + 1)));
            }
            default -> { }
        }
    }

    private final Set<Instruction> staticWrites = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> staticFamilies = new HashSet<>();

    private boolean staticOffset(Value offset) {
        Set<Value> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Value v = offset;
        while (v instanceof Temporary t && t.getDefiningInstruction() != null && seen.add(v)) {
            Instruction def = t.getDefiningInstruction();
            switch (def.getOpcode()) {
                case CAST, STORE -> v = def.getOperands().getFirst();
                case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                    String callee = extractCalleeName(def);
                    return callee != null && (callee.startsWith("jdk/internal/misc/Unsafe.staticFieldOffset(")
                        || callee.startsWith("sun/misc/Unsafe.staticFieldOffset("));
                }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    private static Kind unsafeKind(String name) {
        if (name.startsWith("getAndSet")) return Kind.GET_AND_SET;
        if (name.startsWith("get")) return Kind.READ;
        if (name.startsWith("put")) return Kind.SET;
        if (name.startsWith("compareAndSet") || name.startsWith("weakCompareAndSet")) return Kind.CAS_BOOL;
        if (name.startsWith("compareAndExchange")) return Kind.CAS_WITNESS;
        return null;
    }

    private static Kind kindOf(String name) {
        return switch (name) {
            case "get", "getPlain", "getOpaque", "getAcquire", "getVolatile" -> Kind.READ;
            case "compareAndSet", "weakCompareAndSet", "weakCompareAndSetPlain", "weakCompareAndSetVolatile",
                 "weakCompareAndSetAcquire", "weakCompareAndSetRelease" -> Kind.CAS_BOOL;
            case "compareAndExchange", "compareAndExchangeAcquire", "compareAndExchangeRelease" -> Kind.CAS_WITNESS;
            case "getAndSet", "getAndSetAcquire", "getAndSetRelease" -> Kind.GET_AND_SET;
            case "set", "lazySet", "setPlain", "setOpaque", "setRelease", "setVolatile" -> Kind.SET;
            default -> null;
        };
    }

    private String familyOfWrite(Op op) {
        if (op.kind() == Kind.READ) return null;
        if (op.field() != null) return "field " + op.field();
        Value v = op.newValue();
        if (v == null || isNullConstant(v)) v = op.expected();
        if (v == null || isNullConstant(v)) return null;
        Type t = definiteType(v);
        if (t != null && t.isArray()) return "slot " + t;
        if (t != null && !mayBeArray(t)) return null;
        return UNKNOWN_FAMILY;
    }

    private String familyOfRead(Op op, Value result) {
        if (op.field() != null) return "field " + op.field();
        Type t = useType(op.inst().getParent().getFunction(), result);
        if (t != null && t.isArray()) return "slot " + t;
        if (t != null && !mayBeArray(t)) return null;
        return UNKNOWN_FAMILY;
    }

    private Value borrowedValue(Op op) {
        return switch (op.kind()) {
            case READ, CAS_WITNESS, GET_AND_SET -> isReference(op.inst().getResult()) ? op.inst().getResult() : null;
            default -> null;
        };
    }

    private void checkWrite(Op op) {
        if (op.kind() == Kind.READ) return;
        if (op.kind() == Kind.UNKNOWN_WRITE) {
            Value v = op.newValue();
            if (isNullConstant(v)) return;
            boolean serialization = "java/io/ObjectStreamClass$FieldReflector".equals(op.inst().getParent().getFunction().getOwnerClass());
            boolean staticOnly = staticAccessor(op);
            if (updaterWrites.contains(op.inst())) {
                String where = "unresolved field updater write " + extractCalleeName(op.inst()) + " in " + op.inst().getParent().getFunction().getName();
                for (String fam : fieldFamilies) {
                    if (updaterMayWrite(fam)) invalid.putIfAbsent("field " + fam, where);
                }
                return;
            }
            String where = "unresolved atomic write " + extractCalleeName(op.inst()) + " in " + op.inst().getParent().getFunction().getName();
            for (String fam : fieldFamilies) {
                String owner = fam.substring(0, fam.lastIndexOf('.'));
                if ((serialization && !serializableBelow(owner)) || staticOnly) continue;
                if (op.receiver() == null || mayBeInstanceOf(op.receiver(), owner)) {
                    invalid.putIfAbsent("field " + fam, where);
                }
            }
            if (!serialization && !staticOnly && (op.receiver() == null || valueMayBeArray(op.receiver())) && (v == null || valueMayBeArray(v))) {
                invalid.putIfAbsent(UNKNOWN_FAMILY, where);
                markAllSlotFamiliesUnsafe(op, where);
            }
            return;
        }
        String fam = familyOfWrite(op);
        if (fam == null) return;
        Value v = op.newValue();
        if (v == null || isNullConstant(v)) return;
        Function f = op.inst().getParent().getFunction();
        if (fam.equals(UNKNOWN_FAMILY)) {
            if (valueMayBeArray(v)) markAllSlotFamiliesUnsafe(op, "atomic write of an untyped value in " + f.getName());
            return;
        }
        String why = freshExclusive(f, op, v);
        if (why != null) invalid.putIfAbsent(fam, why);
        if (!definiteArray(v)) allArrays.put(fam, false);
        else allArrays.putIfAbsent(fam, true);
    }

    private final List<Op> untypedOps = new ArrayList<>();
    private final List<String> untypedWhy = new ArrayList<>();
    private final Map<String, Set<String>> familyContainerCache = new HashMap<>();
    private final Map<String, Set<String>> fieldClassCache = new HashMap<>();
    private final Set<String> fieldClassBusy = new HashSet<>();
    private final Map<String, Set<String>> slotClassCache = new HashMap<>();
    private final Set<String> slotClassBusy = new HashSet<>();
    private Map<String, List<Instruction>> putsByField;

    private void markAllSlotFamiliesUnsafe(Op op, String why) {
        untypedOps.add(op);
        untypedWhy.add(why);
    }

    private String untypedConflict(String fam) {
        if (untypedOps.isEmpty()) return null;
        Set<String> containers = familyContainers(fam);
        for (int i = 0; i < untypedOps.size(); i++) {
            Op op = untypedOps.get(i);
            if (containers == null || op.receiver() == null || mayHoldContainer(op.receiver(), containers)) return untypedWhy.get(i);
        }
        return null;
    }

    private final Map<Instruction, String> siteIds = new IdentityHashMap<>();

    private String siteId(Instruction def, String cls) {
        return siteIds.computeIfAbsent(def, d -> cls + "#" + siteIds.size());
    }

    private static String siteClass(String site) {
        return site.substring(0, site.lastIndexOf('#'));
    }

    private boolean mayHoldContainer(Value receiver, Set<String> containerSites) {
        Set<String> own = containerClasses(receiver);
        if (own != null) {
            for (String c : own) if (containerSites.contains(c)) return true;
            return false;
        }
        Set<String> containers = new HashSet<>();
        for (String site : containerSites) containers.add(siteClass(site));
        Type t = receiver.getType();
        if (t == null || t.isUnknown() || !t.isReference() || t.isArray()) return t == null || t.isUnknown();
        String r = t.getClassName();
        if (r == null || r.equals("java/lang/Object")) return true;
        for (String c : containers) {
            if (isSubtype(c, r) || implementsInterface(c, r)) return true;
        }
        return false;
    }

    private Set<String> familyContainers(String fam) {
        if (familyContainerCache.containsKey(fam)) return familyContainerCache.get(fam);
        Set<String> out = new HashSet<>();
        for (int i = 0; i < ops.size() && out != null; i++) {
            Op op = ops.get(i);
            boolean member = fam.equals(familyOfWrite(op))
                || (op.kind() == Kind.READ && fam.equals(pendingBorrows.get(op.inst())));
            if (!member) continue;
            if (op.receiver() == null) {
                out = null;
                break;
            }
            Set<String> c = containerClasses(op.receiver());
            if (c == null) out = null;
            else out.addAll(c);
        }
        familyContainerCache.put(fam, out);
        return out;
    }

    private Set<String> containerClasses(Value v) {
        Set<String> out = new HashSet<>();
        Set<Value> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Value> work = new ArrayDeque<>();
        work.push(v);
        while (!work.isEmpty()) {
            Value cur = work.pop();
            if (cur == null || isNullConstant(cur) || !seen.add(cur)) continue;
            if (!(cur instanceof Temporary t) || t.getDefiningInstruction() == null) return null;
            Instruction def = t.getDefiningInstruction();
            List<Value> o = def.getOperands();
            switch (def.getOpcode()) {
                case NEW -> {
                    if (o.isEmpty() || !(o.getFirst() instanceof Constant c) || c.getValue() == null) return null;
                    out.add(siteId(def, c.getValue().toString()));
                }
                case CAST, CHECKCAST, STORE -> work.push(o.getFirst());
                case PHI, SELECT -> {
                    for (int k = 0; k < o.size(); k++) if (isReference(o.get(k))) work.push(o.get(k));
                }
                case LOAD -> {
                    Function f = def.getParent().getFunction();
                    if (isParamSlot(f, def.getLocalIndex())) return null;
                    List<Instruction> st = index(f).stores.getOrDefault(def.getLocalIndex(), List.of());
                    if (st.isEmpty()) return null;
                    for (int k = 0; k < st.size(); k++) work.push(st.get(k).getOperands().getFirst());
                }
                case GET_FIELD -> {
                    Set<String> fc = finalFieldClasses(extractFieldName(def));
                    if (fc == null) return null;
                    out.addAll(fc);
                }
                case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                    Op read = opOf(def);
                    if (read == null || read.field() != null || read.receiver() == null
                        || !(read.kind() == Kind.READ || read.kind() == Kind.CAS_WITNESS || read.kind() == Kind.GET_AND_SET)) return null;
                    String key = provenance(read.receiver());
                    if (key == null) return null;
                    Set<String> sc = slotClasses(key);
                    if (sc == null) return null;
                    out.addAll(sc);
                }
                default -> {
                    return null;
                }
            }
        }
        return out;
    }

    private String provenance(Value v) {
        String found = null;
        Set<Value> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Value> work = new ArrayDeque<>();
        work.push(v);
        while (!work.isEmpty()) {
            Value cur = work.pop();
            if (cur == null || isNullConstant(cur) || !seen.add(cur)) continue;
            if (!(cur instanceof Temporary t) || t.getDefiningInstruction() == null) return null;
            Instruction def = t.getDefiningInstruction();
            List<Value> o = def.getOperands();
            switch (def.getOpcode()) {
                case CAST, CHECKCAST, STORE -> work.push(o.getFirst());
                case PHI, SELECT -> {
                    for (int k = 0; k < o.size(); k++) if (isReference(o.get(k))) work.push(o.get(k));
                }
                case LOAD -> {
                    Function f = def.getParent().getFunction();
                    if (isParamSlot(f, def.getLocalIndex())) return null;
                    List<Instruction> st = index(f).stores.getOrDefault(def.getLocalIndex(), List.of());
                    if (st.isEmpty()) return null;
                    for (int k = 0; k < st.size(); k++) work.push(st.get(k).getOperands().getFirst());
                }
                case GET_FIELD -> {
                    String key = finalFieldKey(extractFieldName(def));
                    if (key == null || (found != null && !found.equals(key))) return null;
                    found = key;
                }
                default -> {
                    return null;
                }
            }
        }
        return found;
    }

    private Set<String> slotClasses(String key) {
        if (slotClassCache.containsKey(key)) return slotClassCache.get(key);
        if (!slotClassBusy.add(key)) return null;
        Set<String> out = new HashSet<>();
        for (int i = 0; i < ops.size() && out != null; i++) {
            Op op = ops.get(i);
            if (op.field() != null || op.kind() == Kind.READ) continue;
            if (op.kind() == Kind.UNKNOWN_WRITE) {
                if (op.receiver() == null || key.equals(provenance(op.receiver()))) out = null;
                continue;
            }
            if (op.receiver() == null || !key.equals(provenance(op.receiver()))) continue;
            Value nv = op.newValue();
            if (nv == null || isNullConstant(nv)) continue;
            Set<String> c = containerClasses(nv);
            if (c == null) out = null;
            else out.addAll(c);
        }
        slotClassBusy.remove(key);
        slotClassCache.put(key, out);
        return out;
    }

    private String finalFieldKey(String raw) {
        if (raw == null) return null;
        int dot = raw.lastIndexOf('.');
        if (dot <= 0) return null;
        io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode fn = resolver.getField(raw.substring(0, dot), raw.substring(dot + 1));
        if (fn == null || fn.getOwner() == null) return null;
        if ((fn.getAccess() & Opcodes.ACC_STATIC) != 0) return null;
        if (serializableBelow(fn.getOwner())) return null;
        String key = fn.getOwner() + "." + fn.getName();
        if ((fn.getAccess() & Opcodes.ACC_FINAL) == 0 && !writesComplete(key, fn.getOwner())) return null;
        return key;
    }

    private final Map<String, Boolean> completeCache = new HashMap<>();

    private boolean writesComplete(String key, String owner) {
        Boolean cached = completeCache.get(key);
        if (cached != null) return cached;
        boolean ok = !fieldFamilies.contains(key);
        for (int i = 0; i < ops.size() && ok; i++) {
            Op op = ops.get(i);
            if (op.kind() != Kind.UNKNOWN_WRITE) continue;
            boolean serialization = "java/io/ObjectStreamClass$FieldReflector".equals(op.inst().getParent().getFunction().getOwnerClass());
            if (serialization || staticAccessor(op)) continue;
            if (updaterWrites.contains(op.inst())) {
                if (updaterMayWrite(key)) ok = false;
                continue;
            }
            if (op.receiver() == null || mayBeInstanceOf(op.receiver(), owner)) ok = false;
        }
        completeCache.put(key, ok);
        return ok;
    }

    private Set<String> finalFieldClasses(String raw) {
        String key = finalFieldKey(raw);
        if (key == null) return null;
        if (fieldClassCache.containsKey(key)) return fieldClassCache.get(key);
        if (!fieldClassBusy.add(key)) return null;
        if (putsByField == null) {
            putsByField = new HashMap<>();
            for (Function f : module.getFunctions()) {
                if (f.getEntryBlock() == null) continue;
                for (BasicBlock b : f.getBlocks()) {
                    for (Instruction inst : b.getInstructions()) {
                        if (inst.getOpcode() != Opcode.PUT_FIELD || inst.getOperands().size() < 3) continue;
                        String k = finalFieldKey(extractFieldName(inst));
                        if (k != null) putsByField.computeIfAbsent(k, x -> new ArrayList<>()).add(inst);
                    }
                }
            }
        }
        Set<String> out = new HashSet<>();
        List<Instruction> stores = putsByField.getOrDefault(key, List.of());
        for (int i = 0; i < stores.size() && out != null; i++) {
            Set<String> c = containerClasses(stores.get(i).getOperands().get(2));
            if (c == null) out = null;
            else out.addAll(c);
        }
        if (out != null && out.isEmpty()) out = null;
        fieldClassBusy.remove(key);
        fieldClassCache.put(key, out);
        return out;
    }

    private boolean retirable(Op op, String fam) {
        if (fam.startsWith("slot ")) {
            String conflict = untypedConflict(fam);
            if (conflict != null) {
                invalid.putIfAbsent(fam, conflict);
                return false;
            }
        }
        if (op.inst().getResult() == null) return false;
        return switch (op.kind()) {
            case CAS_BOOL, CAS_WITNESS -> op.expected() != null && !isNullConstant(op.expected()) && definiteArray(op.expected())
                && (!fam.startsWith("slot ") || fam.equals("slot " + definiteType(op.expected())));
            case GET_AND_SET -> fam.startsWith("field ") && Boolean.TRUE.equals(allArrays.get(fam));
            default -> false;
        };
    }

    private String freshExclusive(Function f, Op op, Value v) {
        Index idx = index(f);
        Set<Value> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Value> work = new ArrayDeque<>();
        work.push(v);
        while (!work.isEmpty()) {
            Value cur = work.pop();
            if (isNullConstant(cur) || !seen.add(cur)) continue;
            if (!(cur instanceof Temporary t) || t.getDefiningInstruction() == null) {
                return "atomic write of a parameter or constant in " + f.getName();
            }
            Instruction def = t.getDefiningInstruction();
            List<Value> o = def.getOperands();
            switch (def.getOpcode()) {
                case NEW, NEW_ARRAY, MULTI_NEW_ARRAY -> { }
                case CAST, CHECKCAST, STORE -> work.push(o.getFirst());
                case PHI, SELECT -> {
                    for (int k = 0; k < o.size(); k++) if (isReference(o.get(k))) work.push(o.get(k));
                }
                case LOAD -> {
                    List<Instruction> st = idx.stores.getOrDefault(def.getLocalIndex(), List.of());
                    if (isParamSlot(f, def.getLocalIndex())) return "atomic write of a parameter slot in " + f.getName();
                    for (int k = 0; k < st.size(); k++) work.push(st.get(k).getOperands().getFirst());
                }
                case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                    CallShape s = escape.getCallShape(def, extractCalleeName(def));
                    if (s == null || !s.freshReturn()) return "atomic write of a non-fresh result of " + extractCalleeName(def) + " in " + f.getName();
                    int argc = argCount(def);
                    for (int j = 0; j < argc; j++) if (CallShape.has(s.returnedArgs(), j)) work.push(arg(def, j));
                }
                default -> {
                    return "atomic write of a " + def.getOpcode() + " result in " + f.getName();
                }
            }
        }
        for (Value a : aliases(f, v)) {
            List<Instruction> us = idx.users.getOrDefault(a, List.of());
            for (Instruction inst : us) {
                if (inst == op.inst()) continue;
                String why = escapeUse(f, inst, a, true);
                if (why != null) return "published value " + why + " in " + f.getName();
            }
            if (idx.returnedIn.containsKey(a)) return "published value returned from " + f.getName();
            if (idx.thrown.contains(a)) return "published value thrown in " + f.getName();
        }
        return null;
    }

    private String checkBorrow(Function f, Value seed, String fam) {
        Index idx = index(f);
        for (Value a : aliases(f, seed)) {
            if (idx.thrown.contains(a)) return "slot value thrown in " + f.getName();
            if (idx.returnedIn.containsKey(a) && !(seed instanceof Parameter)) {
                String why = propagateReturn(f, fam);
                if (why != null) return why;
            }
            List<Instruction> us = idx.users.getOrDefault(a, List.of());
            for (int u = 0; u < us.size(); u++) {
                String why = escapeUse(f, us.get(u), a, false);
                if (why != null) return "slot value " + why + " in " + f.getName();
            }
        }
        return null;
    }

    private String propagateReturn(Function f, String fam) {
        if (!returnPropagated.add(fam + "|" + f.getName())) return null;
        List<Instruction> sites = callSitesOf.getOrDefault(f, List.of());
        for (Instruction call : sites) {
            if (call.getResult() == null) continue;
            pendingBorrows.putIfAbsent(call, fam);
            String why = checkBorrow(call.getParent().getFunction(), call.getResult(), fam);
            if (why != null) return why;
        }
        return null;
    }

    private String escapeUse(Function f, Instruction inst, Value v, boolean publishing) {
        List<Value> o = inst.getOperands();
        switch (inst.getOpcode()) {
            case GET_FIELD, ALOAD, ARRAYLENGTH, INSTANCEOF, EQ, NE, MONITOR_ENTER, MONITOR_EXIT,
                 CAST, CHECKCAST, PHI, SELECT, STORE, LOAD -> {
                return null;
            }
            case ASTORE -> {
                return o.size() > 2 && o.get(2) == v ? "stored into an array" : null;
            }
            case PUT_FIELD -> {
                if (o.size() > 2 && o.get(2) == v) {
                    String key = extractFieldName(inst);
                    if (!publishing && fieldFamilies.contains(key)) return null;
                    return "stored into field " + key;
                }
                return null;
            }
            case PUT_STATIC -> {
                return "stored into a static field";
            }
            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                if (isAtomicOp(inst)) {
                    Op op = opOf(inst);
                    if (op == null) return "passed to an atomic operation";
                    if (op.kind() == Kind.UNKNOWN_WRITE) return "passed to " + extractCalleeName(inst);
                    if (op.newValue() == v && op.kind() != Kind.READ) return publishing ? "published twice" : "republished";
                    return null;
                }
                CallShape s = escape.getCallShape(inst, extractCalleeName(inst));
                if (s == null) return "passed to unresolved " + extractCalleeName(inst);
                int argc = argCount(inst);
                for (int j = 0; j < argc; j++) {
                    if (arg(inst, j) == v && CallShape.has(s.retainedArgs(), j)) return "retained by " + extractCalleeName(inst);
                }
                return null;
            }
            default -> {
                return "used by " + inst.getOpcode();
            }
        }
    }

    private final Map<Instruction, Op> opIndex = new IdentityHashMap<>();

    private Op opOf(Instruction inst) {
        if (opIndex.isEmpty()) for (int i = 0; i < ops.size(); i++) opIndex.put(ops.get(i).inst(), ops.get(i));
        return opIndex.get(inst);
    }

    private boolean isAtomicOp(Instruction inst) {
        String callee = extractCalleeName(inst);
        if (callee == null) return false;
        return callee.startsWith(ARA + ".") || callee.startsWith(AR + ".") || callee.startsWith(VH + ".") || callee.startsWith(ARFU)
            || callee.startsWith("jdk/internal/misc/Unsafe.") || callee.startsWith("sun/misc/Unsafe.");
    }

    private boolean unusedAfter(Op op, Value v) {
        Function f = op.inst().getParent().getFunction();
        Index idx = index(f);
        for (Value a : aliases(f, v)) {
            if (idx.returnedIn.containsKey(a) || idx.thrown.contains(a)) return false;
            List<Instruction> us = idx.users.getOrDefault(a, List.of());
            for (Instruction inst : us) {
                if (inst == op.inst()) continue;
                Opcode o = inst.getOpcode();
                if (o == Opcode.CAST || o == Opcode.CHECKCAST || o == Opcode.PHI || o == Opcode.SELECT
                    || o == Opcode.STORE || o == Opcode.LOAD) continue;
                if (reaches(op.inst(), inst)) return false;
            }
        }
        return true;
    }

    private boolean reaches(Instruction from, Instruction to) {
        BasicBlock a = from.getParent();
        BasicBlock z = to.getParent();
        List<Instruction> insts = a.getInstructions();
        if (a == z && insts.indexOf(from) < insts.indexOf(to)) return true;
        Set<BasicBlock> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<BasicBlock> work = new ArrayDeque<>();
        pushSuccessors(a, work);
        while (!work.isEmpty()) {
            BasicBlock b = work.poll();
            if (!seen.add(b)) continue;
            if (b == z) return true;
            pushSuccessors(b, work);
        }
        return false;
    }

    private static void pushSuccessors(BasicBlock b, ArrayDeque<BasicBlock> work) {
        for (int i = 0; i < b.getSuccessors().size(); i++) work.add(b.getSuccessors().get(i));
        for (int i = 0; i < b.getExceptionalSuccessors().size(); i++) work.add(b.getExceptionalSuccessors().get(i));
    }

    private Type useType(Function f, Value v) {
        Type direct = definiteType(v);
        if (direct != null) return direct;
        Index idx = index(f);
        Type found = null;
        for (Value a : aliases(f, v)) {
            List<Instruction> us = idx.users.getOrDefault(a, List.of());
            for (Instruction inst : us) {
                if (inst.getOpcode() != Opcode.CHECKCAST || inst.getResult() == null) continue;
                Type t = inst.getResult().getType();
                if (t == null) return null;
                if (found != null && !found.equals(t)) return null;
                found = t;
            }
        }
        return found;
    }

    private Type definiteType(Value v) {
        Set<Value> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Type found = null;
        ArrayDeque<Value> work = new ArrayDeque<>();
        work.push(v);
        while (!work.isEmpty()) {
            Value cur = work.pop();
            if (isNullConstant(cur) || !seen.add(cur)) continue;
            Type t = cur.getType();
            if (t != null && (t.isArray() || (t.isReference() && !mayBeArray(t)))) {
                if (found != null && !found.equals(t)) return null;
                found = t;
                continue;
            }
            if (cur instanceof Temporary tmp && tmp.getDefiningInstruction() != null) {
                Instruction def = tmp.getDefiningInstruction();
                switch (def.getOpcode()) {
                    case CAST, CHECKCAST, STORE -> work.push(def.getOperands().getFirst());
                    case LOAD -> {
                        Function f = def.getParent().getFunction();
                        if (isParamSlot(f, def.getLocalIndex())) return null;
                        List<Instruction> st = index(f).stores.getOrDefault(def.getLocalIndex(), List.of());
                        if (st.isEmpty()) return null;
                        for (int k = 0; k < st.size(); k++) work.push(st.get(k).getOperands().getFirst());
                    }
                    case PHI, SELECT -> {
                        for (int k = 0; k < def.getOperands().size(); k++) {
                            if (isReference(def.getOperands().get(k))) work.push(def.getOperands().get(k));
                        }
                    }
                    default -> {
                        Type pt = typeFromPointsTo(cur);
                        if (pt == null) return null;
                        if (found != null && !found.equals(pt)) return null;
                        found = pt;
                    }
                }
                continue;
            }
            Type pt = typeFromPointsTo(cur);
            if (pt == null) return null;
            if (found != null && !found.equals(pt)) return null;
            found = pt;
        }
        return found;
    }

    private Type typeFromPointsTo(Value v) {
        PointsToSet pts = alias.getPointsTo(v);
        if (pts == null || pts.isEmpty() || pts.containsUnknown()) return null;
        Type found = null;
        for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) {
            Type t = AllocationSite.byId(pts.id(c)).getType();
            if (t == null || t.isUnknown()) return null;
            if (found != null && !found.equals(t)) return null;
            found = t;
        }
        return found;
    }

    private boolean definiteArray(Value v) {
        Type t = definiteType(v);
        return t != null && t.isArray();
    }

    private static boolean mayBeArray(Type t) {
        if (t == null || t.isUnknown() || t.isNull()) return true;
        if (t.isArray()) return true;
        if (!t.isReference()) return false;
        String c = t.getClassName();
        return c == null || c.equals("java/lang/Object") || c.equals("java/lang/Cloneable") || c.equals("java/io/Serializable");
    }

    private boolean valueMayBeArray(Value v) {
        Type d = definiteType(v);
        if (d != null) return mayBeArray(d);
        PointsToSet pts = alias.getPointsTo(v);
        if (pts == null || pts.isEmpty() || pts.containsUnknown()) return true;
        for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) {
            Type t = AllocationSite.byId(pts.id(c)).getType();
            if (t == null || t.isUnknown() || t.isArray()) return true;
        }
        return false;
    }

    private boolean mayBeInstanceOf(Value v, String owner) {
        Type t = v.getType();
        if (t != null && !t.isUnknown() && (!t.isReference() || t.isArray())) return false;
        String c = t == null || t.isUnknown() ? null : t.getClassName();
        boolean staticMaybe;
        if (c == null || c.equals("java/lang/Object")) {
            staticMaybe = true;
        } else if (isSubtype(c, owner) || isSubtype(owner, c)) {
            return true;
        } else {
            ClassNode node = resolver.getClassNode(c);
            staticMaybe = node == null || (node.isInterface() && (implementsInterface(owner, c) || anySubclassImplements(owner, c)));
        }
        if (!staticMaybe) return false;
        PointsToSet pts = alias.getPointsTo(v);
        if (pts == null || pts.isEmpty() || pts.containsUnknown()) return true;
        for (long k = pts.cursor(); pts.has(k); k = pts.next(k)) {
            Type st = AllocationSite.byId(pts.id(k)).getType();
            if (st == null || st.isUnknown()) return true;
            if (st.isReference() && !st.isArray() && st.getClassName() != null && isSubtype(st.getClassName(), owner)) return true;
        }
        return false;
    }

    private static boolean staticAccessor(Op op) {
        String owner = op.inst().getParent().getFunction().getOwnerClass();
        return owner != null && owner.startsWith("jdk/internal/reflect/Unsafe") && owner.contains("Static")
            && owner.endsWith("FieldAccessorImpl");
    }

    private boolean anySubclassImplements(String owner, String iface) {
        for (String sub : resolver.getSubclasses(owner)) {
            if (implementsInterface(sub, iface)) return true;
        }
        return false;
    }

    private boolean serializableBelow(String owner) {
        if (implementsInterface(owner, "java/io/Serializable")) return true;
        for (String sub : resolver.getSubclasses(owner)) {
            if (implementsInterface(sub, "java/io/Serializable")) return true;
        }
        return false;
    }

    private boolean implementsInterface(String cls, String iface) {
        ArrayDeque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        work.add(cls);
        while (!work.isEmpty()) {
            String c = work.poll();
            if (c == null || !seen.add(c)) continue;
            if (c.equals(iface)) return true;
            ClassNode node = resolver.getClassNode(c);
            if (node == null) continue;
            if (node.getSuperName() != null) work.add(node.getSuperName());
            if (node.getInterfaces() != null) work.addAll(node.getInterfaces());
        }
        return false;
    }

    private boolean isSubtype(String cls, String owner) {
        for (String c = cls; c != null; ) {
            if (c.equals(owner)) return true;
            ClassNode node = resolver.getClassNode(c);
            c = node == null ? null : node.getSuperName();
        }
        return false;
    }

    private boolean isParamSlot(Function f, int slot) {
        List<Parameter> ps = f.getParameters();
        for (int i = 0; i < ps.size(); i++) if (ps.get(i).getIndex() == slot) return true;
        return false;
    }

    private Index index(Function f) {
        Index idx = indexes.get(f);
        if (idx != null) return idx;
        idx = new Index();
        for (BasicBlock block : f.getBlocks()) {
            List<Instruction> insts = block.getInstructions();
            for (Instruction inst : insts) {
                int slot = inst.getLocalIndex();
                if (inst.getOpcode() == Opcode.LOAD && slot >= 0 && inst.getResult() != null) {
                    idx.loads.computeIfAbsent(slot, k -> new ArrayList<>()).add(inst);
                }
                if (inst.getOpcode() == Opcode.STORE && slot >= 0 && !inst.getOperands().isEmpty()) {
                    idx.stores.computeIfAbsent(slot, k -> new ArrayList<>()).add(inst);
                }
                List<Value> o = inst.getOperands();
                for (Value v : o) {
                    if (v != null && !(v instanceof Constant)) idx.users.computeIfAbsent(v, x -> new ArrayList<>()).add(inst);
                }
            }
            Terminator term = block.getTerminator();
            if (term instanceof ReturnTerminator rt && rt.getValue() != null) {
                idx.returnedIn.computeIfAbsent(rt.getValue(), x -> new ArrayList<>()).add(block);
            } else if (term instanceof ThrowTerminator tt && tt.getException() != null) {
                idx.thrown.add(tt.getException());
            }
        }
        indexes.put(f, idx);
        return idx;
    }

    private Set<Value> aliases(Function f, Value seed) {
        Index idx = index(f);
        Set<Value> out = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Value> work = new ArrayDeque<>();
        work.push(seed);
        while (!work.isEmpty()) {
            Value v = work.pop();
            if (isNullConstant(v) || !out.add(v)) continue;
            if (v instanceof Parameter p) {
                List<Instruction> ld = idx.loads.getOrDefault(p.getIndex(), List.of());
                for (int k = 0; k < ld.size(); k++) work.push(ld.get(k).getResult());
            }
            List<Instruction> us = idx.users.getOrDefault(v, List.of());
            for (Instruction inst : us) {
                Value res = inst.getResult();
                switch (inst.getOpcode()) {
                    case CAST, CHECKCAST, PHI, SELECT -> {
                        if (res != null) work.push(res);
                    }
                    case STORE -> {
                        if (res != null) work.push(res);
                        List<Instruction> ld = idx.loads.getOrDefault(inst.getLocalIndex(), List.of());
                        for (int k = 0; k < ld.size(); k++) work.push(ld.get(k).getResult());
                    }
                    case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                        if (!isReference(res) || isAtomicOp(inst)) continue;
                        CallShape s = escape.getCallShape(inst, extractCalleeName(inst));
                        int argc = argCount(inst);
                        for (int j = 0; j < argc; j++) {
                            if (arg(inst, j) == v && (s == null || CallShape.has(s.returnedArgs(), j))) work.push(res);
                        }
                    }
                    default -> { }
                }
            }
        }
        return out;
    }

    private final Map<String, List<Function>> dispatchCache = new HashMap<>();

    private List<Function> resolveTargets(Instruction inst) {
        String callee = extractCalleeName(inst);
        if (callee == null) return List.of();
        Opcode op = inst.getOpcode();
        if (op != Opcode.VIRTUAL_CALL && op != Opcode.INTERFACE_CALL) {
            Function g = module.getFunction(canonicalCalleeKey(callee));
            return g != null && g.getEntryBlock() != null ? List.of(g) : List.of();
        }
        Value recv = inst.getOperands().isEmpty() ? null : inst.getOperands().getFirst();
        if (recv != null && recv.getType() != null && recv.getType().isArray()) {
            int p = callee.indexOf('(');
            int d = p < 0 ? -1 : callee.lastIndexOf('.', p);
            if (d <= 0) return List.of();
            Function g = module.getFunction(canonicalCalleeKey("java/lang/Object" + callee.substring(d)));
            return g != null && g.getEntryBlock() != null ? List.of(g) : List.of();
        }
        List<Function> cached = dispatchCache.get(callee);
        if (cached != null) return cached;
        List<Function> computed = dispatchTargets(callee);
        dispatchCache.put(callee, computed);
        return computed;
    }

    private List<Function> dispatchTargets(String callee) {
        int paren = callee.indexOf('(');
        int dot = paren < 0 ? -1 : callee.lastIndexOf('.', paren);
        if (dot <= 0) return List.of();
        String owner = callee.substring(0, dot);
        if (owner.startsWith("[")) return List.of();
        String name = callee.substring(dot + 1, paren);
        String descriptor = callee.substring(paren);
        Set<String> types = new HashSet<>(resolver.getSubclasses(owner));
        types.add(owner);
        Set<Function> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String type : types) {
            ClassNode node = resolver.getClassNode(type);
            if (node == null || node.isInterface() || (node.getAccess() & Opcodes.ACC_ABSTRACT) != 0) continue;
            String[] holder = new String[1];
            MethodNode impl = resolver.findMethodInHierarchy(type, name, descriptor, holder);
            if (impl == null || holder[0] == null || impl.isAbstract()) continue;
            Function g = module.getFunction(canonicalCalleeKey(holder[0] + "." + name + descriptor));
            if (g != null && g.getEntryBlock() != null) out.add(g);
        }
        return new ArrayList<>(out);
    }

    private static boolean isReference(Value v) {
        if (v == null || v.getType() == null) return false;
        Type t = v.getType();
        return t.isReference() || t.isArray() || t.isNull() || t.isUnknown();
    }

    private static boolean isNullConstant(Value v) {
        return v instanceof Constant c && c.getValue() == null;
    }

    private static int argCount(Instruction inst) {
        int n = inst.getOperands().size();
        return switch (inst.getOpcode()) {
            case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL -> n == 0 ? 0 : (n >= 2 ? n - 1 : 1);
            default -> Math.max(0, n - 1);
        };
    }

    private static Value arg(Instruction inst, int j) {
        List<Value> o = inst.getOperands();
        return switch (inst.getOpcode()) {
            case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL -> j == 0 ? o.getFirst() : o.get(j + 1);
            default -> o.get(j + 1);
        };
    }

    private static boolean isCall(Opcode op) {
        return op == Opcode.CALL || op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL
            || op == Opcode.STATIC_CALL || op == Opcode.SPECIAL_CALL;
    }
}
