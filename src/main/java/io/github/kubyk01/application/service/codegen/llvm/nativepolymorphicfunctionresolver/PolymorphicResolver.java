package io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver;

import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.util.LlvmUtil;
import io.github.kubyk01.util.parserC.ParserC;
import io.github.kubyk01.util.parserC.ParserC.NativeMethodInfo;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class PolymorphicResolver {

    /**
     * Cache of parsed native-method lists, keyed by internal class name.
     *
     * <p>{@link #loadAll(Collection, Scheduler)} populates this map from
     * several worker threads at once, and {@link #findBestMatch} reads
     * from it during polymorphic dispatch on the code-generation
     * thread. A plain {@link java.util.HashMap} would corrupt its
     * internal table under concurrent {@code put} operations; the two
     * worker-side operations that touch the map are
     * {@code containsKey} and {@code put}, both of which are atomic
     * in {@link ConcurrentHashMap} and admit the "two threads parse the
     * same file, last write wins" race without any observable
     * difference, since parsing is deterministic.</p>
     */
    private final Map<String, List<NativeMethodInfo>> methodsByClass = new ConcurrentHashMap<>();

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
     * Parallel form of {@link #loadAll(Collection)}. Same contract, but
     * the per-class load is dispatched through the analysis scheduler
     * instead of being run serially on the calling thread.
     *
     * <p>The serial form is retained so that callers that do not own a
     * scheduler (unit tests, embedding code) are not forced to
     * construct one.</p>
     */
    public void loadAll(Collection<String> classNames, Scheduler scheduler) {
        if (classNames == null || classNames.isEmpty()) {
            return;
        }
        Scheduler sched = (scheduler != null)
            ? scheduler
            : reactor.core.scheduler.Schedulers.single();

        LlvmUtil.awaitMono(
            Flux.fromIterable(classNames)
                .parallel()
                .runOn(sched)
                .doOnNext(this::loadFromClass)
                .sequential()
                .then()
        );
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

}