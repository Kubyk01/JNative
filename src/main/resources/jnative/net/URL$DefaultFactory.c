/*
 * java.net.URL$DefaultFactory — native override for the reflective URL
 * stream handler factory.
 *
 * ============================================================================
 * The failure this file exists to close
 * ============================================================================
 *
 * The JDK resolves a non-standard URL protocol handler by building the
 * handler's class name in a running string concatenation and then calling
 * Class.forName on the result:
 *
 *     // java.base/java/net/URL.java, DefaultFactory:
 *     private static final String PREFIX = "sun.net.www.protocol.";
 *
 *     public URLStreamHandler createURLStreamHandler(String protocol) {
 *         switch (protocol) {
 *             case "file": return new sun.net.www.protocol.file.Handler();
 *             case "jar":  return new sun.net.www.protocol.jar.Handler();
 *             case "jrt":  return new sun.net.www.protocol.jrt.Handler();
 *         }
 *         String name = PREFIX + protocol + ".Handler";
 *         try {
 *             Object o = Class.forName(name)
 *                              .getDeclaredConstructor()
 *                              .newInstance();
 *             return (URLStreamHandler)o;
 *         } catch (Exception e) {
 *             // For compatibility, all Exceptions are ignored.
 *         }
 *         return null;
 *     }
 *
 * In JNative the class name is not a compile-time constant, so the
 * reachability analysis (which only registers constant-string targets
 * of Class.forName — see MethodBytecodeVisitor.handleReflectiveCall)
 * never learns about sun.net.www.protocol.http.Handler and its siblings.
 * Those classes are therefore absent from the class map, from the
 * reflection registry, and from @refctors_* tables.
 *
 * At run time, Class.forName returns null, DefaultFactory returns null,
 * and java.net.URL.<init> throws
 *
 *     java.net.MalformedURLException: unknown protocol: http
 *
 * The concrete crash that this file eliminates:
 *
 *     javax.crypto.JceSecurity.<clinit>:
 *         private static final URL NULL_URL;
 *         static {
 *             try {
 *                 var _unused =
 *                     NULL_URL = new URL("http://null.oracle.com/");
 *             } catch (Exception e) {
 *                 throw new RuntimeException(e);
 *             }
 *         }
 *
 * NULL_URL is used purely as a comparison sentinel by
 * JceSecurity.getCodeBase; the handler's other methods are never
 * invoked. But the URL constructor still calls
 * handler.parseURL(this, spec, start, limit), and if the handler is
 * null the constructor throws. The MalformedURLException is caught by
 * JceSecurity's own catch (Exception) and rethrown as
 * RuntimeException, which aborts the process before any user code has
 * run — even for programs that never touch cryptography. The chain
 * that drags JceSecurity into the image is
 *
 *     java.security.Signature$CipherAdapter.<init>
 *         -> javax.crypto.Cipher.<clinit>
 *             -> javax.crypto.JceSecurityManager.<clinit>
 *                 -> javax.crypto.JceSecurity.<clinit>
 *
 * ============================================================================
 * How the override solves it
 * ============================================================================
 *
 * The override replaces the body of DefaultFactory.createURLStreamHandler
 * entirely. It does not use Class.forName; it does not need the concrete
 * handler classes to be in the image. Instead it:
 *
 *   1. Reads the requested protocol name from the String argument.
 *
 *   2. Tries to instantiate the canonical JDK handler class
 *      "sun/net/www/protocol/<protocol>/Handler" if and only if both:
 *         (a) the class is present in the runtime's reflect_all_classes[]
 *             table (i.e. it made it into the emitted image), and
 *         (b) its no-argument constructor's mangled symbol resolves
 *             through dlsym (i.e. the constructor's body was translated
 *             into the module).
 *      Condition (b) is the only honest way to know at run time whether
 *      the class has a usable body: a class can be present in
 *      reflect_all_classes[] with no IR body at all if the reachability
 *      analysis never pulled its constructor in.
 *
 *   3. If either (a) or (b) fails, falls back to a freshly allocated
 *      instance of java.net.URLStreamHandler itself. The base class's
 *      parseURL performs generic RFC 2396 parsing, which is exactly what
 *      is needed for URLs that are only constructed and compared — the
 *      use case that motivates this override in the first place.
 *
 * The base-class fallback is not a stub. It is a real URLStreamHandler
 * with a real implementation of parseURL, hashCode, equals, sameFile,
 * toExternalForm, getDefaultPort and setURL. The only method it does not
 * provide is openConnection, which is abstract on URLStreamHandler; any
 * attempt to open a connection on a URL whose handler went through the
 * fallback path will land on a null vtable slot. That is the correct
 * behaviour for an image that has no HTTP client stack compiled into it:
 * constructing a URL is possible, opening it is not.
 *
 * For the sentinel use case in JceSecurity, openConnection is never
 * reached. For real HTTP client code, the correct fix is to bring the
 * handler classes into the image; once they are present, condition (a)
 * and (b) above will succeed and the override will return the real
 * handler transparently.
 *
 * ============================================================================
 * Why the file path contains a dollar sign
 * ============================================================================
 *
 * NativeOverrideScanner derives the target class name from the C file's
 * path when the function's own suffix does not unambiguously encode the
 * class (see findClassBoundary in NativeOverrideScanner: the first
 * underscore-separated segment whose first character is uppercase is
 * taken as the class boundary). For "createURLStreamHandler" there is
 * no underscore at all, so findClassBoundary returns -1 and the class
 * is taken verbatim from the file path.
 *
 * The file path must therefore name the exact class:
 *
 *     jnative/net/URL$DefaultFactory.c
 *         -> fileClass = "java/net/URL$DefaultFactory"
 *
 * Naming the file anything else (say, jnative/net/URL.c) would make the
 * override register itself under the wrong class and never match the
 * real java.net.URL$DefaultFactory.createURLStreamHandler method.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>

#include "jnative_runtime.h"

/* ============================================================================
 *  Helpers
 * ========================================================================== */

/*
 * Look up a symbol in the running process image. Used to locate the
 * mangled no-argument constructor of a concrete URL-stream handler class
 * without forcing a compile-time dependency on any one JDK version's
 * method set.
 *
 * Returns NULL when the symbol is absent from the image. A NULL return
 * is not an error: it means the class's constructor was never translated
 * into IR, so the class cannot be used at run time and the caller must
 * fall back to the base URLStreamHandler.
 */
static void* lookup_symbol(const char* name) {
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (handle == NULL) return NULL;
    void* sym = dlsym(handle, name);
    dlclose(handle);
    return sym;
}

/*
 * Build the mangled symbol of a class's no-argument constructor, using
 * exactly the same substitution rules as LlvmRuntime.mangleMethod on the
 * Java side:
 *
 *     "fn_" + sanitize(className) + "_" + sanitize("<init>") + "_"
 *         + sanitize("()V")
 *
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. The three underscore-separated components are the
 * class, the method, and the descriptor.
 *
 * Concretely, for class "java/net/URLStreamHandler" the result is the
 * 38-character string
 *
 *     fn_java_net_URLStreamHandler__init____V
 *
 * Every step of the construction is documented inline because the
 * underscore count is easy to get wrong and a mismatch would silently
 * prevent the lookup from ever succeeding.
 */
static void build_noarg_ctor_symbol(const char* class_name,
                                    char* out, size_t out_size)
{
    size_t pos = 0;
    const char* p;

#define PUT_C(c) do { if (pos + 1 < out_size) out[pos++] = (char)(c); } while (0)
#define PUT_S(s) do { \
        for (const char* _p = (s); *_p && pos + 1 < out_size; _p++) { \
            char _c = *_p; \
            if ((_c >= 'a' && _c <= 'z') || (_c >= 'A' && _c <= 'Z') \
                || (_c >= '0' && _c <= '9') || _c == '_') { \
                PUT_C(_c); \
            } else { \
                PUT_C('_'); \
            } \
        } \
    } while (0)

    /* Prefix "fn_" */
    PUT_S("fn_");

    /* Sanitised class name */
    PUT_S(class_name);

    /* Separator between class and method */
    PUT_C('_');

    /* Sanitised method name "<init>" -> "_init_" */
    PUT_S("<init>");

    /* Separator between method and descriptor */
    PUT_C('_');

    /* Sanitised descriptor "()V" -> "__V" */
    PUT_S("()V");

    if (pos < out_size) {
        out[pos] = '\0';
    } else if (out_size > 0) {
        out[out_size - 1] = '\0';
    }

#undef PUT_C
#undef PUT_S
}

/* ============================================================================
 *  The override
 * ========================================================================== */

/*
 * Replacement for java.net.URL$DefaultFactory.createURLStreamHandler.
 *
 * Calling convention: this is an instance method of a private static
 * inner class of java.net.URL, so the C function takes the receiver as
 * its first argument, followed by the Java String argument.
 *
 *     receiver    : the URL$DefaultFactory instance (unused, but must be
 *                   accepted to match the emitted function signature)
 *     protocol_str: a java.lang.String carrying the protocol name
 *
 * Returns a non-null java.net.URLStreamHandler, or NULL when the
 * protocol string is invalid. The default factory's own Java code
 * treats a NULL return as "unknown protocol" and lets URL.<init>
 * raise MalformedURLException; that branch is only reached for inputs
 * that cannot be turned into a valid class name.
 */
void* __jnative_override_createURLStreamHandler(
        void* this_factory, void* protocol_str)
{
    (void)this_factory;

    if (protocol_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    int32_t protocol_len = 0;
    const char* protocol =
        __jnative_read_string_bytes(protocol_str, &protocol_len);
    if (protocol == NULL || protocol_len <= 0) {
        return NULL;
    }

    /*
     * Guard the class-name buffer size. Every legal URL protocol name
     * is a short ASCII identifier; a string longer than this cannot
     * correspond to any Handler class that the JDK would ever emit,
     * and truncating it silently would only produce a confusing
     * diagnostic. Returning NULL here is the same behaviour the
     * original DefaultFactory exhibits for an unknown protocol.
     */
    if (protocol_len > 64) {
        return NULL;
    }

    /*
     * Try the concrete JDK handler first. The check is deliberately
     * two-staged:
     *
     *   1. jnative_class_by_name() answers "is this class present in
     *      the runtime's reflection registry?". It returns non-NULL
     *      only if the class was emitted into @reflect_all_classes[],
     *      which means it is not an external stub and its ClassNode has
     *      bytecode. That in turn means its struct type, vtable and
     *      reflection entry all exist in the compiled image.
     *
     *   2. lookup_symbol() answers "was this class's no-argument
     *      constructor translated into IR?". dlsym returns non-NULL
     *      only for symbols that have a definition in the linked
     *      executable, so a class that is present in the registry but
     *      whose constructor was never translated correctly falls
     *      through to the base-class fallback below.
     *
     * Both conditions must hold before the concrete handler is used.
     * Skipping either would produce an object whose vtable slot for
     * <init> points at a symbol the linker cannot resolve, or whose
     * struct type and offset table were never emitted.
     */
    {
        char class_name[128];
        int n = snprintf(class_name, sizeof(class_name),
                         "sun/net/www/protocol/%.*s/Handler",
                         (int)protocol_len, protocol);

        if (n > 0 && (size_t)n < sizeof(class_name)) {
            ReflectionClass* handler_cls = jnative_class_by_name(class_name);
            if (handler_cls != NULL) {
                char ctor_symbol[512];
                build_noarg_ctor_symbol(class_name,
                                        ctor_symbol, sizeof(ctor_symbol));

                typedef void (*noarg_ctor_t)(void*);
                noarg_ctor_t ctor =
                    (noarg_ctor_t)lookup_symbol(ctor_symbol);

                if (ctor != NULL) {
                    void* instance = jnative_alloc_object(handler_cls);
                    if (instance != NULL) {
                        ctor(instance);
                        return instance;
                    }
                    /*
                     * jnative_alloc_object returns NULL only when
                     * calloc fails or the vtable cannot be resolved.
                     * Both are hard failures that the caller cannot
                     * recover from, so fall through and let the
                     * base-class path handle it. If even that fails,
                     * the generic OOM throw below fires.
                     */
                }
            }
        }
    }

    /*
     * Fallback: allocate a plain java.net.URLStreamHandler instance.
     *
     * The base class is concrete enough to be instantiated by
     * jnative_alloc_object, which does not invoke any constructor — it
     * just callocs object_size bytes and writes the class's vtable
     * pointer into word 0. The base class's no-argument constructor is
     * empty in every JDK version this runtime supports, so skipping it
     * is behaviourally equivalent to calling it.
     *
     * The base class's parseURL does generic RFC 2396 parsing, which
     * is exactly the implementation the URL constructor needs. The
     * base class's equals / hashCode / sameFile / toExternalForm /
     * getDefaultPort are also concrete and work on any URL, so a URL
     * whose handler was produced by this fallback behaves correctly
     * for every operation except openConnection.
     */
    ReflectionClass* base_cls =
        jnative_class_by_name("java/net/URLStreamHandler");
    if (base_cls == NULL) {
        /*
         * The base class itself is missing from the image. This is a
         * genuine build defect: the URL constructor at the very least
         * reaches URLStreamHandler's vtable when it calls
         * handler.parseURL, so the class should always be present.
         * Reporting it through the generic throw helper produces a
         * diagnostic that names the missing class.
         */
        __jnative_throw_exception(NULL);
        return NULL;
    }

    void* instance = jnative_alloc_object(base_cls);
    if (instance == NULL) {
        __jnative_throw_out_of_memory_error_ctx(
            "URL$DefaultFactory.createURLStreamHandler");
        return NULL;
    }

    return instance;
}