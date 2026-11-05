#if defined(_WIN32)

#ifndef WIN32_LEAN_AND_MEAN
#  define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <stdint.h>

int64_t __jnative_fn_sun_io_Win32ErrorMode_setErrorMode__J_J(int64_t mode)
{
    return (int64_t)SetErrorMode((UINT)mode);
}

#endif
