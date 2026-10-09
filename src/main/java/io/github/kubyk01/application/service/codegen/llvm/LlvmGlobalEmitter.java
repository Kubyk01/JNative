package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.application.service.analyzer.dependencyresolver.DependencyResolver;
import io.github.kubyk01.application.service.analyzer.ssa.TypeResolver;
import io.github.kubyk01.domain.analyzer.aliasanalysis.AliasAnalysisResult;
import io.github.kubyk01.domain.analyzer.aliasanalysis.PointsToSet;
import io.github.kubyk01.domain.analyzer.dependencyresolver.ClassNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.FieldReference;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodNode;
import io.github.kubyk01.domain.analyzer.dependencyresolver.MethodReference;
import io.github.kubyk01.domain.analyzer.reflection.ReflectClassInfo;
import io.github.kubyk01.domain.analyzer.reflection.ReflectInfo;
import io.github.kubyk01.domain.ir.BasicBlock;
import io.github.kubyk01.domain.ir.CondBranchTerminator;
import io.github.kubyk01.domain.ir.Constant;
import io.github.kubyk01.domain.ir.Function;
import io.github.kubyk01.domain.ir.IndirectBranchTerminator;
import io.github.kubyk01.domain.ir.Instruction;
import io.github.kubyk01.domain.ir.InvokeDynamicInfo;
import io.github.kubyk01.domain.ir.LookupSwitchTerminator;
import io.github.kubyk01.domain.ir.Module;
import io.github.kubyk01.domain.ir.Opcode;
import io.github.kubyk01.domain.ir.Parameter;
import io.github.kubyk01.domain.ir.ReturnTerminator;
import io.github.kubyk01.domain.ir.TableSwitchTerminator;
import io.github.kubyk01.domain.ir.Terminator;
import io.github.kubyk01.domain.ir.ThrowTerminator;
import io.github.kubyk01.domain.ir.Type;
import io.github.kubyk01.domain.ir.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.Opcodes;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.kubyk01.util.LlvmUtil.getElementSizeOfType;

/**
 * Emits the global data section of a generated LLVM module: struct type
 * definitions, vtables, itables, type-info tables, string literals and the
 * reflection registry.
 *
 * <h2>Thread-safety model</h2>
 *
 * <p>{@code LlvmGlobalEmitter} is shared between the main thread (which
 * drives the pre-emission setup) and every worker thread that runs in
 * parallel during the function-emission phase. It is designed around a
 * simple rule: <b>writes happen before parallel emission, reads happen
 * during and after</b>.</p>
 *
 * <p>Concretely:</p>
 * <ul>
 *   <li>Every cache that is fully populated before the parallel phase is
 *       held in a {@link ConcurrentHashMap} (or a
 *       {@code ConcurrentHashMap.newKeySet()}) so that concurrent reads
 *       never observe a partially-constructed internal table.</li>
 *   <li>{@link #prepareLayouts()} is guarded by double-checked locking on
 *       a {@code volatile} flag; it is idempotent and safe to call from
 *       any number of threads.</li>
 *   <li>{@link #registerDeferredString(String)} is the only method that
 *       writes shared state during the parallel phase. It updates a
 *       {@code synchronized} set, and the reader
 *       ({@link #generateDeferredStringConstants()}) takes a snapshot
 *       under the same monitor before iterating.</li>
 *   <li>{@link #getFieldOffset(String, String)} may be called
 *       concurrently for the same (class, field) pair. The computation
 *       is deterministic — both threads compute the same offset — and
 *       the cache is a {@code ConcurrentHashMap}, so the duplicate
 *       write is harmless.</li>
 * </ul>
 *
 * <h2>Mutability of emitted globals</h2>
 *
 * <p>Most emitted globals are {@code constant}: vtables, itables,
 * interface maps, method/field/constructor descriptors, type-info tables,
 * and the {@code @refifaces_*} arrays are compile-time metadata that
 * generated code and the C runtime only ever read. They live in
 * {@code .rodata} and take a hardware write barrier if anything tries to
 * modify them.</p>
 *
 * <p>The per-class {@code @refclass_*} mirrors are the exception. A
 * {@code java.lang.Class} object is a live Java object whose JDK-side
 * methods store into a number of lazily-computed cache fields — {@code
 * name}, {@code packageName}, {@code componentType}, {@code
 * enumConstants}, {@code enumConstantDirectory}, {@code annotationData},
 * {@code reflectionData}, {@code classValueMap} — on the first read of
 * each. Because those fields live inside the {@code %ReflectionClass}
 * struct, the struct itself must be writable. See the emission site in
 * {@link #generateReflectionData()} for the full rationale; emitting it
 * as {@code constant} was the root cause of a SIGSEGV inside
 * {@code Class.getPackageName()} while the VarHandle bootstrap was
 * resolving {@code ConcurrentSkipListMap.<clinit>}.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class LlvmGlobalEmitter {

    public static final int OBJECT_HEADER_SIZE = 8;

    /**
     * First byte offset of the reserved tail of {@code struct
     * ReflectionClass}. Fields of {@code java.lang.Class} that the
     * emitter has not enumerated land here.
     */
    private static final int CLASS_RESERVED_OFFSET_START = 152;

    /**
     * Authoritative mapping from {@code java.lang.Class} instance-field names to
     * byte offsets inside the runtime structure {@code struct ReflectionClass}.
     *
     * <p>This is the single source of truth for addressing {@code Class} fields
     * from generated code. It is consulted <em>instead of</em>
     * {@link #collectInstanceFields(ClassNode)} so that the layout seen by the
     * emitter is the same layout the C runtime sees
     * ({@code jnative_runtime.h} plus every per-class native file that walks the
     * reflection registry).</p>
     *
     * <p>Why the class file's field order cannot be used: a {@code java.lang.Class}
     * instance is not a {@code %struct.java_lang_Class} at runtime. It is one of
     * the {@code @refclass_*} constants emitted as {@code %ReflectionClass}, whose
     * second word is the internal name and whose third word is the superclass.
     * The class file lists {@code cachedConstructor} first and {@code name}
     * second, so a class-file-derived layout puts {@code name} at offset 16 —
     * i.e. on top of {@code superclass}. That silently made
     * {@code Class.getName()} return the superclass mirror instead of a String
     * for every class that has a superclass, and
     * {@code Class.forName()} then received a {@code Class*} where it expected a
     * {@code String*}.</p>
     *
     * <p>Fields with no slot in {@code struct ReflectionClass} are deliberately
     * absent from this map: {@link #getFieldOffset(String, String)} catches them
     * and routes them to the reserved tail rather than throwing. The reserved
     * tail is real, initialised memory (all slots null), so reads and writes
     * are well defined and consistent across calls — see the comment inside
     * {@code getFieldOffset} for the full rationale.</p>
     *
     * <p>Field ordering and ABI contract with {@code jnative_runtime.h}: see
     * the class-level javadoc on {@code ReflectionClass} in that header. The
     * three descriptions (this map, the C struct, and the emitted
     * {@code %ReflectionClass} type) must always agree byte-for-byte.</p>
     */
    private static final Map<String, Integer> JAVA_LANG_CLASS_FIELD_OFFSETS;

    static {
        Map<String, Integer> m = new HashMap<>();
        // Field order and types are fixed by struct ReflectionClass
        // (jnative_runtime.h) and by the @refclass_* constant emission in
        // generateReflectionData() below. Change one, change all three.
        m.put("name",                     8);   // Java String | null
        m.put("superclass",              16);   // %ReflectionClass*
        m.put("interfaces",              24);   // %ReflectionClass**
        m.put("methods",                 32);   // C-only, Java code never reads it
        m.put("fields",                  40);   // C-only
        m.put("constructors",            48);   // C-only
        m.put("modifiers",               56);   // i32
        m.put("objectSize",              60);   // i32
        m.put("cname",                   64);   // const char*
        // Named tail slots. All are emitted as `i8* null` in every
        // @refclass_* constant except `module`, which points at the shared
        // unnamed-module singleton. See the class-level javadoc on
        // ReflectionClass in jnative_runtime.h for the full rationale.
        m.put("classLoader",             72);   // java/lang/ClassLoader | null
        m.put("module",                  80);   // java/lang/Module      | @jnative_unnamed_module
        m.put("componentType",           88);   // java/lang/Class       | null
        m.put("packageName",             96);   // java/lang/String      | null
        m.put("enumConstants",          104);   // Object[]              | null
        m.put("annotationData",         112);   // Map                   | null
        m.put("genericInfo",            120);   // Object                | null
        m.put("reflectionData",         128);   // SoftReference         | null
        m.put("classValueMap",          136);   // ClassValue            | null
        m.put("enumConstantDirectory",  144);   // Map                   | null
        // +152..+216 is the reserved tail (CLASS_RESERVED_SLOT_COUNT slots,
        // each 8 bytes). An unknown java.lang.Class field falls back to
        // CLASS_RESERVED_OFFSET_START; see getFieldOffset().
        JAVA_LANG_CLASS_FIELD_OFFSETS = Collections.unmodifiableMap(m);
    }

    /**
     * Internal (slash-form) names of the nine primitive class mirrors that
     * {@code java.lang.Class.getPrimitiveClass(String)} must be able to
     * resolve.
     *
     * <p>The native side of {@code getPrimitiveClass} (see
     * {@code jnative/lang/Class.c}) performs a linear lookup of the requested
     * name in the array {@code reflect_all_classes}; if the name is not
     * present, the function returns {@code NULL}. That {@code NULL} then
     * propagates into the static initializer of the corresponding primitive
     * wrapper:
     *
     * <pre>
     * java.lang.Void.&lt;clinit&gt;
     *     -&gt; Class.getPrimitiveClass("void")
     *         -&gt; reflect_all_classes lookup -&gt; NULL
     *             -&gt; gv_java_lang_Void_TYPE = NULL
     * </pre>
     *
     * <p>and subsequently into any JDK code that reads the wrapper's
     * {@code TYPE} field. On JDK 21 the first such reader is
     * {@code java.lang.invoke.VarForm.initMethodTypes}, which calls
     * {@code MethodType.changeReturnType(Void.TYPE)} during the lazy class
     * initialization of the VarHandle bootstrap machinery. The resulting
     * {@code MethodType} carries a {@code null} {@code rtype}, and the
     * intern-table lookup inside {@code MethodType.makeImpl} dereferences it
     * in {@code MethodType.hashCode()} — producing the
     * "Cannot invoke MethodType.hashCode() because … is null" NPE at
     * program start-up.</p>
     *
     * <p>The names below are in slash form because that is what
     * {@code find_registered_class} compares against, and they are exactly
     * the strings that the JDK's primitive wrappers pass to
     * {@code getPrimitiveClass} ("void", "boolean", "byte", "short",
     * "char", "int", "long", "float", "double").</p>
     */
    private static final Set<String> PRIMITIVE_TYPE_NAMES = Set.of(
        "void", "boolean", "byte", "short", "char",
        "int", "long", "float", "double"
    );

    /**
     * JVM access flags applied to every primitive class mirror.
     *
     * <p>JLS §20.1: the {@code Class} objects that represent the primitive
     * types are declared {@code public}, {@code final} and {@code abstract}.
     * This is the value that {@code Class.getModifiers()} must return for
     * {@code int.class}, {@code void.class}, and their siblings. The three
     * flags are taken directly from ASM's {@code Opcodes} so that they stay
     * in sync with the rest of the emitter.</p>
     */
    private static final int PRIMITIVE_CLASS_MODIFIERS =
        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT;

    private final Module module;
    private final DependencyResolver resolver;
    private final AliasAnalysisResult aliasResult;
    private final ReflectInfo reflectInfo;

    // -------------------------------------------------------------------------
    //  Caches. Every map/set that is written by one thread and read by
    //  another uses a ConcurrentHashMap or ConcurrentHashMap.newKeySet().
    // -------------------------------------------------------------------------

    private final Map<String, String> structNames = new ConcurrentHashMap<>();
    private final Map<String, Integer> fieldOffsets = new ConcurrentHashMap<>();
    private final Set<String> emittedStringConstants = ConcurrentHashMap.newKeySet();
    private final List<String> stringLiteralPool =
        Collections.synchronizedList(new ArrayList<>());
    private final Set<String> deferredStrings =
        Collections.synchronizedSet(new LinkedHashSet<>());

    private final Set<String> emittedVtableThunks = ConcurrentHashMap.newKeySet();

    public int getLiteralPoolSize() {
        return stringLiteralPool.size();
    }

    public static final class VtableLayout {
        public final List<String> slots = new ArrayList<>();
        public final Map<String, Integer> slotBySignature = new HashMap<>();
    }

    private final Map<String, VtableLayout> classLayouts = new ConcurrentHashMap<>();
    private final Map<String, VtableLayout> interfaceLayouts = new ConcurrentHashMap<>();
    private final Set<String> classLayoutInProgress = ConcurrentHashMap.newKeySet();
    private final Set<String> ifaceLayoutInProgress = ConcurrentHashMap.newKeySet();

    private final Map<String, Integer> interfaceIds = new ConcurrentHashMap<>();
    private int nextInterfaceId = 0;
    private int totalInterfaces = 0;
    private volatile boolean layoutsBuilt = false;

    private final Map<String, String> vtableNames = new ConcurrentHashMap<>();
    private final Map<String, Integer> vtableLengths = new ConcurrentHashMap<>();
    private final Map<String, Integer> lambdaVtableLengths = new ConcurrentHashMap<>();

    private final Map<String, String> typeInfoNames = new ConcurrentHashMap<>();

    private final Map<String, String> extraStructs = new ConcurrentHashMap<>();
    private final Map<String, String> extraVtables = new ConcurrentHashMap<>();

    // =========================================================================
    //  Native overrides and embedded resources
    // =========================================================================

    /**
     * Vtable slots that must resolve to a hand-written C function instead of
     * the body translated from bytecode. Key is
     * {@code "owner/name(descriptor)"} with the internal (slash) owner form,
     * e.g. {@code "java/lang/Class.getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"}.
     *
     * <p>Registered through {@link #overrideMethod}. Keys are matched against
     * the whole superclass chain, not just the concrete class, because
     * inherited slots are resolved under the subclass name. A key that
     * resolves to a function without a declaration in the module is a hard
     * error rather than a silent fallthrough, so a typo in the C symbol name
     * fails at codegen time instead of producing an unresolved external.
     */
    private final Map<String, String> methodOverrides = new ConcurrentHashMap<>();

    /**
     * Resource bytes to bake into the image, keyed by the internal path form
     * the JDK passes to {@code Class.getResourceAsStream} (leading {@code '/'}
     * stripped, {@code '/'}-separated, no leading package of the caller).
     *
     * <p>Registered through {@link #addEmbeddedResource} and emitted by
     * {@link #generateEmbeddedResources()} as a {@code constant} table the
     * C side indexes with {@code jnative_find_resource}.
     */
    private final Map<String, byte[]> embeddedResources = new LinkedHashMap<>();

    /**
     * Routes a single virtual slot to a native function.
     *
     * <p>The function must already be declared in the module — call
     * {@code LlvmGenerator.ensureExternalFunction(...)} for it first, otherwise
     * {@link #resolveVtableEntry} throws.
     *
     * <p>{@code className} is the class that <em>declares</em> the method, and
     * the registration applies to every subclass as well. That matters because
     * {@link #getOrBuildClassLayout} copies the parent's slot list verbatim
     * into each descendant layout and {@link #generateVtables} then resolves
     * each slot with the <em>concrete</em> class name: a
     * {@code java/lang/Class.getResourceAsStream} slot of
     * {@code jdk/internal/icu/impl/ICUBinary} is looked up under the key
     * {@code jdk/internal/icu/impl/ICUBinary.getResourceAsStream(...)}, which
     * a key registered for {@code java/lang/Class} alone would not match. See
     * {@link #findOverride} for the walk that closes that gap.
     *
     * <p>Registering the same signature on a subclass shadows the inherited
     * one, exactly as a real override would.
     */
    public void overrideMethod(String className, String methodName,
                               String descriptor, String nativeName) {
        methodOverrides.put(overrideKey(className, methodName, descriptor), nativeName);
    }

    private static String overrideKey(String className, String methodName,
                                      String descriptor) {
        return className + "." + methodName + descriptor;
    }

    /**
     * Finds the native override that applies to {@code className}'s
     * {@code name desc} slot, or {@code null} if there is none.
     *
     * <p>Walks the superclass chain, so a registration on the declaring class
     * covers every subclass that inherits the slot. The walk mirrors
     * {@link #getOrBuildClassLayout}'s own parent chain and carries the same
     * guards: unknown classes and self-referential cycles stop the walk
     * instead of spinning, because the class map is a cache of whatever the
     * resolver happened to load and is not guaranteed to be a DAG.
     */
    private String findOverride(String className, String name, String descriptor,
                                String[] ownerOut) {
        String owner = className;
        Set<String> seen = new HashSet<>();
        while (owner != null && !owner.isEmpty() && seen.add(owner)) {
            String nativeName = methodOverrides.get(overrideKey(owner, name, descriptor));
            if (nativeName != null) {
                ownerOut[0] = owner;
                return nativeName;
            }
            ClassNode cn = resolver.getClassNode(owner);
            if (cn == null) return null;
            owner = cn.getSuperName();
        }
        return null;
    }

    /**
     * Registers the bytes of one resource to bake into the image.
     *
     * <p>Later registrations for the same path replace earlier ones.
     */
    public void addEmbeddedResource(String path, byte[] data) {
        embeddedResources.put(path, data);
    }

    private static final class LambdaInfo {
        String lambdaId;
        String lambdaClassName;
        String samInterface;
        String samSig;
        String adaptorName;
        List<Type> capturedTypes;

        /**
         * All interfaces this lambda is an instance of: the SAM interface
         * itself plus every interface reachable from it through transitive
         * {@code extends} edges.
         *
         * <p>Two passes consume this list, and both must see the same set:</p>
         *
         * <ul>
         *   <li>{@link #emitLambdaVtables} builds one itable per interface
         *       here, so that a call site whose static receiver type is an
         *       ancestor of the SAM still resolves through
         *       {@code __jnative_lookup_itable}.</li>
         *   <li>{@link #generateTypeInfo} adds the lambda's vtable to the
         *       {@code @__type_info_*} table of every interface here, so
         *       that {@code obj instanceof AncestorInterface} returns
         *       {@code true} for a lambda whose SAM is a descendant.</li>
         * </ul>
         *
         * <p>The concrete failure this field closes: a lambda whose SAM is
         * {@code Pattern$BmpCharPredicate} is also an instance of
         * {@code Pattern$CharPredicate}, because the former extends the
         * latter. Without this list the lambda's ifacemap carried only
         * {@code BmpCharPredicate} (id 200), the JDK's own dispatch through
         * {@code CharPredicate} (id 201) found no entry in
         * {@code __jnative_lookup_itable} and landed on a garbage function
         * pointer, and every {@code \d} or {@code \s} predicate in
         * {@code java.util.regex} evaluated to {@code false}. The first
         * observable symptom was
         * {@code IllegalArgumentException: Error in security property.
         * Constraint unknown: denyAfter 2019-01-01} thrown from
         * {@code sun.security.util.DisabledAlgorithmConstraints$Constraints.<init>}
         * while parsing its own {@code denyAfter\s+(\d{4})-(\d{2})-(\d{2})}
         * regex.</p>
         *
         * <p>Ordering is stable: the list is built by a depth-first walk that
         * inserts the SAM first and then descends into each {@code extends}
         * edge in declaration order. Both consumers rely on that stability
         * only for determinism of the emitted IR, not for correctness.</p>
         */
        List<String> superInterfaces;
    }

    private final Map<String, LambdaInfo> lambdaRegistry = new ConcurrentHashMap<>();

    // =========================================================================
    //  Extra struct / vtable registry
    // =========================================================================

    public void addExtraStruct(String name, String definition) {
        extraStructs.put(name, definition);
    }

    public void addExtraVtable(String name, String content) {
        extraVtables.put(name, content);
    }

    public void emitExtraStructs(StringBuilder sb) {
        for (String def : extraStructs.values()) {
            sb.append(def).append("\n");
        }
    }

    public void emitExtraVtables(StringBuilder sb) {
        for (String def : extraVtables.values()) {
            sb.append(def).append("\n");
        }
    }

    // =========================================================================
    //  Layout preparation
    // =========================================================================

    /**
     * Prepares every lazily-built cache that the emitter consults.
     *
     * <p>After this method has returned successfully the class-layout map,
     * the interface-layout map, and the interface-id map are fully
     * populated and are never written to again. The parallel
     * function-emission phase relies on that fact: it reads those maps
     * without taking a lock.</p>
     *
     * <p>Double-checked locking on the {@code volatile layoutsBuilt} flag
     * ensures the expensive initialisation happens exactly once, even if
     * several threads call this method simultaneously.</p>
     */
    public void prepareLayouts() {
        if (layoutsBuilt) {
            return;
        }

        synchronized (this) {
            if (layoutsBuilt) {
                return;
            }

            resolver.forceLoadSystemClass("java/lang/String");

            List<ClassNode> all = new ArrayList<>(resolver.getClassMap().values());
            all.sort(Comparator.comparing(ClassNode::getName));

            for (ClassNode cn : all) {
                if (cn.isExternal()) continue;
                if (cn.isInterface()) continue;
                getOrBuildClassLayout(cn.getName());
            }
            getOrBuildClassLayout("java/lang/Object");

            Set<String> needed = new LinkedHashSet<>();

            for (ClassNode cn : all) {
                if (cn.isExternal()) continue;
                if (cn.isInterface()) continue;
                collectAllInterfacesRec(cn, needed);
            }

            for (Function func : module.getFunctions()) {
                for (BasicBlock block : func.getBlocks()) {
                    for (Instruction inst : block.getInstructions()) {
                        if (inst.getOpcode() != Opcode.INTERFACE_CALL) continue;
                        if (inst.getOperands().size() < 2) continue;
                        Value callee = inst.getOperands().get(1);
                        if (!(callee instanceof Constant c)) continue;
                        if (!c.getType().isReference()) continue;
                        String full = c.getValue().toString();
                        int dotIdx = full.lastIndexOf('.');
                        if (dotIdx <= 0) continue;
                        String owner = full.substring(0, dotIdx);

                        ClassNode ownerNode = resolver.getClassNode(owner);
                        if (ownerNode == null || !ownerNode.isInterface()) continue;
                        needed.add(owner);
                    }
                }
            }

            needed.addAll(interfaceIds.keySet());

            List<String> ifaceNames = new ArrayList<>(needed);
            Collections.sort(ifaceNames);
            interfaceIds.clear();
            nextInterfaceId = 0;
            for (String name : ifaceNames) {
                ClassNode cn = resolver.getClassNode(name);
                if (cn == null || !cn.isInterface()) continue;
                interfaceIds.put(name, nextInterfaceId++);
                getOrBuildInterfaceLayout(name);
            }
            totalInterfaces = nextInterfaceId;
            layoutsBuilt = true;
        }
    }

    public void prepareMethodIndex() {
        prepareLayouts();
    }

    private VtableLayout getOrBuildClassLayout(String className) {
        VtableLayout cached = classLayouts.get(className);
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            cached = classLayouts.get(className);
            if (cached != null) {
                return cached;
            }

            if (classLayoutInProgress.contains(className)) {
                // Recursive call for a class currently being built. Return
                // a fresh, empty layout so the caller can proceed; the
                // completed layout will be stored in classLayouts by the
                // outer call.
                return new VtableLayout();
            }
            classLayoutInProgress.add(className);

            VtableLayout layout = new VtableLayout();
            classLayouts.put(className, layout);

            ClassNode cn = resolver.getClassNode(className);
            if (cn == null) {
                classLayoutInProgress.remove(className);
                return layout;
            }

            if (!"java/lang/Object".equals(className)) {
                String superName = cn.getSuperName();
                if (superName == null || superName.equals(className)) {
                    superName = "java/lang/Object";
                }
                VtableLayout parent = getOrBuildClassLayout(superName);
                layout.slots.addAll(parent.slots);
                layout.slotBySignature.putAll(parent.slotBySignature);
            }

            if (!cn.isExternal()) {
                for (MethodNode mn : cn.getMethods()) {
                    if (!isVtableVirtual(mn)) continue;

                    int access = mn.getAccess();
                    if ((access & Opcodes.ACC_STATIC) != 0) {
                        throw new IllegalStateException(
                            "Static method '" + cn.getName() + "." + mn.getName()
                                + mn.getDescriptor() + "' was offered to the vtable "
                                + "layout builder for class '" + className + "'. "
                                + "isVtableVirtual() should have rejected it; the "
                                + "resolver's access flags are inconsistent.");
                    }
                    if ((access & Opcodes.ACC_PRIVATE) != 0) {
                        throw new IllegalStateException(
                            "Private method '" + cn.getName() + "." + mn.getName()
                                + mn.getDescriptor() + "' was offered to the vtable "
                                + "layout builder for class '" + className + "'. "
                                + "isVtableVirtual() should have rejected it.");
                    }

                    String sig = mn.getName() + mn.getDescriptor();
                    if (!layout.slotBySignature.containsKey(sig)) {
                        layout.slotBySignature.put(sig, layout.slots.size());
                        layout.slots.add(sig);
                    }
                }
            }

            classLayoutInProgress.remove(className);
            return layout;
        }
    }

    private VtableLayout getOrBuildInterfaceLayout(String ifaceName) {
        VtableLayout cached = interfaceLayouts.get(ifaceName);
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            cached = interfaceLayouts.get(ifaceName);
            if (cached != null) {
                return cached;
            }

            ClassNode cn = resolver.getClassNode(ifaceName);
            if (cn == null || !cn.isInterface()) {
                return new VtableLayout();
            }

            if (ifaceLayoutInProgress.contains(ifaceName)) {
                return new VtableLayout();
            }
            ifaceLayoutInProgress.add(ifaceName);

            VtableLayout layout = new VtableLayout();
            interfaceLayouts.put(ifaceName, layout);

            for (String parent : cn.getInterfaces()) {
                if (parent.equals(ifaceName)) continue;
                VtableLayout parentLayout = getOrBuildInterfaceLayout(parent);
                for (String sig : parentLayout.slots) {
                    if (!layout.slotBySignature.containsKey(sig)) {
                        layout.slotBySignature.put(sig, layout.slots.size());
                        layout.slots.add(sig);
                    }
                }
            }

            if (!cn.isExternal()) {
                for (MethodNode mn : cn.getMethods()) {
                    if (!isVtableVirtual(mn)) continue;
                    String sig = mn.getName() + mn.getDescriptor();
                    if (!layout.slotBySignature.containsKey(sig)) {
                        layout.slotBySignature.put(sig, layout.slots.size());
                        layout.slots.add(sig);
                    }
                }
            }

            ifaceLayoutInProgress.remove(ifaceName);
            return layout;
        }
    }

    private boolean isVtableVirtual(MethodNode mn) {
        int access = mn.getAccess();
        if ((access & Opcodes.ACC_STATIC) != 0) return false;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return false;
        String n = mn.getName();
        return !n.equals("<init>") && !n.equals("<clinit>");
    }

    public int getVirtualSlot(String owner, String name, String desc) {
        prepareLayouts();
        VtableLayout layout = getOrBuildClassLayout(owner);
        Integer slot = layout.slotBySignature.get(name + desc);
        return slot != null ? slot : -1;
    }

    public int getInterfaceMethodSlot(String iface, String name, String desc) {
        prepareLayouts();
        if (!interfaceIds.containsKey(iface)) return -1;
        VtableLayout layout = getOrBuildInterfaceLayout(iface);
        Integer slot = layout.slotBySignature.get(name + desc);
        return slot != null ? slot : -1;
    }

    public int getInterfaceId(String iface) {
        prepareLayouts();
        Integer id = interfaceIds.get(iface);
        return id != null ? id : -1;
    }

    public int getTotalInterfaces() {
        prepareLayouts();
        return totalInterfaces;
    }

    public String getVtableName(String className) {
        return vtableNames.get(className);
    }

    public int getVtableLength(String className) {
        return vtableLengths.getOrDefault(className, -1);
    }

    public int getLambdaVtableLength(String lambdaId) {
        return lambdaVtableLengths.getOrDefault(lambdaId, -1);
    }

    public String getStructName(String className) {
        return structNames.getOrDefault(className, LlvmTypeMapper.toLlvmStruct(className));
    }

    public String getTypeInfoName(String className) {
        return typeInfoNames.get(className);
    }

    /**
     * Returns the LLVM name of the {@code @refclass_*} mirror for the
     * given class, array descriptor included. The mirror for an array
     * descriptor such as {@code "[B"} is emitted by
     * {@link #generateReflectionData()} alongside the ordinary class
     * mirrors, so the same sanitised-name convention applies to both.
     *
     * <p>Used by NEW_ARRAY, which stores the mirror in the array header
     * at offset 0 so that {@code Object.getClass()} can tell an array
     * from an ordinary object.
     */
    public String getRefClassGlobalName(String className) {
        return "@refclass_" + LlvmTypeMapper.sanitizeIdentifier(className);
    }

    public String generateGlobals() {
        prepareLayouts();
        return generateStructs()
            + generateStaticFields()
            + generateVtables()
            + generateTypeStringConstants()
            + generateStringLiterals()
            + generateTypeInfo()
            // Both of these must run after generateVtables(): the unnamed
            // module points at @vtable_java_lang_Module, which only exists once
            // vtableNames is populated. Both must run after generateStructs():
            // the module object is a %struct.java_lang_Module literal, and the
            // resource table is a %JNativeResourceEntry literal.
            + generateUnnamedModule()
            + generateEmbeddedResources()
            + generateReflectionData();
    }

    // =========================================================================
    //  Unnamed-module singleton
    // =========================================================================

    /**
     * Emits the one shared {@code java.lang.Module} object that stands for the
     * unnamed module of the bootstrap loader.
     *
     * <p>Every {@code @refclass_*} mirror has a {@code module} slot at offset
     * 80, and the bytecode of {@code Class.getModule()} is a plain read of
     * that slot. Emitting null there means every {@code Class.getResourceAsStream}
     * dereferences a null {@code Module} and dies with an NPE on the
     * {@code thisModule.isNamed()} call that follows.
     *
     * <p>So every mirror points at this object instead. It is a {@code Module}
     * with all fields zeroed, which is exactly the JDK's unnamed module:
     * {@code name == null}, {@code loader == null}, no descriptor. That is the
     * state {@code Module.isNamed()} tests ({@code return name != null}), and
     * it steers {@code Class.getResourceAsStream} down its "unnamed module"
     * branch rather than the named-module branch.
     *
     * <p>The field list and their order come from {@link #collectInstanceFields},
     * the same source {@link #generateStructs()} uses, so this literal has
     * exactly the shape of the emitted {@code %struct.java_lang_Module} and LLVM
     * lays it out with the same natural alignment. It is a {@code constant}, so
     * it needs no run-time initialisation.
     *
     * <p>Zero values are produced by {@link #zeroLiteralFor(Type)}, which is
     * the single point where the Java-side {@link Type} and the LLVM
     * spelling of "the zero of that type" are kept in correspondence. An
     * integral literal {@code 0} is not valid where a pointer is expected —
     * the LLVM parser rejects it with "integer constant must have integer
     * type" — so the correspondence must be exact and must be maintained in
     * one place.
     */
    private String generateUnnamedModule() {
        ClassNode moduleNode = resolver.getClassNode("java/lang/Module");
        if (moduleNode == null || moduleNode.isExternal()) {
            throw new IllegalStateException(
                "java.lang.Module must be loaded and non-external; call "
                    + "DependencyResolver.forceLoadSystemClass(\"java/lang/Module\") "
                    + "before LlvmGlobalEmitter.generateGlobals()");
        }

        String structName = LlvmTypeMapper.toLlvmStruct("java/lang/Module");
        String vtableName = vtableNames.get("java/lang/Module");
        if (vtableName == null) {
            throw new IllegalStateException(
                "vtable_java_lang_Module must be emitted before the unnamed module; "
                    + "check that prepareLayouts() ran after forceLoadSystemClass");
        }

        StringBuilder init = new StringBuilder();
        init.append("i8* bitcast (%JNativeVTable* ").append(vtableName).append(" to i8*)");
        for (FieldNode f : collectInstanceFields(moduleNode)) {
            Type ft = f.getType();
            init.append(", ").append(LlvmTypeMapper.toLlvmType(ft))
                .append(" ").append(zeroLiteralFor(ft));
        }

        return "\n; ----- Unnamed module singleton -----\n"
            + "@jnative_unnamed_module = constant " + structName
            + " { " + init + " }, align 8\n\n";
    }

    /**
     * Returns the LLVM literal that spells "zero" for {@code type}, matching
     * the LLVM type that {@link LlvmTypeMapper#toLlvmType(Type)} produces for
     * the same {@link Type}.
     *
     * <p>LLVM's type system is strict about zero literals: an {@code i8*} (or
     * any other pointer type) accepts only {@code null}; an integer type
     * accepts only an integer literal; a floating-point type accepts only a
     * floating-point literal. Emitting {@code 0} where a pointer is expected
     * is not an implicit null — the parser rejects the whole module with
     * "integer constant must have integer type", as happened for the
     * unnamed-module mirror.</p>
     *
     * <p>The mapping is total: every non-null {@link Type} that can occur as
     * the declared type of an instance field is covered, and the {@code null}
     * branch exists only so that a caller passing a null reference does not
     * NPE — no field ever carries a null {@link Type}.</p>
     */
    private static String zeroLiteralFor(Type type) {
        if (type == null) {
            return "null";
        }
        if (type == Type.FLOAT || type == Type.DOUBLE) {
            return "0.0";
        }
        if (type == Type.BOOLEAN) {
            return "false";
        }
        if (type.isPrimitive()) {
            // int, long, byte, short, char.
            return "0";
        }
        // reference, array, null, block, unknown — every one of them is
        // rendered as i8* by LlvmTypeMapper.toLlvmType, and the only
        // well-typed zero for a pointer is `null`.
        return "null";
    }

    // =========================================================================
    //  Embedded resources
    // =========================================================================

    /**
     * Emits every resource registered through {@link #addEmbeddedResource} as a
     * byte array plus one flat lookup table.
     *
     * <p>The entry layout is {@code %JNativeResourceEntry} and is declared here
     * rather than in {@link #generateReflectionData()} because this table is
     * emitted before it. The C side walks the table with
     * {@code jnative_find_resource} and needs no knowledge of LLVM globals.
     *
     * <p>Paths are sorted so the emitted IR is stable across runs regardless of
     * hash iteration order.
     */
    private String generateEmbeddedResources() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Embedded resources -----\n");
        sb.append("%JNativeResourceEntry = type { i8*, i8*, i32 }\n");

        if (embeddedResources.isEmpty()) {
            sb.append("@jnative_builtin_resources = constant "
                + "[1 x %JNativeResourceEntry] "
                + "[%JNativeResourceEntry { i8* null, i8* null, i32 0 }]\n");
            sb.append("@jnative_builtin_resources_count = constant i32 0\n\n");
            return sb.toString();
        }

        List<String> keys = new ArrayList<>(embeddedResources.keySet());
        Collections.sort(keys);

        List<String> entryRefs = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String path = keys.get(i);
            byte[] data = embeddedResources.get(path);

            String pathRef = emitResourceByteLiteral(sb, "__jnative_res_path_" + i,
                path.getBytes(StandardCharsets.UTF_8), 1);
            String dataRef = emitResourceByteLiteral(sb, "__jnative_res_data_" + i,
                data, 0);

            entryRefs.add("%JNativeResourceEntry { i8* " + pathRef
                + ", i8* " + dataRef + ", i32 " + data.length + " }");
        }

        sb.append("@jnative_builtin_resources = constant [")
            .append(entryRefs.size())
            .append(" x %JNativeResourceEntry] [\n");
        for (int i = 0; i < entryRefs.size(); i++) {
            if (i > 0) sb.append(",\n");
            sb.append("  ").append(entryRefs.get(i));
        }
        sb.append("\n]\n");
        sb.append("@jnative_builtin_resources_count = constant i32 ")
            .append(entryRefs.size()).append("\n\n");
        return sb.toString();
    }

    /**
     * Emits {@code bytes} as a {@code private constant [N x i8]} and returns a
     * {@code getelementptr} expression yielding a pointer to its first element.
     *
     * <p>When {@code nulTerminate} is non-zero one {@code \00} byte is appended,
     * so the C side can use {@code strlen} on the path.
     */
    private String emitResourceByteLiteral(StringBuilder sb, String baseName,
                                           byte[] bytes, int nulTerminate) {
        int total = bytes.length + nulTerminate;
        StringBuilder esc = new StringBuilder();
        for (byte b : bytes) {
            int v = b & 0xFF;
            switch (v) {
                case '\\' -> esc.append("\\5C");
                case '"'  -> esc.append("\\22");
                case '\n' -> esc.append("\\0A");
                case '\r' -> esc.append("\\0D");
                case '\t' -> esc.append("\\09");
                default -> {
                    if (v < 0x20 || v > 0x7E) esc.append(String.format("\\%02X", v));
                    else esc.append((char) v);
                }
            }
        }
        if (nulTerminate != 0) esc.append("\\00");

        sb.append("@").append(baseName)
            .append(" = private unnamed_addr constant [")
            .append(total).append(" x i8] c\"")
            .append(esc).append("\", align 1\n");
        return "getelementptr inbounds ([" + total + " x i8], [" + total
            + " x i8]* @" + baseName + ", i32 0, i32 0)";
    }

    // =========================================================================
    //  Internal value-type for vtable slot resolution
    // =========================================================================

    private static final class ResolvedFn {
        final String name;
        final String type;

        ResolvedFn(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private ResolvedFn resolveVtableEntry(String className, String sig) {
        int parenIdx = sig.indexOf('(');
        if (parenIdx <= 0) return null;
        String name = sig.substring(0, parenIdx);
        String desc = sig.substring(parenIdx);

        // ---- explicit native override -------------------------------------
        // Checked before the abstract test on purpose: an override supplies the
        // implementation for a slot the bytecode alone cannot serve, so an
        // abstract declaration overridden here is still a real entry.
        String[] overrideOwner = new String[1];
        String overrideName = findOverride(className, name, desc, overrideOwner);
        if (overrideName != null) {
            if (module.getFunction(overrideName) == null) {
                throw new IllegalStateException(
                    "overrideMethod('" + overrideKey(overrideOwner[0], name, desc)
                        + "' -> '" + overrideName
                        + "') has no declaration in the module. "
                        + "Call LlvmGenerator.ensureExternalFunction(...) first.");
            }
            String[] declOwner = new String[1];
            MethodNode mn = resolver.findMethodInHierarchy(
                overrideOwner[0], name, desc, declOwner);
            if (mn == null) {
                throw new IllegalStateException(
                    "overrideMethod('" + overrideKey(className, name, desc)
                        + "') refers to an unknown method");
            }
            // The receiver is typed as the class the override was registered
            // on, not the concrete one, so every vtable that shares the
            // inherited slot emits the same function-pointer type.
            StringBuilder params = new StringBuilder();
            params.append(LlvmTypeMapper.toLlvmType(Type.reference(overrideOwner[0])));
            for (Type pt : mn.getParameterTypes()) {
                params.append(", ").append(LlvmTypeMapper.toLlvmType(pt));
            }
            String ret = LlvmTypeMapper.toLlvmType(mn.getReturnType());
            return new ResolvedFn(overrideName, ret + " (" + params + ")*");
        }

        String[] foundOwner = new String[1];
        MethodNode mn = resolver.findMethodInHierarchy(className, name, desc, foundOwner);

        if (mn == null) {
            // The whole method is absent from the hierarchy. This is normal
            // for a class whose parent layout was populated from an older
            // class file, or for an interface method that no class actually
            // declares; the thunk will fire only if a call site ever lands
            // here, and the surrounding diagnostic will name the slot.
            log.warn("resolveVtableEntry: method '{}.{}{}' not found in the "
                    + "class hierarchy; vtable slot will be an unresolved thunk "
                    + "(class={})",
                className, name, desc, className);
            return null;
        }
        if (mn.isAbstract()) {
            // Abstract slot with no concrete override in the reachable set.
            // The thunk is intentional: no call site can ever land here
            // because no concrete receiver of this class can exist.
            return null;
        }

        String owner = foundOwner[0] != null ? foundOwner[0] : className;
        String baseName = LlvmRuntime.mangleMethod(owner, name, desc);
        String nativeName = "__jnative_" + baseName;

        String funcName;
        Function target = module.getFunction(baseName);
        if (target != null) {
            funcName = target.getName();
        } else if (module.getFunction(nativeName) != null) {
            funcName = nativeName;
        } else if (mn.isNative()) {
            Type retType = mn.getReturnType();
            List<Type> paramTypes = mn.getParameterTypes();

            List<Type> allParams = new ArrayList<>();
            allParams.add(Type.reference(owner));
            allParams.addAll(paramTypes);

            Function nf = new Function(nativeName, retType);
            for (int i = 0; i < allParams.size(); i++) {
                nf.addParameter(new Parameter(allParams.get(i), i));
            }
            module.addFunction(nf);
            funcName = nativeName;
        } else {
            return null;
        }

        String ret = LlvmTypeMapper.toLlvmType(mn.getReturnType());
        StringBuilder params = new StringBuilder();
        params.append(LlvmTypeMapper.toLlvmType(Type.reference(owner)));
        for (Type pt : mn.getParameterTypes()) {
            params.append(", ").append(LlvmTypeMapper.toLlvmType(pt));
        }
        return new ResolvedFn(funcName, ret + " (" + params + ")*");
    }

    private String vtableSlotEntry(String className,
                                   String ifaceOrNull,
                                   String sig,
                                   ResolvedFn fn,
                                   StringBuilder sb) {
        if (fn != null) {
            return "i8* bitcast (" + fn.type + " @" + fn.name + " to i8*)";
        }

        String slotId = LlvmTypeMapper.sanitizeIdentifier(className)
            + (ifaceOrNull == null
            ? ""
            : "_" + LlvmTypeMapper.sanitizeIdentifier(ifaceOrNull))
            + "_" + LlvmTypeMapper.sanitizeIdentifier(sig);

        String thunkName = "__jnative_vtable_missing_" + slotId;

        if (!emittedVtableThunks.add(thunkName)) {
            return "i8* bitcast (i8* (...)* @" + thunkName + " to i8*)";
        }

        String message;
        if (ifaceOrNull == null) {
            message = "class '" + className + "', slot '" + sig + "'";
        } else {
            message = "interface '" + ifaceOrNull
                + "' implemented by '" + className
                + "', slot '" + sig + "'";
        }

        String msgRef = ensureStringConstantPtr(sb, message);

        sb.append("define i8* @").append(thunkName).append("(...) {\n")
            .append("entry:\n")
            .append("  call void @__jnative_unresolved_slot(i8* ")
            .append(msgRef).append(")\n")
            .append("  unreachable\n")
            .append("}\n");

        return "i8* bitcast (i8* (...)* @" + thunkName + " to i8*)";
    }

    private String generateVtables() {
        prepareLayouts();
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Vtables (hierarchy-local slots) -----\n");

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        allClasses.sort(Comparator.comparing(ClassNode::getName));

        for (String iface : interfaceLayouts.keySet()) {
            String vtableName = "@vtable_" + LlvmTypeMapper.sanitizeIdentifier(iface);
            vtableNames.put(iface, vtableName);
            String ifaceNameRef = ensureStringConstantPtr(sb, iface);
            sb.append(vtableName)
                .append(" = constant %JNativeVTable { i8** null, %JNativeIfaceMap* null, i8* ")
                .append(ifaceNameRef).append(" }\n");
        }

        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            if (cls.isInterface()) continue;

            String className = cls.getName();
            VtableLayout layout = getOrBuildClassLayout(className);
            int length = Math.max(layout.slots.size(), 1);

            List<String> entries = new ArrayList<>(length);
            for (int i = 0; i < length; i++) entries.add("i8* null");

            for (int i = 0; i < layout.slots.size(); i++) {
                String sig = layout.slots.get(i);
                ResolvedFn fn = resolveVtableEntry(className, sig);
                entries.set(i, vtableSlotEntry(className, null, sig, fn, sb));
            }

            String methodsName = "@vtable_methods_" + LlvmTypeMapper.sanitizeIdentifier(className);
            sb.append(methodsName).append(" = private constant [")
                .append(length).append(" x i8*] [");
            for (int i = 0; i < length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(entries.get(i));
            }
            sb.append("]\n");

            vtableLengths.put(className, length);
        }

        for (ClassNode cls : allClasses) {
            if (cls.isExternal() || cls.isInterface()) continue;
            String className = cls.getName();

            Set<String> ifaces = collectAllInterfaces(cls);
            Map<String, String> itableNames = new LinkedHashMap<>();
            Map<String, Integer> itableLens = new LinkedHashMap<>();
            Map<String, Integer> itableIds = new LinkedHashMap<>();

            for (String iface : ifaces) {
                Integer ifaceId = interfaceIds.get(iface);
                if (ifaceId == null) continue;

                VtableLayout ifaceLayout = getOrBuildInterfaceLayout(iface);
                int len = Math.max(ifaceLayout.slots.size(), 1);

                List<String> entries = new ArrayList<>(len);
                for (int i = 0; i < len; i++) entries.add("i8* null");

                for (int i = 0; i < ifaceLayout.slots.size(); i++) {
                    String sig = ifaceLayout.slots.get(i);
                    ResolvedFn fn = resolveVtableEntry(className, sig);
                    entries.set(i, vtableSlotEntry(className, iface, sig, fn, sb));
                }

                String itableName = "@itable_"
                    + LlvmTypeMapper.sanitizeIdentifier(className) + "_"
                    + LlvmTypeMapper.sanitizeIdentifier(iface);
                sb.append(itableName).append(" = private constant [")
                    .append(len).append(" x i8*] [");
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(entries.get(i));
                }
                sb.append("]\n");

                itableNames.put(iface, itableName);
                itableLens.put(iface, len);
                itableIds.put(iface, ifaceId);
            }

            List<String> sortedIfaces = new ArrayList<>(itableIds.keySet());
            sortedIfaces.sort(Comparator.comparingInt(itableIds::get));

            String ifacemapName = "@ifacemap_" + LlvmTypeMapper.sanitizeIdentifier(className);

            if (!sortedIfaces.isEmpty()) {
                String entriesArrayName = "@ifacemap_entries_"
                    + LlvmTypeMapper.sanitizeIdentifier(className);
                sb.append(entriesArrayName).append(" = private constant [")
                    .append(sortedIfaces.size()).append(" x %JNativeIfaceMapEntry] [");
                for (int i = 0; i < sortedIfaces.size(); i++) {
                    if (i > 0) sb.append(", ");
                    String iface = sortedIfaces.get(i);
                    sb.append("%JNativeIfaceMapEntry { i32 ").append(itableIds.get(iface))
                        .append(", i8** bitcast ([").append(itableLens.get(iface))
                        .append(" x i8*]* ").append(itableNames.get(iface))
                        .append(" to i8**) }");
                }
                sb.append("]\n");

                sb.append(ifacemapName).append(" = constant %JNativeIfaceMap { i32 ")
                    .append(sortedIfaces.size()).append(", %JNativeIfaceMapEntry* ")
                    .append(entriesArrayName).append(" }\n");
            } else {
                sb.append(ifacemapName)
                    .append(" = constant %JNativeIfaceMap { i32 0, %JNativeIfaceMapEntry* null }\n");
            }

            int methodLen = vtableLengths.getOrDefault(className, 1);
            String methodsName = "@vtable_methods_" + LlvmTypeMapper.sanitizeIdentifier(className);

            String vtableName = "@vtable_" + LlvmTypeMapper.sanitizeIdentifier(className);
            String classNameRef = ensureStringConstantPtr(sb, className);
            sb.append(vtableName).append(" = constant %JNativeVTable {\n")
                .append("  i8** bitcast ([").append(methodLen).append(" x i8*]* ")
                .append(methodsName).append(" to i8**),\n")
                .append("  %JNativeIfaceMap* ").append(ifacemapName).append(",\n")
                .append("  i8* ").append(classNameRef).append("\n")
                .append("}\n");

            vtableNames.put(className, vtableName);
        }

        return sb.toString();
    }

    private Set<String> collectAllInterfaces(ClassNode cls) {
        Set<String> result = new LinkedHashSet<>();
        if (cls != null) collectAllInterfacesRec(cls, result);
        return result;
    }

    private void collectAllInterfacesRec(ClassNode cls, Set<String> out) {
        if (cls == null) return;
        for (String i : cls.getInterfaces()) {
            if (out.add(i)) {
                ClassNode in = resolver.getClassNode(i);
                if (in != null) collectAllInterfacesRec(in, out);
            }
        }
        String superName = cls.getSuperName();
        if (superName != null && !superName.equals(cls.getName())) {
            ClassNode sn = resolver.getClassNode(superName);
            if (sn != null) collectAllInterfacesRec(sn, out);
        }
    }

    // =========================================================================
    //  Lambdas
    // =========================================================================

    public String registerLambdaStruct(String lambdaId, List<Type> capturedTypes) {
        String structName = "%struct.lambda_" + lambdaId;
        if (extraStructs.containsKey(structName)) return structName;

        StringBuilder fields = new StringBuilder("{ i8*");
        for (Type t : capturedTypes) {
            fields.append(", ").append(LlvmTypeMapper.toLlvmType(t));
        }
        fields.append(" }");

        extraStructs.putIfAbsent(structName, structName + " = type " + fields);
        return structName;
    }

    /**
     * Registers a lambda class with the emitter and precomputes the
     * interface closure it participates in.
     *
     * <p>The closure is the set of every interface a lambda instance is
     * assignable to: the SAM interface itself plus every interface reachable
     * from it through transitive {@code extends} edges. Two separate
     * emission paths consume this set, and both need it to be complete:</p>
     *
     * <ul>
     *   <li>{@link #emitLambdaVtables} emits one itable per interface in the
     *       closure, so that a call site whose static receiver type is an
     *       ancestor of the SAM still resolves through
     *       {@code __jnative_lookup_itable}.</li>
     *   <li>{@link #generateTypeInfo} adds the lambda's vtable to the
     *       {@code @__type_info_*} table of every interface in the closure,
     *       so that {@code obj instanceof SomeAncestorInterface} returns
     *       {@code true} for a lambda whose SAM is a descendant.</li>
     * </ul>
     *
     * <p>The concrete failure that made this closure necessary:
     * {@code java.util.regex.Pattern.newCharProperty} dispatches on
     * {@code p instanceof Pattern$BmpCharPredicate} and takes two different
     * compilation paths. A lambda whose SAM is {@code BmpCharPredicate} is
     * also an instance of {@code Pattern$CharPredicate}, and the pattern
     * compiler routinely dispatches through the more general ancestor —
     * {@code predicate.is(ch)} in every {@code CharPredicate} call site.
     * When the closure was not computed, the lambda's ifacemap carried only
     * the SAM's id (200) and the ancestor's id (201) was absent, so
     * {@code __jnative_lookup_itable} returned {@code NULL}, the emitted
     * call landed on garbage, and every {@code \d} / {@code \s} predicate
     * evaluated to {@code false}. The first observable symptom was
     * {@code IllegalArgumentException: Error in security property.
     * Constraint unknown: denyAfter 2019-01-01} thrown from
     * {@code sun.security.util.DisabledAlgorithmConstraints$Constraints.<init>}
     * while it parsed its own {@code denyAfter\s+(\d{4})-(\d{2})-(\d{2})}
     * regex.</p>
     */
    public String registerLambdaClass(String lambdaId,
                                      String samInterface,
                                      String samSig,
                                      String adaptorName,
                                      List<Type> capturedTypes) {
        prepareLayouts();

        if (samInterface == null
            || samInterface.isEmpty()
            || samInterface.indexOf('(') >= 0
            || samInterface.indexOf(')') >= 0
            || samInterface.charAt(0) == '[') {
            throw new IllegalStateException(
                "registerLambdaClass called with an invalid SAM interface name '"
                    + samInterface + "' for lambda " + lambdaId);
        }

        ClassNode samNode = resolver.getClassNode(samInterface);
        if (samNode != null && !samNode.isExternal() && !samNode.isInterface()) {
            throw new IllegalStateException(
                "registerLambdaClass: SAM '" + samInterface
                    + "' resolves to a class, not an interface (lambdaId="
                    + lambdaId + ")");
        }

        String lambdaClassName = "__Lambda_" + lambdaId;
        if (lambdaRegistry.containsKey(lambdaId)) {
            return vtableNames.get(lambdaClassName);
        }

        synchronized (this) {
            if (!interfaceIds.containsKey(samInterface)) {
                interfaceIds.put(samInterface, nextInterfaceId++);
                totalInterfaces = nextInterfaceId;
            }
            getOrBuildInterfaceLayout(samInterface);

            /*
             * Compute the transitive closure of super-interfaces of the
             * SAM. The closure is built depth-first, with the SAM itself
             * inserted first, then each {@code extends} edge in
             * declaration order. The order is stable across runs, which
             * is what makes the emitted IR deterministic.
             */
            LinkedHashSet<String> superIfaces = new LinkedHashSet<>();
            collectLambdaSuperInterfaces(samInterface, superIfaces);
            List<String> superIfacesList = new ArrayList<>(superIfaces);

            /*
             * Every interface in the closure needs a global id. In the
             * common case prepareLayouts() already assigned one because
             * the interface is reachable from some concrete class; the
             * check here closes the corner case where the SAM is only
             * mentioned through a lambda and nothing else in the image
             * references its ancestors.
             */
            for (String iface : superIfacesList) {
                if (!interfaceIds.containsKey(iface)) {
                    interfaceIds.put(iface, nextInterfaceId++);
                    totalInterfaces = nextInterfaceId;
                }
                getOrBuildInterfaceLayout(iface);
            }

            registerLambdaStruct(lambdaId, capturedTypes);

            LambdaInfo info = new LambdaInfo();
            info.lambdaId = lambdaId;
            info.lambdaClassName = lambdaClassName;
            info.samInterface = samInterface;
            info.samSig = samSig;
            info.adaptorName = adaptorName;
            info.capturedTypes = new ArrayList<>(capturedTypes);
            info.superInterfaces = superIfacesList;
            lambdaRegistry.put(lambdaId, info);

            String vtableName = "@vtable_" + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName);
            vtableNames.put(lambdaClassName, vtableName);
            return vtableName;
        }
    }

    /**
     * Populates {@code out} with the transitive closure of super-interfaces
     * of {@code ifaceName}, starting with {@code ifaceName} itself.
     *
     * <p>The walk follows only {@code extends} edges in the interface
     * hierarchy. {@code java/lang/Object} is never reached, because
     * {@link ClassNode#getInterfaces()} of an interface lists only its
     * declared super-interfaces, and Object is a class, not an interface.</p>
     *
     * <p>Cycles in the interface hierarchy are forbidden by the JVM, but the
     * {@code out} set is used as a visited-set anyway: a diamond-shaped
     * hierarchy (which is legal) would otherwise be visited exponentially
     * many times.</p>
     */
    private void collectLambdaSuperInterfaces(String ifaceName, Set<String> out) {
        if (ifaceName == null || ifaceName.isEmpty()) return;
        if (!out.add(ifaceName)) return;

        ClassNode cn = resolver.getClassNode(ifaceName);
        if (cn == null) return;
        for (String parent : cn.getInterfaces()) {
            collectLambdaSuperInterfaces(parent, out);
        }
    }

    public String emitLambdaVtables() {
        prepareLayouts();
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Lambda vtables -----\n");

        VtableLayout objectLayout = getOrBuildClassLayout("java/lang/Object");
        int methodLen = Math.max(objectLayout.slots.size(), 1);

        for (LambdaInfo info : lambdaRegistry.values()) {
            String lambdaClassName = info.lambdaClassName;
            String samInterface = info.samInterface;

            List<String> methodEntries = new ArrayList<>(methodLen);
            for (int i = 0; i < methodLen; i++) methodEntries.add("i8* null");
            for (int i = 0; i < objectLayout.slots.size(); i++) {
                String sig = objectLayout.slots.get(i);
                ResolvedFn fn = resolveVtableEntry("java/lang/Object", sig);
                methodEntries.set(i, vtableSlotEntry("java/lang/Object", null, sig, fn, sb));
            }

            String methodsName = "@vtable_methods_"
                    + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName);
            sb.append(methodsName).append(" = private constant [")
                    .append(methodLen).append(" x i8*] [");
            for (int i = 0; i < methodLen; i++) {
                if (i > 0) sb.append(", ");
                sb.append(methodEntries.get(i));
            }
            sb.append("]\n");

            /*
             * One itable per interface in the closure, not just the SAM.
             *
             * This is essential: code in the JDK routinely calls a SAM
             * method through an ancestor interface rather than through the
             * declared SAM type. In java.util.regex, `predicate.is(ch)` is
             * compiled as an INTERFACE_CALL on Pattern$CharPredicate, not
             * on Pattern$BmpCharPredicate, even though the lambda's
             * declared SAM is BmpCharPredicate. When the itable for
             * CharPredicate was missing, __jnative_lookup_itable returned
             * NULL for the call, the emitted code dereferenced a garbage
             * function pointer, and every \d / \s predicate evaluated to
             * false — which in turn made
             * DisabledAlgorithmConstraints$Constraints.<init> reject its
             * own `denyAfter\s+(\d{4})-(\d{2})-(\d{2})` regex with
             * "Constraint unknown: denyAfter 2019-01-01".
             *
             * Every non-SAM slot is resolved through resolveVtableEntry()
             * rather than being filled with an unresolved thunk. The
             * lookup starts at the SAM interface — the most-derived
             * interface in the closure — so findMethodInHierarchy walks
             * the whole chain from there and returns the maximally-
             * specific default, matching what the JVM's own resolution
             * would pick. For a lambda whose SAM is BmpCharPredicate (which
             * extends CharPredicate), a dispatch through
             * CharPredicate.union(CharPredicate) therefore lands on
             * BmpCharPredicate's own override of that method, not on
             * CharPredicate's base default — which is exactly the semantics
             * of default-method resolution in JVMS §5.4.3.3.
             *
             * Only if both lookups fail (no body was ever translated into
             * the module for this slot) does the emitter fall back to the
             * diagnostic thunk, which fires only if a call site ever
             * actually lands on the slot at run time.
             *
             * The SAM slot itself is skipped in the loop and filled in with
             * the adaptor afterwards. That keeps the slot bound to the
             * lambda's own body rather than to any inherited default, and
             * it avoids emitting an unreachable thunk that would have been
             * immediately overwritten.
             */
            if (info.superInterfaces == null || info.superInterfaces.isEmpty()) {
                throw new IllegalStateException(
                        "Lambda '" + info.lambdaId + "' has an empty "
                                + "super-interface closure; registerLambdaClass "
                                + "must never produce this state");
            }

            List<String>  ifaceNames  = new ArrayList<>(info.superInterfaces);
            List<Integer> ifaceIds    = new ArrayList<>(ifaceNames.size());
            List<String>  itableNames = new ArrayList<>(ifaceNames.size());
            List<Integer> itableLens  = new ArrayList<>(ifaceNames.size());

            for (String iface : ifaceNames) {
                Integer ifaceId = interfaceIds.get(iface);
                if (ifaceId == null) {
                    throw new IllegalStateException(
                            "Lambda '" + info.lambdaId + "' super-interface '"
                                    + iface + "' has no global interface id; "
                                    + "registerLambdaClass's closure pass missed it");
                }

                VtableLayout ifaceLayout = getOrBuildInterfaceLayout(iface);
                int len = Math.max(ifaceLayout.slots.size(), 1);

                /*
                 * Locate the SAM method inside this interface's own layout.
                 * For the SAM interface itself this is the abstract method
                 * the lambda implements; for an ancestor the slot exists
                 * only when the ancestor also declares (or inherits) the
                 * same signature — otherwise the ancestor simply has no
                 * entry for it and the adaptor is not placed here.
                 */
                Integer slotObj = ifaceLayout.slotBySignature.get(info.samSig);
                int adaptorSlot = (slotObj != null) ? slotObj : -1;

                List<String> entries = new ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    // SAM slot: filled in below with the adaptor. Leaving
                    // it out of the resolution loop avoids emitting an
                    // unused thunk for the slot that the adaptor is about
                    // to overwrite.
                    if (i == adaptorSlot) {
                        entries.add(null);
                        continue;
                    }

                    String sig = i < ifaceLayout.slots.size()
                            ? ifaceLayout.slots.get(i)
                            : "<unknown>";

                    ResolvedFn fn = null;
                    if (!"<unknown>".equals(sig)) {
                        // First try the SAM interface — the most-derived
                        // interface of the closure — so that the
                        // maximally-specific default wins, matching the
                        // JVM's own resolution rule.
                        fn = resolveVtableEntry(samInterface, sig);

                        // Fall back to the interface whose itable we are
                        // currently emitting.  With a correctly built
                        // closure this should never be necessary (the SAM
                        // hierarchy reaches every ancestor interface), but
                        // the second lookup costs nothing and makes the
                        // emitter robust against a closure that was built
                        // from an incomplete class map.
                        if (fn == null) {
                            fn = resolveVtableEntry(iface, sig);
                        }
                    }

                    entries.add(vtableSlotEntry(lambdaClassName, iface, sig, fn, sb));
                }

                // Bind the SAM slot to the lambda's own adaptor. The
                // adaptor's calling convention is the generic
                // `i8* (i8*, ...) *` shape, which the emitter's
                // INTERFACE_CALL path is prepared to accept (it bitcasts
                // the slot back to the concrete function-pointer type
                // derived from the callee signature before invoking).
                if (adaptorSlot >= 0) {
                    entries.set(adaptorSlot,
                            "i8* bitcast (i8* (i8*, ...)* @" + info.adaptorName
                                    + " to i8*)");
                }

                String itableName = "@itable_"
                        + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName) + "_"
                        + LlvmTypeMapper.sanitizeIdentifier(iface);
                sb.append(itableName).append(" = private constant [")
                        .append(len).append(" x i8*] [");
                for (int i = 0; i < len; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(entries.get(i));
                }
                sb.append("]\n");

                ifaceIds.add(ifaceId);
                itableNames.add(itableName);
                itableLens.add(len);
            }

            String ifacemapEntriesName = "@ifacemap_entries_"
                    + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName);
            String ifacemapName = "@ifacemap_"
                    + LlvmTypeMapper.sanitizeIdentifier(lambdaClassName);

            sb.append(ifacemapEntriesName)
                    .append(" = private constant [")
                    .append(ifaceNames.size())
                    .append(" x %JNativeIfaceMapEntry] [");
            for (int i = 0; i < ifaceNames.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("%JNativeIfaceMapEntry { i32 ").append(ifaceIds.get(i))
                        .append(", i8** bitcast ([").append(itableLens.get(i))
                        .append(" x i8*]* ").append(itableNames.get(i))
                        .append(" to i8**) }");
            }
            sb.append("]\n");

            sb.append(ifacemapName)
                    .append(" = constant %JNativeIfaceMap { i32 ")
                    .append(ifaceNames.size())
                    .append(", %JNativeIfaceMapEntry* ")
                    .append(ifacemapEntriesName).append(" }\n");

            String vtableName = vtableNames.get(lambdaClassName);
            String lambdaNameRef = ensureStringConstantPtr(sb, lambdaClassName);
            sb.append(vtableName).append(" = constant %JNativeVTable {\n")
                    .append("  i8** bitcast ([").append(methodLen).append(" x i8*]* ")
                    .append(methodsName).append(" to i8**),\n")
                    .append("  %JNativeIfaceMap* ").append(ifacemapName).append(",\n")
                    .append("  i8* ").append(lambdaNameRef).append("\n")
                    .append("}\n");

            vtableLengths.put(lambdaClassName, methodLen);
            lambdaVtableLengths.put(info.lambdaId, methodLen);
        }
        return sb.toString();
    }

    // =========================================================================
    //  Structs
    // =========================================================================

    private String generateStructs() {
        Set<String> newClassNames = new HashSet<>();
        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() == Opcode.NEW && !inst.getOperands().isEmpty()) {
                        Value v = inst.getOperands().getFirst();
                        if (v instanceof Constant c && c.getType().isReference()) {
                            newClassNames.add(c.getValue().toString());
                        }
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        String objStruct = LlvmTypeMapper.toLlvmStruct("java/lang/Object");
        if (!structNames.containsKey("java/lang/Object")) {
            sb.append(objStruct).append(" = type { i8* }\n");
            structNames.put("java/lang/Object", objStruct);
        }

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        allClasses.sort(Comparator.comparing(ClassNode::getName));
        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            if (structNames.containsKey(cls.getName())) continue;

            String structName = LlvmTypeMapper.toLlvmStruct(cls.getName());
            sb.append(structName).append(" = type { i8*");

            List<FieldNode> allFields = collectInstanceFields(cls);
            for (FieldNode field : allFields) {
                sb.append(", ").append(LlvmTypeMapper.toLlvmType(field.getType()));
            }
            sb.append(" }\n");
            structNames.put(cls.getName(), structName);
        }

        for (String className : newClassNames) {
            if (!structNames.containsKey(className)) {
                String structName = LlvmTypeMapper.toLlvmStruct(className);
                sb.append(structName).append(" = type { i8* }\n");
                structNames.put(className, structName);
            }
        }

        sb.append("\n");
        return sb.toString();
    }

    private List<FieldNode> collectInstanceFields(ClassNode cls) {
        List<FieldNode> result = new ArrayList<>();
        if (cls.getSuperName() != null && !cls.getSuperName().equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(cls.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                result.addAll(collectInstanceFields(superNode));
            }
        }
        for (FieldNode f : cls.getFields()) {
            if ((f.getAccess() & Opcodes.ACC_STATIC) == 0) {
                result.add(f);
            }
        }
        return result;
    }

    // =========================================================================
    //  Static fields
    // =========================================================================

    private String generateStaticFields() {
        StringBuilder sb = new StringBuilder();
        Map<String, PointsToSet> staticFields = aliasResult.getGraph().getStaticFieldPointsToMap();
        List<String> sorted = new ArrayList<>(staticFields.keySet());
        Collections.sort(sorted);
        for (String fullName : sorted) {
            int dot = fullName.lastIndexOf('.');
            String owner = fullName.substring(0, dot);
            String fieldName = fullName.substring(dot + 1);
            Type fieldType = getFieldType(owner, fieldName);
            if (fieldType == null) fieldType = Type.UNKNOWN;
            String llvmType = LlvmTypeMapper.toLlvmType(fieldType);

            String init;
            if (fieldType.isReference() || fieldType.isArray()
                || fieldType.isNull() || fieldType.isUnknown()) {
                init = "null";
            } else if (fieldType == Type.FLOAT || fieldType == Type.DOUBLE) {
                init = "0.0";
            } else {
                init = "0";
            }

            String globalName = "gv_" + LlvmTypeMapper.sanitizeIdentifier(fullName);
            sb.append("@").append(globalName).append(" = global ").append(llvmType)
                .append(" ").append(init).append(", align 8\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    // =========================================================================
    //  String / type-name constants
    // =========================================================================

    private String generateTypeStringConstants() {
        Set<String> names = new LinkedHashSet<>();
        names.add("java/lang/Object");

        for (ClassNode cn : resolver.getClassMap().values()) {
            if (cn.isExternal()) continue;
            names.add(cn.getName());
        }

        for (Function func : module.getFunctions()) {
            String fn = func.getName();
            if (fn != null && !fn.isEmpty()) names.add(fn);
        }

        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    collectStringConstantsFromInstruction(inst, names);
                }
                Terminator term = block.getTerminator();
                if (term != null) collectStringConstantsFromTerminator(term, names);
            }
        }

        StringBuilder sb = new StringBuilder("\n; ----- String / type-name constants -----\n");
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        for (String name : sorted) {
            if (emittedStringConstants.add(name)) {
                sb.append(LlvmRuntime.typeStringConstant(name));
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    public void registerDeferredString(String s) {
        if (s != null && !s.isEmpty()) {
            deferredStrings.add(s);
        }
    }

    public String generateDeferredStringConstants() {
        // Snapshot the synchronized set under its own monitor, then work
        // on the copy. This is the read side of the write performed by
        // registerDeferredString() from the parallel emission phase.
        List<String> snapshot;
        synchronized (deferredStrings) {
            if (deferredStrings.isEmpty()) {
                return "";
            }
            snapshot = new ArrayList<>(deferredStrings);
        }
        Collections.sort(snapshot);

        StringBuilder body = new StringBuilder();
        boolean any = false;
        for (String s : snapshot) {
            if (emittedStringConstants.add(s)) {
                body.append(LlvmRuntime.typeStringConstant(s));
                any = true;
            }
        }
        if (!any) {
            return "";
        }

        return "\n; ----- Deferred string constants (NPE register names) -----\n"
            + body
            + "\n";
    }

    private void collectStringConstantsFromInstruction(Instruction inst, Set<String> names) {
        Opcode op = inst.getOpcode();
        for (Value v : inst.getOperands()) {
            if (v instanceof Constant c
                && c.getType().isReference()
                && c.getValue() instanceof String s) {
                names.add(s);
            }
        }
        if (op == Opcode.INVOKEDYNAMIC
            && inst.getInvokedynamicData() instanceof InvokeDynamicInfo dynInfo
            && dynInfo.bootstrapMethod() != null
            && "java/lang/invoke/StringConcatFactory".equals(dynInfo.bootstrapMethod().getOwner())
            && dynInfo.bootstrapArgs().length >= 1
            && dynInfo.bootstrapArgs()[0] instanceof String recipe) {
            StringBuilder seg = new StringBuilder();
            for (int i = 0; i < recipe.length(); i++) {
                char c = recipe.charAt(i);
                if (c == '\u0001' || c == '\u0002') {
                    if (!seg.isEmpty()) {
                        names.add(seg.toString());
                        seg.setLength(0);
                    }
                } else {
                    seg.append(c);
                }
            }
            if (!seg.isEmpty()) names.add(seg.toString());
            for (int i = 1; i < dynInfo.bootstrapArgs().length; i++) {
                Object a = dynInfo.bootstrapArgs()[i];
                if (a != null) names.add(String.valueOf(a));
            }
        }
    }

    private void addIfStringConstant(Value v, Set<String> names) {
        if (v instanceof Constant c
            && c.getType().isReference()
            && c.getValue() instanceof String s) {
            names.add(s);
        }
    }

    private void collectStringConstantsFromTerminator(Terminator term, Set<String> names) {
        if (term instanceof ReturnTerminator rt) addIfStringConstant(rt.getValue(), names);
        else if (term instanceof ThrowTerminator tt) addIfStringConstant(tt.getException(), names);
        else if (term instanceof CondBranchTerminator cbt) addIfStringConstant(cbt.getCondition(), names);
        else if (term instanceof LookupSwitchTerminator lst) addIfStringConstant(lst.getKey(), names);
        else if (term instanceof TableSwitchTerminator tst) addIfStringConstant(tst.getKey(), names);
        else if (term instanceof IndirectBranchTerminator ibt) addIfStringConstant(ibt.getTargetBlock(), names);
    }

    private Set<String> collectJavaStringLiterals() {
        Set<String> result = new LinkedHashSet<>();
        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    for (Value v : inst.getOperands()) {
                        if (v instanceof Constant c
                            && c.getType().isReference()
                            && "java/lang/String".equals(c.getType().getClassName())
                            && c.getValue() instanceof String s) {
                            result.add(s);
                        }
                    }
                    if (inst.getOpcode() == Opcode.INVOKEDYNAMIC
                        && inst.getInvokedynamicData() instanceof InvokeDynamicInfo dynInfo
                        && dynInfo.bootstrapMethod() != null
                        && "java/lang/invoke/StringConcatFactory".equals(dynInfo.bootstrapMethod().getOwner())
                        && dynInfo.bootstrapArgs().length >= 1
                        && dynInfo.bootstrapArgs()[0] instanceof String recipe) {
                        StringBuilder seg = new StringBuilder();
                        for (int i = 0; i < recipe.length(); i++) {
                            char ch = recipe.charAt(i);
                            if (ch == '\u0001' || ch == '\u0002') {
                                if (!seg.isEmpty()) {
                                    result.add(seg.toString());
                                    seg.setLength(0);
                                }
                            } else {
                                seg.append(ch);
                            }
                        }
                        if (!seg.isEmpty()) result.add(seg.toString());
                        for (int i = 1; i < dynInfo.bootstrapArgs().length; i++) {
                            Object a = dynInfo.bootstrapArgs()[i];
                            if (a instanceof String s) result.add(s);
                        }
                    }
                }
                Terminator term = block.getTerminator();
                if (term != null) {
                    for (Value v : terminatorOperands(term)) {
                        if (v instanceof Constant c
                            && c.getType().isReference()
                            && "java/lang/String".equals(c.getType().getClassName())
                            && c.getValue() instanceof String s) {
                            result.add(s);
                        }
                    }
                }
            }
        }
        result.add("");
        return result;
    }

    private static List<Value> terminatorOperands(Terminator term) {
        List<Value> out = new ArrayList<>();
        if (term instanceof ReturnTerminator rt && rt.getValue() != null) out.add(rt.getValue());
        else if (term instanceof ThrowTerminator tt && tt.getException() != null) out.add(tt.getException());
        else if (term instanceof CondBranchTerminator cbt) out.add(cbt.getCondition());
        else if (term instanceof LookupSwitchTerminator lst) out.add(lst.getKey());
        else if (term instanceof TableSwitchTerminator tst) out.add(tst.getKey());
        else if (term instanceof IndirectBranchTerminator ibt) out.add(ibt.getTargetBlock());
        return out;
    }

    /**
     * Emits every Java string literal that appears anywhere in the module.
     *
     * <p>Each literal becomes two globals:</p>
     *
     * <ul>
     *   <li>{@code @strbytes_<hex>} — the {@code byte[]} that backs the
     *       {@link String}. This is a real Java array and therefore carries
     *       the canonical runtime array header, byte for byte:</li>
     * </ul>
     *
     * <pre>
     *     offset  0 : %ReflectionClass*  klass      (mirror of "[B")
     *     offset  8 : i32                length     (Java-visible byte count)
     *     offset 12 : i32                elem_size  (always 1 for byte[])
     *     offset 16 : [len+1 x i8]       payload    (bytes + NUL terminator)
     * </pre>
     *
     * <ul>
     *   <li>{@code @jstr_<hex>} — the {@link String} object itself, whose
     *       {@code value} field points at the matching {@code @strbytes_*}
     *       global, and whose {@code coder} field selects the interpretation
     *       of that array.</li>
     * </ul>
     *
     * <h2>Compact-string encoding of the payload</h2>
     *
     * <p>A {@link String} does not store characters directly. Its
     * {@code value} field is a {@code byte[]}, and the {@code coder} field
     * selects how those bytes are to be interpreted:</p>
     *
     * <ul>
     *   <li>{@code coder == 0} — <b>LATIN1</b>. One byte per character;
     *       the byte value is the character's low 8 bits. Valid only when
     *       every character of the string is in {@code U+0000..U+00FF}.</li>
     *
     *   <li>{@code coder == 1} — <b>UTF-16</b>. Two bytes per character,
     *       high byte first (big-endian), one pair per {@code char} code
     *       unit. Surrogate pairs are therefore two consecutive code units
     *       in the array, exactly as they appear in the Java {@code String}.</li>
     * </ul>
     *
     * <p>This is the same representation that {@code java.lang.String} uses
     * internally when {@code String.COMPACT_STRINGS} is {@code true} — the
     * flag that {@code String.<clinit>} installs, and that
     * {@code String.coder()} reads before returning either the byte at
     * offset 16 of the object or the UTF-16 sentinel {@code 1}. The
     * {@code @jstr_*} globals emitted below must therefore carry the same
     * {@code coder} value the corresponding {@code @strbytes_*} payload was
     * encoded with; the emitter cannot choose one and the runtime the
     * other.</p>
     *
     * <p>The {@code length} word at offset 8 of {@code @strbytes_*} is the
     * <b>byte</b> count of the payload, not the character count. That is
     * what {@code String.length()} and every other length-sensitive method
     * of {@code String} ultimately reads, via
     *
     * <pre>
     *     length() == value.length &gt;&gt; coder
     * </pre>
     *
     * For the LATIN1 branch the stored word equals the character count; for
     * the UTF-16 branch it equals twice the character count, and the
     * right-shift recovers the character count exactly.</p>
     *
     * <h2>Why the previous UTF-8 encoding was wrong</h2>
     *
     * <p>The earlier revision of this method encoded every literal as UTF-8
     * bytes and hard-coded {@code coder == 0} in {@code @jstr_*}. That made
     * the two halves of the runtime's {@code String} contract disagree
     * whenever a literal contained a character above {@code U+007F}:</p>
     *
     * <ul>
     *   <li>UTF-8 is not Latin-1, so a character such as {@code "\uFFFD"}
     *       occupies three bytes in the array while {@code coder} still
     *       claims one byte per character;</li>
     *   <li>the {@code length} word therefore reports the UTF-8 byte count
     *       (3 for {@code "\uFFFD"}), and {@code String.length()} returns
     *       {@code 3 >> 0 == 3} instead of {@code 1};</li>
     *   <li>every consumer of {@code length()}, {@code charAt()},
     *       {@code substring()}, {@code indexOf()}, {@code hashCode()} and
     *       the {@code makeConcatWithConstants} machinery is then wrong by
     *       the ratio between the UTF-8 length and the character count.</li>
     * </ul>
     *
     * <p>The concrete failure that motivated the change was a {@code
     * java.lang.IllegalArgumentException: Replacement too long} raised from
     * {@code CharsetDecoder.replaceWith} while
     * {@code javax.crypto.JceSecurity.<clinit>} was initialising: the
     * literal {@code "\uFFFD"} passed to the three-argument
     * {@code CharsetDecoder} constructor reported a length of {@code 3}
     * against a {@code maxCharsPerByte} of {@code 1.0}, and the
     * {@code len > maxCharsPerByte} check rejected it.</p>
     *
     * <p>Encoding the payload as one of the two compact-string forms
     * restores the invariant {@code length() == value.length >> coder} for
     * every literal, including the ones that contain characters above
     * {@code U+007F}, and leaves ASCII-only literals byte-identical to
     * what the previous revision emitted.</p>
     *
     * <h2>Why the backing array is emitted as an anonymous struct</h2>
     *
     * <p>The canonical layout is fixed by {@code JAVA_ARR_KLASS_OFFSET},
     * {@code JAVA_ARR_LENGTH_OFFSET}, {@code JAVA_ARR_ELEM_SIZE_OFFSET} and
     * {@code JAVA_ARR_HDR} in {@code jnative_runtime.h}, and every other
     * array-producing site in this codebase writes those exact offsets:</p>
     *
     * <p>The earlier revision of this method emitted the backing array as
     * {@code [N x i8] c"..."} with the header laid out at
     * {@code length@0 / elem_size@4 / payload@8}. That is a silent,
     * deterministic eight-byte-short layout: any load of {@code length}
     * through {@code +8} read the first four bytes of the payload instead,
     * and any load of the payload through {@code +16} read eight bytes into
     * the string. On the literal {@code "sun.nio.MaxDirectMemorySize"} the
     * "length" that {@code String.hashCode()} observed was
     * {@code 0x2e6e7573} (the ASCII bytes of {@code "sun."}), so the
     * vectorised latin1 hash loop ran for roughly 7.79e8 iterations from a
     * bogus starting index and faulted on an address ~15 MB past the end of
     * the 36-byte constant in {@code .rodata}.</p>
     *
     * <p>An anonymous struct is used rather than a hand-escaped byte string
     * because the {@code klass} word is a pointer to {@code @refclass__B}.
     * The address of that global is a link-time relocation, not a
     * compile-time byte sequence, so it cannot be spliced into a
     * {@code [N x i8]} initializer. Expressing the header as a struct lets
     * LLVM emit the relocation itself, in the right slot, with the right
     * alignment, and guarantees that the offsets the runtime reads are the
     * offsets the emitter wrote. Any future change to the array layout in
     * {@code jnative_runtime.h} must be mirrored here, in
     * {@link LlvmFunctionEmitter}, and in
     * {@link LlvmGlobalEmitter#generateReflectionData} — the comment at the
     * top of {@code JAVA_ARR_*} in {@code jnative_runtime.h} is the single
     * source of truth for all three.</p>
     */
    private String generateStringLiterals() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- String literal objects -----\n");
        stringLiteralPool.clear();

        String vtableName = vtableNames.get("java/lang/String");
        if (vtableName == null) {
            throw new IllegalStateException(
                    "vtable_java_lang_String must be emitted before string literals");
        }

        /*
         * Class mirror stored at offset 0 of every String's backing byte[].
         * "@refclass__B" is emitted unconditionally by
         * generateReflectionData() (the [B descriptor is one of the fixed
         * entries in the emitClasses set), so the forward reference from
         * these literal initializers is always resolvable at link time.
         * LLVM does not require definition-before-use for global symbol
         * references, and generateStringLiterals() runs before
         * generateReflectionData() in the generateGlobals() chain.
         */
        String byteArrayKlassRef = getRefClassGlobalName("[B");

        List<String> literals = new ArrayList<>(collectJavaStringLiterals());
        Collections.sort(literals);

        for (String s : literals) {
            /*
             * Decide which of the two compact-string encodings the literal
             * must use.
             *
             * The decision is made per character code unit, not per code
             * point. A supplementary character (U+10000..U+10FFFF) is
             * represented in the Java String as two surrogate code units
             * in the range U+DC80..U+DFFF, both of which are greater than
             * 0xFF; a string containing one therefore lands in the UTF-16
             * branch automatically, with no separate surrogate-pair test
             * required.
             *
             * A string consisting entirely of characters in U+0000..U+00FF
             * is encoded in the compact LATIN1 form: one byte per character
             * code unit, equal to the character's low 8 bits. This is the
             * same representation java.lang.String uses internally when
             * String.COMPACT_STRINGS is true.
             *
             * Every other string is encoded as UTF-16BE: two bytes per
             * character code unit, high byte first. This is the same
             * representation java.lang.String uses internally when the
             * compact-string optimisation is not in effect.
             */
            boolean latin1 = true;
            int charCount = s.length();
            for (int i = 0; i < charCount; i++) {
                if (s.charAt(i) > 0xFF) {
                    latin1 = false;
                    break;
                }
            }

            byte[] bytes;
            int coder;
            int lengthField;

            if (latin1) {
                /*
                 * Compact LATIN1 form. One byte per character code unit;
                 * the byte value is the low 8 bits of the char, which for
                 * a char <= 0xFF is the char itself.
                 */
                bytes = new byte[charCount];
                for (int i = 0; i < charCount; i++) {
                    bytes[i] = (byte) s.charAt(i);
                }
                coder = 0;
                lengthField = charCount;
            } else {
                /*
                 * UTF-16BE form. Two bytes per character code unit, high
                 * byte first. Surrogate pairs are emitted as two
                 * consecutive code units, exactly as they appear in the
                 * Java String.
                 *
                 * The charCount used here is String.length(), i.e. the
                 * number of char code units, not the number of Unicode
                 * code points. That is the number of elements that
                 * String.length() must report, and it is the number the
                 * length word below is derived from.
                 */
                bytes = new byte[charCount * 2];
                for (int i = 0; i < charCount; i++) {
                    char ch = s.charAt(i);
                    bytes[i * 2]     = (byte) (ch >>> 8);
                    bytes[i * 2 + 1] = (byte) (ch & 0xFF);
                }
                coder = 1;
                lengthField = charCount * 2;
            }

            int len = bytes.length;

            /*
             * Escape the payload for LLVM's c"..." string-literal syntax.
             * Every byte outside printable ASCII, plus backslash, double
             * quote and the common whitespace escapes, is rendered in
             * uppercase two-digit hex. A trailing NUL byte is appended so
             * that C-side consumers can treat the payload as a C string;
             * it is C-side slack, not a Java element — the `length` field
             * below is the true Java-visible byte count, and the NUL is
             * never observed by String.length(), String.charAt() or any
             * other method of String.
             */
            StringBuilder escaped = new StringBuilder();
            for (byte b : bytes) {
                int v = b & 0xFF;
                switch (v) {
                    case '\\' -> escaped.append("\\5C");
                    case '"' -> escaped.append("\\22");
                    case '\n' -> escaped.append("\\0A");
                    case '\r' -> escaped.append("\\0D");
                    case '\t' -> escaped.append("\\09");
                    default -> {
                        if (v < 0x20 || v > 0x7E) escaped.append(String.format("\\%02X", v));
                        else escaped.append((char) v);
                    }
                }
            }
            escaped.append("\\00");

            String safe = LlvmRuntime.stringIdSuffix(s);
            String bytesGlobal = "strbytes_" + safe;
            String objGlobal = "jstr_" + safe;

            /*
             * The backing byte[]. Emitted as an anonymous struct so that
             * the klass word at offset 0 can be a real pointer relocation
             * rather than compile-time bytes. LLVM lays the fields out
             * naturally (i8* @0, i32 @8, i32 @12, [N x i8] @16), which is
             * exactly the canonical Java array header:
             *
             *     JAVA_ARR_KLASS_OFFSET      = 0
             *     JAVA_ARR_LENGTH_OFFSET     = 8
             *     JAVA_ARR_ELEM_SIZE_OFFSET  = 12
             *     JAVA_ARR_HDR               = 16
             *
             * `align 8` matches the natural alignment of the i8* header
             * word; the alignment does not change the field offsets but
             * does keep the pointer slot addressable as a pointer.
             *
             * The value written into the length word is `lengthField`,
             * which is the character count in the LATIN1 branch and twice
             * the character count in the UTF-16 branch. That is the value
             * String.length() derives the Java-level length from, via
             * `value.length >> coder`.
             */
            int payloadLen = len + 1;   // NUL terminator is part of the array

            sb.append("@").append(bytesGlobal)
                    .append(" = private unnamed_addr constant { i8*, i32, i32, [")
                    .append(payloadLen).append(" x i8] } {\n")
                    .append("  i8* bitcast (%ReflectionClass* ").append(byteArrayKlassRef)
                    .append(" to i8*),\n")
                    .append("  i32 ").append(lengthField).append(",\n")
                    .append("  i32 1,\n")
                    .append("  [").append(payloadLen).append(" x i8] c\"")
                    .append(escaped).append("\"\n")
                    .append("}, align 8\n");

            /*
             * The String object. Its `value` field is a bitcast of the
             * anonymous struct pointer to i8*, matching the i8* declared
             * for that field in %struct.java_lang_String. The bitcast is
             * necessary because the struct type is anonymous and therefore
             * not nameable in the field declaration.
             *
             * The `coder` field carries the same value the payload was
             * encoded with:
             *
             *     coder == 0  ->  LATIN1  (one byte per character)
             *     coder == 1  ->  UTF-16  (two bytes per character)
             *
             * String.coder() reads it (after checking
             * @gv_java_lang_String_COMPACT_STRINGS, which String.<clinit>
             * sets to true) and String.length() applies it as the
             * right-shift amount.
             *
             * The `hash` field is left at zero so the first hashCode()
             * call computes and caches the real value lazily. The trailing
             * i1 is left false for the same reason.
             */
            sb.append("@").append(objGlobal)
                    .append(" = global %struct.java_lang_String {\n")
                    .append("  i8* bitcast (%JNativeVTable* ").append(vtableName).append(" to i8*),\n")
                    .append("  i8* bitcast ({ i8*, i32, i32, [").append(payloadLen)
                    .append(" x i8] }* @").append(bytesGlobal).append(" to i8*),\n")
                    .append("  i8 ").append(coder).append(",\n")
                    .append("  i32 0,\n")
                    .append("  i1 false\n")
                    .append("}, align 8\n");

            stringLiteralPool.add(objGlobal);
        }

        int n = stringLiteralPool.size();
        sb.append("@__jnative_literal_pool = constant [").append(n).append(" x i8*] [");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append("i8* bitcast (%struct.java_lang_String* @").append(stringLiteralPool.get(i)).append(" to i8*)");
        }
        sb.append("], align 8\n");
        sb.append("@__jnative_literal_pool_size = constant i32 ").append(n).append("\n");
        return sb.toString();
    }

    // =========================================================================
    //  Type info
    // =========================================================================

    private String generateTypeInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Type info tables -----\n");

        List<ClassNode> allClasses = new ArrayList<>(resolver.getClassMap().values());
        allClasses.sort(Comparator.comparing(ClassNode::getName));

        Map<String, Set<String>> instancesOf = new HashMap<>();

        // First pass: for every non-external class C (interfaces included), add C's
        // own vtable to instancesOf[S] for every S in C's supertype closure. This
        // includes C itself — see collectSuperclasses(), which always adds cls.getName()
        // to the accumulator.
        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;

            String className = cls.getName();
            String classVtable = vtableNames.get(className);
            if (classVtable == null) continue;

            Set<String> supertypes = new LinkedHashSet<>();
            collectSuperclasses(cls, supertypes);
            collectInterfaces(cls, supertypes);

            for (String supertype : supertypes) {
                instancesOf.computeIfAbsent(supertype, k -> new LinkedHashSet<>())
                    .add(classVtable);
            }
        }

        // Second pass: register every lambda's vtable in the type-info set of
        // every interface in its super-interface closure.
        //
        // Lambda classes are synthesised by createLambdaAdaptor() during
        // generateLambdaAdaptors() and are not part of resolver.getClassMap(),
        // so the class-driven first pass above never sees them. Without this
        // explicit registration the lambda's vtable is missing from every
        // @__type_info_* table, and `obj instanceof SomeInterface` returns
        // false even when obj genuinely is a lambda implementing that
        // interface.
        //
        // The first consumer to trip over this is
        // java.util.regex.Pattern.newCharProperty: `p instanceof
        // BmpCharPredicate` returned 0, the slower CharProperty branch was
        // taken, and that set hasSupplementary = true and made the pattern
        // compiler pick a different root node type (Slice instead of
        // SliceI). Even after the ifacemap fix above made dispatch work,
        // instanceof still has to answer correctly for the fast-path
        // selection to happen and for arbitrary user code that asks
        // `lambda instanceof AncestorInterface` to see the truth.
        for (LambdaInfo linfo : lambdaRegistry.values()) {
            if (linfo.superInterfaces == null) continue;
            String lambdaVtable = vtableNames.get(linfo.lambdaClassName);
            if (lambdaVtable == null) continue;

            for (String iface : linfo.superInterfaces) {
                instancesOf
                    .computeIfAbsent(iface, k -> new LinkedHashSet<>())
                    .add(lambdaVtable);
            }
        }

        // Emission pass: emit __type_info_X for every class X. The layout is
        //
        //     __type_info_X = [ vtable_X, <sorted subtype vtables>, null ]
        //
        // The [0] slot MUST be X's own vtable. Callers of __type_info_* use
        // position 0 to recover a class's own vtable from its name (see
        // __jnative_own_class_vtable in jnative_runtime.c and its callers in
        // Object.c / Inet4AddressImpl.c / Inet6AddressImpl.c / NetworkInterface.c).
        // Previously the generator put the alphabetically-first subtype vtable
        // there, which caused find_class_by_vtable() to mis-identify an object
        // of class MemberName as an instance of the interface java.lang.reflect.Member.
        for (ClassNode cls : allClasses) {
            if (cls.isExternal()) continue;
            if (cls.getName().startsWith("[")) continue;

            String className = cls.getName();
            String typeInfoName = "@__type_info_" + LlvmTypeMapper.sanitizeIdentifier(className);
            typeInfoNames.put(className, typeInfoName);

            Set<String> entries = instancesOf.getOrDefault(className, Collections.emptySet());
            List<String> sorted = new ArrayList<>(entries);
            Collections.sort(sorted);

            String ownVtable = vtableNames.get(className);
            if (ownVtable != null) {
                sorted.remove(ownVtable);
                sorted.addFirst(ownVtable);
            }

            sb.append(typeInfoName).append(" = constant [")
                .append(sorted.size() + 1).append(" x i8*] [");
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("i8* bitcast (%JNativeVTable* ")
                    .append(sorted.get(i)).append(" to i8*)");
            }
            if (!sorted.isEmpty()) sb.append(", ");
            sb.append("i8* null]\n");
        }

        return sb.toString();
    }

    private void collectSuperclasses(ClassNode cls, Set<String> accumulator) {
        accumulator.add(cls.getName());
        if (cls.getSuperName() != null && !cls.getSuperName().equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(cls.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                collectSuperclasses(superNode, accumulator);
            } else {
                accumulator.add("java/lang/Object");
            }
        } else {
            accumulator.add("java/lang/Object");
        }
    }

    private void collectInterfaces(ClassNode cls, Set<String> accumulator) {
        if (cls == null) return;

        for (String iface : cls.getInterfaces()) {
            if (accumulator.add(iface)) {
                ClassNode ifaceNode = resolver.getClassNode(iface);
                if (ifaceNode != null && !ifaceNode.isExternal()) {
                    collectInterfaces(ifaceNode, accumulator);
                }
            }
        }

        String superName = cls.getSuperName();
        if (superName != null
            && !superName.equals(cls.getName())
            && !superName.equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(superName);
            if (superNode != null && !superNode.isExternal()) {
                collectInterfaces(superNode, accumulator);
            }
        }
    }

    // =========================================================================
    //  Field offsets
    // =========================================================================

    public int getFieldOffset(String className, String fieldName) {
        String key = className + "." + fieldName;
        Integer cached = fieldOffsets.get(key);
        if (cached != null) {
            return cached;
        }

        // java.lang.Class is laid out by the runtime struct ReflectionClass,
        // not by the order of the fields in the class file. See
        // JAVA_LANG_CLASS_FIELD_OFFSETS and the report in fun.txt: the
        // class-file-derived layout made getName() read offset 16
        // (superclass) instead of offset 8 (name), so Class.forName() was
        // handed a Class* where it expected a String*.
        //
        // The nine named tail slots (classLoader, module, componentType,
        // packageName, enumConstants, annotationData, genericInfo,
        // reflectionData, classValueMap) and enumConstantDirectory were
        // added to the struct so that generated code under java.lang.Class
        // — checkPackageAccessForPermittedSubclasses (classLoader),
        // getEnumConstantsShared (enumConstants), enumConstantDirectory
        // (enumConstantDirectory), getAnnotation / getDeclaredAnnotations
        // (annotationData), the ReflectionData machinery (reflectionData),
        // etc. — can be emitted. Every @refclass_* constant emits null in
        // those slots, except `module`, which points at the shared
        // unnamed-module singleton from generateUnnamedModule(); see
        // jnative_runtime.h for the rationale.
        //
        // Any other java.lang.Class field the emitter has not yet seen
        // lands in the reserved tail. The reserved tail is real,
        // initialised memory: every @refclass_* constant has
        // CLASS_RESERVED_SLOT_COUNT trailing `i8* null` entries covering
        // it. Reads land in null, and writes to the same field are read
        // back correctly on the next read, so the JDK's own lazy-cache
        // idiom (test for null, compute, store, read) works without
        // forcing an ABI extension every time a new transient field is
        // introduced by the JDK. This is NOT the "silently return an
        // unrelated offset" failure mode the exception below describes:
        // the reserved tail is storage the emitted constants actually
        // have, and every slot in it is documented and null.
        if ("java/lang/Class".equals(className)) {
            Integer mapped = JAVA_LANG_CLASS_FIELD_OFFSETS.get(fieldName);
            if (mapped != null) {
                fieldOffsets.put(key, mapped);
                return mapped;
            }

            fieldOffsets.put(key, CLASS_RESERVED_OFFSET_START);
            return CLASS_RESERVED_OFFSET_START;
        }

        ClassNode cls = resolver.getClassNode(className);
        if (cls == null) {
            throw new IllegalStateException(
                "Cannot resolve class for field offset: " + className + "." + fieldName);
        }
        if (cls.isExternal()) {
            throw new IllegalStateException(
                "Cannot compute field offset for external class: " + className + "." + fieldName);
        }

        List<FieldNode> allFields = collectInstanceFields(cls);
        int offset = OBJECT_HEADER_SIZE;
        int foundOffset = -1;
        for (FieldNode f : allFields) {
            Type ft = f.getType();
            int align = fieldAlignment(ft);
            offset = (offset + align - 1) & -align;
            if (f.getName().equals(fieldName)) {
                foundOffset = offset;
            }
            offset += fieldSize(ft);
        }

        if (foundOffset < 0) {
            throw new IllegalStateException(
                "Field not found in class hierarchy: " + className + "." + fieldName);
        }
        // Duplicate writes from concurrent threads store the same value.
        fieldOffsets.put(key, foundOffset);
        return foundOffset;
    }

    private static int fieldAlignment(Type ft) {
        if (ft == null) return 8;
        if (ft.isReference() || ft.isArray()) return 8;
        if (ft == Type.LONG || ft == Type.DOUBLE) return 8;
        if (ft == Type.INT || ft == Type.FLOAT) return 4;
        if (ft == Type.SHORT || ft == Type.CHAR) return 2;
        if (ft == Type.BYTE || ft == Type.BOOLEAN) return 1;
        return 8;
    }

    private static int fieldSize(Type ft) {
        if (ft == null) return 8;
        if (ft.isReference() || ft.isArray()) return 8;
        if (ft == Type.LONG || ft == Type.DOUBLE) return 8;
        if (ft == Type.INT || ft == Type.FLOAT) return 4;
        if (ft == Type.SHORT || ft == Type.CHAR) return 2;
        if (ft == Type.BYTE || ft == Type.BOOLEAN) return 1;
        return 8;
    }

    public Type getFieldType(String className, String fieldName) {
        // The fields generated java.lang.Class code can legitimately read
        // must be typed by what the runtime struct holds, not by the class
        // file: `name` is an i8* Java String (or null, in which case getName()
        // goes through initClassName()), and `superclass` is a %ReflectionClass*
        // i.e. another java.lang.Class. The class file agrees for these two
        // (transient String name / Class superclass), but pinning them here
        // keeps the type and the offset from JAVA_LANG_CLASS_FIELD_OFFSETS in
        // one place.
        //
        // The named tail slots are typed the same way. Every reference-typed
        // slot emits an `i8*` load in LLVM regardless, so the exact reference
        // type matters only to the extent that it must be a reference type
        // (not a primitive). The classes named here are pinned to their
        // canonical JDK types so the emitted shape is stable across JDK
        // versions whose class-file declaration order might differ.
        //
        // An unrecognised java.lang.Class field (one that will land in the
        // reserved tail) is typed as Object — the widest reference type.
        // Any reference-typed field the JDK declares is assignable to
        // Object, so this never produces a type error at emission time.
        if ("java/lang/Class".equals(className)) {
            return switch (fieldName) {
                case "name" -> Type.reference("java/lang/String");
                case "superclass" -> Type.reference("java/lang/Class");
                case "classLoader" -> Type.reference("java/lang/ClassLoader");
                case "module" -> Type.reference("java/lang/Module");
                case "componentType" -> Type.reference("java/lang/Class");
                case "packageName" -> Type.reference("java/lang/String");
                case "enumConstants" -> Type.reference("java/lang/Object");
                case "annotationData" -> Type.reference("java/util/Map");
                case "genericInfo" -> Type.reference("java/lang/Object");
                case "reflectionData" -> Type.reference("java/lang/Object");
                case "classValueMap" -> Type.reference("java/lang/ClassValue");
                case "enumConstantDirectory" -> Type.reference("java/util/Map");
                default -> Type.reference("java/lang/Object");
            };
        }
        FieldNode fn = resolver.getField(className, fieldName);
        return fn != null ? fn.getType() : null;
    }

    /**
     * Exact size, in bytes, of the LLVM struct {@code %struct.<class>} for a
     * top-level class, computed by exactly the same rule that
     * {@link #getFieldOffset(String, String)} uses: an 8-byte object header,
     * then every instance field placed at {@code alignof(field)} with no
     * additional padding, and finally the whole object rounded up to 8 bytes.
     *
     * <p>Returns {@code -1} if the class is not loaded, is external, or is an
     * interface.</p>
     *
     * <p>This exists for the C runtime's {@code calloc(sizeof(Thread))} and
     * {@code calloc(sizeof(Thread$FieldHolder))}: the {@code
     * ReflectionClass.object_size} values produced by
     * {@link #generateReflectionData()} ignore alignment and therefore come
     * out smaller than the real LLVM struct size. Computing the size here is
     * the only way to get a value that agrees with the emitter without
     * duplicating the alignment rules.</p>
     */
    public int computeObjectSize(String className) {
        ClassNode cls = resolver.getClassNode(className);
        if (cls == null || cls.isExternal() || cls.isInterface()) {
            return -1;
        }
        int offset = OBJECT_HEADER_SIZE;
        for (FieldNode f : collectInstanceFields(cls)) {
            int align = fieldAlignment(f.getType());
            int size  = fieldSize(f.getType());
            offset = (offset + align - 1) & -align;
            offset += size;
        }
        return (offset + 7) & ~7;
    }

    // =========================================================================
    //  Reflection data
    // =========================================================================

    private String generateReflectionData() {
        StringBuilder sb = new StringBuilder();
        StringBuilder strConsts = new StringBuilder();
        sb.append("\n; ----- Reflection data -----\n");

        sb.append("%ReflectionMethod = type { i8*, i8*, i8*, i32 }\n");
        sb.append("%ReflectionField = type { i8*, i8*, i32, i32 }\n");
        sb.append("%ReflectionConstructor = type { i8*, i8*, i32 }\n");
        // Must stay in lockstep with `struct ReflectionClass` in
        // jnative_runtime.h and with the @refclass_* constant emission below.
        //
        // Layout:
        //   [0]  %JNativeVTable*      vtable
        //   [1]  i8*                  name (Java String cache, kept null)
        //   [2]  %ReflectionClass*    superclass
        //   [3]  %ReflectionClass**   interfaces
        //   [4]  %ReflectionMethod**  methods
        //   [5]  %ReflectionField**   fields
        //   [6]  %ReflectionConstructor** constructors
        //   [7]  i32                  modifiers
        //   [8]  i32                  objectSize
        //   [9]  i8*                  cname (const char*, internal name)
        //   [10] i8*                  classLoader           (always null)
        //   [11] i8*                  module                (always null)
        //   [12] i8*                  componentType         (always null)
        //   [13] i8*                  packageName           (always null)
        //   [14] i8*                  enumConstants         (always null)
        //   [15] i8*                  annotationData        (always null)
        //   [16] i8*                  genericInfo           (always null)
        //   [17] i8*                  reflectionData        (always null)
        //   [18] i8*                  classValueMap         (always null)
        //   [19] i8*                  enumConstantDirectory (always null)
        //   [20..27] i8*              reserved[8]           (always null)
        //
        // The nine named tail slots, enumConstantDirectory, and the eight
        // reserved slots exist because generated bytecode under
        // java.lang.Class reads them. checkPackageAccessForPermitted
        // Subclasses reads classLoader, getEnumConstantsShared reads
        // enumConstants, enumConstantDirectory reads/writes
        // enumConstantDirectory, getAnnotation / getDeclaredAnnotations
        // read annotationData, and the ReflectionData machinery reads
        // reflectionData. The reserved tail is the fallback landing pad
        // for any further Class field the emitter has not enumerated yet
        // — see getFieldOffset() for the routing and the class-level
        // javadoc on ReflectionClass in jnative_runtime.h for the
        // rationale.
        sb.append("%ReflectionClass = type { %JNativeVTable*, i8*, %ReflectionClass*, %ReflectionClass**, "
            + "%ReflectionMethod**, %ReflectionField**, %ReflectionConstructor**, i32, i32, i8*, "
            + "i8*, i8*, i8*, i8*, i8*, i8*, i8*, i8*, i8*, i8*, "
            + "i8*, i8*, i8*, i8*, i8*, i8*, i8*, i8* }\n");
        sb.append("%JNativeSymbolClassEntry = type { i8*, %ReflectionClass* }\n");

        String classVtableRef = vtableNames.get("java/lang/Class");
        if (classVtableRef == null) {
            throw new IllegalStateException(
                "vtable_java_lang_Class must be emitted before reflection data "
                    + "(Class literal objects need a valid %JNativeVTable* in word[0])");
        }

        Set<String> emitClasses = new TreeSet<>();

        for (String cls : resolver.getClassMap().keySet()) {
            if (cls == null || cls.isEmpty()) continue;
            if (cls.charAt(0) == '[') continue;
            emitClasses.add(cls);
        }

        if (reflectInfo != null) {
            for (String cls : reflectInfo.getAllClasses()) {
                if (cls == null || cls.isEmpty()) continue;
                emitClasses.add(cls);
            }
        }

        // ------------------------------------------------------------------
        // Primitive class mirrors.
        //
        // See PRIMITIVE_TYPE_NAMES for the full rationale. In short:
        // Class.getPrimitiveClass(String) resolves its argument through
        // reflect_all_classes[]; the nine primitive names must be present in
        // that array, otherwise every <primitive-wrapper>.TYPE static field
        // ends up NULL and MethodType.changeReturnType(Void.TYPE) produces
        // the "rtype == null" MethodType that crashes the VarHandle
        // bootstrap at program start-up.
        //
        // The mirrors are added unconditionally rather than gated on the
        // reachability walk having seen a particular wrapper's <clinit>,
        // because a getPrimitiveClass call can originate from any class:
        // the JDK's reflective Field.getType / Method.getReturnType /
        // Class.getComponentType paths all reach it, and those paths are
        // not visible to the build-time reachability analysis.
        // ------------------------------------------------------------------
        emitClasses.addAll(PRIMITIVE_TYPE_NAMES);

        // ------------------------------------------------------------------
        // Array mirrors.
        //
        // Every NEW_ARRAY and MULTI_NEW_ARRAY in the module writes a
        // @refclass_* mirror of the array being allocated into the array
        // header at offset 0. If no mirror is emitted for that descriptor,
        // the generated code references an undefined global and LLVM
        // rejects the module — so the descriptors are collected by walking
        // the module's own instructions rather than guessed.
        // ------------------------------------------------------------------
        for (Function func : module.getFunctions()) {
            for (BasicBlock block : func.getBlocks()) {
                for (Instruction inst : block.getInstructions()) {
                    if (inst.getOpcode() == Opcode.NEW_ARRAY
                        && inst.getOperands().size() >= 2
                        && inst.getOperands().get(1) instanceof Constant c) {
                        String desc = arrayDescriptorForElemName(c.getValue().toString());
                        if (desc != null) emitClasses.add(desc);
                    } else if (inst.getOpcode() == Opcode.MULTI_NEW_ARRAY
                        && !inst.getOperands().isEmpty()
                        && inst.getOperands().get(0) instanceof Constant c) {
                        // A multi-dimensional descriptor such as "[[I"
                        // needs a mirror for every dimension, because
                        // __jnative_new_multi_array() stamps the klass of
                        // each sub-array separately.
                        String d = c.getValue().toString();
                        for (int i = 0; i < d.length() && d.charAt(i) == '['; i++) {
                            emitClasses.add(d.substring(i));
                        }
                    }
                }
            }
        }

        // Types the runtime constructs unconditionally, whether or not the
        // compiled program allocates one: String's backing byte[], the
        // main(String[]) argument array, the Class[] built by
        // Class.getInterfaces0(), and the reference arrays built by
        // jnative_ref_array_of_class() / jnative_array_alloc().
        emitClasses.add("[Z"); emitClasses.add("[B");
        emitClasses.add("[S"); emitClasses.add("[C");
        emitClasses.add("[I"); emitClasses.add("[J");
        emitClasses.add("[F"); emitClasses.add("[D");
        emitClasses.add("[Ljava/lang/Object;");
        emitClasses.add("[Ljava/lang/String;");
        emitClasses.add("[Ljava/lang/Class;");
        // The result types of Class.getDeclaredFields0 / getDeclaredMethods0
        // / getDeclaredConstructors0. The native side builds them through
        // jnative_ref_array_of_class, which stamps the array's klass mirror
        // from these descriptors; without the mirror the array's word 0 stays
        // null and Object.getClass() on it fails.
        emitClasses.add("[Ljava/lang/reflect/Field;");
        emitClasses.add("[Ljava/lang/reflect/Method;");
        emitClasses.add("[Ljava/lang/reflect/Constructor;");

        List<String> classNames = new ArrayList<>();
        for (String cn : emitClasses) {
            boolean isArrayPseudoClass = cn.charAt(0) == '[';
            boolean isPrimitive = PRIMITIVE_TYPE_NAMES.contains(cn);

            if (isPrimitive) {
                // No ClassNode is required (or available) for a primitive:
                // there is no .class file, no bytecode, no superclass, no
                // interfaces, no methods, no fields and no constructors.
                // Everything the emission loop needs is synthesised there
                // from PRIMITIVE_TYPE_NAMES and PRIMITIVE_CLASS_MODIFIERS.
                classNames.add(cn);
                continue;
            }

            if (isArrayPseudoClass) {
                // Same reasoning, extended to array descriptors such as
                // "[I": there is no .class file to find, so asking the
                // resolver for one would only install an external stub
                // (and a spurious "class not found" warning) in classMap.
                // The mirror is synthesised entirely by the emission loop.
                classNames.add(cn);
                continue;
            }

            ClassNode node = resolver.getClassNode(cn);

            if (node == null) {
                continue;
            }

            if (node.isExternal() || resolver.getClassBytes(cn) == null) {
                resolver.forceLoadSystemClass(cn);
                node = resolver.getClassNode(cn);
                if (node == null) {
                    continue;
                }

                if (node.isExternal() || resolver.getClassBytes(cn) == null) {
                    continue;
                }
            }

            classNames.add(cn);
        }
        Collections.sort(classNames);

        Map<String, String> classVarNames = new HashMap<>();
        for (String className : classNames) {
            classVarNames.put(className, getRefClassGlobalName(className));
        }

        List<String> classPtrs = new ArrayList<>();

        for (String className : classNames) {
            boolean isPrimitive = PRIMITIVE_TYPE_NAMES.contains(className);
            boolean isArrayPseudoClass = className.charAt(0) == '[';
            ClassNode classNode = (isPrimitive || isArrayPseudoClass)
                ? null
                : resolver.getClassNode(className);

            boolean isReflectClass = !isPrimitive
                && reflectInfo != null
                && reflectInfo.getClassInfoMap().containsKey(className);

            ReflectClassInfo info;
            if (isReflectClass) {
                info = reflectInfo.getClassInfoMap().get(className);
                if (classNode != null && !classNode.isExternal() && !isArrayPseudoClass) {
                    Map<String, FieldNode> mostDerivedByName = new LinkedHashMap<>();
                    for (FieldNode f : collectInstanceFields(classNode)) {
                        if ((f.getAccess() & Opcodes.ACC_STATIC) != 0) continue;
                        mostDerivedByName.put(f.getName(), f);
                    }
                    for (FieldNode f : mostDerivedByName.values()) {
                        info.addField(new FieldReference(
                            className, f.getName(), f.getDescriptor()));
                    }
                }
            } else {
                info = new ReflectClassInfo(className);
            }

            // Primitives have no superclass and no interfaces. Leaving
            // info.getSuperName() null and info.getInterfaces() empty is
            // sufficient: every downstream consumer of both already handles
            // the null / empty case (superClassPtr falls back to "null", the
            // interfaces array is emitted with just the null terminator).
            //
            // Arrays are the exception. There is no class file to read a
            // superclass from, but JLS §4.10.3 fixes the answer: an array
            // type is a direct subtype of Object, Cloneable and
            // Serializable. The superclass is set explicitly here and the
            // interfaces are cleared, because the reflection table may
            // already have carried entries that no longer apply.
            if (isArrayPseudoClass) {
                info.setSuperName("java/lang/Object");
                info.getInterfaces().clear();
            } else if (!isPrimitive) {
                String superName = (classNode != null) ? classNode.getSuperName() : null;
                if (info.getSuperName() == null && superName != null) {
                    info.setSuperName(superName);
                }

                List<String> classInterfaces =
                    (classNode != null) ? classNode.getInterfaces() : Collections.emptyList();
                if (info.getInterfaces().isEmpty() && !classInterfaces.isEmpty()) {
                    info.getInterfaces().addAll(classInterfaces);
                }
            }

            String cleanClassName = LlvmTypeMapper.sanitizeIdentifier(className);
            String classVarName = "@refclass_" + cleanClassName;

            /*
             * object_size is the single number the C runtime uses for
             * every allocation of this class: jnative_alloc_object(),
             * Unsafe.allocateInstance(), and every per-class native
             * that builds a mirror or a value object from C. It must
             * match sizeof(%struct.<class>) byte-for-byte, because
             * generated code writes instance fields at the offsets
             * returned by getFieldOffset() (which applies the natural
             * alignment of every field type), and the C side writes the
             * same fields at the same offsets into the buffer
             * object_size describes. If object_size is smaller than the
             * LLVM struct, the last field of any class that has an
             * alignment gap lies past the end of the allocation and the
             * C-side value is read out of adjacent heap metadata.
             *
             * The previous computation — header plus the unaligned sum
             * of field sizes — was correct only for classes whose
             * fields happen to have no internal padding. It was wrong
             * by 4 bytes for every class that declares an int or float
             * immediately after a reference or long/double, and that
             * includes ArrayList, LinkedList, StringBuilder, Hashtable,
             * IdentityHashMap, Vector, Stack and many more. On a freshly
             * calloc'ed chunk the four stray bytes happen to be zero
             * (the allocator returned a virgin arena page) and the bug
             * is invisible; on a recycled chunk they hold the previous
             * occupant's prev_size field, the last field reads a stale
             * non-zero value, and the field's reader takes the wrong
             * branch.
             *
             * The concrete failure this fix closes:
             *
             *     java.time.zone.TzdbZoneRulesProvider.load was replaced
             *     by a C override that installs an empty ArrayList into
             *     the provider's regionIds field. ZoneRulesProvider's
             *     registration loop then called provideZoneIds(), which
             *     is new HashSet<>(regionIds). AbstractCollection.addAll
             *     iterates that ArrayList; ArrayList.Itr.hasNext reads
             *     `cursor != size`; size lives at offset 24 of a
             *     24-byte allocation. The read returned a non-zero byte
             *     from the adjacent glibc chunk header, hasNext()
             *     returned true, next() was called, and the very next
             *     instruction — elementData.length with elementData
             *     still null — raised
             *
             *         NullPointerException: Cannot invoke
             *             java.util.ArrayList.Itr.next(Ljava/lang/Object;)
             *             because <array> is null
             *
             *     inside the <clinit> of ZoneRulesProvider.
             *
             * computeObjectSize(className) is the canonical,
             * alignment-aware size computation. It is the same function
             * LlvmGenerator.generateMain() already uses to publish the
             * Thread, Thread$FieldHolder and ThreadGroup sizes to the C
             * runtime, so it is the single source of truth for struct
             * sizes in this codebase. Routing object_size through it
             * makes the emitted constant agree with the LLVM struct
             * layout for every class, without duplicating the alignment
             * rules a second time.
             *
             * Primitives and array pseudo-classes do not have an emitted
             * LLVM struct of their own and are never allocated through
             * jnative_alloc_object() or Unsafe.allocateInstance();
             * arrays go through jnative_array_alloc(), which uses
             * JAVA_ARR_HDR rather than object_size. For those the header
             * size is the only meaningful value, matching the previous
             * behaviour.
             */
            int objectSize;
            if (isPrimitive || isArrayPseudoClass || classNode == null) {
                objectSize = OBJECT_HEADER_SIZE;
            } else {
                int computed = computeObjectSize(className);
                if (computed > 0) {
                    objectSize = computed;
                } else {
                    /*
                     * computeObjectSize returns -1 only when the class
                     * is not in the class map, is external, or is an
                     * interface. The branch above already excludes the
                     * interface and pseudo-class cases, and the
                     * earlier loop in this method has already filtered
                     * out classes that are absent or external, so this
                     * fallback is unreachable under normal operation.
                     *
                     * It exists so that a future change to the filtering
                     * above cannot silently reintroduce the alignment
                     * bug by handing a value that computeObjectSize
                     * refuses to compute to the naive sum instead. The
                     * fallback performs the same alignment-aware walk
                     * inline, using the same fieldAlignment / fieldSize
                     * helpers that computeObjectSize and getFieldOffset
                     * use internally, so it cannot disagree with either.
                     */
                    objectSize = OBJECT_HEADER_SIZE;
                    for (FieldNode f : collectInstanceFields(classNode)) {
                        Type ft = f.getType();
                        int align = fieldAlignment(ft);
                        int size  = fieldSize(ft);
                        objectSize = (objectSize + align - 1) & -align;
                        objectSize += size;
                    }
                    objectSize = (objectSize + 7) & ~7;
                    log.warn("computeObjectSize returned {} for class {}; "
                        + "falling back to inline alignment-aware size {}",
                        computed, className, objectSize);
                }
            }

            // ---- Methods ----
            List<MethodReference> sortedMethods = new ArrayList<>(info.getMethods());
            sortedMethods.sort(Comparator.comparing(MethodReference::toString));
            List<String> methodPtrs = new ArrayList<>();
            for (MethodReference method : sortedMethods) {
                String methodName = method.getName();
                String desc = method.getDescriptor();

                // ------------------------------------------------------------------
                // Null / empty descriptor guard.
                //
                // A MethodReference can reach this point with a null or empty
                // descriptor through several paths:
                //
                //   - a MethodNode synthesised by the reflection machinery
                //     (ReachabilityAnalysis.applyMetadata, or a reflective
                //     Class.getDeclaredMethod / MethodHandles.Lookup.findXxx
                //     handler) whose descriptor slot was never populated;
                //
                //   - a metadata-driven entry whose method name was matched
                //     against the class file's methods by name only, so the
                //     synthesised MethodReference carries a placeholder
                //     descriptor that is later emptied by one of the
                //     comparator / dedup passes above;
                //
                //   - a caller that constructed MethodReference directly
                //     with a null descriptor (the @Value-generated
                //     constructor does not validate).
                //
                // In every case, emitting the reference as-is produces an
                // @refmethod_* constant whose descriptor slot is the LLVM
                // literal `null`. Class.create_method_mirror then passes
                // that null to build_parameter_types_array, which returns
                // null, and the resulting java.lang.reflect.Method has a
                // null parameterTypes field.
                //
                // The JDK iterates parameterTypes without a null check in
                // several places — the canonical one being
                // Executable.sharedToString, which the Method.toString /
                // Method.toGenericString family, the access-check error
                // messages in sun.invoke.util.VerifyAccess, and every
                // Method.appendTo() diagnostic all funnel through. The
                // observable symptom is
                //
                //   java.lang.NullPointerException: Cannot invoke
                //   java.lang.reflect.Executable.sharedToString(...)
                //   because <array> is null
                //
                // raised from a <clinit> that stringifies a Method mirror —
                // MethodHandleImpl$CountingWrapper.<clinit> was the first
                // to reach it on a full JDK closure, because its
                // LOOKUP.findStatic path builds a mirror whose descriptor
                // slot came in empty.
                //
                // Substituting "()V" here does not make the missing
                // descriptor correct — it makes the emitted @refmethod_*
                // constant structurally valid, so the mirror's
                // parameterTypes slot becomes an empty Class[] rather than
                // null and every consumer on the Java side sees a
                // well-defined empty argument list instead of dereferencing
                // null. The warning names the entry so the producer can be
                // fixed at its source.
                // ------------------------------------------------------------------
                if (desc == null || desc.isEmpty()) {
                    log.warn("reflection table entry with null/empty descriptor: "
                            + "{}.{} [{}] — emitting \"()V\" placeholder so the "
                            + "reflective Method mirror does not carry a null "
                            + "parameterTypes array (see Executable.sharedToString)",
                        className, methodName, method);
                    desc = "()V";
                }

                String mangledSuffix = methodName + "_"
                    + desc.replaceAll("[^a-zA-Z0-9_]", "_");
                String adaptorName = "__reflect_adaptor_" + cleanClassName + "_" + mangledSuffix;

                Function impl = module.getFunction(
                    LlvmRuntime.mangleMethod(className, methodName, desc));
                String adaptorPtr = "i8* null";
                if (impl != null && impl.getEntryBlock() != null) {
                    sb.append(emitAdaptorForMethod(className, method));
                    adaptorPtr = "i8* bitcast (i8* (i8*, i8**)* @"
                        + adaptorName + " to i8*)";
                }

                int modifiers = 0;
                MethodNode mn = (classNode != null)
                    ? findMethod(classNode, methodName, desc)
                    : null;
                if (mn != null) modifiers = mn.getAccess();

                String methodVar = "@refmethod_" + cleanClassName + "_" + mangledSuffix;
                sb.append(methodVar).append(" = constant %ReflectionMethod { i8* ")
                    .append(ensureStringConstantPtr(strConsts, methodName))
                    .append(", i8* ").append(ensureStringConstantPtr(strConsts, desc))
                    .append(", ").append(adaptorPtr)
                    .append(", i32 ").append(modifiers).append(" }\n");
                methodPtrs.add("i8* bitcast (%ReflectionMethod* " + methodVar + " to i8*)");
            }
            String methodsArray = "@refmethods_" + cleanClassName;
            appendNullTerminatedPtrArray(sb, methodsArray, methodPtrs);

            // ---- Fields ----
            List<FieldReference> sortedFields = new ArrayList<>(info.getFields());
            {
                Map<String, FieldReference> byName = new LinkedHashMap<>();
                for (FieldReference fr : sortedFields) {
                    FieldReference existing = byName.get(fr.getName());
                    if (existing == null) {
                        byName.put(fr.getName(), fr);
                        continue;
                    }
                    FieldNode mostDerived = (classNode != null)
                        ? resolver.getField(className, fr.getName())
                        : null;
                    if (mostDerived != null
                        && mostDerived.getDescriptor() != null
                        && mostDerived.getDescriptor().equals(fr.getDescriptor())) {
                        byName.put(fr.getName(), fr);
                    }
                }
                sortedFields = new ArrayList<>(byName.values());
            }
            sortedFields.sort(Comparator.comparing(FieldReference::toString));
            List<String> fieldPtrs = new ArrayList<>();
            for (FieldReference field : sortedFields) {
                String fieldName = field.getName();
                String desc = field.getDescriptor();
                int modifiers = 0;
                FieldNode fn = resolver.getField(className, fieldName);
                if (fn != null) {
                    modifiers = fn.getAccess();
                    // The resolver's FieldNode is authoritative for the
                    // descriptor: it comes from the class file itself,
                    // whereas a FieldReference sourced from native-image
                    // metadata may carry no descriptor at all, or a stale
                    // one. Only fall back to the reference when the node
                    // is unavailable.
                    //
                    // This matters because the descriptor is what
                    // Class.getDeclaredFields0 hands to
                    // descriptor_to_class_mirror(), which builds the
                    // `type` slot of the java.lang.reflect.Field. A wrong
                    // descriptor produced, for example,
                    // @reffield_java_util_concurrent_ForkJoinPool_poolIds
                    // with "I" for a long field.
                    if (fn.getDescriptor() != null && !fn.getDescriptor().isEmpty()) {
                        desc = fn.getDescriptor();
                    } else if (desc == null) {
                        desc = fn.getDescriptor();
                    }
                }

                int offset = 0;
                if (fn != null && desc != null && (modifiers & Opcodes.ACC_STATIC) == 0) {
                    try {
                        offset = getFieldOffset(className, fieldName);
                    } catch (IllegalStateException ignored) {
                        offset = 0;
                    }
                }

                String fieldVar = "@reffield_" + cleanClassName + "_" + fieldName;
                sb.append(fieldVar).append(" = constant %ReflectionField { i8* ")
                    .append(ensureStringConstantPtr(strConsts, fieldName))
                    .append(", i8* ")
                    .append(desc != null ? ensureStringConstantPtr(strConsts, desc) : "null")
                    .append(", i32 ").append(offset)
                    .append(", i32 ").append(modifiers).append(" }\n");
                fieldPtrs.add("i8* bitcast (%ReflectionField* " + fieldVar + " to i8*)");
            }
            String fieldsArray = "@reffields_" + cleanClassName;
            appendNullTerminatedPtrArray(sb, fieldsArray, fieldPtrs);

            // ---- Constructors ----
            List<MethodReference> sortedCtors = new ArrayList<>(info.getConstructors());
            sortedCtors.sort(Comparator.comparing(MethodReference::toString));
            List<String> ctorPtrs = new ArrayList<>();
            for (MethodReference ctor : sortedCtors) {
                String desc = ctor.getDescriptor();
                // Same null-descriptor guard as for methods above. A null
                // constructor descriptor would give
                // java.lang.reflect.Constructor a null parameterTypes
                // array, and Constructor.sharedToString iterates it
                // without a null check just as Method.sharedToString does.
                if (desc == null || desc.isEmpty()) {
                    log.warn("reflection table entry with null/empty constructor "
                            + "descriptor: {} [{}] — emitting \"()V\" placeholder",
                        className, ctor);
                    desc = "()V";
                }
                String mangledDesc = desc.replaceAll("[^a-zA-Z0-9_]", "_");
                String adaptorName = "__reflect_adaptor_ctor_"
                    + cleanClassName + "_" + mangledDesc;

                Function impl = module.getFunction(
                    LlvmRuntime.mangleMethod(className, "<init>", desc));
                String adaptorPtr = "i8* null";
                if (impl != null && impl.getEntryBlock() != null) {
                    sb.append(emitAdaptorForConstructor(className, ctor, objectSize));
                    adaptorPtr = "i8* bitcast (i8* (i8**)* @"
                        + adaptorName + " to i8*)";
                }

                int modifiers = 0;
                MethodNode mn = (classNode != null)
                    ? findMethod(classNode, "<init>", desc)
                    : null;
                if (mn != null) modifiers = mn.getAccess();

                String ctorVar = "@refctor_" + cleanClassName + "_" + mangledDesc;
                sb.append(ctorVar).append(" = constant %ReflectionConstructor { i8* ")
                    .append(ensureStringConstantPtr(strConsts, desc))
                    .append(", ").append(adaptorPtr)
                    .append(", i32 ").append(modifiers).append(" }\n");
                ctorPtrs.add("i8* bitcast (%ReflectionConstructor* " + ctorVar + " to i8*)");
            }
            String ctorsArray = "@refctors_" + cleanClassName;
            appendNullTerminatedPtrArray(sb, ctorsArray, ctorPtrs);

            // ---- Superclass / interface pointer arrays ----
            //
            // An array mirror points at java/lang/Object as its superclass
            // (see the JLS note above); every other class points at its
            // declared superclass, except Object itself, whose superclass
            // slot stays null.
            String superClassPtr = "null";
            if (isArrayPseudoClass) {
                String objVar = classVarNames.get("java/lang/Object");
                if (objVar != null) superClassPtr = objVar;
            } else if (info.getSuperName() != null
                && !info.getSuperName().equals("java/lang/Object")) {
                String superVar = classVarNames.get(info.getSuperName());
                if (superVar != null) superClassPtr = superVar;
            }
            List<String> ifacePtrs = new ArrayList<>();
            List<String> sortedIfaces = new ArrayList<>(info.getInterfaces());
            Collections.sort(sortedIfaces);
            for (String iface : sortedIfaces) {
                String ifaceVar = classVarNames.get(iface);
                if (ifaceVar != null) ifacePtrs.add(ifaceVar);
            }
            String ifacesArray = "@refifaces_" + cleanClassName;
            sb.append(ifacesArray).append(" = constant [")
                .append(ifacePtrs.size() + 1).append(" x %ReflectionClass*] [");
            for (String p : ifacePtrs) sb.append("%ReflectionClass* ").append(p).append(", ");
            sb.append("%ReflectionClass* null]\n");

            // ---- Component type ----
            //
            // Offset 88 of %ReflectionClass backs the Java field
            // Class.componentType, which is what Class.getComponentType()
            // returns. An array class must answer with the class of its
            // elements — "[I" -> int, "[Ljava/lang/String;" ->
            // java/lang/String, "[[I" -> "[I" — and every other class
            // answers null. A null here makes
            // arr.getClass().getComponentType() return null and the
            // reflection code that walks arrays fail.
            String componentTypePtr = "null";
            if (isArrayPseudoClass) {
                String compName = componentNameOfArrayDescriptor(className);
                if (compName != null) {
                    String compVar = classVarNames.get(compName);
                    if (compVar != null) componentTypePtr = compVar;
                }
            }

            // ---- The class object itself ----
            //
            // For primitives and arrays the access flags are the fixed
            // JLS §20.1 set (public final abstract) — array classes are
            // implicitly final and their modifiers are not derivable from
            // any class file; for everything else they come from the
            // ClassNode that the resolver loaded. Primitives and arrays
            // have no ClassNode, so the branch is mandatory here rather
            // than cosmetic.
            int accessFlags;
            if (isPrimitive || isArrayPseudoClass) {
                accessFlags = PRIMITIVE_CLASS_MODIFIERS;
            } else {
                accessFlags = (classNode != null) ? classNode.getAccess() : 0;
            }

            // =================================================================
            //  MUTABLE GLOBAL — NOT `constant`.
            //
            // java.lang.Class is a live Java object whose own bytecode stores
            // into a number of lazily-computed cache fields on the first read
            // of each. Those fields are slots inside %ReflectionClass, which
            // means the struct itself must live in writable memory. Emitting
            // it as `constant` places the object in .rodata; the first such
            // cache store then takes a hardware write barrier and the process
            // dies with a SIGSEGV against a read-only page.
            //
            // The failure that motivated this change:
            //
            //     java.lang.invoke.MethodHandles.Lookup.findVarHandle
            //         -> resolveOrFail
            //             -> checkSymbolicClass
            //                 -> VerifyAccess.isClassAccessible
            //                     -> Class.getPackageName()
            //                         -> this.packageName = <computed>;
            //
            // Class.getPackageName() writes to the field at offset 96
            // ("packageName" in JAVA_LANG_CLASS_FIELD_OFFSETS) on the very
            // first call. When the mirror was emitted as `constant`, that
            // store faulted with SIGSEGV at the address of the mirror's
            // packageName slot inside .rodata. The crash surfaced while
            // ConcurrentSkipListMap.<clinit> was resolving its VarHandle
            // fields at the tail of the InetAddress.<clinit> chain.
            //
            // Every field below at offsets 8, 88, 96, 104, 112, 128, 136 and
            // 144 is a write target of some java.lang.Class method:
            //
            //     offset  8   name                  -> Class.getName()
            //     offset 88   componentType         -> Class.getComponentType()
            //     offset 96   packageName           -> Class.getPackageName()
            //     offset104   enumConstants         -> Class.getEnumConstantsShared()
            //     offset144   enumConstantDirectory -> Class.enumConstantDirectory()
            //     offset112   annotationData        -> Class.annotationData()
            //     offset128   reflectionData        -> Class.ReflectionData path
            //     offset136   classValueMap         -> ClassValue
            //
            // The struct is a compiled-in descriptor. Java code cannot reach
            // the vtable, cname, methods, fields, or constructors slots
            // because they lie outside the range of any declared Java field
            // of java.lang.Class — those slots are only addressed by the C
            // runtime, which reads them. Making the whole object writable
            // therefore does not expose anything the JDK does not already
            // write to through the fields above.
            //
            // Compare with the sibling emissions: @refmethod_*, @reffield_*,
            // @refctor_*, @refifaces_*, @vtable_*, @ifacemap_* and
            // @__type_info_* are metadata that no Java method writes to, and
            // they remain `constant`. The two `i8* null` name caches above
            // this struct — @strbytes_* and @jstr_* — are also read-only by
            // construction (a String's contents never change after
            // construction), so they too remain `constant`.
            // =================================================================
            sb.append(classVarName).append(" = global %ReflectionClass { %JNativeVTable* ")
                .append(classVtableRef)
                // name — always null. The bytecode of Class.getName() reads
                // this field, tests it for null and falls back to the native
                // initClassName(), which builds the String from ->cname
                // (Class.initClassName is itself `private native`, so nothing
                // ever writes this field back — and it could not: the constant
                // lives in read-only .data.rel.ro). Emitting a cached String
                // here would also mean one heap String per class mirror.
                .append(", i8* null")
                .append(", %ReflectionClass* ").append(superClassPtr)
                .append(", %ReflectionClass** ").append(ifacesArray)
                .append(", %ReflectionMethod** ").append(methodsArray)
                .append(", %ReflectionField** ").append(fieldsArray)
                .append(", %ReflectionConstructor** ").append(ctorsArray)
                .append(", i32 ").append(accessFlags)
                .append(", i32 ").append(objectSize)
                // cname — the internal name as a C string. This is what the
                // native files search reflect_all_classes[] with and what they
                // turn into @__type_info_* symbol names.
                .append(", i8* ").append(ensureStringConstantPtr(strConsts, className))
                // Nine named tail slots, enumConstantDirectory, and the eight
                // reserved slots. All null except `module` and, on an array
                // class, `componentType`. Generated bytecode
                // under java.lang.Class reads each of them from some code path
                // the reachability walk pulls in unconditionally
                // (checkPackageAccessForPermittedSubclasses reads classLoader,
                // getEnumConstantsShared reads enumConstants, the
                // enumConstantDirectory() method reads/writes
                // enumConstantDirectory, getAnnotation /
                // getDeclaredAnnotations read annotationData, the
                // ReflectionData machinery reads reflectionData, …). This
                // runtime has no user class loaders, no array component types on
                // non-array classes, no enum-constant cache, no annotation
                // metadata store, and no ClassValue registry, so null is the
                // truthful value and matches the corresponding native
                // accessors. `module` is the exception: it points at the shared
                // unnamed-module singleton from generateUnnamedModule(), because
                // null there makes every Class.getResourceAsStream NPE on
                // `thisModule.isNamed()`. `componentType` is the other
                // exception: an array class points at the mirror of its element
                // class, which is what Class.getComponentType() must return.
                // The reserved slots are the landing
                // pad for any further Class field the emitter has not
                // enumerated — see getFieldOffset() and the struct javadoc in
                // jnative_runtime.h.
                .append(", i8* null")  // [10] classLoader
                .append(", i8* bitcast (%struct.java_lang_Module* "
                    + "@jnative_unnamed_module to i8*)")  // [11] module
                .append(", i8* ").append(componentTypePtr)  // [12] componentType
                .append(", i8* null")  // [13] packageName
                .append(", i8* null")  // [14] enumConstants
                .append(", i8* null")  // [15] annotationData
                .append(", i8* null")  // [16] genericInfo
                .append(", i8* null")  // [17] reflectionData
                .append(", i8* null")  // [18] classValueMap
                .append(", i8* null")  // [19] enumConstantDirectory
                .append(", i8* null")  // [20] reserved[0]
                .append(", i8* null")  // [21] reserved[1]
                .append(", i8* null")  // [22] reserved[2]
                .append(", i8* null")  // [23] reserved[3]
                .append(", i8* null")  // [24] reserved[4]
                .append(", i8* null")  // [25] reserved[5]
                .append(", i8* null")  // [26] reserved[6]
                .append(", i8* null")  // [27] reserved[7]
                .append(" }\n");

            classPtrs.add("%ReflectionClass* " + classVarName);
        }

        sb.append("@reflect_all_classes = constant [")
            .append(classPtrs.size() + 1).append(" x %ReflectionClass*] [");
        for (String p : classPtrs) sb.append(p).append(", ");
        sb.append("%ReflectionClass* null]\n");

        sb.append(generateSymbolClassMap(strConsts, classVarNames));
        sb.append(generateCallerSensitiveSymbols(strConsts));

        sb.append(strConsts);
        return sb.toString();
    }

    private String generateSymbolClassMap(StringBuilder strConsts,
                                          Map<String, String> classVarNames) {
        TreeMap<String, String> map = new TreeMap<>();

        for (ClassNode cn : resolver.getClassMap().values()) {
            if (cn.isExternal()) continue;
            String className = cn.getName();
            if (className == null || className.isEmpty()) continue;
            if (className.charAt(0) == '[') continue;
            if (!classVarNames.containsKey(className)) continue;

            for (MethodNode mn : cn.getMethods()) {
                String base = LlvmRuntime.mangleMethod(
                    className, mn.getName(), mn.getDescriptor());

                Function baseFunc = module.getFunction(base);
                if (baseFunc != null && baseFunc.getEntryBlock() != null) {
                    map.put(base, className);
                }

                String nativeSym = "__jnative_" + base;
                Function nativeFunc = module.getFunction(nativeSym);
                if (nativeFunc != null && nativeFunc.getEntryBlock() != null) {
                    map.put(nativeSym, className);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Symbol-to-class map (for Reflection.getCallerClass) -----\n");

        if (map.isEmpty()) {
            sb.append("@jnative_symbol_class_map = constant [1 x %JNativeSymbolClassEntry] ["
                + "%JNativeSymbolClassEntry { i8* null, %ReflectionClass* null }]\n");
            sb.append("@jnative_symbol_class_map_size = constant i64 0\n");
            return sb.toString();
        }

        sb.append("@jnative_symbol_class_map = constant [")
            .append(map.size() + 1)
            .append(" x %JNativeSymbolClassEntry] [\n");
        for (Map.Entry<String, String> e : map.entrySet()) {
            String sym = e.getKey();
            String cls = e.getValue();
            String clsVar = classVarNames.get(cls);
            sb.append("  %JNativeSymbolClassEntry { i8* ")
                .append(ensureStringConstantPtr(strConsts, sym))
                .append(", %ReflectionClass* ").append(clsVar)
                .append(" },\n");
        }
        sb.append("  %JNativeSymbolClassEntry { i8* null, %ReflectionClass* null }\n");
        sb.append("]\n");
        sb.append("@jnative_symbol_class_map_size = constant i64 ")
            .append(map.size()).append("\n");
        return sb.toString();
    }

    private String generateCallerSensitiveSymbols(StringBuilder strConsts) {
        TreeSet<String> symbols = new TreeSet<>();

        for (ClassNode cn : resolver.getClassMap().values()) {
            if (cn.isExternal()) continue;
            String className = cn.getName();
            if (className == null || className.isEmpty()) continue;
            if (className.charAt(0) == '[') continue;

            for (MethodNode mn : cn.getMethods()) {
                if (!mn.isCallerSensitive()) continue;

                String base = LlvmRuntime.mangleMethod(
                    className, mn.getName(), mn.getDescriptor());

                Function baseFunc = module.getFunction(base);
                if (baseFunc != null && baseFunc.getEntryBlock() != null) {
                    symbols.add(base);
                }

                String nativeSym = "__jnative_" + base;
                Function nativeFunc = module.getFunction(nativeSym);
                if (nativeFunc != null && nativeFunc.getEntryBlock() != null) {
                    symbols.add(nativeSym);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n; ----- Caller-sensitive symbols (for Reflection.getCallerClass) -----\n");

        if (symbols.isEmpty()) {
            sb.append("@jnative_caller_sensitive_symbols = constant [1 x i8*] [i8* null]\n");
            sb.append("@jnative_caller_sensitive_symbols_size = constant i64 0\n");
            return sb.toString();
        }

        sb.append("@jnative_caller_sensitive_symbols = constant [")
            .append(symbols.size() + 1).append(" x i8*] [\n");
        for (String sym : symbols) {
            sb.append("  i8* ").append(ensureStringConstantPtr(strConsts, sym)).append(",\n");
        }
        sb.append("  i8* null\n]\n");
        sb.append("@jnative_caller_sensitive_symbols_size = constant i64 ")
            .append(symbols.size()).append("\n");
        return sb.toString();
    }

    private void appendNullTerminatedPtrArray(StringBuilder sb, String arrayName, List<String> ptrs) {
        sb.append(arrayName).append(" = constant [")
            .append(ptrs.size() + 1).append(" x i8*] [");
        for (String ptr : ptrs) sb.append(ptr).append(", ");
        sb.append("i8* null]\n");
    }

    /**
     * Maps the element-type operand of NEW_ARRAY to the JVM array
     * descriptor of the array being allocated, so that a mirror exists
     * for the {@code @refclass_*} pointer that NEW_ARRAY stores in the
     * array header. Primitives map to their single-letter code, an
     * already-array element type keeps its descriptor and gains one
     * dimension, and a class internal name is wrapped in {@code L...;}.
     *
     * @return the array descriptor, or {@code null} when the element name
     *         is unusable
     */
    private static String arrayDescriptorForElemName(String elemName) {
        if (elemName == null || elemName.isEmpty()) return null;
        switch (elemName) {
            case "boolean": return "[Z";
            case "byte":    return "[B";
            case "short":   return "[S";
            case "char":    return "[C";
            case "int":     return "[I";
            case "long":    return "[J";
            case "float":   return "[F";
            case "double":  return "[D";
        }
        if (elemName.startsWith("[")) return "[" + elemName;
        return "[L" + elemName + ";";
    }

    /**
     * Returns the name of the class an array descriptor has as its
     * component type, as a key of {@code classVarNames} — either a class
     * internal name, a primitive name, or (for a multi-dimensional array)
     * the descriptor of the inner array class.
     *
     * <p>"[I" yields "int", "[Ljava/lang/String;" yields
     * "java/lang/String", "[[I" yields "[I", and "java/lang/Object"
     * yields {@code null}.
     */
    private static String componentNameOfArrayDescriptor(String desc) {
        if (desc == null || desc.length() < 2 || desc.charAt(0) != '[') return null;
        String inner = desc.substring(1);
        if (inner.startsWith("[")) return inner;
        if (inner.startsWith("L") && inner.endsWith(";")) {
            return inner.substring(1, inner.length() - 1);
        }
        if (inner.length() == 1) {
            return switch (inner.charAt(0)) {
                case 'Z' -> "boolean";
                case 'B' -> "byte";
                case 'S' -> "short";
                case 'C' -> "char";
                case 'I' -> "int";
                case 'J' -> "long";
                case 'F' -> "float";
                case 'D' -> "double";
                default  -> null;
            };
        }
        return null;
    }

    private String ensureStringConstant(StringBuilder defsBuffer, String s) {
        if (s == null) return "null";
        if (emittedStringConstants.add(s)) {
            defsBuffer.append(LlvmRuntime.typeStringConstant(s));
        }
        return LlvmRuntime.typeStringGlobalName(s);
    }

    private String ensureStringConstantPtr(StringBuilder defsBuffer, String s) {
        if (s == null) return "null";
        if (emittedStringConstants.add(s)) {
            defsBuffer.append(LlvmRuntime.typeStringConstant(s));
        }
        int len = LlvmRuntime.typeStringArrayLength(s);
        String g = LlvmRuntime.typeStringGlobalName(s);
        return "getelementptr inbounds ([" + len + " x i8], [" + len + " x i8]* "
            + g + ", i32 0, i32 0)";
    }

    private String emitAdaptorForMethod(String className, MethodReference method) {
        String methodName = method.getName();
        String desc = method.getDescriptor();
        String origFuncName = LlvmRuntime.mangleMethod(className, methodName, desc);
        String adaptorName = "__reflect_adaptor_"
            + LlvmTypeMapper.sanitizeIdentifier(className) + "_"
            + methodName + "_" + desc.replaceAll("[^a-zA-Z0-9_]", "_");

        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);
        Type retType = TypeResolver.descToReturnType(desc);

        MethodNode mn = findMethod(resolver.getClassNode(className), methodName, desc);
        boolean isStatic = mn != null && mn.isStatic();

        StringBuilder sb = new StringBuilder();
        sb.append("define i8* @").append(adaptorName).append("(i8* %obj, i8** %args) {\n");

        List<String> argLoads = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            Type pt = paramTypes.get(i);
            String ptLlvm = LlvmTypeMapper.toLlvmType(pt);
            String addr = "%arg" + i + "_addr";
            String val = "%arg" + i + "_val";
            sb.append("  ").append(addr)
                .append(" = getelementptr i8*, i8** %args, i32 ").append(i).append("\n");
            if (pt.isReference() || pt.isArray() || pt.isNull() || pt.isBlock()) {
                sb.append("  ").append(val).append(" = load i8*, i8** ")
                    .append(addr).append("\n");
            } else {
                String ptr = "%arg" + i + "_ptr";
                sb.append("  ").append(ptr).append(" = bitcast i8** ")
                    .append(addr).append(" to ").append(ptLlvm).append("*\n");
                sb.append("  ").append(val).append(" = load ").append(ptLlvm)
                    .append(", ").append(ptLlvm).append("* ").append(ptr).append("\n");
            }
            argLoads.add(val);
        }

        StringBuilder argsCsv = new StringBuilder();
        boolean firstArg = true;
        if (!isStatic) {
            argsCsv.append("i8* %obj");
            firstArg = false;
        }
        for (int i = 0; i < argLoads.size(); i++) {
            if (!firstArg) argsCsv.append(", ");
            argsCsv.append(LlvmTypeMapper.toLlvmType(paramTypes.get(i)))
                .append(" ").append(argLoads.get(i));
            firstArg = false;
        }

        String retLlvm = LlvmTypeMapper.toLlvmType(retType);
        if (retType.isVoid()) {
            sb.append("  call void @").append(origFuncName)
                .append("(").append(argsCsv).append(")\n");
            sb.append("  ret i8* null\n");
        } else {
            sb.append("  %result = call ").append(retLlvm).append(" @")
                .append(origFuncName).append("(").append(argsCsv).append(")\n");
            if (retType.isReference() || retType.isArray()
                || retType.isNull() || retType.isUnknown()) {
                sb.append("  %ret_ptr = bitcast ").append(retLlvm)
                    .append(" %result to i8*\n");
                sb.append("  ret i8* %ret_ptr\n");
            } else {
                sb.append("  %mem = call i8* @calloc(i64 1, i64 ")
                    .append(getElementSizeOfType(retType)).append(")\n");
                sb.append("  %cast = bitcast i8* %mem to ").append(retLlvm).append("*\n");
                sb.append("  store ").append(retLlvm).append(" %result, ")
                    .append(retLlvm).append("* %cast\n");
                sb.append("  ret i8* %mem\n");
            }
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String emitAdaptorForConstructor(String className, MethodReference ctor, int objectSize) {
        String desc = ctor.getDescriptor();
        String origFuncName = LlvmRuntime.mangleMethod(className, "<init>", desc);
        String adaptorName = "__reflect_adaptor_ctor_"
            + LlvmTypeMapper.sanitizeIdentifier(className) + "_"
            + desc.replaceAll("[^a-zA-Z0-9_]", "_");

        List<Type> paramTypes = TypeResolver.descToParamTypes(desc);

        StringBuilder sb = new StringBuilder();
        sb.append("define i8* @").append(adaptorName).append("(i8** %args) {\n");
        sb.append("  %obj = call i8* @calloc(i64 1, i64 ").append(objectSize).append(")\n");

        String vtableName = getVtableName(className);
        if (vtableName != null) {
            sb.append("  %vtable = bitcast %JNativeVTable* ").append(vtableName)
                .append(" to i8*\n");
            sb.append("  %vtable_slot = bitcast i8* %obj to i8**\n");
            sb.append("  store i8* %vtable, i8** %vtable_slot\n");
        }

        List<String> argLoads = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            Type pt = paramTypes.get(i);
            String ptLlvm = LlvmTypeMapper.toLlvmType(pt);
            String addr = "%arg" + i + "_addr";
            String val = "%arg" + i + "_val";
            sb.append("  ").append(addr)
                .append(" = getelementptr i8*, i8** %args, i32 ").append(i).append("\n");
            if (pt.isReference() || pt.isArray() || pt.isNull() || pt.isBlock()) {
                sb.append("  ").append(val).append(" = load i8*, i8** ")
                    .append(addr).append("\n");
            } else {
                String ptr = "%arg" + i + "_ptr";
                sb.append("  ").append(ptr).append(" = bitcast i8** ")
                    .append(addr).append(" to ").append(ptLlvm).append("*\n");
                sb.append("  ").append(val).append(" = load ").append(ptLlvm)
                    .append(", ").append(ptLlvm).append("* ").append(ptr).append("\n");
            }
            argLoads.add(val);
        }

        StringBuilder argsCsv = new StringBuilder();
        argsCsv.append("i8* %obj");
        for (int i = 0; i < argLoads.size(); i++) {
            argsCsv.append(", ")
                .append(LlvmTypeMapper.toLlvmType(paramTypes.get(i)))
                .append(" ").append(argLoads.get(i));
        }
        sb.append("  call void @").append(origFuncName)
            .append("(").append(argsCsv).append(")\n");
        sb.append("  ret i8* %obj\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private MethodNode findMethod(ClassNode classNode, String name, String descriptor) {
        if (classNode == null || classNode.isExternal()) return null;
        for (MethodNode m : classNode.getMethods()) {
            if (m.getName().equals(name) && m.getDescriptor().equals(descriptor)) return m;
        }
        if (classNode.getSuperName() != null && !classNode.getSuperName().equals("java/lang/Object")) {
            ClassNode superNode = resolver.getClassNode(classNode.getSuperName());
            if (superNode != null && !superNode.isExternal()) {
                return findMethod(superNode, name, descriptor);
            }
        }
        return null;
    }

    public void ensureInterfaceRegistered(String ifaceName) {
        if (ifaceName == null || ifaceName.isEmpty()) return;

        ClassNode cn = resolver.getClassNode(ifaceName);
        if (cn == null || cn.isExternal() || cn.getMethods().isEmpty()) {
            resolver.forceLoadSystemClass(ifaceName);
            cn = resolver.getClassNode(ifaceName);
        }
        if (cn == null || !cn.isInterface()) return;

        prepareLayouts();
        interfaceLayouts.remove(ifaceName);

        synchronized (this) {
            if (!interfaceIds.containsKey(ifaceName)) {
                interfaceIds.put(ifaceName, nextInterfaceId++);
                totalInterfaces = nextInterfaceId;
            }
            getOrBuildInterfaceLayout(ifaceName);
        }
    }

    public void ensureClassLayoutBuilt(String className) {
        if (className == null || className.isEmpty()) return;

        ClassNode cn = resolver.getClassNode(className);
        if (cn == null || cn.isExternal()) {
            resolver.forceLoadSystemClass(className);
            cn = resolver.getClassNode(className);
        }
        if (cn == null || cn.isInterface()) return;

        prepareLayouts();
        synchronized (this) {
            classLayouts.remove(className);
            getOrBuildClassLayout(className);
        }
    }
}