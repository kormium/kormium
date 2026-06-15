package io.github.kormium.postgres.async

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/** Interest flags passed to [SocketReactorBase.pollWaiters]; mapped to OS poll constants per platform. */
internal const val EV_READ = 0
internal const val EV_WRITE = 1

/**
 * OS-agnostic core of the I/O reactor: lets a coroutine wait for a libpq socket to become
 * ready without holding its thread. One reactor thread polls every in-flight socket at once
 * (plus a wake channel), resuming each waiter when its socket fires. The platform-specific
 * syscalls — poll vs WSAPoll, a self-pipe vs a wake socket — are the abstract hooks below;
 * [createSocketReactor] returns the right subclass for the target.
 *
 * Registrations go on a lock-free Treiber stack ([intake]); the reactor drains it into a
 * thread-confined map each iteration, so the hot map needs no lock. The wake hook lets a new
 * registration interrupt a blocked poll; a wake racing the drain is not lost because the
 * platform wake signal persists until the reactor consumes it.
 */
@OptIn(DelicateCoroutinesApi::class)
internal abstract class SocketReactorBase : AutoCloseable {

    private class Waiter(
        val fd: Int,
        val events: Int,
        val cont: CancellableContinuation<Unit>,
        val next: Waiter?,
    )

    private val intake = AtomicReference<Waiter?>(null)

    @Volatile
    private var running = true

    private val dispatcher = newSingleThreadContext("kormium-pg-reactor")
    private val scope = CoroutineScope(dispatcher)

    /** Sets up the wake channel. Called once before the reactor loop starts. */
    protected abstract fun openWake()

    /** Signals the wake channel so a blocked [pollWaiters] returns promptly. Thread-safe. */
    protected abstract fun signalWake()

    /** Tears down the wake channel. */
    protected abstract fun closeWake()

    /**
     * Polls the wake channel plus the given waiter [fds] (interest per [events]); blocks until
     * at least one is ready or the wake fires, consumes the wake signal, and returns readiness
     * indexed parallel to [fds].
     */
    protected abstract fun pollWaiters(fds: IntArray, events: IntArray): BooleanArray

    fun start() {
        openWake()
        scope.launch { loop() }
    }

    suspend fun awaitReadable(fd: Int) = await(fd, EV_READ)

    suspend fun awaitWritable(fd: Int) = await(fd, EV_WRITE)

    private suspend fun await(fd: Int, events: Int) = suspendCancellableCoroutine { cont ->
        while (true) {
            val head = intake.value
            if (intake.compareAndSet(head, Waiter(fd, events, cont, head))) break
        }
        signalWake()
    }

    private fun loop() {
        val waiters = LinkedHashMap<Int, Waiter>()
        while (running) {
            var node = intake.getAndSet(null)
            while (node != null) {
                waiters[node.fd] = node
                node = node.next
            }
            val fds = waiters.keys.toIntArray()
            val events = IntArray(fds.size) { waiters.getValue(fds[it]).events }
            val ready = pollWaiters(fds, events)
            for (i in fds.indices) {
                if (ready[i]) {
                    val waiter = waiters.remove(fds[i])
                    if (waiter != null && waiter.cont.isActive) waiter.cont.resume(Unit)
                }
            }
        }
    }

    override fun close() {
        running = false
        signalWake() // unblock the poll so the loop observes running == false and exits
        scope.cancel()
        dispatcher.close()
        closeWake()
    }
}

/**
 * Builds the platform reactor (poll on Unix, WSAPoll on Windows), or null on platforms without
 * one — in which case the driver falls back to the blocking offload for its suspend path.
 */
internal expect fun createSocketReactor(): SocketReactorBase?
