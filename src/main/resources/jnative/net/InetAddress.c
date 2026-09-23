#define _GNU_SOURCE
#include <stdint.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>

#include "jnative_runtime.h"

/*
 * java.net.InetAddress — the family-agnostic entry points of the
 * InetAddress hierarchy.
 *
 * The class declares three natives:
 *
 *   init()               — one-shot support probe, called from <clinit>
 *   isIPv4Available()    — reports whether AF_INET sockets can be opened
 *   isIPv6Supported()    — reports whether AF_INET6 sockets can be opened
 *
 * The two predicates are used by the Java layer to decide which address
 * families to expose in the resolver's lookup policy, and by the
 * InetAddress.getByName / getAllByName paths to select the appropriate
 * concrete implementation (Inet4AddressImpl or Inet6AddressImpl).
 *
 * All three probes are pure capability checks against the kernel: they
 * open the relevant socket type once and remember the answer. The
 * results are process-wide facts that do not change while the process
 * runs, so each probe is paid exactly once and the answer is then
 * cached in a file-static variable.
 *
 * The dual-stack probe exists in addition to the two single-family
 * probes because the Java layer uses its result to decide whether an
 * AF_INET6 socket can serve IPv4 traffic as well (the IPV6_V6ONLY=0
 * case), which is the difference between a truly dual-stack listener
 * and a v6-only one.
 */

static int __jnative_ipv4_probe_result = -1;
static int __jnative_ipv6_probe_result = -1;
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
 * whether to expose IPv4 entries in the resolver's lookup policy and
 * whether to dispatch to Inet4AddressImpl.
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
 * whether to expose IPv6 entries in the resolver's lookup policy and
 * whether to dispatch to Inet6AddressImpl.
 */
int32_t __jnative_fn_java_net_InetAddress_isIPv6Supported___Z(void) {
    if (__jnative_ipv6_probe_result < 0) {
        __jnative_ipv6_probe_result = probe_ipv6_socket();
    }
    return __jnative_ipv6_probe_result ? 1 : 0;
}