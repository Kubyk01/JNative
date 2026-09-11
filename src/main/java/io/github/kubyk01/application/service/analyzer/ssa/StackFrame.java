package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.UndefinedValue;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.domain.ir.IrBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class StackFrame {
    private final Map<Integer, Value> locals = new HashMap<>();
    private final Deque<Value> stack = new ArrayDeque<>();
    private final IrBuilder builder;

    public StackFrame(IrBuilder builder) {
        this.builder = builder;
    }

    public void setLocal(int index, Value value) {
        locals.put(index, value);
    }

    public Value getLocal(int index) {
        return locals.get(index);
    }

    public Value getOrCreateLocal(int index, Type type) {
        Value local = locals.get(index);
        if (local == null) {
            Temporary tmp = builder.newTemporary(type);
            locals.put(index, tmp);
            local = tmp;
        }
        return local;
    }

    public void push(Value value) {
        stack.push(value);
    }

    public Value pop() {
        return stack.pop();
    }

    public Value peek() {
        return stack.peek();
    }

    public int size() {
        return stack.size();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public List<Value> popArgs(int count) {
        List<Value> args = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (stack.isEmpty()) {
                log.warn("Stack underflow while popping args (requested {}, stack size {}), using undefined",
                    count, 0);
                args.addFirst(new UndefinedValue(Type.UNKNOWN));
            } else {
                args.addFirst(pop());
            }
        }
        return args;
    }

    public void dup() {
        if (!isEmpty()) push(peek());
    }

    public void dupX1() {
        if (size() >= 2) {
            Value v1 = pop();
            Value v2 = pop();
            push(v1);
            push(v2);
            push(v1);
        }
    }

    public void dupX2() {
        if (size() >= 3) {
            Value v1 = pop();
            Value v2 = pop();
            Value v3 = pop();
            push(v1);
            push(v3);
            push(v2);
            push(v1);
        }
    }

    public void dup2() {
        if (size() >= 2) {
            Value v1 = pop();
            Value v2 = pop();
            push(v2);
            push(v1);
            push(v2);
            push(v1);
        }
    }

    /**
     * JVM {@code DUP2_X1}. Two forms exist in the JVM spec:
     *
     * <ul>
     *   <li><b>Form 1</b> (both values category 1):
     *       {@code ..., v3, v2, v1 -> ..., v2, v1, v3, v2, v1}</li>
     *   <li><b>Form 2</b> (value1 category 2, value2 category 1):
     *       {@code ..., v2, v1 -> ..., v1, v2, v1}</li>
     * </ul>
     *
     * In this project's IR a {@code long}/{@code double} is a single {@link Value},
     * so we must look at the type of the top value to decide which form applies.
     */
    public void dup2X1() {
        if (size() < 2) {
            log.warn("Stack underflow in dup2X1 (size={})", size());
            return;
        }
        Value v1 = pop();
        if (isCategory2(v1)) {
            // Form 2: v1 is long/double, v2 is any category-1 value.
            Value v2 = pop();
            push(v1);
            push(v2);
            push(v1);
        } else {
            // Form 1: all three values are category 1.
            if (size() < 2) {
                log.warn("Stack underflow in dup2X1 form 1 (size={})", size());
                push(v1);
                return;
            }
            Value v2 = pop();
            Value v3 = pop();
            push(v2);
            push(v1);
            push(v3);
            push(v2);
            push(v1);
        }
    }

    /**
     * JVM {@code DUP2_X2}. Four forms exist in the JVM spec:
     *
     * <ul>
     *   <li><b>Form 1</b> (all category 1):
     *       {@code ..., v4, v3, v2, v1 -> ..., v2, v1, v4, v3, v2, v1}</li>
     *   <li><b>Form 2</b> (v1 cat 2, v2,v3 cat 1):
     *       {@code ..., v3, v2, v1 -> ..., v1, v3, v2, v1}</li>
     *   <li><b>Form 3</b> (v1,v2 cat 1, v3 cat 2):
     *       {@code ..., v3, v2, v1 -> ..., v2, v1, v3, v2, v1}</li>
     *   <li><b>Form 4</b> (v1,v2 both cat 2):
     *       {@code ..., v2, v1 -> ..., v1, v2, v1}</li>
     * </ul>
     */
    public void dup2X2() {
        if (size() < 2) {
            log.warn("Stack underflow in dup2X2 (size={})", size());
            return;
        }
        Value v1 = pop();
        if (isCategory2(v1)) {
            // v1 is long/double -> Form 2 or Form 4.
            if (size() < 1) {
                log.warn("Stack underflow in dup2X2 form 2/4 (size={})", size());
                push(v1);
                return;
            }
            Value v2 = pop();
            if (isCategory2(v2)) {
                // Form 4
                push(v1);
                push(v2);
                push(v1);
            } else {
                // Form 2
                if (size() < 1) {
                    log.warn("Stack underflow in dup2X2 form 2 (size={})", size());
                    push(v2);
                    push(v1);
                    return;
                }
                Value v3 = pop();
                push(v1);
                push(v3);
                push(v2);
                push(v1);
            }
        } else {
            // v1 is category 1 -> Form 1 or Form 3.
            if (size() < 2) {
                log.warn("Stack underflow in dup2X2 form 1/3 (size={})", size());
                push(v1);
                return;
            }
            Value v2 = pop();
            Value v3 = pop();
            if (isCategory2(v3)) {
                // Form 3
                push(v2);
                push(v1);
                push(v3);
                push(v2);
                push(v1);
            } else {
                // Form 1
                if (size() < 1) {
                    log.warn("Stack underflow in dup2X2 form 1 (size={})", size());
                    push(v3);
                    push(v2);
                    push(v1);
                    return;
                }
                Value v4 = pop();
                push(v2);
                push(v1);
                push(v4);
                push(v3);
                push(v2);
                push(v1);
            }
        }
    }

    public void swap() {
        if (size() >= 2) {
            Value v1 = pop();
            Value v2 = pop();
            push(v1);
            push(v2);
        }
    }

    public void pop2() {
        if (!isEmpty()) pop();
        if (!isEmpty()) pop();
    }

    /**
     * Returns {@code true} if the value occupies two JVM stack slots
     * (i.e. is a {@code long} or {@code double}).
     */
    private static boolean isCategory2(Value v) {
        if (v == null) return false;
        Type t = v.getType();
        return t == Type.LONG || t == Type.DOUBLE;
    }
}