/*
 * jnative_platform.c — Windows-only implementations.
 *
 * On POSIX this file is not compiled at all (jnative_platform.h provides
 * everything inline). On MinGW the pthread shims below are compiled out
 * because MinGW's WinPthreads is used directly; only the dlfcn helper
 * and the Win32 time functions are shared with MSVC.
 */
#ifdef _WIN32

#include "jnative_platform.h"

/* ====================================================================
 * Process-image handle for symbol lookup
 * ==================================================================== */
void* jn_dlsym_default = NULL;

static void __attribute__((constructor))
_jn_init_dlsym_default(void) {
    HMODULE h = GetModuleHandleA(NULL);
    jn_dlsym_default = (void*)h;
}

/* ====================================================================
 * CPU count
 * ==================================================================== */
int jn_cpu_count(void) {
    DWORD n = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    return (n > 0) ? (int)n : 1;
}

/* ====================================================================
 * Time
 * ==================================================================== */
int jn_now_realtime_win(struct timespec* ts) {
    FILETIME ft;
    GetSystemTimePreciseAsFileTime(&ft);
    ULARGE_INTEGER u;
    u.LowPart  = ft.dwLowDateTime;
    u.HighPart = ft.dwHighDateTime;
    uint64_t ns_100 = u.QuadPart;
    uint64_t secs   = ns_100 / 10000000ULL;
    uint64_t rem    = ns_100 % 10000000ULL;
    if (secs < 11644473600ULL) {
        ts->tv_sec  = 0;
        ts->tv_nsec = 0;
        return 0;
    }
    ts->tv_sec  = (time_t)(secs - 11644473600ULL);
    ts->tv_nsec = (long)(rem * 100ULL);
    return 0;
}

int jn_now_monotonic_win(struct timespec* ts) {
    LARGE_INTEGER freq, count;
    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&count);
    uint64_t s  = (uint64_t)count.QuadPart / (uint64_t)freq.QuadPart;
    uint64_t ns = ((uint64_t)count.QuadPart % (uint64_t)freq.QuadPart)
                * 1000000000ULL / (uint64_t)freq.QuadPart;
    ts->tv_sec  = (time_t)s;
    ts->tv_nsec = (long)ns;
    return 0;
}

int jn_sleep_ns_ns_win(const struct timespec* req, struct timespec* rem) {
    if (rem != NULL) { rem->tv_sec = 0; rem->tv_nsec = 0; }
    if (req == NULL) return EINVAL;
    if (req->tv_sec < 0 || req->tv_nsec < 0) return EINVAL;
    int64_t ns = (int64_t)req->tv_sec * 1000000000LL + (int64_t)req->tv_nsec;
    if (ns <= 0) return 0;
    int64_t ms = (ns + 999999) / 1000000;
    Sleep((DWORD)ms);
    return 0;
}

/* ====================================================================
 * MSVC-only pthread shim
 *
 * MinGW has WinPthreads — do not compile these on MinGW (the header
 * already maps pthread_* to the real functions there).
 * ==================================================================== */
#if !defined(__MINGW32__) && !defined(__MINGW64__)

static BOOL CALLBACK _jn_init_cs_cb(PINIT_ONCE once, PVOID param, PVOID* ctx) {
    (void)once; (void)ctx;
    InitializeCriticalSection((CRITICAL_SECTION*)param);
    return TRUE;
}

int jn_pthread_mutex_init_impl(pthread_mutex_t* m) {
    memset(m, 0, sizeof(*m));
    m->once = (INIT_ONCE)INIT_ONCE_STATIC_INIT;
    return 0;
}
int jn_pthread_mutex_lock_impl(pthread_mutex_t* m) {
    InitOnceExecuteOnce(&m->once, _jn_init_cs_cb, &m->cs, NULL);
    EnterCriticalSection(&m->cs);
    return 0;
}
int jn_pthread_mutex_unlock_impl(pthread_mutex_t* m) {
    LeaveCriticalSection(&m->cs);
    return 0;
}
int jn_pthread_mutex_destroy_impl(pthread_mutex_t* m) {
    DeleteCriticalSection(&m->cs);
    return 0;
}

int jn_pthread_cond_init_win(pthread_cond_t* c, const void* attr) {
    (void)attr;
    InitializeConditionVariable(&c->cv);
    return 0;
}
int jn_pthread_cond_timedwait_win(pthread_cond_t* c, pthread_mutex_t* m,
                                  const struct timespec* ts) {
    struct timespec now;
    jn_now_realtime_win(&now);
    int64_t delta_ns = ((int64_t)ts->tv_sec  - (int64_t)now.tv_sec)  * 1000000000LL
                     + ((int64_t)ts->tv_nsec - (int64_t)now.tv_nsec);
    DWORD timeout_ms;
    if (delta_ns <= 0) timeout_ms = 0;
    else {
        int64_t ms = (delta_ns + 999999) / 1000000;
        timeout_ms = (ms > (int64_t)MAXDWORD) ? MAXDWORD : (DWORD)ms;
    }
    BOOL ok = SleepConditionVariableCS(&c->cv, &m->cs, timeout_ms);
    if (!ok) {
        DWORD err = GetLastError();
        if (err == ERROR_TIMEOUT) return ETIMEDOUT;
        return EINVAL;
    }
    return 0;
}

typedef struct { void* (*fn)(void*); void* arg; } _jn_thread_start_t;

static unsigned __stdcall _jn_thread_trampoline(void* p) {
    _jn_thread_start_t* s = (_jn_thread_start_t*)p;
    void* (*fn)(void*) = s->fn;
    void* arg = s->arg;
    free(s);
    fn(arg);
    return 0;
}

int jn_pthread_create_win(pthread_t* t, const pthread_attr_t* attr,
                          void* (*fn)(void*), void* arg) {
    (void)attr;
    _jn_thread_start_t* s = (_jn_thread_start_t*)malloc(sizeof(*s));
    if (s == NULL) return ENOMEM;
    s->fn = fn;
    s->arg = arg;
    uintptr_t h = _beginthreadex(NULL, JN_DEFAULT_STACK_SIZE,
                                 _jn_thread_trampoline, s, 0, NULL);
    if (h == 0) { free(s); return errno ? errno : EAGAIN; }
    t->handle = (HANDLE)h;
    t->id     = GetThreadId((HANDLE)h);
    return 0;
}

int jn_pthread_join_win(pthread_t t, void** retval) {
    if (t.handle == NULL) return EINVAL;
    WaitForSingleObject(t.handle, INFINITE);
    if (retval) {
        DWORD code = 0;
        if (GetExitCodeThread(t.handle, &code)) *retval = (void*)(uintptr_t)code;
    }
    CloseHandle(t.handle);
    return 0;
}

pthread_t jn_pthread_self_win(void) {
    pthread_t t;
    t.handle = GetCurrentThread();
    t.id     = GetCurrentThreadId();
    return t;
}

int jn_pthread_equal_win(pthread_t a, pthread_t b) {
    return a.id == b.id;
}

#endif /* MSVC-only */

#endif /* _WIN32 */
