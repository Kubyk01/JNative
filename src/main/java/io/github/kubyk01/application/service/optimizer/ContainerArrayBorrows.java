package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
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
import java.util.BitSet;
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

final class ContainerArrayBorrows {

    static final Set<String> CONTAINER_KEYS = Set.of(
        "java/util/ArrayList.elementData",
        "java/util/HashMap.table",
        "java/util/IdentityHashMap.table",
        "java/util/ArrayDeque.elements",
        "java/util/PriorityQueue.queue");

    private static final String NOT_CANDIDATE = "";

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult alias;
    private final EscapeAnalysisResult escape;
    private final Map<String, String> excluded = new HashMap<>();
    private final Set<String> sharedClasses = new HashSet<>();
    private boolean cloneAll;
    private final List<String> unknownClones = new ArrayList<>();
    private final Map<Function, Map<String, List<Instruction>>> writersCache = new IdentityHashMap<>();

    private final Map<String, String> canonical = new HashMap<>();
    private final Map<String, List<Instruction>> puts = new HashMap<>();
    private final Map<String, List<Instruction>> gets = new HashMap<>();
    private final Map<Instruction, List<Function>> targets = new IdentityHashMap<>();
    private final Map<Function, List<Function>> callers = new IdentityHashMap<>();
    private final Map<Function, List<Instruction>> callSitesOf = new IdentityHashMap<>();
    private final Set<String> returnPropagated = new HashSet<>();
    private final List<Borrow> roots = new ArrayList<>();
    private final Map<Function, FnIndex> indexes = new IdentityHashMap<>();
    private final Map<Function, BitSet> writes = new IdentityHashMap<>();
    private final Map<String, Integer> keyIds = new HashMap<>();
    private final Map<String, String> retireOnly = new HashMap<>();
    private final Set<String> heldParams = new HashSet<>();
    private final Map<String, Borrow> paramBorrows = new HashMap<>();
    private final Map<Instruction, Value> enters = new IdentityHashMap<>();
    private final Set<Function> exitFunctions = Collections.newSetFromMap(new IdentityHashMap<>());

    private record Borrow(Function fn, String key, Value seed, Instruction origin, Value owner) {}

    private static final class FnIndex {
        Function fn;
        final Map<Value, List<Instruction>> users = new IdentityHashMap<>();
        final Map<Integer, List<Instruction>> loads = new HashMap<>();
        final Map<Value, List<BasicBlock>> returnedIn = new IdentityHashMap<>();
        final Set<Value> thrown = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<BasicBlock, Integer> blockIds = new IdentityHashMap<>();
        final Map<BasicBlock, BitSet> reach = new IdentityHashMap<>();
        final Map<Instruction, Integer> position = new IdentityHashMap<>();
        final Map<Integer, SlotDefs> slotDefs = new HashMap<>();
        final Map<Value, Set<Value>> aliasCache = new IdentityHashMap<>();
    }

    private static final class SlotDefs {
        List<Instruction> stores;
        BitSet[] in;
        int entry;
    }

    ContainerArrayBorrows(Module module, DependencyResolver resolver, AliasAnalysisResult alias,
                          EscapeAnalysisResult escape) {
        this.module = module;
        this.resolver = resolver;
        this.alias = alias;
        this.escape = escape;
    }

    String instrumentedKey(Instruction fieldInst) {
        String key = keyOf(fieldInst);
        return key != null && puts.containsKey(key) && !excluded.containsKey(key) ? key : null;
    }

    String keyOf(Instruction fieldInst) {
        Opcode op = fieldInst.getOpcode();
        if (op != Opcode.GET_FIELD && op != Opcode.PUT_FIELD) return null;
        String raw = extractFieldName(fieldInst);
        String c = canonical.get(raw);
        if (c != null) return c.equals(NOT_CANDIDATE) ? null : c;
        c = NOT_CANDIDATE;
        int dot = raw.lastIndexOf('.');
        if (dot > 0) {
            FieldNode f = resolver.getField(raw.substring(0, dot), raw.substring(dot + 1));
            if (f != null && f.getOwner() != null && f.getDescriptor() != null && f.getDescriptor().startsWith("[")
                && (f.getAccess() & (Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE)) == 0
                && !f.getOwner().startsWith("java/util/concurrent/")) {
                c = f.getOwner() + "." + f.getName();
            }
        }
        canonical.put(raw, c);
        return c.equals(NOT_CANDIDATE) ? null : c;
    }

    boolean immediate(String key) {
        return !retireOnly.containsKey(key);
    }

    Value enterOwner(Instruction origin) {
        return enters.get(origin);
    }

    boolean needsExit(Function f) {
        return exitFunctions.contains(f);
    }

    void compute() {
        long started = System.nanoTime();
        scan();
        long scanned = System.nanoTime();
        for (Map.Entry<String, List<Instruction>> e : puts.entrySet()) {
            String key = e.getKey();
            if (CONTAINER_KEYS.contains(key)) continue;
            String owner = key.substring(0, key.lastIndexOf('.'));
            if (cloneAll && cloneableBelow(owner)) {
                excluded.put(key, "clone() of an unknown receiver may copy a Cloneable " + owner);
                continue;
            }
            for (String cls : sharedClasses) {
                if (isSubtype(cls, owner)) {
                    excluded.put(key, "instances of " + cls + " are shallow-cloned");
                    break;
                }
            }
            List<Instruction> ps = e.getValue();
            for (int i = 0; i < ps.size() && !excluded.containsKey(key); i++) checkStore(key, ps.get(i));
        }
        computeWrites();
        for (Map.Entry<String, List<Instruction>> e : gets.entrySet()) {
            if (!puts.containsKey(e.getKey()) || excluded.containsKey(e.getKey())) continue;
            List<Instruction> gs = e.getValue();
            for (int i = 0; i < gs.size(); i++) {
                Instruction g = gs.get(i);
                if (g.getResult() == null) continue;
                roots.add(new Borrow(g.getParent().getFunction(), e.getKey(), g.getResult(), g, g.getOperands().getFirst()));
            }
        }
        long escapeStart = System.nanoTime();
        ArrayDeque<Borrow> work = new ArrayDeque<>(roots);
        while (!work.isEmpty()) checkEscape(work.poll(), work);
        long heldStart = System.nanoTime();
        ArrayDeque<Borrow> pending = new ArrayDeque<>(paramBorrows.values());
        Set<String> queued = new HashSet<>(paramBorrows.keySet());
        while (!pending.isEmpty()) {
            Borrow b = pending.poll();
            String id = paramId(b.fn(), ((Parameter) b.seed()).getOrdinal(), b.key());
            queued.remove(id);
            if (heldParams.contains(id)) continue;
            currentDependent = id;
            boolean h = held(b);
            currentDependent = null;
            if (!h) continue;
            heldParams.add(id);
            List<String> deps = dependents.getOrDefault(id, List.of());
            for (int i = 0; i < deps.size(); i++) {
                String d = deps.get(i);
                if (!heldParams.contains(d) && queued.add(d)) pending.add(paramBorrows.get(d));
            }
        }
        long rootsStart = System.nanoTime();
        for (int i = 0; i < roots.size(); i++) {
            Borrow b = roots.get(i);
            if (held(b)) {
                if (b.owner() == null) {
                    retireOnly.putIfAbsent(b.key(), "borrowed array returned by a static call in " + b.fn().getName());
                    continue;
                }
                enters.put(b.origin(), b.owner());
                exitFunctions.add(b.fn());
            }
        }
        for (String key : puts.keySet()) {
            if (!CONTAINER_KEYS.contains(key) && retireOnly.containsKey(key)) excluded.putIfAbsent(key, retireOnly.get(key));
        }
        int freed = 0;
        for (String key : puts.keySet()) if (!excluded.containsKey(key)) freed++;
        Map<String, Integer> reasons = new HashMap<>();
        Map<String, String> examples = new HashMap<>();
        for (Map.Entry<String, String> ex : excluded.entrySet()) {
            String kind = ex.getValue().replaceAll(" in fn_.*$", "").replaceAll(" in .*$", "");
            reasons.merge(kind, 1, Integer::sum);
            examples.putIfAbsent(kind, ex.getKey() + ": " + ex.getValue());
        }
        reasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
            .forEach(e -> System.out.println("ContainerArrayBorrows: excluded " + e.getValue() + " field(s): " + e.getKey()
                + " | e.g. " + examples.get(e.getKey())));
        for (String key : CONTAINER_KEYS) {
            String mode = retireOnly.containsKey(key)
                ? "free with the owner (" + retireOnly.get(key) + ")"
                : "free immediately when unborrowed";
            System.out.println("ContainerArrayBorrows: " + key + " -> " + mode);
        }
        System.out.println("ContainerArrayBorrows: " + freed + " of " + puts.size() + " array field(s) free old arrays on overwrite, "
            + enters.size() + " borrow scope(s) in " + exitFunctions.size() + " function(s), scan "
            + (scanned - started) / 1_000_000 + " ms, escape " + (heldStart - escapeStart) / 1_000_000 + " ms, params "
            + (rootsStart - heldStart) / 1_000_000 + " ms, total " + (System.nanoTime() - started) / 1_000_000 + " ms");
        int shown = 0;
        for (Map.Entry<String, String> ex : excluded.entrySet()) {
            String owner = ex.getKey().substring(0, ex.getKey().lastIndexOf('.'));
            String filter = System.getenv("JN_OWN_DEBUG");
            if (filter != null ? !ex.getKey().contains(filter) : (io.github.kubyk01.util.LlvmUtil.isSystemClassName(owner) || shown++ >= 40)) continue;
            System.out.println("ContainerArrayBorrows: kept " + ex.getKey() + ": " + ex.getValue());
        }
        String keyFilter = System.getenv("JN_OWN_DEBUG");
        if (keyFilter != null) {
            for (String key : puts.keySet()) {
                if (key.contains(keyFilter)) System.out.println("ContainerArrayBorrows: key " + key + " excluded=" + excluded.get(key)
                    + " retireOnly=" + retireOnly.get(key) + " puts=" + puts.get(key).size());
            }
        }
        for (int i = 0; i < unknownClones.size() && i < 10; i++) System.out.println("ContainerArrayBorrows: unknown clone receiver in " + unknownClones.get(i));
        indexes.clear();
        writersCache.clear();
    }

    static boolean isDestructor(Function f) {
        return f.getName().startsWith("__destruct_");
    }

    private static String paramId(Function f, int ordinal, String key) {
        return key + "|" + f.getName() + "|" + ordinal;
    }

    private void scan() {
        List<? extends Function> fns = module.getFunctions();
        for (int fi = 0; fi < fns.size(); fi++) {
            Function f = fns.get(fi);
            if (f.getEntryBlock() == null || isDestructor(f)) continue;
            for (int bi = 0; bi < f.getBlocks().size(); bi++) {
                BasicBlock block = f.getBlocks().get(bi);
                for (int ii = 0; ii < block.getInstructions().size(); ii++) {
                    Instruction inst = block.getInstructions().get(ii);
                    switch (inst.getOpcode()) {
                        case PUT_FIELD -> {
                            String key = keyOf(inst);
                            if (key != null) puts.computeIfAbsent(key, k -> new ArrayList<>()).add(inst);
                        }
                        case GET_FIELD -> {
                            String key = keyOf(inst);
                            if (key != null) gets.computeIfAbsent(key, k -> new ArrayList<>()).add(inst);
                        }
                        case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                            List<Function> ts = resolveTargets(inst);
                            targets.put(inst, ts);
                            for (int t = 0; t < ts.size(); t++) {
                                callers.computeIfAbsent(ts.get(t), k -> new ArrayList<>()).add(f);
                                callSitesOf.computeIfAbsent(ts.get(t), k -> new ArrayList<>()).add(inst);
                            }
                            noteClone(inst);
                        }
                        default -> { }
                    }
                }
            }
        }
    }

    private void noteClone(Instruction inst) {
        String callee = extractCalleeName(inst);
        if (callee == null || !callee.endsWith(".clone()Ljava/lang/Object;")) return;
        String owner = callee.substring(0, callee.lastIndexOf('.', callee.indexOf('(')));
        if (owner.startsWith("[") || inst.getOperands().isEmpty()) return;
        Value recv = inst.getOperands().getFirst();
        if (recv == null) return;
        Set<String> classes = new HashSet<>();
        PointsToSet pts = alias.getPointsTo(recv);
        if (!pts.isEmpty() && !pts.containsUnknown()) {
            for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) {
                Type t = AllocationSite.byId(pts.id(c)).getType();
                if (t != null && t.isReference() && t.getClassName() != null) classes.add(t.getClassName());
            }
        } else {
            Type st = recv.getType();
            if (st == null || st.isArray()) return;
            String cls = st.isReference() ? st.getClassName() : null;
            ClassNode node = cls == null ? null : resolver.getClassNode(cls);
            if (cls == null || cls.equals("java/lang/Object") || node == null || node.isInterface()) {
                cloneAll = true;
                unknownClones.add(inst.getParent().getFunction().getName() + " (" + st + ")");
                return;
            }
            classes.add(cls);
            classes.addAll(resolver.getSubclasses(cls));
        }
        for (String cls : classes) {
            String[] holder = new String[1];
            MethodNode impl = resolver.findMethodInHierarchy(cls, "clone", "()Ljava/lang/Object;", holder);
            if ((impl == null || "java/lang/Object".equals(holder[0]) || inst.getOpcode() == Opcode.SPECIAL_CALL)
                && isCloneable(cls)) {
                sharedClasses.add(cls);
            }
        }
    }

    private final Map<String, Boolean> cloneableCache = new HashMap<>();

    private boolean isCloneable(String cls) {
        Boolean cached = cloneableCache.get(cls);
        if (cached != null) return cached;
        boolean result = false;
        ArrayDeque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        work.add(cls);
        while (!work.isEmpty() && !result) {
            String c = work.poll();
            if (c == null || !seen.add(c)) continue;
            if (c.equals("java/lang/Cloneable")) {
                result = true;
                break;
            }
            ClassNode node = resolver.getClassNode(c);
            if (node == null) continue;
            if (node.getSuperName() != null) work.add(node.getSuperName());
            if (node.getInterfaces() != null) work.addAll(node.getInterfaces());
        }
        cloneableCache.put(cls, result);
        return result;
    }

    private boolean cloneableBelow(String owner) {
        if (isCloneable(owner)) return true;
        for (String sub : resolver.getSubclasses(owner)) {
            if (isCloneable(sub)) return true;
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

    private void checkStore(String key, Instruction put) {
        Function f = put.getParent().getFunction();
        Value stored = put.getOperands().get(2);
        if (stored == null || isNullConstant(stored)) return;
        FnIndex idx = index(f);
        List<Instruction> defs = new ArrayList<>();
        Set<Value> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Value> work = new ArrayDeque<>();
        work.push(stored);
        while (!work.isEmpty()) {
            Value v = work.pop();
            if (isNullConstant(v) || !seen.add(v)) continue;
            if (!(v instanceof Temporary t) || t.getDefiningInstruction() == null) {
                excluded.put(key, "stores a parameter or constant in " + f.getName());
                return;
            }
            Instruction def = t.getDefiningInstruction();
            List<Value> ops = def.getOperands();
            switch (def.getOpcode()) {
                case NEW_ARRAY, MULTI_NEW_ARRAY -> defs.add(def);
                case CAST, CHECKCAST, STORE -> work.push(ops.getFirst());
                case PHI, SELECT -> {
                    for (int k = 0; k < ops.size(); k++) if (isReference(ops.get(k))) work.push(ops.get(k));
                }
                case LOAD -> {
                    if (loadSeesEntry(idx, def)) {
                        excluded.put(key, "stores a value loaded from a parameter slot in " + f.getName());
                        return;
                    }
                    List<Instruction> st = reachingStores(idx, def);
                    for (int k = 0; k < st.size(); k++) work.push(st.get(k).getOperands().getFirst());
                }
                case GET_STATIC -> {
                    if (!isEmptySentinel(extractFieldName(def))) {
                        excluded.put(key, "stores static " + extractFieldName(def) + " in " + f.getName());
                        return;
                    }
                }
                case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                    CallShape s = escape.getCallShape(def, extractCalleeName(def));
                    if (s == null || !s.freshReturn()) {
                        excluded.put(key, "stores non-fresh result of " + extractCalleeName(def) + " in " + f.getName());
                        return;
                    }
                    defs.add(def);
                    int argc = argCount(def);
                    for (int j = 0; j < argc; j++) if (CallShape.has(s.returnedArgs(), j)) work.push(arg(def, j));
                }
                default -> {
                    excluded.put(key, "stores " + def.getOpcode() + " result in " + f.getName());
                    return;
                }
            }
        }
        if (reaches(idx, put, put)) {
            for (int d = 0; d < defs.size(); d++) {
                Instruction def = defs.get(d);
                if (def.getParent() != put.getParent() || idx.position.get(def) > idx.position.get(put)) {
                    excluded.put(key, "store inside a loop of a value allocated outside it in " + f.getName());
                    return;
                }
            }
        }
        for (Value v : aliasesOf(f, stored)) {
            List<Instruction> us = idx.users.getOrDefault(v, List.of());
            for (int u = 0; u < us.size(); u++) {
                Instruction inst = us.get(u);
                if (inst == put) continue;
                Opcode op = inst.getOpcode();
                List<Value> ops = inst.getOperands();
                boolean storedElsewhere = (op == Opcode.PUT_FIELD || op == Opcode.ASTORE) && ops.size() > 2 && ops.get(2) == v;
                boolean retained = false;
                if (isCall(op)) {
                    CallShape s = escape.getCallShape(inst, extractCalleeName(inst));
                    int argc = argCount(inst);
                    for (int j = 0; j < argc; j++) {
                        if (arg(inst, j) == v && (s == null || CallShape.has(s.retainedArgs(), j))) retained = true;
                    }
                }
                if (storedElsewhere || retained || idx.thrown.contains(v)) {
                    excluded.put(key, "stored array also kept elsewhere in " + f.getName());
                    return;
                }
            }
        }
    }

    private Set<Value> aliasesOf(Function f, Value v) {
        return aliases(new Borrow(f, "", v, null, null));
    }

    private boolean isEmptySentinel(String staticField) {
        PointsToSet pts = alias.getStaticFieldPointsTo(staticField);
        if (pts == null || pts.isEmpty() || pts.containsUnknown()) return false;
        Map<AllocationSite, Value> siteValues = alias.getAllocationSiteToValue();
        for (long c = pts.cursor(); pts.has(c); c = pts.next(c)) {
            Value v = siteValues.get(AllocationSite.byId(pts.id(c)));
            if (!(v instanceof Temporary t) || t.getDefiningInstruction() == null) return false;
            Instruction def = t.getDefiningInstruction();
            if (def.getOpcode() != Opcode.NEW_ARRAY || def.getOperands().isEmpty()) return false;
            if (!(def.getOperands().getFirst() instanceof Constant len)
                || !(len.getValue() instanceof Number n) || n.intValue() != 0) return false;
        }
        return true;
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

    private void computeWrites() {
        int next = 0;
        for (String key : puts.keySet()) {
            if (!excluded.containsKey(key)) keyIds.put(key, next++);
        }
        Map<Function, Set<Function>> uniqueCallers = new IdentityHashMap<>();
        for (Map.Entry<Function, List<Function>> e : callers.entrySet()) {
            Set<Function> set = Collections.newSetFromMap(new IdentityHashMap<>());
            set.addAll(e.getValue());
            uniqueCallers.put(e.getKey(), set);
        }
        ArrayDeque<Function> work = new ArrayDeque<>();
        Set<Function> queued = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, List<Instruction>> e : puts.entrySet()) {
            Integer id = keyIds.get(e.getKey());
            if (id == null) continue;
            List<Instruction> ps = e.getValue();
            for (int i = 0; i < ps.size(); i++) {
                Function f = ps.get(i).getParent().getFunction();
                writes.computeIfAbsent(f, k -> new BitSet()).set(id);
                if (queued.add(f)) work.add(f);
            }
        }
        BitSet scratch = new BitSet();
        while (!work.isEmpty()) {
            Function f = work.poll();
            queued.remove(f);
            BitSet bs = writes.get(f);
            for (Function c : uniqueCallers.getOrDefault(f, Set.of())) {
                BitSet cb = writes.computeIfAbsent(c, k -> new BitSet());
                scratch.clear();
                scratch.or(bs);
                scratch.andNot(cb);
                if (scratch.isEmpty()) continue;
                cb.or(scratch);
                if (queued.add(c)) work.add(c);
            }
        }
    }

    private boolean writesKey(Instruction inst, String key) {
        if (inst.getOpcode() == Opcode.PUT_FIELD) return key.equals(keyOf(inst));
        if (!isCall(inst.getOpcode())) return false;
        Integer boxed = keyIds.get(key);
        if (boxed == null) return false;
        int id = boxed;
        List<Function> ts = targets.getOrDefault(inst, List.of());
        for (int t = 0; t < ts.size(); t++) {
            BitSet bs = writes.get(ts.get(t));
            if (bs != null && bs.get(id)) return true;
        }
        return false;
    }

    private void checkEscape(Borrow b, ArrayDeque<Borrow> work) {
        FnIndex idx = index(b.fn());
        String key = b.key();
        for (Value v : aliases(b)) {
            if (idx.thrown.contains(v)) {
                retireOnly.putIfAbsent(key, "array thrown in " + b.fn().getName());
                return;
            }
            if (idx.returnedIn.containsKey(v) && !(b.seed() instanceof Parameter)) {
                propagateReturn(b.fn(), key, work);
            }
            List<Instruction> us = idx.users.get(v);
            if (us == null) continue;
            for (int u = 0; u < us.size(); u++) {
                Instruction inst = us.get(u);
                List<Value> ops = inst.getOperands();
                for (int k = 0; k < ops.size(); k++) {
                    if (ops.get(k) != v) continue;
                    String why = escapes(b, inst, k, v, work);
                    if (why != null) {
                        retireOnly.putIfAbsent(key, why + " in " + b.fn().getName());
                        return;
                    }
                }
            }
        }
    }

    private String escapes(Borrow b, Instruction inst, int k, Value v, ArrayDeque<Borrow> work) {
        switch (inst.getOpcode()) {
            case GET_FIELD, ALOAD, ARRAYLENGTH, INSTANCEOF, EQ, NE, MONITOR_ENTER, MONITOR_EXIT,
                 CAST, CHECKCAST, PHI, SELECT, STORE -> {
                return null;
            }
            case ASTORE -> {
                return k == 0 ? null : "array stored into another array";
            }
            case PUT_FIELD -> {
                return k == 0 ? null : "array stored into field " + extractFieldName(inst);
            }
            case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                CallShape s = escape.getCallShape(inst, extractCalleeName(inst));
                if (s == null) return "array passed to unresolved call " + extractCalleeName(inst);
                int argc = argCount(inst);
                for (int j = 0; j < argc; j++) {
                    if (arg(inst, j) != v) continue;
                    if (CallShape.has(s.retainedArgs(), j)) return "array retained by " + extractCalleeName(inst);
                    List<Function> ts = targets.getOrDefault(inst, List.of());
                    for (int t = 0; t < ts.size(); t++) borrowParam(ts.get(t), j, b.key(), work);
                }
                return null;
            }
            default -> {
                return "array used by " + inst.getOpcode();
            }
        }
    }

    private void propagateReturn(Function f, String key, ArrayDeque<Borrow> work) {
        if (!returnPropagated.add(key + "|" + f.getName())) return;
        List<Instruction> sites = callSitesOf.getOrDefault(f, List.of());
        for (int i = 0; i < sites.size(); i++) {
            Instruction call = sites.get(i);
            if (call.getResult() == null) continue;
            Opcode op = call.getOpcode();
            Value owner = op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL || op == Opcode.SPECIAL_CALL
                ? call.getOperands().getFirst() : null;
            Borrow nb = new Borrow(call.getParent().getFunction(), key, call.getResult(), call, owner);
            roots.add(nb);
            work.add(nb);
        }
    }

    private void borrowParam(Function callee, int ordinal, String key, ArrayDeque<Borrow> work) {
        String id = paramId(callee, ordinal, key);
        if (paramBorrows.containsKey(id)) return;
        for (int p = 0; p < callee.getParameters().size(); p++) {
            Parameter param = callee.getParameters().get(p);
            if (param.getOrdinal() == ordinal) {
                Borrow nb = new Borrow(callee, key, param, null, null);
                paramBorrows.put(id, nb);
                work.add(nb);
                return;
            }
        }
    }

    private boolean held(Borrow b) {
        Function f = b.fn();
        FnIndex idx = index(f);
        Set<Value> as = aliases(b);
        List<Instruction> writers = writersCache.computeIfAbsent(f, k -> new HashMap<>()).get(b.key());
        if (writers == null) {
            writers = new ArrayList<>();
            for (int bi = 0; bi < f.getBlocks().size(); bi++) {
                List<Instruction> insts = f.getBlocks().get(bi).getInstructions();
                for (int ii = 0; ii < insts.size(); ii++) {
                    Instruction inst = insts.get(ii);
                    if (writesKey(inst, b.key())) writers.add(inst);
                }
            }
            writersCache.get(f).put(b.key(), writers);
        }
        for (Value v : as) {
            List<Instruction> us = idx.users.getOrDefault(v, List.of());
            for (int u = 0; u < us.size(); u++) {
                Instruction use = us.get(u);
                if (isCall(use.getOpcode()) && passesToHeldParam(use, v, b.key())) return true;
            }
            if (writers.isEmpty()) continue;
            for (int w = 0; w < writers.size(); w++) {
                Instruction writer = writers.get(w);
                if (b.origin() != null && !reaches(idx, b.origin(), writer)) continue;
                for (int u = 0; u < us.size(); u++) {
                    Instruction use = us.get(u);
                    if (use != writer && reaches(idx, writer, use)) return true;
                }
                List<BasicBlock> rets = idx.returnedIn.getOrDefault(v, List.of());
                for (int r = 0; r < rets.size(); r++) {
                    if (reachesBlockEnd(idx, writer, rets.get(r))) return true;
                }
            }
        }
        return false;
    }

    private String currentDependent;
    private final Map<String, List<String>> dependents = new HashMap<>();

    private boolean passesToHeldParam(Instruction call, Value v, String key) {
        int argc = argCount(call);
        List<Function> ts = targets.getOrDefault(call, List.of());
        for (int j = 0; j < argc; j++) {
            if (arg(call, j) != v) continue;
            for (int t = 0; t < ts.size(); t++) {
                String pid = paramId(ts.get(t), j, key);
                if (heldParams.contains(pid)) return true;
                if (currentDependent != null && paramBorrows.containsKey(pid)) {
                    dependents.computeIfAbsent(pid, k -> new ArrayList<>()).add(currentDependent);
                }
            }
        }
        return false;
    }

    private FnIndex index(Function f) {
        FnIndex idx = indexes.get(f);
        if (idx != null) return idx;
        idx = new FnIndex();
        idx.fn = f;
        for (int bi = 0; bi < f.getBlocks().size(); bi++) {
            BasicBlock block = f.getBlocks().get(bi);
            idx.blockIds.put(block, bi);
            List<Instruction> insts = block.getInstructions();
            for (int ii = 0; ii < insts.size(); ii++) {
                Instruction inst = insts.get(ii);
                idx.position.put(inst, ii);
                List<Value> ops = inst.getOperands();
                int slot = inst.getLocalIndex();
                if (inst.getOpcode() == Opcode.LOAD && slot >= 0 && inst.getResult() != null) {
                    idx.loads.computeIfAbsent(slot, k -> new ArrayList<>()).add(inst);
                }
                for (int k = 0; k < ops.size(); k++) {
                    Value v = ops.get(k);
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

    private Set<Value> aliases(Borrow b) {
        FnIndex idx = index(b.fn());
        Set<Value> cached = idx.aliasCache.get(b.seed());
        if (cached != null) return cached;
        Set<Value> out = computeAliases(b, idx);
        idx.aliasCache.put(b.seed(), out);
        return out;
    }

    private Set<Value> computeAliases(Borrow b, FnIndex idx) {
        Set<Value> out = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Value> work = new ArrayDeque<>();
        work.push(b.seed());
        while (!work.isEmpty()) {
            Value v = work.pop();
            if (v == null || isNullConstant(v) || !out.add(v)) continue;
            if (v instanceof Parameter param) {
                List<Instruction> ld = idx.loads.get(param.getIndex());
                if (ld != null) {
                    for (int k = 0; k < ld.size(); k++) {
                        if (loadSeesEntry(idx, ld.get(k))) work.push(ld.get(k).getResult());
                    }
                }
            }
            List<Instruction> us = idx.users.get(v);
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
                        List<Instruction> ld = idx.loads.get(inst.getLocalIndex());
                        if (ld != null && inst.getOperands().getFirst() == v) {
                            for (int k = 0; k < ld.size(); k++) {
                                if (reachingStores(idx, ld.get(k)).contains(inst)) work.push(ld.get(k).getResult());
                            }
                        }
                    }
                    case CALL, VIRTUAL_CALL, INTERFACE_CALL, STATIC_CALL, SPECIAL_CALL -> {
                        CallShape s = escape.getCallShape(inst, extractCalleeName(inst));
                        if (!isReference(res)) continue;
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
        List<Value> ops = inst.getOperands();
        return switch (inst.getOpcode()) {
            case VIRTUAL_CALL, INTERFACE_CALL, SPECIAL_CALL -> j == 0 ? ops.getFirst() : ops.get(j + 1);
            default -> ops.get(j + 1);
        };
    }

    private static boolean isCall(Opcode op) {
        return op == Opcode.CALL || op == Opcode.VIRTUAL_CALL || op == Opcode.INTERFACE_CALL
            || op == Opcode.STATIC_CALL || op == Opcode.SPECIAL_CALL;
    }

    private boolean reaches(FnIndex idx, Instruction from, Instruction to) {
        BasicBlock a = from.getParent();
        BasicBlock z = to.getParent();
        if (a == z && idx.position.get(from) < idx.position.get(to)) return true;
        return reachableFrom(idx, a).get(idx.blockIds.get(z));
    }

    private boolean reachesBlockEnd(FnIndex idx, Instruction from, BasicBlock end) {
        return from.getParent() == end || reachableFrom(idx, from.getParent()).get(idx.blockIds.get(end));
    }

    private BitSet reachableFrom(FnIndex idx, BasicBlock start) {
        BitSet cached = idx.reach.get(start);
        if (cached != null) return cached;
        BitSet out = new BitSet();
        ArrayDeque<BasicBlock> work = new ArrayDeque<>();
        pushSuccessors(start, work);
        while (!work.isEmpty()) {
            BasicBlock b = work.poll();
            Integer id = idx.blockIds.get(b);
            if (id == null || out.get(id)) continue;
            out.set(id);
            pushSuccessors(b, work);
        }
        idx.reach.put(start, out);
        return out;
    }

    private List<Instruction> reachingStores(FnIndex idx, Instruction load) {
        SlotDefs d = slotDefs(idx, load.getLocalIndex());
        BitSet r = reachingBits(idx, d, load);
        List<Instruction> out = new ArrayList<>();
        for (int i = r.nextSetBit(0); i >= 0 && i < d.entry; i = r.nextSetBit(i + 1)) out.add(d.stores.get(i));
        return out;
    }

    private boolean loadSeesEntry(FnIndex idx, Instruction load) {
        SlotDefs d = slotDefs(idx, load.getLocalIndex());
        return reachingBits(idx, d, load).get(d.entry);
    }

    private BitSet reachingBits(FnIndex idx, SlotDefs d, Instruction load) {
        BasicBlock block = load.getParent();
        int pos = idx.position.get(load);
        List<Instruction> insts = block.getInstructions();
        for (int i = pos - 1; i >= 0; i--) {
            Instruction prev = insts.get(i);
            if (prev.getOpcode() == Opcode.STORE && prev.getLocalIndex() == load.getLocalIndex()) {
                int si = d.stores.indexOf(prev);
                BitSet one = new BitSet();
                one.set(si >= 0 ? si : d.entry);
                return one;
            }
        }
        return d.in[idx.blockIds.get(block)];
    }

    private SlotDefs slotDefs(FnIndex idx, int slot) {
        SlotDefs d = idx.slotDefs.get(slot);
        if (d != null) return d;
        d = new SlotDefs();
        d.stores = new ArrayList<>();
        Function f = idx.fn;
        int nb = f.getBlocks().size();
        int[] last = new int[nb];
        BitSet[] all = new BitSet[nb];
        for (int bi = 0; bi < nb; bi++) {
            last[bi] = -1;
            all[bi] = new BitSet();
            List<Instruction> insts = f.getBlocks().get(bi).getInstructions();
            for (int ii = 0; ii < insts.size(); ii++) {
                Instruction inst = insts.get(ii);
                if (inst.getOpcode() == Opcode.STORE && inst.getLocalIndex() == slot) {
                    last[bi] = d.stores.size();
                    all[bi].set(d.stores.size());
                    d.stores.add(inst);
                }
            }
        }
        d.entry = d.stores.size();
        d.in = new BitSet[nb];
        for (int bi = 0; bi < nb; bi++) d.in[bi] = new BitSet();
        Integer entryId = idx.blockIds.get(f.getEntryBlock());
        ArrayDeque<Integer> work = new ArrayDeque<>();
        if (entryId != null) {
            d.in[entryId].set(d.entry);
            work.add(entryId);
        }
        while (!work.isEmpty()) {
            int bi = work.poll();
            BasicBlock block = f.getBlocks().get(bi);
            BitSet out = new BitSet();
            if (last[bi] >= 0) out.set(last[bi]); else out.or(d.in[bi]);
            BitSet exc = (BitSet) d.in[bi].clone();
            exc.or(all[bi]);
            for (int i = 0; i < block.getSuccessors().size(); i++) flow(idx, d, block.getSuccessors().get(i), out, work);
            for (int i = 0; i < block.getExceptionalSuccessors().size(); i++) flow(idx, d, block.getExceptionalSuccessors().get(i), exc, work);
        }
        idx.slotDefs.put(slot, d);
        return d;
    }

    private static void flow(FnIndex idx, SlotDefs d, BasicBlock succ, BitSet bits, ArrayDeque<Integer> work) {
        Integer si = idx.blockIds.get(succ);
        if (si == null) return;
        BitSet in = d.in[si];
        int before = in.cardinality();
        in.or(bits);
        if (in.cardinality() != before) work.add(si);
    }

    private static void pushSuccessors(BasicBlock b, ArrayDeque<BasicBlock> work) {
        work.addAll(b.getSuccessors());
        work.addAll(b.getExceptionalSuccessors());
    }
}
