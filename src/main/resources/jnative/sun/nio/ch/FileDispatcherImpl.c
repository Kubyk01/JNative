#define _GNU_SOURCE

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <winsock2.h>
#  include <windows.h>
#  include <io.h>
#else
#  include <unistd.h>
#  include <fcntl.h>
#  include <sys/types.h>
#  include <sys/stat.h>
#  include <sys/sendfile.h>
#  include <sys/uio.h>
#  include <errno.h>
#  include <string.h>
#endif

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

#define IOS_UNAVAILABLE (-2)
#define IOS_INTERRUPTED (-3)

#if defined(_WIN32)

#include <mswsock.h>
#include <winioctl.h>
#include <string.h>
#include <errno.h>

#define IOS_EOF              (-1)
#define IOS_THROWN           (-5)
#define IOS_UNSUPPORTED_CASE (-6)

#define FDI_NO_LOCK (-1)
#define FDI_LOCKED  0

#define FDI_MAP_RO 0
#define FDI_MAP_RW 1
#define FDI_MAP_PV 2

typedef struct {
    uint64_t base;
    uint64_t len;
} fdi_iovec;

__attribute__((noreturn))
static void fdi_throw_last(const char* what)
{
    DWORD code = GetLastError();
    char sysbuf[256];
    char buf[512];
    DWORD n = FormatMessageA(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        NULL, code, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        sysbuf, (DWORD)sizeof(sysbuf), NULL);
    while (n > 0 && (sysbuf[n - 1] == '\r' || sysbuf[n - 1] == '\n'
                     || sysbuf[n - 1] == ' ' || sysbuf[n - 1] == '\t')) {
        sysbuf[--n] = '\0';
    }
    if (n > 0) {
        snprintf(buf, sizeof(buf), "%s: %s (error %lu)", what, sysbuf, (unsigned long)code);
    } else {
        snprintf(buf, sizeof(buf), "%s (error %lu)", what, (unsigned long)code);
    }
    __jnative_throw_exception(
        __jnative_construct_exception("vtable_java_io_IOException", buf));
}

static int fdi_field_offset(const char* cls, const char* name)
{
    for (ReflectionClass* c = jnative_class_by_name(cls); c != NULL; c = c->superclass) {
        if (c->fields == NULL) continue;
        for (ReflectionField** fp = c->fields; *fp != NULL; fp++) {
            if ((*fp)->name != NULL && strcmp((const char*)(*fp)->name, name) == 0) {
                return (*fp)->offset;
            }
        }
    }
    return -1;
}

static int fdi_handle_offset(void)
{
    static volatile LONG cached = -2;
    LONG off = cached;
    if (off == -2) {
        off = fdi_field_offset("java/io/FileDescriptor", "handle");
        cached = off;
    }
    return (int)off;
}

static HANDLE fdi_handle(void* fdo)
{
    if (fdo == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = jnative_fd_of(fdo);
    if (fd >= 0) return (HANDLE)_get_osfhandle(fd);
    int off = fdi_handle_offset();
    if (off < 0) return INVALID_HANDLE_VALUE;
    return (HANDLE)(intptr_t)*(int64_t*)((char*)fdo + off);
}

static int32_t fdi_ret(DWORD n, int reading)
{
    if (n > 0) return (int32_t)n;
    return reading ? IOS_EOF : 0;
}

static int64_t fdi_ret_long(int64_t n, int reading)
{
    if (n > 0) return n;
    return reading ? IOS_EOF : 0;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_read0__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t address, int32_t len)
{
    HANDLE h = fdi_handle(fdo);
    DWORD nread = 0;
    if (len == 0) return 0;
    if (!ReadFile(h, (LPVOID)(intptr_t)address, (DWORD)len, &nread, NULL)) {
        DWORD err = GetLastError();
        if (err == ERROR_BROKEN_PIPE) return IOS_EOF;
        if (err == ERROR_NO_DATA) return IOS_UNAVAILABLE;
        fdi_throw_last("Read failed");
    }
    return fdi_ret(nread, 1);
}

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t address, int32_t len)
{
    HANDLE h = fdi_handle(fdo);
    fdi_iovec* iov = (fdi_iovec*)(intptr_t)address;
    int64_t total = 0;
    BOOL ok = TRUE;
    for (int32_t i = 0; i < len; i++) {
        DWORD num = (DWORD)iov[i].len;
        DWORD nread = 0;
        ok = ReadFile(h, (LPVOID)(uintptr_t)iov[i].base, num, &nread, NULL);
        if (!ok) break;
        total += nread;
        if (nread < num) break;
    }
    if (!ok) {
        DWORD err = GetLastError();
        if (err == ERROR_BROKEN_PIPE) return IOS_EOF;
        if (err == ERROR_NO_DATA) return IOS_UNAVAILABLE;
        fdi_throw_last("Read failed");
    }
    return fdi_ret_long(total, 1);
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_pread0__Ljava_io_FileDescriptor_JIJ_I(
        void* fdo, int64_t address, int32_t len, int64_t offset)
{
    HANDLE h = fdi_handle(fdo);
    DWORD nread = 0;
    LARGE_INTEGER zero, cur;
    OVERLAPPED ov;
    if (len == 0) return 0;
    zero.QuadPart = 0;
    if (!SetFilePointerEx(h, zero, &cur, FILE_CURRENT)) fdi_throw_last("Seek failed");
    ZeroMemory(&ov, sizeof(ov));
    ov.Offset = (DWORD)offset;
    ov.OffsetHigh = (DWORD)(offset >> 32);
    if (!ReadFile(h, (LPVOID)(intptr_t)address, (DWORD)len, &nread, &ov)) {
        DWORD err = GetLastError();
        if (err == ERROR_HANDLE_EOF) return IOS_EOF;
        fdi_throw_last("Read failed");
    }
    if (!SetFilePointerEx(h, cur, NULL, FILE_BEGIN)) fdi_throw_last("Seek failed");
    return fdi_ret(nread, 1);
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_write0__Ljava_io_FileDescriptor_JIZ_I(
        void* fdo, int64_t address, int32_t len, int32_t append)
{
    HANDLE h = fdi_handle(fdo);
    DWORD written = 0;
    OVERLAPPED ov;
    LPOVERLAPPED lpov = NULL;
    if (append) {
        ZeroMemory(&ov, sizeof(ov));
        ov.Offset = 0xFFFFFFFF;
        ov.OffsetHigh = 0xFFFFFFFF;
        lpov = &ov;
    }
    if (!WriteFile(h, (LPCVOID)(intptr_t)address, (DWORD)len, &written, lpov)) {
        fdi_throw_last("Write failed");
    }
    return fdi_ret(written, 0);
}

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JIZ_J(
        void* fdo, int64_t address, int32_t len, int32_t append)
{
    HANDLE h = fdi_handle(fdo);
    fdi_iovec* iov = (fdi_iovec*)(intptr_t)address;
    int64_t total = 0;
    OVERLAPPED ov;
    LPOVERLAPPED lpov = NULL;
    if (append) {
        ZeroMemory(&ov, sizeof(ov));
        ov.Offset = 0xFFFFFFFF;
        ov.OffsetHigh = 0xFFFFFFFF;
        lpov = &ov;
    }
    for (int32_t i = 0; i < len; i++) {
        DWORD num = (DWORD)iov[i].len;
        DWORD written = 0;
        if (!WriteFile(h, (LPCVOID)(uintptr_t)iov[i].base, num, &written, lpov)) {
            fdi_throw_last("Write failed");
        }
        total += written;
        if (written < num) break;
    }
    return fdi_ret_long(total, 0);
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_pwrite0__Ljava_io_FileDescriptor_JIJ_I(
        void* fdo, int64_t address, int32_t len, int64_t offset)
{
    HANDLE h = fdi_handle(fdo);
    DWORD written = 0;
    LARGE_INTEGER zero, cur;
    OVERLAPPED ov;
    zero.QuadPart = 0;
    if (!SetFilePointerEx(h, zero, &cur, FILE_CURRENT)) fdi_throw_last("Seek failed");
    ZeroMemory(&ov, sizeof(ov));
    ov.Offset = (DWORD)offset;
    ov.OffsetHigh = (DWORD)(offset >> 32);
    if (!WriteFile(h, (LPCVOID)(intptr_t)address, (DWORD)len, &written, &ov)) {
        fdi_throw_last("Write failed");
    }
    if (!SetFilePointerEx(h, cur, NULL, FILE_BEGIN)) fdi_throw_last("Seek failed");
    return fdi_ret(written, 0);
}

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_seek0__Ljava_io_FileDescriptor_J_J(
        void* fdo, int64_t offset)
{
    HANDLE h = fdi_handle(fdo);
    LARGE_INTEGER dist, where;
    BOOL ok;
    if (offset < 0) {
        dist.QuadPart = 0;
        ok = SetFilePointerEx(h, dist, &where, FILE_CURRENT);
    } else {
        dist.QuadPart = offset;
        ok = SetFilePointerEx(h, dist, &where, FILE_BEGIN);
    }
    if (!ok) fdi_throw_last("Seek failed");
    return (int64_t)where.QuadPart;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_I(
        void* fdo, int32_t metadata)
{
    (void)metadata;
    HANDLE h = fdi_handle(fdo);
    if (!FlushFileBuffers(h) && GetLastError() != ERROR_ACCESS_DENIED) {
        fdi_throw_last("Force failed");
    }
    return 0;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_I(
        void* fdo, int64_t size)
{
    HANDLE h = fdi_handle(fdo);
    FILE_END_OF_FILE_INFO eof;
    eof.EndOfFile.QuadPart = size;
    if (!SetFileInformationByHandle(h, FileEndOfFileInfo, &eof, sizeof(eof))) {
        fdi_throw_last("Truncation failed");
    }
    return 0;
}

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_size0__Ljava_io_FileDescriptor__J(
        void* fdo)
{
    HANDLE h = fdi_handle(fdo);
    LARGE_INTEGER size;
    if (!GetFileSizeEx(h, &size)) fdi_throw_last("Size failed");
    return (int64_t)size.QuadPart;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_available0__Ljava_io_FileDescriptor__I(
        void* fdo)
{
    HANDLE h = fdi_handle(fdo);
    if (GetFileType(h) != FILE_TYPE_DISK) return 0;
    LARGE_INTEGER zero, pos, size;
    zero.QuadPart = 0;
    if (!SetFilePointerEx(h, zero, &pos, FILE_CURRENT)) fdi_throw_last("Available failed");
    if (!GetFileSizeEx(h, &size)) fdi_throw_last("Available failed");
    int64_t avail = (int64_t)size.QuadPart - (int64_t)pos.QuadPart;
    if (avail > INT32_MAX) return INT32_MAX;
    if (avail < 0) return 0;
    return (int32_t)avail;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_isOther0__Ljava_io_FileDescriptor__Z(
        void* fdo)
{
    HANDLE h = fdi_handle(fdo);
    BY_HANDLE_FILE_INFORMATION info;
    if (!GetFileInformationByHandle(h, &info)) fdi_throw_last("isOther failed");
    DWORD attr = info.dwFileAttributes;
    if (attr & FILE_ATTRIBUTE_DEVICE) return 1;
    if (attr & FILE_ATTRIBUTE_REPARSE_POINT) {
        void* out = malloc(MAXIMUM_REPARSE_DATA_BUFFER_SIZE);
        if (out == NULL) __jnative_throw_out_of_memory_error_ctx("isOther0");
        DWORD returned = 0;
        BOOL ok = DeviceIoControl(h, FSCTL_GET_REPARSE_POINT, NULL, 0, out,
                                  MAXIMUM_REPARSE_DATA_BUFFER_SIZE, &returned, NULL);
        DWORD err = GetLastError();
        ULONG tag = *(ULONG*)out;
        free(out);
        if (!ok) {
            SetLastError(err);
            fdi_throw_last("DeviceIoControl failed");
        }
        if (tag != IO_REPARSE_TAG_SYMLINK) return 1;
    }
    return 0;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_lock0__Ljava_io_FileDescriptor_ZJJZ_I(
        void* fdo, int32_t block, int64_t pos, int64_t size, int32_t shared)
{
    HANDLE h = fdi_handle(fdo);
    DWORD flags = 0;
    OVERLAPPED o;
    ZeroMemory(&o, sizeof(o));
    o.Offset = (DWORD)pos;
    o.OffsetHigh = (DWORD)(pos >> 32);
    if (!block) flags |= LOCKFILE_FAIL_IMMEDIATELY;
    if (!shared) flags |= LOCKFILE_EXCLUSIVE_LOCK;
    if (!LockFileEx(h, flags, 0, (DWORD)size, (DWORD)(size >> 32), &o)) {
        DWORD err = GetLastError();
        if (err == ERROR_IO_PENDING) {
            DWORD bytes;
            if (GetOverlappedResult(h, &o, &bytes, TRUE)) return FDI_LOCKED;
            err = GetLastError();
        }
        if (err == ERROR_LOCK_VIOLATION) return FDI_NO_LOCK;
        SetLastError(err);
        fdi_throw_last("Lock failed");
    }
    return FDI_LOCKED;
}

void __jnative_fn_sun_nio_ch_FileDispatcherImpl_release0__Ljava_io_FileDescriptor_JJ_V(
        void* fdo, int64_t pos, int64_t size)
{
    HANDLE h = fdi_handle(fdo);
    OVERLAPPED o;
    ZeroMemory(&o, sizeof(o));
    o.Offset = (DWORD)pos;
    o.OffsetHigh = (DWORD)(pos >> 32);
    if (!UnlockFileEx(h, 0, (DWORD)size, (DWORD)(size >> 32), &o)) {
        DWORD err = GetLastError();
        if (err == ERROR_IO_PENDING) {
            DWORD bytes;
            if (GetOverlappedResult(h, &o, &bytes, TRUE)) return;
            err = GetLastError();
        }
        if (err != ERROR_NOT_LOCKED) {
            SetLastError(err);
            fdi_throw_last("Release failed");
        }
    }
}

void __jnative_fn_sun_nio_ch_FileDispatcherImpl_close0__Ljava_io_FileDescriptor__V(
        void* fdo)
{
    if (fdo == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = jnative_fd_of(fdo);
    if (fd >= 0) {
        *(int32_t*)((char*)fdo + FD_OFFSET) = -1;
        if (_close(fd) != 0) {
            __jnative_throw_exception(__jnative_construct_exception(
                "vtable_java_io_IOException", "Close failed"));
        }
        return;
    }
    HANDLE h = fdi_handle(fdo);
    if (h != INVALID_HANDLE_VALUE && !CloseHandle(h)) fdi_throw_last("Close failed");
}

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_duplicateHandle__J_J(int64_t handle)
{
    HANDLE proc = GetCurrentProcess();
    HANDLE result;
    if (!DuplicateHandle(proc, (HANDLE)(intptr_t)handle, proc, &result,
                         0, FALSE, DUPLICATE_SAME_ACCESS)) {
        fdi_throw_last("DuplicateHandle failed");
    }
    return (int64_t)(intptr_t)result;
}

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_allocationGranularity0___J(void)
{
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    return (int64_t)si.dwAllocationGranularity;
}

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_map0__Ljava_io_FileDescriptor_IJJZ_J(
        void* fdo, int32_t prot, int64_t off, int64_t len, int32_t is_sync)
{
    HANDLE fh = fdi_handle(fdo);
    int64_t max_size = off + len;
    DWORD protect = PAGE_READONLY;
    DWORD access = FILE_MAP_READ;
    if (is_sync) {
        __jnative_throw_exception(__jnative_construct_exception(
            "vtable_java_lang_InternalError",
            "should never call map on platform where MAP_SYNC is unimplemented"));
    }
    if (prot == FDI_MAP_RW) {
        protect = PAGE_READWRITE;
        access = FILE_MAP_WRITE;
    } else if (prot == FDI_MAP_PV) {
        protect = PAGE_WRITECOPY;
        access = FILE_MAP_COPY;
    }
    HANDLE mapping = CreateFileMappingW(fh, NULL, protect,
                                        (DWORD)(max_size >> 32), (DWORD)max_size, NULL);
    if (mapping == NULL) fdi_throw_last("Map failed");
    void* addr = MapViewOfFile(mapping, access, (DWORD)(off >> 32), (DWORD)off, (SIZE_T)len);
    DWORD map_err = GetLastError();
    if (!CloseHandle(mapping)) fdi_throw_last("Map failed");
    if (addr == NULL) {
        if (map_err == ERROR_NOT_ENOUGH_MEMORY) {
            __jnative_throw_exception(__jnative_construct_exception(
                "vtable_java_lang_OutOfMemoryError", "Map failed"));
        }
        SetLastError(map_err);
        fdi_throw_last("Map failed");
    }
    return (int64_t)(intptr_t)addr;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_unmap0__JJ_I(int64_t address, int64_t len)
{
    (void)len;
    if (!UnmapViewOfFile((LPCVOID)(intptr_t)address)) fdi_throw_last("Unmap failed");
    return 0;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_maxDirectTransferSize0___I(void)
{
    return INT32_MAX;
}

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferTo0__Ljava_io_FileDescriptor_JJLjava_io_FileDescriptor_Z_J(
        void* src_fdo, int64_t position, int64_t count, void* dst_fdo, int32_t append)
{
    (void)append;
    HANDLE src = fdi_handle(src_fdo);
    if (dst_fdo == NULL) __jnative_throw_null_pointer_exception();
    SOCKET dst = (SOCKET)(uintptr_t)(uint32_t)jnative_fd_of(dst_fdo);
    DWORD chunk = (count > INT32_MAX) ? (DWORD)INT32_MAX : (DWORD)count;
    GUID guid = WSAID_TRANSMITFILE;
    LPFN_TRANSMITFILE transmit = NULL;
    DWORD bytes = 0;
    if (WSAIoctl(dst, SIO_GET_EXTENSION_FUNCTION_POINTER, &guid, sizeof(guid),
                 &transmit, sizeof(transmit), &bytes, NULL, NULL) == SOCKET_ERROR
        || transmit == NULL) {
        return IOS_UNSUPPORTED_CASE;
    }
    LARGE_INTEGER where;
    where.QuadPart = position;
    if (!SetFilePointerEx(src, where, &where, FILE_BEGIN)) fdi_throw_last("SetFilePointerEx failed");
    if (!transmit(dst, src, chunk, 524288, NULL, NULL, TF_USE_KERNEL_APC)) {
        int err = WSAGetLastError();
        if (err == WSAEINVAL && count >= 0) return IOS_UNSUPPORTED_CASE;
        if (err == WSAENOTSOCK) return IOS_UNSUPPORTED_CASE;
        SetLastError((DWORD)err);
        fdi_throw_last("transfer failed");
    }
    return (int64_t)chunk;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_setDirect0__Ljava_io_FileDescriptor_Ljava_nio_CharBuffer__I(
        void* fdo, void* buffer)
{
    static volatile LONG addr_off = -2;
    HANDLE orig = fdi_handle(fdo);
    if (buffer == NULL) __jnative_throw_null_pointer_exception();
    LONG off = addr_off;
    if (off == -2) {
        off = fdi_field_offset("java/nio/Buffer", "address");
        addr_off = off;
    }
    if (off < 0) {
        __jnative_throw_exception(__jnative_construct_exception(
            "vtable_java_io_IOException", "DirectIO setup failed"));
    }
    HANDLE modify = ReOpenFile(orig, 0, 0, FILE_FLAG_NO_BUFFERING);
    if (modify == INVALID_HANDLE_VALUE) return -1;
    CloseHandle(modify);
    LPCWSTR root = (LPCWSTR)(intptr_t)*(int64_t*)((char*)buffer + off);
    DWORD spc, bps, nfc, tnc;
    if (!GetDiskFreeSpaceW(root, &spc, &bps, &nfc, &tnc)) fdi_throw_last("DirectIO setup failed");
    return (int32_t)bps;
}

#else

/* =========== portable FD and pread/pwrite/read/write wrappers =========== */

static inline int32_t fdi_fdval(void* fdo) {
    if (fdo == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = jnative_fd_of(fdo);
    if (fd < 0) {
        void* exc = __jnative_construct_exception(
            "vtable_java_io_IOException", "Stream Closed");
        __jnative_throw_exception(exc);
    }
    return fd;
}

static inline int fdi_is_would_block(int e) {
    return e == EAGAIN || e == EWOULDBLOCK;
}

static inline int fdi_is_interrupted(int e) {
    return e == EINTR;
}

__attribute__((noreturn))
static void fdi_throw_io_code(const char* what, int32_t code) {
    char buf[512];
    snprintf(buf, sizeof(buf), "%s: %s (errno %d)",
             what ? what : "I/O error", strerror((int)code), (int)code);
    void* exc = __jnative_construct_exception("vtable_java_io_IOException", buf);
    __jnative_throw_exception(exc);
}

static inline int32_t fdi_errno(void) {
    return (int32_t)errno;
}

__attribute__((noreturn))
static void fdi_throw_io(const char* what) {
    fdi_throw_io_code(what, fdi_errno());
}


static int64_t fdi_pread(int fd, void* buf, size_t n, int64_t off) {
    return (int64_t)pread(fd, buf, n, (off_t)off);
}
static int64_t fdi_pwrite(int fd, const void* buf, size_t n, int64_t off) {
    return (int64_t)pwrite(fd, buf, n, (off_t)off);
}
static int64_t fdi_read(int fd, void* buf, size_t n) {
    return (int64_t)read(fd, buf, n);
}
static int64_t fdi_write(int fd, const void* buf, size_t n) {
    return (int64_t)write(fd, buf, n);
}
static int64_t fdi_lseek(int fd, int64_t off, int whence) {
    return (int64_t)lseek(fd, (off_t)off, whence);
}
static int fdi_close(int fd) { return close(fd); }
static int fdi_commit(int fd) {
#  if defined(__APPLE__)
    return fcntl(fd, F_FULLFSYNC, 0);
#  else
    return fsync(fd);
#  endif
}

/* =========== init0 =========== */

void __jnative_fn_sun_nio_ch_FileDispatcherImpl_init0___V(void) {
}

/* =========== read0 / write0 =========== */

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_read0__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t address, int32_t len)
{
    int fd = fdi_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

    int64_t n;
    do {
        n = fdi_read(fd, buf, (size_t)len);
    } while (n < 0 && fdi_is_interrupted(fdi_errno()));

    if (n < 0) {
        if (fdi_is_would_block(fdi_errno())) return IOS_UNAVAILABLE;
        fdi_throw_io("read0 failed");
    }
    return (int32_t)n;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_write0__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t address, int32_t len)
{
    int fd = fdi_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

    int64_t n;
    do {
        n = fdi_write(fd, buf, (size_t)len);
    } while (n < 0 && fdi_is_interrupted(fdi_errno()));

    if (n < 0) {
        if (fdi_is_would_block(fdi_errno())) return IOS_UNAVAILABLE;
        fdi_throw_io("write0 failed");
    }
    return (int32_t)n;
}

/* =========== pread0 / pwrite0 =========== */

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_pread0__Ljava_io_FileDescriptor_JIJ_I(
        void* fdo, int64_t address, int32_t len, int64_t position)
{
    int fd = fdi_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

    int64_t n;
    do {
        n = fdi_pread(fd, buf, (size_t)len, position);
    } while (n < 0 && fdi_is_interrupted(fdi_errno()));

    if (n < 0) {
        if (fdi_is_would_block(fdi_errno())) return IOS_UNAVAILABLE;
        fdi_throw_io("pread0 failed");
    }
    return (int32_t)n;
}

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_pwrite0__Ljava_io_FileDescriptor_JIJ_I(
        void* fdo, int64_t address, int32_t len, int64_t position)
{
    int fd = fdi_fdval(fdo);
    void* buf = (void*)(intptr_t)address;

    int64_t n;
    do {
        n = fdi_pwrite(fd, buf, (size_t)len, position);
    } while (n < 0 && fdi_is_interrupted(fdi_errno()));

    if (n < 0) {
        if (fdi_is_would_block(fdi_errno())) return IOS_UNAVAILABLE;
        fdi_throw_io("pwrite0 failed");
    }
    return (int32_t)n;
}

/* =========== readv0 / writev0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t address, int32_t len)
{
    int fd = fdi_fdval(fdo);
    struct iovec* iov = (struct iovec*)(intptr_t)address;
    ssize_t n = readv(fd, iov, len);
    if (n < 0) {
        if (fdi_is_would_block(errno)) return (int64_t)IOS_UNAVAILABLE;
        if (fdi_is_interrupted(errno)) return (int64_t)IOS_INTERRUPTED;
        fdi_throw_io("readv failed");
    }
    return (int64_t)n;
}
int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t address, int32_t len)
{
    int fd = fdi_fdval(fdo);
    struct iovec* iov = (struct iovec*)(intptr_t)address;
    ssize_t n = writev(fd, iov, len);
    if (n < 0) {
        if (fdi_is_would_block(errno)) return (int64_t)IOS_UNAVAILABLE;
        if (fdi_is_interrupted(errno)) return (int64_t)IOS_INTERRUPTED;
        fdi_throw_io("writev failed");
    }
    return (int64_t)n;
}

/* =========== seek0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_seek0__Ljava_io_FileDescriptor_J_J(
        void* fdo, int64_t offset)
{
    int fd = fdi_fdval(fdo);
    int64_t p = fdi_lseek(fd, offset, SEEK_SET);
    if (p < 0) fdi_throw_io("lseek failed");
    return p;
}

/* =========== size0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_size0__Ljava_io_FileDescriptor_J(
        void* fdo)
{
    int fd = fdi_fdval(fdo);
    struct stat st;
    if (fstat(fd, &st) < 0) fdi_throw_io("fstat failed");
    return (int64_t)st.st_size;
}

/* =========== force0 =========== */

void __jnative_fn_sun_nio_ch_FileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(
        void* fdo, int32_t metadata)
{
    int fd = fdi_fdval(fdo);
    (void)metadata;
    if (fdi_commit(fd) != 0) fdi_throw_io("fsync failed");
}

/* =========== truncate0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_J(
        void* fdo, int64_t size)
{
    int fd = fdi_fdval(fdo);
    if (ftruncate(fd, (off_t)size) < 0) fdi_throw_io("ftruncate failed");
    return size;
}

/* =========== close0 =========== */

void __jnative_fn_sun_nio_ch_FileDispatcherImpl_close0__Ljava_io_FileDescriptor_V(
        void* fdo)
{
    int fd = fdi_fdval(fdo);
    fdi_close(fd);
    *(int32_t*)((char*)fdo + FD_OFFSET) = -1;
}

/* =========== dup0 =========== */

int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_dup0__Ljava_io_FileDescriptor_I(
        void* fdo)
{
    int fd = fdi_fdval(fdo);
    int nd = dup(fd);
    if (nd < 0) fdi_throw_io("dup failed");
    return nd;
}

/* =========== transferTo0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferTo0__Ljava_io_FileDescriptor_JJLjava_io_FileDescriptor_Z_J(
        void* src_fdo, int64_t position, int64_t count,
        void* dst_fdo, int32_t append)
{
    int src = fdi_fdval(src_fdo);
    int dst = fdi_fdval(dst_fdo);

    if (append) {
        if (fdi_lseek(dst, 0, SEEK_END) < 0) {
            fdi_throw_io("lseek dst to end failed");
        }
    }

    off_t off = (off_t)position;
    ssize_t sn = sendfile(dst, src, &off, (size_t)count);
    if (sn >= 0) return (int64_t)sn;
    if (errno != EINVAL && errno != ENOSYS && errno != EOPNOTSUPP) {
        fdi_throw_io("sendfile failed");
    }

    char buf[65536];
    int64_t total = 0;
    while (total < count) {
        size_t chunk = (size_t)((count - total < (int64_t)sizeof(buf))
                                ? (count - total) : (int64_t)sizeof(buf));

        int64_t nr;
        do {
            nr = fdi_pread(src, buf, chunk, position + total);
        } while (nr < 0 && fdi_is_interrupted(fdi_errno()));
        if (nr < 0) fdi_throw_io("pread src failed");
        if (nr == 0) break;

        int64_t written = 0;
        while (written < nr) {
            int64_t nw;
            do {
                nw = fdi_write(dst, buf + written, (size_t)(nr - written));
            } while (nw < 0 && fdi_is_interrupted(fdi_errno()));
            if (nw < 0) fdi_throw_io("write dst failed");
            if (nw == 0) return total;
            written += nw;
        }
        total += nr;
    }
    return total;
}

/* =========== transferFrom0 =========== */

int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferFrom0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_JJZ_J(
        void* src_fdo, void* dst_fdo, int64_t position, int64_t count,
        int32_t append)
{
    int src = fdi_fdval(src_fdo);
    int dst = fdi_fdval(dst_fdo);

    if (append) {
        if (fdi_lseek(dst, 0, SEEK_END) < 0) {
            fdi_throw_io("lseek dst to end failed");
        }
    } else {
        if (fdi_lseek(dst, position, SEEK_SET) < 0) {
            fdi_throw_io("lseek dst failed");
        }
    }

    char buf[65536];
    int64_t total = 0;
    while (total < count) {
        size_t chunk = (size_t)((count - total < (int64_t)sizeof(buf))
                                ? (count - total) : (int64_t)sizeof(buf));

        int64_t nr;
        do {
            nr = fdi_read(src, buf, chunk);
        } while (nr < 0 && fdi_is_interrupted(fdi_errno()));
        if (nr < 0) fdi_throw_io("read src failed");
        if (nr == 0) break;

        int64_t written = 0;
        while (written < nr) {
            int64_t nw;
            do {
                nw = fdi_write(dst, buf + written, (size_t)(nr - written));
            } while (nw < 0 && fdi_is_interrupted(fdi_errno()));
            if (nw < 0) fdi_throw_io("write dst failed");
            if (nw == 0) return total;
            written += nw;
        }
        total += nr;
    }
    return total;
}
#endif
