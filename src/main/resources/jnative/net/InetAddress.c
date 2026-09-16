#define _GNU_SOURCE
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <string.h>

static int __jnative_ipv6_probe_result = -1;
static int __jnative_ipv4_probe_result = -1;
static int __jnative_dual_stack_probe_result = -1;

static int probe_ipv4_socket(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        return 0;
    }
    close(fd);
    return 1;
}

static int probe_ipv6_socket(void) {
    int fd = socket(AF_INET6, SOCK_STREAM, 0);
    if (fd < 0) {
        return 0;
    }
    close(fd);
    return 1;
}

static int probe_dual_stack_socket(void) {
    int fd = socket(AF_INET6, SOCK_STREAM, 0);
    if (fd < 0) {
        return 0;
    }
    int v6only = 0;
    socklen_t optlen = sizeof(v6only);
    int result = 0;
    if (getsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &v6only, &optlen) == 0) {
        result = (v6only == 0) ? 1 : 0;
    }
    close(fd);
    return result;
}

void __jnative_fn_java_net_InetAddress_init___V(void) {
    if (__jnative_ipv4_probe_result < 0) {
        __jnative_ipv4_probe_result = probe_ipv4_socket();
    }
    if (__jnative_ipv6_probe_result < 0) {
        __jnative_ipv6_probe_result = probe_ipv6_socket();
    }
    if (__jnative_dual_stack_probe_result < 0) {
        __jnative_dual_stack_probe_result = probe_dual_stack_socket();
    }
}

/*
 * private static native boolean isIPv4Available();
 *
 * True iff this process can open an AF_INET socket. The probe attempts
 * exactly that once and memoises the answer; a negative cached value
 * means the probe has not yet run. Callers use the result to decide
 * whether to expose IPv4 entries in the resolver's lookup policy.
 */
int32_t __jnative_fn_java_net_InetAddress_isIPv4Available___Z(void) {
    if (__jnative_ipv4_probe_result < 0) {
        __jnative_ipv4_probe_result = probe_ipv4_socket();
    }
    return __jnative_ipv4_probe_result ? 1 : 0;
}

/*
 * private static native boolean isIPv6Supported();
 *
 * True iff this process can open an AF_INET6 socket. The probe attempts
 * exactly that once and memoises the answer; a negative cached value
 * means the probe has not yet run. Callers use the result to decide
 * whether to expose IPv6 entries in the resolver's lookup policy.
 */
int32_t __jnative_fn_java_net_InetAddress_isIPv6Supported___Z(void) {
    if (__jnative_ipv6_probe_result < 0) {
        __jnative_ipv6_probe_result = probe_ipv6_socket();
    }
    return __jnative_ipv6_probe_result ? 1 : 0;
}