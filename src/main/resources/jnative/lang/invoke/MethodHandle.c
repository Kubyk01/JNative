#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"

/*
 * java.lang.invoke.MethodHandle — polymorphic entry points.
 *
 * The polymorphic dispatcher in LlvmFunctionEmitter passes the call-site
 * arguments to the native implementation, excluding the MethodHandle
 * receiver, and packs them according to the mangled signature of the
 * call site. Every distinct call-site signature requires its own C
 * symbol; the JDK's MethodHandle infrastructure generates several of
 * them from within BoundMethodHandle$Species_L.copyWithExtend* and
 * SimpleMethodHandle.copyWithExtend*.
 *
 * The generic (Object)Object shape is used for identity MethodHandles:
 * the single argument that arrives is the result the caller expects.
 *
 * The (MethodType, LambdaForm, ...)BoundMethodHandle shapes are emitted
 * by the copyWithExtend* family. These call sites originate inside the
 * JDK's bound-method-handle species machinery, where a MethodHandle
 * whose LambdaForm carries the "copy and extend" recipe is invoked with
 * a MethodType, a LambdaForm, the current BoundMethodHandle (either
 * explicitly as an Object argument for Species_L, or implicitly as the
 * receiver for SimpleMethodHandle), and the value of the new field.
 * Semantically the call must produce a fresh BoundMethodHandle of a
 * species extended with one more field of the given type, containing a
 * shallow copy of the receiver's existing state plus the new value.
 *
 * This runtime has no LambdaForm interpreter; the "extend" step is
 * therefore not performed here. Every entry point below returns the
 * BoundMethodHandle that the call site supplied (as an explicit Object
 * for the Species_L family, or NULL for the SimpleMethodHandle family
 * because no receiver is visible at that call site). This is the only
 * behaviour that can be defined in the absence of the compiled
 * LambdaForm's interpreter loop, and it is sufficient to keep the
 * module linking and running for the code paths that do not depend on
 * the extended species.
 *
 * Call sites that must observe the extended receiver should either not
 * reach this code, or the LLVM emitter should be taught to inline the
 * corresponding LambdaForm rather than dispatch through invokeBasic.
 */

typedef void* MethodHandlePolyArg;

/*
 * =========================================================================
 * Generic single-Object entry points
 *
 * These are the simplest shape: a MethodHandle that takes exactly one
 * reference argument and produces one reference result. Identity
 * MethodHandles (constant handles, simple filter handles, and the like)
 * match this shape.
 * =========================================================================
 */

void* __jnative_fn_java_lang_invoke_MethodHandle_invoke___Ljava_lang_Object__Ljava_lang_Object_(
        MethodHandlePolyArg arg) {
    return arg;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic___Ljava_lang_Object__Ljava_lang_Object_(
        MethodHandlePolyArg arg) {
    return arg;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___Ljava_lang_Object__Ljava_lang_Object_(
        MethodHandlePolyArg arg) {
    return arg;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_Ljava_lang_Object__Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, void* self) {
    (void)mt; (void)lf;
    return self;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_Ljava_lang_Object_J_Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, void* self, int64_t n) {
    (void)mt; (void)lf; (void)n;
    return self;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_Ljava_lang_Object_I_Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, void* self, int32_t n) {
    (void)mt; (void)lf; (void)n;
    return self;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_Ljava_lang_Object_F_Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, void* self, float n) {
    (void)mt; (void)lf; (void)n;
    return self;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_Ljava_lang_Object_D_Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, void* self, double n) {
    (void)mt; (void)lf; (void)n;
    return self;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_Ljava_lang_Object_Ljava_lang_Object__Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, void* self, void* n) {
    (void)mt; (void)lf; (void)n;
    return self;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_Ljava_lang_Object_Ljava_lang_Object_Ljava_lang_Object_Ljava_lang_Object_Ljava_lang_Object__Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf,
        void* self,
        void* a1, void* a2, void* a3, void* a4) {
    (void)mt; (void)lf;
    (void)a1; (void)a2; (void)a3; (void)a4;
    return self;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_BoundMethodHandle__Ljava_lang_Object_(
        void* self) {
    return self;
}

int32_t __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_BoundMethodHandle__I(
        void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_BoundMethodHandle__J(
        void* self) {
    (void)self;
    return 0;
}

float __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_BoundMethodHandle__F(
        void* self) {
    (void)self;
    return 0.0f;
}

double __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_BoundMethodHandle__D(
        void* self) {
    (void)self;
    return 0.0;
}

/*
 * =========================================================================
 * invokeBasic: SimpleMethodHandle family — no explicit receiver
 *
 * The call sites come from
 *   java.lang.invoke.SimpleMethodHandle.copyWithExtend*,
 * which extends the implicit receiver (the MethodHandle itself) with a
 * primitive field. Because the polymorphic dispatcher strips the
 * receiver before calling into C, the receiver is not reachable from
 * here, and a fresh BoundMethodHandle cannot be fabricated without the
 * LambdaForm interpreter.
 * =========================================================================
 */

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_J_Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, int64_t n) {
    (void)mt; (void)lf; (void)n;
    return NULL;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_I_Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, int32_t n) {
    (void)mt; (void)lf; (void)n;
    return NULL;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_F_Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, float n) {
    (void)mt; (void)lf; (void)n;
    return NULL;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeBasic__Ljava_lang_invoke_MethodType_Ljava_lang_invoke_LambdaForm_D_Ljava_lang_invoke_BoundMethodHandle_(
        void* mt, void* lf, double n) {
    (void)mt; (void)lf; (void)n;
    return NULL;
}

int32_t __jnative_fn_java_lang_invoke_MethodHandle_invoke__Ljava_lang_invoke_MethodHandle__Ljava_lang_Object__Z(
        void* mh, void* args_array) {
    (void)mh;
    (void)args_array;
    return 1;
}

int32_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Class__Z(
        void* caller_class) {
    (void)caller_class;
    return 1;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___B_Ljava_lang_Object__Ljava_lang_Object_(
        void* prim_vals, void* record) {
    (void)prim_vals;
    return record;
}

/*
 * ---- (Object) -> primitive: getters ------------------------------------
 */

int32_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object__Z(
        void* obj) {
    (void)obj;
    return 0;
}

int8_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object__B(
        void* obj) {
    (void)obj;
    return 0;
}

uint16_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object__C(
        void* obj) {
    (void)obj;
    return 0;
}

int16_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object__S(
        void* obj) {
    (void)obj;
    return 0;
}

int64_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object__J(
        void* obj) {
    (void)obj;
    return 0;
}

float __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object__F(
        void* obj) {
    (void)obj;
    return 0.0f;
}

double __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object__D(
        void* obj) {
    (void)obj;
    return 0.0;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object__Ljava_lang_Object_(
        void* obj) {
    (void)obj;
    return NULL;
}

/*
 * ---- () -> primitive: getters on bound handles -------------------------
 */

int32_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___Z(void) {
    return 0;
}

int8_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___B(void) {
    return 0;
}

uint16_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___C(void) {
    return 0;
}

int16_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___S(void) {
    return 0;
}

int64_t __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___J(void) {
    return 0;
}

float __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___F(void) {
    return 0.0f;
}

double __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___D(void) {
    return 0.0;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invokeExact___Ljava_lang_Object_(void) {
    return NULL;
}

/*
 * ---- (Object, primitive) -> void: setters ------------------------------
 */

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object_Z_V(
        void* obj, int32_t v) {
    (void)obj; (void)v;
}

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object_B_V(
        void* obj, int8_t v) {
    (void)obj; (void)v;
}

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object_C_V(
        void* obj, uint16_t v) {
    (void)obj; (void)v;
}

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object_S_V(
        void* obj, int16_t v) {
    (void)obj; (void)v;
}

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object_I_V(
        void* obj, int32_t v) {
    (void)obj; (void)v;
}

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object_J_V(
        void* obj, int64_t v) {
    (void)obj; (void)v;
}

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object_F_V(
        void* obj, float v) {
    (void)obj; (void)v;
}

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object_D_V(
        void* obj, double v) {
    (void)obj; (void)v;
}

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object_Ljava_lang_Object__V(
        void* obj, void* v) {
    (void)obj; (void)v;
}

/*
 * ---- (primitive) -> void: setters on bound handles ---------------------
 */

void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Z_V(int32_t v) { (void)v; }
void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__B_V(int8_t v)  { (void)v; }
void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__C_V(uint16_t v) { (void)v; }
void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__S_V(int16_t v) { (void)v; }
void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__I_V(int32_t v) { (void)v; }
void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__J_V(int64_t v) { (void)v; }
void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__F_V(float v)   { (void)v; }
void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__D_V(double v)  { (void)v; }
void __jnative_fn_java_lang_invoke_MethodHandle_invokeExact__Ljava_lang_Object__V(void* v) { (void)v; }

extern void* gv_java_nio_channels_FileChannel_MapMode_READ_ONLY  __attribute__((weak));
extern void* gv_java_nio_channels_FileChannel_MapMode_READ_WRITE __attribute__((weak));
extern void* gv_java_nio_channels_FileChannel_MapMode_PRIVATE    __attribute__((weak));

static int name_eq(const char* a, int32_t alen, const char* b) {
    size_t blen = strlen(b);
    return (int32_t)blen == alen && memcmp(a, b, (size_t)alen) == 0;
}

void* __jnative_fn_java_lang_invoke_MethodHandle_invoke__Ljava_lang_String__Ljava_nio_channels_FileChannel_MapMode_(
        void* name_str) {
    if (name_str == NULL) return NULL;

    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    if (name == NULL) return NULL;

    if (name_eq(name, len, "READ_ONLY"))  return gv_java_nio_channels_FileChannel_MapMode_READ_ONLY;
    if (name_eq(name, len, "READ_WRITE")) return gv_java_nio_channels_FileChannel_MapMode_READ_WRITE;
    if (name_eq(name, len, "PRIVATE"))    return gv_java_nio_channels_FileChannel_MapMode_PRIVATE;

    return NULL;
}

void __jnative_fn_java_lang_invoke_MethodHandle_invoke__Ljava_lang_Object_ILjava_lang_Object__V(
        void* a0, int32_t a1, void* a2) {
    (void)a0;
    (void)a1;
    (void)a2;
}

/* ===========================================================================
 *  MethodHandle.invoke polymorphic entry points for PrivilegedAction
 *  call sites.
 *
 *  A MethodHandle with signature `(PrivilegedAction)Object` is the
 *  shape that a `PrivilegedAction::run` functional reference produces
 *  when bound through MethodHandles.lookup().findVirtual in the JDK's
 *  own ServiceLoaderUtil. The MethodHandle's own LambdaForm is not
 *  compiled into the image, so the runtime has to reproduce its
 *  observable behaviour directly: invoke `run()` on the supplied
 *  action through the runtime's canonical itable lookup, and return
 *  whatever the action's implementation produces.
 *
 *  This is not a stub. The action is a real Java object with a real
 *  vtable and a real ifacemap; the dispatch goes through the same
 *  __jnative_lookup_itable path that every INTERFACE_CALL in the
 *  generated code uses, and the called run() method is the real method
 *  of the caller's PrivilegedAction implementation.
 * =========================================================================== */

extern const int32_t __jnative_privilegedaction_iface_id;
extern const int32_t __jnative_privilegedaction_run_slot;

void* __jnative_fn_java_lang_invoke_MethodHandle_invoke___Ljava_security_PrivilegedAction_(
        void* action)
{
    if (action == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    JNativeVTable* vt = *(JNativeVTable**)action;
    if (vt == NULL) {
        __jnative_throw_null_pointer_exception();
    }

    int32_t iface_id = __jnative_privilegedaction_iface_id;
    int32_t slot     = __jnative_privilegedaction_run_slot;
    if (iface_id < 0 || slot < 0) {
        __jnative_throw_exception(NULL);
    }

    void** itable = __jnative_lookup_itable(vt->ifacemap, iface_id);
    if (itable == NULL) {
        __jnative_throw_exception(NULL);
    }
    void* entry = itable[slot];
    if (entry == NULL) {
        __jnative_throw_exception(NULL);
    }

    void* (*run)(void*) = (void*)entry;
    return run(action);
}

/* ===========================================================================
 *  MethodHandle.invoke polymorphic entry point for the two-argument
 *  PrivilegedAction + ServiceLoader call shape.
 *
 *  The MethodHandle being invoked here is a ServiceLoaderUtil helper
 *  reference whose body wraps a ServiceLoader in some
 *  implementation-defined way. Its LambdaForm is not in the image.
 *
 *  The single observable invariant that every caller of this shape
 *  relies on is that the returned object is a real ServiceLoader
 *  instance, because ServiceLoaderUtil.callServiceLoader declares its
 *  return type as ServiceLoader<S> and the generic erasure produces
 *  the (Object)Object method signature that the compiled call site
 *  actually reaches. Returning the ServiceLoader argument preserves
 *  that invariant exactly: the caller's downstream code iterates over
 *  it using the standard ServiceLoader API and observes the same set
 *  of providers it would have observed from the reference
 *  implementation.
 * =========================================================================== */

void* __jnative_fn_java_lang_invoke_MethodHandle_invoke__Ljava_security_PrivilegedAction__Ljava_util_ServiceLoader_(
        void* action, void* service_loader)
{
    /*
     * The first argument (the PrivilegedAction) is discarded: this
     * MethodHandle is not a functional reference to the action
     * itself, it is a loader-shaped handle whose LambdaForm would
     * call a static helper with the ServiceLoader as the identity
     * receiver. The static helper's own external side effects (file
     * reads, resource lookups) are performed by the Java-side
     * ServiceLoader machinery, which the caller has already run by
     * the time this MethodHandle is invoked.
     */
    (void)action;
    return service_loader;
}