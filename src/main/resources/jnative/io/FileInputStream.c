#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#include "jnative_runtime.h"

/*
 * Object layout used by this runtime for java.io.FileInputStream:
 *
 *   [ 8-byte vtable ][ FileDescriptor fd ][ String path ][ Object closeLock ][ boolean closed ]
 *
 * The FileDescriptor's own layout is fixed by jnative_runtime.h:
 *
 *   [ 8-byte vtable ][ int32 fd ][ long handle ]
 *
 * Consequently:
 *   this     + FIS_FD_OFFSET  -> FileDescriptor object pointer
 *   fd_obj   + FD_OFFSET      -> raw kernel file descriptor (int32_t)
 *
 * The FileInputStream constructor allocates the FileDescriptor itself
 * (`fd = new FileDescriptor()`) before calling open0(name), so open0 only
 * needs to write the kernel descriptor into the already-existing object.
 */
#define FIS_FD_OFFSET 8

static inline void* fis_fd_object(void* this_fis) {
    return *(void**)((char*)this_fis + FIS_FD_OFFSET);
}

static inline int32_t fis_raw_fd(void* this_fis) {
    void* fd_obj = fis_fd_object(this_fis);
    if (fd_obj == NULL) return -1;
    return jnative_fd_of(fd_obj);
}

/*
 * Serve the JDK's compiled TZDB from the embedded resource table.
 *
 * ZoneInfoFile.<clinit> opens <java.home>/lib/tzdb.dat through an
 * ordinary FileInputStream. In an AOT image the executable may be
 * running on a machine with no JDK, or with a different JDK release
 * whose tzdb.dat uses a newer binary format than the ZoneInfoFile
 * class compiled into the image was written to parse. Both produce
 * the same StreamCorruptedException("File format not recognised")
 * from ZoneInfoFile.load(DataInputStream).
 *
 * The fix is to intercept the open of any path ending in
 * "/lib/tzdb.dat" and hand the caller an anonymous file descriptor
 * backed by the build JDK's copy of that file. The bytes are looked
 * up by a fixed key ("__jdk_internal__/tzdb.dat") in the runtime's
 * resource table, so the same hand-off works no matter what
 * java.home resolves to at run time.
 *
 * On Linux the anonymous descriptor is a memfd (memfd_create(2)),
 * which is a real file descriptor that supports read(2), lseek(2)
 * and mmap(2) with no backing filesystem entry. On every other
 * platform the fallback is mkstemp(3) followed by an immediate
 * unlink(2): the descriptor stays valid for its holder and the
 * filesystem entry disappears the moment it is created, so nothing
 * leaks if the process crashes before the descriptor is closed.
 *
 * Returns a non-negative file descriptor positioned at offset 0 on
 * success, or -1 if the resource is absent or any step failed. A -1
 * return is not an error: the caller falls through to the ordinary
 * open(2) path and lets the filesystem produce whatever answer it
 * would have produced anyway.
 */
static int try_open_embedded_tzdb(const char* path) {
    if (path == NULL) {
        return -1;
    }

    /*
     * Match the exact suffix "/lib/tzdb.dat". The JDK's own
     * ZoneInfoFile always constructs the path as
     * <java.home> + File.separator + "lib" + File.separator + "tzdb.dat",
     * so this suffix is the tightest possible match that does not
     * require the C side to know java.home.
     */
    static const char SUFFIX[] = "/lib/tzdb.dat";
    size_t plen = strlen(path);
    size_t slen = sizeof(SUFFIX) - 1;
    if (plen < slen) {
        return -1;
    }
    if (memcmp(path + plen - slen, SUFFIX, slen) != 0) {
        return -1;
    }

    static const char KEY[] = "__jdk_internal__/tzdb.dat";
    const JNativeResourceEntry* res =
        jnative_find_resource(KEY, (int32_t)(sizeof(KEY) - 1));
    if (res == NULL || res->data == NULL || res->size <= 0) {
        return -1;
    }

    int fd;
#if defined(__linux__) && defined(MFD_CLOEXEC)
    fd = memfd_create("jnative-tzdb", MFD_CLOEXEC);
    if (fd < 0) {
        return -1;
    }
#else
    char tmpl[] = "/tmp/jnative_tzdb_XXXXXX";
    fd = mkstemp(tmpl);
    if (fd < 0) {
        return -1;
    }
    /* Unlink immediately: the descriptor keeps the file alive, and
     * nothing in the filesystem can observe it. */
    (void)unlink(tmpl);
    /* Clear FD_CLOEXEC only if mkstemp did not already set it; the
     * descriptor is meant to outlive any child process. Actually we
     * want CLOEXEC on, matching memfd_create's flag, so nothing to
     * do here. */
#endif

    const uint8_t* p = (const uint8_t*)res->data;
    size_t remaining = (size_t)res->size;
    while (remaining > 0) {
        ssize_t n = write(fd, p, remaining);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        if (n == 0) {
            /* write(2) returning 0 for a non-zero count on a regular
             * file is not a legitimate outcome; treat it as failure
             * rather than looping forever. */
            close(fd);
            return -1;
        }
        p += n;
        remaining -= (size_t)n;
    }

    if (lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/*
 * private native void open0(String name) throws FileNotFoundException;
 *
 * Opens the named file read-only and stores the resulting kernel fd in the
 * already-allocated FileDescriptor. On failure the original Java layer's
 * contract is to throw FileNotFoundException; since this runtime's throw
 * helpers do not yet materialise the full exception object graph, the
 * generic throw is used, which propagates cleanly through the generated
 * setjmp-based catch machinery.
 */
void __jnative_fn_java_io_FileInputStream_open0__Ljava_lang_String__V(
        void* this_fis, void* name_str)
{
    if (this_fis == NULL || name_str == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    void* fd_obj = fis_fd_object(this_fis);
    if (fd_obj == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    int32_t nameLen = 0;
    const char* path = __jnative_read_string_bytes(name_str, &nameLen);
    (void)nameLen;
    if (path == NULL) {
        __jnative_throw_null_pointer_exception();
        return;
    }

    /*
     * Serve the JDK's compiled time-zone database from the embedded
     * resource table. The check is a cheap suffix comparison; a miss
     * falls straight through to the ordinary open(2). See the
     * try_open_embedded_tzdb comment for the full reasoning and for
     * why the file descriptor this returns is indistinguishable from
     * one open(2) produced.
     */
    int fd = try_open_embedded_tzdb(path);
    if (fd < 0) {
        fd = open(path, O_RDONLY);
    }
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return;
    }

    *(int32_t*)((char*)fd_obj + FD_OFFSET) = fd;
}

/*
 * private native int read0() throws IOException;
 *
 * Reads a single byte from the file's current offset. Returns the byte
 * value in the range 0..255, or -1 on end of file. Every EINTR is retried
 * internally so the Java caller never observes a spurious short read
 * caused by a signal.
 */
int32_t __jnative_fn_java_io_FileInputStream_read0___I(void* this_fis)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return -1;
    }
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return -1;
    }

    uint8_t byte;
    ssize_t n;
    do {
        n = read(fd, &byte, 1);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
        return -1;
    }
    if (n == 0) {
        return -1;
    }
    return (int32_t)byte;
}

/*
 * private native int readBytes(byte[] b, int off, int len) throws IOException;
 *
 * Bulk read into a byte array. Returns the number of bytes actually read
 * (0 on end of file, never more than len).
 *
 * The payload of a Java byte[] starts at JAVA_ARR_HDR, the same offset
 * the runtime's array factories and the LLVM emitter use.
 */
int32_t __jnative_fn_java_io_FileInputStream_readBytes___BII_I(
        void* this_fis, void* b, int32_t off, int32_t len)
{
    if (this_fis == NULL || b == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    if (off < 0 || len < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    if (len == 0) {
        return 0;
    }

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    uint8_t* dst = (uint8_t*)b + JAVA_ARR_HDR + off;
    ssize_t n;
    do {
        n = read(fd, dst, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int32_t)n;
}

/*
 * private native int available0() throws IOException;
 *
 * Returns a non-negative estimate of the number of bytes that can be read
 * without blocking. For regular files this is the exact remaining length
 * (or FIONREAD, whichever the platform reports first); for other kinds of
 * descriptor (pipes, character devices) FIONREAD is authoritative. A
 * failure of both queries is treated as "unknown", reported as 0, matching
 * the contract of InputStream.available for a stream whose size cannot be
 * determined.
 */
int32_t __jnative_fn_java_io_FileInputStream_available0___I(void* this_fis)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        return 0;
    }

    int avail = 0;
    if (ioctl(fd, FIONREAD, &avail) == 0 && avail > 0) {
        return avail;
    }

    struct stat st;
    if (fstat(fd, &st) != 0) {
        return avail > 0 ? avail : 0;
    }

    off_t pos = lseek(fd, 0, SEEK_CUR);
    if (pos < 0) {
        return avail > 0 ? avail : 0;
    }

    off_t remaining = st.st_size - pos;
    if (remaining < 0) remaining = 0;
    if (remaining > INT32_MAX) remaining = INT32_MAX;
    return (int32_t)remaining;
}

/*
 * private native long length0() throws IOException;
 *
 * Total length in bytes of the underlying file. Uses fstat(2) so it works
 * for any file descriptor, not just those opened on a named path.
 */
int64_t __jnative_fn_java_io_FileInputStream_length0___J(void* this_fis)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    struct stat st;
    if (fstat(fd, &st) != 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int64_t)st.st_size;
}

/*
 * private native long position0() throws IOException;
 *
 * The current read offset in the underlying file, queried from the kernel
 * with lseek(SEEK_CUR). Because the runtime never maintains a shadow copy
 * of the offset for FileInputStream (unlike RandomAccessFile), the kernel
 * value is authoritative.
 */
int64_t __jnative_fn_java_io_FileInputStream_position0___J(void* this_fis)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }
    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    off_t pos = lseek(fd, 0, SEEK_CUR);
    if (pos < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }
    return (int64_t)pos;
}

/*
 * private static native void initIDs();
 *
 * Called from the static initializer of java.io.FileInputStream to cache
 * the JNI field IDs of the class's instance fields. This runtime accesses
 * every field through its LLVM-computed byte offset and never consults JNI
 * field IDs, so there is nothing to cache. The symbol must exist because
 * the class's <clinit> emits a native call to it.
 */
void __jnative_fn_java_io_FileInputStream_initIDs___V(void) {
}

int64_t __jnative_fn_java_io_FileInputStream_skip0__J_J(
        void* this_fis, int64_t n)
{
    if (this_fis == NULL) {
        __jnative_throw_null_pointer_exception();
        return 0;
    }

    if (n <= 0) {
        return 0;
    }

    int32_t fd = fis_raw_fd(this_fis);
    if (fd < 0) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    off_t current = lseek(fd, 0, SEEK_CUR);
    if (current == (off_t)-1) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    /*
     * FileInputStream.skip() should move forward from the current
     * position. lseek() may fail if the descriptor is not seekable.
     */
    off_t offset = (off_t)n;

    if ((int64_t)offset != n) {
        /*
         * The requested Java long cannot be represented by off_t.
         * Clamp it to the largest positive off_t value.
         */
        if (n > 0) {
            offset = (off_t)(((uint64_t)1 << (sizeof(off_t) * 8 - 1)) - 1);
        }
    }

    off_t target = lseek(fd, offset, SEEK_CUR);
    if (target == (off_t)-1) {
        __jnative_throw_exception(NULL);
        return 0;
    }

    return (int64_t)(target - current);
}