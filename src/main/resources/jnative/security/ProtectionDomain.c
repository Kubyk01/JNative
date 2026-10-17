/*
 * java.lang.Class.getProtectionDomain0 /
 * java.security.AccessController.getProtectionDomain
 *
 * The Spring Boot loader asks Class.getProtectionDomain(), then opens
 * the CodeSource's location as a JAR file. In a native image there is
 * no JAR on disk: the input archive was consumed at build time and its
 * classes are already inside the executable. This file closes that gap
 * by
 *
 *   1. embedding a stripped copy of the input JAR as a runtime resource
 *      (see ResourceEmbedder.collectInputJar on the Java side),
 *   2. extracting those bytes to a temp file exactly once per process,
 *   3. building a ProtectionDomain whose CodeSource URL points at that
 *      temp file, using the same field offsets the LLVM emitter used to
 *      lay out URL, CodeSource and ProtectionDomain.
 *
 * The extracted JAR is removed at process exit through an atexit hook,
 * so no temporary file survives a normal termination.
 *
 * If no input JAR was embedded (non-JAR input, or embedding failed at
 * build time), the URL falls back to the running executable. That is a
 * real File on disk, so Archive.create(ProtectionDomain) proceeds past
 * its existence check and fails later with a diagnostic that names the
 * actual problem ("not a valid JAR") rather than the opaque "cannot
 * determine code source archive".
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dlfcn.h>
#include <pthread.h>
#include <limits.h>

#include "jnative_runtime.h"

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

/* Must match ResourceEmbedder.EMBEDDED_INPUT_JAR_KEY. */
static const char EMBEDDED_INPUT_JAR_KEY[] = "__jnative__/app.jar";

static void*          cached_protection_domain = NULL;
static pthread_once_t init_once = PTHREAD_ONCE_INIT;

static char extracted_jar_path[PATH_MAX];
static int  extracted_jar_active = 0;

static void remove_extracted_jar_at_exit(void) {
    if (extracted_jar_active && extracted_jar_path[0] != '\0') {
        unlink(extracted_jar_path);
        extracted_jar_path[0] = '\0';
        extracted_jar_active = 0;
    }
}

static int extract_embedded_input_jar(void) {
    const JNativeResourceEntry* res =
        jnative_find_resource(EMBEDDED_INPUT_JAR_KEY,
                              (int32_t)(sizeof(EMBEDDED_INPUT_JAR_KEY) - 1));
    if (res == NULL || res->data == NULL || res->size <= 0) {
        return 0;
    }

    const char* tmpdir = getenv("TMPDIR");
    if (tmpdir == NULL || tmpdir[0] == '\0') tmpdir = "/tmp";

    char tmpl[PATH_MAX];
    int n = snprintf(tmpl, sizeof(tmpl), "%s/jnative-app-XXXXXX.jar", tmpdir);
    if (n <= 0 || (size_t)n >= sizeof(tmpl)) return 0;

    int fd = mkstemps(tmpl, 4);   /* suffix length = strlen(".jar") */
    if (fd < 0) return 0;

    const uint8_t* p = res->data;
    size_t remaining = (size_t)res->size;
    while (remaining > 0) {
        ssize_t w = write(fd, p, remaining);
        if (w < 0) {
            if (errno == EINTR) continue;
            int saved = errno;
            close(fd);
            unlink(tmpl);
            errno = saved;
            return 0;
        }
        if (w == 0) { close(fd); unlink(tmpl); return 0; }
        p += (size_t)w;
        remaining -= (size_t)w;
    }
    close(fd);

    size_t len = strlen(tmpl);
    if (len + 1 > sizeof(extracted_jar_path)) { unlink(tmpl); return 0; }
    memcpy(extracted_jar_path, tmpl, len + 1);
    extracted_jar_active = 1;
    atexit(remove_extracted_jar_at_exit);
    return 1;
}

/*
 * Find the byte offset of an instance field, walking the superclass
 * chain. The offsets come from the same LlvmGlobalEmitter.getFieldOffset()
 * that produced every direct GET_FIELD / PUT_FIELD in the generated
 * code, so any write performed here agrees byte-for-byte with what
 * Java-level code observes on the next read. Returns -1 when the field
 * is absent, which the callers treat as "skip this write".
 */
static int find_instance_field_offset(ReflectionClass* cls, const char* name) {
    ReflectionClass* cur = cls;
    while (cur != NULL) {
        if (cur->fields != NULL) {
            ReflectionField** fp = cur->fields;
            while (*fp != NULL) {
                ReflectionField* f = *fp;
                if (f->name != NULL
                    && strcmp((const char*)f->name, name) == 0) {
                    return f->offset;
                }
                fp++;
            }
        }
        cur = cur->superclass;
    }
    return -1;
}

/*
 * A URLStreamHandler instance suitable for the "file" protocol. Prefers
 * the concrete sun.net.www.protocol.file.Handler when it is in the image
 * and its no-argument constructor resolves; otherwise allocates the
 * abstract base class as a plain object. The base class's parseURL,
 * getDefaultPort, equals, hashCode, sameFile and toExternalForm are all
 * concrete; only openConnection is abstract, and no caller reached from
 * the code source path ever invokes it.
 */
static void* build_file_url_handler(void) {
    ReflectionClass* handler_cls =
        jnative_class_by_name("sun/net/www/protocol/file/Handler");
    if (handler_cls != NULL) {
        void* h = jnative_alloc_object(handler_cls);
        if (h != NULL) {
            typedef void (*ctor_t)(void*);
            ctor_t ctor = (ctor_t)dlsym(RTLD_DEFAULT,
                "fn_sun_net_www_protocol_file_Handler__init____V");
            if (ctor != NULL) ctor(h);
            return h;
        }
    }

    ReflectionClass* base_cls =
        jnative_class_by_name("java/net/URLStreamHandler");
    if (base_cls == NULL) return NULL;
    return jnative_alloc_object(base_cls);
}

/*
 * Build a java.net.URL("file", null, -1, path, handler).
 *
 * The five-argument constructor of java.net.URL stores its arguments
 * verbatim: it does not parse, does not consult URLStreamHandlerFactory,
 * and does not go through the java.protocol.handler.pkgs lookup. On a
 * JDK whose five-argument constructor is in the image this is the
 * cleanest path; when it is not, the same object is produced by writing
 * the five relevant fields directly through their emitter-computed
 * offsets.
 */
static void* build_file_url(const char* path) {
    ReflectionClass* url_cls = jnative_class_by_name("java/net/URL");
    if (url_cls == NULL) return NULL;

    void* handler     = build_file_url_handler();
    void* protocol_str = jnative_string("file");
    void* file_str     = jnative_string(path);

    typedef void (*url_ctor_t)(void*, void*, void*, int32_t, void*, void*);
    url_ctor_t ctor = (url_ctor_t)dlsym(RTLD_DEFAULT,
        "fn_java_net_URL__init___Ljava_lang_String_Ljava_lang_String_I"
        "Ljava_lang_String_Ljava_net_URLStreamHandler__V");

    void* url = jnative_alloc_object(url_cls);
    if (url == NULL) return NULL;

    if (ctor != NULL && handler != NULL) {
        ctor(url, protocol_str, NULL, -1, file_str, handler);
        return url;
    }

    int off_protocol = find_instance_field_offset(url_cls, "protocol");
    int off_host     = find_instance_field_offset(url_cls, "host");
    int off_file     = find_instance_field_offset(url_cls, "file");
    int off_port     = find_instance_field_offset(url_cls, "port");
    int off_handler  = find_instance_field_offset(url_cls, "handler");

    if (off_protocol >= 0) *(void**)((char*)url + off_protocol) = protocol_str;
    if (off_host     >= 0) *(void**)((char*)url + off_host)     = NULL;
    if (off_file     >= 0) *(void**)((char*)url + off_file)     = file_str;
    if (off_port     >= 0) *(int32_t*)((char*)url + off_port)   = -1;
    if (off_handler  >= 0) *(void**)((char*)url + off_handler)  = handler;
    return url;
}

static void* build_code_source(void* url) {
    ReflectionClass* cs_cls = jnative_class_by_name("java/security/CodeSource");
    if (cs_cls == NULL) return NULL;

    void* cs = jnative_alloc_object(cs_cls);
    if (cs == NULL) return NULL;

    int off_location = find_instance_field_offset(cs_cls, "location");
    if (off_location >= 0) *(void**)((char*)cs + off_location) = url;
    return cs;
}

/*
 * The certificate and signer arrays are left NULL — the documented
 * "unsigned code" state, and the only truthful answer for a locally
 * built artefact. hasAllPerm and staticPermissions are set so that
 * ProtectionDomain.implies short-circuits to true without consulting a
 * permissions collection: with no SecurityManager in the image, nothing
 * enforces permissions at run time, and a fabricated non-empty
 * collection would only exist to make implies() return true anyway.
 */
static void* build_protection_domain(void* code_source) {
    ReflectionClass* pd_cls =
        jnative_class_by_name("java/security/ProtectionDomain");
    if (pd_cls == NULL) return NULL;

    void* pd = jnative_alloc_object(pd_cls);
    if (pd == NULL) return NULL;

    int off_codesource  = find_instance_field_offset(pd_cls, "codesource");
    int off_classloader = find_instance_field_offset(pd_cls, "classloader");
    int off_principals  = find_instance_field_offset(pd_cls, "principals");
    int off_perms       = find_instance_field_offset(pd_cls, "permissions");
    int off_all         = find_instance_field_offset(pd_cls, "hasAllPerm");
    int off_static      = find_instance_field_offset(pd_cls, "staticPermissions");

    if (off_codesource  >= 0) *(void**)((char*)pd + off_codesource)  = code_source;
    if (off_classloader >= 0) *(void**)((char*)pd + off_classloader) = NULL;
    if (off_principals  >= 0) *(void**)((char*)pd + off_principals)  =
        jnative_ref_array(NULL, 0);
    if (off_perms       >= 0) *(void**)((char*)pd + off_perms)       = NULL;
    if (off_all         >= 0) *(uint8_t*)((char*)pd + off_all)       = 1;
    if (off_static      >= 0) *(uint8_t*)((char*)pd + off_static)    = 1;
    return pd;
}

static void init_protection_domain(void) {
    int have_jar = extract_embedded_input_jar();

    char exe_path[PATH_MAX];
    const char* target = NULL;

    if (have_jar) {
        target = extracted_jar_path;
    } else {
        ssize_t n = readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
        if (n > 0) {
            exe_path[n] = '\0';
            target = exe_path;
        }
    }
    if (target == NULL) return;

    void* url = build_file_url(target);
    if (url == NULL) return;
    void* cs = build_code_source(url);
    if (cs == NULL) return;
    cached_protection_domain = build_protection_domain(cs);
}

void* __jnative_get_class_protection_domain(void) {
    pthread_once(&init_once, init_protection_domain);
    return cached_protection_domain;
}
