package io.github.kubyk01.domain.analyzer.ir;

import lombok.Getter;

@Getter
public abstract class Value {
    private final Type type;
    private final int id;

    private static int nextId = 0;

    protected Value(Type type) {
        this.type = type;
        this.id = nextId++;
    }

    @Override
    public String toString() {
        return "v" + id + "(" + type + ")";
    }
}
