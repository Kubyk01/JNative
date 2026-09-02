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
}
