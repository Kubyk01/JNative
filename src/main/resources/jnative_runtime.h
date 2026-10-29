/*
 * jnative_runtime.h
 *
 * Shared ABI for the JNative C runtime and every per-class native
 * support file under src/main/resources/jnative/.
 *
 * -----------------------------------------------------------------------
 * What this header is for
 * -----------------------------------------------------------------------
 *
 * Every native file that ships with this runtime used to carry its own
 * copy of the same boilerplate:
 *
 *   - the seven reflection / dispatch structs that the LLVM emitter
 *     writes into the compiled image (see LlvmGlobalEmitter's
 *     generateReflectionData, emitLambdaVtables, generateVtables);
 *
 *   - the same constants for the Java object header, the Java array
 *     header, and the FileDescriptor layout;
 *
 *   - the same inline helpers for looking a class up in
 *     reflect_all_classes[], resolving a class's @__type_info_* global
 *     through dlsym, allocating a Java object with a valid vtable,
 *     building a Java String from a C string, and building a Java
 *     byte[] / Object[] / int[] from raw data;
 *
 *   - the same forward declarations for the runtime's exception
 *     helpers, string interning, vtable/itable lookup, and monitor
 *     entry/exit.
 *
 * The duplication was not just noise. Because each file defined its own
 * copy of, say, ReflectionClass, the layouts were only kept in sync by
 * hand. Consolidating the definitions here makes the struct layout a
 * single, reviewable artifact, and makes the contract with the LLVM
 * emitter explicit rather than implicit.
 *
 * -----------------------------------------------------------------------
 * How this header is deployed
 * -----------------------------------------------------------------------
 *
 *   Source location : src/main/resources/jnative_runtime.h
 *   Runtime location: <tempdir>/jnative_runtime.h
 *
 * Compiler.extractRuntimeSource() extracts jnative_runtime.c to
 * <tempdir>/jnative_runtime.c; the same extraction step copies this
 * file to <tempdir>/jnative_runtime.h. Because every per-class native
 * file lands at <tempdir>/<package-path>.c, the build command must
 * gain an `-I<tempdir>` so the quoted include below resolves from any
 * subdirectory:
 *
 *     #include "jnative_runtime.h"
 *
 * -----------------------------------------------------------------------
 * ABI contract with the LLVM emitter
 * -----------------------------------------------------------------------
 *
 * The struct definitions below MUST match, byte for byte, the LLVM type
 * definitions emitted by LlvmGlobalEmitter:
 *
 *     C struct                     LLVM type
 *     ------------------------     --------------------------------
 *     ReflectionField              %ReflectionField       { i8*, i8*, i32, i32 }
 *     ReflectionMethod             %ReflectionMethod      { i8*, i8*, i8*, i32 }
 *     ReflectionConstructor        %ReflectionConstructor { i8*, i8*, i32 }
 *     ReflectionClass              %ReflectionClass       { %JNativeVTable*, i8*, ..., i8* }
 *     JNativeIfaceMapEntry         %JNativeIfaceMapEntry  { i32, i8** }
 *     JNativeIfaceMap              %JNativeIfaceMap       { i32, %JNativeIfaceMapEntry* }
 *     JNativeVTable                %JNativeVTable         { i8**, %JNativeIfaceMap*, i8* }
 *     SymbolClassEntry             %JNativeSymbolClassEntry { i8*, %ReflectionClass* }
 *
 * -----------------------------------------------------------------------
 * java.lang.Class layout
 * -----------------------------------------------------------------------
 *
 * Object layout for java.lang.Class is fixed by ReflectionClass (see
 * below) and is the single source of truth for both the C runtime and
 * the generated LLVM code. Java class-file field order is NOT used for
 * Class — LlvmGlobalEmitter.getFieldOffset() consults
 * JAVA_LANG_CLASS_FIELD_OFFSETS instead of collectInstanceFields for
 * every field of Class. In particular:
 *
 *     name                     @ +8    (Java-String | null)
 *     superclass               @ +16   (%ReflectionClass*)
 *     interfaces               @ +24
 *     ...
 *     cname                    @ +64   (C string; used by natives)
 *     classLoader              @ +72   (always null)
 *     module                   @ +80   (@jnative_unnamed_module)
 *     componentType            @ +88   (always null)
 *     packageName              @ +96   (always null)
 *     enumConstants            @ +104  (always null)
 *     annotationData           @ +112  (always null)
 *     genericInfo              @ +120  (always null)
 *     reflectionData           @ +128  (always null)
 *     classValueMap            @ +136  (always null)
 *     enumConstantDirectory    @ +144  (always null)
 *     reserved[8]              @ +152..+216 (always null)
 *
 * The C-only field `cname` is the const char* that every native file
 * under the jnative directory subtree uses for class-name comparisons,
 * symbol mangling, and reflection-table lookups. The Java-visible slot
 * `name` is a java.lang.String or null — the generated Class.getName()
 * reads it, falls back to initClassName() when null, and returns it to
 * Java.
 *
 * The nine named tail slots and the eight reserved slots exist because
 * generated bytecode under java.lang.Class reads them. The reachability
 * walk pulls in every method of Class, including:
 *
 *   - checkPackageAccessForPermittedSubclasses()  reads classLoader
 *   - getEnumConstantsShared()                    reads enumConstants
 *   - enumConstantDirectory()                     reads/writes
 *                                                 enumConstantDirectory
 *   - getAnnotation() / getDeclaredAnnotations()  read annotationData
 *   - the ReflectionData machinery                reads reflectionData
 *   - getModule()                                 reads module
 *   - getComponentType()                          reads componentType
 *
 * Every slot is emitted as `i8* null` in every @refclass_* constant,
 * with one exception: `module` points at the shared unnamed-module
 * singleton @jnative_unnamed_module. This runtime has no user class
 * loaders, no module layer, no array component types on non-array
 * classes, no pre-cached enum constant arrays, no annotation metadata
 * store, no per-class value map, and no class-value registry. Null is
 * the truthful value and matches the behaviour of the corresponding
 * native accessors (Class.getClassLoader -> NULL, Class.getComponent
 * Type -> NULL on a non-array class, Class.getEnumConstantsShared ->
 * null until the JDK's own reflection code populates the array).
 *
 * `module` cannot be null. Class.getModule() is a plain read of that
 * slot, and Class.getResourceAsStream dereferences the result without
 * a null test (`thisModule.isNamed()`), so a null slot makes every such
 * call die with an NPE. The JDK's unnamed module is exactly a Module
 * with all fields zeroed (name == null, loader == null, no
 * descriptor), which is the state Module.isNamed() tests and the state
 * that steers Class.getResourceAsStream into its "unnamed module"
 * branch. LlvmGlobalEmitter.generateUnnamedModule() emits that object
 * as a writable global; every @refclass_* mirror points at it.
 *
 * That object is emitted as a writable `global`, not as a `constant`.
 * The JDK's own module bootstrap (`System.initPhase2` ->
 * `ModuleBootstrap.boot` -> `ModuleLayer.defineModules`) takes it back
 * out of Object.class.getModule() as the `java.base` module and writes
 * its `reads` / `exports` / `opens` fields, and later its
 * `name` / `loader` / `descriptor` fields. Emitting it as a `constant`
 * places the object in `.data.rel.ro`, which RELRO maps read-only, and
 * the first store then takes a SIGSEGV (SEGV_ACCERR at offset 0x30)
 * inside `java.lang.Module.defineModules`. The writable emission is
 * what keeps the JDK's module bootstrap working on every JDK release
 * this runtime targets.
 *
 * The eight reserved slots are a stable landing pad for any
 * java.lang.Class field the emitter has not enumerated yet. An
 * unknown Class field read via getFieldOffset() maps to the first
 * reserved slot, which is guaranteed to be null. That keeps the ABI
 * honest (a real, initialised, documented slot exists) without
 * forcing an ABI extension every time the JDK adds another transient
 * cache field. Reads land in reserved, null-initialised memory —
 * never in unrelated bytes — and writes land there too, so the JDK's
 * own lazy-cache idiom (test for null, compute, store, read back)
 * behaves correctly on the very first call and on every call after.
 *
 * Adding a field: extend the struct here, extend the %ReflectionClass
 * LLVM type and the @refclass_* constant in
 * LlvmGlobalEmitter.generateReflectionData(), and add an entry to
 * JAVA_LANG_CLASS_FIELD_OFFSETS. All three must agree byte-for-byte.
 *
 * -----------------------------------------------------------------------
 * Java object layout
 * -----------------------------------------------------------------------
 *
 * Every Java object begins with an 8-byte header:
 *
 *     offset 0 : void*   vtable  (pointer to struct JNativeVTable)
 *     offset 8 : first declared instance field
 *
 * -----------------------------------------------------------------------
 * Java array layout
 * -----------------------------------------------------------------------
 *
 * Every Java array begins with a 16-byte header, followed by the payload:
 *
 *     offset 0 : ReflectionClass* klass (component-type mirror)
 *     offset 8 : int32_t length        (number of elements)
 *     offset 12: int32_t element_size  (bytes per element)
 *     offset 16: payload
 *
 * The `klass` word is what makes an array look like a real object to
 * the reflection paths: Object.getClass() recognises it by finding the
 * pointer in reflect_all_classes[] instead of treating it as a vtable,
 * and __jnative_instanceof() resolves it against the array's universal
 * supertypes. Every allocation — from generated NEW_ARRAY, from
 * MULTI_NEW_ARRAY, and from the C-side helpers below — goes through
 * jnative_array_alloc(), which is the single place that writes klass,
 * length and elem_size.
 *
 * -----------------------------------------------------------------------
 * Reflect-mirror field offsets are NOT hard-coded
 * -----------------------------------------------------------------------
 *
 * The offsets of the fields inside java.lang.reflect.Field, Method and
 * Constructor are JDK-version-dependent. On some JDK 21 builds
 * `parameterTypes` is declared on java.lang.reflect.Method itself, on
 * others it is declared on the superclass java.lang.reflect.Executable;
 * that single difference shifts every field of Method by one slot and
 * silently mis-routes every write into the mirror.
 *
 * The concrete failure that motivated removing the hard-coded offsets:
 * with a hand-frozen METHOD layout that assumed `parameterTypes` lived
 * at +72, Class.getDeclaredMethods0 wrote the (valid, non-null) empty
 * parameter-types array at offset 72 of the freshly-created Method
 * object, while the JDK read `parameterTypes` from a different offset
 * and observed null. The NPE was raised later, inside
 * Executable.sharedToString, when a <clinit> in
 * java.lang.invoke.MethodHandleImpl$CountingWrapper stringified a
 * mirror. The C write had succeeded; it just landed in the wrong byte.
 *
 * To make the layout independent of the JDK, the numeric offsets are
 * no longer #defines in this header. They are declared extern const here
 * and defined as LLVM `constant i32` globals in _module.ll by
 * LlvmGlobalEmitter.generateLayoutConstants(), which computes them with
 * the same getFieldOffset() the LLVM emitter used to lay out every
 * object. The values therefore exist from process start — there is no
 * start-up hand-off, and -1 means "this JDK does not declare that field".
 *
 * Every consumer that used to write JNATIVE_METHOD_PARAM_TYPES_OFFSET
 * (a #define) now reads an extern const int32_t of the same name; the
 * syntax is identical, so no source file outside this header needed to
 * change.
 *
 * -----------------------------------------------------------------------
 * <clinit> identity preservation
 * -----------------------------------------------------------------------
 *
 * The LLVM backend is free to tail-call the last instruction of a
 * <clinit> body. When it does, the body's frame disappears from the
 * native stack, and Reflection.getCallerClass() can no longer identify
 * the class whose initializer is on the current dynamic extent — the
 * symptom being the IllegalCallerException that
 * ClassLoader.registerAsParallelCapable raises when the caller is
 * misidentified.
 *
 * To make that identification independent of frame layout, the
 * runtime maintains a per-thread stack of currently-executing <clinit>
 * class names. The stack is pushed by __jnative_clinit_enter() exactly
 * when it grants the calling thread the right to run the class body,
 * and popped by __jnative_clinit_exit() after the body has returned.
 *
 * __jnative_current_clinit_class() exposes the top of that stack to the
 * reflection walk in jnative/jdk/internal/reflect/Reflection.c, which
 * consults it whenever it encounters a lazy-<clinit> wrapper frame.
 * Even with the body's frame gone, the correct class name is recovered
 * from the stack.
 */

#ifndef JNATIVE_RUNTIME_H
#define JNATIVE_RUNTIME_H

#include "jnative_platform.h"

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#if defined(_WIN32)
#  include "jnative_dlfcn_win32.h"
#else
#  include <dlfcn.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ========================================================================
 *  Portability shims
 *
 *  JN_NORETURN / JN_WEAK / JN_UNUSED are the platform-selected
 *  constructs (declspec on MSVC, __attribute__ on GCC/Clang). They are
 *  defined once in jnative_platform.h. The three JNATIVE_* aliases
 *  below are the only place this header introduces those names.
 *
 *  An earlier revision aliased JNATIVE_NORETURN / JNATIVE_WEAK /
 *  JNATIVE_UNUSED twice — once to the JN_* forms, once directly to the
 *  __attribute__ forms. The second alias shadowed the first and
 *  produced -Wmacro-redefined on every translation unit that includes
 *  this header. The #ifndef guards below mean the header is safe to
 *  include any number of times, in any order relative to
 *  jnative_platform.h.
 * ======================================================================== */

#ifndef JNATIVE_NORETURN
#  define JNATIVE_NORETURN JN_NORETURN
#endif
#ifndef JNATIVE_WEAK
#  define JNATIVE_WEAK     JN_WEAK
#endif
#ifndef JNATIVE_UNUSED
#  define JNATIVE_UNUSED   JN_UNUSED
#endif

#ifndef RTLD_DEFAULT
#  define RTLD_DEFAULT ((void*)0)
#endif

/* ========================================================================
 *  Layout constants
 * ======================================================================== */

#define OBJECT_HEADER_SIZE   8

/* ------------------------------------------------------------------
 *  Java array layout
 *
 *  offset  0 : ReflectionClass* klass   (mirror of the component type,
 *                                        written on every allocation)
 *  offset  8 : int32_t         length
 *  offset 12 : int32_t         elem_size
 *  offset 16 : payload
 *
 *  Offsets 0..15 must match exactly what the following three sites
 *  write and what every load site reads:
 *
 *    - LlvmFunctionEmitter.NEW_ARRAY / MULTI_NEW_ARRAY / ALOAD /
 *      ASTORE / ARRAYLENGTH / emitBoundsCheck
 *    - LlvmGlobalEmitter.generateReflectionData (array mirrors)
 *    - LlvmGlobalEmitter.generateStringLiterals (backing byte[] of
 *      every String literal — emitted as an anonymous struct
 *      { i8*, i32, i32, [N x i8] })
 *
 *  and what the C helpers in this header (jnative_array_alloc and its
 *  inline wrappers) produce. Any change here must be applied to all
 *  three Java sites synchronously, or the runtime will silently read
 *  length/payload from the wrong offsets on whichever array flavour
 *  was missed.
 * ------------------------------------------------------------------ */
#define JAVA_ARR_KLASS_OFFSET      0
#define JAVA_ARR_LENGTH_OFFSET     8
#define JAVA_ARR_ELEM_SIZE_OFFSET 12
#define JAVA_ARR_HDR              16

/* FileDescriptor layout: vtable at 0, raw kernel fd at 8, handle at 16. */
#define FILE_HEADER_SIZE     8
#define FD_OFFSET            8

/* Element sizes for the array factories below. */
#define JNATIVE_ELEM_SIZE_BYTE      1
#define JNATIVE_ELEM_SIZE_SHORT     2
#define JNATIVE_ELEM_SIZE_INT       4
#define JNATIVE_ELEM_SIZE_LONG      8
#define JNATIVE_ELEM_SIZE_REFERENCE 8

/* ========================================================================
 *  Reflection metadata structures
 * ======================================================================== */

typedef struct ReflectionField       ReflectionField;
typedef struct ReflectionMethod      ReflectionMethod;
typedef struct ReflectionConstructor ReflectionConstructor;
typedef struct ReflectionClass       ReflectionClass;
typedef struct SymbolClassEntry      SymbolClassEntry;
typedef struct JNativeIfaceMapEntry  JNativeIfaceMapEntry;
typedef struct JNativeIfaceMap       JNativeIfaceMap;
typedef struct JNativeVTable         JNativeVTable;

struct ReflectionField {
    void* name;         /* const char* — internal or binary field name */
    void* descriptor;   /* const char* — JVM descriptor, e.g. "I"    */
    int   offset;       /* byte offset from the object's base         */
    int   modifiers;    /* JVM access flags (ACC_*)                   */
};

struct ReflectionMethod {
    void* name;         /* const char* — method name                  */
    void* descriptor;   /* const char* — JVM method descriptor        */
    void* adaptor;      /* i8* (i8* obj, i8** args) * reflect trampoline */
    int   modifiers;    /* JVM access flags                            */
};

struct ReflectionConstructor {
    void* descriptor;   /* const char* — JVM method descriptor        */
    void* adaptor;      /* i8* (i8** args) * reflect trampoline       */
    int   modifiers;    /* JVM access flags                            */
};

/*
 * ReflectionClass mirrors %ReflectionClass emitted by
 * LlvmGlobalEmitter.generateReflectionData. The field order and types
 * are frozen by the ABI: any change here must be applied to
 * LlvmGlobalEmitter's emission synchronously.
 *
 * Field                     Offset  Type                 Notes
 * ------------------------- ------- -------------------- -------------------
 * vtable                     +0     %JNativeVTable*      Java object header
 * name                       +8     i8* (Java String)    null -> initClassName()
 * superclass                 +16    ReflectionClass*     NULL for Object
 * interfaces                 +24    ReflectionClass**    NULL-terminated
 * methods                    +32    ReflectionMethod**   NULL-terminated
 * fields                     +40    ReflectionField**    NULL-terminated
 * constructors               +48    ReflectionConstructor** NULL-terminated
 * modifiers                  +56    i32                  JVM access flags
 * object_size                +60    i32                  sizeof instance
 * cname                      +64    const char*          C-string internal name
 * class_loader               +72    void*                always NULL
 * module                     +80    void*                @jnative_unnamed_module
 * component_type             +88    void*                always NULL
 * package_name               +96    void*                always NULL
 * enum_constants             +104   void*                always NULL
 * annotation_data            +112   void*                always NULL
 * generic_info               +120   void*                always NULL
 * reflection_data            +128   void*                always NULL
 * class_value_map            +136   void*                always NULL
 * enum_constant_directory    +144   void*                always NULL
 * reserved[8]                +152..+216                always NULL
 *
 * The nine named tail slots and the eight reserved slots exist because
 * generated bytecode under java.lang.Class reads them. The reachability
 * walk pulls in every method of Class, including:
 *
 *   - checkPackageAccessForPermittedSubclasses()  reads classLoader
 *   - getEnumConstantsShared()                    reads enumConstants
 *   - enumConstantDirectory()                     reads/writes
 *                                                 enumConstantDirectory
 *   - getAnnotation() / getDeclaredAnnotations()  read annotationData
 *   - the ReflectionData machinery                reads reflectionData
 *   - getModule()                                 reads module
 *   - getComponentType()                          reads componentType
 *
 * Every slot is emitted as `i8* null` in every @refclass_* constant,
 * with one exception: `module` points at the shared unnamed-module
 * singleton @jnative_unnamed_module. The runtime has no user class
 * loaders, no module layer, no component types on non-array classes, no
 * enum-constant cache, no annotation metadata store, and no ClassValue
 * registry. Null is the truthful value and matches the corresponding
 * native accessors.
 *
 * `module` cannot be null: Class.getModule() is a plain read of that
 * slot and Class.getResourceAsStream dereferences the result without a
 * null test. See the class-level javadoc above.
 *
 * The eight reserved slots give a stable landing pad to any
 * java.lang.Class field the emitter has not enumerated yet. An unknown
 * Class field read via getFieldOffset() maps to the first reserved
 * slot, which is guaranteed to be null. Reads land in reserved,
 * initialised, documented memory — never in unrelated bytes — and
 * writes land there too, so the JDK's own lazy-cache idiom (test for
 * null, compute, store, read back) behaves correctly on the very first
 * call and on every call after. This keeps the ABI honest without
 * forcing an extension every time the JDK adds another transient
 * cache field.
 */
struct ReflectionClass {
    void* vtable;                                       /* +0    %JNativeVTable* */
    void* name;                                         /* +8    Java-String|NULL */
    ReflectionClass*        superclass;                 /* +16                   */
    ReflectionClass**       interfaces;                 /* +24                   */
    ReflectionMethod**      methods;                    /* +32                   */
    ReflectionField**       fields;                     /* +40                   */
    ReflectionConstructor** constructors;               /* +48                   */
    int                     modifiers;                  /* +56                   */
    int                     object_size;                /* +60                   */
    const char*             cname;                      /* +64   C string        */
    void*                   class_loader;               /* +72   always NULL     */
    void*                   module;                     /* +80   unnamed module  */
    void*                   component_type;             /* +88   always NULL     */
    void*                   package_name;               /* +96   always NULL     */
    void*                   enum_constants;             /* +104  always NULL     */
    void*                   annotation_data;            /* +112  always NULL     */
    void*                   generic_info;               /* +120  always NULL     */
    void*                   reflection_data;            /* +128  always NULL     */
    void*                   class_value_map;            /* +136  always NULL     */
    void*                   enum_constant_directory;    /* +144  always NULL     */
    void*                   reserved[8];                /* +152..+216 always NULL */
};

struct SymbolClassEntry {
    const char*      symbol;
    ReflectionClass* cls;
};

struct JNativeIfaceMapEntry {
    int32_t id;
    void**  itable;
};

struct JNativeIfaceMap {
    int32_t               count;
    JNativeIfaceMapEntry* entries;
};

struct JNativeVTable {
    void**           methods;
    JNativeIfaceMap* ifacemap;
    const char*      name;
};

/* ========================================================================
 *  java.lang.reflect.{Field,Method,Constructor} field offsets
 * ========================================================================
 *
 * These are the byte offsets, inside the runtime mirror object, of the
 * fields that Class.c writes when it constructs a
 * java.lang.reflect.{Field,Method,Constructor} from the emitter's
 * metadata, and that Unsafe.c reads back when it answers
 * Unsafe.objectFieldOffset / staticFieldOffset.
 *
 * They are emitted as LLVM `constant i32` globals, not #defines: the JDK
 * declaration order of these three classes is not stable across
 * releases — on some JDK 21 builds `parameterTypes` is declared on
 * java.lang.reflect.Method itself, on others it is declared on the
 * superclass java.lang.reflect.Executable. That single difference shifts
 * every field of Method by one slot. When the offset was hard-coded and
 * the layout assumption was wrong, the C side wrote a valid, non-null
 * empty parameter-types array at the wrong byte of the freshly-
 * allocated mirror, Java read `parameterTypes` from a different byte
 * and observed null, and the NPE was raised much later inside
 * Executable.sharedToString when a <clinit> stringified the mirror.
 *
 * The values come from LlvmGlobalEmitter.generateLayoutConstants(),
 * which computes them with the same getFieldOffset() the LLVM emitter
 * used to lay out every object, and emits them into _module.ll's .rodata.
 * They are therefore valid for the whole process lifetime; -1 means "this
 * JDK does not declare that field" and any consumer that dereferences a
 * -1 offset without testing it is making a genuine programming error.
 *
 * Consumers use them exactly as if they were the previous #defines:
 * the syntax is unchanged, only the storage class differs.
 */

extern const int32_t JNATIVE_FIELD_CLAZZ_OFFSET;
extern const int32_t JNATIVE_FIELD_SLOT_OFFSET;
extern const int32_t JNATIVE_FIELD_NAME_OFFSET;
extern const int32_t JNATIVE_FIELD_TYPE_OFFSET;
extern const int32_t JNATIVE_FIELD_MODIFIERS_OFFSET;

extern const int32_t JNATIVE_METHOD_CLAZZ_OFFSET;
extern const int32_t JNATIVE_METHOD_SLOT_OFFSET;
extern const int32_t JNATIVE_METHOD_NAME_OFFSET;
extern const int32_t JNATIVE_METHOD_RETURN_TYPE_OFFSET;
extern const int32_t JNATIVE_METHOD_PARAM_TYPES_OFFSET;
extern const int32_t JNATIVE_METHOD_EXC_TYPES_OFFSET;
extern const int32_t JNATIVE_METHOD_MODIFIERS_OFFSET;
extern const int32_t JNATIVE_METHOD_ROOT_OFFSET;

extern const int32_t JNATIVE_CTOR_CLAZZ_OFFSET;
extern const int32_t JNATIVE_CTOR_SLOT_OFFSET;
extern const int32_t JNATIVE_CTOR_PARAM_TYPES_OFFSET;
extern const int32_t JNATIVE_CTOR_EXC_TYPES_OFFSET;
extern const int32_t JNATIVE_CTOR_MODIFIERS_OFFSET;
extern const int32_t JNATIVE_CTOR_ROOT_OFFSET;

/* JVM access-flag bits used by the reflection filter paths. */
#ifndef JNATIVE_ACC_PUBLIC
#define JNATIVE_ACC_PUBLIC 0x0001
#endif
#ifndef JNATIVE_ACC_STATIC
#define JNATIVE_ACC_STATIC 0x0008
#endif

/* ========================================================================
 *  Weak externs emitted by the LLVM backend
 * ======================================================================== */

extern ReflectionClass* reflect_all_classes[]        JNATIVE_WEAK;

extern SymbolClassEntry jnative_symbol_class_map[]        JNATIVE_WEAK;
extern const int64_t    jnative_symbol_class_map_size     JNATIVE_WEAK;

extern const char* jnative_caller_sensitive_symbols[]     JNATIVE_WEAK;
extern const int64_t jnative_caller_sensitive_symbols_size JNATIVE_WEAK;

/* ---- Function index for stack walking ------------------------------ */

/**
 * One entry of @jnative_function_index. Layout is frozen by the emitted
 * LLVM type; see LlvmGlobalEmitter.generateFunctionIndex().
 */
typedef struct {
    void*            address;  /* function pointer, relocated by the loader */
    const char*      symbol;   /* mangled symbol name */
    ReflectionClass* cls;      /* owning class mirror, or NULL */
} JNativeFunctionEntry;

extern JNativeFunctionEntry jnative_function_index[]     JNATIVE_WEAK;
extern const int64_t        jnative_function_index_size  JNATIVE_WEAK;

/* ---- Lazy <clinit> map ---------------------------------------------- */

/**
 * One entry of @jnative_lazy_clinit_map. Layout is frozen by the
 * emitted LLVM type (see LlvmGlobalEmitter.emitLazyClinitMap):
 *
 *   cls      : %ReflectionClass*  — the class whose <clinit> is lazy
 *   wrapper  : void ()*           — its guard wrapper
 *
 * MethodHandleObjectFieldAccessorImpl.c consults this table to run the
 * class initializer before performing a MemberName-targeted field
 * access (see the file's header comment for the access-order
 * invariant it enforces).
 */
typedef struct {
    ReflectionClass* cls;
    void*            wrapper;   /* void (*)(void) */
} JNativeLazyClinitEntry;

/**
 * The map, and its length. Declared as a weak array: if the LLVM
 * backend has not emitted a matching @jnative_lazy_clinit_map, the
 * linker resolves the symbol to zero, `jnative_lazy_clinit_map`
 * decays to a null pointer, and MethodHandleObjectFieldAccessorImpl's
 * `if (map == NULL)` guard short-circuits the function.
 *
 * The array form (not JNativeLazyClinitEntry*) is deliberate: a weak
 * undefined *array* symbol resolves to address 0, so the identifier
 * itself decays to a null pointer. A weak undefined *pointer* symbol
 * would make the first use a load from address 0 — a null dereference
 * before any guard could fire.
 */
extern JNativeLazyClinitEntry jnative_lazy_clinit_map[]      JNATIVE_WEAK;
extern const int64_t          jnative_lazy_clinit_map_size   JNATIVE_WEAK;

/**
 * Resolves `pc` to the symbol and owning class of the function that
 * contains it. Returns 1 when the address is covered by the emitted
 * function index, 0 otherwise (the caller is expected to fall back to
 * dladdr, which it will rarely need to do).
 *
 * The implementation builds a sorted-by-address copy of the emitted
 * table on first use, then answers queries with a binary search.
 */
extern int __jnative_function_at_address(uintptr_t pc,
                                         const char** out_symbol,
                                         ReflectionClass** out_cls);

/* ---- Embedded resources -------------------------------------------- */

/**
 * One entry of the built-in resource table emitted by
 * LlvmGlobalEmitter.generateEmbeddedResources().
 *
 * <p>Layout matches the emitted %JNativeResourceEntry:
 *   { path, data, size }
 */
typedef struct {
    const char*    path;   /* NUL-terminated, '/'-separated, no leading '/' */
    const uint8_t* data;   /* raw bytes, never NULL-terminated                */
    int32_t        size;   /* byte count of `data`                            */
} JNativeResourceEntry;

extern const JNativeResourceEntry jnative_builtin_resources[]     JNATIVE_WEAK;
extern const int32_t jnative_builtin_resources_count             JNATIVE_WEAK;

/* ========================================================================
 *  Runtime functions (defined in jnative_runtime.c)
 * ======================================================================== */

/* ---- Strings -------------------------------------------------------- */

/*
 * __jnative_read_string_bytes returns a pointer DIRECTLY INTO the payload
 * of a Java string's backing byte array. For strings constructed by
 * compiled Java code (Arrays.copyOf and its derivatives), that array has
 * NO in-bounds NUL terminator: the emitter's allocator reserves exactly
 * `len` bytes of payload. Therefore the returned pointer must never be
 * used as a C string (strlen/strcmp/strchr/open/stat/access/...): the
 * read escapes into the next heap chunk, and the first byte of that chunk
 * (usually a glibc size word, e.g. 0x21) ends up appended to the string.
 *
 * That is exactly what happened to the path ".../lib/tzdb.dat": the
 * 56-byte string was read as a 57-byte ".../lib/tzdb.dat!", the memcmp
 * suffix check failed, open(2) returned ENOENT, and FileInputStream.c
 * threw a NULL exception that catch(Exception) did not handle.
 *
 * The two helpers below are the safe replacement for any site that needs
 * the string's bytes as an actual C string.
 */

/**
 * Copies the payload of Java String `s` into the caller's buffer `out` and
 * NUL-terminates the copy. Returns the number of bytes copied (not counting
 * the terminator), or -1 on error.
 *
 * On error (s == NULL, buffer too small, invalid length) `out[0]` is set
 * to '\0' (if out_cap > 0), the remaining buffer bytes are untouched, and
 * -1 is returned.
 *
 * This is the only safe way to obtain a Java string's contents as a C
 * string. The buffer size should be at least PATH_MAX (4096) for paths
 * and 8192 for arbitrary names.
 */
extern int32_t __jnative_read_string_into(void* s, char* out, int32_t out_cap);

/**
 * Convenience wrapper around __jnative_read_string_into using a per-thread
 * dynamically growing buffer. The returned pointer stays valid until the
 * next call to this function on the same thread.
 *
 * IMPORTANT: two results of __jnative_read_string_cstr must not be alive
 * at the same time — the second call overwrites the first. If two live
 * pointers are needed, use __jnative_read_string_into with two separate
 * buffers.
 *
 * Returns a pointer to an empty string when `s` == NULL. Returns NULL only
 * if the internal buffer allocation itself failed.
 */
extern const char* __jnative_read_string_cstr(void* s, int32_t* out_len);

extern void* __jnative_make_string_obj(const char* bytes, int32_t len);
extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);
extern void* __jnative_string_intern(void* this_str);

/* ---- Exception construction ---------------------------------------- */

/*
 * __jnative_construct_exception (see the "Exception construction and
 * throwers" section below) materialises real exception objects. Use it
 * instead of __jnative_throw_exception(NULL) everywhere the JDK spec
 * requires a concrete checked class: __jnative_catch_matches compares the
 * exception vtable against the @__type_info_X tables, and a generic
 * Throwable is not a subclass of Exception, so catch (Exception) will not
 * catch it. A real FileNotFoundException will.
 */

/* ---- Type identity and dispatch ------------------------------------ */

extern int   __jnative_instanceof(void* obj, void** type_info);
extern int   __jnative_catch_matches(void* exc, void* type_info);
extern void* __jnative_get_exception_object(void);
extern void** __jnative_lookup_itable(JNativeIfaceMap* ifacemap, int32_t iface_id);

/* ---- Monitors ------------------------------------------------------- */

extern void __jnative_monitor_enter(void* obj);
extern void __jnative_monitor_exit(void* obj);

/* ============================================================================
 *  Safe deallocation of Java reference fields.
 * ============================================================================
 *
 * Destructors generated by the runtime call this entry point instead of
 * libc free() when releasing a reference-typed field of a Java object.
 * The reason: a field can legally hold a pointer that is not allocator
 * owned -- most notably a Java String literal, which the emitter bakes
 * into the module image as a static @jstr_* global in .data. Calling
 * free() on such a pointer is undefined behaviour; glibc reports it as
 * "free(): invalid pointer", and the process aborts.
 *
 * This helper checks whether the pointer falls inside the main
 * executable's writable data segments. Everything outside that range is
 * passed to free() unchanged; everything inside is a static object and
 * is left alive for the lifetime of the process.
 *
 * See the implementation in jnative_runtime.c for the full reasoning,
 * including why a per-allocation registry is unnecessary and why a
 * range check is exact for the actual failure mode.
 */
extern void __jnative_free(void* p);
extern void __jnative_free_fast(void* p);

/* ============================================================================
 *  Virtual destructor dispatch.
 * ============================================================================
 *
 * Field-typed static dispatch cannot work for a field whose declared
 * type is abstract. A field declared `Number` may at runtime hold an
 * `Integer`, and the destructor of the enclosing object must free that
 * Integer via `__destruct_java_lang_Integer`. Calling
 * `__destruct_java_lang_Number` there is wrong (abstract Number has no
 * NEW site, so no destructor exists for it) and calling free() on the
 * field value is unsafe (it may be a static literal).
 *
 * __jnative_destruct_dispatch reads the receiver's vtable (or, for an
 * array, the class mirror stored in the first word of the array header),
 * resolves the destructor symbol for that exact runtime type, and calls
 * it. The lookup is cached per vtable/mirror, so repeated destruction
 * through the same type is O(1) after the first call.
 *
 * If the runtime type has no destructor (no NEW site anywhere in the
 * module, or the object is a static literal), the dispatch is a no-op.
 * That is safe: an object whose type never has a NEW site cannot exist
 * at runtime, and a static literal must not be freed.
 */
extern void __jnative_destruct_dispatch(void* obj);

/**
 * Field-overwrite destructor helper.
 *
 * Called before every PUT_FIELD that stores a reference-typed value into
 * a reference-typed field. The previously stored value becomes unreachable
 * at that exact moment (assuming the field was its last owner — the
 * assumption escape analysis encodes when it classifies the allocation as
 * HEAP), so it must be released before the new value replaces it.
 *
 * `old_val == new_val` is a no-op: re-storing the same reference does not
 * transfer ownership, and freeing it would leave the field dangling.
 *
 * `old_val == NULL` is a no-op: a field that was never written (freshly
 * calloc'ed object) has nothing to release.
 *
 * For any other `old_val`, the dispatch goes through the same runtime-type
 * resolution as __jnative_destruct_dispatch: if the receiver's vtable maps
 * to an emitted `__destruct_<type>` symbol, that destructor runs; if not
 * (String literal, Integer cache entry, runtime object with no NEW site),
 * it is a silent no-op. This means the helper is safe to call
 * unconditionally at every reference-typed PUT_FIELD — no static
 * classification of "which values need freeing" is required.
 */
extern void __jnative_destruct_dispatch_if_not_same(void* old_val,
                                                    void* new_val);

/* ============================================================================
 *  Cycle-safe destruction during shutdown.
 * ============================================================================
 *
 * Java object graphs are DAGs with cycles. A shared singleton such as
 * java.lang.module.Configuration.EMPTY_CONFIGURATION is reachable both as
 * a static field of its own class and as a field of another static
 * field's object; and pairs such as
 *
 *     UCharacterProperty.intProp      --->  UCharacterProperty$IntProperty
 *     UCharacterProperty$IntProperty.this$0  --->  UCharacterProperty
 *
 * form genuine cycles. A naive recursive destructor would then
 *   - on a DAG:    free a shared object twice (glibc "double free in tcache 2");
 *   - on a cycle:  recurse forever between the two objects, exhausting the
 *                  native stack and taking SIGSEGV on the guard page.
 *
 * The runtime exposes three entry points that together make the shutdown
 * pass safe against both shapes.
 *
 *   __jnative_begin_shutdown()
 *       Activates the machinery for the remainder of the process. Called
 *       once, from the very beginning of fn___jnative_shutdown, before the
 *       first destructor runs. Before it is called the two guards below
 *       are no-ops; ordinary-execution destructor calls pay nothing.
 *
 *   __jnative_dtor_enter(this) -> int
 *       Called at the entry of every generated destructor. Returns 1 if
 *       the destructor may proceed, 0 if it must return immediately.
 *       Returns 0 when
 *         - `this` is NULL, or
 *         - `this` has already been freed earlier in this shutdown pass
 *           (freed set membership), or
 *         - `this` is currently on the destruction call chain (claim stack
 *           membership, i.e. a cycle or a re-entrant path).
 *       Otherwise it adds `this` to the thread-local claim stack and
 *       returns 1.
 *
 *   __jnative_dtor_unclaim(this)
 *       Called immediately before the destructor delegates to its
 *       superclass destructor or frees the object. It removes `this` from
 *       the claim stack so that the superclass destructor (which receives
 *       the same receiver) can claim it in turn. Without this call the
 *       delegation chain would stop at the first level.
 *
 * Every free() in a destructor path is routed through __jnative_free,
 * which adds the address to the freed set so any later arrival on the
 * same address in the same shutdown pass is rejected by
 * __jnative_dtor_enter.
 *
 * Threading model: the shutdown pass is single-threaded by construction
 * (atexit runs on the main thread), so the claim stack is thread-local
 * and both operations are lock-free. The freed set is process-wide and
 * protected by a mutex, because it must remain valid if any thread ever
 * invokes a destructor during shutdown (defensive, not needed today).
 * ========================================================================== */

extern volatile int gv_jnative_in_shutdown;
extern int __jnative_is_shutdown(void);

extern void __jnative_begin_shutdown(void);
extern int  __jnative_dtor_enter(void* this_obj);
extern void __jnative_dtor_unclaim(void* this_obj);
extern void __jnative_free(void* p);
extern void __jnative_free_fast(void* p);

/* ---- Exception construction and throwers --------------------------- */

/**
 * Constructs a Java exception object of the class named by
 * `vtable_symbol`, with `message` as its detail message.
 *
 * <p>The vtable is looked up by symbol name through dlsym. If the class
 * is not present in the compiled image, the lookup returns NULL and so
 * does this function; the caller must decide whether to substitute a
 * different exception type or pass NULL on to
 * __jnative_throw_exception_ctx, which will install a generic Throwable
 * with a diagnostic message.</p>
 *
 * <p>`vtable_symbol` is the mangled form emitted by
 * LlvmGlobalEmitter.generateVtables(), e.g.
 * "vtable_java_lang_ClassNotFoundException". `message` may be NULL, in
 * which case the constructed exception has no detail message.</p>
 *
 * <p>Use this function to raise well-typed checked exceptions from
 * native code — most notably ClassNotFoundException from Class.forName0
 * — so that Java-level `catch` clauses see the exception class the
 * specification requires, rather than a substituted Throwable.</p>
 */
extern void* __jnative_construct_exception(const char* vtable_symbol,
                                           const char* message);

JNATIVE_NORETURN extern void __jnative_throw_exception(void* exc);
JNATIVE_NORETURN extern void __jnative_throw_null_pointer_exception(void);
JNATIVE_NORETURN extern void __jnative_throw_array_index_out_of_bounds(void);
JNATIVE_NORETURN extern void __jnative_throw_class_cast_exception(void);
JNATIVE_NORETURN extern void __jnative_throw_arithmetic_exception(void);

JNATIVE_NORETURN extern void __jnative_throw_exception_ctx(
        void* exc, const char* caller);
JNATIVE_NORETURN extern void __jnative_throw_null_pointer_exception_ctx(
        const char* caller, const char* var_desc);
JNATIVE_NORETURN extern void __jnative_throw_array_index_out_of_bounds_ctx(
        const char* caller);
JNATIVE_NORETURN extern void __jnative_throw_class_cast_exception_ctx(
        const char* caller);
JNATIVE_NORETURN extern void __jnative_throw_arithmetic_exception_ctx(
    const char* caller);

JNATIVE_NORETURN extern void __jnative_throw_io_ctx(const char* extra,
                                                    const char* caller);

JNATIVE_NORETURN extern void __jnative_throw_clone_not_supported_exception(void);
JNATIVE_NORETURN extern void __jnative_throw_clone_not_supported_exception_ctx(
        const char* caller);

JNATIVE_NORETURN extern void __jnative_throw_out_of_memory_error(void);
JNATIVE_NORETURN extern void __jnative_throw_out_of_memory_error_ctx(
        const char* caller);

JNATIVE_NORETURN extern void __jnative_throw_bad_vtable(void* method_name,
                                                        void* obj);
JNATIVE_NORETURN extern void __jnative_unresolved_slot(const char* what);

/**
 * Returns non-zero if and only if `ptr` equals one of the pointers in
 * reflect_all_classes[].
 *
 * This is what lets the runtime tell "obj[0] is an array's class mirror"
 * (%ReflectionClass*) apart from "obj[0] is an ordinary object's vtable"
 * (%JNativeVTable*). Implemented as a precomputed hash set, initialised on
 * first use through pthread_once.
 */
extern int __jnative_is_class_mirror(const void* ptr);

/**
 * Returns the %JNativeVTable* that a virtual call on `obj` must dispatch
 * through.
 *
 *   - Ordinary object — obj[0].
 *   - Array           — the vtable of java.lang.Object (arrays inherit all
 *     of their methods from Object; they declare none of their own).
 *   - NULL, obj[0] == NULL, or an obj[0] that is neither a registered class
 *     mirror nor a plausible vtable — throws a diagnostic exception.
 */
extern JNativeVTable* __jnative_resolve_dispatch_vtable(void* obj);

/**
 * Diagnostic for a corrupted virtual call. Never returns control.
 */
JNATIVE_NORETURN extern void __jnative_throw_bad_dispatch(void* obj,
                                                          int32_t slot,
                                                          const char* caller);

/*
 * Returns the vtable of the *declaring class* named by the argument
 * (internal form: "java/lang/Object"). Relies on the invariant
 * __type_info_X[0] == @vtable_X established by
 * LlvmGlobalEmitter.generateTypeInfo().
 */
extern void* __jnative_own_class_vtable(const char* class_name);

/**
 * Builds the LLVM global symbol name of a static field, matching
 * LlvmTypeMapper.sanitizeIdentifier on the Java side byte-for-byte:
 *
 *     "gv_" + sanitize(owner + "." + name)
 *
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. Both '/' and '.' are outside that set, so
 * "java/util/concurrent/ForkJoinPool.poolIds" becomes
 * "gv_java_util_concurrent_ForkJoinPool_poolIds".
 *
 * Returns 1 on success and 0 on failure (a NULL argument, or the output
 * buffer is too small to hold the built name plus its terminator).
 *
 * This function is the single source of truth for the symbol-naming
 * rule. Both reflective views of a static field — the java.lang.reflect
 * .Field path implemented in jnative/jdk/internal/misc/Unsafe.c and the
 * MemberName path implemented in
 * jnative/lang/invoke/MethodHandleNatives.c — must construct the same
 * symbol for the same field. If the two views disagreed, the runtime
 * would silently believe the field lives at two different addresses
 * depending on which reflective API the caller used, and the resulting
 * write/read mismatch would only surface as corrupted state far from
 * the point of the divergence.
 *
 * The build the symbol is constructed for is the build of the running
 * process image; the caller is expected to have already dlopen'ed the
 * process image (or to use RTLD_DEFAULT) and to dlsym the returned
 * symbol name itself. The function does not touch the dynamic linker.
 */
extern int jnative_build_static_field_symbol(const char* owner,
                                             const char* name,
                                             int32_t name_len,
                                             char* out, size_t out_size);

/**
 * Builds the @gv_* symbol for a static field and resolves its address
 * through the dynamic linker (RTLD_DEFAULT). Returns NULL when the field
 * is unknown to the running image.
 */
extern void* jnative_static_field_address(const char* owner, const char* name);

/* ---- Try/catch setjmp bridge --------------------------------------- */

extern void __jnative_push_catch(void* jmp_buf_ptr, void* type_info);
extern void __jnative_pop_catch(void);

/* ---- Lazy <clinit> state machine ----------------------------------- */

extern int32_t __jnative_clinit_enter(void* name_str);
extern void    __jnative_clinit_exit(void* name_str);

/**
 * Returns the internal name of the class whose <clinit> body this thread
 * is currently executing, or NULL if no <clinit> is active on this thread.
 *
 * Set by __jnative_clinit_enter when it grants the calling thread the
 * right to run the class initializer, cleared by __jnative_clinit_exit
 * when the initializer's body has returned. The name is slash-separated
 * and is the same string the wrapper passes to __jnative_clinit_enter,
 * i.e. the value stored in ReflectionClass.cname.
 *
 * Consumed by Reflection.getCallerClass() (see
 * jnative/jdk/internal/reflect/Reflection.c): the reflection walk
 * consults this stack whenever it encounters a lazy-<clinit> wrapper
 * frame, so that it can return the correct class even when the LLVM
 * backend has tail-call-optimized the <clinit> body's last instruction
 * and the body's frame is no longer present on the native stack.
 *
 * The returned pointer refers to thread-local storage: the caller must
 * not free it and must not retain it across any call that could enter
 * or exit a <clinit> on the same thread.
 */
extern const char* __jnative_current_clinit_class(void);

/* ---- Argument array construction ----------------------------------- */

extern void* __jnative_create_string_array(int argc, char** argv);
extern void* __jnative_new_multi_array(const char* desc, int dims,
                                       int* sizes, int elem_size);

/* ---- Reflection invoke trampolines --------------------------------- */

extern void* __jnative_invoke_method(ReflectionMethod* method, void* obj,
                                     void** args);
extern void* __jnative_new_instance(ReflectionConstructor* ctor, void** args);

/* ---- Runnable dispatch --------------------------------------------- */

extern void __jnative_invoke_runnable(void* runnable);

/* ---- Built-in resource table ---------------------------------------- */

/**
 * Looks `path` up in @jnative_builtin_resources. Returns NULL when the
 * resource was not baked into the image, which is a legal answer for
 * Class.getResourceAsStream.
 */
extern const JNativeResourceEntry* jnative_find_resource(const char* path,
                                                          int32_t len);

/**
 * Wraps the Java byte[] `bytes` in a fresh java.io.ByteArrayInputStream.
 * `bytes` is a runtime array object: its length is the int32 at
 * JAVA_ARR_LENGTH_OFFSET, and its payload starts at JAVA_ARR_HDR.
 */
extern void* __jnative_make_byte_array_input_stream(void* bytes);

/**
 * Drop-in replacement for the generated
 * fn_java_lang_Class_getResourceAsStream__Ljava_lang_String__Ljava_io_InputStream_,
 * installed by LlvmGlobalEmitter.overrideMethod() into every class vtable
 * that inherits the java/lang/Class.getResourceAsStream slot — not just
 * @vtable_java_lang_Class, because LlvmGlobalEmitter copies the parent's
 * slot list into each subclass layout and then resolves each slot under the
 * concrete class name. Same ABI: (Class, String) -> InputStream.
 */
extern void* __jnative_override_Class_getResourceAsStream(void* this_class,
                                                          void* name_str);

/* ---- String concatenation and value-to-String helpers ------------- */

extern void* __jnative_concat_strings(int count, ...);

extern void* __jnative_value_to_string_int(int32_t v);
extern void* __jnative_value_to_string_long(int64_t v);
extern void* __jnative_value_to_string_float(float v);
extern void* __jnative_value_to_string_double(double v);
extern void* __jnative_value_to_string_boolean(int32_t v);
extern void* __jnative_value_to_string_char(int32_t v);
extern void* __jnative_value_to_string_byte(int32_t v);
extern void* __jnative_value_to_string_short(int32_t v);
extern void* __jnative_value_to_string_object(void* obj);

/* ---- Literal pool -------------------------------------------------- */

/**
 * String literal pool emitted by LlvmGlobalEmitter.generateStringLiterals().
 * `constant`, so it is read directly from .rodata with no initialisation
 * function involved.
 */
extern void* __jnative_literal_pool[];
extern const int32_t __jnative_literal_pool_size;

/* ---- Runtime initialisation ---------------------------------------- */

/**
 * Runs the whole program initialisation: bootstrap-<clinit>s, the
 * bootstrap phases System.initPhase1/2/3, the eager-schedule <clinit>s,
 * the post-bootstrap HashedModules and the final System.props.
 *
 * Defined in _module.ll and invoked from the C constructor
 * __jnative_auto_init (see jnative_runtime.c) before main(). If the built
 * module contains no initialiser at all, the body degenerates to a single
 * `ret void`.
 */
extern void __jnative_run_all_initializers(void);

/* ============================================================================
 *  Parallel <clinit> scheduling.
 * ============================================================================
 *
 * Runs the N clinits whose function pointers are stored in @fns
 * concurrently. The arrays passed in are the `private constant` batches
 * that LlvmGenerator.generateRunAllInitializers() emits for each
 * SCC-condensation level. The `count` argument is the batch size.
 *
 *   - count == 0  → no-op
 *   - count == 1  → single call, no thread spawned
 *   - count  > 1  → work is distributed across at most
 *                   sysconf(_SC_NPROCESSORS_ONLN) pthreads
 *
 * The function never throws and never returns control to the caller
 * until every function pointer in the array has been invoked and its
 * pthread has joined. If thread creation or the internal bookkeeping
 * allocation fails, the affected sub-batch is executed synchronously
 * on the calling thread — correctness is preserved under exhaustion,
 * only the parallelism is lost.
 *
 * The batch is guaranteed by the scheduler (Analyzer.sortInitializers +
 * computeClinitLevelsAndCyclic) to consist of clinits from distinct
 * SCCs at the same condensation level. Those clinits therefore have no
 * dependency edges between them and cannot trigger one another in the
 * eager dependency graph, which is what makes running them in parallel
 * sound.
 */
extern void __jnative_run_parallel(void** fns, int32_t count);

/* ---- Diagnostic hooks ---------------------------------------------- */

extern void __jnative_debug_clinit(const char* name);

/* ---- Bootstrap Properties construction ----------------------------- */

extern void* __jnative_make_bootstrap_props(
        int32_t props_table_off,
        int32_t props_count_off,
        int32_t props_threshold_off,
        int32_t props_loadfactor_off,
        int32_t props_modcount_off,
        int32_t props_defaults_off,
        int32_t props_map_off,
        int32_t chm_table_off,
        int32_t chm_basecount_off,
        int32_t chm_sizectl_off);

/* ---- Thread layout (compile-time) --------------------------------- */

extern const int32_t THREAD_OBJECT_SIZE;
extern const int32_t THREAD_HOLDER_OFFSET;
extern const int32_t THREAD_TID_OFFSET;
extern const int32_t THREAD_NAME_OFFSET;
extern const int32_t THREAD_INTERRUPTLOCK_OFFSET;
extern const int32_t FH_OBJECT_SIZE;
extern const int32_t FH_GROUP_OFFSET;
extern const int32_t FH_PRIORITY_OFFSET;
extern const int32_t FH_DAEMON_OFFSET;
extern const int32_t FH_STATUS_OFFSET;
extern const int32_t TG_OBJECT_SIZE;
extern const int32_t TG_NAME_OFFSET;
extern const int32_t TG_MAXPRIORITY_OFFSET;
extern const int32_t TG_VMALLOW_OFFSET;

/* ========================================================================
 *  Inline helpers
 * ======================================================================== */

/* ---- Class lookup --------------------------------------------------- */

/**
 * Builds the mangled name of the @__type_info_* global for `class_name`.
 *
 * The replacement rule must stay identical to the Java side's
 * LlvmTypeMapper.sanitizeIdentifier (`replaceAll("[^a-zA-Z0-9_]", "_")`),
 * because the emitter sanitises the *whole* class name — not just its
 * separators. For an array descriptor such as "[B" the emitter emits
 * @__type_info__B; a C-side loop that only folded '/' and '.' would
 * look for "__type_info_[B", miss, and silently return NULL.
 */
static inline void jnative_type_info_name(const char* class_name,
                                          char* out, size_t out_size)
{
    if (out_size == 0) return;
    if (class_name == NULL) class_name = "";
    snprintf(out, out_size, "__type_info_%s", class_name);
    for (char* p = out; *p; p++) {
        unsigned char c = (unsigned char)*p;
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9') || c == '_')) {
            *p = '_';
        }
    }
}

/* ---- Class lookup (defined in jnative_runtime.c, hashed) ---- */

extern ReflectionClass* jnative_class_by_name(const char* name);
extern ReflectionClass* jnative_class_by_dotted_name(const char* name);

/* Cached "[B" mirror, initialised once via pthread_once. Used by the
 * String-creation fast path — the single most frequent array allocation
 * in the entire runtime. */
extern ReflectionClass* jnative_get_byte_array_class(void);

static inline void* jnative_lookup_class_vtable(ReflectionClass* cls)
{
    if (cls == NULL || cls->cname == NULL) return NULL;

    char symbol[512];
    jnative_type_info_name(cls->cname, symbol, sizeof(symbol));

    void** type_info = (void**)dlsym(RTLD_DEFAULT, symbol);
    if (type_info == NULL) return NULL;
    return type_info[0];
}

/* ---- Object allocation ---------------------------------------------- */

static inline void* jnative_alloc_object_at_least(ReflectionClass* cls,
                                                  size_t min_size)
{
    if (cls == NULL) return NULL;

    size_t size = (size_t)cls->object_size;
    if (size < min_size) size = min_size;
    if (size < OBJECT_HEADER_SIZE) size = OBJECT_HEADER_SIZE;

    void* obj = calloc(1, size);
    if (obj == NULL) return NULL;

    void* vt = jnative_lookup_class_vtable(cls);
    if (vt == NULL) {
        free(obj);
        return NULL;
    }
    *(void**)obj = vt;
    return obj;
}

static inline void* jnative_alloc_object(ReflectionClass* cls)
{
    return jnative_alloc_object_at_least(cls, 0);
}

/* ---- Strings and arrays --------------------------------------------- */

static inline void* jnative_string(const char* s)
{
    if (s == NULL) return NULL;
    return __jnative_make_string_obj(s, (int32_t)strlen(s));
}

/**
 * Single allocation point for Java arrays.
 *
 * This is the only place that writes `klass`, `length` and `elem_size`.
 * Every other helper in this header, and the whole C runtime, must go
 * through this function (or one of the inline wrappers below) instead of
 * assembling a header by hand — a hand-built header is exactly how the
 * array's first word silently ended up a vtable pointer instead of a
 * ReflectionClass* mirror, which broke Object.getClass() and instanceof.
 *
 * `klass_name` is the JVM array descriptor ("[B", "[I", "[Ljava/lang/String;",
 * "[[I", ...). The mirror is looked up in reflect_all_classes[]; a NULL
 * mirror is tolerated and simply propagates.
 */
/**
 * Allocation with an already-resolved ReflectionClass*. Avoids the
 * jnative_class_by_name lookup entirely for callers that know the class
 * (String's backing [B, the fixed klass of a lambda, ...).
 */
static inline void* jnative_array_alloc_with_class(ReflectionClass* cls,
                                                   int32_t length,
                                                   int32_t elem_size)
{
    if (length < 0) length = 0;
    size_t total = (size_t)JAVA_ARR_HDR + (size_t)length * (size_t)elem_size;
    void* arr = calloc(1, total);
    if (arr == NULL) return NULL;

    *(ReflectionClass**)((char*)arr + JAVA_ARR_KLASS_OFFSET) = cls;
    *(int32_t*)((char*)arr + JAVA_ARR_LENGTH_OFFSET)         = length;
    *(int32_t*)((char*)arr + JAVA_ARR_ELEM_SIZE_OFFSET)      = elem_size;
    return arr;
}

static inline void* jnative_array_alloc(const char* klass_name,
                                        int32_t length,
                                        int32_t elem_size)
{
    return jnative_array_alloc_with_class(
        jnative_class_by_name(klass_name), length, elem_size);
}

static inline void* jnative_byte_array(const void* data, int32_t len)
{
    void* arr = jnative_array_alloc("[B", len, JNATIVE_ELEM_SIZE_BYTE);
    if (arr == NULL) return NULL;
    if (len > 0) {
        if (data != NULL) {
            memcpy((char*)arr + JAVA_ARR_HDR, data, (size_t)len);
        } else {
            memset((char*)arr + JAVA_ARR_HDR, 0, (size_t)len);
        }
    }
    return arr;
}

static inline void* jnative_ref_array_of_class(void** items, int32_t count,
                                               const char* klass_name)
{
    void* arr = jnative_array_alloc(klass_name, count,
                                    JNATIVE_ELEM_SIZE_REFERENCE);
    if (arr == NULL) return NULL;
    if (count > 0) {
        void** slots = (void**)((char*)arr + JAVA_ARR_HDR);
        for (int32_t i = 0; i < count; i++) {
            slots[i] = (items != NULL) ? items[i] : NULL;
        }
    }
    return arr;
}

static inline void* jnative_ref_array(void** items, int32_t count)
{
    return jnative_ref_array_of_class(items, count, "[Ljava/lang/Object;");
}

static inline void* jnative_empty_ref_array(void)
{
    return jnative_ref_array(NULL, 0);
}

static inline void* jnative_int_array(const int32_t* values, int32_t count)
{
    void* arr = jnative_array_alloc("[I", count, JNATIVE_ELEM_SIZE_INT);
    if (arr == NULL) return NULL;
    if (count > 0) {
        int32_t* slots = (int32_t*)((char*)arr + JAVA_ARR_HDR);
        for (int32_t i = 0; i < count; i++) {
            slots[i] = (values != NULL) ? values[i] : 0;
        }
    }
    return arr;
}

/* ---- Array accessors ------------------------------------------------ */

static inline int32_t jnative_array_length(void* arr)
{
    if (arr == NULL) return -1;
    return *(int32_t*)((char*)arr + JAVA_ARR_LENGTH_OFFSET);
}

static inline int32_t jnative_array_elem_size(void* arr)
{
    if (arr == NULL) return -1;
    return *(int32_t*)((char*)arr + JAVA_ARR_ELEM_SIZE_OFFSET);
}

static inline void* jnative_array_data(void* arr)
{
    if (arr == NULL) return NULL;
    return (char*)arr + JAVA_ARR_HDR;
}

static inline ReflectionClass* jnative_array_klass(void* arr)
{
    if (arr == NULL) return NULL;
    return *(ReflectionClass**)((char*)arr + JAVA_ARR_KLASS_OFFSET);
}

/* ---- FileDescriptor ------------------------------------------------- */

static inline int32_t jnative_fd_of(void* fd_obj)
{
    if (fd_obj == NULL) return -1;
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

static inline int32_t jnative_raw_fd(void* fd_obj)
{
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t fd = jnative_fd_of(fd_obj);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
    }
    return fd;
}

/* ---- Unsafe-style address computation ------------------------------- */

static inline void* jnative_effective_address(void* obj, int64_t offset)
{
    return (obj == NULL)
        ? (void*)(uintptr_t)offset
        : (char*)obj + offset;
}

#ifdef __cplusplus
}
#endif

#endif /* JNATIVE_RUNTIME_H */