package io.github.kubyk01.domain.analyzer.ir;

public class UndefinedValue extends Value {
    public UndefinedValue(Type type) {
        super(type);
    }

    @Override
    public String toString() {
        return "undef(" + getType() + ")";
    }
}
