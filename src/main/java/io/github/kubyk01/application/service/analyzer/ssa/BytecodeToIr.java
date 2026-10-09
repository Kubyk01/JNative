package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityAnalysis;
import io.github.kubyk01.application.service.codegen.NativeOverride;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.IrBuilder;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.Type;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.kubyk01.util.LlvmUtil.extractCalleeName;

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

        closeOverIrCallees();

        return builder.getModule();
    }

    /**
     * Transitively closes the module with respect to the relation
     * "there is a call to callee in the IR -> callee must be in the module".
     *
     * <p>The main reachability walk runs over bytecode and fills the
     * worklist. It can miss a target in a narrow set of situations — the
     * visitor did not look far enough into a method, the class was still a
     * stub at traversal time, or the caller was synthesized after the walk
     * finished. In each of those cases the caller ends up in the module, its
     * call instruction references the callee by mangled name, and the
     * callee's body is not in the module.</p>
     *
     * <p>The IR itself is a precise record of what is called: every
     * {@code SPECIAL_CALL / STATIC_CALL / CALL} instruction carries the
     * mangled name of its callee in a constant operand, and that name is
     * built by the same
     * {@link io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime#mangleMethod}
     * used for the target function's name. Walking the IR and translating
     * everything that is missing closes the gap without duplicating the
     * bytecode walk.</p>
     *
     * <p>Iterates to a fixed point: a translated callee may itself contain
     * calls to new callees.</p>
     *
     * <p><b>Visibility.</b> This method is {@code public} so that
     * {@link io.github.kubyk01.application.service.analyzer.Analyzer} can
     * invoke it (through {@link #closeOverIrCalleesWithSsa()}) after
     * resurrecting missing {@code <clinit>} functions. The orchestrator
     * drives the initial pass; the analyzer drives the post-resurrection
     * pass. Both walk the same module through the same method, so the
     * reachability invariant "every call target has a body" is enforced
     * from both ends.</p>
     */
    public void closeOverIrCallees() {
        Module module = builder.getModule();
        final int MAX_PASSES = 64;
        int pass = 0;

        while (pass++ < MAX_PASSES) {
            List<MethodReference> missing = new ArrayList<>();
            Set<MethodReference> seen = new HashSet<>();

            for (Function func : module.getFunctions()) {
                if (func.getEntryBlock() == null) continue;
                for (BasicBlock block : func.getBlocks()) {
                    for (Instruction inst : block.getInstructions()) {
                        Opcode op = inst.getOpcode();
                        if (op != Opcode.CALL
                            && op != Opcode.STATIC_CALL
                            && op != Opcode.VIRTUAL_CALL
                            && op != Opcode.INTERFACE_CALL
                            && op != Opcode.SPECIAL_CALL) {
                            continue;
                        }
                        String callee = extractCalleeName(inst);
                        if (callee == null) continue;

                        int dotIdx   = callee.lastIndexOf('.');
                        int parenIdx = callee.indexOf('(');
                        if (dotIdx <= 0 || parenIdx <= dotIdx) continue;

                        String owner      = callee.substring(0, dotIdx);
                        String methodPart = callee.substring(dotIdx + 1);
                        int    localParen = parenIdx - dotIdx - 1;
                        String name       = methodPart.substring(0, localParen);
                        String desc       = methodPart.substring(localParen);

                        MethodReference ref = new MethodReference(owner, name, desc);
                        if (!seen.add(ref)) continue;

                        String mangled = LlvmRuntime.mangleMethod(owner, name, desc);
                        Function existing = module.getFunction(mangled);
                        if (existing != null && existing.getEntryBlock() != null) {
                            continue;
                        }

                        // Force-load the class if it is still an external stub
                        // or has no bytecode at all.
                        ClassNode cn = resolver.getClassNode(owner);
                        if (cn == null || cn.isExternal()
                            || resolver.getClassBytes(owner) == null) {
                            resolver.forceLoadSystemClass(owner);
                            cn = resolver.getClassNode(owner);
                        }
                        // If the class really is external (C helper, JNI
                        // symbol, runtime function) skip it: such calls are
                        // emitted as declarations and resolved by the linker.
                        if (cn == null || cn.isExternal()) continue;

                        // Check once more after forceLoad: the function may
                        // have been registered as an alias.
                        existing = module.getFunction(mangled);
                        if (existing != null && existing.getEntryBlock() != null) {
                            continue;
                        }

                        missing.add(ref);
                    }
                }
            }

            if (missing.isEmpty()) return;

            for (MethodReference ref : missing) {
                NativeOverride match = findOverride(ref);
                if (match != null) {
                    String key = LlvmRuntime.mangleMethod(
                        ref.getOwner(), ref.getName(), ref.getDescriptor());
                    Function target = module.getFunction(match.getCFunctionName());
                    if (target != null) {
                        module.registerAlias(key, target);
                    }
                    continue;
                }
                translateMethod(ref);
            }
        }

        log.warn("IR-callee closure did not converge after {} passes; "
                + "some call targets may still be missing from the module",
            MAX_PASSES);
    }

    /**
     * Closes over IR callees and applies SSA to every function this call
     * translated.
     *
     * <p>The initial {@link #translate()} pass closes over callees
     * <em>before</em> the orchestrator's SSA loop runs, so the functions
     * it adds are SSA-transformed by that external loop along with the
     * rest of the module. Callers that execute after the loop — most
     * notably
     * {@link io.github.kubyk01.application.service.analyzer.Analyzer}
     * after {@code resurrectRemovedClinits} and
     * {@code ensureReferencedClinitsPresent} have added
     * {@code <clinit>} bodies — cannot rely on that external pass: it
     * has already completed, and any function added afterwards would
     * reach the LLVM emitter without PHIs and without the local-slot
     * renaming that SSA performs.</p>
     *
     * <p>The concrete failure this method exists to close is documented
     * in the fun.txt report. A resurrected
     * {@code java/net/Authenticator$RequestorType.<clinit>} emitted two
     * calls — to the enum constructor
     * {@code <init>(Ljava/lang/String;I)V} and to the synthetic
     * {@code $values()} — neither of which had been translated into the
     * module. Without this pass, the LLVM emitter converted both calls
     * into {@code __jnative_unresolved_slot} traps and the executable
     * aborted before {@code main} had produced any output.</p>
     *
     * <p>The function set is snapshotted before the closure, and only
     * functions that appear in the module <em>after</em> the closure
     * and <em>not before</em> are SSA-transformed. Functions that were
     * already present are left alone: they have already been
     * SSA-transformed by the orchestrator, and re-transforming them
     * would be a no-op at best and a corruption at worst.</p>
     */
    public void closeOverIrCalleesWithSsa() {
        Set<String> preexisting = new HashSet<>();
        for (Function f : builder.getModule().getFunctions()) {
            preexisting.add(f.getName());
        }

        closeOverIrCallees();

        SSATransformer ssa = new SSATransformer();
        for (Function f : builder.getModule().getFunctions()) {
            if (preexisting.contains(f.getName())) continue;
            if (f.getEntryBlock() == null) continue;
            try {
                ssa.transform(f);
            } catch (Exception e) {
                log.warn("SSA transform failed for callee {} translated "
                        + "by closeOverIrCalleesWithSsa: {}",
                    f.getName(), e.getMessage());
            }
        }
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