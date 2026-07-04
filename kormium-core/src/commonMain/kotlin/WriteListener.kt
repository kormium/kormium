package io.github.kormium

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Notified after a [transaction] / [autocommit] block (or their suspend counterparts)
 * commits, with the set of table names written during it. This is the generic,
 * non-reactive seam the rest of the system builds on: the `kormium-observe` module turns
 * it into a `Flow`, but it is equally useful for cache invalidation, audit or metrics.
 *
 * [onCommit] is called synchronously on the thread that ran the transaction, so keep it
 * cheap and non-blocking — fan out to a coroutine/`Flow` off the hot path if you need to.
 */
public fun interface WriteListener {
    /** [tables] is the non-empty set of table names written by the just-committed block. */
    public fun onCommit(tables: Set<String>)
}

/** Handle returned by [WriteListeners.add]; call [remove] to unregister the listener. */
public fun interface Registration {
    public fun remove()
}

/**
 * Per-database registry of [WriteListener]s. A backend that supports change notification
 * exposes its own instance through [io.github.kormium.database.Database.writeListeners] /
 * [io.github.kormium.database.SuspendDatabase.writeListeners]; backends that do not get
 * the shared [Disabled] registry, which ignores everything at zero cost (so observation
 * simply never fires there).
 *
 * The registry is copy-on-write so [fire] iterates a stable snapshot without locking on the
 * hot path. [add] and [Registration.remove] swap the list with a CAS loop, so concurrent
 * registration is lossless — `kormium-observe` registers/unregisters a listener per Flow
 * collection, which can happen from many coroutines at once.
 */
@OptIn(ExperimentalAtomicApi::class)
public open class WriteListeners {
    private val listeners = AtomicReference<List<WriteListener>>(emptyList())

    // The cross-process publish hook, set by `connectNotifications`. Kept SEPARATE from the
    // listener list (and fired only from the local commit path, never from `fire`) so that a
    // remote signal delivered via `fire` is NOT re-published — that would loop instances forever.
    private val commitPublish = AtomicReference<((Set<String>) -> Unit)?>(null)

    /** Registers [listener]; returns a [Registration] that removes it again. */
    public open fun add(listener: WriteListener): Registration {
        listeners.swap { it + listener }
        return Registration {
            listeners.swap { list -> list.filterNot { it === listener } }
        }
    }

    /** Delivers [tables] to every registered listener. No-op when [tables] is empty. */
    public fun fire(tables: Set<String>) {
        if (tables.isEmpty()) return
        val snapshot = listeners.load()
        for (l in snapshot) l.onCommit(tables)
    }

    /** True if at least one listener is registered (lets callers skip dirty-set bookkeeping). */
    public val isActive: Boolean get() = listeners.load().isNotEmpty()

    /** Installs (or clears, with `null`) the cross-process publish hook. Used by `connectNotifications`. */
    public open fun setCommitPublish(hook: ((Set<String>) -> Unit)?) {
        commitPublish.swap { hook }
    }

    /**
     * Publishes a LOCAL commit's [tables] to the connected transport, if any. Called only from the
     * commit path (after [fire]); the inbound/remote path uses [fire] alone, so remote signals are
     * never echoed back out.
     */
    public fun publishCommit(tables: Set<String>) {
        if (tables.isEmpty()) return
        commitPublish.load()?.invoke(tables)
    }

    /** The shared no-op registry for backends that don't support write notification. */
    public object Disabled : WriteListeners() {
        override fun add(listener: WriteListener): Registration = Registration {}
        override fun setCommitPublish(hook: ((Set<String>) -> Unit)?) {}
    }
}

// Lock-free read-modify-write: retry until no concurrent swap intervened. (The stdlib `update`
// extension is still experimental-in-flux across targets; this is the same three-line loop.)
@OptIn(ExperimentalAtomicApi::class)
private inline fun <T> AtomicReference<T>.swap(transform: (T) -> T) {
    while (true) {
        val current = load()
        if (compareAndSet(current, transform(current))) return
    }
}
