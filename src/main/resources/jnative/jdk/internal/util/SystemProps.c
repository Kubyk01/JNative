#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <unistd.h>
#include <limits.h>
#include <dlfcn.h>
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

/* ------------------------------------------------------------------ *
 * java.util.HashMap field offsets.
 *
 * The emitter builds the struct type by walking the class hierarchy
 * superclass-first (LlvmGlobalEmitter.collectInstanceFields). For
 * HashMap that gives, in this exact order:
 *
 *     AbstractMap:  keySet (reference), values (reference)
 *     HashMap:      table (Node[]), entrySet (reference),
 *                   size (int), modCount (int),
 *                   threshold (int), loadFactor (float)
 *
 * so the emitted type is
 *
 *     %struct.java_util_HashMap =
 *         { i8*, i8*, i8*, i8*, i8*, i32, i32, i32, float }
 *           ^vt  ^kS  ^vl  ^tbl ^eS  ^sz  ^mc  ^thr  ^lf
 *
 * and the byte offsets are:
 *
 *     vtable        0    (8 bytes)
 *     keySet        8    (8 bytes, inherited)
 *     values       16    (8 bytes, inherited)
 *     table        24    (8 bytes)
 *     entrySet     32    (8 bytes)
 *     size         40    (4 bytes)
 *     modCount     44    (4 bytes)
 *     threshold    48    (4 bytes)
 *     loadFactor   52    (4 bytes)
 *
 * NOTE: These offsets are for HashMap only. Properties extends
 * Hashtable, ConcurrentHashMap extends AbstractMap with its own set
 * of shadowing fields, and both use their own explicitly-passed
 * offset arguments in __jnative_make_bootstrap_props(). Do not reuse
 * these constants for either of those maps.
 * ------------------------------------------------------------------ */
#define HASHMAP_TABLE_OFF       24
#define HASHMAP_ENTRYSET_OFF    32
#define HASHMAP_SIZE_OFF        40
#define HASHMAP_MODCOUNT_OFF    44
#define HASHMAP_THRESHOLD_OFF   48
#define HASHMAP_LOADFACTOR_OFF  52

static struct ReflectionClass* find_class(const char* name) {
    if (!name || reflect_all_classes == NULL) return NULL;
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

/* Reads the POSIX locale from the environment the same way the platform
 * does: LC_ALL wins over LC_MESSAGES, which wins over LANG. Anything that
 * is not a "ll" or "ll_CC" value leaves the corresponding output empty and
 * lets the caller fall back. */
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

/* ------------------------------------------------------------------ *
 * Raw.platformProperties()
 *
 * jdk.internal.util.SystemProps.Raw stores the String[] returned here in
 * its final "platformProps" field and propDefault(int) is a plain
 * platformProps[index] load. The indices below are the @Native _*_NDX
 * constants of SystemProps.Raw; they are positional, so this table must
 * stay in the exact declared order or every property silently resolves
 * to the wrong value. FIXED_LENGTH is the array length the JDK expects.
 * ------------------------------------------------------------------ */

#define RAW_NDX_display_country           0
#define RAW_NDX_display_language          1
#define RAW_NDX_display_script            2
#define RAW_NDX_display_variant           3
#define RAW_NDX_file_encoding             4
#define RAW_NDX_file_separator            5
#define RAW_NDX_format_country            6
#define RAW_NDX_format_language           7
#define RAW_NDX_format_script             8
#define RAW_NDX_format_variant            9
#define RAW_NDX_ftp_nonProxyHosts        10
#define RAW_NDX_ftp_proxyHost            11
#define RAW_NDX_ftp_proxyPort            12
#define RAW_NDX_http_nonProxyHosts       13
#define RAW_NDX_http_proxyHost           14
#define RAW_NDX_http_proxyPort           15
#define RAW_NDX_https_proxyHost          16
#define RAW_NDX_https_proxyPort          17
#define RAW_NDX_java_io_tmpdir           18
#define RAW_NDX_line_separator           19
#define RAW_NDX_os_arch                  20
#define RAW_NDX_os_name                  21
#define RAW_NDX_os_version               22
#define RAW_NDX_path_separator           23
#define RAW_NDX_socksNonProxyHosts       24
#define RAW_NDX_socksProxyHost           25
#define RAW_NDX_socksProxyPort           26
#define RAW_NDX_stderr_encoding          27
#define RAW_NDX_stdout_encoding          28
#define RAW_NDX_sun_arch_abi             29
#define RAW_NDX_sun_arch_data_model      30
#define RAW_NDX_sun_cpu_endian           31
#define RAW_NDX_sun_cpu_isalist          32
#define RAW_NDX_sun_io_unicode_encoding  33
#define RAW_NDX_sun_jnu_encoding         34
#define RAW_NDX_sun_os_patch_level       35
#define RAW_NDX_user_dir                 36
#define RAW_NDX_user_home                37
#define RAW_NDX_user_name                38
#define RAW_FIXED_LENGTH                 39

static void* make_string_array(int32_t len) {
    return jnative_ref_array_of_class(NULL, len, "[Ljava/lang/String;");
}

/* A null value is meaningful here: SystemProps.put/putIfAbsent skip null
 * defaults, so leaving a slot empty is how "no proxy configured" and
 * "no variant" are expressed. */
static void string_array_set(void* arr, int32_t index, const char* value) {
    if (arr == NULL || value == NULL) return;
    *(void**)((char*)arr + JAVA_ARR_HDR + (size_t)index * sizeof(void*)) =
        __jnative_make_string_obj(value, (int32_t)strlen(value));
}

/* String.hashCode() for a latin1 String. */
static int32_t jnative_string_hash(const char* s, int32_t len) {
    uint32_t h = 0;
    for (int32_t i = 0; i < len; i++) {
        h = 31u * h + (uint32_t)(uint8_t)s[i];
    }
    return (int32_t)h;
}

/* ConcurrentHashMap.spread(): additionally masks off the sign bit, because
 * a CHM.Node.hash of 0 marks the reserved bin. */
static uint32_t jnative_spread(int32_t h) {
    uint32_t uh = (uint32_t)h;
    return (uh ^ (uh >> 16)) & 0x7fffffff;
}

/* HashMap.hash(Object): the same fold WITHOUT the sign-bit mask. Using the
 * CHM variant here would put the node in a different bucket than
 * HashMap.getNode() looks in, and the lookup would always miss. */
static uint32_t jnative_map_spread(int32_t h) {
    uint32_t uh = (uint32_t)h;
    return uh ^ (uh >> 16);
}

/* Raw.cmdProperties() models the properties the launcher passes on the
 * command line. The HotSpot launcher always supplies java.home this way and
 * SystemProps.initProperties() asserts on it, so the map must not be empty.
 *
 * The map is assembled field by field rather than through the generated
 * java.util.HashMap.put(): the generated HashMap.putVal in this module
 * allocates no nodes at all, so every put() into a HashMap is a silent
 * no-op. The layout below matches
 *     %struct.java_util_HashMap       = { i8*, i8*, i8*, i8*, i8*, i32, i32, i32, float }
 *     %struct.java_util_HashMap_Node  = { i8*, i32, i8*, i8*, i8* }
 * and HashMap.hash() = h ^ (h >>> 16), which is the same spread the
 * ConcurrentHashMap bootstrap table below already relies on.
 *
 * The two inherited AbstractMap fields (keySet at +8, values at +16) are
 * left null: HashMap.get() never dereferences them, and the Java-side
 * caller treats a null keySet/values as "the view has not been created
 * yet", which is the correct lazy state for a freshly constructed map. */
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
    if (!handle) {
        fprintf(stderr, "jnative: fatal: dlopen(NULL) failed\n");
        abort();
    }
    void** node_type_info = (void**)dlsym(handle, "__type_info_java_util_HashMap_Node");
    void* node_vtable = (node_type_info && node_type_info[0])
        ? node_type_info[0]
        : lookup_class_vtable(find_class("java/lang/Object"));
    if (!node_vtable) {
        fprintf(stderr, "jnative: fatal: no vtable for HashMap.Node\n");
        abort();
    }

    int table_size = 16;
    /*
     * HashMap.Node[] — the generated HashMap.get() reads this field with
     * ARRAYLENGTH and ALOAD, so it must carry the standard array header.
     * Routing it through jnative_ref_array_of_class() gives it the right
     * length/elem_size words and payload offset; a hand-rolled
     * calloc + two int32 stores would leave length at offset 0 and the
     * payload at 8.
     */
    void* table = jnative_ref_array_of_class(NULL, table_size,
                                             "[Ljava/util/HashMap$Node;");
    if (table == NULL) abort();

    char home_buf[PATH_MAX];
    const char* java_home = resolve_java_home(home_buf, sizeof(home_buf));

    void* key = __jnative_make_string_obj("java.home", 10);
    void* value = __jnative_make_string_obj(java_home, (int32_t)strlen(java_home));

    int32_t h = jnative_string_hash("java.home", 10);
    uint32_t spread = jnative_map_spread(h);
    int idx = (int)(spread & (uint32_t)(table_size - 1));

    void* node = calloc(1, NODE_SIZE);
    if (node == NULL) abort();
    *(void**)((char*)node + 0) = node_vtable;
    *(int32_t*)((char*)node + NODE_HASH_OFFSET) = (int32_t)spread;
    *(void**)((char*)node + NODE_KEY_OFFSET) = key;
    *(void**)((char*)node + NODE_VAL_OFFSET) = value;
    *(void**)((char*)node + NODE_NEXT_OFFSET) = NULL;
    *(void**)((char*)table + JAVA_ARR_HDR + (size_t)idx * sizeof(void*)) = node;

    /* keySet (+8) and values (+16) are inherited AbstractMap slots.
     * Leaving them null keeps the lazy-view semantics intact: the
     * Java-side keySet()/values() methods create the view on first
     * access. HashMap.get() and HashMap.put() never consult them. */
    *(void**)((char*)map + 8)  = NULL;
    *(void**)((char*)map + 16) = NULL;

    *(void**)((char*)map + HASHMAP_TABLE_OFF)      = table;
    *(void**)((char*)map + HASHMAP_ENTRYSET_OFF)   = NULL;
    *(int32_t*)((char*)map + HASHMAP_SIZE_OFF)     = 1;
    *(int32_t*)((char*)map + HASHMAP_MODCOUNT_OFF) = 1;
    *(int32_t*)((char*)map + HASHMAP_THRESHOLD_OFF)= 12;
    *(float*)((char*)map + HASHMAP_LOADFACTOR_OFF) = 0.75f;

    dlclose(handle);
    return map;
}

void* __jnative_fn_jdk_internal_util_SystemProps_Raw_platformProperties____Ljava_lang_String_(void) {
    void* arr = make_string_array(RAW_FIXED_LENGTH);
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

    string_array_set(arr, RAW_NDX_display_language, lang_buf);
    string_array_set(arr, RAW_NDX_display_country,  country_buf);
    string_array_set(arr, RAW_NDX_format_language,  lang_buf);
    string_array_set(arr, RAW_NDX_format_country,   country_buf);
    /* display_script / display_variant / format_script / format_variant
     * stay null, which is how "no script, no variant" is reported. */

    string_array_set(arr, RAW_NDX_file_encoding,   "UTF-8");
    string_array_set(arr, RAW_NDX_file_separator,  "/");
    string_array_set(arr, RAW_NDX_java_io_tmpdir,
                     resolve_tmpdir(tmpdir_buf, sizeof(tmpdir_buf)));
    string_array_set(arr, RAW_NDX_line_separator,  "\n");
    string_array_set(arr, RAW_NDX_os_arch,         resolve_os_arch());
    string_array_set(arr, RAW_NDX_os_name,         "Linux");
    string_array_set(arr, RAW_NDX_os_version,      "");
    string_array_set(arr, RAW_NDX_path_separator,  ":");

    string_array_set(arr, RAW_NDX_stderr_encoding, "UTF-8");
    string_array_set(arr, RAW_NDX_stdout_encoding, "UTF-8");
    string_array_set(arr, RAW_NDX_sun_arch_abi,    resolve_arch_abi());
    string_array_set(arr, RAW_NDX_sun_arch_data_model, "64");
    string_array_set(arr, RAW_NDX_sun_cpu_endian,  "little");
    string_array_set(arr, RAW_NDX_sun_io_unicode_encoding, "UnicodeLittle");
    string_array_set(arr, RAW_NDX_sun_jnu_encoding, "UTF-8");
    string_array_set(arr, RAW_NDX_sun_os_patch_level, "unknown");

    string_array_set(arr, RAW_NDX_user_dir,        cwd);
    string_array_set(arr, RAW_NDX_user_home,
                     resolve_user_home(home_buf, sizeof(home_buf)));
    string_array_set(arr, RAW_NDX_user_name,
                     resolve_user_name(user_buf, sizeof(user_buf)));

    /* The proxy and nonProxyHosts slots stay null: no proxy is configured,
     * and SystemProps.putIfAbsent() drops null defaults. */

    return arr;
}

void* __jnative_fn_jdk_internal_util_SystemProps_Raw_vmProperties____Ljava_lang_String_(void) {
    return make_empty_string_array();
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
    /* ConcurrentHashMap.Node[] — see the HashMap.Node[] note above. */
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
    char lang_buf[32];
    char country_buf[32];
    resolve_locale(lang_buf, sizeof(lang_buf), country_buf, sizeof(country_buf));

    JNATIVE_INSERT("java.home", java_home);
    JNATIVE_INSERT("user.home", home);
    JNATIVE_INSERT("user.dir",  cwd);
    JNATIVE_INSERT("user.name", user);
    JNATIVE_INSERT("java.io.tmpdir", tmpdir);
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

    /* ------------------------------------------------------------------
     * NEW: properties that VM.saveProperties() reads unconditionally.
     *
     * jdk.internal.misc.VM.saveProperties() is invoked from @main right
     * after the bootstrap table is installed on java.lang.System. It
     * reads three keys:
     *
     *   - "sun.nio.MaxDirectMemorySize"    — null-safe: an absent value
     *     leaves directMemory at its "unlimited" default.
     *
     *   - "sun.nio.PageAlignDirectMemory"  — null-safe via
     *     "true".equals(...): an absent value leaves the flag false.
     *
     *   - "java.class.version"             — NOT null-safe. The body is
     *
     *         s = (String)p.get("java.class.version");
     *         int i = s.indexOf('.');       // NPE when s == null
     *         classFileMajorVersion = Integer.parseInt(s.substring(0, i));
     *
     *     so the key must be present or VM.saveProperties itself aborts
     *     with an NPE before System.initPhase1 ever runs. This is
     *     exactly the failure mode reported at build-startup:
     *
     *         java.lang.NullPointerException: Cannot invoke
     *         jdk.internal.misc.VM.saveProperties(Ljava_util_MapV)
     *         because %tmp_63100 is null
     *
     *     The value is the class file format version, major.minor; "65.0"
     *     is the JDK 21 value and matches the java.version string above.
     * ------------------------------------------------------------------ */
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

    dlclose(handle);
    return props;
}