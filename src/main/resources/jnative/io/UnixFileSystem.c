#define _GNU_SOURCE
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <limits.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/types.h>

#include "jnative_runtime.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/*
 * Object layout used by this runtime for java.io.File:
 *
 *   [ 8-byte vtable ][ String path ]
 *
 * File.path is the only instance field; modern JDK sources declare it
 * first in the class body, which places it at offset OBJECT_HEADER_SIZE.
 */
#define FILE_HEADER_SIZE 8
#define FILE_PATH_OFFSET FILE_HEADER_SIZE

/*
 * Attribute bitmask returned by getBooleanAttributes0. The numeric values
 * are identical to the Java-side constants in
 * java.io.UnixFileSystem.BA_EXISTS / BA_REGULAR / BA_DIRECTORY / BA_HIDDEN
 * and are consumed verbatim by File.exists / isFile / isDirectory / isHidden.
 */
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

__attribute__((noreturn))
static void ufs_throw_io(const char* msg) {
    void* exc = __jnative_construct_exception(
        "vtable_java_io_IOException", msg ? msg : "I/O error");
    __jnative_throw_exception(exc);
}

/*
 * Extract the path from a java.io.File into a NUL-terminated stack buffer.
 * Returns 0 on success, -1 if the path does not fit in PATH_MAX. The buffer
 * is always filled (with an empty string in the worst case).
 */
static int extract_file_path(void* file_obj, char* out, size_t out_cap) {
    if (out_cap == 0) return -1;
    out[0] = '\0';
    if (file_obj == NULL) return -1;

    void* path_str = *(void**)((char*)file_obj + FILE_PATH_OFFSET);
    if (path_str == NULL) return -1;

    int32_t n = __jnative_read_string_into(path_str, out, (int32_t)out_cap);
    return (n < 0) ? -1 : 0;
}

void __jnative_fn_java_io_UnixFileSystem_initIDs___V(void) {
}

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

    size_t capacity = 16;
    size_t count = 0;
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

    void* array = jnative_ref_array_of_class((void**)names, (int32_t)count,
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

/* ==========================================================================
 *  Permission / metadata natives
 * ========================================================================== */

/*
 * private native boolean setPermission0(File f, int access,
 *                                       boolean ownerOnly,
 *                                       boolean enable);
 *
 * Applies or removes one permission bit (READ / WRITE / EXECUTE) for the
 * given file, for the owner alone or for owner+group+others.
 *
 * access bitmask matches the Java-side constants in
 * java.io.UnixFileSystem:
 *     4 = ACCESS_READ
 *     2 = ACCESS_WRITE
 *     1 = ACCESS_EXECUTE
 *
 * The current mode is read with stat(2), the corresponding permission
 * bits are added or removed, and chmod(2) writes the new mode back.
 * Any failure returns false; the Java caller (File.setReadable etc.)
 * translates false into a SecurityException or a plain false return
 * depending on context.
 */
int32_t __jnative_fn_java_io_UnixFileSystem_setPermission0__Ljava_io_File_IZZ_Z(
        void* this_fs, void* file_obj,
        int32_t access, int32_t ownerOnly, int32_t enable)
{
    (void)this_fs;
    if (file_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    char path[PATH_MAX];
    if (extract_file_path(file_obj, path, sizeof(path)) < 0) {
        return 0;
    }

    struct stat st;
    if (stat(path, &st) < 0) {
        return 0;
    }

    mode_t mode = st.st_mode;
    mode_t mask = 0;

    if (access & 4) {
        mask |= S_IRUSR;
        if (!ownerOnly) mask |= S_IRGRP | S_IROTH;
    }
    if (access & 2) {
        mask |= S_IWUSR;
        if (!ownerOnly) mask |= S_IWGRP | S_IWOTH;
    }
    if (access & 1) {
        mask |= S_IXUSR;
        if (!ownerOnly) mask |= S_IXGRP | S_IXOTH;
    }

    if (enable) {
        mode |= mask;
    } else {
        mode &= ~mask;
    }

    return chmod(path, mode) == 0 ? 1 : 0;
}

/*
 * private native long getNameMax0(String path);
 *
 * Maximum length of a single path component on the filesystem that
 * contains `path`. Uses pathconf(3) with _PC_NAME_MAX; a filesystem
 * that does not define the limit returns -1, in which case the POSIX
 * compile-time NAME_MAX is used. The Java-side default is 255 and is
 * applied by the caller when this native returns a non-positive value.
 */
int64_t __jnative_fn_java_io_UnixFileSystem_getNameMax0__Ljava_lang_String__J(
        void* this_fs, void* path_str)
{
    (void)this_fs;
    if (path_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    char path[PATH_MAX];
    if (__jnative_read_string_into(path_str, path, (int32_t)sizeof(path)) < 0) {
        return 0;
    }

    long max = pathconf(path, _PC_NAME_MAX);
    if (max <= 0) {
        max = NAME_MAX;
    }
    if (max <= 0) {
        max = 255;
    }
    return (int64_t)max;
}

/*
 * private native boolean rename0(File f1, File f2);
 *
 * Renames f1 to f2 using rename(2). Returns false on any failure —
 * EXDEV for cross-filesystem moves, ENOENT for a missing source,
 * EACCES for a missing parent write permission, and so on. The Java
 * layer treats false as "rename did not happen" and leaves both files
 * untouched.
 */
int32_t __jnative_fn_java_io_UnixFileSystem_rename0__Ljava_io_File_Ljava_io_File__Z(
        void* this_fs, void* file1, void* file2)
{
    (void)this_fs;
    if (file1 == NULL || file2 == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    char path1[PATH_MAX];
    char path2[PATH_MAX];
    if (extract_file_path(file1, path1, sizeof(path1)) < 0) return 0;
    if (extract_file_path(file2, path2, sizeof(path2)) < 0) return 0;

    return rename(path1, path2) == 0 ? 1 : 0;
}

/*
 * private native boolean setLastModifiedTime0(File f, long time);
 *
 * Sets the file's modification time to `time` milliseconds since the
 * Unix epoch. The access time is left unchanged by passing UTIME_OMIT
 * in the first slot of the timespec pair, which is the portable POSIX
 * way of saying "do not touch atime" without having to read it first
 * (and without racing another process that might be updating it).
 *
 * utimensat(2) and AT_FDCWD are declared in <fcntl.h>; UTIME_OMIT and
 * struct timespec come from <sys/stat.h> and <time.h>. Both headers
 * are included at the top of this file.
 */
int32_t __jnative_fn_java_io_UnixFileSystem_setLastModifiedTime0__Ljava_io_File_J_Z(
        void* this_fs, void* file_obj, int64_t time)
{
    (void)this_fs;
    if (file_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    char path[PATH_MAX];
    if (extract_file_path(file_obj, path, sizeof(path)) < 0) {
        return 0;
    }

    struct timespec ts[2];
    ts[0].tv_sec  = 0;
    ts[0].tv_nsec = UTIME_OMIT;
    ts[1].tv_sec  = (time_t)(time / 1000);
    ts[1].tv_nsec = (long)((time % 1000) * 1000000L);

    return utimensat(AT_FDCWD, path, ts, 0) == 0 ? 1 : 0;
}

/*
 * private native boolean createFileExclusively0(String pathname);
 *
 * Creates the file if and only if it does not already exist. Uses
 * open(2) with O_CREAT | O_EXCL, which is the atomic primitive the
 * Java contract requires: two concurrent calls on the same path must
 * produce exactly one success and one false.
 *
 * open(2) and its O_* flags are declared in <fcntl.h>, included at the
 * top of this file.
 */
int32_t __jnative_fn_java_io_UnixFileSystem_createFileExclusively0__Ljava_lang_String__Z(
        void* this_fs, void* path_str)
{
    (void)this_fs;
    if (path_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    char path[PATH_MAX];
    if (__jnative_read_string_into(path_str, path, (int32_t)sizeof(path)) < 0) {
        return 0;
    }

    int fd = open(path, O_RDWR | O_CREAT | O_EXCL, (mode_t)0666);
    if (fd < 0) {
        if (errno == EEXIST) {
            return 0;
        }
        return 0;
    }
    close(fd);
    return 1;
}

/*
 * private native boolean createDirectory0(File f);
 *
 * Creates a single directory component with mkdir(2). The mode 0777 is
 * masked by the process's umask, matching the standard Java behaviour:
 * a directory created by File.mkdir() is world-readable by default and
 * the umask narrows it if the user has set one. Parent directories are
 * not created — that is File.mkdirs(), which calls this native once
 * per component in the Java layer.
 */
int32_t __jnative_fn_java_io_UnixFileSystem_createDirectory0__Ljava_io_File__Z(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    char path[PATH_MAX];
    if (extract_file_path(file_obj, path, sizeof(path)) < 0) {
        return 0;
    }

    return mkdir(path, (mode_t)0777) == 0 ? 1 : 0;
}