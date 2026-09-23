#define _GNU_SOURCE
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <limits.h>
#include <dirent.h>
#include <sys/stat.h>

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
 * and are consumed verbatim by File.exists / isFile / isDirectory /
 * isHidden.
 */
#define BA_EXISTS    0x01
#define BA_REGULAR   0x02
#define BA_DIRECTORY 0x04
#define BA_HIDDEN    0x08

/*
 * Access-mode constants passed to checkAccess0. The numeric values match
 * the Java-side constants in java.io.FileSystem:
 *
 *   ACCESS_READ    = 0x04   == R_OK
 *   ACCESS_WRITE   = 0x02   == W_OK
 *   ACCESS_EXECUTE = 0x01   == X_OK
 *
 * The value 0 (F_OK) means "does the file exist at all", matching
 * File.exists().
 */
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

/* --------------------------------------------------------------------------
 * private static void initIDs();
 *
 * Called from the static initializer of java.io.UnixFileSystem to cache
 * the JNI field IDs of the class's instance fields (slash, colon,
 * javaHome, userDir, cache, ...). This runtime does not use JNI field
 * IDs anywhere: instance fields are accessed directly through their
 * LLVM-computed byte offsets, so there is nothing to cache. The symbol
 * must exist because the class's <clinit> emits a native call to it.
 * ------------------------------------------------------------------------ */
void __jnative_fn_java_io_UnixFileSystem_initIDs___V(void) {
}

/* --------------------------------------------------------------------------
 * private native int getBooleanAttributes0(File f);
 *
 * Returns a bitmask describing the file named by the argument's `path`
 * field. Only four bits are defined:
 *
 *   BA_EXISTS     (1) - the path resolves via stat(2)
 *   BA_REGULAR    (2) - the resolved entry is a regular file
 *   BA_DIRECTORY  (4) - the resolved entry is a directory
 *   BA_HIDDEN     (8) - the basename begins with '.' and is not exactly
 *                       "." or ".."
 *
 * The hidden-bit rule matches the JDK's Unix implementation: on Unix,
 * "hidden" is defined purely by the leading-dot convention, not by any
 * file-system attribute. The two special names "." and ".." are not
 * considered hidden, even though their first character is a dot.
 *
 * A file that cannot be stat()ed (missing, permission-denied parent
 * directory, broken symlink, …) simply contributes no bits; the caller
 * interprets a zero result as "does not exist". No exception is thrown
 * for the not-found case, matching java.io.File.exists.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_java_io_UnixFileSystem_getBooleanAttributes0__Ljava_io_File__I(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    void* path_str = *(void**)((char*)file_obj + FILE_PATH_OFFSET);
    if (path_str == NULL) {
        return 0;
    }

    int32_t pathLen = 0;
    const char* path = __jnative_read_string_bytes(path_str, &pathLen);
    if (path == NULL || pathLen == 0) {
        return 0;
    }

    int32_t result = 0;

    struct stat st;
    if (stat(path, &st) == 0) {
        result |= BA_EXISTS;
        if (S_ISREG(st.st_mode)) result |= BA_REGULAR;
        if (S_ISDIR(st.st_mode)) result |= BA_DIRECTORY;
    }

    /* Hidden test: basename starts with '.' and is not "." or "..". */
    const char* base = strrchr(path, '/');
    base = (base != NULL) ? base + 1 : path;

    if (base[0] == '.' && base[1] != '\0') {
        int is_dot      = (base[1] == '.' && base[2] == '\0');
        if (!is_dot) {
            result |= BA_HIDDEN;
        }
    }

    return result;
}

/* --------------------------------------------------------------------------
 * private native boolean checkAccess0(File f, int access);
 *
 * Answers the access question that File.canRead / canWrite / canExecute
 * delegate to. The `access` argument carries the numeric R_OK / W_OK /
 * X_OK bits defined in java.io.FileSystem (0 means "existence only").
 *
 * The underlying access(2) syscall uses the real UID and GID rather than
 * the effective ones, which is exactly what the JDK's own Unix
 * implementation uses: the contract of File.canRead / canWrite /
 * canExecute is about the process's real credentials, not whatever an
 * elevation mechanism has temporarily installed. A return value of 0
 * means the query was denied or the file does not exist; anything else
 * means access is permitted. The boolean result is expressed in the same
 * true/false sense as the Java method.
 *
 * This function never throws: File.canRead's contract is to answer with
 * a boolean even when the underlying file is inaccessible or missing.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_java_io_UnixFileSystem_checkAccess0__Ljava_io_File_I_Z(
        void* this_fs, void* file_obj, int32_t access_mode)
{
    (void)this_fs;
    if (file_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    void* path_str = *(void**)((char*)file_obj + FILE_PATH_OFFSET);
    if (path_str == NULL) {
        return 0;
    }

    int32_t pathLen = 0;
    const char* path = __jnative_read_string_bytes(path_str, &pathLen);
    if (path == NULL || pathLen == 0) {
        return 0;
    }

    /*
     * The Java-side constants for ACCESS_READ / ACCESS_WRITE /
     * ACCESS_EXECUTE are, by design, the same numbers as R_OK / W_OK /
     * X_OK. Passing `access_mode` straight through to access(2) is
     * therefore exactly the mapping the JDK's own C code performs.
     *
     * F_OK (0) — the "existence only" query that File.exists uses — is
     * handled by the same call: access(path, F_OK) returns 0 iff the
     * path resolves.
     */
    return access(path, (int)access_mode) == 0 ? 1 : 0;
}

/* --------------------------------------------------------------------------
 * private native long getLength0(File f);
 *
 * Total size in bytes of the named file or directory entry, obtained via
 * stat(2) on the path stored in the File object's `path` field. Matches
 * the contract of File.length(), which returns 0 for a file that does
 * not exist and for a path that cannot be stat()ed — the caller
 * interprets the zero as "no length available" rather than as a
 * genuine zero-byte file.
 *
 * Directories report the size of their directory entry, which on every
 * Linux filesystem is a small multiple of the block size; that is the
 * same value File.length() returns under HotSpot.
 *
 * No exception is thrown: File.length() is specified to answer with 0
 * for a missing or unreadable file rather than to propagate an I/O
 * error, and the caller has no way to distinguish the two cases in the
 * reference implementation either.
 * ------------------------------------------------------------------------ */
int64_t __jnative_fn_java_io_UnixFileSystem_getLength0__Ljava_io_File__J(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    void* path_str = *(void**)((char*)file_obj + FILE_PATH_OFFSET);
    if (path_str == NULL) {
        return 0;
    }

    int32_t pathLen = 0;
    const char* path = __jnative_read_string_bytes(path_str, &pathLen);
    if (path == NULL || pathLen == 0) {
        return 0;
    }

    struct stat st;
    if (stat(path, &st) < 0) {
        return 0;
    }
    return (int64_t)st.st_size;
}

/* --------------------------------------------------------------------------
 * private native long getLastModifiedTime0(File f);
 *
 * Returns the last-modified time of the named file as a count of
 * milliseconds since the Unix epoch, matching the contract of
 * File.lastModified().
 *
 * The kernel reports the timestamp in seconds via struct stat's
 * st_mtime; multiplying by 1000 produces the millisecond value the Java
 * layer expects. Sub-second precision is deliberately not preserved —
 * File.lastModified() is specified in milliseconds, and st_mtim.tv_nsec
 * (where available) would be lost in the conversion to a Java long
 * anyway on the JDK's own Unix path.
 *
 * A file that cannot be stat()ed contributes a 0 result, which is the
 * same "unknown" sentinel File.lastModified returns for a missing file.
 * No exception is thrown.
 * ------------------------------------------------------------------------ */
int64_t __jnative_fn_java_io_UnixFileSystem_getLastModifiedTime0__Ljava_io_File__J(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    void* path_str = *(void**)((char*)file_obj + FILE_PATH_OFFSET);
    if (path_str == NULL) {
        return 0;
    }

    int32_t pathLen = 0;
    const char* path = __jnative_read_string_bytes(path_str, &pathLen);
    if (path == NULL || pathLen == 0) {
        return 0;
    }

    struct stat st;
    if (stat(path, &st) < 0) {
        return 0;
    }
    return (int64_t)st.st_mtime * 1000LL;
}

/* --------------------------------------------------------------------------
 * private native boolean delete0(File f);
 *
 * Deletes the file or empty directory named by the argument's `path`
 * field. Returns true on success, false on failure.
 *
 * The JDK's contract for File.delete is: return false if the file does
 * not exist, if it is a non-empty directory, if the caller lacks write
 * permission on the parent directory, or if the OS refuses the
 * operation for any other reason. Unlike most java.io natives, this one
 * does not throw — the caller inspects the boolean and translates a
 * false result into the same false that File.delete returns.
 *
 * `unlink(2)` handles both regular files and symlinks. `rmdir(2)` is
 * required for directories because unlink refuses them outright with
 * EISDIR on Linux. The implementation tries unlink first and falls back
 * to rmdir only when the path is known to be a directory, avoiding the
 * extra stat in the common case where the target is a file.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_java_io_UnixFileSystem_delete0__Ljava_io_File__Z(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    void* path_str = *(void**)((char*)file_obj + FILE_PATH_OFFSET);
    if (path_str == NULL) {
        return 0;
    }

    int32_t pathLen = 0;
    const char* path = __jnative_read_string_bytes(path_str, &pathLen);
    if (path == NULL || pathLen == 0) {
        return 0;
    }

    /* Fast path: most deletes target a regular file or a symlink, and
     * unlink(2) removes both in one syscall. Only on EISDIR (which the
     * kernel returns specifically when the target is a directory) do we
     * pay for the second attempt. */
    if (unlink(path) == 0) {
        return 1;
    }

    if (errno == EISDIR || errno == EPERM) {
        /* EPERM is what Linux returns for rmdir on some filesystems
         * where unlink fails first; try the directory path. */
        struct stat st;
        if (stat(path, &st) == 0 && S_ISDIR(st.st_mode)) {
            if (rmdir(path) == 0) {
                return 1;
            }
        }
    }

    return 0;
}

/* --------------------------------------------------------------------------
 * private native String[] list0(File f);
 *
 * Returns the names of the entries in the directory named by the
 * argument's `path` field, as a String[]. The "." and ".." entries that
 * readdir(3) reports are skipped, matching the Java-side contract of
 * File.list().
 *
 * On any failure — a null receiver, a missing path, a path that is not
 * a directory, EACCES on the directory itself, a malloc failure while
 * building the array — the function returns NULL, which the Java layer
 * interprets as "the listing could not be obtained" and translates to
 * the null result that File.list() returns for such cases. Returning an
 * empty array instead would be wrong: File.list() distinguishes between
 * "the directory is empty" (empty array) and "the directory could not
 * be listed" (null), and the callers that walk the result rely on that
 * distinction — File.listFiles, File.list(FilenameFilter), and the
 * directory-copy paths in java.nio all treat the two cases differently.
 *
 * Each element of the returned array is a freshly constructed Java
 * String object rather than a raw byte[]: the Java-side callers read
 * the entries through String.length()/charAt() and their ilk, and a
 * bare byte[] would trap the moment it was touched.
 *
 * The array uses the runtime's standard Java-array layout — an 8-byte
 * header holding the length and element size, followed by the pointer
 * payload — so that the GET_FIELD / ARRAYLENGTH machinery in the
 * emitted code sees the shape it expects.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_java_io_UnixFileSystem_list0__Ljava_io_File___Ljava_lang_String_(
        void* this_fs, void* file_obj)
{
    (void)this_fs;
    if (file_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    void* path_str = *(void**)((char*)file_obj + FILE_PATH_OFFSET);
    if (path_str == NULL) {
        return NULL;
    }

    int32_t pathLen = 0;
    const char* path = __jnative_read_string_bytes(path_str, &pathLen);
    if (path == NULL || pathLen == 0) {
        return NULL;
    }

    DIR* dir = opendir(path);
    if (dir == NULL) {
        /* Not a directory, no read permission, missing, symlink loop —
         * all of these are the "unlistable" case that File.list
         * reports as null. */
        return NULL;
    }

    /*
     * Collect names into a growable C-side buffer first. We cannot know
     * the final count until readdir has drained, and pre-sizing to
     * PATH_MAX entries would waste memory for the common small
     * directory while still being wrong for a directory with more
     * entries than PATH_MAX.
     */
    size_t capacity = 16;
    size_t count    = 0;
    void** names    = (void**)malloc(capacity * sizeof(void*));
    if (names == NULL) {
        closedir(dir);
        return NULL;
    }

    struct dirent* entry;
    errno = 0;
    while ((entry = readdir(dir)) != NULL) {
        const char* name = entry->d_name;
        if (name == NULL) continue;

        /* Skip the two syntactic entries that every directory carries. */
        if (name[0] == '.' && name[1] == '\0') continue;
        if (name[0] == '.' && name[1] == '.' && name[2] == '\0') continue;

        size_t nameLen = strlen(name);
        void* str = __jnative_make_string_obj(name, (int32_t)nameLen);
        if (str == NULL) {
            /* Out of memory; abandon the partially-built list. */
            for (size_t i = 0; i < count; i++) {
                /* The String objects are GC-managed in the host
                 * runtime, but this image has no GC. Each String and
                 * its backing byte[] were malloc'd by
                 * __jnative_make_string_obj; leaking them here is
                 * preferable to dereferencing free'd memory. Leave
                 * them for the process image to reclaim on exit. */
            }
            free(names);
            closedir(dir);
            return NULL;
        }

        if (count == capacity) {
            capacity *= 2;
            void** grown = (void**)realloc(names, capacity * sizeof(void*));
            if (grown == NULL) {
                free(names);
                closedir(dir);
                return NULL;
            }
            names = grown;
        }
        names[count++] = str;

        errno = 0;
    }

    /* readdir sets errno on error; a non-zero value after the loop
     * means the iteration ended because of an I/O error rather than
     * end-of-stream. File.list() reports that as null. */
    int readdir_error = (errno != 0);
    closedir(dir);

    if (readdir_error) {
        free(names);
        return NULL;
    }

    /*
     * Build the Java String[] through the shared allocator so its header
     * carries the [Ljava/lang/String; class mirror alongside the length
     * and element size.
     */
    void* array = jnative_ref_array_of_class((void**)names, (int32_t)count,
                                             "[Ljava/lang/String;");
    free(names);
    return array;
}

/* --------------------------------------------------------------------------
 * private native String canonicalize0(String path) throws IOException;
 *
 * Returns the canonical (absolute, symlink-free, normalised) form of
 * `path`. The JDK's File.getCanonicalPath contract is that the result
 * refers to the same file as the input and is in a system-dependent
 * canonical form.
 *
 * On Linux the canonicalisation is done by realpath(3), which:
 *
 *   1. makes the path absolute by prepending the current working
 *      directory if it is relative;
 *   2. resolves every symbolic link encountered along the way;
 *   3. collapses "." and ".." components;
 *   4. ensures the result names an existing file — realpath fails with
 *      ENOENT if any intermediate component does not exist.
 *
 * The last point is important: File.getCanonicalPath throws IOException
 * when the file does not exist, which matches the behaviour the JDK's
 * own Unix implementation exhibits. Returning the input unchanged on
 * failure would silently hide the error; the throw helper is what the
 * Java caller's IOException handler expects.
 *
 * The result is returned as a Java String built through the runtime's
 * shared string-construction helper. PATH_MAX bounds the buffer;
 * realpath is allowed by POSIX to return a longer allocation when the
 * result would exceed that, but on Linux it uses the caller-supplied
 * buffer and fails with ENAMETOOLONG otherwise — the failure surfaces
 * through the throw helper, and the Java caller can retry with an
 * explicit getAbsolutePath-based fallback if it cares to.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_java_io_UnixFileSystem_canonicalize0__Ljava_lang_String__Ljava_lang_String_(
        void* this_fs, void* path_str)
{
    (void)this_fs;
    if (path_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    int32_t pathLen = 0;
    const char* path = __jnative_read_string_bytes(path_str, &pathLen);
    if (path == NULL || pathLen == 0) {
        return path_str;
    }

    /* realpath with a caller-supplied buffer does not allocate; the
     * result is written into buf and must be shorter than PATH_MAX. */
    char buf[PATH_MAX];
    char* resolved = realpath(path, buf);
    if (resolved == NULL) {
        /* The file does not exist, a component along the way is not a
         * directory, a symlink is broken, or the result would exceed
         * PATH_MAX. All of those are IOException at the Java level. */
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    size_t rlen = strlen(resolved);
    return __jnative_make_string_obj(resolved, (int32_t)rlen);
}