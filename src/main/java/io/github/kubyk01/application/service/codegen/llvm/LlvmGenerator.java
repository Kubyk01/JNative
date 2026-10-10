package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.application.service.codegen.llvm.nativepolymorphicfunctionresolver.PolymorphicResolver;
import io.github.kubyk01.domain.analyzer.ClinitScheduleEntry;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.IrBuilder;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ResolvedCall;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.Temporary;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.kubyk01.util.LlvmUtil.getElementSizeOfType;

@Slf4j
public class LlvmGenerator {

    private final Module module;
    private final DependencyResolver resolver;
    private final ReflectInfo reflectInfo;
    private final String entryClass;
    private final String entryMethod;
    private final String entryDescriptor;

    private final LlvmGlobalEmitter globalEmitter;
    private final LlvmFunctionEmitter functionEmitter;

    /**
     * The polymorphic-resolver instance handed to the constructor.
     *
     * <p>It is stored as a {@code final} field so that the
     * {@link ThreadLocal} factory inside {@link #emitFunctions(List)}
     * can capture it. Keeping it {@code final} makes the compiler
     * reject any future revision that forgets to initialise it.</p>
     */
    private final PolymorphicResolver polymorphicResolverRef;

    private List<ClinitScheduleEntry> clinitSchedule = new ArrayList<>();
    private Map<String, String> clinitWrappers = new HashMap<>();

    /**
     * Maximum number of CPU cores the generator may use for the parallel
     * function-emission phase.
     *
     * <p>The value is set by {@link
     * io.github.kubyk01.application.service.Orchestrator} through
     * {@link #setCores(int)}; it is never derived here. The default of 1
     * keeps the class usable in tests and in any code path that emits a
     * trivial module without an explicit core budget.</p>
     */
    private int cores = 1;

    /**
     * Resources to bake into the image, in the order the caller collected
     * them. Each entry is a path and its bytes; the path is the internal
     * form {@code Class.getResourceAsStream} resolves to (leading {@code '/'}
     * stripped, {@code '/'}-separated).
     *
     * <p>Supplied by the {@link
     * io.github.kubyk01.application.service.Orchestrator} via
     * {@link #setEmbeddedResources(List)} and forwarded to
     * {@link LlvmGlobalEmitter#addEmbeddedResource(String, byte[])}.
     */
    private List<Map.Entry<String, byte[]>> embeddedResources = new ArrayList<>();

    public void setClinitSchedule(List<ClinitScheduleEntry> schedule) {
        this.clinitSchedule = (schedule != null) ? schedule : new ArrayList<>();
    }

    public void setEmbeddedResources(List<Map.Entry<String, byte[]>> resources) {
        this.embeddedResources = (resources != null) ? resources : new ArrayList<>();
    }

    public void setClinitWrappers(Map<String, String> wrappers) {
        this.clinitWrappers = wrappers != null ? wrappers : new HashMap<>();
    }

    /**
     * Sets the maximum number of CPU cores the generator may use.
     *
     * <p>A value {@code <= 0} collapses to {@code 1}: the caller is
     * expected to have resolved the effective core count before invoking
     * this method, and the absence of a positive value means "run
     * sequentially". The class deliberately does not call
     * {@link Runtime#availableProcessors()} here — that decision belongs
     * to {@link io.github.kubyk01.application.service.Orchestrator}.</p>
     */
    public void setCores(int cores) {
        this.cores = (cores <= 0) ? 1 : cores;
    }

    public LlvmGenerator(Module module, DependencyResolver resolver,
                         AliasAnalysisResult aliasResult,
                         String entryClass, String entryMethod, String entryDescriptor,
                         ReflectInfo reflectInfo,
                         PolymorphicResolver polymorphicResolver) {
        this.module = module;
        this.resolver = resolver;
        this.entryClass = entryClass;
        this.entryMethod = entryMethod;
        this.entryDescriptor = entryDescriptor;
        this.reflectInfo = reflectInfo;
        this.globalEmitter = new LlvmGlobalEmitter(module, resolver, aliasResult, reflectInfo);
        this.functionEmitter = new LlvmFunctionEmitter(
            module, globalEmitter, polymorphicResolver, resolver);
        this.polymorphicResolverRef = polymorphicResolver;
    }

    /**
     * Returns the symbol that {@code main} should call to trigger the given
     * {@code <clinit>}: the lazy wrapper when one has been created for it,
     * the body itself otherwise.
     */
    private String eagerTarget(String realClinitName) {
        String wrapper = clinitWrappers.get(realClinitName);
        return wrapper != null ? wrapper : realClinitName;
    }

    public String generate() {
        ensureExternalDeclarations();

        StringBuilder sb = new StringBuilder();

        sb.append("target datalayout = \"e-m:e-p270:32:32-p271:64:64-i64:64-f80:128-n8:16:32:64-S128\"\n");
        sb.append("target triple = \"x86_64-pc-linux-gnu\"\n\n");

        sb.append(LlvmRuntime.getVtableTypeDefinition());
        sb.append("\n");

        sb.append(LlvmRuntime.getDeclarations());

        generateLambdaAdaptors();

        globalEmitter.emitExtraStructs(sb);

        sb.append(globalEmitter.emitLambdaVtables());
        sb.append("\n");

        sb.append(globalEmitter.generateGlobals());

        // Freeze every lazily-built cache in LlvmGlobalEmitter before the
        // parallel phase. After this call, classLayouts / interfaceLayouts /
        // interfaceIds are complete and no longer written to.
        globalEmitter.prepareLayouts();

        List<Function> functionsCopy = new ArrayList<>(module.getFunctions());
        List<Function> toEmit = new ArrayList<>(functionsCopy.size());
        for (Function func : functionsCopy) {
            if (func.getEntryBlock() != null) {
                toEmit.add(func);
            }
        }

        for (String body : emitFunctions(toEmit)) {
            sb.append(body);
        }

        for (Function func : new ArrayList<>(module.getFunctions())) {
            if (func.getEntryBlock() == null) {
                sb.append(emitDeclaration(func));
            }
        }

        sb.append(globalEmitter.generateDeferredStringConstants());

        sb.append(generateMain());

        return sb.toString();
    }

    /**
     * Emits the LLVM IR body of every function in {@code functions}.
     *
     * <p>When {@link #cores} is 1 (or there is only one function to emit)
     * the loop runs on the calling thread and reuses the single
     * {@link LlvmFunctionEmitter} instance created in the constructor.
     * Otherwise a fixed-size thread pool is used, with one emitter per
     * worker thread cached in a {@link ThreadLocal}.</p>
     *
     * <p>The returned list preserves the input order — the emitted bodies
     * are collected via {@link Future#get()} in submission order — so the
     * output is byte-for-byte identical to the sequential version.</p>
     */
    private List<String> emitFunctions(List<Function> functions) {
        int n = functions.size();
        List<String> results = new ArrayList<>(n);
        if (n == 0) {
            return results;
        }

        int threadCount = Math.max(1, Math.min(cores, n));

        if (threadCount == 1) {
            for (Function func : functions) {
                results.add(functionEmitter.emitFunction(func));
            }
            return results;
        }

        System.out.println("Emitting " + n + " function(s) using "
            + threadCount + " thread(s)...");

        final PolymorphicResolver polyResolver = this.polymorphicResolverRef;
        final Module moduleRef = this.module;
        final LlvmGlobalEmitter globalEmitterRef = this.globalEmitter;
        final DependencyResolver resolverRef = this.resolver;

        ThreadLocal<LlvmFunctionEmitter> emitterTl = ThreadLocal.withInitial(
            () -> new LlvmFunctionEmitter(
                moduleRef, globalEmitterRef, polyResolver, resolverRef));

        // todo remake this to reactor
        ExecutorService pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "jnative-llvm-emit");
            t.setDaemon(true);
            return t;
        });

        try {
            List<Future<String>> futures = new ArrayList<>(n);
            for (Function func : functions) {
                futures.add(pool.submit(() -> emitterTl.get().emitFunction(func)));
            }

            for (int i = 0; i < n; i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(
                        "Interrupted while emitting LLVM IR for function "
                            + functions.get(i).getName(), ie);
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    throw new RuntimeException(
                        "Failed to emit LLVM IR for function "
                            + functions.get(i).getName(),
                        cause != null ? cause : ee);
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
            emitterTl.remove();
        }

        return results;
    }

    private void ensureExternalDeclarations() {
        resolver.forceLoadSystemClass("java/lang/Object");
        resolver.forceLoadSystemClass("java/lang/Class");
        // Class.getModule() reads the `module` slot of every @refclass_*
        // constant, and the slot points at the unnamed-module singleton that
        // generateUnnamedModule() emits. Without this class the emitter has
        // no struct or vtable to build that object from.
        resolver.forceLoadSystemClass("java/lang/Module");
        // The native Class.getResourceAsStream override returns a
        // ByteArrayInputStream over the embedded bytes, and constructs it
        // from C via jnative_class_by_name().
        resolver.forceLoadSystemClass("java/io/InputStream");
        resolver.forceLoadSystemClass("java/io/ByteArrayInputStream");
        resolver.forceLoadSystemClass("java/util/Dictionary");
        resolver.forceLoadSystemClass("java/util/Hashtable");
        resolver.forceLoadSystemClass("java/util/Properties");
        resolver.forceLoadSystemClass("java/util/concurrent/ConcurrentHashMap");
        resolver.forceLoadSystemClass("java/util/concurrent/ConcurrentHashMap$Node");

        // Reflection mirrors created by Class.getDeclared{Fields,Methods,
        // Constructors}0. Their Class<?> objects must exist in
        // reflect_all_classes[] so that jnative_class_by_name() can find
        // them when the native side constructs a mirror.
        //
        // AccessibleObject and Executable are not optional padding: they
        // declare the instance fields that come first in the layout of
        // Field, Method and Constructor (see the layout table in
        // jnative_runtime.h). collectInstanceFields() walks the
        // superclass chain but silently SKIPS a superclass whose ClassNode
        // is missing or external, so leaving either of these unloaded
        // shifts every offset of the three concrete classes and turns the
        // mirror writes in Class.c into writes into a neighbouring field.
        resolver.forceLoadSystemClass("java/lang/reflect/Field");
        resolver.forceLoadSystemClass("java/lang/reflect/Method");
        resolver.forceLoadSystemClass("java/lang/reflect/Constructor");
        resolver.forceLoadSystemClass("java/lang/reflect/AccessibleObject");
        resolver.forceLoadSystemClass("java/lang/reflect/Executable");

        // Thread bootstrap layout setup -- the C runtime needs exact field
        // offsets to synthesise the main thread, its holder and its thread
        // group with the same object layout the LLVM backend uses. Loading
        // the three classes here guarantees they are visible to
        // getFieldOffset() at codegen time.
        resolver.forceLoadSystemClass("java/lang/Thread");
        resolver.forceLoadSystemClass("java/lang/Thread$FieldHolder");
        resolver.forceLoadSystemClass("java/lang/ThreadGroup");

        ensureExternalFunction("__jnative_thread_set_layout", Type.VOID,
            Type.INT, Type.INT, Type.INT, Type.INT, Type.INT,  // Thread: size, holder, tid, name, interruptLock
            Type.INT, Type.INT, Type.INT, Type.INT, Type.INT,  // FieldHolder: size, group, priority, daemon, status
            Type.INT, Type.INT, Type.INT, Type.INT);           // ThreadGroup: size, name, maxPriority, vmAllow

        // Reflection-mirror field-offset handoff.
        //
        // Class.c's create_field_mirror / create_method_mirror /
        // create_constructor_mirror write into java.lang.reflect.{Field,
        // Method, Constructor} instance slots at hard-coded offsets
        // (JNATIVE_FIELD_*_OFFSET / JNATIVE_METHOD_*_OFFSET /
        // JNATIVE_CTOR_*_OFFSET in jnative_runtime.h). Those offsets
        // must agree byte-for-byte with what LlvmGlobalEmitter's
        // collectInstanceFields() produced for the same three classes,
        // because the Java-side reflection code reads the same slots
        // through the LLVM-computed offsets. They only agree by accident
        // if the JDK reshuffles a field between releases.
        //
        // The concrete failure this handoff closes: on JDK builds where
        // `parameterTypes` is declared on Executable rather than on
        // Method, the hard-coded JNATIVE_METHOD_PARAM_TYPES_OFFSET (72)
        // is off by one slot. Class.c then wrote the (correct) parameter
        // array into an unrelated field, and every java.lang.reflect.Method
        // constructed by getDeclaredMethods0 carried a null
        // parameterTypes. The first consumer to iterate that array without
        // a null check — Executable.sharedToString, reached from
        // Method.toString inside MethodHandleImpl$CountingWrapper.<clinit>
        // — aborted with
        //
        //     java.lang.NullPointerException: Cannot invoke
        //     java.lang.reflect.Executable.sharedToString(...) because
        //     <array> is null
        //
        // Passing the real offsets in from @main makes the C side and the
        // Java side agree regardless of how the JDK orders the fields.
        // The signature mirrors the JNATIVE_*_OFFSET constants one for
        // one; see jnative_runtime.h for the C-side declaration.
        ensureExternalFunction("__jnative_reflect_set_layout", Type.VOID,
            Type.INT, Type.INT, Type.INT, Type.INT, Type.INT,   // Field: clazz, slot, name, type, modifiers
            Type.INT, Type.INT, Type.INT, Type.INT, Type.INT, Type.INT, Type.INT,   // Method: clazz, slot, name, returnType, params, exc, modifiers
            Type.INT, Type.INT, Type.INT, Type.INT, Type.INT);  // Constructor: clazz, slot, params, exc, modifiers

        ensureExternalFunction(
            "__jnative_fn_jdk_internal_util_SystemProps_Raw_cmdProperties___Ljava_util_HashMap_",
            Type.reference("java/util/HashMap"));

        // Route Class.getResourceAsStream to the C override. Every
        // java.lang.Class mirror shares @vtable_java_lang_Class, so this one
        // registration covers the call the JDK makes on ICUBinary.class as
        // well as any other. Declared before the override is registered so
        // resolveVtableEntry() finds the Function object.
        ensureExternalFunction(
            "__jnative_override_Class_getResourceAsStream",
            Type.reference("java/io/InputStream"),
            Type.reference("java/lang/Class"),
            Type.reference("java/lang/String"));
        globalEmitter.overrideMethod(
            "java/lang/Class",
            "getResourceAsStream",
            "(Ljava/lang/String;)Ljava/io/InputStream;",
            "__jnative_override_Class_getResourceAsStream");

        for (Map.Entry<String, byte[]> e : embeddedResources) {
            globalEmitter.addEmbeddedResource(e.getKey(), e.getValue());
        }

        ensureReflectClassRegistered("java/util/HashMap");
        ensureReflectClassRegistered("java/util/Properties");
        ensureReflectClassRegistered("java/util/concurrent/ConcurrentHashMap");
        ensureReflectClassRegistered("java/io/ByteArrayInputStream");
        // The reflect.Field/Method/Constructor mirrors carry the
        // declaring class, so the @refclass_* constant for the mirror class
        // itself has to be emitted; ensureReflectClassRegistered also
        // recurses into the superclass chain (Executable, AccessibleObject).
        ensureReflectClassRegistered("java/lang/reflect/Field");
        ensureReflectClassRegistered("java/lang/reflect/Method");
        ensureReflectClassRegistered("java/lang/reflect/Constructor");
    }

    private void ensureExternalFunction(String name, Type returnType, Type... paramTypes) {
        if (module.getFunction(name) != null) return;
        Function f = new Function(name, returnType);
        for (int i = 0; i < paramTypes.length; i++) {
            f.addParameter(new Parameter(paramTypes[i], i));
        }
        module.addFunction(f);
    }

    private void ensureReflectClassRegistered(String className) {
        if (reflectInfo == null) return;
        if (className == null || className.isEmpty()) return;

        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isExternal() || resolver.getClassBytes(className) == null) {
            resolver.forceLoadSystemClass(className);
            cn = resolver.getClassNode(className);
        }
        if (cn == null) return;

        ReflectClassInfo info = reflectInfo.getOrCreateClassInfo(className);
        if (info.getSuperName() == null && cn.getSuperName() != null) {
            info.setSuperName(cn.getSuperName());
        }
        info.getInterfaces().addAll(cn.getInterfaces());

        for (FieldNode field : cn.getFields()) {
            if ((field.getAccess() & Opcodes.ACC_STATIC) == 0) {
                FieldReference ref = new FieldReference(
                    className, field.getName(), field.getDescriptor());
                info.addField(ref);
            }
        }

        for (MethodNode mn : cn.getMethods()) {
            if (mn.getName().equals("<init>")) {
                MethodReference ref = new MethodReference(
                    className, mn.getName(), mn.getDescriptor());
                info.addConstructor(ref);
            }
        }

        String superName = cn.getSuperName();
        if (superName != null && !superName.isEmpty()) {
            ensureReflectClassRegistered(superName);
        }
    }

    private String emitDeclaration(Function func) {
        StringBuilder sb = new StringBuilder();
        sb.append("declare ").append(LlvmTypeMapper.toLlvmType(func.getReturnType()))
            .append(" @").append(func.getName()).append("(");
        List<Parameter> params = func.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(LlvmTypeMapper.toLlvmType(params.get(i).getType()));
        }
        sb.append(")\n");
        return sb.toString();
    }

    private int safeFieldOffset(String owner, String field, int fallback) {
        try {
            return globalEmitter.getFieldOffset(owner, field);
        } catch (Exception e) {
            log.warn("Could not compute field offset for {}.{}: {}; falling back to {}",
                owner, field, e.getMessage(), fallback);
            return fallback;
        }
    }

    private String generateMain() {
        StringBuilder sb = new StringBuilder();
        sb.append("define i32 @main(i32 %argc, i8** %argv) {\n");
        sb.append("  call i32 @atexit(void ()* @")
            .append(LlvmRuntime.mangleFunction("__jnative_shutdown"))
            .append(")\n");

        // ------------------------------------------------------------------
        // Stage 0: layout handoffs.
        //
        // Two C subsystems need the exact byte offsets that the LLVM
        // backend used for a handful of JDK classes, because they write
        // into instances of those classes from C and the Java side reads
        // the same slots through the LLVM-computed offsets. A single
        // value that disagrees between the two sides is not a compile
        // error and not a link error — it is a silent write into a
        // neighbouring slot, discovered only when the Java code that
        // reads the intended slot crashes on a null or a garbage value
        // far from the point of the mismatch.
        //
        // Both handoffs take their arguments from the same functions
        // (LlvmGlobalEmitter.getFieldOffset / computeObjectSize) that the
        // emitter used to lay out the struct types, so the C side and the
        // emitted code can never drift apart, no matter how the JDK
        // reorders or renames the fields between releases.
        // ------------------------------------------------------------------

        // ----- 0a. Thread / FieldHolder / ThreadGroup layout. -----
        //
        // The C runtime synthesises the "main" java.lang.Thread lazily,
        // on the first Thread.currentThread() call. That call happens
        // before any Java-level Thread constructor has run — inside
        // jdk.internal.misc.CarrierThread.<clinit> — so the object has
        // to be constructible from C, carrying a valid Thread$FieldHolder
        // whose group is a valid ThreadGroup.
        int threadSize   = globalEmitter.computeObjectSize("java/lang/Thread");
        int threadHolder = globalEmitter.getFieldOffset("java/lang/Thread", "holder");
        int threadTid    = globalEmitter.getFieldOffset("java/lang/Thread", "tid");
        int threadName   = globalEmitter.getFieldOffset("java/lang/Thread", "name");
        /*
         * interruptLock is the object that Thread.blockedOn() synchronises
         * on when the current thread enters a blocking I/O operation. The
         * C-synthesised main thread does not run Thread.<init>, so the
         * field would be NULL without an explicit write. The first
         * caller that hits this is
         *
         *     BasicImageReader.<init>
         *         -> FileChannelImpl.readInternal
         *             -> AbstractInterruptibleChannel.begin
         *                 -> Thread.blockedOn
         *                     -> synchronized (me.interruptLock)  // NPE
         *
         * and the NPE is immediately masked by an ArrayIndexOutOfBounds
         * from the enclosing finally block (see jnative/lang/Thread.c for
         * the full trace). Passing the offset through the layout handoff
         * keeps the C side and the emitter in lockstep on any JDK whose
         * field layout differs from the current one; the field has been
         * present and named "interruptLock" across JDK 17-22, but the safe
         * lookup below treats a rename or removal as "field absent" and
         * falls back to -1, which the C side interprets as "skip this
         * write".
         */
        int threadInterruptLock = safeFieldOffset(
            "java/lang/Thread", "interruptLock", -1);

        int fhSize       = globalEmitter.computeObjectSize("java/lang/Thread$FieldHolder");
        int fhGroup      = globalEmitter.getFieldOffset("java/lang/Thread$FieldHolder", "group");
        int fhPriority   = globalEmitter.getFieldOffset("java/lang/Thread$FieldHolder", "priority");
        int fhDaemon     = globalEmitter.getFieldOffset("java/lang/Thread$FieldHolder", "daemon");
        int fhStatus     = globalEmitter.getFieldOffset("java/lang/Thread$FieldHolder", "threadStatus");

        int tgSize       = globalEmitter.computeObjectSize("java/lang/ThreadGroup");
        int tgName       = globalEmitter.getFieldOffset("java/lang/ThreadGroup", "name");
        int tgMaxPrio    = globalEmitter.getFieldOffset("java/lang/ThreadGroup", "maxPriority");
        // ThreadGroup.vmAllowSuspension exists up to JDK 17 and was removed
        // later on (JDK-8283117). The C side treats a negative offset as
        // "this field does not exist on this JDK" and skips the write, so a
        // missing field must not abort codegen the way getFieldOffset() does.
        int tgVmAllow    = safeFieldOffset("java/lang/ThreadGroup", "vmAllowSuspension", -1);

        if (threadSize > 0 && fhSize > 0 && tgSize > 0) {
            sb.append("  call void @__jnative_thread_set_layout(")
                .append("i32 ").append(threadSize).append(", ")
                .append("i32 ").append(threadHolder).append(", ")
                .append("i32 ").append(threadTid).append(", ")
                .append("i32 ").append(threadName).append(", ")
                .append("i32 ").append(threadInterruptLock).append(", ")
                .append("i32 ").append(fhSize).append(", ")
                .append("i32 ").append(fhGroup).append(", ")
                .append("i32 ").append(fhPriority).append(", ")
                .append("i32 ").append(fhDaemon).append(", ")
                .append("i32 ").append(fhStatus).append(", ")
                .append("i32 ").append(tgSize).append(", ")
                .append("i32 ").append(tgName).append(", ")
                .append("i32 ").append(tgMaxPrio).append(", ")
                .append("i32 ").append(tgVmAllow).append(")\n");
        } else {
            log.error("Thread layout setup skipped: Thread={}, FieldHolder={}, ThreadGroup={}",
                threadSize, fhSize, tgSize);
        }

        // ----- 0b. java.lang.reflect.{Field,Method,Constructor} layout. -----
        //
        // Class.c's create_field_mirror / create_method_mirror /
        // create_constructor_mirror populate the mirror objects that
        // Class.getDeclaredFields0 / getDeclaredMethods0 /
        // getDeclaredConstructors0 hand back to the JDK. Each write goes
        // to a hard-coded slot name in C (JNATIVE_*_OFFSET, declared in
        // jnative_runtime.h) that must equal the LLVM-computed offset for
        // the same Java-level field. Any drift — a JDK that moves
        // `parameterTypes` from Method to Executable, for example —
        // silently redirects the write into an unrelated slot.
        //
        // The concrete failure this handoff closes: the hard-coded
        // JNATIVE_METHOD_PARAM_TYPES_OFFSET put the parameter array into
        // the wrong slot on every JDK where Executable declares
        // `parameterTypes`. Every mirror built afterwards carried a null
        // `parameterTypes`, and the first reflective toString inside
        // MethodHandleImpl$CountingWrapper.<clinit> aborted the process
        // with
        //
        //     java.lang.NullPointerException: Cannot invoke
        //     java.lang.reflect.Executable.sharedToString(...) because
        //     <array> is null
        //
        // The values below are the same numbers that were used to build
        // the emitted %struct.java_lang_reflect_* types, so passing them
        // to the C side is what makes the write offsets agree with the
        // read offsets on every JDK.
        int fieldClazz      = safeFieldOffset("java/lang/reflect/Field", "clazz",      24);
        int fieldSlot       = safeFieldOffset("java/lang/reflect/Field", "slot",       32);
        int fieldName       = safeFieldOffset("java/lang/reflect/Field", "name",       40);
        int fieldType       = safeFieldOffset("java/lang/reflect/Field", "type",       48);
        int fieldModifiers  = safeFieldOffset("java/lang/reflect/Field", "modifiers",  56);

        int methodClazz     = safeFieldOffset("java/lang/reflect/Method", "clazz",          40);
        int methodSlot      = safeFieldOffset("java/lang/reflect/Method", "slot",           48);
        int methodName      = safeFieldOffset("java/lang/reflect/Method", "name",           56);
        int methodReturn    = safeFieldOffset("java/lang/reflect/Method", "returnType",     64);
        int methodParams    = safeFieldOffset("java/lang/reflect/Method", "parameterTypes", 72);
        int methodExc       = safeFieldOffset("java/lang/reflect/Method", "exceptionTypes", 80);
        int methodMods      = safeFieldOffset("java/lang/reflect/Method", "modifiers",      88);

        int ctorClazz       = safeFieldOffset("java/lang/reflect/Constructor", "clazz",          40);
        int ctorSlot        = safeFieldOffset("java/lang/reflect/Constructor", "slot",           48);
        int ctorParams      = safeFieldOffset("java/lang/reflect/Constructor", "parameterTypes", 56);
        int ctorExc         = safeFieldOffset("java/lang/reflect/Constructor", "exceptionTypes", 64);
        int ctorMods        = safeFieldOffset("java/lang/reflect/Constructor", "modifiers",      72);

        sb.append("  call void @__jnative_reflect_set_layout(")
            .append("i32 ").append(fieldClazz).append(", ")
            .append("i32 ").append(fieldSlot).append(", ")
            .append("i32 ").append(fieldName).append(", ")
            .append("i32 ").append(fieldType).append(", ")
            .append("i32 ").append(fieldModifiers).append(", ")
            .append("i32 ").append(methodClazz).append(", ")
            .append("i32 ").append(methodSlot).append(", ")
            .append("i32 ").append(methodName).append(", ")
            .append("i32 ").append(methodReturn).append(", ")
            .append("i32 ").append(methodParams).append(", ")
            .append("i32 ").append(methodExc).append(", ")
            .append("i32 ").append(methodMods).append(", ")
            .append("i32 ").append(ctorClazz).append(", ")
            .append("i32 ").append(ctorSlot).append(", ")
            .append("i32 ").append(ctorParams).append(", ")
            .append("i32 ").append(ctorExc).append(", ")
            .append("i32 ").append(ctorMods).append(")\n");

        int poolSize = globalEmitter.getLiteralPoolSize();
        sb.append("  call void @__jnative_init_string_pool(i8** getelementptr inbounds ([")
            .append(poolSize).append(" x i8*], [").append(poolSize)
            .append(" x i8*]* @__jnative_literal_pool, i32 0, i32 0), i32 ")
            .append(poolSize).append(")\n");

        // ------------------------------------------------------------------
        // Stage 1: minimal bootstrap prerequisites.
        //
        // These <clinit> bodies and the setJavaLangAccess hook must run
        // before System.props exists (some of them allocate the very
        // structures the bootstrap Properties table is built on), and they
        // must run before initPhase1, which reads System.props.
        // ------------------------------------------------------------------
        String stringClinitName = "fn_java_lang_String__clinit____V";
        Function stringClinit = module.getFunction(stringClinitName);
        if (stringClinit != null && stringClinit.getEntryBlock() != null) {
            emitDebugClinitCall(sb, stringClinitName);
            sb.append("  call void @").append(eagerTarget(stringClinitName)).append("()\n");
        } else {
            log.warn("java.lang.String.<clinit> is missing from the module; "
                + "COMPACT_STRINGS will default to false and every String.length() "
                + "will be halved. Check that ReachabilityAnalysis triggers "
                + "triggerClinit(\"java/lang/String\", ...) and that the class has "
                + "bytecode available.");
        }

        String systemClinitName = "fn_java_lang_System__clinit____V";
        String propsClinitName  = "fn_java_util_Properties__clinit____V";
        String unsafeClinitName = "fn_jdk_internal_misc_Unsafe__clinit____V";
        String unsafeConstantsClinitName =
            "fn_jdk_internal_misc_UnsafeConstants__clinit____V";
        String chmClinitName    = "fn_java_util_concurrent_ConcurrentHashMap__clinit____V";
        String arraysClinitName = "fn_jdk_internal_util_ArraysSupport__clinit____V";
        String setJlaName       = "fn_java_lang_System_setJavaLangAccess___V";
        String characterDataLatin1ClinitName = "fn_java_lang_CharacterDataLatin1__clinit____V";
        String accessibleObjectClinitName = "fn_java_lang_reflect_AccessibleObject__clinit____V";
        /*
         * <clinit> function of java.lang.StackTraceElement$HashedModules.
         *
         * Its static initializer unconditionally calls
         *
         *     ModuleLayer.boot().configuration().findModule("java.base")
         *
         * and ModuleLayer.boot() reads System.bootLayer. The System.bootLayer
         * field is assigned EXCLUSIVELY inside System.initPhase2()
         * (through ModuleBootstrap.boot()) — see System.java:2217-2242.
         *
         * Therefore any invocation of this <clinit> BEFORE Stage 5 runs
         * initPhase2 is guaranteed to NPE. That is exactly what happened:
         * Stage 4 (the eager-<clinit> schedule) invoked it through the
         * lazy-wrapper (fn___lazy_clinit_run_java_lang_StackTraceElement_HashedModules),
         * bypassing the isHashedInJavaBase / VM.isModuleSystemInited()
         * guard inside StackTraceElement, and crashed on the first
         * .configuration() call.
         *
         * Fix: the class is excluded from Stage 4 and initialised
         * explicitly in the new Stage 5b — right after initPhase2, once
         * bootLayer has been assigned, but before the user entry point
         * gains control.
         */
        String hashedModulesClinitName =
            "fn_java_lang_StackTraceElement_HashedModules__clinit____V";

        Function systemClinit = module.getFunction(systemClinitName);
        if (systemClinit != null && systemClinit.getEntryBlock() != null) {
            emitDebugClinitCall(sb, systemClinitName);
            sb.append("  call void @").append(eagerTarget(systemClinitName)).append("()\n");
        }

        Function setJlaFn = module.getFunction(setJlaName);
        if (setJlaFn != null && setJlaFn.getEntryBlock() != null) {
            sb.append("  call void @").append(setJlaName).append("()\n");
        }

        // ------------------------------------------------------------------
        // Bootstrap prerequisite <clinit>s.
        //
        // Everything in this array runs *before* VM.saveProperties(props).
        // Anything VM.saveProperties reaches transitively — directly or
        // through its own callees — must be initialised here, otherwise the
        // static-field read it performs will see the JVM default (null, 0,
        // false) instead of the value its own <clinit> would have installed.
        //
        // UnsafeConstants is listed *before* Unsafe and must stay that
        // way. Unsafe.<clinit> reads ADDRESS_SIZE0, PAGE_SIZE,
        // BIG_ENDIAN, UNALIGNED_ACCESS and DATA_CACHE_LINE_FLUSH_SIZE
        // out of UnsafeConstants and folds them into Unsafe.ADDRESS_SIZE,
        // Unsafe.PAGE_SIZE, Unsafe.unalignedAccess() and the rest of
        // the derived static state. If UnsafeConstants.<clinit> has not
        // run by then, Unsafe caches the placeholder zeros forever and
        // every Unsafe.allocateMemory() call returns NULL. See
        // UnsafeConstants.c for the full failure chain.
        //
        // CharacterDataLatin1 is the newest addition to the non-Unsafe
        // part of this list. VM.saveProperties() parses the
        // "java.class.version" entry we install in
        // __jnative_make_bootstrap_props() via Integer.parseInt("65"),
        // and Integer.parseInt calls Character.digit(int, int), which
        // calls CharacterData.of(int). The ASCII fast path in
        // CharacterData.of is
        //
        //     if (ch >>> 8 == 0) return CharacterDataLatin1.instance;
        //
        // so it reads a static field of CharacterDataLatin1. If that
        // class's <clinit> has not run yet, `instance` is still null
        // and the subsequent virtual call NPEs on the null receiver.
        // The same Character.digit path is reached by Long.parseLong("-1")
        // for "sun.nio.MaxDirectMemorySize", so one entry covers both
        // parses.
        // ------------------------------------------------------------------
        String[] bootstrapPrereqClinits = {
            accessibleObjectClinitName,
            characterDataLatin1ClinitName,
            unsafeConstantsClinitName,   // MUST precede unsafeClinitName
            unsafeClinitName,
            chmClinitName,
            propsClinitName,
            arraysClinitName,
        };

        // ------------------------------------------------------------------
        // Fail loudly if UnsafeConstants was somehow left out of the
        // module. This can only happen if the reachability analysis
        // stopped triggering clinit on UnsafeConstants when
        // Unsafe.<clinit> reads its fields — which would itself be a
        // bug — but the failure mode it prevents is a silent
        // Unsafe.allocateMemory() == NULL, which is far harder to
        // diagnose at run time than a build-time warning.
        // ------------------------------------------------------------------
        {
            Function ucc = module.getFunction(unsafeConstantsClinitName);
            if (ucc == null || ucc.getEntryBlock() == null) {
                log.warn("jdk.internal.misc.UnsafeConstants.<clinit> is "
                    + "missing from the module; Unsafe.ADDRESS_SIZE will "
                    + "be read as 0 and every Unsafe.allocateMemory() call "
                    + "will return NULL. Check that ReachabilityAnalysis "
                    + "triggers clinit on UnsafeConstants when Unsafe.<clinit> "
                    + "reads its fields.");
            }
        }

        for (String prereq : bootstrapPrereqClinits) {
            Function f = module.getFunction(prereq);
            if (f == null || f.getEntryBlock() == null) continue;
            emitDebugClinitCall(sb, prereq);
            sb.append("  call void @").append(eagerTarget(prereq)).append("()\n");
        }

        int propsTableOff      = safeFieldOffset("java/util/Properties", "table",       8);
        int propsCountOff      = safeFieldOffset("java/util/Properties", "count",      16);
        int propsThresholdOff  = safeFieldOffset("java/util/Properties", "threshold",  20);
        int propsLoadFactorOff = safeFieldOffset("java/util/Properties", "loadFactor", 24);
        int propsModCountOff   = safeFieldOffset("java/util/Properties", "modCount",   28);
        int propsDefaultsOff   = safeFieldOffset("java/util/Properties", "defaults",   32);
        int propsMapOff        = safeFieldOffset("java/util/Properties", "map",        40);
        int chmTableOff        = safeFieldOffset("java/util/concurrent/ConcurrentHashMap", "table",     24);
        int chmBaseCountOff    = safeFieldOffset("java/util/concurrent/ConcurrentHashMap", "baseCount", 40);
        int chmSizeCtlOff      = safeFieldOffset("java/util/concurrent/ConcurrentHashMap", "sizeCtl",   48);

        String offsetArgs =
            "i32 " + propsTableOff      + ", " +
                "i32 " + propsCountOff      + ", " +
                "i32 " + propsThresholdOff  + ", " +
                "i32 " + propsLoadFactorOff + ", " +
                "i32 " + propsModCountOff   + ", " +
                "i32 " + propsDefaultsOff   + ", " +
                "i32 " + propsMapOff        + ", " +
                "i32 " + chmTableOff        + ", " +
                "i32 " + chmBaseCountOff    + ", " +
                "i32 " + chmSizeCtlOff;

        // ------------------------------------------------------------------
        // Stage 2: install the bootstrap Properties table on java.lang.System.
        //
        // initPhase1 reads System.props (it iterates the saved property
        // names through saveAndRemoveProperties), so the bootstrap table
        // must exist before initPhase1 runs.
        // ------------------------------------------------------------------
        sb.append("  %bootstrap_props = call i8* @__jnative_make_bootstrap_props(")
            .append(offsetArgs).append(")\n");
        sb.append("  store i8* %bootstrap_props, i8** @gv_java_lang_System_props\n");

        // ------------------------------------------------------------------
        // Stage 3: prime jdk.internal.misc.VM.savedProps.
        //
        // System.initPhase1() is the only writer of
        // @gv_jdk_internal_misc_VM_savedProps in a stock JDK, and it is
        // emitted in stage 5 below, i.e. AFTER the eager <clinit> loop.
        // VM.getSavedProperty(String) throws
        // IllegalStateException("Not yet initialized") while savedProps is
        // still null, and several <clinit> bodies read it transitively:
        //
        //   jdk.internal.loader.ClassLoaders.<clinit>
        //       -> VM.getSavedProperty("jdk.boot.class.path.append")
        //   java.util.concurrent.ThreadLocalRandom.<clinit>
        //       -> VM.getSavedProperty("java.util.secureRandomSeed")
        //   jdk.internal.icu.text.NormalizerBase$NFCModeImpl.<clinit>
        //       -> ... -> Class.getResourceAsStream
        //              -> BootLoader.findResourceAsStream
        //              -> lazy_clinit_run(ClassLoaders)
        //
        // Calling VM.saveProperties(Map) here with the very same bootstrap
        // table that initPhase1 would install removes the ordering
        // dependency without moving the phases: saveProperties() keeps
        // savedProps if it is already set, so the later initPhase1 call
        // becomes a no-op instead of a conflicting second write.
        //
        // The call instruction MUST carry the `i8* ` type prefix on its
        // argument. The LLVM call syntax requires a type token on every
        // argument, including SSA values whose type is already known from
        // their definition; omitting it produces
        //     call void @fn_...(%bootstrap_props)
        // which the LLVM parser rejects with "invalid type for function
        // argument" because it expects a type, not an SSA name, at that
        // position. The same rule applies to every other call site in
        // this method.
        // ------------------------------------------------------------------
        Function saveProps = module.getFunction(
            "fn_jdk_internal_misc_VM_saveProperties__Ljava_util_Map__V");
        if (saveProps != null && saveProps.getEntryBlock() != null) {
            sb.append("  call void @fn_jdk_internal_misc_VM_saveProperties__Ljava_util_Map__V(")
                .append("i8* %bootstrap_props)\n");
        } else {
            log.warn("jdk.internal.misc.VM.saveProperties is missing from the module; "
                + "VM.savedProps stays null during the eager <clinit> schedule and any "
                + "<clinit> calling VM.getSavedProperty will throw "
                + "IllegalStateException(\"Not yet initialized\").");
        }

        // ------------------------------------------------------------------
        // Stage 4: unified initialization schedule.
        //
        // clinitSchedule is the result of Analyzer.sortInitializers(), in
        // which the <clinit> functions and the bootstrap phases
        // System.initPhase1/2/3 are interleaved by a topological sort over
        // the dependency graph. Every phase stands exactly where its
        // dependencies require it: initPhase2, for example, stands before
        // SecurityManager.<clinit>, ModuleLayer.<clinit> and any other
        // <clinit> that (transitively) reads System.bootLayer, because the
        // field-provenance analysis built the corresponding edge.
        //
        // Bootstrap prerequisites (String.<clinit>, System.<clinit>,
        // UnsafeConstants.<clinit>, Unsafe.<clinit>, Properties.<clinit>,
        // ConcurrentHashMap.<clinit>, ArraysSupport.<clinit>,
        // CharacterDataLatin1.<clinit>, AccessibleObject.<clinit>) have
        // already run above and must be skipped.
        //
        // HashedModules.<clinit> (StackTraceElement$HashedModules) is
        // skipped here and executed as a separate Stage 5b: its <clinit>
        // reads System.bootLayer and must run after initPhase3, not right
        // after initPhase2. Field provenance yields only the nearest
        // dependency; the explicit exclusion preserves the "phases fully
        // done" semantics.
        // ------------------------------------------------------------------

        Set<String> bootstrapPrereqNames = Set.of(
            stringClinitName,
            systemClinitName,
            unsafeClinitName,
            unsafeConstantsClinitName,
            chmClinitName,
            propsClinitName,
            arraysClinitName,
            characterDataLatin1ClinitName,
            accessibleObjectClinitName
        );

        int bootstrapPhaseIdx = 0;
        for (ClinitScheduleEntry entry : clinitSchedule) {
            Function f = entry.function();
            String name = f.getName();

            if (bootstrapPrereqNames.contains(name)) continue;
            if (name.equals(hashedModulesClinitName)) continue;

            emitDebugClinitCall(sb, name);

            if (entry.bootstrapPhase()) {
                // Bootstrap phase: call it with zero arguments and discard
                // the result. The arguments are taken from the phase's real
                // signature, so initPhase2(ZZ)I and initPhase1()V are
                // handled correctly and without hard-coding.
                Type retType = f.getReturnType();
                String retLlvm = LlvmTypeMapper.toLlvmType(retType);

                StringBuilder args = new StringBuilder();
                List<Parameter> params = f.getParameters();
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) args.append(", ");
                    Type pt = params.get(i).getType();
                    args.append(LlvmTypeMapper.toLlvmType(pt))
                        .append(" ").append(zeroLiteralForType(pt));
                }

                if (retType.isVoid()) {
                    sb.append("  call void @").append(name)
                      .append("(").append(args).append(")\n");
                } else {
                    sb.append("  %bootstrap_phase_result_").append(bootstrapPhaseIdx++)
                      .append(" = call ").append(retLlvm)
                      .append(" @").append(name)
                      .append("(").append(args).append(")\n");
                }
            } else {
                // Ordinary <clinit>; eagerTarget substitutes the lazy wrapper
                // when the class was marked cyclic.
                sb.append("  call void @").append(eagerTarget(name)).append("()\n");
            }
        }

        // ------------------------------------------------------------------
        // Stage 5b: post-bootstrap <clinit> schedule.
        //
        // Classes whose static initializer depends on state that only
        // exists once the module system is fully up. The only member at
        // present is java.lang.StackTraceElement$HashedModules:
        //
        //     static Set<String> HASHED_MODULES = hashedModules();
        //     static Set<String> hashedModules() {
        //         Optional<ResolvedModule> rm =
        //             ModuleLayer.boot()            // <-- System.bootLayer
        //                 .configuration()
        //                 .findModule("java.base");
        //         ...
        //     }
        //
        // System.bootLayer is assigned EXCLUSIVELY inside initPhase2()
        // (through ModuleBootstrap.boot()), so this <clinit> must run
        // after Stage 5. It used to fall into the Stage 4 eager loop and
        // crashed with NPE on ModuleLayer.boot() == null — see the
        // detailed comment on hashedModulesClinitName above.
        //
        // The position within Stage 5b is deliberately AFTER initPhase3:
        // initPhase3 (SystemImpl.initPhase3) installs the final hooks and
        // activates the security managers; by the time it returns, the
        // module layer is guaranteed to be fully functional, and any
        // future class with the same dependency can be added to this same
        // list without risking a race with the bootstrap phases.
        //
        // The call goes through eagerTarget(), not the raw name: the
        // class is marked lazy (it has the wrapper
        // fn___lazy_clinit_run_java_lang_StackTraceElement_HashedModules),
        // and the wrapper owns the __jnative_clinit_enter /
        // __jnative_clinit_exit state machine. Calling the body directly
        // would bypass the state machine and leave the clinit_table in
        // the "not started" state, which would cause a re-initialisation
        // on a later lazy access from Java code.
        // ------------------------------------------------------------------
        {
            Function hashedModulesClinit = module.getFunction(hashedModulesClinitName);
            if (hashedModulesClinit != null && hashedModulesClinit.getEntryBlock() != null) {
                emitDebugClinitCall(sb, hashedModulesClinitName);
                sb.append("  call void @")
                  .append(eagerTarget(hashedModulesClinitName))
                  .append("()\n");
            } else {
                log.warn("java.lang.StackTraceElement$HashedModules.<clinit> "
                    + "is missing from the module; the class will be initialised "
                    + "lazily on the first real access through StackTraceElement. "
                    + "If that access happens before System.initPhase2 has run, "
                    + "the runtime will still NPE on ModuleLayer.boot().");
            }
        }

        // ------------------------------------------------------------------
        // Stage 6: refresh System.props with the fully initialised values.
        //
        // The pre-initPhase1 bootstrap table carries placeholder values for
        // entries that are only meaningful after the VM bootstrap has
        // finished (e.g. java.home resolution in some configurations). The
        // refresh below replaces that placeholder table with the final one
        // before the user entry point runs. Kept at this position: it is
        // after the general <clinit> schedule, so any <clinit> that read
        // the bootstrap table has already seen a consistent snapshot.
        // ------------------------------------------------------------------
        sb.append("  %final_props = call i8* @__jnative_make_bootstrap_props(")
            .append(offsetArgs).append(")\n");
        sb.append("  store i8* %final_props, i8** @gv_java_lang_System_props\n");

        // ------------------------------------------------------------------
        // Stage 7: user entry point.
        // ------------------------------------------------------------------
        String mainFunc = LlvmRuntime.mangleMethod(entryClass, entryMethod, entryDescriptor);
        sb.append("  %args_array = call i8* @__jnative_create_string_array(i32 %argc, i8** %argv)\n");
        sb.append("  call void @").append(mainFunc).append("(i8* %args_array)\n");
        sb.append("  ret i32 0\n");
        sb.append("}\n\n");

        int ifaceId  = globalEmitter.getInterfaceId("java/lang/Runnable");
        int runSlot  = globalEmitter.getInterfaceMethodSlot("java/lang/Runnable", "run", "()V");
        sb.append("@__jnative_runnable_iface_id = constant i32 ").append(ifaceId).append("\n");
        sb.append("@__jnative_run_method_slot   = constant i32 ").append(runSlot).append("\n");

        int toStringSlot = globalEmitter.getVirtualSlot(
            "java/lang/Object", "toString", "()Ljava/lang/String;");
        sb.append("@__jnative_tostring_slot    = constant i32 ")
            .append(toStringSlot).append("\n\n");

        int privActionIfaceId = globalEmitter.getInterfaceId("java/security/PrivilegedAction");
        int privActionRunSlot = globalEmitter.getInterfaceMethodSlot(
            "java/security/PrivilegedAction", "run", "()Ljava/lang/Object;");
        sb.append("@__jnative_privilegedaction_iface_id = constant i32 ")
            .append(privActionIfaceId).append("\n");
        sb.append("@__jnative_privilegedaction_run_slot = constant i32 ")
            .append(privActionRunSlot).append("\n");

        int privExcActionIfaceId = globalEmitter.getInterfaceId("java/security/PrivilegedExceptionAction");
        int privExcActionRunSlot = globalEmitter.getInterfaceMethodSlot(
            "java/security/PrivilegedExceptionAction", "run", "()Ljava/lang/Object;");
        sb.append("@__jnative_privilegedexceptionaction_iface_id = constant i32 ")
            .append(privExcActionIfaceId).append("\n");
        sb.append("@__jnative_privilegedexceptionaction_run_slot = constant i32 ")
            .append(privExcActionRunSlot).append("\n\n");

        return sb.toString();
    }

    private void emitDebugClinitCall(StringBuilder sb, String clinitName) {
        int len = LlvmRuntime.typeStringArrayLength(clinitName);
        String g = LlvmRuntime.typeStringGlobalName(clinitName);
        sb.append("  call void @__jnative_debug_clinit(i8* getelementptr inbounds ([")
            .append(len).append(" x i8], [").append(len)
            .append(" x i8]* ").append(g).append(", i32 0, i32 0))\n");
    }

    /**
     * LLVM literal "zero" for an IR type. Bootstrap phases are invoked with
     * zero arguments; the returned result is discarded.
     */
    private String zeroLiteralForType(Type type) {
        if (type == null) return "null";
        if (type == Type.BOOLEAN) return "false";
        if (type == Type.FLOAT || type == Type.DOUBLE) return "0.0";
        if (type.isReference() || type.isArray() || type.isNull()
            || type.isBlock() || type.isUnknown()) {
            return "null";
        }
        return "0";
    }

    private void generateLambdaAdaptors() {
        List<Instruction> lambdaInsts = new ArrayList<>();
        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() == Opcode.INVOKEDYNAMIC) {
                        InvokeDynamicInfo info = (InvokeDynamicInfo) inst.getInvokedynamicData();
                        ResolvedCall call = info.resolvedCall();
                        if (call != null && call.getType() == ResolvedCall.Type.LAMBDA) {
                            lambdaInsts.add(inst);
                        }
                    }
                }
            }
        }
        for (Instruction inst : lambdaInsts) {
            InvokeDynamicInfo info = (InvokeDynamicInfo) inst.getInvokedynamicData();
            ResolvedCall call = info.resolvedCall();
            createLambdaAdaptor(call, info);
        }
    }

    private void createLambdaAdaptor(ResolvedCall call, InvokeDynamicInfo info) {
        String lambdaId = call.getLambdaStructName();
        String adaptorName = "adaptor_" + lambdaId;

        Object[] bsmArgs = info.bootstrapArgs();
        if (bsmArgs.length < 2) return;

        if (module.getFunction(adaptorName) != null) return;

        Handle implHandle = (Handle) bsmArgs[1];
        int handleTag = implHandle.getTag();

        boolean isStatic      = (handleTag == Opcodes.H_INVOKESTATIC);
        boolean isConstructor = (handleTag == Opcodes.H_NEWINVOKESPECIAL);

        Opcode callOpcode = switch (handleTag) {
            case Opcodes.H_INVOKESTATIC                          -> Opcode.STATIC_CALL;
            case Opcodes.H_INVOKEINTERFACE                       -> Opcode.INTERFACE_CALL;
            case Opcodes.H_INVOKEVIRTUAL                         -> Opcode.VIRTUAL_CALL;
            case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcode.SPECIAL_CALL;
            default                                              -> Opcode.STATIC_CALL;
        };

        String implOwner = implHandle.getOwner();
        String implName  = implHandle.getName();
        String implDesc  = implHandle.getDesc();

        if (!(bsmArgs[0] instanceof org.objectweb.asm.Type samType)) return;
        if (samType.getSort() != org.objectweb.asm.Type.METHOD) return;

        String samDescriptor = samType.getDescriptor();
        String samSig        = info.name() + samDescriptor;

        String dynDescriptor = info.descriptor();
        org.objectweb.asm.Type dynReturnType =
            org.objectweb.asm.Type.getReturnType(dynDescriptor);
        if (dynReturnType.getSort() != org.objectweb.asm.Type.OBJECT) return;
        String samInterface = dynReturnType.getInternalName();

        Type retType = TypeResolver.descToReturnType(samDescriptor);
        List<Type> paramTypes = TypeResolver.descToParamTypes(samDescriptor);

        IrBuilder builder = new IrBuilder(module);
        List<Type> allParamTypes = new ArrayList<>();
        allParamTypes.add(Type.reference("java/lang/Object"));
        allParamTypes.addAll(paramTypes);
        Function adaptorFunc =
            builder.createFunction(adaptorName, retType, allParamTypes);

        globalEmitter.registerLambdaClass(
            lambdaId, samInterface, samSig, adaptorName, call.getCapturedTypes());

        builder.createBlock(adaptorName + "_entry");
        BasicBlock entry = builder.currentBlock();
        adaptorFunc.setEntryBlock(entry);

        /* -------- load captured fields from the lambda object --------- */
        List<Type>  capturedTypes = call.getCapturedTypes();
        List<Value> loadedCaptures = new ArrayList<>(capturedTypes.size());
        int offset = 8;
        Parameter lambdaObj = adaptorFunc.getParameters().getFirst();

        for (Type capType : capturedTypes) {
            Instruction getField = new Instruction(Opcode.GET_FIELD);
            getField.addOperand(lambdaObj);
            getField.addOperand(new Constant(Type.INT, offset));
            Temporary tmp = builder.newTemporary(capType);
            getField.setResult(tmp);
            tmp.setDefiningInstruction(getField);
            entry.addInstruction(getField);
            loadedCaptures.add(tmp);
            offset += getElementSizeOfType(capType);
        }

        /* -------- SAM parameters (skip the lambda receiver at slot 0) - */
        List<Value> samArgs = new ArrayList<>();
        for (int i = 1; i < adaptorFunc.getParameters().size(); i++) {
            samArgs.add(adaptorFunc.getParameters().get(i));
        }

        /* =============================================================
         * Constructor reference: REF_newInvokeSpecial.
         *
         * The adaptor must allocate a fresh instance of implOwner and
         * invoke <init> on it. The SAM arguments become the constructor
         * arguments. This path cannot go through the generic dispatch
         * below because the "receiver" is not a capture and not a SAM
         * parameter — it is a freshly allocated object.
         * ============================================================= */
        if (isConstructor) {
            buildConstructorAdaptor(
                builder, entry, implOwner, implDesc, samArgs);
            return;
        }

        /* =============================================================
         * Ordinary method reference.
         *
         * Compute the exact arity the target implementation expects:
         * implArity declared parameters, plus the implicit receiver for
         * a non-static method.
         * ============================================================= */
        List<Type> implParamTypes = TypeResolver.descToParamTypes(implDesc);
        int implArity = implParamTypes.size();
        int totalArgs = implArity + (isStatic ? 0 : 1);

        /* Build the argument list according to JLS §15.13.3:            *
         *                                                              *
         *   REF_invokeStatic    : captures || SAM args                 *
         *   REF_invoke* with    : captured[0] || captured[1..]         *
         *     captures present    || SAM args                          *
         *   REF_invoke* without : SAM[0] || SAM[1..]                   *
         *     captures           (SAM[0] plays the receiver role)      */
        List<Value> callArgs = new ArrayList<>();

        if (isStatic) {
            callArgs.addAll(loadedCaptures);
            callArgs.addAll(samArgs);
        } else if (!loadedCaptures.isEmpty()) {
            // Bound instance reference: captures[0] is the receiver,
            // the remaining captures and all SAM args follow.
            callArgs.addAll(loadedCaptures);
            callArgs.addAll(samArgs);
        } else {
            // Unbound instance reference: the first SAM argument is the
            // receiver. This is the branch that used to emit a literal
            // `null` receiver and produced the StringJoiner NPE.
            if (samArgs.isEmpty()) {
                emitUnresolvedAdaptor(entry, adaptorName);
                return;
            }
            callArgs.addAll(samArgs);
        }

        /* Trim or pad to exactly match the target method's arity.      *
         * Trimming discards trailing SAM parameters the implementation *
         * cannot accept; padding inserts null for the inverse case.    */
        while (callArgs.size() > totalArgs) {
            callArgs.removeLast();
        }
        while (callArgs.size() < totalArgs) {
            callArgs.add(new Constant(Type.NULL, null));
        }

        String calleeName = implOwner + "." + implName + implDesc;

        if (callOpcode == Opcode.INTERFACE_CALL) {
            globalEmitter.ensureInterfaceRegistered(implOwner);
        } else if (callOpcode == Opcode.VIRTUAL_CALL) {
            globalEmitter.ensureClassLayoutBuilt(implOwner);
        }

        /* =============================================================
         * Emit the call instruction. Operand layout for the emitter:
         *
         *   STATIC_CALL         : [callee, arg0, arg1, ...]
         *   VIRTUAL/INTERFACE/  : [receiver, callee, arg0, arg1, ...]
         *     SPECIAL_CALL
         * ============================================================= */
        Instruction callInst = new Instruction(callOpcode);
        Constant calleeConst = new Constant(Type.reference(calleeName), calleeName);

        if (isStatic) {
            callInst.addOperand(calleeConst);
            for (Value arg : callArgs) {
                callInst.addOperand(arg);
            }
        } else {
            callInst.addOperand(callArgs.getFirst());
            callInst.addOperand(calleeConst);
            for (int i = 1; i < callArgs.size(); i++) {
                callInst.addOperand(callArgs.get(i));
            }
        }

        if (!retType.isVoid()) {
            Temporary result = builder.newTemporary(retType);
            callInst.setResult(result);
            result.setDefiningInstruction(callInst);
            entry.addInstruction(callInst);
            builder.createReturn(result);
        } else {
            entry.addInstruction(callInst);
            builder.createReturn(null);
        }
    }

    /**
     * Builds the body of a lambda adaptor for a constructor reference
     * (bootstrap-method handle tag {@code REF_newInvokeSpecial}).
     *
     * <p>A constructor reference such as {@code HashMap::new} binds the
     * constructor {@code <init>} of {@code HashMap} to the SAM. The
     * adaptor's job is to allocate a fresh instance of the class and
     * forward the SAM arguments to {@code <init>}. Unlike an ordinary
     * virtual or static reference, the receiver is neither a capture
     * nor a SAM argument: it is the freshly allocated object.</p>
     *
     * <p>Before this method existed, {@link #createLambdaAdaptor} emitted
     * {@code call <init>(null, ...)} for constructor references. The
     * generated {@code <init>} body begins by writing the object's vtable
     * pointer into {@code *(obj + 0)}, so the immediate consequence was a
     * write to the null address — a SIGSEGV in any constructor reference
     * reachable from the program.</p>
     */
    private void buildConstructorAdaptor(IrBuilder builder,
                                         BasicBlock entry,
                                         String implOwner,
                                         String implDesc,
                                         List<Value> samArgs) {
        List<Type> ctorParamTypes = TypeResolver.descToParamTypes(implDesc);
        int ctorArity = ctorParamTypes.size();

        List<Value> ctorArgs = new ArrayList<>(samArgs);
        while (ctorArgs.size() > ctorArity) {
            ctorArgs.removeLast();
        }
        while (ctorArgs.size() < ctorArity) {
            ctorArgs.add(new Constant(Type.NULL, null));
        }

        // Ensure the class layout (and its vtable) is built.
        globalEmitter.ensureClassLayoutBuilt(implOwner);

        // Allocate a fresh instance of the target class. Opcode.NEW's
        // operand is a Constant with the class's internal name; the
        // resulting Temporary has type Type.reference(implOwner).
        Instruction newInst = builder.addInstruction(
            Opcode.NEW,
            new Constant(Type.reference(implOwner), implOwner));
        Value obj = newInst.getResult();

        // Invoke <init> on the freshly allocated object. Operand layout
        // for SPECIAL_CALL is [receiver, callee, arg0, arg1, ...].
        String calleeName = implOwner + "." + "<init>" + implDesc;
        Instruction ctorCall = new Instruction(Opcode.SPECIAL_CALL);
        ctorCall.addOperand(obj);
        ctorCall.addOperand(new Constant(Type.reference(calleeName), calleeName));
        for (Value arg : ctorArgs) {
            ctorCall.addOperand(arg);
        }
        entry.addInstruction(ctorCall);

        // The adaptor's return value is the constructed object. The
        // emitter bitcasts between i8* reference types automatically
        // when the declared return type differs from the object type
        // (Object vs. HashMap, etc.).
        entry.setTerminator(new ReturnTerminator(obj));
    }

    /**
     * Emits a well-defined failure path for the degenerate case where an
     * unbound method reference has an empty SAM parameter list — a
     * shape Java's method-reference rules never actually produce, but
     * which a malformed {@code invokedynamic} could present.
     *
     * <p>The adaptor calls {@code __jnative_unresolved_slot} with a
     * diagnostic string and returns null. The call is declared
     * {@code noreturn} in {@code LlvmRuntime.getDeclarations()}, so the
     * return terminator is unreachable; it is emitted anyway so the IR
     * remains structurally valid for the LLVM verifier.</p>
     */
    private void emitUnresolvedAdaptor(BasicBlock entry, String adaptorName) {
        String message =
            "unresolvable lambda adaptor " + adaptorName
                + ": SAM declares no parameters but the implementation is "
                + "not static, so no receiver can be derived";

        Instruction unresolved = new Instruction(Opcode.STATIC_CALL);
        unresolved.addOperand(new Constant(
            Type.reference("__jnative_unresolved_slot"), "__jnative_unresolved_slot"));
        unresolved.addOperand(new Constant(
            Type.reference("java/lang/String"), message));
        entry.addInstruction(unresolved);
        entry.setTerminator(new ReturnTerminator(new Constant(Type.NULL, null)));
    }
}