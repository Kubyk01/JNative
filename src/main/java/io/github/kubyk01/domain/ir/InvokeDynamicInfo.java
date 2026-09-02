package io.github.kubyk01.domain.ir;

import org.objectweb.asm.Handle;

public record InvokeDynamicInfo(
    String name,
    String descriptor,
    Handle bootstrapMethod,
    Object[] bootstrapArgs,
    ResolvedCall resolvedCall
) {}

