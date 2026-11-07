#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <limits.h>
#include "jnative_runtime.h"

/*
 * JAVA_ARR_HDR is taken from jnative_runtime.h. This file used to redefine
 * it as 8 locally, which silently shadowed the real array header size and
 * desynchronised every array built here from the one the LLVM emitter and
 * the rest of the runtime use.
 */
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

#define HASHMAP_TABLE_OFF      24
#define HASHMAP_ENTRYSET_OFF   32
#define HASHMAP_SIZE_OFF       40
#define HASHMAP_MODCOUNT_OFF   44
#define HASHMAP_THRESHOLD_OFF  48
#define HASHMAP_LOADFACTOR_OFF 52

static struct ReflectionClass* find_class(const char* name) {
    if (!name) return NULL;
    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp) {
        const char* n = (*pp)->cname;
        if (n && strcmp(n, name) == 0) return *pp;
        pp++;
    }
    return NULL;
}

static void* lookup_class_vtable(struct ReflectionClass* cls) {
    if (!cls || !cls->cname) return NULL;
    char buf[512];
    snprintf(buf, sizeof(buf), "__type_info_%s", cls->cname);
    for (char* p = buf; *p; p++) {
        if (*p == '/' || *p == '.') *p = '_';
    }
    void* handle = jn_dlsym_default;
    if (!handle) return NULL;
    void** type_info = (void**)jn_dlsym_default( buf);
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
    return jnative_ref_array_of_class(NULL, 0, "[Ljava/lang/String;");
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
    ssize_t n = jn_readlink("/proc/self/exe", buf, bufsz - 1);
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

static const char* resolve_library_path(char* buf, size_t bufsz) {
    if (bufsz == 0) return "";
    buf[0] = '\0';
#if defined(_WIN32)
    DWORD n = GetModuleFileNameA(NULL, buf, (DWORD)bufsz);
    long len = (n > 0 && n < bufsz) ? (long)n : -1;
    const char sep = ';';
    const char* env = getenv("PATH");
#else
    long len = jn_readlink("/proc/self/exe", buf, bufsz - 1);
    const char sep = ':';
    const char* env = getenv("LD_LIBRARY_PATH");
#endif
    size_t pos = 0;
    if (len > 0) {
        buf[len] = '\0';
        char* cut = NULL;
        for (char* p = buf; *p != '\0'; p++) {
            if (*p == '/' || *p == '\\') cut = p;
        }
        if (cut == buf) cut++;
        if (cut != NULL) *cut = '\0';
        pos = strlen(buf);
    } else {
        buf[0] = '\0';
    }
    if (env != NULL && env[0] != '\0') {
        size_t elen = strlen(env);
        if (pos > 0 && pos + 1 < bufsz) buf[pos++] = sep;
        if (pos + elen >= bufsz) elen = bufsz - pos - 1;
        memcpy(buf + pos, env, elen);
        pos += elen;
        buf[pos] = '\0';
    }
    return buf;
}

static const char* resolve_tmpdir(char* buf, size_t bufsz) {
    if (bufsz == 0) return "/tmp";

    const char* env = getenv("TMPDIR");
    if (env != NULL && env[0] != '\0') {
        size_t len = strlen(env);
        if (len >= bufsz) len = bufsz - 1;
        memcpy(buf, env, len);
        buf[len] = '\0';
        while (len > 1 && buf[len - 1] == '/') {
            buf[--len] = '\0';
        }
        return buf;
    }

    strncpy(buf, "/tmp", bufsz - 1);
    buf[bufsz - 1] = '\0';
    return buf;
}

static const char* resolve_os_arch(void) {
#if defined(__x86_64__)
    return "amd64";
#elif defined(__aarch64__)
    return "aarch64";
#elif defined(__i386__)
    return "i386";
#else
    return "unknown";
#endif
}

static const char* resolve_arch_abi(void) {
#if defined(__x86_64__)
    return "x86_64";
#elif defined(__aarch64__)
    return "aarch64";
#elif defined(__i386__)
    return "i386";
#else
    return "unknown";
#endif
}

static const char* resolve_user_home(char* buf, size_t bufsz) {
    if (bufsz == 0) return "";
    const char* home = getenv("HOME");
    if (home == NULL || home[0] == '\0') home = "/";
    strncpy(buf, home, bufsz - 1);
    buf[bufsz - 1] = '\0';
    return buf;
}

static const char* resolve_user_name(char* buf, size_t bufsz) {
    if (bufsz == 0) return "";
    const char* user = getenv("USER");
    if (user == NULL || user[0] == '\0') user = getenv("LOGNAME");
    if (user == NULL || user[0] == '\0') user = "user";
    strncpy(buf, user, bufsz - 1);
    buf[bufsz - 1] = '\0';
    return buf;
}

static void resolve_locale(char* lang, size_t langsz,
                           char* country, size_t countrysz) {
    if (langsz == 0 || countrysz == 0) return;
    lang[0] = '\0';
    country[0] = '\0';

    const char* loc = NULL;
    const char* env = getenv("LC_ALL");
    if (env != NULL && env[0] != '\0') loc = env;
    if (loc == NULL) {
        env = getenv("LC_MESSAGES");
        if (env != NULL && env[0] != '\0') loc = env;
    }
    if (loc == NULL) {
        env = getenv("LANG");
        if (env != NULL && env[0] != '\0') loc = env;
    }

    if (loc != NULL) {
        size_t i = 0;
        while (loc[i] != '\0' && loc[i] != '_' && loc[i] != '.' && loc[i] != '@'
               && i + 1 < langsz) {
            lang[i] = (char)tolower((unsigned char)loc[i]);
            i++;
        }
        lang[i] = '\0';
        if (loc[i] == '_') {
            size_t j = 0;
            i++;
            while (loc[i] != '\0' && loc[i] != '_' && loc[i] != '.' && loc[i] != '@'
                   && j + 1 < countrysz) {
                country[j] = (char)toupper((unsigned char)loc[i]);
                j++;
            }
            country[j] = '\0';
        }
    }

    if (lang[0] == '\0') {
        strncpy(lang, "en", langsz - 1);
        lang[langsz - 1] = '\0';
    }
    if (country[0] == '\0') {
        strncpy(country, "US", countrysz - 1);
        country[countrysz - 1] = '\0';
    }
}

typedef struct {
    const char* name;
    int32_t     value;
} JNativeClassIntConstant;

extern const JNativeClassIntConstant jnative_systemprops_raw_constants[];

static int32_t raw_constant(const char* name) {
    for (const JNativeClassIntConstant* c = jnative_systemprops_raw_constants; c->name != NULL; c++) {
        if (strcmp(c->name, name) == 0) return c->value;
    }
    return -1;
}

#define RAW_NDX(prop) raw_constant("_" #prop "_NDX")

static void* make_string_array(int32_t len) {
    return jnative_ref_array_of_class(NULL, len, "[Ljava/lang/String;");
}

static void string_array_set(void* arr, int32_t index, const char* value) {
    if (arr == NULL || value == NULL || index < 0) return;
    *(void**)((char*)arr + JAVA_ARR_HDR + (size_t)index * sizeof(void*)) =
        __jnative_make_string_obj(value, (int32_t)strlen(value));
}

static int32_t jnative_string_hash(const char* s, int32_t len) {
    uint32_t h = 0;
    for (int32_t i = 0; i < len; i++) {
        h = 31u * h + (uint32_t)(uint8_t)s[i];
    }
    return (int32_t)h;
}

static uint32_t jnative_spread(int32_t h) {
    uint32_t uh = (uint32_t)h;
    return (uh ^ (uh >> 16)) & 0x7fffffff;
}

static uint32_t jnative_map_spread(int32_t h) {
    uint32_t uh = (uint32_t)h;
    return uh ^ (uh >> 16);
}

/*
 * =====================================================================
 *  Raw.cmdProperties()
 * =====================================================================
 *
 * Returns a freshly-constructed java.util.HashMap populated with the
 * properties that in a real VM would have been supplied on the command
 * line (-D flags). In a native image there are no -D flags, but the
 * JDK's own SystemProps.initProperties expects java.home to be present
 * in the merged result, and the single property this native therefore
 * publishes is java.home.
 *
 * ---------------------------------------------------------------------
 *  Key construction: 9 bytes, not 10
 * ---------------------------------------------------------------------
 *
 * "java.home" is exactly nine ASCII characters:
 *
 *     j a v a . h o m e
 *     1 2 3 4 5 6 7 8 9
 *
 * The terminating NUL byte of the C string literal must NOT be included
 * in the Java String's payload. A String constructed with length 10
 * would carry a trailing '\0' character that no other part of the
 * runtime produces, and the resulting HashMap key would never compare
 * equal to the nine-character literal "java.home" that
 * SystemProps.initProperties uses in its lookup.
 *
 * Two independent consequences followed from the previous hard-coded
 * length of 10:
 *
 *   1. String.hashCode() over "java.home\0" differs from hashCode() over
 *      "java.home", so the node was inserted into a different bucket
 *      than the one the later lookup inspected.
 *
 *   2. Even if the buckets had matched, the key comparison inside
 *      HashMap.getNode would have failed, because the stored key had
 *      one extra character.
 *
 * The observable symptom was the AssertionError "java.home not set"
 * raised from SystemProps.initProperties during System.initPhase1 — the
 * java.home entry was physically present in the map but invisible to
 * the lookup.
 *
 * Both the value-construction call and the hash-computation call had
 * the same off-by-one length, so both are corrected below. To prevent
 * the two from ever drifting apart again, the key and its byte length
 * are pulled into two named constants and every use in this function
 * refers to those constants rather than to a string literal plus a
 * hard-coded integer.
 */
static void cmd_props_put(void* table, int table_size, void* node_vtable,
                          const char* key_cstr, const char* val_cstr)
{
    int32_t klen = (int32_t)strlen(key_cstr);
    int32_t h = jnative_string_hash(key_cstr, klen);
    uint32_t spread = jnative_map_spread(h);
    int idx = (int)(spread & (uint32_t)(table_size - 1));
    void** slot = (void**)((char*)table + JAVA_ARR_HDR + (size_t)idx * sizeof(void*));

    void* node = calloc(1, NODE_SIZE);
    if (node == NULL) abort();
    *(void**)((char*)node + 0) = node_vtable;
    *(int32_t*)((char*)node + NODE_HASH_OFFSET) = (int32_t)spread;
    *(void**)((char*)node + NODE_KEY_OFFSET) = __jnative_make_string_obj(key_cstr, klen);
    *(void**)((char*)node + NODE_VAL_OFFSET) =
        __jnative_make_string_obj(val_cstr, (int32_t)strlen(val_cstr));
    *(void**)((char*)node + NODE_NEXT_OFFSET) = *slot;
    *slot = node;
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

    void* handle = jn_dlsym_default;
    if (!handle) {
        fprintf(stderr, "jnative: fatal: dlopen(NULL) failed\n");
        abort();
    }
    void** node_type_info = (void**)jn_dlsym_default( "__type_info_java_util_HashMap_Node");
    void* node_vtable = (node_type_info && node_type_info[0])
        ? node_type_info[0]
        : lookup_class_vtable(find_class("java/lang/Object"));
    if (!node_vtable) {
        fprintf(stderr, "jnative: fatal: no vtable for HashMap.Node\n");
        abort();
    }

    int table_size = 16;
    void* table = jnative_ref_array_of_class(NULL, table_size,
                                             "[Ljava/util/HashMap$Node;");
    if (table == NULL) abort();

    char home_buf[PATH_MAX];
    const char* java_home = resolve_java_home(home_buf, sizeof(home_buf));

    char libpath_buf[PATH_MAX * 4];
    const char* libpath = resolve_library_path(libpath_buf, sizeof(libpath_buf));

    cmd_props_put(table, table_size, node_vtable, "java.home", java_home);
    cmd_props_put(table, table_size, node_vtable, "java.library.path", libpath);
    cmd_props_put(table, table_size, node_vtable, "sun.boot.library.path", libpath);
    cmd_props_put(table, table_size, node_vtable,
                  "jdk.reflect.useNativeAccessorOnly", "true");
    cmd_props_put(table, table_size, node_vtable,
                  "jdk.reflect.useDirectMethodHandle", "methods");

    *(void**)((char*)map + 8)  = NULL;
    *(void**)((char*)map + 16) = NULL;

    *(void**)((char*)map + HASHMAP_TABLE_OFF)      = table;
    *(void**)((char*)map + HASHMAP_ENTRYSET_OFF)   = NULL;
    *(int32_t*)((char*)map + HASHMAP_SIZE_OFF)     = 5;
    *(int32_t*)((char*)map + HASHMAP_MODCOUNT_OFF) = 5;
    *(int32_t*)((char*)map + HASHMAP_THRESHOLD_OFF)= 12;
    *(float*)((char*)map + HASHMAP_LOADFACTOR_OFF) = 0.75f;

    return map;
}

/*
 * =====================================================================
 *  Raw.platformProperties()
 * =====================================================================
 *
 * Returns the platform-derived properties as a positional String[]
 * indexed against the Java-side RAW_PLATFORM_PROPERTIES table (see the
 * _<name>_NDX constants of SystemProps$Raw for the exact order).
 *
 * Each array slot carries the VALUE of one property. A slot whose value
 * is not available on this platform is left NULL, and the Java-side
 * merge loop skips any entry whose slot is NULL — that is how a
 * property ends up "defined but unset", which is distinct from "never
 * mentioned at all".
 */
void* __jnative_fn_jdk_internal_util_SystemProps_Raw_platformProperties____Ljava_lang_String_(void) {
    int32_t fixed_length = raw_constant("FIXED_LENGTH");
    if (fixed_length < 0) {
        fprintf(stderr, "jnative: fatal: SystemProps$Raw.FIXED_LENGTH not found\n");
        abort();
    }
    void* arr = make_string_array(fixed_length);
    if (arr == NULL) {
        fprintf(stderr, "jnative: fatal: out of memory in platformProperties\n");
        abort();
    }

    char home_buf[PATH_MAX];
    char user_buf[256];
    char tmpdir_buf[PATH_MAX];
    char cwd_buf[PATH_MAX];
    char lang_buf[32];
    char country_buf[32];

    resolve_locale(lang_buf, sizeof(lang_buf), country_buf, sizeof(country_buf));

    const char* cwd = getcwd(cwd_buf, sizeof(cwd_buf));
    if (cwd == NULL) cwd = "/";

    string_array_set(arr, RAW_NDX(display_language), lang_buf);
    string_array_set(arr, RAW_NDX(display_country),  country_buf);
    string_array_set(arr, RAW_NDX(format_language),  lang_buf);
    string_array_set(arr, RAW_NDX(format_country),   country_buf);

    string_array_set(arr, RAW_NDX(file_encoding),   "UTF-8");
    string_array_set(arr, RAW_NDX(native_encoding), "UTF-8");
    string_array_set(arr, RAW_NDX(file_separator),  "/");
    string_array_set(arr, RAW_NDX(java_io_tmpdir),
                     resolve_tmpdir(tmpdir_buf, sizeof(tmpdir_buf)));
    string_array_set(arr, RAW_NDX(line_separator),  "\n");
    string_array_set(arr, RAW_NDX(os_arch),         resolve_os_arch());
    string_array_set(arr, RAW_NDX(os_name),         "Linux");
    string_array_set(arr, RAW_NDX(os_version),      "");
    string_array_set(arr, RAW_NDX(path_separator),  ":");

    string_array_set(arr, RAW_NDX(stderr_encoding), "UTF-8");
    string_array_set(arr, RAW_NDX(stdin_encoding),  "UTF-8");
    string_array_set(arr, RAW_NDX(stdout_encoding), "UTF-8");
    string_array_set(arr, RAW_NDX(sun_arch_abi),    resolve_arch_abi());
    string_array_set(arr, RAW_NDX(sun_arch_data_model), "64");
    string_array_set(arr, RAW_NDX(sun_cpu_endian),  "little");
    string_array_set(arr, RAW_NDX(sun_io_unicode_encoding), "UnicodeLittle");
    string_array_set(arr, RAW_NDX(sun_jnu_encoding), "UTF-8");
    string_array_set(arr, RAW_NDX(sun_os_patch_level), "unknown");

    string_array_set(arr, RAW_NDX(user_dir),        cwd);
    string_array_set(arr, RAW_NDX(user_home),
                     resolve_user_home(home_buf, sizeof(home_buf)));
    string_array_set(arr, RAW_NDX(user_name),
                     resolve_user_name(user_buf, sizeof(user_buf)));

    return arr;
}

/*
 * =====================================================================
 *  Raw.vmProperties()
 * =====================================================================
 *
 * Returns the properties the VM itself supplies. On a native image the
 * complete, authoritative list of VM-provided properties is already
 * present in the bootstrap Properties table built by
 * __jnative_make_bootstrap_props() below, so there is nothing extra to
 * add here; an empty array is the truthful answer and the Java side's
 * merge loop iterates over it zero times.
 */
void* __jnative_fn_jdk_internal_util_SystemProps_Raw_vmProperties____Ljava_lang_String_(void) {
    return make_empty_string_array();
}

/*
 * =====================================================================
 *  Bootstrap Properties construction
 * =====================================================================
 *
 * Builds the java.util.Properties object that System.initPhase1 and
 * every downstream consumer of System.getProperties() observe. The
 * Properties instance is backed by a ConcurrentHashMap whose internal
 * layout this function writes directly, because the emitter guarantees
 * neither that CHM.<init> is present in the module nor that its
 * constructor can be invoked from C without a vtable lookup — and the
 * field offsets are stable across every JDK this runtime targets (they
 * are computed by LlvmGlobalEmitter.getFieldOffset from the same class
 * files that the module was compiled from).
 *
 * The offset arguments are supplied by LlvmGenerator.generateMain() and
 * come from LlvmGlobalEmitter.getFieldOffset / computeObjectSize, so
 * they cannot disagree with what generated bytecode would have used to
 * address the same fields. A value of zero or negative for any offset
 * means "the emitter did not supply it" and the corresponding fallback
 * constant at the top of this file is used instead.
 */
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

    void* handle = jn_dlsym_default;
    if (!handle) {
        fprintf(stderr, "jnative: fatal: dlopen(NULL) failed\n");
        abort();
    }

    void** node_type_info = (void**)jn_dlsym_default(
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
    void* chm_table = jnative_ref_array_of_class(NULL, table_size,
        "[Ljava/util/concurrent/ConcurrentHashMap$Node;");
    if (!chm_table) abort();

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
        uint32_t _h = jnative_spread(_hc);                                               \
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
    char user_buf[256];
    const char* user = resolve_user_name(user_buf, sizeof(user_buf));
    char userhome_buf[PATH_MAX];
    const char* home = resolve_user_home(userhome_buf, sizeof(userhome_buf));
    char tmpdir_buf[PATH_MAX];
    const char* tmpdir = resolve_tmpdir(tmpdir_buf, sizeof(tmpdir_buf));
    char libpath_buf[PATH_MAX * 4];
    const char* libpath = resolve_library_path(libpath_buf, sizeof(libpath_buf));
    char lang_buf[32];
    char country_buf[32];
    resolve_locale(lang_buf, sizeof(lang_buf), country_buf, sizeof(country_buf));

    JNATIVE_INSERT("java.home", java_home);
    JNATIVE_INSERT("user.home", home);
    JNATIVE_INSERT("user.dir",  cwd);
    JNATIVE_INSERT("user.name", user);
    JNATIVE_INSERT("java.io.tmpdir", tmpdir);
    JNATIVE_INSERT("java.library.path", libpath);
    JNATIVE_INSERT("sun.boot.library.path", libpath);
    JNATIVE_INSERT("user.language", lang_buf);
    JNATIVE_INSERT("user.country",  country_buf);

    JNATIVE_INSERT("java.version",               "21.0.0");
    JNATIVE_INSERT("java.version.date",          "2026-09-19");
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
    JNATIVE_INSERT("jdk.reflect.useNativeAccessorOnly", "true");
    JNATIVE_INSERT("jdk.reflect.useDirectMethodHandle", "methods");

    JNATIVE_INSERT("os.name", "Linux");
    JNATIVE_INSERT("os.arch", resolve_os_arch());
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

    JNATIVE_INSERT("java.class.version",         "65.0");
    JNATIVE_INSERT("sun.nio.MaxDirectMemorySize", "-1");
    JNATIVE_INSERT("sun.nio.PageAlignDirectMemory", "false");

    JNATIVE_INSERT("jdk.serialFilter",           "");
    JNATIVE_INSERT("sun.nio.MaxCachedBufferSize", "");
    JNATIVE_INSERT("jdk.module.path",            "");
    JNATIVE_INSERT("jdk.module.upgrade.path",    "");
    JNATIVE_INSERT("jdk.module.main",            "");
    JNATIVE_INSERT("jdk.module.main.class",      "");

#undef JNATIVE_INSERT

    *(int64_t*)((char*)chm + chm_basecount_off) = (int64_t)entry_count;

    return props;
}