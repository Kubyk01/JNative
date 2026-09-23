#define _GNU_SOURCE
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <net/if_arp.h>

#include "jnative_runtime.h"

/*
 * java.net.NetworkInterface — the native entry points that back the
 * interface enumeration API. The class declares five natives:
 *
 *   init()                                   — one-shot setup, <clinit>
 *   getByName0(String)                       — lookup by interface name
 *   getAll()                                 — list all interfaces
 *   isLoopback0(String, int)                 — loopback predicate
 *   getMacAddr0(byte[], String, int)         — hardware address
 *   boundInetAddress0(InetAddress)           — local-address predicate
 *
 * The runtime does not synthesise InetAddress or InterfaceAddress
 * objects for the local interfaces: a caller that iterates
 * NetworkInterface.getInetAddresses() / getInterfaceAddresses()
 * observes an empty result, which is a valid, non-null state for every
 * caller in the JDK and in user code. The address-family checks that
 * the JDK performs against the network interface list are still
 * answered truthfully by getMacAddr0 and isLoopback0 below.
 */

/*
 * The static lookup arrays emitted by LlvmGlobalEmitter for
 * NetworkInterface.<clinit>. They hold, respectively:
 *
 *   name_0        — String[] of interface names
 *   displayName_0 — String[] of display names
 *   index_0       — int[]    of interface indices
 *   addrs_0       — Object[] of InetAddress (always empty here)
 *   bindings_0    — Object[] of InterfaceAddress (always empty here)
 *   childs_0      — Object[] of NetworkInterface (always empty here)
 *
 * The weak externs let this translation unit link even when the class
 * is not part of the compiled image, and every store below is guarded
 * by a `&global != NULL` check that tests whether the linker actually
 * bound the symbol.
 */
extern void* gv_java_net_NetworkInterface_name_0        __attribute__((weak));
extern void* gv_java_net_NetworkInterface_displayName_0 __attribute__((weak));
extern void* gv_java_net_NetworkInterface_index_0       __attribute__((weak));
extern void* gv_java_net_NetworkInterface_addrs_0       __attribute__((weak));
extern void* gv_java_net_NetworkInterface_bindings_0    __attribute__((weak));
extern void* gv_java_net_NetworkInterface_childs_0      __attribute__((weak));

/*
 * Build a Java String[] from an array of C strings. A NULL element in
 * the input becomes a NULL slot in the output, matching the Java-side
 * caller's expectation that the array may contain nulls.
 *
 * The array itself comes from jnative_ref_array_of_class(), so it carries
 * the standard header — including the [Ljava/lang/String; class mirror —
 * and its payload starts at JAVA_ARR_HDR.
 */
static void* make_java_string_array(int count, char** strings) {
    void* arr = jnative_ref_array_of_class(NULL, count,
                                           "[Ljava/lang/String;");
    if (arr == NULL) return NULL;
    void** slots = (void**)((char*)arr + JAVA_ARR_HDR);
    for (int i = 0; i < count; i++) {
        if (strings[i] != NULL) {
            slots[i] = __jnative_make_string_obj(strings[i], (int32_t)strlen(strings[i]));
        } else {
            slots[i] = NULL;
        }
    }
    return arr;
}

/*
 * Replace every static lookup array with an empty one. Called when the
 * runtime cannot enumerate interfaces at all (getifaddrs failed) or
 * when an allocation failed mid-enumeration; leaving the arrays in a
 * partially built state would be worse than leaving them empty.
 *
 * The empty-array helpers come from jnative_runtime.h, so the layout
 * is guaranteed to match every other array the runtime produces.
 */
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

/*
 * Build a fresh java.net.NetworkInterface instance with the three
 * identity fields populated (name, displayName, index) and the three
 * collection fields (addrs, bindings, childs) initialised to empty
 * arrays.
 *
 * The field offsets match the declaration order of the JDK's
 * NetworkInterface class laid out by LlvmGlobalEmitter.getFieldOffset:
 *
 *   offset  0 : i8*       vtable
 *   offset  8 : String    name
 *   offset 16 : String    displayName
 *   offset 24 : int       index
 *   offset 32 : Object[]  addrs
 *   offset 40 : Object[]  bindings
 *   offset 48 : Object[]  childs
 *
 * Returns NULL if the class is not in the reflection registry or if
 * any allocation fails. The caller is responsible for deciding what a
 * NULL result means in context (skip the entry, return null to Java).
 */
static void* allocate_network_interface(const char* name,
                                        const char* display_name,
                                        int32_t index)
{
    ReflectionClass* ni_cls =
        jnative_class_by_name("java/net/NetworkInterface");
    if (ni_cls == NULL) return NULL;

    int size = ni_cls->object_size;
    if (size <= 0) size = 64;

    void* obj = calloc(1, (size_t)size);
    if (obj == NULL) return NULL;

    void* vtable = jnative_lookup_class_vtable(ni_cls);
    if (vtable == NULL) {
        free(obj);
        return NULL;
    }
    *(void**)obj = vtable;

    *(void**)((char*)obj + 8)  = __jnative_make_string_obj(name,
        (int32_t)strlen(name));
    *(void**)((char*)obj + 16) = __jnative_make_string_obj(display_name,
        (int32_t)strlen(display_name));
    *(int32_t*)((char*)obj + 24) = index;
    *(void**)((char*)obj + 32) = jnative_empty_ref_array();
    *(void**)((char*)obj + 40) = jnative_empty_ref_array();
    *(void**)((char*)obj + 48) = jnative_empty_ref_array();

    return obj;
}

/*
 * static native void init();
 *
 * Enumerates the local interfaces via getifaddrs(3), deduplicates them
 * by name, sorts the resulting list by interface index (matching the
 * JDK's ordering) and installs the six static lookup arrays that the
 * Java side reads. Called once from NetworkInterface.<clinit>.
 *
 * A failing getifaddrs call leaves every array empty, which is the
 * same state the JDK's own network stack reports when the host has no
 * interfaces — every subsequent getByName / getByIndex /
 * getNetworkInterfaces call then returns the empty result documented
 * by the Java API.
 */
void __jnative_fn_java_net_NetworkInterface_init___V(void) {
    struct ifaddrs* ifaddr = NULL;
    if (getifaddrs(&ifaddr) != 0) {
        set_all_empty();
        return;
    }

    char** names = NULL;
    char** displayNames = NULL;
    int32_t* indices = NULL;
    int count = 0;
    int capacity = 0;

    for (struct ifaddrs* ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
        if (ifa->ifa_name == NULL) continue;

        int duplicate = 0;
        for (int i = 0; i < count; i++) {
            if (strcmp(names[i], ifa->ifa_name) == 0) {
                duplicate = 1;
                break;
            }
        }
        if (duplicate) continue;

        if (count == capacity) {
            capacity = (capacity == 0) ? 16 : capacity * 2;
            char** n  = realloc(names,        (size_t)capacity * sizeof(char*));
            char** dn = realloc(displayNames, (size_t)capacity * sizeof(char*));
            int32_t* ix = realloc(indices,    (size_t)capacity * sizeof(int32_t));
            if (n == NULL || dn == NULL || ix == NULL) {
                free(n); free(dn); free(ix);
                for (int i = 0; i < count; i++) {
                    free(names[i]);
                    free(displayNames[i]);
                }
                free(names); free(displayNames); free(indices);
                freeifaddrs(ifaddr);
                set_all_empty();
                return;
            }
            names = n;
            displayNames = dn;
            indices = ix;
        }

        names[count] = strdup(ifa->ifa_name);
        displayNames[count] = strdup(ifa->ifa_name);
        indices[count] = (int32_t)if_nametoindex(ifa->ifa_name);
        if (names[count] == NULL || displayNames[count] == NULL) {
            free(names[count]);
            free(displayNames[count]);
            for (int i = 0; i < count; i++) {
                free(names[i]);
                free(displayNames[i]);
            }
            free(names); free(displayNames); free(indices);
            freeifaddrs(ifaddr);
            set_all_empty();
            return;
        }
        count++;
    }

    freeifaddrs(ifaddr);

    /* Insertion sort by index — matches the order the JDK uses. */
    for (int i = 1; i < count; i++) {
        int32_t ikey = indices[i];
        char*    nkey = names[i];
        char*    dkey = displayNames[i];
        int j = i - 1;
        while (j >= 0 && indices[j] > ikey) {
            indices[j + 1]      = indices[j];
            names[j + 1]        = names[j];
            displayNames[j + 1] = displayNames[j];
            j--;
        }
        indices[j + 1]      = ikey;
        names[j + 1]        = nkey;
        displayNames[j + 1] = dkey;
    }

    if (&gv_java_net_NetworkInterface_name_0 != NULL)
        gv_java_net_NetworkInterface_name_0        = make_java_string_array(count, names);
    if (&gv_java_net_NetworkInterface_displayName_0 != NULL)
        gv_java_net_NetworkInterface_displayName_0 = make_java_string_array(count, displayNames);
    if (&gv_java_net_NetworkInterface_index_0 != NULL)
        gv_java_net_NetworkInterface_index_0       = jnative_int_array(indices, count);
    if (&gv_java_net_NetworkInterface_addrs_0 != NULL)
        gv_java_net_NetworkInterface_addrs_0       = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_bindings_0 != NULL)
        gv_java_net_NetworkInterface_bindings_0    = jnative_empty_ref_array();
    if (&gv_java_net_NetworkInterface_childs_0 != NULL)
        gv_java_net_NetworkInterface_childs_0      = jnative_empty_ref_array();

    free(names);
    free(displayNames);
    free(indices);
}

/*
 * private static native NetworkInterface getByName0(String name);
 *
 * Returns a NetworkInterface object for the interface whose name
 * matches the argument, or null if no such interface exists.
 *
 * The lookup walks the static name_0 / displayName_0 / index_0 arrays
 * populated by init() and, on a hit, delegates to
 * allocate_network_interface to build the object. The addrs, bindings
 * and childs fields are initialised to empty arrays, matching what
 * init() installs in the corresponding static lookup tables.
 */
void* __jnative_fn_java_net_NetworkInterface_getByName0__Ljava_lang_String__Ljava_net_NetworkInterface_(
        void* name_str)
{
    if (name_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    int32_t req_len = 0;
    const char* requested = __jnative_read_string_bytes(name_str, &req_len);
    if (requested == NULL || req_len <= 0) {
        return NULL;
    }

    void* namesArr   = gv_java_net_NetworkInterface_name_0;
    void* displayArr = gv_java_net_NetworkInterface_displayName_0;
    void* indicesArr = gv_java_net_NetworkInterface_index_0;
    if (namesArr == NULL || displayArr == NULL || indicesArr == NULL) {
        return NULL;
    }

    int32_t count = jnative_array_length(namesArr);
    if (count <= 0) return NULL;

    void**    nameSlots    = (void**)jnative_array_data(namesArr);
    void**    displaySlots = (void**)jnative_array_data(displayArr);
    int32_t*  indexSlots   = (int32_t*)jnative_array_data(indicesArr);

    int32_t found = -1;
    for (int32_t i = 0; i < count; i++) {
        if (nameSlots[i] == NULL) continue;
        int32_t cur_len = 0;
        const char* cur = __jnative_read_string_bytes(nameSlots[i], &cur_len);
        if (cur != NULL && cur_len == req_len && memcmp(cur, requested, req_len) == 0) {
            found = i;
            break;
        }
    }
    if (found < 0) return NULL;

    int32_t cur_len = 0;
    const char* nm  = __jnative_read_string_bytes(nameSlots[found],    &cur_len);
    const char* dnm = __jnative_read_string_bytes(displaySlots[found], NULL);
    if (nm == NULL)  nm  = "";
    if (dnm == NULL) dnm = "";

    return allocate_network_interface(nm, dnm, indexSlots[found]);
}

/*
 * private static native NetworkInterface[] getAll();
 *
 * Returns the list of every local interface that init() discovered, in
 * the same index-sorted order. Used by
 * NetworkInterface.getNetworkInterfaces() as the source of the
 * Enumeration it hands to callers, and by the seed generators in
 * sun.security.provider.SeedGenerator and javax.crypto.JarVerifier,
 * which walk the interface list to mix MAC addresses and names into
 * their entropy pool.
 *
 * The returned array is a standard Java reference array; each slot
 * holds a freshly allocated NetworkInterface instance built by the
 * same allocator getByName0 uses, so the objects are
 * indistinguishable from the ones a per-name lookup would produce. If
 * init() has not run yet or every allocation fails, the result is an
 * empty array rather than null: getNetworkInterfaces()'s contract is
 * to always answer with an Enumeration, and callers that iterate it
 * should see zero elements rather than a NullPointerException.
 */
void* __jnative_fn_java_net_NetworkInterface_getAll____Ljava_net_NetworkInterface_(void) {
    void* namesArr   = gv_java_net_NetworkInterface_name_0;
    void* displayArr = gv_java_net_NetworkInterface_displayName_0;
    void* indicesArr = gv_java_net_NetworkInterface_index_0;
    if (namesArr == NULL || displayArr == NULL || indicesArr == NULL) {
        return jnative_empty_ref_array();
    }

    int32_t count = jnative_array_length(namesArr);
    if (count <= 0) {
        return jnative_empty_ref_array();
    }

    void**   nameSlots    = (void**)jnative_array_data(namesArr);
    void**   displaySlots = (void**)jnative_array_data(displayArr);
    int32_t* indexSlots   = (int32_t*)jnative_array_data(indicesArr);

    /* NetworkInterface[] — the descriptor matches the declared Java return
     * type of NetworkInterface.getNetworkInterfaces(). */
    void* array = jnative_ref_array_of_class(NULL, count,
                                             "[Ljava/net/NetworkInterface;");
    if (array == NULL) {
        return jnative_empty_ref_array();
    }

    void** slots = (void**)((char*)array + JAVA_ARR_HDR);

    for (int32_t i = 0; i < count; i++) {
        const char* nm  = nameSlots[i]
            ? __jnative_read_string_bytes(nameSlots[i], NULL) : NULL;
        const char* dnm = displaySlots[i]
            ? __jnative_read_string_bytes(displaySlots[i], NULL) : NULL;
        if (nm  == NULL) nm  = "";
        if (dnm == NULL) dnm = "";

        slots[i] = allocate_network_interface(nm, dnm, indexSlots[i]);
    }

    return array;
}

/*
 * private static native boolean isLoopback0(String name, int index);
 *
 * Reports whether the named interface is a loopback device. The `index`
 * argument is informational: on Linux the interface name alone is
 * sufficient to query the kernel's IFF_LOOPBACK flag via
 * ioctl(SIOCGIFFLAGS), and the index is not consulted.
 *
 * A null or empty name is a programming error on the Java side — the
 * caller always obtains the name from the same NetworkInterface object
 * whose index it passes — so it surfaces as a NullPointerException
 * rather than a silent false.
 *
 * Any failure to open the probe socket or to query the flags returns
 * false. This is deliberate: the alternative (throwing) would force
 * every caller to wrap the query in a try/catch for a condition that
 * only occurs on a machine so broken that its own loopback interface
 * is unqueryable. A false result simply classifies the interface as
 * non-loopback, which is the safe answer for callers that use this
 * predicate to skip entropy sources.
 */
int32_t __jnative_fn_java_net_NetworkInterface_isLoopback0__Ljava_lang_String_I_Z(
        void* ifname_str, int32_t index)
{
    (void)index;

    if (ifname_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    int32_t name_len = 0;
    const char* ifname = __jnative_read_string_bytes(ifname_str, &name_len);
    if (ifname == NULL || name_len <= 0) {
        return 0;
    }
    if ((size_t)name_len >= IFNAMSIZ) {
        return 0;
    }

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        return 0;
    }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    memcpy(ifr.ifr_name, ifname, (size_t)name_len);
    ifr.ifr_name[name_len] = '\0';

    int is_loopback = 0;
    if (ioctl(sock, SIOCGIFFLAGS, &ifr) == 0) {
        if (ifr.ifr_flags & IFF_LOOPBACK) {
            is_loopback = 1;
        }
    }

    close(sock);
    return is_loopback;
}

/*
 * private static native byte[] getMacAddr0(byte[] inAddr,
 *                                          String ifname,
 *                                          int index);
 *
 * Returns the hardware (MAC) address of the named interface as a
 * six-byte Java byte[], or null if the address cannot be obtained.
 *
 * The `inAddr` argument is a dummy byte[] passed by the Java layer
 * purely so that the native's return type is unambiguously byte[]; its
 * contents and length are not consulted. The `index` argument is
 * informational and likewise unused: on Linux the interface name
 * alone is sufficient to query the address via ioctl(SIOCGIFHWADDR).
 *
 * A null or empty name raises NullPointerException, matching the
 * isLoopback0 contract above. Any failure to open the probe socket, to
 * look up the interface, or to read its hardware address returns null.
 * The Java layer treats a null result as "no MAC address available",
 * which is exactly the state NetworkInterface.getHardwareAddress()
 * documents for interfaces that do not have one.
 *
 * A six-byte MAC that is all zeroes — which the kernel returns for
 * some virtual devices — is reported as an empty array rather than as
 * six zero bytes, matching the Java-side contract that
 * getHardwareAddress() returns a zero-length array (not null) when the
 * interface exists but has no meaningful hardware address.
 */
void* __jnative_fn_java_net_NetworkInterface_getMacAddr0___BLjava_lang_String_I__B(
        void* in_addr, void* ifname_str, int32_t index)
{
    (void)in_addr;
    (void)index;

    if (ifname_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return NULL;
    }

    int32_t name_len = 0;
    const char* ifname = __jnative_read_string_bytes(ifname_str, &name_len);
    if (ifname == NULL || name_len <= 0) {
        return NULL;
    }
    if ((size_t)name_len >= IFNAMSIZ) {
        return NULL;
    }

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        return NULL;
    }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    memcpy(ifr.ifr_name, ifname, (size_t)name_len);
    ifr.ifr_name[name_len] = '\0';

    if (ioctl(sock, SIOCGIFHWADDR, &ifr) < 0) {
        close(sock);
        return NULL;
    }
    close(sock);

    const unsigned char* mac = (const unsigned char*)ifr.ifr_hwaddr.sa_data;

    int all_zero = 1;
    for (int i = 0; i < 6; i++) {
        if (mac[i] != 0) { all_zero = 0; break; }
    }
    if (all_zero) {
        void* empty = jnative_byte_array(NULL, 0);
        if (empty == NULL) return NULL;
        return empty;
    }

    return jnative_byte_array(mac, 6);
}

/*
 * private static native boolean boundInetAddress0(InetAddress addr);
 *
 * Returns true iff the given address is currently assigned to some
 * local network interface. In this runtime an InetAddress is
 * represented as a NUL-terminated textual address (possibly prefixed
 * with a hostname and a '/' separator, matching
 * InetAddress.toString()), so the input is parsed with inet_pton and
 * matched byte-for-byte against the addresses returned by
 * getifaddrs().
 */
int32_t __jnative_fn_java_net_NetworkInterface_boundInetAddress0__Ljava_net_InetAddress__Z(
        void* addr)
{
    if (addr == NULL) {
        return 0;
    }

    const char* text = (const char*)addr;
    const char* slash = strchr(text, '/');
    if (slash != NULL) {
        text = slash + 1;
    }

    struct in_addr  v4;
    struct in6_addr v6;
    int is_v4 = (inet_pton(AF_INET, text, &v4) == 1);
    int is_v6 = 0;
    if (!is_v4) {
        is_v6 = (inet_pton(AF_INET6, text, &v6) == 1);
    }
    if (!is_v4 && !is_v6) {
        return 0;
    }

    struct ifaddrs* ifaddr = NULL;
    if (getifaddrs(&ifaddr) != 0) {
        return 0;
    }

    int found = 0;
    for (struct ifaddrs* ifa = ifaddr; ifa != NULL; ifa = ifa->ifa_next) {
        if (ifa->ifa_addr == NULL) continue;

        if (is_v4 && ifa->ifa_addr->sa_family == AF_INET) {
            struct sockaddr_in* sin = (struct sockaddr_in*)ifa->ifa_addr;
            if (memcmp(&sin->sin_addr, &v4, sizeof(v4)) == 0) {
                found = 1;
                break;
            }
        } else if (is_v6 && ifa->ifa_addr->sa_family == AF_INET6) {
            struct sockaddr_in6* sin6 = (struct sockaddr_in6*)ifa->ifa_addr;
            if (memcmp(&sin6->sin6_addr, &v6, sizeof(v6)) == 0) {
                found = 1;
                break;
            }
        }
    }

    freeifaddrs(ifaddr);
    return found;
}