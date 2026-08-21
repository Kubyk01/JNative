package io.github.kubyk01.domain.analyzer.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

@Setter
@Getter
public class ThrowTerminator extends Terminator {
    private Value exception;

    public ThrowTerminator(Value exception) {
        this.exception = exception;
    }

    @Override
    public List<BasicBlock> getTargets() { return Collections.emptyList(); }

    @Override
    public String toString() {
        return "THROW " + exception;
    }
}
