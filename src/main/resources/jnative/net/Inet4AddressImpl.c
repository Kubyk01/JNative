#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <dlfcn.h>
#include <netdb.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);

struct ReflectionClass {
    void* name;
    struct ReflectionClass* superclass;
    struct ReflectionClass** interfaces;
    void** methods;
    void** fields;
    void** constructors;
    int modifiers;
    int object_size;
};

extern struct ReflectionClass* reflect_all_classes[];

#define JAVA_ARR_HDR 4
#define JAVA_IPV4 1

static struct ReflectionClass* find_class_by_name(const char* internal_name) {
    if (!internal_name) return NULL;
    if (reflect_all_classes[0] == NULL) return NULL;
    struct ReflectionClass** pp = reflect_all_classes;
    while (*pp) {
        struct ReflectionClass* cls = *pp;
        const char* n = (const char*)cls->name;
        if (n && strcmp(n, internal_name) == 0) return cls;
        pp++;
    }
    return NULL;
}

static void* lookup_class_vtable(struct ReflectionClass* cls) {
    if (!cls || !cls->name) return NULL;
    char buf[512];
    snprintf(buf, sizeof(buf), "__type_info_%s", (const char*)cls->name);
    for (char* p = buf; *p; p++) {
        if (*p == '/' || *p == '.') *p = '_';
    }
    void* handle = dlopen(NULL, RTLD_LAZY);
    if (!handle) return NULL;
    void** type_info = (void**)dlsym(handle, buf);
    dlclose(handle);
    if (!type_info) return NULL;
    return type_info[0];
}

static void* alloc_object(struct ReflectionClass* cls) {
    if (!cls) return NULL;
    int size = cls->object_size;
    if (size <= 0) size = 8;
    void* obj = calloc(1, (size_t)size);
    if (!obj) return NULL;
    void* vt = lookup_class_vtable(cls);
    if (!vt) { free(obj); return NULL; }
    *(void**)obj = vt;
    return obj;
}

static void* make_byte_array(const uint8_t* data, int32_t len) {
    size_t total = JAVA_ARR_HDR + (size_t)len;
    void* arr = malloc(total);
    if (!arr) return NULL;
    *(int32_t*)arr = len;
    if (data && len > 0) memcpy((char*)arr + JAVA_ARR_HDR, data, (size_t)len);
    return arr;
}

static void* make_string(const char* s) {
    if (!s) return NULL;
    size_t len = strlen(s);
    char* copy = (char*)malloc(len + 1);
    if (!copy) return NULL;
    memcpy(copy, s, len + 1);
    return copy;
}

/*
 * Object layouts used below (matching the LLVM emitter's field-offset
 * computation: vtable at offset 0, fields laid out at increasing byte
 * offsets with no padding):
 *
 *   java.net.InetAddress:
 *       offset  8 : canonicalHostName (String)
 *       offset 16 : holder (InetAddress$InetAddressHolder)
 *
 *   java.net.InetAddress$InetAddressHolder:
 *       offset  8 : hostName (String)
 *       offset 16 : family   (int)
 *       offset 20 : addressBytes (byte[])
 */
static void* make_inet4_address(const uint8_t* bytes, const char* hostname) {
    struct ReflectionClass* ia_cls =
        find_class_by_name("java/net/InetAddress");
    struct ReflectionClass* h_cls =
        find_class_by_name("java/net/InetAddress$InetAddressHolder");
    if (!ia_cls || !h_cls) return NULL;

    void* holder = alloc_object(h_cls);
    if (!holder) return NULL;

    *(void**)((char*)holder + 8)    = make_string(hostname);
    *(int32_t*)((char*)holder + 16) = JAVA_IPV4;
    *(void**)((char*)holder + 20)   = make_byte_array(bytes, 4);

    void* ia = alloc_object(ia_cls);
    if (!ia) { free(holder); return NULL; }

    *(void**)((char*)ia + 8)  = NULL;
    *(void**)((char*)ia + 16) = holder;

    return ia;
}

/*
 * private native InetAddress[] lookupAllHostAddr(String host, int policy)
 *     throws UnknownHostException;
 *
 * IPv4-only forward resolution. getaddrinfo is restricted to AF_INET
 * so the returned array holds only IPv4 addresses; anything else the
 * resolver produces is dropped. Each element is a fully-shaped
 * InetAddress so Java callers can invoke getAddress / getHostAddress
 * without further native assistance.
 */
void* __jnative_fn_java_net_Inet4AddressImpl_lookupAllHostAddr__Ljava_lang_String_I__Ljava_net_InetAddress_(
        void* host_str, int32_t policy)
{
    (void)policy;
    if (!host_str) {
        __jnative_throw_exception(NULL);
    }
    const char* host = (const char*)host_str;

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family   = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    struct addrinfo* res = NULL;
    int rc = getaddrinfo(host, NULL, &hints, &res);
    if (rc != 0 || !res) {
        __jnative_throw_exception(NULL);
    }

    int count = 0;
    for (struct addrinfo* p = res; p; p = p->ai_next) {
        if (p->ai_family == AF_INET) count++;
    }
    if (count == 0) {
        freeaddrinfo(res);
        __jnative_throw_exception(NULL);
    }

    size_t total = JAVA_ARR_HDR + (size_t)count * sizeof(void*);
    void* array = malloc(total);
    if (!array) {
        freeaddrinfo(res);
        __jnative_throw_exception(NULL);
    }
    *(int32_t*)array = count;

    void** slots = (void**)((char*)array + JAVA_ARR_HDR);
    int i = 0;
    for (struct addrinfo* p = res; p && i < count; p = p->ai_next) {
        if (p->ai_family != AF_INET) continue;
        struct sockaddr_in* sin = (struct sockaddr_in*)p->ai_addr;
        slots[i++] = make_inet4_address((const uint8_t*)&sin->sin_addr, host);
    }

    freeaddrinfo(res);
    return array;
}

/*
 * private native String getHostByAddr(byte[] addr);
 *
 * Reverse DNS through getnameinfo(3) with NI_NAMEREQD. Only 4-byte
 * input is meaningful for IPv4 reverse lookup; any other length yields
 * null, which the caller turns into a numeric fallback.
 */
void* __jnative_fn_java_net_Inet4AddressImpl_getHostByAddr___B_Ljava_lang_String_(
        void* addr_bytes)
{
    if (!addr_bytes) return NULL;

    int32_t len = *(int32_t*)addr_bytes;
    if (len != 4) return NULL;
    uint8_t* bytes = (uint8_t*)addr_bytes + JAVA_ARR_HDR;

    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    memcpy(&sin.sin_addr, bytes, 4);

    char hostbuf[NI_MAXHOST];
    int rc = getnameinfo((struct sockaddr*)&sin, sizeof(sin),
                         hostbuf, sizeof(hostbuf),
                         NULL, 0, NI_NAMEREQD);
    if (rc != 0) {
        return NULL;
    }
    return make_string(hostbuf);
}

/*
 * private native String getLocalHostName();
 *
 * The local host name as reported by gethostname(3). A null return
 * causes the Java caller to fall back to the numeric loopback address.
 */
void* __jnative_fn_java_net_Inet4AddressImpl_getLocalHostName___Ljava_lang_String_(void)
{
    char buf[256];
    if (gethostname(buf, sizeof(buf)) != 0) {
        return NULL;
    }
    buf[sizeof(buf) - 1] = '\0';
    return make_string(buf);
}