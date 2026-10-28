#define _GNU_SOURCE

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <winsock2.h>
#  include <ws2tcpip.h>
#  include <windows.h>
#  include <io.h>
#  include <mstcpip.h>
#  ifndef SHUT_RD
#    define SHUT_RD   SD_RECEIVE
#  endif
#  ifndef SHUT_WR
#    define SHUT_WR   SD_SEND
#  endif
#  ifndef SHUT_RDWR
#    define SHUT_RDWR SD_BOTH
#  endif
#  ifndef POLLIN
#    define POLLIN    POLLRDNORM
#  endif
#  ifndef POLLOUT
#    define POLLOUT   POLLWRNORM
#  endif
#  ifndef POLLERR
#    define POLLERR   0x0001
#  endif
#  ifndef POLLHUP
#    define POLLHUP   0x0002
#  endif
#  ifndef POLLNVAL
#    define POLLNVAL  0x0004
#  endif
#  ifndef POLLCONN
#    define POLLCONN  POLLWRNORM
#  endif
#else
#  include <unistd.h>
#  include <errno.h>
#  include <fcntl.h>
#  include <poll.h>
#  include <sys/types.h>
#  include <sys/socket.h>
#  include <sys/ioctl.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <arpa/inet.h>
#  include <netdb.h>
#  include <string.h>
#  include <limits.h>
#  ifndef POLLCONN
#    define POLLCONN  POLLOUT
#  endif
#endif

#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>

#include "jnative_runtime.h"

#if defined(_WIN32)
typedef SOCKET jn_os_socket_t;
#  define JN_OS_INVALID_SOCKET INVALID_SOCKET
#else
typedef int    jn_os_socket_t;
#  define JN_OS_INVALID_SOCKET (-1)
#endif

#ifndef TCP_NODELAY
#  define TCP_NODELAY 1
#endif

#define IOS_UNAVAILABLE (-2)
#define IOS_INTERRUPTED (-3)

/* =========== error helpers =========== */

static inline int32_t jn_sock_errno(void) {
#if defined(_WIN32)
    return (int32_t)WSAGetLastError();
#else
    return (int32_t)errno;
#endif
}

static inline int jn_sock_would_block(int32_t e) {
#if defined(_WIN32)
    return e == WSAEWOULDBLOCK || e == WSAEINPROGRESS;
#else
    return e == EAGAIN || e == EWOULDBLOCK || e == EINPROGRESS;
#endif
}

static inline int jn_sock_interrupted(int32_t e) {
#if defined(_WIN32)
    return e == WSAEINTR;
#else
    return e == EINTR;
#endif
}

__attribute__((noreturn))
static void jn_throw_socket_ioexception(const char* what, int32_t err) {
    char msg[512];
#if defined(_WIN32)
    char sysbuf[256];
    DWORD n = FormatMessageA(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        NULL, (DWORD)err, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        sysbuf, (DWORD)sizeof(sysbuf), NULL);
    if (n > 0) {
        while (n > 0 && (sysbuf[n-1] == '\r' || sysbuf[n-1] == '\n'
                      || sysbuf[n-1] == ' '  || sysbuf[n-1] == '\t')) {
            sysbuf[--n] = '\0';
        }
        snprintf(msg, sizeof(msg), "%s: %s (WSA error %d)",
                 what ? what : "Socket I/O error", sysbuf, (int)err);
    } else {
        snprintf(msg, sizeof(msg), "%s (WSA error %d)",
                 what ? what : "Socket I/O error", (int)err);
    }
#else
    snprintf(msg, sizeof(msg), "%s: %s (errno %d)",
             what ? what : "Socket I/O error", strerror((int)err), (int)err);
#endif
    void* exc = __jnative_construct_exception("vtable_java_io_IOException", msg);
    __jnative_throw_exception(exc);
}

/* =========== FileDescriptor helpers =========== */

static inline int32_t net_fdval(void* fdo) {
    if (fdo == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = jnative_fd_of(fdo);
    if (fd < 0) {
        void* exc = __jnative_construct_exception(
            "vtable_java_io_IOException", "Socket closed");
        __jnative_throw_exception(exc);
    }
    return fd;
}

static inline jn_os_socket_t net_os_socket(void* fdo) {
    return (jn_os_socket_t)(uintptr_t)(intptr_t)net_fdval(fdo);
}

static inline void net_set_fdval(void* fdo, int32_t fd) {
    *(int32_t*)((char*)fdo + FD_OFFSET) = fd;
}

/* =========== InetAddress construction =========== */

static void* net_make_inet_address(const struct sockaddr_storage* ss,
                                    const char* host)
{
    ReflectionClass* ia_cls = jnative_class_by_name("java/net/InetAddress");
    ReflectionClass* h_cls  = jnative_class_by_name(
        "java/net/InetAddress$InetAddressHolder");
    if (!ia_cls || !h_cls) return NULL;

    void* holder = jnative_alloc_object(h_cls);
    if (!holder) return NULL;

    int fam = (int)ss->ss_family;
    int jf = (fam == AF_INET) ? 1 : 2;
    int alen = (fam == AF_INET) ? 4 : 16;
    const void* addr_bytes;

    if (fam == AF_INET) {
        addr_bytes = &((const struct sockaddr_in*)ss)->sin_addr;
    } else if (fam == AF_INET6) {
        addr_bytes = &((const struct sockaddr_in6*)ss)->sin6_addr;
    } else {
        free(holder); return NULL;
    }

    *(void**)((char*)holder + 8)    = host ? jnative_string(host) : NULL;
    *(int32_t*)((char*)holder + 16) = jf;
    *(void**)((char*)holder + 20)   = jnative_byte_array(addr_bytes, alen);

    void* ia = jnative_alloc_object(ia_cls);
    if (!ia) { free(holder); return NULL; }

    *(void**)((char*)ia + 8)  = NULL;
    *(void**)((char*)ia + 16) = holder;
    return ia;
}

static int net_sockaddr_from_inetaddress(void* ia, int32_t port,
                                          struct sockaddr_storage* ss, int* sslen)
{
    void* holder = *(void**)((char*)ia + 16);
    int fam = *(int32_t*)((char*)holder + 16);
    void* addr_bytes = *(void**)((char*)holder + 20);
    memset(ss, 0, sizeof(*ss));

    if (fam == 1) {
        struct sockaddr_in* sin = (struct sockaddr_in*)ss;
        sin->sin_family = AF_INET;
        sin->sin_port   = htons((uint16_t)port);
        memcpy(&sin->sin_addr, (uint8_t*)addr_bytes + JAVA_ARR_HDR, 4);
        *sslen = (int)sizeof(*sin);
    } else {
        struct sockaddr_in6* sin6 = (struct sockaddr_in6*)ss;
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port   = htons((uint16_t)port);
        memcpy(&sin6->sin6_addr, (uint8_t*)addr_bytes + JAVA_ARR_HDR, 16);
        *sslen = (int)sizeof(*sin6);
    }
    return 0;
}

/* =========== <clinit> natives =========== */

void __jnative_fn_sun_nio_ch_Net_initIDs___V(void) {}

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
    return (int16_t)POLLCONN;
}

int32_t __jnative_fn_sun_nio_ch_Net_isIPv6Available0___Z(void) {
    static int cached = -1;
    if (cached >= 0) return cached;

    jn_os_socket_t s = socket(AF_INET6, SOCK_STREAM, IPPROTO_TCP);
#if defined(_WIN32)
    if (s != JN_OS_INVALID_SOCKET) { closesocket(s); cached = 1; }
    else cached = 0;
#else
    if (s >= 0) { close(s); cached = 1; }
    else cached = 0;
#endif
    return cached;
}

/*
 * isReusePortAvailable0() boolean
 *
 * Whether the platform exposes a SO_REUSEPORT-style socket option that
 * lets multiple sockets bind to the same address and port with the
 * kernel load-balancing incoming connections.
 *
 * POSIX: true iff the build's <sys/socket.h> defines SO_REUSEPORT
 *        (Linux, macOS, and the BSDs all do). The old Linux kernel
 *        requirement was 3.9; any modern distribution satisfies it.
 * Windows: false. The Windows 10+ SO_REUSE_UNICASTPORT option is not
 *          equivalent to SO_REUSEPORT — it is single-socket exclusive
 *          and does not load-balance — and the JDK's own Windows
 *          implementation of this native returns false unconditionally.
 */
int32_t __jnative_fn_sun_nio_ch_Net_isReusePortAvailable0___Z(void) {
#if defined(_WIN32)
    return 0;
#else
#  if defined(SO_REUSEPORT)
    return 1;
#  else
    return 0;
#  endif
#endif
}

int32_t __jnative_fn_sun_nio_ch_Net_isExclusiveBindAvailable___I(void) {
#if defined(_WIN32)
    return 1;
#else
    return 0;
#endif
}

int32_t __jnative_fn_sun_nio_ch_Net_shouldSetBothIPv4AndIPv6Options0___Z(void) {
#if defined(_WIN32)
    return 1;
#else
    return 0;
#endif
}

int32_t __jnative_fn_sun_nio_ch_Net_canUseIPv6Only__Ljava_io_FileDescriptor_Z_Z(
        void* fdo, int32_t prefer_ipv6)
{
    (void)prefer_ipv6;
    jn_os_socket_t s = net_os_socket(fdo);
    struct sockaddr_storage ss;
    int len = (int)sizeof(ss);

#if defined(_WIN32)
    if (getsockname(s, (struct sockaddr*)&ss, &len) == SOCKET_ERROR) return 0;
#else
    if (getsockname(s, (struct sockaddr*)&ss, (socklen_t*)&len) < 0) return 0;
#endif
    return ss.ss_family == AF_INET6 ? 1 : 0;
}

/* =========== socket0 =========== */

int32_t __jnative_fn_sun_nio_ch_Net_socket0__ZZZZ_I(
        int32_t prefer_ipv6, int32_t stream, int32_t reuse, int32_t blocking)
{
    (void)reuse;

    int family = prefer_ipv6 ? AF_INET6 : AF_INET;
    int type   = stream ? SOCK_STREAM : SOCK_DGRAM;

    jn_os_socket_t s = socket(family, type, 0);

#if defined(_WIN32)
    if (s == JN_OS_INVALID_SOCKET) {
        jn_throw_socket_ioexception("socket", WSAGetLastError());
    }
    if (!blocking) {
        u_long mode = 1;
        if (ioctlsocket(s, FIONBIO, &mode) == SOCKET_ERROR) {
            int32_t e = WSAGetLastError();
            closesocket(s);
            jn_throw_socket_ioexception("ioctlsocket(FIONBIO)", e);
        }
    }
#else
    if (s < 0) {
        jn_throw_socket_ioexception("socket", errno);
    }
    if (!blocking) {
        int flags = fcntl(s, F_GETFL, 0);
        if (flags < 0 || fcntl(s, F_SETFL, flags | O_NONBLOCK) < 0) {
            int32_t e = errno;
            close(s);
            jn_throw_socket_ioexception("fcntl(O_NONBLOCK)", e);
        }
    }
#endif

    return (int32_t)(intptr_t)s;
}

/* =========== bind0 =========== */

void __jnative_fn_sun_nio_ch_Net_bind0__Ljava_io_FileDescriptor_ZZLjava_net_InetAddress_I_V(
        void* fdo, int32_t prefer_ipv6, int32_t use_excl_bind,
        void* ia, int32_t port)
{
    (void)prefer_ipv6;
    jn_os_socket_t s = net_os_socket(fdo);

    struct sockaddr_storage ss;
    int sslen = 0;
    net_sockaddr_from_inetaddress(ia, port, &ss, &sslen);

#if defined(_WIN32)
    if (use_excl_bind) {
        int one = 1;
        setsockopt(s, SOL_SOCKET, SO_EXCLUSIVEADDRUSE,
                   (const char*)&one, sizeof(one));
    }
    if (bind(s, (struct sockaddr*)&ss, sslen) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("bind", WSAGetLastError());
    }
#else
    (void)use_excl_bind;
    if (bind(s, (struct sockaddr*)&ss, (socklen_t)sslen) < 0) {
        jn_throw_socket_ioexception("bind", errno);
    }
#endif
}

/* =========== connect0 =========== */

int32_t __jnative_fn_sun_nio_ch_Net_connect0__Ljava_io_FileDescriptor_ZLjava_net_InetAddress_I_I(
        void* fdo, int32_t prefer_ipv6, void* ia, int32_t port)
{
    (void)prefer_ipv6;
    jn_os_socket_t s = net_os_socket(fdo);

    struct sockaddr_storage ss;
    int sslen = 0;
    net_sockaddr_from_inetaddress(ia, port, &ss, &sslen);

#if defined(_WIN32)
    if (connect(s, (struct sockaddr*)&ss, sslen) == SOCKET_ERROR) {
        int32_t e = WSAGetLastError();
        if (jn_sock_would_block(e))  return 1;
        if (jn_sock_interrupted(e))  return 0;
        jn_throw_socket_ioexception("connect", e);
    }
#else
    if (connect(s, (struct sockaddr*)&ss, (socklen_t)sslen) < 0) {
        int32_t e = errno;
        if (jn_sock_would_block(e))  return 1;
        if (jn_sock_interrupted(e))  return 0;
        jn_throw_socket_ioexception("connect", e);
    }
#endif
    return 0;
}

/* =========== accept0 =========== */

int32_t __jnative_fn_sun_nio_ch_Net_accept0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor__Ljava_net_InetSocketAddress__I(
        void* fdo, void* new_fdo, void* isaa_array)
{
    jn_os_socket_t s = net_os_socket(fdo);
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    int sslen = (int)sizeof(ss);

    jn_os_socket_t new_s;

#if defined(_WIN32)
    new_s = accept(s, (struct sockaddr*)&ss, &sslen);
    if (new_s == JN_OS_INVALID_SOCKET) {
        int32_t e = WSAGetLastError();
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("accept", e);
    }
#else
    new_s = accept(s, (struct sockaddr*)&ss, (socklen_t*)&sslen);
    if (new_s < 0) {
        int32_t e = errno;
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("accept", e);
    }
#endif

    net_set_fdval(new_fdo, (int32_t)(intptr_t)new_s);

    if (isaa_array != NULL) {
        void* ia = net_make_inet_address(&ss, NULL);
        if (ia != NULL) {
            void** slots = (void**)((char*)isaa_array + JAVA_ARR_HDR);
            slots[0] = ia;
        }
    }
    return 1;
}

/* =========== listen / shutdown =========== */

void __jnative_fn_sun_nio_ch_Net_listen__Ljava_io_FileDescriptor_I_V(
        void* fdo, int32_t backlog)
{
    jn_os_socket_t s = net_os_socket(fdo);
#if defined(_WIN32)
    if (listen(s, backlog) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("listen", WSAGetLastError());
    }
#else
    if (listen(s, backlog) < 0) {
        jn_throw_socket_ioexception("listen", errno);
    }
#endif
}

void __jnative_fn_sun_nio_ch_Net_shutdown__Ljava_io_FileDescriptor_I_V(
        void* fdo, int32_t howto)
{
    jn_os_socket_t s = net_os_socket(fdo);
    int sd;
    switch (howto) {
        case 0: sd = SHUT_RD;   break;
        case 1: sd = SHUT_WR;   break;
        case 2: sd = SHUT_RDWR; break;
        default: sd = SHUT_RDWR; break;
    }
#if defined(_WIN32)
    if (shutdown(s, sd) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("shutdown", WSAGetLastError());
    }
#else
    if (shutdown(s, sd) < 0) {
        jn_throw_socket_ioexception("shutdown", errno);
    }
#endif
}

/* =========== send / recv =========== */

int32_t __jnative_fn_sun_nio_ch_Net_send__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t address, int32_t len)
{
    jn_os_socket_t s = net_os_socket(fdo);
    void* buf = (void*)(intptr_t)address;

#if defined(_WIN32)
    int n = send(s, (const char*)buf, len, 0);
    if (n == SOCKET_ERROR) {
        int32_t e = WSAGetLastError();
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("send", e);
    }
    return (int32_t)n;
#else
    ssize_t n = send(s, buf, (size_t)len, 0);
    if (n < 0) {
        int32_t e = errno;
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("send", e);
    }
    return (int32_t)n;
#endif
}

int32_t __jnative_fn_sun_nio_ch_Net_recv__Ljava_io_FileDescriptor_JI_I(
        void* fdo, int64_t address, int32_t len)
{
    jn_os_socket_t s = net_os_socket(fdo);
    void* buf = (void*)(intptr_t)address;

#if defined(_WIN32)
    int n = recv(s, (char*)buf, len, 0);
    if (n == SOCKET_ERROR) {
        int32_t e = WSAGetLastError();
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("recv", e);
    }
    return (int32_t)n;
#else
    ssize_t n = recv(s, buf, (size_t)len, 0);
    if (n < 0) {
        int32_t e = errno;
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("recv", e);
    }
    return (int32_t)n;
#endif
}

/* =========== sendTo / recvFrom =========== */

int32_t __jnative_fn_sun_nio_ch_Net_sendTo__Ljava_io_FileDescriptor_JILjava_net_InetAddress_I_I(
        void* fdo, int64_t address, int32_t len, void* ia, int32_t port)
{
    jn_os_socket_t s = net_os_socket(fdo);
    const void* buf = (const void*)(intptr_t)address;
    struct sockaddr_storage ss;
    int sslen = 0;
    net_sockaddr_from_inetaddress(ia, port, &ss, &sslen);

#if defined(_WIN32)
    int n = sendto(s, (const char*)buf, len, 0,
                   (struct sockaddr*)&ss, sslen);
    if (n == SOCKET_ERROR) {
        int32_t e = WSAGetLastError();
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("sendto", e);
    }
    return (int32_t)n;
#else
    ssize_t n = sendto(s, buf, (size_t)len, 0,
                       (struct sockaddr*)&ss, (socklen_t)sslen);
    if (n < 0) {
        int32_t e = errno;
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("sendto", e);
    }
    return (int32_t)n;
#endif
}

int32_t __jnative_fn_sun_nio_ch_Net_recvFrom__Ljava_io_FileDescriptor_JILjava_net_InetSocketAddress__I(
        void* fdo, int64_t address, int32_t len, void* isaa_array)
{
    jn_os_socket_t s = net_os_socket(fdo);
    void* buf = (void*)(intptr_t)address;
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    int sslen = (int)sizeof(ss);
    int n;

#if defined(_WIN32)
    n = recvfrom(s, (char*)buf, len, 0, (struct sockaddr*)&ss, &sslen);
    if (n == SOCKET_ERROR) {
        int32_t e = WSAGetLastError();
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("recvfrom", e);
    }
#else
    n = (int)recvfrom(s, buf, (size_t)len, 0,
                      (struct sockaddr*)&ss, (socklen_t*)&sslen);
    if (n < 0) {
        int32_t e = errno;
        if (jn_sock_would_block(e)) return IOS_UNAVAILABLE;
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("recvfrom", e);
    }
#endif

    if (isaa_array != NULL) {
        void* ia = net_make_inet_address(&ss, NULL);
        if (ia != NULL) {
            void** slots = (void**)((char*)isaa_array + JAVA_ARR_HDR);
            slots[0] = ia;
        }
    }
    return (int32_t)n;
}

/* =========== sendOOB / recvOOB =========== */

int32_t __jnative_fn_sun_nio_ch_Net_sendOOB__Ljava_io_FileDescriptor_B_I(
        void* fdo, int8_t data)
{
    jn_os_socket_t s = net_os_socket(fdo);
    uint8_t b = (uint8_t)data;

#if defined(_WIN32)
    int n = send(s, (const char*)&b, 1, MSG_OOB);
    if (n == SOCKET_ERROR) jn_throw_socket_ioexception("send(OOB)", WSAGetLastError());
    return (int32_t)n;
#else
    ssize_t n = send(s, &b, 1, MSG_OOB);
    if (n < 0) jn_throw_socket_ioexception("send(OOB)", errno);
    return (int32_t)n;
#endif
}

int32_t __jnative_fn_sun_nio_ch_Net_recvOOB__Ljava_io_FileDescriptor__I(
        void* fdo)
{
    jn_os_socket_t s = net_os_socket(fdo);
    uint8_t b;

#if defined(_WIN32)
    int n = recv(s, (char*)&b, 1, MSG_OOB);
    if (n == SOCKET_ERROR) jn_throw_socket_ioexception("recv(OOB)", WSAGetLastError());
    if (n == 0) return -1;
    return (int32_t)b;
#else
    ssize_t n = recv(s, &b, 1, MSG_OOB);
    if (n < 0) jn_throw_socket_ioexception("recv(OOB)", errno);
    if (n == 0) return -1;
    return (int32_t)b;
#endif
}

/* =========== localInetAddress / remoteInetAddress / localPort =========== */

void* __jnative_fn_sun_nio_ch_Net_localInetAddress__Ljava_io_FileDescriptor__Ljava_net_InetAddress_(
        void* fdo)
{
    jn_os_socket_t s = net_os_socket(fdo);
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    int sslen = (int)sizeof(ss);

#if defined(_WIN32)
    if (getsockname(s, (struct sockaddr*)&ss, &sslen) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("getsockname", WSAGetLastError());
    }
#else
    if (getsockname(s, (struct sockaddr*)&ss, (socklen_t*)&sslen) < 0) {
        jn_throw_socket_ioexception("getsockname", errno);
    }
#endif
    return net_make_inet_address(&ss, NULL);
}

void* __jnative_fn_sun_nio_ch_Net_remoteInetAddress__Ljava_io_FileDescriptor__Ljava_net_InetAddress_(
        void* fdo)
{
    jn_os_socket_t s = net_os_socket(fdo);
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    int sslen = (int)sizeof(ss);

#if defined(_WIN32)
    if (getpeername(s, (struct sockaddr*)&ss, &sslen) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("getpeername", WSAGetLastError());
    }
#else
    if (getpeername(s, (struct sockaddr*)&ss, (socklen_t*)&sslen) < 0) {
        jn_throw_socket_ioexception("getpeername", errno);
    }
#endif
    return net_make_inet_address(&ss, NULL);
}

int32_t __jnative_fn_sun_nio_ch_Net_localPort__Ljava_io_FileDescriptor__I(
        void* fdo)
{
    jn_os_socket_t s = net_os_socket(fdo);
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    int sslen = (int)sizeof(ss);

#if defined(_WIN32)
    if (getsockname(s, (struct sockaddr*)&ss, &sslen) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("getsockname", WSAGetLastError());
    }
#else
    if (getsockname(s, (struct sockaddr*)&ss, (socklen_t*)&sslen) < 0) {
        jn_throw_socket_ioexception("getsockname", errno);
    }
#endif
    if (ss.ss_family == AF_INET6) {
        return (int32_t)ntohs(((struct sockaddr_in6*)&ss)->sin6_port);
    }
    return (int32_t)ntohs(((struct sockaddr_in*)&ss)->sin_port);
}

/* =========== socket options =========== */

/*
 * setIntOption0(FileDescriptor fd, boolean mayNeedConversion,
 *               int level, int opt, int arg, boolean isIPv6) throws IOException;
 */
void __jnative_fn_sun_nio_ch_Net_setIntOption0__Ljava_io_FileDescriptor_ZIIIZ_V(
        void* fdo, int32_t may_need_conversion,
        int32_t level, int32_t opt, int32_t arg, int32_t is_ipv6)
{
    (void)may_need_conversion;
    (void)is_ipv6;
    jn_os_socket_t s = net_os_socket(fdo);
#if defined(_WIN32)
    if (setsockopt(s, level, opt,
                   (const char*)&arg, sizeof(arg)) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("setsockopt", WSAGetLastError());
    }
#else
    if (setsockopt(s, level, opt, &arg, sizeof(arg)) < 0) {
        jn_throw_socket_ioexception("setsockopt", errno);
    }
#endif
}

int32_t __jnative_fn_sun_nio_ch_Net_getIntOption0__Ljava_io_FileDescriptor_ZII_I(
        void* fdo, int32_t may_need_conversion, int32_t level, int32_t opt)
{
    (void)may_need_conversion;
    jn_os_socket_t s = net_os_socket(fdo);
    int32_t arg = 0;
    int len = (int)sizeof(arg);

#if defined(_WIN32)
    if (getsockopt(s, level, opt, (char*)&arg, &len) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("getsockopt", WSAGetLastError());
    }
#else
    if (getsockopt(s, level, opt, &arg, (socklen_t*)&len) < 0) {
        jn_throw_socket_ioexception("getsockopt", errno);
    }
#endif
    return arg;
}

void __jnative_fn_sun_nio_ch_Net_setRawOption0__Ljava_io_FileDescriptor_II_BII_V(
        void* fdo, int32_t level, int32_t opt,
        void* value_array, int32_t offset, int32_t len)
{
    jn_os_socket_t s = net_os_socket(fdo);
    const void* value = value_array
        ? (const uint8_t*)value_array + JAVA_ARR_HDR + offset : NULL;
#if defined(_WIN32)
    if (setsockopt(s, level, opt,
                   (const char*)value, len) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("setsockopt", WSAGetLastError());
    }
#else
    if (setsockopt(s, level, opt, value, (socklen_t)len) < 0) {
        jn_throw_socket_ioexception("setsockopt", errno);
    }
#endif
}

int32_t __jnative_fn_sun_nio_ch_Net_getRawOption0__Ljava_io_FileDescriptor_II_BII_I(
        void* fdo, int32_t level, int32_t opt,
        void* value_array, int32_t offset, int32_t len)
{
    jn_os_socket_t s = net_os_socket(fdo);
    void* value = value_array
        ? (uint8_t*)value_array + JAVA_ARR_HDR + offset : NULL;
    int outlen = len;
#if defined(_WIN32)
    if (getsockopt(s, level, opt, (char*)value, &outlen) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("getsockopt", WSAGetLastError());
    }
#else
    if (getsockopt(s, level, opt, value, (socklen_t*)&outlen) < 0) {
        jn_throw_socket_ioexception("getsockopt", errno);
    }
#endif
    return (int32_t)outlen;
}

int32_t __jnative_fn_sun_nio_ch_Net_getsockoptLength0__Ljava_io_FileDescriptor_II_I(
        void* fdo, int32_t level, int32_t opt)
{
    jn_os_socket_t s = net_os_socket(fdo);
    int outlen = 0;
#if defined(_WIN32)
    if (getsockopt(s, level, opt, NULL, &outlen) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("getsockopt(length)", WSAGetLastError());
    }
#else
    if (getsockopt(s, level, opt, NULL, (socklen_t*)&outlen) < 0) {
        jn_throw_socket_ioexception("getsockopt(length)", errno);
    }
#endif
    return (int32_t)outlen;
}

/* =========== available =========== */

int32_t __jnative_fn_sun_nio_ch_Net_available__Ljava_io_FileDescriptor__I(
        void* fdo)
{
    jn_os_socket_t s = net_os_socket(fdo);
    int avail = 0;

#if defined(_WIN32)
    u_long n = 0;
    if (ioctlsocket(s, FIONREAD, &n) == SOCKET_ERROR) {
        jn_throw_socket_ioexception("ioctlsocket(FIONREAD)", WSAGetLastError());
    }
    avail = (int)n;
#else
    if (ioctl(s, FIONREAD, &avail) < 0) {
        jn_throw_socket_ioexception("ioctl(FIONREAD)", errno);
    }
#endif
    return avail;
}

/* =========== close =========== */

void __jnative_fn_sun_nio_ch_Net_close__Ljava_io_FileDescriptor__V(
        void* fdo)
{
    jn_os_socket_t s = net_os_socket(fdo);
#if defined(_WIN32)
    closesocket(s);
#else
    close(s);
#endif
    net_set_fdval(fdo, -1);
}

/* =========== poll =========== */

int32_t __jnative_fn_sun_nio_ch_Net_poll__Ljava_io_FileDescriptor_IJ_I(
        void* fdo, int32_t events, int64_t timeout)
{
    jn_os_socket_t s = net_os_socket(fdo);

    int ms;
    if (timeout < 0) {
        ms = -1;
    } else if (timeout > INT32_MAX) {
        ms = INT32_MAX;
    } else {
        ms = (int)timeout;
    }

#if defined(_WIN32)
    WSAPOLLFD pfd;
    pfd.fd      = s;
    pfd.events  = (short)events;
    pfd.revents = 0;
    int rc = WSAPoll(&pfd, 1, ms);
    if (rc < 0) {
        int32_t e = WSAGetLastError();
        if (jn_sock_interrupted(e)) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("WSAPoll", e);
    }
    return rc > 0 ? (int32_t)pfd.revents : 0;
#else
    struct pollfd pfd;
    pfd.fd      = s;
    pfd.events  = (short)events;
    pfd.revents = 0;
    int rc = poll(&pfd, 1, ms);
    if (rc < 0) {
        if (errno == EINTR) return IOS_INTERRUPTED;
        jn_throw_socket_ioexception("poll", errno);
    }
    return rc > 0 ? (int32_t)pfd.revents : 0;
#endif
}

/* =========== isLoopback =========== */

int32_t __jnative_fn_sun_nio_ch_Net_isLoopback__Ljava_io_FileDescriptor__Z(
        void* fdo)
{
    jn_os_socket_t s = net_os_socket(fdo);
    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    int sslen = (int)sizeof(ss);

#if defined(_WIN32)
    if (getsockname(s, (struct sockaddr*)&ss, &sslen) == SOCKET_ERROR) return 0;
#else
    if (getsockname(s, (struct sockaddr*)&ss, (socklen_t*)&sslen) < 0) return 0;
#endif

    int fam = (int)ss.ss_family;
    if (fam == AF_INET) {
        uint32_t a = ntohl(((const struct sockaddr_in*)&ss)->sin_addr.s_addr);
        return ((a >> 24) == 127) ? 1 : 0;
    }
    if (fam == AF_INET6) {
        static const uint8_t LO[16] = {0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,1};
        const uint8_t* p = (const uint8_t*)
            &((const struct sockaddr_in6*)&ss)->sin6_addr;
        return memcmp(p, LO, 16) == 0 ? 1 : 0;
    }
    return 0;
}