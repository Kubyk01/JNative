package io.github.kubyk01.application.service.analyzer.reachabilityanalysis;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.reachability.TypedValue;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import io.github.kubyk01.domain.ir.Type;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.*;

import java.util.*;

import static io.github.kubyk01.domain.ir.Type.BOOLEAN;
import static io.github.kubyk01.domain.ir.Type.BYTE;
import static io.github.kubyk01.domain.ir.Type.CHAR;
import static io.github.kubyk01.domain.ir.Type.DOUBLE;
import static io.github.kubyk01.domain.ir.Type.FLOAT;
import static io.github.kubyk01.domain.ir.Type.INT;
import static io.github.kubyk01.domain.ir.Type.LONG;
import static io.github.kubyk01.domain.ir.Type.SHORT;
import static io.github.kubyk01.domain.ir.Type.UNKNOWN;
import static io.github.kubyk01.domain.ir.Type.array;
import static io.github.kubyk01.domain.ir.Type.fromDescriptor;
import static io.github.kubyk01.domain.ir.Type.reference;

@Slf4j
public class MethodBytecodeVisitor extends ClassVisitor {

    private final DependencyResolver resolver;
    private final Set<String> reachableClasses;
    private final ReflectInfo reflectInfo;
    private final ReachabilityAnalysis analysis;

    private final MethodReference currentMethod;
    private final boolean reachableFromUser;
    private final MethodReference caller;

    private String lastLoadedClass = null;

    public MethodBytecodeVisitor(DependencyResolver resolver,
                                 Set<String> reachableClasses,
                                 ReflectInfo reflectInfo,
                                 ReachabilityAnalysis analysis,
                                 MethodReference currentMethod,
                                 boolean reachableFromUser,
                                 MethodReference caller) {
        super(Opcodes.ASM9);
        this.resolver = resolver;
        this.reachableClasses = reachableClasses;
        this.reflectInfo = reflectInfo;
        this.analysis = analysis;
        this.currentMethod = currentMethod;
        this.reachableFromUser = reachableFromUser;
        this.caller = caller;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        if (name.equals(currentMethod.getName()) && descriptor.equals(currentMethod.getDescriptor())) {
            return new MethodVisitorImpl();
        }
        return null;
    }

    public void parse(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        reader.accept(this, ClassReader.SKIP_DEBUG);
    }

    // ------------------------------------------------------------------
    //  Wrappers that thread the reachableFromUser flag through to the
    //  ReachabilityAnalysis API.
    // ------------------------------------------------------------------

    /**
     * Passive reference: the class becomes reachable, but its {@code <clinit>}
     * is NOT enqueued. Use for descriptors, catch-types, {@code LDC X.class},
     * annotation types and method references.
     */
    private void addClass(String className) {
        analysis.addClass(className, reachableFromUser);
    }

    /**
     * Active use: the class becomes reachable AND its {@code <clinit>} is
     * enqueued. Use for {@code new}, {@code getstatic}/{@code putstatic},
     * {@code invokestatic} into a non-native method, and reflective
     * instantiation.
     */
    private void addClassWithInit(String className) {
        analysis.addClassWithInit(className, reachableFromUser);
    }

    private void addMethodWithContext(MethodReference ref, boolean user) {
        analysis.addMethodWithContext(ref, user, caller);
    }

    private void addTypeFromDescriptor(String desc) {
        analysis.addTypeFromDescriptor(desc, reachableFromUser);
    }

    // ------------------------------------------------------------------
    //  Recognizer for MethodHandles.Lookup.findXxx.
    //
    //  Lives on the outer class rather than on MethodVisitorImpl so that it
    //  can be consulted from two different places (isReflectiveCall and
    //  handleReflectiveCall) without duplication. It is static because it
    //  touches no visitor state; all of its inputs arrive as arguments.
    // ------------------------------------------------------------------

    /**
     * Recognises the {@code MethodHandles.Lookup.findXxx} family.
     *
     * <p>These methods are the standard mechanism by which modern JDK code
     * obtains a {@code MethodHandle} or {@code VarHandle} for a field or
     * method whose declaring class is known at compile time. Functionally
     * they are equivalent to {@code Class.getDeclaredMethod} followed by
     * {@code MethodHandles.unreflect} (or the corresponding pair for a
     * field), but they skip the intermediate {@code Method}/{@code Field}
     * mirror.</p>
     *
     * <p>They matter for reachability for exactly the same reason the
     * {@code Class.getDeclaredMethod} family does: without registering the
     * target in {@link ReflectInfo}, the native
     * {@code Class.getDeclaredMethods0} hands the JDK an empty array, and
     * any reflective lookup built on top of it fails with
     * {@code NoSuchMethodException}.</p>
     *
     * <p>Recognised signatures (JDK 21):</p>
     * <ul>
     *   <li>{@code findStatic(Class<?>, String, MethodType)}</li>
     *   <li>{@code findVirtual(Class<?>, String, MethodType)}</li>
     *   <li>{@code findSpecial(Class<?>, String, MethodType, Class<?>)}</li>
     *   <li>{@code findConstructor(Class<?>, MethodType)}</li>
     *   <li>{@code findGetter(Class<?>, String, Class<?>)}</li>
     *   <li>{@code findSetter(Class<?>, String, Class<?>)}</li>
     *   <li>{@code findStaticGetter(Class<?>, String, Class<?>)}</li>
     *   <li>{@code findStaticSetter(Class<?>, String, Class<?>)}</li>
     *   <li>{@code findVarHandle(Class<?>, String, Class<?>)}</li>
     *   <li>{@code findStaticVarHandle(Class<?>, String, Class<?>)}</li>
     * </ul>
     *
     * <p>Matching is done on the exact descriptor, not on the method-name
     * prefix. {@code Lookup} contains other {@code find}-prefixed methods
     * ({@code findClass}, …) that are not reflective and must not reach
     * the handler. An exact-signature match rules them out without any
     * additional return-type analysis.</p>
     */
    private static boolean isLookupFindMethod(String owner, String name, String desc) {
        if (!owner.equals("java/lang/invoke/MethodHandles$Lookup")) {
            return false;
        }

        // findStatic / findVirtual take (Class, String, MethodType).
        return switch (name) {
            case "findStatic", "findVirtual" -> desc.equals(
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/MethodHandle;");


            // findSpecial adds a fourth argument — the class from which the
            // "special" invocation is made (for super/private dispatch).
            case "findSpecial" -> desc.equals(
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");


            // findConstructor has no method-name argument: a constructor is
            // always <init>.
            case "findConstructor" -> desc.equals(
                    "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/MethodHandle;");


            // Field getter/setter — MethodHandle variants.
            case "findGetter", "findSetter", "findStaticGetter", "findStaticSetter" -> desc.equals(
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)"
                            + "Ljava/lang/invoke/MethodHandle;");


            // VarHandle variants. The return type differs (VarHandle rather
            // than MethodHandle), and that is the only thing distinguishing
            // them from the getter/setter family at the descriptor level.
            case "findVarHandle", "findStaticVarHandle" -> desc.equals(
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)"
                            + "Ljava/lang/invoke/VarHandle;");
            default -> false;
        };

    }

    // ------------------------------------------------------------------
    //  MethodVisitor
    // ------------------------------------------------------------------

    private class MethodVisitorImpl extends MethodVisitor {

        private final TypeSimulator simulator = new TypeSimulator();

        public MethodVisitorImpl() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitCode() {
            super.visitCode();
        }

        @Override
        public void visitInsn(int opcode) {
            simulator.visitInsn(opcode);
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            simulator.visitIntInsn(opcode, operand);
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            simulator.visitVarInsn(opcode, var);
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) {
                // `new X` is an active use of X: JLS §12.4.1 requires
                // X's <clinit> to run before the constructor is invoked.
                analysis.addInstantiatedClass(type, reachableFromUser);
            } else if (opcode == Opcodes.ANEWARRAY) {
                // `new X[n]` does NOT trigger initialization of X.
                // JLS §12.4.1: array creation is not an active use of
                // the element type; only the array class itself is
                // created, and it has no <clinit>. The element type
                // must merely be loadable — its Class mirror has to
                // exist for getClass()/instanceof on the resulting
                // array — so the reference is registered as passive.
                addClass(type);
            } else if (opcode == Opcodes.MULTIANEWARRAY) {
                // `type` is an array descriptor such as
                // "[[Ljava/lang/String;". The only non-array class
                // that needs a mirror is the innermost reference
                // component; a fully primitive descriptor such as
                // "[[I" needs no class at all.
                String elem = elementClassOfArrayDescriptor(type);
                if (elem != null) {
                    addClass(elem);
                }
            }
            simulator.visitTypeInsn(opcode, type);
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // getstatic/putstatic on owner's static field is an active use of
            // the owner class (JLS §12.4.1) and must trigger its <clinit>.
            // getfield/putfield only touch an already-created instance and
            // do not require class initialization.
            if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) {
                analysis.triggerClinit(owner, reachableFromUser);
            }
            simulator.visitFieldInsn(opcode, descriptor);
            super.visitFieldInsn(opcode, owner, name == null ? name : name, name);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String mName,
                                    String mDesc, boolean isInterface) {
            boolean reflective = isReflectiveCall(owner, mName, mDesc);

            if (reflective) {
                int argCount = countArguments(mDesc);
                List<TypedValue> args = new ArrayList<>();
                for (int i = 0; i < argCount; i++) {
                    args.addFirst(simulator.pop());
                }
                // Capture the receiver instead of discarding it. For the
                // Class.getDeclaredMethod / getMethod / getDeclaredField /
                // getField / newInstance families the *receiver* is the
                // class whose members are being looked up, and for
                // varargs forms the receiver's class literal is not the
                // last LDC class literal in the bytecode (parameter class
                // literals between it and INVOKEVIRTUAL overwrite
                // `lastLoadedClass`). Passing the receiver explicitly is
                // therefore the only reliable source for the target class.
                TypedValue receiver = null;
                if (opcode != Opcodes.INVOKESTATIC) {
                    receiver = simulator.pop();
                }
                TypedValue reflectiveResult =
                    handleReflectiveCall(owner, mName, mDesc, receiver, args);
                Type retType = TypeResolver.descToReturnType(mDesc);
                if (!retType.isVoid()) {
                    // If the handler produced a value with more information
                    // than the plain declared return type, use it. For
                    // Class.forName and ClassLoader.loadClass with a
                    // constant class-name argument the handler returns a
                    // TypedValue whose `value` field carries the internal
                    // name of the looked-up class (same encoding LDC
                    // X.class uses). Downstream reflective calls — most
                    // importantly Class.getDeclaredConstructor and
                    // Class.getConstructor — recover that name through
                    // resolveClassNameFromValue(receiver) and register the
                    // correct constructors in the reflection table. Without
                    // this the receiver would be an ordinary Class TypedValue
                    // with no class-name information, the handler would fall
                    // back to the unsafe `lastLoadedClass`, and the target
                    // would be whatever class literal happened to be LDC'd
                    // last (for the DirectByteBufferR / DirectByteBuffer
                    // constructor lookups that is MemorySegment, an interface
                    // with no constructors at all).
                    simulator.push(reflectiveResult != null
                        ? reflectiveResult
                        : TypedValue.fromType(retType));
                }
            } else {
                String rawReceiverType = simulator.getReceiverType(opcode, mDesc);
                String receiverType =
                    (isAssignableTo(rawReceiverType, owner))
                        ? rawReceiverType
                        : null;

                // Always add the method for the owner.
                MethodReference ownerRef = new MethodReference(owner, mName, mDesc);
                addMethodWithContext(ownerRef, reachableFromUser);

                // invokestatic triggers class initialization of the
                // class that *declares* the target method, not of the
                // class through which the call site refers to it. JLS
                // §12.4.1 initializes the declaring class; a static
                // method inherited from Super and invoked as Sub.m()
                // initializes Super, not Sub.
                //
                // A native method's implementation lives in C and is
                // independent of the class's <clinit>; forcing <clinit>
                // for it would pull in e.g. Thread.<clinit>
                // (registerNatives) just because someone called
                // Thread.currentThread().
                if (opcode == Opcodes.INVOKESTATIC) {
                    String[] foundOwner = new String[1];
                    MethodNode target = resolver.findMethodInHierarchy(
                        owner, mName, mDesc, foundOwner);
                    if (target == null || !target.isNative()) {
                        String declOwner = (foundOwner[0] != null && !foundOwner[0].isEmpty())
                            ? foundOwner[0]
                            : owner;
                        analysis.triggerClinit(declOwner, reachableFromUser);
                    }
                }

                if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                    Set<String> candidateTypes;

                    if (receiverType != null && isConcreteClass(receiverType)) {
                        // The simulator pinned the receiver to an exact
                        // concrete class: register that class passively
                        // and use it as the sole dispatch candidate.
                        addClass(receiverType);
                        candidateTypes = Collections.singleton(receiverType);
                    } else {
                        String dispatchRoot = receiverType != null ? receiverType : owner;

                        // Primary optimisation: the dynamic type of a
                        // receiver is, by construction, an instantiated
                        // class. Only classes that have been observed
                        // being created (via NEW, a tracked reflective
                        // factory, or an allocator the runtime emits) can
                        // be the runtime type of a receiver, so the
                        // candidate set is the intersection of
                        // "subtypes of dispatchRoot" with "instantiated".
                        //
                        // Correctness: the set of instantiated classes
                        // grows monotonically as the worklist processes
                        // NEW instructions, and the deferred fixed-point
                        // pass in ReachabilityAnalysis
                        // (resolveVirtualDispatchFixedPoint)
                        // re-resolves every recorded dispatch site
                        // against the final instantiation set. A
                        // subclass that becomes instantiated after this
                        // site was first recorded is still picked up on
                        // a later pass of that loop.
                        //
                        // The previous revision fell back to the full
                        // resolver.getSubclasses(dispatchRoot) set
                        // whenever no instantiated subtype was known yet.
                        // For dispatchRoot == java/lang/Object — the
                        // static type of every argument to
                        // String.valueOf, every element of an Object[]
                        // iteration, and every generic-typed receiver —
                        // that fallback expands to every loaded class in
                        // the image. On a three-class input it turned a
                        // handful of reachable methods into ~27k by
                        // pulling in every unrelated override of
                        // toString / hashCode / equals that happens to
                        // be present in the JDK. It has been removed.
                        candidateTypes = analysis.instantiatedSubclasses(dispatchRoot);
                    }

                    // For each candidate receiver, resolve to the class
                    // that actually declares the dispatched
                    // implementation. Only that declaring class (if
                    // different from `owner`) is a genuinely new
                    // reachable target. Subclasses that simply inherit
                    // the owner's implementation contribute nothing and
                    // must not be registered as reachable — otherwise a
                    // single virtual call on an unknown receiver pulls
                    // in every loaded subclass.
                    Set<String> addedDeclaringClasses = new HashSet<>();
                    for (String target : candidateTypes) {
                        if (target.equals(owner)) continue;

                        String[] foundOwner = new String[1];
                        MethodNode targetMethod = resolver.findMethodInHierarchy(
                            target, mName, mDesc, foundOwner);
                        if (targetMethod == null || targetMethod.isAbstract()) continue;

                        String declaring = foundOwner[0];
                        if (declaring == null || declaring.equals(owner)) continue;

                        if (addedDeclaringClasses.add(declaring)) {
                            MethodReference ref = new MethodReference(declaring, mName, mDesc);
                            addMethodWithContext(ref, reachableFromUser);
                        }
                    }
                }
                // For INVOKESPECIAL we already added ownerRef above.
                simulator.visitMethodInsn(opcode, mDesc);
            }
            super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
            if (bsm != null && bsm.getOwner() != null) {
                String bsmOwner = bsm.getOwner();
                String bsmName  = bsm.getName();
                boolean isLambdaFactory =
                    bsmOwner.contains("LambdaMetafactory")
                        && (bsmName.equals("metafactory") || bsmName.equals("altMetafactory"));

                if (isLambdaFactory && bsmArgs.length >= 2 && bsmArgs[1] instanceof Handle implHandle) {
                    MethodReference implRef = new MethodReference(
                        implHandle.getOwner(),
                        implHandle.getName(),
                        implHandle.getDesc()
                    );
                    addMethodWithContext(implRef, reachableFromUser);
                }
            }
            super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String) {
                // String literals are tracked by
                // LlvmGlobalEmitter.generateStringLiterals; nothing to
                // register in the reachability walk.
            } else if (value instanceof org.objectweb.asm.Type asmType) {
                int sort = asmType.getSort();
                if (sort == org.objectweb.asm.Type.OBJECT) {
                    String internalName = asmType.getInternalName();
                    lastLoadedClass = internalName;
                    // LDC of a class literal (Foo.class) is a PASSIVE
                    // reference — it does NOT trigger Foo's <clinit>. It
                    // must, however, materialise a real Class object at
                    // runtime; register it here (threading the current
                    // method's reachability flag through) so the LLVM
                    // emitter produces the matching @refclass_Foo global
                    // that the literal can point at.
                    analysis.addClassLiteral(internalName, reachableFromUser);
                } else if (sort == org.objectweb.asm.Type.ARRAY) {
                    // Array class literal, e.g. Foo[].class or int[].class.
                    // The ASM descriptor IS the internal form of an array
                    // type, and is exactly the key used by
                    // LlvmGlobalEmitter.generateReflectionData when it
                    // emits @refclass_<sanitised descriptor>.
                    analysis.addClassLiteral(asmType.getDescriptor(), reachableFromUser);
                }
                // Primitive class literals (int.class, …) compile to
                // GETSTATIC Integer.TYPE and never reach this branch.
            }
            simulator.visitLdcInsn(value);
            super.visitLdcInsn(value);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            simulator.visitJumpInsn(opcode);
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (type != null) {
                // Catching a type only requires the class to be loaded, not
                // initialized (JLS §12.4.1 explicitly excludes it).
                addClass(type);
            }
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            addTypeFromDescriptor(descriptor);
            return super.visitParameterAnnotation(parameter, descriptor, visible);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addTypeFromDescriptor(descriptor);
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
            addTypeFromDescriptor(descriptor);
            return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
        }

        private boolean isReflectiveCall(String owner, String name, String desc) {
            // ----------------------------------------------------------------
            // Class.forName — both the one-argument form and the
            // three-argument form.
            //
            // Class.forName(String) is the classic reflective entry point.
            // Class.forName(String, boolean, ClassLoader) is the modern
            // overload that the JDK's own service-provider machinery uses:
            // java.security.Provider$Service.newInstance calls it to resolve
            // the provider implementation class whose name is stored in the
            // Service's `className` field. The class-name argument sits at
            // parameter slot 0 in both forms, so the handler below extracts
            // it identically; the boolean and ClassLoader arguments do not
            // affect reachability.
            //
            // Without recognition of the three-argument form, a class that
            // is named only through such a call is not registered with the
            // reachability walk, never enters the class map, and the
            // runtime's Class.forName0 fails to find it. This was the exact
            // failure behind the "NULL exception object substituted by
            // __jnative_throw_exception_ctx" crash inside
            // java.security.SecureRandom.getDefaultPRNG: the class
            // java.security.SecureRandomParameters was reachable only
            // through the three-argument form.
            // ----------------------------------------------------------------
            if (owner.equals("java/lang/Class") && name.equals("forName")
                && (desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")
                || desc.equals("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"))) {
                return true;
            }

            // ----------------------------------------------------------------
            // ClassLoader.loadClass — one-argument and two-argument forms.
            //
            // ClassLoader.loadClass(String) is the classic reflective entry
            // point on the class-loader hierarchy. ClassLoader.loadClass
            // (String, boolean) is the internal form the JDK's own
            // ClassLoader.loadClass delegates to; it appears in bytecode
            // whenever a subclass overrides loadClass and forwards to
            // super.loadClass(name, resolve).
            // ----------------------------------------------------------------
            if (owner.equals("java/lang/ClassLoader") && name.equals("loadClass")
                && (desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")
                || desc.equals("(Ljava/lang/String;Z)Ljava/lang/Class;"))) {
                return true;
            }

            // NOTE: no closing parenthesis in the prefix. The descriptor of
            // the varargs forms is
            //     (Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
            // so a prefix that included the ')' would never match, and the
            // entire handler below would be dead code.
            if (owner.equals("java/lang/Class") && (name.equals("getMethod") || name.equals("getDeclaredMethod"))
                && desc.startsWith("(Ljava/lang/String;")) {
                return true;
            }
            // ------------------------------------------------------------------
            // Class.getConstructor / Class.getDeclaredConstructor.
            //
            // The constructor-lookup counterparts of getMethod / getDeclaredMethod.
            // They are just as important for reachability: the JDK resolves many
            // security-critical implementations (NativePRNG, DRBG, SHA1PRNG,
            // any ServiceLoader-loaded SPI) exclusively through
            // Class.getConstructor(Class[]) inside
            // java.security.Provider$Service.newInstanceUtil.
            //
            // Without recognising them here:
            //
            //   - Provider$Service.newInstanceUtil's reflective call is invisible
            //     to the walk, so nothing is registered in ReflectInfo;
            //   - LlvmGlobalEmitter.generateReflectionData then emits
            //     @refctors_<target> = [1 x i8*] [i8* null] for every such target;
            //   - at run time, Class.getConstructor0 -> getDeclaredConstructors0
            //     returns an empty array, the reflective lookup fails with
            //     NoSuchMethodException even for a public constructor, and the
            //     caller's "should not happen" fallback is what surfaces:
            //
            //       NoSuchAlgorithmException: Error constructing implementation
            //         (algorithm: NativePRNG, provider: SUN,
            //          class: sun.security.provider.NativePRNG)
            //         ... caused by
            //       NoSuchMethodException:
            //         sun.security.provider.NativePRNG.<init>(
            //             java.security.SecureRandomParameters)
            //
            // The descriptor has no name argument (constructors are always named
            // <init>); the Class[] carries only the parameter types, whose
            // individual elements are not visible through the TypeSimulator's
            // array handling. The handler therefore registers every declared
            // constructor of the target class — the same conservative policy
            // that registerAllMethodsByName already uses for
            // getDeclaredMethod's varargs form.
            // ------------------------------------------------------------------
            if (owner.equals("java/lang/Class")
                && (name.equals("getConstructor") || name.equals("getDeclaredConstructor"))
                && desc.equals("([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;")) {
                return true;
            }
            if (owner.equals("java/lang/Class") && (name.equals("getField") || name.equals("getDeclaredField"))
                && desc.startsWith("(Ljava/lang/String;)")) {
                return true;
            }
            if (owner.equals("java/lang/reflect/Method") && name.equals("invoke")
                && desc.equals("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")) {
                return true;
            }
            if (owner.equals("java/lang/reflect/Constructor") && name.equals("newInstance")
                && desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
                return true;
            }
            if (owner.equals("java/lang/Class") && name.equals("newInstance")
                && desc.equals("()Ljava/lang/Object;")) {
                return true;
            }
            // ------------------------------------------------------------------
            // jdk.internal.misc.Unsafe.objectFieldOffset(Class, String)
            // (and its native bridge objectFieldOffset1).
            //
            // The call sites that matter here all live in static initializers
            // of JDK classes that compute their own field offsets at class-
            // initialization time — ConcurrentHashMap.<clinit> is the
            // canonical example, computing SIZECTL, TRANSFERINDEX, BASECOUNT
            // and CELLSBUSY exactly this way.
            //
            // Treating the call as reflective is what makes the reachability
            // walk register the named field in ReflectInfo. Without that
            // registration LlvmGlobalEmitter emits an empty @reffields_*
            // array for the target class; the runtime's
            // objectFieldOffset1 then returns 0 for every lookup, and any
            // CAS that uses the returned offset compares the object's
            // vtable slot at offset 0 instead of the intended field. For
            // ConcurrentHashMap.initTable the effect is a livelock: the
            // CAS on sizeCtl never succeeds because it is not looking at
            // sizeCtl.
            // ------------------------------------------------------------------
            if (owner.equals("jdk/internal/misc/Unsafe")
                && (name.equals("objectFieldOffset") || name.equals("objectFieldOffset1"))
                && desc.equals("(Ljava/lang/Class;Ljava/lang/String;)J")) {
                return true;
            }
            // ------------------------------------------------------------------
            // jdk.internal.misc.Unsafe.allocateInstance(Class<?>)
            //
            // Allocates an instance of the given class without invoking a
            // constructor. Used by ReflectionFactory for deserialisation
            // and by a handful of JDK classes that need to bypass the
            // no-arg-constructor requirement. The class argument is
            // always an LDC of a class literal in JDK call sites, so
            // lastLoadedClass holds the target. Registering the class as
            // instantiated is what keeps the virtual-dispatch candidate
            // filter from dropping overrides on the class.
            // ------------------------------------------------------------------
            if (owner.equals("jdk/internal/misc/Unsafe")
                && name.equals("allocateInstance")
                && desc.equals("(Ljava/lang/Class;)Ljava/lang/Object;")) {
                return true;
            }
            // ------------------------------------------------------------------
            // MethodHandles.Lookup.findStatic / findVirtual / findSpecial /
            // findConstructor / findGetter / findSetter / findStaticGetter /
            // findStaticSetter / findVarHandle / findStaticVarHandle.
            //
            // These calls are the primary mechanism by which JDK 9+
            // resolves MethodHandles and VarHandles for fields and methods
            // known at compile time. They were not recognised by previous
            // revisions of this visitor, and that is precisely why
            // @refmethods_* for
            // java.lang.invoke.MethodHandleImpl$CountingWrapper ended up
            // empty and the class's <clinit> failed with a
            // NoSuchMethodException wrapped in an InternalError.
            // ------------------------------------------------------------------
            return isLookupFindMethod(owner, name, desc);
        }

        /**
         * Handles a single reflective call site.
         *
         * <p>Returns the {@link TypedValue} to push onto the simulated operand
         * stack as the result of the call, or {@code null} if the caller should
         * fall back to the method's declared return type. Only the handlers for
         * {@code Class.forName} and {@code ClassLoader.loadClass} return a
         * non-null value: for those two, and only when the class-name argument
         * is a compile-time constant, the result is a {@code TypedValue} whose
         * {@code value} field carries the internal name of the looked-up class
         * (exactly the encoding {@code LDC X.class} already uses). That name is
         * what downstream reflective calls — {@code getDeclaredConstructor},
         * {@code getDeclaredMethod}, {@code getField}, {@code newInstance} —
         * read back through {@link #resolveClassNameFromValue(TypedValue)} to
         * determine their target class.</p>
         *
         * <p>Returning {@code null} from every other branch preserves the
         * previous behaviour exactly: the caller pushes a plain
         * {@code TypedValue.fromType(retType)} and any downstream handler that
         * needs a class name has to rely on the historical {@code lastLoadedClass}
         * fallback.</p>
         */
        private TypedValue handleReflectiveCall(String owner, String mName, String mDesc,
                                                TypedValue receiver, List<TypedValue> args) {
            MethodReference reflectiveRef = new MethodReference(owner, mName, mDesc);
            addMethodWithContext(reflectiveRef, reachableFromUser);

            // ------------------------------------------------------------------
            // Lookup.findXxx is handled BEFORE the reachableFromUser check.
            //
            // Reflective calls issued from a system <clinit> — for example,
            // from java.lang.invoke.MethodHandleImpl$CountingWrapper.<clinit>
            // — are executed at run time regardless of whether the <clinit>
            // itself was reached directly from user code or through a chain
            // of system initializers. Registering the target in ReflectInfo
            // is a run-time artefact that must be produced in both cases;
            // otherwise Class.getDeclaredMethods0 returns an empty array and
            // the reflective lookup fails with NoSuchMethodException.
            //
            // That is why the reachableFromUser guard is skipped for this
            // family. The only thing the guard would have limited here is
            // the growth of the reflection table, and correctness outweighs
            // that concern.
            // ------------------------------------------------------------------
            if (isLookupFindMethod(owner, mName, mDesc)) {
                handleLookupFindCall(mName, args);
                return null;
            }

            if (!reachableFromUser) return null;

            // ------------------------------------------------------------------
            // Unsafe.objectFieldOffset(Class, String) /
            // Unsafe.objectFieldOffset1(Class, String).
            //
            // Both arguments of every JDK call site that reaches this path are
            // statically resolvable: the class argument is always an LDC of a
            // class literal (which also sets `lastLoadedClass`), and the field
            // name is always an LDC of a string constant. Registering the
            // resolved (class, field) pair in ReflectInfo ensures
            // LlvmGlobalEmitter.generateReflectionData emits a
            // @reffields_<class> entry with the same byte offset that every
            // direct GET_FIELD/PUT_FIELD in the generated code uses.
            //
            // This is what makes ConcurrentHashMap's static initializer
            // agree with the CAS operations in initTable about where
            // sizeCtl actually lives.
            // ------------------------------------------------------------------
            if (owner.equals("jdk/internal/misc/Unsafe")
                && (mName.equals("objectFieldOffset") || mName.equals("objectFieldOffset1"))
                && mDesc.equals("(Ljava/lang/Class;Ljava/lang/String;)J")) {
                registerUnsafeObjectFieldOffset(args);
                return null;
            }

            // ------------------------------------------------------------------
            // Unsafe.allocateInstance(Class<?>)
            //
            // The class argument is a class literal whose internal name
            // has already been stashed in lastLoadedClass. Recording the
            // class as instantiated keeps the virtual-dispatch candidate
            // filter from dropping overrides that live exclusively on it.
            // ------------------------------------------------------------------
            if (owner.equals("jdk/internal/misc/Unsafe")
                && mName.equals("allocateInstance")
                && mDesc.equals("(Ljava/lang/Class;)Ljava/lang/Object;")) {
                String target = resolveClassNameFromValue(
                    args.isEmpty() ? null : args.getFirst());
                if (target == null) {
                    target = lastLoadedClass;
                }
                if (target != null) {
                    analysis.addInstantiatedClass(target, reachableFromUser);
                }
                return null;
            }

            // ------------------------------------------------------------------
            // Class.forName — both forms.
            //
            // The class-name argument is parameter slot 0 in both the
            // one-argument and three-argument forms. When it is a string
            // constant the target is registered as an active use so its
            // <clinit> runs and it becomes a first-class member of the image,
            // and the call's result is returned as a TypedValue that carries
            // the internal name of the looked-up class. That name is what
            // every subsequent reflective call on the returned Class object
            // needs in order to resolve its own target class — without it,
            // a downstream getDeclaredConstructor/getDeclaredMethod handler
            // falls back to lastLoadedClass, which after the caller has built
            // a Class[] of parameter types is a parameter class literal, not
            // the class the caller actually queried.
            //
            // The concrete failure that motivated returning this value: the
            // static initializer of sun.nio.ch.Util does
            //
            //     Class<?> cl = Class.forName("java.nio.DirectByteBufferR");
            //     Constructor<?> ctor = cl.getDeclaredConstructor(
            //         int.class, long.class, FileDescriptor.class,
            //         Runnable.class, boolean.class, MemorySegment.class);
            //
            // and expects to find the class's reflect-only constructor. With
            // the pre-fix behaviour lastLoadedClass ended up being
            // java/lang/foreign/MemorySegment (the last parameter literal LDC'd
            // before INVOKEVIRTUAL), registerAllMethodsByName was called on
            // that interface, and @refctors_java_nio_DirectByteBufferR was
            // emitted empty. Class.getDeclaredConstructor then threw
            // NoSuchMethodException at run time and Util's catch block wrapped
            // it in an InternalError, aborting SystemModuleFinders$SystemImage.
            // ------------------------------------------------------------------
            if (owner.equals("java/lang/Class") && mName.equals("forName")
                && (mDesc.equals("(Ljava/lang/String;)Ljava/lang/Class;")
                || mDesc.equals("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"))) {
                if (!args.isEmpty()) {
                    TypedValue arg = args.getFirst();
                    if (arg.isConstant() && arg.getValue() instanceof String s) {
                        String className = s.replace('.', '/');
                        addClassWithInit(className);
                        analysis.addInstantiatedClass(className, reachableFromUser);
                        return TypedValue.fromConstant(
                            Type.reference("java/lang/Class"), className);
                    }
                }
                return null;
            }

            // ------------------------------------------------------------------
            // ClassLoader.loadClass — both forms.
            //
            // Same reasoning as Class.forName above: the class-name argument
            // is parameter slot 0, and the call's result must carry the
            // internal name of the loaded class so that downstream reflective
            // calls on the returned Class object can resolve their target.
            // The two-argument form (String, boolean) is what a subclass's
            // overridden loadClass forwards to when it delegates to
            // super.loadClass(name, resolve).
            // ------------------------------------------------------------------
            if (owner.equals("java/lang/ClassLoader") && mName.equals("loadClass")
                && (mDesc.equals("(Ljava/lang/String;)Ljava/lang/Class;")
                || mDesc.equals("(Ljava/lang/String;Z)Ljava/lang/Class;"))) {
                if (!args.isEmpty()) {
                    TypedValue arg = args.getFirst();
                    if (arg.isConstant() && arg.getValue() instanceof String s) {
                        String className = s.replace('.', '/');
                        addClassWithInit(className);
                        analysis.addInstantiatedClass(className, reachableFromUser);
                        return TypedValue.fromConstant(
                            Type.reference("java/lang/Class"), className);
                    }
                }
                return null;
            }

            // ------------------------------------------------------------------
            // Class.getMethod / Class.getDeclaredMethod.
            //
            // Both are declared as varargs:
            //
            //     Method getDeclaredMethod(String name, Class<?>... parameterTypes)
            //     Method getMethod        (String name, Class<?>... parameterTypes)
            //
            // When the call site supplies one or more parameter types, javac
            // wraps them into a synthetic Class[]:
            //
            //     LDC Target.class           ; <- receiver (loaded FIRST)
            //     LDC "name"
            //     ICONST_N
            //     ANEWARRAY java/lang/Class
            //     DUP
            //     ICONST_0
            //     LDC Param0.class           ; <- overwrites lastLoadedClass
            //     AASTORE
            //     ...
            //     INVOKEVIRTUAL Class.getDeclaredMethod
            //
            // Two consequences:
            //
            //   1. The target class is the *receiver*, not `lastLoadedClass`.
            //      Between the receiver's class literal and the call site,
            //      every parameter's class literal is also loaded via LDC,
            //      so `lastLoadedClass` ends up being the last parameter's
            //      class. The concrete failure was
            //      java.lang.invoke.MethodHandleImpl$CountingWrapper.<clinit>,
            //      whose getDeclaredMethod("maybeStopCounting", Object.class)
            //      left lastLoadedClass = "java/lang/Object" and never
            //      registered the target method.
            //
            //   2. The parameter list arrives as a single opaque array from
            //      the TypeSimulator's point of view, so it cannot be used
            //      to select a specific overload. The exact-match pass is
            //      attempted first and, when the list cannot be recovered,
            //      the code falls back to name-only registration — the
            //      JDK's own Class.getDeclaredMethod re-checks the exact
            //      signature at run time from the caller's Class[], so
            //      registering extra overloads costs nothing in correctness.
            //
            // The prefix check intentionally omits the closing parenthesis,
            // because the varargs descriptor continues with '[' immediately
            // after the String argument. A prefix that included ')' would
            // never match any real Class.getDeclaredMethod call site.
            // ------------------------------------------------------------------
            if (owner.equals("java/lang/Class")
                && (mName.equals("getMethod") || mName.equals("getDeclaredMethod"))
                && mDesc.startsWith("(Ljava/lang/String;")) {
                if (args.isEmpty()) return null;

                TypedValue nameArg = args.getFirst();
                if (!nameArg.isConstant() || !(nameArg.getValue() instanceof String methodName)) return null;
                switch (methodName) {
                    case "" -> {
                        return null;
                    }
                    // <clinit> is never requested reflectively; <init> is
                    // requested only through the constructor APIs.
                    case "<clinit>" -> {
                        return null;
                    }
                    case "<init>" -> {
                        return null;
                    }
                }

                List<String> paramClassNames = new ArrayList<>();
                boolean paramsFullyResolved = true;
                for (int i = 1; i < args.size(); i++) {
                    TypedValue param = args.get(i);
                    if (param.isConstant() && param.getValue() instanceof String) {
                        paramClassNames.add((String) param.getValue());
                    } else if (param.isExact()) {
                        paramClassNames.add(param.getClassName());
                    } else if (param.getType().isReference()) {
                        String cls = param.getType().getClassName();
                        if (cls != null) {
                            paramClassNames.add(cls);
                        } else {
                            paramsFullyResolved = false;
                        }
                    } else {
                        // Array argument — the common varargs Class[] form.
                        // Its elements are not visible through the
                        // simulator, so the list cannot be completed.
                        paramsFullyResolved = false;
                        break;
                    }
                }

                // Target class = receiver of the INVOKEVIRTUAL. Fall back to
                // lastLoadedClass only when the receiver was not statically
                // resolvable (for example, it was stored into a local under
                // an opaque type).
                String targetClass = null;
                if (receiver != null) {
                    targetClass = resolveClassNameFromValue(receiver);
                }
                if (targetClass == null) {
                    targetClass = lastLoadedClass;
                }
                if (targetClass == null) return null;

                ClassNode cn = resolver.getClassNode(targetClass);
                if (cn == null || cn.isExternal()) return null;

                boolean anyRegistered = false;

                if (paramsFullyResolved) {
                    for (MethodNode mn : cn.getMethods()) {
                        if (!mn.getName().equals(methodName)) continue;
                        if (mn.getName().equals("<clinit>")) continue;
                        if (mn.getName().equals("<init>")) continue;

                        List<Type> paramTypes = mn.getParameterTypes();
                        if (paramTypes.size() != paramClassNames.size()) continue;

                        boolean match = true;
                        for (int i = 0; i < paramTypes.size(); i++) {
                            Type pt = paramTypes.get(i);
                            String expected = paramClassNames.get(i);
                            if (!typeMatches(expected, pt)) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            MethodReference ref = new MethodReference(
                                targetClass, mn.getName(), mn.getDescriptor());
                            reflectInfo.addMethod(targetClass, ref);
                            addMethodWithContext(ref, true);
                            anyRegistered = true;
                        }
                    }
                }

                if (!anyRegistered) {
                    registerAllMethodsByName(targetClass, cn, methodName);
                }
                return null;
            }

            // ------------------------------------------------------------------
            // Class.getConstructor / Class.getDeclaredConstructor.
            //
            // The receiver is the class whose constructors are being looked up,
            // exactly as with getDeclaredMethod. The descriptor is varargs
            // (Class<?>...), so the parameter list arrives as a single opaque
            // Class[] — the same limitation that forces the getDeclaredMethod
            // handler into its name-only fallback. The conservative answer here
            // is the same: register every declared constructor of the target.
            //
            // Two things must happen for the target to become usable:
            //
            //   1. reflectInfo.addConstructor(targetClass, <init>desc). This is
            //      what makes @refctors_<target> carry a real entry for the
            //      constructor. Constructors live in a separate bucket of
            //      ReflectClassInfo from methods, and they are consumed by
            //      emitAdaptorForConstructor, whose symbol-name construction
            //      goes through LlvmRuntime.mangleMethod and therefore sanitises
            //      <init> correctly. Routing them into the methods bucket would
            //      make emitAdaptorForMethod try to build an adaptor symbol from
            //      the raw name "<init>" — the angle brackets are not legal LLVM
            //      symbol characters and clang rejects the whole module.
            //
            //   2. addMethodWithContext(<init>desc, true). This is what pulls the
            //      constructor's bytecode into the module as an IR Function.
            //      Without it, generateReflectionData would find the entry in the
            //      reflection table but no function body to point the adaptor at,
            //      and the @refctor_* constant would carry i8* null in its adaptor
            //      slot — Constructor.newInstance() would then NPE on the null
            //      target at run time.
            //
            // registerAllMethodsByName already handles the "<init>" case
            // correctly: it routes through reflectInfo.addConstructor, not
            // addMethod, and it calls addMethodWithContext for each one. We just
            // dispatch into it.
            //
            // The public/declared distinction (publicOnly=true vs false) is not
            // modelled at the visitor level on purpose: the C side
            // (Class.getDeclaredConstructors0 in Class.c) applies the public-only
            // filter itself, and registering all constructors is what lets the
            // two forms of the lookup — getConstructor(Class[]) and
            // getDeclaredConstructor(Class[]) — share one reflection table.
            // ------------------------------------------------------------------
            if (owner.equals("java/lang/Class")
                && (mName.equals("getConstructor") || mName.equals("getDeclaredConstructor"))
                && mDesc.equals("([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;")) {

                // The target is the receiver, not lastLoadedClass: for the varargs
                // form with explicit parameter classes the last class literal LDC'd
                // is one of the parameter types, not the class the lookup is
                // performed on. See the identical reasoning in the getDeclaredMethod
                // handler above.
                String targetClass = null;
                if (receiver != null) {
                    targetClass = resolveClassNameFromValue(receiver);
                }
                if (targetClass == null) {
                    targetClass = lastLoadedClass;
                }
                if (targetClass == null) return null;

                // Arrays have no constructors in this model — everything they
                // inherit is from java/lang/Object and is already covered by the
                // ordinary Object handling. Skip without loss of correctness.
                if (targetClass.charAt(0) == '[') return null;

                ClassNode cn = resolver.getClassNode(targetClass);
                if (cn == null || cn.isExternal() || resolver.getClassBytes(targetClass) == null) {
                    resolver.forceLoadSystemClass(targetClass);
                    cn = resolver.getClassNode(targetClass);
                }
                if (cn == null || cn.isExternal()) return null;

                registerAllMethodsByName(targetClass, cn, "<init>");
                return null;
            }

            if (owner.equals("java/lang/Class")
                && (mName.equals("getField") || mName.equals("getDeclaredField"))
                && mDesc.startsWith("(Ljava/lang/String;)")) {
                if (!args.isEmpty()) {
                    TypedValue arg = args.getFirst();
                    if (arg.isConstant() && arg.getValue() instanceof String fieldName) {
                        // Receiver is the class the field is looked up on.
                        // Same reasoning as getDeclaredMethod above: the
                        // lastLoadedClass fallback is unsafe when the call
                        // site has any other class literal between the
                        // receiver's literal and the reflective invocation.
                        String targetClass = null;
                        if (receiver != null) {
                            targetClass = resolveClassNameFromValue(receiver);
                        }
                        if (targetClass == null) {
                            targetClass = lastLoadedClass;
                        }
                        if (targetClass != null) {
                            FieldReference ref = new FieldReference(targetClass, fieldName, null);
                            reflectInfo.addField(targetClass, ref);
                            addClassWithInit(targetClass);
                        }
                    }
                }
                return null;
            }
            if (owner.equals("java/lang/reflect/Method") && mName.equals("invoke")
                && mDesc.equals("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;")) {
                for (String cls : reachableClasses) {
                    ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(cls);
                    for (MethodReference method : info.getMethods()) {
                        addMethodWithContext(method, true);
                    }
                }
                return null;
            }
            if (owner.equals("java/lang/reflect/Constructor") && mName.equals("newInstance")
                && mDesc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
                for (String cls : reachableClasses) {
                    ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(cls);
                    for (MethodReference ctor : info.getConstructors()) {
                        addMethodWithContext(ctor, true);
                        analysis.addInstantiatedClass(cls, reachableFromUser);
                    }
                }
                return null;
            }
            if (owner.equals("java/lang/Class") && mName.equals("newInstance")
                && mDesc.equals("()Ljava/lang/Object;")) {
                String targetClass = null;
                if (receiver != null) {
                    targetClass = resolveClassNameFromValue(receiver);
                }
                if (targetClass == null) {
                    targetClass = lastLoadedClass;
                }
                if (targetClass != null) {
                    ClassNode cn = resolver.getClassNode(targetClass);
                    if (cn != null && !cn.isExternal()) {
                        for (MethodNode mn : cn.getMethods()) {
                            if (mn.getName().equals("<init>") && mn.getDescriptor().equals("()V")) {
                                MethodReference ref = new MethodReference(targetClass, "<init>", "()V");
                                reflectInfo.addConstructor(targetClass, ref);
                                addMethodWithContext(ref, true);
                                analysis.addInstantiatedClass(targetClass, reachableFromUser);
                            }
                        }
                    }
                }
                return null;
            }

            return null;
        }

        /**
         * Handles a single call site of the
         * {@code MethodHandles.Lookup.findXxx} family: determines the
         * target class and registers in {@link #reflectInfo} every
         * method or field that could be the subject of the call.
         *
         * <p>Unlike {@code Class.getDeclaredMethod}, which takes a
         * {@code Class[]} of parameter types and allows the specific
         * overload to be identified exactly when that array is
         * statically resolvable, the {@code findXxx} family takes a
         * {@code MethodType} — an object whose contents are opaque at
         * the bytecode level. The handler therefore matches by name:
         * every declared method (or field) of the target class whose
         * name equals the string-literal argument is registered. The
         * JDK's own reflective search ({@code getDeclaredMethods0}
         * followed by {@code searchMethods}) then selects the correct
         * overload at run time from the actual {@code MethodType}, so
         * the over-approximation is safe: it can only make the
         * reflection table more complete, never less.</p>
         *
         * <p>Argument positions (JDK 21):</p>
         * <ul>
         *   <li>{@code findStatic}, {@code findVirtual}:
         *       {@code [0]=Class, [1]=String, [2]=MethodType}</li>
         *   <li>{@code findSpecial}:
         *       {@code [0]=Class, [1]=String, [2]=MethodType, [3]=Class}</li>
         *   <li>{@code findConstructor}: {@code [0]=Class, [1]=MethodType}</li>
         *   <li>{@code findGetter}, {@code findSetter}, {@code findStaticGetter},
         *       {@code findStaticSetter}, {@code findVarHandle},
         *       {@code findStaticVarHandle}:
         *       {@code [0]=Class, [1]=String, [2]=Class}</li>
         * </ul>
         *
         * <p>If the target class or the name cannot be resolved as a
         * constant (for example, the class is held in a local variable,
         * or the name is computed at run time), the handler silently
         * returns. This is a deliberate compromise: the JDK uses
         * constant names at the overwhelming majority of call sites,
         * and handling the dynamic case correctly would require a data
         * flow trace across method boundaries, which is outside the
         * scope of a single visitor.</p>
         */
        private void handleLookupFindCall(String mName, List<TypedValue> args) {
            if (args.isEmpty()) return;

            String targetClass = resolveClassNameFromValue(args.getFirst());
            if (targetClass == null || targetClass.isEmpty()) return;

            // Arrays have no methods of their own in this model;
            // everything resolvable against an array is inherited from
            // java/lang/Object and is already covered by the ordinary
            // Object handling. Skip without loss of correctness.
            if (targetClass.charAt(0) == '[') return;

            ClassNode cn = resolver.getClassNode(targetClass);
            if (cn == null || cn.isExternal() || resolver.getClassBytes(targetClass) == null) {
                resolver.forceLoadSystemClass(targetClass);
                cn = resolver.getClassNode(targetClass);
            }
            if (cn == null || cn.isExternal()) return;

            if (mName.equals("findStatic") || mName.equals("findVirtual")
                || mName.equals("findSpecial")) {
                if (args.size() < 2) return;
                TypedValue nameArg = args.get(1);
                if (!nameArg.isConstant() || !(nameArg.getValue() instanceof String methodName)) return;
                switch (methodName) {
                    case "" -> {
                        return;
                    }
                    // <clinit> is never requested reflectively; <init> is
                    // requested only through findConstructor.
                    case "<clinit>" -> {
                        return;
                    }
                    case "<init>" -> {
                        return;
                    }
                }

                registerAllMethodsByName(targetClass, cn, methodName);
                return;
            }

            if (mName.equals("findConstructor")) {
                registerAllMethodsByName(targetClass, cn, "<init>");
                return;
            }

            // Field getter/setter/VarHandle: [0]=Class, [1]=String.
            if (args.size() < 2) return;
            TypedValue nameArg = args.get(1);
            if (!nameArg.isConstant() || !(nameArg.getValue() instanceof String fieldName)) return;
            if (fieldName.isEmpty()) return;

            registerAllFieldsByName(targetClass, cn, fieldName);
        }

        /**
         * Registers in {@link #reflectInfo} every declared method named
         * {@code methodName} and makes them reachable.
         *
         * <p>Matching by name alone is required for the
         * {@code MethodHandles.Lookup.findXxx} family and for the
         * varargs form of {@code Class.getDeclaredMethod} /
         * {@code Class.getMethod}: in both cases the overload selector
         * is an object whose contents are not visible at the bytecode
         * level. The former passes a {@code MethodType}; the latter
         * passes a compiler-generated {@code Class[]}. Registering
         * every overload with a matching name guarantees that the
         * reflective lookup performed at run time finds whatever
         * overload the caller actually requests; the JDK rejects the
         * call itself if no such overload exists, so the
         * over-approximation costs nothing in correctness.</p>
         *
         * <p>For ordinary (non-{@code <init>}) methods the walk proceeds
         * up the superclass chain and stops at the first class that
         * declares at least one method of that name. This mirrors the
         * semantics of the JDK's own {@code getMethod} /
         * {@code findVirtual}: the search starts with the class itself
         * and then ascends. Including superclass methods when the
         * target class already holds a match would only inflate the
         * reflection table without benefit.</p>
         *
         * <p>For {@code <init>} no such walk is performed: constructors
         * are not inherited, and
         * {@code findConstructor(X.class, ...)} resolves only against
         * {@code <init>} of {@code X} itself. Constructors are recorded
         * through {@link ReflectInfo#addConstructor}, not
         * {@link ReflectInfo#addMethod}: they live in a separate bucket
         * of {@link ReflectClassInfo} that {@code generateReflectionData}
         * consumes to emit {@code @refctor_*} constants. Routing them
         * into the {@code methods} bucket would make
         * {@code emitAdaptorForMethod} try to build an adaptor symbol
         * from the raw method name {@code "<init>"} — the angle brackets
         * are not legal LLVM symbol characters and clang rejects the
         * whole module with "expected '(' in function argument list".
         * Independently, the JDK's own {@code getDeclaredMethods0}
         * would start returning constructors from
         * {@code Class.getDeclaredMethods()}, which is a separate
         * semantic error.</p>
         *
         * <p>{@code addMethodWithContext} is called in both branches:
         * it is what adds the target to the reachability worklist so
         * that {@code BytecodeToIr} translates it into an IR
         * {@code Function}. Registering the reference in
         * {@code ReflectInfo} alone is not sufficient — without the
         * reachability edge, {@code generateReflectionData} would find
         * the entry in the reflection table but no corresponding
         * function body to point the adaptor at, and would emit
         * {@code i8* null} for that slot.</p>
         */
        private void registerAllMethodsByName(String targetClass, ClassNode cn, String methodName) {
            if ("<init>".equals(methodName)) {
                for (MethodNode mn : cn.getMethods()) {
                    if (!mn.getName().equals("<init>")) continue;
                    MethodReference ref = new MethodReference(
                        targetClass, mn.getName(), mn.getDescriptor());
                    // Constructor bucket: consumed by
                    // emitAdaptorForConstructor, whose symbol-name
                    // construction goes through mangleMethod and
                    // therefore sanitises <init> correctly.
                    reflectInfo.addConstructor(targetClass, ref);
                    // Reachability edge: forces BytecodeToIr to emit
                    // the constructor's body into the module.
                    addMethodWithContext(ref, true);
                }
                return;
            }

            Set<String> visited = new HashSet<>();
            ClassNode current = cn;
            while (current != null
                && !current.isExternal()
                && visited.add(current.getName())) {

                boolean found = false;
                for (MethodNode mn : current.getMethods()) {
                    if (!mn.getName().equals(methodName)) continue;
                    if (mn.getName().equals("<clinit>")) continue;
                    if (mn.getName().equals("<init>")) continue;

                    MethodReference ref = new MethodReference(
                        current.getName(), mn.getName(), mn.getDescriptor());
                    reflectInfo.addMethod(current.getName(), ref);
                    addMethodWithContext(ref, true);
                    found = true;
                }

                // Stop at the first class in the chain that declares at
                // least one overload with this name. There is no reason
                // to continue: the JDK's own getMethod / findVirtual
                // does the same — it returns the first match it finds,
                // not the union of all ancestors' overloads.
                if (found) break;

                String superName = current.getSuperName();
                if (superName == null || superName.equals(current.getName())) break;

                ClassNode superNode = resolver.getClassNode(superName);
                if (superNode == null || superNode.isExternal()) break;
                current = superNode;
            }
        }

        /**
         * Registers in {@link #reflectInfo} every declared field named
         * {@code fieldName} and makes the target class reachable.
         *
         * <p>Same reasoning as {@link #registerAllMethodsByName}: the
         * {@code Class} argument that names the field's type cannot be
         * used reliably to select a specific field, so every field with
         * a matching name is registered. The run-time selection in
         * {@code Unsafe.objectFieldOffset(Class, String)} then finds
         * whatever field the caller actually requests.</p>
         *
         * <p>No superclass-chain walk is performed: both
         * {@code findGetter}/{@code findSetter} and
         * {@code findVarHandle} search for the field starting from the
         * class itself and stop at the first name match. A field is
         * either declared in the class itself or in one of its
         * ancestors, and if it is in an ancestor it is already
         * registered under the ancestor's name (the JDK's
         * {@code getDeclaredField} for the ancestor returns it), while
         * {@code getField} on the target class will itself ascend the
         * chain and find the ancestor's entry.</p>
         *
         * <p>{@code addClass} is called without initialization:
         * {@code findXxx} does not require the target class to be
         * initialized, only loaded. Forcing its {@code <clinit>} would
         * be a mistake — it could trigger recursive initialization when
         * the call originates inside the class's own {@code <clinit>}
         * (as happens with {@code ConcurrentHashMap.<clinit>} and its
         * VarHandle fields).</p>
         */
        private void registerAllFieldsByName(String targetClass, ClassNode cn, String fieldName) {
            for (FieldNode f : cn.getFields()) {
                if (!f.getName().equals(fieldName)) continue;
                FieldReference ref = new FieldReference(
                    targetClass, fieldName, f.getDescriptor());
                reflectInfo.addField(targetClass, ref);
            }
            addClass(targetClass);
            // Guarantees that a ReflectClassInfo exists even when no
            // matching field was found. That makes it possible for a
            // later addition (for example from applyMetadata) to land in
            // an existing class entry.
            reflectInfo.getOrCreateClassInfo(targetClass);
        }

        private void registerUnsafeObjectFieldOffset(List<TypedValue> args) {
            if (args.size() < 2) return;

            String targetClass = resolveClassNameFromValue(args.getFirst());
            if (targetClass == null && lastLoadedClass != null) {
                // The class literal LDC that immediately precedes this call
                // set lastLoadedClass. Fall back to it when the arg itself
                // could not be resolved, which happens when the class was
                // ASTORE'd into a local and ALOAD'd back with a widened type.
                targetClass = lastLoadedClass;
            }
            if (targetClass == null) return;

            String fieldName = null;
            TypedValue nameArg = args.get(1);
            if (nameArg.isConstant() && nameArg.getValue() instanceof String s) {
                fieldName = s;
            }
            if (fieldName == null) return;

            ClassNode cn = resolver.getClassNode(targetClass);
            if (cn == null || cn.isExternal() || resolver.getClassBytes(targetClass) == null) {
                resolver.forceLoadSystemClass(targetClass);
                cn = resolver.getClassNode(targetClass);
            }
            if (cn == null || cn.isExternal()) return;

            FieldNode fn = resolver.getField(targetClass, fieldName);
            if (fn == null) return;

            FieldReference ref = new FieldReference(targetClass, fieldName, fn.getDescriptor());
            reflectInfo.addField(targetClass, ref);
            reflectInfo.getOrCreateClassInfo(targetClass);
            addClass(targetClass);
        }

        /**
         * Extracts an internal (slash-separated) class name from a
         * {@link TypedValue} produced by an {@code LDC} of a class
         * literal. The simulator stores such literals as a constant whose
         * value is the class's internal name and whose declared type is a
         * reference to that same class.
         */
        private String resolveClassNameFromValue(TypedValue tv) {
            if (tv == null) return null;
            if (tv.isConstant() && tv.getValue() instanceof String s && !s.isEmpty()) {
                return s.replace('.', '/');
            }
            if (tv.isExact()) {
                return tv.getClassName();
            }
            return null;
        }

        private boolean typeMatches(String expected, Type actual) {
            Type expectedType =
                fromDescriptor(
                    expected.startsWith("[") ? expected : "L" + expected + ";"
                );
            return expectedType.equals(actual);
        }

        private int countArguments(String desc) {
            int count = 0;
            int i = 1;
            while (i < desc.length()) {
                char c = desc.charAt(i);
                if (c == ')') break;
                if (c == 'L') {
                    i = desc.indexOf(';', i) + 1;
                } else if (c == '[') {
                    while (i < desc.length() && desc.charAt(i) == '[') i++;
                    if (i < desc.length() && desc.charAt(i) == 'L') {
                        i = desc.indexOf(';', i) + 1;
                    } else {
                        i++;
                    }
                } else {
                    i++;
                }
                count++;
            }
            return count;
        }

        private boolean isConcreteClass(String className) {
            ClassNode cn = resolver.getClassNode(className);
            if (cn == null || cn.isExternal()) return false;
            if (cn.isInterface()) return false;
            return (cn.getAccess() & Opcodes.ACC_ABSTRACT) == 0;
        }

        private boolean isAssignableTo(String sub, String sup) {
            if (sub == null || sup == null) return false;
            if (sub.equals(sup)) return true;
            Set<String> visited = new HashSet<>();
            Deque<String> work = new ArrayDeque<>();
            work.add(sub);
            while (!work.isEmpty()) {
                String cur = work.pop();
                if (!visited.add(cur)) continue;
                if (cur.equals(sup)) return true;
                ClassNode cn = resolver.getClassNode(cur);
                if (cn == null) continue;
                if (cn.getSuperName() != null) work.add(cn.getSuperName());
                work.addAll(cn.getInterfaces());
            }
            return false;
        }
    }

    /**
     * Returns the internal name of the innermost reference component of
     * an array descriptor, or {@code null} if the component is primitive
     * or the descriptor is malformed.
     *
     * <p>"[[Ljava/lang/String;" yields "java/lang/String";
     * "[[I" yields {@code null};
     * "[Ljava/lang/Object;" yields "java/lang/Object".</p>
     */
    private static String elementClassOfArrayDescriptor(String desc) {
        if (desc == null || desc.length() < 2 || desc.charAt(0) != '[') return null;
        int i = 0;
        while (i < desc.length() && desc.charAt(i) == '[') i++;
        if (i >= desc.length()) return null;
        if (desc.charAt(i) == 'L' && desc.charAt(desc.length() - 1) == ';') {
            return desc.substring(i + 1, desc.length() - 1);
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Bytecode-level type simulation (unchanged)
    // ------------------------------------------------------------------

    private static class TypeSimulator {
        private final Deque<TypedValue> stack = new ArrayDeque<>();
        private final Map<Integer, TypedValue> locals = new HashMap<>();

        void push(TypedValue tv) { stack.push(tv); }
        TypedValue pop() { return stack.isEmpty() ? TypedValue.UNKNOWN : stack.pop(); }
        TypedValue peek() { return stack.isEmpty() ? TypedValue.UNKNOWN : stack.peek(); }
        void storeLocal(int idx, TypedValue tv) { locals.put(idx, tv); }
        TypedValue loadLocal(int idx) { return locals.getOrDefault(idx, TypedValue.UNKNOWN); }

        void visitInsn(int opcode) {
            switch (opcode) {
                case Opcodes.ACONST_NULL: push(TypedValue.NULL); break;
                case Opcodes.ICONST_M1:
                case Opcodes.ICONST_0:
                case Opcodes.ICONST_1:
                case Opcodes.ICONST_2:
                case Opcodes.ICONST_3:
                case Opcodes.ICONST_4:
                case Opcodes.ICONST_5:
                    push(TypedValue.INT); break;
                case Opcodes.LCONST_0:
                case Opcodes.LCONST_1:
                    push(TypedValue.LONG); break;
                case Opcodes.FCONST_0:
                case Opcodes.FCONST_1:
                case Opcodes.FCONST_2:
                    push(TypedValue.FLOAT); break;
                case Opcodes.DCONST_0:
                case Opcodes.DCONST_1:
                    push(TypedValue.DOUBLE); break;
                case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL:
                case Opcodes.IDIV: case Opcodes.IREM: case Opcodes.INEG:
                case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
                case Opcodes.IAND: case Opcodes.IOR: case Opcodes.IXOR:
                    pop(); pop(); push(TypedValue.INT); break;
                case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL:
                case Opcodes.LDIV: case Opcodes.LREM: case Opcodes.LNEG:
                case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
                case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR:
                    pop(); pop(); push(TypedValue.LONG); break;
                case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL:
                case Opcodes.FDIV: case Opcodes.FREM: case Opcodes.FNEG:
                    pop(); pop(); push(TypedValue.FLOAT); break;
                case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL:
                case Opcodes.DDIV: case Opcodes.DREM: case Opcodes.DNEG:
                    pop(); pop(); push(TypedValue.DOUBLE); break;
                case Opcodes.LCMP: case Opcodes.FCMPL: case Opcodes.FCMPG:
                case Opcodes.DCMPL: case Opcodes.DCMPG:
                    pop(); pop(); push(TypedValue.INT); break;
                case Opcodes.POP: pop(); break;
                case Opcodes.POP2: pop(); pop(); break;
                case Opcodes.DUP: push(peek()); break;
                case Opcodes.DUP_X1: {
                    TypedValue v1 = pop();
                    TypedValue v2 = pop();
                    push(v1); push(v2); push(v1);
                    break;
                }
                case Opcodes.DUP_X2: {
                    TypedValue v1 = pop();
                    TypedValue v2 = pop();
                    TypedValue v3 = pop();
                    push(v1); push(v3); push(v2); push(v1);
                    break;
                }
                case Opcodes.DUP2: {
                    TypedValue v1 = pop();
                    TypedValue v2 = pop();
                    push(v2); push(v1); push(v2); push(v1);
                    break;
                }
                case Opcodes.SWAP: {
                    TypedValue v1 = pop();
                    TypedValue v2 = pop();
                    push(v1); push(v2);
                    break;
                }
                case Opcodes.I2L: pop(); push(TypedValue.LONG); break;
                case Opcodes.I2F: pop(); push(TypedValue.FLOAT); break;
                case Opcodes.I2D: pop(); push(TypedValue.DOUBLE); break;
                case Opcodes.L2I: pop(); push(TypedValue.INT); break;
                case Opcodes.L2F: pop(); push(TypedValue.FLOAT); break;
                case Opcodes.L2D: pop(); push(TypedValue.DOUBLE); break;
                case Opcodes.F2I: pop(); push(TypedValue.INT); break;
                case Opcodes.F2L: pop(); push(TypedValue.LONG); break;
                case Opcodes.F2D: pop(); push(TypedValue.DOUBLE); break;
                case Opcodes.D2I: pop(); push(TypedValue.INT); break;
                case Opcodes.D2L: pop(); push(TypedValue.LONG); break;
                case Opcodes.D2F: pop(); push(TypedValue.FLOAT); break;
                case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
                    pop(); push(TypedValue.INT); break;
                case Opcodes.IRETURN: case Opcodes.LRETURN: case Opcodes.FRETURN:
                case Opcodes.DRETURN: case Opcodes.ARETURN: case Opcodes.RETURN:
                    stack.clear(); break;
                case Opcodes.ARRAYLENGTH:
                    pop(); push(TypedValue.INT); break;
                case Opcodes.AALOAD: {
                    pop(); // index
                    TypedValue arrayTv = pop();
                    Type elemType =
                        arrayTv.getType().isArray() ? arrayTv.getType().getElementType() : UNKNOWN;
                    push(TypedValue.fromType(elemType));
                    break;
                }
                case Opcodes.AASTORE: pop(); pop(); pop(); break;
                case Opcodes.ATHROW: stack.clear(); break;
                case Opcodes.MONITORENTER:
                case Opcodes.MONITOREXIT:
                    pop(); break;
                default: break;
            }
        }

        void visitIntInsn(int opcode, int operand) {
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                push(TypedValue.INT);
            } else if (opcode == Opcodes.NEWARRAY) {
                pop();
                Type elemType = primitiveArrayType(operand);
                push(TypedValue.fromType(array(elemType)));
            }
        }

        void visitVarInsn(int opcode, int var) {
            switch (opcode) {
                case Opcodes.ILOAD: push(loadLocal(var)); break;
                case Opcodes.LLOAD: push(TypedValue.LONG); break;
                case Opcodes.FLOAD: push(TypedValue.FLOAT); break;
                case Opcodes.DLOAD: push(TypedValue.DOUBLE); break;
                case Opcodes.ALOAD: push(loadLocal(var)); break;
                case Opcodes.ISTORE: storeLocal(var, pop()); break;
                case Opcodes.LSTORE: storeLocal(var, TypedValue.LONG); pop(); break;
                case Opcodes.FSTORE: storeLocal(var, TypedValue.FLOAT); pop(); break;
                case Opcodes.DSTORE: storeLocal(var, TypedValue.DOUBLE); pop(); break;
                case Opcodes.ASTORE: storeLocal(var, pop()); break;
                case Opcodes.RET: break;
                default: break;
            }
        }

        void visitTypeInsn(int opcode, String type) {
            switch (opcode) {
                case Opcodes.NEW:
                    push(TypedValue.fromReference(type));
                    break;
                case Opcodes.ANEWARRAY:
                    pop();
                    push(TypedValue.fromType(array(reference(type))));
                    break;
                case Opcodes.CHECKCAST:
                    pop();
                    push(TypedValue.fromReference(type));
                    break;
                case Opcodes.INSTANCEOF:
                    pop();
                    push(TypedValue.INT);
                    break;
                default: break;
            }
        }

        void visitFieldInsn(int opcode, String descriptor) {
            Type fieldType = fromDescriptor(descriptor);
            switch (opcode) {
                case Opcodes.GETFIELD:
                    pop();
                    push(TypedValue.fromType(fieldType));
                    break;
                case Opcodes.PUTFIELD:
                    pop(); pop();
                    break;
                case Opcodes.GETSTATIC:
                    push(TypedValue.fromType(fieldType));
                    break;
                case Opcodes.PUTSTATIC:
                    pop();
                    break;
                default: break;
            }
        }

        void visitMethodInsn(int opcode, String desc) {
            int argCount = countArguments(desc);
            Type retType = fromDescriptor(desc.substring(desc.lastIndexOf(')') + 1));

            for (int i = 0; i < argCount; i++) {
                pop();
            }
            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
                || opcode == Opcodes.INVOKESPECIAL) {
                pop();
            }
            if (!retType.isVoid()) {
                push(TypedValue.fromType(retType));
            }
        }

        void visitLdcInsn(Object value) {
            switch (value) {
                case Integer ignored -> push(TypedValue.fromConstant(INT, value));
                case Long ignored -> push(TypedValue.fromConstant(LONG, value));
                case Float ignored -> push(TypedValue.fromConstant(FLOAT, value));
                case Double ignored -> push(TypedValue.fromConstant(DOUBLE, value));
                case String ignored -> push(TypedValue.fromConstant(reference("java/lang/String"), value));
                case org.objectweb.asm.Type asmType -> {
                    if (asmType.getSort() == org.objectweb.asm.Type.OBJECT) {
                        push(TypedValue.fromConstant(reference(asmType.getInternalName()), asmType.getInternalName()));
                    } else {
                        push(TypedValue.fromConstant(fromDescriptor(asmType.getDescriptor()), asmType.getDescriptor()));
                    }
                }
                case null, default -> push(TypedValue.UNKNOWN);
            }
        }

        void visitJumpInsn(int opcode) {
            switch (opcode) {
                case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT:
                case Opcodes.IFGE: case Opcodes.IFGT: case Opcodes.IFLE:
                case Opcodes.IFNULL: case Opcodes.IFNONNULL:
                    pop(); break;
                case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE:
                case Opcodes.IF_ICMPLT: case Opcodes.IF_ICMPGE:
                case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE:
                case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
                    pop(); pop(); break;
                case Opcodes.GOTO: break;
                case Opcodes.JSR:
                    push(TypedValue.BLOCK); break;
                default: break;
            }
        }

        private int countArguments(String desc) {
            int count = 0;
            int i = 1;
            while (i < desc.length()) {
                char c = desc.charAt(i);
                if (c == ')') break;
                if (c == 'L') {
                    i = desc.indexOf(';', i) + 1;
                } else if (c == '[') {
                    while (i < desc.length() && desc.charAt(i) == '[') i++;
                    if (i < desc.length() && desc.charAt(i) == 'L') {
                        i = desc.indexOf(';', i) + 1;
                    } else {
                        i++;
                    }
                } else {
                    i++;
                }
                count++;
            }
            return count;
        }

        private Type primitiveArrayType(int atype) {
            return switch (atype) {
                case Opcodes.T_BOOLEAN -> BOOLEAN;
                case Opcodes.T_BYTE    -> BYTE;
                case Opcodes.T_CHAR    -> CHAR;
                case Opcodes.T_SHORT   -> SHORT;
                case Opcodes.T_INT     -> INT;
                case Opcodes.T_LONG    -> LONG;
                case Opcodes.T_FLOAT   -> FLOAT;
                case Opcodes.T_DOUBLE  -> DOUBLE;
                default -> UNKNOWN;
            };
        }

        String getReceiverType(int opcode, String desc) {
            if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) {
                return null;
            }
            int argCount = countArguments(desc);
            if (stack.size() < argCount + 1) return null;
            int idx = 0;
            TypedValue receiver = null;
            for (TypedValue tv : stack) {
                if (idx == argCount) {
                    receiver = tv;
                    break;
                }
                idx++;
            }
            if (receiver == null) return null;
            if (receiver.isExact()) {
                return receiver.getClassName();
            }
            if (receiver.getType().isReference()) {
                String cls = receiver.getType().getClassName();
                if (cls != null && !cls.equals("java/lang/Object")) {
                    return cls;
                }
            }
            return null;
        }
    }
}