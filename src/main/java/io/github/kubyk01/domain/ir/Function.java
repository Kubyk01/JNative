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

    /**
     * Original Java-level method name of a JNI native.
     *
     * <p>For a JNI native the function's own {@link #getName()} is the
     * mangled {@code Java_<class>_<method>} symbol. The original method
     * name and the original JVM descriptor cannot be recovered from that
     * symbol unambiguously, because JNI mangling is lossy (both {@code '/'}
     * and {@code '_'} map to {@code '_'}, and the overload suffix encoding
     * is not a bijection). This field therefore carries the original name
     * forward so that the emitter can build the {@code (class, name,
     * descriptor)} triple it passes to
     * {@code __jnative_jni_resolve} at every call site.</p>
     *
     * <p>{@code null} for non-JNI functions.</p>
     */
    @Setter
    private String jniMethodName;

    /**
     * Original JVM method descriptor of a JNI native, e.g. {@code "()I"} or
     * {@code "([BI)I"}. See {@link #jniMethodName} for why this cannot be
     * derived from the mangled symbol. {@code null} for non-JNI functions.
     */
    @Setter
    private String jniDescriptor;

    public Function(String name, Type returnType) {
        this.name = name;
        this.returnType = returnType;
    }

    public void addParameter(Parameter p) {
        parameters.add(p);
    }

    public void addBlock(BasicBlock block) {
        blocks.add(block);
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
