#define _GNU_SOURCE
#include <stdint.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>

static int __jnative_inet4_init_done = 0;

void __jnative_fn_java_net_Inet4Address_init___V(void) {
    if (__jnative_inet4_init_done) {
        return;
    }

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd >= 0) {
        close(fd);
    }

    __jnative_inet4_init_done = 1;
}