package io.github.kubyk01.application.service.analyzer.ssa;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.reachabilityanalysis.ReachabilityAnalysis;
import io.github.kubyk01.application.service.codegen.llvm.LlvmRuntime;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.IrBuilder;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Type;

import java.util.List;
import java.util.TreeSet;

/**
 * Synthesises the body of {@code java.lang.Class.getEnumConstantsShared()}.
 *
 * <p>The reference JDK implementation reaches the enum's compiler-generated
 * static {@code values()} method through reflection:</p>
 *
 * <pre>
 *   Method values = getMethod("values");
 *   T[] temporaryConstants = (T[])values.invoke(null);
 * </pre>
 *
 * <p>That path requires two pieces of machinery this runtime does not
 * implement: {@code Class.getDeclaredMethods0} (currently a stub that
 * returns an empty array) must return real {@code java.lang.reflect.Method}
 * objects, and {@code Method.invoke} must dispatch through a
 * MethodAccessor. Both are large, JDK-version-brittle subsystems whose
 * only consumer inside the bootstrap path is this single method.</p>
 *
 * <p>Rather than building that machinery, this class synthesises the
 * method's IR directly. The generated body compares its receiver — the
 * {@code Class} mirror of the enum whose constants are being requested
 * — against every reachable enum class in turn, and on a match calls
 * that enum's compiled {@code values()} function and returns the
 * result. If no enum matches, it returns {@code null}, which is the
 * same value the reference implementation produces for a class that
 * {@code isEnum()} rejects or whose reflective lookup fails.</p>
 *
 * <p>The set of reachable enums is fixed at compile time by
 * {@link ReachabilityAnalysis}, which is also responsible for forcing
 * each enum's {@code values()} into the reachable set (see
 * {@code ReachabilityAnalysis.ensureEnumMethodsReachable}). Both
 * guarantees are what make this dispatch table complete and correct:
 * no enum the program can observe is missing from the chain, and every
 * branch in the chain targets a function that actually exists in the
 * module.</p>
 *
 * <p>The comparison uses pointer identity between the class mirror
 * objects. In this runtime every {@code Class<?>} value is a pointer to
 * one of the {@code @refclass_*} constants emitted by
 * {@link io.github.kubyk01.application.service.codegen.llvm.LlvmGlobalEmitter};
 * there is exactly one mirror per class, and a Java-level {@code ==}
 * comparison on {@code Class} objects is exactly a pointer comparison
 * at the LLVM level. That property is what lets the dispatch be a
 * simple sequence of {@code icmp eq i8*} checks rather than a name
 * lookup.</p>
 */
public final class EnumConstantsSharedEmitter {

    private EnumConstantsSharedEmitter() {}

    private static final String OWNER = "java/lang/Class";
    private static final String NAME  = "getEnumConstantsShared";
    private static final String DESC  = "()[Ljava/lang/Object;";

    /** The compiler-generated enum constant accessor. */
    private static final String VALUES_NAME = "values";

    public static void emit(MethodReference methodRef,
                            IrBuilder builder,
                            DependencyResolver resolver,
                            ReachabilityAnalysis reachability) {

        if (!OWNER.equals(methodRef.getOwner())
            || !NAME.equals(methodRef.getName())
            || !DESC.equals(methodRef.getDescriptor())) {
            throw new IllegalArgumentException(
                "EnumConstantsSharedEmitter called for the wrong method: " + methodRef);
        }

        String mangledName = LlvmRuntime.mangleMethod(OWNER, NAME, DESC);

        // Return type is T[] erased to Object[], i.e. [Ljava/lang/Object;.
        Type returnType = Type.array("[Ljava/lang/Object;");
        Parameter thisParam = new Parameter(Type.reference(OWNER), 0);

        Function func = builder.createFunctionWithSlots(
            mangledName, returnType, List.of(thisParam));

        BasicBlock entry = builder.createBlock(mangledName + "_entry");

        // Collect every reachable enum whose values() is emitted. Sorted
        // so the emitted chain is deterministic across runs; the ORDER
        // of comparisons does not affect correctness, only the emitted
        // IR bytes.
        TreeSet<String> enums = new TreeSet<>();
        for (String cls : reachability.getReachableClasses()) {
            if (cls == null || cls.isEmpty() || cls.charAt(0) == '[') continue;
            ClassNode cn = resolver.getClassNode(cls);
            if (cn == null || cn.isExternal()) continue;
            if (!isEnum(cn)) continue;
            if (!hasValuesFunction(cn)) continue;
            enums.add(cls);
        }

        BasicBlock current = entry;

        for (String enumName : enums) {
            BasicBlock matchBlock = builder.createBlock(
                mangledName + "_match_" + sanitize(enumName));
            BasicBlock nextBlock = builder.createBlock(
                mangledName + "_next_" + sanitize(enumName));

            builder.setCurrentBlock(current);

            // %cmp = icmp eq i8* %this, bitcast(%ReflectionClass* @refclass_E to i8*)
            // Constant of type java/lang/Class with the enum's internal
            // name renders to exactly that bitcast in the LLVM emitter
            // (see LlvmFunctionEmitter.constantToLlvmLiteral).
            Constant mirror = new Constant(
                Type.reference("java/lang/Class"), enumName);
            Instruction cmp = builder.addInstruction(
                Opcode.EQ, thisParam, mirror);

            CondBranchTerminator branch = new CondBranchTerminator(
                cmp.getResult(), matchBlock, nextBlock);
            current.setTerminator(branch);
            current.addSuccessor(matchBlock);
            current.addSuccessor(nextBlock);

            // Match: %v = call <E>.values(); ret %v
            builder.setCurrentBlock(matchBlock);

            String valuesDesc = "()[L" + enumName + ";";
            String valuesCallee = enumName + "." + VALUES_NAME + valuesDesc;

            Instruction call = new Instruction(Opcode.STATIC_CALL);
            call.addOperand(new Constant(
                Type.reference(valuesCallee), valuesCallee));
            Temporary result = builder.newTemporary(returnType);
            call.setResult(result);
            result.setDefiningInstruction(call);
            matchBlock.addInstruction(call);

            matchBlock.setTerminator(new ReturnTerminator(result));

            current = nextBlock;
        }

        // Fall-through: no match. Same as the reference implementation
        // returning null for a non-enum or on reflective failure.
        builder.setCurrentBlock(current);
        current.setTerminator(new ReturnTerminator(
            new Constant(Type.NULL, null)));
    }

    private static boolean isEnum(ClassNode cn) {
        final int ACC_ENUM = 0x4000;
        if ((cn.getAccess() & ACC_ENUM) == 0) return false;
        return "java/lang/Enum".equals(cn.getSuperName());
    }

    private static boolean hasValuesFunction(ClassNode cn) {
        for (MethodNode mn : cn.getMethods()) {
            if (!mn.isStatic()) continue;
            if (!VALUES_NAME.equals(mn.getName())) continue;
            if (!mn.getDescriptor().startsWith("()[L")) continue;
            return true;
        }
        return false;
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}