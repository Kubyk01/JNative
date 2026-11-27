/*
 * jnative_runtime.c — the C runtime that backs every native call
 * emitted by the LLVM backend.
 *
 * This revision fixes the two MinGW compile errors reported after the
 * previous build:
 *
 *   1. The _WIN32 branch used SYMBOL_INFO / PSYMBOL_INFO / SYMOPT_*
 *      directly, all of which are declared only in <dbghelp.h>, which
 *      MinGW does not ship. DbgHelp is loaded dynamically with
 *      LoadLibraryA + GetProcAddress, so the header is not actually
 *      required — but the binary layouts of the structs and the numeric
 *      values of the flags are. They are declared locally below as
 *      JNATIVE_SYMBOL_INFO and JNATIVE_SYMOPT_*, both byte-for-byte
 *      compatible with what the Microsoft SDK ships.
 *
 *   2. __declspec(allocate(".CRT$XCU")) is an MSVC extension. Clang
 *      targeting MinGW expands __declspec(x) to __attribute__((x)),
 *      which does not recognise "allocate", and the registration was
 *      ignored with a warning — meaning the runtime's startup
 *      initialiser never actually ran. The MSVC path is now guarded
 *      with #if defined(_MSC_VER), and MinGW / clang use
 *      __attribute__((constructor)) instead. Both mechanisms end up
 *      invoking __jnative_auto_init_win() before main().
 *
 * The rest of the file is unchanged.
 */

#define _GNU_SOURCE
#include "jnative_platform.h"
#include "jnative_portability.h"
#include <setjmp.h>
#include <stdarg.h>
#include <ctype.h>

#if defined(__linux__)
#  include <link.h>     /* dl_iterate_phdr, struct dl_phdr_info,
                          ElfW(Phdr), PT_LOAD, PF_W */
#  include <unistd.h>
#  include <sys/syscall.h>
#  include <linux/membarrier.h>
#endif

#include "jnative_runtime.h"

/* ========================================================================
 *  POSIX process-image handle for symbol lookup.
 * ====================================================================== */
#ifndef _WIN32

void* jn_dlsym_default = NULL;

#endif /* !_WIN32 */

/* ========================================================================
 *  TLS access model.
 *
 *  Every _Thread_local in this file belongs to a symbol defined inside
 *  the main executable itself.  Initial-exec collapses every access to
 *  a single %fs:offset load/store.  There is no scenario in which this
 *  model is unsafe for the runtime: the .so-style dlopen of a library
 *  that itself uses initial-exec TLS against a symbol defined in the
 *  main binary is not a runtime use case, because jnative symbols are
 *  statically linked.
 * ====================================================================== */

/* ========================================================================
 *  Virtual dispatch
 * ====================================================================== */

void** __jnative_lookup_itable(JNativeIfaceMap* ifacemap, int32_t iface_id) {
    if (ifacemap == NULL) return NULL;
    int32_t n = ifacemap->count;
    JNativeIfaceMapEntry* e = ifacemap->entries;
    if (e == NULL) return NULL;
    for (int32_t i = 0; i < n; i++) {
        if (e[i].id == iface_id) return e[i].itable;
    }
    return NULL;
}

static const char* __jnative_vtable_class_name(void* vtable) {
    if (vtable == NULL) return NULL;
    return ((JNativeVTable*)vtable)->name;
}

/* ========================================================================
 *  Class-name hash map
 * ====================================================================== */

typedef struct {
    const char*      name;
    ReflectionClass* cls;
} ClassNameEntry;

typedef struct {
    ClassNameEntry* entries;
    size_t          capacity;
} ClassNameMap;

static ClassNameMap    class_name_map      = { NULL, 0 };
static jn_once_t  class_name_map_once = JN_ONCE_INIT;

static ReflectionClass* jnative_byte_array_class = NULL;
static jn_once_t   jnative_byte_array_class_once = JN_ONCE_INIT;

static uint32_t hash_class_name(const char* s) {
    uint32_t h = 2166136261u;
    for (const char* p = s; *p; p++) {
        h ^= (unsigned char)*p;
        h *= 16777619u;
    }
    return h;
}

static void build_class_name_map(void) {

    size_t count = 0;
    while (reflect_all_classes[count] != NULL) count++;
    if (count == 0) return;

    size_t cap = 64;
    while (cap < count * 2) cap <<= 1;

    ClassNameEntry* entries =
        (ClassNameEntry*)calloc(cap, sizeof(ClassNameEntry));
    if (entries == NULL) return;

    size_t mask = cap - 1;
    for (size_t i = 0; i < count; i++) {
        ReflectionClass* cls = reflect_all_classes[i];
        if (cls == NULL || cls->cname == NULL) continue;

        uint32_t h = hash_class_name(cls->cname);
        size_t idx = (size_t)(h & mask);
        while (entries[idx].name != NULL) {
            if (strcmp(entries[idx].name, cls->cname) == 0) break;
            idx = (idx + 1) & mask;
        }
        if (entries[idx].name == NULL) {
            entries[idx].name = cls->cname;
            entries[idx].cls  = cls;
        }
    }

    class_name_map.entries  = entries;
    class_name_map.capacity = cap;
}

ReflectionClass* jnative_class_by_name(const char* name) {
    if (name == NULL) return NULL;

    jn_once(&class_name_map_once, build_class_name_map);

    if (class_name_map.capacity == 0) {
        ReflectionClass** pp = reflect_all_classes;
        while (*pp != NULL) {
            if ((*pp)->cname != NULL && strcmp((*pp)->cname, name) == 0) {
                return *pp;
            }
            pp++;
        }
        return NULL;
    }

    size_t mask = class_name_map.capacity - 1;
    uint32_t h = hash_class_name(name);
    size_t idx = (size_t)(h & mask);
    while (class_name_map.entries[idx].name != NULL) {
        if (strcmp(class_name_map.entries[idx].name, name) == 0) {
            return class_name_map.entries[idx].cls;
        }
        idx = (idx + 1) & mask;
    }
    return NULL;
}

ReflectionClass* jnative_class_by_dotted_name(const char* name) {
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

static void init_byte_array_class(void) {
    jnative_byte_array_class = jnative_class_by_name("[B");
}

ReflectionClass* jnative_get_byte_array_class(void) {
    jn_once(&jnative_byte_array_class_once, init_byte_array_class);
    return jnative_byte_array_class;
}

/* ========================================================================
 *  Safe deallocation and cycle-safe destruction.
 * ====================================================================== */

static uintptr_t     jnative_static_low  = 0;
static uintptr_t     jnative_static_high = 0;

#if defined(__linux__)
static int jnative_collect_main_segments(struct dl_phdr_info* info,
                                         size_t size, void* data) {
    (void)size;
    (void)data;
    if (info->dlpi_name != NULL && info->dlpi_name[0] != '\0') return 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        uintptr_t lo = (uintptr_t)info->dlpi_addr + (uintptr_t)ph->p_vaddr;
        uintptr_t hi = lo + (uintptr_t)ph->p_memsz;
        if (jnative_static_low  == 0 || lo < jnative_static_low)  jnative_static_low  = lo;
        if (jnative_static_high == 0 || hi > jnative_static_high) jnative_static_high = hi;
    }
    return 0;
}
#endif

static void jnative_build_static_range(void) {
#if defined(_WIN32)
    HMODULE self = GetModuleHandleA(NULL);
    if (self == NULL) return;
    PIMAGE_DOS_HEADER dos = (PIMAGE_DOS_HEADER)self;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return;
    PIMAGE_NT_HEADERS nt = (PIMAGE_NT_HEADERS)((char*)self + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return;
    jnative_static_low  = (uintptr_t)self;
    jnative_static_high = (uintptr_t)self + nt->OptionalHeader.SizeOfImage;
#elif defined(__linux__)
    dl_iterate_phdr(jnative_collect_main_segments, NULL);
    {
        extern char __data_start[] __attribute__((weak));
        extern char _end[]        __attribute__((weak));
        if (__data_start != NULL && _end != NULL) {
            uintptr_t lo = (uintptr_t)__data_start;
            uintptr_t hi = (uintptr_t)_end;
            if (lo != 0 && hi > lo) {
                if (jnative_static_low  == 0 || lo < jnative_static_low)  jnative_static_low  = lo;
                if (jnative_static_high == 0 || hi > jnative_static_high) jnative_static_high = hi;
            }
        }
    }
#endif
}

volatile int gv_jnative_in_shutdown = 0;

int __jnative_is_shutdown(void) {
    return __atomic_load_n(&gv_jnative_in_shutdown, __ATOMIC_RELAXED);
}

void __jnative_begin_shutdown(void) {
    __atomic_store_n(&gv_jnative_in_shutdown, 1, __ATOMIC_RELEASE);
}

/* --- Freed-address set (permanent for the process lifetime) ------------- */

typedef struct {
    uintptr_t* slots;
    size_t     capacity;
    size_t     size;
} JNativeFreedSet;

#define JNATIVE_FREED_INITIAL_CAPACITY ((size_t)1 << 12)

static JNativeFreedSet jnative_freed_set  = { NULL, 0, 0 };
static jn_mutex_t jnative_freed_lock = JN_MUTEX_INITIALIZER;

static inline __attribute__((always_inline)) uintptr_t jnative_mix(uintptr_t p) {
    p ^= p >> 33;
    p *= 0xff51afd7ed558ccdULL;
    p ^= p >> 33;
    p *= 0xc4ceb9fe1a85ec53ULL;
    p ^= p >> 33;
    return p;
}

static int jnative_freed_grow_locked(void) {
    size_t new_cap = (jnative_freed_set.capacity == 0)
        ? JNATIVE_FREED_INITIAL_CAPACITY
        : jnative_freed_set.capacity * 2;
    uintptr_t* new_slots = (uintptr_t*)calloc(new_cap, sizeof(uintptr_t));
    if (new_slots == NULL) return 0;
    if (jnative_freed_set.slots != NULL) {
        for (size_t i = 0; i < jnative_freed_set.capacity; i++) {
            uintptr_t v = jnative_freed_set.slots[i];
            if (v == 0) continue;
            size_t j = (size_t)(jnative_mix(v) & (new_cap - 1));
            while (new_slots[j] != 0) j = (j + 1) & (new_cap - 1);
            new_slots[j] = v;
        }
        free(jnative_freed_set.slots);
    }
    jnative_freed_set.slots    = new_slots;
    jnative_freed_set.capacity = new_cap;
    return 1;
}

static int jnative_freed_insert(uintptr_t p) {
    if (p == 0) return 0;
    jn_mutex_lock(&jnative_freed_lock);
    if (jnative_freed_set.capacity == 0) {
        if (!jnative_freed_grow_locked()) {
            jn_mutex_unlock(&jnative_freed_lock);
            return 1;
        }
    }
    if (jnative_freed_set.size * 2 >= jnative_freed_set.capacity) {
        (void)jnative_freed_grow_locked();
    }
    size_t idx = (size_t)(jnative_mix(p) & (jnative_freed_set.capacity - 1));
    while (jnative_freed_set.slots[idx] != 0) {
        if (jnative_freed_set.slots[idx] == p) {
            jn_mutex_unlock(&jnative_freed_lock);
            return 0;
        }
        idx = (idx + 1) & (jnative_freed_set.capacity - 1);
    }
    jnative_freed_set.slots[idx] = p;
    jnative_freed_set.size++;
    jn_mutex_unlock(&jnative_freed_lock);
    return 1;
}

static int jnative_freed_contains(uintptr_t p) {
    if (p == 0) return 0;
    jn_mutex_lock(&jnative_freed_lock);
    if (jnative_freed_set.capacity == 0) {
        jn_mutex_unlock(&jnative_freed_lock);
        return 0;
    }
    size_t idx = (size_t)(jnative_mix(p) & (jnative_freed_set.capacity - 1));
    while (jnative_freed_set.slots[idx] != 0) {
        if (jnative_freed_set.slots[idx] == p) {
            jn_mutex_unlock(&jnative_freed_lock);
            return 1;
        }
        idx = (idx + 1) & (jnative_freed_set.capacity - 1);
    }
    jn_mutex_unlock(&jnative_freed_lock);
    return 0;
}

/* --- Thread-local claim stack ------------------------------------------- */

typedef struct {
    uintptr_t* slots;
    size_t     depth;
    size_t     capacity;
} JNativeClaimStack;

static JN_TLS JNativeClaimStack jnative_claim_stack = { NULL, 0, 0 };

static int jnative_claim_push(uintptr_t p) {
    if (jnative_claim_stack.depth == jnative_claim_stack.capacity) {
        size_t new_cap = (jnative_claim_stack.capacity == 0)
            ? 64
            : jnative_claim_stack.capacity * 2;
        uintptr_t* grown = (uintptr_t*)realloc(
            jnative_claim_stack.slots, new_cap * sizeof(uintptr_t));
        if (grown == NULL) return 0;
        jnative_claim_stack.slots    = grown;
        jnative_claim_stack.capacity = new_cap;
    }
    jnative_claim_stack.slots[jnative_claim_stack.depth++] = p;
    return 1;
}

static int jnative_claim_contains(uintptr_t p) {
    for (size_t i = 0; i < jnative_claim_stack.depth; i++) {
        if (jnative_claim_stack.slots[i] == p) return 1;
    }
    return 0;
}

static void jnative_claim_remove(uintptr_t p) {
    for (size_t i = jnative_claim_stack.depth; i > 0; i--) {
        if (jnative_claim_stack.slots[i - 1] == p) {
            jnative_claim_stack.slots[i - 1] =
                jnative_claim_stack.slots[jnative_claim_stack.depth - 1];
            jnative_claim_stack.depth--;
            return;
        }
    }
}

/* --- Public destructor entry / unclaim ---------------------------------- */

int __jnative_dtor_enter(void* this_obj) {
    if (this_obj == NULL) return 0;
    if (!gv_jnative_in_shutdown) return 1;
    uintptr_t p = (uintptr_t)this_obj;
    if (jnative_freed_contains(p))     return 0;
    if (jnative_claim_contains(p))     return 0;
    if (!jnative_claim_push(p))        return 1;
    return 1;
}

void __jnative_dtor_unclaim(void* this_obj) {
    if (this_obj == NULL) return;
    if (!gv_jnative_in_shutdown) return;
    jnative_claim_remove((uintptr_t)this_obj);
}

/* --- Public deallocation entry point ------------------------------------ */

static uint32_t* jn_rc_word_locked(void* p);

static void jn_free_raw(void* p) {
    if (p == NULL) return;
    uintptr_t addr = (uintptr_t)p;
    if (jnative_static_low != 0
        && jnative_static_high != 0
        && addr >= jnative_static_low
        && addr <  jnative_static_high) {
        return;
    }
    if (gv_jnative_in_shutdown) {
        if (!jnative_freed_insert(addr)) return;
    }
    free(p);
}

void __jnative_free(void* p) {
    if (p == NULL) return;
    if (jn_rc_word_locked(p) != NULL) {
        __jnative_rc_dec(p);
        return;
    }
    jn_free_raw(p);
}

void __jnative_free_fast(void* p) {
    if (p == NULL) return;
    if (jn_rc_word_locked(p) != NULL) {
        __jnative_rc_dec(p);
        return;
    }
    uintptr_t addr = (uintptr_t)p;
    uintptr_t lo = jnative_static_low;
    uintptr_t hi = jnative_static_high;
    if (lo != 0 && hi != 0 && addr >= lo && addr < hi) return;
    free(p);
}

typedef void (*jnative_destructor_fn)(void*);
static jnative_destructor_fn jnative_destructor_for(void* key);
static int jn_rc_managed_array(void* p);

typedef struct {
    uintptr_t key;
    int64_t   extra;
} JNativeShareSlot;

static jn_mutex_t        jnative_share_lock = JN_MUTEX_INITIALIZER;
static JNativeShareSlot* jnative_share_slots;
static size_t            jnative_share_cap;
static size_t            jnative_share_used;
static int64_t           jnative_share_live;

static int jnative_share_grow(void) {
    size_t new_cap = jnative_share_cap == 0 ? 1024 : jnative_share_cap * 2;
    JNativeShareSlot* fresh = (JNativeShareSlot*)calloc(new_cap, sizeof(JNativeShareSlot));
    if (fresh == NULL) return 0;
    for (size_t i = 0; i < jnative_share_cap; i++) {
        uintptr_t k = jnative_share_slots[i].key;
        if (k == 0) continue;
        size_t idx = (size_t)(jnative_mix(k) & (new_cap - 1));
        while (fresh[idx].key != 0) idx = (idx + 1) & (new_cap - 1);
        fresh[idx] = jnative_share_slots[i];
    }
    free(jnative_share_slots);
    jnative_share_slots = fresh;
    jnative_share_cap = new_cap;
    return 1;
}

static void jnative_share_remove_at(size_t idx) {
    size_t mask = jnative_share_cap - 1;
    size_t hole = idx;
    size_t j = idx;
    for (;;) {
        j = (j + 1) & mask;
        uintptr_t k = jnative_share_slots[j].key;
        if (k == 0) break;
        size_t home = (size_t)(jnative_mix(k) & mask);
        if (((j - home) & mask) >= ((j - hole) & mask)) {
            jnative_share_slots[hole] = jnative_share_slots[j];
            hole = j;
        }
    }
    jnative_share_slots[hole].key = 0;
    jnative_share_slots[hole].extra = 0;
    jnative_share_used--;
    __atomic_store_n(&jnative_share_live, (int64_t)jnative_share_used, __ATOMIC_RELEASE);
}

void __jnative_share(void* p) {
    if (p == NULL) return;
    if (jn_rc_managed_array(p)) return;
    uintptr_t addr = (uintptr_t)p;
    if (jnative_static_low != 0 && jnative_static_high != 0
        && addr >= jnative_static_low && addr < jnative_static_high) {
        return;
    }
    void* type_word = *(void**)p;
    if (type_word == NULL || jnative_destructor_for(type_word) == NULL) return;
    jn_mutex_lock(&jnative_share_lock);
    if ((jnative_share_used + 1) * 2 > jnative_share_cap && !jnative_share_grow()) {
        jn_mutex_unlock(&jnative_share_lock);
        __jnative_throw_out_of_memory_error_ctx("__jnative_share");
    }
    size_t mask = jnative_share_cap - 1;
    size_t idx = (size_t)(jnative_mix(addr) & mask);
    while (jnative_share_slots[idx].key != 0 && jnative_share_slots[idx].key != addr) {
        idx = (idx + 1) & mask;
    }
    if (jnative_share_slots[idx].key == 0) {
        jnative_share_slots[idx].key = addr;
        jnative_share_slots[idx].extra = 0;
        jnative_share_used++;
        __atomic_store_n(&jnative_share_live, (int64_t)jnative_share_used, __ATOMIC_RELEASE);
    }
    jnative_share_slots[idx].extra++;
    jn_mutex_unlock(&jnative_share_lock);
}

int32_t __jnative_release_owner(void* p) {
    if (p == NULL) return 0;
    if (jn_rc_managed_array(p)) {
        __jnative_rc_dec(p);
        return 0;
    }
    if (__atomic_load_n(&jnative_share_live, __ATOMIC_ACQUIRE) == 0) return 1;
    uintptr_t addr = (uintptr_t)p;
    jn_mutex_lock(&jnative_share_lock);
    int32_t last_owner = 1;
    if (jnative_share_cap != 0) {
        size_t mask = jnative_share_cap - 1;
        size_t idx = (size_t)(jnative_mix(addr) & mask);
        while (jnative_share_slots[idx].key != 0) {
            if (jnative_share_slots[idx].key == addr) {
                last_owner = 0;
                if (--jnative_share_slots[idx].extra == 0) jnative_share_remove_at(idx);
                break;
            }
            idx = (idx + 1) & mask;
        }
    }
    jn_mutex_unlock(&jnative_share_lock);
    return last_owner;
}

void __jnative_free_owned(void* p) {
    if (__jnative_release_owner(p)) __jnative_free(p);
}

enum {
    JN_BOX_ARRAY_FIELD,
    JN_BOX_HASH_TABLE
};

typedef struct {
    const char* class_name;
    const char* field_name;
    int         kind;
    void*       vtable;
    int         offset;
} JNativeContainerKind;

static JNativeContainerKind jnative_containers[] = {
    { "java/util/ArrayList",       "elementData", JN_BOX_ARRAY_FIELD, NULL, -1 },
    { "java/util/ArrayDeque",      "elements",    JN_BOX_ARRAY_FIELD, NULL, -1 },
    { "java/util/PriorityQueue",   "queue",       JN_BOX_ARRAY_FIELD, NULL, -1 },
    { "java/util/IdentityHashMap", "table",       JN_BOX_ARRAY_FIELD, NULL, -1 },
    { "java/lang/StringBuilder",   "value",       JN_BOX_ARRAY_FIELD, NULL, -1 },
    { "java/util/HashMap",         "table",       JN_BOX_HASH_TABLE,  NULL, -1 },
    { "java/util/LinkedHashMap",   "table",       JN_BOX_HASH_TABLE,  NULL, -1 },
};

#define JN_CONTAINER_COUNT ((int)(sizeof(jnative_containers) / sizeof(jnative_containers[0])))

static jn_once_t jnative_containers_once = JN_ONCE_INIT;
static int       jnative_node_next_offset = -1;

static void jnative_containers_init(void) {
    for (int i = 0; i < JN_CONTAINER_COUNT; i++) {
        JNativeContainerKind* k = &jnative_containers[i];
        ReflectionClass* cls = jnative_class_by_name(k->class_name);
        if (cls == NULL) continue;
        int off = jnative_field_offset_in(cls, k->field_name);
        if (off <= 0) continue;
        void* vt = jnative_lookup_class_vtable(cls);
        if (vt == NULL) continue;
        k->offset = off;
        k->vtable = vt;
    }
    int next = jnative_field_offset_in(jnative_class_by_name("java/util/HashMap$Node"), "next");
    if (next > 0) {
        jnative_node_next_offset = next;
    } else {
        for (int i = 0; i < JN_CONTAINER_COUNT; i++) {
            if (jnative_containers[i].kind == JN_BOX_HASH_TABLE) jnative_containers[i].vtable = NULL;
        }
    }
}

static void jnative_free_array_rc(void* arr);
static void jnative_rc_release_fields(void* obj);

static void jnative_free_array_storage(void* arr) {
    if (arr == NULL) return;
    if (*(int32_t*)((char*)arr + JAVA_ARR_LENGTH_OFFSET) <= 0) return;
    jnative_free_array_rc(arr);
}

static uint32_t* jn_rc_word_locked(void* p);

static void jnative_free_hash_table(void* table) {
    if (table == NULL) return;
    int32_t len = *(int32_t*)((char*)table + JAVA_ARR_LENGTH_OFFSET);
    if (len <= 0) return;
    void** slots = (void**)((char*)table + JAVA_ARR_HDR);
    for (int32_t i = 0; i < len; i++) {
        void* node = slots[i];
        if (node != NULL && jn_rc_word_locked(node) != NULL) {
            slots[i] = NULL;
            __jnative_rc_dec(node);
            continue;
        }
        while (node != NULL) {
            void* next = *(void**)((char*)node + jnative_node_next_offset);
            jnative_rc_release_fields(node);
            jn_free_raw(node);
            node = next;
        }
    }
    jn_free_raw(table);
}

typedef struct {
    void*     owner;
    uintptr_t frame;
} JNativeBorrow;

typedef struct {
    void* owner;
    void* array;
} JNativeRetired;

#define JN_DEATH_BUCKETS (1 << 20)

typedef struct JNativeDeathNode {
    void* owner;
    void* array;
    struct JNativeDeathNode* next;
} JNativeDeathNode;

#define JN_DEATH_STRIPES 1024

static JNativeDeathNode*  jnative_death[JN_DEATH_BUCKETS];
static int                jnative_death_locks[JN_DEATH_STRIPES][16];
static int64_t            jnative_death_live;

static void jn_spin_lock(int* lock);
static void jn_spin_unlock(int* lock);

static __thread JNativeBorrow*  jnative_borrows;
static __thread int             jnative_borrow_n;
static __thread int             jnative_borrow_cap;
static __thread JNativeRetired* jnative_retired;
static __thread int             jnative_retired_n;
static __thread int             jnative_retired_cap;

typedef struct {
    uintptr_t* a;
    uintptr_t* b;
    int32_t*   count;
    size_t     cap;
    size_t     n;
} JnBorrowMap;

static __thread JnBorrowMap jnative_borrow_owners;
static __thread JnBorrowMap jnative_borrow_pairs;

static size_t jn_bm_slot(JnBorrowMap* m, uintptr_t a, uintptr_t b) {
    size_t mask = m->cap - 1;
    size_t i = (size_t)((jnative_mix(a) ^ (jnative_mix(b) * 31u)) & mask);
    while (m->a[i] != 0 && (m->a[i] != a || m->b[i] != b)) i = (i + 1) & mask;
    return i;
}

static int jn_bm_grow(JnBorrowMap* m) {
    size_t cap = m->cap == 0 ? 64 : m->cap * 2;
    uintptr_t* na = (uintptr_t*)calloc(cap, sizeof(uintptr_t));
    uintptr_t* nb = (uintptr_t*)calloc(cap, sizeof(uintptr_t));
    int32_t* nc = (int32_t*)calloc(cap, sizeof(int32_t));
    if (na == NULL || nb == NULL || nc == NULL) {
        free(na);
        free(nb);
        free(nc);
        return 0;
    }
    JnBorrowMap old = *m;
    m->a = na;
    m->b = nb;
    m->count = nc;
    m->cap = cap;
    m->n = 0;
    for (size_t i = 0; i < old.cap; i++) {
        if (old.a[i] == 0) continue;
        size_t j = jn_bm_slot(m, old.a[i], old.b[i]);
        m->a[j] = old.a[i];
        m->b[j] = old.b[i];
        m->count[j] = old.count[i];
        m->n++;
    }
    free(old.a);
    free(old.b);
    free(old.count);
    return 1;
}

static int32_t jn_bm_get(JnBorrowMap* m, uintptr_t a, uintptr_t b) {
    if (m->cap == 0) return 0;
    size_t i = jn_bm_slot(m, a, b);
    return m->a[i] == 0 ? 0 : m->count[i];
}

static int jn_bm_add(JnBorrowMap* m, uintptr_t a, uintptr_t b) {
    if ((m->n + 1) * 2 > m->cap && !jn_bm_grow(m)) return 0;
    size_t i = jn_bm_slot(m, a, b);
    if (m->a[i] == 0) {
        m->a[i] = a;
        m->b[i] = b;
        m->count[i] = 0;
        m->n++;
    }
    m->count[i]++;
    return 1;
}

static void jn_bm_remove(JnBorrowMap* m, uintptr_t a, uintptr_t b) {
    if (m->cap == 0) return;
    size_t mask = m->cap - 1;
    size_t i = jn_bm_slot(m, a, b);
    if (m->a[i] == 0) return;
    if (--m->count[i] > 0) return;
    m->a[i] = 0;
    m->b[i] = 0;
    m->n--;
    size_t j = i;
    for (;;) {
        j = (j + 1) & mask;
        if (m->a[j] == 0) break;
        size_t home = (size_t)((jnative_mix(m->a[j]) ^ (jnative_mix(m->b[j]) * 31u)) & mask);
        int between = i <= j ? (home > i && home <= j) : (home > i || home <= j);
        if (between) continue;
        m->a[i] = m->a[j];
        m->b[i] = m->b[j];
        m->count[i] = m->count[j];
        m->a[j] = 0;
        m->b[j] = 0;
        m->count[j] = 0;
        i = j;
    }
}

typedef struct JnRetiredNode {
    void*                 array;
    struct JnRetiredNode* next;
} JnRetiredNode;

typedef struct {
    uintptr_t*      keys;
    JnRetiredNode** heads;
    size_t          cap;
    size_t          n;
} JnRetiredMap;

static __thread JnRetiredMap jnative_retired_map;

static size_t jn_rm_slot(JnRetiredMap* m, uintptr_t key) {
    size_t mask = m->cap - 1;
    size_t i = (size_t)(jnative_mix(key) & mask);
    while (m->keys[i] != 0 && m->keys[i] != key) i = (i + 1) & mask;
    return i;
}

static int jn_rm_grow(JnRetiredMap* m) {
    size_t cap = m->cap == 0 ? 64 : m->cap * 2;
    uintptr_t* keys = (uintptr_t*)calloc(cap, sizeof(uintptr_t));
    JnRetiredNode** heads = (JnRetiredNode**)calloc(cap, sizeof(JnRetiredNode*));
    if (keys == NULL || heads == NULL) {
        free(keys);
        free(heads);
        return 0;
    }
    JnRetiredMap old = *m;
    m->keys = keys;
    m->heads = heads;
    m->cap = cap;
    m->n = 0;
    for (size_t i = 0; i < old.cap; i++) {
        if (old.keys[i] == 0) continue;
        size_t j = jn_rm_slot(m, old.keys[i]);
        m->keys[j] = old.keys[i];
        m->heads[j] = old.heads[i];
        m->n++;
    }
    free(old.keys);
    free(old.heads);
    return 1;
}

static JnRetiredNode* jn_rm_take(JnRetiredMap* m, uintptr_t key) {
    if (m->cap == 0 || m->n == 0) return NULL;
    size_t mask = m->cap - 1;
    size_t i = jn_rm_slot(m, key);
    if (m->keys[i] == 0) return NULL;
    JnRetiredNode* head = m->heads[i];
    m->keys[i] = 0;
    m->heads[i] = NULL;
    m->n--;
    size_t j = i;
    for (;;) {
        j = (j + 1) & mask;
        if (m->keys[j] == 0) break;
        size_t home = (size_t)(jnative_mix(m->keys[j]) & mask);
        int between = i <= j ? (home > i && home <= j) : (home > i || home <= j);
        if (between) continue;
        m->keys[i] = m->keys[j];
        m->heads[i] = m->heads[j];
        m->keys[j] = 0;
        m->heads[j] = NULL;
        i = j;
    }
    return head;
}

static void jnative_free_retired_of(void* owner) {
    JnRetiredNode* n = jn_rm_take(&jnative_retired_map, (uintptr_t)owner);
    while (n != NULL) {
        JnRetiredNode* next = n->next;
        jnative_retired_n--;
        jnative_free_array_rc(n->array);
        free(n);
        n = next;
    }
}

static void jnative_drop_borrows_below(uintptr_t frame, int inclusive) {
    while (jnative_borrow_n > 0) {
        JNativeBorrow* top = &jnative_borrows[jnative_borrow_n - 1];
        if (!(top->frame < frame || (inclusive && top->frame == frame))) break;
        void* owner = top->owner;
        jn_bm_remove(&jnative_borrow_owners, (uintptr_t)owner, 0);
        jn_bm_remove(&jnative_borrow_pairs, (uintptr_t)owner, top->frame);
        jnative_borrow_n--;
        if (jnative_retired_n != 0 && jn_bm_get(&jnative_borrow_owners, (uintptr_t)owner, 0) == 0) {
            jnative_free_retired_of(owner);
        }
    }
}

static int jnative_borrowed(void* owner) {
    return jnative_borrow_n != 0 && jn_bm_get(&jnative_borrow_owners, (uintptr_t)owner, 0) > 0;
}

static void jnative_defer_until_death(void* owner, void* array) {
    JNativeDeathNode* node = (JNativeDeathNode*)malloc(sizeof(JNativeDeathNode));
    if (node == NULL) return;
    node->owner = owner;
    node->array = array;
    size_t b = (size_t)(jnative_mix((uintptr_t)owner) & (JN_DEATH_BUCKETS - 1));
    int* lock = jnative_death_locks[b & (JN_DEATH_STRIPES - 1)];
    jn_spin_lock(lock);
    node->next = jnative_death[b];
    __atomic_store_n(&jnative_death[b], node, __ATOMIC_RELEASE);
    __atomic_add_fetch(&jnative_death_live, 1, __ATOMIC_RELEASE);
    jn_spin_unlock(lock);
}

__attribute__((noinline)) void __jnative_borrow_enter(void* owner) {
    if (owner == NULL) return;
    uintptr_t frame = JN_CALLER_FRAME();
    jnative_drop_borrows_below(frame, 0);
    if (jn_bm_get(&jnative_borrow_pairs, (uintptr_t)owner, frame) > 0) return;
    if (jnative_borrow_n == jnative_borrow_cap) {
        int cap = jnative_borrow_cap == 0 ? 16 : jnative_borrow_cap * 2;
        JNativeBorrow* grown = (JNativeBorrow*)realloc(jnative_borrows, (size_t)cap * sizeof(JNativeBorrow));
        if (grown == NULL) return;
        jnative_borrows = grown;
        jnative_borrow_cap = cap;
    }
    if (!jn_bm_add(&jnative_borrow_owners, (uintptr_t)owner, 0)) return;
    if (!jn_bm_add(&jnative_borrow_pairs, (uintptr_t)owner, frame)) {
        jn_bm_remove(&jnative_borrow_owners, (uintptr_t)owner, 0);
        return;
    }
    jnative_borrows[jnative_borrow_n].owner = owner;
    jnative_borrows[jnative_borrow_n].frame = frame;
    jnative_borrow_n++;
}

__attribute__((noinline)) void __jnative_borrow_exit(void) {
    if (jnative_borrow_n == 0 && jnative_retired_n == 0) return;
    uintptr_t frame = JN_CALLER_FRAME();
    jnative_drop_borrows_below(frame, 1);
}

static void jnative_retire(void* owner, void* array) {
    JnRetiredMap* m = &jnative_retired_map;
    if ((m->n + 1) * 2 > m->cap && !jn_rm_grow(m)) return;
    JnRetiredNode* node = (JnRetiredNode*)malloc(sizeof(JnRetiredNode));
    if (node == NULL) return;
    size_t i = jn_rm_slot(m, (uintptr_t)owner);
    if (m->keys[i] == 0) {
        m->keys[i] = (uintptr_t)owner;
        m->heads[i] = NULL;
        m->n++;
    }
    node->array = array;
    node->next = m->heads[i];
    m->heads[i] = node;
    jnative_retired_n++;
}

__attribute__((noinline)) void __jnative_replace_array(void* owner, void* old, void* replacement, int32_t mode) {
    if (old == NULL || old == replacement) return;
    if (jn_rc_managed_array(old)) return;
    uintptr_t addr = (uintptr_t)old;
    if (jnative_static_low != 0 && jnative_static_high != 0
        && addr >= jnative_static_low && addr < jnative_static_high) {
        return;
    }
    if (*(int32_t*)((char*)old + JAVA_ARR_LENGTH_OFFSET) <= 0) return;
    if (!__jnative_release_owner(old)) return;
    uintptr_t frame = JN_CALLER_FRAME();
    jnative_drop_borrows_below(frame, 0);
    if (mode == 0) {
        jnative_defer_until_death(owner, old);
        return;
    }
    if (!jnative_borrowed(owner)) {
        jnative_free_array_rc(old);
        return;
    }
    jnative_retire(owner, old);
}

void __jnative_release_array_field(void* owner, void* array) {
    if (jn_rc_managed_array(array)) __jnative_rc_dec(array);
    else __jnative_replace_array(owner, array, NULL, 1);
}

static void jnative_release_retired(void* owner) {
    int borrowed = jnative_borrowed(owner);
    if (jnative_retired_n != 0 && !borrowed) jnative_free_retired_of(owner);
    if (__atomic_load_n(&jnative_death_live, __ATOMIC_ACQUIRE) == 0) return;
    size_t b = (size_t)(jnative_mix((uintptr_t)owner) & (JN_DEATH_BUCKETS - 1));
    if (__atomic_load_n(&jnative_death[b], __ATOMIC_ACQUIRE) == NULL) return;
    JNativeDeathNode* found = NULL;
    int* lock = jnative_death_locks[b & (JN_DEATH_STRIPES - 1)];
    jn_spin_lock(lock);
    JNativeDeathNode** pp = &jnative_death[b];
    while (*pp != NULL) {
        JNativeDeathNode* n = *pp;
        if (n->owner == owner) {
            *pp = n->next;
            n->next = found;
            found = n;
            __atomic_sub_fetch(&jnative_death_live, 1, __ATOMIC_RELEASE);
        } else {
            pp = &n->next;
        }
    }
    jn_spin_unlock(lock);
    while (found != NULL) {
        JNativeDeathNode* next = found->next;
        if (borrowed) jnative_retire(owner, found->array);
        else jnative_free_array_rc(found->array);
        free(found);
        found = next;
    }
}

static void jnative_release_storage(void* p);

typedef struct JnEbrThread {
    uint64_t epoch;
    int32_t active;
    struct JnEbrThread* next;
} JnEbrThread;

typedef struct {
    void* ptr;
    uint64_t epoch;
} JnEbrRetired;

static JnEbrThread*            jn_ebr_threads;
static uint64_t                jn_ebr_global = 2;
static __thread JnEbrThread*   jn_ebr_self;
static __thread uintptr_t*     jn_ebr_frames;
static __thread int            jn_ebr_frame_n;
static __thread int            jn_ebr_frame_cap;
static __thread JnEbrRetired*  jn_ebr_retired;
static __thread int            jn_ebr_retired_n;
static __thread int            jn_ebr_retired_cap;
static __thread uint32_t       jn_ebr_ticks;

static JnEbrThread* jn_ebr_thread(void) {
    JnEbrThread* t = jn_ebr_self;
    if (t != NULL) return t;
    t = (JnEbrThread*)calloc(1, sizeof(JnEbrThread));
    if (t == NULL) __jnative_throw_out_of_memory_error_ctx("__jnative_ebr");
    JnEbrThread* head = __atomic_load_n(&jn_ebr_threads, __ATOMIC_ACQUIRE);
    do {
        t->next = head;
    } while (!__atomic_compare_exchange_n(&jn_ebr_threads, &head, t, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE));
    jn_ebr_self = t;
    return t;
}

static void jn_ebr_drop_frames(uintptr_t frame, int inclusive) {
    int w = 0;
    for (int i = 0; i < jn_ebr_frame_n; i++) {
        uintptr_t f = jn_ebr_frames[i];
        if (f < frame || (inclusive && f == frame)) continue;
        jn_ebr_frames[w++] = f;
    }
    jn_ebr_frame_n = w;
}

static void jn_ebr_collect(void) {
    uint64_t g = __atomic_load_n(&jn_ebr_global, __ATOMIC_SEQ_CST);
    int blocked = 0;
    for (JnEbrThread* t = __atomic_load_n(&jn_ebr_threads, __ATOMIC_ACQUIRE); t != NULL; t = t->next) {
        if (__atomic_load_n(&t->active, __ATOMIC_SEQ_CST) && __atomic_load_n(&t->epoch, __ATOMIC_SEQ_CST) != g) {
            blocked = 1;
            break;
        }
    }
    if (!blocked) {
        uint64_t expected = g;
        __atomic_compare_exchange_n(&jn_ebr_global, &expected, g + 1, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    }
    g = __atomic_load_n(&jn_ebr_global, __ATOMIC_SEQ_CST);
    int w = 0;
    int n = jn_ebr_retired_n;
    JnEbrRetired* due = NULL;
    int due_n = 0;
    for (int i = 0; i < n; i++) {
        JnEbrRetired r = jn_ebr_retired[i];
        if (r.epoch + 2 <= g) {
            if (due == NULL) due = (JnEbrRetired*)malloc((size_t)n * sizeof(JnEbrRetired));
            if (due != NULL) due[due_n++] = r;
            else jn_ebr_retired[w++] = r;
        } else {
            jn_ebr_retired[w++] = r;
        }
    }
    jn_ebr_retired_n = w;
    for (int i = 0; i < due_n; i++) {
        jnative_free_array_rc(due[i].ptr);
    }
    free(due);
}

__attribute__((noinline)) void __jnative_ebr_pin(void) {
    uintptr_t frame = JN_CALLER_FRAME();
    JnEbrThread* t = jn_ebr_thread();
    jn_ebr_drop_frames(frame, 0);
    for (int i = jn_ebr_frame_n - 1; i >= 0; i--) {
        if (jn_ebr_frames[i] == frame) return;
    }
    if (jn_ebr_frame_n == jn_ebr_frame_cap) {
        int cap = jn_ebr_frame_cap == 0 ? 16 : jn_ebr_frame_cap * 2;
        uintptr_t* grown = (uintptr_t*)realloc(jn_ebr_frames, (size_t)cap * sizeof(uintptr_t));
        if (grown == NULL) __jnative_throw_out_of_memory_error_ctx("__jnative_ebr_pin");
        jn_ebr_frames = grown;
        jn_ebr_frame_cap = cap;
    }
    jn_ebr_frames[jn_ebr_frame_n++] = frame;
    if (!__atomic_load_n(&t->active, __ATOMIC_RELAXED)) {
        __atomic_store_n(&t->epoch, __atomic_load_n(&jn_ebr_global, __ATOMIC_SEQ_CST), __ATOMIC_SEQ_CST);
        __atomic_store_n(&t->active, 1, __ATOMIC_SEQ_CST);
        __atomic_thread_fence(__ATOMIC_SEQ_CST);
        __atomic_store_n(&t->epoch, __atomic_load_n(&jn_ebr_global, __ATOMIC_SEQ_CST), __ATOMIC_SEQ_CST);
    }
}

__attribute__((noinline)) void __jnative_ebr_unpin(void) {
    JnEbrThread* t = jn_ebr_self;
    if (t == NULL) return;
    uintptr_t frame = JN_CALLER_FRAME();
    jn_ebr_drop_frames(frame, 1);
    if (jn_ebr_frame_n == 0 && __atomic_load_n(&t->active, __ATOMIC_RELAXED)) {
        __atomic_store_n(&t->active, 0, __ATOMIC_SEQ_CST);
        if (jn_ebr_retired_n != 0 && (++jn_ebr_ticks & 63) == 0) jn_ebr_collect();
    }
}

static void jn_ebr_retire(void* p) {
    if (p == NULL || jn_rc_managed_array(p)) return;
    uintptr_t addr = (uintptr_t)p;
    if (jnative_static_low != 0 && jnative_static_high != 0
        && addr >= jnative_static_low && addr < jnative_static_high) {
        return;
    }
    if (*(int32_t*)((char*)p + JAVA_ARR_LENGTH_OFFSET) <= 0) return;
    if (!__jnative_release_owner(p)) return;
    jn_ebr_thread();
    if (jn_ebr_retired_n == jn_ebr_retired_cap) {
        int cap = jn_ebr_retired_cap == 0 ? 64 : jn_ebr_retired_cap * 2;
        JnEbrRetired* grown = (JnEbrRetired*)realloc(jn_ebr_retired, (size_t)cap * sizeof(JnEbrRetired));
        if (grown == NULL) return;
        jn_ebr_retired = grown;
        jn_ebr_retired_cap = cap;
    }
    jn_ebr_retired[jn_ebr_retired_n].ptr = p;
    jn_ebr_retired[jn_ebr_retired_n].epoch = __atomic_load_n(&jn_ebr_global, __ATOMIC_SEQ_CST);
    jn_ebr_retired_n++;
    if (jn_ebr_retired_n >= 64 && (jn_ebr_retired_n & 31) == 0) jn_ebr_collect();
}

__attribute__((noinline)) void __jnative_ebr_retire_if(int32_t ok, void* old) {
    if (ok) jn_ebr_retire(old);
}

__attribute__((noinline)) void __jnative_ebr_retire_if_same(void* witness, void* old) {
    if (witness == old) jn_ebr_retire(old);
}

__attribute__((noinline)) void __jnative_ebr_retire(void* old) {
    jn_ebr_retire(old);
}

__attribute__((noinline)) void __jnative_ebr_discard_if(int32_t published, void* unpublished) {
    if (published || unpublished == NULL || jn_rc_managed_array(unpublished)) return;
    uintptr_t addr = (uintptr_t)unpublished;
    if (jnative_static_low != 0 && jnative_static_high != 0
        && addr >= jnative_static_low && addr < jnative_static_high) {
        return;
    }
    if (__jnative_release_owner(unpublished)) jnative_free_array_rc(unpublished);
}

#define JN_RC_RETIRED   0x80000000u
#define JN_RC_STACK     0x40000000u
#define JN_RC_REVIVED   0x20000000u
#define JN_RC_BUFFERED  0x10000000u
#define JN_RC_IMMORTAL  0x08000000u
#define JN_RC_COUNT     0x07ffffffu
#define JN_RC_REF_ARRAY 1
#define JN_RC_REFERENCE 2
#define JN_RC_CYCLIC    4
#define JN_RC_SLOTS     16384
#define JN_WEAK_BUCKETS 4096

typedef struct {
    int32_t          offset;
    int32_t          flags;
    const int32_t*   fields;
    const int32_t*   owned;
} JnRcInfo;

typedef struct JnWeakNode {
    void*              referent;
    void**             slot;
    struct JnWeakNode* next;
} JnWeakNode;

static void*      jn_rc_keys[JN_RC_SLOTS];
static JnRcInfo*  jn_rc_vals[JN_RC_SLOTS];
static jn_mutex_t jn_rc_lock = JN_MUTEX_INITIALIZER;
static JnRcInfo   jn_rc_plain = { -1, 0, NULL };
static JnWeakNode* jn_weak[JN_WEAK_BUCKETS];
static jn_mutex_t jn_weak_mutex = JN_MUTEX_INITIALIZER;

typedef struct JnRcArray {
    void* array;
    uint32_t state;
} JnRcArray;

#define JN_RC_SHARD_BITS 10
#define JN_RC_TOMB       ((void*)1)

typedef struct JnRcTable {
    size_t            cap;
    void**            keys;
    JnRcArray**       nodes;
    uint64_t          retire_epoch;
    struct JnRcTable* retire_next;
} JnRcTable;

typedef struct __attribute__((aligned(64))) {
    int         lock;
    size_t      used;
    size_t      live;
    JnRcTable*  table;
} JnRcShard;

typedef struct JnRegReader {
    uint64_t            epoch;
    int                 used;
    struct JnRegReader* next;
    char                pad[64 - sizeof(uint64_t) - sizeof(int) - sizeof(void*)];
} JnRegReader;

/* Keep the Java array header and its inline and static representations intact. */
static JnRcShard jn_rc_shards[1u << JN_RC_SHARD_BITS];
static JnRegReader*          jn_reg_readers;
static uint64_t              jn_reg_epoch = 1;
static __thread JnRegReader* jn_reg_self;
static JnRcTable*            jn_rc_retired_tables;
static int                   jn_rc_retired_tables_lock;

static JnRcShard* jn_rc_shard(uintptr_t h) {
    return &jn_rc_shards[h >> (sizeof(uintptr_t) * 8 - JN_RC_SHARD_BITS)];
}

static void jn_spin_lock(int* lock) {
    while (__atomic_exchange_n(lock, 1, __ATOMIC_ACQUIRE)) {
        while (__atomic_load_n(lock, __ATOMIC_RELAXED)) jn_yield();
    }
}

static void jn_spin_unlock(int* lock) {
    __atomic_store_n(lock, 0, __ATOMIC_RELEASE);
}

static void jn_rc_shard_lock(JnRcShard* s) {
    jn_spin_lock(&s->lock);
}

static void jn_rc_shard_unlock(JnRcShard* s) {
    jn_spin_unlock(&s->lock);
}

static JnRegReader* jn_reg_reader(void) {
    JnRegReader* r = jn_reg_self;
    if (r != NULL) return r;
    for (r = __atomic_load_n(&jn_reg_readers, __ATOMIC_ACQUIRE); r != NULL; r = r->next) {
        int expected = 0;
        if (__atomic_compare_exchange_n(&r->used, &expected, 1, 0, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
            jn_reg_self = r;
            return r;
        }
    }
    r = (JnRegReader*)calloc(1, sizeof(JnRegReader));
    if (r == NULL) __jnative_throw_out_of_memory_error_ctx("ARC registry reader");
    r->used = 1;
    JnRegReader* head = __atomic_load_n(&jn_reg_readers, __ATOMIC_ACQUIRE);
    do {
        r->next = head;
    } while (!__atomic_compare_exchange_n(&jn_reg_readers, &head, r, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE));
    jn_reg_self = r;
    return r;
}

static JnRegReader* jn_reg_enter(void) {
    JnRegReader* r = jn_reg_reader();
    __atomic_store_n(&r->epoch, __atomic_load_n(&jn_reg_epoch, __ATOMIC_SEQ_CST), __ATOMIC_SEQ_CST);
    return r;
}

static void jn_reg_exit(JnRegReader* r) {
    __atomic_store_n(&r->epoch, 0, __ATOMIC_RELEASE);
}

static void jn_reg_thread_exit(void) {
    JnRegReader* r = jn_reg_self;
    if (r == NULL) return;
    __atomic_store_n(&r->epoch, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&r->used, 0, __ATOMIC_RELEASE);
    jn_reg_self = NULL;
}

static void jn_rc_tables_reclaim(void) {
    if (__atomic_load_n(&jn_rc_retired_tables, __ATOMIC_ACQUIRE) == NULL) return;
    jn_spin_lock(&jn_rc_retired_tables_lock);
    JnRcTable* list = jn_rc_retired_tables;
    __atomic_store_n(&jn_rc_retired_tables, NULL, __ATOMIC_RELEASE);
    jn_spin_unlock(&jn_rc_retired_tables_lock);
    if (list == NULL) return;
    uint64_t oldest = UINT64_MAX;
    for (JnRegReader* r = __atomic_load_n(&jn_reg_readers, __ATOMIC_ACQUIRE); r != NULL; r = r->next) {
        uint64_t e = __atomic_load_n(&r->epoch, __ATOMIC_SEQ_CST);
        if (e != 0 && e < oldest) oldest = e;
    }
    JnRcTable* keep = NULL;
    while (list != NULL) {
        JnRcTable* next = list->retire_next;
        if (list->retire_epoch < oldest) {
            free(list);
        } else {
            list->retire_next = keep;
            keep = list;
        }
        list = next;
    }
    if (keep == NULL) return;
    jn_spin_lock(&jn_rc_retired_tables_lock);
    JnRcTable* tail = keep;
    while (tail->retire_next != NULL) tail = tail->retire_next;
    tail->retire_next = jn_rc_retired_tables;
    __atomic_store_n(&jn_rc_retired_tables, keep, __ATOMIC_RELEASE);
    jn_spin_unlock(&jn_rc_retired_tables_lock);
}

static void jn_rc_table_retire(JnRcTable* t) {
    t->retire_epoch = __atomic_fetch_add(&jn_reg_epoch, 1, __ATOMIC_SEQ_CST);
    jn_spin_lock(&jn_rc_retired_tables_lock);
    t->retire_next = jn_rc_retired_tables;
    __atomic_store_n(&jn_rc_retired_tables, t, __ATOMIC_RELEASE);
    jn_spin_unlock(&jn_rc_retired_tables_lock);
    jn_rc_tables_reclaim();
}

static JnRcTable* jn_rc_table_new(size_t cap) {
    JnRcTable* t = (JnRcTable*)calloc(1, sizeof(JnRcTable) + cap * (sizeof(void*) + sizeof(JnRcArray*)));
    if (t == NULL) return NULL;
    t->cap = cap;
    t->keys = (void**)(t + 1);
    t->nodes = (JnRcArray**)(t->keys + cap);
    return t;
}

static size_t jn_rc_table_slot(JnRcTable* t, void* p, uintptr_t h) {
    if (t == NULL) return 0;
    size_t mask = t->cap - 1;
    for (size_t i = (size_t)h & mask;; i = (i + 1) & mask) {
        void* k = __atomic_load_n(&t->keys[i], __ATOMIC_ACQUIRE);
        if (k == p) return i;
        if (k == NULL) return t->cap;
    }
}

static int jn_rc_shard_grow(JnRcShard* s) {
    size_t cap = 256;
    while (cap < (s->live + 1) * 4) cap <<= 1;
    JnRcTable* t = jn_rc_table_new(cap);
    if (t == NULL) return 0;
    JnRcTable* old = s->table;
    if (old != NULL) {
        for (size_t j = 0; j < old->cap; j++) {
            void* k = old->keys[j];
            if (k == NULL || k == JN_RC_TOMB) continue;
            size_t i = (size_t)jnative_mix((uintptr_t)k) & (cap - 1);
            while (t->keys[i] != NULL) i = (i + 1) & (cap - 1);
            t->keys[i] = k;
            t->nodes[i] = old->nodes[j];
        }
    }
    __atomic_store_n(&s->table, t, __ATOMIC_SEQ_CST);
    s->used = s->live;
    if (old != NULL) jn_rc_table_retire(old);
    return 1;
}

#define JN_RC_KLASS_MAX 256
static void* jn_rc_klasses[JN_RC_KLASS_MAX];
static int   jn_rc_klass_n;
static int   jn_rc_klass_overflow;
static int   jn_rc_klass_lock;

static int jn_rc_klass_managed(void* klass) {
    if (__atomic_load_n(&jn_rc_klass_overflow, __ATOMIC_ACQUIRE)) return 1;
    int n = __atomic_load_n(&jn_rc_klass_n, __ATOMIC_ACQUIRE);
    for (int i = 0; i < n; i++) {
        if (__atomic_load_n(&jn_rc_klasses[i], __ATOMIC_RELAXED) == klass) return 1;
    }
    return 0;
}

static void jn_rc_klass_add(void* klass) {
    if (jn_rc_klass_managed(klass)) return;
    jn_spin_lock(&jn_rc_klass_lock);
    int n = jn_rc_klass_n;
    int found = 0;
    for (int i = 0; i < n && !found; i++) found = jn_rc_klasses[i] == klass;
    if (!found) {
        if (n < JN_RC_KLASS_MAX) {
            __atomic_store_n(&jn_rc_klasses[n], klass, __ATOMIC_RELAXED);
            __atomic_store_n(&jn_rc_klass_n, n + 1, __ATOMIC_RELEASE);
        } else {
            __atomic_store_n(&jn_rc_klass_overflow, 1, __ATOMIC_RELEASE);
        }
    }
    jn_spin_unlock(&jn_rc_klass_lock);
}

static JnRcArray* jn_rc_array_find(void* p) {
    uintptr_t h = jnative_mix((uintptr_t)p);
    JnRcShard* s = jn_rc_shard(h);
    JnRegReader* r = jn_reg_enter();
    JnRcTable* t = __atomic_load_n(&s->table, __ATOMIC_SEQ_CST);
    JnRcArray* array = NULL;
    if (t != NULL) {
        size_t i = jn_rc_table_slot(t, p, h);
        if (i < t->cap) array = __atomic_load_n(&t->nodes[i], __ATOMIC_ACQUIRE);
    }
    jn_reg_exit(r);
    return array;
}

static int jn_rc_array_insert(JnRcArray* array) {
    uintptr_t h = jnative_mix((uintptr_t)array->array);
    JnRcShard* s = jn_rc_shard(h);
    jn_rc_shard_lock(s);
    JnRcTable* t = s->table;
    if ((t == NULL || (s->used + 1) * 2 > t->cap) && !jn_rc_shard_grow(s)) {
        jn_rc_shard_unlock(s);
        return 0;
    }
    t = s->table;
    size_t mask = t->cap - 1;
    size_t i = (size_t)h & mask;
    while (t->keys[i] != NULL && t->keys[i] != JN_RC_TOMB) i = (i + 1) & mask;
    if (t->keys[i] == NULL) s->used++;
    __atomic_store_n(&t->nodes[i], array, __ATOMIC_RELEASE);
    __atomic_store_n(&t->keys[i], array->array, __ATOMIC_RELEASE);
    s->live++;
    jn_rc_shard_unlock(s);
    return 1;
}

static JnRcArray* jn_rc_array_remove(void* p) {
    uintptr_t h = jnative_mix((uintptr_t)p);
    JnRcShard* s = jn_rc_shard(h);
    jn_rc_shard_lock(s);
    JnRcTable* t = s->table;
    size_t i = jn_rc_table_slot(t, p, h);
    JnRcArray* array = NULL;
    if (t != NULL && i < t->cap) {
        array = t->nodes[i];
        __atomic_store_n(&t->keys[i], JN_RC_TOMB, __ATOMIC_RELEASE);
        __atomic_store_n(&t->nodes[i], NULL, __ATOMIC_RELEASE);
        s->live--;
    }
    jn_rc_shard_unlock(s);
    return array;
}

static int jn_rc_managed_array(void* p) {
    if (p == NULL) return 0;
    void* klass = *(void**)p;
    return klass != NULL && jn_rc_klass_managed(klass) && jn_rc_array_find(p) != NULL;
}

void** __jnative_referent_slot(void* ref) {
    static int offset;
    int off = __atomic_load_n(&offset, __ATOMIC_ACQUIRE);
    if (off == 0) {
        off = jnative_field_offset_in(jnative_class_by_name("java/lang/ref/Reference"), "referent");
        if (off <= 0) off = OBJECT_HEADER_SIZE;
        __atomic_store_n(&offset, off, __ATOMIC_RELEASE);
    }
    return (void**)((char*)ref + off);
}

static int jn_rc_in_static(void* p) {
    uintptr_t addr = (uintptr_t)p;
    return jnative_static_low != 0 && jnative_static_high != 0
        && addr >= jnative_static_low && addr < jnative_static_high;
}

static size_t jn_rc_symbol(char* out, size_t cap, const char* prefix, const char* name) {
    size_t pos = 0;
    for (const char* c = prefix; *c != '\0'; c++) {
        if (pos + 1 >= cap) return 0;
        out[pos++] = *c;
    }
    for (const char* c = name; *c != '\0'; c++) {
        if (pos + 1 >= cap) return 0;
        char ch = *c;
        int keep = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '$';
        out[pos++] = keep ? ch : '_';
    }
    out[pos] = '\0';
    return pos;
}

static JnRcInfo* jn_rc_resolve(void* key) {
    char symbol[1100];
    if (__jnative_is_class_mirror(key)) {
        ReflectionClass* m = (ReflectionClass*)key;
        if (m->cname == NULL || !jn_rc_symbol(symbol, sizeof(symbol), "__jnative_rcarray_", m->cname)) return &jn_rc_plain;
        if (jn_dlsym_default(symbol) == NULL) return &jn_rc_plain;
        JnRcInfo* info = (JnRcInfo*)calloc(1, sizeof(JnRcInfo));
        if (info == NULL) return &jn_rc_plain;
        info->offset = -1;
        info->flags = JN_RC_REF_ARRAY;
        return info;
    }
    JNativeVTable* vt = (JNativeVTable*)key;
    if (vt->name == NULL) return &jn_rc_plain;
    JnRcInfo* info = (JnRcInfo*)calloc(1, sizeof(JnRcInfo));
    if (info == NULL) return &jn_rc_plain;
    info->offset = -1;
    if (jn_rc_symbol(symbol, sizeof(symbol), "__jnative_rcoff_", vt->name)) {
        const int32_t* off = (const int32_t*)jn_dlsym_default(symbol);
        if (off != NULL) info->offset = *off;
    }
    if (jn_rc_symbol(symbol, sizeof(symbol), "__jnative_rcfields_", vt->name)) {
        info->fields = (const int32_t*)jn_dlsym_default(symbol);
    }
    if (jn_rc_symbol(symbol, sizeof(symbol), "__jnative_rccyclic_", vt->name)
            && jn_dlsym_default(symbol) != NULL) {
        info->flags |= JN_RC_CYCLIC;
        if (jn_rc_symbol(symbol, sizeof(symbol), "__jnative_rcowned_", vt->name)) {
            info->owned = (const int32_t*)jn_dlsym_default(symbol);
        }
    }
    for (ReflectionClass* c = jnative_class_by_name(vt->name); c != NULL; c = c->superclass) {
        if (c->cname != NULL && strcmp(c->cname, "java/lang/ref/Reference") == 0) {
            info->flags |= JN_RC_REFERENCE;
            break;
        }
    }
    if (info->offset < 0 && info->fields == NULL && info->flags == 0) {
        free(info);
        return &jn_rc_plain;
    }
    return info;
}

static JnRcInfo* jn_rc_info(void* key) {
    size_t mask = JN_RC_SLOTS - 1;
    size_t start = (size_t)(jnative_mix((uintptr_t)key) & mask);
    size_t i = start;
    for (size_t probe = 0; probe < JN_RC_SLOTS; probe++, i = (i + 1) & mask) {
        void* k = __atomic_load_n(&jn_rc_keys[i], __ATOMIC_ACQUIRE);
        if (k == key) return __atomic_load_n(&jn_rc_vals[i], __ATOMIC_RELAXED);
        if (k == NULL) break;
    }
    JnRcInfo* info = jn_rc_resolve(key);
    jn_mutex_lock(&jn_rc_lock);
    i = start;
    for (size_t probe = 0; probe < JN_RC_SLOTS; probe++, i = (i + 1) & mask) {
        void* k = __atomic_load_n(&jn_rc_keys[i], __ATOMIC_ACQUIRE);
        if (k == key) {
            JnRcInfo* existing = __atomic_load_n(&jn_rc_vals[i], __ATOMIC_RELAXED);
            jn_mutex_unlock(&jn_rc_lock);
            if (info != &jn_rc_plain && info != existing) free(info);
            return existing;
        }
        if (k != NULL) continue;
        __atomic_store_n(&jn_rc_vals[i], info, __ATOMIC_RELAXED);
        __atomic_store_n(&jn_rc_keys[i], key, __ATOMIC_RELEASE);
        break;
    }
    jn_mutex_unlock(&jn_rc_lock);
    return info;
}

static JnRcInfo* jn_rc_of(void* p) {
    if (p == NULL || jn_rc_in_static(p)) return NULL;
    void* vt = *(void**)p;
    if (vt == NULL) return NULL;
    return jn_rc_info(vt);
}

static uint32_t* jn_rc_word_locked(void* p) {
    JnRcInfo* info = jn_rc_of(p);
    if (info == NULL) return NULL;
    if (info->offset >= 0) return (uint32_t*)((char*)p + info->offset);
    if (!jn_rc_klass_managed(*(void**)p)) return NULL;
    JnRcArray* array = jn_rc_array_find(p);
    return array == NULL ? NULL : &array->state;
}

static size_t jn_weak_bucket(void* referent) {
    return (size_t)(jnative_mix((uintptr_t)referent) & (JN_WEAK_BUCKETS - 1));
}

/* All registry operations and promotions share the lock with the hold snapshot. */
static void jn_weak_clear_locked(void* referent) {
    JnWeakNode** link = &jn_weak[jn_weak_bucket(referent)];
    while (*link != NULL) {
        JnWeakNode* node = *link;
        if (node->referent == referent) {
            __atomic_store_n(node->slot, NULL, __ATOMIC_RELEASE);
            *link = node->next;
            free(node);
        } else {
            link = &node->next;
        }
    }
}

void __jnative_weak_store(void** slot, void* referent) {
    JnWeakNode* node = NULL;
    jn_mutex_lock(&jn_weak_mutex);
    if (jn_rc_word_locked(referent) != NULL) {
        node = (JnWeakNode*)malloc(sizeof(JnWeakNode));
        if (node == NULL) {
            jn_mutex_unlock(&jn_weak_mutex);
            __jnative_throw_out_of_memory_error_ctx("Reference.referent");
        }
        node->slot = slot;
        node->referent = referent;
    }
    void* old = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    if (old != NULL) {
        JnWeakNode** link = &jn_weak[jn_weak_bucket(old)];
        while (*link != NULL) {
            JnWeakNode* item = *link;
            if (item->slot == slot) {
                *link = item->next;
                free(item);
                break;
            }
            link = &item->next;
        }
    }
    if (node != NULL) {
        size_t b = jn_weak_bucket(referent);
        node->next = jn_weak[b];
        jn_weak[b] = node;
    }
    __atomic_store_n(slot, referent, __ATOMIC_RELEASE);
    jn_mutex_unlock(&jn_weak_mutex);
}

void __jnative_weak_forget(void* ref) {
    __jnative_weak_store(__jnative_referent_slot(ref), NULL);
}

static void jn_hold_set(void** hold, void* value);

void* __jnative_weak_load(void** slot, void** hold) {
    jn_mutex_lock(&jn_weak_mutex);
    void* value = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    uint32_t* word = value == NULL ? NULL : jn_rc_word_locked(value);
    if (word != NULL && (__atomic_load_n(word, __ATOMIC_SEQ_CST) & JN_RC_RETIRED)) {
        __atomic_fetch_or(word, JN_RC_REVIVED, __ATOMIC_SEQ_CST);
    }
    jn_hold_set(hold, value);
    jn_mutex_unlock(&jn_weak_mutex);
    return value;
}

static int jn_rc_word_inc(uint32_t* word) {
    uint32_t w = __atomic_load_n(word, __ATOMIC_RELAXED);
    for (;;) {
        if (w & (JN_RC_STACK | JN_RC_IMMORTAL)) return 0;
        uint32_t next = (w & JN_RC_COUNT) == JN_RC_COUNT
            ? w | JN_RC_IMMORTAL
            : (w + 1) | ((w & JN_RC_RETIRED) ? JN_RC_REVIVED : 0u);
        if (__atomic_compare_exchange_n(word, &w, next, 1, __ATOMIC_SEQ_CST, __ATOMIC_RELAXED)) return 0;
    }
}

static int jn_rc_word_dec(uint32_t* word) {
    uint32_t w = __atomic_load_n(word, __ATOMIC_RELAXED);
    for (;;) {
        if (!(w & JN_RC_COUNT) || (w & JN_RC_IMMORTAL)) return 0;
        uint32_t next = w - 1;
        int queue = !(next & JN_RC_COUNT) && !(w & JN_RC_RETIRED);
        if (queue) next |= JN_RC_RETIRED;
        if (__atomic_compare_exchange_n(word, &w, next, 1, __ATOMIC_SEQ_CST, __ATOMIC_RELAXED)) {
            return queue ? 2 : (next & JN_RC_COUNT) ? 3 : 1;
        }
    }
}

static int jn_rc_word_drop(uint32_t* word) {
    uint32_t zero = 0;
    return __atomic_compare_exchange_n(word, &zero, JN_RC_RETIRED, 0, __ATOMIC_SEQ_CST, __ATOMIC_RELAXED) ? 2 : 0;
}

static void jn_rc_inc_locked(void* p) {
    uint32_t* word = p == NULL ? NULL : jn_rc_word_locked(p);
    if (word != NULL) jn_rc_word_inc(word);
}

static int jn_rc_queue_locked(void* p);
static int jn_rc_retire_local(void* p);
static void jn_rc_buffer_cycle_candidate(void* p, uint32_t* word);

static int jn_rc_apply(void* p, int (*op)(uint32_t*)) {
    uint32_t* word = jn_rc_word_locked(p);
    if (word == NULL) return 0;
    int r = op(word);
    if (r == 2 && !jn_rc_retire_local(p)) __jnative_throw_out_of_memory_error_ctx("ARC retirement");
    if (r == 3) jn_rc_buffer_cycle_candidate(p, word);
    return r == 1 || r == 2;
}

void __jnative_rc_inc(void* p) {
    if (p != NULL) jn_rc_apply(p, jn_rc_word_inc);
}

void __jnative_rc_pin(void* p) {
    uint32_t* word = p == NULL ? NULL : jn_rc_word_locked(p);
    if (word != NULL && !(__atomic_load_n(word, __ATOMIC_RELAXED) & JN_RC_STACK)) {
        __atomic_fetch_or(word, JN_RC_IMMORTAL, __ATOMIC_SEQ_CST);
    }
}

static __thread uintptr_t jn_stack_lo;
static __thread uintptr_t jn_stack_hi;

static int jn_on_own_stack(const void* p) {
    if (jn_stack_hi == 0) {
        uintptr_t lo = UINTPTR_MAX;
        uintptr_t hi = UINTPTR_MAX;
#if defined(_WIN32)
        ULONG_PTR low = 0;
        ULONG_PTR high = 0;
        GetCurrentThreadStackLimits(&low, &high);
        if (high > low) {
            lo = (uintptr_t)low;
            hi = (uintptr_t)high;
        }
#elif defined(__APPLE__)
        pthread_t self = pthread_self();
        uintptr_t top = (uintptr_t)pthread_get_stackaddr_np(self);
        size_t size = pthread_get_stacksize_np(self);
        if (top > size) {
            lo = top - size;
            hi = top;
        }
#else
        pthread_attr_t attr;
        if (pthread_getattr_np(pthread_self(), &attr) == 0) {
            void* addr = NULL;
            size_t size = 0;
            if (pthread_attr_getstack(&attr, &addr, &size) == 0 && addr != NULL && size > 0) {
                lo = (uintptr_t)addr;
                hi = (uintptr_t)addr + size;
            }
            pthread_attr_destroy(&attr);
        }
#endif
        jn_stack_lo = lo;
        jn_stack_hi = hi;
    }
    uintptr_t a = (uintptr_t)p;
    return a >= jn_stack_lo && a < jn_stack_hi;
}

void __jnative_rc_capture(void* closure, void* value) {
    if (value == NULL) return;
    if (jn_on_own_stack(value)) {
        __jnative_rc_pin(closure);
        return;
    }
    __jnative_rc_inc(value);
}

#define JN_HOLD_CHUNK 32768

typedef struct JnHoldChunk {
    void**              slots;
    size_t              cap;
    size_t              top;
    struct JnHoldChunk* next;
} JnHoldChunk;

typedef struct JnStackDrop {
    void* object;
    struct JnStackDrop* next;
} JnStackDrop;

typedef struct {
    JnHoldChunk* chunk;
    size_t       start;
    int32_t      n;
    void**       holds;
    JnStackDrop* drops;
} JnHoldFrame;

typedef struct JnHoldThread {
    JnHoldChunk*         first;
    void*                ret;
    jn_mutex_t           lock;
    uint32_t             seq;
    int                  scan_req;
    struct JnHoldThread* next;
} JnHoldThread;

static JnHoldThread*           jn_hold_threads;
static jn_mutex_t              jn_hold_list_lock = JN_MUTEX_INITIALIZER;
static __thread JnHoldThread*  jn_hold_self;
static __thread JnHoldChunk*   jn_hold_cur;
static __thread JnHoldFrame*   jn_hold_frames;
static __thread int            jn_hold_frame_n;
static __thread int            jn_hold_frame_cap;
static void** jn_rc_retired;
static size_t jn_rc_retired_n;
static size_t jn_rc_retired_cap;
static int    jn_rc_scan_busy;
#define JN_RC_SCAN_MIN 65536
static size_t jn_rc_scan_at = JN_RC_SCAN_MIN;
#define JN_RC_SCAN_MIN_BYTES ((size_t)64 << 20)
#define JN_RC_BACKLOG_FACTOR 4
static size_t jn_rc_retired_bytes;
static size_t jn_rc_scan_bytes_at = JN_RC_SCAN_MIN_BYTES;
static jn_mutex_t jn_rc_wait_lock = JN_MUTEX_INITIALIZER;
static jn_cond_t  jn_rc_wait_cond = JN_COND_INITIALIZER;
static __thread int jn_rc_in_scan;

#define JN_RC_LOCAL_CAP   1024
#define JN_RC_LOCAL_FLUSH 256
static __thread void** jn_rc_local;
static __thread size_t jn_rc_local_n;

static void jn_rc_scan(void);

static int jn_rc_flush_local(void) {
    size_t k = jn_rc_local_n;
    if (k == 0) return 1;
    int ok = 1;
    jn_mutex_lock(&jn_weak_mutex);
    for (size_t i = 0; i < k && ok; i++) ok = jn_rc_queue_locked(jn_rc_local[i]);
    jn_mutex_unlock(&jn_weak_mutex);
    jn_rc_local_n = 0;
    return ok;
}

static int jn_rc_retire_local(void* p) {
    if (jn_rc_local == NULL) {
        jn_rc_local = (void**)malloc(JN_RC_LOCAL_CAP * sizeof(void*));
        if (jn_rc_local == NULL) {
            jn_mutex_lock(&jn_weak_mutex);
            int ok = jn_rc_queue_locked(p);
            jn_mutex_unlock(&jn_weak_mutex);
            return ok;
        }
    }
    jn_rc_local[jn_rc_local_n++] = p;
    return jn_rc_local_n < JN_RC_LOCAL_CAP || jn_rc_flush_local();
}

#define JN_CYC_LOCAL_CAP   512
#define JN_CYC_LOCAL_FLUSH 128
#define JN_CYC_SCAN_AT     ((size_t)65536)
#define JN_CYC_MAX_NODES   ((size_t)4 << 20)
static size_t          jn_cyc_scan_at = JN_CYC_SCAN_AT;
static size_t          jn_cyc_max_nodes = JN_CYC_MAX_NODES;
static size_t          jn_cyc_prune_at = JN_CYC_SCAN_AT;
static __thread void** jn_cyc_local;
static __thread size_t jn_cyc_local_n;
static void**          jn_cyc_roots;
static size_t          jn_cyc_roots_n;
static size_t          jn_cyc_roots_cap;

static int jn_cyc_push_locked(void* p) {
    if (jn_cyc_roots_n == jn_cyc_roots_cap) {
        size_t cap = jn_cyc_roots_cap == 0 ? 1024 : jn_cyc_roots_cap * 2;
        void** grown = (void**)realloc(jn_cyc_roots, cap * sizeof(void*));
        if (grown == NULL) return 0;
        jn_cyc_roots = grown;
        jn_cyc_roots_cap = cap;
    }
    jn_cyc_roots[jn_cyc_roots_n] = p;
    __atomic_store_n(&jn_cyc_roots_n, jn_cyc_roots_n + 1, __ATOMIC_RELAXED);
    return 1;
}

static void jn_cyc_unbuffer(void* p) {
    uint32_t* word = jn_rc_word_locked(p);
    if (word != NULL) __atomic_fetch_and(word, ~JN_RC_BUFFERED, __ATOMIC_SEQ_CST);
}

static void jn_cyc_flush_local(void) {
    size_t k = jn_cyc_local_n;
    if (k == 0) return;
    jn_mutex_lock(&jn_weak_mutex);
    for (size_t i = 0; i < k; i++) {
        if (!jn_cyc_push_locked(jn_cyc_local[i])) jn_cyc_unbuffer(jn_cyc_local[i]);
    }
    jn_mutex_unlock(&jn_weak_mutex);
    jn_cyc_local_n = 0;
}

static void jn_rc_buffer_cycle_candidate(void* p, uint32_t* word) {
    JnRcInfo* info = jn_rc_of(p);
    if (info == NULL || !(info->flags & JN_RC_CYCLIC)) return;
    uint32_t w = __atomic_load_n(word, __ATOMIC_RELAXED);
    for (;;) {
        if ((w & (JN_RC_BUFFERED | JN_RC_STACK)) || !(w & JN_RC_COUNT)) return;
        if (__atomic_compare_exchange_n(word, &w, w | JN_RC_BUFFERED, 1, __ATOMIC_SEQ_CST, __ATOMIC_RELAXED)) break;
    }
    if (jn_cyc_local == NULL) {
        jn_cyc_local = (void**)malloc(JN_CYC_LOCAL_CAP * sizeof(void*));
        if (jn_cyc_local == NULL) {
            __atomic_fetch_and(word, ~JN_RC_BUFFERED, __ATOMIC_SEQ_CST);
            return;
        }
    }
    jn_cyc_local[jn_cyc_local_n++] = p;
    if (jn_cyc_local_n == JN_CYC_LOCAL_CAP) jn_cyc_flush_local();
}

static int jn_rc_over(size_t factor) {
    return __atomic_load_n(&jn_rc_retired_n, __ATOMIC_RELAXED) >= factor * __atomic_load_n(&jn_rc_scan_at, __ATOMIC_RELAXED)
        || __atomic_load_n(&jn_rc_retired_bytes, __ATOMIC_RELAXED) >= factor * __atomic_load_n(&jn_rc_scan_bytes_at, __ATOMIC_RELAXED);
}

static int jn_rc_cycles_due(void) {
    return __atomic_load_n(&jn_cyc_roots_n, __ATOMIC_RELAXED) >= __atomic_load_n(&jn_cyc_scan_at, __ATOMIC_RELAXED);
}

static int        jn_rc_wake;
static int        jn_rc_daemon_state;
static jn_cond_t  jn_rc_daemon_cond = JN_COND_INITIALIZER;
static jn_once_t  jn_rc_daemon_once = JN_ONCE_INIT;
static jn_thread_t jn_rc_daemon_thread;

static void* jn_rc_daemon(void* arg) {
    (void)arg;
    jn_mutex_lock(&jn_rc_wait_lock);
    for (;;) {
        while (!__atomic_load_n(&jn_rc_wake, __ATOMIC_ACQUIRE)) jn_cond_wait(&jn_rc_daemon_cond, &jn_rc_wait_lock);
        __atomic_store_n(&jn_rc_wake, 0, __ATOMIC_RELEASE);
        jn_mutex_unlock(&jn_rc_wait_lock);
        if (!gv_jnative_in_shutdown) jn_rc_scan();
        jn_mutex_lock(&jn_rc_wait_lock);
        if (!gv_jnative_in_shutdown && (jn_rc_over(1) || jn_rc_cycles_due())) {
            __atomic_store_n(&jn_rc_wake, 1, __ATOMIC_RELEASE);
        }
        jn_cond_broadcast(&jn_rc_wait_cond);
    }
    return NULL;
}

static void jn_rc_daemon_start(void) {
    int ok = jn_thread_create(&jn_rc_daemon_thread, NULL, jn_rc_daemon, NULL) == 0;
    __atomic_store_n(&jn_rc_daemon_state, ok ? 1 : -1, __ATOMIC_RELEASE);
}

static int jn_rc_request_scan(void) {
    if (jn_rc_in_scan) return 1;
    jn_once(&jn_rc_daemon_once, jn_rc_daemon_start);
    if (__atomic_load_n(&jn_rc_daemon_state, __ATOMIC_ACQUIRE) <= 0) return 0;
    if (__atomic_load_n(&jn_rc_wake, __ATOMIC_RELAXED)) return 1;
    jn_mutex_lock(&jn_rc_wait_lock);
    if (!__atomic_load_n(&jn_rc_wake, __ATOMIC_RELAXED)) {
        __atomic_store_n(&jn_rc_wake, 1, __ATOMIC_RELEASE);
        jn_cond_signal(&jn_rc_daemon_cond);
    }
    jn_mutex_unlock(&jn_rc_wait_lock);
    return 1;
}

static void jn_rc_kick(void) {
    if (!jn_rc_request_scan()) jn_rc_scan();
}

static void jn_rc_maybe_scan(void) {
    if (jn_rc_in_scan) return;
    if (jn_rc_local_n >= JN_RC_LOCAL_FLUSH && !jn_rc_flush_local()) {
        __jnative_throw_out_of_memory_error_ctx("ARC retirement");
    }
    if (jn_cyc_local_n >= JN_CYC_LOCAL_FLUSH) jn_cyc_flush_local();
    if (!jn_rc_over(1) && !jn_rc_cycles_due()) return;
    jn_rc_kick();
    if (!jn_rc_over(JN_RC_BACKLOG_FACTOR)) return;
    jn_mutex_lock(&jn_rc_wait_lock);
    while (jn_rc_over(JN_RC_BACKLOG_FACTOR)
           && (__atomic_load_n(&jn_rc_scan_busy, __ATOMIC_ACQUIRE) || __atomic_load_n(&jn_rc_wake, __ATOMIC_ACQUIRE))) {
        jn_cond_wait(&jn_rc_wait_cond, &jn_rc_wait_lock);
    }
    jn_mutex_unlock(&jn_rc_wait_lock);
}

static int       jn_rc_asym;
static jn_once_t jn_rc_asym_once = JN_ONCE_INIT;

static void jn_rc_asym_init(void) {
#if defined(__linux__) && defined(__NR_membarrier)
    long cmds = syscall(__NR_membarrier, MEMBARRIER_CMD_QUERY, 0, 0);
    if (cmds > 0
            && (cmds & MEMBARRIER_CMD_PRIVATE_EXPEDITED)
            && (cmds & MEMBARRIER_CMD_REGISTER_PRIVATE_EXPEDITED)
            && syscall(__NR_membarrier, MEMBARRIER_CMD_REGISTER_PRIVATE_EXPEDITED, 0, 0) == 0) {
        __atomic_store_n(&jn_rc_asym, 1, __ATOMIC_RELEASE);
    }
#elif defined(_WIN32)
    __atomic_store_n(&jn_rc_asym, 1, __ATOMIC_RELEASE);
#endif
}

static void jn_rc_process_fence(void) {
    if (__atomic_load_n(&jn_rc_asym, __ATOMIC_ACQUIRE)) {
#if defined(__linux__) && defined(__NR_membarrier)
        if (syscall(__NR_membarrier, MEMBARRIER_CMD_PRIVATE_EXPEDITED, 0, 0) != 0) abort();
#elif defined(_WIN32)
        FlushProcessWriteBuffers();
#endif
    }
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
}

static void jn_hold_write(JnHoldThread* t, void** hold, void* value) {
    uint32_t s = t->seq;
    __atomic_store_n(&t->seq, s + 1, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    if (__atomic_load_n(&jn_rc_asym, __ATOMIC_RELAXED)) {
        __atomic_store_n(hold, value, __ATOMIC_RELEASE);
        __atomic_signal_fence(__ATOMIC_SEQ_CST);
    } else {
        __atomic_store_n(hold, value, __ATOMIC_SEQ_CST);
    }
    __atomic_store_n(&t->seq, s + 2, __ATOMIC_RELEASE);
}

static void jn_hold_set(void** hold, void* value) {
    if (__atomic_load_n(hold, __ATOMIC_RELAXED) == value) return;
    JnHoldThread* t = jn_hold_self;
    if (value == NULL || t == NULL) {
        __atomic_store_n(hold, value, __ATOMIC_SEQ_CST);
        return;
    }
    if (__atomic_load_n(&t->scan_req, __ATOMIC_ACQUIRE)) {
        jn_mutex_lock(&t->lock);
        jn_hold_write(t, hold, value);
        jn_mutex_unlock(&t->lock);
        return;
    }
    jn_hold_write(t, hold, value);
}

static JnHoldChunk* jn_hold_new_chunk(size_t cap) {
    JnHoldChunk* c = (JnHoldChunk*)calloc(1, sizeof(JnHoldChunk));
    void** slots = (void**)calloc(cap, sizeof(void*));
    if (c == NULL || slots == NULL) {
        free(c);
        free(slots);
        __jnative_throw_out_of_memory_error_ctx("__jnative_rc_frame_enter");
    }
    c->slots = slots;
    c->cap = cap;
    return c;
}

static JnHoldThread* jn_hold_thread(void) {
    JnHoldThread* t = jn_hold_self;
    if (t != NULL) return t;
    jn_once(&jn_rc_asym_once, jn_rc_asym_init);
    t = (JnHoldThread*)calloc(1, sizeof(JnHoldThread));
    if (t == NULL) __jnative_throw_out_of_memory_error_ctx("__jnative_rc_frame_enter");
    t->first = jn_hold_new_chunk(JN_HOLD_CHUNK);
    jn_mutex_init(&t->lock, NULL);
    jn_hold_cur = t->first;
    jn_mutex_lock(&jn_hold_list_lock);
    t->next = jn_hold_threads;
    jn_hold_threads = t;
    jn_mutex_unlock(&jn_hold_list_lock);
    jn_hold_self = t;
    return t;
}

static void jn_hold_pop_to(int keep) {
    while (jn_hold_frame_n > keep) {
        JnHoldFrame* f = &jn_hold_frames[jn_hold_frame_n - 1];
        while (f->drops != NULL) {
            void* object = f->drops->object;
            f->drops = f->drops->next;
            __jnative_free_container_storage(object);
            jn_mutex_lock(&jn_weak_mutex);
            jn_weak_clear_locked(object);
            jn_mutex_unlock(&jn_weak_mutex);
            /* A generated storage destructor may have grown the frame vector. */
            f = &jn_hold_frames[jn_hold_frame_n - 1];
        }
        jn_hold_frame_n--;
        for (int32_t i = 0; i < f->n; i++) __atomic_store_n(&f->holds[i], NULL, __ATOMIC_RELEASE);
        __atomic_store_n(&f->chunk->top, f->start, __ATOMIC_RELEASE);
        jn_hold_cur = f->chunk;
    }
}

__attribute__((noinline)) void** __jnative_rc_frame_enter(int32_t n) {
    jn_hold_thread();
    if (jn_hold_frame_n == jn_hold_frame_cap) {
        int cap = jn_hold_frame_cap == 0 ? 64 : jn_hold_frame_cap * 2;
        JnHoldFrame* grown = (JnHoldFrame*)realloc(jn_hold_frames, (size_t)cap * sizeof(JnHoldFrame));
        if (grown == NULL) __jnative_throw_out_of_memory_error_ctx("__jnative_rc_frame_enter");
        jn_hold_frames = grown;
        jn_hold_frame_cap = cap;
    }
    JnHoldChunk* c = jn_hold_cur;
    while (c->top + (size_t)n > c->cap) {
        JnHoldChunk* next = c->next;
        if (next == NULL || next->cap < (size_t)n) {
            JnHoldChunk* fresh = jn_hold_new_chunk((size_t)n > JN_HOLD_CHUNK ? (size_t)n : JN_HOLD_CHUNK);
            fresh->next = next;
            __atomic_store_n(&c->next, fresh, __ATOMIC_RELEASE);
            next = fresh;
        }
        c = next;
        __atomic_store_n(&c->top, 0, __ATOMIC_RELEASE);
    }
    JnHoldFrame* f = &jn_hold_frames[jn_hold_frame_n++];
    f->n = n;
    f->drops = NULL;
    f->chunk = c;
    f->start = c->top;
    f->holds = &c->slots[c->top];
    __atomic_store_n(&c->top, c->top + (size_t)n, __ATOMIC_RELEASE);
    jn_hold_cur = c;
    return f->holds;
}

void __jnative_rc_stack_push(void* object, JnStackDrop* drop) {
    JnHoldFrame* frame = &jn_hold_frames[jn_hold_frame_n - 1];
    drop->object = object;
    drop->next = frame->drops;
    frame->drops = drop;
    JnRcInfo* info = jn_rc_of(object);
    if (info != NULL && info->offset >= 0) {
        *(uint32_t*)((char*)object + info->offset) = JN_RC_STACK;
    }
}

void __jnative_rc_hold(void** hold, void* object) {
    jn_hold_set(hold, object);
}

void __jnative_rc_frame_exit(void** holds, int32_t n, void* ret) {
    (void)n;
    JnHoldThread* t = jn_hold_self;
    if (t == NULL) return;
    if (ret != NULL) jn_hold_set(&t->ret, ret);
    int keep = jn_hold_frame_n;
    while (keep > 0 && jn_hold_frames[keep - 1].holds != holds) keep--;
    if (keep > 0) jn_hold_pop_to(keep - 1);
    jn_rc_maybe_scan();
}

static void jn_rc_release_local(void) {
    jn_rc_flush_local();
    free(jn_rc_local);
    jn_rc_local = NULL;
    jn_rc_local_n = 0;
    jn_cyc_flush_local();
    free(jn_cyc_local);
    jn_cyc_local = NULL;
    jn_cyc_local_n = 0;
}

void __jnative_rc_thread_exit(void) {
    JnHoldThread* t = jn_hold_self;
    if (t == NULL) {
        jn_rc_release_local();
        jn_rc_kick();
        jn_reg_thread_exit();
        return;
    }
    /* The entry trampoline calls this after its generated frames return. */
    jn_mutex_lock(&jn_hold_list_lock);
    JnHoldThread** link = &jn_hold_threads;
    while (*link != t) link = &(*link)->next;
    *link = t->next;
    jn_mutex_unlock(&jn_hold_list_lock);
    for (JnHoldChunk* chunk = t->first; chunk != NULL;) {
        JnHoldChunk* next = chunk->next;
        free(chunk->slots);
        free(chunk);
        chunk = next;
    }
    jn_mutex_destroy(&t->lock);
    free(t);
    free(jn_hold_frames);
    jn_hold_self = NULL;
    jn_hold_cur = NULL;
    jn_hold_frames = NULL;
    jn_hold_frame_n = 0;
    jn_hold_frame_cap = 0;
    jn_rc_release_local();
    jn_rc_kick();
    jn_reg_thread_exit();
}

void* __jnative_rc_load(void** addr, void** hold) {
    void* value = __atomic_load_n(addr, __ATOMIC_SEQ_CST);
    for (;;) {
        jn_hold_set(hold, value);
        if (value == NULL) return NULL;
        void* again = __atomic_load_n(addr, __ATOMIC_SEQ_CST);
        if (again == value) return value;
        value = again;
    }
}

void** __jnative_rc_ret_slot(void) {
    return &jn_hold_thread()->ret;
}

void* __jnative_rc_load_ret(void** addr) {
    JnHoldThread* t = jn_hold_thread();
    return __jnative_rc_load(addr, &t->ret);
}

void* __jnative_rc_cae(void** addr, void* expected, void* desired, void** hold) {
    __jnative_rc_inc(desired);
    void* witness;
    int swapped;
    for (;;) {
        witness = expected;
        swapped = __atomic_compare_exchange_n(addr, &witness, desired, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
        if (hold == NULL) break;
        jn_hold_set(hold, witness);
        if (swapped || witness == NULL || __atomic_load_n(addr, __ATOMIC_SEQ_CST) == witness) break;
    }
    __jnative_rc_dec(swapped ? expected : desired);
    return witness;
}

void* __jnative_rc_cae_in(void** addr, void* expected, void* desired, void** hold, void* self) {
    void* counted_desired = desired == self ? NULL : desired;
    void* counted_expected = expected == self ? NULL : expected;
    __jnative_rc_inc(counted_desired);
    void* witness;
    int swapped;
    for (;;) {
        witness = expected;
        swapped = __atomic_compare_exchange_n(addr, &witness, desired, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
        if (hold == NULL) break;
        jn_hold_set(hold, witness);
        if (swapped || witness == NULL || __atomic_load_n(addr, __ATOMIC_SEQ_CST) == witness) break;
    }
    __jnative_rc_dec(swapped ? counted_expected : counted_desired);
    return witness;
}

static size_t jn_rc_bytes_locked(void* p) {
    JnRcInfo* info = jn_rc_of(p);
    if (info == NULL || info->offset >= 0) return 0;
    int32_t len = *(int32_t*)((char*)p + JAVA_ARR_LENGTH_OFFSET);
    int32_t elem = *(int32_t*)((char*)p + JAVA_ARR_ELEM_SIZE_OFFSET);
    return JAVA_ARR_HDR + (len > 0 && elem > 0 ? (size_t)len * (size_t)elem : 0);
}

static int jn_rc_queue_locked(void* p) {
    if (jn_rc_retired_n == jn_rc_retired_cap) {
        size_t cap = jn_rc_retired_cap == 0 ? 64 : jn_rc_retired_cap * 2;
        void** grown = (void**)realloc(jn_rc_retired, cap * sizeof(void*));
        if (grown == NULL) return 0;
        jn_rc_retired = grown;
        jn_rc_retired_cap = cap;
    }
    jn_rc_retired[jn_rc_retired_n] = p;
    __atomic_store_n(&jn_rc_retired_n, jn_rc_retired_n + 1, __ATOMIC_RELAXED);
    __atomic_store_n(&jn_rc_retired_bytes, jn_rc_retired_bytes + jn_rc_bytes_locked(p), __ATOMIC_RELAXED);
    return 1;
}

static void jn_rc_register_array(void* p, void** hold) {
    JnRcArray* array = (JnRcArray*)malloc(sizeof(JnRcArray));
    if (array != NULL) {
        array->array = p;
        array->state = JN_RC_RETIRED;
        jn_rc_klass_add(*(void**)p);
        jn_hold_set(hold, p);
        jn_mutex_lock(&jn_weak_mutex);
        if (jn_rc_array_insert(array)) {
            if (jn_rc_queue_locked(p)) {
                jn_mutex_unlock(&jn_weak_mutex);
                return;
            }
            jn_rc_array_remove(p);
        }
        jn_mutex_unlock(&jn_weak_mutex);
        jn_hold_set(hold, NULL);
        free(array);
    }
    jn_free_raw(p);
    __jnative_throw_out_of_memory_error_ctx("ARC array");
}

static size_t jn_rc_hash(void* p, size_t mask) {
    return (size_t)(jnative_mix((uintptr_t)p) & mask);
}

typedef struct {
    void* object;
    int held;
} JnRcCandidate;

static JnRcCandidate* jn_rc_candidate(JnRcCandidate* table, size_t mask, void* object) {
    size_t i = jn_rc_hash(object, mask);
    while (table[i].object != NULL && table[i].object != object) i = (i + 1) & mask;
    return &table[i];
}

static void jn_rc_mark_held(JnRcCandidate* table, size_t mask, void* v) {
    if (v == NULL) return;
    JnRcCandidate* candidate = jn_rc_candidate(table, mask, v);
    if (candidate->object == v) candidate->held = 1;
}

static void jn_rc_mark_thread(JnRcCandidate* table, size_t mask, JnHoldThread* t) {
    jn_rc_mark_held(table, mask, __atomic_load_n(&t->ret, __ATOMIC_ACQUIRE));
    for (JnHoldChunk* c = t->first; c != NULL; c = __atomic_load_n(&c->next, __ATOMIC_ACQUIRE)) {
        size_t top = __atomic_load_n(&c->top, __ATOMIC_ACQUIRE);
        for (size_t k = 0; k < top; k++) {
            jn_rc_mark_held(table, mask, __atomic_load_n(&c->slots[k], __ATOMIC_ACQUIRE));
        }
    }
}

static JnRcCandidate* jn_rc_cand;
static size_t         jn_rc_cand_cap;
static size_t         jn_rc_kept_buffered;

static int jn_rc_reclaim_pending(void) {
    if (!jn_rc_flush_local()) __jnative_throw_out_of_memory_error_ctx("ARC retirement");
    jn_mutex_lock(&jn_weak_mutex);
    size_t n = jn_rc_retired_n;
    if (n == 0) {
        jn_mutex_unlock(&jn_weak_mutex);
        return 0;
    }
    size_t cap = 2;
    while (cap < n * 2) cap <<= 1;
    size_t mask = cap - 1;
    if (cap > jn_rc_cand_cap) {
        free(jn_rc_cand);
        jn_rc_cand = (JnRcCandidate*)malloc(cap * sizeof(JnRcCandidate));
        jn_rc_cand_cap = jn_rc_cand != NULL ? cap : 0;
    }
    JnRcCandidate* candidates = jn_rc_cand;
    if (candidates != NULL) memset(candidates, 0, cap * sizeof(JnRcCandidate));
    for (size_t i = 0; i < n; i++) {
        void* p = jn_rc_retired[i];
        uint32_t* word = jn_rc_word_locked(p);
        uint32_t w = __atomic_load_n(word, __ATOMIC_SEQ_CST);
        for (;;) {
            uint32_t next = (w & JN_RC_COUNT) ? w & ~(JN_RC_RETIRED | JN_RC_REVIVED) : w & ~JN_RC_REVIVED;
            if (__atomic_compare_exchange_n(word, &w, next, 1, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) break;
        }
        if (w & JN_RC_COUNT) {
            jn_rc_retired[i] = NULL;
        } else if (candidates != NULL) {
            JnRcCandidate* candidate = jn_rc_candidate(candidates, mask, p);
            candidate->object = p;
            candidate->held = 0;
        }
    }
    jn_mutex_unlock(&jn_weak_mutex);

    if (candidates != NULL) {
        jn_rc_process_fence();
        jn_mutex_lock(&jn_hold_list_lock);
        for (JnHoldThread* t = jn_hold_threads; t != NULL; t = t->next) {
            if (t == jn_hold_self) {
                jn_rc_mark_thread(candidates, mask, t);
                continue;
            }
            int locked = 0;
            for (int attempt = 0;; attempt++) {
                if (attempt == 3) {
                    __atomic_store_n(&t->scan_req, 1, __ATOMIC_SEQ_CST);
                    jn_mutex_lock(&t->lock);
                    locked = 1;
                }
                uint32_t s1 = __atomic_load_n(&t->seq, __ATOMIC_ACQUIRE);
                if (s1 & 1u) {
                    jn_yield();
                    continue;
                }
                jn_rc_mark_thread(candidates, mask, t);
                __atomic_thread_fence(__ATOMIC_ACQUIRE);
                if (__atomic_load_n(&t->seq, __ATOMIC_RELAXED) == s1) break;
            }
            if (locked) {
                jn_mutex_unlock(&t->lock);
                __atomic_store_n(&t->scan_req, 0, __ATOMIC_RELEASE);
            }
        }
        jn_mutex_unlock(&jn_hold_list_lock);
    }

    jn_mutex_lock(&jn_weak_mutex);
    size_t keep = 0;
    size_t kept_buffered = 0;
    for (size_t i = 0; i < n; i++) {
        void* p = jn_rc_retired[i];
        if (p == NULL) continue;
        JnRcCandidate* candidate = candidates == NULL ? NULL : jn_rc_candidate(candidates, mask, p);
        uint32_t* word = jn_rc_word_locked(p);
        uint32_t w = __atomic_load_n(word, __ATOMIC_SEQ_CST);
        while ((w & JN_RC_COUNT)
               && !__atomic_compare_exchange_n(word, &w, w & ~(JN_RC_RETIRED | JN_RC_REVIVED), 1, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) {}
        if (w & JN_RC_COUNT) {
            if (candidate != NULL) candidate->held = 1;
        } else if (candidate == NULL || candidate->held || (w & (JN_RC_REVIVED | JN_RC_BUFFERED))) {
            if (candidate != NULL) candidate->held = 1;
            if (w & JN_RC_BUFFERED) kept_buffered++;
            jn_rc_retired[keep++] = p;
        } else {
            jn_weak_clear_locked(p);
            free(jn_rc_array_remove(p));
        }
    }
    size_t kept_bytes = 0;
    for (size_t i = 0; i < keep; i++) kept_bytes += jn_rc_bytes_locked(jn_rc_retired[i]);
    size_t fresh_bytes = 0;
    for (size_t i = n; i < jn_rc_retired_n; i++) {
        fresh_bytes += jn_rc_bytes_locked(jn_rc_retired[i]);
        jn_rc_retired[keep++] = jn_rc_retired[i];
    }
    size_t kept_n = keep - (jn_rc_retired_n - n);
    __atomic_store_n(&jn_rc_kept_buffered, kept_buffered, __ATOMIC_RELAXED);
    __atomic_store_n(&jn_rc_retired_n, keep, __ATOMIC_RELAXED);
    __atomic_store_n(&jn_rc_retired_bytes, kept_bytes + fresh_bytes, __ATOMIC_RELAXED);
    __atomic_store_n(&jn_rc_scan_at, kept_n * 2 > JN_RC_SCAN_MIN ? kept_n * 2 : JN_RC_SCAN_MIN, __ATOMIC_RELAXED);
    __atomic_store_n(&jn_rc_scan_bytes_at, kept_bytes * 2 > JN_RC_SCAN_MIN_BYTES ? kept_bytes * 2 : JN_RC_SCAN_MIN_BYTES, __ATOMIC_RELAXED);
    jn_mutex_unlock(&jn_weak_mutex);

    if (candidates == NULL) return 0;
    /* Destructors can enqueue more candidates or unregister a Reference. */
    int reclaimed = 0;
    for (size_t i = 0; i < cap; i++) {
        void* p = candidates[i].object;
        if (p == NULL || candidates[i].held) continue;
        jnative_release_storage(p);
        jnative_rc_release_fields(p);
        jn_free_raw(p);
        reclaimed = 1;
    }
    return reclaimed;
}

typedef struct {
    void*    obj;
    uint32_t word;
    int32_t  trial;
    int      black;
} JnCycNode;

typedef struct {
    JnCycNode* nodes;
    size_t     n;
    size_t     cap;
    size_t*    index;
    size_t     mask;
    size_t     limit;
    int        failed;
} JnCycGraph;

static uint32_t* jn_cyc_word(void* v) {
    if (v == NULL || jn_rc_in_static(v)) return NULL;
    void* vt = *(void**)v;
    if (vt == NULL || __jnative_is_class_mirror(vt)) return NULL;
    JnRcInfo* info = jn_rc_info(vt);
    if (!(info->flags & JN_RC_CYCLIC) || info->offset < 0) return NULL;
    uint32_t* word = (uint32_t*)((char*)v + info->offset);
    return (__atomic_load_n(word, __ATOMIC_RELAXED) & JN_RC_IMMORTAL) ? NULL : word;
}

static long jn_cyc_find(JnCycGraph* g, void* obj) {
    if (g->index == NULL) return -1;
    size_t i = jn_rc_hash(obj, g->mask);
    while (g->index[i] != 0) {
        size_t k = g->index[i] - 1;
        if (g->nodes[k].obj == obj) return (long)k;
        i = (i + 1) & g->mask;
    }
    return -1;
}

static int jn_cyc_rehash(JnCycGraph* g, size_t slots) {
    size_t* index = (size_t*)calloc(slots, sizeof(size_t));
    if (index == NULL) return 0;
    free(g->index);
    g->index = index;
    g->mask = slots - 1;
    for (size_t k = 0; k < g->n; k++) {
        size_t i = jn_rc_hash(g->nodes[k].obj, g->mask);
        while (g->index[i] != 0) i = (i + 1) & g->mask;
        g->index[i] = k + 1;
    }
    return 1;
}

static long jn_cyc_add(JnCycGraph* g, void* obj, uint32_t word) {
    if (g->n >= g->limit) {
        g->failed = 1;
        return -1;
    }
    if (g->n == g->cap) {
        size_t cap = g->cap == 0 ? 1024 : g->cap * 2;
        JnCycNode* grown = (JnCycNode*)realloc(g->nodes, cap * sizeof(JnCycNode));
        if (grown == NULL) {
            g->failed = 1;
            return -1;
        }
        g->nodes = grown;
        g->cap = cap;
    }
    if (g->index == NULL || (g->n + 1) * 2 > g->mask + 1) {
        size_t slots = g->index == NULL ? 2048 : (g->mask + 1) * 2;
        if (!jn_cyc_rehash(g, slots)) {
            g->failed = 1;
            return -1;
        }
    }
    JnCycNode* node = &g->nodes[g->n];
    node->obj = obj;
    node->word = word;
    node->trial = (int32_t)(word & JN_RC_COUNT);
    node->black = (word & (JN_RC_RETIRED | JN_RC_STACK | JN_RC_BUFFERED | JN_RC_REVIVED)) != 0;
    size_t i = jn_rc_hash(obj, g->mask);
    while (g->index[i] != 0) i = (i + 1) & g->mask;
    g->index[i] = g->n + 1;
    return (long)g->n++;
}

typedef void (*jn_cyc_edge_fn)(void* source, void* target, void* ctx);

static void jn_cyc_array_edges(void* source, void* arr, jn_cyc_edge_fn fn, void* ctx) {
    if (arr == NULL || jn_rc_in_static(arr)) return;
    void* mirror = *(void**)arr;
    if (mirror == NULL || !__jnative_is_class_mirror(mirror)) return;
    if (!(jn_rc_info(mirror)->flags & JN_RC_REF_ARRAY)) return;
    int32_t len = *(int32_t*)((char*)arr + JAVA_ARR_LENGTH_OFFSET);
    void** slots = (void**)((char*)arr + JAVA_ARR_HDR);
    for (int32_t i = 0; i < len; i++) fn(source, __atomic_load_n(&slots[i], __ATOMIC_ACQUIRE), ctx);
}

static void jn_cyc_edges(void* obj, jn_cyc_edge_fn fn, void* ctx) {
    JnRcInfo* info = jn_rc_of(obj);
    if (info == NULL) return;
    if (info->fields != NULL) {
        for (int32_t i = 1; i <= info->fields[0]; i++) {
            fn(obj, __atomic_load_n((void**)((char*)obj + info->fields[i]), __ATOMIC_ACQUIRE), ctx);
        }
    }
    if (info->owned != NULL) {
        for (int32_t i = 1; i <= info->owned[0]; i++) {
            jn_cyc_array_edges(obj, __atomic_load_n((void**)((char*)obj + info->owned[i]), __ATOMIC_ACQUIRE), fn, ctx);
        }
    }
    jn_once(&jnative_containers_once, jnative_containers_init);
    void* vt = *(void**)obj;
    for (int i = 0; i < JN_CONTAINER_COUNT; i++) {
        JNativeContainerKind* k = &jnative_containers[i];
        if (k->vtable != vt) continue;
        jn_cyc_array_edges(obj, __atomic_load_n((void**)((char*)obj + k->offset), __ATOMIC_ACQUIRE), fn, ctx);
        break;
    }
}

typedef struct {
    JnCycGraph* g;
    size_t*     stack;
    size_t      stack_n;
    size_t      stack_cap;
} JnCycWalk;

static void jn_cyc_push(JnCycWalk* w, size_t k) {
    if (w->stack_n == w->stack_cap) {
        size_t cap = w->stack_cap == 0 ? 1024 : w->stack_cap * 2;
        size_t* grown = (size_t*)realloc(w->stack, cap * sizeof(size_t));
        if (grown == NULL) {
            w->g->failed = 1;
            return;
        }
        w->stack = grown;
        w->stack_cap = cap;
    }
    w->stack[w->stack_n++] = k;
}

static void jn_cyc_gray_edge(void* source, void* target, void* ctx) {
    JnCycWalk* w = (JnCycWalk*)ctx;
    if (target == source || w->g->failed) return;
    uint32_t* word = jn_cyc_word(target);
    if (word == NULL) return;
    long k = jn_cyc_find(w->g, target);
    if (k < 0) {
        k = jn_cyc_add(w->g, target, __atomic_load_n(word, __ATOMIC_SEQ_CST));
        if (k < 0) return;
        jn_cyc_push(w, (size_t)k);
    }
    w->g->nodes[k].trial--;
}

static void jn_cyc_black_edge(void* source, void* target, void* ctx) {
    JnCycWalk* w = (JnCycWalk*)ctx;
    if (target == source || w->g->failed) return;
    long k = jn_cyc_find(w->g, target);
    if (k < 0 || w->g->nodes[k].black) return;
    w->g->nodes[k].black = 1;
    jn_cyc_push(w, (size_t)k);
}

static void jn_cyc_mark_held(JnCycGraph* g, void* v) {
    if (v == NULL) return;
    long k = jn_cyc_find(g, v);
    if (k >= 0) g->nodes[k].black = 1;
}

static void jn_cyc_mark_holds(JnCycGraph* g) {
    jn_rc_process_fence();
    jn_mutex_lock(&jn_hold_list_lock);
    for (JnHoldThread* t = jn_hold_threads; t != NULL; t = t->next) {
        int locked = 0;
        for (int attempt = 0;; attempt++) {
            if (t != jn_hold_self && attempt == 3) {
                __atomic_store_n(&t->scan_req, 1, __ATOMIC_SEQ_CST);
                jn_mutex_lock(&t->lock);
                locked = 1;
            }
            uint32_t s1 = __atomic_load_n(&t->seq, __ATOMIC_ACQUIRE);
            if ((s1 & 1u) && t != jn_hold_self) {
                jn_yield();
                continue;
            }
            jn_cyc_mark_held(g, __atomic_load_n(&t->ret, __ATOMIC_ACQUIRE));
            for (JnHoldChunk* c = t->first; c != NULL; c = __atomic_load_n(&c->next, __ATOMIC_ACQUIRE)) {
                size_t top = __atomic_load_n(&c->top, __ATOMIC_ACQUIRE);
                for (size_t i = 0; i < top; i++) jn_cyc_mark_held(g, __atomic_load_n(&c->slots[i], __ATOMIC_ACQUIRE));
            }
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (t == jn_hold_self || __atomic_load_n(&t->seq, __ATOMIC_RELAXED) == s1) break;
        }
        if (locked) {
            jn_mutex_unlock(&t->lock);
            __atomic_store_n(&t->scan_req, 0, __ATOMIC_RELEASE);
        }
    }
    jn_mutex_unlock(&jn_hold_list_lock);
}

static void jn_cyc_requeue_locked(void** roots, size_t n) {
    for (size_t i = 0; i < n; i++) {
        uint32_t* word = jn_cyc_word(roots[i]);
        if (word == NULL) continue;
        uint32_t w = __atomic_load_n(word, __ATOMIC_SEQ_CST);
        for (;;) {
            if ((w & (JN_RC_BUFFERED | JN_RC_STACK | JN_RC_RETIRED)) || !(w & JN_RC_COUNT)) break;
            if (__atomic_compare_exchange_n(word, &w, w | JN_RC_BUFFERED, 1, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST)) {
                if (!jn_cyc_push_locked(roots[i])) __atomic_fetch_and(word, ~JN_RC_BUFFERED, __ATOMIC_SEQ_CST);
                break;
            }
        }
    }
}

static void jn_cyc_prune(void) {
    jn_mutex_lock(&jn_weak_mutex);
    size_t keep = 0;
    for (size_t i = 0; i < jn_cyc_roots_n; i++) {
        void* p = jn_cyc_roots[i];
        uint32_t* word = jn_cyc_word(p);
        if (word == NULL) continue;
        uint32_t w = __atomic_load_n(word, __ATOMIC_SEQ_CST);
        if ((w & JN_RC_COUNT) && !(w & JN_RC_RETIRED)) {
            jn_cyc_roots[keep++] = p;
        } else {
            __atomic_fetch_and(word, ~JN_RC_BUFFERED, __ATOMIC_SEQ_CST);
        }
    }
    __atomic_store_n(&jn_cyc_roots_n, keep, __ATOMIC_RELAXED);
    __atomic_store_n(&jn_cyc_prune_at, keep * 2 > JN_CYC_SCAN_AT ? keep * 2 : JN_CYC_SCAN_AT, __ATOMIC_RELAXED);
    __atomic_store_n(&jn_rc_kept_buffered, 0, __ATOMIC_RELAXED);
    jn_mutex_unlock(&jn_weak_mutex);
}

static void jn_rc_collect_cycles(void) {
    jn_cyc_flush_local();
    if (!jn_rc_cycles_due()) {
        if (__atomic_load_n(&jn_rc_kept_buffered, __ATOMIC_RELAXED) > 0
                && __atomic_load_n(&jn_cyc_roots_n, __ATOMIC_RELAXED) >= __atomic_load_n(&jn_cyc_prune_at, __ATOMIC_RELAXED)) {
            jn_cyc_prune();
        }
        return;
    }
    jn_mutex_lock(&jn_weak_mutex);
    void** roots = jn_cyc_roots;
    size_t root_n = jn_cyc_roots_n;
    jn_cyc_roots = NULL;
    jn_cyc_roots_cap = 0;
    __atomic_store_n(&jn_cyc_roots_n, 0, __ATOMIC_RELAXED);
    for (size_t i = 0; i < root_n; i++) jn_cyc_unbuffer(roots[i]);
    jn_mutex_unlock(&jn_weak_mutex);
    if (root_n == 0) {
        free(roots);
        return;
    }

    JnCycGraph g = {0};
    g.limit = __atomic_load_n(&jn_cyc_max_nodes, __ATOMIC_RELAXED);
    JnCycWalk walk = {0};
    walk.g = &g;
    for (size_t i = 0; i < root_n && !g.failed; i++) {
        uint32_t* word = jn_cyc_word(roots[i]);
        if (word == NULL || jn_cyc_find(&g, roots[i]) >= 0) continue;
        uint32_t w = __atomic_load_n(word, __ATOMIC_SEQ_CST);
        if (!(w & JN_RC_COUNT) || (w & (JN_RC_RETIRED | JN_RC_STACK | JN_RC_BUFFERED))) continue;
        long k = jn_cyc_add(&g, roots[i], w);
        if (k >= 0) jn_cyc_push(&walk, (size_t)k);
    }
    while (walk.stack_n > 0 && !g.failed) {
        size_t k = walk.stack[--walk.stack_n];
        jn_cyc_edges(g.nodes[k].obj, jn_cyc_gray_edge, &walk);
    }

    size_t white_n = 0;
    if (!g.failed && g.n > 0) {
        jn_mutex_lock(&jn_weak_mutex);
        jn_cyc_mark_holds(&g);
        walk.stack_n = 0;
        for (size_t k = 0; k < g.n; k++) {
            if (g.nodes[k].trial > 0) g.nodes[k].black = 1;
            if (g.nodes[k].black) jn_cyc_push(&walk, k);
        }
        while (walk.stack_n > 0 && !g.failed) {
            size_t k = walk.stack[--walk.stack_n];
            jn_cyc_edges(g.nodes[k].obj, jn_cyc_black_edge, &walk);
        }
        int valid = !g.failed;
        for (size_t k = 0; k < g.n && valid; k++) {
            if (g.nodes[k].black) continue;
            uint32_t* word = jn_cyc_word(g.nodes[k].obj);
            if (word == NULL || __atomic_load_n(word, __ATOMIC_SEQ_CST) != g.nodes[k].word) valid = 0;
        }
        if (valid) {
            for (size_t k = 0; k < g.n; k++) {
                if (g.nodes[k].black) continue;
                __atomic_store_n(jn_cyc_word(g.nodes[k].obj), JN_RC_STACK, __ATOMIC_SEQ_CST);
                jn_weak_clear_locked(g.nodes[k].obj);
                g.nodes[white_n++] = g.nodes[k];
            }
        } else {
            jn_cyc_requeue_locked(roots, root_n);
        }
        jn_mutex_unlock(&jn_weak_mutex);
    } else if (g.failed) {
        jn_mutex_lock(&jn_weak_mutex);
        jn_cyc_requeue_locked(roots, root_n);
        jn_mutex_unlock(&jn_weak_mutex);
    }
    size_t scan_at = __atomic_load_n(&jn_cyc_scan_at, __ATOMIC_RELAXED);
    size_t next_at = g.n > JN_CYC_SCAN_AT ? g.n : JN_CYC_SCAN_AT;
    if (g.failed && g.n >= g.limit) {
        __atomic_store_n(&jn_cyc_max_nodes, g.limit * 2, __ATOMIC_RELAXED);
        if (next_at < scan_at * 2) next_at = scan_at * 2;
    }
    __atomic_store_n(&jn_cyc_scan_at, next_at, __ATOMIC_RELAXED);
    size_t requeued = __atomic_load_n(&jn_cyc_roots_n, __ATOMIC_RELAXED);
    __atomic_store_n(&jn_cyc_prune_at, requeued * 2 > JN_CYC_SCAN_AT ? requeued * 2 : JN_CYC_SCAN_AT, __ATOMIC_RELAXED);
    for (size_t k = 0; k < white_n; k++) {
        jnative_release_storage(g.nodes[k].obj);
        jnative_rc_release_fields(g.nodes[k].obj);
    }
    for (size_t k = 0; k < white_n; k++) jn_free_raw(g.nodes[k].obj);
    free(walk.stack);
    free(g.index);
    free(g.nodes);
    free(roots);
}

static void jn_rc_scan(void) {
    if (jn_rc_in_scan || __atomic_load_n(&jn_rc_scan_busy, __ATOMIC_RELAXED)) return;
    if (!jn_rc_flush_local()) __jnative_throw_out_of_memory_error_ctx("ARC retirement");
    if (__atomic_exchange_n(&jn_rc_scan_busy, 1, __ATOMIC_ACQUIRE)) return;
    jn_rc_in_scan = 1;
    while (jn_rc_reclaim_pending() && jn_rc_over(1)) {}
    jn_rc_collect_cycles();
    while (jn_rc_reclaim_pending() && jn_rc_over(1)) {}
    jn_rc_tables_reclaim();
    jn_rc_in_scan = 0;
    __atomic_store_n(&jn_rc_scan_busy, 0, __ATOMIC_RELEASE);
    jn_mutex_lock(&jn_rc_wait_lock);
    jn_cond_broadcast(&jn_rc_wait_cond);
    jn_mutex_unlock(&jn_rc_wait_lock);
}

void __jnative_rc_dec(void* p) {
    if (p == NULL) return;
    if (jn_rc_apply(p, jn_rc_word_dec) && jn_hold_frame_n == 0) jn_rc_maybe_scan();
}

void __jnative_rc_cas(int32_t ok, void* x, void* expected) {
    __jnative_rc_dec(ok ? expected : x);
}

void __jnative_rc_drop(void* p) {
    if (p != NULL) jn_rc_apply(p, jn_rc_word_drop);
}

static void jnative_rc_release_fields(void* obj) {
    JnRcInfo* info = jn_rc_of(obj);
    if (info == NULL) return;
    if (info->flags & JN_RC_REFERENCE) __jnative_weak_forget(obj);
    if (info->fields == NULL) return;
    int32_t n = info->fields[0];
    for (int32_t i = 1; i <= n; i++) {
        void** slot = (void**)((char*)obj + info->fields[i]);
        void* v = __atomic_exchange_n(slot, NULL, __ATOMIC_ACQ_REL);
        if (v != NULL) __jnative_rc_dec(v);
    }
}

static void jnative_rc_release_elements(void* arr) {
    void* mirror = *(void**)arr;
    if (mirror == NULL) return;
    JnRcInfo* info = jn_rc_info(mirror);
    if (!(info->flags & JN_RC_REF_ARRAY)) return;
    int32_t len = *(int32_t*)((char*)arr + JAVA_ARR_LENGTH_OFFSET);
    void** slots = (void**)((char*)arr + JAVA_ARR_HDR);
    for (int32_t i = 0; i < len; i++) {
        void* v = slots[i];
        if (v == NULL) continue;
        slots[i] = NULL;
        __jnative_rc_dec(v);
    }
}

static void jnative_free_array_rc(void* arr) {
    if (arr == NULL || jn_rc_in_static(arr)) return;
    if (jn_rc_managed_array(arr)) {
        __jnative_rc_dec(arr);
        return;
    }
    jnative_rc_release_elements(arr);
    jn_free_raw(arr);
}

void __jnative_rc_cloned(void* copy) {
    if (copy == NULL) return;
    void* first = *(void**)copy;
    if (first == NULL) return;
    JnRcInfo* info = jn_rc_info(first);
    if (info->flags & JN_RC_REF_ARRAY) {
        int32_t len = *(int32_t*)((char*)copy + JAVA_ARR_LENGTH_OFFSET);
        void** slots = (void**)((char*)copy + JAVA_ARR_HDR);
        for (int32_t i = 0; i < len; i++) __jnative_rc_inc(slots[i]);
        return;
    }
    if (info->offset >= 0) __atomic_store_n((uint32_t*)((char*)copy + info->offset), 0u, __ATOMIC_RELAXED);
    if (info->fields != NULL) {
        int32_t n = info->fields[0];
        for (int32_t i = 1; i <= n; i++) __jnative_rc_inc(*(void**)((char*)copy + info->fields[i]));
    }
}

int32_t __jnative_rc_array_copy(void* src, int32_t srcPos, void* dest, int32_t destPos, int32_t length) {
    if (src == NULL || dest == NULL || length <= 0) return 0;
    void* mirror = *(void**)dest;
    if (mirror == NULL || !(jn_rc_info(mirror)->flags & JN_RC_REF_ARRAY)) return 0;
    void** s = (void**)((char*)src + JAVA_ARR_HDR) + srcPos;
    void** d = (void**)((char*)dest + JAVA_ARR_HDR) + destPos;
    int backward = src == dest && destPos > srcPos;
    void* olds[128];
    int32_t k = 0;
    while (k < length) {
        int32_t n = 0;
        jn_mutex_lock(&jn_weak_mutex);
        for (; k < length && n < 128; k++) {
            int32_t i = backward ? length - 1 - k : k;
            void* value = __atomic_load_n(&s[i], __ATOMIC_ACQUIRE);
            jn_rc_inc_locked(value);
            void* old = __atomic_exchange_n(&d[i], value, __ATOMIC_ACQ_REL);
            if (old != NULL) olds[n++] = old;
        }
        jn_mutex_unlock(&jn_weak_mutex);
        for (int32_t j = 0; j < n; j++) __jnative_rc_dec(olds[j]);
    }
    return 1;
}

void __jnative_container_detach_clone(void* clone) {
    if (clone == NULL) return;
    void* vt = *(void**)clone;
    if (vt == NULL) return;
    jn_once(&jnative_containers_once, jnative_containers_init);
    JNativeVTable* v = (JNativeVTable*)vt;
    ReflectionClass* cls = v->name != NULL ? jnative_class_by_name(v->name) : NULL;
    for (; cls != NULL; cls = cls->superclass) {
        if (cls->cname == NULL) continue;
        for (int i = 0; i < JN_CONTAINER_COUNT; i++) {
            JNativeContainerKind* k = &jnative_containers[i];
            if (k->offset > 0 && strcmp(k->class_name, cls->cname) == 0) {
                *(void**)((char*)clone + k->offset) = NULL;
            }
        }
    }
}

typedef void (*jnative_storage_fn)(void*);

#define JN_STORAGE_SLOTS 16384

static void*             jnative_storage_keys[JN_STORAGE_SLOTS];
static jnative_storage_fn jnative_storage_fns[JN_STORAGE_SLOTS];
static jn_mutex_t        jnative_storage_lock = JN_MUTEX_INITIALIZER;

static jnative_storage_fn jnative_resolve_storage(void* key) {
    if (__jnative_is_class_mirror(key)) return NULL;
    JNativeVTable* vt = (JNativeVTable*)key;
    if (vt->name == NULL) return NULL;
    static const char PREFIX[] = "__jnative_storage_";
    char symbol[1024];
    size_t pos = 0;
    for (size_t i = 0; PREFIX[i] != '\0'; i++) symbol[pos++] = PREFIX[i];
    for (const char* c = vt->name; *c != '\0'; c++) {
        if (pos + 1 >= sizeof(symbol)) return NULL;
        symbol[pos++] = (*c == '/' || *c == '.') ? '_' : *c;
    }
    symbol[pos] = '\0';
    return (jnative_storage_fn)jn_dlsym_default(symbol);
}

static jnative_storage_fn jnative_storage_for(void* key) {
    size_t mask = JN_STORAGE_SLOTS - 1;
    size_t start = (size_t)(jnative_mix((uintptr_t)key) & mask);
    size_t i = start;
    for (size_t probe = 0; probe < JN_STORAGE_SLOTS; probe++, i = (i + 1) & mask) {
        void* k = __atomic_load_n(&jnative_storage_keys[i], __ATOMIC_ACQUIRE);
        if (k == key) return __atomic_load_n(&jnative_storage_fns[i], __ATOMIC_RELAXED);
        if (k == NULL) break;
    }
    jnative_storage_fn fn = jnative_resolve_storage(key);
    jn_mutex_lock(&jnative_storage_lock);
    i = start;
    for (size_t probe = 0; probe < JN_STORAGE_SLOTS; probe++, i = (i + 1) & mask) {
        void* k = __atomic_load_n(&jnative_storage_keys[i], __ATOMIC_ACQUIRE);
        if (k == key) break;
        if (k != NULL) continue;
        __atomic_store_n(&jnative_storage_fns[i], fn, __ATOMIC_RELAXED);
        __atomic_store_n(&jnative_storage_keys[i], key, __ATOMIC_RELEASE);
        break;
    }
    jn_mutex_unlock(&jnative_storage_lock);
    return fn;
}

static void jnative_release_storage(void* p) {
    void* vt = *(void**)p;
    if (vt != NULL) {
        jn_once(&jnative_containers_once, jnative_containers_init);
        for (int i = 0; i < JN_CONTAINER_COUNT; i++) {
            JNativeContainerKind* k = &jnative_containers[i];
            if (k->vtable != vt) continue;
            void* inner = *(void**)((char*)p + k->offset);
            *(void**)((char*)p + k->offset) = NULL;
            if (k->kind == JN_BOX_HASH_TABLE) jnative_free_hash_table(inner);
            else jnative_free_array_storage(inner);
            break;
        }
        jnative_storage_fn fn = jnative_storage_for(vt);
        if (fn != NULL) fn(p);
    }
    jnative_release_retired(p);
}

void __jnative_free_container(void* p) {
    if (p == NULL) return;
    if (jn_rc_managed_array(p)) {
        __jnative_rc_drop(p);
        return;
    }
    uintptr_t addr = (uintptr_t)p;
    if (jnative_static_low != 0 && jnative_static_high != 0
        && addr >= jnative_static_low && addr < jnative_static_high) {
        return;
    }
    void* first = *(void**)p;
    if (first != NULL && __jnative_is_class_mirror(first)) {
        jnative_free_array_rc(p);
        return;
    }
    JnRcInfo* info = jn_rc_of(p);
    if (info != NULL && info->offset >= 0) {
        __jnative_rc_drop(p);
        return;
    }
    jnative_release_storage(p);
    jnative_rc_release_fields(p);
    jn_free_raw(p);
}

void __jnative_free_container_storage(void* p) {
    if (p == NULL) return;
    jnative_release_storage(p);
    jnative_rc_release_fields(p);
}

/* ========================================================================
 *  Monitor table
 * ====================================================================== */

#define HASH_SIZE 65536

typedef struct MonitorEntry {
    void* obj;
    jn_mutex_t mutex;
    jn_cond_t entry_cond;
    jn_cond_t wait_cond;
    jn_thread_t owner;
    int count;
    int contenders;
    int waiters;
    int refs;
    struct MonitorEntry* next;
} MonitorEntry;

#define MONITOR_STRIPES 256

static MonitorEntry* monitor_table[HASH_SIZE] = {0};
static int monitor_stripe_lock[MONITOR_STRIPES][16];

static uint32_t hash_ptr(void* p) {
    uint64_t h = (uint64_t)(uintptr_t)p;
    h ^= h >> 33;
    h *= 0xff51afd7ed558ccdULL;
    h ^= h >> 33;
    return (uint32_t)h & (HASH_SIZE - 1);
}

static int* monitor_lock_for(void* obj) {
    return monitor_stripe_lock[hash_ptr(obj) & (MONITOR_STRIPES - 1)];
}

static MonitorEntry** find_entry(void* obj) {
    MonitorEntry** pp = &monitor_table[hash_ptr(obj)];
    while (*pp && (*pp)->obj != obj) pp = &(*pp)->next;
    return pp;
}

static MonitorEntry* monitor_free_list[MONITOR_STRIPES];

static MonitorEntry* monitor_pin(void* obj) {
    uint32_t h = hash_ptr(obj);
    int* lock = monitor_stripe_lock[h & (MONITOR_STRIPES - 1)];
    jn_spin_lock(lock);
    MonitorEntry* entry = *find_entry(obj);
    if (entry == NULL) {
        MonitorEntry** pool = &monitor_free_list[h & (MONITOR_STRIPES - 1)];
        entry = *pool;
        if (entry != NULL) {
            *pool = entry->next;
        } else {
            entry = calloc(1, sizeof(MonitorEntry));
            if (entry == NULL) {
                jn_spin_unlock(lock);
                __jnative_throw_out_of_memory_error_ctx("monitor");
                return NULL;
            }
            jn_mutex_init(&entry->mutex, NULL);
            jn_cond_init(&entry->entry_cond, NULL);
            jn_cond_init(&entry->wait_cond, NULL);
        }
        entry->obj = obj;
        entry->next = monitor_table[h];
        monitor_table[h] = entry;
    }
    entry->refs++;
    jn_spin_unlock(lock);
    return entry;
}

static void monitor_release_locked(MonitorEntry* entry) {
    if (--entry->refs > 0) return;
    uint32_t h = hash_ptr(entry->obj);
    MonitorEntry** pp = &monitor_table[h];
    while (*pp != entry) pp = &(*pp)->next;
    *pp = entry->next;
    entry->obj = NULL;
    entry->next = monitor_free_list[h & (MONITOR_STRIPES - 1)];
    monitor_free_list[h & (MONITOR_STRIPES - 1)] = entry;
}

static void monitor_unpin(MonitorEntry* entry) {
    int* lock = monitor_lock_for(entry->obj);
    jn_spin_lock(lock);
    monitor_release_locked(entry);
    jn_spin_unlock(lock);
}

static int monitor_owned_by(MonitorEntry* entry, jn_thread_t self) {
    return entry->count > 0 && jn_thread_equal(entry->owner, self);
}

static void monitor_take_locked(MonitorEntry* entry, jn_thread_t self, int count) {
    while (entry->count > 0) {
        entry->contenders++;
        jn_cond_wait(&entry->entry_cond, &entry->mutex);
        entry->contenders--;
    }
    entry->owner = self;
    entry->count = count;
}

void __jnative_monitor_enter(void* obj) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    MonitorEntry* entry = monitor_pin(obj);
    if (entry == NULL) return;
    jn_thread_t self = jn_thread_self();
    jn_mutex_lock(&entry->mutex);
    if (monitor_owned_by(entry, self)) entry->count++;
    else monitor_take_locked(entry, self, 1);
    jn_mutex_unlock(&entry->mutex);
}

void __jnative_monitor_exit(void* obj) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    jn_thread_t self = jn_thread_self();
    int* lock = monitor_lock_for(obj);
    jn_spin_lock(lock);
    MonitorEntry* entry = *find_entry(obj);
    if (entry != NULL) {
        jn_mutex_lock(&entry->mutex);
        int owned = monitor_owned_by(entry, self);
        if (owned && --entry->count == 0 && entry->contenders > 0) jn_cond_signal(&entry->entry_cond);
        jn_mutex_unlock(&entry->mutex);
        if (owned) monitor_release_locked(entry);
    }
    jn_spin_unlock(lock);
}

void __jnative_monitor_wait(void* obj, int64_t timeout_ms) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    MonitorEntry* entry = monitor_pin(obj);
    if (entry == NULL) return;
    jn_thread_t self = jn_thread_self();
    jn_mutex_lock(&entry->mutex);
    int saved = 0;
    if (monitor_owned_by(entry, self)) {
        saved = entry->count;
        entry->count = 0;
        if (entry->contenders > 0) jn_cond_signal(&entry->entry_cond);
    }
    entry->waiters++;
    if (timeout_ms <= 0) {
        jn_cond_wait(&entry->wait_cond, &entry->mutex);
    } else {
        struct timespec ts;
        jn_now_realtime(&ts);
        ts.tv_sec += (time_t)(timeout_ms / 1000);
        ts.tv_nsec += (long)((timeout_ms % 1000) * 1000000L);
        if (ts.tv_nsec >= 1000000000L) {
            ts.tv_sec++;
            ts.tv_nsec -= 1000000000L;
        }
        jn_cond_timedwait(&entry->wait_cond, &entry->mutex, &ts);
    }
    entry->waiters--;
    if (saved > 0) monitor_take_locked(entry, self, saved);
    jn_mutex_unlock(&entry->mutex);
    monitor_unpin(entry);
}

void __jnative_monitor_notify(void* obj, int all) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    int* lock = monitor_lock_for(obj);
    jn_spin_lock(lock);
    MonitorEntry* entry = *find_entry(obj);
    if (entry != NULL) {
        jn_mutex_lock(&entry->mutex);
        if (entry->waiters > 0) {
            if (all) jn_cond_broadcast(&entry->wait_cond);
            else jn_cond_signal(&entry->wait_cond);
        }
        jn_mutex_unlock(&entry->mutex);
    }
    jn_spin_unlock(lock);
}

/* ========================================================================
 *  Catch context stack
 * ====================================================================== */

typedef struct CatchContext {
    void*    type_info;
    void*    jmp_buf_ptr;
    uint64_t id;
    int      rc_depth;
} CatchContext;

#define CATCH_STACK_INITIAL 64

static JN_TLS CatchContext* catch_stack = NULL;
static JN_TLS int          catch_capacity = 0;
static JN_TLS int          catch_depth = 0;
static JN_TLS void*        current_exception = NULL;
static JN_TLS uint64_t     jnative_catch_id_counter = 1;

static inline CatchContext* current_catch_ctx(void) {
    return catch_depth > 0 ? &catch_stack[catch_depth - 1] : NULL;
}

void __jnative_push_catch(void* jmp_buf_ptr, void* type_info) {
    if (catch_depth >= catch_capacity) {
        int cap = catch_capacity > 0 ? catch_capacity * 2 : CATCH_STACK_INITIAL;
        CatchContext* grown = (CatchContext*)realloc(catch_stack, (size_t)cap * sizeof(CatchContext));
        if (grown == NULL) {
            fprintf(stderr, "jnative: out of memory growing catch stack to %d\n", cap);
            abort();
        }
        catch_stack    = grown;
        catch_capacity = cap;
    }
    CatchContext* ctx = &catch_stack[catch_depth++];
    ctx->type_info   = type_info;
    ctx->jmp_buf_ptr = jmp_buf_ptr;
    ctx->id          = jnative_catch_id_counter++;
    ctx->rc_depth    = jn_hold_frame_n;
}

void __jnative_pop_catch(void) {
    if (catch_depth > 0) catch_depth--;
}

/* ========================================================================
 *  Lazy <clinit> state machine
 * ====================================================================== */

#define CLINIT_STATE_NOT_STARTED 0
#define CLINIT_STATE_IN_PROGRESS 1
#define CLINIT_STATE_DONE        2
#define CLINIT_STATE_FAILED      3

typedef struct ClinitEntry {
    char* name;
    int   state;
    struct ClinitEntry* next;
    jn_thread_t      initializing_thread;
    jn_cond_t done_cond;
} ClinitEntry;

static void mark_abandoned_clinits(CatchContext* target);

static JN_NORETURN void jn_catch_longjmp(void* buf) {
    CatchContext* ctx = current_catch_ctx();
    if (jn_hold_self != NULL) {
        __jnative_rc_hold(&jn_hold_self->ret, current_exception);
        jn_hold_pop_to(ctx->rc_depth);
        jn_rc_maybe_scan();
    }
#ifdef _WIN64
    ((_JUMP_BUFFER*)buf)->Frame = 0;
#endif
    longjmp(*(jmp_buf*)buf, 1);
}

#define CLINIT_HASH_BUCKETS 1024

static ClinitEntry*    clinit_buckets[CLINIT_HASH_BUCKETS] = {0};
static jn_mutex_t clinit_lock = JN_MUTEX_INITIALIZER;

static uint32_t clinit_hash(const char* name) {
    uint32_t h = 2166136261u;
    for (const char* p = name; *p; p++) {
        h ^= (uint8_t)*p;
        h *= 16777619u;
    }
    return h;
}

typedef struct ClinitFrame {
    char*               name;
    struct ClinitFrame* prev;
    uint64_t            recorded_ctx_id;
} ClinitFrame;

static JN_TLS ClinitFrame* tls_clinit_stack = NULL;

static void clinit_stack_push(const char* name) {
    if (name == NULL) return;
    ClinitFrame* f = (ClinitFrame*)malloc(sizeof(ClinitFrame));
    if (f == NULL) return;
    f->name = strdup(name);
    if (f->name == NULL) { free(f); return; }
    f->prev = tls_clinit_stack;
    CatchContext* cc = current_catch_ctx();
    f->recorded_ctx_id = (cc != NULL) ? cc->id : 0;
    tls_clinit_stack = f;
}

static void clinit_stack_pop(void) {
    ClinitFrame* f = tls_clinit_stack;
    if (f == NULL) return;
    tls_clinit_stack = f->prev;
    free(f->name);
    free(f);
}

const char* __jnative_current_clinit_class(void) {
    return tls_clinit_stack != NULL ? tls_clinit_stack->name : NULL;
}

static ClinitEntry* clinit_lookup(const char* name) {
    uint32_t bucket = clinit_hash(name) & (CLINIT_HASH_BUCKETS - 1);
    for (ClinitEntry* e = clinit_buckets[bucket]; e; e = e->next) {
        if (strcmp(e->name, name) == 0) return e;
    }
    return NULL;
}

static ClinitEntry* clinit_lookup_or_create(const char* name) {
    uint32_t bucket = clinit_hash(name) & (CLINIT_HASH_BUCKETS - 1);
    for (ClinitEntry* e = clinit_buckets[bucket]; e; e = e->next) {
        if (strcmp(e->name, name) == 0) return e;
    }
    ClinitEntry* e = (ClinitEntry*)malloc(sizeof(ClinitEntry));
    if (e == NULL) return NULL;
    e->name = strdup(name);
    if (e->name == NULL) { free(e); return NULL; }
    e->state               = CLINIT_STATE_NOT_STARTED;
    e->initializing_thread = jn_thread_none();
    jn_cond_init(&e->done_cond, NULL);
    e->next = clinit_buckets[bucket];
    clinit_buckets[bucket] = e;
    return e;
}

__attribute__((noinline))
int32_t __jnative_clinit_enter(void* name_str) {
    const char* name = __jnative_read_string_bytes(name_str, NULL);
    int32_t len = (int32_t)strlen(name);
    if (len <= 0) return 0;

    jn_mutex_lock(&clinit_lock);
    ClinitEntry* e = clinit_lookup_or_create(name);
    if (e == NULL) { jn_mutex_unlock(&clinit_lock); return 0; }

    int32_t result = 0;
    for (;;) {
        if (e->state == CLINIT_STATE_DONE) { result = 1; break; }
        if (e->state == CLINIT_STATE_FAILED) {
            jn_mutex_unlock(&clinit_lock);
            char buf[600];
            snprintf(buf, sizeof(buf),
                     "Could not initialize class %.*s", (int)len, name);
            void* exc = __jnative_construct_exception(
                "vtable_java_lang_NoClassDefFoundError", buf);
            __jnative_throw_exception_ctx(exc, NULL);
        }
        if (e->state == CLINIT_STATE_IN_PROGRESS) {
            if (jn_thread_equal(e->initializing_thread, jn_thread_self())) {
                result = 1;
                break;
            }
            jn_cond_wait(&e->done_cond, &clinit_lock);
            continue;
        }
        e->state               = CLINIT_STATE_IN_PROGRESS;
        e->initializing_thread = jn_thread_self();
        result = 0;
        break;
    }
    jn_mutex_unlock(&clinit_lock);

    if (result == 0) clinit_stack_push(name);
    return result;
}

__attribute__((noinline))
void __jnative_clinit_exit(void* name_str) {
    const char* name = __jnative_read_string_bytes(name_str, NULL);
    if (name[0] == '\0') return;
    clinit_stack_pop();
    jn_mutex_lock(&clinit_lock);
    ClinitEntry* e = clinit_lookup(name);
    if (e != NULL) {
        e->state               = CLINIT_STATE_DONE;
        e->initializing_thread = jn_thread_none();
        jn_cond_broadcast(&e->done_cond);
    }
    jn_mutex_unlock(&clinit_lock);
}

static void mark_abandoned_clinits(CatchContext* target) {
    if (target == NULL || tls_clinit_stack == NULL) return;
    const char* exc_msg = NULL;
    if (current_exception != NULL) {
        void* msg_obj = *(void**)((char*)current_exception + 16);
        if (msg_obj != NULL) {
            exc_msg = __jnative_read_string_bytes(msg_obj, NULL);
        }
    }
    jn_mutex_lock(&clinit_lock);
    while (tls_clinit_stack != NULL) {
        ClinitFrame* f = tls_clinit_stack;
        if (target->id > f->recorded_ctx_id) break;
        ClinitEntry* e = clinit_lookup(f->name);
        if (e != NULL && e->state == CLINIT_STATE_IN_PROGRESS) {
            e->state               = CLINIT_STATE_FAILED;
            e->initializing_thread = jn_thread_none();
            jn_cond_broadcast(&e->done_cond);
        }
        fprintf(stderr, "jnative: <clinit> of %s abandoned: %s\n",
                f->name, exc_msg != NULL ? exc_msg : "<no message>");
        tls_clinit_stack = f->prev;
        free(f->name);
        free(f);
    }
    jn_mutex_unlock(&clinit_lock);
}

/* ========================================================================
 *  Function index for stack walking
 * ====================================================================== */

typedef struct {
    uintptr_t        address;
    const char*      symbol;
    ReflectionClass* cls;
} FunctionIndexSortedEntry;

static FunctionIndexSortedEntry* function_index_sorted = NULL;
static int64_t                   function_index_count  = 0;
static jn_once_t            function_index_once   = JN_ONCE_INIT;
static uintptr_t function_index_text_low  = 0;
static uintptr_t function_index_text_high = 0;

#if defined(__linux__)
static int jnative_find_main_text_end(struct dl_phdr_info* info,
                                      size_t size, void* data) {
    (void)size;
    if (info->dlpi_name == NULL || info->dlpi_name[0] == '\0') {
        uintptr_t end = 0;
        for (int i = 0; i < info->dlpi_phnum; i++) {
            const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
            if (ph->p_type == PT_LOAD && (ph->p_flags & PF_X)) {
                uintptr_t seg_end = (uintptr_t)info->dlpi_addr
                                  + (uintptr_t)ph->p_vaddr
                                  + (uintptr_t)ph->p_memsz;
                if (seg_end > end) end = seg_end;
            }
        }
        *(uintptr_t*)data = end;
        return 1;
    }
    return 0;
}
#endif

#define PAGE_SHIFT          12
#define PAGE_SIZE_BYTES     (1u << PAGE_SHIFT)
#define PAGE_MASK           (PAGE_SIZE_BYTES - 1u)

typedef struct {
    uintptr_t page_number;
    int64_t   sorted_index;
} PageIndexEntry;

static PageIndexEntry* page_index      = NULL;
static size_t          page_index_size = 0;

static uintptr_t hash_page_number(uintptr_t p) {
    return (p * 0x9E3779B97F4A7C15ULL);
}

static int compare_function_entries(const void* a, const void* b) {
    uintptr_t aa = ((const FunctionIndexSortedEntry*)a)->address;
    uintptr_t bb = ((const FunctionIndexSortedEntry*)b)->address;
    if (aa < bb) return -1;
    if (aa > bb) return  1;
    return 0;
}

static void build_page_index(void) {
    if (function_index_sorted == NULL || function_index_count == 0) return;
    size_t distinct = 1;
    uintptr_t last_page = function_index_sorted[0].address >> PAGE_SHIFT;
    for (int64_t i = 1; i < function_index_count; i++) {
        uintptr_t p = function_index_sorted[i].address >> PAGE_SHIFT;
        if (p != last_page) { distinct++; last_page = p; }
    }
    size_t cap = 16;
    while (cap < distinct * 2) cap <<= 1;
    PageIndexEntry* table = calloc(cap, sizeof(*table));
    if (table == NULL) return;
    int64_t first_in_page = 0;
    uintptr_t current_page = function_index_sorted[0].address >> PAGE_SHIFT;
    for (int64_t i = 1; i <= function_index_count; i++) {
        uintptr_t p = (i < function_index_count)
            ? (function_index_sorted[i].address >> PAGE_SHIFT)
            : (uintptr_t)-1;
        if (p == current_page) continue;
        size_t slot = (size_t)(hash_page_number(current_page) & (cap - 1));
        while (table[slot].sorted_index != 0) slot = (slot + 1) & (cap - 1);
        table[slot].page_number  = current_page;
        table[slot].sorted_index = first_in_page + 1;
        if (i < function_index_count) { current_page = p; first_in_page = i; }
    }
    page_index      = table;
    page_index_size = cap;
}

static void build_function_index(void) {
    if ((uintptr_t)&jnative_function_index_size == 0) return;
    if ((uintptr_t)&jnative_function_index      == 0) return;
    int64_t n = jnative_function_index_size;
    if (n <= 0) return;
    FunctionIndexSortedEntry* arr = malloc((size_t)n * sizeof(*arr));
    if (arr == NULL) return;
    for (int64_t i = 0; i < n; i++) {
        arr[i].address = (uintptr_t)jnative_function_index[i].address;
        arr[i].symbol  = jnative_function_index[i].symbol;
        arr[i].cls     = jnative_function_index[i].cls;
    }
    qsort(arr, (size_t)n, sizeof(*arr), compare_function_entries);
    function_index_sorted = arr;
    function_index_count  = n;
    function_index_text_low = arr[0].address;
#if defined(__linux__)
    {
        uintptr_t end = 0;
        dl_iterate_phdr(jnative_find_main_text_end, &end);
        function_index_text_high = end;
    }
#endif
    build_page_index();
}

static const FunctionIndexSortedEntry* function_index_lookup(uintptr_t pc) {
    if (function_index_sorted == NULL || function_index_count == 0) return NULL;
    if (function_index_text_high != 0
        && (pc < function_index_text_low || pc >= function_index_text_high)) {
        return NULL;
    }
    if (page_index != NULL && page_index_size > 0) {
        uintptr_t page = pc >> PAGE_SHIFT;
        size_t slot = (size_t)(hash_page_number(page) & (page_index_size - 1));
        while (page_index[slot].sorted_index != 0) {
            if (page_index[slot].page_number == page) {
                int64_t idx = page_index[slot].sorted_index - 1;
                while (idx > 0 && function_index_sorted[idx].address > pc) idx--;
                if (function_index_sorted[idx].address > pc) break;
                while (idx + 1 < function_index_count
                       && function_index_sorted[idx + 1].address <= pc) idx++;
                return &function_index_sorted[idx];
            }
            slot = (slot + 1) & (page_index_size - 1);
        }
    }
    int64_t lo = 0, hi = function_index_count;
    while (lo < hi) {
        int64_t mid = lo + (hi - lo) / 2;
        if (function_index_sorted[mid].address <= pc) lo = mid + 1;
        else                                          hi = mid;
    }
    if (lo == 0) return NULL;
    return &function_index_sorted[lo - 1];
}

int __jnative_function_at_address(uintptr_t pc,
                                  const char** out_symbol,
                                  ReflectionClass** out_cls) {
    jn_once(&function_index_once, build_function_index);
    const FunctionIndexSortedEntry* e = function_index_lookup(pc);
    if (e == NULL || e->symbol == NULL) return 0;
    if (out_symbol != NULL) *out_symbol = e->symbol;
    if (out_cls    != NULL) *out_cls    = e->cls;
    return 1;
}

/* ========================================================================
 *  Canonical own-class-vtable resolution / static field symbol names
 * ====================================================================== */

void* __jnative_own_class_vtable(const char* class_name) {
    if (class_name == NULL) return NULL;
    char symbol[512];
    jnative_type_info_name(class_name, symbol, sizeof(symbol));
    void** type_info = (void**)jn_dlsym_default(symbol);
    if (type_info == NULL) return NULL;
    return type_info[0];
}

static int jnative_is_word_char(unsigned char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || (c == '_');
}

int jnative_build_static_field_symbol(const char* owner,
                                      const char* name,
                                      int32_t name_len,
                                      char* out, size_t out_size)
{
    if (owner == NULL || name == NULL) return 0;
    if (name_len < 0) return 0;
    if (out_size < 4) return 0;
    size_t pos = 0;
    out[pos++] = 'g';
    out[pos++] = 'v';
    out[pos++] = '_';
    for (const char* p = owner; *p != '\0'; p++) {
        if (pos + 2 >= out_size) return 0;
        unsigned char c = (unsigned char)*p;
        out[pos++] = jnative_is_word_char(c) ? (char)c : '_';
    }
    if (pos + 2 >= out_size) return 0;
    out[pos++] = '_';
    for (int32_t i = 0; i < name_len; i++) {
        if (pos + 2 >= out_size) return 0;
        unsigned char c = (unsigned char)name[i];
        out[pos++] = jnative_is_word_char(c) ? (char)c : '_';
    }
    out[pos] = '\0';
    return 1;
}

void* jnative_static_field_address(const char* owner, const char* name) {
    char symbol[1024];
    if (!jnative_build_static_field_symbol(owner, name,
                                           (int32_t)strlen(name),
                                           symbol, sizeof(symbol))) {
        return NULL;
    }
    return jn_dlsym_default(symbol);
}

/* ========================================================================
 *  Symbol demangling and frame printing
 * ====================================================================== */

static const char* dotted_class_name(const char* internal, char* buf, size_t buf_size) {
    if (!internal) return NULL;
    strncpy(buf, internal, buf_size - 1);
    buf[buf_size - 1] = '\0';
    for (char* p = buf; *p; p++) if (*p == '/') *p = '.';
    return buf;
}

static void __jnative_demangle(const char* sym, char* out, size_t out_size) {
    if (!sym || !out || out_size == 0) { if (out) out[0] = '\0'; return; }
    while (*sym == '_') sym++;
    if (strncmp(sym, "fn_", 3) != 0) {
        size_t len = strlen(sym);
        if (len >= out_size) len = out_size - 1;
        memcpy(out, sym, len);
        out[len] = '\0';
        return;
    }
    sym += 3;
    const char* desc_sep   = strstr(sym, "__");
    const char* desc_start = desc_sep ? desc_sep + 2 : NULL;
    size_t cm_len          = desc_sep ? (size_t)(desc_sep - sym) : strlen(sym);
    const char* last_us = NULL;
    for (size_t i = 0; i < cm_len; i++) if (sym[i] == '_') last_us = &sym[i];
    size_t class_len  = last_us ? (size_t)(last_us - sym) : cm_len;
    size_t method_len = last_us ? (cm_len - class_len - 1) : 0;
    size_t w = 0;
#define PUT(c) do { if (w < out_size - 1) out[w++] = (char)(c); } while (0)
    for (size_t i = 0; i < class_len; i++) PUT(sym[i] == '_' ? '.' : sym[i]);
    if (method_len > 0) {
        PUT('.');
        for (size_t i = 0; i < method_len; i++) PUT(last_us[1 + i]);
    }
    if (desc_start) {
        PUT('(');
        for (const char* d = desc_start; *d; d++) {
            if (*d == '_' && (d[1]=='L' || d[1]=='I' || d[1]=='J' || d[1]=='B'
                            || d[1]=='S' || d[1]=='C' || d[1]=='F' || d[1]=='D'
                            || d[1]=='Z' || d[1]=='V' || d[1]=='_')) continue;
            PUT(*d);
        }
        PUT(')');
    }
    out[w] = '\0';
#undef PUT
}

static void __jnative_print_frame(uint64_t rip, int index);
static void __jnative_unwind_with_backtrace(int skip);

static void __jnative_print_frame(uint64_t rip, int index) {
    jn_dl_info dli;
    memset(&dli, 0, sizeof(dli));
    char line[4096];
    int n;
    if (jn_dladdr((void*)(uintptr_t)rip, &dli) && dli.dli_sname) {
        char demangled[1024];
        __jnative_demangle(dli.dli_sname, demangled, sizeof(demangled));
        ptrdiff_t off = dli.dli_saddr
            ? (ptrdiff_t)(rip - (uint64_t)(uintptr_t)dli.dli_saddr) : 0;
        const char* mod = dli.dli_fname ? dli.dli_fname : "?";
        const char* slash = strrchr(mod, '/');
        if (slash == NULL) slash = strrchr(mod, '\\');
        mod = slash ? slash + 1 : mod;
        n = snprintf(line, sizeof(line), "\t#%-2d  %s (%s+0x%lx)  [0x%lx]\n",
            index, demangled, mod, (unsigned long)off, (unsigned long)rip);
    } else {
        n = snprintf(line, sizeof(line), "\t#%-2d  <unknown>  [0x%lx]\n",
            index, (unsigned long)rip);
    }
    if (n > 0) (void)!write(2, line, (size_t)n);
    jn_dl_info_free(&dli);
}

/* ========================================================================
 *  Platform-specific unwinding.
 *
 *  The two definitions below are mutually exclusive: the POSIX version
 *  is compiled only when _WIN32 is not defined, and the Windows version
 *  only when it is.
 * ====================================================================== */

#if !defined(_WIN32)

static void __jnative_unwind_from_context(void* context) {
    uint64_t rip = 0, rsp = 0, rbp = 0;
    jn_get_context_regs(context, &rip, &rsp, &rbp);
    if (rip == 0) return;

    __jnative_print_frame(rip, 0);

#if defined(__x86_64__)
    uint64_t fp = rbp;
    int depth = 1;
    int fp_chain_ok = 1;
    for (; depth < 128 && fp != 0; depth++) {
        if (rsp != 0 && fp < rsp) { fp_chain_ok = 0; break; }
        if ((fp & 0x7) != 0) { fp_chain_ok = 0; break; }
        if (rsp != 0 && fp > rsp + (8ull << 20)) { fp_chain_ok = 0; break; }
        if (fp < 0x1000) { fp_chain_ok = 0; break; }
        uint64_t* frame = (uint64_t*)(uintptr_t)fp;
        uint64_t next_fp  = frame[0];
        uint64_t ret_addr = frame[1];
        if (ret_addr == 0) break;
        __jnative_print_frame(ret_addr, depth);
        if (next_fp <= fp) { fp_chain_ok = 0; break; }
        fp = next_fp;
    }
    if (!fp_chain_ok || depth < 3) {
        const char* msg = "\t-- frame-pointer chain broken, "
                          "falling back to .eh_frame unwinder --\n";
        (void)!write(2, msg, strlen(msg));
        __jnative_unwind_with_backtrace(1);
    }
#else
    (void)rsp; (void)rbp;
#endif
}

#else /* _WIN32 */

/*
 * =========================================================================
 *  DbgHelp structure and flag definitions.
 * =========================================================================
 *
 * The Windows DbgHelp API is loaded dynamically (LoadLibraryA +
 * GetProcAddress) so the runtime does not import dbghelp.dll at load
 * time. That removes any hard dependency, but it does not remove the
 * need for the two types the runtime passes across the dynamic boundary:
 * SYMBOL_INFO (the caller-supplied name buffer that SymFromAddr fills in)
 * and the SYMOPT_* flags that SymSetOptions consumes.
 *
 * Both are declared in <dbghelp.h>, which MinGW does not ship. They are
 * re-declared below under a JNATIVE_ prefix so the file compiles on
 * every Windows toolchain without touching the SDK. The binary layout of
 * the struct and the numeric values of the flags are frozen by the
 * DbgHelp ABI and have not changed since Windows 2000:
 *
 *   https://learn.microsoft.com/en-us/windows/win32/api/dbghelp/ns-dbghelp-symbol_info
 *
 * The layout below matches SYMBOL_INFO byte-for-byte on x86_64 (the
 * only target this runtime currently supports on Windows):
 *
 *     ULONG   SizeOfStruct     @ 0
 *     ULONG   TypeIndex        @ 4
 *     ULONG64 Reserved[2]      @ 8  (8-byte aligned)
 *     ULONG   Index            @ 24
 *     ULONG   Size             @ 28
 *     ULONG   ModBase          @ 32
 *     ULONG   Flags            @ 36
 *     ULONG64 Value            @ 40
 *     ULONG64 Address          @ 48
 *     ULONG   Register         @ 56
 *     ULONG   Scope            @ 60
 *     ULONG   Tag              @ 64
 *     ULONG   NameLen          @ 68
 *     ULONG   MaxNameLen       @ 72
 *     CHAR    Name[1]          @ 76
 *     (total header: 80 bytes, padded to 8)
 *
 * The actual name buffer must follow the struct in the caller's
 * allocation; the runtime uses a union of the struct and a raw byte
 * buffer of sizeof(struct) + 256 to provide that trailing space.
 * =========================================================================
 */
typedef struct {
    ULONG   SizeOfStruct;
    ULONG   TypeIndex;
    ULONG64 Reserved[2];
    ULONG   Index;
    ULONG   Size;
    ULONG   ModBase;
    ULONG   Flags;
    ULONG64 Value;
    ULONG64 Address;
    ULONG   Register;
    ULONG   Scope;
    ULONG   Tag;
    ULONG   NameLen;
    ULONG   MaxNameLen;
    CHAR    Name[1];
} JNATIVE_SYMBOL_INFO, *JNATIVE_PSYMBOL_INFO;

#define JNATIVE_SYMOPT_UNDNAME           0x00000002
#define JNATIVE_SYMOPT_DEFERRED_LOADS    0x00000004
#define JNATIVE_SYMOPT_LOAD_LINES        0x00000010

/*
 * DbgHelp delay-load state. The function pointers are resolved lazily by
 * jnative_load_dbghelp(); if any of them is NULL the frame printer
 * degrades to printing the raw address, which is still useful and cannot
 * crash.
 */
typedef struct {
    BOOL  (WINAPI *SymInitialize)(HANDLE, PCSTR, BOOL);
    BOOL  (WINAPI *SymFromAddr)(HANDLE, DWORD64, PDWORD64, JNATIVE_PSYMBOL_INFO);
    DWORD64 (WINAPI *SymGetModuleBase64)(HANDLE, DWORD64);
    DWORD (WINAPI *SymSetOptions)(DWORD);
    BOOL  (WINAPI *SymCleanup)(HANDLE);
    HMODULE dbghelp;
} JNativeDbgHelp;

static JNativeDbgHelp jnative_dbghelp = { NULL };
static jn_once_t      jnative_dbghelp_once = JN_ONCE_INIT;

static void jnative_load_dbghelp(void) {
    jnative_dbghelp.dbghelp = LoadLibraryA("dbghelp.dll");
    if (jnative_dbghelp.dbghelp == NULL) return;

    jnative_dbghelp.SymInitialize =
        (BOOL (WINAPI *)(HANDLE, PCSTR, BOOL))
            GetProcAddress(jnative_dbghelp.dbghelp, "SymInitialize");
    jnative_dbghelp.SymFromAddr =
        (BOOL (WINAPI *)(HANDLE, DWORD64, PDWORD64, JNATIVE_PSYMBOL_INFO))
            GetProcAddress(jnative_dbghelp.dbghelp, "SymFromAddr");
    jnative_dbghelp.SymGetModuleBase64 =
        (DWORD64 (WINAPI *)(HANDLE, DWORD64))
            GetProcAddress(jnative_dbghelp.dbghelp, "SymGetModuleBase64");
    jnative_dbghelp.SymSetOptions =
        (DWORD (WINAPI *)(DWORD))
            GetProcAddress(jnative_dbghelp.dbghelp, "SymSetOptions");
    jnative_dbghelp.SymCleanup =
        (BOOL (WINAPI *)(HANDLE))
            GetProcAddress(jnative_dbghelp.dbghelp, "SymCleanup");

    if (jnative_dbghelp.SymSetOptions != NULL) {
        jnative_dbghelp.SymSetOptions(
            JNATIVE_SYMOPT_UNDNAME
          | JNATIVE_SYMOPT_DEFERRED_LOADS
          | JNATIVE_SYMOPT_LOAD_LINES);
    }
    if (jnative_dbghelp.SymInitialize != NULL) {
        jnative_dbghelp.SymInitialize(GetCurrentProcess(), NULL, TRUE);
    }
}

static void __jnative_print_frame_win(uint64_t rip, int index) {
    if (jnative_dbghelp.SymFromAddr == NULL) {
        char line[256];
        int n = snprintf(line, sizeof(line),
                         "\t#%-2d  <unknown>  [0x%llx]\n",
                         index, (unsigned long long)rip);
        if (n > 0) (void)!write(2, line, (size_t)n);
        return;
    }

    /*
     * SYMBOL_INFO is a variable-length structure: the trailing
     * Name[] array is sized by MaxNameLen. Allocate it on the stack
     * with enough room for 255 characters and the terminating NUL,
     * which is the maximum DbgHelp will write.
     */
    union {
        JNATIVE_SYMBOL_INFO info;
        char raw[sizeof(JNATIVE_SYMBOL_INFO) + 256];
    } buf;
    JNATIVE_PSYMBOL_INFO sym = &buf.info;
    sym->SizeOfStruct = sizeof(JNATIVE_SYMBOL_INFO);
    sym->MaxNameLen   = 255;

    DWORD64 displacement = 0;
    char line[4096];
    int n;
    if (jnative_dbghelp.SymFromAddr(GetCurrentProcess(), rip,
                                    &displacement, sym)) {
        n = snprintf(line, sizeof(line),
                     "\t#%-2d  %s+0x%llx  [0x%llx]\n",
                     index, sym->Name,
                     (unsigned long long)displacement,
                     (unsigned long long)rip);
    } else {
        n = snprintf(line, sizeof(line),
                     "\t#%-2d  <unknown>  [0x%llx]\n",
                     index, (unsigned long long)rip);
    }
    if (n > 0) (void)!write(2, line, (size_t)n);
}

static void __jnative_unwind_from_context(void* context) {
    if (context == NULL) return;
    jn_once(&jnative_dbghelp_once, jnative_load_dbghelp);

    CONTEXT* ctx = (CONTEXT*)context;
    uint64_t rip = 0, rsp = 0, rbp = 0;
    jn_get_context_regs(context, &rip, &rsp, &rbp);
    (void)rsp; (void)rbp;

    __jnative_print_frame_win(rip, 0);

    CONTEXT local = *ctx;
    for (int depth = 1; depth < 128; depth++) {
        if (jnative_dbghelp.SymGetModuleBase64 == NULL) break;
        DWORD64 image_base =
            jnative_dbghelp.SymGetModuleBase64(GetCurrentProcess(), local.Rip);
        if (image_base == 0) break;

        PRUNTIME_FUNCTION rf = RtlLookupFunctionEntry(local.Rip,
                                                      &image_base, NULL);
        if (rf == NULL) break;

        PVOID handler_data = NULL;
        ULONG64 establisher_frame = 0;
        RtlVirtualUnwind(UNW_FLAG_NHANDLER,
                         image_base, local.Rip, rf,
                         &local, &handler_data, &establisher_frame, NULL);
        __jnative_print_frame_win(local.Rip, depth);
        if (local.Rip == 0) break;
    }
}

#endif /* _WIN32 */

/* ========================================================================
 *  Backtrace-based unwinding (both platforms)
 * ====================================================================== */

static void __jnative_unwind_with_backtrace(int skip) {
    void* frames[128];
    int n = jn_backtrace(frames, 128);
    if (n <= skip) return;
    for (int i = skip; i < n; i++) {
        __jnative_print_frame((uint64_t)(uintptr_t)frames[i], i - skip);
    }
}

/* ========================================================================
 *  Fatal signal handler and auto-init
 * ====================================================================== */

#ifdef _WIN32

static volatile LONG __jnative_in_fatal_handler_win = 0;

static LONG CALLBACK __jnative_vectored_handler(EXCEPTION_POINTERS* ep) {
    DWORD code = ep->ExceptionRecord->ExceptionCode;
    if ((code & 0xC0000000u) != 0xC0000000u) return EXCEPTION_CONTINUE_SEARCH;
    if (__jnative_in_fatal_handler_win) return EXCEPTION_CONTINUE_SEARCH;
    __jnative_in_fatal_handler_win = 1;

    ULONG_PTR fault = (ULONG_PTR)ep->ExceptionRecord->ExceptionInformation[1];
    const char* sig_desc;
    switch (code) {
        case EXCEPTION_ACCESS_VIOLATION:      sig_desc = "Access violation";          break;
        case EXCEPTION_ILLEGAL_INSTRUCTION:   sig_desc = "Illegal instruction";       break;
        case EXCEPTION_INT_DIVIDE_BY_ZERO:    sig_desc = "Integer divide by zero";    break;
        case EXCEPTION_PRIV_INSTRUCTION:      sig_desc = "Privileged instruction";    break;
        case EXCEPTION_STACK_OVERFLOW:        sig_desc = "Stack overflow";            break;
        default:                              sig_desc = "Fatal exception";           break;
    }
    char line[4096];
    int n = snprintf(line, sizeof(line),
        "\n=== JNative fatal error ===\nException : %s (0x%08lx)\nAddress   : %p\n",
        sig_desc, (unsigned long)code, (void*)fault);
    if (n > 0) (void)!write(2, line, (size_t)n);
    if (ep->ContextRecord != NULL) {
        n = snprintf(line, sizeof(line),
            "RIP : 0x%016llx\nRSP : 0x%016llx\nRBP : 0x%016llx\n",
            (unsigned long long)ep->ContextRecord->Rip,
            (unsigned long long)ep->ContextRecord->Rsp,
            (unsigned long long)ep->ContextRecord->Rbp);
        if (n > 0) (void)!write(2, line, (size_t)n);
        n = snprintf(line, sizeof(line),
                     "\nCall tree (from crash point, innermost first):\n");
        if (n > 0) (void)!write(2, line, (size_t)n);
        __jnative_unwind_from_context(ep->ContextRecord);
    }
    n = snprintf(line, sizeof(line), "\n=== end of JNative trace ===\n\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    return EXCEPTION_EXECUTE_HANDLER;
}

static void __jnative_install_fatal_handlers(void) {
    jn_once(&jnative_dbghelp_once, jnative_load_dbghelp);
    AddVectoredExceptionHandler(1, __jnative_vectored_handler);
}

/*
 * =========================================================================
 *  Windows startup initialiser.
 * =========================================================================
 *
 * Registers __jnative_auto_init_win() with the CRT so that it runs
 * before main():
 *
 *   - MSVC / clang-cl accept the standard `__declspec(allocate(".CRT$XCU"))`
 *     mechanism: a function pointer stored in the .CRT$XCU section is
 *     picked up by the CRT's own initialiser walk.
 *
 *   - MinGW (GCC or clang targeting x86_64-w64-windows-gnu) uses the
 *     GCC/ELF-style `__attribute__((constructor))`. The generated
 *     initialiser goes into the .ctors section, which MinGW's CRT also
 *     walks at startup. clang-gnu targeting Windows emits the same
 *     section entries.
 *
 * The two paths cover every Windows toolchain this runtime has been
 * built with. If a future toolchain falls outside both, the initialiser
 * will simply not run — but nothing in the runtime depends on it
 * running *early*: the first call to any runtime entry point
 * initialises whatever it needs lazily (jnative_build_static_range is
 * also invoked from __jnative_free's first call through the offset
 * check).
 * ====================================================================== */

static void __jnative_auto_init_win(void) {
    jnative_build_static_range();
    __jnative_install_fatal_handlers();
    __jnative_run_all_initializers();
}

#if defined(_MSC_VER) && !defined(__clang__)
typedef void (__cdecl* _PVFV)(void);
#pragma section(".CRT$XCU", read)
__declspec(allocate(".CRT$XCU"))
static _PVFV __jnative_auto_init_c = __jnative_auto_init_win;
#else
__attribute__((constructor))
static void __jnative_auto_init_win_attr(void) {
    __jnative_auto_init_win();
}
#endif

#else /* POSIX */

static volatile sig_atomic_t __jnative_in_fatal_handler = 0;

static void __jnative_fatal_signal_handler(int sig, siginfo_t* info, void* ucontext) {
    if (__jnative_in_fatal_handler) {
        const char* msg = "\n[jnative] fatal: signal handler re-entered, aborting\n";
        (void)!write(2, msg, strlen(msg));
        _exit(128 + sig);
    }
    __jnative_in_fatal_handler = 1;
    const char* sig_desc;
    switch (sig) {
        case SIGSEGV: sig_desc = "Segmentation fault";       break;
        case SIGBUS:  sig_desc = "Bus error";                break;
        case SIGFPE:  sig_desc = "Floating point exception"; break;
        case SIGILL:  sig_desc = "Illegal instruction";      break;
        default:      sig_desc = "Fatal signal";             break;
    }
    char line[4096];
    int  n;
    uintptr_t fault_addr = info ? (uintptr_t)info->si_addr : 0;
    const char* fault_kind;
    if (fault_addr == 0) fault_kind = "NULL pointer dereference";
    else if (fault_addr < 0x1000) fault_kind = "near-NULL pointer dereference (likely NULL + offset)";
    else if (fault_addr < 0x10000) fault_kind = "small-offset pointer dereference";
    else fault_kind = "invalid memory access";
    n = snprintf(line, sizeof(line),
        "\n=== JNative fatal error ===\nSignal  : %s (SIG%d)\nAddress : %p   (%s)\n",
        sig_desc, sig, info ? info->si_addr : NULL, fault_kind);
    if (n > 0) (void)!write(2, line, (size_t)n);
#if defined(__x86_64__)
    if (ucontext) {
        ucontext_t* uc = (ucontext_t*)ucontext;
        n = snprintf(line, sizeof(line),
            "RIP     : 0x%016lx\nRSP     : 0x%016lx\nRBP     : 0x%016lx\n",
            (unsigned long)uc->uc_mcontext.gregs[REG_RIP],
            (unsigned long)uc->uc_mcontext.gregs[REG_RSP],
            (unsigned long)uc->uc_mcontext.gregs[REG_RBP]);
        if (n > 0) (void)!write(2, line, (size_t)n);
    }
#endif
    if (current_exception) {
        n = snprintf(line, sizeof(line),
            "Thrown  : exception %p was being propagated\n", current_exception);
        if (n > 0) (void)!write(2, line, (size_t)n);
    }
    n = snprintf(line, sizeof(line),
                 "\nCall tree (from crash point, innermost first):\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
#if defined(__x86_64__)
    if (ucontext) __jnative_unwind_from_context(ucontext);
    else __jnative_unwind_with_backtrace(0);
#else
    __jnative_unwind_with_backtrace(0);
#endif
    n = snprintf(line, sizeof(line), "\n=== end of JNative trace ===\n\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    signal(sig, SIG_DFL);
    raise(sig);
    _exit(128 + sig);
}

static void __jnative_install_fatal_handlers(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = __jnative_fatal_signal_handler;
    sa.sa_flags     = SA_SIGINFO | SA_NODEFER | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS,  &sa, NULL);
    sigaction(SIGFPE,  &sa, NULL);
    sigaction(SIGILL,  &sa, NULL);
}

#if defined(__GLIBC__)
#include <malloc.h>
static void jnative_tune_malloc(void) {
    if (getenv("MALLOC_MMAP_THRESHOLD_") == NULL) mallopt(M_MMAP_THRESHOLD, 1024 * 1024);
    if (getenv("MALLOC_ARENA_MAX") == NULL) {
        long cpus = sysconf(_SC_NPROCESSORS_ONLN);
        mallopt(M_ARENA_MAX, cpus < 2 ? 2 : (int)cpus);
    }
    if (getenv("MALLOC_TOP_PAD_") == NULL) mallopt(M_TOP_PAD, 64 * 1024 * 1024);
    if (getenv("MALLOC_TRIM_THRESHOLD_") == NULL) mallopt(M_TRIM_THRESHOLD, 256 * 1024 * 1024);
}
#endif

__attribute__((constructor))
static void __jnative_auto_init(void) {
#if defined(__GLIBC__)
    jnative_tune_malloc();
#endif
    jn_dlsym_default = dlopen(NULL, RTLD_LAZY);
    jnative_build_static_range();
    __jnative_install_fatal_handlers();
    __jnative_run_all_initializers();
}

#endif /* _WIN32 */

/* ========================================================================
 *  Exception object construction
 * ====================================================================== */

#define JNATIVE_THROWABLE_MESSAGE_OFFSET 16
#define JNATIVE_EXCEPTION_OBJECT_SIZE    64

static JN_TLS char jnative_last_missing_vtable[256];

static JNativeVTable* __jnative_lookup_vtable_weak(const char* symbol) {
    void* handle = jn_dlsym_default;
    void* sym = handle != NULL ? dlsym(handle, symbol) : NULL;
    if (sym == NULL) snprintf(jnative_last_missing_vtable, sizeof(jnative_last_missing_vtable), "%s", symbol);
    return (JNativeVTable*)sym;
}

static void* __jnative_make_exception_object(const char* vtable_symbol,
                                             const char* message) {
    JNativeVTable* vt = __jnative_lookup_vtable_weak(vtable_symbol);
    if (vt == NULL) return NULL;
    ReflectionClass* cls = vt->name != NULL ? jnative_class_by_name(vt->name) : NULL;
    size_t size = cls != NULL && (size_t)cls->object_size > JNATIVE_EXCEPTION_OBJECT_SIZE
        ? (size_t)cls->object_size : JNATIVE_EXCEPTION_OBJECT_SIZE;
    void* exc = calloc(1, size);
    if (exc == NULL) return NULL;
    *(void**)((char*)exc + 0) = (void*)vt;
    if (message != NULL) {
        void* msg = __jnative_make_string_obj(message, (int32_t)strlen(message));
        __jnative_rc_inc(msg);
        *(void**)((char*)exc + JNATIVE_THROWABLE_MESSAGE_OFFSET) = msg;
    }
    return exc;
}

void* __jnative_construct_exception(const char* vtable_symbol,
                                    const char* message) {
    if (vtable_symbol == NULL) return NULL;
    return __jnative_make_exception_object(vtable_symbol, message);
}

static void* __jnative_make_null_pointer_exception(const char* caller,
                                                   const char* var_desc) {
    char buf[512];
    char demangled[256];
    if (caller != NULL) __jnative_demangle(caller, demangled, sizeof(demangled));
    else { strncpy(demangled, "unknown method", sizeof(demangled) - 1);
           demangled[sizeof(demangled) - 1] = '\0'; }
    if (var_desc != NULL) {
        snprintf(buf, sizeof(buf),
            "Cannot invoke %s because %s is null", demangled, var_desc);
    } else {
        snprintf(buf, sizeof(buf),
            "Cannot invoke %s because of a null reference", demangled);
    }
    return __jnative_make_exception_object(
        "vtable_java_lang_NullPointerException", buf);
}

static void* __jnative_make_array_index_out_of_bounds_exception(const char* caller) {
    char buf[512];
    char demangled[256];
    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
        snprintf(buf, sizeof(buf), "Array index out of bounds in %s", demangled);
    } else snprintf(buf, sizeof(buf), "Array index out of bounds");
    return __jnative_make_exception_object(
        "vtable_java_lang_ArrayIndexOutOfBoundsException", buf);
}

static void* __jnative_make_class_cast_exception(const char* caller) {
    char buf[512];
    char demangled[256];
    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
        snprintf(buf, sizeof(buf), "Class cast failed in %s", demangled);
    } else snprintf(buf, sizeof(buf), "Class cast failed");
    return __jnative_make_exception_object(
        "vtable_java_lang_ClassCastException", buf);
}

static void* __jnative_make_arithmetic_exception(const char* caller) {
    char buf[512];
    char demangled[256];
    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
        snprintf(buf, sizeof(buf), "Arithmetic error in %s", demangled);
    } else snprintf(buf, sizeof(buf), "Arithmetic error");
    return __jnative_make_exception_object(
        "vtable_java_lang_ArithmeticException", buf);
}

/* ========================================================================
 *  Exception message extraction / unhandled reporting
 * ====================================================================== */

#define JNATIVE_THROWABLE_CAUSE_OFFSET  24
#define JNATIVE_MAX_CAUSE_DEPTH        32

static const char* __jnative_class_name_of(void* obj, char* buf, size_t buf_size) {
    if (obj == NULL) return NULL;
    void* vtable = *(void**)obj;
    if (vtable == NULL) return NULL;
    const char* internal = __jnative_vtable_class_name(vtable);
    if (internal == NULL) return NULL;
    return dotted_class_name(internal, buf, buf_size);
}

static const char* __jnative_message_of(void* obj) {
    if (obj == NULL) return NULL;
    void* msg = *(void**)((char*)obj + JNATIVE_THROWABLE_MESSAGE_OFFSET);
    if (msg == NULL) return NULL;
    return __jnative_read_string_bytes(msg, NULL);
}

__attribute__((noreturn))
static void __jnative_log_unhandled_exception(void* exc, const char* className,
                                              const char* caller, const char* extra) {
    void* chain[JNATIVE_MAX_CAUSE_DEPTH];
    int chain_len = 0;
    void* cursor = exc;
    while (cursor != NULL && chain_len < JNATIVE_MAX_CAUSE_DEPTH) {
        int seen = 0;
        for (int k = 0; k < chain_len; k++) {
            if (chain[k] == cursor) { seen = 1; break; }
        }
        if (seen) break;
        chain[chain_len++] = cursor;
        cursor = *(void**)((char*)cursor + JNATIVE_THROWABLE_CAUSE_OFFSET);
    }
    for (int i = 0; i < chain_len; i++) {
        void* cur = chain[i];
        char dottedBuf[256];
        const char* clsName = (i == 0 && className != NULL)
            ? className
            : __jnative_class_name_of(cur, dottedBuf, sizeof(dottedBuf));
        if (clsName == NULL) clsName = "java.lang.Throwable";
        const char* message = __jnative_message_of(cur);
        if (i == 0) {
            if (message != NULL)
                fprintf(stderr, "Exception in thread \"main\" %s: %s\n",
                        clsName, message);
            else
                fprintf(stderr, "Exception in thread \"main\" %s\n", clsName);
        } else {
            if (message != NULL)
                fprintf(stderr, "Caused by: %s: %s\n", clsName, message);
            else
                fprintf(stderr, "Caused by: %s\n", clsName);
        }
    }
    if (caller != NULL) {
        char demangled[1024];
        __jnative_demangle(caller, demangled, sizeof(demangled));
        fprintf(stderr, "\tat %s\n", demangled);
    }
    if (extra != NULL) fprintf(stderr, "\t(null value: %s)\n", extra);
    fflush(stderr);
    _exit(1);
}

void* __jnative_get_exception_object(void) { return current_exception; }

int __jnative_catch_matches(void* exc, void* type_info) {
    if (exc == NULL || type_info == NULL) return 0;
    void* vtable = *(void**)exc;
    void** ti = (void**)type_info;
    while (*ti) { if (*ti == vtable) return 1; ti++; }

    if (getenv("JNATIVE_DEBUG_CATCH") != NULL) {
        const char* exc_name = "?";
        if (vtable != NULL) {
            const char* n = ((JNativeVTable*)vtable)->name;
            if (n != NULL) exc_name = n;
        }
        fprintf(stderr, "[catch] MISS: '%s' (vtable=%p) not in %p\n",
                exc_name, vtable, type_info);
        ti = (void**)type_info;
        int i = 0;
        while (*ti != NULL && i < 16) {
            const char* n = ((JNativeVTable*)*ti)->name;
            fprintf(stderr, "  [%d] %s\n", i, n ? n : "?");
            ti++; i++;
        }
    }
    return 0;
}

static void* jnative_universal_vtables[3];
static jn_once_t jnative_universal_vtables_once = JN_ONCE_INIT;

static void jnative_init_universal_vtables(void) {
    jnative_universal_vtables[0] =
        __jnative_own_class_vtable("java/lang/Object");
    jnative_universal_vtables[1] =
        __jnative_own_class_vtable("java/lang/Cloneable");
    jnative_universal_vtables[2] =
        __jnative_own_class_vtable("java/io/Serializable");
}

#define JN_IOF_CACHE 1024

typedef struct {
    void*  vt;
    void** ti;
    int    result;
} JnIofEntry;

static __thread JnIofEntry jn_iof_cache[JN_IOF_CACHE];

static int jn_instanceof_slow(void* first_word, void** type_info);

int __jnative_instanceof(void* obj, void** type_info) {
    if (obj == NULL || type_info == NULL) return 0;
    void* first_word = *(void**)obj;
    if (*type_info == first_word) return 1;
    size_t i = (size_t)(jnative_mix((uintptr_t)first_word ^ ((uintptr_t)type_info * 31u)) & (JN_IOF_CACHE - 1));
    JnIofEntry* e = &jn_iof_cache[i];
    if (e->vt == first_word && e->ti == type_info) return e->result;
    int result = jn_instanceof_slow(first_word, type_info);
    e->vt = first_word;
    e->ti = type_info;
    e->result = result;
    return result;
}

static int jn_instanceof_slow(void* first_word, void** type_info) {
    void** ti = type_info;
    while (*ti) { if (*ti == first_word) return 1; ti++; }
    if (!__jnative_is_class_mirror(first_word)) return 0;
    jn_once(&jnative_universal_vtables_once, jnative_init_universal_vtables);
    for (int i = 0; i < 3; i++) {
        void* v = jnative_universal_vtables[i];
        if (v == NULL) continue;
        ti = type_info;
        while (*ti) { if (*ti == v) return 1; ti++; }
    }
    return 0;
}

/* ========================================================================
 *  Throw helpers
 * ====================================================================== */

__attribute__((noreturn))
void __jnative_throw_exception_ctx(void* exc, const char* caller) {
    if (exc == NULL) {
        char msg[512];
        snprintf(msg, sizeof(msg),
            "NULL exception object substituted by __jnative_throw_exception_ctx "
            "(caller: %s, last missing vtable: %s)",
            caller ? caller : "?",
            jnative_last_missing_vtable[0] ? jnative_last_missing_vtable : "none");
        exc = __jnative_make_exception_object("vtable_java_lang_Throwable", msg);
    }
    if (exc == NULL) {
        const char* msg = "jnative: fatal: NULL exception thrown and no "
                          "vtable_java_lang_Throwable available\n";
        (void)!write(2, msg, strlen(msg));
        _exit(1);
    }
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) {
        mark_abandoned_clinits(ctx);
        jn_catch_longjmp(ctx->jmp_buf_ptr);
    }
    __jnative_log_unhandled_exception(exc, NULL, caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_null_pointer_exception_ctx(const char* caller,
                                                const char* var_desc) {
    void* npe = __jnative_make_null_pointer_exception(caller, var_desc);
    current_exception = npe;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) {
        mark_abandoned_clinits(ctx);
        jn_catch_longjmp(ctx->jmp_buf_ptr);
    }
    __jnative_log_unhandled_exception(npe, "java.lang.NullPointerException",
                                      caller, var_desc);
}

__attribute__((noreturn))
void __jnative_throw_array_index_out_of_bounds_ctx(const char* caller) {
    void* exc = __jnative_make_array_index_out_of_bounds_exception(caller);
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) { mark_abandoned_clinits(ctx); jn_catch_longjmp(ctx->jmp_buf_ptr); }
    __jnative_log_unhandled_exception(exc,
        "java.lang.ArrayIndexOutOfBoundsException", caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_class_cast_exception_ctx(const char* caller) {
    void* exc = __jnative_make_class_cast_exception(caller);
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) { mark_abandoned_clinits(ctx); jn_catch_longjmp(ctx->jmp_buf_ptr); }
    __jnative_log_unhandled_exception(exc,
        "java.lang.ClassCastException", caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_arithmetic_exception_ctx(const char* caller) {
    void* exc = __jnative_make_arithmetic_exception(caller);
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) { mark_abandoned_clinits(ctx); jn_catch_longjmp(ctx->jmp_buf_ptr); }
    __jnative_log_unhandled_exception(exc,
        "java.lang.ArithmeticException", caller, NULL);
}
__attribute__((noreturn))
void __jnative_throw_io_ctx(const char* extra, const char* caller) {
    char buf[512];
    char demangled[256];

    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
        if (extra != NULL) {
            snprintf(buf, sizeof(buf), "%s (in %s)", extra, demangled);
        } else {
            snprintf(buf, sizeof(buf), "I/O error in %s", demangled);
        }
    } else {
        if (extra != NULL) {
            snprintf(buf, sizeof(buf), "%s", extra);
        } else {
            snprintf(buf, sizeof(buf), "I/O error");
        }
    }

    void* exc = __jnative_make_exception_object(
        "vtable_java_io_IOException", buf);
    if (exc == NULL) {
        exc = __jnative_make_exception_object("vtable_java_lang_Throwable", buf);
    }
    if (exc == NULL) {
        const char* msg = "jnative: fatal: cannot construct IOException and "
                          "no Throwable vtable available\n";
        (void)!write(2, msg, strlen(msg));
        _exit(1);
    }
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) {
        mark_abandoned_clinits(ctx);
        jn_catch_longjmp(ctx->jmp_buf_ptr);
    }
    __jnative_log_unhandled_exception(exc, "java.io.IOException", caller, extra);
}

__attribute__((noreturn)) void __jnative_throw_exception(void* exc) {
    __jnative_throw_exception_ctx(exc, NULL);
}
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void) {
    __jnative_throw_null_pointer_exception_ctx(NULL, NULL);
}
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void) {
    __jnative_throw_array_index_out_of_bounds_ctx(NULL);
}
__attribute__((noreturn)) void __jnative_throw_class_cast_exception(void) {
    __jnative_throw_class_cast_exception_ctx(NULL);
}
__attribute__((noreturn)) void __jnative_throw_arithmetic_exception(void) {
    __jnative_throw_arithmetic_exception_ctx(NULL);
}

/* ========================================================================
 *  CloneNotSupportedException / OutOfMemoryError / bad-vtable
 * ====================================================================== */

static void* __jnative_make_clone_not_supported_exception(const char* caller) {
    char buf[512];
    char demangled[256];
    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
        snprintf(buf, sizeof(buf), "Object.clone() not supported in %s", demangled);
    } else snprintf(buf, sizeof(buf), "Object.clone() not supported");
    void* exc = __jnative_make_exception_object(
        "vtable_java_lang_CloneNotSupportedException", buf);
    if (exc == NULL) {
        exc = __jnative_make_exception_object("vtable_java_lang_Throwable", buf);
    }
    return exc;
}

__attribute__((noreturn))
void __jnative_throw_clone_not_supported_exception_ctx(const char* caller) {
    void* exc = __jnative_make_clone_not_supported_exception(caller);
    if (exc == NULL) {
        const char* msg = "jnative: fatal: cannot construct "
                          "CloneNotSupportedException and no Throwable vtable\n";
        (void)!write(2, msg, strlen(msg));
        _exit(1);
    }
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) { mark_abandoned_clinits(ctx); jn_catch_longjmp(ctx->jmp_buf_ptr); }
    __jnative_log_unhandled_exception(exc,
        "java.lang.CloneNotSupportedException", caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_clone_not_supported_exception(void) {
    __jnative_throw_clone_not_supported_exception_ctx(NULL);
}

static void* __jnative_make_out_of_memory_error(const char* caller) {
    char buf[512];
    char demangled[256];
    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
        snprintf(buf, sizeof(buf), "Out of memory in %s", demangled);
    } else snprintf(buf, sizeof(buf), "Out of memory");
    void* exc = __jnative_make_exception_object(
        "vtable_java_lang_OutOfMemoryError", buf);
    if (exc == NULL) {
        exc = __jnative_make_exception_object("vtable_java_lang_Throwable", buf);
    }
    return exc;
}

__attribute__((noreturn))
void __jnative_throw_out_of_memory_error_ctx(const char* caller) {
    void* exc = __jnative_make_out_of_memory_error(caller);
    if (exc == NULL) {
        const char* msg = "jnative: fatal: cannot construct OutOfMemoryError "
                          "and no Throwable vtable\n";
        (void)!write(2, msg, strlen(msg));
        _exit(1);
    }
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) { mark_abandoned_clinits(ctx); jn_catch_longjmp(ctx->jmp_buf_ptr); }
    __jnative_log_unhandled_exception(exc, "java.lang.OutOfMemoryError",
                                      caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_out_of_memory_error(void) {
    __jnative_throw_out_of_memory_error_ctx(NULL);
}

__attribute__((noreturn))
void __jnative_throw_bad_vtable(void* method_name, void* obj) {
    char line[4096];
    int  n;
    n = snprintf(line, sizeof(line),
        "\n=== JNative bad vtable ===\nMethod  : %s\nReceiver: %p\n",
        method_name ? (const char*)method_name : "<null>", obj);
    if (n > 0) (void)!write(2, line, (size_t)n);
    if (obj != NULL) {
        void* first_word = *(void**)obj;
        n = snprintf(line, sizeof(line),
                     "First word (vtable): %p\n", first_word);
        if (n > 0) (void)!write(2, line, (size_t)n);
    }
    n = snprintf(line, sizeof(line), "\nCall tree (from throw point):\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    __jnative_unwind_with_backtrace(0);
    fflush(stderr);
    signal(SIGABRT, SIG_DFL);
    abort();
}

/* ========================================================================
 *  Dispatch-receiver validation / unresolved slot
 * ====================================================================== */

#define JNATIVE_MIRROR_SET_BUCKETS 8192u
#define JNATIVE_MIRROR_SET_MASK    (JNATIVE_MIRROR_SET_BUCKETS - 1u)

typedef struct JNativeMirrorSetEntry {
    const void* ptr;
    struct JNativeMirrorSetEntry* next;
} JNativeMirrorSetEntry;

static JNativeMirrorSetEntry* jnative_mirror_set[JNATIVE_MIRROR_SET_BUCKETS];
static jn_once_t         jnative_mirror_set_once = JN_ONCE_INIT;

static uintptr_t jnative_hash_pointer(const void* p) {
    uintptr_t h = (uintptr_t)p;
    h ^= h >> 33; h *= 0xff51afd7ed558ccdULL;
    h ^= h >> 33; h *= 0xc4ceb9fe1a85ec53ULL;
    h ^= h >> 33;
    return h;
}

static void jnative_build_mirror_set(void) {
    ReflectionClass** pp = reflect_all_classes;
    while (*pp != NULL) {
        const void* ptr = (const void*)*pp;
        size_t bucket =
            (size_t)(jnative_hash_pointer(ptr) & JNATIVE_MIRROR_SET_MASK);
        JNativeMirrorSetEntry* e =
            (JNativeMirrorSetEntry*)malloc(sizeof(*e));
        if (e != NULL) {
            e->ptr  = ptr;
            e->next = jnative_mirror_set[bucket];
            jnative_mirror_set[bucket] = e;
        }
        pp++;
    }
}

int __jnative_is_class_mirror(const void* ptr) {
    if (ptr == NULL) return 0;
    jn_once(&jnative_mirror_set_once, jnative_build_mirror_set);
    size_t bucket =
        (size_t)(jnative_hash_pointer(ptr) & JNATIVE_MIRROR_SET_MASK);
    for (JNativeMirrorSetEntry* e = jnative_mirror_set[bucket]; e; e = e->next) {
        if (e->ptr == ptr) return 1;
    }
    return 0;
}

static JNativeVTable* jnative_cached_object_vtable = NULL;
static jn_once_t jnative_object_vtable_once  = JN_ONCE_INIT;

static void jnative_init_object_vtable(void) {
    jnative_cached_object_vtable =
        (JNativeVTable*)__jnative_own_class_vtable("java/lang/Object");
}

JNativeVTable* __jnative_resolve_dispatch_vtable(void* obj) {
    if (obj == NULL) { __jnative_throw_null_pointer_exception(); }
    void* first = *(void**)obj;
    if (first == NULL) __jnative_throw_bad_vtable("virtual dispatch", obj);
    if (__jnative_is_class_mirror(first)) {
        jn_once(&jnative_object_vtable_once, jnative_init_object_vtable);
        if (jnative_cached_object_vtable == NULL) {
            __jnative_throw_bad_vtable(
                "virtual dispatch on array receiver "
                "(java/lang/Object has no registered vtable)", obj);
        }
        return jnative_cached_object_vtable;
    }
    return (JNativeVTable*)first;
}

__attribute__((noreturn))
void __jnative_throw_bad_dispatch(void* obj, int32_t slot,
                                  const char* caller) {
    char line[1024];
    int n = snprintf(line, sizeof(line),
        "\n=== JNative corrupted dispatch ===\n"
        "Caller : %s\nReceiver: %p\nSlot   : %d\n",
        caller != NULL ? caller : "<unknown>", obj, slot);
    if (n > 0) (void)!write(2, line, (size_t)n);
    if (obj != NULL) {
        void* first = *(void**)obj;
        const char* kind;
        if (first == NULL) kind = "NULL (obj[0] was never written)";
        else if (__jnative_is_class_mirror(first)) kind = "registered class mirror";
        else kind = "not a registered class mirror";
        n = snprintf(line, sizeof(line),
                     "obj[0] : %p  (%s)\n", first, kind);
        if (n > 0) (void)!write(2, line, (size_t)n);
    }
    n = snprintf(line, sizeof(line),
                 "\nCall tree (from dispatch point):\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    __jnative_unwind_with_backtrace(0);
    fflush(stderr);
    signal(SIGABRT, SIG_DFL);
    abort();
}

__attribute__((noreturn))
void __jnative_unresolved_slot(const char* what) {
    char line[1024];
    int n = snprintf(line, sizeof(line),
        "\n=== JNative unresolved vtable/itable slot ===\n%s\n",
        what != NULL ? what : "<unknown slot>");
    if (n > 0) (void)!write(2, line, (size_t)n);
    n = snprintf(line, sizeof(line), "\nCall tree (from throw point):\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    __jnative_unwind_with_backtrace(0);
    fflush(stderr);
    signal(SIGABRT, SIG_DFL);
    abort();
}

/* ========================================================================
 *  String / array / reflection helpers
 * ====================================================================== */

void* __jnative_rc_new_array(void* mirror, int32_t length, int32_t elem_size, void** hold) {
    if (length < 0) {
        __jnative_throw_exception(__jnative_make_exception_object(
            "vtable_java_lang_NegativeArraySizeException", "negative array size"));
    }
    void* array = jnative_array_alloc_with_class((ReflectionClass*)mirror, length, elem_size);
    if (array == NULL) __jnative_throw_out_of_memory_error_ctx("ARC array");
    jn_rc_register_array(array, hold);
    return array;
}

void* __jnative_create_string_array(int argc, char** argv) {
    void* array = jnative_array_alloc("[Ljava/lang/String;",
                                      argc, JNATIVE_ELEM_SIZE_REFERENCE);
    if (!array) return NULL;
    void** slots = (void**)((char*)array + JAVA_ARR_HDR);
    for (int i = 0; i < argc; i++) {
        int len = (int)strlen(argv[i]);
        slots[i] = __jnative_make_string_obj(argv[i], len);
        __jnative_rc_inc(slots[i]);
    }
    return array;
}

static void* create_multi_array_rec(const char* desc, int last_dim,
                                    int* sizes, int current_dim,
                                    int elem_size) {
    int is_last = (current_dim == last_dim);
    int length = sizes[current_dim];
    int slot_size = is_last ? elem_size : (int)sizeof(void*);
    for (int i = 0; i <= current_dim; i++) {
        if (desc[i] != '[') return NULL;
    }
    const char* sub_desc = desc + current_dim;
    void* array = jnative_array_alloc(sub_desc, length, slot_size);
    if (array == NULL) return NULL;
    if (!is_last) {
        void** slots = (void**)((char*)array + JAVA_ARR_HDR);
        for (int i = 0; i < length; i++) {
            slots[i] = create_multi_array_rec(desc, last_dim, sizes,
                                              current_dim + 1, elem_size);
            __jnative_rc_inc(slots[i]);
        }
    }
    return array;
}

void* __jnative_new_multi_array(const char* desc, int dims,
                                int* sizes, int elem_size) {
    if (dims <= 0 || sizes == NULL) return NULL;
    int last_dim = dims - 1;
    return create_multi_array_rec(desc, last_dim, sizes, 0, elem_size);
}

void* __jnative_invoke_method(ReflectionMethod* method, void* obj,
                              void** args) {
    if (method == NULL || method->adaptor == NULL) return NULL;
    typedef void* (*adaptor_t)(void*, void**);
    adaptor_t adaptor = (adaptor_t)method->adaptor;
    return adaptor(obj, args);
}

void* __jnative_new_instance(ReflectionConstructor* ctor, void** args) {
    if (ctor == NULL || ctor->adaptor == NULL) return NULL;
    typedef void* (*adaptor_t)(void**);
    adaptor_t adaptor = (adaptor_t)ctor->adaptor;
    return adaptor(args);
}

extern JNativeVTable vtable_java_lang_String;

static int32_t jn_utf8_decode(const uint8_t* in, int32_t n, uint16_t* out) {
    int32_t r = 0, w = 0;
    while (r < n) {
        uint32_t b = in[r];
        uint32_t cp;
        int32_t need;
        uint32_t min;
        if (b < 0x80) { out[w++] = (uint16_t)b; r++; continue; }
        else if ((b & 0xE0) == 0xC0) { cp = b & 0x1F; need = 1; min = 0x80; }
        else if ((b & 0xF0) == 0xE0) { cp = b & 0x0F; need = 2; min = 0x800; }
        else if ((b & 0xF8) == 0xF0) { cp = b & 0x07; need = 3; min = 0x10000; }
        else { out[w++] = 0xFFFD; r++; continue; }
        int32_t k = 1;
        while (k <= need && r + k < n && (in[r + k] & 0xC0) == 0x80) {
            cp = (cp << 6) | (in[r + k] & 0x3F);
            k++;
        }
        if (k <= need || cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out[w++] = 0xFFFD;
            r += k;
            continue;
        }
        r += k;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out[w++] = (uint16_t)(0xD800 | (cp >> 10));
            out[w++] = (uint16_t)(0xDC00 | (cp & 0x3FF));
        } else {
            out[w++] = (uint16_t)cp;
        }
    }
    return w;
}

static void* jn_make_string(const char* bytes, int32_t len, uint16_t* units, int32_t nunits);

void* __jnative_make_string_obj(const char* bytes, int32_t len) {
    if (len < 0) len = 0;
    if (bytes == NULL) len = 0;
    for (int32_t i = 0; i < len; i++) {
        if ((uint8_t)bytes[i] & 0x80) {
            uint16_t* units = (uint16_t*)malloc((size_t)len * sizeof(uint16_t));
            if (units == NULL)
                __jnative_throw_out_of_memory_error_ctx("__jnative_make_string_obj");
            int32_t nunits = jn_utf8_decode((const uint8_t*)bytes, len, units);
            return jn_make_string(NULL, 0, units, nunits);
        }
    }
    return jn_make_string(bytes, len, NULL, 0);
}

void* __jnative_make_string_mutf8(const uint8_t* bytes, int32_t len) {
    if (bytes == NULL || len <= 0) return jn_make_string(NULL, 0, NULL, 0);
    uint16_t* units = (uint16_t*)malloc((size_t)len * sizeof(uint16_t));
    if (units == NULL)
        __jnative_throw_out_of_memory_error_ctx("__jnative_make_string_mutf8");
    int32_t n = 0;
    for (int32_t i = 0; i < len; ) {
        uint8_t c = bytes[i];
        if (c < 0x80) {
            units[n++] = c;
            i++;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < len) {
            units[n++] = (uint16_t)(((c & 0x1F) << 6) | (bytes[i + 1] & 0x3F));
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < len) {
            units[n++] = (uint16_t)(((c & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F));
            i += 3;
        } else {
            units[n++] = 0xFFFD;
            i++;
        }
    }
    return jn_make_string(NULL, 0, units, n);
}

static void* jn_make_string(const char* bytes, int32_t len, uint16_t* units, int32_t nunits) {
    uint8_t coder = 0;
    int32_t blen = len;
    if (units != NULL) {
        for (int32_t j = 0; j < nunits; j++) {
            if (units[j] > 0xFF) { coder = 1; break; }
        }
        blen = nunits << coder;
    }
    ReflectionClass* ba_cls = jnative_get_byte_array_class();
    void* value = jnative_array_alloc_with_class(
        ba_cls, blen + 1, JNATIVE_ELEM_SIZE_BYTE);
    if (value == NULL) {
        free(units);
        __jnative_throw_out_of_memory_error_ctx("jn_make_string");
    }
    *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = blen;
    uint8_t* payload = (uint8_t*)value + JAVA_ARR_HDR;
    if (units == NULL) {
        if (len > 0) memcpy(payload, bytes, (size_t)len);
    } else if (coder == 0) {
        for (int32_t j = 0; j < nunits; j++) payload[j] = (uint8_t)units[j];
    } else {
        for (int32_t j = 0; j < nunits; j++) {
            payload[j * 2]     = (uint8_t)(units[j] & 0xFF);
            payload[j * 2 + 1] = (uint8_t)(units[j] >> 8);
        }
    }
    free(units);
    payload[blen] = 0;

    void* s = calloc(1, 32);
    if (s == NULL)
        __jnative_throw_out_of_memory_error_ctx("__jnative_make_string_obj");
    *(void**)((char*)s + 0)    = (void*)&vtable_java_lang_String;
    __jnative_rc_inc(value);
    *(void**)((char*)s + 8)    = value;
    *(uint8_t*)((char*)s + 16) = coder;
    *(int32_t*)((char*)s + 20) = 0;
    *(uint8_t*)((char*)s + 24) = 0;
    return s;
}

#define JN_UTF8_RING 8

typedef struct { char* buf[JN_UTF8_RING]; size_t cap[JN_UTF8_RING]; int next; } JnUtf8Ring;

static jn_tls_key_t jn_utf8_ring_key;
static jn_once_t jn_utf8_ring_once = JN_ONCE_INIT;

static void jn_utf8_ring_destroy(void* p) {
    if (p == NULL) return;
    JnUtf8Ring* r = (JnUtf8Ring*)p;
    for (int i = 0; i < JN_UTF8_RING; i++) free(r->buf[i]);
    free(r);
}

static void jn_utf8_ring_init(void) {
    jn_tls_key_create(&jn_utf8_ring_key, jn_utf8_ring_destroy);
}

static int32_t jn_string_to_utf8(const uint8_t* src, int32_t blen, uint8_t coder, char* out) {
    int32_t w = 0;
    if (coder == 0) {
        for (int32_t i = 0; i < blen; i++) {
            uint8_t c = src[i];
            if (c < 0x80) {
                out[w++] = (char)c;
            } else {
                out[w++] = (char)(0xC0 | (c >> 6));
                out[w++] = (char)(0x80 | (c & 0x3F));
            }
        }
        return w;
    }
    int32_t n = blen / 2;
    for (int32_t i = 0; i < n; i++) {
        uint32_t c = (uint32_t)src[i * 2] | ((uint32_t)src[i * 2 + 1] << 8);
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n) {
            uint32_t d = (uint32_t)src[i * 2 + 2] | ((uint32_t)src[i * 2 + 3] << 8);
            if (d >= 0xDC00 && d <= 0xDFFF) {
                uint32_t cp = 0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00);
                out[w++] = (char)(0xF0 | (cp >> 18));
                out[w++] = (char)(0x80 | ((cp >> 12) & 0x3F));
                out[w++] = (char)(0x80 | ((cp >> 6) & 0x3F));
                out[w++] = (char)(0x80 | (cp & 0x3F));
                i++;
                continue;
            }
        }
        if (c >= 0xD800 && c <= 0xDFFF) {
            out[w++] = '?';
        } else if (c < 0x80) {
            out[w++] = (char)c;
        } else if (c < 0x800) {
            out[w++] = (char)(0xC0 | (c >> 6));
            out[w++] = (char)(0x80 | (c & 0x3F));
        } else {
            out[w++] = (char)(0xE0 | (c >> 12));
            out[w++] = (char)(0x80 | ((c >> 6) & 0x3F));
            out[w++] = (char)(0x80 | (c & 0x3F));
        }
    }
    return w;
}

const char* __jnative_read_string_bytes(void* s, int32_t* out_len) {
    if (out_len) *out_len = 0;
    if (s == NULL) return "";
    void* value = *(void**)((char*)s + 8);
    if (value == NULL) return "";
    int32_t blen = *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET);
    if (blen < 0) blen = 0;
    uint8_t coder = *(uint8_t*)((char*)s + 16);
    const uint8_t* src = (const uint8_t*)value + JAVA_ARR_HDR;
    int ascii = (coder == 0);
    for (int32_t i = 0; ascii && i < blen; i++) {
        if (src[i] & 0x80) ascii = 0;
    }
    if (ascii && out_len != NULL) {
        *out_len = blen;
        return (const char*)src;
    }
    jn_once(&jn_utf8_ring_once, jn_utf8_ring_init);
    JnUtf8Ring* ring = (JnUtf8Ring*)jn_tls_get(jn_utf8_ring_key);
    if (ring == NULL) {
        ring = (JnUtf8Ring*)calloc(1, sizeof(JnUtf8Ring));
        if (ring == NULL) return "";
        jn_tls_set(jn_utf8_ring_key, ring);
    }
    int slot = ring->next;
    ring->next = (slot + 1) % JN_UTF8_RING;
    size_t need = (size_t)blen * 2 + 1;
    if (ring->cap[slot] < need) {
        char* nb = (char*)realloc(ring->buf[slot], need);
        if (nb == NULL) return "";
        ring->buf[slot] = nb;
        ring->cap[slot] = need;
    }
    int32_t n = jn_string_to_utf8(src, blen, coder, ring->buf[slot]);
    ring->buf[slot][n] = '\0';
    if (out_len) *out_len = n;
    return ring->buf[slot];
}

int32_t __jnative_read_string_into(void* s, char* out, int32_t out_cap) {
    if (out == NULL || out_cap <= 0) return -1;
    out[0] = '\0';
    if (s == NULL) return -1;
    int32_t len = 0;
    const char* src = __jnative_read_string_bytes(s, &len);
    if (len >= out_cap) return -1;
    if (len > 0) memcpy(out, src, (size_t)len);
    out[len] = '\0';
    return len;
}

static jn_tls_key_t tls_cstr_key;
static jn_once_t tls_cstr_key_once = JN_ONCE_INIT;

typedef struct { char* buf; size_t cap; } TlsCstrBuf;

static void tls_cstr_destroy(void* p) {
    if (p != NULL) {
        TlsCstrBuf* t = (TlsCstrBuf*)p;
        free(t->buf);
        free(t);
    }
}

static void tls_cstr_key_init(void) {
    jn_tls_key_create(&tls_cstr_key, tls_cstr_destroy);
}

const char* __jnative_read_string_cstr(void* s, int32_t* out_len) {
    if (out_len != NULL) *out_len = 0;
    if (s == NULL) return "";
    int32_t len = 0;
    const char* src = __jnative_read_string_bytes(s, &len);
    jn_once(&tls_cstr_key_once, tls_cstr_key_init);
    TlsCstrBuf* tls = (TlsCstrBuf*)jn_tls_get(tls_cstr_key);
    if (tls == NULL) {
        tls = (TlsCstrBuf*)calloc(1, sizeof(TlsCstrBuf));
        if (tls == NULL) return NULL;
        jn_tls_set(tls_cstr_key, tls);
    }
    size_t need = (size_t)len + 1;
    if (tls->cap < need) {
        size_t new_cap = tls->cap ? tls->cap : 256;
        while (new_cap < need) new_cap <<= 1;
        char* nb = (char*)realloc(tls->buf, new_cap);
        if (nb == NULL) return NULL;
        tls->buf = nb;
        tls->cap = new_cap;
    }
    if (len > 0) memcpy(tls->buf, src, (size_t)len);
    tls->buf[len] = '\0';
    if (out_len != NULL) *out_len = len;
    return tls->buf;
}

static int32_t __jnative_string_eq(void* a, void* b) {
    int32_t la = 0, lb = 0;
    const char* ba = __jnative_read_string_bytes(a, &la);
    const char* bb = __jnative_read_string_bytes(b, &lb);
    return la == lb && memcmp(ba, bb, (size_t)la) == 0;
}

typedef struct JNativeInternEntry {
    void* str;
    struct JNativeInternEntry* next;
} JNativeInternEntry;

#define JNATIVE_INTERN_BUCKETS 65536
static JNativeInternEntry* __jnative_intern_table[JNATIVE_INTERN_BUCKETS];
static jn_mutex_t __jnative_intern_lock = JN_MUTEX_INITIALIZER;

static uint32_t __jnative_string_hash(void* s) {
    int32_t len = 0;
    const char* bytes = __jnative_read_string_bytes(s, &len);
    uint32_t h = 2166136261u;
    for (int32_t i = 0; i < len; i++) {
        h ^= (uint8_t)bytes[i];
        h *= 16777619u;
    }
    return h;
}

void* __jnative_concat_strings(int count, ...) {
    va_list args;
    va_start(args, count);
    size_t total = 0;
    for (int i = 0; i < count; i++) {
        void* s = va_arg(args, void*);
        int32_t len = 0;
        __jnative_read_string_bytes(s, &len);
        total += (size_t)len;
    }
    va_end(args);
    char* buf = malloc(total + 1);
    if (buf == NULL)
        __jnative_throw_null_pointer_exception_ctx(NULL, NULL);
    size_t pos = 0;
    va_start(args, count);
    for (int i = 0; i < count; i++) {
        void* s = va_arg(args, void*);
        int32_t len = 0;
        const char* bytes = __jnative_read_string_bytes(s, &len);
        if (len > 0) memcpy(buf + pos, bytes, (size_t)len);
        pos += (size_t)len;
    }
    va_end(args);
    buf[pos] = '\0';
    void* result = __jnative_make_string_obj(buf, (int32_t)pos);
    free(buf);
    return result;
}

extern const int32_t __jnative_tostring_slot;

void* __jnative_value_to_string_int(int32_t v) {
    char buf[16];
    int n = snprintf(buf, sizeof(buf), "%d", v);
    return __jnative_make_string_obj(buf, n);
}
void* __jnative_value_to_string_long(int64_t v) {
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%lld", (long long)v);
    return __jnative_make_string_obj(buf, n);
}
void* __jnative_value_to_string_float(float v) {
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%g", (double)v);
    return __jnative_make_string_obj(buf, n);
}
void* __jnative_value_to_string_double(double v) {
    char buf[32];
    int n = snprintf(buf, sizeof(buf), "%g", v);
    return __jnative_make_string_obj(buf, n);
}
void* __jnative_value_to_string_boolean(int32_t v) {
    return v ? __jnative_make_string_obj("true", 4)
             : __jnative_make_string_obj("false", 5);
}
void* __jnative_value_to_string_char(int32_t v) {
    char c = (char)(v & 0xFF);
    return __jnative_make_string_obj(&c, 1);
}
void* __jnative_value_to_string_byte(int32_t v) {
    return __jnative_value_to_string_int((int8_t)v);
}
void* __jnative_value_to_string_short(int32_t v) {
    return __jnative_value_to_string_int((int16_t)v);
}
void* __jnative_value_to_string_object(void* obj) {
    if (obj == NULL) return __jnative_make_string_obj("null", 4);
    JNativeVTable* vt = *(JNativeVTable**)obj;
    if (vt == NULL) return __jnative_make_string_obj("null", 4);
    int32_t slot = __jnative_tostring_slot;
    if (slot < 0) return __jnative_make_string_obj("null", 4);
    void* entry = vt->methods[slot];
    if (entry == NULL) return __jnative_make_string_obj("null", 4);
    void* (*toString)(void*) = (void* (*)(void*))entry;
    return toString(obj);
}

static jn_once_t __jnative_intern_literals_once = JN_ONCE_INIT;

static void __jnative_intern_literals_init(void) {
    for (int32_t i = __jnative_literal_pool_size - 1; i >= 0; i--) {
        void* lit = __jnative_literal_pool[i];
        if (lit == NULL) continue;
        uint32_t bucket = __jnative_string_hash(lit) % JNATIVE_INTERN_BUCKETS;
        JNativeInternEntry* ne = malloc(sizeof(JNativeInternEntry));
        if (ne == NULL) continue;
        ne->str = lit;
        ne->next = __jnative_intern_table[bucket];
        __jnative_intern_table[bucket] = ne;
    }
}

void* __jnative_string_intern(void* this_str) {
    if (this_str == NULL) return NULL;
    jn_once(&__jnative_intern_literals_once, __jnative_intern_literals_init);
    uint32_t h = __jnative_string_hash(this_str);
    uint32_t bucket = h % JNATIVE_INTERN_BUCKETS;
    jn_mutex_lock(&__jnative_intern_lock);
    for (JNativeInternEntry* e = __jnative_intern_table[bucket]; e;
         e = e->next) {
        if (__jnative_string_eq(e->str, this_str)) {
            void* found = e->str;
            jn_mutex_unlock(&__jnative_intern_lock);
            return found;
        }
    }
    JNativeInternEntry* ne = malloc(sizeof(JNativeInternEntry));
    if (ne != NULL) {
        __jnative_rc_inc(this_str);
        ne->str = this_str;
        ne->next = __jnative_intern_table[bucket];
        __jnative_intern_table[bucket] = ne;
    }
    jn_mutex_unlock(&__jnative_intern_lock);
    return this_str;
}

extern const int32_t __jnative_runnable_iface_id;
extern const int32_t __jnative_run_method_slot;

void __jnative_invoke_runnable(void* runnable) {
    if (runnable == NULL) return;
    const int32_t iface_id = __jnative_runnable_iface_id;
    const int32_t slot     = __jnative_run_method_slot;
    if (iface_id < 0 || slot < 0) return;
    JNativeVTable* vt = *(JNativeVTable**)runnable;
    if (vt == NULL) return;
    void** itable = __jnative_lookup_itable(vt->ifacemap, iface_id);
    if (itable == NULL) return;
    void* entry = itable[slot];
    if (entry == NULL) return;
    void (*run)(void*) = (void (*)(void*))entry;
    run(runnable);
}

void __jnative_debug_clinit(const char* name) {
    static int enabled = -1;
    if (enabled < 0) {
        enabled = (getenv("JNATIVE_TRACE_CLINIT") != NULL) ? 1 : 0;
    }
    if (!enabled) return;
    fprintf(stderr, "[clinit] %s\n", name);
    fflush(stderr);
}

extern const JNativeAnnotationClass jnative_annotation_classes[] JNATIVE_WEAK;
extern const int64_t jnative_annotation_classes_size JNATIVE_WEAK;

static const JNativeAnnotationClass** jn_annot_table;
static size_t jn_annot_mask;
static int32_t** jn_annot_cp_offsets;
static jn_once_t jn_annot_once = JN_ONCE_INIT;
static jn_mutex_t jn_annot_cp_lock = JN_MUTEX_INITIALIZER;

static size_t jn_annot_hash(const void* p) {
    uintptr_t v = (uintptr_t)p;
    v ^= v >> 17;
    v *= (uintptr_t)0x9E3779B97F4A7C15ull;
    return (size_t)(v ^ (v >> 29));
}

static void jn_annot_init(void) {
    if ((uintptr_t)&jnative_annotation_classes_size == 0) return;
    int64_t n = jnative_annotation_classes_size;
    if (n <= 0) return;
    size_t cap = 16;
    while (cap < (size_t)n * 2) cap <<= 1;
    const JNativeAnnotationClass** table =
        (const JNativeAnnotationClass**)calloc(cap, sizeof(*table));
    int32_t** offsets = (int32_t**)calloc((size_t)n, sizeof(int32_t*));
    if (table == NULL || offsets == NULL) { free(table); free(offsets); return; }
    for (int64_t i = 0; i < n; i++) {
        const JNativeAnnotationClass* e = &jnative_annotation_classes[i];
        if (e->cls == NULL) continue;
        size_t h = jn_annot_hash(e->cls) & (cap - 1);
        while (table[h] != NULL) h = (h + 1) & (cap - 1);
        table[h] = e;
    }
    jn_annot_cp_offsets = offsets;
    jn_annot_mask = cap - 1;
    jn_annot_table = table;
}

const JNativeAnnotationClass* jnative_annotation_class(const ReflectionClass* cls) {
    if (cls == NULL) return NULL;
    jn_once(&jn_annot_once, jn_annot_init);
    if (jn_annot_table == NULL) return NULL;
    size_t h = jn_annot_hash(cls) & jn_annot_mask;
    while (jn_annot_table[h] != NULL) {
        if (jn_annot_table[h]->cls == cls) return jn_annot_table[h];
        h = (h + 1) & jn_annot_mask;
    }
    return NULL;
}

const JNativeAnnotationMember* jnative_annotation_member(const ReflectionClass* cls,
                                                         const char* name, const char* descriptor) {
    const JNativeAnnotationClass* e = jnative_annotation_class(cls);
    if (e == NULL || name == NULL || descriptor == NULL) return NULL;
    for (int32_t i = 0; i < e->member_count; i++) {
        const JNativeAnnotationMember* m = &e->members[i];
        if (strcmp(m->name, name) == 0 && strcmp(m->descriptor, descriptor) == 0) return m;
    }
    return NULL;
}

static int32_t* jn_annot_build_offsets(const JNativeAnnotationClass* e) {
    int32_t* off = (int32_t*)calloc((size_t)(e->constant_pool_count > 0 ? e->constant_pool_count : 1),
                                    sizeof(int32_t));
    if (off == NULL) return NULL;
    const uint8_t* cp = e->constant_pool;
    int32_t pos = 0;
    for (int32_t i = 1; i < e->constant_pool_count && pos < e->constant_pool_len; i++) {
        off[i] = pos;
        uint8_t tag = cp[pos];
        switch (tag) {
            case 1:  pos += 3 + ((cp[pos + 1] << 8) | cp[pos + 2]); break;
            case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: pos += 5; break;
            case 5: case 6: pos += 9; i++; if (i < e->constant_pool_count) off[i] = -1; break;
            case 7: case 8: case 16: case 19: case 20: pos += 3; break;
            case 15: pos += 4; break;
            default: free(off); return NULL;
        }
    }
    return off;
}

const uint8_t* jnative_constant_pool_entry(const ReflectionClass* cls, int32_t index, uint8_t* tag) {
    const JNativeAnnotationClass* e = jnative_annotation_class(cls);
    if (e == NULL || index <= 0 || index >= e->constant_pool_count) return NULL;
    size_t slot = (size_t)(e - jnative_annotation_classes);
    jn_mutex_lock(&jn_annot_cp_lock);
    int32_t* off = jn_annot_cp_offsets[slot];
    if (off == NULL) {
        off = jn_annot_build_offsets(e);
        jn_annot_cp_offsets[slot] = off;
    }
    jn_mutex_unlock(&jn_annot_cp_lock);
    if (off == NULL || off[index] < 0) return NULL;
    const uint8_t* p = e->constant_pool + off[index];
    if (tag != NULL) *tag = p[0];
    return p + 1;
}

void* jnative_annotation_bytes(const uint8_t* data, int32_t len) {
    if (data == NULL || len <= 0) return NULL;
    void* arr = jnative_byte_array(data, len);
    if (arr == NULL) __jnative_throw_out_of_memory_error_ctx("annotation bytes");
    return arr;
}

const JNativeResourceEntry* jnative_find_resource(const char* path,
                                                  int32_t len) {
    if (path == NULL || len <= 0) return NULL;
    int32_t n = jnative_builtin_resources_count;
    for (int32_t i = 0; i < n; i++) {
        const char* p = jnative_builtin_resources[i].path;
        if (p == NULL) continue;
        size_t pl = strlen(p);
        if ((int32_t)pl == len && memcmp(p, path, (size_t)len) == 0) {
            return &jnative_builtin_resources[i];
        }
    }
    return NULL;
}

#define BAIS_BUF_OFFSET   8
#define BAIS_POS_OFFSET   16
#define BAIS_MARK_OFFSET  20
#define BAIS_COUNT_OFFSET 24

void* __jnative_make_byte_array_input_stream(void* bytes) {
    if (bytes == NULL)
        __jnative_throw_null_pointer_exception_ctx(
            "__jnative_make_byte_array_input_stream", "bytes");
    ReflectionClass* cls =
        jnative_class_by_name("java/io/ByteArrayInputStream");
    if (cls == NULL) {
        fprintf(stderr, "jnative: fatal: java.io.ByteArrayInputStream is not "
                        "registered\n");
        _exit(1);
    }
    void* bais = jnative_alloc_object(cls);
    if (bais == NULL)
        __jnative_throw_out_of_memory_error_ctx("Class.getResourceAsStream");
    int32_t len = jnative_array_length(bytes);
    __jnative_rc_inc(bytes);
    *(void**)((char*)bais + BAIS_BUF_OFFSET)    = bytes;
    *(int32_t*)((char*)bais + BAIS_POS_OFFSET)   = 0;
    *(int32_t*)((char*)bais + BAIS_MARK_OFFSET)  = 0;
    *(int32_t*)((char*)bais + BAIS_COUNT_OFFSET) = len;
    return bais;
}

/* ========================================================================
 *  Parallel <clinit> scheduling
 * ====================================================================== */

typedef struct {
    void**  fns;
    int32_t start;
    int32_t end;
} JNativeParallelChunk;

static void jnative_run_parallel_chunk(JNativeParallelChunk* c) {
    for (int32_t i = c->start; i < c->end; i++) {
        ((void (*)(void))c->fns[i])();
    }
}

static void* jnative_parallel_worker(void* arg) {
    jnative_run_parallel_chunk((JNativeParallelChunk*)arg);
    __jnative_rc_thread_exit();
    return NULL;
}

void __jnative_run_parallel(void** fns, int32_t count) {
    if (fns == NULL || count <= 0) return;
    if (count == 1) {
        ((void (*)(void))fns[0])();
        return;
    }
#if defined(_WIN32)
    int32_t max_threads = (int32_t)jn_cpu_count();
#else
    int32_t max_threads = (int32_t)sysconf(_SC_NPROCESSORS_ONLN);
#endif
    if (max_threads <= 0) max_threads = 4;
    if (max_threads > count) max_threads = count;
    if (max_threads <= 1) {
        for (int32_t i = 0; i < count; i++) ((void (*)(void))fns[i])();
        return;
    }
    int32_t chunk_size = (count + max_threads - 1) / max_threads;
    int32_t n_chunks   = (count + chunk_size - 1) / chunk_size;
    jn_thread_t* threads =
        (jn_thread_t*)malloc((size_t)n_chunks * sizeof(jn_thread_t));
    JNativeParallelChunk* chunks =
        (JNativeParallelChunk*)malloc((size_t)n_chunks *
                                      sizeof(JNativeParallelChunk));
    if (threads == NULL || chunks == NULL) {
        free(threads);
        free(chunks);
        for (int32_t i = 0; i < count; i++) ((void (*)(void))fns[i])();
        return;
    }
    for (int32_t c = 0; c < n_chunks; c++) {
        chunks[c].fns   = fns;
        chunks[c].start = c * chunk_size;
        chunks[c].end   = (c + 1) * chunk_size;
        if (chunks[c].end > count) chunks[c].end = count;
        if (jn_thread_create(&threads[c], NULL, jnative_parallel_worker,
                             &chunks[c]) != 0) {
            threads[c] = jn_thread_none();
            jnative_run_parallel_chunk(&chunks[c]);
        }
    }
    for (int32_t c = 0; c < n_chunks; c++) {
        if (jn_thread_is_valid(threads[c])) jn_thread_join(threads[c], NULL);
    }
    free(threads);
    free(chunks);
}

/* ========================================================================
 *  Virtual destructor dispatch
 * ====================================================================== */

typedef void (*jnative_destructor_fn)(void*);

typedef struct JNativeDestructCacheEntry {
    void*                       key;
    jnative_destructor_fn       fn;
    struct JNativeDestructCacheEntry* next;
} JNativeDestructCacheEntry;

#define JNATIVE_DESTRUCT_BUCKETS 1024u
#define JNATIVE_DESTRUCT_MASK    (JNATIVE_DESTRUCT_BUCKETS - 1u)

static JNativeDestructCacheEntry*
    jnative_destruct_cache[JNATIVE_DESTRUCT_BUCKETS];
static jn_mutex_t jnative_destruct_lock = JN_MUTEX_INITIALIZER;

static uintptr_t jnative_destruct_hash(const void* p) {
    uintptr_t h = (uintptr_t)p;
    h ^= h >> 33; h *= 0xff51afd7ed558ccdULL;
    h ^= h >> 33; h *= 0xc4ceb9fe1a85ec53ULL;
    h ^= h >> 33;
    return h;
}

static int jnative_build_array_destructor_symbol(const char* desc,
                                                 char* out,
                                                 size_t out_size) {
    if (desc == NULL || desc[0] != '[' || out_size == 0) return 0;
    size_t pos = 0;
    static const char PREFIX[] = "__destruct_array_";
    for (size_t i = 0; PREFIX[i] != '\0'; i++) {
        if (pos + 1 >= out_size) return 0;
        out[pos++] = PREFIX[i];
    }
    int depth = 0;
    const char* p = desc;
    while (*p == '[') { depth++; p++; }
    if (depth == 0 || *p == '\0') return 0;
    char raw[1024];
    size_t rl = 0;
    for (int i = 0; i < depth; i++) {
        if (rl + 6 >= sizeof(raw)) return 0;
        memcpy(raw + rl, "array[", 6);
        rl += 6;
    }
    if (*p == 'L') {
        const char* semi = strchr(p, ';');
        if (semi == NULL) return 0;
        size_t inner_len = (size_t)(semi - p - 1);
        if (inner_len + 6 + rl >= sizeof(raw)) return 0;
        memcpy(raw + rl, "ref(", 4);
        rl += 4;
        memcpy(raw + rl, p + 1, inner_len);
        rl += inner_len;
        raw[rl++] = ')';
    } else {
        const char* pname = NULL;
        switch (*p) {
            case 'Z': pname = "boolean"; break;
            case 'B': pname = "byte";    break;
            case 'S': pname = "short";   break;
            case 'C': pname = "char";    break;
            case 'I': pname = "int";     break;
            case 'J': pname = "long";    break;
            case 'F': pname = "float";   break;
            case 'D': pname = "double";  break;
            case 'V': pname = "void";    break;
            default: return 0;
        }
        size_t n = strlen(pname);
        if (rl + n + 1 >= sizeof(raw)) return 0;
        memcpy(raw + rl, pname, n);
        rl += n;
    }
    for (int i = 0; i < depth; i++) {
        if (rl + 1 >= sizeof(raw)) return 0;
        raw[rl++] = ']';
    }
    raw[rl] = '\0';
    for (size_t i = 0; i < rl; i++) {
        unsigned char c = (unsigned char)raw[i];
        char r;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9') || c == '_' || c == '.') {
            r = (char)c;
        } else {
            r = '_';
        }
        if (pos + 1 >= out_size) return 0;
        out[pos++] = r;
    }
    out[pos] = '\0';
    return 1;
}

static jnative_destructor_fn jnative_resolve_destructor(void* key) {
    char symbol[1024];
    if (__jnative_is_class_mirror(key)) {
        ReflectionClass* mirror = (ReflectionClass*)key;
        if (mirror->cname == NULL) return NULL;
        if (!jnative_build_array_destructor_symbol(mirror->cname,
                                                   symbol,
                                                   sizeof(symbol))) {
            return NULL;
        }
    } else {
        JNativeVTable* vt = (JNativeVTable*)key;
        if (vt->name == NULL) return NULL;
        static const char PREFIX[] = "__destruct_";
        size_t pos = 0;
        for (size_t i = 0; PREFIX[i] != '\0'; i++) {
            if (pos + 1 >= sizeof(symbol)) return NULL;
            symbol[pos++] = PREFIX[i];
        }
        for (const char* p = vt->name; *p != '\0'; p++) {
            if (pos + 1 >= sizeof(symbol)) return NULL;
            char c = *p;
            symbol[pos++] = (c == '/' || c == '.') ? '_' : c;
        }
        symbol[pos] = '\0';
    }
    return (jnative_destructor_fn)jn_dlsym_default(symbol);
}

static jnative_destructor_fn jnative_destructor_for(void* key) {
    if (key == NULL) return NULL;
    size_t bucket =
        (size_t)(jnative_destruct_hash(key) & JNATIVE_DESTRUCT_MASK);
    jn_mutex_lock(&jnative_destruct_lock);
    for (JNativeDestructCacheEntry* e = jnative_destruct_cache[bucket]; e;
         e = e->next) {
        if (e->key == key) {
            jnative_destructor_fn fn = e->fn;
            jn_mutex_unlock(&jnative_destruct_lock);
            return fn;
        }
    }
    jn_mutex_unlock(&jnative_destruct_lock);
    jnative_destructor_fn fn = jnative_resolve_destructor(key);
    jn_mutex_lock(&jnative_destruct_lock);
    for (JNativeDestructCacheEntry* e = jnative_destruct_cache[bucket]; e;
         e = e->next) {
        if (e->key == key) {
            fn = e->fn;
            jn_mutex_unlock(&jnative_destruct_lock);
            return fn;
        }
    }
    JNativeDestructCacheEntry* ne =
        (JNativeDestructCacheEntry*)malloc(sizeof(*ne));
    if (ne != NULL) {
        ne->key  = key;
        ne->fn   = fn;
        ne->next = jnative_destruct_cache[bucket];
        jnative_destruct_cache[bucket] = ne;
    }
    jn_mutex_unlock(&jnative_destruct_lock);
    return fn;
}

void __jnative_destruct_dispatch(void* obj) {
    if (obj == NULL) return;
    void* first_word = *(void**)obj;
    if (first_word == NULL) return;
    jnative_destructor_fn fn = jnative_destructor_for(first_word);
    if (fn != NULL) fn(obj);
}
