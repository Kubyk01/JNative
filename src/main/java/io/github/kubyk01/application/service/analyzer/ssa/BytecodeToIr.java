package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityAnalysis;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.ir.Function;
import io.github.kubyk01.domain.analyzer.ir.IrBuilder;
import io.github.kubyk01.domain.analyzer.ir.Module;
import io.github.kubyk01.domain.analyzer.ir.Parameter;
import io.github.kubyk01.domain.analyzer.ir.Type;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class BytecodeToIr {

    private final DependencyResolver resolver;
    private final ReachabilityAnalysis reachability;
    private final IrBuilder builder = new IrBuilder();
    private final Map<MethodReference, Function> functionMap = new HashMap<>();

    public Module translate() {
        for (MethodReference methodRef : reachability.getReachableMethods()) {
            translateMethod(methodRef);
        }
        return builder.getModule();
    }

    private void translateMethod(MethodReference methodRef) {
        MethodNode methodNode = null;
        try {
            String owner = methodRef.getOwner();
            String name = methodRef.getName();
            String desc = methodRef.getDescriptor();

            ClassNode classNode = resolver.getClassNode(owner);
            methodNode = findMethod(classNode, name, desc);
            boolean isStatic = methodNode != null && methodNode.isStatic();

            if (classNode.isExternal()) {
                // For external or abstract methods, we use the same logic.
                Function func = createExternalFunction(methodRef, isStatic);
                functionMap.put(methodRef, func);
                return;
            }

            if (methodNode != null && methodNode.isNative()) {
                // Native method: declare the function with the __jnative_ prefix,
                // the implementation is provided by the runtime (jnative_runtime.c)
                String nativeName = "__jnative_" + LlvmRuntime.mangleMethod(owner, name, desc);
                Function func = new Function(nativeName, methodNode.getReturnType());
                for (Type paramType : methodNode.getParameterTypes()) {
                    func.addParameter(new Parameter(paramType, func.getParameters().size()));
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
                log.warn("No bytecode for class {}", owner);
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
            }
        } catch (Exception e) {
            log.warn("Failed to translate method: {} - {}", methodRef, e.getMessage());
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
