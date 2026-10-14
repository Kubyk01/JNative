package io.github.kubyk01.domain.ir;

import org.objectweb.asm.Label;

import java.util.List;

public class TryCatchRange {
    public final Label start;
    public final Label end;
    public final Label handler;
    public final String type;
    public List<BasicBlock> body;

    public TryCatchRange(Label start, Label end, Label handler, String type) {
        this.start = start;
        this.end = end;
        this.handler = handler;
        this.type = type;
    }
}
