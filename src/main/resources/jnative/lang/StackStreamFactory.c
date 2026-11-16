#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>

#include "jnative_runtime.h"
#include "jnative_portability.h"

#define SSF_MAX_FRAMES          1024
#define SSF_CLASS_INFO_ONLY     0x2
#define SSF_WALK_MAGIC          0x4a4e535357414c4bULL
#define SSF_RMN_MAGIC           0x4a4e524d4e414d45ULL

#define MN_IS_METHOD            0x00010000
#define MN_IS_CONSTRUCTOR       0x00020000
#define MN_CALLER_SENSITIVE     0x00100000
#define MN_REFERENCE_KIND_SHIFT 24
#define MN_REF_invokeVirtual    5
#define MN_REF_invokeStatic     6
#define MN_REF_invokeSpecial    7
#define MN_REF_invokeInterface  9
#define CFI_MEMBER_INFO_FLAGS   0x00FFFFFF

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
extern const int32_t JNATIVE_MEMBERNAME_CLAZZ_OFFSET JNATIVE_WEAK;
extern const int32_t JNATIVE_MEMBERNAME_NAME_OFFSET  JNATIVE_WEAK;
extern const int32_t JNATIVE_MEMBERNAME_TYPE_OFFSET  JNATIVE_WEAK;
extern const int32_t JNATIVE_MEMBERNAME_FLAGS_OFFSET JNATIVE_WEAK;

extern const int32_t JNATIVE_SFI_BCI_OFFSET          JNATIVE_WEAK;

extern const int32_t JNATIVE_CFI_CLASS_OR_MEMBER_NAME_OFFSET JNATIVE_WEAK;
extern const int32_t JNATIVE_CFI_FLAGS_OFFSET                JNATIVE_WEAK;
extern const int32_t JNATIVE_SFI_NAME_OFFSET                 JNATIVE_WEAK;
extern const int32_t JNATIVE_SFI_TYPE_OFFSET                 JNATIVE_WEAK;
extern const int32_t JNATIVE_RMN_VMHOLDER_OFFSET             JNATIVE_WEAK;

extern void* fn_java_lang_StackStreamFactory_AbstractStackWalker_doStackWalk__JIIII_Ljava_lang_Object_(
        void* self, int64_t anchor, int32_t skipFrames, int32_t numOrBatch,
        int32_t bufStartIndex, int32_t bufEndIndex) JNATIVE_WEAK;

typedef struct {
    const char*      symbol;
    ReflectionClass* cls;
    const char*      name;
    const char*      descriptor;
    int32_t          modifiers;
} SsfFrame;

typedef struct {
    uint64_t magic;
    int32_t  count;
    int32_t  next;
    SsfFrame frames[SSF_MAX_FRAMES];
} SsfWalk;

typedef struct {
    uint64_t magic;
    SsfFrame method;
} SsfRmnTrailer;

JNATIVE_NORETURN static void ssf_throw(const char* vtable, const char* msg) {
    __jnative_throw_exception(__jnative_construct_exception(vtable, msg));
}

static int ssf_offset_ok(const int32_t* offset) {
    return (uintptr_t)offset != 0 && *offset >= 0;
}

static int ssf_has_prefix(const char* s, const char* prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

static int ssf_new_layout(void) {
    return ssf_offset_ok(&JNATIVE_CFI_CLASS_OR_MEMBER_NAME_OFFSET);
}

static int ssf_is_walker_class(ReflectionClass* cls) {
    for (ReflectionClass* c = cls; c != NULL; c = c->superclass) {
        const char* n = (const char*)c->cname;
        if (n == NULL) continue;
        if (strcmp(n, "java/lang/StackWalker") == 0) return 1;
        if (strcmp(n, "java/lang/StackStreamFactory$AbstractStackWalker") == 0) return 1;
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

static int ssf_is_caller_sensitive(const char* sym) {
    if ((uintptr_t)&jnative_caller_sensitive_symbols_size == 0) return 0;
    if ((uintptr_t)&jnative_caller_sensitive_symbols == 0) return 0;
    int64_t lo = 0;
    int64_t hi = jnative_caller_sensitive_symbols_size;
    while (lo < hi) {
        int64_t mid = lo + (hi - lo) / 2;
        int cmp = strcmp(jnative_caller_sensitive_symbols[mid], sym);
        if (cmp == 0) return 1;
        if (cmp < 0) lo = mid + 1;
        else         hi = mid;
    }
    return 0;
}

static SsfWalk* ssf_capture(int32_t skipFrames) {
    SsfWalk* walk = (SsfWalk*)calloc(1, sizeof(SsfWalk));
    if (walk == NULL) __jnative_throw_out_of_memory_error_ctx("StackWalker.walk");
    walk->magic = SSF_WALK_MAGIC;
    if ((uintptr_t)jnative_function_info == 0) return walk;

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
        int64_t idx = ssf_info_index(symbol);
        if (idx < 0) continue;
        const JNativeFunctionInfo* info = &jnative_function_info[idx];
        if (info->name == NULL || info->descriptor == NULL) continue;
        if (leading) {
            if (ssf_is_walker_class(cls)) continue;
            leading = 0;
        }
        if (skipped < skipFrames) {
            skipped++;
            continue;
        }
        SsfFrame* f = &walk->frames[walk->count++];
        f->symbol = symbol;
        f->cls = cls;
        f->name = info->name;
        f->descriptor = info->descriptor;
        f->modifiers = info->modifiers;
    }
    return walk;
}

static SsfWalk* ssf_walk_from_anchor(int64_t anchor) {
    SsfWalk* walk = (SsfWalk*)(uintptr_t)anchor;
    if (walk == NULL || walk->magic != SSF_WALK_MAGIC) {
        ssf_throw("vtable_java_lang_InternalError", "StackWalker: invalid stack walk anchor");
    }
    return walk;
}

static void* ssf_string(const char* s) {
    if (s == NULL) return NULL;
    return __jnative_make_string_obj(s, (int32_t)strlen(s));
}

static int32_t ssf_member_flags(const SsfFrame* frame) {
    int is_ctor = strcmp(frame->name, "<init>") == 0;
    int32_t flags = (frame->modifiers & 0xFFFF)
                  | (is_ctor ? MN_IS_CONSTRUCTOR : MN_IS_METHOD);
    if (ssf_is_caller_sensitive(frame->symbol)) flags |= MN_CALLER_SENSITIVE;
    return flags;
}

static ReflectionClass* ssf_rmn_class(void) {
    static ReflectionClass* cached;
    if (cached == NULL) cached = jnative_class_by_name("java/lang/invoke/ResolvedMethodName");
    return cached;
}

static size_t ssf_rmn_trailer_offset(ReflectionClass* rmn) {
    return ((size_t)rmn->object_size + 7) & ~(size_t)7;
}

static void* ssf_new_resolved_method_name(const SsfFrame* frame) {
    ReflectionClass* rmn = ssf_rmn_class();
    if (rmn == NULL || !ssf_offset_ok(&JNATIVE_RMN_VMHOLDER_OFFSET)) {
        ssf_throw("vtable_java_lang_InternalError",
                  "StackWalker: java.lang.invoke.ResolvedMethodName is not in the image");
    }
    size_t off = ssf_rmn_trailer_offset(rmn);
    void* obj = jnative_alloc_object_at_least(rmn, off + sizeof(SsfRmnTrailer));
    if (obj == NULL) __jnative_throw_out_of_memory_error_ctx("StackWalker ResolvedMethodName");
    *(void**)((char*)obj + JNATIVE_RMN_VMHOLDER_OFFSET) = frame->cls;
    SsfRmnTrailer* t = (SsfRmnTrailer*)((char*)obj + off);
    t->magic = SSF_RMN_MAGIC;
    t->method = *frame;
    return obj;
}

static const SsfFrame* ssf_rmn_method(void* obj) {
    static void* vt;
    ReflectionClass* rmn = ssf_rmn_class();
    if (obj == NULL || rmn == NULL) return NULL;
    if (vt == NULL) vt = jnative_lookup_class_vtable(rmn);
    if (vt == NULL || *(void**)obj != vt) return NULL;
    SsfRmnTrailer* t = (SsfRmnTrailer*)((char*)obj + ssf_rmn_trailer_offset(rmn));
    return t->magic == SSF_RMN_MAGIC ? &t->method : NULL;
}

static void ssf_fill_member_name_jdk21(void* sfi, const SsfFrame* frame) {
    if (!ssf_offset_ok(&JNATIVE_SFI_MEMBERNAME_OFFSET)) return;
    void* mn = *(void**)((char*)sfi + JNATIVE_SFI_MEMBERNAME_OFFSET);
    if (mn == NULL) return;

    int32_t kind;
    if (strcmp(frame->name, "<init>") == 0 || (frame->modifiers & ACC_PRIVATE_FLAG)) {
        kind = MN_REF_invokeSpecial;
    } else if (frame->modifiers & ACC_STATIC_FLAG) {
        kind = MN_REF_invokeStatic;
    } else if (frame->cls->modifiers & ACC_INTERFACE_FLAG) {
        kind = MN_REF_invokeInterface;
    } else {
        kind = MN_REF_invokeVirtual;
    }
    int32_t flags = ssf_member_flags(frame) | (kind << MN_REFERENCE_KIND_SHIFT);

    if (ssf_offset_ok(&JNATIVE_MEMBERNAME_CLAZZ_OFFSET)) {
        *(void**)((char*)mn + JNATIVE_MEMBERNAME_CLAZZ_OFFSET) = frame->cls;
    }
    if (ssf_offset_ok(&JNATIVE_MEMBERNAME_NAME_OFFSET)) {
        *(void**)((char*)mn + JNATIVE_MEMBERNAME_NAME_OFFSET) = ssf_string(frame->name);
    }
    if (ssf_offset_ok(&JNATIVE_MEMBERNAME_TYPE_OFFSET)) {
        *(void**)((char*)mn + JNATIVE_MEMBERNAME_TYPE_OFFSET) = ssf_string(frame->descriptor);
    }
    if (ssf_offset_ok(&JNATIVE_MEMBERNAME_FLAGS_OFFSET)) {
        *(int32_t*)((char*)mn + JNATIVE_MEMBERNAME_FLAGS_OFFSET) = flags;
    }
    if (ssf_offset_ok(&JNATIVE_SFI_BCI_OFFSET)) {
        *(int32_t*)((char*)sfi + JNATIVE_SFI_BCI_OFFSET) = 0;
    }
}

static void ssf_fill_frame_info(void* cfi, const SsfFrame* frame, int class_only) {
    void* holder = class_only ? (void*)frame->cls : ssf_new_resolved_method_name(frame);
    *(void**)((char*)cfi + JNATIVE_CFI_CLASS_OR_MEMBER_NAME_OFFSET) = holder;
    if (ssf_offset_ok(&JNATIVE_CFI_FLAGS_OFFSET)) {
        int32_t* flags = (int32_t*)((char*)cfi + JNATIVE_CFI_FLAGS_OFFSET);

        *flags = (*flags & ~CFI_MEMBER_INFO_FLAGS) | (ssf_member_flags(frame) & CFI_MEMBER_INFO_FLAGS);
    }
    if (!class_only) {

        if (ssf_offset_ok(&JNATIVE_SFI_NAME_OFFSET)) {
            *(void**)((char*)cfi + JNATIVE_SFI_NAME_OFFSET) = NULL;
        }
        if (ssf_offset_ok(&JNATIVE_SFI_TYPE_OFFSET)) {
            *(void**)((char*)cfi + JNATIVE_SFI_TYPE_OFFSET) = NULL;
        }
        if (ssf_offset_ok(&JNATIVE_SFI_BCI_OFFSET)) {
            *(int32_t*)((char*)cfi + JNATIVE_SFI_BCI_OFFSET) = 0;
        }
    }
}

static int32_t ssf_fill(SsfWalk* walk, int64_t mode, int32_t batchSize,
                        int32_t startIndex, void* frames) {
    if (frames == NULL) __jnative_throw_null_pointer_exception();
    int32_t length = jnative_array_length(frames);
    if (length < 0) return startIndex;
    void** slots = (void**)jnative_array_data(frames);
    int class_only = (mode & SSF_CLASS_INFO_ONLY) != 0;
    int new_layout = ssf_new_layout();
    int32_t end = startIndex;
    while (walk->next < walk->count
           && end - startIndex < batchSize
           && end < length) {
        const SsfFrame* frame = &walk->frames[walk->next];
        if (new_layout) {
            if (slots[end] == NULL) {
                ssf_throw("vtable_java_lang_InternalError",
                          "StackWalker: frame buffer slot is not allocated");
            }
            ssf_fill_frame_info(slots[end], frame, class_only);
        } else if (class_only) {
            slots[end] = frame->cls;
        } else if (slots[end] != NULL) {
            ssf_fill_member_name_jdk21(slots[end], frame);
        }
        walk->next++;
        end++;
    }
    return end;
}

int32_t __jnative_fn_java_lang_StackStreamFactory_checkStackWalkModes___Z(void) {
    return 1;
}

static void* ssf_call_stack_walk(void* self, int64_t mode, int32_t skipFrames,
                                 void* continuation, int32_t batchSize,
                                 int32_t startIndex, void* frames, int pass_count) {
    if (continuation != NULL) {
        ssf_throw("vtable_java_lang_UnsupportedOperationException",
                  "StackWalker: continuation stack walks are not supported");
    }
    if (fn_java_lang_StackStreamFactory_AbstractStackWalker_doStackWalk__JIIII_Ljava_lang_Object_ == NULL) {
        ssf_throw("vtable_java_lang_InternalError", "StackWalker.walk: doStackWalk is not linked");
    }

    SsfWalk* walk = ssf_capture(skipFrames);
    int32_t end = ssf_fill(walk, mode, batchSize, startIndex, frames);
    if (end == startIndex && walk->count > 0) {
        free(walk);
        ssf_throw("vtable_java_lang_InternalError", "StackWalker.walk: frame buffer is empty");
    }

    int32_t third = pass_count ? end - startIndex : batchSize;

    jmp_buf jb;
    void* volatile result = NULL;
    if (setjmp(jb) == 0) {
        __jnative_push_catch(&jb, NULL);
        result = fn_java_lang_StackStreamFactory_AbstractStackWalker_doStackWalk__JIIII_Ljava_lang_Object_(
                self, (int64_t)(uintptr_t)walk, skipFrames, third, startIndex, end);
        __jnative_pop_catch();
        walk->magic = 0;
        free(walk);
        return result;
    }
    __jnative_pop_catch();
    walk->magic = 0;
    free(walk);
    __jnative_throw_exception(__jnative_get_exception_object());
}

void* __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_callStackWalk__IILjdk_internal_vm_ContinuationScope_Ljdk_internal_vm_Continuation_II_Ljava_lang_Object__Ljava_lang_Object_(
        void* self, int32_t mode, int32_t skipFrames, void* contScope,
        void* continuation, int32_t batchSize, int32_t startIndex, void* frames)
{
    (void)contScope;
    return ssf_call_stack_walk(self, mode, skipFrames, continuation,
                               batchSize, startIndex, frames, 1);
}

void* __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_callStackWalk__JILjdk_internal_vm_ContinuationScope_Ljdk_internal_vm_Continuation_II_Ljava_lang_Object__Ljava_lang_Object_(
        void* self, int64_t mode, int32_t skipFrames, void* contScope,
        void* continuation, int32_t batchSize, int32_t startIndex, void* frames)
{
    (void)contScope;
    return ssf_call_stack_walk(self, mode, skipFrames, continuation,
                               batchSize, startIndex, frames, 0);
}

int32_t __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_fetchStackFrames__IJIII_Ljava_lang_Object__I(
        void* self, int32_t mode, int64_t anchor, int32_t lastBatchFrameCount,
        int32_t bufferSize, int32_t startIndex, void* frames)
{
    (void)self;
    (void)lastBatchFrameCount;
    SsfWalk* walk = ssf_walk_from_anchor(anchor);
    return ssf_fill(walk, mode, bufferSize, startIndex, frames) - startIndex;
}

int32_t __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_fetchStackFrames__JJII_Ljava_lang_Object__I(
        void* self, int64_t mode, int64_t anchor, int32_t batchSize,
        int32_t startIndex, void* frames)
{
    (void)self;
    SsfWalk* walk = ssf_walk_from_anchor(anchor);
    return ssf_fill(walk, mode, batchSize, startIndex, frames);
}

void __jnative_fn_java_lang_StackStreamFactory_AbstractStackWalker_setContinuation__J_Ljava_lang_Object_Ljdk_internal_vm_Continuation__V(
        void*   self,
        int64_t anchor,
        void*   frames,
        void*   continuation)
{
    (void)self;
    (void)frames;
    ssf_walk_from_anchor(anchor);
    if (continuation != NULL) {
        ssf_throw("vtable_java_lang_UnsupportedOperationException",
                  "StackWalker: continuation stack walks are not supported");
    }
}

int jnative_stack_frame_expand(void* sfi) {
    if (sfi == NULL || !ssf_new_layout()
        || !ssf_offset_ok(&JNATIVE_SFI_NAME_OFFSET)
        || !ssf_offset_ok(&JNATIVE_SFI_TYPE_OFFSET)) {
        return 0;
    }
    const SsfFrame* m = ssf_rmn_method(
        *(void**)((char*)sfi + JNATIVE_CFI_CLASS_OR_MEMBER_NAME_OFFSET));
    if (m == NULL) return 0;
    void** name = (void**)((char*)sfi + JNATIVE_SFI_NAME_OFFSET);
    void** type = (void**)((char*)sfi + JNATIVE_SFI_TYPE_OFFSET);
    if (*name == NULL) *name = ssf_string(m->name);
    if (*type == NULL) *type = ssf_string(m->descriptor);
    return 1;
}

int jnative_stack_frame_describe(void* sfi, ReflectionClass** out_cls,
                                 void** out_name, int32_t* out_modifiers) {
    if (sfi == NULL) return 0;
    if (ssf_new_layout()) {
        const SsfFrame* m = ssf_rmn_method(
            *(void**)((char*)sfi + JNATIVE_CFI_CLASS_OR_MEMBER_NAME_OFFSET));
        if (m == NULL) return 0;
        if (!jnative_stack_frame_expand(sfi)) return 0;
        *out_cls = m->cls;
        *out_name = *(void**)((char*)sfi + JNATIVE_SFI_NAME_OFFSET);
        *out_modifiers = m->modifiers;
        return 1;
    }
    if (!ssf_offset_ok(&JNATIVE_SFI_MEMBERNAME_OFFSET)
        || !ssf_offset_ok(&JNATIVE_MEMBERNAME_CLAZZ_OFFSET)
        || !ssf_offset_ok(&JNATIVE_MEMBERNAME_NAME_OFFSET)) {
        return 0;
    }
    void* mn = *(void**)((char*)sfi + JNATIVE_SFI_MEMBERNAME_OFFSET);
    if (mn == NULL) return 0;
    ReflectionClass* cls = *(ReflectionClass**)((char*)mn + JNATIVE_MEMBERNAME_CLAZZ_OFFSET);
    if (cls == NULL) return 0;
    *out_cls = cls;
    *out_name = *(void**)((char*)mn + JNATIVE_MEMBERNAME_NAME_OFFSET);
    *out_modifiers = ssf_offset_ok(&JNATIVE_MEMBERNAME_FLAGS_OFFSET)
        ? (*(int32_t*)((char*)mn + JNATIVE_MEMBERNAME_FLAGS_OFFSET) & 0xFFFF) : 0;
    return 1;
}
