#define _GNU_SOURCE
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
 * handle0 is the only one with nontrivial semantics. In the reference
 * VM it interacts with HotSpot's own signal-dispatch table: the JVM
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
 */

/* -------------------------------------------------------------------------
 * private static native int findSignal0(String sigName);
 *
 * Maps a signal name to its number. The input is the part of the name
 * after the "SIG" prefix — "INT", "TERM", "HUP", "QUIT", "USR1", …
 * — because the Java layer strips that prefix itself before invoking
 * this hook.
 *
 * because several of those constants are not guaranteed to exist on
 * every platform. The numeric values below are the ones every POSIX
 * system assigns to the standard signals, which is what the Java API
 * publishes in its own documentation and what user code that reads
 * `signal.getNumber()` expects to see.
 *
 * A null argument or an unrecognised name returns -1, which the Java
 * caller turns into IllegalArgumentException with the signal name
 * attached. That is the same behaviour the reference implementation
 * exhibits for an unknown name.
 * ----------------------------------------------------------------------- */
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

    /* Case-sensitive match, matching the reference implementation. */
    struct { const char* name; int num; } table[] = {
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
        { "IOT",  SIGIOT  },
        { "KILL", SIGKILL },
        { "PIPE", SIGPIPE },
        { "PROF", SIGPROF },
        { "PWR",  SIGPWR  },
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

    for (size_t i = 0; i < sizeof(table) / sizeof(table[0]); i++) {
        size_t tlen = strlen(table[i].name);
        if ((int32_t)tlen == len && memcmp(table[i].name, name, tlen) == 0) {
            return (int32_t)table[i].num;
        }
    }
    return -1;
}

/* -------------------------------------------------------------------------
 * private static native long handle0(int sig, long nativeH);
 *
 * Installs, replaces, or restores a Java-level signal handler. The
 * `nativeH` argument is the address that the Java layer has decided to
 * associate with the signal: on the current JDK it is a handle that the
 * Java code obtained by reflecting on its own method pointers, and the
 * reference VM stores it in a table for its native dispatcher to look
 * up when the signal fires.
 *
 * This runtime has no such table and no JVM-side dispatcher. Its own
 * SIGSEGV / SIGBUS / SIGFPE / SIGILL handlers are installed once at
 * program start-up by jnative_runtime.c with the sole purpose of
 * producing a readable crash trace; those handlers are never exposed
 * through the Java Signal API, and the signals that user code typically
 * wants to observe (INT, TERM, HUP, USR1) are not handled by the
 * runtime at all — they retain whatever the process inherited from the
 * parent, which is the kernel default for a freshly-exec'd binary.
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
 * ----------------------------------------------------------------------- */
int64_t __jnative_fn_jdk_internal_misc_Signal_handle0__IJ_J(
        int32_t sig, int64_t nativeH)
{
    (void)sig;
    (void)nativeH;
    return (int64_t)0;
}

/* -------------------------------------------------------------------------
 * static native void raise0(int sig);
 *
 * Delivers a signal to the calling thread or process. This is a thin
 * wrapper over raise(3) and is the one Signal native whose semantics
 * are identical to its C counterpart — unlike handle0 and findSignal0,
 * there is no JVM-side dispatch table that could change the meaning.
 *
 * The Java-side caller wraps this in a try/catch that turns the
 * IllegalArgumentException of a bad signal number into a checked
 * path, and lets the signal's own delivery take its course otherwise.
 * No throw is emitted here: a failure of raise(3) (an invalid signal
 * number, EINVAL) surfaces the same way any other call to raise would,
 * which is what the Java API documents.
 * ----------------------------------------------------------------------- */
void __jnative_fn_jdk_internal_misc_Signal_raise0__I_V(int32_t sig)
{
    if (sig <= 0) {
        return;
    }
    (void)raise((int)sig);
}