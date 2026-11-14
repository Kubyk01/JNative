#define _GNU_SOURCE
#include "jnative_platform.h"
#include "jnative_portability.h"
#include "jnative_runtime.h"
#include <errno.h>
#include <limits.h>
#include <wchar.h>

static jn_once_t runtime_once = JN_ONCE_INIT;
static char* runtime_home;
static uint64_t runtime_files_written;

#if defined(_WIN32)
static wchar_t* runtime_wide_path(const char* path) {
    int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);
    if (count <= 0) { errno = EINVAL; return NULL; }
    wchar_t* result = malloc((size_t)count * sizeof(wchar_t));
    if (result == NULL) return NULL;
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, result, count) <= 0) {
        free(result);
        errno = EINVAL;
        return NULL;
    }
    return result;
}

static char* create_runtime_directory(void) {
    DWORD count = GetTempPathW(0, NULL);
    if (count == 0) { errno = EIO; return NULL; }
    const wchar_t prefix[] = L"jnative-runtime-";
    unsigned char random[16];
    size_t capacity = count + sizeof(prefix) / sizeof(wchar_t) + sizeof(random) * 2;
    wchar_t* path = malloc(capacity * sizeof(wchar_t));
    if (path == NULL) return NULL;
    DWORD length = GetTempPathW(count, path);
    if (length == 0 || length >= count) { free(path); errno = EIO; return NULL; }
    memcpy(path + length, prefix, sizeof(prefix));
    size_t start = length + sizeof(prefix) / sizeof(wchar_t) - 1;
    HMODULE library = LoadLibraryW(L"advapi32.dll");
    typedef BOOLEAN (WINAPI *RandomFunction)(void*, ULONG);
    RandomFunction generate = library == NULL ? NULL
        : (RandomFunction)(void*)GetProcAddress(library, "SystemFunction036");
    int created = 0;
    while (generate != NULL && generate(random, sizeof(random))) {
        const wchar_t digits[] = L"0123456789abcdef";
        for (size_t i = 0; i < sizeof(random); i++) {
            path[start + i * 2] = digits[random[i] >> 4];
            path[start + i * 2 + 1] = digits[random[i] & 15];
        }
        path[start + sizeof(random) * 2] = L'\0';
        if (CreateDirectoryW(path, NULL)) { created = 1; break; }
        if (GetLastError() != ERROR_ALREADY_EXISTS) break;
    }
    if (library != NULL) FreeLibrary(library);
    if (!created) { free(path); errno = EIO; return NULL; }
    int bytes = WideCharToMultiByte(CP_UTF8, 0, path, -1, NULL, 0, NULL, NULL);
    char* result = bytes > 0 ? malloc((size_t)bytes) : NULL;
    if (result != NULL && WideCharToMultiByte(CP_UTF8, 0, path, -1, result, bytes, NULL, NULL) == 0) {
        free(result);
        result = NULL;
    }
    if (result == NULL) RemoveDirectoryW(path);
    free(path);
    return result;
}

static int runtime_mkdir(const char* path) {
    wchar_t* wide = runtime_wide_path(path);
    if (wide == NULL) return -1;
    int result = CreateDirectoryW(wide, NULL) ? 0 : -1;
    if (result != 0 && GetLastError() == ERROR_ALREADY_EXISTS) {
        DWORD attributes = GetFileAttributesW(wide);
        if (attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY)
                && !(attributes & FILE_ATTRIBUTE_REPARSE_POINT)) result = 0;
    }
    free(wide);
    if (result != 0) errno = EIO;
    return result;
}

static void runtime_remove(const char* path, int directory) {
    wchar_t* wide = runtime_wide_path(path);
    if (wide == NULL) return;
    if (directory) RemoveDirectoryW(wide);
    else DeleteFileW(wide);
    free(wide);
}
#else
static char* create_runtime_directory(void) {
    const char* temporary = getenv("TMPDIR");
    if (temporary == NULL || temporary[0] == '\0') temporary = P_tmpdir;
    char* base = realpath(temporary, NULL);
    if (base == NULL) return NULL;
    const char suffix[] = "/jnative-runtime-XXXXXX";
    size_t size = strlen(base) + sizeof(suffix);
    char* path = malloc(size);
    if (path != NULL) snprintf(path, size, "%s%s", base, suffix);
    free(base);
    if (path == NULL) return NULL;
    if (mkdtemp(path) == NULL) { free(path); return NULL; }
    return path;
}

static int runtime_mkdir(const char* path) {
    if (mkdir(path, 0700) == 0) return 0;
    if (errno != EEXIST) return -1;
    struct stat attributes;
    return lstat(path, &attributes) == 0 && S_ISDIR(attributes.st_mode) ? 0 : -1;
}

static void runtime_remove(const char* path, int directory) {
    if (directory) rmdir(path);
    else unlink(path);
}
#endif

static int valid_runtime_path(const char* path) {
    if (path == NULL || path[0] == '\0') return 0;
    const char* component = path;
    for (const char* p = path; ; p++) {
        if (*p == '\\' || *p == ':') return 0;
        if (*p != '/' && *p != '\0') continue;
        size_t length = (size_t)(p - component);
        if (length == 0 || (length == 1 && component[0] == '.')
                || (length == 2 && component[0] == '.' && component[1] == '.')) return 0;
        if (*p == '\0') return 1;
        component = p + 1;
    }
}

static char* runtime_file_path(const char* name) {
    size_t base = strlen(runtime_home);
    size_t length = strlen(name);
    if (length > SIZE_MAX - base - 2) { errno = ENAMETOOLONG; return NULL; }
    char* path = malloc(base + length + 2);
    if (path != NULL) {
        memcpy(path, runtime_home, base);
        path[base] = '/';
        memcpy(path + base + 1, name, length + 1);
    }
    return path;
}

static void cleanup_runtime_directory(void) {
    if (runtime_home == NULL) return;
    size_t base = strlen(runtime_home);
    for (uint64_t i = runtime_files_written; i > 0; i--) {
        const char* name = jnative_runtime_files[i - 1].path;
        if (!valid_runtime_path(name)) continue;
        char* path = runtime_file_path(name);
        if (path == NULL) continue;
        runtime_remove(path, 0);
        char* slash;
        while ((slash = strrchr(path, '/')) != NULL && (size_t)(slash - path) > base) {
            *slash = '\0';
            runtime_remove(path, 1);
        }
        free(path);
    }
    runtime_remove(runtime_home, 1);
    free(runtime_home);
    runtime_home = NULL;
}

static JN_NORETURN void runtime_failure(const char* path) {
    int error = errno;
    fprintf(stderr, "jnative: cannot materialize embedded runtime %s: %s\n", path, strerror(error));
    cleanup_runtime_directory();
    exit(EXIT_FAILURE);
}

static void initialize_runtime_directory(void) {
    runtime_home = create_runtime_directory();
    if (runtime_home == NULL) runtime_failure("directory");
    if (atexit(cleanup_runtime_directory) != 0) { errno = ENOMEM; runtime_failure(runtime_home); }
    if (jnative_runtime_data_size > SIZE_MAX) { errno = EFBIG; runtime_failure(runtime_home); }
    for (uint64_t i = 0; i < jnative_runtime_files_count; i++) {
        const JNativeRuntimeFile* file = &jnative_runtime_files[i];
        if (!valid_runtime_path(file->path) || file->offset > jnative_runtime_data_size
                || file->size > jnative_runtime_data_size - file->offset) {
            errno = EINVAL;
            runtime_failure("file table");
        }
        runtime_files_written = i + 1;
        char* path = runtime_file_path(file->path);
        if (path == NULL) runtime_failure(file->path);
        for (char* p = path + strlen(runtime_home) + 1; *p != '\0'; p++) {
            if (*p != '/') continue;
            *p = '\0';
            if (runtime_mkdir(path) != 0) runtime_failure(path);
            *p = '/';
        }
        int flags = O_WRONLY | O_CREAT | O_EXCL;
#if defined(_WIN32)
        flags |= O_BINARY;
#endif
        int fd = jn_open_utf8(path, flags, 0600);
        if (fd < 0) runtime_failure(path);
        const unsigned char* data = jnative_runtime_data + (size_t)file->offset;
        uint64_t remaining = file->size;
        while (remaining > 0) {
            unsigned int chunk = remaining > INT_MAX ? INT_MAX : (unsigned int)remaining;
            ssize_t written = write(fd, data, chunk);
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) {
                int error = written == 0 ? EIO : errno;
                close(fd);
                errno = error;
                runtime_failure(path);
            }
            data += written;
            remaining -= (uint64_t)written;
        }
        if (close(fd) != 0) runtime_failure(path);
        free(path);
    }
}

const char* __jnative_runtime_java_home(void) {
    if (jn_once(&runtime_once, initialize_runtime_directory) != 0 || runtime_home == NULL) {
        errno = EIO;
        runtime_failure("directory initialization");
    }
    return runtime_home;
}

char* __jnative_runtime_path(const char* name) {
    __jnative_runtime_java_home();
    if (!valid_runtime_path(name)) { errno = EINVAL; runtime_failure("file path"); }
    char* path = runtime_file_path(name);
    if (path == NULL) runtime_failure(name);
    return path;
}

char* __jnative_runtime_class_path(void) {
    const char* home = __jnative_runtime_java_home();
    size_t base = strlen(home);
    size_t capacity = 1;
    for (size_t i = 0; jnative_runtime_classpath[i] != NULL; i++) {
        const char* name = jnative_runtime_classpath[i];
        if (!valid_runtime_path(name)) { errno = EINVAL; runtime_failure("classpath"); }
        size_t length = strlen(name);
        if (base > SIZE_MAX - length - 2 || capacity > SIZE_MAX - base - length - 2) {
            errno = ENAMETOOLONG;
            runtime_failure("classpath");
        }
        capacity += base + length + 2;
    }
    char* result = malloc(capacity);
    if (result == NULL) runtime_failure("classpath");
    char* next = result;
    for (size_t i = 0; jnative_runtime_classpath[i] != NULL; i++) {
        if (i > 0) {
#if defined(_WIN32)
            *next++ = ';';
#else
            *next++ = ':';
#endif
        }
        memcpy(next, home, base);
        next += base;
        *next++ = '/';
        size_t length = strlen(jnative_runtime_classpath[i]);
        memcpy(next, jnative_runtime_classpath[i], length);
        next += length;
    }
    *next = '\0';
    return result;
}

const unsigned char* __jnative_runtime_file_data(const char* path, uint64_t* size) {
    if (path == NULL || size == NULL) return NULL;
    const char* home = __jnative_runtime_java_home();
    size_t prefix = strlen(home);
    if (strncmp(path, home, prefix) != 0) return NULL;
    if (path[prefix] != '/' && path[prefix] != '\\') return NULL;
    const char* relative = path + prefix + 1;
    for (uint64_t i = 0; i < jnative_runtime_files_count; i++) {
        const JNativeRuntimeFile* file = &jnative_runtime_files[i];
        const char* a = relative;
        const char* b = file->path;
        while (*a != '\0' && *b != '\0' && (*a == *b || (*a == '\\' && *b == '/'))) { a++; b++; }
        if (*a == '\0' && *b == '\0') {
            *size = file->size;
            return jnative_runtime_data + (size_t)file->offset;
        }
    }
    return NULL;
}
