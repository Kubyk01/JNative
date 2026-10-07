#define _GNU_SOURCE
#include <pthread.h>
#include <setjmp.h>
#include <stdarg.h>
#include <signal.h>
#include <unistd.h>
#include <ucontext.h>
#include <sys/ucontext.h>
#include <execinfo.h>

#include "jnative_runtime.h"

/* ============================================================================
 * Virtual dispatch
 *
 * The only function of the vtable ABI that is not inlined by the header:
 * looking an interface's itable up in a class's interface map. Every
 * generated INTERFACE_CALL site funnels through here, and every native
 * that performs a Runnable / PrivilegedAction dispatch (see
 * AccessController.c and the __jnative_invoke_runnable helper below)
 * calls it too.
 * ========================================================================== */

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

/* ============================================================================
 * Reflect-mirror field offsets
 *
 * These globals are declared extern in jnative_runtime.h and referenced by
 * jnative/lang/Class.c (mirror construction) and jnative/jdk/internal/misc/
 * Unsafe.c (field-offset queries). Until @main publishes the real values,
 * every offset is -1, which means "not yet set"; any consumer that reads a
 * -1 offset is making a genuine programming error and the runtime refuses
 * to write into a reflect mirror. See __jnative_reflect_set_layout below.
 *
 * NOTE: the analogous __jnative_thread_set_layout function and its 13
 * static variables live in jnative/lang/Thread.c, not here. They are
 * owned by the Thread class because every consumer of those offsets is
 * in Thread.c and its sibling files.
 * ========================================================================== */

int32_t JNATIVE_FIELD_CLAZZ_OFFSET     = -1;
int32_t JNATIVE_FIELD_SLOT_OFFSET      = -1;
int32_t JNATIVE_FIELD_NAME_OFFSET      = -1;
int32_t JNATIVE_FIELD_TYPE_OFFSET      = -1;
int32_t JNATIVE_FIELD_MODIFIERS_OFFSET = -1;

int32_t JNATIVE_METHOD_CLAZZ_OFFSET        = -1;
int32_t JNATIVE_METHOD_SLOT_OFFSET         = -1;
int32_t JNATIVE_METHOD_NAME_OFFSET         = -1;
int32_t JNATIVE_METHOD_RETURN_TYPE_OFFSET  = -1;
int32_t JNATIVE_METHOD_PARAM_TYPES_OFFSET  = -1;
int32_t JNATIVE_METHOD_EXC_TYPES_OFFSET    = -1;
int32_t JNATIVE_METHOD_MODIFIERS_OFFSET    = -1;
int32_t JNATIVE_METHOD_ROOT_OFFSET         = -1;

int32_t JNATIVE_CTOR_CLAZZ_OFFSET       = -1;
int32_t JNATIVE_CTOR_SLOT_OFFSET        = -1;
int32_t JNATIVE_CTOR_PARAM_TYPES_OFFSET = -1;
int32_t JNATIVE_CTOR_EXC_TYPES_OFFSET   = -1;
int32_t JNATIVE_CTOR_MODIFIERS_OFFSET   = -1;
int32_t JNATIVE_CTOR_ROOT_OFFSET        = -1;

/* ============================================================================
 * Reflect-mirror layout hand-off
 *
 * Idempotent. A second call with the same values is a no-op; a second call
 * with different values is a programming error and is reported to stderr
 * without corrupting the previously published layout.
 * ========================================================================== */

void __jnative_reflect_set_layout(
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
    int32_t ctor_modifiers_offset,
    int32_t ctor_root_offset,
    int32_t method_root_offset)
{
    /* First call wins. Everything after is either a no-op (identical
     * values, the common case in a well-formed image where @main runs
     * exactly once) or a warning that the caller is trying to change
     * the layout under our feet. */
    if (JNATIVE_FIELD_CLAZZ_OFFSET >= 0) {
        if (JNATIVE_FIELD_CLAZZ_OFFSET     != field_clazz_offset
         || JNATIVE_FIELD_SLOT_OFFSET      != field_slot_offset
         || JNATIVE_FIELD_NAME_OFFSET      != field_name_offset
         || JNATIVE_FIELD_TYPE_OFFSET      != field_type_offset
         || JNATIVE_FIELD_MODIFIERS_OFFSET != field_modifiers_offset
         || JNATIVE_METHOD_CLAZZ_OFFSET        != method_clazz_offset
         || JNATIVE_METHOD_SLOT_OFFSET         != method_slot_offset
         || JNATIVE_METHOD_NAME_OFFSET         != method_name_offset
         || JNATIVE_METHOD_RETURN_TYPE_OFFSET  != method_return_type_offset
         || JNATIVE_METHOD_PARAM_TYPES_OFFSET  != method_param_types_offset
         || JNATIVE_METHOD_EXC_TYPES_OFFSET    != method_exc_types_offset
         || JNATIVE_METHOD_MODIFIERS_OFFSET    != method_modifiers_offset
         || JNATIVE_METHOD_ROOT_OFFSET         != method_root_offset
         || JNATIVE_CTOR_CLAZZ_OFFSET       != ctor_clazz_offset
         || JNATIVE_CTOR_SLOT_OFFSET        != ctor_slot_offset
         || JNATIVE_CTOR_PARAM_TYPES_OFFSET != ctor_param_types_offset
         || JNATIVE_CTOR_EXC_TYPES_OFFSET   != ctor_exc_types_offset
         || JNATIVE_CTOR_MODIFIERS_OFFSET   != ctor_modifiers_offset
         || JNATIVE_CTOR_ROOT_OFFSET        != ctor_root_offset) {
            fprintf(stderr,
                "jnative: warning: __jnative_reflect_set_layout called "
                "twice with different values; ignoring the second call\n");
        }
        return;
    }

    JNATIVE_FIELD_CLAZZ_OFFSET     = field_clazz_offset;
    JNATIVE_FIELD_SLOT_OFFSET      = field_slot_offset;
    JNATIVE_FIELD_NAME_OFFSET      = field_name_offset;
    JNATIVE_FIELD_TYPE_OFFSET      = field_type_offset;
    JNATIVE_FIELD_MODIFIERS_OFFSET = field_modifiers_offset;

    JNATIVE_METHOD_CLAZZ_OFFSET        = method_clazz_offset;
    JNATIVE_METHOD_SLOT_OFFSET         = method_slot_offset;
    JNATIVE_METHOD_NAME_OFFSET         = method_name_offset;
    JNATIVE_METHOD_RETURN_TYPE_OFFSET  = method_return_type_offset;
    JNATIVE_METHOD_PARAM_TYPES_OFFSET  = method_param_types_offset;
    JNATIVE_METHOD_EXC_TYPES_OFFSET    = method_exc_types_offset;
    JNATIVE_METHOD_MODIFIERS_OFFSET    = method_modifiers_offset;
    JNATIVE_METHOD_ROOT_OFFSET         = method_root_offset;

    JNATIVE_CTOR_CLAZZ_OFFSET       = ctor_clazz_offset;
    JNATIVE_CTOR_SLOT_OFFSET        = ctor_slot_offset;
    JNATIVE_CTOR_PARAM_TYPES_OFFSET = ctor_param_types_offset;
    JNATIVE_CTOR_EXC_TYPES_OFFSET   = ctor_exc_types_offset;
    JNATIVE_CTOR_MODIFIERS_OFFSET   = ctor_modifiers_offset;
    JNATIVE_CTOR_ROOT_OFFSET        = ctor_root_offset;
}

/* ============================================================================
 * Monitor table
 *
 * Every object that is synchronized on gets a lazily-created recursive
 * pthread mutex. Entries live for the whole process lifetime; the
 * runtime has no way to know when an object is no longer reachable.
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
 * Lazy <clinit> state machine
 *
 * The two entry points below are deliberately marked __attribute__((noinline)):
 *
 *   - They are called from every instrumented active-use site of every
 *     class whose <clinit> participates in a cyclic initialization
 *     group. That is tens of thousands of call sites on a large image.
 *
 *   - Their bodies contain a pthread_mutex_lock and a linear strcmp walk
 *     over clinit_table. Inlining them into every call site duplicates
 *     the lock and the strcmp chain tens of thousands of times, bloating
 *     the binary and (more importantly) obscuring the CFG of the
 *     surrounding function under a heavyweight instruction sequence —
 *     a control-flow defect in the surrounding function then shows up in
 *     a profiler as a hot spot inside the state machine instead of in
 *     the function that actually has the defect.
 *
 * Marking the functions noinline keeps a single copy of the state-machine
 * body in the module and turns each call site into a compact CALL.
 *
 * ---------------------------------------------------------------------------
 * Thread-local <clinit> identity stack
 * ---------------------------------------------------------------------------
 *
 * The LLVM backend is free to tail-call the last instruction of a
 * <clinit> body. When it does — as it does for
 * java.security.SecureClassLoader.<clinit>, whose body is a single
 * `ClassLoader.registerAsParallelCapable()`, and for
 * jdk.internal.loader.BuiltinClassLoader.<clinit> and
 * jdk.internal.loader.ClassLoaders$PlatformClassLoader.<clinit> for the
 * same reason — the body's frame disappears from the native stack. By
 * the time a @CallerSensitive method invoked from inside such a body
 * calls Reflection.getCallerClass(), the walk in
 * jnative/jdk/internal/reflect/Reflection.c sees only the wrapper
 * (fn___lazy_clinit_run_<class>) and then the outer frame, which is
 * typically another <clinit> that is *not* the one the caller wants.
 *
 * Symptom: ClassLoader.registerAsParallelCapable checks that its caller
 * is a subclass of ClassLoader, and the misidentified outer frame
 * (jdk.internal.loader.ClassLoaders, a package-private helper class) is
 * not. The result is
 *
 *     java.lang.IllegalCallerException:
 *         class jdk.internal.loader.ClassLoaders not a subclass of
 *         ClassLoader
 *
 * The fix is to give the reflection walk a second source of truth that
 * does not depend on how the compiler shaped the frames. Between the
 * moment __jnative_clinit_enter() grants this thread the right to run a
 * <clinit> body and the moment __jnative_clinit_exit() publishes its
 * completion, the body is on the current dynamic extent of exactly one
 * thread. Recording the class's internal name in a thread-local stack
 * for that interval is sufficient to reconstruct the answer, and the
 * stack is popped the moment the body returns — with or without an
 * exception in flight.
 * ========================================================================== */

#define CLINIT_STATE_NOT_STARTED 0
#define CLINIT_STATE_IN_PROGRESS 1
#define CLINIT_STATE_DONE        2

typedef struct ClinitEntry {
    char* name;
    int   state;
    struct ClinitEntry* next;
} ClinitEntry;

static ClinitEntry*    clinit_table = NULL;
static pthread_mutex_t clinit_lock = PTHREAD_MUTEX_INITIALIZER;

typedef struct ClinitFrame {
    char*               name;
    struct ClinitFrame* prev;
} ClinitFrame;

static _Thread_local ClinitFrame* tls_clinit_stack = NULL;

static void clinit_stack_push(const char* name) {
    if (name == NULL) return;
    ClinitFrame* f = (ClinitFrame*)malloc(sizeof(ClinitFrame));
    if (f == NULL) return;
    f->name = strdup(name);
    if (f->name == NULL) {
        free(f);
        return;
    }
    f->prev = tls_clinit_stack;
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
    for (ClinitEntry* e = clinit_table; e; e = e->next) {
        if (strcmp(e->name, name) == 0) return e;
    }
    return NULL;
}

static ClinitEntry* clinit_lookup_or_create(const char* name) {
    ClinitEntry* e = clinit_lookup(name);
    if (e != NULL) return e;
    e = (ClinitEntry*)malloc(sizeof(ClinitEntry));
    if (e == NULL) return NULL;
    e->name  = strdup(name);
    if (e->name == NULL) {
        free(e);
        return NULL;
    }
    e->state = CLINIT_STATE_NOT_STARTED;
    e->next  = clinit_table;
    clinit_table = e;
    return e;
}

/* === noinline on the state-machine entry points ========================= */
__attribute__((noinline))
int32_t __jnative_clinit_enter(void* name_str) {
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    if (name == NULL || len <= 0) return 0;

    pthread_mutex_lock(&clinit_lock);
    ClinitEntry* e = clinit_lookup_or_create(name);
    if (e == NULL) {
        pthread_mutex_unlock(&clinit_lock);
        return 0;
    }

    int32_t result;
    if (e->state == CLINIT_STATE_DONE
        || e->state == CLINIT_STATE_IN_PROGRESS) {
        result = 1;
    } else {
        e->state = CLINIT_STATE_IN_PROGRESS;
        result = 0;
    }
    pthread_mutex_unlock(&clinit_lock);

    if (result == 0) {
        clinit_stack_push(name);
    }
    return result;
}

/* === noinline on the state-machine entry points ========================= */
__attribute__((noinline))
void __jnative_clinit_exit(void* name_str) {
    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    if (name == NULL || len <= 0) return;

    clinit_stack_pop();

    pthread_mutex_lock(&clinit_lock);
    ClinitEntry* e = clinit_lookup(name);
    if (e != NULL) e->state = CLINIT_STATE_DONE;
    pthread_mutex_unlock(&clinit_lock);
}

/* ============================================================================
 * Canonical own-class-vtable resolution
 * ========================================================================== */

void* __jnative_own_class_vtable(const char* class_name) {
    if (class_name == NULL) return NULL;

    char symbol[512];
    jnative_type_info_name(class_name, symbol, sizeof(symbol));

    void** type_info = (void**)dlsym(RTLD_DEFAULT, symbol);
    if (type_info == NULL) return NULL;
    return type_info[0];
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

static void __jnative_print_frame(uint64_t rip, int index);
static void __jnative_unwind_with_backtrace(int skip);

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
    int depth = 1;
    int fp_chain_ok = 1;
    for (; depth < 128 && fp != 0; depth++) {
        if (fp < rsp) { fp_chain_ok = 0; break; }
        if ((fp & 0x7) != 0) { fp_chain_ok = 0; break; }
        if (fp > rsp + (8ull << 20)) { fp_chain_ok = 0; break; }
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
 * Exception object construction
 * ========================================================================== */

#define JNATIVE_THROWABLE_MESSAGE_OFFSET 16
#define JNATIVE_EXCEPTION_OBJECT_SIZE    64

static JNativeVTable* __jnative_lookup_vtable_weak(const char* symbol) {
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (handle == NULL) return NULL;
    void* sym = dlsym(handle, symbol);
    dlclose(handle);
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
        void* msg = __jnative_make_string_obj(
            message, (int32_t)strlen(message));
        *(void**)((char*)exc + JNATIVE_THROWABLE_MESSAGE_OFFSET) = msg;
    }
    return exc;
}

/*
 * Public wrapper around __jnative_make_exception_object.
 *
 * Used by per-class native files (for example jnative/lang/Class.c's
 * forName0) that must construct a specific exception object whose type
 * is not one of the fixed set the runtime builds internally.
 *
 * Returns NULL when the requested vtable is not present in the image.
 * The caller is expected to either substitute a different exception
 * type or pass NULL into __jnative_throw_exception_ctx, in which case
 * the generic substitution path in that function fires and produces a
 * Throwable whose message names the missing vtable. Returning NULL is
 * therefore never a silent failure: it always produces a diagnostic
 * exception with a message that identifies the problem.
 */
void* __jnative_construct_exception(const char* vtable_symbol,
                                    const char* message) {
    if (vtable_symbol == NULL) return NULL;
    return __jnative_make_exception_object(vtable_symbol, message);
}

static void* __jnative_make_null_pointer_exception(const char* caller,
                                                   const char* var_desc) {
    char buf[512];
    char demangled[256];

    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
    } else {
        strncpy(demangled, "unknown method", sizeof(demangled) - 1);
        demangled[sizeof(demangled) - 1] = '\0';
    }

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
        snprintf(buf, sizeof(buf),
            "Array index out of bounds in %s", demangled);
    } else {
        snprintf(buf, sizeof(buf), "Array index out of bounds");
    }

    return __jnative_make_exception_object(
        "vtable_java_lang_ArrayIndexOutOfBoundsException", buf);
}

static void* __jnative_make_class_cast_exception(const char* caller) {
    char buf[512];
    char demangled[256];

    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
        snprintf(buf, sizeof(buf), "Class cast failed in %s", demangled);
    } else {
        snprintf(buf, sizeof(buf), "Class cast failed");
    }

    return __jnative_make_exception_object(
        "vtable_java_lang_ClassCastException", buf);
}

static void* __jnative_make_arithmetic_exception(const char* caller) {
    char buf[512];
    char demangled[256];

    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
        snprintf(buf, sizeof(buf), "Arithmetic error in %s", demangled);
    } else {
        snprintf(buf, sizeof(buf), "Arithmetic error");
    }

    return __jnative_make_exception_object(
        "vtable_java_lang_ArithmeticException", buf);
}

/* ============================================================================
 * Exception message extraction
 * ========================================================================== */

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

/* ============================================================================
 * Unhandled-exception reporting
 * ========================================================================== */

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
        const char* clsName = NULL;

        if (i == 0 && className != NULL) {
            clsName = className;
        } else {
            clsName = __jnative_class_name_of(cur, dottedBuf, sizeof(dottedBuf));
        }
        if (clsName == NULL) clsName = "java.lang.Throwable";

        const char* message = __jnative_message_of(cur);

        if (i == 0) {
            if (message != NULL) {
                fprintf(stderr, "Exception in thread \"main\" %s: %s\n",
                        clsName, message);
            } else {
                fprintf(stderr, "Exception in thread \"main\" %s\n", clsName);
            }
        } else {
            if (message != NULL) {
                fprintf(stderr, "Caused by: %s: %s\n", clsName, message);
            } else {
                fprintf(stderr, "Caused by: %s\n", clsName);
            }
        }
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

    void* first_word = *(void**)obj;

    void** ti = type_info;
    while (*ti) {
        if (*ti == first_word) return 1;
        ti++;
    }

    if (reflect_all_classes == NULL) return 0;
    ReflectionClass** cp = reflect_all_classes;
    while (*cp != NULL) {
        if ((void*)(*cp) == first_word) {
            static const char* universal_supers[] = {
                "java/lang/Object",
                "java/lang/Cloneable",
                "java/io/Serializable",
                NULL
            };
            for (int i = 0; universal_supers[i] != NULL; i++) {
                void* v = __jnative_own_class_vtable(universal_supers[i]);
                if (v == NULL) continue;
                ti = type_info;
                while (*ti) {
                    if (*ti == v) return 1;
                    ti++;
                }
            }
            return 0;
        }
        cp++;
    }
    return 0;
}

/* ============================================================================
 * Throw helpers
 * ========================================================================== */

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
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(exc, NULL, caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_null_pointer_exception_ctx(const char* caller, const char* var_desc) {
    void* npe = __jnative_make_null_pointer_exception(caller, var_desc);
    current_exception = npe;
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(npe, "java.lang.NullPointerException",
                                      caller, var_desc);
}

__attribute__((noreturn))
void __jnative_throw_array_index_out_of_bounds_ctx(const char* caller) {
    void* exc = __jnative_make_array_index_out_of_bounds_exception(caller);
    current_exception = exc;
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(exc,
        "java.lang.ArrayIndexOutOfBoundsException", caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_class_cast_exception_ctx(const char* caller) {
    void* exc = __jnative_make_class_cast_exception(caller);
    current_exception = exc;
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(exc,
        "java.lang.ClassCastException", caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_arithmetic_exception_ctx(const char* caller) {
    void* exc = __jnative_make_arithmetic_exception(caller);
    current_exception = exc;
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(exc,
        "java.lang.ArithmeticException", caller, NULL);
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
 * CloneNotSupportedException and OutOfMemoryError constructors
 * ========================================================================== */

static void* __jnative_make_clone_not_supported_exception(const char* caller) {
    char buf[512];
    char demangled[256];

    if (caller != NULL) {
        __jnative_demangle(caller, demangled, sizeof(demangled));
        snprintf(buf, sizeof(buf), "Object.clone() not supported in %s", demangled);
    } else {
        snprintf(buf, sizeof(buf), "Object.clone() not supported");
    }

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
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
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
    } else {
        snprintf(buf, sizeof(buf), "Out of memory");
    }

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
    CatchContext* ctx = current_context;
    if (ctx) longjmp(ctx->buf, 1);
    __jnative_log_unhandled_exception(exc, "java.lang.OutOfMemoryError",
                                      caller, NULL);
}

__attribute__((noreturn))
void __jnative_throw_out_of_memory_error(void) {
    __jnative_throw_out_of_memory_error_ctx(NULL);
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
 * Dispatch-receiver validation
 * ========================================================================== */

#define JNATIVE_MIRROR_SET_BUCKETS 8192u
#define JNATIVE_MIRROR_SET_MASK    (JNATIVE_MIRROR_SET_BUCKETS - 1u)

typedef struct JNativeMirrorSetEntry {
    const void* ptr;
    struct JNativeMirrorSetEntry* next;
} JNativeMirrorSetEntry;

static JNativeMirrorSetEntry* jnative_mirror_set[JNATIVE_MIRROR_SET_BUCKETS];
static pthread_once_t         jnative_mirror_set_once = PTHREAD_ONCE_INIT;

static uintptr_t jnative_hash_pointer(const void* p) {
    uintptr_t h = (uintptr_t)p;
    h ^= h >> 33;
    h *= 0xff51afd7ed558ccdULL;
    h ^= h >> 33;
    h *= 0xc4ceb9fe1a85ec53ULL;
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
    pthread_once(&jnative_mirror_set_once, jnative_build_mirror_set);
    size_t bucket =
        (size_t)(jnative_hash_pointer(ptr) & JNATIVE_MIRROR_SET_MASK);
    for (JNativeMirrorSetEntry* e = jnative_mirror_set[bucket];
         e != NULL; e = e->next) {
        if (e->ptr == ptr) return 1;
    }
    return 0;
}

JNativeVTable* __jnative_resolve_dispatch_vtable(void* obj) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    void* first = *(void**)obj;
    if (first == NULL) {
        __jnative_throw_bad_vtable("virtual dispatch", obj);
    }
    if (__jnative_is_class_mirror(first)) {
        void* obj_vtable = __jnative_own_class_vtable("java/lang/Object");
        if (obj_vtable == NULL) {
            __jnative_throw_bad_vtable(
                "virtual dispatch on array receiver "
                "(java/lang/Object has no registered vtable)", obj);
        }
        return (JNativeVTable*)obj_vtable;
    }
    return (JNativeVTable*)first;
}

__attribute__((noreturn))
void __jnative_throw_bad_dispatch(void* obj, int32_t slot, const char* caller) {
    char line[1024];
    int n = snprintf(line, sizeof(line),
        "\n=== JNative corrupted dispatch ===\n"
        "Caller : %s\nReceiver: %p\nSlot   : %d\n",
        caller != NULL ? caller : "<unknown>",
        obj, slot);
    if (n > 0) (void)!write(2, line, (size_t)n);

    if (obj != NULL) {
        void* first = *(void**)obj;
        const char* kind;
        if (first == NULL) {
            kind = "NULL (obj[0] was never written)";
        } else if (__jnative_is_class_mirror(first)) {
            kind = "registered class mirror (array header)";
        } else {
            kind = "not a registered class mirror (unknown kind)";
        }
        n = snprintf(line, sizeof(line),
            "obj[0] : %p  (%s)\n", first, kind);
        if (n > 0) (void)!write(2, line, (size_t)n);
    }

    n = snprintf(line, sizeof(line), "\nCall tree (from dispatch point):\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    __jnative_unwind_with_backtrace(0);

    n = snprintf(line, sizeof(line),
        "\n=== end of JNative corrupted-dispatch trace ===\n\n");
    if (n > 0) (void)!write(2, line, (size_t)n);
    fflush(stderr);
    signal(SIGABRT, SIG_DFL);
    abort();
}

/* ============================================================================
 * Unresolved vtable/itable slot
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

/* ============================================================================
 * Multi-dimensional array construction
 * ========================================================================== */

static void* create_multi_array_rec(const char* desc, int last_dim, int* sizes,
                                    int current_dim, int elem_size) {
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

void* __jnative_invoke_method(ReflectionMethod* method, void* obj, void** args) {
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

/* ============================================================================
 * String object helpers
 * ========================================================================== */

extern JNativeVTable vtable_java_lang_String;

void* __jnative_make_string_obj(const char* bytes, int32_t len) {
    if (len < 0) len = 0;
    void* value = jnative_array_alloc("[B", len + 1, JNATIVE_ELEM_SIZE_BYTE);
    if (value == NULL) {
        __jnative_throw_out_of_memory_error_ctx("__jnative_make_string_obj");
    }
    *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = len;
    if (len > 0 && bytes != NULL) memcpy((char*)value + JAVA_ARR_HDR, bytes, (size_t)len);
    ((char*)value)[JAVA_ARR_HDR + len] = '\0';

    void* s = calloc(1, 32);
    if (s == NULL) {
        __jnative_throw_out_of_memory_error_ctx("__jnative_make_string_obj");
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
    if (out_len) *out_len = *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET);
    return (const char*)value + JAVA_ARR_HDR;
}

static int32_t __jnative_string_eq(void* a, void* b) {
    int32_t la = 0, lb = 0;
    const char* ba = __jnative_read_string_bytes(a, &la);
    const char* bb = __jnative_read_string_bytes(b, &lb);
    return la == lb && memcmp(ba, bb, (size_t)la) == 0;
}

/* ---- Literal pool ---- */
static void**  __jnative_pool = NULL;
static int32_t __jnative_pool_size = 0;

void __jnative_init_string_pool(void** pool, int32_t size) {
    __jnative_pool = pool;
    __jnative_pool_size = size;
}

/* ---- Runtime intern table ---- */
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
 * String concatenation
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
 * Value-to-String helpers
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
    JNativeVTable* vt = *(JNativeVTable**)obj;
    if (vt == NULL) return __jnative_make_string_obj("null", 4);
    int32_t slot = __jnative_tostring_slot;
    if (slot < 0) return __jnative_make_string_obj("null", 4);
    void* entry = vt->methods[slot];
    if (entry == NULL) return __jnative_make_string_obj("null", 4);
    void* (*toString)(void*) = (void* (*)(void*))entry;
    return toString(obj);
}

/* ============================================================================
 * Runtime-side intern
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
    JNativeVTable* vt = *(JNativeVTable**)runnable;
    if (vt == NULL) return;
    void** itable = __jnative_lookup_itable(vt->ifacemap, iface_id);
    if (itable == NULL) return;
    void* entry = itable[slot];
    if (entry == NULL) return;
    void (*run)(void*) = (void (*)(void*))entry;
    run(runnable);
}

/* ============================================================================
 * <clinit> trace hook
 * ========================================================================== */

void __jnative_debug_clinit(const char* name) {
    fprintf(stderr, "[clinit] %s\n", name);
    fflush(stderr);
}

/* ============================================================================
 * Built-in resource table
 * ========================================================================== */

const JNativeResourceEntry* jnative_find_resource(const char* path, int32_t len)
{
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

/* ============================================================================
 * ByteArrayInputStream construction
 * ========================================================================== */

#define BAIS_BUF_OFFSET   8
#define BAIS_POS_OFFSET   16
#define BAIS_MARK_OFFSET  20
#define BAIS_COUNT_OFFSET 24

void* __jnative_make_byte_array_input_stream(void* bytes)
{
    if (bytes == NULL) {
        __jnative_throw_null_pointer_exception_ctx(
            "__jnative_make_byte_array_input_stream", "bytes");
    }

    ReflectionClass* cls = jnative_class_by_name("java/io/ByteArrayInputStream");
    if (cls == NULL) {
        fprintf(stderr,
                "jnative: fatal: java.io.ByteArrayInputStream is not "
                "registered; LlvmGenerator.ensureReflectClassRegistered must "
                "force-load it before codegen\n");
        _exit(1);
    }

    void* bais = jnative_alloc_object(cls);
    if (bais == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Class.getResourceAsStream");
    }

    int32_t len = jnative_array_length(bytes);
    *(void**)((char*)bais + BAIS_BUF_OFFSET)    = bytes;
    *(int32_t*)((char*)bais + BAIS_POS_OFFSET)   = 0;
    *(int32_t*)((char*)bais + BAIS_MARK_OFFSET)  = 0;
    *(int32_t*)((char*)bais + BAIS_COUNT_OFFSET) = len;

    return bais;
}

/* ============================================================================
 * Class.getResourceAsStream override
 * ========================================================================== */

#define RES_PATH_BUF 1024

void* __jnative_override_Class_getResourceAsStream(void* this_class,
                                                   void* name_str)
{
    if (this_class == NULL) {
        __jnative_throw_null_pointer_exception_ctx(
            "__jnative_override_Class_getResourceAsStream", "this_class");
    }
    if (name_str == NULL) {
        __jnative_throw_null_pointer_exception_ctx(
            "__jnative_override_Class_getResourceAsStream", "name_str");
    }

    int32_t name_len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &name_len);
    if (name == NULL || name_len <= 0) return NULL;

    char lookup_path[RES_PATH_BUF];
    int32_t lookup_len;

    if (name[0] == '/') {
        lookup_len = name_len - 1;
        if (lookup_len <= 0 || lookup_len >= RES_PATH_BUF) return NULL;
        memcpy(lookup_path, name + 1, (size_t)lookup_len);
    } else {
        const char* cname = ((ReflectionClass*)this_class)->cname;
        if (cname == NULL) return NULL;
        const char* slash = strrchr(cname, '/');
        if (slash == NULL) {
            if (name_len >= RES_PATH_BUF) return NULL;
            memcpy(lookup_path, name, (size_t)name_len);
            lookup_len = name_len;
        } else {
            size_t pkg_len = (size_t)(slash - cname) + 1;
            if (pkg_len + (size_t)name_len >= RES_PATH_BUF) return NULL;
            memcpy(lookup_path, cname, pkg_len);
            memcpy(lookup_path + pkg_len, name, (size_t)name_len);
            lookup_len = (int32_t)(pkg_len + (size_t)name_len);
        }
    }
    lookup_path[lookup_len] = '\0';

    const JNativeResourceEntry* res = jnative_find_resource(lookup_path, lookup_len);
    if (res == NULL) return NULL;

    void* bytes = jnative_byte_array(res->data, res->size);
    if (bytes == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Class.getResourceAsStream");
    }

    return __jnative_make_byte_array_input_stream(bytes);
}