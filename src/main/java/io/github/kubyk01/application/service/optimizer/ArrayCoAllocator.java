package io.github.kubyk01.application.service.optimizer;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AllocationSite;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeAnalysisResult;
import io.github.kubyk01.domain.analyzer.escapeanalysis.EscapeStatus;
import io.github.kubyk01.domain.analyzer.lifetime.DestructionPoint;
import io.github.kubyk01.domain.analyzer.lifetime.LifetimeAnalysisResult;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.util.LlvmUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class ArrayCoAllocator {

    private static final Set<Opcode> PURE_OPS = EnumSet.of(
        Opcode.ADD, Opcode.SUB, Opcode.MUL, Opcode.AND, Opcode.OR, Opcode.XOR,
        Opcode.SHL, Opcode.SHR, Opcode.USHR, Opcode.CAST);

    private static final Set<String> PURE_CALLS = Set.of(
        "java/lang/Math.max(II)I", "java/lang/Math.min(II)I");

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult aliasResult;
    private final EscapeAnalysisResult escapeResult;
    private final LifetimeAnalysisResult lifetimeResult;
    private final ReflectInfo reflectInfo;

    private final Map<Value, List<Instruction>> uses = new HashMap<>();
    private final Set<Value> terminatorValues = new HashSet<>();
    private final Map<String, List<Instruction>> newsByClass = new HashMap<>();
    private final Set<String> indyMentioned = new HashSet<>();

    private record Candidate(String className, String fieldKey, String ctorCallee,
                             Function ctor, Instruction newArray, Value length) {}

    private record Site(Instruction newInst, Instruction call) {}

    public Set<String> apply() {
        scanModule();
        Set<String> coAllocated = new LinkedHashSet<>();
        for (Map.Entry<String, List<Instruction>> e : newsByClass.entrySet()) {
            Candidate c = findCandidate(e.getKey());
            if (c == null) continue;
            List<Site> sites = new ArrayList<>();
            boolean ok = true;
            for (Instruction n : e.getValue()) {
                Site s = matchSite(n, c);
                if (s == null) { ok = false; break; }
                sites.add(s);
            }
            if (!ok || sites.isEmpty()) continue;
            for (Site s : sites) rewriteSite(s, c);
            Parameter thisParam = c.ctor().getParameters().getFirst();
            c.newArray().addOperand(thisParam);
            c.newArray().addOperand(new Constant(Type.reference(c.className()), c.className()));
            coAllocated.add(c.fieldKey());
        }
        if (!coAllocated.isEmpty()) {
            log.info("ArrayCoAllocator: co-allocated {} array field(s): {}",
                coAllocated.size(), coAllocated);
        }
        return coAllocated;
    }

    private void scanModule() {
        for (Function f : module.getFunctions()) {
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    for (Value op : inst.getOperands()) {
                        uses.computeIfAbsent(op, k -> new ArrayList<>()).add(inst);
                    }
                    if (inst.getOpcode() == Opcode.NEW && !inst.getOperands().isEmpty()
                        && inst.getOperands().getFirst() instanceof Constant c
                        && c.getType().isReference()) {
                        newsByClass.computeIfAbsent(c.getValue().toString(),
                            k -> new ArrayList<>()).add(inst);
                    }
                    if (inst.getOpcode() == Opcode.INVOKEDYNAMIC) {
                        recordIndy(inst.getInvokedynamicData());
                    }
                }
                if (b.getTerminator() instanceof ReturnTerminator rt && rt.getValue() != null) {
                    terminatorValues.add(rt.getValue());
                } else if (b.getTerminator() instanceof ThrowTerminator tt && tt.getException() != null) {
                    terminatorValues.add(tt.getException());
                }
            }
        }
    }

    private void recordIndy(Object data) {
        if (data instanceof InvokeDynamicInfo info) {
            if (info.bootstrapArgs() == null) return;
            for (Object arg : info.bootstrapArgs()) {
                if (arg instanceof Handle h) indyMentioned.add(h.getOwner());
            }
        } else if (data != null) {
            indyMentioned.add(data.toString());
        }
    }

    private boolean mentionedByIndy(String className) {
        for (String s : indyMentioned) {
            if (s.contains(className)) return true;
        }
        return false;
    }

    private Candidate findCandidate(String className) {
        if (LlvmUtil.isSystemClassName(className)) return null;
        if (mentionedByIndy(className)) return null;
        ReflectClassInfo reflectClassInfo = reflectInfo.getClassInfoMap().get(className);
        if (reflectClassInfo != null && !reflectClassInfo.getConstructors().isEmpty()) return null;
        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isExternal() || cn.isInterface()) return null;
        int access = cn.getAccess();
        if ((access & Opcodes.ACC_FINAL) == 0 || (access & Opcodes.ACC_ABSTRACT) != 0) return null;
        if (!"java/lang/Object".equals(cn.getSuperName())) return null;
        if (cn.getInterfaces() != null && cn.getInterfaces().contains("java/lang/Cloneable")) return null;

        MethodNode ctorNode = null;
        for (MethodNode m : cn.getMethods()) {
            if ("<clinit>".equals(m.getName())) return null;
            if ("<init>".equals(m.getName())) {
                if (ctorNode != null) return null;
                ctorNode = m;
            }
        }
        if (ctorNode == null) return null;

        Function ctor = module.getFunction(
            LlvmRuntime.mangleMethod(className, "<init>", ctorNode.getDescriptor()));
        if (ctor == null || ctor.getEntryBlock() == null || ctor.getParameters().isEmpty()) return null;
        Parameter thisParam = ctor.getParameters().getFirst();
        String ctorCallee = className + ".<init>" + ctorNode.getDescriptor();

        for (BasicBlock b : ctor.getBlocks()) {
            for (Instruction put : b.getInstructions()) {
                if (put.getOpcode() != Opcode.PUT_FIELD || put.getOperands().size() < 3) continue;
                if (put.getOperands().get(0) != thisParam) continue;
                if (!(put.getOperands().get(1) instanceof Constant keyConst)) continue;
                if (!(put.getOperands().get(2) instanceof Temporary arr)) continue;
                Instruction newArray = arr.getDefiningInstruction();
                if (newArray == null || newArray.getOpcode() != Opcode.NEW_ARRAY
                    || newArray.getOperands().size() != 2
                    || newArray.getParent() == null
                    || newArray.getParent().getFunction() != ctor) continue;

                String fieldKey = keyConst.getValue().toString();
                if (!fieldKey.startsWith(className + ".")) continue;
                FieldNode field = findField(cn, fieldKey.substring(className.length() + 1));
                if (field == null) continue;
                if (reflectsField(reflectClassInfo, field.getName())) continue;
                int fa = field.getAccess();
                if ((fa & Opcodes.ACC_FINAL) == 0 || (fa & Opcodes.ACC_STATIC) != 0) continue;
                Type ft = field.getType();
                if (ft == null || !ft.isArray() || ft.getElementType() == null
                    || !ft.getElementType().isPrimitive()) continue;

                Value length = newArray.getOperands().getFirst();
                if (!isPure(length, ctor, new HashSet<>())) continue;
                if (inCycle(newArray.getParent())) continue;
                if (!onlyArrayAccess(arr, put)) continue;
                if (!fieldOnlyWrittenBy(fieldKey, put)) continue;
                if (!fieldReadsOnlyArrayAccess(fieldKey)) continue;
                if (!heapOwnedWithoutDestruction(arr)) continue;

                return new Candidate(className, fieldKey, ctorCallee, ctor, newArray, length);
            }
        }
        return null;
    }

    private static boolean reflectsField(ReflectClassInfo info, String fieldName) {
        if (info == null) return false;
        for (FieldReference ref : info.getFields()) {
            if (fieldName.equals(ref.getName())) return true;
        }
        return false;
    }

    private static FieldNode findField(ClassNode cn, String name) {
        if (cn.getFields() == null) return null;
        for (FieldNode f : cn.getFields()) {
            if (name.equals(f.getName())) return f;
        }
        return null;
    }

    private boolean isPure(Value v, Function ctor, Set<Value> seen) {
        if (v instanceof Constant) return true;
        if (v instanceof Parameter p) {
            return p.getOrdinal() >= 1 && ctor.getParameters().contains(p);
        }
        if (!(v instanceof Temporary t)) return false;
        if (!seen.add(t)) return true;
        Instruction def = t.getDefiningInstruction();
        if (def == null || def.getParent() == null || def.getParent().getFunction() != ctor) return false;
        if (PURE_OPS.contains(def.getOpcode())) {
            for (Value op : def.getOperands()) {
                if (!isPure(op, ctor, seen)) return false;
            }
            return true;
        }
        if (def.getOpcode() == Opcode.STATIC_CALL && !def.getOperands().isEmpty()
            && def.getOperands().getFirst() instanceof Constant callee
            && PURE_CALLS.contains(callee.getValue().toString())) {
            for (int i = 1; i < def.getOperands().size(); i++) {
                if (!isPure(def.getOperands().get(i), ctor, seen)) return false;
            }
            return true;
        }
        return false;
    }

    private static boolean inCycle(BasicBlock start) {
        Deque<BasicBlock> work = new ArrayDeque<>(start.getSuccessors());
        Set<BasicBlock> seen = new HashSet<>();
        while (!work.isEmpty()) {
            BasicBlock b = work.pop();
            if (b == start) return true;
            if (!seen.add(b)) continue;
            work.addAll(b.getSuccessors());
        }
        return false;
    }

    private boolean onlyArrayAccess(Value v, Instruction allowedPut) {
        if (terminatorValues.contains(v)) return false;
        for (Instruction use : uses.getOrDefault(v, List.of())) {
            List<Value> ops = use.getOperands();
            if (use == allowedPut) {
                if (ops.get(0) == v || ops.get(1) == v || ops.get(2) != v) return false;
                continue;
            }
            switch (use.getOpcode()) {
                case ALOAD, ARRAYLENGTH -> {
                    for (int i = 1; i < ops.size(); i++) if (ops.get(i) == v) return false;
                    if (ops.getFirst() != v) return false;
                }
                case ASTORE -> {
                    if (ops.size() < 3 || ops.get(0) != v || ops.get(1) == v || ops.get(2) == v) return false;
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean fieldOnlyWrittenBy(String fieldKey, Instruction put) {
        for (Function f : module.getFunctions()) {
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    if (inst == put || inst.getOpcode() != Opcode.PUT_FIELD) continue;
                    if (inst.getOperands().size() >= 2
                        && inst.getOperands().get(1) instanceof Constant c
                        && fieldKey.equals(c.getValue().toString())) return false;
                }
            }
        }
        return true;
    }

    private boolean fieldReadsOnlyArrayAccess(String fieldKey) {
        for (Function f : module.getFunctions()) {
            for (BasicBlock b : f.getBlocks()) {
                for (Instruction inst : b.getInstructions()) {
                    if (inst.getOpcode() != Opcode.GET_FIELD) continue;
                    if (inst.getOperands().size() < 2
                        || !(inst.getOperands().get(1) instanceof Constant c)
                        || !fieldKey.equals(c.getValue().toString())) continue;
                    if (inst.getResult() != null && !onlyArrayAccess(inst.getResult(), null)) return false;
                }
            }
        }
        return true;
    }

    private boolean heapOwnedWithoutDestruction(Value v) {
        for (AllocationSite site : aliasResult.getPointsTo(v).getSites()) {
            if (escapeResult.getSiteStatus(site) == EscapeStatus.STACK) return false;
            Set<DestructionPoint> dps = lifetimeResult.getDestructionPoints().get(site);
            if (dps != null && !dps.isEmpty()) return false;
        }
        return true;
    }

    private Site matchSite(Instruction n, Candidate c) {
        if (n.isStackAllocated() || n.getOperands().size() != 1) return null;
        Temporary obj = n.getResult();
        BasicBlock b = n.getParent();
        if (obj == null || b == null) return null;
        for (AllocationSite site : aliasResult.getPointsTo(obj).getSites()) {
            if (escapeResult.getSiteStatus(site) == EscapeStatus.STACK) return null;
        }
        List<Instruction> insts = b.getInstructions();
        for (int i = insts.indexOf(n) + 1; i < insts.size(); i++) {
            Instruction inst = insts.get(i);
            List<Value> ops = inst.getOperands();
            if (inst.getOpcode() == Opcode.SPECIAL_CALL && ops.size() >= 2 && ops.get(0) == obj
                && ops.get(1) instanceof Constant callee
                && c.ctorCallee().equals(callee.getValue().toString())) {
                for (int k = 2; k < ops.size(); k++) if (ops.get(k) == obj) return null;
                return new Site(n, inst);
            }
            if (ops.contains(obj)) return null;
        }
        return null;
    }

    private void rewriteSite(Site s, Candidate c) {
        BasicBlock b = s.newInst().getParent();
        List<Instruction> insts = b.getInstructions();
        insts.remove(s.newInst());
        List<Instruction> cloned = new ArrayList<>();
        Value length = cloneExpr(c.length(), s.call(), new HashMap<>(), cloned);
        int at = insts.indexOf(s.call());
        for (Instruction inst : cloned) inst.setParent(b);
        insts.addAll(at, cloned);
        insts.add(at + cloned.size(), s.newInst());
        s.newInst().addOperand(length);
        s.newInst().addOperand(c.newArray().getOperands().get(1));
    }

    private Value cloneExpr(Value v, Instruction call, Map<Value, Value> memo, List<Instruction> out) {
        if (v instanceof Constant) return v;
        if (v instanceof Parameter p) return call.getOperands().get(p.getOrdinal() + 1);
        Value done = memo.get(v);
        if (done != null) return done;
        Temporary t = (Temporary) v;
        Instruction def = t.getDefiningInstruction();
        Instruction copy = new Instruction(def.getOpcode());
        copy.setLocalIndex(def.getLocalIndex());
        copy.setInvokedynamicData(def.getInvokedynamicData());
        copy.setPolymorphicSignature(def.isPolymorphicSignature());
        for (Value op : def.getOperands()) {
            copy.addOperand(cloneExpr(op, call, memo, out));
        }
        Temporary result = new Temporary(t.getType());
        copy.setResult(result);
        result.setDefiningInstruction(copy);
        out.add(copy);
        memo.put(v, result);
        return result;
    }
}
