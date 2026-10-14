package io.github.kubyk01.application.service.codegen;

import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Parameter;
import lombok.Value;

import java.util.List;

@Value
public class NativeOverride {
    /** Internal name of the class being overridden, e.g. "java/security/SecureRandom". */
    String className;

    /** Method name being overridden. */
    String methodName;

    /** JVM descriptor, or null to match every overload. */
    String descriptor;

    /** Full C symbol, e.g. "__jnative_override_getDefaultPRNG". */
    String cFunctionName;

    /** Java IR return type derived from the C return type. */
    Type returnType;

    /** Java IR parameter types, receiver included as the first entry for instance methods. */
    List<Type> parameterTypes;

    /** true when the C signature has a receiver (first parameter is void* / a class pointer). */
    boolean hasReceiver;

    /** The Java method's mangled name, cached for the alias registration. */
    String mangledJavaName() {
        return io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime
            .mangleMethod(className, methodName, descriptor != null ? descriptor : "()V");
    }

    public List<Parameter> toParameters() {
        java.util.ArrayList<Parameter> out = new java.util.ArrayList<>();
        for (int i = 0; i < parameterTypes.size(); i++) {
            out.add(new Parameter(parameterTypes.get(i), i));
        }
        return out;
    }
}
