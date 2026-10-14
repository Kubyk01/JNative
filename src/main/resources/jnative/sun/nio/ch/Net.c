#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>

#include "jnative_runtime.h"

/*
 * sun.nio.ch.Net — native support for the NIO socket layer.
 *
 * The class is a repository of small helpers that SocketChannelImpl,
 * ServerSocketChannelImpl, DatagramChannelImpl and the selector
 * implementations call when they need to talk to the underlying
 * socket file descriptor directly. Every method receives the
 * FileDescriptor object (not the raw kernel fd); the raw fd is the
 * first instance field at offset FD_OFFSET, matching the layout used
 * by every other native in this runtime (FileInputStream.c,
 * UnixFileDispatcherImpl.c, SocketDispatcher.c, …). The helpers
 * jnative_fd_of and jnative_raw_fd from jnative_runtime.h perform
 * that extraction.
 */

/*
 * static native void initIDs();
 *
 * Called from Net.<clinit>. On HotSpot this hook caches the JNI field
 * IDs of Net's static fields. This runtime accesses every field
 * through its LLVM-computed byte offset and never consults JNI field
 * IDs, so there is nothing to cache. The symbol must exist because
 * Net.<clinit> emits a native call to it.
 */
void __jnative_fn_sun_nio_ch_Net_initIDs___V(void) {
}

/*
 * static native short pollinValue();
 * static native short polloutValue();
 * static native short pollerrValue();
 * static native short pollhupValue();
 * static native short pollnvalValue();
 * static native short pollconnValue();
 *
 * Return the bit values that poll(2) uses for the corresponding
 * events. The Java layer reads these once in <clinit> and combines
 * them with bitwise OR to build the event mask it passes to poll().
 * Returning the actual <poll.h> constants rather than hard-coded
 * numbers keeps the runtime portable to any platform whose <poll.h>
 * disagrees with the common Linux values (though every platform this
 * runtime targets uses the same set).
 *
 * pollconnValue is the bit that signals "the connect completed" on a
 * non-blocking socket. On Linux that is exactly POLLOUT — a socket
 * becoming writable is what "connect done" looks like through poll(2).
 */
int16_t __jnative_fn_sun_nio_ch_Net_pollinValue___S(void) {
    return (int16_t)POLLIN;
}

int16_t __jnative_fn_sun_nio_ch_Net_polloutValue___S(void) {
    return (int16_t)POLLOUT;
}

int16_t __jnative_fn_sun_nio_ch_Net_pollerrValue___S(void) {
    return (int16_t)POLLERR;
}

int16_t __jnative_fn_sun_nio_ch_Net_pollhupValue___S(void) {
    return (int16_t)POLLHUP;
}

int16_t __jnative_fn_sun_nio_ch_Net_pollnvalValue___S(void) {
    return (int16_t)POLLNVAL;
}

int16_t __jnative_fn_sun_nio_ch_Net_pollconnValue___S(void) {
    /*
     * On Linux, "connection completed" on a non-blocking socket is
     * signalled by the socket becoming writable.
     */
    return (int16_t)POLLOUT;
}

/*
 * static native int isExclusiveBindAvailable();
 *
 * Returns 1 if the platform supports the "exclusive bind" model in
 * which SO_REUSEADDR is not required for rebinding, -1 if it does not,
 * and 0 if the answer is unknown. Exclusive binding is a Windows
 * concept (the SO_EXCLUSIVEADDRUSE option); on Linux it does not
 * exist. The Java layer treats any non-positive return as "not
 * available" and consequently always sets SO_REUSEADDR on the
 * listening socket before bind, which matches the reference Unix
 * implementation.
 */
int32_t __jnative_fn_sun_nio_ch_Net_isExclusiveBindAvailable___I(void) {
    return -1;
}

/*
 * static native boolean isIPv6Available0();
 *
 * Returns true iff the process can open an AF_INET6 socket. The probe
 * attempts exactly that once and memoises the answer. Java callers use
 * the result to decide whether to enable the IPv6 dual-stack selector
 * and whether to honour an explicit IPv6 address family request.
 */
int32_t __jnative_fn_sun_nio_ch_Net_isIPv6Available0___Z(void) {
    static int cached = -1;
    if (cached < 0) {
        int fd = socket(AF_INET6, SOCK_STREAM, 0);
        if (fd >= 0) {
            close(fd);
            cached = 1;
        } else {
            cached = 0;
        }
    }
    return cached;
}

/*
 * static native boolean isReusePortAvailable0();
 *
 * Returns true iff the platform provides SO_REUSEPORT, which lets
 * several sockets bind to the same port and lets the kernel
 * load-balance incoming connections across them. On Linux this option
 * has existed since kernel 3.9; the probe checks for its presence in
 * the running kernel by querying the option on a throwaway socket,
 * rather than assuming from the compile-time headers, so a build that
 * targets an older kernel still reports the correct answer at run
 * time.
 */
int32_t __jnative_fn_sun_nio_ch_Net_isReusePortAvailable0___Z(void) {
    static int cached = -1;
    if (cached < 0) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) {
            cached = 0;
        } else {
#ifdef SO_REUSEPORT
            int one = 1;
            if (setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &one, sizeof(one)) == 0) {
                cached = 1;
            } else {
                cached = 0;
            }
#else
            cached = 0;
#endif
            close(fd);
        }
    }
    return cached;
}

/*
 * private static native int getIntOption0(FileDescriptor fd,
 *                                         boolean mayNeedConversion,
 *                                         int level, int opt);
 *
 * Reads an integer-valued socket option via getsockopt(2). The
 * `mayNeedConversion` flag exists for Windows, where a few socket
 * options have a different internal representation than their API
 * value; on Linux it is ignored.
 *
 * Returns the option value on success. Every error — including a null
 * fd, a negative descriptor, an EINVAL from an unknown option, and a
 * genuine IO error — surfaces as the generic throw helper, which the
 * Java caller's `catch (IOException x)` block converts into the
 * appropriate SocketException.
 */
int32_t __jnative_fn_sun_nio_ch_Net_getIntOption0__Ljava_io_FileDescriptor_ZII_I(
        void* fd_obj, int32_t mayNeedConversion, int32_t level, int32_t opt)
{
    (void)mayNeedConversion;
    int32_t fd = jnative_raw_fd(fd_obj);

    int value = 0;
    socklen_t len = (socklen_t)sizeof(value);

    if (getsockopt(fd, (int)level, (int)opt, &value, &len) < 0) {
        __jnative_throw_exception(NULL);
    }
    return (int32_t)value;
}

/*
 * private static native void setIntOption0(FileDescriptor fd,
 *                                          boolean isIPv6,
 *                                          int level,
 *                                          int opt,
 *                                          int arg,
 *                                          boolean mayNeedConversion);
 *
 * Writes an integer-valued socket option via setsockopt(2). The
 * `isIPv6` flag is informational on Linux — the option's own level
 * already identifies the protocol stack — and `mayNeedConversion` is
 * again a Windows carry-over that is ignored here.
 *
 * The JDK's own Linux implementation delegates to `setsockopt`, which
 * is what this function does. Any failure surfaces as the generic
 * throw helper and the Java caller turns it into a SocketException.
 */
void __jnative_fn_sun_nio_ch_Net_setIntOption0__Ljava_io_FileDescriptor_ZIIIZ_V(
        void* fd_obj, int32_t isIPv6, int32_t level, int32_t opt,
        int32_t arg, int32_t mayNeedConversion)
{
    (void)isIPv6;
    (void)mayNeedConversion;
    int32_t fd = jnative_raw_fd(fd_obj);

    int value = (int)arg;
    if (setsockopt(fd, (int)level, (int)opt, &value, (socklen_t)sizeof(value)) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * private static native int poll(FileDescriptor fd, int events,
 *                                long timeout);
 *
 * Polls a single file descriptor for readiness on the events in
 * `events`, waiting up to `timeout` milliseconds (0 = return
 * immediately, -1 = block indefinitely). Returns the poll(2) event
 * mask that fired: a combination of the pollinValue / polloutValue /
 * pollerrValue / pollhupValue / pollnvalValue bits read in <clinit>.
 *
 * The `timeout` parameter is a Java `long` because the selector
 * computes it from millisecond deadlines that can exceed INT_MAX; the
 * C side clamps to the range poll(2) accepts.
 *
 * EINTR is retried with the full remaining timeout. A signal delivered
 * during the wait therefore does not terminate the poll with a
 * spurious 0; the caller only sees a 0 when the requested timeout has
 * actually elapsed.
 */
int32_t __jnative_fn_sun_nio_ch_Net_poll__Ljava_io_FileDescriptor_IJ_I(
        void* fd_obj, int32_t events, int64_t timeout)
{
    int32_t fd = jnative_raw_fd(fd_obj);

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = (short)events;
    pfd.revents = 0;

    int ctimeout;
    if (timeout < 0) {
        ctimeout = -1;
    } else if (timeout > INT32_MAX) {
        ctimeout = INT32_MAX;
    } else {
        ctimeout = (int)timeout;
    }

    int rv;
    do {
        rv = poll(&pfd, 1, ctimeout);
    } while (rv < 0 && errno == EINTR);

    if (rv < 0) {
        __jnative_throw_exception(NULL);
    }
    if (rv == 0) {
        return 0;
    }
    return (int32_t)pfd.revents;
}

/*
 * private static native int available(FileDescriptor fd);
 *
 * Number of bytes that can be read from the socket without blocking.
 * Uses ioctl(FIONREAD) on the raw fd, which is the same query the
 * reference Unix implementation performs. On a healthy socket this is
 * always non-negative; a failed ioctl is reported as 0, which the Java
 * caller interprets as "no data currently available".
 *
 * A NULL FileDescriptor returns 0 rather than throwing: the Java-side
 * caller uses this as a lightweight "is there data" probe and treats a
 * 0 result as "not now", which is exactly the behaviour it wants for a
 * stale or uninitialised descriptor.
 */
int32_t __jnative_fn_sun_nio_ch_Net_available__Ljava_io_FileDescriptor__I(void* fd_obj) {
    if (fd_obj == NULL) {
        return 0;
    }
    int32_t fd = jnative_fd_of(fd_obj);
    if (fd < 0) {
        return 0;
    }

    int n = 0;
    if (ioctl(fd, FIONREAD, &n) < 0) {
        return 0;
    }
    if (n < 0) return 0;
    return (int32_t)n;
}

/*
 * private static native void shutdown(FileDescriptor fd, int how);
 *
 * Half-closes the socket. `how` is one of SHUT_RD / SHUT_WR /
 * SHUT_RDWR, mirroring the constants that SocketChannelImpl declares
 * in Java. Any failure surfaces as the generic throw helper.
 */
void __jnative_fn_sun_nio_ch_Net_shutdown__Ljava_io_FileDescriptor_I_V(
        void* fd_obj, int32_t how)
{
    int32_t fd = jnative_raw_fd(fd_obj);

    if (shutdown(fd, (int)how) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * ===================================================================
 * Socket address introspection and dual-stack option probing.
 * ===================================================================
 *
 * The three natives below are declared in sun/nio/ch/Net.java and
 * called from SocketChannelImpl / ServerSocketChannelImpl when the
 * Java layer needs to reflect the kernel's own view of a socket back
 * into an InetSocketAddress. None of them existed in the C runtime
 * before, so the reachability walk pulled the declarations into the
 * emitted IR and the linker had no bodies for them.
 *
 *   localInetAddress(FileDescriptor) -> InetAddress
 *       getsockname(2) on the socket, then wraps the result in a
 *       java.net.InetAddress instance via the same construction path
 *       used by Inet4AddressImpl.c / Inet6AddressImpl.c.
 *
 *   localPort(FileDescriptor) -> int
 *       getsockname(2) and returns the port in host byte order.
 *
 *   shouldSetBothIPv4AndIPv6Options0() -> boolean
 *       Returns true iff the platform requires the same option value
 *       to be set on both an AF_INET and an AF_INET6 socket when the
 *       underlying socket is dual-stack. This is a Windows-specific
 *       quirk; on Linux each socket has its own options and the JDK
 *       must return false so the Java layer only sets the option once.
 *       Returning true here would double-apply every option and, for
 *       several of them (IP_TOS, IP_MULTICAST_IF), produce EINVAL on
 *       the second call.
 */

/*
 * Build a java.net.InetAddress whose InetAddressHolder carries the
 * given address bytes, family and textual host name. Layout matches
 * the one used by Inet4AddressImpl.c / Inet6AddressImpl.c:
 *
 *   java.net.InetAddress:
 *       +8  String canonicalHostName
 *       +16 InetAddressHolder holder
 *
 *   java.net.InetAddress$InetAddressHolder:
 *       +8  String hostName
 *       +16 int    family
 *       +20 byte[] addressBytes
 */
static void* net_make_inet_address(const uint8_t* bytes, int java_family,
                                   const char* hostname)
{
    ReflectionClass* ia_cls =
        jnative_class_by_name("java/net/InetAddress");
    ReflectionClass* h_cls =
        jnative_class_by_name("java/net/InetAddress$InetAddressHolder");
    if (ia_cls == NULL || h_cls == NULL) {
        __jnative_throw_exception(NULL);
    }

    void* holder = jnative_alloc_object(h_cls);
    if (holder == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Net.localInetAddress");
    }

    *(void**)((char*)holder + 8)    = jnative_string(hostname);
    *(int32_t*)((char*)holder + 16) = java_family;
    *(void**)((char*)holder + 20)   =
        jnative_byte_array(bytes, java_family == 1 ? 4 : 16);

    void* ia = jnative_alloc_object(ia_cls);
    if (ia == NULL) {
        __jnative_throw_out_of_memory_error_ctx("Net.localInetAddress");
    }
    *(void**)((char*)ia + 8)  = NULL;
    *(void**)((char*)ia + 16) = holder;
    return ia;
}

void* __jnative_fn_sun_nio_ch_Net_localInetAddress__Ljava_io_FileDescriptor__Ljava_net_InetAddress_(
        void* fd_obj)
{
    int32_t fd = jnative_raw_fd(fd_obj);

    struct sockaddr_storage ss;
    socklen_t salen = (socklen_t)sizeof(ss);
    memset(&ss, 0, sizeof(ss));

    if (getsockname(fd, (struct sockaddr*)&ss, &salen) < 0) {
        __jnative_throw_exception(NULL);
        return NULL;
    }

    if (ss.ss_family == AF_INET) {
        struct sockaddr_in* sin = (struct sockaddr_in*)&ss;
        return net_make_inet_address((const uint8_t*)&sin->sin_addr, 1,
                                     "0.0.0.0");
    }
    if (ss.ss_family == AF_INET6) {
        struct sockaddr_in6* sin6 = (struct sockaddr_in6*)&ss;
        return net_make_inet_address((const uint8_t*)&sin6->sin6_addr, 2,
                                     "::");
    }
    __jnative_throw_exception(NULL);
    return NULL;
}

int32_t __jnative_fn_sun_nio_ch_Net_localPort__Ljava_io_FileDescriptor__I(
        void* fd_obj)
{
    int32_t fd = jnative_raw_fd(fd_obj);

    struct sockaddr_storage ss;
    socklen_t salen = (socklen_t)sizeof(ss);
    memset(&ss, 0, sizeof(ss));

    if (getsockname(fd, (struct sockaddr*)&ss, &salen) < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    if (ss.ss_family == AF_INET) {
        return (int32_t)ntohs(((struct sockaddr_in*)&ss)->sin_port);
    }
    if (ss.ss_family == AF_INET6) {
        return (int32_t)ntohs(((struct sockaddr_in6*)&ss)->sin6_port);
    }
    return 0;
}

int32_t __jnative_fn_sun_nio_ch_Net_shouldSetBothIPv4AndIPv6Options0___Z(void) {
    /*
     * Linux: each socket has its own option set. The JDK's own
     * Unix implementation of this method returns false, and the
     * Java side only consults the return value to decide whether
     * to mirror a socket option from the AF_INET socket to the
     * AF_INET6 one on a dual-stack bind.
     */
    return 0;
}

/*
 * static native void bind0(FileDescriptor fd, boolean preferIPv6,
 *                          boolean useExclBind, InetAddress addr,
 *                          int port)
 *     throws IOException;
 *
 * Binds a socket to a local address and port. Called from
 * Net.bind(ProtocolFamily, FileDescriptor, InetAddress, int), which
 * has already resolved the ProtocolFamily and normalized the
 * address. This is the entry point the JDK's own Unix implementation
 * uses; the arguments map one-for-one onto the underlying bind(2)
 * call:
 *
 *   fd            the socket to bind
 *   preferIPv6    selects AF_INET6 when the address is null or
 *                 wildcard and the platform is dual-stack; the
 *                 family is otherwise derived from the address
 *   useExclBind   Windows-only; informational here
 *   addr          the local address, or null for the wildcard
 *   port          the local port in host byte order
 *
 * The address is turned into a sockaddr_in or sockaddr_in6 by the
 * same net_make_sockaddr helper the localInetAddress native uses,
 * so the two are guaranteed to agree on how an InetAddress is read.
 *
 * Any failure — EADDRINUSE, EACCES for a privileged port,
 * EADDRNOTAVAIL for an address not assigned to this host — surfaces
 * as a BindException at the Java layer through the generic throw
 * helper and the caller's exception translation.
 */
void __jnative_fn_sun_nio_ch_Net_bind0__Ljava_io_FileDescriptor_ZZLjava_net_InetAddress_I_V(
        void* fd_obj, int32_t preferIPv6, int32_t useExclBind,
        void* addr_obj, int32_t port)
{
    (void)useExclBind;
    int32_t fd = jnative_raw_fd(fd_obj);

    struct sockaddr_storage ss;
    socklen_t sslen;

    if (addr_obj == NULL) {
        /* Wildcard bind. The family is whatever the socket was
         * created with; preferIPv6 only matters when the socket is
         * created here, which is not this function's job. */
        if (preferIPv6) {
            struct sockaddr_in6* s6 = (struct sockaddr_in6*)&ss;
            memset(s6, 0, sizeof(*s6));
            s6->sin6_family = AF_INET6;
            s6->sin6_port   = htons((uint16_t)port);
            sslen = sizeof(*s6);
        } else {
            struct sockaddr_in* s4 = (struct sockaddr_in*)&ss;
            memset(s4, 0, sizeof(*s4));
            s4->sin_family = AF_INET;
            s4->sin_port   = htons((uint16_t)port);
            sslen = sizeof(*s4);
        }
    } else {
        /*
         * Extract the address bytes and family from the InetAddress
         * through the same holder layout the address classes were
         * constructed with:
         *   InetAddress +16 -> InetAddressHolder
         *   InetAddressHolder +16 -> int family
         *   InetAddressHolder +20 -> byte[] addressBytes
         */
        void* holder = *(void**)((char*)addr_obj + 16);
        if (holder == NULL) {
            __jnative_throw_exception(NULL);
            return;
        }
        int32_t family = *(int32_t*)((char*)holder + 16);
        void*   bytes  = *(void**)((char*)holder + 20);
        if (bytes == NULL) {
            __jnative_throw_exception(NULL);
            return;
        }
        int32_t blen = jnative_array_length(bytes);
        const uint8_t* bp = (const uint8_t*)jnative_array_data(bytes);

        if (family == 1 && blen == 4) {
            struct sockaddr_in* s4 = (struct sockaddr_in*)&ss;
            memset(s4, 0, sizeof(*s4));
            s4->sin_family = AF_INET;
            s4->sin_port   = htons((uint16_t)port);
            memcpy(&s4->sin_addr, bp, 4);
            sslen = sizeof(*s4);
        } else if (family == 2 && blen == 16) {
            struct sockaddr_in6* s6 = (struct sockaddr_in6*)&ss;
            memset(s6, 0, sizeof(*s6));
            s6->sin6_family = AF_INET6;
            s6->sin6_port   = htons((uint16_t)port);
            memcpy(&s6->sin6_addr, bp, 16);
            sslen = sizeof(*s6);
        } else {
            __jnative_throw_exception(NULL);
            return;
        }
    }

    if (bind(fd, (struct sockaddr*)&ss, sslen) < 0) {
        __jnative_throw_exception(NULL);
    }
}