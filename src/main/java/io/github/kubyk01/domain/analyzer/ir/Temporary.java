package io.github.kubyk01.domain.analyzer.ir;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Temporary extends Value {
    private Instruction definingInstruction;

    public Temporary(Type type) {
        super(type);
    }

    @Override
    public String toString() {
        return "t" + getId() + "(" + getType() + ")";
    }
}
