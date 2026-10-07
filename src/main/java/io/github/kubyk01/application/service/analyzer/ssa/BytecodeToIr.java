package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityAnalysis;
import io.github.kubyk01.application.service.codegen.NativeOverride;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IrBuilder;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Type;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class BytecodeToIr {

    private final DependencyResolver resolver;
    private final ReachabilityAnalysis reachability;
    private final IrBuilder builder = new IrBuilder();
    private final Map<MethodReference, Function> functionMap = new HashMap<>();

    /** Mangling of a Java method -> the C override that replaces it. */
    private final Map<String, NativeOverride> overrides = new HashMap<>();

    public BytecodeToIr(DependencyResolver resolver,
                        ReachabilityAnalysis reachability,
                        List<NativeOverride> nativeOverrides) {
        this.resolver = resolver;
        this.reachability = reachability;
        for (NativeOverride o : nativeOverrides) {
            overrides.put(mangledKey(o), o);
        }
    }

    public Module translate() {
        // 1. Register every override function up front, before any
        //    Java method is translated. This makes the alias resolvable
        //    from the very first call site that the translator emits.
        Module module = builder.getModule();
        registerOverrides();

        // 2. Translate every reachable method, skipping those that have
        //    an override.
        for (MethodReference ref : reachability.getReachableMethods()) {
            NativeOverride match = findOverride(ref);
            if (match != null) {
                // Alias every reachable overload onto the single C function.
                String key = LlvmRuntime.mangleMethod(
                    ref.getOwner(), ref.getName(), ref.getDescriptor());
                Function target = module.getFunction(match.getCFunctionName());
                module.registerAlias(key, target);
                continue;
            }
            translateMethod(ref);
        }
        return builder.getModule();
    }

    private NativeOverride findOverride(MethodReference ref) {
        for (NativeOverride o : overrides.values()) {
            if (!o.getClassName().equals(ref.getOwner()))       continue;
            if (!o.getMethodName().equals(ref.getName()))       continue;
            if (o.getDescriptor() != null
                && !o.getDescriptor().equals(ref.getDescriptor())) continue;
            return o;
        }
        return null;
    }

    private void registerOverrides() {
        Module module = builder.getModule();
        for (NativeOverride o : overrides.values()) {
            if (module.getFunction(o.getCFunctionName()) == null) {
                Function f = new Function(o.getCFunctionName(), o.getReturnType());
                for (Parameter p : o.toParameters()) {
                    f.addParameter(p);
                }
                module.addFunction(f);
            }
            Function f = module.getFunction(o.getCFunctionName());
            module.registerAlias(mangledKey(o), f);
        }
    }

    private static String mangledKey(NativeOverride o) {
        return LlvmRuntime.mangleMethod(o.getClassName(), o.getMethodName(),
            o.getDescriptor() != null ? o.getDescriptor() : "()V");
    }

    private void translateMethod(MethodReference methodRef) {
        if ("java/lang/Class".equals(methodRef.getOwner())
            && "getEnumConstantsShared".equals(methodRef.getName())
            && "()[Ljava/lang/Object;".equals(methodRef.getDescriptor())) {
            EnumConstantsSharedEmitter.emit(
                methodRef, builder, resolver, reachability);
            return;
        }

        MethodNode methodNode = null;
        try {
            String owner = methodRef.getOwner();
            String name = methodRef.getName();
            String desc = methodRef.getDescriptor();

            ClassNode classNode = resolver.getClassNode(owner);
            methodNode = findMethod(classNode, name, desc);
            boolean isStatic = methodNode != null && methodNode.isStatic();

            if (classNode.isExternal()) {
                Function func = createExternalFunction(methodRef, isStatic);
                functionMap.put(methodRef, func);
                return;
            }

            if (methodNode != null && methodNode.isNative()) {
                String nativeName = "__jnative_" + LlvmRuntime.mangleMethod(owner, name, desc);
                Function func = new Function(nativeName, methodNode.getReturnType());

                List<Type> allParams = new ArrayList<>();
                if (!methodNode.isStatic()) {
                    allParams.add(Type.reference(owner));
                }
                allParams.addAll(methodNode.getParameterTypes());

                for (int i = 0; i < allParams.size(); i++) {
                    func.addParameter(new Parameter(allParams.get(i), i));
                }
                builder.getModule().addFunction(func);
                functionMap.put(methodRef, func);
                return;
            }

            if (methodNode == null || methodNode.isAbstract()) {
                Function func = createExternalFunction(methodRef, isStatic);
                functionMap.put(methodRef, func);
                return;
            }

            byte[] bytes = resolver.getClassBytes(owner);
            if (bytes == null) {
                // Try one more time — the class may have been loaded via
                // reflection earlier and not yet replaced by its real
                // bytecode-backed ClassNode.
                resolver.forceLoadSystemClass(owner);
                bytes = resolver.getClassBytes(owner);
            }
            if (bytes == null) {
                // Do NOT emit a declaration without a body: the linker
                // would fail with an undefined-symbol error.  Skipping the
                // method leaves the mangled name out of the module, and the
                // LLVM emitter will skip any call to it.
                log.warn("No bytecode for class {}; method {}.{}{} will "
                        + "be skipped (no body emitted)",
                    owner, owner, name, desc);
                return;
            }

            ClassReader reader = new ClassReader(bytes);
            MethodTranslator translator = new MethodTranslator(methodRef, methodNode.isStatic(), builder, resolver);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String mName, String mDesc,
                                                 String signature, String[] exceptions) {
                    if (mName.equals(name) && mDesc.equals(desc)) {
                        return translator;
                    }
                    return null;
                }
            }, ClassReader.SKIP_DEBUG);

            Function func = translator.getCurrentFunction();
            if (func != null) {
                functionMap.put(methodRef, func);
            } else {
                Function external = createExternalFunction(methodRef, isStatic);
                functionMap.put(methodRef, external);
            }
        } catch (Exception e) {
            log.warn("Failed to translate method {}; falling back to external declaration: {}",
                methodRef, e.getMessage());
            boolean isStatic = methodNode != null && methodNode.isStatic();
            Function func = createExternalFunction(methodRef, isStatic);
            functionMap.put(methodRef, func);
        }
    }

    private MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode m : classNode.getMethods()) {
            if (m.getName().equals(name) && m.getDescriptor().equals(desc)) {
                return m;
            }
        }
        if (classNode.getSuperName() != null) {
            ClassNode superNode = resolver.getClassNode(classNode.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                return findMethod(superNode, name, desc);
            }
        }
        return null;
    }

    private Function createExternalFunction(MethodReference ref, boolean isStatic) {
        Type retType = TypeResolver.descToReturnType(ref.getDescriptor());
        List<Type> paramTypes = TypeResolver.descToParamTypes(ref.getDescriptor());
        List<Type> allParams = new ArrayList<>();
        if (!isStatic) {
            // Instance method: receiver is the first parameter
            allParams.add(Type.reference(ref.getOwner()));
        }
        allParams.addAll(paramTypes);
        String mangledName = LlvmRuntime.mangleMethod(ref.getOwner(), ref.getName(), ref.getDescriptor());
        Function func = new Function(mangledName, retType);
        for (int i = 0; i < allParams.size(); i++) {
            func.addParameter(new Parameter(allParams.get(i), i));
        }
        builder.getModule().addFunction(func);
        return func;
    }
}