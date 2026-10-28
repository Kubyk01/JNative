#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <limits.h>

#if defined(_WIN32)
#  include <wchar.h>
#  include <io.h>
#  include <fcntl.h>
#  include <sys/stat.h>
#else
#  include <dirent.h>
#  include <sys/stat.h>
#  include <unistd.h>
#endif

#include "jnative_runtime.h"

#ifndef PATH_MAX
#  if defined(_WIN32)
#    define PATH_MAX 4096
#  else
#    define PATH_MAX 4096
#  endif
#endif

#define FILE_HEADER_SIZE 8
#define FILE_PATH_OFFSET FILE_HEADER_SIZE

#define BA_EXISTS    0x01
#define BA_REGULAR   0x02
#define BA_DIRECTORY 0x04
#define BA_HIDDEN    0x08

#ifndef F_OK
#define F_OK 0
#endif
#ifndef R_OK
#define R_OK 4
#endif
#ifndef W_OK
#define W_OK 2
#endif
#ifndef X_OK
#define X_OK 1
#endif

/* ============================================================================
 *  Windows implementation
 * ========================================================================= */
#if defined(_WIN32)

static int extract_file_path_wide(void* file_obj, wchar_t* out, int out_cap) {
    if (out_cap <= 0) return -1;
    out[0] = 0;
    if (file_obj == NULL) return -1;
    void* path_str = *(void**)((char*)file_obj + FILE_PATH_OFFSET);
    if (path_str == NULL) return -1;
    int32_t len = 0;
    const char* utf8 = __jnative_read_string_bytes(path_str, &len);
    if (utf8 == NULL) return -1;
    if (len <= 0) { out[0] = 0; return 0; }
    int w = MultiByteToWideChar(CP_UTF8, 0, utf8, len, out, out_cap - 1);
    if (w <= 0) return -1;
    out[w] = 0;
    return 0;
}

/* Map a Win32 error to the Linux errno value the compiled bytecode
 * understands.  The JDK's UnixException caches errno as a Linux-style
 * int and later feeds it to strerror; using GetLastError()'s native
 * numeric code would produce a wrong message. */
static int win_error_to_linux_errno(DWORD err) {
    switch (err) {
        case ERROR_FILE_NOT_FOUND:      return 2;   /* ENOENT */
        case ERROR_PATH_NOT_FOUND:      return 2;
        case ERROR_ACCESS_DENIED:       return 13;  /* EACCES */
        case ERROR_FILE_EXISTS:         return 17;  /* EEXIST */
        case ERROR_ALREADY_EXISTS:      return 17;
        case ERROR_DISK_FULL:           return 28;  /* ENOSPC */
        case ERROR_NOT_ENOUGH_MEMORY:   return 12;  /* ENOMEM */
        case ERROR_INVALID_HANDLE:      return 9;   /* EBADF */
        case ERROR_INVALID_PARAMETER:   return 22;  /* EINVAL */
        case ERROR_DIRECTORY:           return 20;  /* ENOTDIR */
        case ERROR_READ_FAULT:          return 5;   /* EIO */
        case ERROR_WRITE_FAULT:         return 5;
        case ERROR_DIR_NOT_EMPTY:       return 39;  /* ENOTEMPTY */
        case ERROR_NOT_SAME_DEVICE:     return 18;  /* EXDEV */
        case ERROR_SHARING_VIOLATION:   return 11;  /* EAGAIN */
        case ERROR_LOCK_VIOLATION:      return 11;
        case ERROR_BUFFER_OVERFLOW:     return 36;  /* ENAMETOOLONG */
        default:                        return 5;   /* EIO */
    }
}

static void set_errno_from_win(void) {
    errno = win_error_to_linux_errno(GetLastError());
}

void __jnative_fn_java_io_UnixFileSystem_initIDs___V(void) {
}

int32_t __jnative_fn_java_io_UnixFileSystem_getBooleanAttributes0__Ljava_io_File__I(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();

    wchar_t path[PATH_MAX];
    if (extract_file_path_wide(file_obj, path, PATH_MAX) < 0) return 0;

    int32_t result = 0;
    DWORD attrs = GetFileAttributesW(path);
    if (attrs != INVALID_FILE_ATTRIBUTES) {
        result |= BA_EXISTS;
        if (attrs & FILE_ATTRIBUTE_DIRECTORY) result |= BA_DIRECTORY;
        else                                  result |= BA_REGULAR;
        if (attrs & FILE_ATTRIBUTE_HIDDEN)    result |= BA_HIDDEN;
    }

    const wchar_t* base = wcsrchr(path, L'\\');
    if (base == NULL) base = wcsrchr(path, L'/');
    base = (base != NULL) ? base + 1 : path;
    if (base[0] == L'.' && base[1] != L'\0') {
        if (!(base[1] == L'.' && base[2] == L'\0')) {
            result |= BA_HIDDEN;
        }
    }
    return result;
}

int32_t __jnative_fn_java_io_UnixFileSystem_checkAccess0__Ljava_io_File_I_Z(
        void* this_fs, void* file_obj, int32_t access_mode)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    wchar_t path[PATH_MAX];
    if (extract_file_path_wide(file_obj, path, PATH_MAX) < 0) return 0;

    /* X_OK (1) is not meaningful on Windows; the existence test passes
     * for every other mode combination supported by _waccess. */
    int mode = access_mode & (R_OK | W_OK);
    if (access_mode & X_OK) return 0;
    if (mode == 0) mode = F_OK;
    return (_waccess(path, mode) == 0) ? 1 : 0;
}

int64_t __jnative_fn_java_io_UnixFileSystem_getLength0__Ljava_io_File__J(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    wchar_t path[PATH_MAX];
    if (extract_file_path_wide(file_obj, path, PATH_MAX) < 0) return 0;

    WIN32_FILE_ATTRIBUTE_DATA info;
    if (!GetFileAttributesExW(path, GetFileExInfoStandard, &info)) return 0;
    return ((int64_t)info.nFileSizeHigh << 32) | (int64_t)info.nFileSizeLow;
}

int64_t __jnative_fn_java_io_UnixFileSystem_getLastModifiedTime0__Ljava_io_File__J(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    wchar_t path[PATH_MAX];
    if (extract_file_path_wide(file_obj, path, PATH_MAX) < 0) return 0;

    WIN32_FILE_ATTRIBUTE_DATA info;
    if (!GetFileAttributesExW(path, GetFileExInfoStandard, &info)) return 0;

    ULARGE_INTEGER ull;
    ull.LowPart  = info.ftLastWriteTime.dwLowDateTime;
    ull.HighPart = info.ftLastWriteTime.dwHighDateTime;

    /* FILETIME = 100-ns intervals since 1601-01-01.  Convert to
     * milliseconds since the Unix epoch (1970-01-01). */
    int64_t unix_ms = (int64_t)(ull.QuadPart / 10000ULL) - 11644473600000LL;
    return unix_ms;
}

int32_t __jnative_fn_java_io_UnixFileSystem_delete0__Ljava_io_File__Z(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    wchar_t path[PATH_MAX];
    if (extract_file_path_wide(file_obj, path, PATH_MAX) < 0) return 0;

    DWORD attrs = GetFileAttributesW(path);
    if (attrs == INVALID_FILE_ATTRIBUTES) return 0;

    if (attrs & FILE_ATTRIBUTE_DIRECTORY) {
        return RemoveDirectoryW(path) ? 1 : 0;
    }
    return DeleteFileW(path) ? 1 : 0;
}

void* __jnative_fn_java_io_UnixFileSystem_list0__Ljava_io_File___Ljava_lang_String_(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();

    wchar_t path[PATH_MAX];
    if (extract_file_path_wide(file_obj, path, PATH_MAX) < 0) return NULL;

    size_t plen = wcslen(path);
    if (plen + 3 >= PATH_MAX) return NULL;
    if (plen > 0 && path[plen - 1] != L'\\' && path[plen - 1] != L'/') {
        path[plen++] = L'\\';
    }
    path[plen++] = L'*';
    path[plen]   = 0;

    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(path, &fd);
    if (h == INVALID_HANDLE_VALUE) return NULL;

    size_t capacity = 16, count = 0;
    void** names = (void**)malloc(capacity * sizeof(void*));
    if (names == NULL) { FindClose(h); return NULL; }

    do {
        if (wcscmp(fd.cFileName, L".") == 0) continue;
        if (wcscmp(fd.cFileName, L"..") == 0) continue;

        int ulen = WideCharToMultiByte(CP_UTF8, 0, fd.cFileName, -1,
                                       NULL, 0, NULL, NULL);
        if (ulen <= 0) continue;
        char* utf8 = (char*)malloc((size_t)ulen);
        if (utf8 == NULL) continue;
        WideCharToMultiByte(CP_UTF8, 0, fd.cFileName, -1, utf8, ulen, NULL, NULL);
        void* str = __jnative_make_string_obj(utf8, ulen - 1);
        free(utf8);
        if (str == NULL) { free(names); FindClose(h); return NULL; }

        if (count == capacity) {
            capacity *= 2;
            void** grown = (void**)realloc(names, capacity * sizeof(void*));
            if (grown == NULL) { free(names); FindClose(h); return NULL; }
            names = grown;
        }
        names[count++] = str;
    } while (FindNextFileW(h, &fd));
    FindClose(h);

    void* array = jnative_ref_array_of_class(names, (int32_t)count,
                                             "[Ljava/lang/String;");
    free(names);
    return array;
}

void* __jnative_fn_java_io_UnixFileSystem_canonicalize0__Ljava_lang_String__Ljava_lang_String_(
        void* this_fs, void* path_str)
{
    (void)this_fs;
    if (path_str == NULL) __jnative_throw_null_pointer_exception();

    int32_t len = 0;
    const char* utf8 = __jnative_read_string_bytes(path_str, &len);
    if (utf8 == NULL || len <= 0) return path_str;

    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8, len, NULL, 0);
    if (wlen <= 0) return path_str;
    wchar_t* wpath = (wchar_t*)malloc((size_t)(wlen + 1) * sizeof(wchar_t));
    if (wpath == NULL) return path_str;
    MultiByteToWideChar(CP_UTF8, 0, utf8, len, wpath, wlen);
    wpath[wlen] = 0;

    wchar_t resolved[PATH_MAX];
    DWORD n = GetFullPathNameW(wpath, PATH_MAX, resolved, NULL);
    free(wpath);
    if (n == 0 || n >= PATH_MAX) return path_str;

    /* Verify the canonical path actually exists — matches realpath semantics */
    HANDLE h = CreateFileW(resolved, 0,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           NULL, OPEN_EXISTING,
                           FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (h == INVALID_HANDLE_VALUE) {
        set_errno_from_win();
        __jnative_throw_io_ctx("canonicalize", "UnixFileSystem");
    }
    CloseHandle(h);

    int ulen = WideCharToMultiByte(CP_UTF8, 0, resolved, -1,
                                   NULL, 0, NULL, NULL);
    if (ulen <= 0) return path_str;
    char* out_utf8 = (char*)malloc((size_t)ulen);
    if (out_utf8 == NULL) return path_str;
    WideCharToMultiByte(CP_UTF8, 0, resolved, -1, out_utf8, ulen, NULL, NULL);
    void* result = __jnative_make_string_obj(out_utf8, ulen - 1);
    free(out_utf8);
    return result;
}

/* ============================================================================
 *  POSIX implementation (unchanged)
 * ========================================================================= */
#else  /* !_WIN32 */

__attribute__((noreturn))
static void ufs_throw_io(const char* msg) {
    __jnative_throw_io_ctx(msg ? msg : "I/O error", "UnixFileSystem");
}

static int extract_file_path(void* file_obj, char* out, size_t out_cap) {
    if (out_cap == 0) return -1;
    out[0] = '\0';
    if (file_obj == NULL) return -1;
    void* path_str = *(void**)((char*)file_obj + FILE_PATH_OFFSET);
    if (path_str == NULL) return -1;
    int32_t n = __jnative_read_string_into(path_str, out, (int32_t)out_cap);
    return (n < 0) ? -1 : 0;
}

void __jnative_fn_java_io_UnixFileSystem_initIDs___V(void) {}

int32_t __jnative_fn_java_io_UnixFileSystem_getBooleanAttributes0__Ljava_io_File__I(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    char path[PATH_MAX];
    if (extract_file_path(file_obj, path, sizeof(path)) < 0) return 0;

    int32_t result = 0;
    struct stat st;
    if (stat(path, &st) == 0) {
        result |= BA_EXISTS;
        if (S_ISREG(st.st_mode)) result |= BA_REGULAR;
        if (S_ISDIR(st.st_mode)) result |= BA_DIRECTORY;
    }

    const char* base = strrchr(path, '/');
    base = (base != NULL) ? base + 1 : path;
    if (base[0] == '.' && base[1] != '\0') {
        int is_dot = (base[1] == '.' && base[2] == '\0');
        if (!is_dot) result |= BA_HIDDEN;
    }
    return result;
}

int32_t __jnative_fn_java_io_UnixFileSystem_checkAccess0__Ljava_io_File_I_Z(
        void* this_fs, void* file_obj, int32_t access_mode)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    char path[PATH_MAX];
    if (extract_file_path(file_obj, path, sizeof(path)) < 0) return 0;
    return access(path, (int)access_mode) == 0 ? 1 : 0;
}

int64_t __jnative_fn_java_io_UnixFileSystem_getLength0__Ljava_io_File__J(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    char path[PATH_MAX];
    if (extract_file_path(file_obj, path, sizeof(path)) < 0) return 0;
    struct stat st;
    if (stat(path, &st) < 0) return 0;
    return (int64_t)st.st_size;
}

int64_t __jnative_fn_java_io_UnixFileSystem_getLastModifiedTime0__Ljava_io_File__J(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    char path[PATH_MAX];
    if (extract_file_path(file_obj, path, sizeof(path)) < 0) return 0;
    struct stat st;
    if (stat(path, &st) < 0) return 0;
    return (int64_t)st.st_mtime * 1000LL;
}

int32_t __jnative_fn_java_io_UnixFileSystem_delete0__Ljava_io_File__Z(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    char path[PATH_MAX];
    if (extract_file_path(file_obj, path, sizeof(path)) < 0) return 0;

    if (unlink(path) == 0) return 1;
    if (errno == EISDIR || errno == EPERM) {
        struct stat st;
        if (stat(path, &st) == 0 && S_ISDIR(st.st_mode)) {
            if (rmdir(path) == 0) return 1;
        }
    }
    return 0;
}

void* __jnative_fn_java_io_UnixFileSystem_list0__Ljava_io_File___Ljava_lang_String_(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) __jnative_throw_null_pointer_exception();
    char path[PATH_MAX];
    if (extract_file_path(file_obj, path, sizeof(path)) < 0) return NULL;

    DIR* dir = opendir(path);
    if (dir == NULL) return NULL;

    size_t capacity = 16, count = 0;
    void** names = (void**)malloc(capacity * sizeof(void*));
    if (names == NULL) { closedir(dir); return NULL; }

    struct dirent* entry;
    errno = 0;
    while ((entry = readdir(dir)) != NULL) {
        const char* name = entry->d_name;
        if (name == NULL) continue;
        if (name[0] == '.' && name[1] == '\0') continue;
        if (name[0] == '.' && name[1] == '.' && name[2] == '\0') continue;

        void* str = __jnative_make_string_obj(name, (int32_t)strlen(name));
        if (str == NULL) { free(names); closedir(dir); return NULL; }

        if (count == capacity) {
            capacity *= 2;
            void** grown = (void**)realloc(names, capacity * sizeof(void*));
            if (grown == NULL) { free(names); closedir(dir); return NULL; }
            names = grown;
        }
        names[count++] = str;
        errno = 0;
    }
    int readdir_error = (errno != 0);
    closedir(dir);

    if (readdir_error) { free(names); return NULL; }

    void* array = jnative_ref_array_of_class(names, (int32_t)count,
                                             "[Ljava/lang/String;");
    free(names);
    return array;
}

void* __jnative_fn_java_io_UnixFileSystem_canonicalize0__Ljava_lang_String__Ljava_lang_String_(
        void* this_fs, void* path_str)
{
    (void)this_fs;
    if (path_str == NULL) __jnative_throw_null_pointer_exception();

    char path_buf[PATH_MAX];
    int32_t n = __jnative_read_string_into(path_str, path_buf,
                                           (int32_t)sizeof(path_buf));
    if (n < 0) ufs_throw_io("path too long");
    if (n == 0) return path_str;

    char resolved[PATH_MAX];
    if (realpath(path_buf, resolved) == NULL) {
        ufs_throw_io("realpath failed");
    }
    return __jnative_make_string_obj(resolved, (int32_t)strlen(resolved));
}

#endif /* _WIN32 */

/* ============================================================================
 *  sun.nio.fs.UnixFileSystem.bufferedCopy0
 *
 *  This native lives in this translation unit (rather than in a separate
 *  jnative/sun/nio/fs/UnixFileSystem.c) so that it is compiled under the
 *  same `_WIN32` branch selection as the java.io natives.  It is called by
 *  sun.nio.fs.UnixCopyFile#copyFile and copies a fixed-size range from
 *  src_fd at offset `offset` into dst_fd.
 * ========================================================================= */
#if defined(_WIN32)

/* File-descriptor → HANDLE mapping is shared with the UnixNativeDispatcher
 * translation unit (see section 3 below);  here we only need the two open
 * fds as Win32 handles, so we re-declare the mapping as extern. */
extern HANDLE jn_win_fd_to_handle(int fd);

void __jnative_fn_sun_nio_fs_UnixFileSystem_bufferedCopy0__IIJIJ_V(
        void* this_fs, int32_t dst_fd, int32_t src_fd,
        int64_t address, int32_t len, int64_t offset)
{
    (void)this_fs;
    if (len <= 0) return;

    HANDLE hs = jn_win_fd_to_handle(src_fd);
    HANDLE hd = jn_win_fd_to_handle(dst_fd);
    if (hs == INVALID_HANDLE_VALUE || hd == INVALID_HANDLE_VALUE) {
        __jnative_throw_io_ctx("bufferedCopy0: bad fd", "UnixFileSystem");
    }

    /* Use the supplied buffer when non-zero;  otherwise use a private
     * stack buffer sized to 64 KiB — the same chunk size the reference
     * implementation uses. */
    char  stackbuf[65536];
    char* buf      = (address != 0) ? (char*)(intptr_t)address : stackbuf;
    size_t bufsize = (address != 0) ? (size_t)len : sizeof(stackbuf);
    if (bufsize == 0) bufsize = sizeof(stackbuf);

    int32_t remaining = len;

    while (remaining > 0) {
        DWORD want = (DWORD)((remaining < (int32_t)bufsize) ? remaining
                                                            : (int32_t)bufsize);
        OVERLAPPED rov;
        memset(&rov, 0, sizeof(rov));
        rov.Offset     = (DWORD)(offset & 0xFFFFFFFF);
        rov.OffsetHigh = (DWORD)((offset >> 32) & 0xFFFFFFFF);

        DWORD got = 0;
        if (!ReadFile(hs, buf, want, &got, &rov)) {
            __jnative_throw_io_ctx("bufferedCopy0: ReadFile",
                                   "UnixFileSystem");
        }
        if (got == 0) break;  /* EOF */

        DWORD written = 0;
        if (!WriteFile(hd, buf, got, &written, NULL)) {
            __jnative_throw_io_ctx("bufferedCopy0: WriteFile",
                                   "UnixFileSystem");
        }
        offset    += written;
        remaining -= (int32_t)written;
    }
}

#else  /* !_WIN32 */

void __jnative_fn_sun_nio_fs_UnixFileSystem_bufferedCopy0__IIJIJ_V(
        void* this_fs, int32_t dst_fd, int32_t src_fd,
        int64_t address, int32_t len, int64_t offset)
{
    (void)this_fs;
    if (address == 0) {
        __jnative_throw_null_pointer_exception_ctx(
            "UnixFileSystem.bufferedCopy0", "address");
    }
    if (len <= 0) return;

    char* buf = (char*)(intptr_t)address;
    int64_t read_offset = offset;
    int32_t remaining = len;

    while (remaining > 0) {
        ssize_t r;
        do {
            r = pread(src_fd, buf, (size_t)remaining, (off_t)read_offset);
        } while (r < 0 && errno == EINTR);
        if (r < 0) __jnative_throw_io_ctx("pread", "UnixFileSystem.bufferedCopy0");
        if (r == 0) break;

        ssize_t written = 0;
        while (written < r) {
            ssize_t w;
            do {
                w = write(dst_fd, buf + written, (size_t)(r - written));
            } while (w < 0 && errno == EINTR);
            if (w < 0) __jnative_throw_io_ctx("write", "UnixFileSystem.bufferedCopy0");
            written += w;
        }
        read_offset += r;
        remaining   -= (int32_t)r;
    }
}

#endif /* _WIN32 */