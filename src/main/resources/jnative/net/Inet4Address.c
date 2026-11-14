#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>

#include "jnative_runtime.h"

static int __jnative_inet4_init_done = 0;

void __jnative_fn_java_net_Inet4Address_init___V(void) {
    if (__jnative_inet4_init_done) {
        return;
    }

    jn_socket_t fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd != JN_INVALID_SOCKET) {
        jn_close_socket(fd);
    }

    __jnative_inet4_init_done = 1;
}
