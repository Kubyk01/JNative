package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.domain.ir.Type;

public class LlvmTypeMapper {

    /**
     * Sanitizes a string for use as an LLVM identifier.
     * Replaces all characters that are not letters, digits, or underscore with '_'.
     * Also handles empty or null inputs.
     */
    public static String sanitizeIdentifier(String name) {
        if (name == null || name.isEmpty()) return "_";
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    public String toLlvmType(Type type) {
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

    public String toLlvmStruct(String className) {
        return "%struct." + sanitizeIdentifier(className);
    }
}