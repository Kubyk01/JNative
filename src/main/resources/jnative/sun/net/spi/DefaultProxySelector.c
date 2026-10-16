#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

/*
 * sun.net.spi.DefaultProxySelector — native entry points.
 *
 * ============================================================================
 * Why this file exists
 * ============================================================================
 *
 * The class declares two native methods:
 *
 *     private static native boolean init();
 *     private synchronized native Proxy[] getSystemProxies(String protocol,
 *                                                          String host);
 *
 * Both are called from reachable methods of the class:
 *
 *   init             — from the class's static initializer, but only when
 *                      the system property java.net.useSystemProxies is
 *                      true.
 *
 *   getSystemProxies — from the PrivilegedAction body inside select(URI),
 *                      but only when hasSystemProxies is true (which is
 *                      itself set from init()'s return value).
 *
 * The LLVM emitter records a call site for each in the generated IR, so
 * without definitions in the linked executable clang rejects the
 * generated sun/net/spi/DefaultProxySelector$3.ll (and its siblings)
 * with
 *
 *     error: use of undefined value
 *         '@__jnative_fn_sun_net_spi_DefaultProxySelector_getSystemProxies__Ljava_lang_String_Ljava_lang_String___Ljava_net_Proxy_'
 *
 * ============================================================================
 * What the reference implementation does
 * ============================================================================
 *
 * On HotSpot, DefaultProxySelector is the default java.net.ProxySelector.
 * Its two natives bridge into a small C library (libnet) that reads the
 * platform's system proxy configuration:
 *
 *   init
 *       probes the platform for a system-wide proxy source. On Linux it
 *       tries to connect to the GNOME gconf/dconf daemon; on macOS it
 *       queries the System Configuration framework; on Windows it reads
 *       the WinINet proxy settings. Returns true iff a source was found
 *       and its C-side state was successfully initialised.
 *
 *   getSystemProxies
 *       given a protocol and a host, queries the platform source for the
 *       proxy list that should be used. Returns a Proxy[] with zero or
 *       more entries, or null if the source has no proxies for the given
 *       pair.
 *
 * ============================================================================
 * Behaviour in this runtime
 * ============================================================================
 *
 * This runtime has no desktop session, no D-Bus connection, no GNOME
 * gconf or dconf daemon, and no platform-specific proxy-discovery
 * library linked in. Even if the user set
 * -Djava.net.useSystemProxies=true, there is no source to probe.
 *
 * The two natives provide the answers that keep the runtime internally
 * consistent:
 *
 *   init             returns false. The Java-side static initializer
 *                    stores this value in hasSystemProxies, so the
 *                    subsequent check inside select(URI)'s
 *                    PrivilegedAction body is skipped and the entire
 *                    system-proxy lookup path is bypassed.
 *
 *   getSystemProxies returns null. The Java-side caller (inside the
 *                    PrivilegedAction body) checks for null and returns
 *                    it up the stack, where select(URI) treats it as
 *                    "no proxy found" and falls through to the
 *                    properties-based path — which is entirely
 *                    Java-side and does work in this runtime. That path
 *                    reads http.proxyHost / https.proxyHost /
 *                    ftp.proxyHost / socksProxyHost and their Port and
 *                    nonProxyHosts companions from NetProperties, all
 *                    of which are backed by ordinary System.getProperty
 *                    calls.
 *
 * These are genuine "no system proxy source" answers, not stubs that
 * abort. The reference implementation itself returns exactly these
 * values on a system with no desktop session (a headless server, a
 * container, a build machine without an X11 connection) — and the
 * Java-side code that consumes them is written and tested against
 * those values. Returning true from init or a non-null Proxy[] from
 * getSystemProxies would fabricate proxy settings that no source ever
 * produced and would silently redirect every outgoing HTTP request
 * through them.
 *
 * ============================================================================
 * Calling convention
 * ============================================================================
 *
 * init is a STATIC native: no receiver argument.
 *
 * getSystemProxies is an INSTANCE native: the receiver is the first
 * argument, followed by the two String parameters.
 *
 * init returns boolean (i1 at the IR level, int32_t at the C ABI
 * level, matching every other boolean native in this runtime).
 *
 * getSystemProxies returns a Java reference array — an i8* in LLVM
 * terms. A NULL return is the "no proxies found" signal the Java-side
 * caller checks against.
 *
 * ============================================================================
 * The mangled names
 * ============================================================================
 *
 * LlvmRuntime.mangleMethod applies
 *     "fn_" + sanitize(class) + "_" + sanitize(method) + "_" + sanitize(desc)
 * where sanitize replaces every character outside [a-zA-Z0-9_] with a
 * single underscore. The emitter prefixes the result with "__jnative_".
 *
 *   class "sun/net/spi/DefaultProxySelector"
 *       -> "sun_net_spi_DefaultProxySelector"
 *
 *   init             desc "()Z"
 *       -> "__jnative_fn_sun_net_spi_DefaultProxySelector_init___Z"
 *
 *   getSystemProxies desc
 *       "(Ljava/lang/String;Ljava/lang/String;)[Ljava/net/Proxy;"
 *       -> "__jnative_fn_sun_net_spi_DefaultProxySelector_getSystemProxies__Ljava_lang_String_Ljava_lang_String___Ljava_net_Proxy_"
 *
 * The getSystemProxies name is the one the linker reported as
 * undefined. Its shape: after the method-name separator `_`, the
 * sanitised `(` contributes `_`, then `Ljava_lang_String_` for the
 * first parameter (`/` and `;` both sanitize to `_`), then
 * `Ljava_lang_String_` for the second, then the three characters `;`,
 * `)`, `[` in a row sanitize to `___`, then `Ljava_net_Proxy_` for the
 * array's element type, then the trailing `;` sanitizes to `_`. That
 * gives the triple underscore the diagnostic shows between the second
 * `String_` and `Ljava_net_Proxy_`.
 *
 * ============================================================================
 * Class path
 * ============================================================================
 *
 * Compiler.getNativeResourcePath("sun/net/spi/DefaultProxySelector")
 * does not strip a leading "java/" — the class is in the sun.*
 * namespace — so it returns
 * "jnative/sun/net/spi/DefaultProxySelector.c". That is the path this
 * file must live at for Compiler.extractSystemNativeSources to find it
 * and compile it into the executable.
 */

/*
 * private static native boolean init();
 *
 * Returns false. See the file header for the full rationale: the value
 * is stored in hasSystemProxies, and false keeps the entire
 * system-proxy lookup path bypassed. That is the same value the
 * reference implementation produces on a system with no desktop
 * session — a headless server, a container, a build machine without an
 * X11 connection — and the Java-side code that consumes it is written
 * and tested against it.
 *
 * STATIC native: no receiver argument.
 */
int32_t __jnative_fn_sun_net_spi_DefaultProxySelector_init___Z(void) {
    return 0;
}

/*
 * private synchronized native Proxy[] getSystemProxies(String protocol,
 *                                                      String host);
 *
 * Returns null. On HotSpot this queries the platform's system proxy
 * source (GNOME gconf/dconf on Linux, System Configuration on macOS,
 * WinINet on Windows). This runtime has no such source, so the
 * truthful answer is "no proxies for this protocol and host"; the
 * Java-side caller checks for null and returns it up the stack, where
 * select(URI) falls through to the properties-based path that reads
 * http.proxyHost, https.proxyHost, ftp.proxyHost and socksProxyHost
 * from NetProperties.
 *
 * The `protocol` and `host` arguments are captured and discarded.
 * There is no platform source to query with them.
 */
void* __jnative_fn_sun_net_spi_DefaultProxySelector_getSystemProxies__Ljava_lang_String_Ljava_lang_String___Ljava_net_Proxy_(
        void* this_selector, void* protocol, void* host)
{
    (void)this_selector;
    (void)protocol;
    (void)host;
    return NULL;
}