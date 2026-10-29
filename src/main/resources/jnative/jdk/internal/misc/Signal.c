#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <signal.h>
#include <errno.h>

#include "jnative_runtime.h"

/*
 * jdk.internal.misc.Signal — the VM-level entry points that back
 * sun.misc.Signal and jdk.internal.misc.Signal.
 *
 * Three natives:
 *
 *   findSignal0(String name)   -- "INT" -> 2, "TERM" -> 15, …
 *   handle0(int sig, long h)   -- install/replace/restore a handler
 *   raise0(int sig)            -- raise(sig)
 *
 * handle0 is the only one with nontrivial semantics in HotSpot, where
 * it interacts with the JVM's own signal-dispatch table: the JVM
 * installs a single native handler for every signal that a user
 * program might want to observe, and dispatches to whatever Java-side
 * handler was most recently installed through this hook. This runtime
 * does not have a JVM-side signal dispatcher — its own SIGSEGV/SIGBUS/
 * SIGFPE/SIGILL handlers are installed once at program start-up by
 * jnative_runtime.c and are not exposed to the Java layer — so
 * handle0's only honest answer is "there was no previous Java
 * handler", which is the sentinel `0`.
 *
 * Every Java caller of handle0 (Signal.handle, Signal.handle0,
 * Signal.handle1, and the `sun.misc.Signal` wrapper that predates
 * them) treats a `-1` return as failure and raises
 * IllegalArgumentException; any other value is stored back into the
 * Signal instance's `handler` field as the "previous handler" so a
 * subsequent call can restore it. Returning `0` matches the "handler
 * was previously SIG_DFL and no Java-level handler had been installed"
 * state, which is exactly the state this runtime is in for every
 * signal at every moment.
 *
 * ------------------------------------------------------------------------
 * Signal number table
 * ------------------------------------------------------------------------
 *
 * The table below maps the part of a signal name after the "SIG" prefix
 * ("INT", "TERM", "USR1", …) to a numeric signal identifier. The Java
 * layer strips "SIG" itself, then hands the remainder to findSignal0.
 *
 * The two branches differ because Windows does not implement the POSIX
 * signal model:
 *
 *   - POSIX: the full set of signals is available in <signal.h>, and
 *     findSignal0 returns the real kernel number for each name.
 *
 *   - Windows: MinGW's <signal.h> declares only the six C-standard
 *     signals plus SIGBREAK. HotSpot's os_windows.cpp still exposes
 *     HUP, PIPE, QUIT, USR1 and USR2 through the same native by
 *     declaring those constants locally, so that user code that calls
 *     `new Signal("USR1")` succeeds on Windows as well as on Linux.
 *     This file does the same, with one addition: raise0 filters
 *     against the CRT-supported set before calling raise(), so
 *     raising a POSIX-only number on Windows is a no-op instead of
 *     tripping the CRT's invalid-parameter handler.
 */

/* ========================================================================
 *  Signal table (file scope, used by findSignal0 and raise0)
 * ====================================================================== */

typedef struct {
    const char* name;
    int         number;
} SignalEntry;

#if defined(_WIN32)

/*
 * Windows: the signals the CRT understands, plus the POSIX names that
 * HotSpot exposes on Windows by local declaration.
 *
 * The values for SIGHUP/SIGQUIT/SIGPIPE/SIGUSR1/SIGUSR2 match Linux so
 * that the mapping between a Java-side Signal name and its numeric
 * identity is the same across all platforms this runtime supports.
 * They have no OS-level meaning on Windows — only findSignal0 and
 * handle0 (a no-op) ever read them.
 */
#ifndef SIGHUP
#define SIGHUP  1
#endif
#ifndef SIGQUIT
#define SIGQUIT 3
#endif
#ifndef SIGPIPE
#define SIGPIPE 13
#endif
#ifndef SIGUSR1
#define SIGUSR1 16
#endif
#ifndef SIGUSR2
#define SIGUSR2 17
#endif

static const SignalEntry signal_table[] = {
    { "ABRT",  SIGABRT  },
#ifdef SIGBREAK
    { "BREAK", SIGBREAK },
#endif
    { "FPE",   SIGFPE   },
    { "HUP",   SIGHUP   },
    { "ILL",   SIGILL   },
    { "INT",   SIGINT   },
    { "PIPE",  SIGPIPE  },
    { "QUIT",  SIGQUIT  },
    { "SEGV",  SIGSEGV  },
    { "TERM",  SIGTERM  },
    { "USR1",  SIGUSR1  },
    { "USR2",  SIGUSR2  },
};

#else

/*
 * POSIX: the full set of signals the JDK's Signal API exposes. The
 * values come straight from <signal.h>.
 *
 * SIGIOT and SIGPWR are guarded because they are Linux-only: neither
 * is defined on macOS, the BSDs, or any other strict POSIX platform
 * this runtime targets. Skipping them on those platforms matches what
 * HotSpot's src/hotspot/os/posix/os_posix.cpp does — the entry simply
 * does not exist in the table, so findSignal0 returns -1 for it and
 * the Java layer raises IllegalArgumentException as documented for an
 * unsupported signal name.
 */
static const SignalEntry signal_table[] = {
    { "ABRT", SIGABRT },
    { "ALRM", SIGALRM },
    { "BUS",  SIGBUS  },
    { "CHLD", SIGCHLD },
    { "CONT", SIGCONT },
    { "FPE",  SIGFPE  },
    { "HUP",  SIGHUP  },
    { "ILL",  SIGILL  },
    { "INT",  SIGINT  },
    { "IO",   SIGIO   },
#ifdef SIGIOT
    { "IOT",  SIGIOT  },
#endif
    { "KILL", SIGKILL },
    { "PIPE", SIGPIPE },
    { "PROF", SIGPROF },
#ifdef SIGPWR
    { "PWR",  SIGPWR  },
#endif
    { "QUIT", SIGQUIT },
    { "SEGV", SIGSEGV },
    { "STOP", SIGSTOP },
    { "SYS",  SIGSYS  },
    { "TERM", SIGTERM },
    { "TRAP", SIGTRAP },
    { "TSTP", SIGTSTP },
    { "TTIN", SIGTTIN },
    { "TTOU", SIGTTOU },
    { "URG",  SIGURG  },
    { "USR1", SIGUSR1 },
    { "USR2", SIGUSR2 },
    { "VTALRM", SIGVTALRM },
    { "WINCH", SIGWINCH },
    { "XCPU", SIGXCPU },
    { "XFSZ", SIGXFSZ },
};

#endif

#define SIGNAL_TABLE_LEN (sizeof(signal_table) / sizeof(signal_table[0]))

/* ========================================================================
 *  Native entry points
 * ====================================================================== */

/*
 * int findSignal0(String sigName);
 *
 * Maps a signal name to its number. The input is the part of the name
 * after the "SIG" prefix — "INT", "TERM", "HUP", "QUIT", "USR1", …
 * — because the Java layer strips that prefix itself before invoking
 * this hook.
 *
 * A null argument or an unrecognised name returns -1, which the Java
 * caller turns into IllegalArgumentException with the signal name
 * attached. That is the same behaviour the reference implementation
 * exhibits for an unknown name — including, on Windows, every POSIX-
 * only name that MinGW does not define.
 */
int32_t __jnative_fn_jdk_internal_misc_Signal_findSignal0__Ljava_lang_String__I(
        void* name_str)
{
    if (name_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return -1;
    }

    int32_t len = 0;
    const char* name = __jnative_read_string_bytes(name_str, &len);
    if (name == NULL || len <= 0) {
        return -1;
    }

    for (size_t i = 0; i < SIGNAL_TABLE_LEN; i++) {
        size_t tlen = strlen(signal_table[i].name);
        if ((int32_t)tlen == len
            && memcmp(signal_table[i].name, name, tlen) == 0) {
            return (int32_t)signal_table[i].number;
        }
    }
    return -1;
}

/*
 * long handle0(int sig, long nativeH);
 *
 * Installs, replaces, or restores a Java-level signal handler. The
 * `nativeH` argument is the address that the Java layer has decided to
 * associate with the signal: on the current JDK it is a handle that
 * the Java code obtained by reflecting on its own method pointers, and
 * the reference VM stores it in a table for its native dispatcher to
 * look up when the signal fires.
 *
 * This runtime has no such table and no JVM-side dispatcher. Its own
 * SIGSEGV / SIGBUS / SIGFPE / SIGILL handlers are installed once at
 * program start-up by jnative_runtime.c with the sole purpose of
 * producing a readable crash trace; those handlers are never exposed
 * through the Java Signal API, and the signals that user code
 * typically wants to observe (INT, TERM, HUP, USR1) are not handled
 * by the runtime at all — they retain whatever the process inherited
 * from the parent, which is the kernel default for a freshly-exec'd
 * binary.
 *
 * The correct answer for "what was the previous handler?" is therefore
 * `0`, the sentinel the Java layer interprets as "SIG_DFL, no Java
 * handler installed". Returning `-1` would make every call to
 * Signal.handle throw IllegalArgumentException; returning any other
 * value would make a later restore attempt to install a bogus address.
 * `0` is both truthful and safe.
 *
 * Both arguments are ignored. The syscall that would normally perform
 * the installation (sigaction with a JVM-internal trampoline) is not
 * run, so the process's actual signal dispositions are unchanged.
 */
int64_t __jnative_fn_jdk_internal_misc_Signal_handle0__IJ_J(
        int32_t sig, int64_t nativeH)
{
    (void)sig;
    (void)nativeH;
    return (int64_t)0;
}

/*
 * void raise0(int sig);
 *
 * Delivers a signal to the calling thread or process. This is a thin
 * wrapper over raise(3) and is the one Signal native whose semantics
 * are identical to its C counterpart — unlike handle0 and findSignal0,
 * there is no JVM-side dispatch table that could change the meaning.
 *
 * The Java-side caller wraps this in a try/catch that turns the
 * IllegalArgumentException of a bad signal number into a checked path,
 * and lets the signal's own delivery take its course otherwise. No
 * throw is emitted here: a failure of raise(3) (an invalid signal
 * number, EINVAL) surfaces the same way any other call to raise would,
 * which is what the Java API documents.
 *
 * On Windows, raise() only understands the six C-standard signals plus
 * SIGBREAK. Calling it with any other value — including the POSIX-only
 * constants that findSignal0 returns on Windows so that `new
 * Signal("USR1")` succeeds — invokes the CRT's invalid parameter
 * handler, which by default aborts the process. To preserve the
 * "no-op for signals that have no Windows equivalent" behaviour this
 * runtime documents, raise0 filters its argument against the
 * CRT-supported set before handing it to raise(). A POSIX-only number
 * is silently discarded; a C-standard number is delivered exactly as
 * HotSpot would deliver it.
 */
void __jnative_fn_jdk_internal_misc_Signal_raise0__I_V(int32_t sig)
{
    if (sig <= 0) {
        return;
    }

#if defined(_WIN32)
    switch (sig) {
        case SIGABRT:
        case SIGFPE:
        case SIGILL:
        case SIGINT:
        case SIGSEGV:
        case SIGTERM:
#ifdef SIGBREAK
        case SIGBREAK:
#endif
            break;
        default:
            /* POSIX-only signal number on Windows: no-op. */
            return;
    }
#endif

    (void)raise((int)sig);
}