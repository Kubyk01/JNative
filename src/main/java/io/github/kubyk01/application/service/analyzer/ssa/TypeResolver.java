package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.domain.ir.Type;

import java.util.ArrayList;
import java.util.List;

public final class TypeResolver {

    private TypeResolver() {}

    public static Type descToIrType(String desc) {
        return Type.fromDescriptor(desc);
    }

    public static List<Type> descToParamTypes(String desc) {
        List<Type> params = new ArrayList<>();
        int i = 1;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == ')') break;
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                String name = desc.substring(i + 1, end);
                params.add(Type.reference(name));
                i = end + 1;
            } else if (c == '[') {
                int start = i;
                while (i < desc.length() && desc.charAt(i) == '[') i++;
                int end;
                if (i < desc.length() && desc.charAt(i) == 'L') {
                    end = desc.indexOf(';', i) + 1;
                } else {
                    end = i + 1;
                }
                String sub = desc.substring(start, end);
                params.add(Type.array(sub));
                i = end;
            } else {
                params.add(Type.fromDescriptor(String.valueOf(c)));
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

    /** Descriptor of a primitive type ("I" for int); null for anything else. */
    public static String primitiveDescriptor(Type t) {
        if (t == Type.BOOLEAN) return "Z";
        if (t == Type.BYTE)    return "B";
        if (t == Type.SHORT)   return "S";
        if (t == Type.CHAR)    return "C";
        if (t == Type.INT)     return "I";
        if (t == Type.LONG)    return "J";
        if (t == Type.FLOAT)   return "F";
        if (t == Type.DOUBLE)  return "D";
        return null;
    }

    /** Boxed class of a primitive descriptor ("I" -> java/lang/Integer); null for anything else. */
    public static String wrapperClassOf(String primitiveDesc) {
        return switch (primitiveDesc) {
            case "Z" -> "java/lang/Boolean";
            case "B" -> "java/lang/Byte";
            case "S" -> "java/lang/Short";
            case "C" -> "java/lang/Character";
            case "I" -> "java/lang/Integer";
            case "J" -> "java/lang/Long";
            case "F" -> "java/lang/Float";
            case "D" -> "java/lang/Double";
            default  -> null;
        };
    }
}
