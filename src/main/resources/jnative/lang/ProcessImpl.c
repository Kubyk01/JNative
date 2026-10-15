#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>

#include "jnative_runtime.h"

/*
 * java.lang.ProcessImpl — the POSIX fork+exec backend for ProcessBuilder.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * ProcessImpl declares two native methods. Both are called from bytecode
 * that the reachability walk pulls into the compiled image:
 *
 *   forkAndExec — from the private ProcessImpl(...) constructor, which is
 *                 reached from ProcessImpl.start whenever a
 *                 ProcessBuilder is started;
 *
 *   init        — from the class's static initializer, on every path that
 *                 touches the class.
 *
 * The LLVM emitter records a call site for each in the generated IR, so
 * without definitions in the linked executable clang rejects
 * java/lang/ProcessImpl.ll with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_java_lang_ProcessImpl_forkAndExec__I_B_B_BI_BI_B_IZ_I'
 *
 * ============================================================================
 * What this file provides
 * ============================================================================
 *
 * forkAndExec performs the same job the reference JDK's implementation in
 * ProcessImpl_md.c does:
 *
 *   1. Creates pipes for stdin/stdout/stderr wherever the caller passed
 *      -1 in the fds array.
 *   2. Forks a child process.
 *   3. In the child: installs the requested stdio (either the pipe end or
 *      the caller-supplied fd, dup2'd to 0/1/2), changes directory if a
 *      working directory was supplied, and execs the program with the
 *      caller's argv and envp. Any failure before exec is reported by
 *      _exit(127), which the parent observes as a normal exit status.
 *   4. In the parent: closes the child-side ends of each pipe, writes the
 *      parent-side ends back into the fds array, and returns the child's
 *      pid.
 *
 * The three launch modes the caller can request (FORK / POSIX_SPAWN /
 * VFORK) all reduce to plain fork+exec here. The mode argument is
 * ignored; POSIX_SPAWN would have required the jspawnhelper binary,
 * VFORK would have been undefined behaviour given that we modify the
 * child's file descriptors before exec, and plain fork is correct on
 * every platform this runtime targets.
 *
 * ============================================================================
 * fd handling in the child
 * ============================================================================
 *
 * The child inherits the parent's whole fd table at fork time. It must
 * end up with:
 *
 *     fd 0 = the requested stdin source
 *     fd 1 = the requested stdout destination
 *     fd 2 = the requested stderr destination (or a dup of fd 1 when
 *            redirectErrorStream is true)
 *
 * and nothing else open beyond what exec is supposed to see.
 *
 * The straightforward dup2 sequence has two pitfalls:
 *
 *   - if a source fd happens to be 0, 1, or 2 (possible when the parent
 *     itself has one of its standard fds closed), naively dup2'ing it
 *     and then closing "the original" would close the target;
 *
 *   - if two different stdio slots are backed by the same source fd, the
 *     first dup2 rewrites the source out from under the second.
 *
 * Both are avoided by the same technique the reference JDK uses: copy
 * every source fd to a fresh high-numbered fd with fcntl(F_DUPFD, 10)
 * before touching anything, then close all the originals, then dup2 the
 * high-numbered copies into place. dup2 clears FD_CLOEXEC on the target,
 * so the final fd 0/1/2 survive exec; the high-numbered copies are
 * closed explicitly before exec.
 *
 * ============================================================================
 * FD_CLOEXEC on the parent-side pipe ends
 * ============================================================================
 *
 * Every pipe fd the parent creates is marked FD_CLOEXEC immediately.
 * Without this, a subsequent ProcessBuilder.start would inherit the
 * parent-side write end of the previous child's stdin pipe, and the
 * child's read from that pipe would never see EOF because the parent's
 * own fd was still holding the write end open. The mark has no effect
 * on the dup2'd fd 0/1/2 in the child, because dup2 clears the flag.
 */

/* ============================================================================
 *  Small helpers
 * ========================================================================== */

static inline int32_t* java_int_array(void* arr) {
    if (arr == NULL) return NULL;
    return (int32_t*)jnative_array_data(arr);
}

static inline const char* java_byte_array_cstr(void* arr) {
    if (arr == NULL) return NULL;
    return (const char*)jnative_array_data(arr);
}

static inline void close_if_open(int fd) {
    if (fd >= 0) {
        (void)close(fd);
    }
}

/*
 * Child-side file-descriptor setup. Called with the child's copy of the
 * parent's fd table still intact. On any failure, _exit(127) so the
 * parent observes a clean "exec failed" exit status.
 */
static void child_dup_stdio(int32_t in_fd, int32_t out_fd, int32_t err_fd,
                            int32_t redirect_error_stream)
{
    /*
     * Move every source fd we care about to a fresh fd >= 10. Doing this
     * before any dup2 removes every possible collision with the targets
     * 0/1/2 and with each other.
     */
#define SAFE_MIN 10

    int in_hi  = -1;
    int out_hi = -1;
    int err_hi = -1;

    if (in_fd >= 0) {
        if (in_fd == 0) {
            in_hi = 0;              /* already the target */
        } else {
            in_hi = fcntl(in_fd, F_DUPFD, SAFE_MIN);
            if (in_hi < 0) _exit(127);
        }
    }

    if (out_fd >= 0) {
        if (out_fd == 1) {
            out_hi = 1;
        } else {
            out_hi = fcntl(out_fd, F_DUPFD, SAFE_MIN);
            if (out_hi < 0) _exit(127);
        }
    }

    if (!redirect_error_stream && err_fd >= 0) {
        if (err_fd == 2) {
            err_hi = 2;
        } else {
            err_hi = fcntl(err_fd, F_DUPFD, SAFE_MIN);
            if (err_hi < 0) _exit(127);
        }
    }

    /* Close every original fd we already saved a copy of. */
    if (in_fd  >= 0 && in_fd  != in_hi  && in_fd  != 0) close_if_open(in_fd);
    if (out_fd >= 0 && out_fd != out_hi && out_fd != 1) close_if_open(out_fd);
    if (err_fd >= 0 && err_fd != err_hi && err_fd != 2) close_if_open(err_fd);

    /* Install the saved fds into their final positions. */
    if (in_hi > 0) {
        if (dup2(in_hi, 0) < 0) _exit(127);
        close_if_open(in_hi);
    }
    if (out_hi > 1) {
        if (dup2(out_hi, 1) < 0) _exit(127);
        close_if_open(out_hi);
    }
    if (redirect_error_stream) {
        if (dup2(1, 2) < 0) _exit(127);
        if (err_hi > 2) close_if_open(err_hi);
    } else if (err_hi > 2) {
        if (dup2(err_hi, 2) < 0) _exit(127);
        close_if_open(err_hi);
    }

#undef SAFE_MIN
}

/* ============================================================================
 *  forkAndExec
 * ========================================================================== */

/*
 * private native int forkAndExec(int mode,
 *                                byte[] helperpath,
 *                                byte[] prog,
 *                                byte[] argBlock, int argc,
 *                                byte[] envBlock, int envc,
 *                                byte[] dir,
 *                                int[] fds,
 *                                boolean redirectErrorStream)
 *     throws IOException;
 *
 * See the file header for the overall design. The parameters are:
 *
 *   mode                  launch mechanism selector; ignored here
 *   helperpath            jspawnhelper path; only meaningful for POSIX_SPAWN
 *   prog                  NUL-terminated program path
 *   argBlock              argc NUL-terminated argument strings concatenated
 *   argc                  number of argument strings in argBlock
 *   envBlock              envc NUL-terminated name=value strings, or null
 *                         to inherit the parent's environment
 *   envc                  number of entries in envBlock
 *   dir                   NUL-terminated working directory, or null
 *   fds                   int[3] of {stdin, stdout, stderr}; -1 on input
 *                         means "create a pipe", >= 0 means "use this fd";
 *                         on output, pipe slots carry the parent-side fd
 *                         and non-pipe slots are set to -1
 *   redirectErrorStream   when true, the child's stderr is dup2'd to fd 1
 *
 * Returns the child's pid on success, or throws IOException.
 */
int32_t __jnative_fn_java_lang_ProcessImpl_forkAndExec__I_B_B_BI_BI_B_IZ_I(
        void* this_proc,
        int32_t mode,
        void* helperpath,
        void* prog,
        void* argBlock,
        int32_t argc,
        void* envBlock,
        int32_t envc,
        void* dir,
        void* fds_arr,
        int32_t redirect_error_stream)
{
    (void)this_proc;
    (void)mode;
    (void)helperpath;

    if (prog == NULL || fds_arr == NULL) {
        __jnative_throw_null_pointer_exception();
        return -1;
    }

    int32_t* fds = java_int_array(fds_arr);
    if (fds == NULL) {
        __jnative_throw_null_pointer_exception();
        return -1;
    }

    /* Capture the caller-supplied values before we overwrite them. */
    const int32_t in_orig  = fds[0];
    const int32_t out_orig = fds[1];
    const int32_t err_orig = fds[2];

    /* Pipe ends, -1 when the corresponding stdio slot is not a pipe. */
    int stdin_pipe[2]  = { -1, -1 };
    int stdout_pipe[2] = { -1, -1 };
    int stderr_pipe[2] = { -1, -1 };

    if (in_orig == -1) {
        if (pipe(stdin_pipe) < 0) {
            __jnative_throw_exception(NULL);
            return -1;
        }
    }
    if (out_orig == -1) {
        if (pipe(stdout_pipe) < 0) {
            close_if_open(stdin_pipe[0]);
            close_if_open(stdin_pipe[1]);
            __jnative_throw_exception(NULL);
            return -1;
        }
    }
    if (err_orig == -1 && !redirect_error_stream) {
        if (pipe(stderr_pipe) < 0) {
            close_if_open(stdin_pipe[0]);
            close_if_open(stdin_pipe[1]);
            close_if_open(stdout_pipe[0]);
            close_if_open(stdout_pipe[1]);
            __jnative_throw_exception(NULL);
            return -1;
        }
    }

    /*
     * Mark every pipe fd FD_CLOEXEC. The child's own copies go through
     * F_DUPFD (which preserves the flag) and then dup2 (which clears it),
     * so the final fd 0/1/2 in the child survive exec; the parent's ends
     * stay marked and are therefore invisible to any later fork+exec.
     */
    if (stdin_pipe[0] >= 0) {
        (void)fcntl(stdin_pipe[0],  F_SETFD, FD_CLOEXEC);
        (void)fcntl(stdin_pipe[1],  F_SETFD, FD_CLOEXEC);
    }
    if (stdout_pipe[0] >= 0) {
        (void)fcntl(stdout_pipe[0], F_SETFD, FD_CLOEXEC);
        (void)fcntl(stdout_pipe[1], F_SETFD, FD_CLOEXEC);
    }
    if (stderr_pipe[0] >= 0) {
        (void)fcntl(stderr_pipe[0], F_SETFD, FD_CLOEXEC);
        (void)fcntl(stderr_pipe[1], F_SETFD, FD_CLOEXEC);
    }

    /* Flush every stdio buffer so the child does not inherit pending
     * buffered output and emit it twice. */
    fflush(NULL);

    pid_t child = fork();

    if (child < 0) {
        close_if_open(stdin_pipe[0]);
        close_if_open(stdin_pipe[1]);
        close_if_open(stdout_pipe[0]);
        close_if_open(stdout_pipe[1]);
        close_if_open(stderr_pipe[0]);
        close_if_open(stderr_pipe[1]);
        __jnative_throw_exception(NULL);
        return -1;
    }

    if (child == 0) {
        /* --------------------------------------------------------------
         * Child.
         * -------------------------------------------------------------- */

        /* Resolve the source fd for each stdio slot. When a pipe was
         * created, the child uses the "other" end from the parent. */
        int32_t in_src  = (in_orig  == -1) ? stdin_pipe[0]  : in_orig;
        int32_t out_src = (out_orig == -1) ? stdout_pipe[1] : out_orig;
        int32_t err_src;
        if (redirect_error_stream) {
            err_src = -1;           /* will be dup2(1, 2) */
        } else if (err_orig == -1) {
            err_src = stderr_pipe[1];
        } else {
            err_src = err_orig;
        }

        /* Close every pipe end we are not going to use directly in the
         * child. Doing this before the dup2 dance keeps the child's fd
         * table as small as possible across exec. */
        if (in_orig == -1) {
            close_if_open(stdin_pipe[1]);   /* parent's write end */
        } else {
            close_if_open(stdin_pipe[0]);
            close_if_open(stdin_pipe[1]);
        }
        if (out_orig == -1) {
            close_if_open(stdout_pipe[0]);  /* parent's read end */
        } else {
            close_if_open(stdout_pipe[0]);
            close_if_open(stdout_pipe[1]);
        }
        if (redirect_error_stream) {
            close_if_open(stderr_pipe[0]);
            close_if_open(stderr_pipe[1]);
        } else if (err_orig == -1) {
            close_if_open(stderr_pipe[0]);
        } else {
            close_if_open(stderr_pipe[0]);
            close_if_open(stderr_pipe[1]);
        }

        /* Install the requested stdio. */
        child_dup_stdio(in_src, out_src, err_src, redirect_error_stream);

        /* Working directory. `dir` is a NUL-terminated byte[]; a length
         * of 1 (just the terminator) means the caller asked for "" which
         * is not a valid chdir argument and is treated as "no chdir". */
        if (dir != NULL) {
            const char* d = java_byte_array_cstr(dir);
            int32_t dlen = jnative_array_length(dir);
            if (d != NULL && dlen > 1 && d[0] != '\0') {
                if (chdir(d) < 0) {
                    _exit(127);
                }
            }
        }

        /* Build argv. */
        const char* prog_str = java_byte_array_cstr(prog);
        if (prog_str == NULL) _exit(127);

        char** argv = (char**)malloc(sizeof(char*) * ((size_t)argc + 2));
        if (argv == NULL) _exit(127);

        argv[0] = (char*)prog_str;

        if (argc > 0 && argBlock != NULL) {
            const char* p = java_byte_array_cstr(argBlock);
            for (int32_t i = 0; i < argc; i++) {
                if (p == NULL) _exit(127);
                argv[i + 1] = (char*)p;
                p += strlen(p) + 1;
            }
        }
        argv[argc + 1] = NULL;

        /* Build envp, or leave it NULL to inherit. */
        char** envp = NULL;
        if (envBlock != NULL) {
            envp = (char**)malloc(sizeof(char*) * ((size_t)envc + 1));
            if (envp == NULL) _exit(127);

            if (envc > 0) {
                const char* ep = java_byte_array_cstr(envBlock);
                for (int32_t i = 0; i < envc; i++) {
                    if (ep == NULL) _exit(127);
                    envp[i] = (char*)ep;
                    ep += strlen(ep) + 1;
                }
            }
            envp[envc] = NULL;
        }

        if (envp != NULL) {
            execve(prog_str, argv, envp);
        } else {
            execv(prog_str, argv);
        }

        /* exec failed; tell the parent with the conventional 127. */
        _exit(127);
    }

    /* --------------------------------------------------------------
     * Parent.
     * -------------------------------------------------------------- */

    /*
     * For each stdio slot that the caller asked us to pipe, close the
     * child-side end and write the parent-side end back into fds[].
     * For every other slot, set fds[i] to -1 so the Java-level
     * initStreams picks the corresponding Null*Stream.
     */
    if (in_orig == -1) {
        close_if_open(stdin_pipe[0]);
        fds[0] = stdin_pipe[1];
    } else {
        fds[0] = -1;
    }

    if (out_orig == -1) {
        close_if_open(stdout_pipe[1]);
        fds[1] = stdout_pipe[0];
    } else {
        fds[1] = -1;
    }

    if (redirect_error_stream) {
        fds[2] = -1;
    } else if (err_orig == -1) {
        close_if_open(stderr_pipe[1]);
        fds[2] = stderr_pipe[0];
    } else {
        fds[2] = -1;
    }

    return (int32_t)child;
}

/* ============================================================================
 *  init
 * ========================================================================== */

/*
 * private static native void init();
 *
 * Called from ProcessImpl.<clinit>. On HotSpot this hook resolves the
 * native method IDs for the rest of the class's natives and caches
 * them. This runtime resolves every native through its statically-linked
 * __jnative_fn_* symbol, so there is nothing to install. The symbol
 * must nevertheless exist because the class's static initializer emits
 * a native call to it.
 *
 * This is a STATIC native: no receiver argument.
 */
void __jnative_fn_java_lang_ProcessImpl_init___V(void) {
}