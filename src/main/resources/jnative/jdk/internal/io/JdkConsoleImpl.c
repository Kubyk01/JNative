#define _GNU_SOURCE
#include <stdint.h>
#include <unistd.h>
#include <termios.h>
#include <errno.h>

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
 * On a Unix platform the flag lives in the termios structure
 * associated with the controlling terminal. The reference
 * implementation reads it with tcgetattr(3), toggles the ECHO bit,
 * writes the modified structure back with tcsetattr(3), and returns
 * the bit's previous value.
 *
 * Two cases need explicit handling:
 *
 *   1. The process has no controlling terminal, or stdin has been
 *      redirected away from one. tcgetattr fails with ENOTTY. There is
 *      no echo to toggle in that case, and the only honest answer is
 *      "echo was in whatever state the caller asked for" — i.e. return
 *      the `on` argument. A caller that receives `true` back after
 *      asking for `true` will try to restore it to `true` later, which
 *      is a no-op on a non-terminal; the same is true for the `false`
 *      direction. Returning the argument keeps the caller's
 *      bookkeeping self-consistent without inventing a state that has
 *      no observable effect.
 *
 *   2. tcsetattr fails after tcgetattr succeeded. The terminal state
 *      has not been modified, so the previous state is still whatever
 *      tcgetattr reported. Returning that value is correct: the
 *      caller's subsequent restore attempt will also be a no-op, and
 *      the terminal is left in the state the user had it in.
 *
 * TCSANOW is used rather than TCSAFLUSH for two reasons. First, the
 * caller is about to read a password and must not flush any pending
 * input the user has already typed — flushing would silently discard
 * keystrokes. Second, password input is a strictly interactive
 * operation; the small window between the tcsetattr call and the
 * subsequent read is not one in which any other thread could
 * meaningfully race, and the JDK's own implementation uses TCSANOW for
 * the same reasons.
 */

int32_t __jnative_fn_jdk_internal_io_JdkConsoleImpl_echo__Z_Z(int32_t on) {
    int fd = STDIN_FILENO;

    struct termios tty;
    if (tcgetattr(fd, &tty) != 0) {
        return on ? 1 : 0;
    }

    int previous_on = (tty.c_lflag & ECHO) ? 1 : 0;

    if (on) {
        tty.c_lflag |= ECHO;
    } else {
        tty.c_lflag &= ~ECHO;
    }

    (void)tcsetattr(fd, TCSANOW, &tty);

    return previous_on;
}