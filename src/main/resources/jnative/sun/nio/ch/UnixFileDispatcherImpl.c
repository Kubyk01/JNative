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
#  include <sys/mman.h>
#  include <errno.h>
#  include <string.h>
#  ifndef MAP_SYNC
#    define MAP_SYNC 0
#  endif
#endif

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/* ============================================================================
 *  Externs for the FileDispatcherImpl functions we forward to.
 *
 *  size0 and truncate0 are NOT in this list: their JDK 21 signatures differ
 *  between the two classes (return int on both, but FileDispatcherImpl's
 *  current C implementation returns long). Rather than depend on a cast
 *  through a possibly-mismatched extern, the two functions are implemented
 *  standalone in this file below.
 * ========================================================================== */

extern int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_read0__Ljava_io_FileDescriptor_JI_I(
        void*, int64_t, int32_t);
extern int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_write0__Ljava_io_FileDescriptor_JI_I(
        void*, int64_t, int32_t);
extern int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_pread0__Ljava_io_FileDescriptor_JIJ_I(
        void*, int64_t, int32_t, int64_t);
extern int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_pwrite0__Ljava_io_FileDescriptor_JIJ_I(
        void*, int64_t, int32_t, int64_t);
extern int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
        void*, int64_t, int32_t);
extern int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JI_J(
        void*, int64_t, int32_t);
extern int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_seek0__Ljava_io_FileDescriptor_J_J(
        void*, int64_t);
extern void    __jnative_fn_sun_nio_ch_FileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(
        void*, int32_t);
extern void    __jnative_fn_sun_nio_ch_FileDispatcherImpl_close0__Ljava_io_FileDescriptor_V(
        void*);
extern int32_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_dup0__Ljava_io_FileDescriptor_I(
        void*);
extern int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferTo0__Ljava_io_FileDescriptor_JJLjava_io_FileDescriptor_Z_J(
        void*, int64_t, int64_t, void*, int32_t);
extern int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferFrom0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_JJZ_J(
        void*, void*, int64_t, int64_t, int32_t);
extern void    __jnative_fn_sun_nio_ch_FileDispatcherImpl_init0___V(void);

/* ============================================================================
 *  Forwarding wrappers.
 * ========================================================================== */

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_read0__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t addr, int32_t len) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_read0__Ljava_io_FileDescriptor_JI_I(
            fdo, addr, len);
}

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_write0__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t addr, int32_t len) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_write0__Ljava_io_FileDescriptor_JI_I(
            fdo, addr, len);
}

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_pread0__Ljava_io_FileDescriptor_JIJ_I(
        void* fdo, int64_t addr, int32_t len, int64_t pos) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_pread0__Ljava_io_FileDescriptor_JIJ_I(
            fdo, addr, len, pos);
}

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_pwrite0__Ljava_io_FileDescriptor_JIJ_I(
        void* fdo, int64_t addr, int32_t len, int64_t pos) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_pwrite0__Ljava_io_FileDescriptor_JIJ_I(
            fdo, addr, len, pos);
}

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t addr, int32_t len) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_readv0__Ljava_io_FileDescriptor_JI_J(
            fdo, addr, len);
}

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JI_J(
        void* fdo, int64_t addr, int32_t len) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_writev0__Ljava_io_FileDescriptor_JI_J(
            fdo, addr, len);
}

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_seek0__Ljava_io_FileDescriptor_J_J(
        void* fdo, int64_t offset) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_seek0__Ljava_io_FileDescriptor_J_J(
            fdo, offset);
}

void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(
        void* fdo, int32_t meta) {
    __jnative_fn_sun_nio_ch_FileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(fdo, meta);
}

void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_close0__Ljava_io_FileDescriptor_V(
        void* fdo) {
    __jnative_fn_sun_nio_ch_FileDispatcherImpl_close0__Ljava_io_FileDescriptor_V(fdo);
}

int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_dup0__Ljava_io_FileDescriptor_I(
        void* fdo) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_dup0__Ljava_io_FileDescriptor_I(fdo);
}

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_transferTo0__Ljava_io_FileDescriptor_JJLjava_io_FileDescriptor_Z_J(
        void* src_fdo, int64_t position, int64_t count, void* dst_fdo,
        int32_t append) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferTo0__Ljava_io_FileDescriptor_JJLjava_io_FileDescriptor_Z_J(
            src_fdo, position, count, dst_fdo, append);
}

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_transferFrom0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_JJZ_J(
        void* src_fdo, void* dst_fdo, int64_t position, int64_t count,
        int32_t append) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferFrom0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_JJZ_J(
            src_fdo, dst_fdo, position, count, append);
}

void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_init0___V(void) {
    __jnative_fn_sun_nio_ch_FileDispatcherImpl_init0___V();
}

/* ============================================================================
 *  Local helpers.
 * ========================================================================== */

__attribute__((noreturn))
static void ufdi_throw_io(const char* what) {
    char buf[512];
#if defined(_WIN32)
    snprintf(buf, sizeof(buf), "%s (error %d)",
             what ? what : "I/O error", (int)GetLastError());
#else
    snprintf(buf, sizeof(buf), "%s: %s (errno %d)",
             what ? what : "I/O error", strerror(errno), errno);
#endif
    void* exc = __jnative_construct_exception("vtable_java_io_IOException", buf);
    __jnative_throw_exception(exc);
}

static inline int32_t ufdi_fdval(void* fdo) {
    if (fdo == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = jnative_fd_of(fdo);
    if (fd < 0) {
        void* exc = __jnative_construct_exception(
            "vtable_java_io_IOException", "Stream Closed");
        __jnative_throw_exception(exc);
    }
    return fd;
}

/* ============================================================================
 *  Standalone implementations for the two natives whose JDK 21 signatures
 *  differ between the two classes.
 * ========================================================================== */

/*
 * long size0(FileDescriptor fd) throws IOException;
 *
 * Mangled name carries TWO underscores before the return `J`: `;)J` yields
 * `__J` (semicolon and closing parenthesis each mangle to one underscore).
 * The previous version of this file used `_J` (one underscore), which was
 * a typo — the linker never matched the symbol the .ll requested.
 */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_size0__Ljava_io_FileDescriptor__J(
        void* fdo)
{
    int32_t fd = ufdi_fdval(fdo);
#if defined(_WIN32)
    intptr_t h = _get_osfhandle(fd);
    if (h == -1) ufdi_throw_io("_get_osfhandle failed");
    LARGE_INTEGER sz;
    if (!GetFileSizeEx((HANDLE)h, &sz))
        ufdi_throw_io("GetFileSizeEx failed");
    return (int64_t)sz.QuadPart;
#else
    struct stat st;
    if (fstat(fd, &st) < 0) ufdi_throw_io("fstat failed");
    return (int64_t)st.st_size;
#endif
}

/*
 * int truncate0(FileDescriptor fd, long size) throws IOException;
 *
 * JDK 21 returns int (older JDKs returned long). The mangled name ends
 * `_J_I` — `J` is the size argument, `I` is the return type.
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_I(
        void* fdo, int64_t size)
{
    int32_t fd = ufdi_fdval(fdo);
#if defined(_WIN32)
    intptr_t h = _get_osfhandle(fd);
    if (h == -1) ufdi_throw_io("_get_osfhandle failed");
    LARGE_INTEGER li;
    li.QuadPart = size;
    if (SetFilePointerEx((HANDLE)h, li, NULL, FILE_BEGIN) == 0)
        ufdi_throw_io("SetFilePointerEx failed");
    if (!SetEndOfFile((HANDLE)h))
        ufdi_throw_io("SetEndOfFile failed");
    return 0;
#else
    if (ftruncate(fd, (off_t)size) < 0) ufdi_throw_io("ftruncate failed");
    return 0;
#endif
}

/* ============================================================================
 *  Unix-specific natives (not in FileDispatcherImpl).
 * ========================================================================== */

/*
 * long allocationGranularity0() throws IOException;
 *
 * Returns the smallest alignment unit a memory mapping can use.
 * POSIX: the page size from sysconf(_SC_PAGE_SIZE).
 * Windows: SYSTEM_INFO.dwAllocationGranularity — the value is 64 KiB on
 * every x86/x64 Windows build and is what FileChannel.map must round
 * to.
 */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_allocationGranularity0___J(void)
{
#if defined(_WIN32)
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    return (int64_t)si.dwAllocationGranularity;
#else
    long ps = sysconf(_SC_PAGE_SIZE);
    return (ps > 0) ? (int64_t)ps : 4096;
#endif
}

/*
 * int setDirect0(FileDescriptor fd) throws IOException;
 *
 * Marks the fd as a direct (native-memory) mapping so subsequent
 * release0 / unmap0 calls can issue without consulting the channel's
 * bookkeeping. JDK 21 returns int (older JDKs returned void).
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_setDirect0__Ljava_io_FileDescriptor__I(
        void* fdo)
{
    (void)fdo;
    return 0;
}

/*
 * long map0(FileDescriptor fd, int prot, long position, long length,
 *           boolean isSync) throws IOException;
 */
int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_map0__Ljava_io_FileDescriptor_IJJZ_J(
        void* fdo, int32_t prot, int64_t position, int64_t length,
        int32_t isSync)
{
#if defined(_WIN32)
    (void)fdo; (void)prot; (void)position; (void)length; (void)isSync;
    ufdi_throw_io("map0 not supported on this platform");
#else
    int fd = ufdi_fdval(fdo);
    int flags = MAP_SHARED;
    if (isSync && MAP_SYNC != 0) {
        flags |= MAP_SYNC;
    }
    void* addr = mmap(NULL, (size_t)length, prot, flags, fd, (off_t)position);
    if (addr == MAP_FAILED) {
        ufdi_throw_io("mmap failed");
    }
    return (int64_t)(uintptr_t)addr;
#endif
}

/*
 * int unmap0(long address, long length) throws IOException;
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_unmap0__JJ_I(
        int64_t address, int64_t length)
{
#if defined(_WIN32)
    (void)address; (void)length;
    return 0;
#else
    if (munmap((void*)(uintptr_t)address, (size_t)length) < 0) {
        ufdi_throw_io("munmap failed");
    }
    return 0;
#endif
}

/*
 * void release0(FileDescriptor fd, long address, long length)
 *     throws IOException;
 */
void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_release0__Ljava_io_FileDescriptor_JJ_V(
        void* fdo, int64_t address, int64_t length)
{
#if defined(_WIN32)
    (void)fdo; (void)address; (void)length;
#else
    (void)fdo;
    if (munmap((void*)(uintptr_t)address, (size_t)length) < 0) {
        ufdi_throw_io("munmap (release) failed");
    }
#endif
}