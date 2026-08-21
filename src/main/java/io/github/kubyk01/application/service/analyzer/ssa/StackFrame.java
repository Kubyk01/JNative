package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.analyzer.ir.Temporary;
import io.github.kubyk01.domain.analyzer.ir.Type;
import io.github.kubyk01.domain.analyzer.ir.UndefinedValue;
import io.github.kubyk01.domain.analyzer.ir.Value;
import io.github.kubyk01.domain.analyzer.ir.IrBuilder;
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
