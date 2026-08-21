package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.domain.analyzer.ir.Type;

public class LlvmTypeMapper {

    public String toLlvmType(Type type) {
        return switch (type) {
            case VOID -> "void";
            case BOOLEAN -> "i1";
            case BYTE -> "i8";
            case SHORT, CHAR -> "i16";
            case INT -> "i32";
            case LONG -> "i64";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case REFERENCE, ARRAY, NULL -> "i8*";
            default -> "i8*";
        };
    }

    public String toLlvmStruct(String className) {
        return "%struct." + className.replace('/', '_').replace('.', '_');
    }

    public String toLlvmPtr(String inner) {
        return inner + "*";
    }
}
