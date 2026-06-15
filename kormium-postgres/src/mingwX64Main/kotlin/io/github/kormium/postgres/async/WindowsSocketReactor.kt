package io.github.kormium.postgres.async

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import winsock.KSOCK_EV_ERR
import winsock.KSOCK_EV_READ
import winsock.KSOCK_EV_WRITE
import winsock.KSOCK_INVALID
import winsock.ksock_cleanup
import winsock.ksock_close
import winsock.ksock_drain_wake
import winsock.ksock_open_wake
import winsock.ksock_poll
import winsock.ksock_pollfd
import winsock.ksock_signal_wake
import winsock.ksock_startup
import winsock.ksock_t
import kotlin.concurrent.Volatile

/**
 * Windows reactor: WSAPoll over the waiter sockets plus a loopback UDP socket used as the wake
 * channel (Winsock cannot poll a pipe, so signalling is a self-addressed datagram). The Winsock
 * surface lives behind the project-owned `winsock` cinterop shim (`ksock_*`); Kotlin/Native's
 * platform.windows does not expose WSAPoll and a direct system-header cinterop binds nothing.
 */
@OptIn(ExperimentalForeignApi::class)
internal class WindowsSocketReactor : SocketReactorBase() {

    @Volatile
    private var wakeSock: ksock_t = KSOCK_INVALID

    override fun openWake() {
        check(ksock_startup() == 0) { "WSAStartup failed" }
        wakeSock = ksock_open_wake()
        check(wakeSock != KSOCK_INVALID) { "wake socket setup failed" }
    }

    override fun signalWake() {
        ksock_signal_wake(wakeSock)
    }

    override fun pollWaiters(fds: IntArray, events: IntArray): BooleanArray = memScoped {
        val count = fds.size + 1
        val arr = allocArray<ksock_pollfd>(count)
        arr[0].fd = wakeSock
        arr[0].events = KSOCK_EV_READ.toShort()
        for (i in fds.indices) {
            arr[i + 1].fd = fds[i].convert()
            arr[i + 1].events = (if (events[i] == EV_WRITE) KSOCK_EV_WRITE else KSOCK_EV_READ).toShort()
        }
        ksock_poll(arr, count.convert(), -1)
        if (arr[0].revents.toInt() and KSOCK_EV_READ != 0) ksock_drain_wake(wakeSock)
        val readyMask = KSOCK_EV_READ or KSOCK_EV_WRITE or KSOCK_EV_ERR
        BooleanArray(fds.size) { (arr[it + 1].revents.toInt() and readyMask) != 0 }
    }

    override fun closeWake() {
        if (wakeSock != KSOCK_INVALID) ksock_close(wakeSock)
        ksock_cleanup()
    }
}

internal actual fun createSocketReactor(): SocketReactorBase? = WindowsSocketReactor().apply { start() }
