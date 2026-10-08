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
 * java.net.Inet4AddressImpl — the native entry points that back the
 * IPv4 resolver. The class lives on top of the JDK's InetAddress
 * hierarchy and is dispatched to only when the Java layer has already
 * selected the IPv4 path (either because the resolver policy asks for
 * IPv4 first, or because IPv6 support is not available on the host).
 *
 * The results produced here are fully-shaped java.net.InetAddress
 * instances, not raw address bytes: every returned object carries a
 * valid vtable, a canonical host name and an InetAddressHolder with
 * the family and the address bytes already populated. Java callers can
 * therefore invoke getAddress / getHostAddress / toString on the
 * result without any further native assistance.
 */

/*
 * The `family` value stored in InetAddressHolder. The numeric value
 * matches the Java-side constant InetAddress.IPv4 (1) and is consumed
 * by InetAddress itself, not by any native.
 */
#define JAVA_IPV4 1

/*
 * Build a fully-shaped java.net.InetAddress whose holder carries the
 * given hostname, family and 4-byte IPv4 address. Returns NULL if
 * either of the required classes is not in the reflection registry or
 * if any allocation fails.
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
static void* make_inet4_address(const uint8_t* bytes, const char* hostname) {
    ReflectionClass* ia_cls =
        jnative_class_by_name("java/net/InetAddress");
    ReflectionClass* h_cls =
        jnative_class_by_name("java/net/InetAddress$InetAddressHolder");
    if (!ia_cls || !h_cls) return NULL;

    void* holder = jnative_alloc_object(h_cls);
    if (!holder) return NULL;

    *(void**)((char*)holder + 8)    = jnative_string(hostname);
    *(int32_t*)((char*)holder + 16) = JAVA_IPV4;
    *(void**)((char*)holder + 20)   = jnative_byte_array(bytes, 4);

    void* ia = jnative_alloc_object(ia_cls);
    if (!ia) { free(holder); return NULL; }

    *(void**)((char*)ia + 8)  = NULL;
    *(void**)((char*)ia + 16) = holder;

    return ia;
}

/*
 * lookupAllHostAddr — canonical single-argument implementation.
 *
 *   private native InetAddress[] lookupAllHostAddr(String host)
 *       throws UnknownHostException;
 *
 * This is the JDK 8 – 17 signature: no policy argument, IPv4-only
 * resolution via getaddrinfo with ai_family = AF_INET. It is the base
 * implementation that both of the later overloads forward to, so the
 * same body serves every JDK generation this runtime targets.
 *
 * The returned array is packed in the runtime's Java-array layout
 * (4-byte int length header, then pointer slots), and each element is
 * a fully-shaped InetAddress as produced by make_inet4_address above.
 *
 * Every error path — a null host, a resolver failure, an empty result
 * set — is reported through the generic throw helper, which the
 * Java-side caller's UnknownHostException catch block converts into
 * the appropriate checked exception.
 */
void* __jnative_fn_java_net_Inet4AddressImpl_lookupAllHostAddr__Ljava_lang_String___Ljava_net_InetAddress_(
        void* host_str)
{
    if (!host_str) {
        __jnative_throw_exception(NULL);
    }
    int32_t hostLen = 0;
    const char* host = __jnative_read_string_bytes(host_str, &hostLen);
    (void)hostLen;

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family   = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    struct addrinfo* res = NULL;
    int rc = getaddrinfo(host, NULL, &hints, &res);
    if (rc != 0 || !res) {
        __jnative_throw_exception(NULL);
    }

    int count = 0;
    for (struct addrinfo* p = res; p; p = p->ai_next) {
        if (p->ai_family == AF_INET) count++;
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
        if (p->ai_family != AF_INET) continue;
        struct sockaddr_in* sin = (struct sockaddr_in*)p->ai_addr;
        slots[i++] = make_inet4_address((const uint8_t*)&sin->sin_addr, host);
    }

    freeaddrinfo(res);
    return array;
}

/*
 * lookupAllHostAddr — pre-JDK-18 two-argument form.
 *
 *   private native InetAddress[] lookupAllHostAddr(String host, int policy)
 *       throws UnknownHostException;
 *
 * Some intermediate JDK versions carried an int policy argument whose
 * bit values selected the address-family ordering. Inet4AddressImpl is
 * selected only when the caller has already decided the IPv4 path
 * applies, so the policy is informational here; the IPv4-only
 * resolution is the correct answer regardless of which bits are set.
 */
void* __jnative_fn_java_net_Inet4AddressImpl_lookupAllHostAddr__Ljava_lang_String_I__Ljava_net_InetAddress_(
        void* host_str, int32_t policy)
{
    (void)policy;
    return __jnative_fn_java_net_Inet4AddressImpl_lookupAllHostAddr__Ljava_lang_String___Ljava_net_InetAddress_(
        host_str);
}

/*
 * lookupAllHostAddr — JDK 18+ LookupPolicy form.
 *
 *   public native InetAddress[] lookupAllHostAddr(
 *           String host,
 *           InetAddressResolver.LookupPolicy lookupPolicy)
 *       throws UnknownHostException;
 *
 * The LookupPolicy object carries the caller's requested address-family
 * ordering. As with the int policy above, Inet4AddressImpl is only
 * ever dispatched to when the Java layer has already established that
 * the IPv4 path applies, so the argument is informational and the body
 * forwards to the canonical single-argument implementation.
 *
 * The mangled symbol encodes the descriptor
 *   (Ljava/lang/String;Ljava/net/spi/InetAddressResolver$LookupPolicy;)
 *       [Ljava/net/InetAddress;
 * exactly, with the three underscores between `Ljava_lang_String_` and
 * `Ljava_net_InetAddress_` coming from the `;`, `)`, and `[`
 * respectively.
 */
void* __jnative_fn_java_net_Inet4AddressImpl_lookupAllHostAddr__Ljava_lang_String_Ljava_net_spi_InetAddressResolver_LookupPolicy___Ljava_net_InetAddress_(
        void* host_str, void* lookup_policy)
{
    (void)lookup_policy;
    return __jnative_fn_java_net_Inet4AddressImpl_lookupAllHostAddr__Ljava_lang_String___Ljava_net_InetAddress_(
        host_str);
}

/*
 * getHostByAddr — reverse DNS.
 *
 *   public native String getHostByAddr(byte[] addr)
 *       throws UnknownHostException;
 *
 * Reverses the 4-byte address stored in the given byte array into a
 * host name via getnameinfo(NI_NAMEREQD). A byte array whose length is
 * not exactly 4 is reported as "no name available" by returning NULL;
 * the Java-side caller translates that into UnknownHostException.
 */
void* __jnative_fn_java_net_Inet4AddressImpl_getHostByAddr___B_Ljava_lang_String_(
        void* addr_bytes)
{
    if (!addr_bytes) return NULL;

    int32_t len = jnative_array_length(addr_bytes);
    if (len != 4) return NULL;
    uint8_t* bytes = (uint8_t*)jnative_array_data(addr_bytes);

    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    memcpy(&sin.sin_addr, bytes, 4);

    char hostbuf[NI_MAXHOST];
    int rc = getnameinfo((struct sockaddr*)&sin, sizeof(sin),
                         hostbuf, sizeof(hostbuf),
                         NULL, 0, NI_NAMEREQD);
    if (rc != 0) {
        return NULL;
    }
    return jnative_string(hostbuf);
}

/*
 * getLocalHostName — the local machine's host name.
 *
 *   public native String getLocalHostName();
 *
 * Thin wrapper over gethostname(3), with a NUL terminator forced at
 * the end of the buffer so the resulting String cannot include
 * uninitialised bytes if the kernel filled the buffer completely. A
 * failed gethostname returns NULL, which the Java-side caller treats
 * as "no local host name available".
 */
void* __jnative_fn_java_net_Inet4AddressImpl_getLocalHostName___Ljava_lang_String_(void)
{
    char buf[256];
    if (gethostname(buf, sizeof(buf)) != 0) {
        return NULL;
    }
    buf[sizeof(buf) - 1] = '\0';
    return jnative_string(buf);
}

/*
 * private native boolean isReachable0(byte[] addr, int timeout,
 *                                     byte[] inf, int ttl)
 *     throws IOException;
 *
 * IPv4 counterpart of Inet6AddressImpl.isReachable0. The four-argument
 * form is the JDK 21 IPv4 signature; the arguments are the 4-byte
 * network-order address, the timeout in milliseconds, an optional
 * 4-byte local-interface address, and the TTL for the echo probe.
 *
 * The same TCP-connect fallback that the IPv6 implementation uses
 * applies here: the raw-socket ICMP path requires root and is not
 * available in this runtime, so the fallback is what actually runs.
 * A successful connect or a refused connect both prove the host is
 * on a working path; an unreachable or timed-out attempt proves the
 * opposite.
 */
int32_t __jnative_fn_java_net_Inet4AddressImpl_isReachable0___BI_BI_Z(
        void* addr_bytes, int32_t timeout,
        void* inf_bytes, int32_t ttl)
{
    (void)inf_bytes; (void)ttl;

    if (addr_bytes == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    int32_t addr_len = jnative_array_length(addr_bytes);
    if (addr_len != 4) {
        return 0;
    }
    const uint8_t* addr = (const uint8_t*)jnative_array_data(addr_bytes);

    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    memcpy(&sin.sin_addr, addr, 4);
    sin.sin_port = htons(7);

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        return 0;
    }

    if (timeout > 0) {
        struct timeval tv;
        tv.tv_sec  = timeout / 1000;
        tv.tv_usec = (timeout % 1000) * 1000;
        (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    }

    int rc;
    do {
        rc = connect(fd, (struct sockaddr*)&sin, sizeof(sin));
    } while (rc < 0 && errno == EINTR);

    int reachable = (rc == 0 || errno == ECONNREFUSED) ? 1 : 0;
    close(fd);
    return reachable;
}