package io.github.kubyk01.domain.ir;

import lombok.Getter;

@Getter
public class Constant extends Value {
    private final Object value;

    public Constant(Type type, Object value) {
        super(type);
        this.value = value;
    }

    @Override
    public String toString() {
        return "const(" + value + ")";
    }
}
