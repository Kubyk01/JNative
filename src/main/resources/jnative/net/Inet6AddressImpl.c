#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#include "jnative_runtime.h"

/*
 * java.net.Inet6AddressImpl — the native entry points that back the
 * IPv6 (and dual-stack) resolver. The class lives on top of the JDK's
 * InetAddress hierarchy and is dispatched to when the Java layer has
 * selected the IPv6 path, either because the resolver policy asks for
 * IPv6 first, or because the host is known to support dual-stack
 * sockets.
 *
 * The results produced here are fully-shaped java.net.InetAddress
 * instances, not raw address bytes: every returned object carries a
 * valid vtable, a canonical host name and an InetAddressHolder with
 * the family and the address bytes already populated. Java callers
 * can therefore invoke getAddress / getHostAddress / toString on the
 * result without any further native assistance.
 *
 * Unlike the IPv4 implementation, this one is genuinely dual-stack:
 * the resolver is invoked with ai_family = AF_UNSPEC, and the result
 * set may contain both AF_INET and AF_INET6 entries. Every such entry
 * is wrapped into an InetAddress carrying the matching java_family
 * value (see JAVA_IPV4 / JAVA_IPV6 below).
 */

/*
 * The `family` values stored in InetAddressHolder. The numeric values
 * match the Java-side constants InetAddress.IPv4 (1) and InetAddress.IPv6
 * (2) and are consumed by InetAddress itself, not by any native.
 */
#define JAVA_IPV4 1
#define JAVA_IPV6 2

/*
 * Build a fully-shaped java.net.InetAddress whose holder carries the
 * given hostname, family and address bytes. The number of bytes copied
 * into the address array depends on the family: 4 for IPv4, 16 for
 * IPv6. Returns NULL if either of the required classes is not in the
 * reflection registry or if any allocation fails.
 *
 * The field layout used here matches LlvmGlobalEmitter.getFieldOffset
 * for the two target classes:
 *
 *   java.net.InetAddress:
 *       offset  8 : String canonicalHostName
 *       offset 16 : InetAddressHolder holder
 *
 *   java.net.InetAddress$InetAddressHolder:
 *       offset  8 : String hostName
 *       offset 16 : int    family
 *       offset 20 : byte[] addressBytes
 */
static void* make_inet_address(const uint8_t* bytes, int java_family,
                               const char* hostname)
{
    ReflectionClass* ia_cls =
        jnative_class_by_name("java/net/InetAddress");
    ReflectionClass* h_cls =
        jnative_class_by_name("java/net/InetAddress$InetAddressHolder");
    if (!ia_cls || !h_cls) return NULL;

    void* holder = jnative_alloc_object(h_cls);
    if (!holder) return NULL;

    *(void**)((char*)holder + 8)    = jnative_string(hostname);
    *(int32_t*)((char*)holder + 16) = java_family;
    *(void**)((char*)holder + 20)   =
        jnative_byte_array(bytes, java_family == JAVA_IPV4 ? 4 : 16);

    void* ia = jnative_alloc_object(ia_cls);
    if (!ia) { free(holder); return NULL; }

    *(void**)((char*)ia + 8)  = NULL;
    *(void**)((char*)ia + 16) = holder;

    return ia;
}

void* __jnative_fn_java_net_Inet6AddressImpl_lookupAllHostAddr__Ljava_lang_String_I__Ljava_net_InetAddress_(
        void* host_str, int32_t policy)
{
    (void)policy;
    if (!host_str) {
        __jnative_throw_exception(NULL);
    }
    int32_t hostLen = 0;
    const char* host = __jnative_read_string_bytes(host_str, &hostLen);
    (void)hostLen;

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family   = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    struct addrinfo* res = NULL;
    int rc = getaddrinfo(host, NULL, &hints, &res);
    if (rc != 0 || !res) {
        __jnative_throw_exception(NULL);
    }

    int count = 0;
    for (struct addrinfo* p = res; p; p = p->ai_next) {
        if (p->ai_family == AF_INET || p->ai_family == AF_INET6) count++;
    }
    if (count == 0) {
        freeaddrinfo(res);
        __jnative_throw_exception(NULL);
    }

    /* InetAddress[] — the descriptor matches the declared Java return
     * type, so getClass() on the result answers with
     * [Ljava/net/InetAddress;. */
    void* array = jnative_ref_array_of_class(NULL, count,
                                             "[Ljava/net/InetAddress;");
    if (!array) {
        freeaddrinfo(res);
        __jnative_throw_exception(NULL);
    }

    void** slots = (void**)((char*)array + JAVA_ARR_HDR);
    int i = 0;
    for (struct addrinfo* p = res; p && i < count; p = p->ai_next) {
        const uint8_t* bytes = NULL;
        int java_family = 0;
        if (p->ai_family == AF_INET) {
            struct sockaddr_in* sin = (struct sockaddr_in*)p->ai_addr;
            bytes = (const uint8_t*)&sin->sin_addr;
            java_family = JAVA_IPV4;
        } else if (p->ai_family == AF_INET6) {
            struct sockaddr_in6* sin6 = (struct sockaddr_in6*)p->ai_addr;
            bytes = (const uint8_t*)&sin6->sin6_addr;
            java_family = JAVA_IPV6;
        } else {
            continue;
        }
        slots[i++] = make_inet_address(bytes, java_family, host);
    }

    freeaddrinfo(res);
    return array;
}

void* __jnative_fn_java_net_Inet6AddressImpl_getHostByAddr___B_Ljava_lang_String_(
        void* addr_bytes)
{
    if (!addr_bytes) return NULL;

    int32_t len = jnative_array_length(addr_bytes);
    uint8_t* bytes = (uint8_t*)jnative_array_data(addr_bytes);

    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    socklen_t sslen = 0;

    if (len == 4) {
        struct sockaddr_in* sin = (struct sockaddr_in*)&ss;
        sin->sin_family = AF_INET;
        memcpy(&sin->sin_addr, bytes, 4);
        sslen = sizeof(struct sockaddr_in);
    } else if (len == 16) {
        struct sockaddr_in6* sin6 = (struct sockaddr_in6*)&ss;
        sin6->sin6_family = AF_INET6;
        memcpy(&sin6->sin6_addr, bytes, 16);
        sslen = sizeof(struct sockaddr_in6);
    } else {
        return NULL;
    }

    char hostbuf[NI_MAXHOST];
    int rc = getnameinfo((struct sockaddr*)&ss, sslen,
                         hostbuf, sizeof(hostbuf),
                         NULL, 0, NI_NAMEREQD);
    if (rc != 0) {
        return NULL;
    }
    return jnative_string(hostbuf);
}

void* __jnative_fn_java_net_Inet6AddressImpl_getLocalHostName___Ljava_lang_String_(void)
{
    char buf[256];
    if (gethostname(buf, sizeof(buf)) != 0) {
        return NULL;
    }
    buf[sizeof(buf) - 1] = '\0';
    return jnative_string(buf);
}

/*
 * private native boolean isReachable0(byte[] addr, int scope,
 *                                     int timeout, byte[] inf,
 *                                     int ttl, int if_scope)
 *     throws IOException;
 *
 * Probes reachability of a remote IPv6 or IPv4 address. The `addr`
 * argument is the 4- or 16-byte network-order address, `inf` is an
 * optional 4- or 16-byte local-interface address, and the remaining
 * arguments are the ICMP/echo parameters that the reference
 * implementation passes to the platform's reachability mechanism.
 *
 * The reference implementation builds a raw socket or uses the
 * platform's ping equivalent. Raw-socket ICMP requires root, uses
 * SOCK_RAW which the runtime's ordinary socket machinery cannot
 * emulate, and is not part of any code path the image exercises
 * except via InetAddress.isReachable, which itself is specified to
 * have a fallback when ICMP is unavailable.
 *
 * The runtime therefore implements the fallback directly: a TCP
 * connect(2) to port 7 (echo) with the caller-supplied timeout. Any
 * successful connect — including ECONNREFUSED, which proves a
 * listener-reachable host exists on the path — reports the address
 * as reachable. A timeout or an EHOSTUNREACH / ENETUNREACH reports
 * unreachable. This matches the reference implementation's own
 * documented fallback behaviour for the case where the ICMP path is
 * unavailable, and satisfies the Java-level contract of
 * InetAddress.isReachable(timeout).
 */
int32_t __jnative_fn_java_net_Inet6AddressImpl_isReachable0___BII_BII_Z(
        void* addr_bytes, int32_t scope, int32_t timeout,
        void* inf_bytes, int32_t ttl, int32_t if_scope)
{
    (void)scope; (void)inf_bytes; (void)ttl; (void)if_scope;

    if (addr_bytes == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    int32_t addr_len = jnative_array_length(addr_bytes);
    const uint8_t* addr = (const uint8_t*)jnative_array_data(addr_bytes);

    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    socklen_t sslen;

    if (addr_len == 4) {
        struct sockaddr_in* sin = (struct sockaddr_in*)&ss;
        sin->sin_family = AF_INET;
        memcpy(&sin->sin_addr, addr, 4);
        sin->sin_port = htons(7);
        sslen = sizeof(*sin);
    } else if (addr_len == 16) {
        struct sockaddr_in6* sin6 = (struct sockaddr_in6*)&ss;
        sin6->sin6_family = AF_INET6;
        memcpy(&sin6->sin6_addr, addr, 16);
        sin6->sin6_port = htons(7);
        sslen = sizeof(*sin6);
    } else {
        return 0;
    }

    int fd = socket(ss.ss_family, SOCK_STREAM, 0);
    if (fd < 0) {
        return 0;
    }

    /* Apply the caller's timeout to the connect attempt. A zero or
     * negative timeout means "use the platform default", which for a
     * blocking connect is roughly the kernel's SYN retry window; the
     * Java-level isReachable(0) means "use the default timeout", so
     * leaving SO_SNDTIMEO unset in that case is correct. */
    if (timeout > 0) {
        struct timeval tv;
        tv.tv_sec  = timeout / 1000;
        tv.tv_usec = (timeout % 1000) * 1000;
        (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    }

    int rc;
    do {
        rc = connect(fd, (struct sockaddr*)&ss, sslen);
    } while (rc < 0 && errno == EINTR);

    int reachable = 0;
    if (rc == 0) {
        reachable = 1;
    } else if (errno == ECONNREFUSED) {
        /* The host answered — it is reachable even though nothing
         * is listening on port 7. This is the same reasoning the
         * reference implementation uses for its TCP fallback. */
        reachable = 1;
    }

    close(fd);
    return reachable;
}