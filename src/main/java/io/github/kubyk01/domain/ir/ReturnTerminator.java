package io.github.kubyk01.domain.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

@Setter
@Getter
public class ReturnTerminator extends Terminator {
    private Value value;

    public ReturnTerminator(Value value) {
        this.value = value;
    }

    @Override
    public List<BasicBlock> getTargets() { return Collections.emptyList(); }

    @Override
    public String toString() {
        if (value == null) return "RETURN void";
        return "RETURN " + value;
    }
}
