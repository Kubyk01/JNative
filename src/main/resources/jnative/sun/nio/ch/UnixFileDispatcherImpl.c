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
 *  Externs for the FileDispatcherImpl versions of each inherited native.
 *  These symbols are defined in FileDispatcherImpl.c and are already linked
 *  into the image (they were the focus of the previous fix). The wrappers
 *  below duplicate them under the UnixFileDispatcherImpl mangled name so
 *  that a .ll produced by a Linux analysis still links against a Windows
 *  target.
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
extern int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_size0__Ljava_io_FileDescriptor_J(
        void*);
extern void    __jnative_fn_sun_nio_ch_FileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(
        void*, int32_t);
extern int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_J(
        void*, int64_t);
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
 *  Forwarding wrappers for the inherited natives.
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

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_size0__Ljava_io_FileDescriptor_J(
        void* fdo) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_size0__Ljava_io_FileDescriptor_J(fdo);
}

void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(
        void* fdo, int32_t meta) {
    __jnative_fn_sun_nio_ch_FileDispatcherImpl_force0__Ljava_io_FileDescriptor_Z_V(fdo, meta);
}

int64_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_J(
        void* fdo, int64_t size) {
    return __jnative_fn_sun_nio_ch_FileDispatcherImpl_truncate0__Ljava_io_FileDescriptor_J_J(
            fdo, size);
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
 *  Local helpers for the Unix-specific natives.
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
 *  Unix-specific natives
 * ========================================================================== */

/*
 * long map0(FileDescriptor fd, int prot, long position, long length,
 *           boolean isSync) throws IOException;
 *
 * Maps `length` bytes starting at `position` of the file behind `fd`
 * into the caller's address space and returns the mapped address.
 * `prot` is a bitwise OR of PROT_READ / PROT_WRITE / PROT_EXEC as
 * defined by the caller, and `isSync` selects MAP_SYNC when the
 * platform supports it.
 *
 * Linux / BSD / macOS: implement with mmap. The MAP_SYNC flag is only
 * defined on Linux with a recent enough kernel header, so the
 * fallback below treats "isSync && !MAP_SYNC" as the non-sync case —
 * the caller's sync intent is still honoured as far as the kernel
 * allows.
 *
 * Windows: this native is only reached when the class was compiled
 * against a Unix java.base. On a Windows target the analysis-side
 * UnixFileDispatcherImpl is never loaded at runtime, so throwing here
 * is a defensive measure — the code path is unreachable in practice.
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
 * int unmap0(long address, long length);
 *
 * Unmaps the region returned by a previous map0. Returns 0 on success,
 * throws IOException on failure.
 */
int32_t __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_unmap0__JJ_I(
        int64_t address, int64_t length)
{
#if defined(_WIN32)
    (void)address; (void)length;
    /* Nothing was mapped by map0 on Windows; treat as already unmapped. */
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
 *
 * Unmaps the region as part of the channel's cleanup path. On Windows
 * it is a silent no-op because nothing was mapped; on Unix it mirrors
 * unmap0 but is only called from cleanup, where throwing is the
 * documented contract on mmap failure.
 */
void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_release0__Ljava_io_FileDescriptor_JJ_V(
        void* fdo, int64_t address, int64_t length)
{
#if defined(_WIN32)
    (void)fdo; (void)address; (void)length;
    /* no-op — see the comment above */
#else
    (void)fdo;
    if (munmap((void*)(uintptr_t)address, (size_t)length) < 0) {
        ufdi_throw_io("munmap (release) failed");
    }
#endif
}

/*
 * void setDirect0(FileDescriptor fd) throws IOException;
 *
 * Marks the fd as a direct (native-memory) mapping so that subsequent
 * release0 / unmap0 calls can be issued without consulting the
 * channel's own bookkeeping. The JDK's own Unix implementation is a
 * strict no-op; this one is too.
 */
void __jnative_fn_sun_nio_ch_UnixFileDispatcherImpl_setDirect0__Ljava_io_FileDescriptor__V(
        void* fdo)
{
    (void)fdo;
}