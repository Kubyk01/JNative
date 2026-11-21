package io.github.kubyk01.domain.analyzer.escapeanalysis.strategies;

import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Value;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConstantContext {

    static final Object NULL_REF = new Object();
    private static final Object NAC = new Object();

    private final Map<BasicBlock, Integer> index = new IdentityHashMap<>();
    private final Map<Value, Object> values = new IdentityHashMap<>();
    private final Map<Integer, Object> slots = new HashMap<>();
    private final Set<Integer> storedSlots = new HashSet<>();
    private final Set<BasicBlock> forced = Collections.newSetFromMap(new IdentityHashMap<>());
    private final boolean[] live;
    private boolean folded;

    ConstantContext(Function f, Object[] consts) {
        List<BasicBlock> blocks = f.getBlocks();
        for (int b = 0; b < blocks.size(); b++) index.put(blocks.get(b), b);
        live = new boolean[blocks.size()];
        for (Parameter p : f.getParameters()) {
            int o = p.getOrdinal();
            Object c = o >= 0 && o < consts.length && consts[o] != null ? consts[o] : NAC;
            values.put(p, c);
            slots.merge(p.getIndex(), c, ConstantContext::join);
        }
        for (BasicBlock block : blocks) {
            for (Instruction inst : block.getInstructions()) {
                if (inst.getOpcode() == Opcode.STORE) storedSlots.add(inst.getLocalIndex());
            }
        }
        Integer entry = f.getEntryBlock() == null ? null : index.get(f.getEntryBlock());
        if (entry == null) {
            Arrays.fill(live, true);
            return;
        }
        live[entry] = true;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int b = 0; b < blocks.size(); b++) {
                if (!live[b]) continue;
                BasicBlock block = blocks.get(b);
                for (Instruction inst : block.getInstructions()) {
                    if (eval(block, inst)) changed = true;
                }
                for (BasicBlock succ : block.getSuccessors()) {
                    Integer si = index.get(succ);
                    if (si != null && !live[si] && edge(block, succ)) {
                        live[si] = true;
                        changed = true;
                    }
                }
            }
            if (changed) continue;
            for (int b = 0; b < blocks.size(); b++) {
                BasicBlock block = blocks.get(b);
                if (live[b] && block.getTerminator() instanceof CondBranchTerminator cb
                    && value(cb.getCondition()) == null && forced.add(block)) changed = true;
            }
        }
        for (int b = 0; b < blocks.size(); b++) {
            BasicBlock block = blocks.get(b);
            if (live[b] && !forced.contains(block) && block.getTerminator() instanceof CondBranchTerminator cb
                && value(cb.getCondition()) instanceof Long) {
                folded = true;
                break;
            }
        }
    }

    boolean folded() {
        return folded;
    }

    boolean live(BasicBlock block) {
        Integer b = block == null ? null : index.get(block);
        return b == null || live[b];
    }

    boolean edge(BasicBlock pred, BasicBlock succ) {
        Integer pi = index.get(pred);
        if (pi != null && !live[pi]) return false;
        if (!(pred.getTerminator() instanceof CondBranchTerminator cb) || forced.contains(pred)) return true;
        if (pred.getExceptionalSuccessors().contains(succ)) return true;
        Object c = value(cb.getCondition());
        if (c == null) return false;
        if (!(c instanceof Long l)) return true;
        BasicBlock taken = l != 0 ? cb.getTrueTarget() : cb.getFalseTarget();
        return succ == taken || (succ != cb.getTrueTarget() && succ != cb.getFalseTarget());
    }

    Object constant(Value v) {
        Object c = value(v);
        return c instanceof Long || c == NULL_REF ? c : null;
    }

    static boolean maybeConstant(Value v) {
        if (v instanceof Constant) return true;
        if (!(v instanceof Temporary t) || t.getDefiningInstruction() == null) return false;
        return switch (t.getDefiningInstruction().getOpcode()) {
            case LOAD, STORE, PHI, CAST, CHECKCAST -> true;
            default -> false;
        };
    }

    private Object value(Value v) {
        if (v == null) return NAC;
        if (v instanceof Constant c) return literal(c);
        Object x = values.get(v);
        if (x != null) return x;
        if (v instanceof Temporary t && t.getDefiningInstruction() != null) {
            BasicBlock pb = t.getDefiningInstruction().getParent();
            return pb != null && index.containsKey(pb) ? null : NAC;
        }
        return NAC;
    }

    private static Object literal(Constant c) {
        Object o = c.getValue();
        if (o == null) return NULL_REF;
        if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            return ((Number) o).longValue();
        }
        if (o instanceof Character ch) return (long) ch;
        if (o instanceof Boolean b) return b ? 1L : 0L;
        return NAC;
    }

    private boolean eval(BasicBlock block, Instruction inst) {
        List<Value> ops = inst.getOperands();
        boolean changed = false;
        Object r;
        switch (inst.getOpcode()) {
            case EQ, NE, LT, LE, GT, GE -> r = ops.size() == 2
                ? compare(inst.getOpcode(), value(ops.get(0)), value(ops.get(1))) : NAC;
            case CAST, CHECKCAST -> r = ops.isEmpty() ? NAC : narrow(value(ops.getFirst()));
            case PHI -> {
                List<BasicBlock> preds = block.getPredecessors();
                boolean aligned = preds.size() == ops.size();
                r = null;
                for (int k = 0; k < ops.size(); k++) {
                    if (aligned && !edge(preds.get(k), block)) continue;
                    r = join(r, value(ops.get(k)));
                }
            }
            case STORE -> {
                r = ops.isEmpty() ? NAC : value(ops.getFirst());
                if (r != null) {
                    Object old = slots.get(inst.getLocalIndex());
                    Object nv = join(old, r);
                    if (nv != old) {
                        slots.put(inst.getLocalIndex(), nv);
                        changed = true;
                    }
                }
            }
            case LOAD -> {
                int slot = inst.getLocalIndex();
                r = slots.get(slot);
                if (r == null && !storedSlots.contains(slot)) r = NAC;
            }
            default -> r = NAC;
        }
        Temporary res = inst.getResult();
        if (res == null || r == null) return changed;
        Object old = values.get(res);
        Object nv = join(old, r);
        if (nv != old) {
            values.put(res, nv);
            changed = true;
        }
        return changed;
    }

    private static Object join(Object a, Object b) {
        if (a == null) return b;
        if (b == null || a == b) return a;
        if (a == NAC || b == NAC) return NAC;
        return a.equals(b) ? a : NAC;
    }

    private static Object narrow(Object x) {
        if (x == null || x == NAC || x == NULL_REF) return x;
        if (x instanceof Long l && l >= 0 && l <= 127) return l;
        return NAC;
    }

    private static Object compare(Opcode op, Object a, Object b) {
        if (a == NAC || b == NAC) return NAC;
        if (a == null || b == null) return null;
        if (a == NULL_REF || b == NULL_REF) {
            if (a != b) return NAC;
            return switch (op) {
                case EQ -> 1L;
                case NE -> 0L;
                default -> NAC;
            };
        }
        if (!(a instanceof Long x) || !(b instanceof Long y)) return NAC;
        boolean r = switch (op) {
            case EQ -> x.longValue() == y.longValue();
            case NE -> x.longValue() != y.longValue();
            case LT -> x < y;
            case LE -> x <= y;
            case GT -> x > y;
            case GE -> x >= y;
            default -> false;
        };
        return r ? 1L : 0L;
    }
}
