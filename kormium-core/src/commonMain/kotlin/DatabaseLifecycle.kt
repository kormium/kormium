package io.github.kormium

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The shared open/closed state machine a backend composes to implement the lifecycle contract
 * (see [io.github.kormium.database.Database.close]) the same way everywhere:
 *
 *  - [close] runs [teardown] exactly once — calling it again is a safe no-op (idempotent);
 *  - [isClosed] reflects whether [close] has been called;
 *  - [checkOpen] throws [DatabaseClosedException] once closed, so a backend can guard
 *    `usePinned` / `useConnection` and report use-after-close as one uniform type.
 *
 * Thread-safe and free of locks: the state is a single atomic flag. A statement that is already
 * in flight when [close] is called is not interrupted here — backends drain their pool inside
 * [teardown] where they can wait for borrowed connections to come back.
 */
@OptIn(ExperimentalAtomicApi::class)
class DatabaseLifecycle(private val teardown: () -> Unit) {
    private val closed = AtomicReference(false)

    /** Whether [close] has been called. */
    val isClosed: Boolean get() = closed.load()

    /** Throws [DatabaseClosedException] if already closed; call before borrowing a connection. */
    fun checkOpen() {
        if (closed.load()) throw DatabaseClosedException()
    }

    /** Idempotent: runs [teardown] on the first call only; later calls return immediately. */
    fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) teardown()
    }
}
