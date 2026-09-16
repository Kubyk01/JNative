#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

void* gv_java_net_NetworkInterface_name_0        = NULL;
void* gv_java_net_NetworkInterface_displayName_0 = NULL;
void* gv_java_net_NetworkInterface_index_0       = NULL;
void* gv_java_net_NetworkInterface_addrs_0       = NULL;
void* gv_java_net_NetworkInterface_bindings_0    = NULL;
void* gv_java_net_NetworkInterface_childs_0      = NULL;

#define JAVA_ARR_HDR 4

static void* make_string_array(int count, char** strings) {
    size_t total = JAVA_ARR_HDR + (size_t)count * sizeof(void*);
    void* arr = malloc(total);
    if (arr == NULL) return NULL;
    *(int32_t*)arr = count;
    void** slots = (void**)((char*)arr + JAVA_ARR_HDR);
    for (int i = 0; i < count; i++) {
        slots[i] = strings[i];
    }
    return arr;
}

static void* make_int_array(int count, int32_t* values) {
    size_t total = JAVA_ARR_HDR + (size_t)count * sizeof(int32_t);
    void* arr = malloc(total);
    if (arr == NULL) return NULL;
    *(int32_t*)arr = count;
    int32_t* slots = (int32_t*)((char*)arr + JAVA_ARR_HDR);
    for (int i = 0; i < count; i++) {
        slots[i] = values[i];
    }
    return arr;
}

static void* make_empty_ref_array(void) {
    void* arr = malloc(JAVA_ARR_HDR);
    if (arr == NULL) return NULL;
    *(int32_t*)arr = 0;
    return arr;
}

static void set_all_empty(void) {
    gv_java_net_NetworkInterface_name_0        = make_empty_ref_array();
    gv_java_net_NetworkInterface_displayName_0 = make_empty_ref_array();
    gv_java_net_NetworkInterface_index_0       = make_int_array(0, NULL);
    gv_java_net_NetworkInterface_addrs_0       = make_empty_ref_array();
    gv_java_net_NetworkInterface_bindings_0    = make_empty_ref_array();
    gv_java_net_NetworkInterface_childs_0      = make_empty_ref_array();
}

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

    gv_java_net_NetworkInterface_name_0        = make_string_array(count, names);
    gv_java_net_NetworkInterface_displayName_0 = make_string_array(count, displayNames);
    gv_java_net_NetworkInterface_index_0       = make_int_array(count, indices);
    gv_java_net_NetworkInterface_addrs_0       = make_empty_ref_array();
    gv_java_net_NetworkInterface_bindings_0    = make_empty_ref_array();
    gv_java_net_NetworkInterface_childs_0      = make_empty_ref_array();

    free(names);
    free(displayNames);
    free(indices);
}

/*
 * private static native boolean boundInetAddress0(InetAddress addr);
 *
 * Returns true iff the given address is currently assigned to some local
 * network interface. In this runtime an InetAddress is represented as a
 * NUL-terminated textual address (possibly prefixed with a hostname and a
 * '/' separator, matching InetAddress.toString()), so the input is parsed
 * with inet_pton and matched byte-for-byte against the addresses returned
 * by getifaddrs().
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