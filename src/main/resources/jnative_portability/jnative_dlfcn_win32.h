#ifndef JNATIVE_DLFCN_WIN32_H
#define JNATIVE_DLFCN_WIN32_H

#if defined(_WIN32)

#include <windows.h>

#ifndef RTLD_DEFAULT
#define RTLD_DEFAULT ((void*)0)
#endif
#ifndef RTLD_LAZY
#define RTLD_LAZY    0
#endif
#ifndef RTLD_NOW
#define RTLD_NOW     0
#endif
#ifndef RTLD_GLOBAL
#define RTLD_GLOBAL  0
#endif
#ifndef RTLD_LOCAL
#define RTLD_LOCAL   0
#endif

static inline void* dlopen(const char* filename, int flags) {
    (void)flags;
    if (filename == NULL) {
        return (void*)GetModuleHandleA(NULL);
    }
    return (void*)LoadLibraryA(filename);
}

static inline void* dlsym(void* handle, const char* symbol) {
    if (handle == NULL) handle = (void*)GetModuleHandleA(NULL);
    return (void*)GetProcAddress((HMODULE)handle, symbol);
}

static inline int dlclose(void* handle) {
    if (handle == NULL) return 0;
    return FreeLibrary((HMODULE)handle) ? 0 : -1;
}

static inline char* dlerror(void) {
    static __thread char buf[256];
    DWORD err = GetLastError();
    if (err == 0) return NULL;
    FormatMessageA(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                   NULL, err, 0, buf, sizeof(buf), NULL);
    return buf;
}

#endif
#endif
