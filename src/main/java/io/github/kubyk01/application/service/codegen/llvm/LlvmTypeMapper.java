package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.domain.ir.Type;

public class LlvmTypeMapper {

    /**
     * Name of the synthetic static field that carries the lazy-{@code <clinit>}
     * completion flag of a class.
     *
     * <p>The field is not part of any class file: it exists only in the IR
     * that the lazy-{@code <clinit>} guard wrapper reads and writes. Its
     * full name is {@code <internal-class-name>.__clinit_done}, which makes
     * it indistinguishable from an ordinary static field as far as the
     * generic {@code GET_STATIC}/{@code PUT_STATIC} emission is concerned.
     * The two emitters that must agree on it — the one that defines the
     * global and the one that accesses it — both derive the name from this
     * constant, so they cannot drift apart.</p>
     */
    public static final String LAZY_CLINIT_FLAG_FIELD = "__clinit_done";

    /**
     * Name of the LLVM global that holds the lazy-{@code <clinit>}
     * completion flag of a class.
     *
     * <p>The global is derived from the synthetic field's full name, exactly
     * like every other static field's global, so the generic
     * {@code GET_STATIC}/{@code PUT_STATIC} emission finds it under the name
     * it expects.</p>
     */
    public static String lazyClinitFlagGlobal(String className) {
        return "gv_" + sanitizeIdentifier(className + "." + LAZY_CLINIT_FLAG_FIELD);
    }

    /**
     * Sanitizes a string for use as an LLVM identifier.
     * Replaces all characters that are not letters, digits, or underscore with '_'.
     * Also handles empty or null inputs.
     */
    public static String sanitizeIdentifier(String name) {
        if (name == null || name.isEmpty()) return "_";
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    public static String toLlvmType(Type type) {
        if (type.isVoid()) return "void";
        if (type.isPrimitive()) {
            if (type == Type.BOOLEAN) return "i1";
            if (type == Type.BYTE) return "i8";
            if (type == Type.SHORT || type == Type.CHAR) return "i16";
            if (type == Type.INT) return "i32";
            if (type == Type.LONG) return "i64";
            if (type == Type.FLOAT) return "float";
            if (type == Type.DOUBLE) return "double";
        }
        if (type.isReference() || type.isArray() || type.isNull() || type.isBlock()) {
            return "i8*";
        }
        return "i8*"; // fallback
    }

    public static String toLlvmStruct(String className) {
        return "%struct." + sanitizeIdentifier(className);
    }
}