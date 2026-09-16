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
#define JAVA_IPV6 2

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

static void* make_inet_address(const uint8_t* bytes, int java_family,
                               const char* hostname)
{
    struct ReflectionClass* ia_cls =
        find_class_by_name("java/net/InetAddress");
    struct ReflectionClass* h_cls =
        find_class_by_name("java/net/InetAddress$InetAddressHolder");
    if (!ia_cls || !h_cls) return NULL;

    void* holder = alloc_object(h_cls);
    if (!holder) return NULL;

    *(void**)((char*)holder + 8)    = make_string(hostname);
    *(int32_t*)((char*)holder + 16) = java_family;
    *(void**)((char*)holder + 20)   =
        make_byte_array(bytes, java_family == JAVA_IPV4 ? 4 : 16);

    void* ia = alloc_object(ia_cls);
    if (!ia) { free(holder); return NULL; }

    *(void**)((char*)ia + 8)  = NULL;
    *(void**)((char*)ia + 16) = holder;

    return ia;
}

void* __jnative_fn_java_net_Inet6AddressImpl_lookupAllHostAddr__Ljava_lang_String_I__Ljava_net_InetAddress_(
        void* host_str, int32_t policy)
{
    (void)policy;
    if (!host_str) {
        __jnative_throw_exception(NULL);
    }
    const char* host = (const char*)host_str;

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family   = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    struct addrinfo* res = NULL;
    int rc = getaddrinfo(host, NULL, &hints, &res);
    if (rc != 0 || !res) {
        __jnative_throw_exception(NULL);
    }

    int count = 0;
    for (struct addrinfo* p = res; p; p = p->ai_next) {
        if (p->ai_family == AF_INET || p->ai_family == AF_INET6) count++;
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
        const uint8_t* bytes = NULL;
        int java_family = 0;
        if (p->ai_family == AF_INET) {
            struct sockaddr_in* sin = (struct sockaddr_in*)p->ai_addr;
            bytes = (const uint8_t*)&sin->sin_addr;
            java_family = JAVA_IPV4;
        } else if (p->ai_family == AF_INET6) {
            struct sockaddr_in6* sin6 = (struct sockaddr_in6*)p->ai_addr;
            bytes = (const uint8_t*)&sin6->sin6_addr;
            java_family = JAVA_IPV6;
        } else {
            continue;
        }
        slots[i++] = make_inet_address(bytes, java_family, host);
    }

    freeaddrinfo(res);
    return array;
}

void* __jnative_fn_java_net_Inet6AddressImpl_getHostByAddr___B_Ljava_lang_String_(
        void* addr_bytes)
{
    if (!addr_bytes) return NULL;

    int32_t len = *(int32_t*)addr_bytes;
    uint8_t* bytes = (uint8_t*)addr_bytes + JAVA_ARR_HDR;

    struct sockaddr_storage ss;
    memset(&ss, 0, sizeof(ss));
    socklen_t sslen = 0;

    if (len == 4) {
        struct sockaddr_in* sin = (struct sockaddr_in*)&ss;
        sin->sin_family = AF_INET;
        memcpy(&sin->sin_addr, bytes, 4);
        sslen = sizeof(struct sockaddr_in);
    } else if (len == 16) {
        struct sockaddr_in6* sin6 = (struct sockaddr_in6*)&ss;
        sin6->sin6_family = AF_INET6;
        memcpy(&sin6->sin6_addr, bytes, 16);
        sslen = sizeof(struct sockaddr_in6);
    } else {
        return NULL;
    }

    char hostbuf[NI_MAXHOST];
    int rc = getnameinfo((struct sockaddr*)&ss, sslen,
                         hostbuf, sizeof(hostbuf),
                         NULL, 0, NI_NAMEREQD);
    if (rc != 0) {
        return NULL;
    }
    return make_string(hostbuf);
}

void* __jnative_fn_java_net_Inet6AddressImpl_getLocalHostName___Ljava_lang_String_(void)
{
    char buf[256];
    if (gethostname(buf, sizeof(buf)) != 0) {
        return NULL;
    }
    buf[sizeof(buf) - 1] = '\0';
    return make_string(buf);
}