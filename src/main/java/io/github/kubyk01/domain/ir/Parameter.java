package io.github.kubyk01.domain.ir;

import lombok.Getter;

@Getter
public class Parameter extends Value {
    private final int index;

    public Parameter(Type type, int index) {
        super(type);
        this.index = index;
    }

    @Override
    public String toString() {
        return "param" + index + "(" + getType() + ")";
    }
}
