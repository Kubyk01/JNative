#ifndef JNATIVE_PLATFORM_H
#define JNATIVE_PLATFORM_H

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>

#ifdef _WIN32
/* ====================================================================
 * Windows
 * ==================================================================== */
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef _CRT_SECURE_NO_WARNINGS
#define _CRT_SECURE_NO_WARNINGS
#endif
#ifndef WIN32_EXTRA_LEAN
#define WIN32_EXTRA_LEAN
#endif

#include <winsock2.h>
#include <windows.h>
#include <ws2tcpip.h>
#include <psapi.h>
#include <process.h>
#include <io.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <direct.h>
#include <signal.h>
#include <errno.h>

#ifndef PATH_MAX
#define PATH_MAX MAX_PATH
#endif

/* MSVC does not ship POSIX names; MinGW does. Only redefine on MSVC. */
#if !defined(__MINGW32__) && !defined(__MINGW64__)
#  ifndef strdup
#    define strdup      _strdup
#  endif
#  define strcasecmp    _stricmp
#  define strncasecmp   _strnicmp
#  define close         _close
#  define read          _read
#  define write         _write
#  define lseek         _lseek
#  define open          _open
#  define unlink        _unlink
#  define getcwd        _getcwd
#  define access        _access
#  define dup           _dup
#  define dup2          _dup2
#  define fileno        _fileno
#endif

#ifndef F_OK
#define F_OK 0
#endif
#ifndef R_OK
#define R_OK 4
#endif
#ifndef W_OK
#define W_OK 2
#endif
#ifndef X_OK
#define X_OK 1
#endif

#ifndef STDIN_FILENO
#define STDIN_FILENO  0
#endif
#ifndef STDOUT_FILENO
#define STDOUT_FILENO 1
#endif
#ifndef STDERR_FILENO
#define STDERR_FILENO 2
#endif

#if defined(__GNUC__) || defined(__clang__)
#  define JN_NORETURN __attribute__((noreturn))
#  define JN_WEAK     __attribute__((weak))
#  define JN_UNUSED   __attribute__((unused))
#else
#  define JN_NORETURN __declspec(noreturn)
#  define JN_WEAK
#  define JN_UNUSED
#endif

typedef int jn_socket_t;
#define JN_INVALID_SOCKET INVALID_SOCKET

/* Windows has no readlink(2); report failure so callers take their
 * existing error path. */
static inline long jn_readlink(const char* path, char* buf, size_t len) {
    (void)path; (void)buf; (void)len;
    return -1;
}

/* Number of logical CPUs, used by the parallel initializer runner. */
int jn_cpu_count(void);

/*
 * MinGW ships WinPthreads — use it directly. Redefining pthread_mutex_t,
 * pthread_cond_t, pthread_t etc. is exactly what produced the
 * "typedef redefinition with different types" cascade in the error log.
 * On MinGW the real <pthread.h> wins and we simply forward our jn_*
 * aliases to it.
 *
 * MSVC does not ship pthreads at all — there we still provide the
 * CRITICAL_SECTION / CONDITION_VARIABLE shim (implemented in
 * jnative_platform.c so it cannot be shadowed by a system pthread.h).
 */
#if defined(__MINGW32__) || defined(__MINGW64__)
#  include <pthread.h>
#  include <sched.h>

   typedef pthread_t       jn_thread_t;
   typedef pthread_mutex_t jn_mutex_t;
   typedef pthread_cond_t  jn_cond_t;

#  define JN_MUTEX_INITIALIZER PTHREAD_MUTEX_INITIALIZER
#  define JN_COND_INITIALIZER  PTHREAD_COND_INITIALIZER
#  define JN_TLS               __thread __attribute__((tls_model("initial-exec")))

#  define jn_mutex_init(m, a)   pthread_mutex_init((m), (const pthread_mutexattr_t*)(a))
#  define jn_mutex_lock(m)      pthread_mutex_lock(m)
#  define jn_mutex_unlock(m)    pthread_mutex_unlock(m)
#  define jn_mutex_destroy(m)   pthread_mutex_destroy(m)

#  define jn_cond_init(c, a)    pthread_cond_init((c), (const pthread_condattr_t*)(a))
#  define jn_cond_wait(c, m)    pthread_cond_wait((c), (m))
#  define jn_cond_timedwait(c, m, ts) pthread_cond_timedwait((c), (m), (ts))
#  define jn_cond_signal(c)     pthread_cond_signal(c)
#  define jn_cond_broadcast(c)  pthread_cond_broadcast(c)

#  define jn_thread_create(t, a, f, x) pthread_create((t), (const pthread_attr_t*)(a), (f), (x))
#  define jn_thread_join(t, r)         pthread_join((t), (r))
#  define jn_yield()                   sched_yield()

static inline void jn_recmutex_init(jn_mutex_t* m) {
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(m, &attr);
    pthread_mutexattr_destroy(&attr);
}
#else
   /* ---- MSVC shim ---- */
   typedef struct { CRITICAL_SECTION cs; INIT_ONCE once; } pthread_mutex_t;
#  define PTHREAD_MUTEX_INITIALIZER { {0}, INIT_ONCE_STATIC_INIT }
#  define PTHREAD_MUTEX_RECURSIVE   1

   typedef struct { CONDITION_VARIABLE cv; } pthread_cond_t;
#  define PTHREAD_COND_INITIALIZER { CONDITION_VARIABLE_INIT }

   typedef struct { HANDLE handle; DWORD id; } pthread_t;
   typedef int pthread_mutexattr_t;
   typedef int pthread_attr_t;

   int jn_pthread_mutex_init_impl(pthread_mutex_t* m);
   int jn_pthread_mutex_lock_impl(pthread_mutex_t* m);
   int jn_pthread_mutex_unlock_impl(pthread_mutex_t* m);
   int jn_pthread_mutex_destroy_impl(pthread_mutex_t* m);

#  define pthread_mutex_init(m, a)     ((void)(a), jn_pthread_mutex_init_impl(m))
#  define pthread_mutex_lock(m)        jn_pthread_mutex_lock_impl(m)
#  define pthread_mutex_unlock(m)      jn_pthread_mutex_unlock_impl(m)
#  define pthread_mutex_destroy(m)     jn_pthread_mutex_destroy_impl(m)
#  define pthread_mutexattr_init(a)    ((void)(a), 0)
#  define pthread_mutexattr_settype(a,t) ((void)(a), (void)(t), 0)
#  define pthread_mutexattr_destroy(a) ((void)(a), 0)

   int       jn_pthread_cond_init_win(pthread_cond_t* c, const void* attr);
   int       jn_pthread_cond_timedwait_win(pthread_cond_t* c, pthread_mutex_t* m, const struct timespec* ts);
   int       jn_pthread_create_win(pthread_t* t, const pthread_attr_t* attr, void* (*fn)(void*), void* arg);
   int       jn_pthread_join_win(pthread_t t, void** retval);
   pthread_t jn_pthread_self_win(void);
   int       jn_pthread_equal_win(pthread_t a, pthread_t b);

#  define pthread_cond_init(c, a)         jn_pthread_cond_init_win((c), (a))
#  define pthread_cond_destroy(c)         ((void)(c), 0)
#  define pthread_cond_signal(c)          ((void)WakeConditionVariable(&(c)->cv), 0)
#  define pthread_cond_broadcast(c)       ((void)WakeAllConditionVariable(&(c)->cv), 0)
#  define pthread_cond_wait(c, m)         ((void)SleepConditionVariableCS(&(c)->cv, &(m)->cs, INFINITE), 0)
#  define pthread_cond_timedwait(c, m, ts) jn_pthread_cond_timedwait_win((c), (m), (ts))
#  define pthread_create(t, a, f, x)      jn_pthread_create_win((t), (a), (f), (x))
#  define pthread_join(t, r)              jn_pthread_join_win((t), (r))
#  define pthread_self()                  jn_pthread_self_win()
#  define pthread_equal(a, b)             jn_pthread_equal_win((a), (b))

   typedef pthread_t       jn_thread_t;
   typedef pthread_mutex_t jn_mutex_t;
   typedef pthread_cond_t  jn_cond_t;

#  define JN_MUTEX_INITIALIZER PTHREAD_MUTEX_INITIALIZER
#  define JN_COND_INITIALIZER  PTHREAD_COND_INITIALIZER
#  define JN_TLS               __declspec(thread)

#  define jn_mutex_init(m, a)   pthread_mutex_init((m), (a))
#  define jn_mutex_lock(m)      pthread_mutex_lock(m)
#  define jn_mutex_unlock(m)    pthread_mutex_unlock(m)
#  define jn_mutex_destroy(m)   pthread_mutex_destroy(m)
   /* CRITICAL_SECTION is recursive by definition. */
#  define jn_recmutex_init(m)   pthread_mutex_init((m), NULL)

#  define jn_cond_init(c, a)    pthread_cond_init((c), (a))
#  define jn_cond_wait(c, m)    pthread_cond_wait((c), (m))
#  define jn_cond_timedwait(c, m, ts) pthread_cond_timedwait((c), (m), (ts))
#  define jn_cond_signal(c)     pthread_cond_signal(c)
#  define jn_cond_broadcast(c)  pthread_cond_broadcast(c)

#  define jn_thread_create(t, a, f, x) pthread_create((t), (a), (f), (x))
#  define jn_thread_join(t, r)         pthread_join((t), (r))
#  define jn_yield()                   SwitchToThread()
#endif

/* ---- dlfcn shim (same on MinGW and MSVC) ---- */
#include "jnative_dlfcn_win32.h"

/* ---- Time (implemented in jnative_platform.c for Windows) ---- */
int jn_now_realtime_win(struct timespec* ts);
int jn_now_monotonic_win(struct timespec* ts);
int jn_sleep_ns_ns_win(const struct timespec* req, struct timespec* rem);

#define jn_now_realtime(ts)      jn_now_realtime_win(ts)
#define jn_now_monotonic(ts)     jn_now_monotonic_win(ts)
#define jn_sleep_ns_ns(req, rem) jn_sleep_ns_ns_win((req), (rem))

/* The process-image handle for symbol lookup. Defined in
 * jnative_platform.c; initialised by a constructor before main() runs. */
extern void* jn_dlsym_default;
#define jn_dlsym_default(name) dlsym(jn_dlsym_default, name)

#else
/* ====================================================================
 * POSIX
 * ==================================================================== */
#include <unistd.h>
#include <pthread.h>
#include <sched.h>
#include <dlfcn.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <netinet/in.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <dirent.h>
#include <termios.h>
#include <signal.h>
#include <fcntl.h>

typedef int jn_socket_t;
#define JN_INVALID_SOCKET (-1)

typedef pthread_t       jn_thread_t;
typedef pthread_mutex_t jn_mutex_t;
typedef pthread_cond_t  jn_cond_t;

#define JN_MUTEX_INITIALIZER PTHREAD_MUTEX_INITIALIZER
#define JN_COND_INITIALIZER  PTHREAD_COND_INITIALIZER
#define JN_TLS               __thread __attribute__((tls_model("initial-exec")))

#if defined(__GNUC__) || defined(__clang__)
#  define JN_NORETURN __attribute__((noreturn))
#  define JN_WEAK     __attribute__((weak))
#  define JN_UNUSED   __attribute__((unused))
#else
#  define JN_NORETURN
#  define JN_WEAK
#  define JN_UNUSED
#endif

#ifndef RTLD_DEFAULT
#define RTLD_DEFAULT ((void*)0)
#endif

#define jn_mutex_init(m, a)   ((a) == NULL ? pthread_mutex_init((m), NULL) \
                                           : pthread_mutex_init((m), (const pthread_mutexattr_t*)(a)))
#define jn_mutex_lock(m)      pthread_mutex_lock(m)
#define jn_mutex_unlock(m)    pthread_mutex_unlock(m)
#define jn_mutex_destroy(m)   pthread_mutex_destroy(m)

#define jn_cond_init(c, a)    pthread_cond_init((c), (const pthread_condattr_t*)(a))
#define jn_cond_wait(c, m)    pthread_cond_wait((c), (m))
#define jn_cond_timedwait(c, m, ts) pthread_cond_timedwait((c), (m), (ts))
#define jn_cond_signal(c)     pthread_cond_signal(c)
#define jn_cond_broadcast(c)  pthread_cond_broadcast(c)

#define jn_thread_create(t, a, f, x) pthread_create((t), (const pthread_attr_t*)(a), (f), (x))
#define jn_thread_join(t, r)         pthread_join((t), (r))

static inline long jn_readlink(const char* path, char* buf, size_t len) {
    return (long)readlink(path, buf, len);
}

static inline int jn_cpu_count(void) {
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    return n > 0 ? (int)n : 1;
}

static inline void jn_recmutex_init(jn_mutex_t* m) {
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(m, &attr);
    pthread_mutexattr_destroy(&attr);
}

static inline int jn_now_realtime(struct timespec* ts) {
    return clock_gettime(CLOCK_REALTIME, ts);
}
static inline int jn_now_monotonic(struct timespec* ts) {
    return clock_gettime(CLOCK_MONOTONIC, ts);
}
static inline int jn_sleep_ns_ns(const struct timespec* req, struct timespec* rem) {
    return nanosleep(req, rem);
}
static inline void jn_yield(void) { sched_yield(); }

extern void* jn_dlsym_default;
#define jn_dlsym_default(name) dlsym(jn_dlsym_default, name)

#endif /* _WIN32 */

#ifndef JN_DEFAULT_STACK_SIZE
#define JN_DEFAULT_STACK_SIZE (1024 * 1024)
#endif

#endif /* JNATIVE_PLATFORM_H */
