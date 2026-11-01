package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.domain.ir.Type;

public class LlvmTypeMapper {

    public static final String LAZY_CLINIT_FLAG_FIELD = "__clinit_done";

    public static String lazyClinitFlagGlobal(String className) {
        return "gv_" + sanitizeIdentifier(className + "." + LAZY_CLINIT_FLAG_FIELD);
    }

    public static String sanitizeIdentifier(String name) {
        if (name == null || name.isEmpty()) return "_";
        int n = name.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
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
        return "i8*";
    }

    public static String toLlvmStruct(String className) {
        return "%struct." + sanitizeIdentifier(className);
    }
}