#ifdef _WIN32

#include "jnative_runtime.h"

#include <wchar.h>
#include <direct.h>
#include <io.h>

extern JNativeVTable vtable_java_lang_String;

#define FILE_PATH_OFFSET 8

#define BA_EXISTS    0x01
#define BA_REGULAR   0x02
#define BA_DIRECTORY 0x04
#define BA_HIDDEN    0x08

#define ACCESS_READ    0x04
#define ACCESS_WRITE   0x02
#define ACCESS_EXECUTE 0x01

#define SPACE_TOTAL  0
#define SPACE_FREE   1
#define SPACE_USABLE 2

static wchar_t* jstr_to_wchars(void* jstr, int32_t* out_len) {
    if (out_len != NULL) *out_len = 0;
    if (jstr == NULL) return NULL;
    void* value = *(void**)((char*)jstr + 8);
    if (value == NULL) return NULL;
    int32_t byte_len = *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET);
    uint8_t coder = *(uint8_t*)((char*)jstr + 16);
    int32_t char_count = (coder == 0) ? byte_len : byte_len / 2;
    if (char_count < 0) char_count = 0;
    wchar_t* out = (wchar_t*)malloc(((size_t)char_count + 1) * sizeof(wchar_t));
    if (out == NULL) return NULL;
    const uint8_t* bytes = (const uint8_t*)value + JAVA_ARR_HDR;
    if (coder == 0) {
        for (int32_t i = 0; i < char_count; i++) {
            out[i] = (wchar_t)bytes[i];
        }
    } else {
        for (int32_t i = 0; i < char_count; i++) {
            out[i] = (wchar_t)(((uint16_t)bytes[i * 2] << 8) | bytes[i * 2 + 1]);
        }
    }
    out[char_count] = 0;
    if (out_len != NULL) *out_len = char_count;
    return out;
}

static void* wchars_to_jstr(const wchar_t* wstr, int32_t char_count) {
    if (wstr == NULL) return NULL;
    if (char_count < 0) char_count = 0;

    int all_latin1 = 1;
    for (int32_t i = 0; i < char_count; i++) {
        if ((uint32_t)wstr[i] > 0xFF) { all_latin1 = 0; break; }
    }

    ReflectionClass* ba_cls = jnative_get_byte_array_class();
    if (ba_cls == NULL) return NULL;

    void* value;
    uint8_t coder;
    int32_t payload_bytes;

    if (all_latin1) {
        value = jnative_array_alloc_with_class(ba_cls, char_count + 1, 1);
        if (value == NULL) return NULL;
        *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = char_count;
        uint8_t* data = (uint8_t*)value + JAVA_ARR_HDR;
        for (int32_t i = 0; i < char_count; i++) {
            data[i] = (uint8_t)wstr[i];
        }
        data[char_count] = 0;
        coder = 0;
        payload_bytes = char_count;
    } else {
        value = jnative_array_alloc_with_class(ba_cls, char_count * 2 + 2, 1);
        if (value == NULL) return NULL;
        *(int32_t*)((char*)value + JAVA_ARR_LENGTH_OFFSET) = char_count * 2;
        uint8_t* data = (uint8_t*)value + JAVA_ARR_HDR;
        for (int32_t i = 0; i < char_count; i++) {
            uint16_t c = (uint16_t)wstr[i];
            data[i * 2] = (uint8_t)(c >> 8);
            data[i * 2 + 1] = (uint8_t)(c & 0xFF);
        }
        data[char_count * 2] = 0;
        data[char_count * 2 + 1] = 0;
        coder = 1;
        payload_bytes = char_count * 2;
    }
    (void)payload_bytes;

    void* s = calloc(1, 32);
    if (s == NULL) return NULL;
    *(void**)((char*)s + 0) = (void*)&vtable_java_lang_String;
    *(void**)((char*)s + 8) = value;
    *(uint8_t*)((char*)s + 16) = coder;
    return s;
}

static wchar_t* file_to_wchars(void* file, int32_t* out_len) {
    if (file == NULL) {
        if (out_len != NULL) *out_len = 0;
        return NULL;
    }
    void* path_str = *(void**)((char*)file + FILE_PATH_OFFSET);
    return jstr_to_wchars(path_str, out_len);
}

static wchar_t* file_to_wchars_nul_empty(void* file) {
    int32_t len = 0;
    wchar_t* p = file_to_wchars(file, &len);
    if (p == NULL) {
        p = (wchar_t*)calloc(1, sizeof(wchar_t));
    }
    return p;
}

void* __jnative_fn_java_io_WinNTFileSystem_getDriveDirectory__I_Ljava_lang_String_(
    void* this_fs, int32_t drive) {
    if (drive < 1 || drive > 26) return NULL;
    wchar_t* buf = _wgetdcwd(drive, NULL, 0);
    if (buf == NULL) return NULL;
    size_t len = wcslen(buf);
    void* result = NULL;
    if (len >= 2 && buf[1] == L':') {
        result = wchars_to_jstr(buf + 2, (int32_t)(len - 2));
    }
    free(buf);
    return result;
}

void* __jnative_fn_java_io_WinNTFileSystem_canonicalize0__Ljava_lang_String__Ljava_lang_String_(
    void* this_fs, void* path_str) {
    int32_t len = 0;
    wchar_t* path = jstr_to_wchars(path_str, &len);
    if (path == NULL) {
        __jnative_throw_io_ctx("null path", "java.io.WinNTFileSystem.canonicalize0");
    }

    DWORD need = GetFullPathNameW(path, 0, NULL, NULL);
    if (need == 0 || need > 32768) {
        free(path);
        __jnative_throw_io_ctx("GetFullPathNameW failed",
                               "java.io.WinNTFileSystem.canonicalize0");
    }

    wchar_t* abs_path = (wchar_t*)malloc(((size_t)need + 1) * sizeof(wchar_t));
    if (abs_path == NULL) {
        free(path);
        __jnative_throw_io_ctx("out of memory",
                               "java.io.WinNTFileSystem.canonicalize0");
    }

    DWORD got = GetFullPathNameW(path, need + 1, abs_path, NULL);
    free(path);
    if (got == 0 || got > need) {
        free(abs_path);
        __jnative_throw_io_ctx("GetFullPathNameW failed",
                               "java.io.WinNTFileSystem.canonicalize0");
    }

    DWORD long_need = GetLongPathNameW(abs_path, NULL, 0);
    if (long_need > 0 && long_need < 32768) {
        wchar_t* long_path = (wchar_t*)malloc(((size_t)long_need + 1) * sizeof(wchar_t));
        if (long_path != NULL) {
            DWORD long_got = GetLongPathNameW(abs_path, long_path, long_need + 1);
            if (long_got > 0 && long_got <= long_need) {
                free(abs_path);
                abs_path = long_path;
                got = long_got;
            } else {
                free(long_path);
            }
        }
    }

    DWORD attrs = GetFileAttributesW(abs_path);
    if (attrs == INVALID_FILE_ATTRIBUTES) {
        free(abs_path);
        __jnative_throw_io_ctx("cannot canonicalize",
                               "java.io.WinNTFileSystem.canonicalize0");
    }

    if (attrs & FILE_ATTRIBUTE_DIRECTORY) {
        size_t n = wcslen(abs_path);
        if (n > 3 && abs_path[n - 1] != L'\\') {
            wchar_t* with_slash = (wchar_t*)malloc((n + 2) * sizeof(wchar_t));
            if (with_slash != NULL) {
                memcpy(with_slash, abs_path, n * sizeof(wchar_t));
                with_slash[n] = L'\\';
                with_slash[n + 1] = 0;
                free(abs_path);
                abs_path = with_slash;
            }
        }
    }

    void* result = wchars_to_jstr(abs_path, (int32_t)wcslen(abs_path));
    free(abs_path);
    return result;
}

static wchar_t* final_path_of(const wchar_t* path) {
    HANDLE h = CreateFileW(path, FILE_READ_ATTRIBUTES,
                           FILE_SHARE_DELETE | FILE_SHARE_READ | FILE_SHARE_WRITE,
                           NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (h == INVALID_HANDLE_VALUE) return NULL;

    DWORD cap = MAX_PATH;
    wchar_t* result = (wchar_t*)malloc((size_t)cap * sizeof(wchar_t));
    DWORD len = 0;
    if (result != NULL) {
        len = GetFinalPathNameByHandleW(h, result, cap, 0);
        if (len >= cap) {
            cap = len + 1;
            wchar_t* bigger = (wchar_t*)realloc(result, (size_t)cap * sizeof(wchar_t));
            if (bigger == NULL) {
                free(result);
                result = NULL;
            } else {
                result = bigger;
                len = GetFinalPathNameByHandleW(h, result, cap, 0);
            }
        }
    }
    CloseHandle(h);

    if (result == NULL) return NULL;
    if (len == 0 || len >= cap) {
        free(result);
        return NULL;
    }

    if (len >= 4 && result[0] == L'\\' && result[1] == L'\\'
        && result[2] == L'?' && result[3] == L'\\') {
        int is_unc = len >= 7 && result[4] == L'U' && result[5] == L'N' && result[6] == L'C';
        DWORD prefix_len = is_unc ? 7 : 4;
        DWORD prefix_keep = is_unc ? 1 : 0;
        wmemmove(result + prefix_keep, result + prefix_len, (size_t)(len - prefix_len + 1));
    }
    return result;
}

void* __jnative_fn_java_io_WinNTFileSystem_getFinalPath0__Ljava_lang_String__Ljava_lang_String_(
    void* this_fs, void* path_str) {
    (void)this_fs;
    if (path_str == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    wchar_t* path = jstr_to_wchars(path_str, NULL);
    if (path == NULL) {
        __jnative_throw_io_ctx("Bad pathname", "java.io.WinNTFileSystem.getFinalPath0");
    }
    wchar_t* final_path = final_path_of(path);
    free(path);
    if (final_path == NULL) {
        __jnative_throw_io_ctx("Bad pathname", "java.io.WinNTFileSystem.getFinalPath0");
    }
    void* result = wchars_to_jstr(final_path, (int32_t)wcslen(final_path));
    free(final_path);
    if (result == NULL) {
        __jnative_throw_out_of_memory_error_ctx("java.io.WinNTFileSystem.getFinalPath0");
    }
    return result;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_getBooleanAttributes0__Ljava_io_File__I(
    void* this_fs, void* file) {
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    DWORD attrs = GetFileAttributesW(path);
    free(path);
    if (attrs == INVALID_FILE_ATTRIBUTES) return 0;
    int32_t result = BA_EXISTS;
    if (attrs & FILE_ATTRIBUTE_DIRECTORY) {
        result |= BA_DIRECTORY;
    } else {
        result |= BA_REGULAR;
    }
    if (attrs & FILE_ATTRIBUTE_HIDDEN) {
        result |= BA_HIDDEN;
    }
    return result;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_checkAccess0__Ljava_io_File_I_Z(
    void* this_fs, void* file, int32_t access) {
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    DWORD attrs = GetFileAttributesW(path);
    free(path);
    if (attrs == INVALID_FILE_ATTRIBUTES) return 0;

    if (access == ACCESS_READ) {
        return 1;
    }
    if (access == ACCESS_WRITE) {
        return (attrs & FILE_ATTRIBUTE_READONLY) ? 0 : 1;
    }
    if (access == ACCESS_EXECUTE) {
        return 1;
    }
    return 0;
}

int64_t __jnative_fn_java_io_WinNTFileSystem_getLastModifiedTime0__Ljava_io_File__J(
    void* this_fs, void* file) {
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    WIN32_FILE_ATTRIBUTE_DATA data;
    BOOL ok = GetFileAttributesExW(path, GetFileExInfoStandard, &data);
    free(path);
    if (!ok) return 0;
    ULARGE_INTEGER ft;
    ft.LowPart = data.ftLastWriteTime.dwLowDateTime;
    ft.HighPart = data.ftLastWriteTime.dwHighDateTime;
    const int64_t EPOCH_DIFF = 116444736000000000LL;
    if (ft.QuadPart <= (uint64_t)EPOCH_DIFF) return 0;
    return (int64_t)((ft.QuadPart - (uint64_t)EPOCH_DIFF) / 10000ULL);
}

int64_t __jnative_fn_java_io_WinNTFileSystem_getLength0__Ljava_io_File__J(
    void* this_fs, void* file) {
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    WIN32_FILE_ATTRIBUTE_DATA data;
    BOOL ok = GetFileAttributesExW(path, GetFileExInfoStandard, &data);
    free(path);
    if (!ok) return 0;
    ULARGE_INTEGER size;
    size.LowPart = data.nFileSizeLow;
    size.HighPart = data.nFileSizeHigh;
    return (int64_t)size.QuadPart;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_setPermission0__Ljava_io_File_IZZ_Z(
    void* this_fs, void* file, int32_t access, int32_t enable, int32_t owneronly) {
    (void)owneronly;
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    DWORD attrs = GetFileAttributesW(path);
    if (attrs == INVALID_FILE_ATTRIBUTES) {
        free(path);
        return 0;
    }
    DWORD new_attrs = attrs;
    if (access & ACCESS_WRITE) {
        if (enable) {
            new_attrs &= ~FILE_ATTRIBUTE_READONLY;
        } else {
            new_attrs |= FILE_ATTRIBUTE_READONLY;
        }
    } else if (access & ACCESS_READ) {
        if (!enable) {
            new_attrs |= FILE_ATTRIBUTE_READONLY;
        }
    }
    uint8_t result = 1;
    if (new_attrs != attrs) {
        result = SetFileAttributesW(path, new_attrs) ? 1 : 0;
    }
    free(path);
    return result;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_createFileExclusively0__Ljava_lang_String__Z(
    void* this_fs, void* path_str) {
    int32_t len = 0;
    wchar_t* path = jstr_to_wchars(path_str, &len);
    if (path == NULL) {
        __jnative_throw_io_ctx("null path",
                               "java.io.WinNTFileSystem.createFileExclusively0");
    }
    HANDLE h = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_NEW,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) {
        free(path);
        __jnative_throw_io_ctx("cannot create file",
                               "java.io.WinNTFileSystem.createFileExclusively0");
    }
    CloseHandle(h);
    free(path);
    return 1;
}

void* __jnative_fn_java_io_WinNTFileSystem_list0__Ljava_io_File___Ljava_lang_String_(
    void* this_fs, void* file) {
    int32_t plen = 0;
    wchar_t* path = file_to_wchars(file, &plen);
    if (path == NULL) return NULL;

    size_t n = wcslen(path);
    wchar_t* pattern = (wchar_t*)malloc((n + 3) * sizeof(wchar_t));
    if (pattern == NULL) { free(path); return NULL; }
    memcpy(pattern, path, n * sizeof(wchar_t));
    if (n == 0 || pattern[n - 1] != L'\\') {
        pattern[n++] = L'\\';
    }
    pattern[n++] = L'*';
    pattern[n] = 0;
    free(path);

    WIN32_FIND_DATAW data;
    HANDLE h = FindFirstFileW(pattern, &data);
    free(pattern);
    if (h == INVALID_HANDLE_VALUE) return NULL;

    void** items = NULL;
    int32_t count = 0;
    int32_t capacity = 0;

    do {
        if (wcscmp(data.cFileName, L".") == 0) continue;
        if (wcscmp(data.cFileName, L"..") == 0) continue;
        if (count == capacity) {
            int32_t new_cap = (capacity == 0) ? 16 : capacity * 2;
            void** grown = (void**)realloc(items, (size_t)new_cap * sizeof(void*));
            if (grown == NULL) {
                for (int32_t i = 0; i < count; i++) {
                    /* String objects are managed; skip explicit free */
                    (void)items[i];
                }
                free(items);
                FindClose(h);
                return NULL;
            }
            items = grown;
            capacity = new_cap;
        }
        items[count++] = wchars_to_jstr(data.cFileName,
                                        (int32_t)wcslen(data.cFileName));
    } while (FindNextFileW(h, &data));

    FindClose(h);

    void* result = jnative_ref_array_of_class(items, count, "[Ljava/lang/String;");
    free(items);
    return result;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_createDirectory0__Ljava_io_File__Z(
    void* this_fs, void* file) {
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    BOOL ok = CreateDirectoryW(path, NULL);
    free(path);
    return ok ? 1 : 0;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_setLastModifiedTime0__Ljava_io_File_J_Z(
    void* this_fs, void* file, int64_t time) {
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    HANDLE h = CreateFileW(path, FILE_WRITE_ATTRIBUTES,
                           FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                           OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (h == INVALID_HANDLE_VALUE) { free(path); return 0; }
    const int64_t EPOCH_DIFF = 116444736000000000LL;
    ULARGE_INTEGER ft;
    ft.QuadPart = (uint64_t)(time * 10000LL + EPOCH_DIFF);
    FILETIME ftime;
    ftime.dwLowDateTime = ft.LowPart;
    ftime.dwHighDateTime = ft.HighPart;
    BOOL ok = SetFileTime(h, NULL, NULL, &ftime);
    CloseHandle(h);
    free(path);
    return ok ? 1 : 0;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_setReadOnly0__Ljava_io_File__Z(
    void* this_fs, void* file) {
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    DWORD attrs = GetFileAttributesW(path);
    if (attrs == INVALID_FILE_ATTRIBUTES) { free(path); return 0; }
    BOOL ok = SetFileAttributesW(path, attrs | FILE_ATTRIBUTE_READONLY);
    free(path);
    return ok ? 1 : 0;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_delete0__Ljava_io_File_Z_Z(
    void* this_fs, void* file, int32_t allow_delete_readonly) {
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    DWORD attrs = GetFileAttributesW(path);
    if (attrs == INVALID_FILE_ATTRIBUTES) { free(path); return 0; }

    uint8_t is_dir = (attrs & FILE_ATTRIBUTE_DIRECTORY) ? 1 : 0;
    BOOL ok = is_dir ? RemoveDirectoryW(path) : DeleteFileW(path);

    if (!ok && allow_delete_readonly && (attrs & FILE_ATTRIBUTE_READONLY)) {
        DWORD new_attrs = attrs & ~FILE_ATTRIBUTE_READONLY;
        if (SetFileAttributesW(path, new_attrs)) {
            ok = is_dir ? RemoveDirectoryW(path) : DeleteFileW(path);
        }
    }
    free(path);
    return ok ? 1 : 0;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_rename0__Ljava_io_File_Ljava_io_File__Z(
    void* this_fs, void* f1, void* f2) {
    wchar_t* path1 = file_to_wchars(f1, NULL);
    wchar_t* path2 = file_to_wchars(f2, NULL);
    if (path1 == NULL || path2 == NULL) {
        if (path1 != NULL) free(path1);
        if (path2 != NULL) free(path2);
        return 0;
    }
    BOOL ok = MoveFileExW(path1, path2, MOVEFILE_REPLACE_EXISTING);
    free(path1);
    free(path2);
    return ok ? 1 : 0;
}

int32_t __jnative_fn_java_io_WinNTFileSystem_listRoots0___I(void) {
    return (int32_t)GetLogicalDrives();
}

int64_t __jnative_fn_java_io_WinNTFileSystem_getSpace0__Ljava_io_File_I_J(
    void* this_fs, void* file, int32_t t) {
    wchar_t* path = file_to_wchars(file, NULL);
    if (path == NULL) return 0;
    ULARGE_INTEGER free_avail, total_bytes, total_free;
    if (!GetDiskFreeSpaceExW(path, &free_avail, &total_bytes, &total_free)) {
        free(path);
        return 0;
    }
    free(path);
    switch (t) {
        case SPACE_TOTAL:  return (int64_t)total_bytes.QuadPart;
        case SPACE_FREE:   return (int64_t)total_free.QuadPart;
        case SPACE_USABLE: return (int64_t)free_avail.QuadPart;
        default:           return 0;
    }
}

int32_t __jnative_fn_java_io_WinNTFileSystem_getNameMax0__Ljava_lang_String__I(
    void* this_fs, void* path_str) {
    wchar_t* path = jstr_to_wchars(path_str, NULL);
    if (path == NULL) return 0;
    DWORD max_component = 0;
    BOOL ok = GetVolumeInformationW(path, NULL, 0, NULL, &max_component,
                                    NULL, NULL, 0);
    free(path);
    return ok ? (int32_t)max_component : 0;
}

void __jnative_fn_java_io_WinNTFileSystem_initIDs___V(void) {
}

#endif
