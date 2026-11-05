#if defined(_WIN32)

#ifndef WIN32_LEAN_AND_MEAN
#  define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <windows.h>
#include <ws2tcpip.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#include "jnative_runtime.h"

#define WEP_CTL_ADD 1
#define WEP_CTL_MOD 2
#define WEP_CTL_DEL 3

#define WEP_IN      0x0001u
#define WEP_PRI     0x0002u
#define WEP_OUT     0x0004u
#define WEP_ERR     0x0008u
#define WEP_HUP     0x0010u
#define WEP_RDNORM  0x0040u
#define WEP_RDBAND  0x0080u
#define WEP_WRNORM  0x0100u
#define WEP_WRBAND  0x0200u
#define WEP_RDHUP   0x2000u
#define WEP_ONESHOT 0x80000000u

typedef struct {
    uint32_t events;
    uint64_t data;
} wep_event;

typedef struct {
    SOCKET   sock;
    uint32_t events;
    uint32_t gen;
    int      armed;
} wep_reg;

typedef struct {
    CRITICAL_SECTION lock;
    SOCKET   wake;
    wep_reg* regs;
    int      count;
    int      cap;
    int      waiters;
    int      wake_pending;
} wep_t;

typedef struct {
    SOCKET   sock;
    uint32_t gen;
} wep_snap;

__attribute__((noreturn))
static void wep_throw(const char* what, int code)
{
    char sysbuf[256];
    char buf[512];
    DWORD n = FormatMessageA(
        FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        NULL, (DWORD)code, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        sysbuf, (DWORD)sizeof(sysbuf), NULL);
    while (n > 0 && (sysbuf[n - 1] == '\r' || sysbuf[n - 1] == '\n'
                     || sysbuf[n - 1] == ' ' || sysbuf[n - 1] == '\t')) {
        sysbuf[--n] = '\0';
    }
    if (n > 0) snprintf(buf, sizeof(buf), "%s: %s (error %d)", what, sysbuf, code);
    else snprintf(buf, sizeof(buf), "%s (error %d)", what, code);
    __jnative_throw_exception(
        __jnative_construct_exception("vtable_java_io_IOException", buf));
}

static int wep_find(wep_t* ep, SOCKET s, int* insert_at)
{
    int lo = 0, hi = ep->count - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        SOCKET m = ep->regs[mid].sock;
        if (m == s) return mid;
        if (m < s) lo = mid + 1;
        else hi = mid - 1;
    }
    if (insert_at) *insert_at = lo;
    return -1;
}

static void wep_remove_at(wep_t* ep, int i)
{
    memmove(&ep->regs[i], &ep->regs[i + 1], (size_t)(ep->count - i - 1) * sizeof(wep_reg));
    ep->count--;
}

static void wep_signal(wep_t* ep)
{
    if (ep->waiters > 0 && !ep->wake_pending) {
        char b = 0;
        if (send(ep->wake, &b, 1, 0) == 1) ep->wake_pending = 1;
    }
}

static void wep_drain(wep_t* ep)
{
    char buf[64];
    while (recv(ep->wake, buf, (int)sizeof(buf), 0) > 0) {
    }
}

static SHORT wep_to_poll(uint32_t ev)
{
    SHORT p = 0;
    if (ev & (WEP_IN | WEP_RDNORM | WEP_RDHUP)) p |= POLLRDNORM;
    if (ev & (WEP_PRI | WEP_RDBAND)) p |= POLLRDBAND;
    if (ev & (WEP_OUT | WEP_WRNORM | WEP_WRBAND)) p |= POLLWRNORM;
    return p;
}

static uint32_t wep_from_poll(SHORT r)
{
    uint32_t ev = 0;
    if (r & POLLRDNORM) ev |= WEP_IN | WEP_RDNORM;
    if (r & POLLRDBAND) ev |= WEP_PRI | WEP_RDBAND;
    if (r & POLLWRNORM) ev |= WEP_OUT | WEP_WRNORM | WEP_WRBAND;
    if (r & POLLHUP) ev |= WEP_HUP | WEP_IN | WEP_RDNORM | WEP_RDHUP;
    if (r & POLLERR) ev |= WEP_ERR | WEP_IN | WEP_RDNORM | WEP_OUT | WEP_WRNORM;
    return ev;
}

int32_t __jnative_fn_sun_nio_ch_WEPoll_eventSize___I(void)
{
    return (int32_t)sizeof(wep_event);
}

int32_t __jnative_fn_sun_nio_ch_WEPoll_eventsOffset___I(void)
{
    return (int32_t)offsetof(wep_event, events);
}

int32_t __jnative_fn_sun_nio_ch_WEPoll_dataOffset___I(void)
{
    return (int32_t)offsetof(wep_event, data);
}

int64_t __jnative_fn_sun_nio_ch_WEPoll_create___J(void)
{
    WSADATA wsa;
    int rc = WSAStartup(MAKEWORD(2, 2), &wsa);
    if (rc != 0) wep_throw("epoll_create1 failed", rc);
    wep_t* ep = (wep_t*)calloc(1, sizeof(wep_t));
    if (ep == NULL) {
        WSACleanup();
        __jnative_throw_out_of_memory_error_ctx("WEPoll.create");
    }
    SOCKET s = WSASocketW(AF_INET, SOCK_DGRAM, IPPROTO_UDP, NULL, 0, WSA_FLAG_NO_HANDLE_INHERIT);
    struct sockaddr_in addr;
    int alen = (int)sizeof(addr);
    u_long nb = 1;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (s == INVALID_SOCKET
        || bind(s, (struct sockaddr*)&addr, alen) == SOCKET_ERROR
        || getsockname(s, (struct sockaddr*)&addr, &alen) == SOCKET_ERROR
        || connect(s, (struct sockaddr*)&addr, alen) == SOCKET_ERROR
        || ioctlsocket(s, FIONBIO, &nb) == SOCKET_ERROR) {
        int err = WSAGetLastError();
        if (s != INVALID_SOCKET) closesocket(s);
        free(ep);
        WSACleanup();
        wep_throw("epoll_create1 failed", err);
    }
    InitializeCriticalSection(&ep->lock);
    ep->wake = s;
    return (int64_t)(intptr_t)ep;
}

int32_t __jnative_fn_sun_nio_ch_WEPoll_ctl__JIJI_I(int64_t h, int32_t opcode, int64_t s, int32_t events)
{
    wep_t* ep = (wep_t*)(intptr_t)h;
    SOCKET sock = (SOCKET)(uintptr_t)s;
    if (ep == NULL) return EBADF;
    if (opcode != WEP_CTL_ADD && opcode != WEP_CTL_MOD && opcode != WEP_CTL_DEL) return EINVAL;
    if (opcode != WEP_CTL_DEL) {
        int type;
        int tlen = (int)sizeof(type);
        if (sock == INVALID_SOCKET
            || getsockopt(sock, SOL_SOCKET, SO_TYPE, (char*)&type, &tlen) == SOCKET_ERROR) {
            return ENOTSOCK;
        }
    }
    int res = 0;
    int at = 0;
    EnterCriticalSection(&ep->lock);
    int i = wep_find(ep, sock, &at);
    if (opcode == WEP_CTL_ADD) {
        if (i >= 0) {
            res = EEXIST;
        } else {
            if (ep->count == ep->cap) {
                int ncap = ep->cap ? ep->cap * 2 : 16;
                wep_reg* n = (wep_reg*)realloc(ep->regs, (size_t)ncap * sizeof(wep_reg));
                if (n == NULL) res = ENOMEM;
                else { ep->regs = n; ep->cap = ncap; }
            }
            if (res == 0) {
                memmove(&ep->regs[at + 1], &ep->regs[at], (size_t)(ep->count - at) * sizeof(wep_reg));
                ep->regs[at].sock = sock;
                ep->regs[at].events = (uint32_t)events;
                ep->regs[at].gen = 0;
                ep->regs[at].armed = 1;
                ep->count++;
                wep_signal(ep);
            }
        }
    } else if (i < 0) {
        res = ENOENT;
    } else if (opcode == WEP_CTL_MOD) {
        ep->regs[i].events = (uint32_t)events;
        ep->regs[i].gen++;
        ep->regs[i].armed = 1;
        wep_signal(ep);
    } else {
        wep_remove_at(ep, i);
        wep_signal(ep);
    }
    LeaveCriticalSection(&ep->lock);
    return res;
}

int32_t __jnative_fn_sun_nio_ch_WEPoll_wait__JJII_I(int64_t h, int64_t address, int32_t numfds, int32_t timeout)
{
    wep_t* ep = (wep_t*)(intptr_t)h;
    wep_event* out = (wep_event*)(intptr_t)address;
    if (ep == NULL || numfds <= 0) wep_throw("epoll_wait failed", WSAEINVAL);
    ULONGLONG deadline = timeout > 0 ? GetTickCount64() + (ULONGLONG)timeout : 0;
    WSAPOLLFD* fds = NULL;
    wep_snap* snap = NULL;
    int cap = 0;
    int result = 0;
    for (;;) {
        EnterCriticalSection(&ep->lock);
        int n = 0;
        if (ep->count + 1 > cap) {
            int ncap = ep->count + 1;
            WSAPOLLFD* nf = (WSAPOLLFD*)realloc(fds, (size_t)ncap * sizeof(WSAPOLLFD));
            if (nf != NULL) fds = nf;
            wep_snap* ns = nf ? (wep_snap*)realloc(snap, (size_t)ncap * sizeof(wep_snap)) : NULL;
            if (ns != NULL) snap = ns;
            if (nf == NULL || ns == NULL) {
                LeaveCriticalSection(&ep->lock);
                free(fds);
                free(snap);
                __jnative_throw_out_of_memory_error_ctx("WEPoll.wait");
            }
            cap = ncap;
        }
        fds[n].fd = ep->wake;
        fds[n].events = POLLRDNORM;
        fds[n].revents = 0;
        n++;
        for (int i = 0; i < ep->count; i++) {
            if (!ep->regs[i].armed) continue;
            fds[n].fd = ep->regs[i].sock;
            fds[n].events = wep_to_poll(ep->regs[i].events);
            fds[n].revents = 0;
            snap[n].sock = ep->regs[i].sock;
            snap[n].gen = ep->regs[i].gen;
            n++;
        }
        ep->waiters++;
        LeaveCriticalSection(&ep->lock);

        INT wait_ms;
        if (timeout < 0) {
            wait_ms = -1;
        } else if (timeout == 0) {
            wait_ms = 0;
        } else {
            ULONGLONG now = GetTickCount64();
            wait_ms = now >= deadline ? 0 : (INT)(deadline - now);
        }
        int rc = WSAPoll(fds, (ULONG)n, wait_ms);
        int err = rc == SOCKET_ERROR ? WSAGetLastError() : 0;

        EnterCriticalSection(&ep->lock);
        ep->waiters--;
        if (rc > 0 && fds[0].revents != 0) {
            wep_drain(ep);
            ep->wake_pending = 0;
        }
        if (rc > 0) {
            for (int k = 1; k < n && result < numfds; k++) {
                SHORT r = fds[k].revents;
                if (r == 0) continue;
                int i = wep_find(ep, snap[k].sock, NULL);
                if (i < 0 || ep->regs[i].gen != snap[k].gen || !ep->regs[i].armed) continue;
                if (r & POLLNVAL) {
                    wep_remove_at(ep, i);
                    continue;
                }
                uint32_t want = ep->regs[i].events | WEP_ERR | WEP_HUP;
                uint32_t ev = wep_from_poll(r) & want & ~WEP_ONESHOT;
                if (ev == 0) continue;
                out[result].events = ev;
                out[result].data = (uint64_t)ep->regs[i].sock;
                result++;
                if (ep->regs[i].events & WEP_ONESHOT) {
                    ep->regs[i].armed = 0;
                    ep->regs[i].gen++;
                }
            }
        }
        LeaveCriticalSection(&ep->lock);

        if (rc == SOCKET_ERROR) {
            free(fds);
            free(snap);
            wep_throw("epoll_wait failed", err);
        }
        if (result > 0 || timeout == 0) break;
        if (timeout > 0 && GetTickCount64() >= deadline) break;
    }
    free(fds);
    free(snap);
    return result;
}

void __jnative_fn_sun_nio_ch_WEPoll_close__J_V(int64_t h)
{
    wep_t* ep = (wep_t*)(intptr_t)h;
    if (ep == NULL) return;
    closesocket(ep->wake);
    DeleteCriticalSection(&ep->lock);
    free(ep->regs);
    free(ep);
    WSACleanup();
}

#endif
