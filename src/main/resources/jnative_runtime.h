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
 *     cname                    @ +64   (C-строка; используется native)
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
 * native accessors (Class.getClassLoader → NULL, Class.getComponent
 * Type → NULL on a non-array class, Class.getEnumConstantsShared → null
 * until the JDK's own reflection code populates the array).
 *
 * `module` cannot be null. Class.getModule() is a plain read of that
 * slot, and Class.getResourceAsStream dereferences the result without
 * a null test (`thisModule.isNamed()`) — a null slot makes every such
 * call die with an NPE. The JDK's unnamed module is exactly a Module
 * with all fields zeroed (name == null, loader == null, no
 * descriptor), which is the state Module.isNamed() tests and the state
 * that steers Class.getResourceAsStream into its "unnamed module"
 * branch. LlvmGlobalEmitter.generateUnnamedModule() emits that object
 * as a constant; every @refclass_* mirror points at it. See fun.txt.
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
 * no longer #defines in this header. They are declared extern here and
 * assigned at program start-up by @main, which reads them from the
 * same LlvmGlobalEmitter.getFieldOffset() the LLVM emitter used to lay
 * out every object. See __jnative_reflect_set_layout() below and its
 * emitter-side caller in LlvmGenerator.generateMain().
 *
 * Every consumer that used to write JNATIVE_METHOD_PARAM_TYPES_OFFSET
 * (a compile-time constant) now writes JNATIVE_METHOD_PARAM_TYPES_OFFSET
 * as an ordinary variable read; the syntax is identical, so no source
 * file outside this header needed to change.
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

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <dlfcn.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ========================================================================
 *  Portability shims
 * ======================================================================== */

#if defined(__GNUC__) || defined(__clang__)
#  define JNATIVE_NORETURN __attribute__((noreturn))
#  define JNATIVE_WEAK     __attribute__((weak))
#  define JNATIVE_UNUSED   __attribute__((unused))
#else
#  define JNATIVE_NORETURN
#  define JNATIVE_WEAK
#  define JNATIVE_UNUSED
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
 * null test. See the class-level javadoc above and fun.txt.
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
    const char*             cname;                      /* +64   C-строка        */
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
 * They are NOT compile-time constants. The JDK declaration order of
 * these three classes is not stable across releases: on some JDK 21
 * builds `parameterTypes` is declared on java.lang.reflect.Method
 * itself, on others it is declared on the superclass
 * java.lang.reflect.Executable. That single difference shifts every
 * field of Method by one slot. When the offset was hard-coded and the
 * layout assumption was wrong, the C side wrote a valid, non-null
 * empty parameter-types array at the wrong byte of the freshly-
 * allocated mirror, Java read `parameterTypes` from a different byte
 * and observed null, and the NPE was raised much later inside
 * Executable.sharedToString when a <clinit> stringified the mirror.
 *
 * The values are assigned at program start-up by @main through
 * __jnative_reflect_set_layout(), which reads them from the same
 * LlvmGlobalEmitter.getFieldOffset() the LLVM emitter used to lay out
 * every object. Until that call runs, every offset is -1 and any
 * consumer that dereferences one of them is making a genuine
 * programming error — the runtime refuses to write into a mirror
 * before the layout it was compiled for has been published.
 *
 * Consumers use them exactly as if they were the previous #defines:
 * the syntax is unchanged, only the storage class differs.
 */

extern int32_t JNATIVE_FIELD_CLAZZ_OFFSET;
extern int32_t JNATIVE_FIELD_SLOT_OFFSET;
extern int32_t JNATIVE_FIELD_NAME_OFFSET;
extern int32_t JNATIVE_FIELD_TYPE_OFFSET;
extern int32_t JNATIVE_FIELD_MODIFIERS_OFFSET;

extern int32_t JNATIVE_METHOD_CLAZZ_OFFSET;
extern int32_t JNATIVE_METHOD_SLOT_OFFSET;
extern int32_t JNATIVE_METHOD_NAME_OFFSET;
extern int32_t JNATIVE_METHOD_RETURN_TYPE_OFFSET;
extern int32_t JNATIVE_METHOD_PARAM_TYPES_OFFSET;
extern int32_t JNATIVE_METHOD_EXC_TYPES_OFFSET;
extern int32_t JNATIVE_METHOD_MODIFIERS_OFFSET;

extern int32_t JNATIVE_CTOR_CLAZZ_OFFSET;
extern int32_t JNATIVE_CTOR_SLOT_OFFSET;
extern int32_t JNATIVE_CTOR_PARAM_TYPES_OFFSET;
extern int32_t JNATIVE_CTOR_EXC_TYPES_OFFSET;
extern int32_t JNATIVE_CTOR_MODIFIERS_OFFSET;

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

extern void* __jnative_make_string_obj(const char* bytes, int32_t len);
extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);
extern void* __jnative_string_intern(void* this_str);

/* ---- Type identity and dispatch ------------------------------------ */

extern int   __jnative_instanceof(void* obj, void** type_info);
extern int   __jnative_catch_matches(void* exc, void* type_info);
extern void* __jnative_get_exception_object(void);
extern void** __jnative_lookup_itable(JNativeIfaceMap* ifacemap, int32_t iface_id);

/* ---- Monitors ------------------------------------------------------- */

extern void __jnative_monitor_enter(void* obj);
extern void __jnative_monitor_exit(void* obj);

/* ---- Exception throwers -------------------------------------------- */

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

extern void __jnative_init_string_pool(void** pool, int32_t size);

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

/* ---- Thread layout handoff ----------------------------------------- */

extern void __jnative_thread_set_layout(
        int32_t thread_object_size,
        int32_t thread_holder_offset,
        int32_t thread_tid_offset,
        int32_t thread_name_offset,
        int32_t fh_object_size,
        int32_t fh_group_offset,
        int32_t fh_priority_offset,
        int32_t fh_daemon_offset,
        int32_t fh_status_offset,
        int32_t tg_object_size,
        int32_t tg_name_offset,
        int32_t tg_maxpriority_offset,
        int32_t tg_vmallow_offset);

/* ---- Reflect-mirror layout handoff --------------------------------- */
/*
 * Hand-off from LlvmGenerator.generateMain. The 17 offset arguments
 * below are the byte offsets of the fields that Class.c's mirror-
 * construction routines write into a freshly allocated
 * java.lang.reflect.{Field,Method,Constructor}, and that Unsafe.c's
 * field-offset accessors read back out. Each one comes from the same
 * LlvmGlobalEmitter.getFieldOffset() that laid out the mirror's
 * %struct type, so the values are guaranteed to agree with what
 * generated bytecode reads.
 *
 * Idempotent: a second call with the same values is a no-op. A second
 * call with different values is a programming error and is reported
 * to stderr; the second call is ignored so that a caller which
 * happens to invoke @main twice cannot silently corrupt the layout.
 *
 * A value of -1 for any offset means "the field does not exist on
 * this JDK". Callers that construct mirrors must check the field they
 * are about to write and skip it if the offset is negative. Consumers
 * that only read a mirror (Unsafe.objectFieldOffset, Unsafe.static
 * FieldOffset) will not encounter -1 unless the class was never
 * loaded by the emitter, in which case the mirror would not exist in
 * the first place.
 */
extern void __jnative_reflect_set_layout(
        int32_t field_clazz_offset,
        int32_t field_slot_offset,
        int32_t field_name_offset,
        int32_t field_type_offset,
        int32_t field_modifiers_offset,
        int32_t method_clazz_offset,
        int32_t method_slot_offset,
        int32_t method_name_offset,
        int32_t method_return_type_offset,
        int32_t method_param_types_offset,
        int32_t method_exc_types_offset,
        int32_t method_modifiers_offset,
        int32_t ctor_clazz_offset,
        int32_t ctor_slot_offset,
        int32_t ctor_param_types_offset,
        int32_t ctor_exc_types_offset,
        int32_t ctor_modifiers_offset);

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

static inline ReflectionClass* jnative_class_by_name(const char* name)
{
    if (name == NULL) return NULL;
    if (reflect_all_classes == NULL) return NULL;

    ReflectionClass** pp = reflect_all_classes;
    while (*pp != NULL) {
        ReflectionClass* cls = *pp;
        const char* cls_name = cls->cname;
        if (cls_name != NULL && strcmp(cls_name, name) == 0) {
            return cls;
        }
        pp++;
    }
    return NULL;
}

static inline ReflectionClass* jnative_class_by_dotted_name(const char* name)
{
    if (name == NULL) return NULL;

    ReflectionClass* cls = jnative_class_by_name(name);
    if (cls != NULL) return cls;

    char buf[512];
    size_t n = strlen(name);
    if (n >= sizeof(buf)) return NULL;
    memcpy(buf, name, n + 1);
    for (char* p = buf; *p; p++) {
        if (*p == '.') *p = '/';
    }
    return jnative_class_by_name(buf);
}

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
static inline void* jnative_array_alloc(const char* klass_name,
                                        int32_t length,
                                        int32_t elem_size)
{
    if (length < 0) length = 0;
    size_t total = (size_t)JAVA_ARR_HDR + (size_t)length * (size_t)elem_size;
    void* arr = calloc(1, total);
    if (arr == NULL) return NULL;

    ReflectionClass* cls = jnative_class_by_name(klass_name);
    *(ReflectionClass**)((char*)arr + JAVA_ARR_KLASS_OFFSET) = cls;
    *(int32_t*)((char*)arr + JAVA_ARR_LENGTH_OFFSET)         = length;
    *(int32_t*)((char*)arr + JAVA_ARR_ELEM_SIZE_OFFSET)      = elem_size;
    return arr;
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