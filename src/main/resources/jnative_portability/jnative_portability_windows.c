/* jnative_portability_windows.c — Windows implementation of the
 * portability layer. Compiled only on Windows targets. */
#if defined(_WIN32) || defined(_WIN64)

#define _GNU_SOURCE
#include "jnative_portability.h"

#include <dbghelp.h>
#include <tlhelp32.h>
#include <psapi.h>
#include <winternl.h>

#pragma comment(lib, "dbghelp.lib")
#pragma comment(lib, "psapi.lib")

/* ==================================================================
 *  Threads
 * ================================================================== */

typedef struct { void* (*fn)(void*); void* arg; } _thr_ctx_t;

static unsigned __stdcall _thr_trampoline(void* p) {
    _thr_ctx_t* c = (_thr_ctx_t*)p;
    void* r = c->fn(c->arg);
    free(c);
    return (unsigned)(uintptr_t)r;
}

int jn_thread_create(jn_thread_t* out, void* (*fn)(void*), void* arg) {
    _thr_ctx_t* c = (_thr_ctx_t*)malloc(sizeof(*c));
    if (!c) return -1;
    c->fn = fn; c->arg = arg;

    uintptr_t h = _beginthreadex(NULL, 0, _thr_trampoline, c, 0, NULL);
    if (h == 0) { free(c); return -1; }

    out->handle = (HANDLE)h;
    out->id = GetThreadId((HANDLE)h);
    return 0;
}

int jn_thread_join(jn_thread_t t, void** retval) {
    DWORD wr = WaitForSingleObject(t.handle, INFINITE);
    if (retval) {
        DWORD code = 0;
        if (GetExitCodeThread(t.handle, &code)) *retval = (void*)(uintptr_t)code;
    }
    CloseHandle(t.handle);
    return wr == WAIT_OBJECT_0 ? 0 : -1;
}

jn_thread_t jn_thread_self(void) {
    jn_thread_t t;
    t.handle = GetCurrentThread();      /* pseudo-handle; do not CloseHandle */
    t.id     = GetCurrentThreadId();
    return t;
}

int jn_thread_equal(jn_thread_t a, jn_thread_t b) {
    return a.id == b.id;
}

/* ==================================================================
 *  Locks
 * ================================================================== */

void jn_lock_acquire(jn_lock_t* l) { AcquireSRWLockExclusive(l); }
void jn_lock_release(jn_lock_t* l) { ReleaseSRWLockExclusive(l); }

void jn_recmutex_init(jn_recmutex_t* m)   { InitializeCriticalSection(m); }
void jn_recmutex_lock(jn_recmutex_t* m)   { EnterCriticalSection(m); }
void jn_recmutex_unlock(jn_recmutex_t* m) { LeaveCriticalSection(m); }
void jn_recmutex_destroy(jn_recmutex_t* m){ DeleteCriticalSection(m); }

/* ==================================================================
 *  Condition variables
 * ================================================================== */

void jn_cond_init(jn_cond_t* c)       { InitializeConditionVariable(c); }
void jn_cond_signal(jn_cond_t* c)     { WakeConditionVariable(c); }
void jn_cond_broadcast(jn_cond_t* c)  { WakeAllConditionVariable(c); }

int jn_cond_wait(jn_cond_t* c, jn_lock_t* l) {
    return SleepConditionVariableSRW(c, l, INFINITE, 0) ? 0 : -1;
}

int jn_cond_timedwait(jn_cond_t* c, jn_lock_t* l, uint64_t timeout_ms) {
    DWORD ms = (timeout_ms > 0xFFFFFFF0ULL) ? 0xFFFFFFF0UL : (DWORD)timeout_ms;
    if (SleepConditionVariableSRW(c, l, ms, 0)) return 0;
    return (GetLastError() == ERROR_TIMEOUT) ? -2 : -1;
}

/* ==================================================================
 *  Once
 * ================================================================== */

static BOOL CALLBACK _jn_once_adapter(PINIT_ONCE o, PVOID p, PVOID* c) {
    (void)o; (void)c;
    void (*fn)(void) = (void (*)(void))p;
    fn();
    return TRUE;
}

int jn_once(jn_once_t* o, void (*fn)(void)) {
    return InitOnceExecuteOnce(o, _jn_once_adapter, (PVOID)fn, NULL) ? 0 : -1;
}

/* ==================================================================
 *  TLS with destructor (FlsAlloc provides this; TlsAlloc does not)
 * ================================================================== */

int jn_tls_key_create(jn_tls_key_t* k, void (*destructor)(void*)) {
    DWORD idx = FlsAlloc((PFLS_CALLBACK_FUNCTION)destructor);
    if (idx == FLS_OUT_OF_INDEXES) return -1;
    *k = idx;
    return 0;
}
void* jn_tls_get(jn_tls_key_t k)         { return FlsGetValue(k); }
int   jn_tls_set(jn_tls_key_t k, void* v){ return FlsSetValue(k, v) ? 0 : -1; }

/* ==================================================================
 *  Dynamic symbol resolution
 * ================================================================== */

void* jn_lib_sym_self(const char* name) {
    HMODULE h = GetModuleHandleA(NULL);
    void* p = (void*)GetProcAddress(h, name);
    if (p) return p;

    /* Fallback: search every loaded module. Needed for symbols that the
     * linker placed in an import thunk rather than the main module. */
    HMODULE mods[256];
    DWORD needed = 0;
    if (EnumProcessModules(GetCurrentProcess(), mods, sizeof(mods), &needed)) {
        int n = needed / (int)sizeof(HMODULE);
        for (int i = 0; i < n; i++) {
            p = (void*)GetProcAddress(mods[i], name);
            if (p) return p;
        }
    }
    return NULL;
}

jn_lib_t jn_lib_open_self(void) { return (jn_lib_t)GetModuleHandleA(NULL); }
void jn_lib_close(jn_lib_t h)   { (void)h; /* image cannot be unloaded */ }

/* ==================================================================
 *  Time
 * ================================================================== */

static LARGE_INTEGER _jn_qpc_freq;
static volatile LONG _jn_qpc_init = 0;

static void _jn_qpc_ensure(void) {
    if (InterlockedCompareExchange(&_jn_qpc_init, 1, 0) == 0) {
        QueryPerformanceFrequency(&_jn_qpc_freq);
    } else {
        while (InterlockedCompareExchange(&_jn_qpc_init, 2, 1) != 2) {
            Sleep(0);
        }
    }
}

int64_t jn_clock_monotonic_ns(void) {
    _jn_qpc_ensure();
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return (int64_t)((c.QuadPart * 1000000000LL) / _jn_qpc_freq.QuadPart);
}

int64_t jn_clock_realtime_ns(void) {
    FILETIME ft;
    GetSystemTimePreciseAsFileTime(&ft);
    ULARGE_INTEGER u;
    u.LowPart = ft.dwLowDateTime;
    u.HighPart = ft.dwHighDateTime;
    /* FILETIME = 100ns intervals since 1601-01-01. */
    const int64_t EPOCH_DIFF = 116444736000000000LL;
    return (int64_t)((u.QuadPart - EPOCH_DIFF) * 100LL);
}

int64_t jn_clock_resolution_ns(void) {
    _jn_qpc_ensure();
    return (int64_t)(1000000000LL / _jn_qpc_freq.QuadPart);
}

void jn_sleep_ms(uint64_t ms) {
    Sleep((DWORD)(ms > 0xFFFFFFFFULL ? 0xFFFFFFFFUL : ms));
}

/* ==================================================================
 *  Stack traces via DbgHelp
 * ================================================================== */

static INIT_ONCE      _jn_sym_once = INIT_ONCE_STATIC_INIT;
static BOOL           _jn_sym_ok   = FALSE;
static CRITICAL_SECTION _jn_sym_lock;

static BOOL CALLBACK _jn_sym_init(PINIT_ONCE o, PVOID p, PVOID* c) {
    (void)o; (void)p; (void)c;
    SymSetOptions(SYMOPT_UNDNAME | SYMOPT_DEFERRED_LOADS |
                  SYMOPT_LOAD_LINES | SYMOPT_DEBUG |
                  SYMOPT_INCLUDE_32BIT_MODULES);
    _jn_sym_ok = SymInitialize(GetCurrentProcess(), NULL, TRUE);
    InitializeCriticalSection(&_jn_sym_lock);
    return TRUE;
}

int jn_dladdr(void* addr, jn_dl_info* info) {
    if (!info) return 0;
    memset(info, 0, sizeof(*info));

    InitOnceExecuteOnce(&_jn_sym_once, _jn_sym_init, NULL, NULL);
    if (!_jn_sym_ok) return 0;

    MEMORY_BASIC_INFORMATION mbi;
    if (!VirtualQuery(addr, &mbi, sizeof(mbi))) return 0;
    HMODULE mod = (HMODULE)mbi.AllocationBase;
    info->dli_fbase = (void*)mod;

    char modpath[MAX_PATH];
    if (GetModuleFileNameA(mod, modpath, sizeof(modpath))) {
        info->dli_fname = _strdup(modpath);
    }

    char buf[sizeof(SYMBOL_INFO) + 256];
    PSYMBOL_INFO sym = (PSYMBOL_INFO)buf;
    sym->SizeOfStruct = sizeof(SYMBOL_INFO);
    sym->MaxNameLen = 255;
    DWORD64 disp = 0;
    EnterCriticalSection(&_jn_sym_lock);
    BOOL ok = SymFromAddr(GetCurrentProcess(), (DWORD64)(uintptr_t)addr,
                          &disp, sym);
    LeaveCriticalSection(&_jn_sym_lock);

    if (ok) {
        info->dli_sname = _strdup(sym->Name);
        info->dli_saddr = (void*)(uintptr_t)sym->Address;
    }
    return 1;
}

void jn_dl_info_free(jn_dl_info* info) {
    if (!info) return;
    free((void*)info->dli_fname);
    free((void*)info->dli_sname);
    memset(info, 0, sizeof(*info));
}

int jn_backtrace(void** buffer, int size) {
    if (size <= 0) return 0;
    USHORT n = RtlCaptureStackBackTrace(0, (ULONG)size, buffer, NULL);
    return (int)n;
}

/* ==================================================================
 *  Segment ranges from PE headers
 * ================================================================== */

static int _jn_pe_ranges(uintptr_t* lo, uintptr_t* hi, int want_text) {
    HMODULE mod = GetModuleHandleA(NULL);
    if (!mod) return 0;

    IMAGE_DOS_HEADER* dos = (IMAGE_DOS_HEADER*)mod;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return 0;

    IMAGE_NT_HEADERS* nt = (IMAGE_NT_HEADERS*)((char*)mod + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return 0;

    uintptr_t base = (uintptr_t)mod;
    uintptr_t lo_v = 0, hi_v = 0;

    IMAGE_SECTION_HEADER* sec = IMAGE_FIRST_SECTION(nt);
    for (int i = 0; i < nt->FileHeader.NumberOfSections; i++, sec++) {
        DWORD ch = sec->Characteristics;
        int is_match = want_text
            ? ((ch & IMAGE_SCN_MEM_EXECUTE) != 0)
            : ((ch & IMAGE_SCN_MEM_WRITE) != 0);
        if (!is_match) continue;

        uintptr_t s_lo = base + sec->VirtualAddress;
        uintptr_t s_hi = s_lo + sec->Misc.VirtualSize;
        if (lo_v == 0 || s_lo < lo_v) lo_v = s_lo;
        if (hi_v == 0 || s_hi > hi_v) hi_v = s_hi;
    }
    if (lo_v == 0 || hi_v == 0) return 0;
    *lo = lo_v; *hi = hi_v;
    return 1;
}

int jn_get_static_range(uintptr_t* lo, uintptr_t* hi) {
    return _jn_pe_ranges(lo, hi, 0);
}
int jn_get_text_range(uintptr_t* lo, uintptr_t* hi) {
    return _jn_pe_ranges(lo, hi, 1);
}

/* ==================================================================
 *  Fatal exception handler via Vectored Exception Handling
 * ================================================================== */

static jn_fatal_handler_t _jn_fatal_cb;
static PVOID              _jn_veh_handle;
static volatile LONG      _jn_in_handler = 0;

static LONG WINAPI _jn_veh(EXCEPTION_POINTERS* ep) {
    if (InterlockedCompareExchange(&_jn_in_handler, 1, 0) != 0) {
        return EXCEPTION_CONTINUE_SEARCH;
    }

    DWORD code = ep->ExceptionRecord->ExceptionCode;
    int sig;
    switch (code) {
        case EXCEPTION_ACCESS_VIOLATION:    sig = SIGSEGV; break;
        case EXCEPTION_ILLEGAL_INSTRUCTION: sig = SIGILL;  break;
        case EXCEPTION_INT_DIVIDE_BY_ZERO:  sig = SIGFPE;  break;
        case EXCEPTION_STACK_OVERFLOW:      sig = SIGBUS;  break;
        case EXCEPTION_IN_PAGE_ERROR:       sig = SIGBUS;  break;
        default:
            InterlockedExchange(&_jn_in_handler, 0);
            return EXCEPTION_CONTINUE_SEARCH;
    }

    void* fault_addr = NULL;
    if (code == EXCEPTION_ACCESS_VIOLATION &&
        ep->ExceptionRecord->NumberParameters >= 2) {
        fault_addr = (void*)ep->ExceptionRecord->ExceptionInformation[1];
    }

    if (_jn_fatal_cb) {
        _jn_fatal_cb(sig, fault_addr, ep->ContextRecord);
    }
    ExitProcess((UINT)(128 + sig));
}

int jn_install_fatal_handler(jn_fatal_handler_t handler) {
    _jn_fatal_cb = handler;
    _jn_veh_handle = AddVectoredExceptionHandler(1, _jn_veh);
    return _jn_veh_handle ? 0 : -1;
}

void jn_get_context_regs(void* ucontext, uint64_t* rip, uint64_t* rsp, uint64_t* rbp) {
    *rip = 0; *rsp = 0; *rbp = 0;
    if (!ucontext) return;
    CONTEXT* ctx = (CONTEXT*)ucontext;
#if defined(_M_X64) || defined(__x86_64__)
    *rip = (uint64_t)ctx->Rip;
    *rsp = (uint64_t)ctx->Rsp;
    *rbp = (uint64_t)ctx->Rbp;
#elif defined(_M_ARM64) || defined(__aarch64__)
    *rip = (uint64_t)ctx->Pc;
    *rsp = (uint64_t)ctx->Sp;
    *rbp = (uint64_t)ctx->Fp;
#endif
}

JNATIVE_NORETURN void jn_exit_process(int code) {
    ExitProcess((UINT)code);
}

/* ==================================================================
 *  getifaddrs shim (minimal — no per-address entries)
 *
 *  The runtime's NetworkInterface.c uses getifaddrs only for the
 *  loopback/MAC-address queries. On Windows those come from
 *  GetAdaptersAddresses directly; this shim reports "no interfaces"
 *  so the generic code path degrades to an empty list.
 * ================================================================== */

struct jn_ifaddrs;

int jn_getifaddrs(struct jn_ifaddrs** out) {
    if (out) *out = NULL;
    return 0;
}
void jn_freeifaddrs(struct jn_ifaddrs* p) { (void)p; }

#endif /* _WIN32 */