#include <pthread.h>
#include <stdlib.h>
#include <stdint.h>
#include <setjmp.h>
#include <stdio.h>
#include <string.h>

// ---------------------------------------------------------------------------
// Forward declarations for exception throwing functions
// ---------------------------------------------------------------------------
__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);
__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void);
__attribute__((noreturn)) void __jnative_throw_class_cast_exception(void);
__attribute__((noreturn)) void __jnative_throw_arithmetic_exception(void);
void* __jnative_get_exception_object(void);
int __jnative_catch_matches(void* exc, void* type_info);
int __jnative_instanceof(void* obj, void** type_info);

// ---------------------------------------------------------------------------
// Monitors (mutex per object)
// ---------------------------------------------------------------------------

#define HASH_SIZE 1024

typedef struct MonitorEntry {
    void* obj;
    pthread_mutex_t mutex;
    struct MonitorEntry* next;
} MonitorEntry;

static MonitorEntry* monitor_table[HASH_SIZE] = {0};
static pthread_mutex_t table_lock = PTHREAD_MUTEX_INITIALIZER;

static uint32_t hash_ptr(void* p) {
    return (uint32_t)((uintptr_t)p) % HASH_SIZE;
}

static pthread_mutex_t* find_mutex(void* obj) {
    uint32_t idx = hash_ptr(obj);
    MonitorEntry* entry = monitor_table[idx];
    while (entry) {
        if (entry->obj == obj) {
            return &entry->mutex;
        }
        entry = entry->next;
    }
    return NULL;
}

static pthread_mutex_t* get_or_create_mutex(void* obj) {
    if (obj == NULL) return NULL;

    uint32_t idx = hash_ptr(obj);
    pthread_mutex_lock(&table_lock);

    MonitorEntry* entry = monitor_table[idx];
    while (entry) {
        if (entry->obj == obj) {
            pthread_mutex_unlock(&table_lock);
            return &entry->mutex;
        }
        entry = entry->next;
    }

    MonitorEntry* new_entry = malloc(sizeof(MonitorEntry));
    if (new_entry == NULL) {
        pthread_mutex_unlock(&table_lock);
        return NULL;
    }
    new_entry->obj = obj;

    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&new_entry->mutex, &attr);
    pthread_mutexattr_destroy(&attr);

    new_entry->next = monitor_table[idx];
    monitor_table[idx] = new_entry;

    pthread_mutex_unlock(&table_lock);
    return &new_entry->mutex;
}

void __jnative_monitor_destroy(void* obj) {
    if (obj == NULL) return;
    uint32_t idx = hash_ptr(obj);
    pthread_mutex_lock(&table_lock);

    MonitorEntry** pp = &monitor_table[idx];
    while (*pp) {
        MonitorEntry* entry = *pp;
        if (entry->obj == obj) {
            *pp = entry->next;
            pthread_mutex_destroy(&entry->mutex);
            free(entry);
            break;
        }
        pp = &entry->next;
    }
    pthread_mutex_unlock(&table_lock);
}

void __jnative_monitor_enter(void* obj) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    pthread_mutex_t* mtx = get_or_create_mutex(obj);
    if (mtx) {
        pthread_mutex_lock(mtx);
    }
}

void __jnative_monitor_exit(void* obj) {
    if (obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    pthread_mutex_lock(&table_lock);
    pthread_mutex_t* mtx = find_mutex(obj);
    pthread_mutex_unlock(&table_lock);
    if (mtx) {
        pthread_mutex_unlock(mtx);
    }
}

// ---------------------------------------------------------------------------
// Exception handling based on setjmp/longjmp
// ---------------------------------------------------------------------------

typedef struct CatchContext {
    struct CatchContext* next;
    void* type_info;
    jmp_buf buf;
} CatchContext;

static _Thread_local CatchContext* current_context = NULL;
static _Thread_local void* current_exception = NULL;

void __jnative_push_catch(void* jmp_buf_ptr, void* type_info) {
    CatchContext* ctx = (CatchContext*)malloc(sizeof(CatchContext));
    if (ctx == NULL) {
        fprintf(stderr, "Exception in thread \"main\": out of memory while installing handler\n");
        abort();
    }
    ctx->type_info = type_info;
    memcpy(ctx->buf, jmp_buf_ptr, sizeof(jmp_buf));
    ctx->next = current_context;
    current_context = ctx;
}

void __jnative_pop_catch(void) {
    if (current_context) {
        CatchContext* old = current_context;
        current_context = old->next;
        free(old);
    }
}

__attribute__((noreturn)) void __jnative_throw_exception(void* exc) {
    current_exception = exc;
    CatchContext* ctx = current_context;
    if (ctx) {
        longjmp(ctx->buf, 1);
    }
    fprintf(stderr, "Exception in thread \"main\": unhandled exception\n");
    abort();
}

void* __jnative_get_exception_object(void) {
    return current_exception;
}

int __jnative_catch_matches(void* exc, void* type_info) {
    if (exc == NULL || type_info == NULL) return 0;
    void* vtable = *(void**)exc;
    void** ti = (void**)type_info;
    while (*ti) {
        if (*ti == vtable) return 1;
        ti++;
    }
    return 0;
}

int __jnative_instanceof(void* obj, void** type_info) {
    if (obj == NULL) return 0;
    if (type_info == NULL) return 0;
    void* vtable = *(void**)obj;
    void** ti = type_info;
    while (*ti) {
        if (*ti == vtable) return 1;
        ti++;
    }
    return 0;
}

// ---------------------------------------------------------------------------
// Standard runtime exceptions
// ---------------------------------------------------------------------------

__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void) {
    __jnative_throw_exception(NULL);
}

__attribute__((noreturn)) void __jnative_throw_array_index_out_of_bounds(void) {
    __jnative_throw_exception(NULL);
}

__attribute__((noreturn)) void __jnative_throw_class_cast_exception(void) {
    __jnative_throw_exception(NULL);
}

__attribute__((noreturn)) void __jnative_throw_arithmetic_exception(void) {
    __jnative_throw_exception(NULL);
}

// ---------------------------------------------------------------------------
// String array for main(int argc -> String[] args)
// ---------------------------------------------------------------------------

void* __jnative_create_string_array(int argc, char** argv) {
    int total_size = 4 + argc * 8;
    void* array = malloc(total_size);
    if (!array) return NULL;
    *(int*)array = argc;
    char** slots = (char**)((char*)array + 4);
    for (int i = 0; i < argc; i++) {
        int len = strlen(argv[i]);
        char* str = malloc(len + 1);
        if (str) {
            strcpy(str, argv[i]);
            slots[i] = str;
        } else {
            slots[i] = NULL;
        }
    }
    return array;
}

// ---------------------------------------------------------------------------
// Multi-dimensional array creation (moved from LLVM to C)
// ---------------------------------------------------------------------------

static void* create_multi_array_rec(const char* desc, int last_dim, int* sizes,
                                    int current_dim, int elem_size) {
    int is_last = (current_dim == last_dim);
    int length = sizes[current_dim];
    int total_size = 4 + length * (is_last ? elem_size : sizeof(void*));
    void* array = malloc(total_size);
    if (!array) return NULL;
    *(int*)array = length; // store length in header

    if (!is_last) {
        void** slots = (void**)((char*)array + 4);
        for (int i = 0; i < length; i++) {
            slots[i] = create_multi_array_rec(desc, last_dim, sizes, current_dim + 1, elem_size);
        }
    }
    return array;
}

void* __jnative_new_multi_array(const char* desc, int dims, int* sizes, int elem_size) {
    if (dims <= 0 || sizes == NULL) return NULL;
    int last_dim = dims - 1;
    return create_multi_array_rec(desc, last_dim, sizes, 0, elem_size);
}

// ---------------------------------------------------------------------------
// Reflection runtime (structs and functions)
// ---------------------------------------------------------------------------

struct ReflectionField {
    void* name;
    void* descriptor;
    int offset;
    int modifiers;
};

struct ReflectionMethod {
    void* name;
    void* descriptor;
    void* adaptor;
    int modifiers;
};

struct ReflectionConstructor {
    void* descriptor;
    void* adaptor;
    int modifiers;
};

struct ReflectionClass {
    void* name;
    struct ReflectionClass* superclass;
    struct ReflectionClass** interfaces;
    struct ReflectionMethod** methods;
    struct ReflectionField** fields;
    struct ReflectionConstructor** constructors;
    int modifiers;
    int object_size;
};

void* __jnative_invoke_method(struct ReflectionMethod* method, void* obj, void** args) {
    if (method == NULL || method->adaptor == NULL) return NULL;
    typedef void* (*adaptor_t)(void*, void**);
    adaptor_t adaptor = (adaptor_t)method->adaptor;
    return adaptor(obj, args);
}

void* __jnative_new_instance(struct ReflectionConstructor* ctor, void** args) {
    if (ctor == NULL || ctor->adaptor == NULL) return NULL;
    typedef void* (*adaptor_t)(void**);
    adaptor_t adaptor = (adaptor_t)ctor->adaptor;
    return adaptor(args);
}