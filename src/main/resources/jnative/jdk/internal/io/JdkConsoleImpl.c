/*
 * jdk.internal.io.JdkConsoleImpl — the native support behind
 * java.io.Console's password-prompt machinery.
 *
 * The class declares a single native:
 *
 *   private static native boolean echo(boolean on);
 *
 * and calls it from two places:
 *
 *   - JdkConsoleImpl$1.run(), the privileged action that wraps a
 *     password read, to disable terminal echo before reading the
 *     password and to restore it afterwards;
 *
 *   - JdkConsoleImpl.readPassword(String, Object...), which brackets
 *     the read with the same disable/enable pair.
 *
 * The return value is the *previous* state of terminal echo, so the
 * caller can restore exactly what was there before it intervened. A
 * caller that finds echo already disabled (because a previous
 * invocation was interrupted mid-prompt, for instance) leaves it
 * disabled rather than force-enabling it — that is what the reference
 * implementation does and what the contract of the native promises.
 *
 * ------------------------------------------------------------------------
 * Platform implementations
 * ------------------------------------------------------------------------
 *
 * POSIX: the flag lives in the termios structure associated with the
 * controlling terminal. The reference implementation reads it with
 * tcgetattr(3), toggles the ECHO bit in c_lflag, writes the modified
 * structure back with tcsetattr(3), and returns the bit's previous
 * value.
 *
 * Windows: the flag lives in the console input mode bitmask of the
 * standard input handle. GetConsoleMode returns the current mode; the
 * ENABLE_ECHO_INPUT bit is the equivalent of POSIX's ECHO;
 * SetConsoleMode applies the modified mode. This is exactly the API
 * the reference JVM's src/java.base/windows/native/libjava/Console_md.c
 * uses for the same native, so the behaviour matches the reference
 * implementation byte for byte on Windows.
 *
 * The two implementations are mutually exclusive and each is compiled
 * only for its own target; there is no shared fallback and no branch
 * that silently does nothing on one platform. That is required because
 * the `struct termios` type, the `tcgetattr` / `tcsetattr` functions,
 * the `ECHO` macro, and the `TCSANOW` constant all belong to
 * <termios.h>, which MinGW does not ship — the previous revision's
 * direct use of them made the whole file fail to compile on Windows
 * with:
 *
 *     error: variable has incomplete type 'struct termios'
 *     error: call to undeclared function 'tcgetattr'
 *     error: use of undeclared identifier 'ECHO'
 *     error: call to undeclared function 'tcsetattr'
 *     error: use of undeclared identifier 'TCSANOW'
 *
 * ------------------------------------------------------------------------
 * Shared non-terminal fallback
 * ------------------------------------------------------------------------
 *
 * On either platform, if the process has no controlling terminal — for
 * example, stdin was redirected from a pipe or a file — the "read the
 * current echo state" primitive fails:
 *
 *     POSIX   : tcgetattr returns ENOTTY
 *     Windows : GetConsoleMode returns ERROR_INVALID_HANDLE
 *
 * There is no echo to toggle in that case. The only honest answer is
 * "echo was in whatever state the caller asked for" — i.e. return the
 * `on` argument. A caller that receives `true` back after asking for
 * `true` will try to restore it to `true` later, which is a no-op on a
 * non-terminal; the same is true for the `false` direction. Returning
 * the argument keeps the caller's bookkeeping self-consistent without
 * inventing a state that has no observable effect.
 *
 * The "write the new state" primitive can also fail after the read
 * succeeded (rare, e.g. a race where the terminal was hung up between
 * the two calls). The terminal state has not been modified in that
 * case, so the previous state is still whatever the read reported.
 * Returning that value is correct: the caller's subsequent restore
 * attempt will also be a no-op, and the terminal is left in the state
 * the user had it in.
 *
 * ------------------------------------------------------------------------
 * TCSANOW vs TCSAFLUSH (POSIX only)
 * ------------------------------------------------------------------------
 *
 * The POSIX branch uses TCSANOW rather than TCSAFLUSH for two reasons:
 *
 *   1. The caller is about to read a password and must not flush any
 *      pending input the user has already typed — flushing would
 *      silently discard keystrokes.
 *
 *   2. Password input is a strictly interactive operation; the small
 *      window between the tcsetattr call and the subsequent read is
 *      not one in which any other thread could meaningfully race, and
 *      the JDK's own implementation uses TCSANOW for the same reasons.
 *
 * The Windows API has no equivalent distinction — SetConsoleMode
 * takes effect immediately and never flushes the input buffer — so the
 * Windows branch needs no corresponding choice.
 */

#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <errno.h>

#include "jnative_runtime.h"

#if defined(_WIN32)
#  include <windows.h>
#else
#  include <termios.h>
#  include <unistd.h>
#endif

int32_t __jnative_fn_jdk_internal_io_JdkConsoleImpl_echo__Z_Z(int32_t on) {
#if defined(_WIN32)

    /*
     * Windows: read, flip, write the ENABLE_ECHO_INPUT bit on the
     * standard input handle's console mode.
     *
     * GetStdHandle can fail in two ways:
     *   - INVALID_HANDLE_VALUE — the process has no standard input
     *     handle at all (detached service, closed stdio);
     *   - NULL — the process has a standard input slot but it is not
     *     associated with an input handle (rare, mainly during early
     *     start-up before the CRT has fully initialised).
     * Both mean "there is no terminal to configure", so both fall back
     * to returning the requested state, matching the POSIX fallback
     * behaviour when tcgetattr reports ENOTTY.
     */
    HANDLE h = GetStdHandle(STD_INPUT_HANDLE);
    if (h == INVALID_HANDLE_VALUE || h == NULL) {
        return on ? 1 : 0;
    }

    /*
     * GetConsoleMode fails with ERROR_INVALID_HANDLE when the handle
     * exists but is not a console input handle — this is the exact
     * analogue of POSIX's ENOTTY on tcgetattr. Returning the requested
     * state keeps the caller's bookkeeping consistent.
     */
    DWORD mode;
    if (!GetConsoleMode(h, &mode)) {
        return on ? 1 : 0;
    }

    int previous_on = (mode & ENABLE_ECHO_INPUT) ? 1 : 0;

    DWORD newmode = on
        ? (mode |  ENABLE_ECHO_INPUT)
        : (mode & ~ENABLE_ECHO_INPUT);

    /*
     * SetConsoleMode can fail for reasons that leave the current mode
     * untouched (the handle was closed between the two calls, or the
     * console was detached). As on POSIX, the read state is still the
     * correct answer to return in that case, because the terminal was
     * not modified and the caller's subsequent restore will also be a
     * no-op.
     */
    (void)SetConsoleMode(h, newmode);

    return previous_on;

#else /* !_WIN32 — POSIX */

    int fd = STDIN_FILENO;

    struct termios tty;
    if (tcgetattr(fd, &tty) != 0) {
        /*
         * No controlling terminal (ENOTTY) or another I/O error on
         * stdin. There is no echo state to observe, so return the
         * requested state — see the "Shared non-terminal fallback"
         * comment above for the full reasoning.
         */
        return on ? 1 : 0;
    }

    int previous_on = (tty.c_lflag & ECHO) ? 1 : 0;

    if (on) {
        tty.c_lflag |= ECHO;
    } else {
        tty.c_lflag &= ~ECHO;
    }

    /*
     * TCSANOW, not TCSAFLUSH: the caller is about to read a password
     * and must not discard keystrokes the user has already typed. See
     * the "TCSANOW vs TCSAFLUSH" comment above.
     */
    (void)tcsetattr(fd, TCSANOW, &tty);

    return previous_on;

#endif
}