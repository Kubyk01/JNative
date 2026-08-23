package io.github.kubyk01.domain.analyzer.ir;

import org.objectweb.asm.Label;

public class TryCatchRange {
    public final Label start, end, handler;
    public final String type; // internal class name, null for finally

    public TryCatchRange(Label start, Label end, Label handler, String type) {
        this.start = start;
        this.end = end;
        this.handler = handler;
        this.type = type;
    }
}
