/*
 * jnative_portability.h
 *
 * Single point of abstraction over platform-specific primitives.
 * Every per-class native under jnative/ and jnative_runtime.c uses
 * ONLY these primitives — no <pthread.h>, <unistd.h>, <dlfcn.h>,
 * <execinfo.h>, <sys/ucontext.h>, <link.h>, or <signal.h> anywhere
 * outside this header and its two implementation files.
 *
 * On POSIX (Linux / macOS / BSD), the primitives map directly onto the
 * system calls with no overhead. On Windows, they map onto Win32 APIs
 * (SRWLOCK, CONDITION_VARIABLE, CRITICAL_SECTION, INIT_ONCE, FlsAlloc,
 * _beginthreadex, LoadLibrary / GetProcAddress, QueryPerformanceCounter,
 * DbgHelp, AddVectoredExceptionHandler).
 *
 * Threading model:
 *   - jn_lock_t      : statically initialised, non-recursive, exclusive.
 *                     Use for hash-table guards and any place where the
 *                     lock is never re-taken on the same thread.
 *   - jn_recmutex_t  : dynamically initialised, recursive.
 *                     Use for Java-level monitors (`synchronized`).
 *   - jn_cond_t      : statically initialised; works with jn_lock_t.
 *   - jn_once_t      : one-shot initialisation, statically initialised.
 *
 * The header deliberately includes <winsock2.h> BEFORE <windows.h>,
 * because winsock2.h requires being first to avoid redefinition of
 * the Win32 socket structs.
 */
#ifndef JNATIVE_PORTABILITY_H
#define JNATIVE_PORTABILITY_H

#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>

/* ------------------------------------------------------------------ *
 *  Platform selection and ordering-critical includes
 * ------------------------------------------------------------------ */
#if defined(_WIN32) || defined(_WIN64)
#  define JNATIVE_PLATFORM_WINDOWS 1

#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  ifndef NOMINMAX
#    define NOMINMAX
#  endif

   /* winsock2.h MUST be included before windows.h */
#  include <winsock2.h>
#  include <windows.h>
#  include <ws2tcpip.h>
#  include <iphlpapi.h>
#  include <process.h>
#  include <io.h>
#  include <fcntl.h>
#  include <direct.h>
#  include <sys/types.h>
#  include <sys/stat.h>
#  include <errno.h>

#  ifndef PATH_MAX
#    define PATH_MAX 260
#  endif

#  if defined(_MSC_VER) && !defined(__MINGW32__)
#    ifndef strdup
#      define strdup _strdup
#    endif
#  endif
#else
#  define JNATIVE_PLATFORM_POSIX 1

#  include <pthread.h>
#  include <unistd.h>
#  include <fcntl.h>
#  include <errno.h>
#  include <sys/types.h>
#  include <sys/stat.h>
#  include <limits.h>

#  ifndef PATH_MAX
#    define PATH_MAX 4096
#  endif
#endif

/*
 * jnative_runtime.c includes jnative_platform.h first (for sockets and
 * the native ABI types) and this header second (for diagnostics and
 * time). In that combination jnative_platform.h already owns
 * jn_thread_t / jn_mutex_t / jn_cond_t, so only the portability
 * extensions are declared below. Standalone users (the two
 * jnative_portability_*.c translation units) get the full definitions.
 */
#if defined(JNATIVE_PLATFORM_H)
#  define JNATIVE_PORTABILITY_OVER_PLATFORM 1
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ *
 *  Attribute shims
 * ------------------------------------------------------------------ */
#if defined(__GNUC__) || defined(__clang__)
#  define JNATIVE_NORETURN __attribute__((noreturn))
#  define JNATIVE_WEAK     __attribute__((weak))
#  define JNATIVE_UNUSED   __attribute__((unused))
#else
#  define JNATIVE_NORETURN
#  define JNATIVE_WEAK
#  define JNATIVE_UNUSED
#endif

/* ==================================================================
 *  1. THREADS, LOCKS, CONDITION VARIABLES, TLS, ONCE
 * ================================================================== */

#if !defined(JNATIVE_PORTABILITY_OVER_PLATFORM)

#if defined(JNATIVE_PLATFORM_WINDOWS)

typedef struct { HANDLE handle; DWORD id; } jn_thread_t;
typedef CRITICAL_SECTION jn_recmutex_t;
typedef SRWLOCK          jn_lock_t;
typedef CONDITION_VARIABLE jn_cond_t;
typedef INIT_ONCE        jn_once_t;
typedef DWORD            jn_tls_key_t;

#  define JN_LOCK_INIT  SRWLOCK_INIT
#  define JN_COND_INIT  CONDITION_VARIABLE_INIT
#  define JN_ONCE_INIT  INIT_ONCE_STATIC_INIT
#  define JN_TLS        __declspec(thread)

int         jn_thread_create(jn_thread_t* out, void* (*fn)(void*), void* arg);
int         jn_thread_join  (jn_thread_t t, void** retval);
jn_thread_t jn_thread_self  (void);
int         jn_thread_equal (jn_thread_t a, jn_thread_t b);

void jn_lock_acquire(jn_lock_t*);
void jn_lock_release(jn_lock_t*);

void jn_recmutex_init   (jn_recmutex_t*);
void jn_recmutex_lock   (jn_recmutex_t*);
void jn_recmutex_unlock (jn_recmutex_t*);
void jn_recmutex_destroy(jn_recmutex_t*);

void jn_cond_init      (jn_cond_t*);
int  jn_cond_wait      (jn_cond_t*, jn_lock_t*);
int  jn_cond_timedwait (jn_cond_t*, jn_lock_t*, uint64_t timeout_ms);
void jn_cond_signal    (jn_cond_t*);
void jn_cond_broadcast (jn_cond_t*);

int  jn_once(jn_once_t*, void (*fn)(void));

int  jn_tls_key_create(jn_tls_key_t* k, void (*destructor)(void*));
void* jn_tls_get(jn_tls_key_t k);
int  jn_tls_set(jn_tls_key_t k, void* v);

#else  /* POSIX */

typedef struct { pthread_t pthread; } jn_thread_t;
typedef pthread_mutex_t jn_recmutex_t;
typedef pthread_mutex_t jn_lock_t;
typedef pthread_cond_t  jn_cond_t;
typedef pthread_once_t  jn_once_t;
typedef pthread_key_t   jn_tls_key_t;

#  define JN_LOCK_INIT  PTHREAD_MUTEX_INITIALIZER
#  define JN_COND_INIT  PTHREAD_COND_INITIALIZER
#  define JN_ONCE_INIT  PTHREAD_ONCE_INIT
#  define JN_TLS        __thread __attribute__((tls_model("initial-exec")))

static inline int jn_thread_create(jn_thread_t* t, void* (*fn)(void*), void* arg) {
    return pthread_create(&t->pthread, NULL, fn, arg);
}
static inline int jn_thread_join(jn_thread_t t, void** rv) {
    return pthread_join(t.pthread, rv);
}
static inline jn_thread_t jn_thread_self(void) {
    jn_thread_t t; t.pthread = pthread_self(); return t;
}
static inline int jn_thread_equal(jn_thread_t a, jn_thread_t b) {
    return pthread_equal(a.pthread, b.pthread);
}

static inline void jn_lock_acquire(jn_lock_t* l) { pthread_mutex_lock(l); }
static inline void jn_lock_release(jn_lock_t* l) { pthread_mutex_unlock(l); }

static inline void jn_recmutex_init(jn_recmutex_t* m) {
    pthread_mutexattr_t a;
    pthread_mutexattr_init(&a);
    pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(m, &a);
    pthread_mutexattr_destroy(&a);
}
static inline void jn_recmutex_lock(jn_recmutex_t* m)    { pthread_mutex_lock(m); }
static inline void jn_recmutex_unlock(jn_recmutex_t* m)  { pthread_mutex_unlock(m); }
static inline void jn_recmutex_destroy(jn_recmutex_t* m) { pthread_mutex_destroy(m); }

static inline void jn_cond_init(jn_cond_t* c)    { pthread_cond_init(c, NULL); }
static inline void jn_cond_signal(jn_cond_t* c)  { pthread_cond_signal(c); }
static inline void jn_cond_broadcast(jn_cond_t* c){ pthread_cond_broadcast(c); }

static inline int jn_cond_wait(jn_cond_t* c, jn_lock_t* l) {
    return pthread_cond_wait(c, l);
}
static inline int jn_cond_timedwait(jn_cond_t* c, jn_lock_t* l, uint64_t timeout_ms) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec  += (time_t)(timeout_ms / 1000);
    ts.tv_nsec += (long)((timeout_ms % 1000) * 1000000ULL);
    if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
    int r = pthread_cond_timedwait(c, l, &ts);
    return (r == ETIMEDOUT) ? -2 : (r == 0 ? 0 : -1);
}

static inline int jn_once(jn_once_t* o, void (*fn)(void)) {
    return pthread_once(o, fn);
}

static inline int  jn_tls_key_create(jn_tls_key_t* k, void (*d)(void*)) {
    return pthread_key_create(k, d);
}
static inline void* jn_tls_get(jn_tls_key_t k)            { return pthread_getspecific(k); }
static inline int   jn_tls_set(jn_tls_key_t k, void* v)   { return pthread_setspecific(k, v); }

#endif /* platform */

#else  /* JNATIVE_PORTABILITY_OVER_PLATFORM */

/*
 * The platform header is already in scope. It provides jn_thread_t,
 * jn_mutex_t, jn_cond_t and the jn_thread_* / jn_mutex_* / jn_cond_*
 * macros; add the portability-only names and one-shot/TLS wrappers.
 */
typedef jn_mutex_t jn_lock_t;
typedef jn_mutex_t jn_recmutex_t;

#if defined(JNATIVE_PLATFORM_WINDOWS)
   typedef INIT_ONCE jn_once_t;
#  define JN_ONCE_INIT INIT_ONCE_STATIC_INIT
   typedef DWORD     jn_tls_key_t;

   int   jn_once(jn_once_t*, void (*fn)(void));
   int   jn_tls_key_create(jn_tls_key_t* k, void (*destructor)(void*));
   void* jn_tls_get(jn_tls_key_t k);
   int   jn_tls_set(jn_tls_key_t k, void* v);
#else
   typedef pthread_once_t jn_once_t;
#  define JN_ONCE_INIT PTHREAD_ONCE_INIT
   typedef pthread_key_t  jn_tls_key_t;

   static inline int jn_once(jn_once_t* o, void (*fn)(void)) {
       return pthread_once(o, fn);
   }
   static inline int jn_tls_key_create(jn_tls_key_t* k, void (*d)(void*)) {
       return pthread_key_create(k, d);
   }
   static inline void* jn_tls_get(jn_tls_key_t k) {
       return pthread_getspecific(k);
   }
   static inline int jn_tls_set(jn_tls_key_t k, void* v) {
       return pthread_setspecific(k, v);
   }
#endif

#  define jn_thread_self()      pthread_self()
#  define jn_thread_equal(a, b) pthread_equal((a), (b))

/* The platform header already defines these macros on Windows. The
 * guards keep this header usable in both include orders. */
#if defined(JNATIVE_PLATFORM_WINDOWS)
#  ifndef jn_now_realtime
#    define jn_now_realtime(ts)      jn_now_realtime_win(ts)
#  endif
#  ifndef jn_now_monotonic
#    define jn_now_monotonic(ts)     jn_now_monotonic_win(ts)
#  endif
#  ifndef jn_sleep_ns_ns
#    define jn_sleep_ns_ns(req, rem) jn_sleep_ns_ns_win((req), (rem))
#  endif
#endif
#  ifndef jn_dlsym_default
#    define jn_dlsym_default(name)   dlsym(jn_dlsym_default, name)
#  endif

#endif /* JNATIVE_PORTABILITY_OVER_PLATFORM */

/* ==================================================================
 *  2. DYNAMIC SYMBOL RESOLUTION
 * ==================================================================
 *
 * The runtime never dlopen's a foreign .so/.dll: every __jnative_*
 * symbol and every @gv_* / @refclass_* global is linked directly into
 * the executable. The functions below therefore all resolve against
 * the process image itself.
 * ================================================================== */
typedef void* jn_lib_t;

void*    jn_lib_sym_self(const char* name);
jn_lib_t jn_lib_open_self(void);
void     jn_lib_close(jn_lib_t h);

/* ==================================================================
 *  3. TIME
 * ================================================================== */
int64_t jn_clock_monotonic_ns(void);
int64_t jn_clock_realtime_ns(void);
int64_t jn_clock_resolution_ns(void);
void    jn_sleep_ms(uint64_t ms);

/* ==================================================================
 *  4. STACK TRACES
 * ==================================================================
 *
 * jn_dladdr fills info with HEAP-ALLOCATED copies of the module path
 * and symbol name (or NULL when unknown). jn_dl_info_free must be
 * called on every result to release them. This uniform heap-allocation
 * policy hides the POSIX `dladdr` vs. Windows `SymFromAddr` lifetime
 * differences from every caller.
 * ================================================================== */
typedef struct {
    const char* dli_fname;   /* may be NULL */
    void*       dli_fbase;   /* may be NULL */
    const char* dli_sname;   /* may be NULL */
    void*       dli_saddr;   /* may be NULL */
} jn_dl_info;

int  jn_dladdr(void* addr, jn_dl_info* info);
void jn_dl_info_free(jn_dl_info* info);
int  jn_backtrace(void** buffer, int size);

/* ==================================================================
 *  5. FATAL SIGNAL / SEH HANDLER
 * ==================================================================
 *
 * The handler is called for SIGSEGV/SIGBUS/SIGFPE/SIGILL (POSIX) or
 * for EXCEPTION_ACCESS_VIOLATION / EXCEPTION_ILLEGAL_INSTRUCTION /
 * EXCEPTION_INT_DIVIDE_BY_ZERO / EXCEPTION_STACK_OVERFLOW (Windows).
 *
 * `ucontext` is an opaque pointer to `ucontext_t*` (POSIX) or
 * `CONTEXT*` (Windows). Use jn_get_context_regs to extract the
 * instruction and stack pointers.
 *
 * The handler must not return; if it does, the platform shim will
 * terminate the process with ExitProcess / _exit after the handler
 * returns.
 * ================================================================== */
typedef void (*jn_fatal_handler_t)(int sig, void* fault_addr, void* ucontext);

int  jn_install_fatal_handler(jn_fatal_handler_t handler);
void jn_get_context_regs(void* ucontext, uint64_t* rip, uint64_t* rsp, uint64_t* rbp);

/** Terminate the process immediately. Async-signal-safe on both platforms. */
JNATIVE_NORETURN void jn_exit_process(int code);

/* ==================================================================
 *  6. SEGMENT RANGE QUERIES
 * ==================================================================
 *
 * jn_get_static_range returns the inclusive-exclusive range of the
 * main executable's writable data segments. Used by __jnative_free to
 * skip static objects (string literals in .data).
 *
 * jn_get_text_range returns the range of the main executable's
 * executable segments. Used by the function index to reject PCs that
 * fall outside the emitted code.
 *
 * Either function may return 0, meaning "range not available"; callers
 * treat that as "cannot check" and fall back to a safe default.
 * ================================================================== */
int jn_get_static_range(uintptr_t* low, uintptr_t* high);
int jn_get_text_range  (uintptr_t* low, uintptr_t* high);

/* ==================================================================
 *  7. CONSTRUCTOR
 * ================================================================== */
#if defined(_MSC_VER) && !defined(__clang__)
#  pragma section(".CRT$XCU", read)
#  define JN_CONSTRUCTOR(name) \
      static void __cdecl name(void); \
      __declspec(allocate(".CRT$XCU")) \
          void (__cdecl* _jn_ctor_##name)(void) = name; \
      static void __cdecl name(void)
#else
#  define JN_CONSTRUCTOR(name) \
      static void name(void) __attribute__((constructor)); \
      static void name(void)
#endif

/* ==================================================================
 *  8. WINDOWS-ONLY COMPAT SHIMS FOR POSIX SYMBOLS
 * ==================================================================
 *
 * Per-class native files use a handful of POSIX constants and helpers
 * that are not part of the Win32 API. They are provided here so the
 * files themselves need no #ifdef around every use.
 * ================================================================== */
#if defined(JNATIVE_PLATFORM_WINDOWS)

#  ifndef O_CLOEXEC
#    define O_CLOEXEC 0
#  endif
#  ifndef O_SYNC
#    define O_SYNC 0
#  endif
#  ifndef O_DSYNC
#    define O_DSYNC 0
#  endif
#  ifndef O_BINARY
#    define O_BINARY _O_BINARY
#  endif
#  ifndef EINTR
#    define EINTR 4
#  endif
#  ifndef EBADF
#    define EBADF 9
#  endif
#  ifndef ENOENT
#    define ENOENT 2
#  endif
#  ifndef ECONNREFUSED
#    define ECONNREFUSED 10061
#  endif
#  ifndef F_OK
#    define F_OK 0
#  endif
#  ifndef R_OK
#    define R_OK 4
#  endif
#  ifndef W_OK
#    define W_OK 2
#  endif
#  ifndef X_OK
#    define X_OK 1
#  endif

/* AF_UNIX is not available before Windows 10 1803 — provide the
 * fallback constant so socket-family switches compile. */
#  ifndef AF_UNIX
#    define AF_UNIX 1
#  endif

/* Windows-time compatibility aliases for POSIX-named calls. */
#  ifndef jn_now_realtime
#    define jn_now_realtime(ts)      jn_now_realtime_win(ts)
#  endif
#  ifndef jn_now_monotonic
#    define jn_now_monotonic(ts)     jn_now_monotonic_win(ts)
#  endif
#  ifndef jn_sleep_ns_ns
#    define jn_sleep_ns_ns(req, rem) jn_sleep_ns_ns_win((req), (rem))
#  endif
#  ifndef jn_dlsym_default
#    define jn_dlsym_default(name)   dlsym(jn_dlsym_default, (name))
#  endif

/* Signal numbers used by the fatal-handler path; not the same values as
 * POSIX, but they are only used to build the exit code. */
#  ifndef SIGSEGV
#    define SIGSEGV 11
#  endif
#  ifndef SIGBUS
#    define SIGBUS  7
#  endif
#  ifndef SIGFPE
#    define SIGFPE  8
#  endif
#  ifndef SIGILL
#    define SIGILL  4
#  endif

/* readlink / symlink — Windows has no direct equivalent, so the shims
 * return -1 (the caller's error path). */
#if !defined(JNATIVE_PORTABILITY_OVER_PLATFORM)
static inline long jn_readlink(const char* path, char* buf, size_t len) {
    (void)path; (void)buf; (void)len;
    return -1;
}
#endif

/* getifaddrs — implemented via GetAdaptersAddresses in
 * jnative_portability_windows.c. The struct is deliberately opaque:
 * callers only pass the pointer around. */
struct jn_ifaddrs;
int  jn_getifaddrs(struct jn_ifaddrs** out);
void jn_freeifaddrs(struct jn_ifaddrs* p);

#  define ifaddrs jn_ifaddrs
#  define getifaddrs(p) jn_getifaddrs(p)
#  define freeifaddrs(p) jn_freeifaddrs(p)

#endif /* JNATIVE_PLATFORM_WINDOWS */

#ifdef __cplusplus
}
#endif

#endif /* JNATIVE_PORTABILITY_H */