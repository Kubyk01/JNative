/*
 * sun.nio.fs.UnixNativeDispatcher — POSIX native bindings.
 *
 * ============================================================================
 * Platform structure
 * ============================================================================
 *
 * The actual implementation is guarded by `#if !defined(_WIN32)`. On the
 * POSIX branch it is a thin wrapper over the runtime syscalls
 * (open, openat, lstat, statvfs, symlink, renameat, …). On Windows the
 * file compiles to linker stubs that throw a diagnostic
 * UnsupportedOperationException if called.
 *
 * ============================================================================
 * Why the class can even be linked on Windows
 * ============================================================================
 *
 * sun.nio.fs.UnixNativeDispatcher is a Unix-only class: on a real JDK
 * build, Windows never loads it because FileSystemProvider selection is
 * done by name from os.name, and Windows picks WindowsFileSystemProvider
 * which uses WindowsNativeDispatcher. On a JNative Windows build, however,
 * the reachability analysis does not filter Unix-only classes for the
 * target, so the whole Unix filesystem subsystem
 * (UnixNativeDispatcher, LinuxNativeDispatcher, UnixFileAttributes,
 * UnixFileStoreAttributes, UnixPath, UnixFileSystem, …) ends up in the
 * linked image whether or not it can ever run.
 *
 * The linker therefore sees direct calls to the UnixNativeDispatcher_*
 * symbols and needs bodies for them. This file provides those bodies. On
 * the POSIX side they are real. On Windows they are diagnostic throwers.
 *
 * ============================================================================
 * Error handling on POSIX
 * ============================================================================
 *
 * Every failing syscall produces a real sun.nio.fs.UnixException:
 *
 *   - its vtable is @vtable_sun_nio_fs_UnixException, so `catch
 *     (UnixException)` in the surrounding Java code fires;
 *   - its `errno` field is populated from the reflection metadata that
 *     LlvmGlobalEmitter emitted (not from a hard-coded offset), so code
 *     that inspects UnixException.errno() sees the actual error code;
 *   - its detail message is set from strerror(3), matching what the
 *     reference implementation's getMessage() would compute.
 *
 * If the UnixException class is not in the image (a build defect — it is
 * always reachable whenever any file-system code is reachable) the helper
 * falls back to java.io.IOException, and then to java.lang.Exception.
 *
 * ============================================================================
 * EINTR
 * ============================================================================
 *
 * Every syscall loops on EINTR. A signal that arrives while the process
 * is blocked inside open/renameat/etc. must not surface as an
 * InterruptedIOException to the Java caller; the reference JDK's own C
 * implementations retry in exactly the same way.
 *
 * ============================================================================
 * lstat0 / statvfs0 field offsets
 * ============================================================================
 *
 * The layout of sun.nio.fs.UnixFileAttributes and
 * sun.nio.fs.UnixFileStoreAttributes is computed by
 * LlvmGlobalEmitter.getFieldOffset at build time and emitted into the
 * .rodata section of _module.ll. At run time it is reachable through
 * reflect_all_classes[] -> ReflectionClass.fields -> ReflectionField.offset.
 *
 * This file looks the offsets up once, on first use, and caches them.
 * Hard-coding them (the previous approach, used for the reflect mirrors
 * before) is correct on exactly one JDK release and silently mis-routes
 * on the next.
 */

#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>

#include "jnative_runtime.h"

/* ============================================================================
 *                       WINDOWS: LINKER STUBS
 * ============================================================================
 *
 * The Unix filesystem primitives cannot be implemented on Windows: there
 * is no openat, no lstat, no statvfs, no symlink, and no sendfile. The
 * stubs below satisfy the linker and, on the off chance that a Windows
 * image somehow reaches UnixNativeDispatcher at run time, throw a clear
 * exception that names the operation and explains the situation.
 *
 * init() returns 1 so that UnixNativeDispatcher.<clinit> succeeds — a
 * failure there would surface as ExceptionInInitializerError from a
 * reflective class load, which is far less informative than the
 * per-operation UnsupportedOperationException that the stubs raise when
 * they are actually reached.
 * ========================================================================== */

#if defined(_WIN32)

__attribute__((noreturn))
static void throw_unsupported(const char* op) {
    char msg[512];
    snprintf(msg, sizeof(msg),
        "%s is not implemented on Windows: "
        "sun.nio.fs.UnixNativeDispatcher requires POSIX file-system "
        "primitives (openat, lstat, statvfs, symlink, ...) that Windows "
        "does not provide. This class was linked into the image because "
        "the reachability analysis did not filter Unix-only classes for "
        "this target, or because os.name does not match the running "
        "platform. If you are running a Windows-targeted build, this "
        "operation should never have been reached.",
        op);

    void* exc = __jnative_construct_exception(
        "vtable_java_lang_UnsupportedOperationException", msg);
    if (exc == NULL) {
        exc = __jnative_construct_exception("vtable_java_lang_Exception", msg);
    }
    if (exc == NULL) {
        exc = __jnative_construct_exception("vtable_java_lang_Throwable", msg);
    }
    __jnative_throw_exception(exc);
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___I(void) {
    /* Let <clinit> succeed. Every real operation below throws on first
     * call, giving a diagnostic message that names the operation. */
    return 1;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_close0__I_V(int32_t fd) {
    /* Nothing was opened by open0 (which throws), so there is nothing to
     * close. Silence is correct here: the reference implementation also
     * treats close as a no-op on an invalid fd. */
    (void)fd;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_open0__JII_I(
        int64_t pathAddress, int32_t flags, int32_t mode) {
    (void)pathAddress; (void)flags; (void)mode;
    throw_unsupported("UnixNativeDispatcher.open0");
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_openat0__IJII_I(
        int32_t dfd, int64_t pathAddress, int32_t flags, int32_t mode) {
    (void)dfd; (void)pathAddress; (void)flags; (void)mode;
    throw_unsupported("UnixNativeDispatcher.openat0");
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_opendir0__J_J(
        int64_t pathAddress) {
    (void)pathAddress;
    throw_unsupported("UnixNativeDispatcher.opendir0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_mkdir0__JI_V(
        int64_t pathAddress, int32_t mode) {
    (void)pathAddress; (void)mode;
    throw_unsupported("UnixNativeDispatcher.mkdir0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rmdir0__J_V(int64_t pathAddress) {
    (void)pathAddress;
    throw_unsupported("UnixNativeDispatcher.rmdir0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlink0__J_V(int64_t pathAddress) {
    (void)pathAddress;
    throw_unsupported("UnixNativeDispatcher.unlink0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlinkat0__IJI_V(
        int32_t dfd, int64_t pathAddress, int32_t flags) {
    (void)dfd; (void)pathAddress; (void)flags;
    throw_unsupported("UnixNativeDispatcher.unlinkat0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_symlink0__JJ_V(
        int64_t newAddress, int64_t targetAddress) {
    (void)newAddress; (void)targetAddress;
    throw_unsupported("UnixNativeDispatcher.symlink0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_renameat0__IJIJ_V(
        int32_t dfd1, int64_t pathAddress1, int32_t dfd2, int64_t pathAddress2) {
    (void)dfd1; (void)pathAddress1; (void)dfd2; (void)pathAddress2;
    throw_unsupported("UnixNativeDispatcher.renameat0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chmod0__JI_V(
        int64_t pathAddress, int32_t mode) {
    (void)pathAddress; (void)mode;
    throw_unsupported("UnixNativeDispatcher.chmod0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchmod0__II_V(
        int32_t fd, int32_t mode) {
    (void)fd; (void)mode;
    throw_unsupported("UnixNativeDispatcher.fchmod0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid) {
    (void)pathAddress; (void)uid; (void)gid;
    throw_unsupported("UnixNativeDispatcher.chown0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lchown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid) {
    (void)pathAddress; (void)uid; (void)gid;
    throw_unsupported("UnixNativeDispatcher.lchown0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchown0__III_V(
        int32_t fd, int32_t uid, int32_t gid) {
    (void)fd; (void)uid; (void)gid;
    throw_unsupported("UnixNativeDispatcher.fchown0");
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_exists0__J_Z(
        int64_t pathAddress) {
    (void)pathAddress;
    throw_unsupported("UnixNativeDispatcher.exists0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lstat0__JLsun_nio_fs_UnixFileAttributes__V(
        int64_t pathAddress, void* attrs) {
    (void)pathAddress; (void)attrs;
    throw_unsupported("UnixNativeDispatcher.lstat0");
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_statvfs0__JLsun_nio_fs_UnixFileStoreAttributes__V(
        int64_t pathAddress, void* attrs) {
    (void)pathAddress; (void)attrs;
    throw_unsupported("UnixNativeDispatcher.statvfs0");
}

int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_directCopy0__IIJ_I(
        int32_t dst, int32_t src, int64_t count) {
    (void)dst; (void)src; (void)count;
    throw_unsupported("LinuxNativeDispatcher.directCopy0");
}

#else /* !_WIN32 — POSIX IMPLEMENTATION */
/* ============================================================================
 *                      POSIX: REAL IMPLEMENTATION
 * ========================================================================== */

#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/statvfs.h>

#if defined(__linux__)
#  include <sys/sendfile.h>
#endif

/* -------------------------------------------------------------------------
 * Exception construction
 * ----------------------------------------------------------------------- */

static void* make_unix_exception(int err, const char* msg) {
    void* exc = __jnative_construct_exception(
        "vtable_sun_nio_fs_UnixException", msg);
    if (exc != NULL) {
        ReflectionClass* cls = jnative_class_by_name("sun/nio/fs/UnixException");
        if (cls != NULL && cls->fields != NULL) {
            for (ReflectionField** fp = cls->fields; *fp != NULL; fp++) {
                const char* fname = (const char*)(*fp)->name;
                if (fname != NULL && strcmp(fname, "errno") == 0) {
                    *(int32_t*)((char*)exc + (*fp)->offset) = err;
                    break;
                }
            }
        }
    }
    return exc;
}

__attribute__((noreturn))
static void throw_unix_exception(int err) {
    const char* msg = strerror(err);
    void* exc = make_unix_exception(err, msg);
    if (exc == NULL) {
        exc = __jnative_construct_exception("vtable_java_io_IOException", msg);
    }
    if (exc == NULL) {
        exc = __jnative_construct_exception("vtable_java_lang_Exception", msg);
    }
    __jnative_throw_exception(exc);
}

/* -------------------------------------------------------------------------
 * Field-offset lookup, cached
 * ----------------------------------------------------------------------- */

static int32_t lookup_field(ReflectionClass* cls, const char* name) {
    if (cls == NULL || cls->fields == NULL) return -1;
    for (ReflectionField** fp = cls->fields; *fp != NULL; fp++) {
        const char* fname = (const char*)(*fp)->name;
        if (fname != NULL && strcmp(fname, name) == 0) return (*fp)->offset;
    }
    return -1;
}

/* sun.nio.fs.UnixFileAttributes */
static jn_once_t attrs_fields_once = JN_ONCE_INIT;
static int32_t f_st_mode          = -1;
static int32_t f_st_ino           = -1;
static int32_t f_st_dev           = -1;
static int32_t f_st_rdev          = -1;
static int32_t f_st_nlink         = -1;
static int32_t f_st_uid           = -1;
static int32_t f_st_gid           = -1;
static int32_t f_st_size          = -1;
static int32_t f_st_atime_sec     = -1;
static int32_t f_st_atime_nsec    = -1;
static int32_t f_st_mtime_sec     = -1;
static int32_t f_st_mtime_nsec    = -1;
static int32_t f_st_ctime_sec     = -1;
static int32_t f_st_ctime_nsec    = -1;
static int32_t f_st_birthtime_sec = -1;

static void init_attrs_fields(void) {
    ReflectionClass* cls = jnative_class_by_name("sun/nio/fs/UnixFileAttributes");
    if (cls == NULL) return;
    f_st_mode          = lookup_field(cls, "st_mode");
    f_st_ino           = lookup_field(cls, "st_ino");
    f_st_dev           = lookup_field(cls, "st_dev");
    f_st_rdev          = lookup_field(cls, "st_rdev");
    f_st_nlink         = lookup_field(cls, "st_nlink");
    f_st_uid           = lookup_field(cls, "st_uid");
    f_st_gid           = lookup_field(cls, "st_gid");
    f_st_size          = lookup_field(cls, "st_size");
    f_st_atime_sec     = lookup_field(cls, "st_atime_sec");
    f_st_atime_nsec    = lookup_field(cls, "st_atime_nsec");
    f_st_mtime_sec     = lookup_field(cls, "st_mtime_sec");
    f_st_mtime_nsec    = lookup_field(cls, "st_mtime_nsec");
    f_st_ctime_sec     = lookup_field(cls, "st_ctime_sec");
    f_st_ctime_nsec    = lookup_field(cls, "st_ctime_nsec");
    f_st_birthtime_sec = lookup_field(cls, "st_birthtime_sec");
}

/* sun.nio.fs.UnixFileStoreAttributes */
static jn_once_t store_fields_once = JN_ONCE_INIT;
static int32_t f_f_bsize  = -1;
static int32_t f_f_frsize = -1;
static int32_t f_f_blocks = -1;
static int32_t f_f_bfree  = -1;
static int32_t f_f_bavail = -1;

static void init_store_fields(void) {
    ReflectionClass* cls = jnative_class_by_name("sun/nio/fs/UnixFileStoreAttributes");
    if (cls == NULL) return;
    f_f_bsize  = lookup_field(cls, "f_bsize");
    f_f_frsize = lookup_field(cls, "f_frsize");
    f_f_blocks = lookup_field(cls, "f_blocks");
    f_f_bfree  = lookup_field(cls, "f_bfree");
    f_f_bavail = lookup_field(cls, "f_bavail");
}

/* -------------------------------------------------------------------------
 * Public natives
 * ----------------------------------------------------------------------- */

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___I(void) {
    jn_once(&attrs_fields_once, init_attrs_fields);
    jn_once(&store_fields_once, init_store_fields);
    return 1;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_open0__JII_I(
        int64_t pathAddress, int32_t flags, int32_t mode) {
    const char* path = (const char*)(intptr_t)pathAddress;
    int fd;
    do { fd = open(path, (int)flags, (mode_t)mode); }
    while (fd < 0 && errno == EINTR);
    if (fd < 0) throw_unix_exception(errno);
    return fd;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_openat0__IJII_I(
        int32_t dfd, int64_t pathAddress, int32_t flags, int32_t mode) {
    const char* path = (const char*)(intptr_t)pathAddress;
    int fd;
    do { fd = openat((int)dfd, path, (int)flags, (mode_t)mode); }
    while (fd < 0 && errno == EINTR);
    if (fd < 0) throw_unix_exception(errno);
    return fd;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_close0__I_V(int32_t fd) {
    (void)close((int)fd);
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_opendir0__J_J(
        int64_t pathAddress) {
    const char* path = (const char*)(intptr_t)pathAddress;
    DIR* dir = opendir(path);
    if (dir == NULL) throw_unix_exception(errno);
    return (int64_t)(intptr_t)dir;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_mkdir0__JI_V(
        int64_t pathAddress, int32_t mode) {
    const char* path = (const char*)(intptr_t)pathAddress;
    int r;
    do { r = mkdir(path, (mode_t)mode); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rmdir0__J_V(int64_t pathAddress) {
    const char* path = (const char*)(intptr_t)pathAddress;
    int r;
    do { r = rmdir(path); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlink0__J_V(int64_t pathAddress) {
    const char* path = (const char*)(intptr_t)pathAddress;
    int r;
    do { r = unlink(path); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlinkat0__IJI_V(
        int32_t dfd, int64_t pathAddress, int32_t flags) {
    const char* path = (const char*)(intptr_t)pathAddress;
    int r;
    do { r = unlinkat((int)dfd, path, (int)flags); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_symlink0__JJ_V(
        int64_t newAddress, int64_t targetAddress) {
    const char* new_path = (const char*)(intptr_t)newAddress;
    const char* target   = (const char*)(intptr_t)targetAddress;
    int r;
    do { r = symlink(new_path, target); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_renameat0__IJIJ_V(
        int32_t dfd1, int64_t pathAddress1, int32_t dfd2, int64_t pathAddress2) {
    const char* old_path = (const char*)(intptr_t)pathAddress1;
    const char* new_path = (const char*)(intptr_t)pathAddress2;
    int r;
    do { r = renameat((int)dfd1, old_path, (int)dfd2, new_path); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chmod0__JI_V(
        int64_t pathAddress, int32_t mode) {
    const char* path = (const char*)(intptr_t)pathAddress;
    int r;
    do { r = chmod(path, (mode_t)mode); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchmod0__II_V(
        int32_t fd, int32_t mode) {
    int r;
    do { r = fchmod((int)fd, (mode_t)mode); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid) {
    const char* path = (const char*)(intptr_t)pathAddress;
    int r;
    do { r = chown(path, (uid_t)uid, (gid_t)gid); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lchown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid) {
    const char* path = (const char*)(intptr_t)pathAddress;
    int r;
    do { r = lchown(path, (uid_t)uid, (gid_t)gid); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchown0__III_V(
        int32_t fd, int32_t uid, int32_t gid) {
    int r;
    do { r = fchown((int)fd, (uid_t)uid, (gid_t)gid); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_exists0__J_Z(
        int64_t pathAddress) {
    const char* path = (const char*)(intptr_t)pathAddress;
    return access(path, F_OK) == 0 ? 1 : 0;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lstat0__JLsun_nio_fs_UnixFileAttributes__V(
        int64_t pathAddress, void* attrs) {
    if (attrs == NULL) __jnative_throw_null_pointer_exception();

    const char* path = (const char*)(intptr_t)pathAddress;
    struct stat st;
    int r;
    do { r = lstat(path, &st); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);

    jn_once(&attrs_fields_once, init_attrs_fields);

    if (f_st_mode  > 0) *(int32_t*)((char*)attrs + f_st_mode)  = (int32_t)st.st_mode;
    if (f_st_ino   > 0) *(int64_t*)((char*)attrs + f_st_ino)   = (int64_t)st.st_ino;
    if (f_st_dev   > 0) *(int64_t*)((char*)attrs + f_st_dev)   = (int64_t)st.st_dev;
    if (f_st_rdev  > 0) *(int64_t*)((char*)attrs + f_st_rdev)  = (int64_t)st.st_rdev;
    if (f_st_nlink > 0) *(int32_t*)((char*)attrs + f_st_nlink) = (int32_t)st.st_nlink;
    if (f_st_uid   > 0) *(int32_t*)((char*)attrs + f_st_uid)   = (int32_t)st.st_uid;
    if (f_st_gid   > 0) *(int32_t*)((char*)attrs + f_st_gid)   = (int32_t)st.st_gid;
    if (f_st_size  > 0) *(int64_t*)((char*)attrs + f_st_size)  = (int64_t)st.st_size;

#if defined(__APPLE__)
    if (f_st_atime_sec  > 0) *(int64_t*)((char*)attrs + f_st_atime_sec)  = (int64_t)st.st_atimespec.tv_sec;
    if (f_st_atime_nsec > 0) *(int64_t*)((char*)attrs + f_st_atime_nsec) = (int64_t)st.st_atimespec.tv_nsec;
    if (f_st_mtime_sec  > 0) *(int64_t*)((char*)attrs + f_st_mtime_sec)  = (int64_t)st.st_mtimespec.tv_sec;
    if (f_st_mtime_nsec > 0) *(int64_t*)((char*)attrs + f_st_mtime_nsec) = (int64_t)st.st_mtimespec.tv_nsec;
    if (f_st_ctime_sec  > 0) *(int64_t*)((char*)attrs + f_st_ctime_sec)  = (int64_t)st.st_ctimespec.tv_sec;
    if (f_st_ctime_nsec > 0) *(int64_t*)((char*)attrs + f_st_ctime_nsec) = (int64_t)st.st_ctimespec.tv_nsec;
    if (f_st_birthtime_sec > 0) *(int32_t*)((char*)attrs + f_st_birthtime_sec) = (int32_t)st.st_birthtimespec.tv_sec;
#else
    if (f_st_atime_sec  > 0) *(int64_t*)((char*)attrs + f_st_atime_sec)  = (int64_t)st.st_atim.tv_sec;
    if (f_st_atime_nsec > 0) *(int64_t*)((char*)attrs + f_st_atime_nsec) = (int64_t)st.st_atim.tv_nsec;
    if (f_st_mtime_sec  > 0) *(int64_t*)((char*)attrs + f_st_mtime_sec)  = (int64_t)st.st_mtim.tv_sec;
    if (f_st_mtime_nsec > 0) *(int64_t*)((char*)attrs + f_st_mtime_nsec) = (int64_t)st.st_mtim.tv_nsec;
    if (f_st_ctime_sec  > 0) *(int64_t*)((char*)attrs + f_st_ctime_sec)  = (int64_t)st.st_ctim.tv_sec;
    if (f_st_ctime_nsec > 0) *(int64_t*)((char*)attrs + f_st_ctime_nsec) = (int64_t)st.st_ctim.tv_nsec;
    if (f_st_birthtime_sec > 0) *(int32_t*)((char*)attrs + f_st_birthtime_sec) = 0;  /* Linux has no birthtime */
#endif
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_statvfs0__JLsun_nio_fs_UnixFileStoreAttributes__V(
        int64_t pathAddress, void* attrs) {
    if (attrs == NULL) __jnative_throw_null_pointer_exception();

    const char* path = (const char*)(intptr_t)pathAddress;
    struct statvfs vfs;
    int r;
    do { r = statvfs(path, &vfs); }
    while (r < 0 && errno == EINTR);
    if (r < 0) throw_unix_exception(errno);

    jn_once(&store_fields_once, init_store_fields);

    if (f_f_bsize  > 0) *(int64_t*)((char*)attrs + f_f_bsize)  = (int64_t)vfs.f_bsize;
    if (f_f_frsize > 0) *(int64_t*)((char*)attrs + f_f_frsize) = (int64_t)vfs.f_frsize;
    if (f_f_blocks > 0) *(int64_t*)((char*)attrs + f_f_blocks) = (int64_t)vfs.f_blocks;
    if (f_f_bfree  > 0) *(int64_t*)((char*)attrs + f_f_bfree)  = (int64_t)vfs.f_bfree;
    if (f_f_bavail > 0) *(int64_t*)((char*)attrs + f_f_bavail) = (int64_t)vfs.f_bavail;
}

int32_t __jnative_fn_sun_nio_fs_LinuxNativeDispatcher_directCopy0__IIJ_I(
        int32_t dst, int32_t src, int64_t count) {
#if defined(__linux__)
    ssize_t n = sendfile((int)dst, (int)src, NULL, (size_t)count);
    if (n < 0) throw_unix_exception(errno);
    return (int32_t)n;
#else
    /*
     * Non-Linux fallback: userspace copy. This path is unreachable in
     * practice — the only caller is LinuxFileSystem.directCopy, which is
     * only reachable on Linux — but keeping a correct implementation
     * here means the linker never sees a missing symbol even on those
     * platforms.
     */
    char buf[8192];
    int64_t remaining = count;
    int32_t total = 0;
    while (remaining > 0) {
        size_t chunk = (size_t)(remaining < (int64_t)sizeof(buf)
                                ? remaining : (int64_t)sizeof(buf));
        ssize_t n = read((int)src, buf, chunk);
        if (n < 0) {
            if (errno == EINTR) continue;
            throw_unix_exception(errno);
        }
        if (n == 0) break;
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write((int)dst, buf + written, (size_t)(n - written));
            if (w < 0) {
                if (errno == EINTR) continue;
                throw_unix_exception(errno);
            }
            written += w;
        }
        remaining -= n;
        total     += (int32_t)n;
    }
    return total;
#endif
}

#endif /* _WIN32 */