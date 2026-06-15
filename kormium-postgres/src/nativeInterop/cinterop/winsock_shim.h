#ifndef KORMIUM_WINSOCK_SHIM_H
#define KORMIUM_WINSOCK_SHIM_H

/*
 * Project-owned Winsock bridge for the Windows async reactor.
 *
 * Kotlin/Native's platform.windows does not expose WSAPoll/WSADATA/SOCKET, and a direct
 * cinterop on the system <winsock2.h> yields an empty binding package (see
 * WINDOWS_ASYNC_NOTES.md). cinterop *does* emit declarations from a project-owned header,
 * so this header wraps the handful of Winsock calls the reactor needs behind a small
 * `ksock_*` API. The wrappers are `static inline`, so cinterop compiles them into the
 * generated stub klib — no separate .c file or link step beyond -lws2_32.
 *
 * The whole Winsock surface (SOCKET handles, sockaddr_in, WSAPOLLFD, the self-addressed
 * wake datagram) stays in C; Kotlin only sees opaque 64-bit handles and a flat poll struct.
 */

/* WSAPoll requires Windows Vista+ (0x0600). Set before any Windows header is pulled in. */
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif

#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Opaque socket handle. SOCKET is UINT_PTR (64-bit on x64); carry it as a fixed-width int. */
typedef uint64_t ksock_t;

/* Sentinel for an unopened/failed socket (mirrors INVALID_SOCKET == (SOCKET)~0). */
#define KSOCK_INVALID ((ksock_t)~(uint64_t)0)

/* Interest/readiness bits, mapped to/from the WSAPoll POLL* flags inside ksock_poll. */
#define KSOCK_EV_READ  1
#define KSOCK_EV_WRITE 2
#define KSOCK_EV_ERR   4

/* One entry per socket for ksock_poll; parallel to WSAPOLLFD but with our own event bits. */
typedef struct {
    ksock_t fd;
    int16_t events;
    int16_t revents;
} ksock_pollfd;

/* Initialise Winsock 2.2. Returns 0 on success, the WSAStartup error code otherwise. */
static inline int ksock_startup(void) {
    WSADATA wsa;
    return WSAStartup(MAKEWORD(2, 2), &wsa);
}

static inline void ksock_cleanup(void) {
    WSACleanup();
}

static inline int ksock_is_valid(ksock_t s) {
    return s != KSOCK_INVALID;
}

/*
 * Create the wake channel: a loopback UDP socket bound to an OS-assigned port, then
 * connect()ed to its own address so a plain send() targets itself. Set non-blocking so
 * draining the wake byte(s) never stalls the reactor. Returns KSOCK_INVALID on failure.
 */
static inline ksock_t ksock_open_wake(void) {
    SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s == INVALID_SOCKET) return KSOCK_INVALID;

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0; /* ask the OS for an ephemeral port */

    if (bind(s, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        closesocket(s);
        return KSOCK_INVALID;
    }

    /* Read back the assigned port, then connect to ourselves so send()/recv() work. */
    int len = (int)sizeof(addr);
    if (getsockname(s, (struct sockaddr *)&addr, &len) != 0 ||
        connect(s, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        closesocket(s);
        return KSOCK_INVALID;
    }

    u_long nonblocking = 1;
    ioctlsocket(s, FIONBIO, &nonblocking);
    return (ksock_t)s;
}

static inline void ksock_signal_wake(ksock_t s) {
    char b = 1;
    send((SOCKET)s, &b, 1, 0);
}

static inline void ksock_drain_wake(ksock_t s) {
    char buf[64];
    while (recv((SOCKET)s, buf, (int)sizeof(buf), 0) > 0) { /* drain all queued wakes */ }
}

static inline void ksock_close(ksock_t s) {
    closesocket((SOCKET)s);
}

/*
 * WSAPoll over count entries; timeout_ms < 0 blocks indefinitely. Translates our event
 * bits to/from the Winsock POLL* flags. Returns the WSAPoll result (>0 ready, 0 timeout,
 * SOCKET_ERROR on error).
 */
static inline int ksock_poll(ksock_pollfd *fds, unsigned int count, int timeout_ms) {
    if (count == 0) return 0;
    WSAPOLLFD *wfds = (WSAPOLLFD *)malloc(sizeof(WSAPOLLFD) * count);
    if (wfds == NULL) return SOCKET_ERROR;

    for (unsigned int i = 0; i < count; i++) {
        wfds[i].fd = (SOCKET)fds[i].fd;
        SHORT ev = 0;
        if (fds[i].events & KSOCK_EV_READ) ev |= POLLRDNORM;
        if (fds[i].events & KSOCK_EV_WRITE) ev |= POLLWRNORM;
        wfds[i].events = ev;
        wfds[i].revents = 0;
    }

    int rc = WSAPoll(wfds, count, timeout_ms);

    for (unsigned int i = 0; i < count; i++) {
        SHORT re = wfds[i].revents;
        int16_t out = 0;
        if (re & (POLLRDNORM | POLLIN)) out |= KSOCK_EV_READ;
        if (re & (POLLWRNORM | POLLOUT)) out |= KSOCK_EV_WRITE;
        if (re & (POLLERR | POLLHUP | POLLNVAL)) out |= KSOCK_EV_ERR;
        fds[i].revents = out;
    }

    free(wfds);
    return rc;
}

#endif /* KORMIUM_WINSOCK_SHIM_H */
