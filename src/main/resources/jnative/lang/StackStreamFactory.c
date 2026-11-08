#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "jnative_runtime.h"
#include "jnative_portability.h"

#define SSF_MAX_FRAMES          1024
#define SSF_FILL_CLASS_REFS_ONLY 0x2
#define SSF_WALK_MAGIC          0x4a4e535357414c4bULL

#define MN_IS_METHOD            0x00010000
#define MN_IS_CONSTRUCTOR       0x00020000
#define MN_REFERENCE_KIND_SHIFT 24
#define MN_REF_invokeVirtual    5
#define MN_REF_invokeStatic     6
#define MN_REF_invokeSpecial    7
#define MN_REF_invokeInterface  9

#define ACC_PRIVATE_FLAG        0x0002
#define ACC_STATIC_FLAG         0x0008
#define ACC_INTERFACE_FLAG      0x0200

typedef struct {
    const char* name;
    const char* descriptor;
    int32_t     modifiers;
} JNativeFunctionInfo;

extern JNativeFunctionInfo jnative_function_info[] JNATIVE_WEAK;

extern const int32_t JNATIVE_SFI_MEMBERNAME_OFFSET   JNATIVE_WEAK;
extern const int32_t JNATIVE_SFI_BCI_OFFSET          JNATIVE_WEAK;
extern const int32_t JNATIVE_MEMBERNAME_CLAZZ_OFFSET JNATIVE_WEAK;
extern const int32_t JNATIVE_MEMBERNAME_NAME_OFFSET  JNATIVE_WEAK;
extern const int32_t JNATIVE_MEMBERNAME_TYPE_OFFSET  JNATIVE_WEAK;
extern const int32_t JNATIVE_MEMBERNAME_FLAGS_OFFSET JNATIVE_WEAK;

extern void* fn_java_lang_StackStreamFactory_AbstractStackWalker_doStackWalk__JIIII_Ljava_lang_Object_(
        void* self, int64_t anchor, int32_t skipFrames, int32_t batchSize,
        int32_t bufStartIndex, int32_t bufEndIndex) JNATIVE_WEAK;

typedef struct {
    const char*      symbol;
    ReflectionClass* cls;
} SsfFrame;

typedef struct {
    uint64_t magic;
    int32_t  count;
    int32_t  next;
    SsfFrame frames[SSF_MAX_FRAMES];
} SsfWalk;

JNATIVE_NORETURN static void ssf_throw(const char* vtable, const char* msg) {
    __jnative_throw_exception(__jnative_construct_exception(vtable, msg));
}

static int ssf_offset_ok(const int32_t* offset) {
    return (uintptr_t)offset != 0 && *offset >= 0;
}

static int ssf_has_prefix(const char* s, const char* prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

static int ssf_is_walker_class(ReflectionClass* cls) {
    for (ReflectionClass* c = cls; c != NULL; c = c->superclass) {
        const char* n = (const char*)c->cname;
        if (n == NULL) continue;
        if (strcmp(n, "java/lang/StackWalker") == 0) return 1;
        if (strcmp(n, "java/lang/StackStreamFactory$AbstractStackWalker") == 0) return 1;
        if (strcmp(n, "java.lang.StackWalker") == 0) return 1;
        if (strcmp(n, "java.lang.StackStreamFactory$AbstractStackWalker") == 0) return 1;
    }
    return 0;
}

static int64_t ssf_info_index(const char* symbol) {
    if ((uintptr_t)&jnative_function_index_size == 0) return -1;
    if ((uintptr_t)jnative_function_index == 0) return -1;
    int64_t lo = 0;
    int64_t hi = jnative_function_index_size;
    while (lo < hi) {
        int64_t mid = lo + (hi - lo) / 2;
        const char* s = jnative_function_index[mid].symbol;
        int cmp = s == NULL ? -1 : strcmp(s, symbol);
        if (cmp == 0) return mid;
        if (cmp < 0) lo = mid + 1;
        else         hi = mid;
    }
    return -1;
}

static SsfWalk* ssf_capture(int32_t skipFrames) {
    SsfWalk* walk = (SsfWalk*)calloc(1, sizeof(SsfWalk));
    if (walk == NULL) __jnative_throw_out_of_memory_error_ctx("StackWalker.walk");
    walk->magic = SSF_WALK_MAGIC;

    void* pcs[SSF_MAX_FRAMES];
    int n = jn_backtrace(pcs, SSF_MAX_FRAMES);
    int leading = 1;
    int32_t skipped = 0;

    for (int i = 0; i < n && walk->count < SSF_MAX_FRAMES; i++) {
        const char* symbol = NULL;
        ReflectionClass* cls = NULL;
        if (!__jnative_function_at_address((uintptr_t)pcs[i], &symbol, &cls)) continue;
        if (symbol == NULL || cls == NULL) continue;
        if (ssf_has_prefix(symbol, "fn___lazy_clinit_run_")) continue;
        if (ssf_has_prefix(symbol, "__reflect_adaptor_")) continue;
        if (leading) {
            if (ssf_is_walker_class(cls)) continue;
            leading = 0;
        }
        if (skipped < skipFrames) {
            skipped++;
            continue;
        }
        walk->frames[walk->count].symbol = symbol;
        walk->frames[walk->count].cls = cls;
        walk->count++;
    }
    return walk;
}

static void* ssf_string(const char* s) {
    if (s == NULL) return NULL;
    return __jnative_make_string_obj(s, (int32_t)strlen(s));
}

static void ssf_fill_member_name(void* sfi, const SsfFrame* frame) {
    if (!ssf_offset_ok(&JNATIVE_SFI_MEMBERNAME_OFFSET)) return;
    void* mn = *(void**)((char*)sfi + JNATIVE_SFI_MEMBERNAME_OFFSET);
    if (mn == NULL) return;

    const char* name = NULL;
    const char* desc = NULL;
    int32_t modifiers = 0;
    int64_t idx = ssf_info_index(frame->symbol);
    if (idx >= 0 && (uintptr_t)jnative_function_info != 0) {
        name = jnative_function_info[idx].name;
        desc = jnative_function_info[idx].descriptor;
        modifiers = jnative_function_info[idx].modifiers;
    }

    int is_ctor = name != NULL && strcmp(name, "<init>") == 0;
    int32_t kind;
    if (is_ctor || (modifiers & ACC_PRIVATE_FLAG)) {
        kind = MN_REF_invokeSpecial;
    } else if (modifiers & ACC_STATIC_FLAG) {
        kind = MN_REF_invokeStatic;
    } else if (frame->cls->modifiers & ACC_INTERFACE_FLAG) {
        kind = MN_REF_invokeInterface;
    } else {
        kind = MN_REF_invokeVirtual;
    }
    int32_t flags = (modifiers & 0xFFFF)
                  | (is_ctor ? MN_IS_CONSTRUCTOR : MN_IS_METHOD)
                  | (kind << MN_REFERENCE_KIND_SHIFT);

    if (ssf_offset_ok(&JNATIVE_MEMBERNAME_CLAZZ_OFFSET)) {
        *(void**)((char*)mn + JNATIVE_MEMBERNAME_CLAZZ_OFFSET) = frame->cls;
    }
    if (ssf_offset_ok(&JNATIVE_MEMBERNAME_NAME_OFFSET)) {
        *(void**)((char*)mn + JNATIVE_MEMBERNAME_NAME_OFFSET) = ssf_string(name != NULL ? name : frame->symbol);
    }
    if (ssf_offset_ok(&JNATIVE_MEMBERNAME_TYPE_OFFSET) && desc != NULL) {
        *(void**)((char*)mn + JNATIVE_MEMBERNAME_TYPE_OFFSET) = ssf_string(desc);
    }
    if (ssf_offset_ok(&JNATIVE_MEMBERNAME_FLAGS_OFFSET)) {
        *(int32_t*)((char*)mn + JNATIVE_MEMBERNAME_FLAGS_OFFSET) = flags;
    }
    if (ssf_offset_ok(&JNATIVE_SFI_BCI_OFFSET)) {
        *(int32_t*)((char*)sfi + JNATIVE_SFI_BCI_OFFSET) = 0;
    }
}

static int32_t ssf_fill(SsfWalk* walk, int64_t mode, int32_t batchSize,
                        int32_t startIndex, void* frames) {
    int32_t length = jnative_array_length(frames);
    if (length < 0) return startIndex;
    void** slots = (void**)jnative_array_data(frames);
    int32_t end = startIndex;
    while (walk->next < walk->count
           && end - startIndex < batchSize
           && end < length) {
        const SsfFrame* frame = &walk->frames[walk->next++];
        if (mode & SSF_FILL_CLASS_REFS_ONLY) {
            slots[end] = frame->cls;
        } else if (slots[end] != NULL) {
            ssf_fill_member_name(slots[end], frame);
        }
        end++;
    }
    return end;
}

int32_t __jnative_fn_java_lang_StackStreamFactory_checkStackWalkModes___Z(void) {
    return 1;
}

void* __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_callStackWalk__JILjdk_internal_vm_ContinuationScope_Ljdk_internal_vm_Continuation_II_Ljava_lang_Object__Ljava_lang_Object_(
        void*   self,
        int64_t mode,
        int32_t skipFrames,
        void*   contScope,
        void*   continuation,
        int32_t batchSize,
        int32_t startIndex,
        void*   frames)
{
    (void)contScope;
    (void)continuation;
    if (fn_java_lang_StackStreamFactory_AbstractStackWalker_doStackWalk__JIIII_Ljava_lang_Object_ == NULL) {
        ssf_throw("vtable_java_lang_InternalError", "StackWalker.walk: doStackWalk is not linked");
    }

    SsfWalk* walk = ssf_capture(skipFrames);
    int32_t end = ssf_fill(walk, mode, batchSize, startIndex, frames);
    if (end == startIndex && walk->count > 0) {
        free(walk);
        ssf_throw("vtable_java_lang_InternalError", "StackWalker.walk: frame buffer is empty");
    }

    void* result = fn_java_lang_StackStreamFactory_AbstractStackWalker_doStackWalk__JIIII_Ljava_lang_Object_(
            self, (int64_t)(uintptr_t)walk, skipFrames, batchSize, startIndex, end);
    walk->magic = 0;
    free(walk);
    return result;
}

int32_t __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_fetchStackFrames__JJII_Ljava_lang_Object__I(
        void*   self,
        int64_t mode,
        int64_t anchor,
        int32_t batchSize,
        int32_t startIndex,
        void*   frames)
{
    (void)self;
    SsfWalk* walk = (SsfWalk*)(uintptr_t)anchor;
    if (walk == NULL || walk->magic != SSF_WALK_MAGIC) {
        ssf_throw("vtable_java_lang_InternalError", "StackWalker: invalid stack walk anchor");
    }
    return ssf_fill(walk, mode, batchSize, startIndex, frames);
}

void __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_setContinuation__J_Ljava_lang_Object_Ljdk_internal_vm_Continuation__V(
        void*   self,
        int64_t anchor,
        void*   frames,
        void*   continuation)
{
    (void)self;
    (void)anchor;
    (void)frames;
    if (continuation != NULL) {
        ssf_throw("vtable_java_lang_UnsupportedOperationException", "StackWalker: continuation stack walks are not supported");
    }
}
