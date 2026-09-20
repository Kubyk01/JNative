#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <limits.h>
#include <dlfcn.h>

extern void* __jnative_make_string_obj(const char* bytes, int32_t len);
extern const char* __jnative_read_string_bytes(void* s, int32_t* out_len);

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

#define JAVA_ARR_HDR 8

#define PROPERTIES_TABLE_OFFSET      8
#define PROPERTIES_COUNT_OFFSET     16
#define PROPERTIES_THRESHOLD_OFFSET 20
#define PROPERTIES_LOADFACTOR_OFFSET 24
#define PROPERTIES_MODCOUNT_OFFSET  28
#define PROPERTIES_DEFAULTS_OFFSET  32
#define PROPERTIES_MAP_OFFSET       40

#define CHM_TABLE_OFFSET     24
#define CHM_NEXTTABLE_OFFSET 32
#define CHM_BASECOUNT_OFFSET 40
#define CHM_SIZECTL_OFFSET   48
#define CHM_TRANSFERIDX_OFFSET 52
#define CHM_CELLSBUSY_OFFSET 56

#define CHM_COUNTERCELLS_OFFSET 64
#define CHM_KEYSET_OFFSET       72
#define CHM_VALUES_OFFSET       80
#define CHM_ENTRYSET_OFFSET     88

#define NODE_SIZE        40
#define NODE_HASH_OFFSET  8
#define NODE_KEY_OFFSET  16
#define NODE_VAL_OFFSET  24
#define NODE_NEXT_OFFSET 32

struct ReflectionClass {
    void* vtable;
    void* name;
    struct ReflectionClass* superclass;
    struct ReflectionClass** interfaces;
    void** methods;
    void** fields;
    void** constructors;
    int   modifiers;
    int   object_size;
};

extern struct ReflectionClass* reflect_all_classes[] __attribute__((weak));

static struct ReflectionClass* find_class(const char* name) {
    if (!name || reflect_all_classes == NULL) return NULL;
    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp) {
        const char* n = (const char*)(*pp)->name;
        if (n && strcmp(n, name) == 0) return *pp;
        pp++;
    }
    return NULL;
}

static void* lookup_class_vtable(struct ReflectionClass* cls) {
    if (!cls || !cls->name) return NULL;
    char buf[512];
    snprintf(buf, sizeof(buf), "__type_info_%s", (const char*)cls->name);
    for (char* p = buf; *p; p++) {
        if (*p == '/' || *p == '.') *p = '_';
    }
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (!handle) return NULL;
    void** type_info = (void**)dlsym(handle, buf);
    dlclose(handle);
    return type_info ? type_info[0] : NULL;
}

static void* alloc_java_object(const char* class_name, size_t min_size) {
    struct ReflectionClass* cls = find_class(class_name);
    if (!cls) return NULL;
    size_t size = (size_t)cls->object_size;
    if (size < min_size) size = min_size;
    if (size < 64) size = 64;
    void* obj = calloc(1, size);
    if (!obj) return NULL;
    void* vt = lookup_class_vtable(cls);
    if (!vt) { free(obj); return NULL; }
    *(void**)obj = vt;
    return obj;
}

static void* make_empty_string_array(void) {
    void* arr = malloc(JAVA_ARR_HDR);
    if (arr == NULL) return NULL;
    *(int32_t*)arr = 0;
    *(int32_t*)((char*)arr + 4) = 8;
    return arr;
}

static const char* resolve_java_home(char* buf, size_t bufsz) {
    if (bufsz == 0) return "";

    const char* env = getenv("JAVA_HOME");
    if (env && env[0]) {
        strncpy(buf, env, bufsz - 1);
        buf[bufsz - 1] = '\0';
        return buf;
    }

#if defined(__linux__)
    ssize_t n = readlink("/proc/self/exe", buf, bufsz - 1);
    if (n > 0) {
        buf[n] = '\0';
        char* slash = strrchr(buf, '/');
        if (slash && slash != buf) {
            *slash = '\0';
            return buf;
        }
    }
#elif defined(__APPLE__)
    {
        extern int _NSGetExecutablePath(char* buf, uint32_t* size);
        uint32_t size = (uint32_t)bufsz;
        if (_NSGetExecutablePath(buf, &size) == 0) {
            char* slash = strrchr(buf, '/');
            if (slash && slash != buf) {
                *slash = '\0';
                return buf;
            }
        }
    }
#endif

    strncpy(buf, "/usr/lib/jnative", bufsz - 1);
    buf[bufsz - 1] = '\0';
    return buf;
}

void* __jnative_fn_jdk_internal_util_SystemProps_Raw_cmdProperties___Ljava_util_HashMap_(
        void* self)
{
    (void)self;
    void* map = alloc_java_object("java/util/HashMap", 64);
    if (map == NULL) {
        fprintf(stderr, "jnative: fatal: HashMap not registered\n");
        abort();
    }
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (handle) {
        typedef void (*ctor_t)(void*);
        ctor_t hctor = (ctor_t)dlsym(handle, "fn_java_util_HashMap__init____V");
        if (hctor) hctor(map);
        dlclose(handle);
    }
    return map;
}

void* __jnative_fn_jdk_internal_util_SystemProps_Raw_platformProperties____Ljava_lang_String_(void) {
    return make_empty_string_array();
}

void* __jnative_fn_jdk_internal_util_SystemProps_Raw_vmProperties____Ljava_lang_String_(void) {
    return make_empty_string_array();
}

static uint32_t jnative_spread(int32_t h) {
    uint32_t uh = (uint32_t)h;
    return (uh ^ (uh >> 16)) & 0x7fffffff;
}

static int32_t jnative_string_hash(const char* s, int32_t len) {
    uint32_t h = 0;
    for (int32_t i = 0; i < len; i++) {
        h = 31u * h + (uint32_t)(uint8_t)s[i];
    }
    return (int32_t)h;
}

void* __jnative_make_bootstrap_props(
    int32_t props_table_off,
    int32_t props_count_off,
    int32_t props_threshold_off,
    int32_t props_loadfactor_off,
    int32_t props_modcount_off,
    int32_t props_defaults_off,
    int32_t props_map_off,
    int32_t chm_table_off,
    int32_t chm_basecount_off,
    int32_t chm_sizectl_off)
{
    if (props_table_off      <= 0) props_table_off      = PROPERTIES_TABLE_OFFSET;
    if (props_count_off      <= 0) props_count_off      = PROPERTIES_COUNT_OFFSET;
    if (props_threshold_off  <= 0) props_threshold_off  = PROPERTIES_THRESHOLD_OFFSET;
    if (props_loadfactor_off <= 0) props_loadfactor_off = PROPERTIES_LOADFACTOR_OFFSET;
    if (props_modcount_off   <= 0) props_modcount_off   = PROPERTIES_MODCOUNT_OFFSET;
    if (props_defaults_off   <= 0) props_defaults_off   = PROPERTIES_DEFAULTS_OFFSET;
    if (props_map_off        <= 0) props_map_off        = PROPERTIES_MAP_OFFSET;
    if (chm_table_off        <= 0) chm_table_off        = CHM_TABLE_OFFSET;
    if (chm_basecount_off    <= 0) chm_basecount_off    = CHM_BASECOUNT_OFFSET;
    if (chm_sizectl_off      <= 0) chm_sizectl_off      = CHM_SIZECTL_OFFSET;

    void* handle = dlopen(NULL, RTLD_LAZY);
    if (!handle) {
        fprintf(stderr, "jnative: fatal: dlopen(NULL) failed\n");
        abort();
    }

    void** node_type_info = (void**)dlsym(handle,
        "__type_info_java_util_concurrent_ConcurrentHashMap_Node");
    void* node_vtable = (node_type_info && node_type_info[0])
        ? node_type_info[0]
        : lookup_class_vtable(find_class("java/lang/Object"));
    if (!node_vtable) {
        fprintf(stderr, "jnative: fatal: no vtable for CHM.Node\n");
        abort();
    }

    void* chm = alloc_java_object("java/util/concurrent/ConcurrentHashMap", 128);
    if (!chm) {
        fprintf(stderr, "jnative: fatal: ConcurrentHashMap not registered\n");
        abort();
    }

    int table_size = 16;
    size_t table_total = JAVA_ARR_HDR + (size_t)table_size * sizeof(void*);
    void* chm_table = calloc(1, table_total);
    if (!chm_table) abort();
    *(int32_t*)chm_table = table_size;
    *(int32_t*)((char*)chm_table + 4) = 8;

    *(void**)((char*)chm + 8)  = NULL;
    *(void**)((char*)chm + 16) = NULL;
    *(void**)((char*)chm + chm_table_off)        = chm_table;
    *(void**)((char*)chm + CHM_NEXTTABLE_OFFSET) = NULL;
    *(int64_t*)((char*)chm + chm_basecount_off)  = 0;
    *(int32_t*)((char*)chm + chm_sizectl_off)    = 12;
    *(int32_t*)((char*)chm + CHM_TRANSFERIDX_OFFSET) = 0;
    *(int32_t*)((char*)chm + CHM_CELLSBUSY_OFFSET)   = 0;
    *(void**)((char*)chm + CHM_COUNTERCELLS_OFFSET)  = NULL;
    *(void**)((char*)chm + CHM_KEYSET_OFFSET)        = NULL;
    *(void**)((char*)chm + CHM_VALUES_OFFSET)        = NULL;
    *(void**)((char*)chm + CHM_ENTRYSET_OFFSET)      = NULL;

    void* props = alloc_java_object("java/util/Properties", 128);
    if (!props) {
        fprintf(stderr, "jnative: fatal: Properties not registered\n");
        abort();
    }

    *(void**)((char*)props + props_table_off)       = NULL;
    *(int32_t*)((char*)props + props_count_off)     = 0;
    *(int32_t*)((char*)props + props_threshold_off) = 0;
    *(float*)((char*)props + props_loadfactor_off)  = 0.75f;
    *(int32_t*)((char*)props + props_modcount_off)  = 0;
    *(void**)((char*)props + props_defaults_off)    = NULL;
    *(void**)((char*)props + props_map_off)         = chm;

    void** slots = (void**)((char*)chm_table + JAVA_ARR_HDR);
    int32_t entry_count = 0;

#define JNATIVE_INSERT(key_cstr, val_cstr) do {                                          \
        const char* _kc = (key_cstr);                                                    \
        const char* _vc = (val_cstr);                                                    \
        int32_t _klen = (int32_t)strlen(_kc);                                            \
        void* _k = __jnative_make_string_obj(_kc, _klen);                                \
        void* _v = __jnative_make_string_obj(_vc, (int32_t)strlen(_vc));                 \
        int32_t _hc = jnative_string_hash(_kc, _klen);                                   \
        uint32_t _h = jnative_spread(_hc);                                                \
        int _idx = (int)(_h & (uint32_t)(table_size - 1));                               \
        void* _node = calloc(1, NODE_SIZE);                                              \
        if (_node) {                                                                      \
            *(void**)((char*)_node + 0) = node_vtable;                                   \
            *(int32_t*)((char*)_node + NODE_HASH_OFFSET) = (int32_t)_h;                  \
            *(void**)((char*)_node + NODE_KEY_OFFSET) = _k;                              \
            *(void**)((char*)_node + NODE_VAL_OFFSET) = _v;                              \
            *(void**)((char*)_node + NODE_NEXT_OFFSET) = NULL;                           \
            if (slots[_idx] == NULL) {                                                    \
                slots[_idx] = _node;                                                      \
            } else {                                                                      \
                void* _cur = slots[_idx];                                                 \
                while (*(void**)((char*)_cur + NODE_NEXT_OFFSET) != NULL) {              \
                    _cur = *(void**)((char*)_cur + NODE_NEXT_OFFSET);                     \
                }                                                                         \
                *(void**)((char*)_cur + NODE_NEXT_OFFSET) = _node;                        \
            }                                                                             \
            entry_count++;                                                                \
        }                                                                                 \
    } while (0)

    char home_buf[PATH_MAX];
    const char* java_home = resolve_java_home(home_buf, sizeof(home_buf));
    char cwd_buf[PATH_MAX];
    const char* cwd = getcwd(cwd_buf, sizeof(cwd_buf));
    if (cwd == NULL) cwd = "/";
    const char* user = getenv("USER");
    if (user == NULL || user[0] == '\0') user = "user";
    const char* home = getenv("HOME");
    if (home == NULL || home[0] == '\0') home = "/";

    JNATIVE_INSERT("java.home", java_home);
    JNATIVE_INSERT("user.home", home);
    JNATIVE_INSERT("user.dir",  cwd);
    JNATIVE_INSERT("user.name", user);

    JNATIVE_INSERT("java.version",               "21.0.0");
    JNATIVE_INSERT("java.version.date",          "2023-09-19");
    JNATIVE_INSERT("java.vendor",                "jnative");
    JNATIVE_INSERT("java.vendor.url",            "https://github.com/kubyk01/jnative");
    JNATIVE_INSERT("java.vendor.version",        "jnative");
    JNATIVE_INSERT("java.specification.version", "21");
    JNATIVE_INSERT("java.specification.vendor",  "jnative");
    JNATIVE_INSERT("java.specification.name",    "Java Platform API Specification");
    JNATIVE_INSERT("java.vm.specification.version", "21");
    JNATIVE_INSERT("java.vm.specification.vendor",  "jnative");
    JNATIVE_INSERT("java.vm.specification.name",    "Java Virtual Machine Specification");
    JNATIVE_INSERT("java.vm.version",            "0.1");
    JNATIVE_INSERT("java.vm.vendor",             "jnative");
    JNATIVE_INSERT("java.vm.name",               "jnative");
    JNATIVE_INSERT("java.vm.info",               "jnative");
    JNATIVE_INSERT("jdk.debug",                  "release");

    JNATIVE_INSERT("os.name", "Linux");
#if defined(__x86_64__)
    JNATIVE_INSERT("os.arch", "amd64");
#elif defined(__aarch64__)
    JNATIVE_INSERT("os.arch", "aarch64");
#else
    JNATIVE_INSERT("os.arch", "unknown");
#endif
    JNATIVE_INSERT("os.version", "");

    JNATIVE_INSERT("file.separator",             "/");
    JNATIVE_INSERT("path.separator",             ":");
    JNATIVE_INSERT("line.separator",             "\n");
    JNATIVE_INSERT("file.encoding",              "UTF-8");
    JNATIVE_INSERT("native.encoding",            "UTF-8");
    JNATIVE_INSERT("stdin.encoding",             "UTF-8");
    JNATIVE_INSERT("stdout.encoding",            "UTF-8");
    JNATIVE_INSERT("stderr.encoding",            "UTF-8");
    JNATIVE_INSERT("sun.jnu.encoding",           "UTF-8");
    JNATIVE_INSERT("sun.io.unicode.encoding",    "UnicodeLittle");

    JNATIVE_INSERT("jdk.serialFilter",           "");
    JNATIVE_INSERT("sun.nio.MaxCachedBufferSize", "");
    JNATIVE_INSERT("jdk.module.path",            "");
    JNATIVE_INSERT("jdk.module.upgrade.path",    "");
    JNATIVE_INSERT("jdk.module.main",            "");
    JNATIVE_INSERT("jdk.module.main.class",      "");

#undef JNATIVE_INSERT

    *(int64_t*)((char*)chm + chm_basecount_off) = (int64_t)entry_count;

    dlclose(handle);
    return props;
}