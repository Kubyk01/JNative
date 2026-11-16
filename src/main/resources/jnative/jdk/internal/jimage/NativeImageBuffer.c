#define _GNU_SOURCE
#include "jnative_platform.h"
#include "jnative_runtime.h"

typedef struct JNativeImageMapping {
    char* path;
    void* address;
    int64_t size;
    struct JNativeImageMapping* next;
} JNativeImageMapping;

static jn_mutex_t image_mutex = JN_MUTEX_INITIALIZER;
static JNativeImageMapping* image_mappings;

static void* map_image(const char* path, int64_t* size) {
#if defined(_WIN32)
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);
    if (length == 0) return NULL;
    wchar_t* wide = malloc((size_t)length * sizeof(wchar_t));
    if (wide == NULL) return NULL;
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, wide, length) == 0) {
        free(wide);
        return NULL;
    }
    HANDLE file = CreateFileW(wide, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE,
                             NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    free(wide);
    if (file == INVALID_HANDLE_VALUE) return NULL;
    LARGE_INTEGER length_bytes;
    if (!GetFileSizeEx(file, &length_bytes) || length_bytes.QuadPart <= 0
            || length_bytes.QuadPart > INT32_MAX) {
        CloseHandle(file);
        return NULL;
    }
    HANDLE mapping = CreateFileMappingW(file, NULL, PAGE_READONLY, 0, 0, NULL);
    CloseHandle(file);
    if (mapping == NULL) return NULL;
    void* address = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 0);
    CloseHandle(mapping);
    if (address == NULL) return NULL;
    *size = length_bytes.QuadPart;
    return address;
#else
    int file = open(path, O_RDONLY);
    if (file < 0) return NULL;
    struct stat info;
    if (fstat(file, &info) != 0 || !S_ISREG(info.st_mode)
            || info.st_size <= 0 || info.st_size > INT32_MAX) {
        close(file);
        return NULL;
    }
    void* address = mmap(NULL, (size_t)info.st_size, PROT_READ, MAP_PRIVATE, file, 0);
    close(file);
    if (address == MAP_FAILED) return NULL;
    *size = (int64_t)info.st_size;
    return address;
#endif
}

void* __jnative_fn_jdk_internal_jimage_NativeImageBuffer_getNativeMap__Ljava_lang_String__Ljava_nio_ByteBuffer_(
        void* image_path)
{
    if (image_path == NULL) __jnative_throw_null_pointer_exception();
    const char* image_name = __jnative_read_string_cstr(image_path, NULL);
    if (image_name == NULL) return NULL;
    uint64_t embedded_size;
    const unsigned char* embedded = __jnative_runtime_file_data(image_name, &embedded_size);
    if (embedded != NULL && embedded_size <= INT32_MAX) {
        return __jnative_new_direct_byte_buffer((void*)embedded, (int64_t)embedded_size);
    }
    char* path = strdup(image_name);
    if (path == NULL) return NULL;
    jn_mutex_lock(&image_mutex);
    JNativeImageMapping* image = image_mappings;
    while (image != NULL && strcmp(image->path, path) != 0) image = image->next;
    if (image == NULL) {
        image = calloc(1, sizeof(*image));
        if (image != NULL) {
            image->address = map_image(path, &image->size);
            if (image->address == NULL) {
                free(image);
                image = NULL;
            } else {
                image->path = path;
                path = NULL;
                image->next = image_mappings;
                image_mappings = image;
            }
        }
    }
    jn_mutex_unlock(&image_mutex);
    free(path);
    return image != NULL ? __jnative_new_direct_byte_buffer(image->address, image->size) : NULL;
}
