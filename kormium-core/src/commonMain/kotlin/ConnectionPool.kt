package io.github.kormium

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One pinned connection borrowed from a [ConnectionPool]: the per-connection
 * [executor] plus the transaction primitives. The blocking [runPinned] and the
 * suspend [runConnection] drive these the same way; only how they're scheduled
 * differs.
 */
public interface PinnedConnection {
    public val executor: SqlExecutor

    /**
     * Opens a transaction. [isolation] (when non-null) and [readOnly] are applied here; how
     * each backend honors them — and any state it must restore on [release] — is backend-specific
     * (see [TransactionIsolation]).
     */
    public fun begin(isolation: TransactionIsolation? = null, readOnly: Boolean = false)
    public fun commit()
    public fun rollback()
    /** Returns the connection to the pool (and restores any per-borrow state). */
    public fun release()
}

/**
 * The pooling seam shared by a backend's blocking [Database] and suspend
 * [io.github.kormium.database.SuspendDatabase]: ONE pool serves both paths
 * (decision: no second suspend-only pool). A backend implements [acquire]; if its
 * pool can hand out a connection without blocking (e.g. a Channel-based native pool),
 * it overrides [acquireSuspending] for that free path — otherwise the default just
 * offloads the blocking [acquire] to the IO dispatcher.
 */
public interface ConnectionPool {
    /** Borrows a connection, blocking until one is free. */
    public fun acquire(): PinnedConnection

    /** Borrows a connection, suspending until one is free. */
    public suspend fun acquireSuspending(): PinnedConnection = withContext(ioDispatcher) { acquire() }
}

/**
 * Blocking pinned-connection run: borrow, BEGIN/COMMIT/ROLLBACK around [block] when
 * [transactional], always release. Backs [Database.usePinned].
 */
public fun <R> ConnectionPool.runPinned(
    transactional: Boolean,
    isolation: TransactionIsolation? = null,
    readOnly: Boolean = false,
    block: (SqlExecutor) -> R,
): R {
    val conn = acquire()
    try {
        if (!transactional) return block(conn.executor)
        conn.begin(isolation, readOnly)
        return try {
            block(conn.executor).also { conn.commit() }
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        }
    } finally {
        conn.release()
    }
}

/**
 * Suspend pinned-connection run (offload strategy B): borrow without blocking, run
 * the block via a [SuspendExecutorAdapter] that offloads each blocking driver call to
 * [ioDispatcher]; BEGIN/COMMIT/ROLLBACK and release are offloaded too. Operations run
 * sequentially, so the connection is never used concurrently. Cleanup runs under
 * [NonCancellable] so a cancelled block still rolls back and releases. Backs
 * [io.github.kormium.database.SuspendDatabase.useConnection] for blocking drivers;
 * a truly async backend (r2dbc) implements useConnection itself instead.
 */
public suspend fun <R> ConnectionPool.runConnection(
    transactional: Boolean,
    isolation: TransactionIsolation? = null,
    readOnly: Boolean = false,
    block: suspend (SuspendSqlExecutor) -> R,
): R {
    val conn = acquireSuspending()
    try {
        val exec = SuspendExecutorAdapter(conn.executor, ioDispatcher)
        if (!transactional) return block(exec)
        withContext(ioDispatcher) { conn.begin(isolation, readOnly) }
        return try {
            block(exec).also { withContext(ioDispatcher) { conn.commit() } }
        } catch (e: Throwable) {
            withContext(NonCancellable + ioDispatcher) { runCatching { conn.rollback() } }
            throw e
        }
    } finally {
        withContext(NonCancellable + ioDispatcher) { conn.release() }
    }
}
