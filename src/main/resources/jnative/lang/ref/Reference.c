#include <stdint.h>
#include <pthread.h>

#include "jnative_runtime.h"

#define REFERENT_OFFSET OBJECT_HEADER_SIZE

static pthread_mutex_t reference_wait_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  reference_wait_cond  = PTHREAD_COND_INITIALIZER;

static inline void** referent_slot(void* ref) {
    return (void**)((char*)ref + REFERENT_OFFSET);
}

int32_t __jnative_fn_java_lang_ref_Reference_refersTo0__Ljava_lang_Object__Z(
        void* this_ref, void* o)
{
    if (this_ref == NULL) return 0;
    return (*referent_slot(this_ref) == o) ? 1 : 0;
}

void __jnative_fn_java_lang_ref_Reference_clear0___V(void* this_ref) {
    if (this_ref == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }
    *referent_slot(this_ref) = NULL;
}

int32_t __jnative_fn_java_lang_ref_Reference_hasReferencePendingList___Z(void) {
    return 0;
}

void __jnative_fn_java_lang_ref_Reference_waitForReferencePendingList___V(void) {
    pthread_mutex_lock(&reference_wait_mutex);
    pthread_cond_wait(&reference_wait_cond, &reference_wait_mutex);
    pthread_mutex_unlock(&reference_wait_mutex);
}

void* __jnative_fn_java_lang_ref_Reference_getAndClearReferencePendingList___Ljava_lang_ref_Reference_(void) {
    return NULL;
}