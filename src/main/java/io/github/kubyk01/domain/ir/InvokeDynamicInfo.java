package io.github.kubyk01.domain.ir;

import org.objectweb.asm.Handle;

import java.lang.invoke.LambdaMetafactory;
import java.util.ArrayList;
import java.util.List;

public record InvokeDynamicInfo(
    String name,
    String descriptor,
    Handle bootstrapMethod,
    Object[] bootstrapArgs,
    ResolvedCall resolvedCall
) {
    public String lambdaInterface() {
        if (resolvedCall == null || resolvedCall.getType() != ResolvedCall.Type.LAMBDA) return null;
        var result = org.objectweb.asm.Type.getReturnType(descriptor);
        return result.getSort() == org.objectweb.asm.Type.OBJECT ? result.getInternalName() : null;
    }

    public List<String> lambdaMarkerInterfaces() {
        if (bootstrapMethod == null || !"altMetafactory".equals(bootstrapMethod.getName())
                || bootstrapArgs == null || bootstrapArgs.length < 4
                || !(bootstrapArgs[3] instanceof Integer flags)) return List.of();

        List<String> interfaces = new ArrayList<>();
        if ((flags & LambdaMetafactory.FLAG_MARKERS) != 0
                && bootstrapArgs.length > 4 && bootstrapArgs[4] instanceof Integer count) {
            for (int i = 0; i < count && i < bootstrapArgs.length - 5; i++) {
                if (bootstrapArgs[5 + i] instanceof org.objectweb.asm.Type type
                        && type.getSort() == org.objectweb.asm.Type.OBJECT) {
                    interfaces.add(type.getInternalName());
                }
            }
        }
        if ((flags & LambdaMetafactory.FLAG_SERIALIZABLE) != 0) interfaces.add("java/io/Serializable");
        return interfaces;
    }
}

