#include "jnative_platform.h"
#include <stdint.h>

#include "jnative_runtime.h"

#if defined(_WIN32)
#include <windows.h>
#else
#include <errno.h>
#include <signal.h>
#include <unistd.h>
#endif

void __jnative_fn_java_lang_ProcessHandleImpl_initNative___V(void) {
}

int64_t __jnative_fn_java_lang_ProcessHandleImpl_getCurrentPid0___J(void) {
#if defined(_WIN32)
    return (int64_t)GetCurrentProcessId();
#else
    return (int64_t)getpid();
#endif
}

int64_t __jnative_fn_java_lang_ProcessHandleImpl_isAlive0__J_J(int64_t pid) {
#if defined(_WIN32)
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, (DWORD)pid);
    if (h == NULL) return -1;
    DWORD code = 0;
    BOOL ok = GetExitCodeProcess(h, &code);
    CloseHandle(h);
    return (ok && code == STILL_ACTIVE) ? 0 : -1;
#else
    if (pid <= 0) return -1;
    if (kill((pid_t)pid, 0) == 0 || errno == EPERM) return 0;
    return -1;
#endif
}
