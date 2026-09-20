#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/* The traditional fallback range, matching the reference implementation. */
#define PORT_CONFIG_DEFAULT_LOWER 32768
#define PORT_CONFIG_DEFAULT_UPPER 65535

/* The procfs entry that holds the current range on Linux. */
#define PORT_CONFIG_PROC_PATH "/proc/sys/net/ipv4/ip_local_port_range"

static int read_port_range(int32_t* lower, int32_t* upper) {
    FILE* f = fopen(PORT_CONFIG_PROC_PATH, "r");
    if (f == NULL) {
        *lower = PORT_CONFIG_DEFAULT_LOWER;
        *upper = PORT_CONFIG_DEFAULT_UPPER;
        return 0;
    }

    long lo = 0;
    long hi = 0;
    int matched = fscanf(f, "%ld %ld", &lo, &hi);
    fclose(f);

    /* Reject anything that is not exactly two integers, or that falls
     * outside the valid TCP/UDP port range. A port number is 0..65535;
     * the ephemeral range is further constrained to non-zero ports by
     * the kernel, but a zero lower bound is technically observable in
     * some configurations and is not by itself grounds for rejection.
     */
    if (matched != 2
        || lo < 0 || lo > 65535
        || hi < 0 || hi > 65535
        || lo > hi) {
        *lower = PORT_CONFIG_DEFAULT_LOWER;
        *upper = PORT_CONFIG_DEFAULT_UPPER;
        return 0;
    }

    *lower = (int32_t)lo;
    *upper = (int32_t)hi;
    return 1;
}

/* --------------------------------------------------------------------------
 * private static native int getLower0();
 *
 * Returns the lowest ephemeral port number the kernel will assign to a
 * socket that binds without specifying a port. Called from
 * PortConfig.<clinit> and cached in the class's static `lower` field.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_sun_net_PortConfig_getLower0___I(void) {
    int32_t lower = PORT_CONFIG_DEFAULT_LOWER;
    int32_t upper = PORT_CONFIG_DEFAULT_UPPER;
    (void)read_port_range(&lower, &upper);
    return lower;
}

/* --------------------------------------------------------------------------
 * private static native int getUpper0();
 *
 * Returns the highest ephemeral port number the kernel will assign to a
 * socket that binds without specifying a port. Called from
 * PortConfig.<clinit> and cached in the class's static `upper` field.
 * ------------------------------------------------------------------------ */
int32_t __jnative_fn_sun_net_PortConfig_getUpper0___I(void) {
    int32_t lower = PORT_CONFIG_DEFAULT_LOWER;
    int32_t upper = PORT_CONFIG_DEFAULT_UPPER;
    (void)read_port_range(&lower, &upper);
    return upper;
}