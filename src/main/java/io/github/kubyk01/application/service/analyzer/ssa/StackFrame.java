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
     * JVM DUP2_X1.
     * Form 1 (both top values category 1): ..., v3, v2, v1 -> ..., v2, v1, v3, v2, v1
     * Form 2 (top value category 2, second category 1): ..., v2, v1 -> ..., v1, v2, v1
     *
     * As in the rest of this StackFrame, every Value occupies exactly one slot,
     * so we use the category-1 interpretation.
     */
    public void dup2X1() {
        if (size() >= 3) {
            Value v1 = pop();   // top
            Value v2 = pop();
            Value v3 = pop();
            push(v2);
            push(v1);
            push(v3);
            push(v2);
            push(v1);
        } else {
            log.warn("Stack underflow in dup2X1 (size={})", size());
        }
    }

    /**
     * JVM DUP2_X2.
     * Form 4 (all four values category 1): ..., v4, v3, v2, v1 -> ..., v2, v1, v4, v3, v2, v1
     * (Other forms involve category-2 values; we use the uniform category-1 form.)
     */
    public void dup2X2() {
        if (size() >= 4) {
            Value v1 = pop();   // top
            Value v2 = pop();
            Value v3 = pop();
            Value v4 = pop();
            push(v2);
            push(v1);
            push(v4);
            push(v3);
            push(v2);
            push(v1);
        } else {
            log.warn("Stack underflow in dup2X2 (size={})", size());
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
}