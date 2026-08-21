package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.analyzer.ir.Type;

import java.util.ArrayList;
import java.util.List;

public final class TypeResolver {

    private TypeResolver() {}

    public static Type descToIrType(String desc) {
        if (desc.length() == 1) {
            return switch (desc.charAt(0)) {
                case 'Z' -> Type.BOOLEAN;
                case 'B' -> Type.BYTE;
                case 'S' -> Type.SHORT;
                case 'C' -> Type.CHAR;
                case 'I' -> Type.INT;
                case 'J' -> Type.LONG;
                case 'F' -> Type.FLOAT;
                case 'D' -> Type.DOUBLE;
                case 'V' -> Type.VOID;
                default -> Type.REFERENCE;
            };
        } else if (desc.startsWith("L")) {
            return Type.REFERENCE;
        } else if (desc.startsWith("[")) {
            return Type.ARRAY;
        } else {
            return Type.UNKNOWN;
        }
    }

    public static List<Type> descToParamTypes(String desc) {
        List<Type> params = new ArrayList<>();
        int i = 1;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == ')') break;
            if (c == 'L') {
                params.add(Type.REFERENCE);
                i = desc.indexOf(';', i) + 1;
            } else if (c == '[') {
                params.add(Type.ARRAY);
                i++;
            } else {
                params.add(descToIrType("" + c));
                i++;
            }
        }
        return params;
    }

    public static Type descToReturnType(String desc) {
        int idx = desc.lastIndexOf(')');
        if (idx < 0) return Type.VOID;
        String ret = desc.substring(idx + 1);
        return descToIrType(ret);
    }
}
