#if defined(_WIN32)

#include "jnative_runtime.h"

#include <windows.h>
#include <winioctl.h>
#include <sddl.h>
#include <wchar.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

extern JNativeVTable vtable_java_lang_String;

#define WND_PTR(x) ((void*)(intptr_t)(x))
#define WND_WSTR(x) ((LPCWSTR)(intptr_t)(x))
#define WND_JLONG(p) ((int64_t)(intptr_t)(p))

typedef struct {
    const char* cls;
    const char* name;
    int offset;
    int resolved;
} WndField;

static WndField fld_ff_handle     = { "sun/nio/fs/WindowsNativeDispatcher$FirstFile", "handle", -1, 0 };
static WndField fld_ff_name       = { "sun/nio/fs/WindowsNativeDispatcher$FirstFile", "name", -1, 0 };
static WndField fld_ff_attributes = { "sun/nio/fs/WindowsNativeDispatcher$FirstFile", "attributes", -1, 0 };
static WndField fld_fs_handle     = { "sun/nio/fs/WindowsNativeDispatcher$FirstStream", "handle", -1, 0 };
static WndField fld_fs_name       = { "sun/nio/fs/WindowsNativeDispatcher$FirstStream", "name", -1, 0 };
static WndField fld_vi_fsname     = { "sun/nio/fs/WindowsNativeDispatcher$VolumeInformation", "fileSystemName", -1, 0 };
static WndField fld_vi_volname    = { "sun/nio/fs/WindowsNativeDispatcher$VolumeInformation", "volumeName", -1, 0 };
static WndField fld_vi_serial     = { "sun/nio/fs/WindowsNativeDispatcher$VolumeInformation", "volumeSerialNumber", -1, 0 };
static WndField fld_vi_flags      = { "sun/nio/fs/WindowsNativeDispatcher$VolumeInformation", "flags", -1, 0 };
static WndField fld_dfs_avail     = { "sun/nio/fs/WindowsNativeDispatcher$DiskFreeSpace", "freeBytesAvailable", -1, 0 };
static WndField fld_dfs_total     = { "sun/nio/fs/WindowsNativeDispatcher$DiskFreeSpace", "totalNumberOfBytes", -1, 0 };
static WndField fld_dfs_free      = { "sun/nio/fs/WindowsNativeDispatcher$DiskFreeSpace", "totalNumberOfFreeBytes", -1, 0 };
static WndField fld_dfs_sector    = { "sun/nio/fs/WindowsNativeDispatcher$DiskFreeSpace", "bytesPerSector", -1, 0 };
static WndField fld_acl_count     = { "sun/nio/fs/WindowsNativeDispatcher$AclInformation", "aceCount", -1, 0 };
static WndField fld_acc_domain    = { "sun/nio/fs/WindowsNativeDispatcher$Account", "domain", -1, 0 };
static WndField fld_acc_name      = { "sun/nio/fs/WindowsNativeDispatcher$Account", "name", -1, 0 };
static WndField fld_acc_use       = { "sun/nio/fs/WindowsNativeDispatcher$Account", "use", -1, 0 };
static WndField fld_cs_error      = { "sun/nio/fs/WindowsNativeDispatcher$CompletionStatus", "error", -1, 0 };
static WndField fld_cs_bytes      = { "sun/nio/fs/WindowsNativeDispatcher$CompletionStatus", "bytesTransferred", -1, 0 };
static WndField fld_cs_key        = { "sun/nio/fs/WindowsNativeDispatcher$CompletionStatus", "completionKey", -1, 0 };

static WndField* const wnd_all_fields[] = {
    &fld_ff_handle, &fld_ff_name, &fld_ff_attributes,
    &fld_fs_handle, &fld_fs_name,
    &fld_vi_fsname, &fld_vi_volname, &fld_vi_serial, &fld_vi_flags,
    &fld_dfs_avail, &fld_dfs_total, &fld_dfs_free, &fld_dfs_sector,
    &fld_acl_count,
    &fld_acc_domain, &fld_acc_name, &fld_acc_use,
    &fld_cs_error, &fld_cs_bytes, &fld_cs_key,
};

static int wnd_field_offset(WndField* f) {
    if (f->resolved) return f->offset;
    int off = -1;
    ReflectionClass* cur = jnative_class_by_name(f->cls);
    while (cur != NULL && off < 0) {
        if (cur->fields != NULL) {
            for (ReflectionField** fp = cur->fields; *fp != NULL; fp++) {
                if ((*fp)->name != NULL && strcmp((const char*)(*fp)->name, f->name) == 0) {
                    off = (*fp)->offset;
                    break;
                }
            }
        }
        cur = cur->superclass;
    }
    f->offset = off;
    f->resolved = 1;
    return off;
}

static void wnd_set_int(void* obj, WndField* f, int32_t v) {
    int off = wnd_field_offset(f);
    if (obj != NULL && off >= 0) *(int32_t*)((char*)obj + off) = v;
}

static void wnd_set_long(void* obj, WndField* f, int64_t v) {
    int off = wnd_field_offset(f);
    if (obj != NULL && off >= 0) *(int64_t*)((char*)obj + off) = v;
}

static void wnd_set_obj(void* obj, WndField* f, void* v) {
    int off = wnd_field_offset(f);
    if (obj != NULL && off >= 0) *(void**)((char*)obj + off) = v;
}

JNATIVE_NORETURN static void wnd_throw(DWORD last_error) {
    ReflectionClass* cls = jnative_class_by_name("sun/nio/fs/WindowsException");
    if (cls != NULL) {
        void* exc = jnative_alloc_object(cls);
        if (exc != NULL) {
            typedef void (*ctor_int_t)(void*, int32_t);
            ctor_int_t ctor = (ctor_int_t)jn_dlsym_default("fn_sun_nio_fs_WindowsException__init__I_V");
            if (ctor != NULL) {
                ctor(exc, (int32_t)last_error);
                __jnative_throw_exception(exc);
            }
            free(exc);
        }
    }
    char buf[96];
    snprintf(buf, sizeof(buf), "WindowsException (error %lu)", (unsigned long)last_error);
    __jnative_throw_io_ctx(buf, "WindowsNativeDispatcher");
}

JNATIVE_NORETURN static void wnd_throw_last(void) {
    wnd_throw(GetLastError());
}

static void* wnd_string(const WCHAR* wstr, size_t len) {
    if (wstr == NULL) return NULL;
    int32_t n = (int32_t)len;
    int all_latin1 = 1;
    for (int32_t i = 0; i < n; i++) {
        if (wstr[i] > 0xFF) { all_latin1 = 0; break; }
    }
    ReflectionClass* ba_cls = jnative_get_byte_array_class();
    void* value;
    uint8_t coder;
    if (all_latin1) {
        value = jnative_array_alloc_with_class(ba_cls, n + 1, 1);
        if (value == NULL) __jnative_throw_out_of_memory_error_ctx("WindowsNativeDispatcher");
        *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = n;
        uint8_t* data = (uint8_t*)value + JAVA_ARR_HDR;
        for (int32_t i = 0; i < n; i++) data[i] = (uint8_t)wstr[i];
        data[n] = 0;
        coder = 0;
    } else {
        value = jnative_array_alloc_with_class(ba_cls, n * 2 + 2, 1);
        if (value == NULL) __jnative_throw_out_of_memory_error_ctx("WindowsNativeDispatcher");
        *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = n * 2;
        uint8_t* data = (uint8_t*)value + JAVA_ARR_HDR;
        for (int32_t i = 0; i < n; i++) {
            uint16_t c = (uint16_t)wstr[i];
            data[i * 2] = (uint8_t)(c >> 8);
            data[i * 2 + 1] = (uint8_t)(c & 0xFF);
        }
        data[n * 2] = 0;
        data[n * 2 + 1] = 0;
        coder = 1;
    }
    void* s = calloc(1, 32);
    if (s == NULL) __jnative_throw_out_of_memory_error_ctx("WindowsNativeDispatcher");
    *(void**)s = (void*)&vtable_java_lang_String;
    *(void**)((char*)s + 8) = value;
    *(uint8_t*)((char*)s + 16) = coder;
    return s;
}

static void* wnd_cstring(const WCHAR* wstr) {
    return wnd_string(wstr, wcslen(wstr));
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_initIDs___V(void) {
    for (size_t i = 0; i < sizeof(wnd_all_fields) / sizeof(wnd_all_fields[0]); i++) {
        wnd_field_offset(wnd_all_fields[i]);
    }
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_CreateEvent__ZZ_J(int32_t manual_reset, int32_t initial_state) {
    HANDLE h = CreateEventW(NULL, manual_reset ? TRUE : FALSE, initial_state ? TRUE : FALSE, NULL);
    if (h == NULL) wnd_throw_last();
    return WND_JLONG(h);
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_CreateFile0__JIIJII_J(
    int64_t address, int32_t desired_access, int32_t share_mode, int64_t sd_address,
    int32_t creation_disposition, int32_t flags_and_attributes) {
    SECURITY_ATTRIBUTES sa;
    LPSECURITY_ATTRIBUTES psa = NULL;
    if (sd_address != 0) {
        sa.nLength = sizeof(SECURITY_ATTRIBUTES);
        sa.lpSecurityDescriptor = WND_PTR(sd_address);
        sa.bInheritHandle = FALSE;
        psa = &sa;
    }
    HANDLE h = CreateFileW(WND_WSTR(address), (DWORD)desired_access, (DWORD)share_mode, psa,
                           (DWORD)creation_disposition, (DWORD)flags_and_attributes, NULL);
    if (h == INVALID_HANDLE_VALUE) wnd_throw_last();
    return WND_JLONG(h);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_CloseHandle__J_V(int64_t handle) {
    CloseHandle((HANDLE)WND_PTR(handle));
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_DeleteFile0__J_V(int64_t address) {
    if (DeleteFileW(WND_WSTR(address)) == 0) wnd_throw_last();
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_CreateDirectory0__JJ_V(int64_t address, int64_t sd_address) {
    SECURITY_ATTRIBUTES sa;
    LPSECURITY_ATTRIBUTES psa = NULL;
    if (sd_address != 0) {
        sa.nLength = sizeof(SECURITY_ATTRIBUTES);
        sa.lpSecurityDescriptor = WND_PTR(sd_address);
        sa.bInheritHandle = FALSE;
        psa = &sa;
    }
    if (CreateDirectoryW(WND_WSTR(address), psa) == 0) wnd_throw_last();
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_RemoveDirectory0__J_V(int64_t address) {
    if (RemoveDirectoryW(WND_WSTR(address)) == 0) wnd_throw_last();
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_DeviceIoControlSetSparse__J_V(int64_t handle) {
    DWORD bytes = 0;
    if (DeviceIoControl((HANDLE)WND_PTR(handle), FSCTL_SET_SPARSE, NULL, 0, NULL, 0, &bytes, NULL) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_DeviceIoControlGetReparsePoint__JJI_V(
    int64_t handle, int64_t buffer_address, int32_t buffer_size) {
    DWORD bytes = 0;
    if (DeviceIoControl((HANDLE)WND_PTR(handle), FSCTL_GET_REPARSE_POINT, NULL, 0,
                        WND_PTR(buffer_address), (DWORD)buffer_size, &bytes, NULL) == 0) {
        wnd_throw_last();
    }
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetFileSizeEx__J_J(int64_t handle) {
    LARGE_INTEGER size;
    if (GetFileSizeEx((HANDLE)WND_PTR(handle), &size) == 0) wnd_throw_last();
    return (int64_t)size.QuadPart;
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_FindFirstFile0__JLsun_nio_fs_WindowsNativeDispatcher_FirstFile__V(
    int64_t address, void* obj) {
    WIN32_FIND_DATAW data;
    HANDLE h = FindFirstFileW(WND_WSTR(address), &data);
    if (h == INVALID_HANDLE_VALUE) wnd_throw_last();
    void* name = wnd_cstring(data.cFileName);
    wnd_set_long(obj, &fld_ff_handle, WND_JLONG(h));
    wnd_set_obj(obj, &fld_ff_name, name);
    wnd_set_int(obj, &fld_ff_attributes, (int32_t)data.dwFileAttributes);
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_FindFirstFile1__JJ_J(int64_t address, int64_t data_address) {
    HANDLE h = FindFirstFileW(WND_WSTR(address), (LPWIN32_FIND_DATAW)WND_PTR(data_address));
    if (h == INVALID_HANDLE_VALUE) wnd_throw_last();
    return WND_JLONG(h);
}

void* __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_FindNextFile0__JJ_Ljava_lang_String_(int64_t handle, int64_t data_address) {
    LPWIN32_FIND_DATAW data = (LPWIN32_FIND_DATAW)WND_PTR(data_address);
    if (FindNextFileW((HANDLE)WND_PTR(handle), data) != 0) {
        return wnd_cstring(data->cFileName);
    }
    DWORD err = GetLastError();
    if (err != ERROR_NO_MORE_FILES) wnd_throw(err);
    return NULL;
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_FindFirstStream0__JLsun_nio_fs_WindowsNativeDispatcher_FirstStream__V(
    int64_t address, void* obj) {
    WIN32_FIND_STREAM_DATA data;
    HANDLE h = FindFirstStreamW(WND_WSTR(address), FindStreamInfoStandard, &data, 0);
    if (h != INVALID_HANDLE_VALUE) {
        void* name = wnd_cstring(data.cStreamName);
        wnd_set_long(obj, &fld_fs_handle, WND_JLONG(h));
        wnd_set_obj(obj, &fld_fs_name, name);
        return;
    }
    DWORD err = GetLastError();
    if (err != ERROR_HANDLE_EOF) wnd_throw(err);
    wnd_set_long(obj, &fld_fs_handle, WND_JLONG(h));
}

void* __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_FindNextStream0__J_Ljava_lang_String_(int64_t handle) {
    WIN32_FIND_STREAM_DATA data;
    if (FindNextStreamW((HANDLE)WND_PTR(handle), &data) != 0) {
        return wnd_cstring(data.cStreamName);
    }
    DWORD err = GetLastError();
    if (err != ERROR_HANDLE_EOF) wnd_throw(err);
    return NULL;
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_FindClose__J_V(int64_t handle) {
    if (FindClose((HANDLE)WND_PTR(handle)) == 0) wnd_throw_last();
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetFileInformationByHandle0__JJ_V(int64_t handle, int64_t address) {
    if (GetFileInformationByHandle((HANDLE)WND_PTR(handle), (LPBY_HANDLE_FILE_INFORMATION)WND_PTR(address)) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_CopyFileEx0__JJIJ_V(
    int64_t existing_address, int64_t new_address, int32_t flags, int64_t cancel_address) {
    if (CopyFileExW(WND_WSTR(existing_address), WND_WSTR(new_address), NULL, NULL,
                    (LPBOOL)WND_PTR(cancel_address), (DWORD)flags) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_MoveFileEx0__JJI_V(
    int64_t existing_address, int64_t new_address, int32_t flags) {
    if (MoveFileExW(WND_WSTR(existing_address), WND_WSTR(new_address), (DWORD)flags) == 0) {
        wnd_throw_last();
    }
}

int32_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetLogicalDrives___I(void) {
    DWORD res = GetLogicalDrives();
    if (res == 0) wnd_throw_last();
    return (int32_t)res;
}

int32_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetFileAttributes0__J_I(int64_t address) {
    DWORD attrs = GetFileAttributesW(WND_WSTR(address));
    if (attrs == INVALID_FILE_ATTRIBUTES) wnd_throw_last();
    return (int32_t)attrs;
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_SetFileAttributes0__JI_V(int64_t address, int32_t value) {
    if (SetFileAttributesW(WND_WSTR(address), (DWORD)value) == 0) wnd_throw_last();
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetFileAttributesEx0__JJ_V(int64_t address, int64_t data_address) {
    if (GetFileAttributesExW(WND_WSTR(address), GetFileExInfoStandard, WND_PTR(data_address)) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_SetFileTime0__JJJJ_V(
    int64_t handle, int64_t create_time, int64_t last_access_time, int64_t last_write_time) {
    FILETIME c, a, w;
    FILETIME* pc = NULL;
    FILETIME* pa = NULL;
    FILETIME* pw = NULL;
    if (create_time != -1) {
        c.dwLowDateTime = (DWORD)create_time;
        c.dwHighDateTime = (DWORD)((uint64_t)create_time >> 32);
        pc = &c;
    }
    if (last_access_time != -1) {
        a.dwLowDateTime = (DWORD)last_access_time;
        a.dwHighDateTime = (DWORD)((uint64_t)last_access_time >> 32);
        pa = &a;
    }
    if (last_write_time != -1) {
        w.dwLowDateTime = (DWORD)last_write_time;
        w.dwHighDateTime = (DWORD)((uint64_t)last_write_time >> 32);
        pw = &w;
    }
    if (SetFileTime((HANDLE)WND_PTR(handle), pc, pa, pw) == 0) wnd_throw_last();
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_SetEndOfFile__J_V(int64_t handle) {
    if (SetEndOfFile((HANDLE)WND_PTR(handle)) == 0) wnd_throw_last();
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetVolumeInformation0__JLsun_nio_fs_WindowsNativeDispatcher_VolumeInformation__V(
    int64_t address, void* obj) {
    WCHAR volume_name[MAX_PATH + 1];
    WCHAR fs_name[MAX_PATH + 1];
    DWORD serial = 0;
    DWORD max_component = 0;
    DWORD flags = 0;
    if (GetVolumeInformationW(WND_WSTR(address), volume_name, MAX_PATH + 1, &serial, &max_component,
                              &flags, fs_name, MAX_PATH + 1) == 0) {
        wnd_throw_last();
    }
    void* fs = wnd_cstring(fs_name);
    void* vol = wnd_cstring(volume_name);
    wnd_set_obj(obj, &fld_vi_fsname, fs);
    wnd_set_obj(obj, &fld_vi_volname, vol);
    wnd_set_int(obj, &fld_vi_serial, (int32_t)serial);
    wnd_set_int(obj, &fld_vi_flags, (int32_t)flags);
}

int32_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetDriveType0__J_I(int64_t address) {
    return (int32_t)GetDriveTypeW(WND_WSTR(address));
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetDiskFreeSpaceEx0__JLsun_nio_fs_WindowsNativeDispatcher_DiskFreeSpace__V(
    int64_t address, void* obj) {
    ULARGE_INTEGER avail, total, total_free;
    if (GetDiskFreeSpaceExW(WND_WSTR(address), &avail, &total, &total_free) == 0) wnd_throw_last();
    wnd_set_long(obj, &fld_dfs_avail, (int64_t)avail.QuadPart);
    wnd_set_long(obj, &fld_dfs_total, (int64_t)total.QuadPart);
    wnd_set_long(obj, &fld_dfs_free, (int64_t)total_free.QuadPart);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetDiskFreeSpace0__JLsun_nio_fs_WindowsNativeDispatcher_DiskFreeSpace__V(
    int64_t address, void* obj) {
    DWORD sectors_per_cluster = 0;
    DWORD bytes_per_sector = 0;
    DWORD free_clusters = 0;
    DWORD total_clusters = 0;
    if (GetDiskFreeSpaceW(WND_WSTR(address), &sectors_per_cluster, &bytes_per_sector,
                          &free_clusters, &total_clusters) == 0) {
        wnd_throw_last();
    }
    wnd_set_long(obj, &fld_dfs_sector, (int64_t)bytes_per_sector);
}

void* __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetVolumePathName0__J_Ljava_lang_String_(int64_t address) {
    WCHAR volume_name[MAX_PATH + 1];
    if (GetVolumePathNameW(WND_WSTR(address), volume_name, MAX_PATH) == 0) wnd_throw_last();
    return wnd_cstring(volume_name);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_InitializeSecurityDescriptor__J_V(int64_t address) {
    if (InitializeSecurityDescriptor((PSECURITY_DESCRIPTOR)WND_PTR(address), SECURITY_DESCRIPTOR_REVISION) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_InitializeAcl__JI_V(int64_t address, int32_t size) {
    if (InitializeAcl((PACL)WND_PTR(address), (DWORD)size, ACL_REVISION) == 0) wnd_throw_last();
}

int32_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetFileSecurity0__JIJI_I(
    int64_t path_address, int32_t requested_information, int64_t desc_address, int32_t length) {
    DWORD needed = 0;
    if (GetFileSecurityW(WND_WSTR(path_address), (SECURITY_INFORMATION)requested_information,
                         (PSECURITY_DESCRIPTOR)WND_PTR(desc_address), (DWORD)length, &needed) == 0) {
        DWORD err = GetLastError();
        if (err == ERROR_INSUFFICIENT_BUFFER) return (int32_t)needed;
        wnd_throw(err);
    }
    return length;
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_SetFileSecurity0__JIJ_V(
    int64_t path_address, int32_t security_information, int64_t desc_address) {
    if (SetFileSecurityW(WND_WSTR(path_address), (SECURITY_INFORMATION)security_information,
                         (PSECURITY_DESCRIPTOR)WND_PTR(desc_address)) == 0) {
        wnd_throw_last();
    }
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetSecurityDescriptorOwner__J_J(int64_t address) {
    PSID owner = NULL;
    BOOL defaulted = FALSE;
    if (GetSecurityDescriptorOwner((PSECURITY_DESCRIPTOR)WND_PTR(address), &owner, &defaulted) == 0) {
        wnd_throw_last();
    }
    return WND_JLONG(owner);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_SetSecurityDescriptorOwner__JJ_V(int64_t desc_address, int64_t owner_address) {
    if (SetSecurityDescriptorOwner((PSECURITY_DESCRIPTOR)WND_PTR(desc_address), (PSID)WND_PTR(owner_address), FALSE) == 0) {
        wnd_throw_last();
    }
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetSecurityDescriptorDacl__J_J(int64_t address) {
    BOOL present = FALSE;
    BOOL defaulted = FALSE;
    PACL dacl = NULL;
    if (GetSecurityDescriptorDacl((PSECURITY_DESCRIPTOR)WND_PTR(address), &present, &dacl, &defaulted) == 0) {
        wnd_throw_last();
    }
    return present ? WND_JLONG(dacl) : 0;
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_SetSecurityDescriptorDacl__JJ_V(int64_t desc_address, int64_t acl_address) {
    if (SetSecurityDescriptorDacl((PSECURITY_DESCRIPTOR)WND_PTR(desc_address), TRUE, (PACL)WND_PTR(acl_address), FALSE) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetAclInformation0__JLsun_nio_fs_WindowsNativeDispatcher_AclInformation__V(
    int64_t address, void* obj) {
    ACL_SIZE_INFORMATION info;
    if (GetAclInformation((PACL)WND_PTR(address), &info, sizeof(info), AclSizeInformation) == 0) {
        wnd_throw_last();
    }
    wnd_set_int(obj, &fld_acl_count, (int32_t)info.AceCount);
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetAce__JI_J(int64_t address, int32_t ace_index) {
    LPVOID ace = NULL;
    if (GetAce((PACL)WND_PTR(address), (DWORD)ace_index, &ace) == 0) return 0;
    return WND_JLONG(ace);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_AddAccessAllowedAceEx__JIIJ_V(
    int64_t acl_address, int32_t flags, int32_t mask, int64_t sid_address) {
    if (AddAccessAllowedAceEx((PACL)WND_PTR(acl_address), ACL_REVISION, (DWORD)flags, (DWORD)mask,
                              (PSID)WND_PTR(sid_address)) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_AddAccessDeniedAceEx__JIIJ_V(
    int64_t acl_address, int32_t flags, int32_t mask, int64_t sid_address) {
    if (AddAccessDeniedAceEx((PACL)WND_PTR(acl_address), ACL_REVISION, (DWORD)flags, (DWORD)mask,
                             (PSID)WND_PTR(sid_address)) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_LookupAccountSid0__JLsun_nio_fs_WindowsNativeDispatcher_Account__V(
    int64_t address, void* obj) {
    WCHAR domain[255];
    WCHAR name[255];
    DWORD domain_len = (DWORD)(sizeof(domain) / sizeof(domain[0]));
    DWORD name_len = (DWORD)(sizeof(name) / sizeof(name[0]));
    SID_NAME_USE use;
    if (LookupAccountSidW(NULL, (PSID)WND_PTR(address), name, &name_len, domain, &domain_len, &use) == 0) {
        wnd_throw_last();
    }
    void* s_domain = wnd_cstring(domain);
    void* s_name = wnd_cstring(name);
    wnd_set_obj(obj, &fld_acc_domain, s_domain);
    wnd_set_obj(obj, &fld_acc_name, s_name);
    wnd_set_int(obj, &fld_acc_use, (int32_t)use);
}

int32_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_LookupAccountName0__JJI_I(
    int64_t name_address, int64_t sid_address, int32_t cb_sid) {
    DWORD sid_len = (DWORD)cb_sid;
    DWORD domain_len = 0;
    SID_NAME_USE use;
    if (LookupAccountNameW(NULL, WND_WSTR(name_address), (PSID)WND_PTR(sid_address), &sid_len,
                           NULL, &domain_len, &use) == 0) {
        DWORD err = GetLastError();
        if (err != ERROR_INSUFFICIENT_BUFFER) wnd_throw(err);
    }
    return (int32_t)sid_len;
}

int32_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetLengthSid__J_I(int64_t address) {
    return (int32_t)GetLengthSid((PSID)WND_PTR(address));
}

void* __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_ConvertSidToStringSid__J_Ljava_lang_String_(int64_t address) {
    LPWSTR str = NULL;
    if (ConvertSidToStringSidW((PSID)WND_PTR(address), &str) == 0) wnd_throw_last();
    void* result = wnd_cstring(str);
    LocalFree(str);
    return result;
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_ConvertStringSidToSid0__J_J(int64_t address) {
    PSID sid = NULL;
    if (ConvertStringSidToSidW(WND_WSTR(address), &sid) == 0) wnd_throw_last();
    return WND_JLONG(sid);
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetCurrentProcess___J(void) {
    return WND_JLONG(GetCurrentProcess());
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetCurrentThread___J(void) {
    return WND_JLONG(GetCurrentThread());
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_OpenProcessToken__JI_J(int64_t process, int32_t desired_access) {
    HANDLE token = NULL;
    if (OpenProcessToken((HANDLE)WND_PTR(process), (DWORD)desired_access, &token) == 0) wnd_throw_last();
    return WND_JLONG(token);
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_OpenThreadToken__JIZ_J(
    int64_t thread, int32_t desired_access, int32_t open_as_self) {
    HANDLE token = NULL;
    if (OpenThreadToken((HANDLE)WND_PTR(thread), (DWORD)desired_access, open_as_self ? TRUE : FALSE, &token) == 0) {
        DWORD err = GetLastError();
        if (err == ERROR_NO_TOKEN) return 0;
        wnd_throw(err);
    }
    return WND_JLONG(token);
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_DuplicateTokenEx__JI_J(int64_t token, int32_t desired_access) {
    HANDLE result = NULL;
    if (DuplicateTokenEx((HANDLE)WND_PTR(token), (DWORD)desired_access, NULL, SecurityImpersonation,
                         TokenImpersonation, &result) == 0) {
        wnd_throw_last();
    }
    return WND_JLONG(result);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_SetThreadToken__JJ_V(int64_t thread, int64_t token) {
    if (SetThreadToken((PHANDLE)WND_PTR(thread), (HANDLE)WND_PTR(token)) == 0) wnd_throw_last();
}

int32_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetTokenInformation__JIJI_I(
    int64_t token, int32_t info_class, int64_t info_address, int32_t info_length) {
    DWORD needed = 0;
    if (GetTokenInformation((HANDLE)WND_PTR(token), (TOKEN_INFORMATION_CLASS)info_class,
                            WND_PTR(info_address), (DWORD)info_length, &needed) == 0) {
        DWORD err = GetLastError();
        if (err == ERROR_INSUFFICIENT_BUFFER) return (int32_t)needed;
        wnd_throw(err);
    }
    return info_length;
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_AdjustTokenPrivileges__JJI_V(
    int64_t token, int64_t luid, int32_t attributes) {
    TOKEN_PRIVILEGES privs;
    privs.PrivilegeCount = 1;
    privs.Privileges[0].Luid = *(PLUID)WND_PTR(luid);
    privs.Privileges[0].Attributes = (DWORD)attributes;
    if (AdjustTokenPrivileges((HANDLE)WND_PTR(token), FALSE, &privs, 0, NULL, NULL) == 0) wnd_throw_last();
}

int32_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_AccessCheck__JJIIIII_Z(
    int64_t token, int64_t security_info, int32_t access_mask, int32_t generic_read,
    int32_t generic_write, int32_t generic_execute, int32_t generic_all) {
    PRIVILEGE_SET privileges;
    DWORD privileges_length = sizeof(privileges);
    DWORD granted = 0;
    BOOL status = FALSE;
    DWORD mask = (DWORD)access_mask;
    GENERIC_MAPPING mapping;
    mapping.GenericRead = (DWORD)generic_read;
    mapping.GenericWrite = (DWORD)generic_write;
    mapping.GenericExecute = (DWORD)generic_execute;
    mapping.GenericAll = (DWORD)generic_all;
    MapGenericMask(&mask, &mapping);
    if (AccessCheck((PSECURITY_DESCRIPTOR)WND_PTR(security_info), (HANDLE)WND_PTR(token), mask, &mapping,
                    &privileges, &privileges_length, &granted, &status) == 0) {
        wnd_throw_last();
    }
    return status ? 1 : 0;
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_LookupPrivilegeValue0__J_J(int64_t name) {
    PLUID luid = (PLUID)LocalAlloc(0, sizeof(LUID));
    if (luid == NULL) wnd_throw_last();
    if (LookupPrivilegeValueW(NULL, WND_WSTR(name), luid) == 0) {
        DWORD err = GetLastError();
        LocalFree(luid);
        wnd_throw(err);
    }
    return WND_JLONG(luid);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_CreateSymbolicLink0__JJI_V(
    int64_t link_address, int64_t target_address, int32_t flags) {
    if (CreateSymbolicLinkW(WND_WSTR(link_address), WND_WSTR(target_address), (DWORD)flags) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_CreateHardLink0__JJ_V(int64_t new_file_address, int64_t existing_file_address) {
    if (CreateHardLinkW(WND_WSTR(new_file_address), WND_WSTR(existing_file_address), NULL) == 0) {
        wnd_throw_last();
    }
}

void* __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetFullPathName0__J_Ljava_lang_String_(int64_t address) {
    WCHAR buffer[MAX_PATH];
    LPCWSTR path = WND_WSTR(address);
    DWORD len = GetFullPathNameW(path, MAX_PATH, buffer, NULL);
    if (len == 0) wnd_throw_last();
    if (len < MAX_PATH) return wnd_string(buffer, len);
    DWORD cap = len + 1;
    WCHAR* buf = (WCHAR*)malloc((size_t)cap * sizeof(WCHAR));
    if (buf == NULL) __jnative_throw_out_of_memory_error_ctx("GetFullPathName");
    len = GetFullPathNameW(path, cap, buf, NULL);
    if (len == 0 || len >= cap) {
        DWORD err = (len == 0) ? GetLastError() : ERROR_INSUFFICIENT_BUFFER;
        free(buf);
        wnd_throw(err);
    }
    void* result = wnd_string(buf, len);
    free(buf);
    return result;
}

void* __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetFinalPathNameByHandle__J_Ljava_lang_String_(int64_t handle) {
    WCHAR buffer[MAX_PATH];
    HANDLE h = (HANDLE)WND_PTR(handle);
    DWORD len = GetFinalPathNameByHandleW(h, buffer, MAX_PATH, 0);
    if (len == 0) wnd_throw_last();
    if (len < MAX_PATH) return wnd_string(buffer, len);
    DWORD cap = len + 1;
    WCHAR* buf = (WCHAR*)malloc((size_t)cap * sizeof(WCHAR));
    if (buf == NULL) __jnative_throw_out_of_memory_error_ctx("GetFinalPathNameByHandle");
    len = GetFinalPathNameByHandleW(h, buf, cap, 0);
    if (len == 0 || len >= cap) {
        DWORD err = (len == 0) ? GetLastError() : ERROR_INSUFFICIENT_BUFFER;
        free(buf);
        wnd_throw(err);
    }
    void* result = wnd_string(buf, len);
    free(buf);
    return result;
}

void* __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_FormatMessage__I_Ljava_lang_String_(int32_t error_code) {
    WCHAR message[255];
    DWORD len = FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS, NULL,
                               (DWORD)error_code, 0, message, 255, NULL);
    if (len == 0) return NULL;
    return wnd_cstring(message);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_LocalFree__J_V(int64_t address) {
    LocalFree((HLOCAL)WND_PTR(address));
}

int64_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_CreateIoCompletionPort__JJJ_J(
    int64_t file_handle, int64_t existing_port, int64_t completion_key) {
    HANDLE port = CreateIoCompletionPort((HANDLE)WND_PTR(file_handle), (HANDLE)WND_PTR(existing_port),
                                         (ULONG_PTR)completion_key, 0);
    if (port == NULL) wnd_throw_last();
    return WND_JLONG(port);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetQueuedCompletionStatus0__JLsun_nio_fs_WindowsNativeDispatcher_CompletionStatus__V(
    int64_t completion_port, void* obj) {
    DWORD bytes = 0;
    ULONG_PTR key = 0;
    OVERLAPPED* ov = NULL;
    BOOL res = GetQueuedCompletionStatus((HANDLE)WND_PTR(completion_port), &bytes, &key, &ov, INFINITE);
    DWORD io_result = (res == 0) ? GetLastError() : 0;
    if (res == 0 && ov == NULL) wnd_throw(io_result);
    wnd_set_int(obj, &fld_cs_error, (int32_t)io_result);
    wnd_set_int(obj, &fld_cs_bytes, (int32_t)bytes);
    wnd_set_long(obj, &fld_cs_key, (int64_t)key);
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_PostQueuedCompletionStatus__JJ_V(
    int64_t completion_port, int64_t completion_key) {
    if (PostQueuedCompletionStatus((HANDLE)WND_PTR(completion_port), 0, (ULONG_PTR)completion_key, NULL) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_ReadDirectoryChangesW__JJIZIJJ_V(
    int64_t directory, int64_t buffer_address, int32_t buffer_length, int32_t watch_sub_tree,
    int32_t filter, int64_t bytes_returned_address, int64_t overlapped_address) {
    if (ReadDirectoryChangesW((HANDLE)WND_PTR(directory), WND_PTR(buffer_address), (DWORD)buffer_length,
                              watch_sub_tree ? TRUE : FALSE, (DWORD)filter,
                              (LPDWORD)WND_PTR(bytes_returned_address),
                              (LPOVERLAPPED)WND_PTR(overlapped_address), NULL) == 0) {
        wnd_throw_last();
    }
}

void __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_CancelIo__J_V(int64_t handle) {
    if (CancelIo((HANDLE)WND_PTR(handle)) == 0) wnd_throw_last();
}

int32_t __jnative_fn_sun_nio_fs_WindowsNativeDispatcher_GetOverlappedResult__JJ_I(int64_t handle, int64_t overlapped_address) {
    DWORD bytes = 0;
    if (GetOverlappedResult((HANDLE)WND_PTR(handle), (LPOVERLAPPED)WND_PTR(overlapped_address), &bytes, TRUE) == 0) {
        wnd_throw_last();
    }
    return (int32_t)bytes;
}

#endif
