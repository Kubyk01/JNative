package io.github.kubyk01.domain.ir;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Temporary extends Value {
    private Instruction definingInstruction;
    private Type effectiveType;

    public Temporary(Type type) {
        super(type);
    }

    @Override
    public Type getType() {
        return effectiveType != null ? effectiveType : super.getType();
    }

    public void setType(Type type) {
        this.effectiveType = type;
    }

    @Override
    public String toString() {
        return "t" + getId() + "(" + getType() + ")";
    }
}