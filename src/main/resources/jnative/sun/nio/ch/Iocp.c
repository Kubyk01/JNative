#if defined(_WIN32)

#ifndef WIN32_LEAN_AND_MEAN
#  define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "jnative_runtime.h"

typedef struct {
    const char* name;
    int32_t     offset;
} IocpField;

static IocpField iocp_cs_error       = { "error",            -1 };
static IocpField iocp_cs_transferred = { "bytesTransferred", -1 };
static IocpField iocp_cs_key         = { "completionKey",    -1 };
static IocpField iocp_cs_overlapped  = { "overlapped",       -1 };

static int32_t iocp_resolve_field(const char* name) {
    for (ReflectionClass* c = jnative_class_by_name("sun/nio/ch/Iocp$CompletionStatus");
         c != NULL; c = c->superclass) {
        if (c->fields == NULL) continue;
        for (ReflectionField** fp = c->fields; *fp != NULL; fp++) {
            if ((*fp)->name != NULL && strcmp((const char*)(*fp)->name, name) == 0) {
                return (*fp)->offset;
            }
        }
    }
    return -1;
}

static int32_t iocp_field_offset(IocpField* f) {
    int32_t off = __atomic_load_n(&f->offset, __ATOMIC_ACQUIRE);
    if (off < 0) {
        off = iocp_resolve_field(f->name);
        if (off < 0) {
            char msg[128];
            snprintf(msg, sizeof(msg), "Iocp$CompletionStatus.%s is not present in the image", f->name);
            __jnative_throw_exception(__jnative_construct_exception("vtable_java_lang_InternalError", msg));
        }
        __atomic_store_n(&f->offset, off, __ATOMIC_RELEASE);
    }
    return off;
}

JNATIVE_NORETURN static void iocp_throw_last_error(const char* what) {
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

void __jnative_fn_sun_nio_ch_Iocp_initIDs___V(void) {
    iocp_field_offset(&iocp_cs_error);
    iocp_field_offset(&iocp_cs_transferred);
    iocp_field_offset(&iocp_cs_key);
    iocp_field_offset(&iocp_cs_overlapped);
}

int64_t __jnative_fn_sun_nio_ch_Iocp_createIoCompletionPort__JJII_J(
        int64_t handle, int64_t existing_port, int32_t completion_key, int32_t concurrency) {
    HANDLE port = CreateIoCompletionPort((HANDLE)(intptr_t)handle,
                                         (HANDLE)(intptr_t)existing_port,
                                         (ULONG_PTR)(uint32_t)completion_key,
                                         (DWORD)concurrency);
    if (port == NULL) iocp_throw_last_error("CreateIoCompletionPort failed");
    return (int64_t)(intptr_t)port;
}

void __jnative_fn_sun_nio_ch_Iocp_close0__J_V(int64_t handle) {
    CloseHandle((HANDLE)(intptr_t)handle);
}

void __jnative_fn_sun_nio_ch_Iocp_getQueuedCompletionStatus__JLsun_nio_ch_Iocp_CompletionStatus__V(
        int64_t completion_port, void* status) {
    if (status == NULL) __jnative_throw_null_pointer_exception();
    DWORD transferred = 0;
    ULONG_PTR key = 0;
    OVERLAPPED* overlapped = NULL;
    BOOL res = GetQueuedCompletionStatus((HANDLE)(intptr_t)completion_port,
                                         &transferred, &key, &overlapped, INFINITE);
    if (res == 0 && overlapped == NULL) iocp_throw_last_error("GetQueuedCompletionStatus failed");
    DWORD io_result = res == 0 ? GetLastError() : 0;
    *(int32_t*)((char*)status + iocp_field_offset(&iocp_cs_error))       = (int32_t)io_result;
    *(int32_t*)((char*)status + iocp_field_offset(&iocp_cs_transferred)) = (int32_t)transferred;
    *(int32_t*)((char*)status + iocp_field_offset(&iocp_cs_key))         = (int32_t)key;
    *(int64_t*)((char*)status + iocp_field_offset(&iocp_cs_overlapped))  = (int64_t)(intptr_t)overlapped;
}

void __jnative_fn_sun_nio_ch_Iocp_postQueuedCompletionStatus__JI_V(
        int64_t completion_port, int32_t completion_key) {
    if (!PostQueuedCompletionStatus((HANDLE)(intptr_t)completion_port, 0,
                                    (ULONG_PTR)(uint32_t)completion_key, NULL)) {
        iocp_throw_last_error("PostQueuedCompletionStatus failed");
    }
}

void* __jnative_fn_sun_nio_ch_Iocp_getErrorMessage__I_Ljava_lang_String_(int32_t error) {
    wchar_t wbuf[256];
    DWORD n = FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                             NULL, (DWORD)error, 0, wbuf, (DWORD)(sizeof(wbuf) / sizeof(wbuf[0])), NULL);
    if (n == 0) return NULL;
    while (n > 0 && (wbuf[n - 1] == L'\r' || wbuf[n - 1] == L'\n')) n--;
    char buf[1024];
    int len = WideCharToMultiByte(CP_UTF8, 0, wbuf, (int)n, buf, (int)sizeof(buf), NULL, NULL);
    if (len <= 0) return NULL;
    return __jnative_make_string_obj(buf, len);
}

#endif
