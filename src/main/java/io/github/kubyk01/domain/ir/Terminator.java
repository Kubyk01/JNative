package io.github.kubyk01.domain.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public abstract class Terminator {
    private BasicBlock block;

    public abstract List<BasicBlock> getTargets();

    public boolean canThrow() {
        return this instanceof ThrowTerminator;
    }
}
