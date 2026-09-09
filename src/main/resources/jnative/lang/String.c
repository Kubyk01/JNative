#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>

/* ---------------------------------------------------------------------------
 * String interning pool.
 *
 * In this runtime a Java String is represented as a plain NUL-terminated
 * C string (see __jnative_concat_strings, __jnative_create_string_array, etc).
 * The intern pool therefore maps C strings to their canonical instance.
 * ------------------------------------------------------------------------- */

typedef struct InternEntry {
    char* canonical;
    struct InternEntry* next;
} InternEntry;

#define INTERN_BUCKETS 8192
static InternEntry* intern_table[INTERN_BUCKETS];
static pthread_mutex_t intern_lock = PTHREAD_MUTEX_INITIALIZER;

static uint32_t hash_cstr(const char* s) {
    /* FNV-1a */
    uint32_t h = 2166136261u;
    while (*s) {
        h ^= (uint8_t)*s++;
        h *= 16777619u;
    }
    return h;
}

/* public native String intern(); */
void* __jnative_fn_java_lang_String_intern___Ljava_lang_String_(void* this_str) {
    if (this_str == NULL) return NULL;

    const char* s = (const char*)this_str;
    uint32_t bucket = hash_cstr(s) % INTERN_BUCKETS;

    pthread_mutex_lock(&intern_lock);

    /* Lookup */
    for (InternEntry* e = intern_table[bucket]; e; e = e->next) {
        if (strcmp(e->canonical, s) == 0) {
            char* canonical = e->canonical;
            pthread_mutex_unlock(&intern_lock);
            return (void*)canonical;
        }
    }

    /* Insert a canonical copy */
    size_t len = strlen(s);
    char* canonical = (char*)malloc(len + 1);
    if (!canonical) {
        pthread_mutex_unlock(&intern_lock);
        /* Fall back to the original string (do not crash). */
        return this_str;
    }
    memcpy(canonical, s, len + 1);

    InternEntry* ne = (InternEntry*)malloc(sizeof(InternEntry));
    if (!ne) {
        free(canonical);
        pthread_mutex_unlock(&intern_lock);
        return this_str;
    }
    ne->canonical = canonical;
    ne->next = intern_table[bucket];
    intern_table[bucket] = ne;

    pthread_mutex_unlock(&intern_lock);
    return (void*)canonical;
}