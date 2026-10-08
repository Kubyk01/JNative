/*
 * sun.net.sdp.SdpSupport
 *
 * SDP (Sockets Direct Protocol) is a Solaris-specific sockets-over-
 * InfiniBand transport. The reference JDK implements the class in
 * src/java.base/solaris/native/sun/net/sdp/SdpSupport.c, which is only
 * compiled on Solaris. On Linux the class's static initializer checks
 * `isSolaris` and sets `supported` to false, so no code path in the
 * JDK ever calls the native methods. The emitted IR still contains the
 * declarations because the bytecode of the Java-side
 * SdpSupport.isSupported() references them, and the linker requires a
 * body for each.
 *
 * Both entry points therefore throw a RuntimeException on any platform
 * where they actually run. This is truthful: an image built on Linux
 * cannot speak SDP, and calling into it is a programming error at the
 * Java level. It also keeps the linking honest — the symbols exist,
 * and their behaviour is defined.
 */

#include <stdint.h>

#include "jnative_runtime.h"

void __jnative_fn_sun_net_sdp_SdpSupport_convert0__I_V(int32_t fd) {
    (void)fd;
    __jnative_throw_exception(NULL);
}

int32_t __jnative_fn_sun_net_sdp_SdpSupport_create0___I(void) {
    __jnative_throw_exception(NULL);
    return -1;
}
