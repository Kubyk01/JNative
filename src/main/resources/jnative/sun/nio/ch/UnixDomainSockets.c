#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <winsock2.h>
#  include <ws2tcpip.h>
#  include <afunix.h>
typedef SOCKET uds_socket_t;
#  define UDS_INVALID_SOCKET INVALID_SOCKET
#else
#  include <sys/types.h>
#  include <sys/socket.h>
#  include <sys/un.h>
#  include <unistd.h>
typedef int uds_socket_t;
#  define UDS_INVALID_SOCKET (-1)
#endif

#include "jnative_runtime.h"

#define UDS_IOS_UNAVAILABLE (-2)
#define UDS_IOS_INTERRUPTED (-3)

static int32_t uds_errno(void) {
#if defined(_WIN32)
    return (int32_t)WSAGetLastError();
#else
    return (int32_t)errno;
#endif
}

static const char* uds_exception_class(int32_t err) {
#if defined(_WIN32)
    switch (err) {
        case WSAEADDRINUSE:
        case WSAEADDRNOTAVAIL:
        case WSAEACCES:
            return "vtable_java_net_BindException";
        case WSAECONNREFUSED:
        case WSAETIMEDOUT:
        case WSAENOTCONN:
            return "vtable_java_net_ConnectException";
        default:
            return "vtable_java_net_SocketException";
    }
#else
    switch (err) {
        case EADDRINUSE:
        case EADDRNOTAVAIL:
        case EACCES:
            return "vtable_java_net_BindException";
        case ECONNREFUSED:
        case ETIMEDOUT:
        case ENOTCONN:
            return "vtable_java_net_ConnectException";
        default:
            return "vtable_java_net_SocketException";
    }
#endif
}

JNATIVE_NORETURN static void uds_throw(const char* exception_vtable, const char* msg) {
    void* exc = __jnative_construct_exception(exception_vtable, msg);
    if (exc == NULL) exc = __jnative_construct_exception("vtable_java_io_IOException", msg);
    __jnative_throw_exception(exc);
}

JNATIVE_NORETURN static void uds_throw_error(const char* what, int32_t err) {
    char msg[512];
#if defined(_WIN32)
    char sysbuf[256];
    DWORD n = FormatMessageA(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                             NULL, (DWORD)err, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
                             sysbuf, (DWORD)sizeof(sysbuf), NULL);
    while (n > 0 && (sysbuf[n - 1] == '\r' || sysbuf[n - 1] == '\n' || sysbuf[n - 1] == ' ')) {
        sysbuf[--n] = '\0';
    }
    if (n > 0) snprintf(msg, sizeof(msg), "%s: %s", what, sysbuf);
    else snprintf(msg, sizeof(msg), "%s failed (WSA error %d)", what, (int)err);
#else
    snprintf(msg, sizeof(msg), "%s: %s", what, strerror((int)err));
#endif
    uds_throw(uds_exception_class(err), msg);
}

static uds_socket_t uds_fd(void* fdo) {
    if (fdo == NULL) __jnative_throw_null_pointer_exception();
    int32_t fd = jnative_fd_of(fdo);
    if (fd < 0) uds_throw("vtable_java_net_SocketException", "Socket closed");
    return (uds_socket_t)(uintptr_t)(intptr_t)fd;
}

static socklen_t uds_sockaddr(void* path, struct sockaddr_un* sa) {
    if (path == NULL) __jnative_throw_null_pointer_exception();
    int32_t len = jnative_array_length(path);
    memset(sa, 0, sizeof(*sa));
    sa->sun_family = AF_UNIX;
    if (len < 0 || (size_t)len >= sizeof(sa->sun_path)) {
        uds_throw("vtable_java_io_IOException", "Unix domain path too long");
    }
    memcpy(sa->sun_path, jnative_array_data(path), (size_t)len);
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + (size_t)len + 1);
}

static void* uds_address_bytes(const struct sockaddr_un* sa, socklen_t sa_len) {
    size_t header = offsetof(struct sockaddr_un, sun_path);
    if ((size_t)sa_len <= header || sa->sun_path[0] == '\0') {
        return jnative_byte_array("", 0);
    }
    size_t max = (size_t)sa_len - header;
    if (max > sizeof(sa->sun_path)) max = sizeof(sa->sun_path);
    size_t len = 0;
    while (len < max && sa->sun_path[len] != '\0') len++;
    return jnative_byte_array(sa->sun_path, (int32_t)len);
}

int32_t __jnative_fn_sun_nio_ch_UnixDomainSockets_init___Z(void) {
#if defined(_WIN32)
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return 0;
    SOCKET s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s == INVALID_SOCKET) return 0;
    closesocket(s);
    return 1;
#else
    return 1;
#endif
}

int32_t __jnative_fn_sun_nio_ch_UnixDomainSockets_socket0___I(void) {
    uds_socket_t s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s == UDS_INVALID_SOCKET) uds_throw_error("socket", uds_errno());
#if defined(_WIN32)
    SetHandleInformation((HANDLE)s, HANDLE_FLAG_INHERIT, 0);
#endif
    return (int32_t)(intptr_t)s;
}

void __jnative_fn_sun_nio_ch_UnixDomainSockets_bind0__Ljava_io_FileDescriptor__B_V(void* fdo, void* path) {
    struct sockaddr_un sa;
    socklen_t sa_len = uds_sockaddr(path, &sa);
    if (bind(uds_fd(fdo), (struct sockaddr*)&sa, sa_len) != 0) {
        uds_throw_error("Bind", uds_errno());
    }
}

int32_t __jnative_fn_sun_nio_ch_UnixDomainSockets_connect0__Ljava_io_FileDescriptor__B_I(void* fdo, void* path) {
    struct sockaddr_un sa;
    socklen_t sa_len = uds_sockaddr(path, &sa);
    if (connect(uds_fd(fdo), (struct sockaddr*)&sa, sa_len) != 0) {
        int32_t err = uds_errno();
#if defined(_WIN32)
        if (err == WSAEWOULDBLOCK || err == WSAEINPROGRESS) return UDS_IOS_UNAVAILABLE;
#else
        if (err == EINPROGRESS) return UDS_IOS_UNAVAILABLE;
        if (err == EINTR) return UDS_IOS_INTERRUPTED;
#endif
        uds_throw_error("Connect", err);
    }
    return 1;
}

int32_t __jnative_fn_sun_nio_ch_UnixDomainSockets_accept0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor__Ljava_lang_Object__I(
        void* fdo, void* newfdo, void* array) {
    if (newfdo == NULL) __jnative_throw_null_pointer_exception();
    struct sockaddr_un sa;
    socklen_t sa_len = (socklen_t)sizeof(sa);
    memset(&sa, 0, sizeof(sa));
    uds_socket_t s = accept(uds_fd(fdo), (struct sockaddr*)&sa, &sa_len);
    if (s == UDS_INVALID_SOCKET) {
        int32_t err = uds_errno();
#if defined(_WIN32)
        if (err == WSAEWOULDBLOCK) return UDS_IOS_UNAVAILABLE;
#else
        if (err == EAGAIN || err == EWOULDBLOCK) return UDS_IOS_UNAVAILABLE;
        if (err == EINTR) return UDS_IOS_INTERRUPTED;
#endif
        uds_throw_error("Accept", err);
    }
#if defined(_WIN32)
    SetHandleInformation((HANDLE)s, HANDLE_FLAG_INHERIT, 0);
#endif
    *(int32_t*)((char*)newfdo + FD_OFFSET) = (int32_t)(intptr_t)s;
    if (array != NULL && jnative_array_length(array) > 0) {
        ((void**)jnative_array_data(array))[0] = uds_address_bytes(&sa, sa_len);
    }
    return 1;
}

void* __jnative_fn_sun_nio_ch_UnixDomainSockets_localAddress0__Ljava_io_FileDescriptor___B(void* fdo) {
    struct sockaddr_un sa;
    socklen_t sa_len = (socklen_t)sizeof(sa);
    memset(&sa, 0, sizeof(sa));
    if (getsockname(uds_fd(fdo), (struct sockaddr*)&sa, &sa_len) != 0) {
        uds_throw_error("getsockname", uds_errno());
    }
    return uds_address_bytes(&sa, sa_len);
}
