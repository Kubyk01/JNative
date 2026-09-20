#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <errno.h>
#include <sys/sendfile.h>
#include <sys/types.h>

__attribute__((noreturn)) void __jnative_throw_exception(void* exc);
__attribute__((noreturn)) void __jnative_throw_null_pointer_exception(void);

/*
 * FileDescriptor layout in this runtime:
 *   [ 8 bytes vtable ][ int32 fd ][ long handle ]
 *
 * The raw kernel fd is the first instance field, at offset 8.
 */
#define FD_OFFSET 8

static inline int32_t fd_of(void* fd_obj) {
    return *(int32_t*)((char*)fd_obj + FD_OFFSET);
}

static int32_t raw_fd(void* fd_obj) {
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
    }
    int32_t fd = fd_of(fd_obj);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
    }
    return fd;
}

/* -------------------------------------------------------------------------
 * static native void init0();
 *
 * Called from FileDispatcherImpl.<clinit>. On HotSpot this hook caches
 * the JNI field IDs used by the transfer* family of natives. This
 * runtime accesses every field through its LLVM-computed byte offset and
 * never consults JNI field IDs, so there is nothing to cache.
 * ----------------------------------------------------------------------- */
void __jnative_fn_sun_nio_ch_FileDispatcherImpl_init0___V(void) {
}

/*
 * -------------------------------------------------------------------------
 * Zero-copy bulk transfer between two file descriptors.
 *
 * The JDK's FileChannel offers two directions, and the underlying
 * dispatcher declares a native for each:
 *
 *   transferTo0  (FileDescriptor src, long position, long count,
 *                 FileDescriptor dst, boolean isTransferTo)
 *       -> long
 *
 *   transferFrom0(FileDescriptor src, FileDescriptor dst,
 *                 long position, long count, boolean isTransferTo)
 *       -> long
 *
 * Both end up as a single sendfile(2) call on Linux:
 *
 *     ssize_t sendfile(int out_fd, int in_fd, off_t *offset, size_t count);
 *
 * `out_fd` is the destination, `in_fd` is the source. The `offset`
 * argument is the byte position in `in_fd` from which the transfer
 * begins. The Java API uses `position < 0` to mean "start at the
 * current file position and advance it as bytes are transferred";
 * passing NULL as the offset argument gives exactly that behaviour. A
 * non-negative `position` is a byte offset within the source that must
 * be honoured without disturbing the source's file pointer — sendfile
 * does that when a valid offset pointer is supplied.
 *
 * `count` is a Java long, but sendfile caps a single call at
 * approximately 0x7ffff000 bytes on Linux (the value is derived from
 * the maximum size of a single read/write transaction the kernel will
 * accept). The caller loops until it has moved every requested byte; we
 * clamp here so an oversized count does not cause sendfile to return
 * EINVAL.
 *
 * EINTR is retried transparently, so a signal delivered during the
 * kernel-side copy does not surface as a spurious IOException to the
 * Java caller.
 *
 * Any hard error (EBADF, EINVAL, ENOMEM, a closed descriptor) is
 * reported through the generic throw helper, which the Java side's
 * `catch (IOException x)` block turns into the appropriate exception.
 * The return value is the number of bytes actually transferred, which
 * the Java layer accumulates across loop iterations.
 * -------------------------------------------------------------------------
 */
#define TRANSFER_FROM_MAX (0x7ffff000L)

/* -------------------------------------------------------------------------
 * static native long transferTo0(FileDescriptor src,
 *                                long position,
 *                                long count,
 *                                FileDescriptor dst,
 *                                boolean isTransferTo);
 *
 * Transfer `count` bytes starting at `position` in `src` into `dst`.
 * The argument order matches the JDK's own declaration on every
 * platform; the trailing boolean is informational and unused by the
 * native body — it exists so that a single native signature can serve
 * both FileChannel.transferTo and FileChannel.transferFrom on some
 * JDK versions.
 * ----------------------------------------------------------------------- */
int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferTo0__Ljava_io_FileDescriptor_JJLjava_io_FileDescriptor_Z_J(
        void* src_fd_obj, int64_t position, int64_t count,
        void* dst_fd_obj, int32_t isTransferTo)
{
    (void)isTransferTo;

    int32_t src_fd = raw_fd(src_fd_obj);
    int32_t dst_fd = raw_fd(dst_fd_obj);

    if (count < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    if (count == 0) {
        return 0;
    }

    /* Clamp to the maximum single-call size that Linux's sendfile will
     * accept. The Java layer loops until `count` bytes have been
     * transferred, so this clamp does not change the total amount the
     * caller sees. */
    size_t nbytes = (size_t)count;
    if (nbytes > (size_t)TRANSFER_FROM_MAX) {
        nbytes = (size_t)TRANSFER_FROM_MAX;
    }

    off_t  off_storage = (off_t)position;
    off_t* off_ptr = (position < 0) ? NULL : &off_storage;

    ssize_t n;
    do {
        n = sendfile(dst_fd, src_fd, off_ptr, nbytes);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int64_t)n;
}

/* -------------------------------------------------------------------------
 * static native long transferFrom0(FileDescriptor src,
 *                                  FileDescriptor dst,
 *                                  long position,
 *                                  long count,
 *                                  boolean isTransferTo);
 *
 * FileChannel.transferFrom(src, position, count, dst) calls this with
 * (dst, src, position, count, false) after swapping its own argument
 * order; FileChannel.transferTo uses the transferTo0 entry point above.
 * The first argument is therefore always the *destination* and the
 * second is always the *source*, regardless of which direction the
 * Java caller invoked.
 *
 * The remaining logic is identical to transferTo0 — the same sendfile
 * call, the same clamping, the same EINTR retry, the same throw helper
 * on hard errors. The two entry points are kept separate rather than
 * sharing a single body because their argument orders differ and
 * dispatching between them at runtime would only obscure the mapping
 * between the Java declaration and the C definition.
 * ----------------------------------------------------------------------- */
int64_t __jnative_fn_sun_nio_ch_FileDispatcherImpl_transferFrom0__Ljava_io_FileDescriptor_Ljava_io_FileDescriptor_JJZ_J(
        void* src_fd_obj, void* dst_fd_obj,
        int64_t position, int64_t count,
        int32_t isTransferTo)
{
    (void)isTransferTo;

    int32_t dst_fd = raw_fd(dst_fd_obj);
    int32_t src_fd = raw_fd(src_fd_obj);

    if (count < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    if (count == 0) {
        return 0;
    }

    size_t nbytes = (size_t)count;
    if (nbytes > (size_t)TRANSFER_FROM_MAX) {
        nbytes = (size_t)TRANSFER_FROM_MAX;
    }

    off_t  off_storage = (off_t)position;
    off_t* off_ptr = (position < 0) ? NULL : &off_storage;

    ssize_t n;
    do {
        n = sendfile(dst_fd, src_fd, off_ptr, nbytes);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int64_t)n;
}