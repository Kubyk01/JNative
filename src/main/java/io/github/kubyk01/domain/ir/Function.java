package io.github.kubyk01.domain.ir;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class Function {
    private final String name;
    private final Type returnType;
    private final List<Parameter> parameters = new ArrayList<>();
    private final List<BasicBlock> blocks = new ArrayList<>();
    @Setter
    private BasicBlock entryBlock;
    @Setter
    private Module module;
    @Setter
    private List<TryCatchRange> tryCatchRanges = new ArrayList<>();

    /**
     * Internal class name that owns this function, or null for synthetic
     * functions (main, shutdown, lambda adaptors). Drives per-class .ll
     * file partitioning in LlvmGenerator.
     */
    @Setter
    private String ownerClass;

    /**
     * True when this function is an external JNI native that must be
     * invoked through the JNI calling convention: a thread-local
     * {@code JNIEnv*} is passed as the first operand, followed (for a
     * static method) by the {@code jclass} mirror. Set by
     * {@code BytecodeToIr} when the declaring class ships no JNative C
     * implementation and the symbol is resolved from the platform's
     * native shared objects at link time.
     */
    @Setter
    private boolean jniNative;

    /**
     * True when the JNI native is {@code static}, so the emitter knows to
     * prepend the {@code jclass} mirror after the {@code JNIEnv*}. Paired
     * with {@link #jniNative}.
     */
    @Setter
    private boolean staticJniNative;

    public Function(String name, Type returnType) {
        this.name = name;
        this.returnType = returnType;
    }

    public void addParameter(Parameter p) {
        parameters.add(p);
    }

    /**
     * Appends {@code block} to the end of the block list.
     *
     * <p>This is correct when the block is genuinely the last one to be
     * emitted, but WRONG when it is a tail split off an existing block:
     * the block list determines emission order in
     * {@link io.github.kubyk01.application.service.codegen.llvm.LlvmFunctionEmitter},
     * and a tail whose STORE results are consumed by earlier siblings must
     * be emitted before those siblings. Use
     * {@link #insertBlockAfter(BasicBlock, BasicBlock)} in that case.</p>
     */
    public void addBlock(BasicBlock block) {
        blocks.add(block);
        block.setFunction(this);
    }

    /**
     * Inserts {@code block} immediately after {@code existing} in the block
     * list. If {@code existing} is not in the list, falls back to appending.
     *
     * <p>Preserves definition-before-use order in the emitted LLVM text:
     * any value defined by an instruction in {@code block} is emitted
     * before any successor block that appears later in the list consumes
     * it. This is required by the single-pass
     * {@link io.github.kubyk01.application.service.codegen.llvm.LlvmValueMapper}.</p>
     */
    public void insertBlockAfter(BasicBlock existing, BasicBlock block) {
        if (block == null) return;
        int idx = blocks.indexOf(existing);
        if (idx < 0) {
            blocks.add(block);
        } else {
            blocks.add(idx + 1, block);
        }
        block.setFunction(this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("function ").append(name).append("(");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parameters.get(i));
        }
        sb.append(") -> ").append(returnType).append(" {\n");
        for (BasicBlock bb : blocks) {
            sb.append(bb);
        }
        sb.append("}\n");
        return sb.toString();
    }
}
