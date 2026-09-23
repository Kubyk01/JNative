#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <limits.h>
#include <errno.h>
#include <time.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/xattr.h>

#include "jnative_runtime.h"

/*
 * sun.nio.fs.UnixNativeDispatcher — the low-level filesystem syscall
 * bindings that every Unix file-system provider in java.nio.file uses.
 *
 * The class contributes a broad family of entry points: stat/lstat/fstat,
 * statvfs, open/openat/close/dup, directory streams, mkdir/rmdir/unlink,
 * symlink/readlink, access, chmod/chown, timestamp setters, and the
 * Linux extended-attribute accessors. Each method is a thin wrapper
 * around exactly one syscall, with the error handling the JDK's
 * Java-side callers expect: a hard failure surfaces as an IOException
 * through the generic throw helper, while "the query succeeded but the
 * answer is negative" is reported via the return value.
 *
 * The two custom classes whose fields this file writes are
 * sun.nio.fs.UnixFileAttributes (15 instance fields) and
 * sun.nio.fs.UnixFileStoreAttributes (5 instance fields). The offsets
 * below are the ones LlvmGlobalEmitter.getFieldOffset computes from the
 * JDK's declared field order and the runtime's alignment rules, and
 * they are frozen by that computation: any change to the layout of
 * either class must be mirrored here synchronously.
 *
 * Layout for UnixFileAttributes:
 *    0 : vtable
 *    8 : int  st_mode
 *   12 : pad
 *   16 : long st_ino
 *   24 : long st_dev
 *   32 : long st_rdev
 *   40 : int  st_nlink
 *   44 : int  st_uid
 *   48 : int  st_gid
 *   52 : pad
 *   56 : long st_size
 *   64 : long st_atime_sec
 *   72 : long st_atime_nsec
 *   80 : long st_mtime_sec
 *   88 : long st_mtime_nsec
 *   96 : long st_ctime_sec
 *  104 : long st_ctime_nsec
 *  112 : long st_birthtime_sec
 *
 * Layout for UnixFileStoreAttributes:
 *    0 : vtable
 *    8 : long f_bsize
 *   16 : long f_frsize
 *   24 : long f_blocks
 *   32 : long f_bfree
 *   40 : long f_bavail
 */
#define UFA_ST_MODE          8
#define UFA_ST_INO           16
#define UFA_ST_DEV           24
#define UFA_ST_RDEV          32
#define UFA_ST_NLINK         40
#define UFA_ST_UID           44
#define UFA_ST_GID           48
#define UFA_ST_SIZE          56
#define UFA_ST_ATIME_SEC     64
#define UFA_ST_ATIME_NSEC    72
#define UFA_ST_MTIME_SEC     80
#define UFA_ST_MTIME_NSEC    88
#define UFA_ST_CTIME_SEC     96
#define UFA_ST_CTIME_NSEC    104
#define UFA_ST_BIRTHTIME_SEC 112

#define UFSA_F_BSIZE  8
#define UFSA_F_FRSIZE 16
#define UFSA_F_BLOCKS 24
#define UFSA_F_BFREE  32
#define UFSA_F_BAVAIL 40

/*
 * The path and pointer arguments arrive as jlong addresses that the
 * Java layer has obtained from a direct ByteBuffer. On every platform
 * this runtime targets, a jlong is exactly as wide as a pointer, so a
 * plain cast through intptr_t recovers the original C pointer without
 * any loss.
 */
static inline const char* path_of(int64_t address) {
    return (const char*)(intptr_t)address;
}

static inline void* ptr_of(int64_t address) {
    return (void*)(intptr_t)address;
}

static void fill_attrs(void* attrs, const struct stat* st) {
    if (attrs == NULL) return;
    char* p = (char*)attrs;

    *(int32_t*)(p + UFA_ST_MODE)  = (int32_t)st->st_mode;
    *(int64_t*)(p + UFA_ST_INO)   = (int64_t)st->st_ino;
    *(int64_t*)(p + UFA_ST_DEV)   = (int64_t)st->st_dev;
    *(int64_t*)(p + UFA_ST_RDEV)  = (int64_t)st->st_rdev;
    *(int32_t*)(p + UFA_ST_NLINK) = (int32_t)st->st_nlink;
    *(int32_t*)(p + UFA_ST_UID)   = (int32_t)st->st_uid;
    *(int32_t*)(p + UFA_ST_GID)   = (int32_t)st->st_gid;
    *(int64_t*)(p + UFA_ST_SIZE)  = (int64_t)st->st_size;

    *(int64_t*)(p + UFA_ST_ATIME_SEC)  = (int64_t)st->st_atim.tv_sec;
    *(int64_t*)(p + UFA_ST_ATIME_NSEC) = (int64_t)st->st_atim.tv_nsec;
    *(int64_t*)(p + UFA_ST_MTIME_SEC)  = (int64_t)st->st_mtim.tv_sec;
    *(int64_t*)(p + UFA_ST_MTIME_NSEC) = (int64_t)st->st_mtim.tv_nsec;
    *(int64_t*)(p + UFA_ST_CTIME_SEC)  = (int64_t)st->st_ctim.tv_sec;
    *(int64_t*)(p + UFA_ST_CTIME_NSEC) = (int64_t)st->st_ctim.tv_nsec;

    *(int64_t*)(p + UFA_ST_BIRTHTIME_SEC) = 0;
}

static void* make_byte_array(const void* data, size_t len) {
    if (len > (size_t)INT32_MAX) {
        __jnative_throw_exception(NULL);
    }
    /*
     * jnative_byte_array() builds the whole header — [B class mirror,
     * length and elem_size — so the result is indistinguishable from one
     * the LLVM emitter allocated via NEW_ARRAY.
     */
    void* arr = jnative_byte_array(data, (int32_t)len);
    if (arr == NULL) {
        __jnative_throw_exception(NULL);
    }
    return arr;
}

/*
 * static native int init();
 *
 * Called from UnixNativeDispatcher.<clinit>. In HotSpot this hook
 * probes for the availability of various Unix system calls and caches
 * the results in static Java fields. This runtime performs the same
 * probes lazily inside the functions that need them, so <clinit> has
 * nothing to install.
 *
 * Returns -1 to signal "no capability flags cached". The Java layer
 * checks for that sentinel and takes the conservative path.
 */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___I(void) {
    return -1;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_exists0__J_Z(
        int64_t pathAddress)
{
    struct stat st;
    return (stat(path_of(pathAddress), &st) == 0) ? 1 : 0;
}

/*
 * static native byte[] getcwd();
 *
 * Returns the current working directory as a byte[]. Uses getcwd(3) with
 * a buffer large enough for any path the kernel will report. Failures —
 * ENOENT after a concurrent rmdir, EACCES after a chdir into a
 * now-unreadable directory, ENOMEM for an over-long path — surface as
 * an IOException through the generic throw helper.
 */
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getcwd____B(void) {
    char buf[65536];

    if (getcwd(buf, sizeof(buf)) == NULL) {
        __jnative_throw_exception(NULL);
        return NULL;
    }

    return make_byte_array(buf, strlen(buf));
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_strerror__I__B(int32_t errnum) {
    const char* msg = strerror((int)errnum);
    if (msg == NULL) {
        msg = "Unknown error";
    }
    return make_byte_array(msg, strlen(msg));
}

/*
 * static native int stat0(long pathAddress, UnixFileAttributes attrs);
 *
 * Populates `attrs` from stat(2) on the given path. Symlinks are
 * followed. Returns 0 on success; any failure surfaces as an
 * IOException.
 */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_stat0__JLsun_nio_fs_UnixFileAttributes__I(
        int64_t pathAddress, void* attrs)
{
    struct stat st;
    if (stat(path_of(pathAddress), &st) < 0) {
        __jnative_throw_exception(NULL);
        return -1;
    }
    fill_attrs(attrs, &st);
    return 0;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lstat0__JLsun_nio_fs_UnixFileAttributes__V(
        int64_t pathAddress, void* attrs)
{
    struct stat st;
    if (lstat(path_of(pathAddress), &st) < 0) {
        __jnative_throw_exception(NULL);
        return;
    }
    fill_attrs(attrs, &st);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstat0__ILsun_nio_fs_UnixFileAttributes__V(
        int32_t fd, void* attrs)
{
    struct stat st;
    if (fstat((int)fd, &st) < 0) {
        __jnative_throw_exception(NULL);
        return;
    }
    fill_attrs(attrs, &st);
}

/*
 * static native void statvfs0(long pathAddress,
 *                             UnixFileStoreAttributes attrs);
 *
 * Populates `attrs` from statvfs(2) on the given path. The result
 * describes the mounted filesystem that contains the path, which is
 * what FileStore queries need.
 */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_statvfs0__JLsun_nio_fs_UnixFileStoreAttributes__V(
        int64_t pathAddress, void* attrs)
{
    struct statvfs vfs;
    if (statvfs(path_of(pathAddress), &vfs) < 0) {
        __jnative_throw_exception(NULL);
        return;
    }
    if (attrs == NULL) return;
    char* p = (char*)attrs;
    *(int64_t*)(p + UFSA_F_BSIZE)  = (int64_t)vfs.f_bsize;
    *(int64_t*)(p + UFSA_F_FRSIZE) = (int64_t)vfs.f_frsize;
    *(int64_t*)(p + UFSA_F_BLOCKS) = (int64_t)vfs.f_blocks;
    *(int64_t*)(p + UFSA_F_BFREE)  = (int64_t)vfs.f_bfree;
    *(int64_t*)(p + UFSA_F_BAVAIL) = (int64_t)vfs.f_bavail;
}

/*
 * static native int open0(long pathAddress, int flags, int mode);
 *
 * Thin wrapper around open(2). Returns the raw kernel descriptor on
 * success; any failure surfaces as an IOException.
 */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_open0__JII_I(
        int64_t pathAddress, int32_t flags, int32_t mode)
{
    int fd = open(path_of(pathAddress), (int)flags, (mode_t)mode);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)fd;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_openat0__IJII_I(
        int32_t dfd, int64_t pathAddress, int32_t flags, int32_t mode)
{
    int fd = openat((int)dfd, path_of(pathAddress), (int)flags, (mode_t)mode);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)fd;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_close0__I_V(int32_t fd) {
    if (fd < 0) return;
    (void)close((int)fd);
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_dup__I_I(int32_t fd) {
    int newfd = dup((int)fd);
    if (newfd < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)newfd;
}

/*
 * static native long opendir0(long pathAddress);
 *
 * Opens a directory stream via opendir(3). Returns the directory
 * stream pointer as a jlong on success; any failure surfaces as an
 * IOException. The pointer is only valid until the matching
 * closedir / fdopendir pair is invoked.
 */
int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_opendir0__J_J(
        int64_t pathAddress)
{
    DIR* d = opendir(path_of(pathAddress));
    if (d == NULL) {
        __jnative_throw_exception(NULL);
    }
    return (int64_t)(intptr_t)d;
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fdopendir__I_J(int32_t fd) {
    DIR* d = fdopendir((int)fd);
    if (d == NULL) {
        __jnative_throw_exception(NULL);
    }
    return (int64_t)(intptr_t)d;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_closedir__J_V(int64_t dirp) {
    if (dirp == 0) return;
    (void)closedir((DIR*)(intptr_t)dirp);
}

/*
 * static native byte[] readdir0(long dirp);
 *
 * Returns the raw d_name bytes of the next directory entry, or null
 * at end of stream. The caller (UnixDirectoryStream) turns the byte
 * array into a String.
 *
 * A readdir failure after the end of stream is signalled by a non-zero
 * errno; that is translated into an IOException rather than being
 * conflated with the normal end-of-directory case.
 */
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readdir0__J__B(int64_t dirp) {
    DIR* d = (DIR*)(intptr_t)dirp;
    if (d == NULL) return NULL;

    errno = 0;
    struct dirent* entry = readdir(d);
    if (entry == NULL) {
        if (errno != 0) {
            __jnative_throw_exception(NULL);
        }
        return NULL;
    }

    return make_byte_array(entry->d_name, strlen(entry->d_name));
}

/*
 * static native void mkdir0(long pathAddress, int mode);
 *
 * Creates a directory via mkdir(2). Any failure surfaces as an
 * IOException.
 */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_mkdir0__JI_V(
        int64_t pathAddress, int32_t mode)
{
    if (mkdir(path_of(pathAddress), (mode_t)mode) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rmdir0__J_V(int64_t pathAddress) {
    if (rmdir(path_of(pathAddress)) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlink0__J_V(int64_t pathAddress) {
    if (unlink(path_of(pathAddress)) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlinkat0__IJI_V(
        int32_t dfd, int64_t pathAddress, int32_t flags)
{
    if (unlinkat((int)dfd, path_of(pathAddress), (int)flags) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_symlink0__JJ_V(
        int64_t linkAddress, int64_t targetAddress)
{
    if (symlink(path_of(linkAddress), path_of(targetAddress)) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * static native byte[] readlink0(long pathAddress);
 *
 * Returns the target of a symbolic link as a byte[]. A symlink whose
 * target is longer than PATH_MAX is reported as ENAMETOOLONG by the
 * kernel and surfaces as an IOException.
 */
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readlink0__J__B(
        int64_t pathAddress)
{
    char buf[PATH_MAX + 1];
    ssize_t n = readlink(path_of(pathAddress), buf, sizeof(buf) - 1);
    if (n < 0) {
        __jnative_throw_exception(NULL);
        return NULL;
    }
    return make_byte_array(buf, (size_t)n);
}

/*
 * static native void access0(long pathAddress, int amode);
 *
 * Tests accessibility of the given path via access(2). The `amode`
 * argument carries one or more of R_OK / W_OK / X_OK, or F_OK for an
 * existence-only query. Any failure surfaces as an IOException.
 */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_access0__JI_V(
        int64_t pathAddress, int32_t amode)
{
    if (access(path_of(pathAddress), (int)amode) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * static native void chmod0(long pathAddress, int mode);
 *
 * Changes the permission bits of the given path via chmod(2). Any
 * failure surfaces as an IOException.
 */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chmod0__JI_V(
        int64_t pathAddress, int32_t mode)
{
    if (chmod(path_of(pathAddress), (mode_t)mode) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchmod0__II_V(
        int32_t fd, int32_t mode)
{
    if (fchmod((int)fd, (mode_t)mode) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid)
{
    if (chown(path_of(pathAddress), (uid_t)uid, (gid_t)gid) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lchown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid)
{
    if (lchown(path_of(pathAddress), (uid_t)uid, (gid_t)gid) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchown0__III_V(
        int32_t fd, int32_t uid, int32_t gid)
{
    if (fchown((int)fd, (uid_t)uid, (gid_t)gid) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * =========================================================================
 * Timestamp setters.
 *
 * The Java layer converts every timestamp to a single long before the
 * call; the native side splits it into seconds/nanoseconds (or
 * seconds/microseconds for the *utimes family).
 * =========================================================================
 */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_futimes0__IJJ_V(
        int32_t fd, int64_t atime_usec, int64_t mtime_usec)
{
    struct timeval times[2];
    times[0].tv_sec  = (time_t)(atime_usec / 1000000);
    times[0].tv_usec = (suseconds_t)(atime_usec % 1000000);
    times[1].tv_sec  = (time_t)(mtime_usec / 1000000);
    times[1].tv_usec = (suseconds_t)(mtime_usec % 1000000);

    if (futimes((int)fd, times) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_utimes0__JJJ_V(
        int64_t pathAddress, int64_t atime_usec, int64_t mtime_usec)
{
    struct timeval times[2];
    times[0].tv_sec  = (time_t)(atime_usec / 1000000);
    times[0].tv_usec = (suseconds_t)(atime_usec % 1000000);
    times[1].tv_sec  = (time_t)(mtime_usec / 1000000);
    times[1].tv_usec = (suseconds_t)(mtime_usec % 1000000);

    if (utimes(path_of(pathAddress), times) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lutimes0__JJJ_V(
        int64_t pathAddress, int64_t atime_usec, int64_t mtime_usec)
{
    struct timespec ts[2];
    ts[0].tv_sec  = (time_t)(atime_usec / 1000000);
    ts[0].tv_nsec = (long)((atime_usec % 1000000) * 1000);
    ts[1].tv_sec  = (time_t)(mtime_usec / 1000000);
    ts[1].tv_nsec = (long)((mtime_usec % 1000000) * 1000);

    if (utimensat(AT_FDCWD, path_of(pathAddress), ts, AT_SYMLINK_NOFOLLOW) < 0) {
        __jnative_throw_exception(NULL);
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_futimens0__IJJ_V(
        int32_t fd, int64_t atime_nsec, int64_t mtime_nsec)
{
    struct timespec ts[2];
    ts[0].tv_sec  = (time_t)(atime_nsec / 1000000000);
    ts[0].tv_nsec = (long)(atime_nsec % 1000000000);
    ts[1].tv_sec  = (time_t)(mtime_nsec / 1000000000);
    ts[1].tv_nsec = (long)(mtime_nsec % 1000000000);

    if (futimens((int)fd, ts) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * =========================================================================
 * Extended attributes (Linux only).
 *
 * These are reached from the UnixUserDefinedFileAttributeView
 * implementation and operate on the xattr namespace of the given
 * descriptor. The `name` and `value` arguments are native addresses
 * pointing into a direct ByteBuffer; the length arguments are
 * explicit because the buffers themselves carry no length.
 * =========================================================================
 */

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fgetxattr0__IJJI_I(
        int32_t fd, int64_t nameAddress, int64_t valueAddress, int32_t valueLen)
{
    const char* name = path_of(nameAddress);
    void* value = ptr_of(valueAddress);

    ssize_t res = (value == NULL)
        ? fgetxattr((int)fd, name, NULL, 0)
        : fgetxattr((int)fd, name, value, (size_t)valueLen);

    if (res < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int32_t)res;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fsetxattr0__IJJI_V(
        int32_t fd, int64_t nameAddress, int64_t valueAddress, int32_t valueLen)
{
    const char* name = path_of(nameAddress);
    const void* value = ptr_of(valueAddress);

    if (value == NULL) {
        if (fsetxattr((int)fd, name, "", 0, 0) < 0) {
            __jnative_throw_exception(NULL);
        }
        return;
    }

    if (fsetxattr((int)fd, name, value, (size_t)valueLen, 0) < 0) {
        __jnative_throw_exception(NULL);
    }
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_flistxattr__IJI_I(
        int32_t fd, int64_t listAddress, int32_t size)
{
    void* list = ptr_of(listAddress);

    ssize_t res = (list == NULL)
        ? flistxattr((int)fd, NULL, 0)
        : flistxattr((int)fd, list, (size_t)size);

    if (res < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int32_t)res;
}