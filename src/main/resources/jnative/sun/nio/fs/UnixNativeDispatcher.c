#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <limits.h>

#if defined(_WIN32)
#  include <wchar.h>
#  include <io.h>
#  include <fcntl.h>
#  include <sys/stat.h>
#  include <sys/utime.h>
#  include <winioctl.h>
#else
#  include <unistd.h>
#  include <fcntl.h>
#  include <dirent.h>
#  include <sys/stat.h>
#  include <sys/types.h>
#  include <sys/time.h>
#  include <sys/statvfs.h>
#  include <utime.h>
#  include <pwd.h>
#  include <grp.h>
#endif

#if defined(__linux__)
#  include <sys/xattr.h>      /* getxattr, setxattr, listxattr, removexattr
                              * and the f* / l* variants */
#  include <sys/sendfile.h>   /* sendfile */
#endif

#include "jnative_runtime.h"

/* -------------------------------------------------------------------------
 *  Linux open(2) / access(2) / at(2) constants. These mirror the
 *  compiled bytecode's view of the POSIX ABI; the compiled bytecode
 *  always uses the Linux numeric values, regardless of target.
 * ------------------------------------------------------------------------- */
#define LNX_O_ACCMODE   3
#define LNX_O_RDONLY    0
#define LNX_O_WRONLY    1
#define LNX_O_RDWR      2
#define LNX_O_CREAT     00000100
#define LNX_O_EXCL      00000200
#define LNX_O_TRUNC     00001000
#define LNX_O_APPEND    00002000
#define LNX_O_NONBLOCK  00004000

#define LNX_AT_FDCWD            (-100)
#define LNX_AT_SYMLINK_NOFOLLOW 0x100
#define LNX_AT_REMOVEDIR        0x200

#define LNX_F_OK  0
#define LNX_X_OK  1
#define LNX_W_OK  2
#define LNX_R_OK  4

/* -------------------------------------------------------------------------
 *  POSIX st_mode file-type bits. MinGW already defines S_IFMT / S_IFREG /
 *  S_IFBLK / S_IFDIR / S_IFCHR / S_IFIFO with the correct numeric values
 *  (as aliases for _S_IFMT etc.), so the #ifndef guards keep the system
 *  header authoritative for those. Only S_IFSOCK and S_IFLNK are absent
 *  from MinGW and need to be provided here.
 * ------------------------------------------------------------------------- */
#ifndef S_IFMT
#define S_IFMT   0170000
#endif
#ifndef S_IFSOCK
#define S_IFSOCK 0140000
#endif
#ifndef S_IFLNK
#define S_IFLNK  0120000
#endif
#ifndef S_IFREG
#define S_IFREG  0100000
#endif
#ifndef S_IFBLK
#define S_IFBLK  0060000
#endif
#ifndef S_IFDIR
#define S_IFDIR  0040000
#endif
#ifndef S_IFCHR
#define S_IFCHR  0020000
#endif
#ifndef S_IFIFO
#define S_IFIFO  0010000
#endif

/* -------------------------------------------------------------------------
 *  Linux errno values the compiled bytecode may hand to strerror().
 * ------------------------------------------------------------------------- */
#define LINUX_ENOENT        2
#define LINUX_EBADF         9
#define LINUX_EAGAIN       11
#define LINUX_ENOMEM       12
#define LINUX_EACCES       13
#define LINUX_EEXIST       17
#define LINUX_EXDEV        18
#define LINUX_ENOTDIR      20
#define LINUX_EINVAL       22
#define LINUX_ENOSPC       28
#define LINUX_ENAMETOOLONG 36
#define LINUX_ENOTEMPTY    39
#define LINUX_ENOTSUP      95

/* -------------------------------------------------------------------------
 *  UnixFileAttributes field offsets (JDK 21 layout):
 *
 *      +8    int32_t  st_mode
 *      +16   int64_t  st_ino
 *      +24   int64_t  st_dev
 *      +32   int64_t  st_rdev
 *      +40   int32_t  st_nlink
 *      +44   int32_t  st_uid
 *      +48   int32_t  st_gid
 *      +56   int64_t  st_size
 *      +64   int64_t  st_atime_sec
 *      +72   int64_t  st_atime_nsec
 *      +80   int64_t  st_mtime_sec
 *      +88   int64_t  st_mtime_nsec
 *      +96   int64_t  st_ctime_sec
 *      +104  int64_t  st_ctime_nsec
 *      +112  int64_t  st_birthtime_sec
 * ------------------------------------------------------------------------- */
#define UFA_ST_MODE_OFFSET          8
#define UFA_ST_INO_OFFSET          16
#define UFA_ST_DEV_OFFSET          24
#define UFA_ST_RDEV_OFFSET         32
#define UFA_ST_NLINK_OFFSET        40
#define UFA_ST_UID_OFFSET          44
#define UFA_ST_GID_OFFSET          48
#define UFA_ST_SIZE_OFFSET         56
#define UFA_ST_ATIME_SEC_OFFSET    64
#define UFA_ST_ATIME_NSEC_OFFSET   72
#define UFA_ST_MTIME_SEC_OFFSET    80
#define UFA_ST_MTIME_NSEC_OFFSET   88
#define UFA_ST_CTIME_SEC_OFFSET    96
#define UFA_ST_CTIME_NSEC_OFFSET  104
#define UFA_ST_BIRTHTIME_OFFSET   112

/* -------------------------------------------------------------------------
 *  UnixFileStoreAttributes field offsets (JDK 21 layout):
 *
 *      +8    int64_t  f_bsize
 *      +16   int64_t  f_frsize
 *      +24   int64_t  f_blocks
 *      +32   int64_t  f_bfree
 *      +40   int64_t  f_bavail
 * ------------------------------------------------------------------------- */
#define UFSAT_BSIZE_OFFSET     8
#define UFSAT_FRSIZE_OFFSET   16
#define UFSAT_BLOCKS_OFFSET   24
#define UFSAT_BFREE_OFFSET    32
#define UFSAT_BAVAIL_OFFSET   40

/* ============================================================================
 *  Shared exception thrower
 * ========================================================================= */
static void throw_unix_exception(int linux_errno) {
    ReflectionClass* cls = jnative_class_by_name("sun/nio/fs/UnixException");
    if (cls != NULL) {
        void* exc = jnative_alloc_object(cls);
        if (exc != NULL) {
            typedef void (*ctor_int_t)(void*, int32_t);
            ctor_int_t ctor_i = (ctor_int_t)jn_dlsym_default(
                "fn_sun_nio_fs_UnixException__init__I_V");
            if (ctor_i != NULL) {
                ctor_i(exc, linux_errno);
                __jnative_throw_exception(exc);
            }

            char msg[96];
            snprintf(msg, sizeof(msg), "UnixException (errno %d)", linux_errno);
            void* msg_obj = __jnative_make_string_obj(msg, (int32_t)strlen(msg));
            typedef void (*ctor_str_t)(void*, void*);
            ctor_str_t ctor_s = (ctor_str_t)jn_dlsym_default(
                "fn_sun_nio_fs_UnixException__init__Ljava_lang_String__V");
            if (ctor_s != NULL) {
                ctor_s(exc, msg_obj);
                __jnative_throw_exception(exc);
            }
            free(msg_obj);
            free(exc);
        }
    }

    char buf[96];
    snprintf(buf, sizeof(buf), "UnixNativeDispatcher (errno %d)", linux_errno);
    __jnative_throw_io_ctx(buf, "UnixNativeDispatcher");
}

/* POSIX errno path (also correct on MinGW, where CRT calls populate
 * errno with the POSIX numeric value). */
static void throw_unix_exception_from_errno(void) {
    throw_unix_exception(errno);
}

/* ============================================================================
 *  Windows implementation
 * ========================================================================= */
#if defined(_WIN32)

int jn_win_err_to_linux_errno(unsigned long err) {
    switch (err) {
        case ERROR_FILE_NOT_FOUND:      return LINUX_ENOENT;
        case ERROR_PATH_NOT_FOUND:      return LINUX_ENOENT;
        case ERROR_ACCESS_DENIED:       return LINUX_EACCES;
        case ERROR_FILE_EXISTS:         return LINUX_EEXIST;
        case ERROR_ALREADY_EXISTS:      return LINUX_EEXIST;
        case ERROR_DISK_FULL:           return LINUX_ENOSPC;
        case ERROR_NOT_ENOUGH_MEMORY:   return LINUX_ENOMEM;
        case ERROR_INVALID_HANDLE:      return LINUX_EBADF;
        case ERROR_INVALID_PARAMETER:   return LINUX_EINVAL;
        case ERROR_DIRECTORY:           return LINUX_ENOTDIR;
        case ERROR_READ_FAULT:          return LINUX_EAGAIN;
        case ERROR_WRITE_FAULT:         return LINUX_EAGAIN;
        case ERROR_DIR_NOT_EMPTY:       return LINUX_ENOTEMPTY;
        case ERROR_NOT_SAME_DEVICE:     return LINUX_EXDEV;
        case ERROR_SHARING_VIOLATION:   return LINUX_EAGAIN;
        case ERROR_LOCK_VIOLATION:      return LINUX_EAGAIN;
        case ERROR_BUFFER_OVERFLOW:     return LINUX_ENAMETOOLONG;
        default:                        return LINUX_ENOENT;
    }
}

static void throw_unix_exception_from_win(void) {
    throw_unix_exception(jn_win_err_to_linux_errno(GetLastError()));
}

/* -------------------------------------------------------------------------
 *  CRT fd → Win32 HANDLE. All fds produced by this translation unit are
 *  CRT fds (they come from _open_osfhandle / _dup / _open), so
 *  _get_osfhandle is the correct inverse. A stale or invalid fd is
 *  reported as INVALID_HANDLE_VALUE, which every consumer already treats
 *  as EBADF.
 * ------------------------------------------------------------------------- */
HANDLE jn_win_fd_to_handle(int fd) {
    if (fd < 0) return INVALID_HANDLE_VALUE;
    intptr_t h = _get_osfhandle(fd);
    if (h == -1) return INVALID_HANDLE_VALUE;
    return (HANDLE)h;
}

/* -------------------------------------------------------------------------
 *  UTF-8 C string at raw address → wide-path buffer.
 * ------------------------------------------------------------------------- */
static int win_path_from_addr(int64_t pathAddress, wchar_t* out, int cap) {
    if (pathAddress == 0 || cap <= 0) return -1;
    const char* utf8 = (const char*)(intptr_t)pathAddress;
    int w = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, out, cap);
    return (w > 0) ? w - 1 : -1;
}

/* FILETIME → Unix-epoch seconds + nano fraction. */
static void win_filetime_to_unix(FILETIME ft,
                                 int64_t* sec_out, int64_t* nsec_out) {
    ULARGE_INTEGER u;
    u.LowPart  = ft.dwLowDateTime;
    u.HighPart = ft.dwHighDateTime;
    int64_t hundred_ns = (int64_t)u.QuadPart - 116444736000000000LL;
    *sec_out  = hundred_ns / 10000000LL;
    *nsec_out = (hundred_ns % 10000000LL) * 100LL;
}

static void fill_unix_file_attrs_from_handle_info(
        void* attrs, const BY_HANDLE_FILE_INFORMATION* info)
{
    if (attrs == NULL) return;
    char* base = (char*)attrs;

    int is_dir = (info->dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
    int is_ro  = (info->dwFileAttributes & FILE_ATTRIBUTE_READONLY)  != 0;
    int32_t st_mode = (is_dir ? S_IFDIR : S_IFREG) |
                      (is_ro  ? 0444    : 0644);

    int64_t ino  = ((int64_t)info->nFileIndexHigh << 32) |
                   (int64_t)info->nFileIndexLow;
    int64_t size = ((int64_t)info->nFileSizeHigh   << 32) |
                   (int64_t)info->nFileSizeLow;

    int64_t at_s, at_ns, mt_s, mt_ns, ct_s, ct_ns;
    win_filetime_to_unix(info->ftLastAccessTime, &at_s, &at_ns);
    win_filetime_to_unix(info->ftLastWriteTime,  &mt_s, &mt_ns);
    win_filetime_to_unix(info->ftCreationTime,   &ct_s, &ct_ns);

    *(int32_t*)(base + UFA_ST_MODE_OFFSET)  = st_mode;
    *(int64_t*)(base + UFA_ST_INO_OFFSET)   = ino;
    *(int64_t*)(base + UFA_ST_DEV_OFFSET)   = (int64_t)info->dwVolumeSerialNumber;
    *(int64_t*)(base + UFA_ST_RDEV_OFFSET)  = 0;
    *(int32_t*)(base + UFA_ST_NLINK_OFFSET) = (int32_t)info->nNumberOfLinks;
    *(int32_t*)(base + UFA_ST_UID_OFFSET)   = 0;
    *(int32_t*)(base + UFA_ST_GID_OFFSET)   = 0;
    *(int64_t*)(base + UFA_ST_SIZE_OFFSET)  = size;

    *(int64_t*)(base + UFA_ST_ATIME_SEC_OFFSET)  = at_s;
    *(int64_t*)(base + UFA_ST_ATIME_NSEC_OFFSET) = at_ns;
    *(int64_t*)(base + UFA_ST_MTIME_SEC_OFFSET)  = mt_s;
    *(int64_t*)(base + UFA_ST_MTIME_NSEC_OFFSET) = mt_ns;
    *(int64_t*)(base + UFA_ST_CTIME_SEC_OFFSET)  = ct_s;
    *(int64_t*)(base + UFA_ST_CTIME_NSEC_OFFSET) = ct_ns;
    *(int64_t*)(base + UFA_ST_BIRTHTIME_OFFSET)  = ct_s;
}

static int fill_unix_file_attrs_from_path(
        void* attrs, const wchar_t* wpath, int follow_symlinks)
{
    DWORD flags = FILE_FLAG_BACKUP_SEMANTICS;
    if (!follow_symlinks) flags |= FILE_FLAG_OPEN_REPARSE_POINT;

    HANDLE h = CreateFileW(wpath, 0,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           NULL, OPEN_EXISTING, flags, NULL);
    if (h == INVALID_HANDLE_VALUE) return -1;

    BY_HANDLE_FILE_INFORMATION info;
    int ok = GetFileInformationByHandle(h, &info);
    CloseHandle(h);
    if (!ok) return -1;

    if (!follow_symlinks &&
        (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) {
        fill_unix_file_attrs_from_handle_info(attrs, &info);
        *(int32_t*)((char*)attrs + UFA_ST_MODE_OFFSET) = S_IFLNK | 0777;
        return 0;
    }

    fill_unix_file_attrs_from_handle_info(attrs, &info);
    return 0;
}

static int fill_unix_file_attrs_from_fd(void* attrs, int fd) {
    HANDLE h = jn_win_fd_to_handle(fd);
    if (h == INVALID_HANDLE_VALUE) return -1;
    BY_HANDLE_FILE_INFORMATION info;
    if (!GetFileInformationByHandle(h, &info)) return -1;
    fill_unix_file_attrs_from_handle_info(attrs, &info);
    return 0;
}

/* Directory stream container. */
typedef struct {
    HANDLE            hfind;
    WIN32_FIND_DATAW  cur;
    int               first;
} JnDirStream;

static void* wrap_dir_stream(HANDLE hfind, WIN32_FIND_DATAW* first) {
    JnDirStream* ds = (JnDirStream*)calloc(1, sizeof(JnDirStream));
    if (ds == NULL) return NULL;
    ds->hfind = hfind;
    ds->cur   = *first;
    ds->first = 1;
    return ds;
}

/* ---- Public natives (Windows) ---- */

/* init() returns int (0 on success; the JDK throws on non-zero). Also
 * provide the old void form defensively. */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___I(void) {
    return 0;
}
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___V(void) { }

/* ---- open / dup / close ---- */

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_open0__JII_I(
        int64_t pathAddress, int32_t flags, int32_t mode)
{
    (void)mode;
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        return -LINUX_ENAMETOOLONG;

    DWORD access;
    switch (flags & LNX_O_ACCMODE) {
        case LNX_O_RDONLY: access = GENERIC_READ; break;
        case LNX_O_WRONLY: access = GENERIC_WRITE; break;
        case LNX_O_RDWR:   access = GENERIC_READ | GENERIC_WRITE; break;
        default:           access = GENERIC_READ; break;
    }

    DWORD disp;
    int has_creat = (flags & LNX_O_CREAT) != 0;
    int has_excl  = (flags & LNX_O_EXCL)  != 0;
    int has_trunc = (flags & LNX_O_TRUNC) != 0;
    if (has_creat && has_excl)  disp = CREATE_NEW;
    else if (has_creat)         disp = has_trunc ? CREATE_ALWAYS : OPEN_ALWAYS;
    else if (has_trunc)         disp = TRUNCATE_EXISTING;
    else                        disp = OPEN_EXISTING;

    HANDLE h = CreateFileW(wpath, access,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           NULL, disp,
                           FILE_ATTRIBUTE_NORMAL | FILE_FLAG_BACKUP_SEMANTICS,
                           NULL);
    if (h == INVALID_HANDLE_VALUE)
        return -jn_win_err_to_linux_errno(GetLastError());

    int fd = _open_osfhandle((intptr_t)h, _O_BINARY);
    if (fd < 0) { CloseHandle(h); return -LINUX_ENOMEM; }
    return fd;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_openat0__IJII_I(
        int32_t dfd, int64_t pathAddress, int32_t flags, int32_t mode)
{
    (void)dfd;
    return __jnative_fn_sun_nio_fs_UnixNativeDispatcher_open0__JII_I(
        pathAddress, flags, mode);
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_dup__I_I(int32_t fd) {
    int nfd = _dup(fd);
    return (nfd >= 0) ? nfd : -LINUX_EBADF;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_close0__I_V(int32_t fd) {
    if (fd >= 0) _close(fd);
}

/* ---- read / write / pread / pwrite ---- */

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_read0__IJI_I(
        int32_t fd, int64_t buf, int32_t len)
{
    if (fd < 0) return -LINUX_EBADF;
    int n = _read(fd, (void*)(intptr_t)buf, (unsigned)len);
    return (n < 0) ? -errno : n;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_write0__IJI_I(
        int32_t fd, int64_t buf, int32_t len)
{
    if (fd < 0) return -LINUX_EBADF;
    int n = _write(fd, (void*)(intptr_t)buf, (unsigned)len);
    return (n < 0) ? -errno : n;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_pread0__IJIJ_I(
        int32_t fd, int64_t buf, int32_t len, int64_t offset)
{
    HANDLE h = jn_win_fd_to_handle(fd);
    if (h == INVALID_HANDLE_VALUE) return -LINUX_EBADF;
    OVERLAPPED ov;
    memset(&ov, 0, sizeof(ov));
    ov.Offset     = (DWORD)(offset & 0xFFFFFFFF);
    ov.OffsetHigh = (DWORD)((offset >> 32) & 0xFFFFFFFF);
    DWORD got = 0;
    if (!ReadFile(h, (void*)(intptr_t)buf, (DWORD)len, &got, &ov)) {
        DWORD err = GetLastError();
        if (err == ERROR_HANDLE_EOF) return 0;
        return -jn_win_err_to_linux_errno(err);
    }
    return (int32_t)got;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_pwrite0__IJIJ_I(
        int32_t fd, int64_t buf, int32_t len, int64_t offset)
{
    HANDLE h = jn_win_fd_to_handle(fd);
    if (h == INVALID_HANDLE_VALUE) return -LINUX_EBADF;
    OVERLAPPED ov;
    memset(&ov, 0, sizeof(ov));
    ov.Offset     = (DWORD)(offset & 0xFFFFFFFF);
    ov.OffsetHigh = (DWORD)((offset >> 32) & 0xFFFFFFFF);
    DWORD written = 0;
    if (!WriteFile(h, (void*)(intptr_t)buf, (DWORD)len, &written, &ov))
        return -jn_win_err_to_linux_errno(GetLastError());
    return (int32_t)written;
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lseek__IJI_J(
        int32_t fd, int64_t offset, int32_t whence)
{
    if (fd < 0) return -LINUX_EBADF;
    DWORD method = (whence == 0) ? FILE_BEGIN :
                   (whence == 1) ? FILE_CURRENT :
                   (whence == 2) ? FILE_END : 0xFFFFFFFFu;
    if (method == 0xFFFFFFFFu) return -LINUX_EINVAL;
    long long r = _lseeki64(fd, offset, method);
    return (r < 0) ? -errno : r;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_ftruncate__IJ_V(
        int32_t fd, int64_t size)
{
    HANDLE h = jn_win_fd_to_handle(fd);
    if (h == INVALID_HANDLE_VALUE) throw_unix_exception(LINUX_EBADF);
    LARGE_INTEGER li;
    li.QuadPart = size;
    if (!SetFilePointerEx(h, li, NULL, FILE_BEGIN))
        throw_unix_exception_from_win();
    if (!SetEndOfFile(h))
        throw_unix_exception_from_win();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fsync__I_V(int32_t fd) {
    HANDLE h = jn_win_fd_to_handle(fd);
    if (h != INVALID_HANDLE_VALUE) FlushFileBuffers(h);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fdatasync__I_V(int32_t fd) {
    __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fsync__I_V(fd);
}

/* ---- access / mkdir / rmdir / unlink ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_access0__JI_V(
        int64_t pathAddress, int32_t amode)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    int mode = 0;
    if (amode & LNX_R_OK) mode |= 4;
    if (amode & LNX_W_OK) mode |= 2;
    if (_waccess(wpath, mode) != 0)
        throw_unix_exception_from_errno();
}

/* boolean exists(UnixPath) — fast path, no exception on failure. */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_exists0__J_Z(
        int64_t pathAddress)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0) return 0;
    return (_waccess(wpath, 0) == 0) ? 1 : 0;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_mkdir0__JI_V(
        int64_t pathAddress, int32_t mode)
{
    (void)mode;
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    if (!CreateDirectoryW(wpath, NULL))
        throw_unix_exception_from_win();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rmdir0__J_V(
        int64_t pathAddress)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    if (!RemoveDirectoryW(wpath))
        throw_unix_exception_from_win();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlink0__J_V(
        int64_t pathAddress)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    if (!DeleteFileW(wpath))
        throw_unix_exception_from_win();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlinkat0__IJI_V(
        int32_t dfd, int64_t pathAddress, int32_t flags)
{
    (void)dfd;
    if (flags & LNX_AT_REMOVEDIR)
        __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rmdir0__J_V(pathAddress);
    else
        __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlink0__J_V(pathAddress);
}

/* ---- rename / renameat ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rename0__JJ_V(
        int64_t fromAddress, int64_t toAddress)
{
    wchar_t wfrom[4096], wto[4096];
    if (win_path_from_addr(fromAddress, wfrom, 4096) < 0 ||
        win_path_from_addr(toAddress,   wto,   4096) < 0) {
        throw_unix_exception(LINUX_ENAMETOOLONG);
    }
    if (!MoveFileExW(wfrom, wto,
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_COPY_ALLOWED))
        throw_unix_exception_from_win();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_renameat0__IJIJ_V(
        int32_t olddfd, int64_t oldAddress,
        int32_t newdfd, int64_t newAddress)
{
    (void)olddfd; (void)newdfd;
    __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rename0__JJ_V(
        oldAddress, newAddress);
}

/* ---- stat / fstat / lstat / fstatat ---- */

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_stat0__JLsun_nio_fs_UnixFileAttributes__I(
        int64_t pathAddress, void* attrs)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        return -LINUX_ENAMETOOLONG;
    if (fill_unix_file_attrs_from_path(attrs, wpath, 1) < 0)
        return -jn_win_err_to_linux_errno(GetLastError());
    return 0;
}

/* lstat returns void and throws on error (JDK 21 signature). */
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lstat0__JLsun_nio_fs_UnixFileAttributes__V(
        int64_t pathAddress, void* attrs)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    if (fill_unix_file_attrs_from_path(attrs, wpath, 0) < 0)
        throw_unix_exception_from_win();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstat0__ILsun_nio_fs_UnixFileAttributes__V(
        int32_t fd, void* attrs)
{
    if (fill_unix_file_attrs_from_fd(attrs, fd) < 0)
        throw_unix_exception_from_win();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstatat0__IJILsun_nio_fs_UnixFileAttributes__V(
        int32_t dfd, int64_t pathAddress, int32_t flag, void* attrs)
{
    (void)dfd;
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    int follow = (flag & LNX_AT_SYMLINK_NOFOLLOW) == 0;
    if (fill_unix_file_attrs_from_path(attrs, wpath, follow) < 0)
        throw_unix_exception_from_win();
}

/* ---- chmod / chown ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chmod0__JI_V(
        int64_t pathAddress, int32_t mode)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    DWORD attrs = GetFileAttributesW(wpath);
    if (attrs == INVALID_FILE_ATTRIBUTES)
        throw_unix_exception_from_win();
    if ((mode & 0200) == 0) attrs |= FILE_ATTRIBUTE_READONLY;
    else                    attrs &= ~FILE_ATTRIBUTE_READONLY;
    if (!SetFileAttributesW(wpath, attrs))
        throw_unix_exception_from_win();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchmod0__II_V(
        int32_t fd, int32_t mode) { (void)fd; (void)mode; }

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid)
{ (void)pathAddress; (void)uid; (void)gid; }

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lchown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid)
{ __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chown0__JII_V(pathAddress, uid, gid); }

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchown0__III_V(
        int32_t fd, int32_t uid, int32_t gid)
{ (void)fd; (void)uid; (void)gid; }

/* ---- times ---- */

static void win_set_file_times(HANDLE h, int64_t atime_sec, int64_t mtime_sec) {
    ULARGE_INTEGER ua, um;
    ua.QuadPart = (uint64_t)((atime_sec * 10000000LL) + 116444736000000000LL);
    um.QuadPart = (uint64_t)((mtime_sec * 10000000LL) + 116444736000000000LL);
    FILETIME fa, fm;
    fa.dwLowDateTime  = ua.LowPart;
    fa.dwHighDateTime = ua.HighPart;
    fm.dwLowDateTime  = um.LowPart;
    fm.dwHighDateTime = um.HighPart;
    SetFileTime(h, NULL, &fa, &fm);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_utimes0__JJJ_V(
        int64_t pathAddress, int64_t atime, int64_t mtime)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    HANDLE h = CreateFileW(wpath, FILE_WRITE_ATTRIBUTES,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           NULL, OPEN_EXISTING,
                           FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (h == INVALID_HANDLE_VALUE) throw_unix_exception_from_win();
    win_set_file_times(h, atime, mtime);
    CloseHandle(h);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lutimes0__JJJ_V(
        int64_t pathAddress, int64_t atime, int64_t mtime)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    HANDLE h = CreateFileW(wpath, FILE_WRITE_ATTRIBUTES,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           NULL, OPEN_EXISTING,
                           FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                           NULL);
    if (h == INVALID_HANDLE_VALUE) throw_unix_exception_from_win();
    win_set_file_times(h, atime, mtime);
    CloseHandle(h);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_futimes0__IJJ_V(
        int32_t fd, int64_t atime, int64_t mtime)
{
    HANDLE h = jn_win_fd_to_handle(fd);
    if (h == INVALID_HANDLE_VALUE) throw_unix_exception(LINUX_EBADF);
    win_set_file_times(h, atime, mtime);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_futimens0__IJJ_V(
        int32_t fd, int64_t atime, int64_t mtime)
{
    __jnative_fn_sun_nio_fs_UnixNativeDispatcher_futimes0__IJJ_V(fd, atime, mtime);
}

/* ---- directory streams ---- */

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_opendir0__J_J(
        int64_t pathAddress)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    size_t plen = wcslen(wpath);
    if (plen + 3 >= 4096) throw_unix_exception(LINUX_ENAMETOOLONG);
    if (plen > 0 && wpath[plen - 1] != L'\\' && wpath[plen - 1] != L'/')
        wpath[plen++] = L'\\';
    wpath[plen++] = L'*';
    wpath[plen]   = 0;

    WIN32_FIND_DATAW findData;
    HANDLE hf = FindFirstFileW(wpath, &findData);
    if (hf == INVALID_HANDLE_VALUE) throw_unix_exception_from_win();
    JnDirStream* ds = (JnDirStream*)wrap_dir_stream(hf, &findData);
    if (ds == NULL) { FindClose(hf); throw_unix_exception(LINUX_ENOMEM); }
    return (int64_t)(intptr_t)ds;
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fdopendir__I_J(int32_t fd) {
    HANDLE h = jn_win_fd_to_handle(fd);
    if (h == INVALID_HANDLE_VALUE) throw_unix_exception(LINUX_EBADF);

    wchar_t dir_path[4096];
    DWORD n = GetFinalPathNameByHandleW(h, dir_path, 4096, VOLUME_NAME_DOS);
    if (n == 0 || n >= 4096) throw_unix_exception_from_win();

    size_t plen = wcslen(dir_path);
    if (plen + 3 >= 4096) throw_unix_exception(LINUX_ENAMETOOLONG);
    if (plen > 0 && dir_path[plen - 1] != L'\\')
        dir_path[plen++] = L'\\';
    dir_path[plen++] = L'*';
    dir_path[plen]   = 0;

    WIN32_FIND_DATAW findData;
    HANDLE hf = FindFirstFileW(dir_path, &findData);
    if (hf == INVALID_HANDLE_VALUE) throw_unix_exception_from_win();
    JnDirStream* ds = (JnDirStream*)wrap_dir_stream(hf, &findData);
    if (ds == NULL) { FindClose(hf); throw_unix_exception(LINUX_ENOMEM); }
    return (int64_t)(intptr_t)ds;
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readdir0__J__B(int64_t dir) {
    JnDirStream* ds = (JnDirStream*)(intptr_t)dir;
    if (ds == NULL) throw_unix_exception(LINUX_EBADF);

    for (;;) {
        if (!ds->first) {
            if (!FindNextFileW(ds->hfind, &ds->cur)) {
                DWORD e = GetLastError();
                if (e == ERROR_NO_MORE_FILES) return NULL;
                throw_unix_exception(jn_win_err_to_linux_errno(e));
            }
        }
        ds->first = 0;

        if (wcscmp(ds->cur.cFileName, L".") == 0)  continue;
        if (wcscmp(ds->cur.cFileName, L"..") == 0) continue;

        int ulen = WideCharToMultiByte(CP_UTF8, 0, ds->cur.cFileName, -1,
                                       NULL, 0, NULL, NULL);
        if (ulen <= 0) continue;
        char* utf8 = (char*)malloc((size_t)ulen);
        if (utf8 == NULL) throw_unix_exception(LINUX_ENOMEM);
        WideCharToMultiByte(CP_UTF8, 0, ds->cur.cFileName, -1,
                            utf8, ulen, NULL, NULL);
        void* arr = jnative_byte_array(utf8, ulen - 1);
        free(utf8);
        return arr;
    }
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_closedir__J_V(int64_t dir) {
    JnDirStream* ds = (JnDirStream*)(intptr_t)dir;
    if (ds == NULL) return;
    if (ds->hfind != INVALID_HANDLE_VALUE) FindClose(ds->hfind);
    free(ds);
}

/* ---- cwd / symlinks / realpath / hardlinks ---- */

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getcwd____B(void) {
    wchar_t buf[4096];
    DWORD n = GetCurrentDirectoryW(4096, buf);
    if (n == 0 || n >= 4096) throw_unix_exception_from_win();
    int ulen = WideCharToMultiByte(CP_UTF8, 0, buf, -1, NULL, 0, NULL, NULL);
    if (ulen <= 0) throw_unix_exception(LINUX_ENOMEM);
    char* utf8 = (char*)malloc((size_t)ulen);
    if (utf8 == NULL) throw_unix_exception(LINUX_ENOMEM);
    WideCharToMultiByte(CP_UTF8, 0, buf, -1, utf8, ulen, NULL, NULL);
    void* arr = jnative_byte_array(utf8, ulen - 1);
    free(utf8);
    return arr;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_symlink0__JJ_V(
        int64_t targetAddress, int64_t linkpathAddress)
{
    wchar_t wtarget[4096], wlink[4096];
    if (win_path_from_addr(targetAddress, wtarget, 4096) < 0 ||
        win_path_from_addr(linkpathAddress, wlink, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    if (!CreateSymbolicLinkW(wlink, wtarget, 0))
        throw_unix_exception_from_win();
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readlink0__J__B(
        int64_t pathAddress)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);

    HANDLE h = CreateFileW(wpath, 0,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           NULL, OPEN_EXISTING,
                           FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                           NULL);
    if (h == INVALID_HANDLE_VALUE) throw_unix_exception_from_win();

    DWORD buf_size = 16 * 1024;
    char* buf = (char*)malloc(buf_size);
    if (buf == NULL) { CloseHandle(h); throw_unix_exception(LINUX_ENOMEM); }

    DWORD returned = 0;
    BOOL ok = DeviceIoControl(h, FSCTL_GET_REPARSE_POINT,
                              NULL, 0, buf, buf_size, &returned, NULL);
    CloseHandle(h);
    if (!ok) { free(buf); throw_unix_exception_from_win(); }

    typedef struct {
        ULONG  ReparseTag;
        USHORT ReparseDataLength;
        USHORT Reserved;
        USHORT SubstituteNameOffset;
        USHORT SubstituteNameLength;
        USHORT PrintNameOffset;
        USHORT PrintNameLength;
        ULONG  Flags;
        WCHAR  PathBuffer[1];
    } SYMLINK_REPARSE;
    SYMLINK_REPARSE* sr = (SYMLINK_REPARSE*)buf;
    if (sr->ReparseTag != IO_REPARSE_TAG_SYMLINK) {
        free(buf);
        throw_unix_exception(LINUX_EINVAL);
    }
    size_t sub_chars = sr->SubstituteNameLength / sizeof(WCHAR);
    WCHAR* sub = sr->PathBuffer + (sr->SubstituteNameOffset / sizeof(WCHAR));

    WCHAR* start = sub;
    size_t  n     = sub_chars;
    if (n >= 4 && start[0] == L'\\' && start[1] == L'?' &&
                  start[2] == L'?' && start[3] == L'\\') {
        start += 4;
        n     -= 4;
    }
    int ulen = WideCharToMultiByte(CP_UTF8, 0, start, (int)n,
                                   NULL, 0, NULL, NULL);
    if (ulen <= 0) { free(buf); throw_unix_exception(LINUX_ENOMEM); }
    char* utf8 = (char*)malloc((size_t)ulen);
    if (utf8 == NULL) { free(buf); throw_unix_exception(LINUX_ENOMEM); }
    WideCharToMultiByte(CP_UTF8, 0, start, (int)n, utf8, ulen, NULL, NULL);
    void* arr = jnative_byte_array(utf8, ulen);
    free(utf8);
    free(buf);
    return arr;
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_realpath0__J__B(
        int64_t pathAddress)
{
    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);

    wchar_t resolved[4096];
    DWORD n = GetFullPathNameW(wpath, 4096, resolved, NULL);
    if (n == 0 || n >= 4096) throw_unix_exception_from_win();

    HANDLE h = CreateFileW(resolved, 0,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           NULL, OPEN_EXISTING,
                           FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (h == INVALID_HANDLE_VALUE) throw_unix_exception_from_win();
    CloseHandle(h);

    int ulen = WideCharToMultiByte(CP_UTF8, 0, resolved, -1, NULL, 0, NULL, NULL);
    if (ulen <= 0) throw_unix_exception(LINUX_ENOMEM);
    char* utf8 = (char*)malloc((size_t)ulen);
    if (utf8 == NULL) throw_unix_exception(LINUX_ENOMEM);
    WideCharToMultiByte(CP_UTF8, 0, resolved, -1, utf8, ulen, NULL, NULL);
    void* arr = jnative_byte_array(utf8, ulen - 1);
    free(utf8);
    return arr;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_link0__JJ_V(
        int64_t existingAddress, int64_t newAddress)
{
    wchar_t wexisting[4096], wnew[4096];
    if (win_path_from_addr(existingAddress, wexisting, 4096) < 0 ||
        win_path_from_addr(newAddress,      wnew,      4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);
    if (!CreateHardLinkW(wnew, wexisting, NULL))
        throw_unix_exception_from_win();
}

/* ---- xattrs (not supported on Windows) ---- */

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fgetxattr0__IJJI_I(
        int32_t fd, int64_t n, int64_t v, int32_t l)
{ (void)fd;(void)n;(void)v;(void)l; return -LINUX_ENOTSUP; }

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getxattr0__JJJI_I(
        int64_t p, int64_t n, int64_t v, int32_t l)
{ (void)p;(void)n;(void)v;(void)l; return -LINUX_ENOTSUP; }

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lgetxattr0__JJJI_I(
        int64_t p, int64_t n, int64_t v, int32_t l)
{ (void)p;(void)n;(void)v;(void)l; return -LINUX_ENOTSUP; }

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fsetxattr0__IJJI_V(
        int32_t fd, int64_t n, int64_t v, int32_t l)
{ (void)fd;(void)n;(void)v;(void)l; throw_unix_exception(LINUX_ENOTSUP); return 0; }

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_setxattr0__JJJI_V(
        int64_t p, int64_t n, int64_t v, int32_t l)
{ (void)p;(void)n;(void)v;(void)l; throw_unix_exception(LINUX_ENOTSUP); return 0; }

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lsetxattr0__JJJI_V(
        int64_t p, int64_t n, int64_t v, int32_t l)
{ (void)p;(void)n;(void)v;(void)l; throw_unix_exception(LINUX_ENOTSUP); return 0; }

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fremovexattr0__IJ_V(
        int32_t fd, int64_t n)
{ (void)fd;(void)n; throw_unix_exception(LINUX_ENOTSUP); }

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_removexattr0__JJ_V(
        int64_t p, int64_t n)
{ (void)p;(void)n; throw_unix_exception(LINUX_ENOTSUP); }

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lremovexattr0__JJ_V(
        int64_t p, int64_t n)
{ (void)p;(void)n; throw_unix_exception(LINUX_ENOTSUP); }

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_flistxattr__IJI_I(
        int32_t fd, int64_t v, int32_t l)
{ (void)fd;(void)v;(void)l; return -LINUX_ENOTSUP; }

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_listxattr0__JJI_I(
        int64_t p, int64_t v, int32_t l)
{ (void)p;(void)v;(void)l; return -LINUX_ENOTSUP; }

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_llistxattr0__JJI_I(
        int64_t p, int64_t v, int32_t l)
{ (void)p;(void)v;(void)l; return -LINUX_ENOTSUP; }

/* ---- user / group lookups (byte[] form, JDK 21) ----
 *
 * The JDK 21 signatures are:
 *   private static native byte[] getpwnam(byte[] name);
 *   private static native byte[] getpwuid(int uid);
 *   private static native byte[] getgrnam(byte[] name);
 *   private static native byte[] getgrgid(int gid);
 *
 * Returning NULL makes UnixUserPrincipals.fromUid/fromGid raise
 * UnixException("no passwd entry for uid X"), which is the correct
 * Windows answer (no POSIX passwd database). The old Group/User forms
 * are kept as dead code so an older JDK that still calls them does not
 * fail to link.
 * ------------------------------------------------------------------------- */

/* -------------------------------------------------------------------------
 *  User / group lookups — JDK 17+ native names.
 *
 *  JDK 17 renamed the four byte[]-returning user/group lookup entry
 *  points on UnixNativeDispatcher and simultaneously changed their
 *  return type from "int index into a native array" to "byte[] that
 *  holds the raw passwd/group record":
 *
 *      pre-JDK-17                     JDK 17+
 *      int getpwuid0(int uid)         byte[] getpwuid(int uid)
 *      int getpwnam0(byte[] name)     byte[] getpwnam(byte[] name)
 *      int getgrgid0(int gid)         byte[] getgrgid(int gid)
 *      int getgrnam0(byte[] name)     byte[] getgrnam(byte[] name)
 *
 *  The compiled JDK 21 bytecode emits calls to the post-JDK-17 names
 *  only (see UnixUserPrincipals.fromUid / fromGid / fromName), so the
 *  linker wants `getpwuid` / `getgrgid` / `getpwnam` / `getgrnam` —
 *  without the trailing `0`. Providing only the `0`-suffixed forms
 *  leaves all four symbols undefined at link time.
 *
 *  Returning NULL is the JDK-documented "no database entry" answer.
 *  UnixUserPrincipals.fromUid / fromGid / fromName each check the
 *  result for null and raise UnixException("no passwd entry for uid X")
 *  or UnixException("no group entry for gid X") in that case, which is
 *  the correct behaviour on Windows (no POSIX passwd/group database
 *  exists there).
 * ------------------------------------------------------------------------- */

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwuid__I__B(int32_t uid)
{ (void)uid; return NULL; }

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrgid__I__B(int32_t gid)
{ (void)gid; return NULL; }

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwnam___B__B(void* name)
{ (void)name; return NULL; }

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrnam___B__B(void* name)
{ (void)name; return NULL; }

/* Older JDK form (compatibility) */
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrgid0__I_Lsun_nio_fs_UnixNativeDispatcher_Group_(int32_t gid)
{ (void)gid; return NULL; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrnam0___B_Lsun_nio_fs_UnixNativeDispatcher_Group_(void* name)
{ (void)name; return NULL; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwnam0___B_Lsun_nio_fs_UnixNativeDispatcher_Passwd_(void* name)
{ (void)name; return NULL; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwuid0__I_Lsun_nio_fs_UnixNativeDispatcher_Passwd_(int32_t uid)
{ (void)uid; return NULL; }

/* ---- uid / gid ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_setuid0__I_V(int32_t uid) { (void)uid; }
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_setgid0__I_V(int32_t gid) { (void)gid; }
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getuid___I(void)  { return 0; }
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_geteuid___I(void) { return 0; }
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgid___I(void)  { return 0; }
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getegid___I(void) { return 0; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgroups____I(void) {
    return jnative_int_array(NULL, 0);
}

/* ---- statvfs family ----
 *
 * JDK 21 signatures:
 *   static native void statvfs(UnixPath path, UnixFileStoreAttributes attrs);
 *   static native void fstatvfs(int fd, UnixFileStoreAttributes attrs);
 *
 * The legacy (long,long)->int and (int,long)->int forms are kept so
 * older callers do not fail to link, but they no longer write partial
 * data.
 * ------------------------------------------------------------------------- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_statvfs0__JLsun_nio_fs_UnixFileStoreAttributes__V(
        int64_t pathAddress, void* attrs)
{
    if (attrs == NULL) throw_unix_exception(LINUX_EINVAL);

    wchar_t wpath[4096];
    if (win_path_from_addr(pathAddress, wpath, 4096) < 0)
        throw_unix_exception(LINUX_ENAMETOOLONG);

    ULONGLONG free_avail = 0, total = 0, total_free = 0;
    if (!GetDiskFreeSpaceExW(wpath, (PULARGE_INTEGER)&free_avail,
                             (PULARGE_INTEGER)&total,
                             (PULARGE_INTEGER)&total_free))
        throw_unix_exception_from_win();

    DWORD spc = 0, bps = 0, fc = 0, tc = 0;
    BOOL got = GetDiskFreeSpaceW(wpath, &spc, &bps, &fc, &tc);
    int64_t block_size = got ? (int64_t)spc * (int64_t)bps : 4096;
    if (block_size <= 0) block_size = 4096;

    *(int64_t*)((char*)attrs + UFSAT_BSIZE_OFFSET)  = block_size;
    *(int64_t*)((char*)attrs + UFSAT_FRSIZE_OFFSET) = block_size;
    *(int64_t*)((char*)attrs + UFSAT_BLOCKS_OFFSET) = (int64_t)(total      / (ULONGLONG)block_size);
    *(int64_t*)((char*)attrs + UFSAT_BFREE_OFFSET)  = (int64_t)(total_free / (ULONGLONG)block_size);
    *(int64_t*)((char*)attrs + UFSAT_BAVAIL_OFFSET) = (int64_t)(free_avail / (ULONGLONG)block_size);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstatvfs0__ILsun_nio_fs_UnixFileStoreAttributes__V(
        int32_t fd, void* attrs)
{
    if (attrs == NULL) throw_unix_exception(LINUX_EINVAL);
    HANDLE h = jn_win_fd_to_handle(fd);
    if (h == INVALID_HANDLE_VALUE) throw_unix_exception(LINUX_EBADF);

    wchar_t dir_path[4096];
    DWORD n = GetFinalPathNameByHandleW(h, dir_path, 4096, VOLUME_NAME_DOS);
    if (n == 0 || n >= 4096) throw_unix_exception_from_win();

    ULONGLONG free_avail = 0, total = 0, total_free = 0;
    if (!GetDiskFreeSpaceExW(dir_path, (PULARGE_INTEGER)&free_avail,
                             (PULARGE_INTEGER)&total,
                             (PULARGE_INTEGER)&total_free))
        throw_unix_exception_from_win();

    DWORD spc = 0, bps = 0, fc = 0, tc = 0;
    BOOL got = GetDiskFreeSpaceW(dir_path, &spc, &bps, &fc, &tc);
    int64_t block_size = got ? (int64_t)spc * (int64_t)bps : 4096;
    if (block_size <= 0) block_size = 4096;

    *(int64_t*)((char*)attrs + UFSAT_BSIZE_OFFSET)  = block_size;
    *(int64_t*)((char*)attrs + UFSAT_FRSIZE_OFFSET) = block_size;
    *(int64_t*)((char*)attrs + UFSAT_BLOCKS_OFFSET) = (int64_t)(total      / (ULONGLONG)block_size);
    *(int64_t*)((char*)attrs + UFSAT_BFREE_OFFSET)  = (int64_t)(total_free / (ULONGLONG)block_size);
    *(int64_t*)((char*)attrs + UFSAT_BAVAIL_OFFSET) = (int64_t)(free_avail / (ULONGLONG)block_size);
}

/* Legacy (long,long)->int form; kept for compatibility. */
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_statvfs0__JJ_I(
        int64_t pathAddress, int64_t bufAddr)
{
    (void)pathAddress; (void)bufAddr; return -LINUX_ENOTSUP;
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstatvfs0__IJ_I(
        int32_t fd, int64_t bufAddr)
{
    (void)fd; (void)bufAddr; return -LINUX_ENOTSUP;
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_sendfile0__IIJI_J(
        int32_t o, int32_t i, int64_t off, int64_t cnt)
{ (void)o;(void)i;(void)off;(void)cnt; return -LINUX_ENOTSUP; }

/* ---- strerror ---- */

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_strerror__I__B(int32_t err) {
    DWORD code;
    switch (err) {
        case LINUX_ENOENT:       code = ERROR_FILE_NOT_FOUND;    break;
        case LINUX_EBADF:        code = ERROR_INVALID_HANDLE;    break;
        case LINUX_EAGAIN:       code = ERROR_SHARING_VIOLATION; break;
        case LINUX_ENOMEM:       code = ERROR_NOT_ENOUGH_MEMORY; break;
        case LINUX_EACCES:       code = ERROR_ACCESS_DENIED;     break;
        case LINUX_EEXIST:       code = ERROR_FILE_EXISTS;       break;
        case LINUX_EXDEV:        code = ERROR_NOT_SAME_DEVICE;   break;
        case LINUX_ENOTDIR:      code = ERROR_DIRECTORY;         break;
        case LINUX_EINVAL:       code = ERROR_INVALID_PARAMETER; break;
        case LINUX_ENOSPC:       code = ERROR_DISK_FULL;         break;
        case LINUX_ENAMETOOLONG: code = ERROR_BUFFER_OVERFLOW;   break;
        case LINUX_ENOTEMPTY:    code = ERROR_DIR_NOT_EMPTY;     break;
        case LINUX_ENOTSUP:      code = ERROR_NOT_SUPPORTED;     break;
        default:                 code = ERROR_GEN_FAILURE;       break;
    }
    char msg[512];
    DWORD n = FormatMessageA(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        NULL, code, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        msg, (DWORD)sizeof(msg), NULL);
    if (n == 0) {
        snprintf(msg, sizeof(msg), "error %d", err);
        n = (DWORD)strlen(msg);
    }
    while (n > 0 && (msg[n - 1] == '\r' || msg[n - 1] == '\n')) n--;
    return jnative_byte_array(msg, (int32_t)n);
}

/* ============================================================================
 *  POSIX implementation
 * ========================================================================= */
#else

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___I(void) { return 0; }
void    __jnative_fn_sun_nio_fs_UnixNativeDispatcher_init___V(void) { }

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_open0__JII_I(
        int64_t pathAddress, int32_t flags, int32_t mode)
{
    int r = open((const char*)(intptr_t)pathAddress, flags, (mode_t)mode);
    return (r < 0) ? -errno : r;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_openat0__IJII_I(
        int32_t dfd, int64_t pathAddress, int32_t flags, int32_t mode)
{
    int r = openat(dfd, (const char*)(intptr_t)pathAddress,
                   flags, (mode_t)mode);
    return (r < 0) ? -errno : r;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_dup__I_I(int32_t fd) {
    int r = dup(fd);
    return (r < 0) ? -errno : r;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_close0__I_V(int32_t fd) {
    if (fd >= 0) close(fd);
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_read0__IJI_I(
        int32_t fd, int64_t buf, int32_t len)
{
    ssize_t n = read(fd, (void*)(intptr_t)buf, (size_t)len);
    return (n < 0) ? -errno : (int32_t)n;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_write0__IJI_I(
        int32_t fd, int64_t buf, int32_t len)
{
    ssize_t n = write(fd, (void*)(intptr_t)buf, (size_t)len);
    return (n < 0) ? -errno : (int32_t)n;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_pread0__IJIJ_I(
        int32_t fd, int64_t buf, int32_t len, int64_t offset)
{
    ssize_t n = pread(fd, (void*)(intptr_t)buf, (size_t)len, (off_t)offset);
    return (n < 0) ? -errno : (int32_t)n;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_pwrite0__IJIJ_I(
        int32_t fd, int64_t buf, int32_t len, int64_t offset)
{
    ssize_t n = pwrite(fd, (void*)(intptr_t)buf, (size_t)len, (off_t)offset);
    return (n < 0) ? -errno : (int32_t)n;
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lseek__IJI_J(
        int32_t fd, int64_t offset, int32_t whence)
{
    off_t r = lseek(fd, (off_t)offset, whence);
    return (r < 0) ? -errno : (int64_t)r;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_ftruncate__IJ_V(
        int32_t fd, int64_t size)
{
    if (ftruncate(fd, (off_t)size) < 0) throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fsync__I_V(int32_t fd) {
    if (fsync(fd) < 0) throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fdatasync__I_V(int32_t fd) {
    if (fdatasync(fd) < 0) throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_access0__JI_V(
        int64_t pathAddress, int32_t amode)
{
    if (access((const char*)(intptr_t)pathAddress, amode) < 0)
        throw_unix_exception_from_errno();
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_exists0__J_Z(
        int64_t pathAddress)
{
    return (access((const char*)(intptr_t)pathAddress, F_OK) == 0) ? 1 : 0;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_mkdir0__JI_V(
        int64_t pathAddress, int32_t mode)
{
    if (mkdir((const char*)(intptr_t)pathAddress, (mode_t)mode) < 0)
        throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rmdir0__J_V(
        int64_t pathAddress)
{
    if (rmdir((const char*)(intptr_t)pathAddress) < 0)
        throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlink0__J_V(
        int64_t pathAddress)
{
    if (unlink((const char*)(intptr_t)pathAddress) < 0)
        throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_unlinkat0__IJI_V(
        int32_t dfd, int64_t pathAddress, int32_t flags)
{
    if (unlinkat(dfd, (const char*)(intptr_t)pathAddress, flags) < 0)
        throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_rename0__JJ_V(
        int64_t fromAddress, int64_t toAddress)
{
    if (rename((const char*)(intptr_t)fromAddress,
               (const char*)(intptr_t)toAddress) < 0)
        throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_renameat0__IJIJ_V(
        int32_t olddfd, int64_t oldAddress, int32_t newdfd, int64_t newAddress)
{
    if (renameat(olddfd, (const char*)(intptr_t)oldAddress,
                 newdfd, (const char*)(intptr_t)newAddress) < 0)
        throw_unix_exception_from_errno();
}

static void fill_unix_file_attrs_from_posix_stat(void* attrs,
                                                 const struct stat* st)
{
    char* base = (char*)attrs;
    *(int32_t*)(base + UFA_ST_MODE_OFFSET)  = (int32_t)st->st_mode;
    *(int64_t*)(base + UFA_ST_INO_OFFSET)   = (int64_t)st->st_ino;
    *(int64_t*)(base + UFA_ST_DEV_OFFSET)   = (int64_t)st->st_dev;
    *(int64_t*)(base + UFA_ST_RDEV_OFFSET)  = (int64_t)st->st_rdev;
    *(int32_t*)(base + UFA_ST_NLINK_OFFSET) = (int32_t)st->st_nlink;
    *(int32_t*)(base + UFA_ST_UID_OFFSET)   = (int32_t)st->st_uid;
    *(int32_t*)(base + UFA_ST_GID_OFFSET)   = (int32_t)st->st_gid;
    *(int64_t*)(base + UFA_ST_SIZE_OFFSET)  = (int64_t)st->st_size;

#if defined(__APPLE__)
    *(int64_t*)(base + UFA_ST_ATIME_SEC_OFFSET)  = (int64_t)st->st_atimespec.tv_sec;
    *(int64_t*)(base + UFA_ST_ATIME_NSEC_OFFSET) = (int64_t)st->st_atimespec.tv_nsec;
    *(int64_t*)(base + UFA_ST_MTIME_SEC_OFFSET)  = (int64_t)st->st_mtimespec.tv_sec;
    *(int64_t*)(base + UFA_ST_MTIME_NSEC_OFFSET) = (int64_t)st->st_mtimespec.tv_nsec;
    *(int64_t*)(base + UFA_ST_CTIME_SEC_OFFSET)  = (int64_t)st->st_ctimespec.tv_sec;
    *(int64_t*)(base + UFA_ST_CTIME_NSEC_OFFSET) = (int64_t)st->st_ctimespec.tv_nsec;
    *(int64_t*)(base + UFA_ST_BIRTHTIME_OFFSET)  = (int64_t)st->st_birthtimespec.tv_sec;
#else
    *(int64_t*)(base + UFA_ST_ATIME_SEC_OFFSET)  = (int64_t)st->st_atim.tv_sec;
    *(int64_t*)(base + UFA_ST_ATIME_NSEC_OFFSET) = (int64_t)st->st_atim.tv_nsec;
    *(int64_t*)(base + UFA_ST_MTIME_SEC_OFFSET)  = (int64_t)st->st_mtim.tv_sec;
    *(int64_t*)(base + UFA_ST_MTIME_NSEC_OFFSET) = (int64_t)st->st_mtim.tv_nsec;
    *(int64_t*)(base + UFA_ST_CTIME_SEC_OFFSET)  = (int64_t)st->st_ctim.tv_sec;
    *(int64_t*)(base + UFA_ST_CTIME_NSEC_OFFSET) = (int64_t)st->st_ctim.tv_nsec;
#  if defined(__linux__)
    *(int64_t*)(base + UFA_ST_BIRTHTIME_OFFSET)  = 0;
#  endif
#endif
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_stat0__JLsun_nio_fs_UnixFileAttributes__I(
        int64_t pathAddress, void* attrs)
{
    struct stat st;
    if (stat((const char*)(intptr_t)pathAddress, &st) == 0) {
        fill_unix_file_attrs_from_posix_stat(attrs, &st);
        return 0;
    }
    return -errno;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lstat0__JLsun_nio_fs_UnixFileAttributes__V(
        int64_t pathAddress, void* attrs)
{
    struct stat st;
    if (lstat((const char*)(intptr_t)pathAddress, &st) < 0)
        throw_unix_exception_from_errno();
    fill_unix_file_attrs_from_posix_stat(attrs, &st);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstat0__ILsun_nio_fs_UnixFileAttributes__V(
        int32_t fd, void* attrs)
{
    struct stat st;
    if (fstat(fd, &st) < 0) throw_unix_exception_from_errno();
    fill_unix_file_attrs_from_posix_stat(attrs, &st);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstatat0__IJILsun_nio_fs_UnixFileAttributes__V(
        int32_t dfd, int64_t pathAddress, int32_t flag, void* attrs)
{
    struct stat st;
    if (fstatat(dfd, (const char*)(intptr_t)pathAddress, &st, flag) < 0)
        throw_unix_exception_from_errno();
    fill_unix_file_attrs_from_posix_stat(attrs, &st);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chmod0__JI_V(
        int64_t pathAddress, int32_t mode)
{
    if (chmod((const char*)(intptr_t)pathAddress, (mode_t)mode) < 0)
        throw_unix_exception_from_errno();
}
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchmod0__II_V(
        int32_t fd, int32_t mode)
{
    if (fchmod(fd, (mode_t)mode) < 0) throw_unix_exception_from_errno();
}
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_chown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid)
{
    if (chown((const char*)(intptr_t)pathAddress, (uid_t)uid, (gid_t)gid) < 0)
        throw_unix_exception_from_errno();
}
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lchown0__JII_V(
        int64_t pathAddress, int32_t uid, int32_t gid)
{
    if (lchown((const char*)(intptr_t)pathAddress, (uid_t)uid, (gid_t)gid) < 0)
        throw_unix_exception_from_errno();
}
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fchown0__III_V(
        int32_t fd, int32_t uid, int32_t gid)
{
    if (fchown(fd, (uid_t)uid, (gid_t)gid) < 0) throw_unix_exception_from_errno();
}

static void posix_set_times(const char* path, int64_t atime, int64_t mtime,
                            int follow_links)
{
    struct timespec ts[2];
    ts[0].tv_sec  = (time_t)atime;  ts[0].tv_nsec = 0;
    ts[1].tv_sec  = (time_t)mtime;  ts[1].tv_nsec = 0;
    int r = follow_links ? utimensat(AT_FDCWD, path, ts, 0)
                         : utimensat(AT_FDCWD, path, ts, AT_SYMLINK_NOFOLLOW);
    if (r < 0) throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_utimes0__JJJ_V(
        int64_t p, int64_t a, int64_t m)
{ posix_set_times((const char*)(intptr_t)p, a, m, 1); }

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lutimes0__JJJ_V(
        int64_t p, int64_t a, int64_t m)
{ posix_set_times((const char*)(intptr_t)p, a, m, 0); }

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_futimes0__IJJ_V(
        int32_t fd, int64_t a, int64_t m)
{
    struct timespec ts[2];
    ts[0].tv_sec = (time_t)a; ts[0].tv_nsec = 0;
    ts[1].tv_sec = (time_t)m; ts[1].tv_nsec = 0;
    if (futimens(fd, ts) < 0) throw_unix_exception_from_errno();
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_futimens0__IJJ_V(
        int32_t fd, int64_t a, int64_t m)
{
    struct timespec ts[2];
    ts[0].tv_sec = (time_t)a; ts[0].tv_nsec = 0;
    ts[1].tv_sec = (time_t)m; ts[1].tv_nsec = 0;
    if (futimens(fd, ts) < 0) throw_unix_exception_from_errno();
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_opendir0__J_J(
        int64_t pathAddress)
{
    DIR* d = opendir((const char*)(intptr_t)pathAddress);
    if (d == NULL) throw_unix_exception_from_errno();
    return (int64_t)(intptr_t)d;
}

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fdopendir__I_J(int32_t fd) {
    DIR* d = fdopendir(fd);
    if (d == NULL) throw_unix_exception_from_errno();
    return (int64_t)(intptr_t)d;
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readdir0__J__B(int64_t dir) {
    DIR* d = (DIR*)(intptr_t)dir;
    if (d == NULL) throw_unix_exception(LINUX_EBADF);
    struct dirent* e = readdir(d);
    if (e == NULL) return NULL;
    size_t n = strlen(e->d_name);
    return jnative_byte_array(e->d_name, (int32_t)n);
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_closedir__J_V(int64_t dir) {
    DIR* d = (DIR*)(intptr_t)dir;
    if (d != NULL) closedir(d);
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getcwd____B(void) {
    char buf[PATH_MAX];
    if (getcwd(buf, sizeof(buf)) == NULL) throw_unix_exception_from_errno();
    return jnative_byte_array(buf, (int32_t)strlen(buf));
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_symlink0__JJ_V(
        int64_t t, int64_t l)
{
    if (symlink((const char*)(intptr_t)t, (const char*)(intptr_t)l) < 0)
        throw_unix_exception_from_errno();
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_readlink0__J__B(
        int64_t pathAddress)
{
    char buf[PATH_MAX];
    ssize_t n = readlink((const char*)(intptr_t)pathAddress, buf, sizeof(buf));
    if (n < 0) throw_unix_exception_from_errno();
    return jnative_byte_array(buf, (int32_t)n);
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_realpath0__J__B(
        int64_t pathAddress)
{
    char resolved[PATH_MAX];
    if (realpath((const char*)(intptr_t)pathAddress, resolved) == NULL)
        throw_unix_exception_from_errno();
    return jnative_byte_array(resolved, (int32_t)strlen(resolved));
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_link0__JJ_V(
        int64_t e, int64_t n)
{
    if (link((const char*)(intptr_t)e, (const char*)(intptr_t)n) < 0)
        throw_unix_exception_from_errno();
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fgetxattr0__IJJI_I(
        int32_t fd, int64_t n, int64_t v, int32_t l)
{
    ssize_t r = fgetxattr(fd, (const char*)(intptr_t)n,
                          (void*)(intptr_t)v, (size_t)l);
    return (r < 0) ? -errno : (int32_t)r;
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getxattr0__JJJI_I(
        int64_t p, int64_t n, int64_t v, int32_t l)
{
    ssize_t r = getxattr((const char*)(intptr_t)p, (const char*)(intptr_t)n,
                         (void*)(intptr_t)v, (size_t)l);
    return (r < 0) ? -errno : (int32_t)r;
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lgetxattr0__JJJI_I(
        int64_t p, int64_t n, int64_t v, int32_t l)
{
    ssize_t r = lgetxattr((const char*)(intptr_t)p, (const char*)(intptr_t)n,
                          (void*)(intptr_t)v, (size_t)l);
    return (r < 0) ? -errno : (int32_t)r;
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fsetxattr0__IJJI_V(
        int32_t fd, int64_t n, int64_t v, int32_t l)
{
    if (fsetxattr(fd, (const char*)(intptr_t)n,
                  (void*)(intptr_t)v, (size_t)l, 0) < 0)
        throw_unix_exception_from_errno();
    return 0;
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_setxattr0__JJJI_V(
        int64_t p, int64_t n, int64_t v, int32_t l)
{
    if (setxattr((const char*)(intptr_t)p, (const char*)(intptr_t)n,
                 (void*)(intptr_t)v, (size_t)l, 0) < 0)
        throw_unix_exception_from_errno();
    return 0;
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lsetxattr0__JJJI_V(
        int64_t p, int64_t n, int64_t v, int32_t l)
{
    if (lsetxattr((const char*)(intptr_t)p, (const char*)(intptr_t)n,
                  (void*)(intptr_t)v, (size_t)l, 0) < 0)
        throw_unix_exception_from_errno();
    return 0;
}
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fremovexattr0__IJ_V(
        int32_t fd, int64_t n)
{
    if (fremovexattr(fd, (const char*)(intptr_t)n) < 0)
        throw_unix_exception_from_errno();
}
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_removexattr0__JJ_V(
        int64_t p, int64_t n)
{
    if (removexattr((const char*)(intptr_t)p, (const char*)(intptr_t)n) < 0)
        throw_unix_exception_from_errno();
}
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_lremovexattr0__JJ_V(
        int64_t p, int64_t n)
{
    if (lremovexattr((const char*)(intptr_t)p, (const char*)(intptr_t)n) < 0)
        throw_unix_exception_from_errno();
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_flistxattr__IJI_I(
        int32_t fd, int64_t v, int32_t l)
{
    ssize_t r = flistxattr(fd, (char*)(intptr_t)v, (size_t)l);
    return (r < 0) ? -errno : (int32_t)r;
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_listxattr0__JJI_I(
        int64_t p, int64_t v, int32_t l)
{
    ssize_t r = listxattr((const char*)(intptr_t)p,
                          (char*)(intptr_t)v, (size_t)l);
    return (r < 0) ? -errno : (int32_t)r;
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_llistxattr0__JJI_I(
        int64_t p, int64_t v, int32_t l)
{
    ssize_t r = llistxattr((const char*)(intptr_t)p,
                           (char*)(intptr_t)v, (size_t)l);
    return (r < 0) ? -errno : (int32_t)r;
}

/* User / group lookups: return NULL (JDK 21 byte[] form). */
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrgid0__I__B(int32_t gid)
{ (void)gid; return NULL; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwuid0__I__B(int32_t uid)
{ (void)uid; return NULL; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrnam0___B__B(void* name)
{ (void)name; return NULL; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwnam0___B__B(void* name)
{ (void)name; return NULL; }

/* Older JDK form (compatibility) */
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrgid0__I_Lsun_nio_fs_UnixNativeDispatcher_Group_(int32_t gid)
{ (void)gid; return NULL; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrnam0___B_Lsun_nio_fs_UnixNativeDispatcher_Group_(void* n)
{ (void)n; return NULL; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwnam0___B_Lsun_nio_fs_UnixNativeDispatcher_Passwd_(void* n)
{ (void)n; return NULL; }
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwuid0__I_Lsun_nio_fs_UnixNativeDispatcher_Passwd_(int32_t uid)
{ (void)uid; return NULL; }

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_setuid0__I_V(int32_t uid) {
    if (setuid((uid_t)uid) < 0) throw_unix_exception_from_errno();
}
void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_setgid0__I_V(int32_t gid) {
    if (setgid((gid_t)gid) < 0) throw_unix_exception_from_errno();
}
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getuid___I(void)  { return (int32_t)getuid(); }
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_geteuid___I(void) { return (int32_t)geteuid(); }
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgid___I(void)  { return (int32_t)getgid(); }
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getegid___I(void) { return (int32_t)getegid(); }

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgroups____I(void) {
    int n = getgroups(0, NULL);
    if (n <= 0) return jnative_int_array(NULL, 0);
    gid_t* gids = (gid_t*)malloc((size_t)n * sizeof(gid_t));
    if (gids == NULL) throw_unix_exception(LINUX_ENOMEM);
    if (getgroups(n, gids) != n) {
        free(gids);
        throw_unix_exception_from_errno();
    }
    int32_t* out = (int32_t*)malloc((size_t)n * sizeof(int32_t));
    if (out == NULL) { free(gids); throw_unix_exception(LINUX_ENOMEM); }
    for (int i = 0; i < n; i++) out[i] = (int32_t)gids[i];
    void* arr = jnative_int_array(out, n);
    free(gids);
    free(out);
    return arr;
}

/* ---- statvfs family ---- */

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_statvfs0__JLsun_nio_fs_UnixFileStoreAttributes__V(
        int64_t pathAddress, void* attrs)
{
    if (attrs == NULL) throw_unix_exception(LINUX_EINVAL);
    struct statvfs sv;
    if (statvfs((const char*)(intptr_t)pathAddress, &sv) < 0)
        throw_unix_exception_from_errno();
    char* base = (char*)attrs;
    *(int64_t*)(base + UFSAT_BSIZE_OFFSET)  = (int64_t)sv.f_bsize;
    *(int64_t*)(base + UFSAT_FRSIZE_OFFSET) = (int64_t)sv.f_frsize;
    *(int64_t*)(base + UFSAT_BLOCKS_OFFSET) = (int64_t)sv.f_blocks;
    *(int64_t*)(base + UFSAT_BFREE_OFFSET)  = (int64_t)sv.f_bfree;
    *(int64_t*)(base + UFSAT_BAVAIL_OFFSET) = (int64_t)sv.f_bavail;
}

void __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstatvfs0__ILsun_nio_fs_UnixFileStoreAttributes__V(
        int32_t fd, void* attrs)
{
    if (attrs == NULL) throw_unix_exception(LINUX_EINVAL);
    struct statvfs sv;
    if (fstatvfs(fd, &sv) < 0) throw_unix_exception_from_errno();
    char* base = (char*)attrs;
    *(int64_t*)(base + UFSAT_BSIZE_OFFSET)  = (int64_t)sv.f_bsize;
    *(int64_t*)(base + UFSAT_FRSIZE_OFFSET) = (int64_t)sv.f_frsize;
    *(int64_t*)(base + UFSAT_BLOCKS_OFFSET) = (int64_t)sv.f_blocks;
    *(int64_t*)(base + UFSAT_BFREE_OFFSET)  = (int64_t)sv.f_bfree;
    *(int64_t*)(base + UFSAT_BAVAIL_OFFSET) = (int64_t)sv.f_bavail;
}

int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_statvfs0__JJ_I(
        int64_t p, int64_t b)
{ (void)p; (void)b; return -LINUX_ENOTSUP; }
int32_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_fstatvfs0__IJ_I(
        int32_t fd, int64_t b)
{ (void)fd; (void)b; return -LINUX_ENOTSUP; }

int64_t __jnative_fn_sun_nio_fs_UnixNativeDispatcher_sendfile0__IIJI_J(
        int32_t out_fd, int32_t in_fd, int64_t offset, int64_t count)
{
#if defined(__linux__)
    off_t off = (off_t)offset;
    ssize_t n = sendfile(out_fd, in_fd, &off, (size_t)count);
    return (n < 0) ? -errno : (int64_t)n;
#else
    (void)out_fd; (void)in_fd; (void)offset; (void)count;
    return -LINUX_ENOTSUP;
#endif
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_strerror__I__B(int32_t err) {
    char buf[512];
#if defined(__GLIBC__) && defined(_GNU_SOURCE)
    const char* msg = strerror_r(err, buf, sizeof(buf));
    return jnative_byte_array(msg, (int32_t)strlen(msg));
#else
    if (strerror_r(err, buf, sizeof(buf)) != 0) {
        snprintf(buf, sizeof(buf), "error %d", err);
    }
    return jnative_byte_array(buf, (int32_t)strlen(buf));
#endif
}

/* ------------------------------------------------------------------------
 * Compatibility aliases for JDKs that renamed getpwuid0 / getgrgid0 to
 * getpwuid / getgrgid (JDK 24+). Same body, same behaviour. The
 * "0"-suffixed forms remain present because JDK 21 through 23 use them,
 * and the build must link against either.
 * ------------------------------------------------------------------------ */
void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwuid__I__B(int32_t uid) {
    return __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getpwuid0__I__B(uid);
}

void* __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrgid__I__B(int32_t gid) {
    return __jnative_fn_sun_nio_fs_UnixNativeDispatcher_getgrgid0__I__B(gid);
}

#endif /* _WIN32 */