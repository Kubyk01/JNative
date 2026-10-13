package io.github.kubyk01.domain.ir;

import lombok.Getter;

@Getter
public class Parameter extends Value {
    /**
     * JVM local-variable slot index. This is what LOAD/STORE use and
     * what the emitter uses to allocate {@code %local_N}. For a
     * non-static method, slot 0 holds the receiver; long and double
     * parameters occupy two slots each, so slot numbers are NOT
     * contiguous in general.
     */
    private final int index;

    /**
     * Ordinal position of this parameter in the method's declared
     * parameter list, receiver included as ordinal 0 for non-static
     * methods. Unlike {@link #index} this is always contiguous:
     * ordinal 0, 1, 2, … and it is what FunctionSummary /
     * EscapeSummary use to index parameter sets.
     *
     * <p>Storing both is the whole point of this class. The two
     * numbers coincide for methods whose parameters are all of
     * primitive or reference width; they diverge the moment a
     * long/double parameter is present. Before this field existed,
     * summary builders used the slot number as if it were an
     * ordinal, and consumers — which iterate {@code getCallArguments}
     * by ordinal — silently selected the wrong argument for any
     * method with a long/double parameter.</p>
     */
    private final int ordinal;

    /**
     * Legacy two-argument constructor. Sets {@code ordinal = index}.
     * Safe only for functions whose parameters are all of reference
     * or 32-bit width; JVM-translated methods must use the
     * three-argument form so the two values are computed
     * independently.
     */
    public Parameter(Type type, int index) {
        this(type, index, index);
    }

    public Parameter(Type type, int index, int ordinal) {
        super(type);
        this.index = index;
        this.ordinal = ordinal;
    }

    @Override
    public String toString() {
        return "param" + index + "(" + getType() + ")";
    }
}
