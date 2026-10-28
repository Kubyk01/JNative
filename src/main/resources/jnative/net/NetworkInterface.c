#define _GNU_SOURCE

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  ifndef _WIN32_WINNT
#    define _WIN32_WINNT 0x0600
#  endif
#  include <winsock2.h>
#  include <ws2tcpip.h>
#  include <iphlpapi.h>
#  include <windows.h>
#  include <winnls.h>
#  include <io.h>

static const char* adapter_label(PIP_ADAPTER_ADDRESSES p,
                                 char* buf, size_t buf_size) {
    if (p->FriendlyName != NULL && p->FriendlyName[0] != 0) {
        int n = WideCharToMultiByte(CP_UTF8, 0, p->FriendlyName, -1,
                                    buf, (int)buf_size, NULL, NULL);
        if (n > 0) return buf;
    }
    return p->AdapterName ? p->AdapterName : "";
}
#else
#  include <unistd.h>
#  include <stdio.h>
#  include <stdlib.h>
#  include <string.h>
#  include <errno.h>
#  include <net/if.h>
#  include <net/if_arp.h>
#  include <ifaddrs.h>
#  include <sys/ioctl.h>
#  include <sys/socket.h>
#  include <netinet/in.h>
#  include <arpa/inet.h>
#endif

#include "jnative_platform.h"
#include <stdint.h>

#include "jnative_runtime.h"

extern void* gv_java_net_NetworkInterface_name_0        __attribute__((weak));
extern void* gv_java_net_NetworkInterface_displayName_0 __attribute__((weak));
extern void* gv_java_net_NetworkInterface_index_0       __attribute__((weak));
extern void* gv_java_net_NetworkInterface_addrs_0       __attribute__((weak));
extern void* gv_java_net_NetworkInterface_bindings_0    __attribute__((weak));
extern void* gv_java_net_NetworkInterface_childs_0      __attribute__((weak));

/* ============================================================================
 *  Helpers shared by both platforms.
 * ========================================================================== */

static void* make_java_string_array(int count, char** strings) {
    void* arr = jnative_ref_array_of_class(NULL, count, "[Ljava/lang/String;");
    if (!arr) return NULL;
    void** slots = (void**)((char*)arr + JAVA_ARR_HDR);
    for (int i = 0; i < count; i++) {
        slots[i] = strings[i] ? __jnative_make_string_obj(strings[i], (int32_t)strlen(strings[i])) : NULL;
    }
    return arr;
}

static void set_all_empty(void) {
    if (&gv_java_net_NetworkInterface_name_0 != NULL)
        gv_java_net_NetworkInterface_name_0        = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_displayName_0 != NULL)
        gv_java_net_NetworkInterface_displayName_0 = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_index_0 != NULL)
        gv_java_net_NetworkInterface_index_0       = jnative_int_array(NULL, 0);
    if (&gv_java_net_NetworkInterface_addrs_0 != NULL)
        gv_java_net_NetworkInterface_addrs_0       = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_bindings_0 != NULL)
        gv_java_net_NetworkInterface_bindings_0    = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_childs_0 != NULL)
        gv_java_net_NetworkInterface_childs_0      = jnative_empty_ref_array();
}

static void* allocate_network_interface(const char* name, const char* display_name, int32_t index) {
    ReflectionClass* ni_cls = jnative_class_by_name("java/net/NetworkInterface");
    if (!ni_cls) return NULL;
    int size = ni_cls->object_size > 0 ? ni_cls->object_size : 64;
    void* obj = calloc(1, (size_t)size);
    if (!obj) return NULL;
    void* vt = jnative_lookup_class_vtable(ni_cls);
    if (!vt) { free(obj); return NULL; }
    *(void**)obj = vt;
    *(void**)((char*)obj + 8)  = __jnative_make_string_obj(name, (int32_t)strlen(name));
    *(void**)((char*)obj + 16) = __jnative_make_string_obj(display_name, (int32_t)strlen(display_name));
    *(int32_t*)((char*)obj + 24) = index;
    *(void**)((char*)obj + 32) = jnative_empty_ref_array();
    *(void**)((char*)obj + 40) = jnative_empty_ref_array();
    *(void**)((char*)obj + 48) = jnative_empty_ref_array();
    return obj;
}

/* ============================================================================
 *  init — enumerate local interfaces.
 * ========================================================================== */

void __jnative_fn_java_net_NetworkInterface_init___V(void) {

#if defined(_WIN32)

    ULONG size = 15000;
    PIP_ADAPTER_ADDRESSES addrs = NULL;

    for (int attempt = 0; attempt < 3; attempt++) {
        addrs = (PIP_ADAPTER_ADDRESSES)malloc(size);
        if (!addrs) { set_all_empty(); return; }
        ULONG rc = GetAdaptersAddresses(
            AF_UNSPEC,
            GAA_FLAG_INCLUDE_PREFIX | GAA_FLAG_SKIP_ANYCAST
                | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
            NULL, addrs, &size);
        if (rc == NO_ERROR) break;
        free(addrs);
        addrs = NULL;
        if (rc != ERROR_BUFFER_OVERFLOW) { set_all_empty(); return; }
    }
    if (!addrs) { set_all_empty(); return; }

    int count = 0;
    for (PIP_ADAPTER_ADDRESSES p = addrs; p; p = p->Next) {
        if (p->AdapterName && p->AdapterName[0]) count++;
    }
    if (count == 0) { free(addrs); set_all_empty(); return; }

    char** names = (char**)calloc((size_t)count, sizeof(char*));
    char** display = (char**)calloc((size_t)count, sizeof(char*));
    int32_t* indices = (int32_t*)calloc((size_t)count, sizeof(int32_t));
    if (!names || !display || !indices) {
        free(names); free(display); free(indices); free(addrs);
        set_all_empty(); return;
    }

    int i = 0;
    for (PIP_ADAPTER_ADDRESSES p = addrs; p && i < count; p = p->Next) {
        if (!p->AdapterName || !p->AdapterName[0]) continue;
        /* FriendlyName is the human-readable label (e.g. "Ethernet").
         * AdapterName is the GUID. We use FriendlyName for both the
         * Java-level name and displayName, matching the POSIX path
         * (where ifa_name is a single label that serves both). */
        char label_buf[256];
        const char* label = adapter_label(p, label_buf, sizeof(label_buf));
        names[i]   = strdup(label);
        display[i] = strdup(label);
        indices[i] = (int32_t)p->IfIndex;
        i++;
    }
    count = i;

    /* Stable insertion-sort by IfIndex — same order the POSIX path uses. */
    for (int a = 1; a < count; a++) {
        int32_t k = indices[a];
        char* kn = names[a];
        char* kd = display[a];
        int b = a - 1;
        while (b >= 0 && indices[b] > k) {
            indices[b+1] = indices[b];
            names[b+1]   = names[b];
            display[b+1] = display[b];
            b--;
        }
        indices[b+1] = k;
        names[b+1]   = kn;
        display[b+1] = kd;
    }

    if (&gv_java_net_NetworkInterface_name_0 != NULL)
        gv_java_net_NetworkInterface_name_0        = make_java_string_array(count, names);
    if (&gv_java_net_NetworkInterface_displayName_0 != NULL)
        gv_java_net_NetworkInterface_displayName_0 = make_java_string_array(count, display);
    if (&gv_java_net_NetworkInterface_index_0 != NULL)
        gv_java_net_NetworkInterface_index_0       = jnative_int_array(indices, count);
    if (&gv_java_net_NetworkInterface_addrs_0 != NULL)
        gv_java_net_NetworkInterface_addrs_0       = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_bindings_0 != NULL)
        gv_java_net_NetworkInterface_bindings_0    = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_childs_0 != NULL)
        gv_java_net_NetworkInterface_childs_0      = jnative_empty_ref_array();

    for (int k = 0; k < count; k++) { free(names[k]); free(display[k]); }
    free(names); free(display); free(indices); free(addrs);

#else /* POSIX */

    struct ifaddrs* ifaddr = NULL;
    if (getifaddrs(&ifaddr) != 0) { set_all_empty(); return; }

    char** names = NULL; char** display = NULL; int32_t* indices = NULL;
    int count = 0, capacity = 0;

    for (struct ifaddrs* ifa = ifaddr; ifa; ifa = ifa->ifa_next) {
        if (!ifa->ifa_name) continue;
        int dup = 0;
        for (int k = 0; k < count; k++) if (strcmp(names[k], ifa->ifa_name) == 0) { dup = 1; break; }
        if (dup) continue;

        if (count == capacity) {
            capacity = capacity ? capacity * 2 : 16;
            char** nn  = (char**)realloc(names,   (size_t)capacity * sizeof(char*));
            char** dn  = (char**)realloc(display, (size_t)capacity * sizeof(char*));
            int32_t* ix = (int32_t*)realloc(indices, (size_t)capacity * sizeof(int32_t));
            if (!nn || !dn || !ix) {
                free(nn); free(dn); free(ix);
                for (int k = 0; k < count; k++) { free(names[k]); free(display[k]); }
                free(names); free(display); free(indices);
                freeifaddrs(ifaddr); set_all_empty(); return;
            }
            names = nn; display = dn; indices = ix;
        }
        names[count]   = strdup(ifa->ifa_name);
        display[count] = strdup(ifa->ifa_name);
        indices[count] = (int32_t)if_nametoindex(ifa->ifa_name);
        if (!names[count] || !display[count]) {
            free(names[count]); free(display[count]);
            for (int k = 0; k < count; k++) { free(names[k]); free(display[k]); }
            free(names); free(display); free(indices);
            freeifaddrs(ifaddr); set_all_empty(); return;
        }
        count++;
    }
    freeifaddrs(ifaddr);

    for (int a = 1; a < count; a++) {
        int32_t k = indices[a];
        char* kn = names[a]; char* kd = display[a];
        int b = a - 1;
        while (b >= 0 && indices[b] > k) {
            indices[b+1] = indices[b]; names[b+1] = names[b]; display[b+1] = display[b]; b--;
        }
        indices[b+1] = k; names[b+1] = kn; display[b+1] = kd;
    }

    if (&gv_java_net_NetworkInterface_name_0 != NULL)
        gv_java_net_NetworkInterface_name_0        = make_java_string_array(count, names);
    if (&gv_java_net_NetworkInterface_displayName_0 != NULL)
        gv_java_net_NetworkInterface_displayName_0 = make_java_string_array(count, display);
    if (&gv_java_net_NetworkInterface_index_0 != NULL)
        gv_java_net_NetworkInterface_index_0       = jnative_int_array(indices, count);
    if (&gv_java_net_NetworkInterface_addrs_0 != NULL)
        gv_java_net_NetworkInterface_addrs_0       = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_bindings_0 != NULL)
        gv_java_net_NetworkInterface_bindings_0    = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_childs_0 != NULL)
        gv_java_net_NetworkInterface_childs_0      = jnative_empty_ref_array();

    for (int k = 0; k < count; k++) { free(names[k]); free(display[k]); }
    free(names); free(display); free(indices);

#endif
}

/* ============================================================================
 *  getByName0
 * ========================================================================== */

void* __jnative_fn_java_net_NetworkInterface_getByName0__Ljava_lang_String__Ljava_net_NetworkInterface_(
        void* name_str)
{
    if (!name_str) { __jnative_throw_null_pointer_exception(); return NULL; }

    int32_t req_len = 0;
    const char* req = __jnative_read_string_bytes(name_str, &req_len);
    if (!req || req_len <= 0) return NULL;

    void* namesArr   = gv_java_net_NetworkInterface_name_0;
    void* displayArr = gv_java_net_NetworkInterface_displayName_0;
    void* indicesArr = gv_java_net_NetworkInterface_index_0;
    if (!namesArr || !displayArr || !indicesArr) return NULL;

    int32_t count = jnative_array_length(namesArr);
    if (count <= 0) return NULL;

    void** nameSlots    = (void**)jnative_array_data(namesArr);
    void** displaySlots = (void**)jnative_array_data(displayArr);
    int32_t* idxSlots   = (int32_t*)jnative_array_data(indicesArr);

    for (int32_t i = 0; i < count; i++) {
        int32_t cur_len = 0;
        const char* cur = nameSlots[i]
            ? __jnative_read_string_bytes(nameSlots[i], &cur_len)
            : NULL;
        if (cur && cur_len == req_len && memcmp(cur, req, (size_t)req_len) == 0) {
            const char* disp = displaySlots[i]
                ? __jnative_read_string_bytes(displaySlots[i], NULL) : "";
            return allocate_network_interface(cur, disp ? disp : "", idxSlots[i]);
        }
    }
    return NULL;
}

/* ============================================================================
 *  getAll
 * ========================================================================== */

void* __jnative_fn_java_net_NetworkInterface_getAll____Ljava_net_NetworkInterface_(void) {
    void* namesArr   = gv_java_net_NetworkInterface_name_0;
    void* displayArr = gv_java_net_NetworkInterface_displayName_0;
    void* indicesArr = gv_java_net_NetworkInterface_index_0;
    if (!namesArr || !displayArr || !indicesArr) return jnative_empty_ref_array();

    int32_t count = jnative_array_length(namesArr);
    if (count <= 0) return jnative_empty_ref_array();

    void** nameSlots    = (void**)jnative_array_data(namesArr);
    void** displaySlots = (void**)jnative_array_data(displayArr);
    int32_t* idxSlots   = (int32_t*)jnative_array_data(indicesArr);

    void* arr = jnative_ref_array_of_class(NULL, count,
                                            "[Ljava/net/NetworkInterface;");
    if (!arr) return jnative_empty_ref_array();
    void** slots = (void**)((char*)arr + JAVA_ARR_HDR);

    for (int32_t i = 0; i < count; i++) {
        const char* nm  = nameSlots[i]    ? __jnative_read_string_bytes(nameSlots[i], NULL)    : "";
        const char* dsp = displaySlots[i] ? __jnative_read_string_bytes(displaySlots[i], NULL) : "";
        slots[i] = allocate_network_interface(nm ? nm : "", dsp ? dsp : "", idxSlots[i]);
    }
    return arr;
}

/* ============================================================================
 *  isLoopback0
 * ========================================================================== */

int32_t __jnative_fn_java_net_NetworkInterface_isLoopback0__Ljava_lang_String_I_Z(
        void* ifname_str, int32_t index)
{
    if (!ifname_str) { __jnative_throw_null_pointer_exception(); return 0; }
    (void)index;

#if defined(_WIN32)

    int32_t name_len = 0;
    const char* ifname = __jnative_read_string_bytes(ifname_str, &name_len);
    if (!ifname || name_len <= 0) return 0;

    ULONG size = 15000;
    PIP_ADAPTER_ADDRESSES addrs = NULL;
    for (int attempt = 0; attempt < 3; attempt++) {
        addrs = (PIP_ADAPTER_ADDRESSES)malloc(size);
        if (!addrs) return 0;
        ULONG rc = GetAdaptersAddresses(AF_UNSPEC,
            GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
            NULL, addrs, &size);
        if (rc == NO_ERROR) break;
        free(addrs); addrs = NULL;
        if (rc != ERROR_BUFFER_OVERFLOW) return 0;
    }
    if (!addrs) return 0;

    int result = 0;
    for (PIP_ADAPTER_ADDRESSES p = addrs; p; p = p->Next) {
        char label_buf[256];
        const char* label = adapter_label(p, label_buf, sizeof(label_buf));
        if (!label || !label[0]) continue;
        size_t ll = strlen(label);
        if ((int32_t)ll == name_len && memcmp(label, ifname, (size_t)name_len) == 0) {
            if (p->IfType == IF_TYPE_SOFTWARE_LOOPBACK) result = 1;
            break;
        }
    }
    free(addrs);
    return result;

#else

    int32_t name_len = 0;
    const char* ifname = __jnative_read_string_bytes(ifname_str, &name_len);
    if (!ifname || name_len <= 0 || (size_t)name_len >= IFNAMSIZ) return 0;

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) return 0;

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    memcpy(ifr.ifr_name, ifname, (size_t)name_len);
    int result = 0;
    if (ioctl(sock, SIOCGIFFLAGS, &ifr) == 0 && (ifr.ifr_flags & IFF_LOOPBACK)) {
        result = 1;
    }
    close(sock);
    return result;

#endif
}

/* ============================================================================
 *  getMacAddr0
 * ========================================================================== */

void* __jnative_fn_java_net_NetworkInterface_getMacAddr0___BLjava_lang_String_I__B(
        void* in_addr, void* ifname_str, int32_t index)
{
    (void)in_addr;
    if (!ifname_str) { __jnative_throw_null_pointer_exception(); return NULL; }

    int32_t name_len = 0;
    const char* ifname = __jnative_read_string_bytes(ifname_str, &name_len);
    if (!ifname || name_len <= 0) return NULL;

#if defined(_WIN32)

    ULONG size = 15000;
    PIP_ADAPTER_ADDRESSES addrs = NULL;
    for (int attempt = 0; attempt < 3; attempt++) {
        addrs = (PIP_ADAPTER_ADDRESSES)malloc(size);
        if (!addrs) return NULL;
        ULONG rc = GetAdaptersAddresses(AF_UNSPEC,
            GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
            NULL, addrs, &size);
        if (rc == NO_ERROR) break;
        free(addrs); addrs = NULL;
        if (rc != ERROR_BUFFER_OVERFLOW) return NULL;
    }
    if (!addrs) return NULL;

    void* result = NULL;
    for (PIP_ADAPTER_ADDRESSES p = addrs; p; p = p->Next) {
        char label_buf[256];
        const char* label = adapter_label(p, label_buf, sizeof(label_buf));
        if (!label || !label[0]) continue;
        size_t ll = strlen(label);
        if ((int32_t)ll != name_len || memcmp(label, ifname, (size_t)name_len) != 0) continue;

        int mac_len = (int)p->PhysicalAddressLength;
        if (mac_len <= 0 || mac_len > 16) {
            result = jnative_byte_array(NULL, 0);
            break;
        }
        int all_zero = 1;
        for (int k = 0; k < mac_len; k++) if (p->PhysicalAddress[k]) { all_zero = 0; break; }
        if (all_zero) {
            result = jnative_byte_array(NULL, 0);
        } else {
            result = jnative_byte_array(p->PhysicalAddress, mac_len);
        }
        break;
    }
    free(addrs);
    return result;

#else

    if ((size_t)name_len >= IFNAMSIZ) return NULL;

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) return NULL;

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    memcpy(ifr.ifr_name, ifname, (size_t)name_len);
    if (ioctl(sock, SIOCGIFHWADDR, &ifr) < 0) { close(sock); return NULL; }
    close(sock);

    const unsigned char* mac = (const unsigned char*)ifr.ifr_hwaddr.sa_data;
    int all_zero = 1;
    for (int k = 0; k < 6; k++) if (mac[k]) { all_zero = 0; break; }
    if (all_zero) return jnative_byte_array(NULL, 0);
    return jnative_byte_array(mac, 6);

#endif
}

/* ============================================================================
 *  boundInetAddress0
 * ========================================================================== */

int32_t __jnative_fn_java_net_NetworkInterface_boundInetAddress0__Ljava_net_InetAddress__Z(
        void* addr)
{
    if (!addr) return 0;

    const char* text = (const char*)addr;
    const char* slash = strchr(text, '/');
    if (slash) text = slash + 1;

    /* Parse into a numeric form. InetPtonA on Windows, inet_pton on POSIX. */
    struct in_addr  v4;
    struct in6_addr v6;
    int is_v4 = 0, is_v6 = 0;
#if defined(_WIN32)
    if (InetPtonA(AF_INET, text, &v4) == 1) is_v4 = 1;
    else if (InetPtonA(AF_INET6, text, &v6) == 1) is_v6 = 1;
#else
    if (inet_pton(AF_INET, text, &v4) == 1) is_v4 = 1;
    else if (inet_pton(AF_INET6, text, &v6) == 1) is_v6 = 1;
#endif
    if (!is_v4 && !is_v6) return 0;

#if defined(_WIN32)

    ULONG size = 15000;
    PIP_ADAPTER_ADDRESSES addrs = NULL;
    for (int attempt = 0; attempt < 3; attempt++) {
        addrs = (PIP_ADAPTER_ADDRESSES)malloc(size);
        if (!addrs) return 0;
        ULONG rc = GetAdaptersAddresses(AF_UNSPEC,
            GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
            NULL, addrs, &size);
        if (rc == NO_ERROR) break;
        free(addrs); addrs = NULL;
        if (rc != ERROR_BUFFER_OVERFLOW) return 0;
    }
    if (!addrs) return 0;

    int found = 0;
    for (PIP_ADAPTER_ADDRESSES p = addrs; p && !found; p = p->Next) {
        for (PIP_ADAPTER_UNICAST_ADDRESS ua = p->FirstUnicastAddress;
             ua; ua = ua->Next) {
            if (!ua->Address.lpSockaddr) continue;
            int fam = ua->Address.lpSockaddr->sa_family;
            if (is_v4 && fam == AF_INET) {
                struct sockaddr_in* sin = (struct sockaddr_in*)ua->Address.lpSockaddr;
                if (memcmp(&sin->sin_addr, &v4, sizeof(v4)) == 0) { found = 1; break; }
            } else if (is_v6 && fam == AF_INET6) {
                struct sockaddr_in6* sin6 = (struct sockaddr_in6*)ua->Address.lpSockaddr;
                if (memcmp(&sin6->sin6_addr, &v6, sizeof(v6)) == 0) { found = 1; break; }
            }
        }
    }
    free(addrs);
    return found;

#else

    struct ifaddrs* ifaddr = NULL;
    if (getifaddrs(&ifaddr) != 0) return 0;

    int found = 0;
    for (struct ifaddrs* ifa = ifaddr; ifa; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr) continue;
        if (is_v4 && ifa->ifa_addr->sa_family == AF_INET) {
            struct sockaddr_in* sin = (struct sockaddr_in*)ifa->ifa_addr;
            if (memcmp(&sin->sin_addr, &v4, sizeof(v4)) == 0) { found = 1; break; }
        } else if (is_v6 && ifa->ifa_addr->sa_family == AF_INET6) {
            struct sockaddr_in6* sin6 = (struct sockaddr_in6*)ifa->ifa_addr;
            if (memcmp(&sin6->sin6_addr, &v6, sizeof(v6)) == 0) { found = 1; break; }
        }
    }
    freeifaddrs(ifaddr);
    return found;

#endif
}