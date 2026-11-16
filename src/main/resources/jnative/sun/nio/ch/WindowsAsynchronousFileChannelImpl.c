#if defined(_WIN32)

#ifndef WIN32_LEAN_AND_MEAN
#  define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <stdint.h>
#include <stdio.h>

#include "jnative_runtime.h"

#define WAFC_IOS_EOF         (-1)
#define WAFC_IOS_UNAVAILABLE (-2)

JNATIVE_NORETURN static void wafc_throw_last_error(const char* what) {
    DWORD error = GetLastError();
    char msg[512];
    wchar_t wbuf[256];
    DWORD n = FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                             NULL, error, 0, wbuf, (DWORD)(sizeof(wbuf) / sizeof(wbuf[0])), NULL);
    while (n > 0 && (wbuf[n - 1] == L'\r' || wbuf[n - 1] == L'\n' || wbuf[n - 1] == L' ' || wbuf[n - 1] == L'.')) {
        wbuf[--n] = L'\0';
    }
    if (n > 0 && WideCharToMultiByte(CP_UTF8, 0, wbuf, (int)n + 1, msg, (int)sizeof(msg), NULL, NULL) > 0) {
        __jnative_throw_exception(__jnative_construct_exception("vtable_java_io_IOException", msg));
    }
    snprintf(msg, sizeof(msg), "%s (error %lu)", what, (unsigned long)error);
    __jnative_throw_exception(__jnative_construct_exception("vtable_java_io_IOException", msg));
}

static OVERLAPPED* wafc_overlapped(int64_t ov, int64_t offset) {
    OVERLAPPED* o = (OVERLAPPED*)(intptr_t)ov;
    o->Offset = (DWORD)offset;
    o->OffsetHigh = (DWORD)((uint64_t)offset >> 32);
    o->hEvent = NULL;
    return o;
}

int32_t __jnative_fn_sun_nio_ch_WindowsAsynchronousFileChannelImpl_readFile__JJIJJ_I(
        int64_t handle, int64_t address, int32_t len, int64_t offset, int64_t ov) {
    OVERLAPPED* o = wafc_overlapped(ov, offset);
    if (!ReadFile((HANDLE)(intptr_t)handle, (LPVOID)(intptr_t)address, (DWORD)len, NULL, o)) {
        DWORD error = GetLastError();
        if (error == ERROR_IO_PENDING) return WAFC_IOS_UNAVAILABLE;
        if (error == ERROR_HANDLE_EOF) return WAFC_IOS_EOF;
        wafc_throw_last_error("ReadFile failed");
    }
    return WAFC_IOS_UNAVAILABLE;
}

int32_t __jnative_fn_sun_nio_ch_WindowsAsynchronousFileChannelImpl_writeFile__JJIJJ_I(
        int64_t handle, int64_t address, int32_t len, int64_t offset, int64_t ov) {
    OVERLAPPED* o = wafc_overlapped(ov, offset);
    if (!WriteFile((HANDLE)(intptr_t)handle, (LPCVOID)(intptr_t)address, (DWORD)len, NULL, o)) {
        DWORD error = GetLastError();
        if (error == ERROR_IO_PENDING) return WAFC_IOS_UNAVAILABLE;
        wafc_throw_last_error("WriteFile failed");
    }
    return WAFC_IOS_UNAVAILABLE;
}

int32_t __jnative_fn_sun_nio_ch_WindowsAsynchronousFileChannelImpl_lockFile__JJJZJ_I(
        int64_t handle, int64_t position, int64_t size, int32_t shared, int64_t ov) {
    OVERLAPPED* o = wafc_overlapped(ov, position);
    DWORD flags = (shared & 1) ? 0 : LOCKFILE_EXCLUSIVE_LOCK;
    if (!LockFileEx((HANDLE)(intptr_t)handle, flags, 0,
                    (DWORD)size, (DWORD)((uint64_t)size >> 32), o)) {
        DWORD error = GetLastError();
        if (error == ERROR_IO_PENDING) return WAFC_IOS_UNAVAILABLE;
        wafc_throw_last_error("LockFile failed");
    }
    return WAFC_IOS_UNAVAILABLE;
}

#endif
