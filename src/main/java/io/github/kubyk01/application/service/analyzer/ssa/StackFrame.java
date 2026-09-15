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

    private static boolean isCategory2(Type t) {
        return t == Type.LONG || t == Type.DOUBLE;
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
        if (isEmpty()) {
            log.warn("Stack underflow in dup2 (size=0)");
            return;
        }
        Value v1 = peek();
        if (isCategory2(v1.getType())) {
            // Form 2
            push(v1);
        } else {
            // Form 1
            if (size() < 2) {
                log.warn("Stack underflow in dup2 (size={})", size());
                return;
            }
            Value a = pop(); // v1
            Value b = pop(); // v2
            push(b); push(a); push(b); push(a);
        }
    }

    public void dup2X1() {
        if (isEmpty()) {
            log.warn("Stack underflow in dup2X1 (size=0)");
            return;
        }
        Value v1 = peek();
        if (isCategory2(v1.getType())) {
            // Form 2 — only two logical values involved.
            if (size() < 2) {
                log.warn("Stack underflow in dup2X1 (size={})", size());
                return;
            }
            Value a = pop(); // v1 (cat 2)
            Value b = pop(); // v2 (cat 1)
            push(a); push(b); push(a);
        } else {
            // Form 1 — three logical values involved.
            if (size() < 3) {
                log.warn("Stack underflow in dup2X1 (size={})", size());
                return;
            }
            Value a = pop(); // v1
            Value b = pop(); // v2
            Value c = pop(); // v3
            push(b); push(a); push(c); push(b); push(a);
        }
    }

    public void dup2X2() {
        if (size() < 2) {
            log.warn("Stack underflow in dup2X2 (size={})", size());
            return;
        }
        Value v1 = peek();
        boolean v1c2 = isCategory2(v1.getType());

        if (v1c2) {
            Value a = pop(); // v1 (cat 2)
            if (isEmpty()) {
                push(a);
                log.warn("Stack underflow in dup2X2");
                return;
            }
            Value v2 = peek();
            if (isCategory2(v2.getType())) {
                // Form 4: ..., v2, v1 -> ..., v1, v2, v1
                Value b = pop();
                push(a); push(b); push(a);
            } else {
                // Form 2: ..., v3, v2, v1 -> ..., v1, v3, v2, v1
                if (size() < 2) {
                    push(a);
                    log.warn("Stack underflow in dup2X2");
                    return;
                }
                Value b = pop(); // v2 (cat 1)
                Value c = pop(); // v3 (cat 1)
                push(a); push(c); push(b); push(a);
            }
        } else {
            Value a = pop(); // v1 (cat 1)
            if (isEmpty()) {
                push(a);
                log.warn("Stack underflow in dup2X2");
                return;
            }
            Value v2 = peek();
            if (isCategory2(v2.getType())) {
                // Form 3: ..., v3, v2, v1 -> ..., v2, v1, v3, v2, v1
                if (size() < 2) {
                    push(a);
                    log.warn("Stack underflow in dup2X2");
                    return;
                }
                Value b = pop(); // v2 (cat 2)
                Value c = pop(); // v3 (cat 1)
                push(b); push(a); push(c); push(b); push(a);
            } else {
                // Form 1: needs four logical values.
                if (size() < 3) {
                    push(a);
                    log.warn("Stack underflow in dup2X2");
                    return;
                }
                Value b = pop(); // v2 (cat 1)
                Value c = pop(); // v3 (cat 1)
                Value d = pop(); // v4 (cat 1)
                push(b); push(a); push(d); push(c); push(b); push(a);
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
        if (isEmpty()) return;
        Value v = pop();
        if (!isCategory2(v.getType())) {
            if (!isEmpty()) pop();
        }
    }
}