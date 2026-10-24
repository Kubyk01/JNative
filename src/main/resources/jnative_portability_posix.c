/* jnative_portability_posix.c — POSIX implementation of the
 * portability layer. Only compiled on Linux / macOS / BSD. */
#if !defined(_WIN32) && !defined(_WIN64)

#define _GNU_SOURCE
#include "jnative_portability.h"

#include <dlfcn.h>
#include <time.h>
#include <signal.h>
#include <execinfo.h>
#include <unistd.h>
#include <link.h>
#include <sys/ucontext.h>

#if defined(__APPLE__)
#  include <mach-o/dyld.h>
#endif

/* ==================================================================
 *  Dynamic symbol resolution
 * ================================================================== */

void* jn_lib_sym_self(const char* name) {
    return dlsym(RTLD_DEFAULT, name);
}
jn_lib_t jn_lib_open_self(void) {
    return (jn_lib_t)dlopen(NULL, RTLD_LAZY);
}
void jn_lib_close(jn_lib_t h) {
    if (h) dlclose(h);
}

/* ==================================================================
 *  Time
 * ================================================================== */

int64_t jn_clock_monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}
int64_t jn_clock_realtime_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}
int64_t jn_clock_resolution_ns(void) {
    struct timespec ts;
    if (clock_getres(CLOCK_MONOTONIC, &ts) != 0) return 1;
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}
void jn_sleep_ms(uint64_t ms) {
    struct timespec req = { (time_t)(ms / 1000),
                            (long)((ms % 1000) * 1000000ULL) };
    while (nanosleep(&req, &req) == -1 && errno == EINTR) {}
}

/* ==================================================================
 *  Stack traces
 * ================================================================== */

int jn_dladdr(void* addr, jn_dl_info* info) {
    if (!info) return 0;
    memset(info, 0, sizeof(*info));

    Dl_info di;
    if (!dladdr(addr, &di)) return 0;

    /* Normalise the lifetime: dladdr's strings point into loader-owned
     * memory; the portability contract is that the caller owns them. */
    if (di.dli_fname) info->dli_fname = strdup(di.dli_fname);
    info->dli_fbase = di.dli_fbase;
    if (di.dli_sname) info->dli_sname = strdup(di.dli_sname);
    info->dli_saddr = di.dli_saddr;
    return 1;
}

void jn_dl_info_free(jn_dl_info* info) {
    if (!info) return;
    free((void*)info->dli_fname);
    free((void*)info->dli_sname);
    memset(info, 0, sizeof(*info));
}

int jn_backtrace(void** buffer, int size) {
    return backtrace(buffer, size);
}

/* ==================================================================
 *  Segment ranges
 * ================================================================== */

typedef struct {
    uintptr_t lo;
    uintptr_t hi;
    int       text;
} _seg_scan_t;

static int _scan_main_phdrs(struct dl_phdr_info* info, size_t sz, void* data) {
    (void)sz;
    _seg_scan_t* r = (_seg_scan_t*)data;
    /* main executable has empty name */
    if (info->dlpi_name != NULL && info->dlpi_name[0] != '\0') return 0;

    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        if (r->text) {
            if (!(ph->p_flags & PF_X)) continue;
        } else {
            if (!(ph->p_flags & PF_W)) continue;
        }
        uintptr_t lo = (uintptr_t)info->dlpi_addr + (uintptr_t)ph->p_vaddr;
        uintptr_t hi = lo + (uintptr_t)ph->p_memsz;
        if (r->lo == 0 || lo < r->lo) r->lo = lo;
        if (r->hi == 0 || hi > r->hi) r->hi = hi;
    }
    return 1;   /* stop after the main exe */
}

int jn_get_static_range(uintptr_t* low, uintptr_t* high) {
    if (!low || !high) return 0;

#if defined(__linux__)
    _seg_scan_t s = { 0, 0, 0 };
    dl_iterate_phdr(_scan_main_phdrs, &s);

    /* Belt-and-braces: data_start/_end cover any unusual linker layouts. */
    extern char __data_start[] __attribute__((weak));
    extern char _end[]        __attribute__((weak));
    if (__data_start != NULL && _end != NULL) {
        uintptr_t lo = (uintptr_t)__data_start;
        uintptr_t hi = (uintptr_t)_end;
        if (lo && hi > lo) {
            if (s.lo == 0 || lo < s.lo) s.lo = lo;
            if (s.hi == 0 || hi > s.hi) s.hi = hi;
        }
    }
    if (s.lo == 0 || s.hi == 0) return 0;
    *low = s.lo; *high = s.hi;
    return 1;

#elif defined(__APPLE__)
    /* Mach-O uses a different loader API; returning 0 means the caller
     * will not attempt static-range filtering, which only costs a
     * couple of free() calls on string literals. */
    return 0;
#else
    return 0;
#endif
}

int jn_get_text_range(uintptr_t* low, uintptr_t* high) {
    if (!low || !high) return 0;
#if defined(__linux__)
    _seg_scan_t s = { 0, 0, 1 };
    dl_iterate_phdr(_scan_main_phdrs, &s);
    if (s.lo == 0 || s.hi == 0) return 0;
    *low = s.lo; *high = s.hi;
    return 1;
#else
    return 0;
#endif
}

/* ==================================================================
 *  Fatal signal handler
 * ================================================================== */

static jn_fatal_handler_t _jn_fatal_cb;
static volatile sig_atomic_t _jn_in_handler = 0;

static void _jn_signal_trampoline(int sig, siginfo_t* si, void* uc) {
    if (_jn_in_handler) {
        _exit(128 + sig);
    }
    _jn_in_handler = 1;
    if (_jn_fatal_cb) {
        void* fault = si ? (void*)si->si_addr : NULL;
        _jn_fatal_cb(sig, fault, uc);
    }
    _exit(128 + sig);
}

int jn_install_fatal_handler(jn_fatal_handler_t handler) {
    _jn_fatal_cb = handler;
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = _jn_signal_trampoline;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS,  &sa, NULL);
    sigaction(SIGFPE,  &sa, NULL);
    sigaction(SIGILL,  &sa, NULL);
    return 0;
}

void jn_get_context_regs(void* ucontext, uint64_t* rip, uint64_t* rsp, uint64_t* rbp) {
    *rip = 0; *rsp = 0; *rbp = 0;
    if (!ucontext) return;
#if defined(__x86_64__)
    ucontext_t* uc = (ucontext_t*)ucontext;
    *rip = (uint64_t)uc->uc_mcontext.gregs[REG_RIP];
    *rsp = (uint64_t)uc->uc_mcontext.gregs[REG_RSP];
    *rbp = (uint64_t)uc->uc_mcontext.gregs[REG_RBP];
#elif defined(__aarch64__)
    ucontext_t* uc = (ucontext_t*)ucontext;
    *rip = (uint64_t)uc->uc_mcontext.pc;
    *rsp = (uint64_t)uc->uc_mcontext.sp;
    *rbp = (uint64_t)uc->uc_mcontext.regs[29];
#else
    (void)ucontext;
#endif
}

JNATIVE_NORETURN void jn_exit_process(int code) {
    _exit(code);
}

#endif /* POSIX */