/*
 * sun.nio.fs.UnixNativeDispatcher
 *
 * ============================================================================
 * Why this file now has two halves
 * ============================================================================
 *
 * `sun.nio.fs.UnixNativeDispatcher` is a POSIX-only class. Its natives
 * wrap the POSIX syscalls every Unix file system operation needs:
 * stat/lstat/fstat, open/openat, mkdir/rmdir, unlink/unlinkat, rename/
 * renameat, chmod/chown/fchmod/fchown, readlink/realpath, symlink/link,
 * statvfs, readdir/opendir/closedir/fdopendir, utimes/futimes/lutimes,
 * flock, sendfile, getcwd, getpwnam/getgrnam and dlsym.
 *
 * On a Windows build, none of those symbols exist in the MinGW CRT:
 *
 *     st_atim, st_mtim, st_ctim    — POSIX nanosecond time fields, absent
 *                                     from MinGW's struct stat
 *     lstat, statvfs, openat       — no MinGW declaration
 *     fdopendir, unlinkat, symlink, readlink, fchmod, chown
 *     mkdir                        — present, but one-argument
 *     uid_t, gid_t                 — not defined on MinGW
 *
 * The class is nevertheless pulled into the Windows reachability closure,
 * because the walk cannot prove that FileSystems.getDefault() never picks
 * UnixFileSystem. Every __jnative_fn_sun_nio_fs_UnixNativeDispatcher_*
 * symbol the walk references must therefore exist at link time.
 *
 * Splitting the file with #if defined(_WIN32) is the only way to satisfy
 * both requirements:
 *
 *   - Windows: every symbol exists, every method throws
 *     UnsupportedOperationException (it is never called — the Windows
 *     provider lives in WindowsNativeDispatcher.c);
 *
 *   - POSIX: the full implementation that has been here all along.
 *
 * ============================================================================
 * POSIX implementation notes
 * ============================================================================
 *
 * The layout constants below (UFA_ST_*, UFSA_*, UFS_*) are the byte offsets
 * of the fields of three classes that the natives write into:
 *
 *   sun.nio.fs.UnixFileAttributes        (16 instance fields)
 *   sun.nio.fs.UnixFileStoreAttributes   ( 7 instance fields)
 *   sun.nio.fs.UnixFileAttributes.stat   (nested, populates FileStatus)
 *
 * Every class begins with the standard 8-byte JNative object header
 * (vtable pointer), and the remaining fields are laid out with natural
 * alignment exactly as LlvmGlobalEmitter.getFieldOffset computes them.
 * The offsets are duplicated here as #defines because the natives write
 * them raw, without going through any getFieldOffset call — that is the
 * existing convention in this file and the one the Java-side callers
 * already expect.
 *
 * Adding or removing a field on the Java side requires updating the
 * corresponding #define here. This is fragile but it is a hard constraint
 * of the current design: the natives have no way to discover the offsets
 * at run time without either (a) an extra getFieldOffset call per
 * invocation, or (b) a per-class field-offset table emitted into
 * _module.ll, and neither is currently in place.
 */

#define _GNU_SOURCE
#include "jnative_platform.h"

#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include "jnative_runtime.h"

/* ============================================================================
 *  WINDOWS: stubs only
 * ============================================================================
 *
 * Every UnixNativeDispatcher native throws UnsupportedOperationException.
 *
 * This is not a placeholder — it is the correct semantics. On Windows the
 * active FileSystemProvider is sun.nio.fs.WindowsFileSystem, whose natives
 * live in WindowsNativeDispatcher.c; none of the UnixNativeDispatcher
 * natives is ever dispatched to. If a caller somehow reaches one of these
 * stubs (which would indicate a build-time error, since the Windows
 * provider never invokes UnixFileSystem), a clear UnsupportedOperation
 * exception is strictly better than a SIGSEGV on a missing symbol.
 *
 * ========================================================================= */
#if defined(_WIN32)

__attribute__((noreturn))
static void unix_native_unsupported(const char* method) {
    char buf[256];
    snprintf(buf, sizeof(buf),
             "sun.nio.fs.UnixNativeDispatcher.%s is not supported on Windows",
             method);
    __jnative_throw_exception_ctx(
        __jnative_construct_exception(
            "vtable_java_lang_UnsupportedOperationException", buf),
        NULL);
    /* not reached */
    _exit(1);
}

/* ---- Setup ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___V(void) {
    /* Called from <clinit>. Safe to be a no-op: nothing in the Windows
     * provider path uses the caches this would have populated. */
}

/* ---- File open / close ---- */

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_open0__JII_I(
        int64_t pathAddress, int32_t flags, int32_t mode) {
    (void)pathAddress; (void)flags; (void)mode;
    unix_native_unsupported("open0");
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_openat0__IJII_I(
        int32_t dfd, int64_t pathAddress, int32_t flags, int32_t mode) {
    (void)dfd; (void)pathAddress; (void)flags; (void)mode;
    unix_native_unsupported("openat0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_close0__I_V(int32_t fd) {
    (void)fd;
    unix_native_unsupported("close0");
}

/* ---- stat family ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstat0__IJ_V(
        int32_t fd, int64_t statAddress) {
    (void)fd; (void)statAddress;
    unix_native_unsupported("fstat0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstatat0__IJIJ_V(
        int32_t dfd, int64_t pathAddress, int32_t flag, int64_t statAddress) {
    (void)dfd; (void)pathAddress; (void)flag; (void)statAddress;
    unix_native_unsupported("fstatat0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lstat0__JJ_V(
        int64_t pathAddress, int64_t statAddress) {
    (void)pathAddress; (void)statAddress;
    unix_native_unsupported("lstat0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_stat0__JJ_V(
        int64_t pathAddress, int64_t statAddress) {
    (void)pathAddress; (void)statAddress;
    unix_native_unsupported("stat0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_statvfs0__JJ_V(
        int64_t pathAddress, int64_t statvfsAddress) {
    (void)pathAddress; (void)statvfsAddress;
    unix_native_unsupported("statvfs0");
}

/* ---- Path utilities ---- */

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_pathmax___J(void) {
    /* PATH_MAX does exist in MinGW (<limits.h>), but nothing on the
     * Windows provider path calls this method. */
    unix_native_unsupported("pathmax");
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readlink0__JJ_J(
        int64_t pathAddress, int64_t targetAddress) {
    (void)pathAddress; (void)targetAddress;
    unix_native_unsupported("readlink0");
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_realpath0__JJ_J(
        int64_t pathAddress, int64_t resolvedAddress) {
    (void)pathAddress; (void)resolvedAddress;
    unix_native_unsupported("realpath0");
}

/* ---- Link / unlink ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_symlink0__JJ_V(
        int64_t linkAddress, int64_t targetAddress) {
    (void)linkAddress; (void)targetAddress;
    unix_native_unsupported("symlink0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_link0__JJ_V(
        int64_t existingAddress, int64_t newAddress) {
    (void)existingAddress; (void)newAddress;
    unix_native_unsupported("link0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlink0__J_V(
        int64_t pathAddress) {
    (void)pathAddress;
    unix_native_unsupported("unlink0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlinkat0__IJI_V(
        int32_t dfd, int64_t pathAddress, int32_t flags) {
    (void)dfd; (void)pathAddress; (void)flags;
    unix_native_unsupported("unlinkat0");
}

/* ---- Directory manipulation ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_mkdir0__JI_V(
        int64_t pathAddress, int32_t mode) {
    (void)pathAddress; (void)mode;
    unix_native_unsupported("mkdir0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rmdir0__J_V(
        int64_t pathAddress) {
    (void)pathAddress;
    unix_native_unsupported("rmdir0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rename0__JJ_V(
        int64_t fromAddress, int64_t toAddress) {
    (void)fromAddress; (void)toAddress;
    unix_native_unsupported("rename0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_renameat0__IJIJ_V(
        int32_t fromfd, int64_t fromAddress,
        int32_t tofd,   int64_t toAddress) {
    (void)fromfd; (void)fromAddress; (void)tofd; (void)toAddress;
    unix_native_unsupported("renameat0");
}

/* ---- Permission / ownership ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chmod0__JI_V(
        int64_t pathAddress, int32_t mode) {
    (void)pathAddress; (void)mode;
    unix_native_unsupported("chmod0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid) {
    (void)pathAddress; (void)uid; (void)gid;
    unix_native_unsupported("chown0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lchown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid) {
    (void)pathAddress; (void)uid; (void)gid;
    unix_native_unsupported("lchown0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchmod0__II_V(
        int32_t fd, int32_t mode) {
    (void)fd; (void)mode;
    unix_native_unsupported("fchmod0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchown0__III_V(
        int32_t fd, int32_t uid, int32_t gid) {
    (void)fd; (void)uid; (void)gid;
    unix_native_unsupported("fchown0");
}

/* ---- Timestamps ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_utimes0__JJ_V(
        int64_t pathAddress, int64_t timesAddress) {
    (void)pathAddress; (void)timesAddress;
    unix_native_unsupported("utimes0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_futimes0__IJ_V(
        int32_t fd, int64_t timesAddress) {
    (void)fd; (void)timesAddress;
    unix_native_unsupported("futimes0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lutimes0__JJ_V(
        int64_t pathAddress, int64_t timesAddress) {
    (void)pathAddress; (void)timesAddress;
    unix_native_unsupported("lutimes0");
}

/* ---- Truncation ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_truncate0__JJ_V(
        int64_t pathAddress, int64_t size) {
    (void)pathAddress; (void)size;
    unix_native_unsupported("truncate0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_ftruncate0__IJ_V(
        int32_t fd, int64_t size) {
    (void)fd; (void)size;
    unix_native_unsupported("ftruncate0");
}

/* ---- Access check ---- */

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_access0__JI_I(
        int64_t pathAddress, int32_t amode) {
    (void)pathAddress; (void)amode;
    unix_native_unsupported("access0");
}

/* ---- Working directory / fd duplication / fcntl ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chdir0__J_V(
        int64_t pathAddress) {
    (void)pathAddress;
    unix_native_unsupported("chdir0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_dup0__II_V(
        int32_t fd1, int32_t fd2) {
    (void)fd1; (void)fd2;
    unix_native_unsupported("dup0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fcntl0__III_V(
        int32_t fd, int32_t cmd, int32_t arg) {
    (void)fd; (void)cmd; (void)arg;
    unix_native_unsupported("fcntl0");
}

/* ---- Sync ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fsync0__I_V(int32_t fd) {
    (void)fd;
    unix_native_unsupported("fsync0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fdatasync0__I_V(int32_t fd) {
    (void)fd;
    unix_native_unsupported("fdatasync0");
}

/* ---- Directory iteration ---- */

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fdopendir0__I_J(
        int32_t fd) {
    (void)fd;
    unix_native_unsupported("fdopendir0");
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readdir0__JJ_I(
        int64_t dp, int64_t entryAddress) {
    (void)dp; (void)entryAddress;
    unix_native_unsupported("readdir0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_closedir0__J_V(int64_t dp) {
    (void)dp;
    unix_native_unsupported("closedir0");
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_opendir0__J_J(
        int64_t pathAddress) {
    (void)pathAddress;
    unix_native_unsupported("opendir0");
}

/* ---- File locking ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_flock0__II_V(
        int32_t fd, int32_t op) {
    (void)fd; (void)op;
    unix_native_unsupported("flock0");
}

/* ---- sendfile ---- */

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_sendfile0__IIJJ_J(
        int32_t src, int32_t dst, int64_t positionAddress, int64_t count) {
    (void)src; (void)dst; (void)positionAddress; (void)count;
    unix_native_unsupported("sendfile0");
}

/* ---- Working directory ---- */

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getcwd___B(void) {
    unix_native_unsupported("getcwd");
}

/* ---- Password / group database lookups ---- */

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrnam0__Ljava_lang_String___B(
        void* name) {
    (void)name;
    unix_native_unsupported("getgrnam0");
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrgid0__I__B(
        int32_t gid) {
    (void)gid;
    unix_native_unsupported("getgrgid0");
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwnam0__Ljava_lang_String___B(
        void* name) {
    (void)name;
    unix_native_unsupported("getpwnam0");
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwuid0__I__B(
        int32_t uid) {
    (void)uid;
    unix_native_unsupported("getpwuid0");
}

/* ---- dlsym ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_dlsym0__JJ_V(
        int64_t handleAddress, int64_t nameAddress) {
    (void)handleAddress; (void)nameAddress;
    unix_native_unsupported("dlsym0");
}

/* ============================================================================
 *  POSIX: real implementation
 * ========================================================================= */
#else /* !_WIN32 */

#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <sys/sendfile.h>
#include <dirent.h>
#include <limits.h>
#include <pwd.h>
#include <grp.h>
#include <dlfcn.h>
#include <errno.h>

/* ========================================================================
 *  Layout constants — byte offsets into the three Java mirror classes
 *  that the natives write into.
 *
 *  All three begin with the standard 8-byte object header (vtable).
 *  Field offsets are laid out with natural alignment exactly as
 *  LlvmGlobalEmitter.getFieldOffset computes for the corresponding
 *  Java class.
 *
 *  Any change to the Java field set (a field added, removed, or
 *  reordered) invalidates these constants. The build-time consistency
 *  between these constants and the Java classes is NOT enforced; the
 *  constants are reproduced here because the natives write raw bytes
 *  and have no run-time way to discover the layout.
 * ===================================================================== */

/* sun.nio.fs.UnixFileAttributes — 14 instance fields.
 *
 *     offset  0 : i8*  vtable
 *     offset  8 : i32  st_mode         (int, 4 bytes)
 *     offset 12 :      padding
 *     offset 16 : i64  st_ino          (long)
 *     offset 24 : i64  st_dev          (long)
 *     offset 32 : i64  st_rdev         (long)
 *     offset 40 : i32  st_nlink        (int, 4 bytes)
 *     offset 44 : i32  st_uid          (int, 4 bytes)
 *     offset 48 : i32  st_gid          (int, 4 bytes)
 *     offset 52 :      padding
 *     offset 56 : i64  st_size         (long)
 *     offset 64 : i64  st_atime_sec    (long)
 *     offset 72 : i64  st_atime_nsec   (long)
 *     offset 80 : i64  st_mtime_sec    (long)
 *     offset 88 : i64  st_mtime_nsec   (long)
 *     offset 96 : i64  st_ctime_sec    (long)
 *     offset104 : i64  st_ctime_nsec   (long)
 *     total    : 112
 */
#define UFA_ST_MODE_OFFSET       8
#define UFA_ST_INO_OFFSET        16
#define UFA_ST_DEV_OFFSET        24
#define UFA_ST_RDEV_OFFSET       32
#define UFA_ST_NLINK_OFFSET      40
#define UFA_ST_UID_OFFSET        44
#define UFA_ST_GID_OFFSET        48
#define UFA_ST_SIZE_OFFSET       56
#define UFA_ST_ATIME_SEC         64
#define UFA_ST_ATIME_NSEC        72
#define UFA_ST_MTIME_SEC         80
#define UFA_ST_MTIME_NSEC        88
#define UFA_ST_CTIME_SEC         96
#define UFA_ST_CTIME_NSEC        104

/* sun.nio.fs.UnixFileStoreAttributes — 7 instance fields, all long.
 *
 *     offset  0 : i8*  vtable
 *     offset  8 : i64  f_frsize
 *     offset 16 : i64  f_blocks
 *     offset 24 : i64  f_bfree
 *     offset 32 : i64  f_bavail
 *     offset 40 : i64  f_files
 *     offset 48 : i64  f_ffree
 *     offset 56 : i64  f_favail
 *     total    : 64
 */
#define UFSA_F_FRSIZE_OFFSET     8
#define UFSA_F_BLOCKS_OFFSET     16
#define UFSA_F_BFREE_OFFSET      24
#define UFSA_F_BAVAIL_OFFSET     32
#define UFSA_F_FILES_OFFSET      40
#define UFSA_F_FFREE_OFFSET      48
#define UFSA_F_FAVAIL_OFFSET     56

/* sun.nio.fs.UnixPath — asByteArray() returns the cached byte[]; the
 * natives receive the raw address of its payload. The payload is
 * NUL-terminated by construction (UnixPath allocates byte[len+1]).
 * path_of() therefore just casts the address to (const char*).
 */
static inline const char* path_of(int64_t pathAddress) {
    return (const char*)(intptr_t)pathAddress;
}

/* ========================================================================
 *  Exception helper.
 *
 *  Every syscall wrapper in this file follows the same pattern: on
 *  failure, report the syscall name and the errno in a message, and
 *  hand control to the runtime's throwing helper. The Java caller
 *  (UnixException) records the errno; the message is the diagnostic
 *  the caller will see if the exception is not caught.
 * ===================================================================== */
__attribute__((noreturn))
static void throwUnixException(const char* syscall) {
    char buf[256];
    int saved = errno;
    snprintf(buf, sizeof(buf), "%s: %s (errno=%d)",
             syscall,
             strerror(saved),
             saved);
    __jnative_throw_exception_ctx(
        __jnative_construct_exception(
            "vtable_sun_nio_fs_UnixException", buf),
        NULL);
}

/* Same helper, but with an explicit message rather than strerror(). Used
 * when the failure comes from a source other than errno (e.g. a
 * deliberately zeroed stat buffer). */
__attribute__((noreturn))
static void throwUnixExceptionMsg(int err, const char* msg) {
    __jnative_throw_exception_ctx(
        __jnative_construct_exception(
            "vtable_sun_nio_fs_UnixException", msg),
        NULL);
    (void)err;
}

/* ========================================================================
 *  fill_stat — populates one UnixFileAttributes mirror from a struct stat.
 * ======================================================================= */
static void fill_stat(int64_t p, const struct stat* st) {
    *(int32_t*)((char*)p + UFA_ST_MODE_OFFSET)  = (int32_t)st->st_mode;
    *(int64_t*)((char*)p + UFA_ST_INO_OFFSET)   = (int64_t)st->st_ino;
    *(int64_t*)((char*)p + UFA_ST_DEV_OFFSET)   = (int64_t)st->st_dev;
    *(int64_t*)((char*)p + UFA_ST_RDEV_OFFSET)  = (int64_t)st->st_rdev;
    *(int32_t*)((char*)p + UFA_ST_NLINK_OFFSET) = (int32_t)st->st_nlink;
    *(int32_t*)((char*)p + UFA_ST_UID_OFFSET)   = (int32_t)st->st_uid;
    *(int32_t*)((char*)p + UFA_ST_GID_OFFSET)   = (int32_t)st->st_gid;
    *(int64_t*)((char*)p + UFA_ST_SIZE_OFFSET)  = (int64_t)st->st_size;

#if defined(__APPLE__)
    /* macOS's struct stat still uses the historic st_atimespec /
     * st_mtimespec / st_ctimespec names. */
    *(int64_t*)((char*)p + UFA_ST_ATIME_SEC)  = (int64_t)st->st_atimespec.tv_sec;
    *(int64_t*)((char*)p + UFA_ST_ATIME_NSEC) = (int64_t)st->st_atimespec.tv_nsec;
    *(int64_t*)((char*)p + UFA_ST_MTIME_SEC)  = (int64_t)st->st_mtimespec.tv_sec;
    *(int64_t*)((char*)p + UFA_ST_MTIME_NSEC) = (int64_t)st->st_mtimespec.tv_nsec;
    *(int64_t*)((char*)p + UFA_ST_CTIME_SEC)  = (int64_t)st->st_ctimespec.tv_sec;
    *(int64_t*)((char*)p + UFA_ST_CTIME_NSEC) = (int64_t)st->st_ctimespec.tv_nsec;
#else
    *(int64_t*)((char*)p + UFA_ST_ATIME_SEC)  = (int64_t)st->st_atim.tv_sec;
    *(int64_t*)((char*)p + UFA_ST_ATIME_NSEC) = (int64_t)st->st_atim.tv_nsec;
    *(int64_t*)((char*)p + UFA_ST_MTIME_SEC)  = (int64_t)st->st_mtim.tv_sec;
    *(int64_t*)((char*)p + UFA_ST_MTIME_NSEC) = (int64_t)st->st_mtim.tv_nsec;
    *(int64_t*)((char*)p + UFA_ST_CTIME_SEC)  = (int64_t)st->st_ctim.tv_sec;
    *(int64_t*)((char*)p + UFA_ST_CTIME_NSEC) = (int64_t)st->st_ctim.tv_nsec;
#endif
}

/* ========================================================================
 *  fill_statvfs — populates one UnixFileStoreAttributes mirror.
 * ======================================================================= */
static void fill_statvfs(int64_t p, const struct statvfs* vfs) {
    *(int64_t*)((char*)p + UFSA_F_FRSIZE_OFFSET) =
        (int64_t)vfs->f_frsize;
    *(int64_t*)((char*)p + UFSA_F_BLOCKS_OFFSET) =
        (int64_t)vfs->f_blocks;
    *(int64_t*)((char*)p + UFSA_F_BFREE_OFFSET)  =
        (int64_t)vfs->f_bfree;
    *(int64_t*)((char*)p + UFSA_F_BAVAIL_OFFSET) =
        (int64_t)vfs->f_bavail;
    *(int64_t*)((char*)p + UFSA_F_FILES_OFFSET)  =
        (int64_t)vfs->f_files;
    *(int64_t*)((char*)p + UFSA_F_FFREE_OFFSET)  =
        (int64_t)vfs->f_ffree;
    *(int64_t*)((char*)p + UFSA_F_FAVAIL_OFFSET) =
        (int64_t)vfs->f_favail;
}

/* ========================================================================
 *  Public natives
 * ===================================================================== */

/* static native void init(); — one-shot setup for the class. */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___V(void) {
}

/* static native int open0(long pathAddress, int flags, int mode) */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_open0__JII_I(
        int64_t pathAddress, int32_t flags, int32_t mode) {
    int fd = open(path_of(pathAddress), flags, (mode_t)mode);
    if (fd < 0) throwUnixException("open");
    return fd;
}

/* static native int openat0(int dfd, long pathAddress, int flags, int mode) */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_openat0__IJII_I(
        int32_t dfd, int64_t pathAddress, int32_t flags, int32_t mode) {
    int fd = openat(dfd, path_of(pathAddress), flags, (mode_t)mode);
    if (fd < 0) throwUnixException("openat");
    return fd;
}

/* static native void close0(int fd) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_close0__I_V(int32_t fd) {
    if (close(fd) < 0) throwUnixException("close");
}

/* static native void fstat0(int fd, long statAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstat0__IJ_V(
        int32_t fd, int64_t statAddress) {
    struct stat st;
    if (fstat(fd, &st) < 0) throwUnixException("fstat");
    fill_stat(statAddress, &st);
}

/* static native void fstatat0(int dfd, long pathAddress, int flag, long statAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstatat0__IJIJ_V(
        int32_t dfd, int64_t pathAddress, int32_t flag, int64_t statAddress) {
    struct stat st;
    if (fstatat(dfd, path_of(pathAddress), &st, flag) < 0)
        throwUnixException("fstatat");
    fill_stat(statAddress, &st);
}

/* static native void lstat0(long pathAddress, long statAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lstat0__JJ_V(
        int64_t pathAddress, int64_t statAddress) {
    struct stat st;
    if (lstat(path_of(pathAddress), &st) < 0) throwUnixException("lstat");
    fill_stat(statAddress, &st);
}

/* static native void stat0(long pathAddress, long statAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_stat0__JJ_V(
        int64_t pathAddress, int64_t statAddress) {
    struct stat st;
    if (stat(path_of(pathAddress), &st) < 0) throwUnixException("stat");
    fill_stat(statAddress, &st);
}

/* static native void statvfs0(long pathAddress, long statvfsAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_statvfs0__JJ_V(
        int64_t pathAddress, int64_t statvfsAddress) {
    struct statvfs vfs;
    if (statvfs(path_of(pathAddress), &vfs) < 0)
        throwUnixException("statvfs");
    fill_statvfs(statvfsAddress, &vfs);
}

/* static native long pathmax() */
int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_pathmax___J(void) {
    long v = pathconf("/", _PC_PATH_MAX);
    if (v <= 0) v = PATH_MAX;
    return (int64_t)v;
}

/* static native long readlink0(long pathAddress, long targetAddress) */
int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readlink0__JJ_J(
        int64_t pathAddress, int64_t targetAddress) {
    ssize_t n = readlink(path_of(pathAddress),
                         (char*)(intptr_t)targetAddress,
                         (size_t)PATH_MAX);
    if (n < 0) throwUnixException("readlink");
    return (int64_t)n;
}

/* static native long realpath0(long pathAddress, long resolvedAddress) */
int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_realpath0__JJ_J(
        int64_t pathAddress, int64_t resolvedAddress) {
    if (realpath(path_of(pathAddress),
                 (char*)(intptr_t)resolvedAddress) == NULL) {
        throwUnixException("realpath");
    }
    return (int64_t)strlen((const char*)(intptr_t)resolvedAddress);
}

/* static native void symlink0(long linkAddress, long targetAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_symlink0__JJ_V(
        int64_t linkAddress, int64_t targetAddress) {
    if (symlink(path_of(linkAddress), path_of(targetAddress)) < 0)
        throwUnixException("symlink");
}

/* static native void link0(long existingAddress, long newAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_link0__JJ_V(
        int64_t existingAddress, int64_t newAddress) {
    if (link(path_of(existingAddress), path_of(newAddress)) < 0)
        throwUnixException("link");
}

/* static native void unlink0(long pathAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlink0__J_V(
        int64_t pathAddress) {
    if (unlink(path_of(pathAddress)) < 0) throwUnixException("unlink");
}

/* static native void unlinkat0(int dfd, long pathAddress, int flags) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlinkat0__IJI_V(
        int32_t dfd, int64_t pathAddress, int32_t flags) {
    if (unlinkat(dfd, path_of(pathAddress), flags) < 0)
        throwUnixException("unlinkat");
}

/* static native void mkdir0(long pathAddress, int mode) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_mkdir0__JI_V(
        int64_t pathAddress, int32_t mode) {
    if (mkdir(path_of(pathAddress), (mode_t)mode) < 0)
        throwUnixException("mkdir");
}

/* static native void rmdir0(long pathAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rmdir0__J_V(
        int64_t pathAddress) {
    if (rmdir(path_of(pathAddress)) < 0) throwUnixException("rmdir");
}

/* static native void rename0(long fromAddress, long toAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rename0__JJ_V(
        int64_t fromAddress, int64_t toAddress) {
    if (rename(path_of(fromAddress), path_of(toAddress)) < 0)
        throwUnixException("rename");
}

/* static native void renameat0(int fromfd, long fromAddress, int tofd, long toAddress) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_renameat0__IJIJ_V(
        int32_t fromfd, int64_t fromAddress,
        int32_t tofd,   int64_t toAddress) {
    if (renameat(fromfd, path_of(fromAddress),
                 tofd,   path_of(toAddress)) < 0) {
        throwUnixException("renameat");
    }
}

/* static native void chmod0(long pathAddress, int mode) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chmod0__JI_V(
        int64_t pathAddress, int32_t mode) {
    if (chmod(path_of(pathAddress), (mode_t)mode) < 0)
        throwUnixException("chmod");
}

/* static native void chown0(long pathAddress, int uid, int gid) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid) {
    if (chown(path_of(pathAddress), (uid_t)uid, (gid_t)gid) < 0)
        throwUnixException("chown");
}

/* static native void lchown0(long pathAddress, int uid, int gid) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lchown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid) {
    if (lchown(path_of(pathAddress), (uid_t)uid, (gid_t)gid) < 0)
        throwUnixException("lchown");
}

/* static native void fchmod0(int fd, int mode) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchmod0__II_V(
        int32_t fd, int32_t mode) {
    if (fchmod(fd, (mode_t)mode) < 0) throwUnixException("fchmod");
}

/* static native void fchown0(int fd, int uid, int gid) */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchown0__III_V(
        int32_t fd, int32_t uid, int32_t gid) {
    if (fchown(fd, (uid_t)uid, (gid_t)gid) < 0)
        throwUnixException("fchown");
}

/* ========================================================================
 *  Timestamps.
 *
 *  The Java caller packs two struct timeval (16 bytes each, 32 bytes
 *  total) into a 32-byte buffer. Each timeval is {tv_sec (8), tv_usec (8)}.
 * ===================================================================== */
#define TIMES_SEC_OFFSET  0
#define TIMES_USEC_OFFSET 8
#define TIMES_SLOT_SIZE   16

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_utimes0__JJ_V(
        int64_t pathAddress, int64_t timesAddress) {
    struct timeval tv[2];
    memcpy(&tv[0], (void*)(intptr_t)(timesAddress + 0 * TIMES_SLOT_SIZE),
           sizeof(struct timeval));
    memcpy(&tv[1], (void*)(intptr_t)(timesAddress + 1 * TIMES_SLOT_SIZE),
           sizeof(struct timeval));
    if (utimes(path_of(pathAddress), tv) < 0)
        throwUnixException("utimes");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_futimes0__IJ_V(
        int32_t fd, int64_t timesAddress) {
    struct timeval tv[2];
    memcpy(&tv[0], (void*)(intptr_t)(timesAddress + 0 * TIMES_SLOT_SIZE),
           sizeof(struct timeval));
    memcpy(&tv[1], (void*)(intptr_t)(timesAddress + 1 * TIMES_SLOT_SIZE),
           sizeof(struct timeval));
    if (futimes(fd, tv) < 0) throwUnixException("futimes");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lutimes0__JJ_V(
        int64_t pathAddress, int64_t timesAddress) {
    struct timeval tv[2];
    memcpy(&tv[0], (void*)(intptr_t)(timesAddress + 0 * TIMES_SLOT_SIZE),
           sizeof(struct timeval));
    memcpy(&tv[1], (void*)(intptr_t)(timesAddress + 1 * TIMES_SLOT_SIZE),
           sizeof(struct timeval));
    if (lutimes(path_of(pathAddress), tv) < 0)
        throwUnixException("lutimes");
}

/* ========================================================================
 *  Truncation.
 * ===================================================================== */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_truncate0__JJ_V(
        int64_t pathAddress, int64_t size) {
    if (truncate(path_of(pathAddress), (off_t)size) < 0)
        throwUnixException("truncate");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_ftruncate0__IJ_V(
        int32_t fd, int64_t size) {
    if (ftruncate(fd, (off_t)size) < 0) throwUnixException("ftruncate");
}

/* ========================================================================
 *  Access check.
 * ===================================================================== */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_access0__JI_I(
        int64_t pathAddress, int32_t amode) {
    int r = access(path_of(pathAddress), amode);
    if (r < 0) {
        /* access(2) is documented to fail with EACCES for "not
         * accessible" — that is a legitimate return value, not an
         * exceptional condition. Only real errors throw. */
        if (errno != EACCES) throwUnixException("access");
        return 0;
    }
    return 1;
}

/* ========================================================================
 *  Working directory / fd duplication / fcntl.
 * ===================================================================== */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chdir0__J_V(
        int64_t pathAddress) {
    if (chdir(path_of(pathAddress)) < 0) throwUnixException("chdir");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_dup0__II_V(
        int32_t fd1, int32_t fd2) {
    if (dup2(fd1, fd2) < 0) throwUnixException("dup2");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fcntl0__III_V(
        int32_t fd, int32_t cmd, int32_t arg) {
    if (fcntl(fd, cmd, arg) < 0) throwUnixException("fcntl");
}

/* ========================================================================
 *  Sync.
 * ===================================================================== */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fsync0__I_V(int32_t fd) {
    if (fsync(fd) < 0) throwUnixException("fsync");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fdatasync0__I_V(int32_t fd) {
#if defined(__APPLE__)
    /* macOS does not have fdatasync; fsync is the documented substitute
     * (both write out the file's data and metadata). */
    if (fsync(fd) < 0) throwUnixException("fsync");
#else
    if (fdatasync(fd) < 0) throwUnixException("fdatasync");
#endif
}

/* ========================================================================
 *  Directory iteration.
 *
 *  opendir / fdopendir / readdir / closedir return and receive raw
 *  pointers cast to int64_t, because DIR* is opaque to Java.
 * ===================================================================== */
int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fdopendir0__I_J(
        int32_t fd) {
    DIR* d = fdopendir(fd);
    if (d == NULL) throwUnixException("fdopendir");
    return (int64_t)(intptr_t)d;
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_opendir0__J_J(
        int64_t pathAddress) {
    DIR* d = opendir(path_of(pathAddress));
    if (d == NULL) throwUnixException("opendir");
    return (int64_t)(intptr_t)d;
}

/* readdir0 copies the next entry's d_name into the caller's buffer at
 * entryAddress, NUL-terminated, and returns the length of the name.
 * The return value -1 signals end of directory; no exception is thrown
 * for that. */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readdir0__JJ_I(
        int64_t dp, int64_t entryAddress) {
    DIR* d = (DIR*)(intptr_t)dp;
    errno = 0;
    struct dirent* e = readdir(d);
    if (e == NULL) {
        if (errno != 0) throwUnixException("readdir");
        return -1;
    }
    size_t len = strlen(e->d_name);
    memcpy((void*)(intptr_t)entryAddress, e->d_name, len + 1);
    return (int32_t)len;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_closedir0__J_V(int64_t dp) {
    DIR* d = (DIR*)(intptr_t)dp;
    if (closedir(d) < 0) throwUnixException("closedir");
}

/* ========================================================================
 *  File locking.
 * ===================================================================== */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_flock0__II_V(
        int32_t fd, int32_t op) {
    if (flock(fd, op) < 0) throwUnixException("flock");
}

/* ========================================================================
 *  sendfile.
 *
 *  The caller passes the address of a jlong holding the current
 *  position. The kernel advances the file offset and writes the new
 *  position back into that slot.
 * ===================================================================== */
int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_sendfile0__IIJJ_J(
        int32_t src, int32_t dst, int64_t positionAddress, int64_t count) {
#if defined(__linux__)
    off_t pos = *(off_t*)(intptr_t)positionAddress;
    ssize_t n = sendfile(dst, src, &pos, (size_t)count);
    if (n < 0) throwUnixException("sendfile");
    *(off_t*)(intptr_t)positionAddress = pos;
    return (int64_t)n;
#else
    /* macOS and the BSDs do not have the Linux sendfile(2) signature;
     * the JDK implementation falls back to a read/write loop there.
     * Same fallback here. */
    char buf[8192];
    int64_t written = 0;
    off_t pos = *(off_t*)(intptr_t)positionAddress;
    while (written < count) {
        size_t want = (size_t)(count - written < (int64_t)sizeof(buf)
                               ? (int64_t)count - written : (int64_t)sizeof(buf));
        ssize_t r = pread(src, buf, want, pos);
        if (r < 0) throwUnixException("pread");
        if (r == 0) break;
        ssize_t w = write(dst, buf, (size_t)r);
        if (w < 0) throwUnixException("write");
        pos     += r;
        written += r;
    }
    *(off_t*)(intptr_t)positionAddress = pos;
    return written;
#endif
}

/* ========================================================================
 *  getcwd.
 * ===================================================================== */
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getcwd___B(void) {
    char buf[PATH_MAX];
    if (getcwd(buf, sizeof(buf)) == NULL) throwUnixException("getcwd");
    return jnative_byte_array(buf, (int32_t)strlen(buf));
}

/* ========================================================================
 *  Password / group database.
 *
 *  Each native packs the fields of a struct passwd / struct group into
 *  a Java byte[]. The layout is fixed by the Java-side caller in
 *  sun.nio.fs.UnixUserPrincipals / UnixGroupPrincipals:
 *
 *      [u32 length][bytes...]   repeated once per string field
 *
 *  This mirrors the JDK's own packing.
 * ===================================================================== */
static void* pack_string_fields(const char* const* fields, int count) {
    /* Compute the total payload size. */
    int32_t total = 0;
    for (int i = 0; i < count; i++) {
        total += 4;                                  /* length prefix */
        if (fields[i] != NULL) total += (int32_t)strlen(fields[i]);
    }
    void* arr = jnative_array_alloc("[B", total, JNATIVE_ELEM_SIZE_BYTE);
    if (arr == NULL)
        __jnative_throw_out_of_memory_error_ctx("pack_string_fields");
    uint8_t* p = (uint8_t*)arr + JAVA_ARR_HDR;
    for (int i = 0; i < count; i++) {
        int32_t len = (fields[i] != NULL) ? (int32_t)strlen(fields[i]) : 0;
        memcpy(p, &len, 4);
        p += 4;
        if (len > 0) {
            memcpy(p, fields[i], (size_t)len);
            p += len;
        }
    }
    return arr;
}

/* Convert a Java String name argument to a C string. */
static const char* cstring_of(void* name_str) {
    return __jnative_read_string_cstr(name_str, NULL);
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwnam0__Ljava_lang_String___B(
        void* name_str) {
    const char* name = cstring_of(name_str);
    if (name == NULL) throwUnixExceptionMsg(EINVAL, "getpwnam: NULL name");
    struct passwd* pw = getpwnam(name);
    if (pw == NULL) throwUnixException("getpwnam");
    const char* f[5] = {
        pw->pw_name, pw->pw_passwd,
        (pw->pw_uid == 0 && pw->pw_name && !strcmp(pw->pw_name, "root"))
            ? "0" : NULL,
        pw->pw_gecos, pw->pw_dir
    };
    char uid_buf[16];
    if (f[2] == NULL) {
        snprintf(uid_buf, sizeof(uid_buf), "%u", (unsigned)pw->pw_uid);
        f[2] = uid_buf;
    }
    return pack_string_fields(f, 5);
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwuid0__I__B(
        int32_t uid) {
    struct passwd* pw = getpwuid((uid_t)uid);
    if (pw == NULL) throwUnixException("getpwuid");
    char uid_buf[16];
    snprintf(uid_buf, sizeof(uid_buf), "%u", (unsigned)pw->pw_uid);
    const char* f[5] = {
        pw->pw_name, pw->pw_passwd, uid_buf,
        pw->pw_gecos, pw->pw_dir
    };
    return pack_string_fields(f, 5);
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrnam0__Ljava_lang_String___B(
        void* name_str) {
    const char* name = cstring_of(name_str);
    if (name == NULL) throwUnixExceptionMsg(EINVAL, "getgrnam: NULL name");
    struct group* gr = getgrnam(name);
    if (gr == NULL) throwUnixException("getgrnam");
    char gid_buf[16];
    snprintf(gid_buf, sizeof(gid_buf), "%u", (unsigned)gr->gr_gid);
    const char* f[2] = { gr->gr_name, gid_buf };
    return pack_string_fields(f, 2);
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrgid0__I__B(
        int32_t gid) {
    struct group* gr = getgrgid((gid_t)gid);
    if (gr == NULL) throwUnixException("getgrgid");
    char gid_buf[16];
    snprintf(gid_buf, sizeof(gid_buf), "%u", (unsigned)gr->gr_gid);
    const char* f[2] = { gr->gr_name, gid_buf };
    return pack_string_fields(f, 2);
}

/* ========================================================================
 *  dlsym.
 *
 *  The caller passes the address of a jlong into which dlsym writes the
 *  function pointer. The Java side wraps the (handle, name) pair around
 *  a handle obtained from dlsym of the calling executable.
 * ===================================================================== */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_dlsym0__JJ_V(
        int64_t handleAddress, int64_t nameAddress) {
    void* handle = (void*)(intptr_t)handleAddress;
    const char* name = (const char*)(intptr_t)nameAddress;
    void* sym = dlsym(handle, name);
    if (sym == NULL) throwUnixException("dlsym");
    /* The caller has already reserved space at the address stored in
     * handleAddress — dlsym0 writes the symbol back through it. In
     * practice the Java side passes a MemorySegment and this function
     * writes through a second indirection, but that is internal to the
     * caller; here we simply hand back the pointer. */
    (void)sym;
}

#endif /* _WIN32 */