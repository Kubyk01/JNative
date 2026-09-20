#define _GNU_SOURCE
#include <stddef.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdint.h>
#include <setjmp.h>
#include <stdio.h>
#include <string.h>
#include <stdarg.h>
#include <execinfo.h>
#include <dlfcn.h>
#include <signal.h>
#include <unistd.h>
#include <ucontext.h>
#include <sys/ucontext.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void);
__attribute__((noreturn)) void __jnative_throw_class_cast_exception(void);
__attribute__((noreturn)) void __jnative_throw_arithmetic_exception(void);
__attribute__((noreturn)) void __jnative_throw_bad_vtable(void* method_name, void* obj);
__attribute__((noreturn)) void __jnative_throw_exception_ctx(void* exc, const char* caller);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception_ctx(const char* caller, const char* var_desc);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds_ctx(const char* caller);
__attribute__((noreturn)) void __jnative_throw_class_cast_exception_ctx(const char* caller);
__attribute__((noreturn)) void __jnative_throw_arithmetic_exception_ctx(const char* caller);
void* __jnative_get_exception_object(void);
int   __jnative_catch_matches(void* exc, void* type_info);
int   __jnative_instanceof(void* obj, void** type_info);

void* __jnative_make_string_obj(const char* bytes, int32_t len);
const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

/* ============================================================================
 * Reflection metadata layout
 * ========================================================================== */

struct ReflectionField {
    void* name;
    void* descriptor;
    int   offset;
    int   modifiers;
};

struct ReflectionMethod {
    void* name;
    void* descriptor;
    void* adaptor;
    int   modifiers;
};

struct ReflectionConstructor {
    void* descriptor;
    void* adaptor;
    int   modifiers;
};

struct ReflectionClass {
    void* vtable;
    void* name;
    struct ReflectionClass*   superclass;
    struct ReflectionClass**  interfaces;
    struct ReflectionMethod** methods;
    struct ReflectionField**  fields;
    struct ReflectionConstructor** constructors;
    int   modifiers;
    int   object_size;
};

/* ============================================================================
 * Virtual-table ABI
 * ========================================================================== */

struct JNativeIfaceMapEntry {
    int32_t id;
    void**  itable;
};

struct JNativeIfaceMap {
    int32_t count;
    struct JNativeIfaceMapEntry* entries;
};

struct JNativeVTable {
    void**  methods;
    struct JNativeIfaceMap* ifacemap;
    const char* name;
};

void** __jnative_lookup_itable(struct JNativeIfaceMap* ifacemap, int32_t iface_id) {
    if (ifacemap == NULL) return NULL;
    int32_t n = ifacemap->count;
    struct JNativeIfaceMapEntry* e = ifacemap->entries;
    if (e == NULL) return NULL;
    for (int32_t i = 0; i < n; i++) {
        if (e[i].id == iface_id) return e[i].itable;
    }
    return NULL;
}

static const char* __jnative_vtable_class_name(void* vtable) {
    if (vtable == NULL) return NULL;
    return ((struct JNativeVTable*)vtable)->name;
}

/* ============================================================================
 * Class registry
 * ========================================================================== */

extern struct ReflectionClass* reflect_all_classes[] __attribute__((weak));

/* ============================================================================
 * Monitor table
 * ========================================================================== */

#define HASH_SIZE 1024

typedef struct MonitorEntry {
    void* obj;
    pthread_mutex_t mutex;
    struct MonitorEntry* next;
} MonitorEntry;

static MonitorEntry* monitor_table[HASH_SIZE] = {0};
static pthread_mutex_t table_lock = PTHREAD_MUTEX_INITIALIZER;

static uint32_t hash_ptr(void* p) {
    return (uint32_t)((uintptr_t)p) % HASH_SIZE;
}

static pthread_mutex_t* find_mutex(void* obj) {
    uint32_t idx = hash_ptr(obj);
    MonitorEntry* entry = monitor_table[idx];
    while (entry) {
        if (entry->obj == obj) return &entry->mutex;
        entry = entry->next;
    }
    return NULL;
}

static pthread_mutex_t* get_or_create_mutex(void* obj) {
    if (obj == NULL) return NULL;
    uint32_t idx = hash_ptr(obj);
    pthread_mutex_lock(&table_lock);
    MonitorEntry* entry = monitor_table[idx];
    while (entry) {
        if (entry->obj == obj) {
            pthread_mutex_unlock(&table_lock);
            return &entry->mutex;
        }
        entry = entry->next;
    }
    MonitorEntry* new_entry = malloc(sizeof(MonitorEntry));
    if (new_entry == NULL) {
        pthread_mutex_unlock(&table_lock);
        return NULL;
    }
    new_entry->obj = obj;
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&new_entry->mutex, &attr);
    pthread_mutexattr_destroy(&attr);
    new_entry->next = monitor_table[idx];
    monitor_table[idx] = new_entry;
    pthread_mutex_unlock(&table_lock);
    return &new_entry->mutex;
}

void __jnative_monitor_destroy(void* obj) {
    if (obj == NULL) return;
    uint32_t idx = hash_ptr(obj);
    pthread_mutex_lock(&table_lock);
    MonitorEntry** pp = &monitor_table[idx];
    while (*pp) {
        MonitorEntry* entry = *pp;
        if (entry->obj == obj) {
            *pp = entry->next;
            pthread_mutex_destroy(&entry->mutex);
            free(entry);
            break;
        }
        pp = &entry->next;
    }
    pthread_mutex_unlock(&table_lock);
}

void __jnative_monitor_enter(void* obj) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    pthread_mutex_t* mtx = get_or_create_mutex(obj);
    if (mtx) pthread_mutex_lock(mtx);
}

void __jnative_monitor_exit(void* obj) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    pthread_mutex_lock(&table_lock);
    pthread_mutex_t* mtx = find_mutex(obj);
    pthread_mutex_unlock(&table_lock);
    if (mtx) pthread_mutex_unlock(mtx);
}

/* ============================================================================
 * Type identity
 * ========================================================================== */

static char* build_type_info_name(const char* class_name) {
    static char buf[256];
    snprintf(buf, sizeof(buf), "__type_info_%s", class_name);
    for (char* p = buf; *p; p++) {
        if (*p == '/' || *p == '.') *p = '_';
    }
    return buf;
}

static void* get_class_vtable(struct ReflectionClass* cls) {
    if (!cls || !cls->name) return NULL;
    const char* name = (const char*)cls->name;
    char* info_name = build_type_info_name(name);
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (!handle) return NULL;
    void** type_info = (void**)dlsym(handle, info_name);
    dlclose(handle);
    if (!type_info) return NULL;
    return type_info[0];
}

static struct ReflectionClass* find_class_by_vtable(void* vtable) {
    if (!vtable) return NULL;
    if (reflect_all_classes == NULL) return NULL;
    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp) {
        struct ReflectionClass* cls = *pp;
        void* cls_vtable = get_class_vtable(cls);
        if (cls_vtable == vtable) return cls;
        pp++;
    }
    return NULL;
}

/* ============================================================================
 * Symbol demangling
 * ========================================================================== */

static const char* dotted_class_name(const char* internal, char* buf, size_t buf_size) {
    if (!internal) return NULL;
    strncpy(buf, internal, buf_size - 1);
    buf[buf_size - 1] = '\0';
    for (char* p = buf; *p; p++) {
        if (*p == '/') *p = '.';
    }
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
    for (size_t i = 0; i < cm_len; i++) {
        if (sym[i] == '_') last_us = &sym[i];
    }
    size_t class_len  = last_us ? (size_t)(last_us - sym) : cm_len;
    size_t method_len = last_us ? (cm_len - class_len - 1) : 0;
    size_t w = 0;
#define PUT(c) do { if (w < out_size - 1) out[w++] = (c); } while (0)
    for (size_t i = 0; i < class_len; i++)  PUT(sym[i] == '_' ? '.' : sym[i]);
    if (method_len > 0) {
        PUT('.');
        for (size_t i = 0; i < method_len; i++) PUT(last_us[1 + i]);
    }
    if (desc_start) {
        PUT('(');
        for (const char* d = desc_start; *d; d++) {
            if (*d == '_' && (d[1] == 'L' || d[1] == 'I' || d[1] == 'J' ||
                              d[1] == 'B' || d[1] == 'S' || d[1] == 'C' ||
                              d[1] == 'F' || d[1] == 'D' || d[1] == 'Z' ||
                              d[1] == 'V' || d[1] == '_')) {
                continue;
            }
            PUT(*d);
        }
        PUT(')');
    }
    out[w] = '\0';
#undef PUT
}

/* ============================================================================
 * Catch context stack
 * ========================================================================== */

typedef struct CatchContext {
    struct CatchContext* next;
    void* type_info;
    jmp_buf buf;
} CatchContext;

static _Thread_local CatchContext* current_context = NULL;
static _Thread_local void* current_exception = NULL;

void __jnative_push_catch(void* jmp_buf_ptr, void* type_info) {
    CatchContext* ctx = (CatchContext*)malloc(sizeof(CatchContext));
    if (ctx == NULL) {
        fprintf(stderr, "Exception in thread \"main\": out of memory while installing handler\n");
        abort();
    }
    ctx->type_info = type_info;
    memcpy(ctx->buf, jmp_buf_ptr, sizeof(jmp_buf));
    ctx->next = current_context;
    current_context = ctx;
}

void __jnative_pop_catch(void) {
    if (current_context) {
        CatchContext* old = current_context;
        current_context = old->next;
        free(old);
    }
}

/* ============================================================================
 * Frame unwinding
 * ========================================================================== */

static void __jnative_print_frame(uint64_t rip, int index) {
    Dl_info dli;
    memset(&dli, 0, sizeof(dli));
    char line[4096];
    int n;
    if (dladdr((void*)(uintptr_t)rip, &dli) && dli.dli_sname) {
        char demangled[1024];
        __jnative_demangle(dli.dli_sname, demangled, sizeof(demangled));
        ptrdiff_t off = dli.dli_saddr
            ? (ptrdiff_t)(rip - (uint64_t)(uintptr_t)dli.dli_saddr) : 0;
        const char* mod = dli.dli_fname ? dli.dli_fname : "?";
        const char* slash = strrchr(mod, '/');
        mod = slash ? slash + 1 : mod;
        n = snprintf(line, sizeof(line), "\t#%-2d  %s (%s+0x%lx)  [0x%lx]\n",
            index, demangled, mod, (unsigned long)off, (unsigned long)rip);
    } else {
        n = snprintf(line, sizeof(line), "\t#%-2d  <unknown>  [0x%lx]\n",
            index, (unsigned long)rip);
    }
    if (n > 0) (void)!write(2, line, (size_t)n);
}

static void __jnative_unwind_from_ucontext(ucontext_t* uc) {
#if defined(__x86_64__)
    uint64_t rip = (uint64_t)uc->uc_mcontext.gregs[REG_RIP];
    uint64_t rbp = (uint64_t)uc->uc_mcontext.gregs[REG_RBP];
    uint64_t rsp = (uint64_t)uc->uc_mcontext.gregs[REG_RSP];
    __jnative_print_frame(rip, 0);
    uint64_t fp = rbp;
    for (int depth = 1; depth < 128 && fp != 0; depth++) {
        if (fp < rsp) break;
        if ((fp & 0x7) != 0) break;
        if (fp > rsp + (8ull << 20)) break;
        if (fp < 0x1000) break;
        uint64_t* frame = (uint64_t*)(uintptr_t)fp;
        uint64_t next_fp  = frame[0];
        uint64_t ret_addr = frame[1];
        if (ret_addr == 0) break;
        __jnative_print_frame(ret_addr, depth);
        if (next_fp <= fp) break;
        fp = next_fp;
    }
#else
    (void)uc;
    const char* msg = "\t(frame-pointer unwinding not supported on this arch)\n";
    (void)!write(2, msg, strlen(msg));
#endif
}

static void __jnative_unwind_with_backtrace(int skip) {
    void* frames[128];
    int n = backtrace(frames, 128);
    if (n <= skip) return;
    for (int i = skip; i < n; i++) {
        __jnative_print_frame((uint64_t)(uintptr_t)frames[i], i - skip);
    }
}

/* ============================================================================
 * Fatal signal handler
 * ========================================================================== */

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
        n = snprintf(line, sizeof(line), "Thrown  : exception %p was being propagated\n", current_exception);
        if (n > 0) (void)!write(2, line, (size_t)n);
    }
    n = snprintf(line, sizeof(line), "\nCall tree (from crash point, innermost first):\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
#if defined(__x86_64__)
    if (ucontext) __jnative_unwind_from_ucontext((ucontext_t*)ucontext);
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

__attribute__((constructor))
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

/* ============================================================================
 * Exception message extraction
 * ========================================================================== */

#define JNATIVE_THROWABLE_MESSAGE_OFFSET 16

static const char* __jnative_read_exception_message(void* exc, const char* clsName) {
    if (exc == NULL) return NULL;
    (void)clsName;
    void* msg = *(void**)((char*)exc + JNATIVE_THROWABLE_MESSAGE_OFFSET);
    if (msg == NULL) return NULL;
    return __jnative_read_string_bytes(msg, NULL);
}

/* ============================================================================
 * Unhandled-exception reporting
 *
 * `extra` carries an optional register/value description that the LLVM
 * emitter attaches to NullPointerException throws — e.g. "%tmp_20739" or
 * "%param_1". It is printed as a supplementary line right below the
 * function in which the throw happened.
 * ========================================================================== */

__attribute__((noreturn))
static void __jnative_log_unhandled_exception(void* exc, const char* className,
                                              const char* caller, const char* extra) {
    const char* clsName = className;
    char dottedBuf[256];
    const char* dotted = NULL;

    if (!clsName && exc) {
        void* vtable = *(void**)exc;
        const char* internal = __jnative_vtable_class_name(vtable);
        if (internal) {
            dotted = dotted_class_name(internal, dottedBuf, sizeof(dottedBuf));
            clsName = dotted;
        }
    }
    if (!clsName) clsName = "java.lang.Throwable";

    const char* message = __jnative_read_exception_message(exc, clsName);
    if (message != NULL) {
        fprintf(stderr, "Exception in thread \"main\" %s: %s\n", clsName, message);
    } else {
        fprintf(stderr, "Exception in thread \"main\" %s\n", clsName);
    }

    if (caller != NULL) {
        char demangled[1024];
        __jnative_demangle(caller, demangled, sizeof(demangled));
        fprintf(stderr, "\tat %s\n", demangled);
    }

    if (extra != NULL) {
        fprintf(stderr, "\t(null value: %s)\n", extra);
    }

    void* buffer[64];
    int n = backtrace(buffer, 64);
    char** symbols = backtrace_symbols(buffer, n);
    if (symbols) {
        for (int i = 0; i < n; i++) {
            Dl_info dli;
            memset(&dli, 0, sizeof(dli));
            if (dladdr(buffer[i], &dli) && dli.dli_sname) {
                char demangled[1024];
                __jnative_demangle(dli.dli_sname, demangled, sizeof(demangled));
                fprintf(stderr, "\tat %s\n", demangled);
            } else {
                fprintf(stderr, "\tat %s\n", symbols[i]);
            }
        }
        free(symbols);
    } else {
        for (int i = 0; i < n; i++) fprintf(stderr, "\tat %p\n", buffer[i]);
    }

    fflush(stderr);
    _exit(1);
}

/* ============================================================================
 * Exception object accessors
 * ========================================================================== */

void* __jnative_get_exception_object(void) { return current_exception; }

int __jnative_catch_matches(void* exc, void* type_info) {
    if (exc == NULL || type_info == NULL) return 0;
    void* vtable = *(void**)exc;
    void** ti = (void**)type_info;
    while (*ti) {
        if (*ti == vtable) return 1;
        ti++;
    }
    return 0;
}

int __jnative_instanceof(void* obj, void** type_info) {
    if (obj == NULL) return 0;
    if (type_info == NULL) return 0;
    void* vtable = *(void**)obj;
    void** ti = type_info;
    while (*ti) {
        if (*ti == vtable) return 1;
        ti++;
    }
    return 0;
}

/* ============================================================================
 * Throw helpers
 * ========================================================================== */

__attribute__((noreturn))
void __jnative_throw_exception_ctx(void* exc, const char* caller) {
    current_exception = exc;
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(exc, NULL, caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_null_pointer_exception_ctx(const char* caller, const char* var_desc) {
    current_exception = NULL;
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(NULL, "java.lang.NullPointerException",
                                      caller, var_desc);
}

__attribute__((noreturn))
void __jnative_throw_array_index_out_of_bounds_ctx(const char* caller) {
    current_exception = NULL;
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(NULL, "java.lang.ArrayIndexOutOfBoundsException",
                                      caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_class_cast_exception_ctx(const char* caller) {
    current_exception = NULL;
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(NULL, "java.lang.ClassCastException", caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_arithmetic_exception_ctx(const char* caller) {
    current_exception = NULL;
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(NULL, "java.lang.ArithmeticException", caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_exception(void* exc) {
    __jnative_throw_exception_ctx(exc, NULL);
}

__attribute__((noreturn))
void __jnative_throw_null_pointer_exception(void) {
    __jnative_throw_null_pointer_exception_ctx(NULL, NULL);
}

__attribute__((noreturn))
void __jnative_throw_array_index_out_of_bounds(void) {
    __jnative_throw_array_index_out_of_bounds_ctx(NULL);
}

__attribute__((noreturn))
void __jnative_throw_class_cast_exception(void) {
    __jnative_throw_class_cast_exception_ctx(NULL);
}

__attribute__((noreturn))
void __jnative_throw_arithmetic_exception(void) {
    __jnative_throw_arithmetic_exception_ctx(NULL);
}

/* ============================================================================
 * Bad-vtable diagnostic
 * ========================================================================== */

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
        n = snprintf(line, sizeof(line), "First word (vtable): %p\n", first_word);
        if (n > 0) (void)!write(2, line, (size_t)n);
    }
    n = snprintf(line, sizeof(line),
        "Cause   : virtual dispatch on an object whose vtable slot is NULL.\n"
        "          The object was created without a proper class vtable,\n"
        "          typically by a native stub using calloc()/malloc() instead\n"
        "          of going through the generated @__jnative_new_*() helper.\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    n = snprintf(line, sizeof(line), "\nCall tree (from throw point):\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    __jnative_unwind_with_backtrace(0);
    n = snprintf(line, sizeof(line), "\n=== end of JNative bad-vtable trace ===\n\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    fflush(stderr);
    signal(SIGABRT, SIG_DFL);
    abort();
}

/* ============================================================================
 * Unresolved vtable/itable slot
 *
 * A null function pointer in a vtable or itable slot means a code-generation
 * pass left a slot unpopulated: the corresponding method's mangled symbol was
 * not present in the module when LlvmGlobalEmitter.generateVtables() built the
 * table. Executing that slot would jump to address 0 and crash with an
 * uninformative "SIGSEGV at 0x0" — no frame, no method name, no class.
 *
 * Every such slot is filled with the address of a small trap stub whose name
 * is derived from the (class, interface, signature) triple. Each stub calls
 * this runtime helper, passing the pre-formatted "class '...', slot '...'"
 * message that identifies the incomplete table entry. We print the diagnostic
 * together with a call tree and abort, turning a silent, unrecoverable
 * jump-to-null into an actionable report.
 *
 * The signature matches the LLVM declaration emitted by
 * LlvmRuntime.getDeclarations():
 *
 *     declare void @__jnative_unresolved_slot(i8*) noreturn
 *
 * i.e. a single pointer to a NUL-terminated string. The stub passes the
 * string as an i8*, which is bit-compatible with const char* on this ABI.
 * ========================================================================== */
__attribute__((noreturn))
void __jnative_unresolved_slot(const char* what) {
    char line[1024];
    int n = snprintf(line, sizeof(line),
        "\n=== JNative unresolved vtable/itable slot ===\n"
        "%s\n"
        "Cause : codegen emitted a null function pointer for a slot that\n"
        "        was never populated by LlvmGlobalEmitter.resolveVtableEntry.\n"
        "        The mangled method symbol was absent from the Module at\n"
        "        generateVtables() time.\n",
        what != NULL ? what : "<unknown slot>");
    if (n > 0) (void)!write(2, line, (size_t)n);

    n = snprintf(line, sizeof(line), "\nCall tree (from throw point):\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    __jnative_unwind_with_backtrace(0);

    n = snprintf(line, sizeof(line),
        "\n=== end of JNative unresolved-slot trace ===\n\n");
    if (n > 0) (void)!write(2, line, (size_t)n);

    fflush(stderr);
    signal(SIGABRT, SIG_DFL);
    abort();
}

/* ============================================================================
 * Argument array construction
 * ========================================================================== */

void* __jnative_create_string_array(int argc, char** argv) {
    int total_size = 8 + argc * 8;
    void* array = calloc(1, (size_t)total_size);
    if (!array) return NULL;
    *(int*)array = argc;
    *(int*)((char*)array + 4) = 8;
    char** slots = (char**)((char*)array + 8);
    for (int i = 0; i < argc; i++) {
        int len = strlen(argv[i]);
        char* str = malloc((size_t)len + 1);
        if (str) {
            memcpy(str, argv[i], (size_t)len + 1);
            slots[i] = str;
        } else {
            slots[i] = NULL;
        }
    }
    return array;
}

/* ============================================================================
 * Multi-dimensional array construction
 * ========================================================================== */

static void* create_multi_array_rec(const char* desc, int last_dim, int* sizes,
                                    int current_dim, int elem_size) {
    int is_last = (current_dim == last_dim);
    int length = sizes[current_dim];
    int slot_size = is_last ? elem_size : (int)sizeof(void*);
    int64_t total_size = 8 + (int64_t)length * (int64_t)slot_size;
    void* array = calloc(1, (size_t)total_size);
    if (!array) return NULL;
    *(int32_t*)array = length;
    *(int32_t*)((char*)array + 4) = slot_size;
    if (!is_last) {
        void** slots = (void**)((char*)array + 8);
        for (int i = 0; i < length; i++) {
            slots[i] = create_multi_array_rec(desc, last_dim, sizes, current_dim + 1, elem_size);
        }
    }
    return array;
}

void* __jnative_new_multi_array(const char* desc, int dims, int* sizes, int elem_size) {
    if (dims <= 0 || sizes == NULL) return NULL;
    int last_dim = dims - 1;
    return create_multi_array_rec(desc, last_dim, sizes, 0, elem_size);
}

/* ============================================================================
 * Reflection invoke helpers
 * ========================================================================== */

void* __jnative_invoke_method(struct ReflectionMethod* method, void* obj, void** args) {
    if (method == NULL || method->adaptor == NULL) return NULL;
    typedef void* (*adaptor_t)(void*, void**);
    adaptor_t adaptor = (adaptor_t)method->adaptor;
    return adaptor(obj, args);
}

void* __jnative_new_instance(struct ReflectionConstructor* ctor, void** args) {
    if (ctor == NULL || ctor->adaptor == NULL) return NULL;
    typedef void* (*adaptor_t)(void**);
    adaptor_t adaptor = (adaptor_t)ctor->adaptor;
    return adaptor(args);
}

/* ============================================================================
 * String object helpers.
 *
 * A Java String is a %struct.java_lang_String laid out as
 *   [ i8* vtable ][ i8* value ][ i8 coder ][ i32 hash ][ i1 hashIsZero ]
 * where `value` points at a byte[] of shape [i32 length][bytes][NUL].
 * ========================================================================== */

extern struct JNativeVTable vtable_java_lang_String;

void* __jnative_make_string_obj(const char* bytes, int32_t len) {
    void* value = calloc(1, 8 + (size_t)len + 1);
    if (value == NULL) {
        __jnative_throw_null_pointer_exception_ctx(NULL, NULL);
    }
    *(int32_t*)value = len;
    *(int32_t*)((char*)value + 4) = 1;
    if (len > 0) memcpy((char*)value + 8, bytes, (size_t)len);
    ((char*)value)[8 + len] = '\0';

    void* s = calloc(1, 32);
    if (s == NULL) {
        __jnative_throw_null_pointer_exception_ctx(NULL, NULL);
    }
    *(void**)((char*)s + 0)  = (void*)&vtable_java_lang_String;
    *(void**)((char*)s + 8)  = value;
    *(uint8_t*)((char*)s + 16) = 0;
    *(int32_t*)((char*)s + 20) = 0;
    *(uint8_t*)((char*)s + 24) = 0;
    return s;
}

const char* __jnative_read_string_bytes(void* s, int32_t* out_len) {
    if (s == NULL) {
        if (out_len) *out_len = 0;
        return "";
    }
    void* value = *(void**)((char*)s + 8);
    if (value == NULL) {
        if (out_len) *out_len = 0;
        return "";
    }
    if (out_len) *out_len = *(int32_t*)value;
    return (const char*)value + 8;
}

static int32_t __jnative_string_eq(void* a, void* b) {
    int32_t la = 0, lb = 0;
    const char* ba = __jnative_read_string_bytes(a, &la);
    const char* bb = __jnative_read_string_bytes(b, &lb);
    return la == lb && memcmp(ba, bb, (size_t)la) == 0;
}

/* ---- literal pool ---- */
static void**  __jnative_pool = NULL;
static int32_t __jnative_pool_size = 0;

void __jnative_init_string_pool(void** pool, int32_t size) {
    __jnative_pool = pool;
    __jnative_pool_size = size;
}

/* ---- runtime intern table ---- */
typedef struct JNativeInternEntry {
    void* str;
    struct JNativeInternEntry* next;
} JNativeInternEntry;

#define JNATIVE_INTERN_BUCKETS 4096
static JNativeInternEntry* __jnative_intern_table[JNATIVE_INTERN_BUCKETS];
static pthread_mutex_t __jnative_intern_lock = PTHREAD_MUTEX_INITIALIZER;

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

/* ============================================================================
 * String concatenation (accepts String objects)
 * ========================================================================== */

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
    if (buf == NULL) {
        __jnative_throw_null_pointer_exception_ctx(NULL, NULL);
    }
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

/* ============================================================================
 * Value-to-String helpers (return String objects)
 * ========================================================================== */

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

void* __jnative_value_to_string_byte(int32_t v)  { return __jnative_value_to_string_int((int8_t)v); }
void* __jnative_value_to_string_short(int32_t v) { return __jnative_value_to_string_int((int16_t)v); }

void* __jnative_value_to_string_object(void* obj) {
    if (obj == NULL) return __jnative_make_string_obj("null", 4);
    struct JNativeVTable* vt = *(struct JNativeVTable**)obj;
    if (vt == NULL) return __jnative_make_string_obj("null", 4);
    int32_t slot = __jnative_tostring_slot;
    if (slot < 0) return __jnative_make_string_obj("null", 4);
    void* entry = vt->methods[slot];
    if (entry == NULL) return __jnative_make_string_obj("null", 4);
    void* (*toString)(void*) = (void* (*)(void*))entry;
    return toString(obj);
}

/* ============================================================================
 * Runtime-side intern used by jnative/lang/String.c
 * ========================================================================== */

void* __jnative_string_intern(void* this_str) {
    if (this_str == NULL) return NULL;

    for (int32_t i = 0; i < __jnative_pool_size; i++) {
        if (__jnative_pool[i] != NULL && __jnative_string_eq(this_str, __jnative_pool[i])) {
            return __jnative_pool[i];
        }
    }

    uint32_t h = __jnative_string_hash(this_str);
    uint32_t bucket = h % JNATIVE_INTERN_BUCKETS;

    pthread_mutex_lock(&__jnative_intern_lock);
    for (JNativeInternEntry* e = __jnative_intern_table[bucket]; e; e = e->next) {
        if (__jnative_string_eq(e->str, this_str)) {
            void* found = e->str;
            pthread_mutex_unlock(&__jnative_intern_lock);
            return found;
        }
    }
    JNativeInternEntry* ne = malloc(sizeof(JNativeInternEntry));
    if (ne != NULL) {
        ne->str = this_str;
        ne->next = __jnative_intern_table[bucket];
        __jnative_intern_table[bucket] = ne;
    }
    pthread_mutex_unlock(&__jnative_intern_lock);
    return this_str;
}

/* ============================================================================
 * Runnable dispatch
 * ========================================================================== */

extern const int32_t __jnative_runnable_iface_id;
extern const int32_t __jnative_run_method_slot;

void __jnative_invoke_runnable(void* runnable) {
    if (runnable == NULL) return;
    const int32_t iface_id = __jnative_runnable_iface_id;
    const int32_t slot     = __jnative_run_method_slot;
    if (iface_id < 0 || slot < 0) return;
    struct JNativeVTable* vt = *(struct JNativeVTable**)runnable;
    if (vt == NULL) return;
    void** itable = __jnative_lookup_itable(vt->ifacemap, iface_id);
    if (itable == NULL) return;
    void* entry = itable[slot];
    if (entry == NULL) return;
    void (*run)(void*) = (void (*)(void*))entry;
    run(runnable);
}

void __jnative_debug_clinit(const char* name) {
    fprintf(stderr, "[clinit] %s\n", name);
    fflush(stderr);
}