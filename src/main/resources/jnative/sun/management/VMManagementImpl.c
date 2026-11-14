#include "jnative_platform.h"
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "jnative_runtime.h"
#include "jnative_portability.h"

#if defined(_WIN32)
#include <process.h>
#define VMM_GETPID() ((int32_t)_getpid())
#else
#include <unistd.h>
#define VMM_GETPID() ((int32_t)getpid())
#endif

#define VMM_JMM_VERSION 0x20040000u

static int64_t vmm_startup_millis = 0;

JN_CONSTRUCTOR(vmm_record_startup) {
    vmm_startup_millis = jn_clock_realtime_ns() / 1000000;
}

static int64_t vmm_loaded_class_count(void) {
    int64_t count = 0;
    while (reflect_all_classes[count] != NULL) count++;
    return count;
}

void* __jnative_fn_sun_management_VMManagementImpl_getVersion0___Ljava_lang_String_(void) {
    char buf[32];
    unsigned int major = (VMM_JMM_VERSION & 0x0FFF0000u) >> 16;
    unsigned int minor = (VMM_JMM_VERSION & 0xFF00u) >> 8;
    int n = snprintf(buf, sizeof(buf), "%u.%u", major, minor);
    return __jnative_make_string_obj(buf, (int32_t)n);
}

void __jnative_fn_sun_management_VMManagementImpl_initOptionalSupportFields___V(void) {
}

int32_t __jnative_fn_sun_management_VMManagementImpl_isThreadContentionMonitoringEnabled___Z(void* self) {
    (void)self;
    return 0;
}

int32_t __jnative_fn_sun_management_VMManagementImpl_isThreadCpuTimeEnabled___Z(void* self) {
    (void)self;
    return 0;
}

int32_t __jnative_fn_sun_management_VMManagementImpl_isThreadAllocatedMemoryEnabled___Z(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalClassCount___J(void* self) {
    (void)self;
    return vmm_loaded_class_count();
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getUnloadedClassCount___J(void* self) {
    (void)self;
    return 0;
}

int32_t __jnative_fn_sun_management_VMManagementImpl_getVerboseClass___Z(void* self) {
    (void)self;
    return 0;
}

int32_t __jnative_fn_sun_management_VMManagementImpl_getVerboseGC___Z(void* self) {
    (void)self;
    return 0;
}

int32_t __jnative_fn_sun_management_VMManagementImpl_getProcessId___I(void* self) {
    (void)self;
    return VMM_GETPID();
}

void* __jnative_fn_sun_management_VMManagementImpl_getVmArguments0____Ljava_lang_String_(void* self) {
    (void)self;
    void* arr = jnative_ref_array_of_class(NULL, 0, "[Ljava/lang/String;");
    if (arr == NULL) __jnative_throw_out_of_memory_error_ctx("VMManagementImpl.getVmArguments0");
    return arr;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getStartupTime___J(void* self) {
    (void)self;
    return vmm_startup_millis;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getUptime0___J(void* self) {
    (void)self;
    return jn_clock_realtime_ns() / 1000000 - vmm_startup_millis;
}

int32_t __jnative_fn_sun_management_VMManagementImpl_getAvailableProcessors___I(void* self) {
    (void)self;
    return (int32_t)jn_cpu_count();
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalCompileTime___J(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalThreadCount___J(void* self) {
    (void)self;
    int64_t started = 0;
    __jnative_thread_counts(NULL, NULL, &started, NULL);
    return started;
}

int32_t __jnative_fn_sun_management_VMManagementImpl_getLiveThreadCount___I(void* self) {
    (void)self;
    int32_t live = 0;
    __jnative_thread_counts(&live, NULL, NULL, NULL);
    return live;
}

int32_t __jnative_fn_sun_management_VMManagementImpl_getPeakThreadCount___I(void* self) {
    (void)self;
    int32_t peak = 0;
    __jnative_thread_counts(NULL, NULL, NULL, &peak);
    return peak;
}

int32_t __jnative_fn_sun_management_VMManagementImpl_getDaemonThreadCount___I(void* self) {
    (void)self;
    int32_t daemon = 0;
    __jnative_thread_counts(NULL, &daemon, NULL, NULL);
    return daemon;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getSafepointCount___J(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalSafepointTime___J(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getSafepointSyncTime___J(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getTotalApplicationNonStoppedTime___J(void* self) {
    (void)self;
    return jn_clock_realtime_ns() / 1000000 - vmm_startup_millis;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getLoadedClassSize___J(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getUnloadedClassSize___J(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getClassLoadingTime___J(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getMethodDataSize___J(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getInitializedClassCount___J(void* self) {
    (void)self;
    return vmm_loaded_class_count();
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getClassInitializationTime___J(void* self) {
    (void)self;
    return 0;
}

int64_t __jnative_fn_sun_management_VMManagementImpl_getClassVerificationTime___J(void* self) {
    (void)self;
    return 0;
}
