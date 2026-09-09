package io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver;

import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.util.parserC.ParserC;
import io.github.kubyk01.util.parserC.ParserC.NativeMethodInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;

@Slf4j
public class PolymorphicResolver {

    private final Map<String, List<NativeMethodInfo>> methodsByClass = new HashMap<>();

    public void loadFromClass(String className) {
        if (className == null || className.isEmpty()) return;
        if (methodsByClass.containsKey(className)) {
            return;
        }

        String resourcePath = ParserC.getResourcePathForClass(className);
        try {
            List<NativeMethodInfo> methods = ParserC.parse(resourcePath);
            methodsByClass.put(className, methods);
            log.debug("Loaded {} native methods for class {} from {}", methods.size(), className, resourcePath);
        } catch (IOException e) {
            log.warn("Failed to load native methods for class {} from {}: {}", className, resourcePath, e.getMessage());
            methodsByClass.put(className, Collections.emptyList());
        }
    }

    public void loadAll(Collection<String> classNames) {
        for (String className : classNames) {
            loadFromClass(className);
        }
    }

    /**
     * Finds the best match for a polymorphic method.
     * Returns {@link NativeMethodInfo} with the full C function name.
     */
    public NativeMethodInfo findBestMatch(String className, String methodName,
                                          Type returnType, List<Type> paramTypes) {
        List<NativeMethodInfo> candidates = methodsByClass.getOrDefault(className, Collections.emptyList());
        if (candidates.isEmpty()) {
            return null;
        }

        String expectedDescriptor = buildDescriptor(returnType, paramTypes).trim();

        NativeMethodInfo exactMatch = null;
        NativeMethodInfo returnMatch = null;
        NativeMethodInfo voidMatch = null;

        for (NativeMethodInfo info : candidates) {
            System.out.println("candidate: " + info);
            if (!info.getMethodName().equals(methodName)) continue;

            String infoDescriptor = info.getDescriptor().trim();
            if (expectedDescriptor.equals(infoDescriptor)) {
                exactMatch = info;
                break;
            }

            if (isVoidPtrArgsSignature(info)) {
                char expectedReturnChar = typeToDescriptorChar(returnType);
                char actualReturnChar = info.getDescriptor().charAt(info.getDescriptor().length() - 1);

                if (expectedReturnChar == actualReturnChar) {
                    returnMatch = info;
                } else if (actualReturnChar == 'V') {
                    voidMatch = info;
                }
            }
        }

        if (exactMatch != null) return exactMatch;
        if (returnMatch != null) return returnMatch;
        return voidMatch;
    }

    private boolean isVoidPtrArgsSignature(NativeMethodInfo info) {
        List<ParserC.CParameter> params = info.getParameters();
        if (params.size() != 1) return false;
        String type = params.getFirst().getType().trim();
        return type.contains("void") && type.contains("*");
    }

    // ----- Fixed buildDescriptor method with array and reference support -----
    private String buildDescriptor(Type returnType, List<Type> paramTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (Type pt : paramTypes) {
            sb.append(typeToFullDescriptor(pt));
        }
        sb.append(')');
        sb.append(typeToFullDescriptor(returnType));
        return sb.toString();
    }

    private String typeToFullDescriptor(Type type) {
        if (type.isPrimitive()) {
            return String.valueOf(typeToDescriptorChar(type));
        }
        if (type.isArray()) {
            return "[" + typeToFullDescriptor(type.getElementType());
        }
        if (type.isReference()) {
            return "L" + type.getClassName() + ";";
        }
        if (type.isNull()) {
            return "Ljava/lang/Object;";
        }
        return "Ljava/lang/Object;";
    }

    private char typeToDescriptorChar(Type type) {
        if (type == Type.VOID) return 'V';
        if (type == Type.BOOLEAN) return 'Z';
        if (type == Type.BYTE) return 'B';
        if (type == Type.SHORT) return 'S';
        if (type == Type.CHAR) return 'C';
        if (type == Type.INT) return 'I';
        if (type == Type.LONG) return 'J';
        if (type == Type.FLOAT) return 'F';
        if (type == Type.DOUBLE) return 'D';
        if (type.isReference()) return 'L';
        if (type.isArray()) return '[';
        return 'L';
    }

    public boolean isLoaded(String className) {
        return methodsByClass.containsKey(className);
    }

    public List<NativeMethodInfo> getMethodsForClass(String className) {
        return methodsByClass.getOrDefault(className, Collections.emptyList());
    }
}