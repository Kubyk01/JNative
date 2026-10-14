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