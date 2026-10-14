/*
 * java.nio.MappedMemoryUtils
 *
 * The class contributes a single native:
 *
 *   static native void unload0(long address, long size);
 *
 * It is called from the Cleaner that MappedByteBuffer / DirectByteBuffer
 * install when a memory-mapped region is created. In HotSpot the body
 * maps onto the platform's memory-unmapping primitive; the Windows
 * version is UnmapViewOfFile, the Unix version is munmap(2).
 *
 * The address and size are exactly what was passed to mmap() when the
 * mapping was established. A zero address is treated as "nothing to
 * unmap" and succeeds silently: Buffer's own bookkeeping clears the
 * address field before scheduling the Cleaner, and a second Cleaner
 * call after that point must not attempt to unmap address 0.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <sys/mman.h>

#include "jnative_runtime.h"

void __jnative_fn_java_nio_MappedMemoryUtils_unload0__JJ_V(
        int64_t address, int64_t size)
{
    if (address == 0 || size <= 0) {
        return;
    }
    (void)munmap((void*)(intptr_t)address, (size_t)size);
}

/*
 * private static native void force0(FileDescriptor fd, long address,
 *                                   long length)
 *     throws IOException;
 *
 * Flushes a memory-mapped region to its backing store. `address` is the
 * base of the region and `length` is its size in bytes, both exactly as
 * they were passed to mmap(2) when the mapping was established. The fd
 * argument is informational: msync(2) identifies the mapping by address
 * range, not by the descriptor, so the descriptor is not consulted.
 *
 * A zero address or a non-positive length is treated as "nothing to
 * flush" and succeeds silently. Buffer's Cleaner machinery clears the
 * address field before scheduling the flush, and the flush can race
 * with the cleaner; both sides must tolerate that race without turning
 * it into a spurious IOException.
 *
 * The MS_SYNC flag is used unconditionally. MappedMemoryUtils.force is
 * only invoked when the buffer was created with a MapMode that requires
 * synchronous write-back (MapMode.READ_WRITE with the
 * "jdk.nio.mapmode" system property set to "sync", or the explicit
 * MappedByteBuffer.force() call). The asynchronous MS_ASYNC path is not
 * exposed through this native; a caller that wants it must use
 * FileChannel.force instead.
 */
void __jnative_fn_java_nio_MappedMemoryUtils_force0__Ljava_io_FileDescriptor_JJ_V(
        void* fd_obj, int64_t address, int64_t length)
{
    (void)fd_obj;
    if (address == 0 || length <= 0) {
        return;
    }
    if (msync((void*)(intptr_t)address, (size_t)length, MS_SYNC) < 0) {
        __jnative_throw_exception(NULL);
    }
}

/*
 * private static native boolean isLoaded0(long address, long length,
 *                                         long pageCount);
 *
 * Reports whether the pages backing the given address range are
 * currently resident in physical memory. On Linux the query is
 * implemented with mincore(2), which takes three arguments: the base
 * address, the length in bytes, and a byte-vector whose i-th entry is
 * non-zero iff the i-th page is resident.
 *
 * The `pageCount` argument is the number of pages the caller expects
 * the range to cover. It is used to size the temporary byte-vector
 * that mincore fills in; the runtime does not consult the kernel's
 * page size directly, because the caller has already computed the
 * count from its own page-size query (which it obtained through
 * Unsafe.pageSize() -- see the UnsafeConstants fix for why that value
 * is no longer zero).
 *
 * The return value is true iff every page in the range is resident,
 * matching the contract of MappedByteBuffer.isLoaded(). A failing
 * mincore call reports false rather than throwing: the method's
 * Java-level signature does not declare a checked exception, and
 * "not currently loaded" is a truthful answer for every failure mode
 * that mincore can produce.
 */
int32_t __jnative_fn_java_nio_MappedMemoryUtils_isLoaded0__JJJ_Z(
        int64_t address, int64_t length, int64_t page_count)
{
    if (address == 0 || length <= 0 || page_count <= 0) {
        return 1;
    }

    /*
     * The vector is sized to the caller-supplied page count and capped
     * at a reasonable bound to prevent a runaway allocation if the
     * caller's page count is somehow absurd. The 64 MiB cap corresponds
     * to a 256 GiB mapped region at 4 KiB pages, which is larger than
     * any MappedByteBuffer the JDK will produce on a 64-bit system.
     */
    const size_t MAX_PAGES = 64u * 1024u * 1024u;
    size_t n = (size_t)page_count;
    if (n > MAX_PAGES) n = MAX_PAGES;

    unsigned char* vec = (unsigned char*)malloc(n);
    if (vec == NULL) {
        __jnative_throw_out_of_memory_error_ctx("MappedMemoryUtils.isLoaded0");
        return 0;
    }

    int rc = mincore((void*)(intptr_t)address, (size_t)length, vec);
    if (rc < 0) {
        free(vec);
        return 0;
    }

    int all_resident = 1;
    for (size_t i = 0; i < n; i++) {
        if ((vec[i] & 0x01) == 0) {
            all_resident = 0;
            break;
        }
    }
    free(vec);
    return all_resident;
}

/*
 * private static native void load0(long address, long length);
 *
 * Advises the kernel that the pages backing the given address range
 * will be accessed soon, so they can be paged in ahead of the first
 * fault. The primitive is madvise(MADV_WILLNEED) on Linux; every
 * other Unix this runtime targets provides an equivalent, but the
 * operation is advisory on all of them and a failure to perform it
 * is never observable to the caller.
 *
 * The address and length are exactly as they were passed to mmap(2)
 * when the mapping was established. A zero address or a non-positive
 * length is treated as "nothing to advise on" and succeeds silently:
 * the Java-side MappedByteBuffer.load can race with the Cleaner's
 * munmap, and both sides must tolerate that race without turning it
 * into a spurious IOException.
 */
void __jnative_fn_java_nio_MappedMemoryUtils_load0__JJ_V(
        int64_t address, int64_t length)
{
    if (address == 0 || length <= 0) {
        return;
    }
    (void)madvise((void*)(intptr_t)address, (size_t)length, MADV_WILLNEED);
}
