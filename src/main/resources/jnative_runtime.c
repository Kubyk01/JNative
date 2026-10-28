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
#endif

#include "jnative_runtime.h"

/* ========================================================================
 *  POSIX process-image handle for symbol lookup.
 * ====================================================================== */
#ifndef _WIN32

void* jn_dlsym_default = NULL;

static void __attribute__((constructor))
_jn_init_dlsym_default_posix(void) {
    jn_dlsym_default = dlopen(NULL, RTLD_LAZY);
}

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
    if (reflect_all_classes == NULL) return;

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
    if (reflect_all_classes == NULL) return NULL;

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
static int jnative_collect_main_writable_segments(struct dl_phdr_info* info,
                                                  size_t size, void* data) {
    (void)size;
    (void)data;
    if (info->dlpi_name != NULL && info->dlpi_name[0] != '\0') return 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        if (!(ph->p_flags & PF_W)) continue;
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
    PIMAGE_SECTION_HEADER sec = IMAGE_FIRST_SECTION(nt);
    for (int i = 0; i < nt->FileHeader.NumberOfSections; i++) {
        DWORD chars = sec[i].Characteristics;
        if (!(chars & IMAGE_SCN_MEM_WRITE)) continue;
        if (!(chars & (IMAGE_SCN_CNT_INITIALIZED_DATA
                     | IMAGE_SCN_CNT_UNINITIALIZED_DATA))) continue;
        uintptr_t lo = (uintptr_t)self + sec[i].VirtualAddress;
        uintptr_t hi = lo + sec[i].Misc.VirtualSize;
        if (lo == 0 || hi <= lo) continue;
        if (jnative_static_low  == 0 || lo < jnative_static_low)  jnative_static_low  = lo;
        if (jnative_static_high == 0 || hi > jnative_static_high) jnative_static_high = hi;
    }
#elif defined(__linux__)
    dl_iterate_phdr(jnative_collect_main_writable_segments, NULL);
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

static inline uintptr_t jnative_mix(uintptr_t p) {
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

void __jnative_free(void* p) {
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

void __jnative_free_fast(void* p) {
    if (p == NULL) return;
    uintptr_t addr = (uintptr_t)p;
    uintptr_t lo = jnative_static_low;
    uintptr_t hi = jnative_static_high;
    if (lo != 0 && hi != 0 && addr >= lo && addr < hi) return;
    free(p);
}

/* ========================================================================
 *  Monitor table
 * ====================================================================== */

#define HASH_SIZE 1024

typedef struct MonitorEntry {
    void* obj;
    jn_mutex_t mutex;
    struct MonitorEntry* next;
} MonitorEntry;

static MonitorEntry* monitor_table[HASH_SIZE] = {0};
static jn_mutex_t table_lock = JN_MUTEX_INITIALIZER;

static uint32_t hash_ptr(void* p) {
    return (uint32_t)((uintptr_t)p) % HASH_SIZE;
}

static jn_mutex_t* find_mutex(void* obj) {
    uint32_t idx = hash_ptr(obj);
    MonitorEntry* entry = monitor_table[idx];
    while (entry) {
        if (entry->obj == obj) return &entry->mutex;
        entry = entry->next;
    }
    return NULL;
}

static jn_mutex_t* get_or_create_mutex(void* obj) {
    if (obj == NULL) return NULL;
    uint32_t idx = hash_ptr(obj);
    jn_mutex_lock(&table_lock);
    MonitorEntry* entry = monitor_table[idx];
    while (entry) {
        if (entry->obj == obj) {
            jn_mutex_unlock(&table_lock);
            return &entry->mutex;
        }
        entry = entry->next;
    }
    MonitorEntry* new_entry = malloc(sizeof(MonitorEntry));
    if (new_entry == NULL) {
        jn_mutex_unlock(&table_lock);
        return NULL;
    }
    new_entry->obj = obj;
    /* Recursive mutex: Java monitors are re-entrant. */
    jn_recmutex_init(&new_entry->mutex);
    new_entry->next = monitor_table[idx];
    monitor_table[idx] = new_entry;
    jn_mutex_unlock(&table_lock);
    return &new_entry->mutex;
}

void __jnative_monitor_destroy(void* obj) {
    if (obj == NULL) return;
    uint32_t idx = hash_ptr(obj);
    jn_mutex_lock(&table_lock);
    MonitorEntry** pp = &monitor_table[idx];
    while (*pp) {
        MonitorEntry* entry = *pp;
        if (entry->obj == obj) {
            *pp = entry->next;
            jn_mutex_destroy(&entry->mutex);
            free(entry);
            break;
        }
        pp = &entry->next;
    }
    jn_mutex_unlock(&table_lock);
}

void __jnative_monitor_enter(void* obj) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    jn_mutex_t* mtx = get_or_create_mutex(obj);
    if (mtx) jn_mutex_lock(mtx);
}

void __jnative_monitor_exit(void* obj) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    jn_mutex_lock(&table_lock);
    jn_mutex_t* mtx = find_mutex(obj);
    jn_mutex_unlock(&table_lock);
    if (mtx) jn_mutex_unlock(mtx);
}

/* ========================================================================
 *  Catch context stack
 * ====================================================================== */

typedef struct CatchContext {
    void*    type_info;
    void*    jmp_buf_ptr;
    uint64_t id;
} CatchContext;

#define CATCH_STACK_DEPTH 64

static JN_TLS CatchContext catch_stack[CATCH_STACK_DEPTH];
static JN_TLS int          catch_depth = 0;
static JN_TLS void*        current_exception = NULL;
static JN_TLS uint64_t     jnative_catch_id_counter = 1;

static inline CatchContext* current_catch_ctx(void) {
    return catch_depth > 0 ? &catch_stack[catch_depth - 1] : NULL;
}

void __jnative_push_catch(void* jmp_buf_ptr, void* type_info) {
    if (catch_depth >= CATCH_STACK_DEPTH) {
        fprintf(stderr, "jnative: try/catch nesting > %d\n", CATCH_STACK_DEPTH);
        abort();
    }
    CatchContext* ctx = &catch_stack[catch_depth++];
    ctx->type_info   = type_info;
    ctx->jmp_buf_ptr = jmp_buf_ptr;
    ctx->id          = jnative_catch_id_counter++;
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
    e->initializing_thread = (jn_thread_t)0;
    jn_cond_init(&e->done_cond, NULL);
    e->next = clinit_buckets[bucket];
    clinit_buckets[bucket] = e;
    return e;
}

__attribute__((noinline))
int32_t __jnative_clinit_enter(void* name_str) {
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    if (name == NULL || len <= 0) return 0;

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
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    if (name == NULL || len <= 0) return;
    clinit_stack_pop();
    jn_mutex_lock(&clinit_lock);
    ClinitEntry* e = clinit_lookup(name);
    if (e != NULL) {
        e->state               = CLINIT_STATE_DONE;
        e->initializing_thread = (jn_thread_t)0;
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
            e->initializing_thread = (jn_thread_t)0;
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
    if (__jnative_in_fatal_handler_win) return EXCEPTION_CONTINUE_SEARCH;
    __jnative_in_fatal_handler_win = 1;

    DWORD code = ep->ExceptionRecord->ExceptionCode;
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

__attribute__((constructor))
static void __jnative_auto_init(void) {
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

static JNativeVTable* __jnative_lookup_vtable_weak(const char* symbol) {
    void* handle = jn_dlsym_default;
    if (handle == NULL) return NULL;
    void* sym = dlsym(handle, symbol);
    if (handle != (void*)-1) dlclose(handle);
    return (JNativeVTable*)sym;
}

static void* __jnative_make_exception_object(const char* vtable_symbol,
                                             const char* message) {
    JNativeVTable* vt = __jnative_lookup_vtable_weak(vtable_symbol);
    if (vt == NULL) return NULL;
    void* exc = calloc(1, JNATIVE_EXCEPTION_OBJECT_SIZE);
    if (exc == NULL) return NULL;
    *(void**)((char*)exc + 0) = (void*)vt;
    if (message != NULL) {
        void* msg = __jnative_make_string_obj(message, (int32_t)strlen(message));
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

int __jnative_instanceof(void* obj, void** type_info) {
    if (obj == NULL || type_info == NULL) return 0;
    void* first_word = *(void**)obj;
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
        exc = __jnative_make_exception_object(
            "vtable_java_lang_Throwable",
            "NULL exception object substituted by __jnative_throw_exception_ctx");
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
        longjmp(*(jmp_buf*)ctx->jmp_buf_ptr, 1);
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
        longjmp(*(jmp_buf*)ctx->jmp_buf_ptr, 1);
    }
    __jnative_log_unhandled_exception(npe, "java.lang.NullPointerException",
                                      caller, var_desc);
}

__attribute__((noreturn))
void __jnative_throw_array_index_out_of_bounds_ctx(const char* caller) {
    void* exc = __jnative_make_array_index_out_of_bounds_exception(caller);
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) { mark_abandoned_clinits(ctx); longjmp(*(jmp_buf*)ctx->jmp_buf_ptr, 1); }
    __jnative_log_unhandled_exception(exc,
        "java.lang.ArrayIndexOutOfBoundsException", caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_class_cast_exception_ctx(const char* caller) {
    void* exc = __jnative_make_class_cast_exception(caller);
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) { mark_abandoned_clinits(ctx); longjmp(*(jmp_buf*)ctx->jmp_buf_ptr, 1); }
    __jnative_log_unhandled_exception(exc,
        "java.lang.ClassCastException", caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_arithmetic_exception_ctx(const char* caller) {
    void* exc = __jnative_make_arithmetic_exception(caller);
    current_exception = exc;
    CatchContext* ctx = current_catch_ctx();
    if (ctx) { mark_abandoned_clinits(ctx); longjmp(*(jmp_buf*)ctx->jmp_buf_ptr, 1); }
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
        longjmp(*(jmp_buf*)ctx->jmp_buf_ptr, 1);
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
    if (ctx) { mark_abandoned_clinits(ctx); longjmp(*(jmp_buf*)ctx->jmp_buf_ptr, 1); }
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
    if (ctx) { mark_abandoned_clinits(ctx); longjmp(*(jmp_buf*)ctx->jmp_buf_ptr, 1); }
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
    if (reflect_all_classes == NULL) return;
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

void* __jnative_create_string_array(int argc, char** argv) {
    void* array = jnative_array_alloc("[Ljava/lang/String;",
                                      argc, JNATIVE_ELEM_SIZE_REFERENCE);
    if (!array) return NULL;
    void** slots = (void**)((char*)array + JAVA_ARR_HDR);
    for (int i = 0; i < argc; i++) {
        int len = (int)strlen(argv[i]);
        slots[i] = __jnative_make_string_obj(argv[i], len);
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

void* __jnative_make_string_obj(const char* bytes, int32_t len) {
    if (len < 0) len = 0;
    ReflectionClass* ba_cls = jnative_get_byte_array_class();
    void* value = jnative_array_alloc_with_class(
        ba_cls, len + 1, JNATIVE_ELEM_SIZE_BYTE);
    if (value == NULL)
        __jnative_throw_out_of_memory_error_ctx("__jnative_make_string_obj");
    *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = len;
    if (len > 0 && bytes != NULL) {
        memcpy((char*)value + JAVA_ARR_HDR, bytes, (size_t)len);
    }
    ((char*)value)[JAVA_ARR_HDR + len] = '\0';

    void* s = calloc(1, 32);
    if (s == NULL)
        __jnative_throw_out_of_memory_error_ctx("__jnative_make_string_obj");
    *(void**)((char*)s + 0)    = (void*)&vtable_java_lang_String;
    *(void**)((char*)s + 8)    = value;
    *(uint8_t*)((char*)s + 16) = 0;
    *(int32_t*)((char*)s + 20) = 0;
    *(uint8_t*)((char*)s + 24) = 0;
    return s;
}

const char* __jnative_read_string_bytes(void* s, int32_t* out_len) {
    if (s == NULL) { if (out_len) *out_len = 0; return ""; }
    void* value = *(void**)((char*)s + 8);
    if (value == NULL) { if (out_len) *out_len = 0; return ""; }
    if (out_len)
        *out_len = *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET);
    return (const char*)value + JAVA_ARR_HDR;
}

int32_t __jnative_read_string_into(void* s, char* out, int32_t out_cap) {
    if (out == NULL || out_cap <= 0) return -1;
    out[0] = '\0';
    if (s == NULL) return -1;
    void* value = *(void**)((char*)s + 8);
    if (value == NULL) return 0;
    int32_t len = *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET);
    if (len < 0) return -1;
    if (len >= out_cap) return -1;
    const char* src = (const char*)value + JAVA_ARR_HDR;
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
    void* value = *(void**)((char*)s + 8);
    if (value == NULL) return "";
    int32_t len = *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET);
    if (len < 0) len = 0;
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
    const char* src = (const char*)value + JAVA_ARR_HDR;
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

#define JNATIVE_INTERN_BUCKETS 4096
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

void* __jnative_string_intern(void* this_str) {
    if (this_str == NULL) return NULL;
    if (__jnative_literal_pool_size > 0) {
        for (int32_t i = 0; i < __jnative_literal_pool_size; i++) {
            if (__jnative_literal_pool[i] != NULL
                && __jnative_string_eq(this_str, __jnative_literal_pool[i])) {
                return __jnative_literal_pool[i];
            }
        }
    }
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

static void* jnative_run_parallel_chunk(void* arg) {
    JNativeParallelChunk* c = (JNativeParallelChunk*)arg;
    for (int32_t i = c->start; i < c->end; i++) {
        ((void (*)(void))c->fns[i])();
    }
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
        if (jn_thread_create(&threads[c], NULL, jnative_run_parallel_chunk,
                             &chunks[c]) != 0) {
            threads[c] = 0;
            jnative_run_parallel_chunk(&chunks[c]);
        }
    }
    for (int32_t c = 0; c < n_chunks; c++) {
        if (threads[c] != 0) jn_thread_join(threads[c], NULL);
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